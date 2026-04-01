package io.runcycles.events.repository;

import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.List;

@Repository
public class DeliveryQueueRepository {

    private final JedisPool jedisPool;

    public DeliveryQueueRepository(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    /** Blocking pop from dispatch:pending. Returns deliveryId or null on timeout. */
    public String popPending(int timeoutSeconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> result = jedis.brpop(timeoutSeconds, "dispatch:pending");
            return result != null && result.size() == 2 ? result.get(1) : null;
        }
    }

    /** Add delivery to retry queue with score = next_retry_at millis. */
    public void scheduleRetry(String deliveryId, long nextRetryAtMillis) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zadd("dispatch:retry", nextRetryAtMillis, deliveryId);
        }
    }

    /** Pop deliveries ready for retry (score <= now). Returns up to limit IDs. */
    public List<String> popRetryReady(long nowMillis, int limit) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> ready = new ArrayList<>(
                    jedis.zrangeByScore("dispatch:retry", "-inf", String.valueOf(nowMillis), 0, limit));
            List<String> requeued = new ArrayList<>();
            for (String id : ready) {
                // Only lpush if we successfully removed — prevents duplicate deliveries
                // when multiple instances race on the same retry entries
                if (jedis.zrem("dispatch:retry", id) > 0) {
                    jedis.lpush("dispatch:pending", id);
                    requeued.add(id);
                }
            }
            return requeued;
        }
    }
}
