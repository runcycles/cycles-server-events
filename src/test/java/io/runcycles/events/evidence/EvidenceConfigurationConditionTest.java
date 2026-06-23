package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EvidenceConfigurationConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(EvidenceQueueConsumer.class, () -> mock(EvidenceQueueConsumer.class))
            .withBean(EvidenceSink.class, () -> envelope -> { })
            .withUserConfiguration(
                    EnvelopeSigner.class,
                    CyclesEvidenceCanonicalizer.class,
                    LocalEvidenceSigningKey.class,
                    CyclesEvidenceEnvelopeBuilder.class,
                    EvidenceWorker.class);

    @Test
    void evidenceSignerBeansAreAbsentWhenServerIdIsBlank() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(LocalEvidenceSigningKey.class);
            assertThat(context).doesNotHaveBean(CyclesEvidenceEnvelopeBuilder.class);
            assertThat(context).doesNotHaveBean(EvidenceWorker.class);
        });
    }

    @Test
    void evidenceSignerBeansArePresentWhenServerIdIsSet() {
        contextRunner
                .withPropertyValues("cycles.evidence.server-id=https://cycles.example.com/v1")
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalEvidenceSigningKey.class);
                    assertThat(context).hasSingleBean(CyclesEvidenceEnvelopeBuilder.class);
                    assertThat(context).hasSingleBean(EvidenceWorker.class);
                });
    }
}
