package io.runcycles.events.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

/**
 * Blocking consumer of the dedicated CyclesEvidence source queue
 * ({@code evidence:pending}) that cycles-server feeds. Mirrors the webhook
 * {@code DeliveryQueueRepository} BRPOP pattern.
 */
@Repository
public class EvidenceQueueConsumer {

    private final JedisPool jedisPool;
    private final String pendingKey;

    public EvidenceQueueConsumer(
            JedisPool jedisPool,
            @Value("${cycles.evidence.queue.pending-key:evidence:pending}") String pendingKey) {
        this.jedisPool = jedisPool;
        this.pendingKey = pendingKey;
    }

    /** Blocking pop of one evidence-source record JSON, or {@code null} on timeout. */
    public String popPending(int timeoutSeconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> result = jedis.brpop(timeoutSeconds, pendingKey);
            return result != null && result.size() == 2 ? result.get(1) : null;
        }
    }
}
