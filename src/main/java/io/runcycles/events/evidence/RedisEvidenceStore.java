package io.runcycles.events.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Default {@link EvidenceStore}: persists envelopes in the shared Redis at
 * {@code <key-prefix><evidence_id>}. Active unless
 * {@code cycles.evidence.store.backend} selects another backend (e.g. an
 * {@code s3} implementation drops in via {@code havingValue="s3"}).
 *
 * <p>{@code ttl-seconds} defaults to 0 = no expiry (envelopes are an archival
 * record). Set a positive TTL only for non-archival deployments.
 */
@Component
@ConditionalOnProperty(name = "cycles.evidence.store.backend", havingValue = "redis", matchIfMissing = true)
public class RedisEvidenceStore implements EvidenceStore {

    private final JedisPool jedisPool;
    private final String keyPrefix;
    private final long ttlSeconds;

    public RedisEvidenceStore(
            JedisPool jedisPool,
            @Value("${cycles.evidence.store.key-prefix:evidence:envelope:}") String keyPrefix,
            @Value("${cycles.evidence.store.ttl-seconds:0}") long ttlSeconds) {
        this.jedisPool = jedisPool;
        this.keyPrefix = keyPrefix;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void put(String evidenceId, String envelopeJson) {
        String key = keyPrefix + evidenceId;
        try (Jedis jedis = jedisPool.getResource()) {
            if (ttlSeconds > 0) {
                jedis.setex(key, ttlSeconds, envelopeJson);
            } else {
                jedis.set(key, envelopeJson);
            }
        }
    }
}
