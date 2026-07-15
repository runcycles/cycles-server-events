package io.runcycles.events.service;

import io.runcycles.events.metrics.CyclesMetrics;
import io.runcycles.events.repository.DeliveryQueueRepository;
import io.runcycles.events.repository.DeliveryQueueRepository.ClaimedDelivery;
import io.runcycles.events.repository.DeliveryRepository.CorruptDeliveryRecordException;
import io.runcycles.events.service.DeliveryHandler.RecoverableDeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class DispatchLoopTest {

    @Mock private DeliveryQueueRepository queueRepository;
    @Mock private DeliveryHandler deliveryHandler;
    @Mock private CyclesMetrics metrics;

    private DispatchLoop dispatchLoop;
    private final AtomicLong nanoTime = new AtomicLong(1_000L);

    @BeforeEach
    void setUp() {
        dispatchLoop = new DispatchLoop(queueRepository, deliveryHandler, metrics,
                5, 120_000L, 30, 500,
                nanoTime::get);
        lenient().when(queueRepository.tryAcquireOrderingLock(anyString(), eq(120_000L))).thenReturn(true);
    }

    @Test
    void processNext_claimsHandlesAndAcks() {
        ClaimedDelivery claim = new ClaimedDelivery("del-1", "claim-1");
        when(queueRepository.claimPending(5)).thenReturn(claim);
        when(queueRepository.ack(claim)).thenReturn(true);

        dispatchLoop.processNext();

        verify(deliveryHandler).handle(claim);
        verify(queueRepository).ack(claim);
        verify(queueRepository).releaseOrderingLock(anyString());
    }

    @Test
    void supersededClaimIsNotTreatedAsSuccessfullyAcknowledged() {
        ClaimedDelivery claim = new ClaimedDelivery("del-stale", "claim-stale");
        when(queueRepository.claimPending(5)).thenReturn(claim);
        when(queueRepository.ack(claim)).thenReturn(false);

        dispatchLoop.processNext();

        verify(deliveryHandler).handle(claim);
        verify(queueRepository).ack(claim);
        verify(queueRepository).releaseOrderingLock(anyString());
    }

    @Test
    void processNext_timeout_noOp() {
        when(queueRepository.claimPending(5)).thenReturn(null);

        dispatchLoop.processNext();

        verify(deliveryHandler, never()).handle(any(ClaimedDelivery.class));
        verify(queueRepository, never()).ack(any());
    }

    @Test
    void processNext_handlerException_caughtAndNotAcked() {
        ClaimedDelivery claim = new ClaimedDelivery("del-1", "claim-1");
        when(queueRepository.claimPending(5)).thenReturn(claim);
        doThrow(new RuntimeException("handler error")).when(deliveryHandler).handle(claim);

        // Should not throw
        dispatchLoop.processNext();

        verify(queueRepository, never()).ack(any());
    }

    @Test
    void recoverableWaitStateIsRetainedWithoutAckOrQuarantine() {
        ClaimedDelivery claim = new ClaimedDelivery("del-wait", "claim-wait");
        when(queueRepository.claimPending(5)).thenReturn(claim);
        doThrow(new RecoverableDeliveryException("missing_signing_secret", "not ready"))
                .when(deliveryHandler).handle(claim);

        dispatchLoop.processNext();

        verify(queueRepository, never()).ack(any());
        verify(queueRepository, never()).deadLetterCorruptOwned(any(), anyLong());
        verifyNoInteractions(metrics);
    }

    @Test
    void corruptRecordIsOwnerFencedAndQuarantinedOnce() {
        ClaimedDelivery claim = new ClaimedDelivery("del-corrupt", "claim-corrupt");
        when(queueRepository.claimPending(5)).thenReturn(claim);
        doThrow(new CorruptDeliveryRecordException(
                "del-corrupt", "stored delivery JSON is invalid", null))
                .when(deliveryHandler).handle(claim);
        when(queueRepository.deadLetterCorruptOwned(eq(claim), anyLong())).thenReturn(true);

        dispatchLoop.processNext();

        verify(queueRepository).deadLetterCorruptOwned(eq(claim), anyLong());
        verify(queueRepository, never()).ack(any());
        verify(metrics).recordDeliveryDeadLettered("corrupt_record");
    }

    @Test
    void supersededCorruptRecordIsNotCountedAsQuarantined() {
        ClaimedDelivery claim = new ClaimedDelivery("del-corrupt", "claim-stale");
        when(queueRepository.claimPending(5)).thenReturn(claim);
        doThrow(new CorruptDeliveryRecordException(
                "del-corrupt", "stored delivery status is invalid", null))
                .when(deliveryHandler).handle(claim);
        when(queueRepository.deadLetterCorruptOwned(eq(claim), anyLong())).thenReturn(false);

        dispatchLoop.processNext();

        verify(metrics, never()).recordDeliveryDeadLettered(anyString());
    }

    @Test
    void processNext_claimException_caught() {
        when(queueRepository.claimPending(5)).thenThrow(new RuntimeException("redis error"));

        // Should not throw
        dispatchLoop.processNext();

        verify(deliveryHandler, never()).handle(any(ClaimedDelivery.class));
    }

    @Test
    void processNext_standbyReplicaDoesNotClaimWithoutOrderingLock() {
        when(queueRepository.tryAcquireOrderingLock(anyString(), eq(120_000L))).thenReturn(false);

        dispatchLoop.processNext();

        verify(queueRepository, never()).claimPending(anyInt());
        verify(queueRepository, never()).releaseOrderingLock(anyString());
    }

    @Test
    void standbyReplicaBacksOffInsteadOfHammeringRedisEverySchedulerTick() {
        when(queueRepository.tryAcquireOrderingLock(anyString(), eq(120_000L))).thenReturn(false);

        dispatchLoop.processNext();
        dispatchLoop.processNext();

        verify(queueRepository, times(1)).tryAcquireOrderingLock(anyString(), eq(120_000L));
    }

    @Test
    void negativeMonotonicClockValueDoesNotLookLikeActiveBackoff() {
        nanoTime.set(-1_000L);
        when(queueRepository.tryAcquireOrderingLock(anyString(), eq(120_000L))).thenReturn(false);

        dispatchLoop.processNext();

        verify(queueRepository).tryAcquireOrderingLock(anyString(), eq(120_000L));
    }

    @Test
    void processNextUsesANewOwnerTokenAndReleasesTheMatchingLeaseEachRun() {
        when(queueRepository.claimPending(5)).thenReturn(null);

        dispatchLoop.processNext();
        dispatchLoop.processNext();

        ArgumentCaptor<String> acquired = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> released = ArgumentCaptor.forClass(String.class);
        verify(queueRepository, times(2)).tryAcquireOrderingLock(acquired.capture(), eq(120_000L));
        verify(queueRepository, times(2)).releaseOrderingLock(released.capture());
        assertThat(acquired.getAllValues()).containsExactlyElementsOf(released.getAllValues());
        assertThat(acquired.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void constructorRejectsInvalidQueueTiming() {
        assertThatThrownBy(() -> new DispatchLoop(queueRepository, deliveryHandler, metrics,
                0, 120_000L, 30, 500, nanoTime::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatchLoop(queueRepository, deliveryHandler, metrics,
                5, 120_000L, 0, 500, nanoTime::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatchLoop(queueRepository, deliveryHandler, metrics,
                5, 35_000L, 30, 500, nanoTime::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatchLoop(queueRepository, deliveryHandler, metrics,
                5, 120_000L, 30, 0, nanoTime::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatchLoop(queueRepository, deliveryHandler, null,
                5, 120_000L, 30, 500, nanoTime::get))
                .isInstanceOf(NullPointerException.class);
    }
}
