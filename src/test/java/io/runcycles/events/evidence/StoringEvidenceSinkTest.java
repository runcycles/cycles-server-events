package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StoringEvidenceSinkTest {

    @Test
    void persistsEnvelopeByEvidenceId() throws Exception {
        EvidenceStore store = mock(EvidenceStore.class);
        StoringEvidenceSink sink = new StoringEvidenceSink(store);

        ObjectNode node = (ObjectNode) new ObjectMapper().readTree("{\"evidence_id\":\"abc123\"}");
        BuiltEvidenceEnvelope env = new BuiltEvidenceEnvelope("abc123", node, "{\"evidence_id\":\"abc123\"}");

        sink.accept(env);

        verify(store).put("abc123", "{\"evidence_id\":\"abc123\"}");
    }
}
