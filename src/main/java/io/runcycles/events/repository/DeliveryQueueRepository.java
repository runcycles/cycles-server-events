package io.runcycles.events.repository;

import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.args.ListDirection;

import java.util.ArrayList;
import java.util.List;

@Repository
public class DeliveryQueueRepository {

    static final String PENDING_KEY = "dispatch:pending";
    static final String PROCESSING_KEY = "dispatch:processing";
    static final String PROCESSING_CLAIMED_AT_KEY = "dispatch:processing:claimed_at";
    static final String RETRY_KEY = "dispatch:retry";

    private static final String REQUEUE_RETRY_LUA =
            "if redis.call('ZREM', KEYS[1], ARGV[1]) > 0 then\n" +
            "  redis.call('LPUSH', KEYS[2], ARGV[1])\n" +
            "  return 1\n" +
            "end\n" +
            "return 0\n";

    private static final String REQUEUE_STALE_PROCESSING_LUA =
            "if redis.call('LREM', KEYS[1], 1, ARGV[1]) > 0 then\n" +
            "  redis.call('ZREM', KEYS[2], ARGV[1])\n" +
            "  redis.call('LPUSH', KEYS[3], ARGV[1])\n" +
            "  return 1\n" +
            "end\n" +
            "return 0\n";

    private static final String ACK_PROCESSING_LUA =
            "redis.call('LREM', KEYS[1], 1, ARGV[1])\n" +
            "redis.call('ZREM', KEYS[2], ARGV[1])\n" +
            "return 1\n";

    private final JedisPool jedisPool;

    public DeliveryQueueRepository(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
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
        long moved = 0;
        long cutoff = nowMillis - Math.max(0, idleMillis);
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> processing = jedis.lrange(PROCESSING_KEY, 0, -1);
            for (String deliveryId : processing) {
                Double claimedAt = jedis.zscore(PROCESSING_CLAIMED_AT_KEY, deliveryId);
                if (claimedAt == null) {
                    jedis.zadd(PROCESSING_CLAIMED_AT_KEY, nowMillis, deliveryId);
                    continue;
                }
                if (claimedAt <= cutoff) {
                    Object result = jedis.eval(REQUEUE_STALE_PROCESSING_LUA,
                            List.of(PROCESSING_KEY, PROCESSING_CLAIMED_AT_KEY, PENDING_KEY),
                            List.of(deliveryId));
                    if (Long.valueOf(1L).equals(result)) {
                        moved++;
                    }
                }
            }
        }
        return moved;
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
            List<String> ready = new ArrayList<>(
                    jedis.zrangeByScore(RETRY_KEY, "-inf", String.valueOf(nowMillis), 0, limit));
            List<String> requeued = new ArrayList<>();
            for (String id : ready) {
                Object moved = jedis.eval(REQUEUE_RETRY_LUA, List.of(RETRY_KEY, PENDING_KEY), List.of(id));
                if (Long.valueOf(1L).equals(moved)) {
                    requeued.add(id);
                }
            }
            return requeued;
        }
    }
}
