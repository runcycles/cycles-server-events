package io.runcycles.events.service;

import io.runcycles.events.repository.DeliveryQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrySchedulerTest {

    @Mock private DeliveryQueueRepository queueRepository;

    private RetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new RetryScheduler(queueRepository);
    }

    @Test
    void processRetries_requeuedItems() {
        when(queueRepository.popRetryReady(anyLong(), anyInt()))
                .thenReturn(Arrays.asList("del-1", "del-2"));

        scheduler.processRetries();

        verify(queueRepository).popRetryReady(anyLong(), anyInt());
    }

    @Test
    void processRetries_noItems() {
        when(queueRepository.popRetryReady(anyLong(), anyInt()))
                .thenReturn(Collections.emptyList());

        scheduler.processRetries();

        verify(queueRepository).popRetryReady(anyLong(), anyInt());
    }

    @Test
    void processRetries_exception_caught() {
        when(queueRepository.popRetryReady(anyLong(), anyInt()))
                .thenThrow(new RuntimeException("redis error"));

        // Should not throw
        scheduler.processRetries();
    }
}
