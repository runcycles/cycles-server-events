package io.runcycles.events.evidence;

import io.runcycles.events.evidence.EvidenceQueueConsumer.ClaimedEvidence;
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
        when(jedis.eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(1L);
        String record = "{\"artifact_type\":\"reserve\"}";
        ClaimedEvidence claim = consumer().claim(5);
        assertThat(claim.recordJson()).isEqualTo(record);
        assertThat(claim.claimToken()).isNotBlank();
        verify(jedis).eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(List.of("evidence:processing",
                        "evidence:processing:claimed_at", "evidence:processing:claim_owner",
                        "evidence:processing:payload")),
                org.mockito.ArgumentMatchers.argThat(args -> args.size() == 3
                        && record.equals(args.get(0))
                        && claim.claimToken().equals(args.get(1))));
    }

    @Test
    void claimReturnsNullOnTimeout() {
        when(jedis.blmove("evidence:pending", "evidence:processing",
                ListDirection.RIGHT, ListDirection.LEFT, 5.0)).thenReturn(null);
        assertThat(consumer().claim(5)).isNull();
    }

    @Test
    void claimReturnsNullWhenRecoveryWinsBeforeClaimIdentityIsInstalled() {
        when(jedis.blmove("evidence:pending", "evidence:processing",
                ListDirection.RIGHT, ListDirection.LEFT, 5.0)).thenReturn("record");
        when(jedis.eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(0L);
        assertThat(consumer().claim(5)).isNull();
    }

    @Test
    void ackRemovesRecordFromProcessing() {
        ClaimedEvidence claim = new ClaimedEvidence("rec", "token");
        when(jedis.eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(1L);
        assertThat(consumer().ack(claim)).isTrue();
        verify(jedis).eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(List.of("evidence:processing",
                        "evidence:processing:claimed_at", "evidence:processing:claim_owner",
                        "evidence:processing:payload")),
                org.mockito.ArgumentMatchers.eq(List.of("token")));
    }

    @Test
    void recoverMovesOnlyStaleInFlightRecordsBackToPending() {
        when(jedis.eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(List.of("evidence:processing", "evidence:processing:claimed_at",
                        "evidence:pending", "evidence:processing:claim_owner", "evidence:processing:payload")),
                org.mockito.ArgumentMatchers.eq(List.of("100", "10000", "5000")))).thenReturn(1L);

        assertThat(consumer().recoverStale(10_000L, 5_000L, 100)).isEqualTo(1L);
    }

    @Test
    void recoveryMarksUntrackedClaimBeforeConsideringItStale() {
        when(jedis.eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(List.of("evidence:processing", "evidence:processing:claimed_at",
                        "evidence:pending", "evidence:processing:claim_owner", "evidence:processing:payload")),
                org.mockito.ArgumentMatchers.eq(List.of("100", "10000", "-110000")))).thenReturn(0L);

        assertThat(consumer().recoverStale(10_000L, 120_000L, 100)).isZero();
    }

    @Test
    void deadLetterAndAckIsOneAtomicRedisTransition() {
        when(jedis.eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(List.of(
                        "evidence:failed", "evidence:processing", "evidence:processing:claimed_at",
                        "evidence:processing:claim_owner", "evidence:processing:payload")),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(1L);

        ClaimedEvidence claim = new ClaimedEvidence("rec", "token");
        assertThat(consumer().deadLetterAndAck(claim)).isTrue();

        verify(jedis).eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(List.of(
                        "evidence:failed", "evidence:processing", "evidence:processing:claimed_at",
                        "evidence:processing:claim_owner", "evidence:processing:payload")),
                org.mockito.ArgumentMatchers.eq(List.of("token", "rec", "10000")));
    }

    @Test
    void deadLetterAndAckDoesNotReportMoveWhenRecordIsNoLongerInFlight() {
        when(jedis.eval(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(0L);

        assertThat(consumer().deadLetterAndAck(new ClaimedEvidence("rec", "token"))).isFalse();
    }

    @Test
    void rejectsUnboundedDlqBlankKeysAndInvalidRecoveryLimit() {
        assertThatThrownBy(() -> new ClaimedEvidence(null, "token"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaimedEvidence("record", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaimedEvidence("record", " "))
                .isInstanceOf(IllegalArgumentException.class);
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
                        "evidence:processing", "evidence:processing:claimed_at", "evidence:pending",
                        "evidence:processing:claim_owner", "evidence:processing:payload")),
                org.mockito.ArgumentMatchers.eq(List.of("1", "10000", "10000"))))
                .thenReturn("unexpected");

        assertThat(consumer().recoverStale(10_000L, -1L, 1)).isZero();
    }
}
