package io.runcycles.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full pipeline integration test: Redis → DispatchLoop → DeliveryHandler → WebhookTransport → HTTP.
 *
 * Uses Testcontainers for Redis and an embedded HTTP server as webhook receiver.
 * Simulates what admin/runtime servers do (write to Redis), then verifies the
 * events service picks up deliveries and delivers them with correct HMAC signatures.
 *
 * Requires Docker. Excluded from unit tests by naming convention (*IntegrationTest).
 * Run with: mvn test -Dtest="WebhookDeliveryIntegrationTest"
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebhookDeliveryIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static HttpServer webhookServer;
    static int webhookPort;
    static final CopyOnWriteArrayList<Map<String, String>> receivedWebhooks = new CopyOnWriteArrayList<>();
    static CountDownLatch deliveryLatch;

    static final String SIGNING_SECRET = "integration-test-secret";
    static final String SUBSCRIPTION_ID = "whsub_inttest001";
    static final String TENANT_ID = "test-tenant";

    @Autowired
    private JedisPool jedisPool;

    private static final ObjectMapper objectMapper = new ObjectMapper() {{
        registerModule(new JavaTimeModule());
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }};

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        registry.add("redis.host", redis::getHost);
        registry.add("redis.port", () -> redis.getMappedPort(6379));
        registry.add("redis.password", () -> "");
        registry.add("webhook.secret.encryption-key", () -> "");
        registry.add("dispatch.pending.timeout-seconds", () -> "1");
        registry.add("dispatch.retry.poll-interval-ms", () -> "1000");
        registry.add("dispatch.max-delivery-age-ms", () -> "86400000");
        registry.add("events.retention.event-ttl-days", () -> "1");
        registry.add("events.retention.delivery-ttl-days", () -> "1");
        registry.add("events.retention.cleanup-interval-ms", () -> "3600000");
    }

    @BeforeAll
    static void startWebhookReceiver() throws Exception {
        webhookServer = HttpServer.create(new InetSocketAddress(0), 0);
        webhookPort = webhookServer.getAddress().getPort();
        webhookServer.createContext("/webhook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<String, String> captured = new HashMap<>();
            captured.put("body", new String(body, StandardCharsets.UTF_8));
            captured.put("signature", exchange.getRequestHeaders().getFirst("X-Cycles-Signature"));
            captured.put("event_id", exchange.getRequestHeaders().getFirst("X-Cycles-Event-Id"));
            captured.put("event_type", exchange.getRequestHeaders().getFirst("X-Cycles-Event-Type"));
            captured.put("content_type", exchange.getRequestHeaders().getFirst("Content-Type"));
            captured.put("user_agent", exchange.getRequestHeaders().getFirst("User-Agent"));
            receivedWebhooks.add(captured);

            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
            if (deliveryLatch != null) deliveryLatch.countDown();
        });
        webhookServer.start();
    }

    @AfterAll
    static void stopWebhookReceiver() {
        if (webhookServer != null) webhookServer.stop(0);
    }

    @BeforeEach
    void resetState() {
        receivedWebhooks.clear();
    }

    @Test
    @Order(1)
    @DisplayName("Full pipeline: event → delivery → webhook with HMAC signature")
    void fullPipeline_eventDeliveredWithHmacSignature() throws Exception {
        deliveryLatch = new CountDownLatch(1);

        try (Jedis jedis = jedisPool.getResource()) {
            // 1. Write subscription (simulates admin server)
            Map<String, Object> subscription = Map.ofEntries(
                    Map.entry("subscription_id", SUBSCRIPTION_ID),
                    Map.entry("tenant_id", TENANT_ID),
                    Map.entry("url", "http://localhost:" + webhookPort + "/webhook"),
                    Map.entry("event_types", List.of("tenant.created")),
                    Map.entry("status", "ACTIVE"),
                    Map.entry("disable_after_failures", 10),
                    Map.entry("consecutive_failures", 0),
                    Map.entry("retry_policy", Map.of(
                            "max_retries", 3, "initial_delay_ms", 1000,
                            "backoff_multiplier", 2.0, "max_delay_ms", 10000
                    ))
            );
            jedis.set("webhook:" + SUBSCRIPTION_ID, objectMapper.writeValueAsString(subscription));
            jedis.sadd("webhooks:" + TENANT_ID, SUBSCRIPTION_ID);
            jedis.set("webhook:secret:" + SUBSCRIPTION_ID, SIGNING_SECRET);

            // 2. Write event
            String eventId = "evt_inttest_" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> event = Map.of(
                    "event_id", eventId, "event_type", "tenant.created",
                    "category", "tenant", "timestamp", Instant.now().toString(),
                    "tenant_id", TENANT_ID, "source", "integration-test",
                    "data", Map.of("tenant_id", TENANT_ID, "new_status", "ACTIVE"));
            jedis.set("event:" + eventId, objectMapper.writeValueAsString(event));

            // 3. Write PENDING delivery + LPUSH
            String deliveryId = "del_inttest_" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> delivery = Map.of(
                    "delivery_id", deliveryId, "subscription_id", SUBSCRIPTION_ID,
                    "event_id", eventId, "event_type", "tenant.created",
                    "status", "PENDING", "attempted_at", Instant.now().toString(),
                    "attempts", 0);
            jedis.set("delivery:" + deliveryId, objectMapper.writeValueAsString(delivery));
            jedis.lpush("dispatch:pending", deliveryId);

            // 4. Wait for delivery
            boolean delivered = deliveryLatch.await(15, TimeUnit.SECONDS);
            assertThat(delivered).as("Webhook should be delivered within 15s").isTrue();

            // 5. Verify webhook received with correct headers
            assertThat(receivedWebhooks).hasSize(1);
            Map<String, String> webhook = receivedWebhooks.get(0);
            assertThat(webhook.get("event_id")).isEqualTo(eventId);
            assertThat(webhook.get("event_type")).isEqualTo("tenant.created");
            assertThat(webhook.get("content_type")).isEqualTo("application/json");
            assertThat(webhook.get("user_agent")).startsWith("cycles-server-events/");

            // 6. Verify HMAC signature
            String signature = webhook.get("signature");
            assertThat(signature).startsWith("sha256=");
            String expectedSig = computeHmac(webhook.get("body"), SIGNING_SECRET);
            assertThat(signature).isEqualTo(expectedSig);

            // 7. Verify body contains event data
            assertThat(webhook.get("body")).contains(eventId).contains("tenant.created").contains(TENANT_ID);

            // 8. Verify delivery updated to SUCCESS in Redis (poll — handler writes after HTTP response)
            String deliveryJson = null;
            for (int i = 0; i < 20; i++) {
                deliveryJson = jedis.get("delivery:" + deliveryId);
                if (deliveryJson != null && deliveryJson.contains("\"status\":\"SUCCESS\"")) break;
                Thread.sleep(250);
            }
            assertThat(deliveryJson).contains("\"status\":\"SUCCESS\"");
            assertThat(deliveryJson).contains("\"response_status\":200");
        }
    }

    @Test
    @Order(2)
    @DisplayName("Stale delivery is auto-failed, not delivered")
    void staleDelivery_autoFailed() throws Exception {
        try (Jedis jedis = jedisPool.getResource()) {
            String eventId = "evt_stale_" + UUID.randomUUID().toString().substring(0, 8);
            jedis.set("event:" + eventId, objectMapper.writeValueAsString(Map.of(
                    "event_id", eventId, "event_type", "tenant.created",
                    "category", "tenant", "timestamp", Instant.now().toString(),
                    "tenant_id", TENANT_ID, "source", "test")));

            String deliveryId = "del_stale_" + UUID.randomUUID().toString().substring(0, 8);
            jedis.set("delivery:" + deliveryId, objectMapper.writeValueAsString(Map.of(
                    "delivery_id", deliveryId, "subscription_id", SUBSCRIPTION_ID,
                    "event_id", eventId, "event_type", "tenant.created",
                    "status", "PENDING",
                    "attempted_at", Instant.now().minusMillis(86400001).toString(),
                    "attempts", 0)));
            jedis.lpush("dispatch:pending", deliveryId);

            Thread.sleep(5000);

            assertThat(receivedWebhooks).as("Stale delivery should NOT be delivered").isEmpty();

            String deliveryJson = jedis.get("delivery:" + deliveryId);
            assertThat(deliveryJson).contains("\"status\":\"FAILED\"");
            assertThat(deliveryJson).contains("expired");
        }
    }

    @Test
    @Order(3)
    @DisplayName("Delivery without signing secret omits X-Cycles-Signature")
    void noSigningSecret_noSignatureHeader() throws Exception {
        deliveryLatch = new CountDownLatch(1);

        try (Jedis jedis = jedisPool.getResource()) {
            String unsignedSubId = "whsub_unsigned_001";
            jedis.set("webhook:" + unsignedSubId, objectMapper.writeValueAsString(Map.ofEntries(
                    Map.entry("subscription_id", unsignedSubId),
                    Map.entry("tenant_id", TENANT_ID),
                    Map.entry("url", "http://localhost:" + webhookPort + "/webhook"),
                    Map.entry("event_types", List.of("tenant.created")),
                    Map.entry("status", "ACTIVE"),
                    Map.entry("disable_after_failures", 10),
                    Map.entry("consecutive_failures", 0))));
            jedis.sadd("webhooks:" + TENANT_ID, unsignedSubId);
            // No signing secret set

            String eventId = "evt_nosig_" + UUID.randomUUID().toString().substring(0, 8);
            jedis.set("event:" + eventId, objectMapper.writeValueAsString(Map.of(
                    "event_id", eventId, "event_type", "tenant.created",
                    "category", "tenant", "timestamp", Instant.now().toString(),
                    "tenant_id", TENANT_ID, "source", "test")));

            String deliveryId = "del_nosig_" + UUID.randomUUID().toString().substring(0, 8);
            jedis.set("delivery:" + deliveryId, objectMapper.writeValueAsString(Map.of(
                    "delivery_id", deliveryId, "subscription_id", unsignedSubId,
                    "event_id", eventId, "event_type", "tenant.created",
                    "status", "PENDING", "attempted_at", Instant.now().toString(),
                    "attempts", 0)));
            jedis.lpush("dispatch:pending", deliveryId);

            boolean delivered = deliveryLatch.await(15, TimeUnit.SECONDS);
            assertThat(delivered).isTrue();

            Map<String, String> webhook = receivedWebhooks.get(0);
            assertThat(webhook.get("signature")).isNull();
            assertThat(webhook.get("event_id")).isEqualTo(eventId);

            jedis.srem("webhooks:" + TENANT_ID, unsignedSubId);
        }
    }

    private static String computeHmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256=");
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
