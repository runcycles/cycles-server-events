package io.runcycles.events.service;

import io.runcycles.events.repository.DeliveryQueueRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatchRecoveryTest {

    private final DeliveryQueueRepository queueRepository = mock(DeliveryQueueRepository.class);
    private final DispatchRecovery recovery = new DispatchRecovery(queueRepository, 120_000L);

    @Test
    void recoverOnStartup_recoversProcessingQueue() {
        when(queueRepository.recoverStaleProcessing(anyLong(), eq(120_000L))).thenReturn(2L);

        recovery.recoverOnStartup();

        verify(queueRepository).recoverStaleProcessing(anyLong(), eq(120_000L));
    }

    @Test
    void recoverOnStartup_swallowsRecoveryFailure() {
        when(queueRepository.recoverStaleProcessing(anyLong(), eq(120_000L)))
                .thenThrow(new RuntimeException("redis down"));

        recovery.recoverOnStartup();

        verify(queueRepository).recoverStaleProcessing(anyLong(), eq(120_000L));
    }

    @Test
    void periodicRecoveryRechecksYoungStartupOrphans() {
        when(queueRepository.recoverStaleProcessing(anyLong(), eq(120_000L))).thenReturn(1L);

        recovery.recoverPeriodically();

        verify(queueRepository).recoverStaleProcessing(anyLong(), eq(120_000L));
    }

    @Test
    void rejectsInvalidRecoveryIdleTime() {
        assertThatThrownBy(() -> new DispatchRecovery(queueRepository, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
