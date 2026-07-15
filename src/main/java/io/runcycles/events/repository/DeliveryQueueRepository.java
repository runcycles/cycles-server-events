package io.runcycles.events.repository;

import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.args.ListDirection;
import redis.clients.jedis.params.SetParams;

import java.util.List;

@Repository
public class DeliveryQueueRepository {

    static final String PENDING_KEY = "dispatch:pending";
    static final String PROCESSING_KEY = "dispatch:processing";
    static final String PROCESSING_CLAIMED_AT_KEY = "dispatch:processing:claimed_at";
    static final String RETRY_KEY = "dispatch:retry";
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

    private static final String ACK_PROCESSING_LUA =
            "redis.call('LREM', KEYS[1], 1, ARGV[1])\n" +
            "redis.call('ZREM', KEYS[2], ARGV[1])\n" +
            "return 1\n";

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
              if not claimed_at then
                redis.call('ZADD', KEYS[2], now, id)
              elseif tonumber(claimed_at) <= cutoff then
                if redis.call('LREM', KEYS[1], 1, id) > 0 then
                  redis.call('ZREM', KEYS[2], id)
                  redis.call('LPUSH', KEYS[3], id)
                  moved = moved + 1
                end
              end
            end
            return moved
            """;

    private final JedisPool jedisPool;

    public DeliveryQueueRepository(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
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
     * Atomically claim one delivery by moving it from pending to processing.
     * The delivery stays in processing until {@link #ack(String)} succeeds.
     */
    public String claimPending(int timeoutSeconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            String deliveryId = jedis.blmove(PENDING_KEY, PROCESSING_KEY,
                    ListDirection.RIGHT, ListDirection.LEFT, timeoutSeconds);
            if (deliveryId != null) {
                jedis.zadd(PROCESSING_CLAIMED_AT_KEY, System.currentTimeMillis(), deliveryId);
            }
            return deliveryId;
        }
    }

    /** Acknowledge a processed delivery by removing it from processing. */
    public void ack(String deliveryId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.eval(ACK_PROCESSING_LUA, List.of(PROCESSING_KEY, PROCESSING_CLAIMED_AT_KEY), List.of(deliveryId));
        }
    }

    /**
     * Move stale in-flight deliveries back to pending after a crash.
     *
     * <p>Recovery is age-gated so a newly-started replica does not requeue work
     * another live replica just claimed from the shared processing list. Entries
     * without a claim timestamp are first marked with {@code nowMillis} and get
     * a full idle window before recovery; this closes the BLMOVE-to-ZADD race
     * without duplicating another worker's active delivery.
     */
    public long recoverStaleProcessing(long nowMillis, long idleMillis) {
        long cutoff = nowMillis - Math.max(0, idleMillis);
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(RECOVER_STALE_BATCH_LUA,
                    List.of(PROCESSING_KEY, PROCESSING_CLAIMED_AT_KEY, PENDING_KEY),
                    List.of(Integer.toString(RECOVERY_SCAN_LIMIT), Long.toString(nowMillis), Long.toString(cutoff)));
            return result instanceof Long count ? count : 0L;
        }
    }

    /** Add delivery to retry queue with score = next_retry_at millis. */
    public void scheduleRetry(String deliveryId, long nextRetryAtMillis) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zadd(RETRY_KEY, nextRetryAtMillis, deliveryId);
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
}
