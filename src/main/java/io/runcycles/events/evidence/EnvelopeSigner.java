package io.runcycles.events.evidence;

import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

/**
 * Ed25519 signing and verification for CyclesEvidence envelopes, using the JDK
 * native EdDSA provider (JDK 15+). No third-party crypto dependency.
 *
 * <p>Keys and signatures are lowercase hex matching {@code cycles-evidence-v0.1}:
 * {@code signer_did} is a raw 32-byte Ed25519 public key (64 hex chars) and
 * {@code signature} is the 64-byte signature (128 hex chars). Raw keys are
 * wrapped in the fixed Ed25519 DER prefixes (SubjectPublicKeyInfo / PKCS#8) so
 * the JDK {@code KeyFactory} can decode them — the same wrapping the APS
 * verifier uses, ensuring interop.
 */
@Component
public class EnvelopeSigner {

    // DER prefix for an Ed25519 SubjectPublicKeyInfo wrapping a raw 32-byte key.
    private static final byte[] PUBLIC_DER_PREFIX =
            HexFormat.of().parseHex("302a300506032b6570032100");
    // DER prefix for an Ed25519 PKCS#8 PrivateKeyInfo wrapping a raw 32-byte seed.
    private static final byte[] PRIVATE_DER_PREFIX =
            HexFormat.of().parseHex("302e020100300506032b657004220420");

    /**
     * Verify a hex Ed25519 {@code signature} over {@code message} against a hex
     * public key ({@code signer_did}). Returns {@code false} — never throws —
     * on a malformed key/signature or a verification failure (fail-closed).
     */
    public boolean verify(byte[] message, String signatureHex, String publicKeyHex) {
        if (publicKeyHex == null || publicKeyHex.length() != 64) {
            return false;
        }
        if (signatureHex == null || signatureHex.length() != 128) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKeyFromHex(publicKeyHex));
            verifier.update(message);
            return verifier.verify(HexFormat.of().parseHex(signatureHex));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Sign {@code message} with a raw hex Ed25519 private key (32-byte seed),
     *  returning the 64-byte signature as lowercase hex. */
    public String sign(byte[] message, String privateKeyHex) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKeyFromHex(privateKeyHex));
            signer.update(message);
            return HexFormat.of().formatHex(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    private PublicKey publicKeyFromHex(String hex) throws GeneralSecurityException {
        byte[] der = concat(PUBLIC_DER_PREFIX, HexFormat.of().parseHex(hex));
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    private PrivateKey privateKeyFromHex(String hex) throws GeneralSecurityException {
        byte[] der = concat(PRIVATE_DER_PREFIX, HexFormat.of().parseHex(hex));
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
