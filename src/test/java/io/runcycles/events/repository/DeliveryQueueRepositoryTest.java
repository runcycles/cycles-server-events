package io.runcycles.events.repository;

import io.runcycles.events.repository.DeliveryQueueRepository.ClaimedDelivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.args.ListDirection;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryQueueRepositoryTest {

    @Mock
    private JedisPool jedisPool;
    @Mock
    private Jedis jedis;

    private DeliveryQueueRepository repository;

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        repository = new DeliveryQueueRepository(jedisPool);
    }

    @Test
    void claimPending_movesDeliveryToProcessingAndReturnsDeliveryId() {
        when(jedis.blmove("dispatch:pending", "dispatch:processing",
                ListDirection.RIGHT, ListDirection.LEFT, 5.0)).thenReturn("del-1");
        when(jedis.eval(anyString(), eq(List.of("dispatch:processing",
                "dispatch:processing:claimed_at", "dispatch:processing:claim_owner")), anyList()))
                .thenReturn(1L);

        ClaimedDelivery result = repository.claimPending(5);

        assertThat(result.deliveryId()).isEqualTo("del-1");
        assertThat(result.claimToken()).isNotBlank();
    }

    @Test
    void orderingLockUsesOwnedLeaseAndCompareDeleteRelease() {
        when(jedis.set(eq("dispatch:ordering:lock"), eq("owner-1"),
                any(redis.clients.jedis.params.SetParams.class))).thenReturn("OK");

        assertThat(repository.tryAcquireOrderingLock("owner-1", 60_000)).isTrue();
        repository.releaseOrderingLock("owner-1");

        verify(jedis).set(eq("dispatch:ordering:lock"), eq("owner-1"),
                any(redis.clients.jedis.params.SetParams.class));
        verify(jedis).eval(anyString(), eq(List.of("dispatch:ordering:lock")), eq(List.of("owner-1")));
    }

    @Test
    void orderingLockRejectsInvalidOwnerAndLease() {
        assertThatThrownBy(() -> repository.tryAcquireOrderingLock(null, 60_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.tryAcquireOrderingLock("", 60_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.tryAcquireOrderingLock("owner-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jedisPool, never()).getResource();
    }

    @Test
    void claimPending_timeout_returnsNull() {
        when(jedis.blmove("dispatch:pending", "dispatch:processing",
                ListDirection.RIGHT, ListDirection.LEFT, 5.0)).thenReturn(null);

        ClaimedDelivery result = repository.claimPending(5);

        assertThat(result).isNull();
    }

    @Test
    void ack_removesDeliveryFromProcessing() {
        ClaimedDelivery claim = new ClaimedDelivery("del-1", "claim-1");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        assertThat(repository.ack(claim)).isTrue();

        verify(jedis).eval(anyString(),
                eq(List.of("dispatch:processing", "dispatch:processing:claimed_at",
                        "dispatch:processing:claim_owner")),
                eq(List.of("del-1", "claim-1")));
    }

    @Test
    void ackRejectsInvalidClaimAndReportsSupersededOwner() {
        assertThatThrownBy(() -> repository.ack(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ClaimedDelivery(null, "token"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaimedDelivery("del-1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.ack(new ClaimedDelivery("", "token")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.ack(new ClaimedDelivery("del-1", "")))
                .isInstanceOf(IllegalArgumentException.class);

        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        assertThat(repository.ack(new ClaimedDelivery("del-1", "stale-token"))).isFalse();
    }

    @Test
    void claimReturnsNullWhenRecoveryWinsBeforeOwnershipIsRecorded() {
        when(jedis.blmove("dispatch:pending", "dispatch:processing",
                ListDirection.RIGHT, ListDirection.LEFT, 5.0)).thenReturn("del-1");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);

        assertThat(repository.claimPending(5)).isNull();
    }

    @Test
    void claimReturnsNullWhenLateClaimantWouldOverwriteSuccessorOwner() {
        when(jedis.blmove("dispatch:pending", "dispatch:processing",
                ListDirection.RIGHT, ListDirection.LEFT, 5.0)).thenReturn("del-1");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-1L);

        assertThat(repository.claimPending(5)).isNull();
    }

    @Test
    void corruptDeliveryIsQuarantinedOnlyByCurrentOwner() {
        ClaimedDelivery claim = new ClaimedDelivery("del-1", "claim-1");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L, 0L);

        assertThat(repository.deadLetterCorruptOwned(claim, 1234L)).isTrue();
        assertThat(repository.deadLetterCorruptOwned(claim, 1234L)).isFalse();

        verify(jedis, times(2)).eval(anyString(),
                eq(List.of("dispatch:failed", "dispatch:processing",
                        "dispatch:processing:claimed_at", "dispatch:processing:claim_owner",
                        "delivery:del-1")),
                eq(List.of("del-1", "claim-1", "1234", "10000")));
    }

    @Test
    void corruptDeliveryQuarantineValidatesInputsAndBound() {
        ClaimedDelivery claim = new ClaimedDelivery("del-1", "claim-1");
        assertThatThrownBy(() -> repository.deadLetterCorruptOwned(null, 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.deadLetterCorruptOwned(claim, -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryQueueRepository(jedisPool, 0))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jedisPool, never()).getResource();
    }

    @Test
    void recoverStaleProcessing_marksUntrackedEntriesWithoutRequeueing() {
        when(jedis.eval(anyString(),
                eq(List.of("dispatch:processing", "dispatch:processing:claimed_at", "dispatch:pending",
                        "dispatch:processing:claim_owner")),
                eq(List.of("1000", "10000", "-110000")))).thenReturn(0L);

        long recovered = repository.recoverStaleProcessing(10_000L, 120_000L);

        assertThat(recovered).isZero();
    }

    @Test
    void recoverStaleProcessing_requeuesOnlyIdleEntries() {
        when(jedis.eval(anyString(),
                eq(List.of("dispatch:processing", "dispatch:processing:claimed_at", "dispatch:pending",
                        "dispatch:processing:claim_owner")),
                eq(List.of("1000", "10000", "5000")))).thenReturn(1L);

        long recovered = repository.recoverStaleProcessing(10_000L, 5_000L);

        assertThat(recovered).isEqualTo(1L);
    }

    @Test
    void recoverStaleProcessingClampsNegativeIdleAndHandlesUnexpectedLuaResult() {
        when(jedis.eval(anyString(),
                eq(List.of("dispatch:processing", "dispatch:processing:claimed_at", "dispatch:pending",
                        "dispatch:processing:claim_owner")),
                eq(List.of("1000", "10000", "10000")))).thenReturn("unexpected");

        assertThat(repository.recoverStaleProcessing(10_000L, -1L)).isZero();
    }

    @Test
    void scheduleRetryRequiresCurrentClaimOwner() {
        ClaimedDelivery claim = new ClaimedDelivery("del-1", "token");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        assertThat(repository.scheduleRetryOwned(claim, 1700000000000L)).isTrue();

        verify(jedis).eval(anyString(),
                eq(List.of("dispatch:processing:claim_owner", "dispatch:retry")),
                eq(List.of("del-1", "token", "1700000000000")));
        assertThatThrownBy(() -> repository.scheduleRetryOwned(null, 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.scheduleRetryOwned(claim, -1L))
                .isInstanceOf(IllegalArgumentException.class);

        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        assertThat(repository.scheduleRetryOwned(claim, 1L)).isFalse();
    }

    @Test
    void popRetryReady_withReadyItems() {
        when(jedis.eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")),
                eq(List.of("1700000000000", "100"))))
                .thenReturn(Arrays.asList("del-1", "del-2"));

        List<String> result = repository.popRetryReady(1700000000000L, 100);

        assertThat(result).containsExactly("del-1", "del-2");
        verify(jedis).eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")),
                eq(List.of("1700000000000", "100")));
    }

    @Test
    void popRetryReady_decodesBinaryLuaResults() {
        when(jedis.eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), anyList()))
                .thenReturn(Arrays.asList("del-1".getBytes(java.nio.charset.StandardCharsets.UTF_8), "del-2"));

        List<String> result = repository.popRetryReady(1700000000000L, 100);

        assertThat(result).containsExactly("del-1", "del-2");
    }

    @Test
    void popRetryReady_noReadyItems() {
        when(jedis.eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), anyList()))
                .thenReturn(Collections.emptyList());

        List<String> result = repository.popRetryReady(1700000000000L, 100);

        assertThat(result).isEmpty();
        verify(jedis).eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), anyList());
    }

    @Test
    void popRetryReadySkipsRedisForNonPositiveLimitAndRejectsUnexpectedLuaShape() {
        assertThat(repository.popRetryReady(1L, 0)).isEmpty();
        verify(jedis, never()).eval(anyString(), anyList(), anyList());

        when(jedis.eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), anyList()))
                .thenReturn("unexpected");
        assertThat(repository.popRetryReady(1L, 1)).isEmpty();
    }
}
