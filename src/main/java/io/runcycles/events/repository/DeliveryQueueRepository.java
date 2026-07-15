package io.runcycles.events.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.args.ListDirection;
import redis.clients.jedis.params.SetParams;

import java.util.List;
import java.util.UUID;

@Repository
public class DeliveryQueueRepository {

    static final String PENDING_KEY = "dispatch:pending";
    static final String PROCESSING_KEY = "dispatch:processing";
    static final String PROCESSING_CLAIMED_AT_KEY = "dispatch:processing:claimed_at";
    static final String PROCESSING_CLAIM_OWNER_KEY = "dispatch:processing:claim_owner";
    static final String RETRY_KEY = "dispatch:retry";
    public static final String FAILED_KEY = "dispatch:failed";
    static final String ORDERING_LOCK_KEY = "dispatch:ordering:lock";
    private static final int RECOVERY_SCAN_LIMIT = 1_000;

    private static final String REQUEUE_RETRY_BATCH_LUA = """
            local ready = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1],
                                     'LIMIT', 0, ARGV[2])
            local moved = {}
            for _, id in ipairs(ready) do
              if redis.call('ZREM', KEYS[1], id) > 0 then
                redis.call('LPUSH', KEYS[2], id)
                table.insert(moved, id)
              end
            end
            return moved
            """;

    private static final String RECORD_PROCESSING_CLAIM_LUA = """
            if not redis.call('LPOS', KEYS[1], ARGV[1]) then return 0 end
            local owner = redis.call('HGET', KEYS[3], ARGV[1])
            if owner and owner ~= '__orphan__' then return -1 end
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
            redis.call('HSET', KEYS[3], ARGV[1], ARGV[3])
            return 1
            """;

    private static final String ACK_PROCESSING_LUA = """
            if redis.call('HGET', KEYS[3], ARGV[1]) ~= ARGV[2] then return 0 end
            local removed = redis.call('LREM', KEYS[1], 1, ARGV[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            return removed
            """;

    private static final String DEAD_LETTER_CORRUPT_OWNED_LUA = """
            if redis.call('HGET', KEYS[4], ARGV[1]) ~= ARGV[2] then return 0 end
            if redis.call('LREM', KEYS[2], 1, ARGV[1]) == 0 then
              redis.call('ZREM', KEYS[3], ARGV[1])
              redis.call('HDEL', KEYS[4], ARGV[1])
              return 0
            end
            local payload = redis.call('GET', KEYS[5])
            local dead_letter = cjson.encode({
              delivery_id=ARGV[1],
              reason='corrupt_record',
              quarantined_at_ms=tonumber(ARGV[3]),
              payload=payload or ''
            })
            redis.call('LPUSH', KEYS[1], dead_letter)
            redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[4]) - 1)
            redis.call('ZREM', KEYS[3], ARGV[1])
            redis.call('HDEL', KEYS[4], ARGV[1])
            return 1
            """;

    private static final String SCHEDULE_RETRY_OWNED_LUA = """
            if redis.call('HGET', KEYS[1], ARGV[1]) ~= ARGV[2] then return 0 end
            redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
            return 1
            """;

    private static final String RELEASE_ORDERING_LOCK_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then\n" +
            "  return redis.call('DEL', KEYS[1])\n" +
            "end\n" +
            "return 0\n";

    private static final String RECOVER_STALE_BATCH_LUA = """
            local scan_count = math.min(tonumber(ARGV[1]), redis.call('LLEN', KEYS[1]))
            local now = tonumber(ARGV[2])
            local cutoff = tonumber(ARGV[3])
            local moved = 0
            for i = 1, scan_count do
              local id = redis.call('LMOVE', KEYS[1], KEYS[1], 'RIGHT', 'LEFT')
              if not id then break end
              local claimed_at = redis.call('ZSCORE', KEYS[2], id)
              local owner = redis.call('HGET', KEYS[4], id)
              if not claimed_at or not owner then
                redis.call('ZADD', KEYS[2], now, id)
                if not owner then redis.call('HSET', KEYS[4], id, '__orphan__') end
              elseif tonumber(claimed_at) <= cutoff then
                if redis.call('LREM', KEYS[1], 1, id) > 0 then
                  redis.call('ZREM', KEYS[2], id)
                  redis.call('HDEL', KEYS[4], id)
                  redis.call('LPUSH', KEYS[3], id)
                  moved = moved + 1
                end
              end
            end
            return moved
            """;

    private final JedisPool jedisPool;
    private final int failedMaxLen;

    @Autowired
    public DeliveryQueueRepository(
            JedisPool jedisPool,
            @Value("${dispatch.failed.max-len:10000}") int failedMaxLen) {
        if (failedMaxLen <= 0) {
            throw new IllegalArgumentException("delivery failed queue bound must be positive");
        }
        this.jedisPool = jedisPool;
        this.failedMaxLen = failedMaxLen;
    }

    DeliveryQueueRepository(JedisPool jedisPool) {
        this(jedisPool, 10_000);
    }

