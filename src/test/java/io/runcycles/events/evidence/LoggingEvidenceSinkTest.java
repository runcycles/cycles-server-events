package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class LoggingEvidenceSinkTest {

    @Test
    void acceptsWithoutThrowing() throws Exception {
        var env = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree("{\"a\":1}");
        BuiltEvidenceEnvelope built = new BuiltEvidenceEnvelope("abc123", env, "{\"a\":1}");
        assertThatCode(() -> new LoggingEvidenceSink().accept(built)).doesNotThrowAnyException();
    }
}
