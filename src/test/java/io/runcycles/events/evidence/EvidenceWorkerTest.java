package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceWorkerTest {

    private static final String SERVER_ID = "https://cycles.example.com/v1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final EnvelopeSigner signer = new EnvelopeSigner();
    private final CyclesEvidenceCanonicalizer canonicalizer = new CyclesEvidenceCanonicalizer();
    private final EvidenceSigningKey key = new LocalEvidenceSigningKey(signer, "", "", true); // ephemeral dev key
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
        String record = sourceRecord("reserve", "ALLOW");
        when(consumer.claim(1)).thenReturn(record);

        worker(consumer, sink).processNext();

        assertThat(sink.count).isEqualTo(1);
        verify(consumer).ack(record); // stored → acked off the processing list
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
        when(consumer.claim(1)).thenReturn(null);

        worker(consumer, sink).processNext();

        assertThat(sink.count).isZero();
    }

    @Test
    void deadLettersMalformedRecordWithoutThrowingOrSinking() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        String bad = "{ not valid json";
        when(consumer.claim(1)).thenReturn(bad);

        worker(consumer, sink).processNext(); // must not throw

        assertThat(sink.count).isZero();
        verify(consumer).deadLetter(bad);
        verify(consumer).ack(bad); // dead-lettered → cleared from processing
    }

    @Test
    void deadLettersRecordWithMissingPayloadRatherThanSigningGarbage() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        String noPayload = "{\"artifact_type\":\"reserve\",\"issued_at_ms\":1}";
        when(consumer.claim(1)).thenReturn(noPayload);

        worker(consumer, sink).processNext();

        assertThat(sink.count).isZero(); // must NOT sign an empty/garbage envelope
        verify(consumer).deadLetter(noPayload);
        verify(consumer).ack(noPayload);
    }

    @Test
    void deadLettersWhenDirectWorkerHasBlankServerId() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        String record = sourceRecord("reserve", "ALLOW");
        when(consumer.claim(1)).thenReturn(record);

        worker(consumer, sink, "").processNext(); // blank server_id

        assertThat(sink.count).isZero(); // must NOT sign an envelope with blank server_id
        verify(consumer).deadLetter(record);
        verify(consumer).ack(record);
    }

    @Test
    void storesWhenProducerStampedEvidenceIdMatches() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        // compute the id this worker's builder will produce, then stamp it on the record
        String expectedId = builtEvidenceIdFor("reserve", "ALLOW");
        String record = sourceRecord("reserve", "ALLOW", expectedId);
        when(consumer.claim(1)).thenReturn(record);

        worker(consumer, sink).processNext();

        assertThat(sink.count).isEqualTo(1);
        assertThat(sink.last.evidenceId()).isEqualTo(expectedId);
        verify(consumer).ack(record);
        verify(consumer, never()).deadLetter(anyString());
    }

    @Test
    void deadLettersWhenProducerStampedEvidenceIdMismatches() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        // a wrong id (e.g. producer/worker server-id or signer-did config drift)
        String record = sourceRecord("reserve", "ALLOW", "f".repeat(64));
        when(consumer.claim(1)).thenReturn(record);

        worker(consumer, sink).processNext();

        assertThat(sink.count).isZero(); // must NOT store an unbindable envelope
        verify(consumer).deadLetter(record);
        verify(consumer).ack(record);
    }

    @Test
    void storedEnvelopeIsNotDeadLetteredWhenAckFails() {
        // Finding 1: after a successful store, an ack failure must leave the record
        // in processing for recovery — NOT dead-letter an already-stored envelope.
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        String record = sourceRecord("reserve", "ALLOW");
        when(consumer.claim(1)).thenReturn(record);
        doThrow(new RuntimeException("redis blip on ack")).when(consumer).ack(record);

        worker(consumer, sink).processNext(); // must not throw

        assertThat(sink.count).isEqualTo(1); // it WAS stored
        verify(consumer, never()).deadLetter(anyString()); // and must NOT be dead-lettered
    }

    @Test
    void enumLookupIsLocaleIndependent() {
        // Finding 2: artifact_type must upper-case under Locale.ROOT — under a locale
        // with special casing (Turkish dotless-i), "decide" -> "DECİDE" would
        // break the enum lookup and wrongly dead-letter a valid record.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            CapturingSink sink = new CapturingSink();
            EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
            String record = sourceRecord("decide", "ALLOW");
            when(consumer.claim(1)).thenReturn(record);

            worker(consumer, sink).processNext();

            assertThat(sink.count).isEqualTo(1); // built, not dead-lettered
            verify(consumer).ack(record);
            verify(consumer, never()).deadLetter(anyString());
        } finally {
            Locale.setDefault(previous);
        }
    }

    /** The evidence_id this test's builder produces for a given record's facts. */
    private String builtEvidenceIdFor(String artifactType, String decision) {
        try {
            JsonNode payloadBody = mapper.readTree(sourceRecord(artifactType, decision)).get("payload");
            EvidenceArtifactType type = EvidenceArtifactType.valueOf(artifactType.toUpperCase(Locale.ROOT));
            return builder.build(type, SERVER_ID, 1810000000100L,
                    "0af7651916cd43dd8448eb211c80319c", payloadBody).evidenceId();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String sourceRecord(String artifactType, String decision, String evidenceId) {
        try {
            ObjectNode rec = (ObjectNode) mapper.readTree(sourceRecord(artifactType, decision));
            rec.put("evidence_id", evidenceId);
            return mapper.writeValueAsString(rec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
