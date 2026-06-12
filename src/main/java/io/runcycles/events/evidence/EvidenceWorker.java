package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
        if (serverId == null || serverId.isBlank()) {
            LOG.warn("cycles.evidence.server-id is not configured — evidence records will be "
                    + "DEAD-LETTERED (an empty server_id is not a valid envelope). Set EVIDENCE_SERVER_ID.");
        }
    }

    @Scheduled(fixedDelay = 1)
    public void processNext() {
        String record = consumer.claim(timeoutSeconds);
        if (record == null) {
            return;
        }
        try {
            sink.accept(build(record));
            consumer.ack(record); // stored → remove from the in-flight processing list
        } catch (Exception e) {
            // A malformed/unbuildable record must not stall the loop. Dead-letter
            // it (do not silently drop — evidence is an audit trail) and continue.
            LOG.error("failed to build evidence envelope from source record: {} — dead-lettering",
                    e.getMessage());
            try {
                consumer.deadLetter(record);
                consumer.ack(record); // now in evidence:failed → clear from processing
            } catch (RuntimeException dl) {
                // leave it in processing so recover() retries it on the next startup
                LOG.error("failed to dead-letter evidence source record: {} (left in-flight for recovery)",
                        dl.getMessage());
            }
        }
    }

    /**
     * Map a source record to a built, signed envelope. Validates the record and
     * config BEFORE signing — a corrupt record (or unconfigured {@code server_id})
     * must NOT be signed into a valid-looking but garbage envelope; it throws so
     * {@link #processNext()} dead-letters it.
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
        EvidenceArtifactType type = EvidenceArtifactType.valueOf(typeNode.asText().toUpperCase());
        String traceId = rec.hasNonNull("trace_id") ? rec.get("trace_id").asText() : null;
        return builder.build(type, serverId, issuedNode.asLong(), traceId, payloadBody);
    }
}
