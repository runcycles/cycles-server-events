package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full Redis round-trip: a source record LPUSH'd to {@code evidence:pending} is
 * picked up by the scheduled {@link EvidenceWorker}, built and signed against a
 * configured Ed25519 key, and PERSISTED to the durable store — this test reads
 * it back from Redis and verifies the signature. Exercises the real BRPOP loop,
 * builder, signer, and store against real Redis (Testcontainers), not mocks.
 *
 * <p>Requires Docker. Excluded from unit runs by the {@code *IntegrationTest}
 * naming convention.
 */
@Testcontainers
@SpringBootTest
class EvidenceWorkerIntegrationTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final KeyHex KEY = freshKeyPair();
    private static final String SERVER_ID = "https://cycles.example.com/v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("redis.host", redis::getHost);
        r.add("redis.port", () -> redis.getMappedPort(6379));
        r.add("cycles.evidence.signing.private-key-hex", KEY::priv);
        r.add("cycles.evidence.signing.signer-did", KEY::pub);
        r.add("cycles.evidence.server-id", () -> SERVER_ID);
        r.add("cycles.evidence.queue.timeout-seconds", () -> "1");
    }

    @Autowired
    private JedisPool jedisPool;

    @Test
    void reserveSourceRecordIsBuiltSignedAndStored() throws Exception {
        ObjectNode rec = MAPPER.createObjectNode();
        rec.put("artifact_type", "reserve");
        rec.put("issued_at_ms", 1810000000100L);
        rec.put("trace_id", "0af7651916cd43dd8448eb211c80319c");
        ObjectNode payload = rec.putObject("payload");
        payload.putObject("request").put("idempotency_key", "k1");
        payload.putObject("response").put("decision", "ALLOW");

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lpush("evidence:pending", MAPPER.writeValueAsString(rec));
        }

        String envJson = pollForStoredEnvelope(15_000);
        assertThat(envJson).as("envelope persisted to the store within 15s").isNotNull();

        ObjectNode env = (ObjectNode) MAPPER.readTree(envJson);
        assertThat(env.get("artifact_type").asText()).isEqualTo("reserve");
        assertThat(env.get("server_id").asText()).isEqualTo(SERVER_ID);
        assertThat(env.get("signer_did").asText()).isEqualTo(KEY.pub());
        assertThat(env.path("payload").path("reserve").path("response").path("decision").asText())
                .isEqualTo("ALLOW");

        // the stored envelope is content-addressed and its signature verifies
        CyclesEvidenceCanonicalizer canon = new CyclesEvidenceCanonicalizer();
        EnvelopeSigner signer = new EnvelopeSigner();
        String evidenceId = env.get("evidence_id").asText();
        assertThat(canon.computeEvidenceId(env)).isEqualTo(evidenceId);
        byte[] signingBytes = canon.signingBytes(env, evidenceId);
        assertThat(signer.verify(signingBytes, env.get("signature").asText(), KEY.pub())).isTrue();
    }

    private String pollForStoredEnvelope(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Jedis jedis = jedisPool.getResource()) {
                Set<String> keys = jedis.keys("evidence:envelope:*");
                if (!keys.isEmpty()) {
                    return jedis.get(keys.iterator().next());
                }
            }
            Thread.sleep(100);
        }
        return null;
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
