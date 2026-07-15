package io.runcycles.events.evidence;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.args.ListDirection;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        String record = "{\"artifact_type\":\"reserve\"}";
        assertThat(consumer().claim(5)).isEqualTo(record);
        verify(jedis).zadd(org.mockito.ArgumentMatchers.eq("evidence:processing:claimed_at"),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.eq(record));
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
        verify(jedis).eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(List.of("evidence:processing", "evidence:processing:claimed_at")),
                org.mockito.ArgumentMatchers.eq(List.of("rec")));
    }

    @Test
    void recoverMovesOnlyStaleInFlightRecordsBackToPending() {
        when(jedis.eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(List.of("evidence:processing", "evidence:processing:claimed_at", "evidence:pending")),
                org.mockito.ArgumentMatchers.eq(List.of("100", "10000", "5000")))).thenReturn(1L);

        assertThat(consumer().recoverStale(10_000L, 5_000L, 100)).isEqualTo(1L);
    }

    @Test
    void recoveryMarksUntrackedClaimBeforeConsideringItStale() {
        when(jedis.eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(List.of("evidence:processing", "evidence:processing:claimed_at", "evidence:pending")),
                org.mockito.ArgumentMatchers.eq(List.of("100", "10000", "-110000")))).thenReturn(0L);

        assertThat(consumer().recoverStale(10_000L, 120_000L, 100)).isZero();
    }

    @Test
    void deadLetterAndAckIsOneAtomicRedisTransition() {
        when(jedis.eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(List.of(
                        "evidence:failed", "evidence:processing", "evidence:processing:claimed_at")),
                org.mockito.ArgumentMatchers.eq(List.of("rec", "10000")))).thenReturn(1L);

        assertThat(consumer().deadLetterAndAck("rec")).isTrue();

        verify(jedis).eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(List.of(
                        "evidence:failed", "evidence:processing", "evidence:processing:claimed_at")),
                org.mockito.ArgumentMatchers.eq(List.of("rec", "10000")));
    }

    @Test
    void deadLetterAndAckDoesNotReportMoveWhenRecordIsNoLongerInFlight() {
        when(jedis.eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(0L);

        assertThat(consumer().deadLetterAndAck("rec")).isFalse();
    }

    @Test
    void rejectsUnboundedDlqBlankKeysAndInvalidRecoveryLimit() {
        assertThatThrownBy(() -> new EvidenceQueueConsumer(pool, null, "processing", "failed", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceQueueConsumer(pool, "", "processing", "failed", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceQueueConsumer(pool, "pending", null, "failed", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceQueueConsumer(pool, "pending", "", "failed", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceQueueConsumer(pool, "pending", "processing", null, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceQueueConsumer(pool, "pending", "processing", "", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceQueueConsumer(pool, "pending", "processing", "failed", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer().recoverStale(1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recoverClampsNegativeIdleAndTreatsUnexpectedLuaResultAsNoMove() {
        when(jedis.eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(List.of(
                        "evidence:processing", "evidence:processing:claimed_at", "evidence:pending")),
                org.mockito.ArgumentMatchers.eq(List.of("1", "10000", "10000"))))
                .thenReturn("unexpected");

        assertThat(consumer().recoverStale(10_000L, -1L, 1)).isZero();
    }
}
