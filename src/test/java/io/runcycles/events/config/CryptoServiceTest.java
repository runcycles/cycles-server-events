package io.runcycles.events.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private static String generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void encryptDecrypt_roundTrip() {
        CryptoService service = new CryptoService(generateKey());
        String secret = "whsec_myTestSigningSecret123";

        String encrypted = service.encrypt(secret);
        String decrypted = service.decrypt(encrypted);

        assertThat(encrypted).startsWith("enc:");
        assertThat(encrypted).isNotEqualTo(secret);
        assertThat(decrypted).isEqualTo(secret);
    }

    @Test
    void encrypt_differentIVsProduceDifferentCiphertexts() {
        CryptoService service = new CryptoService(generateKey());
        String secret = "whsec_test";

        String enc1 = service.encrypt(secret);
        String enc2 = service.encrypt(secret);

        assertThat(enc1).isNotEqualTo(enc2);
        assertThat(service.decrypt(enc1)).isEqualTo(secret);
        assertThat(service.decrypt(enc2)).isEqualTo(secret);
    }

    @Test
    void passThrough_whenNoKey() {
        CryptoService service = new CryptoService("");

        assertThat(service.encrypt("plain")).isEqualTo("plain");
        assertThat(service.decrypt("plain")).isEqualTo("plain");
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void passThrough_nullValues() {
        CryptoService service = new CryptoService(generateKey());

        assertThat(service.encrypt(null)).isNull();
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    void decrypt_plaintextBackwardCompat() {
        CryptoService service = new CryptoService(generateKey());

        assertThat(service.decrypt("whsec_old")).isEqualTo("whsec_old");
    }

    @Test
    void constructor_invalidKeyLength_throws() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new CryptoService(shortKey))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isEnabled_trueWithKey() {
        assertThat(new CryptoService(generateKey()).isEnabled()).isTrue();
    }

    @Test
    void decrypt_encryptedValueWithNoKey_returnsRaw() {
        CryptoService noKey = new CryptoService("");
        String encrypted = "enc:someBase64Data";

        // No key = can't decrypt, returns raw value with warning
        assertThat(noKey.decrypt(encrypted)).isEqualTo(encrypted);
    }

    @Test
    void decrypt_wrongKey_throws() {
        CryptoService encryptor = new CryptoService(generateKey());
        CryptoService wrongKey = new CryptoService(generateKey());

        String encrypted = encryptor.encrypt("secret");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> wrongKey.decrypt(encrypted));
    }
}
