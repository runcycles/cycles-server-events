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
    }

    @Scheduled(fixedDelay = 1)
    public void processNext() {
        String record = consumer.popPending(timeoutSeconds);
        if (record == null) {
            return;
        }
        try {
            sink.accept(build(record));
        } catch (Exception e) {
            // A malformed/unbuildable record must not stall the loop. Drop it
            // (it stays auditable on the producer side) and continue.
            LOG.error("failed to build evidence envelope from source record: {}", e.getMessage());
        }
    }

    /** Map a source record to a built, signed envelope. */
    BuiltEvidenceEnvelope build(String recordJson) throws Exception {
        JsonNode rec = mapper.readTree(recordJson);
        EvidenceArtifactType type =
                EvidenceArtifactType.valueOf(rec.get("artifact_type").asText().toUpperCase());
        long issuedAtMs = rec.get("issued_at_ms").asLong();
        String traceId = rec.hasNonNull("trace_id") ? rec.get("trace_id").asText() : null;
        JsonNode payloadBody = rec.get("payload");
        return builder.build(type, serverId, issuedAtMs, traceId, payloadBody);
    }
}
