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
 * it back from Redis and verifies the signature. Exercises the real BLMOVE loop,
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
            new GenericContainer<>(DockerImageName.parse(
                    "redis:7-alpine@sha256:6ab0b6e7381779332f97b8ca76193e45b0756f38d4c0dcda72dbb3c32061ab99"))
                    .withExposedPorts(6379);

    private static final KeyHex KEY = freshKeyPair();
    private static final String SERVER_ID = "https://cycles.example.com/v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("redis.host", redis::getHost);
        r.add("redis.port", () -> redis.getMappedPort(6379));
        r.add("webhook.secret.allow-plaintext", () -> "true");
        r.add("cycles.evidence.signing.private-key-hex", KEY::priv);
        r.add("cycles.evidence.signing.signer-did", KEY::pub);
        r.add("cycles.evidence.server-id", () -> SERVER_ID);
        r.add("cycles.evidence.queue.timeout-seconds", () -> "1");
    }

    @Autowired
    private JedisPool jedisPool;

    @Test
    void reserveSourceRecordIsBuiltSignedAndStored() throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        putDecisionRequest(payload.putObject("request"), "k1");
        ObjectNode response = payload.putObject("response");
        response.put("decision", "ALLOW");
        response.putArray("affected_scopes").add("tenant:acme");
        response.put("reservation_id", "res_reserve_1");

        ObjectNode env = roundTrip("reserve", payload);

        assertThat(env.path("payload").path("reserve").path("response").path("decision").asText())
                .isEqualTo("ALLOW");
    }

    @Test
    void decideSourceRecordIsBuiltSignedAndStored() throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        putDecisionRequest(payload.putObject("request"), "d1");
        payload.putObject("response").put("decision", "ALLOW");

        ObjectNode env = roundTrip("decide", payload);

        assertThat(env.path("payload").path("decide").path("response").path("decision").asText())
                .isEqualTo("ALLOW");
    }

    @Test
    void commitSourceRecordHoistsReservationIdAndIsSignedAndStored() throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservation_id", "res_commit_1");
        ObjectNode request = payload.putObject("request");
        request.put("idempotency_key", "c1");
        putAmount(request.putObject("actual"), 750);
        ObjectNode response = payload.putObject("response");
        response.put("status", "COMMITTED");
        putAmount(response.putObject("charged"), 750);

        ObjectNode env = roundTrip("commit", payload);

        // reservation_id is hoisted into the signed payload for commit/release
        assertThat(env.path("payload").path("commit").path("reservation_id").asText())
                .isEqualTo("res_commit_1");
    }

    @Test
    void releaseSourceRecordHoistsReservationIdAndIsSignedAndStored() throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservation_id", "res_release_1");
        payload.putObject("request").put("idempotency_key", "r1");
        ObjectNode response = payload.putObject("response");
        response.put("status", "RELEASED");
        putAmount(response.putObject("released"), 1000);

        ObjectNode env = roundTrip("release", payload);

        assertThat(env.path("payload").path("release").path("reservation_id").asText())
                .isEqualTo("res_release_1");
    }

    @Test
    void errorSourceRecordCarriesEndpointAndStatusAndIsSignedAndStored() throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("endpoint", "POST /v1/reservations");
        payload.put("http_status", 409);
        ObjectNode response = payload.putObject("response");
        response.put("error", "BUDGET_EXCEEDED");
        response.put("message", "Budget exceeded for: tenant:acme");
        response.put("request_id", "req_e1");

        ObjectNode env = roundTrip("error", payload);

        assertThat(env.path("payload").path("error").path("endpoint").asText())
                .isEqualTo("POST /v1/reservations");
        assertThat(env.path("payload").path("error").path("http_status").asInt()).isEqualTo(409);
    }

    /**
     * Drive one source record (as cycles-server's {@code EvidenceEmitter} would
     * LPUSH it) through the LIVE worker against real Redis and assert the stored
     * envelope is well-formed, content-addressed, and its Ed25519 signature
     * verifies against the configured key — the signer-tier end-to-end guarantee
     * for the given artifact type. Returns the stored envelope for type-specific
     * assertions.
     */
    private ObjectNode roundTrip(String artifactType, ObjectNode payloadBody) throws Exception {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll(); // isolate from other parametrized cases
        }
        ObjectNode rec = MAPPER.createObjectNode();
        rec.put("artifact_type", artifactType);
        rec.put("issued_at_ms", 1810000000100L);
        rec.put("trace_id", "0af7651916cd43dd8448eb211c80319c");
        rec.set("payload", payloadBody);

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lpush("evidence:pending", MAPPER.writeValueAsString(rec));
        }

        String envJson = pollForStoredEnvelope(15_000);
        assertThat(envJson).as("%s envelope persisted to the store within 15s", artifactType).isNotNull();
        assertThat(pollUntilProcessingQueueDrained(5_000))
                .as("%s source record acknowledged from the real Redis processing queue", artifactType)
                .isTrue();

        ObjectNode env = (ObjectNode) MAPPER.readTree(envJson);
        assertThat(env.get("artifact_type").asText()).isEqualTo(artifactType);
        assertThat(env.get("server_id").asText()).isEqualTo(SERVER_ID);
        assertThat(env.get("signer_did").asText()).isEqualTo(KEY.pub());
        // artifact_type <-> payload-key pairing: the body is nested under the type
        assertThat(env.path("payload").has(artifactType))
                .as("payload nested under artifact_type %s", artifactType).isTrue();

        // content-addressed id recomputes, and the Ed25519 signature verifies
        CyclesEvidenceCanonicalizer canon = new CyclesEvidenceCanonicalizer();
        EnvelopeSigner signer = new EnvelopeSigner();
        String evidenceId = env.get("evidence_id").asText();
        assertThat(evidenceId).as("evidence_id is 64-hex").matches("^[0-9a-f]{64}$");
        assertThat(canon.computeEvidenceId(env)).isEqualTo(evidenceId);
        assertThat(envJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .as("stored evidence bytes are RFC 8785 canonical")
                .isEqualTo(canon.canonicalize(env));
        byte[] signingBytes = canon.signingBytes(env, evidenceId);
        assertThat(signer.verify(signingBytes, env.get("signature").asText(), KEY.pub())).isTrue();
        return env;
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

    private boolean pollUntilProcessingQueueDrained(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Jedis jedis = jedisPool.getResource()) {
                if (jedis.llen("evidence:processing") == 0
                        && jedis.zcard("evidence:processing:claimed_at") == 0) {
                    return true;
                }
            }
            Thread.sleep(25);
        }
        return false;
    }

    private record KeyHex(String priv, String pub) {
    }

    private static void putDecisionRequest(ObjectNode request, String idempotencyKey) {
        request.put("idempotency_key", idempotencyKey);
        request.putObject("subject").put("tenant", "acme");
        ObjectNode action = request.putObject("action");
        action.put("kind", "model.call");
        action.put("name", "test-model");
        putAmount(request.putObject("estimate"), 1000);
    }

    private static void putAmount(ObjectNode amount, long value) {
        amount.put("unit", "USD_MICROCENTS");
        amount.put("amount", value);
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
