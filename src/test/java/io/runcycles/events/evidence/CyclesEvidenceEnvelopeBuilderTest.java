package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CyclesEvidenceEnvelopeBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CyclesEvidenceCanonicalizer canonicalizer = new CyclesEvidenceCanonicalizer();
    private final EnvelopeSigner signer = new EnvelopeSigner();

    /**
     * Assembly correctness: rebuilding a reference fixture from its own
     * metadata + payload (and its signer_did, via a fixed key) must reproduce
     * the fixture's {@code evidence_id} byte-for-byte — proving the builder
     * lays out the envelope exactly as the reference toolchain does. Covers
     * all four lifecycle types and the trace-id-absent case.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "01-decide-allow", "02-reserve-allow", "05-commit-success",
            "06-release-success", "08-reserve-allow-no-trace-id"
    })
    void reproducesFixtureEvidenceIdFromParts(String fixture) throws Exception {
        ObjectNode env = loadFixture(fixture);
        EvidenceArtifactType type = EvidenceArtifactType.valueOf(env.get("artifact_type").asText().toUpperCase());
        String traceId = env.hasNonNull("trace_id") ? env.get("trace_id").asText() : null;

        CyclesEvidenceEnvelopeBuilder builder = new CyclesEvidenceEnvelopeBuilder(
                canonicalizer, new FixedKey(env.get("signer_did").asText()));

        BuiltEvidenceEnvelope built = builder.build(
                type,
                env.get("server_id").asText(),
                env.get("issued_at_ms").asLong(),
                traceId,
                env.get("payload").get(type.wireName()));

        assertThat(built.evidenceId())
                .as("rebuilt evidence_id matches reference for %s", fixture)
                .isEqualTo(env.get("evidence_id").asText());
    }

    @Test
    void buildsSelfConsistentSignedEnvelope() throws Exception {
        EvidenceSigningKey key = new LocalEvidenceSigningKey(signer, "", "", true); // ephemeral dev key
        CyclesEvidenceEnvelopeBuilder builder = new CyclesEvidenceEnvelopeBuilder(canonicalizer, key);
        ObjectNode src = loadFixture("02-reserve-allow");

        BuiltEvidenceEnvelope built = builder.build(
                EvidenceArtifactType.RESERVE, "https://cycles.example.com/v1",
                1810000000100L, "0af7651916cd43dd8448eb211c80319c",
                src.get("payload").get("reserve"));

        ObjectNode e = built.envelope();
        assertThat(e.get("schema_version").asText()).isEqualTo("cycles-evidence/v0.1");
        assertThat(e.get("artifact_type").asText()).isEqualTo("reserve");
        assertThat(e.get("payload").fieldNames()).toIterable().containsExactly("reserve");
        assertThat(e.get("signer_did").asText()).isEqualTo(key.signerDid());

        // evidence_id recomputes and the signature verifies against signer_did
        assertThat(canonicalizer.computeEvidenceId(e)).isEqualTo(built.evidenceId());
        byte[] signingBytes = canonicalizer.signingBytes(e, built.evidenceId());
        assertThat(signer.verify(signingBytes, e.get("signature").asText(), key.signerDid())).isTrue();
    }

    @Test
    void omitsTraceIdWhenBlank() throws Exception {
        EvidenceSigningKey key = new LocalEvidenceSigningKey(signer, "", "", true);
        CyclesEvidenceEnvelopeBuilder builder = new CyclesEvidenceEnvelopeBuilder(canonicalizer, key);
        ObjectNode src = loadFixture("02-reserve-allow");

        BuiltEvidenceEnvelope built = builder.build(
                EvidenceArtifactType.RESERVE, "https://cycles.example.com/v1",
                1810000000100L, null, src.get("payload").get("reserve"));

        assertThat(built.envelope().has("trace_id")).isFalse();
    }

    private ObjectNode loadFixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(
                "/cycles-evidence-fixtures/" + name + ".json")) {
            return (ObjectNode) mapper.readTree(in);
        }
    }

    /** A signing key with a fixed signer_did, used to reproduce a fixture's
     *  evidence_id (which is independent of the signature value). */
    private record FixedKey(String did) implements EvidenceSigningKey {
        @Override
        public String signerDid() {
            return did;
        }

        @Override
        public String sign(byte[] signingInput) {
            return "00".repeat(64);
        }
    }
}
