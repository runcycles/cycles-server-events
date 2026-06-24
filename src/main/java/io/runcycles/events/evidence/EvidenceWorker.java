package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Consumes CyclesEvidence source records off {@code evidence:pending}, builds
 * and signs a {@code cycles-evidence/v0.1} envelope for each, and hands it to
 * the {@link EvidenceSink}.
 *
 * <p>This is the sibling-in-event-tier signing worker: it owns the server
 * identity it stamps. cycles-server emits only operational facts
 * ({@code artifact_type}, {@code issued_at_ms}, {@code trace_id}, payload);
 * this worker adds {@code server_id} (config) and {@code signer_did} (the
 * signing key) at build time. Mirrors the webhook {@code DispatchLoop} BRPOP
 * scheduling.
 */
@Component
@ConditionalOnEvidenceConfigured
public class EvidenceWorker {

    private static final Logger LOG = LoggerFactory.getLogger(EvidenceWorker.class);

    private final EvidenceQueueConsumer consumer;
    private final CyclesEvidenceEnvelopeBuilder builder;
    private final EvidenceSink sink;
    private final ObjectMapper mapper;
    private final int timeoutSeconds;
    private final String serverId;

    public EvidenceWorker(
            EvidenceQueueConsumer consumer,
            CyclesEvidenceEnvelopeBuilder builder,
            EvidenceSink sink,
            ObjectMapper mapper,
            @Value("${cycles.evidence.queue.timeout-seconds:5}") int timeoutSeconds,
            @Value("${cycles.evidence.server-id:}") String serverId) {
        this.consumer = consumer;
        this.builder = builder;
        this.sink = sink;
        this.mapper = mapper;
        this.timeoutSeconds = timeoutSeconds;
        this.serverId = serverId;
    }

    @Scheduled(fixedDelay = 1)
    public void processNext() {
        String record = consumer.claim(timeoutSeconds);
        if (record == null) {
            return;
        }
        BuiltEvidenceEnvelope envelope = null;
        // Build + store first. A failure HERE means the envelope was never stored,
        // so the record is dead-lettered (it is an audit trail — never silently
        // dropped). Kept separate from the ack below: a post-store ack failure must
        // NOT dead-letter an already-stored envelope.
        try {
            envelope = build(record);
            sink.accept(envelope);
        } catch (Exception e) {
            EvidenceSourceLogContext ctx = sourceContext(record);
            LOG.error("Failed to build or store evidence envelope; dead-lettering source record: artifact_type={} evidence_id={} trace_id={} issued_at_ms={} source_parseable={} error={}",
                    ctx.artifactType(), ctx.evidenceId(), ctx.traceId(), ctx.issuedAtMs(), ctx.parseable(),
                    e.getMessage(), e);
            try {
                consumer.deadLetter(record);
                consumer.ack(record); // now in evidence:failed → clear from processing
            } catch (RuntimeException dl) {
                // leave it in processing so recover() retries it on the next startup
                EvidenceSourceLogContext dlCtx = sourceContext(record);
                LOG.error("Failed to dead-letter evidence source record; left in-flight for recovery: artifact_type={} evidence_id={} trace_id={} issued_at_ms={} source_parseable={} error={}",
                        dlCtx.artifactType(), dlCtx.evidenceId(), dlCtx.traceId(), dlCtx.issuedAtMs(), dlCtx.parseable(),
                        dl.getMessage(), dl);
            }
            return;
        }
        // Stored successfully → remove from the in-flight processing list. If the ack
        // fails, the envelope is ALREADY stored, so we must NOT dead-letter it; leave
        // the record in processing and let recover() requeue it. Reprocessing is
        // idempotent: the store is content-addressed (same evidence_id → same key),
        // so a re-store overwrites identical bytes.
        try {
            consumer.ack(record);
        } catch (RuntimeException ackEx) {
            EvidenceSourceLogContext ctx = sourceContext(record);
            LOG.warn("Evidence envelope stored but ack failed; left in-flight for idempotent recovery: artifact_type={} evidence_id={} stored_evidence_id={} trace_id={} issued_at_ms={} source_parseable={} error={}",
                    ctx.artifactType(), ctx.evidenceId(), envelope != null ? envelope.evidenceId() : null,
                    ctx.traceId(), ctx.issuedAtMs(), ctx.parseable(), ackEx.getMessage(), ackEx);
        }
    }

    /**
     * Map a source record to a built, signed envelope. Validates the record and
     * config BEFORE signing — a corrupt record must NOT be signed into a
     * valid-looking but garbage envelope. Spring creates this worker only when
     * evidence is enabled; the direct guard below remains fail-closed.
     */
    BuiltEvidenceEnvelope build(String recordJson) throws Exception {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalStateException(
                    "cycles.evidence.server-id is not configured — refusing to sign an empty server_id");
        }
        JsonNode rec = mapper.readTree(recordJson);
        JsonNode typeNode = rec.get("artifact_type");
        JsonNode issuedNode = rec.get("issued_at_ms");
        JsonNode payloadBody = rec.get("payload");
        if (typeNode == null || !typeNode.isTextual()) {
            throw new IllegalArgumentException("source record missing string artifact_type");
        }
        if (issuedNode == null || !issuedNode.isNumber()) {
            throw new IllegalArgumentException("source record missing numeric issued_at_ms");
        }
        if (payloadBody == null || !payloadBody.isObject()) {
            throw new IllegalArgumentException("source record missing object payload");
        }
        // Locale.ROOT: artifact_type is an ASCII wire token; the default locale could
        // mis-case it (e.g. Turkish dotless-i) and break the enum lookup.
        EvidenceArtifactType type = EvidenceArtifactType.valueOf(typeNode.asText().toUpperCase(Locale.ROOT));
        String traceId = rec.hasNonNull("trace_id") ? rec.get("trace_id").asText() : null;
        BuiltEvidenceEnvelope built = builder.build(type, serverId, issuedNode.asLong(), traceId, payloadBody);

        // Cross-check the producer-stamped evidence_id (when present): cycles-server
        // computes it synchronously and returns it to the caller, so the envelope we
        // sign+store MUST hash to the same id or the caller would resolve a 404 /
        // mismatched envelope. A mismatch means server_id/signer_did config drift
        // between the producer and this worker — fail closed (dead-letter) rather than
        // store an unbindable envelope. Records without evidence_id (producer identity
        // unconfigured) skip the check.
        if (rec.hasNonNull("evidence_id")) {
            String claimed = rec.get("evidence_id").asText();
            if (!claimed.equals(built.evidenceId())) {
                throw new IllegalStateException(
                        "evidence_id cross-check failed: producer stamped " + claimed
                        + " but worker computed " + built.evidenceId()
                        + " — server-id/signer-did config drift between producer and worker?");
            }
        }
        return built;
    }

    private EvidenceSourceLogContext sourceContext(String recordJson) {
        try {
            JsonNode rec = mapper.readTree(recordJson);
            return new EvidenceSourceLogContext(
                    textOrNull(rec, "artifact_type"),
                    textOrNull(rec, "evidence_id"),
                    textOrNull(rec, "trace_id"),
                    rec.hasNonNull("issued_at_ms") ? rec.get("issued_at_ms").asLong() : null,
                    true);
        } catch (Exception e) {
            return new EvidenceSourceLogContext(null, null, null, null, false);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private record EvidenceSourceLogContext(
            String artifactType,
            String evidenceId,
            String traceId,
            Long issuedAtMs,
            boolean parseable) {}
}
