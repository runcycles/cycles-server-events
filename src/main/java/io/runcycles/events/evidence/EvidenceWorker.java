package io.runcycles.events.evidence;

import static io.runcycles.events.logging.LogSanitizer.safe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;
import io.runcycles.events.metrics.CyclesMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Consumes CyclesEvidence source records off {@code evidence:pending}, builds
 * and signs a {@code cycles-evidence/v0.1} envelope for each, and hands it to
 * the {@link EvidenceSink}.
 *
 * <p>This is the sibling-in-event-tier signing worker: it owns the server
 * identity it stamps. cycles-server emits only operational facts
 * ({@code artifact_type}, {@code issued_at_ms}, {@code trace_id}, payload);
 * this worker adds {@code server_id} (config) and {@code signer_did} (the
 * signing key) at build time. Mirrors the webhook {@code DispatchLoop} reliable
 * queue scheduling.
 */
@Component
@ConditionalOnEvidenceConfigured
public class EvidenceWorker {

    private static final Logger LOG = LoggerFactory.getLogger(EvidenceWorker.class);
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern EVIDENCE_ID = Pattern.compile("^[0-9a-f]{64}$");
    private final EvidenceQueueConsumer consumer;
    private final CyclesEvidenceEnvelopeBuilder builder;
    private final CyclesEvidenceSchemaValidator schemaValidator;
    private final EvidenceSink sink;
    private final ObjectMapper mapper;
    private final CyclesMetrics metrics;
    private final int timeoutSeconds;
    private final String serverId;
    private final long queueFailureBackoffMs;
    private final long infrastructureBackoffMs;
    private volatile long claimRetryNotBeforeMs;

