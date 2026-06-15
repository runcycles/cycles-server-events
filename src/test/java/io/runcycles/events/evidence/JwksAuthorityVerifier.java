package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Reference verifier for the cycles-evidence v0.2 signer-key-resolution AUTHORITY
 * loop (test-only). Given a CyclesEvidence envelope and the resolved JWK Set, it
 * resolves the signing key and reports exactly one of the five dispositions
 * defined in {@code drafts/cycles-evidence-v0.1.yaml} (CyclesEvidenceJwks).
 *
 * <p>It reuses the PRODUCTION {@link CyclesEvidenceCanonicalizer} (re-derive
 * {@code evidence_id} + signing bytes) and {@link EnvelopeSigner} (Ed25519
 * verify), so this is exactly the verify path a consumer (e.g. the APS resolver)
 * must implement — a runnable reference for that side, exercised here against the
 * real golden fixtures + the published JWKS.
 *
 * <p>Scope: the v0.1 RAW-HEX {@code signer_did} path (what cycles-server
 * publishes today). {@code did:cycles} kid-resolution is the v0.2-store follow-up.
 */
public final class JwksAuthorityVerifier {

    public enum Disposition {
        /** Signature valid AND authority established (a window-covering key resolved). */
        AUTHENTIC,
        /** Signature valid, but signer authority not established (no JWKS resolved). */
        BINDING_ONLY,
        /** Set resolved+parsed, but no single authorized key (hash/window/absent/ambiguous). */
        SIGNER_AUTHORITY_FAILED,
        /** The DID method / JWK Set could not be obtained or parsed. */
        SIGNER_RESOLUTION_FAILED,
        /** The bytes do not verify against the resolved/named key (tamper). */
        SIGNATURE_INVALID
    }

    private final CyclesEvidenceCanonicalizer canonicalizer;
    private final EnvelopeSigner signer;

    public JwksAuthorityVerifier(CyclesEvidenceCanonicalizer canonicalizer, EnvelopeSigner signer) {
        this.canonicalizer = canonicalizer;
        this.signer = signer;
    }

    /**
     * Full authority verification. {@code jwks} is the resolved JWK Set, or
     * {@code null} when resolution (DID method / fetch / parse) failed.
     */
    public Disposition resolveAndVerify(ObjectNode envelope, JsonNode jwks) {
        // Resolution: did we OBTAIN a parseable key set? (Not "search it" — a
        // missing/ambiguous key in a fetched set is an AUTHORITY failure below.)
        if (jwks == null || !jwks.hasNonNull("keys") || !jwks.get("keys").isArray()) {
            return Disposition.SIGNER_RESOLUTION_FAILED;
        }

        String signerDid = envelope.get("signer_did").asText();
        byte[] wantKey = rawHexOrNull(signerDid);
        if (wantKey == null || !isIntegral(envelope.get("issued_at_ms"))) {
            // did:cycles/malformed signer_did, or a non-integral issued_at_ms we
            // can't window-evaluate — authority cannot be established.
            return Disposition.SIGNER_AUTHORITY_FAILED;
        }
        long issuedAt = envelope.get("issued_at_ms").asLong();

        // Deterministic selection: EXACTLY ONE valid, window-covering JWK whose x
        // decodes to the raw key. Zero / ambiguous → authority failure.
        String resolvedPubHex = null;
        int matches = 0;
        for (JsonNode jwk : jwks.get("keys")) {
            if (!isValidEd25519Jwk(jwk)) continue;
            byte[] x = decodeX(jwk);
            if (x == null || !Arrays.equals(x, wantKey)) continue;
            if (!windowCovers(jwk, issuedAt)) continue;
            matches++;
            resolvedPubHex = HexFormat.of().formatHex(x);
        }
        if (matches != 1) {
            return Disposition.SIGNER_AUTHORITY_FAILED;
        }
        return verifyBytes(envelope, resolvedPubHex);
    }

    /**
     * The v0.1 shippable posture: no JWKS authority resolved, so authority is not
     * established. The signature is checked against the named {@code signer_did}
     * and, when valid, the result is {@code BINDING_ONLY} — which per the spec
     * subsumes "valid signature, authority not asserted" whether or not a pin is
     * present (the {@code signer_pin_matched} companion records that separately):
     * {@code expectedSigner == null} ⇒ valid-but-unpinned binding_only;
     * a matching pin ⇒ pinned binding_only. A MISMATCHED pin means the bytes are
     * authentic to {@code signer_did} but not to the signer the caller trusts, so
     * authority to the expected signer is NOT established → {@code SIGNER_AUTHORITY_FAILED}.
     */
    public Disposition bindingOnly(ObjectNode envelope, String expectedSigner) {
        String signerDid = envelope.get("signer_did").asText();
        byte[] wantKey = rawHexOrNull(signerDid);
        if (wantKey == null || (expectedSigner != null && !expectedSigner.equalsIgnoreCase(signerDid))) {
            return Disposition.SIGNER_AUTHORITY_FAILED;
        }
        Disposition d = verifyBytes(envelope, signerDid);
        return d == Disposition.AUTHENTIC ? Disposition.BINDING_ONLY : d;
    }

    private Disposition verifyBytes(ObjectNode envelope, String pubHex) {
        // evidence_id must re-derive (content integrity) ...
        if (!canonicalizer.computeEvidenceId(envelope).equals(envelope.get("evidence_id").asText())) {
            return Disposition.SIGNATURE_INVALID;
        }
        // ... and the Ed25519 signature must verify over the signing input.
        byte[] signingBytes = canonicalizer.signingBytes(envelope, envelope.get("evidence_id").asText());
        boolean ok = signer.verify(signingBytes, envelope.get("signature").asText(), pubHex);
        return ok ? Disposition.AUTHENTIC : Disposition.SIGNATURE_INVALID;
    }

    private static byte[] rawHexOrNull(String signerDid) {
        if (signerDid == null || !signerDid.matches("[0-9a-fA-F]{64}")) return null;
        return HexFormat.of().parseHex(signerDid);
    }

    private static boolean isValidEd25519Jwk(JsonNode jwk) {
        if (!"OKP".equals(text(jwk, "kty")) || !"Ed25519".equals(text(jwk, "crv"))) return false;
        String alg = text(jwk, "alg");           // optional, but if present MUST be EdDSA
        if (alg != null && !"EdDSA".equals(alg)) return false;
        if (!jwk.hasNonNull("x") || !isIntegral(jwk.get("cycles_nbf_ms"))) return false;
        // cycles_exp_ms, if present and non-null, MUST be integral (not coercible to 0)
        JsonNode exp = jwk.get("cycles_exp_ms");
        if (exp != null && !exp.isNull() && !exp.isIntegralNumber()) return false;
        byte[] x = decodeX(jwk);                 // base64url, exactly 32 raw bytes
        return x != null && x.length == 32;
    }

    /** {@code cycles_nbf_ms <= issued_at_ms AND (cycles_exp_ms absent/null OR issued_at_ms < cycles_exp_ms)}.
     *  Assumes {@link #isValidEd25519Jwk} already vetted the members are integral. */
    private static boolean windowCovers(JsonNode jwk, long issuedAt) {
        if (issuedAt < jwk.get("cycles_nbf_ms").asLong()) return false;
        JsonNode exp = jwk.get("cycles_exp_ms");
        return exp == null || exp.isNull() || issuedAt < exp.asLong();
    }

    private static boolean isIntegral(JsonNode n) {
        return n != null && n.isIntegralNumber();
    }

    private static byte[] decodeX(JsonNode jwk) {
        try {
            return Base64.getUrlDecoder().decode(jwk.get("x").asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
