package io.runcycles.events.service;

import io.runcycles.events.repository.DeliveryQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class DispatchLoopTest {

    @Mock private DeliveryQueueRepository queueRepository;
    @Mock private DeliveryHandler deliveryHandler;

    private DispatchLoop dispatchLoop;

    @BeforeEach
    void setUp() {
        dispatchLoop = new DispatchLoop(queueRepository, deliveryHandler, 5, 120_000L, 30, 500);
        lenient().when(queueRepository.tryAcquireOrderingLock(anyString(), eq(120_000L))).thenReturn(true);
    }

    @Test
    void processNext_claimsHandlesAndAcks() {
        when(queueRepository.claimPending(5)).thenReturn("del-1");

        dispatchLoop.processNext();

        verify(deliveryHandler).handle("del-1");
        verify(queueRepository).ack("del-1");
        verify(queueRepository).releaseOrderingLock(anyString());
    }

    @Test
    void processNext_timeout_noOp() {
        when(queueRepository.claimPending(5)).thenReturn(null);

        dispatchLoop.processNext();

        verify(deliveryHandler, never()).handle(anyString());
        verify(queueRepository, never()).ack(anyString());
    }

    @Test
    void processNext_handlerException_caughtAndNotAcked() {
        when(queueRepository.claimPending(5)).thenReturn("del-1");
        doThrow(new RuntimeException("handler error")).when(deliveryHandler).handle("del-1");

        // Should not throw
        dispatchLoop.processNext();

        verify(queueRepository, never()).ack(anyString());
    }

    @Test
    void processNext_claimException_caught() {
        when(queueRepository.claimPending(5)).thenThrow(new RuntimeException("redis error"));

        // Should not throw
        dispatchLoop.processNext();

        verify(deliveryHandler, never()).handle(anyString());
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
        assertThatThrownBy(() -> new DispatchLoop(queueRepository, deliveryHandler, 0, 120_000L, 30, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatchLoop(queueRepository, deliveryHandler, 5, 120_000L, 0, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatchLoop(queueRepository, deliveryHandler, 5, 35_000L, 30, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatchLoop(queueRepository, deliveryHandler, 5, 120_000L, 30, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
