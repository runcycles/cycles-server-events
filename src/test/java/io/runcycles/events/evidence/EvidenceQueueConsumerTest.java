package io.runcycles.events.evidence;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceQueueConsumerTest {

    private final JedisPool pool = mock(JedisPool.class);
    private final Jedis jedis = mock(Jedis.class);

    private EvidenceQueueConsumer consumer() {
        when(pool.getResource()).thenReturn(jedis);
        return new EvidenceQueueConsumer(pool, "evidence:pending", "evidence:failed");
    }

    @Test
    void returnsRecordValueOnPop() {
        when(jedis.brpop(5, "evidence:pending")).thenReturn(List.of("evidence:pending", "{\"artifact_type\":\"reserve\"}"));
        assertThat(consumer().popPending(5)).isEqualTo("{\"artifact_type\":\"reserve\"}");
    }

    @Test
    void returnsNullOnTimeout() {
        when(jedis.brpop(5, "evidence:pending")).thenReturn(null);
        assertThat(consumer().popPending(5)).isNull();
    }

    @Test
    void deadLettersToFailedQueue() {
        String record = "{\"artifact_type\":\"reserve\"}";
        consumer().deadLetter(record);
        verify(jedis).lpush("evidence:failed", record);
    }
}
