package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceWorkerTest {

    private static final String SERVER_ID = "https://cycles.example.com/v1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final EnvelopeSigner signer = new EnvelopeSigner();
    private final CyclesEvidenceCanonicalizer canonicalizer = new CyclesEvidenceCanonicalizer();
    private final EvidenceSigningKey key = new LocalEvidenceSigningKey(signer, "", ""); // ephemeral
    private final CyclesEvidenceEnvelopeBuilder builder = new CyclesEvidenceEnvelopeBuilder(canonicalizer, key);

    /** Captures the last envelope handed to the sink. */
    private static final class CapturingSink implements EvidenceSink {
        BuiltEvidenceEnvelope last;
        int count;
        @Override public void accept(BuiltEvidenceEnvelope envelope) {
            this.last = envelope;
            this.count++;
        }
    }

    private EvidenceWorker worker(EvidenceQueueConsumer consumer, EvidenceSink sink) {
        return worker(consumer, sink, SERVER_ID);
    }

    private EvidenceWorker worker(EvidenceQueueConsumer consumer, EvidenceSink sink, String serverId) {
        return new EvidenceWorker(consumer, builder, sink, mapper, 1, serverId);
    }

    @Test
    void buildsAndSinksAValidSignedEnvelopeFromASourceRecord() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        when(consumer.popPending(1)).thenReturn(sourceRecord("reserve", "ALLOW"));

        worker(consumer, sink).processNext();

        assertThat(sink.count).isEqualTo(1);
        ObjectNode env = sink.last.envelope();
        assertThat(env.get("artifact_type").asText()).isEqualTo("reserve");
        assertThat(env.get("server_id").asText()).isEqualTo(SERVER_ID);
        assertThat(env.get("signer_did").asText()).isEqualTo(key.signerDid());
        assertThat(env.path("payload").path("reserve").path("response").path("decision").asText())
                .isEqualTo("ALLOW");
        assertThat(env.path("payload").path("reserve").path("request").path("idempotency_key").asText())
                .isEqualTo("k1");

        // the emitted envelope is self-consistent and verifies
        assertThat(canonicalizer.computeEvidenceId(env)).isEqualTo(sink.last.evidenceId());
        byte[] signingBytes = canonicalizer.signingBytes(env, sink.last.evidenceId());
        assertThat(signer.verify(signingBytes, env.get("signature").asText(), key.signerDid())).isTrue();
    }

    @Test
    void doesNothingOnEmptyQueue() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        when(consumer.popPending(1)).thenReturn(null);

        worker(consumer, sink).processNext();

        assertThat(sink.count).isZero();
    }

    @Test
    void deadLettersMalformedRecordWithoutThrowingOrSinking() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        String bad = "{ not valid json";
        when(consumer.popPending(1)).thenReturn(bad);

        worker(consumer, sink).processNext(); // must not throw

        assertThat(sink.count).isZero();
        verify(consumer).deadLetter(bad);
    }

    @Test
    void deadLettersRecordWithMissingPayloadRatherThanSigningGarbage() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        String noPayload = "{\"artifact_type\":\"reserve\",\"issued_at_ms\":1}";
        when(consumer.popPending(1)).thenReturn(noPayload);

        worker(consumer, sink).processNext();

        assertThat(sink.count).isZero(); // must NOT sign an empty/garbage envelope
        verify(consumer).deadLetter(noPayload);
    }

    @Test
    void deadLettersWhenServerIdUnconfigured() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        String record = sourceRecord("reserve", "ALLOW");
        when(consumer.popPending(1)).thenReturn(record);

        worker(consumer, sink, "").processNext(); // blank server_id

        assertThat(sink.count).isZero(); // empty server_id is not a valid envelope
        verify(consumer).deadLetter(record);
    }

    private String sourceRecord(String artifactType, String decision) {
        ObjectNode rec = mapper.createObjectNode();
        rec.put("artifact_type", artifactType);
        rec.put("issued_at_ms", 1810000000100L);
        rec.put("trace_id", "0af7651916cd43dd8448eb211c80319c");
        ObjectNode payload = rec.putObject("payload");
        payload.putObject("request").put("idempotency_key", "k1");
        payload.putObject("response").put("decision", decision);
        try {
            return mapper.writeValueAsString(rec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
