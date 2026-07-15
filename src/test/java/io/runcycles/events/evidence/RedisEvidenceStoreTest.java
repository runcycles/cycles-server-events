package io.runcycles.events.evidence;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        verify(jedis).set(org.mockito.ArgumentMatchers.eq("evidence:envelope:abc123"),
                org.mockito.ArgumentMatchers.eq("{}"),
                org.mockito.ArgumentMatchers.any(redis.clients.jedis.params.SetParams.class));
    }

    @Test
    void rejectsInvalidConfigurationAndInput() {
        assertThatThrownBy(() -> new RedisEvidenceStore(pool, " ", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisEvidenceStore(pool, "evidence:", -1))
                .isInstanceOf(IllegalArgumentException.class);

        EvidenceStore store = new RedisEvidenceStore(pool, "evidence:", 0);
        assertThatThrownBy(() -> store.put("", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("abc", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