    public EvidenceWorker(
            EvidenceQueueConsumer consumer,
            CyclesEvidenceEnvelopeBuilder builder,
            CyclesEvidenceSchemaValidator schemaValidator,
            EvidenceSink sink,
            ObjectMapper mapper,
            CyclesMetrics metrics,
            @Value("${cycles.evidence.queue.timeout-seconds:5}") int timeoutSeconds,
            @Value("${cycles.evidence.server-id:}") String serverId,
            @Value("${cycles.evidence.queue.failure-backoff-ms:1000}") long queueFailureBackoffMs,
            @Value("${cycles.evidence.queue.infrastructure-backoff-ms:30000}") long infrastructureBackoffMs) {
        this.consumer = consumer;
        this.builder = builder;
        this.schemaValidator = schemaValidator;
        this.sink = sink;
        this.mapper = mapper;
        this.metrics = metrics;
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("evidence queue timeout must be positive");
        }
        if (queueFailureBackoffMs <= 0 || queueFailureBackoffMs > 60_000
                || infrastructureBackoffMs <= 0 || infrastructureBackoffMs > 3_600_000) {
            throw new IllegalArgumentException("evidence claim backoffs are outside their supported range");
        }
        this.timeoutSeconds = timeoutSeconds;
        this.serverId = requireAbsoluteServerId(serverId);
        this.queueFailureBackoffMs = queueFailureBackoffMs;
        this.infrastructureBackoffMs = infrastructureBackoffMs;
    }

    @Scheduled(fixedDelayString = "${cycles.evidence.queue.loop-delay-ms:25}")
    public void processNext() {
        if (System.currentTimeMillis() < claimRetryNotBeforeMs) {
            return;
        }
        String record;
        try {
            record = consumer.claim(timeoutSeconds);
        } catch (JedisConnectionException unavailable) {
            deferClaims(queueFailureBackoffMs);
            LOG.warn("Evidence queue Redis connection failure: pending_queue=evidence:pending processing_queue=evidence:processing timeout_seconds={} error={}",
                    timeoutSeconds, safe(unavailable.getMessage()));
            metrics.recordEvidenceRetryDeferred(null, "queue_unavailable");
            return;
        } catch (RuntimeException unexpected) {
            deferClaims(queueFailureBackoffMs);
            LOG.error("Evidence queue claim failed: pending_queue=evidence:pending processing_queue=evidence:processing timeout_seconds={} error={}",
                    timeoutSeconds, safe(unexpected.getMessage()), unexpected);
            metrics.recordEvidenceRetryDeferred(null, "claim_failure");
            return;
        }
        if (record == null) {
            return;
        }
        EvidenceSourceLogContext claimedContext = sourceContext(record);
        metrics.recordEvidenceClaimed(claimedContext.artifactType());
        BuiltEvidenceEnvelope envelope;
        // Deterministic source/build failures are poison records and belong in
        // the DLQ. Storage failures are infrastructure failures and are handled
        // separately below so they remain in-flight for retry.
        try {
            envelope = build(record);
        } catch (EvidenceIdentityMismatchException identityMismatch) {
            deferClaims(infrastructureBackoffMs);
            EvidenceSourceLogContext ctx = sourceContext(record);
            LOG.error("Evidence producer/worker identity mismatch; source record remains in-flight until configuration is reconciled: artifact_type={} evidence_id={} trace_id={} issued_at_ms={} error={}",
                    safe(ctx.artifactType()), safe(ctx.evidenceId()), safe(ctx.traceId()), ctx.issuedAtMs(),
                    safe(identityMismatch.getMessage()));
            metrics.recordEvidenceRetryDeferred(ctx.artifactType(), "identity_mismatch");
            return;
        } catch (JsonProcessingException | IllegalArgumentException invalidSource) {
            deadLetterInvalidSource(record, invalidSource);
            return;
        } catch (Exception buildFailure) {
            deferClaims(infrastructureBackoffMs);
            EvidenceSourceLogContext ctx = sourceContext(record);
            LOG.error("Evidence build/signing infrastructure failure; source record remains in-flight for recovery: artifact_type={} evidence_id={} trace_id={} issued_at_ms={} exception_class={} error={}",
                    safe(ctx.artifactType()), safe(ctx.evidenceId()), safe(ctx.traceId()), ctx.issuedAtMs(),
                    buildFailure.getClass().getSimpleName(), safe(buildFailure.getMessage()), buildFailure);
            metrics.recordEvidenceRetryDeferred(ctx.artifactType(), "build_failure");
            return;
        }
        try {
            sink.accept(envelope);
        } catch (RuntimeException storeFailure) {
            deferClaims(infrastructureBackoffMs);
            EvidenceSourceLogContext ctx = sourceContext(record);
            LOG.error("Evidence store unavailable; source record remains in-flight for idle-based retry: artifact_type={} evidence_id={} built_evidence_id={} trace_id={} issued_at_ms={} source_parseable={} error={}",
                    safe(ctx.artifactType()), safe(ctx.evidenceId()), safe(envelope.evidenceId()),
                    safe(ctx.traceId()), ctx.issuedAtMs(), ctx.parseable(),
                    safe(storeFailure.getMessage()), storeFailure);
            metrics.recordEvidenceRetryDeferred(ctx.artifactType(), "store_failure");
            return;
        }
        metrics.recordEvidenceStored(envelope.envelope().path("artifact_type").asText(null));
        // Stored successfully → remove from the in-flight processing list. If the ack
        // fails, the envelope is ALREADY stored, so we must NOT dead-letter it; leave
        // the record in processing and let stale recovery requeue it. Reprocessing is
        // idempotent: the store is content-addressed (same evidence_id → same key),
        // so a re-store overwrites identical bytes.
        try {
            consumer.ack(record);
        } catch (RuntimeException ackEx) {
            deferClaims(queueFailureBackoffMs);
            EvidenceSourceLogContext ctx = sourceContext(record);
            LOG.warn("Evidence envelope stored but ack failed; left in-flight for idempotent recovery: artifact_type={} evidence_id={} stored_evidence_id={} trace_id={} issued_at_ms={} source_parseable={} error={}",
                    safe(ctx.artifactType()), safe(ctx.evidenceId()), safe(envelope != null ? envelope.evidenceId() : null),
                    safe(ctx.traceId()), ctx.issuedAtMs(), ctx.parseable(), safe(ackEx.getMessage()), ackEx);
            metrics.recordEvidenceRetryDeferred(ctx.artifactType(), "ack_failure");
        }
    }

    private void deadLetterInvalidSource(String record, Exception invalidSource) {
        EvidenceSourceLogContext ctx = sourceContext(record);
        String errorSummary = invalidSource instanceof JsonProcessingException
                ? "malformed JSON"
                : safe(invalidSource.getMessage());
        LOG.error("Invalid evidence source record; dead-lettering source record: artifact_type={} evidence_id={} trace_id={} issued_at_ms={} source_parseable={} exception_class={} error={}",
                safe(ctx.artifactType()), safe(ctx.evidenceId()), safe(ctx.traceId()), ctx.issuedAtMs(), ctx.parseable(),
                invalidSource.getClass().getSimpleName(), errorSummary);
        try {
            if (consumer.deadLetterAndAck(record)) {
                metrics.recordEvidenceDeadLettered(ctx.artifactType(), "invalid_source");
            } else {
                LOG.warn("Evidence poison record was no longer in-flight during DLQ move; deferring to queue recovery: artifact_type={} evidence_id={} trace_id={} issued_at_ms={}",
                        safe(ctx.artifactType()), safe(ctx.evidenceId()), safe(ctx.traceId()), ctx.issuedAtMs());
                metrics.recordEvidenceRetryDeferred(ctx.artifactType(), "dlq_move_conflict");
            }
        } catch (RuntimeException dl) {
            deferClaims(queueFailureBackoffMs);
            LOG.error("Failed to dead-letter evidence source record; left in-flight for recovery: artifact_type={} evidence_id={} trace_id={} issued_at_ms={} source_parseable={} error={}",
                    safe(ctx.artifactType()), safe(ctx.evidenceId()), safe(ctx.traceId()), ctx.issuedAtMs(), ctx.parseable(),
                    safe(dl.getMessage()), dl);
            metrics.recordEvidenceRetryDeferred(ctx.artifactType(), "dlq_move_failure");
        }
    }

    private void deferClaims(long delayMs) {
        long candidate = System.currentTimeMillis() + delayMs;
        claimRetryNotBeforeMs = Math.max(claimRetryNotBeforeMs, candidate);
    }

    /**
     * Map a source record to a built, signed envelope. Validates the record and
     * config BEFORE signing — a corrupt record must NOT be signed into a
     * valid-looking but garbage envelope. Spring creates this worker only when
     * evidence is enabled; the direct guard below remains fail-closed.
     */
    BuiltEvidenceEnvelope build(String recordJson) throws Exception {
        JsonNode rec = mapper.readTree(recordJson);
        if (rec == null || !rec.isObject()) {
            throw new IllegalArgumentException("source record must be a JSON object");
        }
        JsonNode typeNode = rec.get("artifact_type");
        JsonNode issuedNode = rec.get("issued_at_ms");
        JsonNode payloadBody = rec.get("payload");
        if (typeNode == null || !typeNode.isTextual() || typeNode.asText().isBlank()) {
            throw new IllegalArgumentException("source record missing string artifact_type");
        }
        if (issuedNode == null || !issuedNode.isIntegralNumber() || !issuedNode.canConvertToLong()
                || issuedNode.longValue() < 0) {
            throw new IllegalArgumentException("source record issued_at_ms must be a non-negative int64");
        }
        if (payloadBody == null || !payloadBody.isObject()) {
            throw new IllegalArgumentException("source record missing object payload");
        }
        // Locale.ROOT: artifact_type is an ASCII wire token; the default locale could
        // mis-case it (e.g. Turkish dotless-i) and break the enum lookup.
        EvidenceArtifactType type = EvidenceArtifactType.valueOf(typeNode.asText().toUpperCase(Locale.ROOT));
        String traceId = null;
        if (rec.hasNonNull("trace_id")) {
            JsonNode traceNode = rec.get("trace_id");
            if (!traceNode.isTextual() || !TRACE_ID.matcher(traceNode.asText()).matches()) {
                throw new IllegalArgumentException("source record trace_id must be 32 lowercase hex characters");
            }
            traceId = traceNode.asText();
        }
        schemaValidator.validatePayload(type, payloadBody);
        BuiltEvidenceEnvelope built = builder.build(type, serverId, issuedNode.asLong(), traceId, payloadBody);
        schemaValidator.validateEnvelope(built.envelope());

        // Cross-check the producer-stamped evidence_id (when present): cycles-server
        // computes it synchronously and returns it to the caller, so the envelope we
        // sign+store MUST hash to the same id or the caller would resolve a 404 /
        // mismatched envelope. A mismatch means server_id/signer_did config drift
        // between the producer and this worker. Leave the record in-flight and pause
        // claims briefly rather than storing an unbindable envelope or draining a whole
        // queue into the DLQ. Records without evidence_id (producer identity
        // unconfigured) skip the check.
        if (rec.hasNonNull("evidence_id")) {
            JsonNode claimedNode = rec.get("evidence_id");
            if (!claimedNode.isTextual() || !EVIDENCE_ID.matcher(claimedNode.asText()).matches()) {
                throw new IllegalArgumentException("source record evidence_id must be 64 lowercase hex characters");
            }
            String claimed = claimedNode.asText();
            if (!claimed.equals(built.evidenceId())) {
                throw new EvidenceIdentityMismatchException(
                        "evidence_id cross-check failed: producer stamped " + claimed
                        + " but worker computed " + built.evidenceId()
                        + " — server-id/signer-did config drift between producer and worker?");
            }
        }
        return built;
    }

    private static String requireAbsoluteServerId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("cycles.evidence.server-id must be configured when evidence is enabled");
        }
        String normalized = value.trim();
        try {
            URI uri = URI.create(normalized);
            if (!uri.isAbsolute()) {
                throw new IllegalStateException("cycles.evidence.server-id must be an absolute URI");
            }
            return normalized;
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalStateException("cycles.evidence.server-id must be a valid absolute URI", invalidUri);
        }
    }

    static final class EvidenceIdentityMismatchException extends IllegalStateException {
        EvidenceIdentityMismatchException(String message) {
            super(message);
        }
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
