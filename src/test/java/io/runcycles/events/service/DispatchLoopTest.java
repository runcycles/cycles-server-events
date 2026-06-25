package io.runcycles.events.service;

import io.runcycles.events.repository.DeliveryQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DispatchLoopTest {

    @Mock private DeliveryQueueRepository queueRepository;
    @Mock private DeliveryHandler deliveryHandler;

    private DispatchLoop dispatchLoop;

    @BeforeEach
    void setUp() {
        dispatchLoop = new DispatchLoop(queueRepository, deliveryHandler, 5);
    }

    @Test
    void processNext_claimsHandlesAndAcks() {
        when(queueRepository.claimPending(5)).thenReturn("del-1");

        dispatchLoop.processNext();

        verify(deliveryHandler).handle("del-1");
        verify(queueRepository).ack("del-1");
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
}
