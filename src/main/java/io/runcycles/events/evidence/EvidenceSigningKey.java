package io.runcycles.events.evidence;

/**
 * The server's evidence-signing identity — a clean seam over the Ed25519
 * signing key.
 *
 * <p>Envelope construction depends only on this interface, never on where the
 * key lives, so the local config-backed implementation
 * ({@link LocalEvidenceSigningKey}) can later be swapped for a KMS/HSM- or
 * dedicated-signing-service-backed one without touching the emit path. Signing
 * key rotation / {@code did:cycles} resolution (cycles-protocol#103) will
 * extend this seam rather than the call sites.
 *
 * <p>The raw private key never crosses this boundary: implementations expose
 * only the public {@link #signerDid()} and a {@link #sign(byte[])} operation.
 */
public interface EvidenceSigningKey {

    /**
     * The {@code signer_did} to stamp on emitted envelopes. In
     * cycles-evidence-v0.1 this is the raw 32-byte Ed25519 public key as
     * lowercase hex (64 chars).
     */
    String signerDid();

    /**
     * Sign the canonical signing-input bytes (JCS of the envelope with
     * {@code evidence_id} populated and {@code signature} emptied), returning
     * the 64-byte Ed25519 signature as lowercase hex.
     */
    String sign(byte[] signingInput);
}
