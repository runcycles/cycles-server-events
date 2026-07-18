package io.runcycles.events.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;

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
        CryptoService service = new CryptoService(generateKey(), false);
        String secret = "whsec_myTestSigningSecret123";

        String encrypted = service.encrypt(secret);
        String decrypted = service.decrypt(encrypted);

        assertThat(encrypted).startsWith("enc:");
        assertThat(encrypted).isNotEqualTo(secret);
        assertThat(decrypted).isEqualTo(secret);
    }

    @Test
    void encrypt_differentIVsProduceDifferentCiphertexts() {
        CryptoService service = new CryptoService(generateKey(), false);
        String secret = "whsec_test";

        String enc1 = service.encrypt(secret);
        String enc2 = service.encrypt(secret);

        assertThat(enc1).isNotEqualTo(enc2);
        assertThat(service.decrypt(enc1)).isEqualTo(secret);
        assertThat(service.decrypt(enc2)).isEqualTo(secret);
    }

    @Test
    void missingKeyFailsStartupByDefault() {
        assertThatThrownBy(() -> new CryptoService("", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOK_SECRET_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new CryptoService(null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOK_SECRET_ALLOW_PLAINTEXT");
    }

    @Test
    void allowPlaintextOptOutPassesThroughAndWarns() {
        Logger logger = (Logger) LoggerFactory.getLogger(CryptoService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CryptoService service = new CryptoService("", true);

            assertThat(service.encrypt("plain")).isEqualTo("plain");
            assertThat(service.decrypt("plain")).isEqualTo("plain");
            assertThat(service.isEnabled()).isFalse();
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("webhook signing secrets stored UNENCRYPTED");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void passThrough_nullValues() {
        CryptoService service = new CryptoService(generateKey(), false);

        assertThat(service.encrypt(null)).isNull();
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    void plaintextToEncryptedMigrationReadsLegacyAndWritesEncrypted() {
        CryptoService service = new CryptoService(generateKey(), false);

        String legacyPlaintext = service.decrypt("whsec_old");
        String migratedWrite = service.encrypt(legacyPlaintext);

        assertThat(legacyPlaintext).isEqualTo("whsec_old");
        assertThat(migratedWrite).startsWith("enc:");
        assertThat(service.decrypt(migratedWrite)).isEqualTo("whsec_old");
    }

    @Test
    void constructor_invalidKeyLength_throws() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new CryptoService(shortKey, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isEnabled_trueWithKey() {
        assertThat(new CryptoService(generateKey(), false).isEnabled()).isTrue();
    }

    @Test
    void decrypt_encryptedValueWithNoKey_throws() {
        CryptoService noKey = new CryptoService("", true);
        String encrypted = "enc:someBase64Data";

        assertThatThrownBy(() -> noKey.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOK_SECRET_ENCRYPTION_KEY");
    }

    @Test
    void decrypt_wrongKey_throws() {
        CryptoService encryptor = new CryptoService(generateKey(), false);
        CryptoService wrongKey = new CryptoService(generateKey(), false);

        String encrypted = encryptor.encrypt("secret");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> wrongKey.decrypt(encrypted));
    }
}
