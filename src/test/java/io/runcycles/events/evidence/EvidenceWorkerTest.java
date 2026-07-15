package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.events.metrics.CyclesMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private final CyclesEvidenceSchemaValidator schemaValidator = new CyclesEvidenceSchemaValidator();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CyclesMetrics metrics = new CyclesMetrics(meterRegistry, false);

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
        return new EvidenceWorker(
                consumer, builder, schemaValidator, sink, mapper, metrics,
                1, serverId, 1_000, 30_000);
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
    void queueConnectionFailureDoesNotEscapeScheduledWorker() {
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        when(consumer.claim(1)).thenThrow(new redis.clients.jedis.exceptions.JedisConnectionException("down"));

        EvidenceWorker worker = worker(consumer, envelope -> { });
        org.assertj.core.api.Assertions.assertThatCode(worker::processNext).doesNotThrowAnyException();
        worker.processNext();

        verify(consumer).claim(1);
        assertThat(meterRegistry.find(CyclesMetrics.EVIDENCE_RETRY_DEFERRED)
                .tags("artifact_type", CyclesMetrics.TAG_UNKNOWN, "reason", "queue_unavailable")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void deadLettersMalformedRecordWithoutThrowingOrSinking() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        String bad = "{ not valid json";
        when(consumer.claim(1)).thenReturn(bad);
        when(consumer.deadLetterAndAck(bad)).thenReturn(true);

        worker(consumer, sink).processNext(); // must not throw

        assertThat(sink.count).isZero();
        verify(consumer).deadLetterAndAck(bad);
    }

    @Test
    void deadLettersRecordWithMissingPayloadRatherThanSigningGarbage() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        String noPayload = "{\"artifact_type\":\"reserve\",\"issued_at_ms\":1}";
        when(consumer.claim(1)).thenReturn(noPayload);
        when(consumer.deadLetterAndAck(noPayload)).thenReturn(true);

        worker(consumer, sink).processNext();

        assertThat(sink.count).isZero(); // must NOT sign an empty/garbage envelope
        verify(consumer).deadLetterAndAck(noPayload);
    }

    @Test
    void constructorRejectsBlankServerIdBeforeAnyRecordCanBeClaimed() {
        assertThatThrownBy(() -> worker(mock(EvidenceQueueConsumer.class), envelope -> { }, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server-id");
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
        verify(consumer, never()).deadLetterAndAck(anyString());
    }

    @Test
    void identityMismatchRemainsInFlightInsteadOfDrainingValidRecordsIntoDlq() {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        // a wrong id (e.g. producer/worker server-id or signer-did config drift)
        String record = sourceRecord("reserve", "ALLOW", "f".repeat(64));
        when(consumer.claim(1)).thenReturn(record);

        EvidenceWorker worker = worker(consumer, sink);
        worker.processNext();
        worker.processNext();

        assertThat(sink.count).isZero(); // must NOT store an unbindable envelope
        verify(consumer, never()).deadLetterAndAck(record);
        verify(consumer, never()).ack(record);
        verify(consumer).claim(1);
        assertThat(meterRegistry.find(CyclesMetrics.EVIDENCE_RETRY_DEFERRED)
                .tags("artifact_type", "reserve", "reason", "identity_mismatch")
                .counter().count()).isEqualTo(1.0);
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
        verify(consumer, never()).deadLetterAndAck(anyString()); // and must NOT be dead-lettered
    }

    @Test
    void transientStoreFailureRemainsInFlightForRetry() {
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        EvidenceSink unavailable = mock(EvidenceSink.class);
        String record = sourceRecord("reserve", "ALLOW");
        when(consumer.claim(1)).thenReturn(record);
        doThrow(new IllegalStateException("store unavailable")).when(unavailable)
                .accept(org.mockito.ArgumentMatchers.any());

        EvidenceWorker worker = worker(consumer, unavailable);
        worker.processNext();
        worker.processNext();

        verify(consumer).claim(1);
        verify(consumer, never()).deadLetterAndAck(anyString());
        verify(consumer, never()).ack(anyString());
        assertThat(meterRegistry.find(CyclesMetrics.EVIDENCE_RETRY_DEFERRED)
                .tags("artifact_type", "reserve", "reason", "store_failure")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void rejectsFractionalOrNegativeIssuedAtBeforeSigning() throws Exception {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        ObjectNode invalid = (ObjectNode) mapper.readTree(sourceRecord("reserve", "ALLOW"));
        invalid.put("issued_at_ms", -1.5);
        String record = mapper.writeValueAsString(invalid);
        when(consumer.claim(1)).thenReturn(record);
        when(consumer.deadLetterAndAck(record)).thenReturn(true);

        worker(consumer, sink).processNext();

        assertThat(sink.count).isZero();
        verify(consumer).deadLetterAndAck(record);
    }

    @Test
    void rejectsInvalidTraceIdAndArtifactPayloadBeforeSigning() throws Exception {
        CapturingSink sink = new CapturingSink();
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        ObjectNode invalid = (ObjectNode) mapper.readTree(sourceRecord("commit", "ALLOW"));
        invalid.put("trace_id", "NOT-A-TRACE");
        String record = mapper.writeValueAsString(invalid);
        when(consumer.claim(1)).thenReturn(record);
        when(consumer.deadLetterAndAck(record)).thenReturn(true);

        worker(consumer, sink).processNext();

        assertThat(sink.count).isZero();
        verify(consumer).deadLetterAndAck(record);
    }

    @Test
    void poisonRecordMovedByRecoveryIsNotCountedAsDeadLettered() {
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        String bad = "{ not valid json";
        when(consumer.claim(1)).thenReturn(bad);
        when(consumer.deadLetterAndAck(bad)).thenReturn(false);

        worker(consumer, envelope -> { }).processNext();

        assertThat(meterRegistry.find(CyclesMetrics.EVIDENCE_RETRY_DEFERRED)
                .tags("artifact_type", CyclesMetrics.TAG_UNKNOWN, "reason", "dlq_move_conflict")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void deadLetterMoveFailureLeavesPoisonRecordInFlight() {
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        String bad = "{ not valid json";
        when(consumer.claim(1)).thenReturn(bad);
        when(consumer.deadLetterAndAck(bad)).thenThrow(new IllegalStateException("redis unavailable"));

        worker(consumer, envelope -> { }).processNext();

        assertThat(meterRegistry.find(CyclesMetrics.EVIDENCE_RETRY_DEFERRED)
                .tags("artifact_type", CyclesMetrics.TAG_UNKNOWN, "reason", "dlq_move_failure")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void signingInfrastructureFailureRemainsInFlight() {
        EvidenceQueueConsumer consumer = mock(EvidenceQueueConsumer.class);
        CyclesEvidenceEnvelopeBuilder unavailableBuilder = mock(CyclesEvidenceEnvelopeBuilder.class);
        String record = sourceRecord("reserve", "ALLOW");
        when(consumer.claim(1)).thenReturn(record);
        when(unavailableBuilder.build(
                org.mockito.ArgumentMatchers.eq(EvidenceArtifactType.RESERVE),
                org.mockito.ArgumentMatchers.eq(SERVER_ID),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.any(JsonNode.class)))
                .thenThrow(new IllegalStateException("signer unavailable"));
        EvidenceWorker worker = new EvidenceWorker(
                consumer, unavailableBuilder, schemaValidator, envelope -> { },
                mapper, metrics, 1, SERVER_ID, 1_000, 30_000);

        worker.processNext();

        verify(consumer, never()).ack(anyString());
        verify(consumer, never()).deadLetterAndAck(anyString());
        assertThat(meterRegistry.find(CyclesMetrics.EVIDENCE_RETRY_DEFERRED)
                .tags("artifact_type", "reserve", "reason", "build_failure")
                .counter().count()).isEqualTo(1.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{}",
            "{\"artifact_type\":1,\"issued_at_ms\":1,\"payload\":{}}",
            "{\"artifact_type\":\"\",\"issued_at_ms\":1,\"payload\":{}}",
            "{\"artifact_type\":\"reserve\",\"issued_at_ms\":-1,\"payload\":{\"request\":{},\"response\":{}}}",
            "{\"artifact_type\":\"reserve\",\"issued_at_ms\":1,\"payload\":[],\"trace_id\":7}",
            "{\"artifact_type\":\"reserve\",\"issued_at_ms\":1,\"payload\":{\"request\":{},\"response\":{},\"extra\":true}}",
            "{\"artifact_type\":\"commit\",\"issued_at_ms\":1,\"payload\":{\"request\":{},\"response\":{}}}",
            "{\"artifact_type\":\"error\",\"issued_at_ms\":1,\"payload\":{\"endpoint\":\"POST /v1/decide\",\"http_status\":399,\"response\":{}}}",
            "{\"artifact_type\":\"error\",\"issued_at_ms\":1,\"payload\":{\"endpoint\":\"GET /unknown\",\"http_status\":500,\"response\":{}}}",
            "{\"artifact_type\":\"reserve\",\"issued_at_ms\":1,\"payload\":{\"request\":{},\"response\":{}},\"evidence_id\":7}"
    })
    void rejectsNonConformingSourceShapes(String record) {
        EvidenceWorker worker = worker(mock(EvidenceQueueConsumer.class), envelope -> { });

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> worker.build(record))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsRelativeServerIdentityUri() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> worker(mock(EvidenceQueueConsumer.class), envelope -> { }, "relative/server"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absolute URI");
    }

    @Test
    void constructorRejectsNonPositiveQueueTimeout() {
        assertThatThrownBy(() -> new EvidenceWorker(
                mock(EvidenceQueueConsumer.class), builder, schemaValidator, envelope -> { },
                mapper, metrics, 0, SERVER_ID, 1_000, 30_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void constructorRejectsInvalidClaimBackoffs() {
        assertThatThrownBy(() -> new EvidenceWorker(
                mock(EvidenceQueueConsumer.class), builder, schemaValidator, envelope -> { },
                mapper, metrics, 1, SERVER_ID, 0, 30_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backoff");
        assertThatThrownBy(() -> new EvidenceWorker(
                mock(EvidenceQueueConsumer.class), builder, schemaValidator, envelope -> { },
                mapper, metrics, 1, SERVER_ID, 1_000, 3_600_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backoff");
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
            verify(consumer, never()).deadLetterAndAck(anyString());
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
        switch (artifactType) {
            case "decide" -> {
                putDecisionRequest(payload.putObject("request"), "k1");
                payload.putObject("response").put("decision", decision);
            }
            case "reserve" -> {
                putDecisionRequest(payload.putObject("request"), "k1");
                ObjectNode response = payload.putObject("response");
                response.put("decision", decision);
                response.putArray("affected_scopes").add("tenant:acme");
                if (!"DENY".equals(decision)) {
                    response.put("reservation_id", "res_1");
                }
            }
            case "commit" -> {
                payload.put("reservation_id", "res_1");
                ObjectNode request = payload.putObject("request");
                request.put("idempotency_key", "k1");
                putAmount(request.putObject("actual"), 750);
                ObjectNode response = payload.putObject("response");
                response.put("status", "COMMITTED");
                putAmount(response.putObject("charged"), 750);
            }
            case "release" -> {
                payload.put("reservation_id", "res_1");
                payload.putObject("request").put("idempotency_key", "k1");
                ObjectNode response = payload.putObject("response");
                response.put("status", "RELEASED");
                putAmount(response.putObject("released"), 1000);
            }
            case "error" -> {
                payload.put("endpoint", "POST /v1/reservations");
                payload.put("http_status", 409);
                ObjectNode response = payload.putObject("response");
                response.put("error", "BUDGET_EXCEEDED");
                response.put("message", "Budget exceeded");
                response.put("request_id", "req_1");
            }
            default -> {
                // Unknown artifact types are rejected before their payload is inspected.
            }
        }
        try {
            return mapper.writeValueAsString(rec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void putDecisionRequest(ObjectNode request, String idempotencyKey) {
        request.put("idempotency_key", idempotencyKey);
        request.putObject("subject").put("tenant", "acme");
        ObjectNode action = request.putObject("action");
        action.put("kind", "model.call");
        action.put("name", "test-model");
        putAmount(request.putObject("estimate"), 1000);
    }

    private static void putAmount(ObjectNode amount, long value) {
        amount.put("unit", "USD_MICROCENTS");
        amount.put("amount", value);
    }
}
