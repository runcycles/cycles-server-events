package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end CyclesEvidence signer-key-resolution AUTHORITY loop, on REAL
 * artifacts: the 13 golden fixtures (already byte-validated against the APS
 * verifier) + the published JWK Set ({@code cycles-jwks.json}). Proves a key
 * RESOLVED FROM THE JWKS authenticates the envelopes the signer actually
 * produces, and that the five dispositions are reported correctly — using the
 * production canonicalizer + signer ({@link JwksAuthorityVerifier}).
 *
 * <p>This is the loop a live deployment runs: emit/sign → publish JWKS
 * (getEvidenceJwks) → resolve the window-covering key → verify. Here without
 * HTTP, so it is durable regression coverage rather than a one-time smoke.
 */
class JwksAuthorityLoopTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIXTURES = "/cycles-evidence-fixtures/";

    private final JwksAuthorityVerifier verifier =
            new JwksAuthorityVerifier(new CyclesEvidenceCanonicalizer(), new EnvelopeSigner());

    @ParameterizedTest
    @ValueSource(strings = {
            "01-decide-allow", "02-reserve-allow", "03-reserve-dry-run-deny",
            "04-reserve-allow-with-caps", "05-commit-success", "06-release-success",
            "07-release-with-reason", "08-reserve-allow-no-trace-id",
            "09-decide-risk-points-allow", "10-reserve-credits-allow",
            "11-reserve-live-budget-exceeded", "12-decide-live-forbidden",
            "13-commit-with-metrics"
    })
    void everyGoldenFixture_resolvesToAuthentic(String fixture) throws Exception {
        ObjectNode envelope = load(fixture);
        ObjectNode jwks = loadJwks();
        assertThat(verifier.resolveAndVerify(envelope, jwks))
                .as("JWKS-resolved key must authenticate %s", fixture)
                .isEqualTo(JwksAuthorityVerifier.Disposition.AUTHENTIC);
    }

    @Test
    void noJwksWithMatchingPin_isBindingOnly() throws Exception {
        ObjectNode envelope = load("02-reserve-allow");
        String signerDid = envelope.get("signer_did").asText();
        assertThat(verifier.bindingOnly(envelope, signerDid))
                .isEqualTo(JwksAuthorityVerifier.Disposition.BINDING_ONLY);
    }

    @Test
    void noJwksNoPin_isStillBindingOnly_validButUnpinned() throws Exception {
        // Per spec, binding_only subsumes "valid signature, authority not asserted"
        // even with no pin (signer_pin_matched absent ⇒ valid-but-unpinned).
        ObjectNode envelope = load("02-reserve-allow");
        assertThat(verifier.bindingOnly(envelope, null))
                .isEqualTo(JwksAuthorityVerifier.Disposition.BINDING_ONLY);
    }

    @Test
    void noJwksMismatchedPin_isAuthorityFailed() throws Exception {
        // Bytes are authentic to signer_did, but not to the signer the caller
        // pinned ⇒ authority to the EXPECTED signer is not established.
        ObjectNode envelope = load("02-reserve-allow");
        assertThat(verifier.bindingOnly(envelope, "ff".repeat(32)))
                .isEqualTo(JwksAuthorityVerifier.Disposition.SIGNER_AUTHORITY_FAILED);
    }

    @Test
    void nonNumericWindowValue_doesNotCoerceToAuthentic() throws Exception {
        // A malformed cycles_nbf_ms must NOT coerce to 0 and pass the window gate;
        // the JWK is invalid ⇒ excluded ⇒ no covering key ⇒ authority failure.
        ObjectNode envelope = load("02-reserve-allow");
        ObjectNode jwks = loadJwks();
        ((ObjectNode) jwks.get("keys").get(0)).put("cycles_nbf_ms", "not-a-number");
        assertThat(verifier.resolveAndVerify(envelope, jwks))
                .isEqualTo(JwksAuthorityVerifier.Disposition.SIGNER_AUTHORITY_FAILED);
    }

    @Test
    void unreachableOrUnparseableSet_isResolutionFailed() throws Exception {
        ObjectNode envelope = load("02-reserve-allow");
        // null = DID method / fetch / parse failed
        assertThat(verifier.resolveAndVerify(envelope, null))
                .isEqualTo(JwksAuthorityVerifier.Disposition.SIGNER_RESOLUTION_FAILED);
        // present but without a `keys` array = unparseable as a JWK Set
        assertThat(verifier.resolveAndVerify(envelope, MAPPER.createObjectNode()))
                .isEqualTo(JwksAuthorityVerifier.Disposition.SIGNER_RESOLUTION_FAILED);
    }

    @Test
    void keyOutsideValidityWindow_isAuthorityFailed() throws Exception {
        ObjectNode envelope = load("02-reserve-allow");
        long issuedAt = envelope.get("issued_at_ms").asLong();
        ObjectNode jwks = loadJwks();
        // nbf AFTER the envelope's issuance ⇒ no window-covering key.
        ((ObjectNode) jwks.get("keys").get(0)).put("cycles_nbf_ms", issuedAt + 1);
        assertThat(verifier.resolveAndVerify(envelope, jwks))
                .isEqualTo(JwksAuthorityVerifier.Disposition.SIGNER_AUTHORITY_FAILED);
    }

    @Test
    void signerKeyAbsentFromSet_isAuthorityFailed() throws Exception {
        ObjectNode envelope = load("02-reserve-allow");
        ObjectNode jwks = loadJwks();
        // a different key's x ⇒ the envelope's signer is not in the set.
        ((ObjectNode) jwks.get("keys").get(0)).put("x", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThat(verifier.resolveAndVerify(envelope, jwks))
                .isEqualTo(JwksAuthorityVerifier.Disposition.SIGNER_AUTHORITY_FAILED);
    }

    @Test
    void tamperedSignature_isSignatureInvalid() throws Exception {
        ObjectNode envelope = load("02-reserve-allow");
        ObjectNode jwks = loadJwks();
        String sig = envelope.get("signature").asText();
        // flip the first hex char (stays 128-hex, so it parses but won't verify)
        envelope.put("signature", (sig.charAt(0) == '0' ? "1" : "0") + sig.substring(1));
        assertThat(verifier.resolveAndVerify(envelope, jwks))
                .isEqualTo(JwksAuthorityVerifier.Disposition.SIGNATURE_INVALID);
    }

    @Test
    void tamperedPayload_breaksEvidenceId_isSignatureInvalid() throws Exception {
        ObjectNode envelope = load("02-reserve-allow");
        ObjectNode jwks = loadJwks();
        // mutate issued_at_ms ⇒ re-derived evidence_id no longer matches the field.
        envelope.put("issued_at_ms", envelope.get("issued_at_ms").asLong() + 1);
        assertThat(verifier.resolveAndVerify(envelope, jwks))
                .isEqualTo(JwksAuthorityVerifier.Disposition.SIGNATURE_INVALID);
    }

    private ObjectNode load(String fixture) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(FIXTURES + fixture + ".json")) {
            assertThat(in).as("fixture %s present", fixture).isNotNull();
            return (ObjectNode) MAPPER.readTree(in);
        }
    }

    private ObjectNode loadJwks() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(FIXTURES + "cycles-jwks.json")) {
            assertThat(in).as("jwks fixture present").isNotNull();
            return (ObjectNode) MAPPER.readTree(in);
        }
    }
}
