// EvidenceKeygen — operator helper for CyclesEvidence identity enablement (v0.1).
//
// Generates an Ed25519 signing keypair and prints the three environment
// variables that turn CyclesEvidence on, in the EXACT formats the reference
// signer validates (see LocalEvidenceSigningKey): each key is the raw 32-byte
// tail of its DER encoding, lowercase-hex (64 chars), and the pair is checked
// with a sign/verify probe before anything is printed — so it cannot emit
// values the worker would reject at startup.
//
// This is the v0.1 RAW-HEX signer_did path. It needs nothing from anyone but
// you: you supply your own server_id (this deployment's canonical base URL),
// you keep the private key. There is no hosted Cycles and no central key.
//
// Run (JDK 17+, no build, no dependencies):
//
//     java tools/EvidenceKeygen.java https://cycles.example.com/v1
//
// SECURITY: the last line of output (EVIDENCE_SIGNING_PRIVATE_KEY_HEX) is your
// signing SECRET. Run this on a trusted host, capture it straight into your
// secret manager, and never commit it or paste it into chat/logs/tickets.
//
// See docs/evidence-identity-enablement.md for what to do with the output.

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

public class EvidenceKeygen {

    // The fixed Ed25519 DER prefixes EnvelopeSigner re-wraps the raw keys with.
    // We probe the EMITTED hex through these so the validation covers exactly
    // the bytes we print — not provider-internal key objects.
    private static final byte[] PUBLIC_DER_PREFIX =
            HexFormat.of().parseHex("302a300506032b6570032100");
    private static final byte[] PRIVATE_DER_PREFIX =
            HexFormat.of().parseHex("302e020100300506032b657004220420");

    public static void main(String[] args) throws GeneralSecurityException {
        // server_id is the operator's own canonical deployment base URL,
        // INCLUDING /v1 (e.g. https://cycles.example.com/v1). Optional here:
        // if omitted we print a clearly-marked placeholder so the key still
        // gets generated, but the value must be byte-identical on both services.
        String serverId = args.length > 0 ? args[0].trim() : "";
        boolean serverIdGiven = !serverId.isEmpty();

        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        // The raw 32-byte key is the tail of the DER-encoded form — the same
        // extraction LocalEvidenceSigningKey.rawTailHex uses, so these values
        // load without transformation.
        String privateKeyHex = rawTailHex(kp.getPrivate().getEncoded()); // 32-byte seed
        String signerDid      = rawTailHex(kp.getPublic().getEncoded());  // 32-byte pubkey

        // Self-probe the EMITTED hex: reconstruct both keys from the printed
        // hex via the same fixed DER prefixes EnvelopeSigner uses, then run the
        // sign/verify check LocalEvidenceSigningKey.pairConsistent runs. This
        // validates the actual bytes we hand over — independent of which
        // provider/encoding generated them — so we never emit a pair the worker
        // would reject at startup.
        if (privateKeyHex.length() != 64
                || signerDid.length() != 64
                || !probeHex(privateKeyHex, signerDid)) {
            System.err.println("ERROR: generated key failed self-validation; not emitting. Re-run.");
            System.exit(1);
        }

        String serverIdValue = serverIdGiven ? serverId : "<SET-THIS-to-your-deployment-base-url-including-/v1>";

        StringBuilder out = new StringBuilder();
        out.append("# ----------------------------------------------------------------------\n");
        out.append("# CyclesEvidence identity (v0.1, raw-hex signer_did). Generated locally.\n");
        out.append("# Public identity (EVIDENCE_SERVER_ID + EVIDENCE_SIGNING_SIGNER_DID) goes on\n");
        out.append("# BOTH cycles-server AND cycles-server-events, byte-identical.\n");
        out.append("# EVIDENCE_SIGNING_PRIVATE_KEY_HEX is SECRET and goes ONLY on cycles-server-events.\n");
        out.append("# ----------------------------------------------------------------------\n");
        out.append("EVIDENCE_SERVER_ID=").append(serverIdValue).append('\n');
        out.append("EVIDENCE_SIGNING_SIGNER_DID=").append(signerDid).append('\n');
        out.append("# v vv SECRET — events worker only, capture into your secret manager v vv\n");
        out.append("EVIDENCE_SIGNING_PRIVATE_KEY_HEX=").append(privateKeyHex).append('\n');
        System.out.print(out);

        if (!serverIdGiven) {
            System.err.println();
            System.err.println("NOTE: no server_id argument supplied — EVIDENCE_SERVER_ID is a PLACEHOLDER.");
            System.err.println("      Set it to this deployment's canonical base URL (including /v1) and use");
            System.err.println("      the same value on both services. Re-running this tool makes a NEW key.");
        }
    }

    /** The raw 32-byte key is the tail of the DER-encoded form. */
    private static String rawTailHex(byte[] der) {
        return HexFormat.of().formatHex(Arrays.copyOfRange(der, der.length - 32, der.length));
    }

    /** Reconstruct the keys from the emitted seed/pubkey hex the way
     *  EnvelopeSigner does (fixed Ed25519 DER prefixes), sign a fixed probe
     *  with the reconstructed private key and verify with the reconstructed
     *  public key — the same consistency guarantee the worker enforces at
     *  startup, applied to exactly the bytes we print. */
    private static boolean probeHex(String seedHex, String didHex) {
        byte[] message = "cycles-evidence-signing-key-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            PrivateKey priv = KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(concat(PRIVATE_DER_PREFIX, HexFormat.of().parseHex(seedHex))));
            PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(concat(PUBLIC_DER_PREFIX, HexFormat.of().parseHex(didHex))));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(priv);
            s.update(message);
            byte[] sig = s.sign();
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(pub);
            v.update(message);
            return v.verify(sig);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
