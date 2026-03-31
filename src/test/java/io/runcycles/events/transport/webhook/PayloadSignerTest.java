package io.runcycles.events.transport.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadSignerTest {

    private final PayloadSigner signer = new PayloadSigner();

    @Test
    void sign_producesValidHmacSha256() throws Exception {
        String payload = "{\"event_id\":\"evt-1\",\"event_type\":\"tenant.created\"}";
        String secret = "my-webhook-secret";

        String signature = signer.sign(payload, secret);

        // Compute expected HMAC-SHA256
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder expected = new StringBuilder("sha256=");
        for (byte b : hash) {
            expected.append(String.format("%02x", b));
        }

        assertThat(signature).isEqualTo(expected.toString());
        assertThat(signature).startsWith("sha256=");
        assertThat(signature).hasSize(7 + 64); // "sha256=" + 64 hex chars
    }

    @Test
    void sign_differentPayloads_differentSignatures() {
        String sig1 = signer.sign("payload-a", "secret");
        String sig2 = signer.sign("payload-b", "secret");

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    void sign_differentSecrets_differentSignatures() {
        String sig1 = signer.sign("payload", "secret-1");
        String sig2 = signer.sign("payload", "secret-2");

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    void sign_emptyPayload() {
        String signature = signer.sign("", "secret");

        assertThat(signature).startsWith("sha256=");
        assertThat(signature).hasSize(7 + 64);
    }

    @Test
    void sign_deterministic() {
        String sig1 = signer.sign("test", "key");
        String sig2 = signer.sign("test", "key");

        assertThat(sig1).isEqualTo(sig2);
    }
}
