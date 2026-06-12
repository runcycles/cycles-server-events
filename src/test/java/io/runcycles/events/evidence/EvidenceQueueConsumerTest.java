package io.runcycles.events.evidence;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.args.ListDirection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceQueueConsumerTest {

    private final JedisPool pool = mock(JedisPool.class);
    private final Jedis jedis = mock(Jedis.class);

    private EvidenceQueueConsumer consumer() {
        when(pool.getResource()).thenReturn(jedis);
        return new EvidenceQueueConsumer(pool, "evidence:pending", "evidence:processing", "evidence:failed", 10000);
    }

    @Test
    void claimBlockMovesPendingToProcessingAndReturnsRecord() {
        when(jedis.blmove("evidence:pending", "evidence:processing",
                ListDirection.RIGHT, ListDirection.LEFT, 5.0))
                .thenReturn("{\"artifact_type\":\"reserve\"}");
        assertThat(consumer().claim(5)).isEqualTo("{\"artifact_type\":\"reserve\"}");
    }

    @Test
    void claimReturnsNullOnTimeout() {
        when(jedis.blmove("evidence:pending", "evidence:processing",
                ListDirection.RIGHT, ListDirection.LEFT, 5.0)).thenReturn(null);
        assertThat(consumer().claim(5)).isNull();
    }

    @Test
    void ackRemovesRecordFromProcessing() {
        consumer().ack("rec");
        verify(jedis).lrem("evidence:processing", 1L, "rec");
    }

    @Test
    void recoverMovesAllInFlightBackToPending() {
        when(jedis.lmove("evidence:processing", "evidence:pending",
                ListDirection.LEFT, ListDirection.RIGHT)).thenReturn("r1", "r2", null);
        assertThat(consumer().recover()).isEqualTo(2L);
    }

    @Test
    void deadLettersToFailedQueueAndBoundsIt() {
        String record = "{\"artifact_type\":\"reserve\"}";
        consumer().deadLetter(record);
        verify(jedis).lpush("evidence:failed", record);
        verify(jedis).ltrim("evidence:failed", 0, 9999); // bounded to failed-max-len
    }
}