    /**
     * Acquire the cross-replica dispatch critical section before claiming.
     * Claiming while holding this lease prevents concurrent claim/send sections
     * under horizontal scaling; the TTL prevents a crashed owner from blocking
     * dispatch permanently. Retry backoff still permits later events to finish
     * before an earlier event's retry.
     */
    public boolean tryAcquireOrderingLock(String owner, long leaseMillis) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("ordering lock owner must not be blank");
        }
        if (leaseMillis <= 0) {
            throw new IllegalArgumentException("ordering lock lease must be positive");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String result = jedis.set(ORDERING_LOCK_KEY, owner,
                    SetParams.setParams().nx().px(leaseMillis));
            return "OK".equals(result);
        }
    }

    /** Release only the caller's lock; never delete a successor's lease. */
    public void releaseOrderingLock(String owner) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.eval(RELEASE_ORDERING_LOCK_LUA, List.of(ORDERING_LOCK_KEY), List.of(owner));
        }
    }

    /**
     * Atomically move one delivery from pending to processing, then attach a
     * unique claim generation before returning it. Recovery can move the list
     * entry between those operations, so the claim-recording Lua verifies the
     * entry is still in processing and has no non-orphan successor owner. A
     * failed verification means the delivery is already recoverable or owned
     * elsewhere and this invocation owns no work.
     */
    public ClaimedDelivery claimPending(int timeoutSeconds) {
        String claimToken = UUID.randomUUID().toString();
        try (Jedis jedis = jedisPool.getResource()) {
            String deliveryId = jedis.blmove(PENDING_KEY, PROCESSING_KEY,
                    ListDirection.RIGHT, ListDirection.LEFT, timeoutSeconds);
            if (deliveryId == null) {
                return null;
            }
            Object recorded = jedis.eval(RECORD_PROCESSING_CLAIM_LUA,
                    List.of(PROCESSING_KEY, PROCESSING_CLAIMED_AT_KEY, PROCESSING_CLAIM_OWNER_KEY),
                    List.of(deliveryId, Long.toString(System.currentTimeMillis()), claimToken));
            return Long.valueOf(1L).equals(recorded)
                    ? new ClaimedDelivery(deliveryId, claimToken)
                    : null;
        }
    }

    /**
     * Acknowledge only the caller's claim generation. A worker resuming after
     * stale recovery cannot remove a successor's processing marker.
     */
    public boolean ack(ClaimedDelivery claim) {
        java.util.Objects.requireNonNull(claim, "delivery claim is required");
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(ACK_PROCESSING_LUA,
                    List.of(PROCESSING_KEY, PROCESSING_CLAIMED_AT_KEY, PROCESSING_CLAIM_OWNER_KEY),
                    List.of(claim.deliveryId(), claim.claimToken()));
            return Long.valueOf(1L).equals(result);
        }
    }

    /**
     * Atomically quarantine a corrupt stored delivery and resolve only the
     * caller's processing generation. The original delivery key is retained
     * for operator inspection and repair.
     */
    public boolean deadLetterCorruptOwned(ClaimedDelivery claim, long nowMillis) {
        requireClaim(claim);
        if (nowMillis < 0) {
            throw new IllegalArgumentException("quarantine time must be non-negative");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(DEAD_LETTER_CORRUPT_OWNED_LUA,
                    List.of(FAILED_KEY, PROCESSING_KEY, PROCESSING_CLAIMED_AT_KEY,
                            PROCESSING_CLAIM_OWNER_KEY, "delivery:" + claim.deliveryId()),
                    List.of(claim.deliveryId(), claim.claimToken(), Long.toString(nowMillis),
                            Integer.toString(failedMaxLen)));
            return Long.valueOf(1L).equals(result);
        }
    }

    /**
     * Move stale in-flight deliveries back to pending after a crash.
     *
     * <p>Recovery is age-gated so a newly-started replica does not requeue work
     * another live replica just claimed from the shared processing list. Entries
     * without a timestamp or owner token are first completed with recovery
     * metadata at {@code nowMillis} and get a full idle window; this closes the
     * BLMOVE-to-claim-record race without duplicating active work.
     */
    public long recoverStaleProcessing(long nowMillis, long idleMillis) {
        long cutoff = nowMillis - Math.max(0, idleMillis);
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(RECOVER_STALE_BATCH_LUA,
                    List.of(PROCESSING_KEY, PROCESSING_CLAIMED_AT_KEY, PENDING_KEY,
                            PROCESSING_CLAIM_OWNER_KEY),
                    List.of(Integer.toString(RECOVERY_SCAN_LIMIT), Long.toString(nowMillis), Long.toString(cutoff)));
            return result instanceof Long count ? count : 0L;
        }
    }

    /** Restore a retry schedule only while the caller owns the processing claim. */
    public boolean scheduleRetryOwned(ClaimedDelivery claim, long nextRetryAtMillis) {
        requireClaim(claim);
        if (nextRetryAtMillis < 0) {
            throw new IllegalArgumentException("next retry time must be non-negative");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(SCHEDULE_RETRY_OWNED_LUA,
                    List.of(PROCESSING_CLAIM_OWNER_KEY, RETRY_KEY),
                    List.of(claim.deliveryId(), claim.claimToken(),
                            Long.toString(nextRetryAtMillis)));
            return Long.valueOf(1L).equals(result);
        }
    }

    /** Pop deliveries ready for retry (score <= now). Returns up to limit IDs. */
    public List<String> popRetryReady(long nowMillis, int limit) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (limit <= 0) {
                return List.of();
            }
            Object result = jedis.eval(REQUEUE_RETRY_BATCH_LUA,
                    List.of(RETRY_KEY, PENDING_KEY),
                    List.of(Long.toString(nowMillis), Integer.toString(limit)));
            if (!(result instanceof List<?> values)) {
                return List.of();
            }
            return values.stream()
                    .map(value -> value instanceof byte[] bytes
                            ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                            : String.valueOf(value))
                    .toList();
        }
    }

    public record ClaimedDelivery(String deliveryId, String claimToken) {
        public ClaimedDelivery {
            if (deliveryId == null || deliveryId.isBlank()
                    || claimToken == null || claimToken.isBlank()) {
                throw new IllegalArgumentException("delivery claim id and token are required");
            }
        }
    }

    private static void requireClaim(ClaimedDelivery claim) {
        java.util.Objects.requireNonNull(claim, "delivery claim is required");
    }
}
