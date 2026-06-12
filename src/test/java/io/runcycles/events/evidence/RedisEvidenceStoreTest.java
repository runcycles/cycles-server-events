package io.runcycles.events.evidence;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RedisEvidenceStoreTest {

    private final JedisPool pool = mock(JedisPool.class);
    private final Jedis jedis = mock(Jedis.class);

    @Test
    void persistsContentAddressedWithNoExpiryWhenTtlZero() {
        when(pool.getResource()).thenReturn(jedis);
        EvidenceStore store = new RedisEvidenceStore(pool, "evidence:envelope:", 0);

        store.put("abc123", "{\"evidence_id\":\"abc123\"}");

        verify(jedis).set("evidence:envelope:abc123", "{\"evidence_id\":\"abc123\"}");
    }

    @Test
    void persistsWithTtlWhenConfigured() {
        when(pool.getResource()).thenReturn(jedis);
        EvidenceStore store = new RedisEvidenceStore(pool, "evidence:envelope:", 86400);

        store.put("abc123", "{}");

        verify(jedis).setex("evidence:envelope:abc123", 86400L, "{}");
    }
}
