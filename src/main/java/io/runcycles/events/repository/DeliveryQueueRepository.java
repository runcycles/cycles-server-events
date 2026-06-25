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
    static final String RETRY_KEY = "dispatch:retry";

    private static final String REQUEUE_RETRY_LUA =
            "if redis.call('ZREM', KEYS[1], ARGV[1]) > 0 then\n" +
            "  redis.call('LPUSH', KEYS[2], ARGV[1])\n" +
            "  return 1\n" +
            "end\n" +
            "return 0\n";

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
            return jedis.blmove(PENDING_KEY, PROCESSING_KEY,
                    ListDirection.RIGHT, ListDirection.LEFT, timeoutSeconds);
        }
    }

    /** Acknowledge a processed delivery by removing it from processing. */
    public void ack(String deliveryId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lrem(PROCESSING_KEY, 1, deliveryId);
        }
    }

    /** Move orphaned in-flight deliveries back to pending after a crash. */
    public long recoverProcessing() {
        long moved = 0;
        try (Jedis jedis = jedisPool.getResource()) {
            while (jedis.lmove(PROCESSING_KEY, PENDING_KEY, ListDirection.LEFT, ListDirection.RIGHT) != null) {
                moved++;
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
