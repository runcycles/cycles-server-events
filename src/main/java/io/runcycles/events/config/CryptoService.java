package io.runcycles.events.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption and decryption for webhook signing secrets at rest.
 * Missing key material fails startup unless an explicit development/backward-
 * compatibility opt-out enables plaintext pass-through. Existing plaintext
 * values remain readable after a key is configured, while all subsequent writes
 * use the {@code enc:} format. Encrypted values fail closed because delivering
 * an unsigned webhook when a stored signing secret cannot be decrypted breaks
 * authenticity.
 */
@Component
public class CryptoService {

    private static final Logger LOG = LoggerFactory.getLogger(CryptoService.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String ENCRYPTED_PREFIX = "enc:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public CryptoService(
            @Value("${webhook.secret.encryption-key:}") String base64Key,
            @Value("${webhook.secret.allow-plaintext:false}") boolean allowPlaintext) {
        if (base64Key == null || base64Key.isBlank()) {
            if (!allowPlaintext) {
                throw new IllegalStateException(
                        "WEBHOOK_SECRET_ENCRYPTION_KEY must be configured; "
                        + "set WEBHOOK_SECRET_ALLOW_PLAINTEXT=true only for local/development compatibility");
            }
            this.key = null;
            LOG.warn("SECURITY WARNING: webhook signing secrets stored UNENCRYPTED "
                    + "because webhook.secret.allow-plaintext=true");
        } else {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("Encryption key must be 32 bytes (256 bits), got " + keyBytes.length);
            }
            this.key = new SecretKeySpec(keyBytes, "AES");
            LOG.info("Webhook secret encryption enabled (AES-256-GCM)");
        }
    }

    public String encrypt(String plaintext) {
        if (key == null || plaintext == null) return plaintext;
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = ByteBuffer.allocate(IV_LENGTH + ciphertext.length)
                    .put(iv).put(ciphertext).array();
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt secret", e);
        }
    }

    public String decrypt(String value) {
        if (value == null) return null;
        if (!value.startsWith(ENCRYPTED_PREFIX)) {
            return value; // Plaintext (backward compatible)
        }
        if (key == null) {
            throw new IllegalStateException(
                    "Webhook secret is encrypted but WEBHOOK_SECRET_ENCRYPTION_KEY is not configured");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(value.substring(ENCRYPTED_PREFIX.length()));
            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt secret", e);
        }
    }

    public boolean isEnabled() {
        return key != null;
    }
}
