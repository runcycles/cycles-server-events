package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.events.metrics.CyclesMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EvidenceConfigurationConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(EvidenceQueueConsumer.class, () -> mock(EvidenceQueueConsumer.class))
            .withBean(EvidenceSink.class, () -> envelope -> { })
            .withBean(CyclesMetrics.class, () -> new CyclesMetrics(new SimpleMeterRegistry(), false))
            .withUserConfiguration(
                    EnvelopeSigner.class,
                    CyclesEvidenceCanonicalizer.class,
                    LocalEvidenceSigningKey.class,
                    CyclesEvidenceEnvelopeBuilder.class,
                    CyclesEvidenceSchemaValidator.class,
                    EvidenceWorker.class);

    @Test
    void evidenceSignerBeansAreAbsentWhenServerIdIsBlank() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LocalEvidenceSigningKey.class);
            assertThat(context).doesNotHaveBean(CyclesEvidenceEnvelopeBuilder.class);
            assertThat(context).doesNotHaveBean(CyclesEvidenceSchemaValidator.class);
            assertThat(context).doesNotHaveBean(EvidenceWorker.class);
        });
    }

    @Test
    void evidenceSignerBeansFailWhenServerIdIsSetWithoutSigningKey() {
        contextRunner
                .withPropertyValues("cycles.evidence.server-id=https://cycles.example.com/v1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(rootCause(context.getStartupFailure()))
                            .hasMessageContaining("evidence signing key is required");
                });
    }

    @Test
    void evidenceSignerBeansArePresentWhenServerIdIsSetAndEphemeralDevModeAllowed() {
        contextRunner
                .withPropertyValues(
                        "cycles.evidence.server-id=https://cycles.example.com/v1",
                        "cycles.evidence.signing.allow-ephemeral=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LocalEvidenceSigningKey.class);
                    assertThat(context).hasSingleBean(CyclesEvidenceEnvelopeBuilder.class);
                    assertThat(context).hasSingleBean(CyclesEvidenceSchemaValidator.class);
                    assertThat(context).hasSingleBean(EvidenceWorker.class);
                });
    }

    @Test
    void evidenceWorkerFailsStartupForRelativeServerIdentity() {
        contextRunner
                .withPropertyValues(
                        "cycles.evidence.server-id=relative/server",
                        "cycles.evidence.signing.allow-ephemeral=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("absolute URI");
                });
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
