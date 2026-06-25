package io.runcycles.events.evidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Config-backed {@link EvidenceSigningKey} — holds the server's Ed25519 signing
 * key locally, as a sibling to the dispatch workers in the event tier (the
 * pragmatic starting point; a dedicated signing service can replace it behind
 * the {@link EvidenceSigningKey} seam once key rotation lands,
 * cycles-protocol#103).
 *
 * <p>Provisioning via {@code cycles.evidence.signing.*}:
 * <ul>
 *   <li><b>both</b> {@code private-key-hex} (32-byte seed) and {@code signer-did}
 *       (32-byte public key), each 64 hex chars → used as the server identity,
 *       validated at startup by a sign/verify probe;</li>
 *   <li><b>neither</b> → an EPHEMERAL keypair is generated and a warning logged
 *       (development only — the identity does not survive a restart);</li>
 *   <li><b>exactly one</b> → startup fails (half-configured).</li>
 * </ul>
 *
 * <p>The raw private key never leaves this component.
 */
@Component
@ConditionalOnEvidenceConfigured
public class LocalEvidenceSigningKey implements EvidenceSigningKey {

    private static final Logger log = LoggerFactory.getLogger(LocalEvidenceSigningKey.class);
    private static final byte[] PROBE = "cycles-evidence-signing-key-probe".getBytes(StandardCharsets.UTF_8);

    private final EnvelopeSigner signer;
    private final String privateKeyHex;
    private final String signerDid;

    public LocalEvidenceSigningKey(
            EnvelopeSigner signer,
            @Value("${cycles.evidence.signing.private-key-hex:}") String privateKeyHex,
            @Value("${cycles.evidence.signing.signer-did:}") String signerDid) {
        this.signer = signer;
        boolean hasPriv = privateKeyHex != null && !privateKeyHex.isBlank();
        boolean hasDid = signerDid != null && !signerDid.isBlank();

        if (hasPriv && hasDid) {
            this.privateKeyHex = requireHex(privateKeyHex.trim(), "cycles.evidence.signing.private-key-hex");
            this.signerDid = requireHex(signerDid.trim(), "cycles.evidence.signing.signer-did");
            if (!pairConsistent(this.privateKeyHex, this.signerDid)) {
                throw new IllegalStateException(
                        "configured evidence signing key and signer-did do not form a valid Ed25519 pair");
            }
            log.info("Evidence signing key loaded from configuration: signer_did={} key_mode=configured",
                    this.signerDid);
        } else if (!hasPriv && !hasDid) {
            KeyHex generated = generateEphemeral();
            this.privateKeyHex = generated.privateHex();
            this.signerDid = generated.publicHex();
            log.warn("Evidence signing key is ephemeral: signer_did={} key_mode=ephemeral production_safe=false reason=cycles.evidence.signing.private-key-hex_and_signer-did_not_configured",
                    this.signerDid);
        } else {
            throw new IllegalStateException("evidence signing is half-configured: set BOTH "
                    + "cycles.evidence.signing.private-key-hex and cycles.evidence.signing.signer-did, or neither");
        }
    }

    @Override
    public String signerDid() {
        return signerDid;
    }

    @Override
    public String sign(byte[] signingInput) {
        return signer.sign(signingInput, privateKeyHex);
    }

    /** A configured key+did are consistent iff a probe signed by the key
     *  verifies against the did. */
    private boolean pairConsistent(String privHex, String didHex) {
        try {
            return signer.verify(PROBE, signer.sign(PROBE, privHex), didHex);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String requireHex(String value, String name) {
        boolean ok = value.length() == 64 && value.chars().allMatch(LocalEvidenceSigningKey::isHexDigit);
        if (!ok) {
            throw new IllegalStateException(name + " must be 64 hex characters (a 32-byte Ed25519 key)");
        }
        return value;
    }

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static KeyHex generateEphemeral() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new KeyHex(rawTailHex(kp.getPrivate().getEncoded()), rawTailHex(kp.getPublic().getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 key generation failed", e);
        }
    }

    /** The raw 32-byte key is the tail of the DER-encoded form. */
    private static String rawTailHex(byte[] der) {
        return HexFormat.of().formatHex(Arrays.copyOfRange(der, der.length - 32, der.length));
    }

    private record KeyHex(String privateHex, String publicHex) {
    }
}
