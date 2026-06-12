package io.runcycles.events.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceRecoveryTest {

    @Test
    void recoversOrphansOnStartup() {
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        when(consumer.recover()).thenReturn(3L);

        new EvidenceRecovery(consumer).recoverOnStartup();

        verify(consumer).recover();
    }

    @Test
    void startupRecoveryFailureDoesNotThrow() {
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        when(consumer.recover()).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> new EvidenceRecovery(consumer).recoverOnStartup()).doesNotThrowAnyException();
    }
}
