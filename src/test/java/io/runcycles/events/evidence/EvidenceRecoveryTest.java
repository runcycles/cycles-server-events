package io.runcycles.events.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceRecoveryTest {

    @Test
    void recoversOrphansOnStartup() {
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        when(consumer.recoverStale(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(120_000L), org.mockito.ArgumentMatchers.eq(100))).thenReturn(3L);

        new EvidenceRecovery(consumer, 120_000L, 100).recoverOnStartup();

        verify(consumer).recoverStale(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(120_000L), org.mockito.ArgumentMatchers.eq(100));
    }

    @Test
    void startupRecoveryFailureDoesNotThrow() {
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        when(consumer.recoverStale(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(120_000L), org.mockito.ArgumentMatchers.eq(100)))
                .thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> new EvidenceRecovery(consumer, 120_000L, 100).recoverOnStartup())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidRecoveryConfiguration() {
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);

        assertThatThrownBy(() -> new EvidenceRecovery(consumer, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceRecovery(consumer, 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
