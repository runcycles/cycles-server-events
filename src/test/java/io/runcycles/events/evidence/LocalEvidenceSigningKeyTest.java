package io.runcycles.events.evidence;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalEvidenceSigningKeyTest {

    private final EnvelopeSigner signer = new EnvelopeSigner();

    @Test
    void rejectsMissingKeyByDefault() {
        assertThatThrownBy(() -> new LocalEvidenceSigningKey(signer, "", "", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidence signing key is required");
    }

    @Test
    void ephemeralWhenExplicitlyAllowed() {
        LocalEvidenceSigningKey key = new LocalEvidenceSigningKey(signer, "", "", true);

        assertThat(key.signerDid()).hasSize(64);
        // a signature it produces verifies against the did it advertises
        byte[] msg = "envelope-bytes".getBytes();
        assertThat(signer.verify(msg, key.sign(msg), key.signerDid())).isTrue();
    }

    @Test
    void usesConfiguredKeyPair() {
        KeyHex kp = freshKeyPair();
        LocalEvidenceSigningKey key = new LocalEvidenceSigningKey(signer, kp.priv(), kp.pub(), false);

        assertThat(key.signerDid()).isEqualTo(kp.pub());
        byte[] msg = "envelope-bytes".getBytes();
        assertThat(signer.verify(msg, key.sign(msg), kp.pub())).isTrue();
    }

    @Test
    void normalizesConfiguredHexToLowercaseWireIdentity() {
        KeyHex kp = freshKeyPair();
        LocalEvidenceSigningKey key = new LocalEvidenceSigningKey(
                signer, kp.priv().toUpperCase(java.util.Locale.ROOT),
                kp.pub().toUpperCase(java.util.Locale.ROOT), false);

        assertThat(key.signerDid()).isEqualTo(kp.pub());
        assertThat(key.signerDid()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsMismatchedPair() {
        String priv = freshKeyPair().priv();
        String unrelatedDid = freshKeyPair().pub();
        assertThatThrownBy(() -> new LocalEvidenceSigningKey(signer, priv, unrelatedDid, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid Ed25519 pair");
    }

    @Test
    void rejectsHalfConfiguredKeyOnly() {
        String priv = freshKeyPair().priv();
        assertThatThrownBy(() -> new LocalEvidenceSigningKey(signer, priv, "", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("half-configured");
    }

    @Test
    void rejectsHalfConfiguredDidOnly() {
        String did = freshKeyPair().pub();
        assertThatThrownBy(() -> new LocalEvidenceSigningKey(signer, "", did, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("half-configured");
    }

    @Test
    void rejectsMalformedHex() {
        String did = freshKeyPair().pub();
        assertThatThrownBy(() -> new LocalEvidenceSigningKey(signer, "not-hex", did, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64 hex characters");
    }

    @Test
    void nullConfigurationValuesFollowTheSameMissingAndHalfConfiguredRules() {
        String priv = freshKeyPair().priv();
        String did = freshKeyPair().pub();
        assertThatThrownBy(() -> new LocalEvidenceSigningKey(signer, null, null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> new LocalEvidenceSigningKey(signer, null, did, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("half-configured");
        assertThatThrownBy(() -> new LocalEvidenceSigningKey(signer, priv, null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("half-configured");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/", ":", "@", "G", "`", "g"})
    void rejectsEveryNonHexCharacterClass(String invalidCharacter) {
        String malformed = invalidCharacter.repeat(64);
        assertThatThrownBy(() -> new LocalEvidenceSigningKey(
                signer, malformed, freshKeyPair().pub(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64 hex characters");
    }

    private record KeyHex(String priv, String pub) {
    }

    private static KeyHex freshKeyPair() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new KeyHex(tail32(kp.getPrivate().getEncoded()), tail32(kp.getPublic().getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String tail32(byte[] der) {
        return HexFormat.of().formatHex(Arrays.copyOfRange(der, der.length - 32, der.length));
    }
}
