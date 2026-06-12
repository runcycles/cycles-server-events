package io.runcycles.events.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Assembles and signs a complete {@code cycles-evidence/v0.1} envelope.
 *
 * <p>Given the lifecycle metadata and the artifact-specific payload body, it
 * builds the envelope, derives {@code evidence_id} and the Ed25519
 * {@code signature} via {@link CyclesEvidenceCanonicalizer} +
 * {@link EvidenceSigningKey}, and returns the finished, self-consistent
 * envelope. The {@code artifact_type} ↔ {@code payload}-key pairing is
 * enforced by construction (the body is always nested under the artifact
 * type's wire name), so the builder cannot emit a mismatched envelope.
 */
@Component
public class CyclesEvidenceEnvelopeBuilder {

    static final String SCHEMA_VERSION = "cycles-evidence/v0.1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CyclesEvidenceCanonicalizer canonicalizer;
    private final EvidenceSigningKey signingKey;

    public CyclesEvidenceEnvelopeBuilder(CyclesEvidenceCanonicalizer canonicalizer,
                                         EvidenceSigningKey signingKey) {
        this.canonicalizer = canonicalizer;
        this.signingKey = signingKey;
    }

    /**
     * Build a signed envelope.
     *
     * @param artifactType the lifecycle artifact (decide/reserve/commit/release/error)
     * @param serverId     stable issuing-server URI ({@code server_id})
     * @param issuedAtMs   issuance clock ({@code issued_at_ms})
     * @param traceId      correlation id, or {@code null}/blank to omit the field
     * @param payloadBody  the artifact-specific body, nested under
     *                     {@code payload.<artifactType>}
     */
    public BuiltEvidenceEnvelope build(EvidenceArtifactType artifactType, String serverId,
                                       long issuedAtMs, String traceId, JsonNode payloadBody) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("schema_version", SCHEMA_VERSION);
        envelope.put("artifact_type", artifactType.wireName());
        envelope.put("server_id", serverId);
        envelope.put("signer_did", signingKey.signerDid());
        envelope.put("issued_at_ms", issuedAtMs);
        if (traceId != null && !traceId.isBlank()) {
            envelope.put("trace_id", traceId);
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.set(artifactType.wireName(), payloadBody);
        envelope.set("payload", payload);
        envelope.put("evidence_id", "");
        envelope.put("signature", "");

        String evidenceId = canonicalizer.computeEvidenceId(envelope);
        String signature = signingKey.sign(canonicalizer.signingBytes(envelope, evidenceId));
        envelope.put("evidence_id", evidenceId);
        envelope.put("signature", signature);

        return new BuiltEvidenceEnvelope(evidenceId, envelope, serialize(envelope));
    }

    private String serialize(ObjectNode envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("envelope serialization failed", e);
        }
    }

    /**
     * A finished envelope: its content-addressed {@code evidence_id}, the
     * envelope node, and its JSON serialization (the bytes to persist/serve).
     */
    public record BuiltEvidenceEnvelope(String evidenceId, ObjectNode envelope, String json) {
    }
}
