package io.runcycles.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
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
 * Full pipeline integration test: Redis → DispatchLoop → DeliveryHandler → WebhookTransport → HTTP receiver.
 *
 * Simulates what admin/runtime servers do (write event + subscription + delivery to Redis + LPUSH),
 * then verifies the events service picks up the delivery and delivers it via HTTP POST with HMAC signing.
 *
 * Requires Docker (Testcontainers starts Redis).
 * Excluded from unit tests by naming convention (*IntegrationTest).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebhookDeliveryIntegrationTest {

    static GenericContainer<?> redis;
    static JedisPool jedisPool;
    static ObjectMapper objectMapper;
    static HttpServer webhookServer;
    static int webhookPort;
    static org.springframework.context.ConfigurableApplicationContext app;

    static final String SIGNING_SECRET = "integration-test-secret";
    static final String SUBSCRIPTION_ID = "whsub_inttest001";
    static final String TENANT_ID = "test-tenant";

    // Captured webhook deliveries
    static final CopyOnWriteArrayList<Map<String, String>> receivedWebhooks = new CopyOnWriteArrayList<>();
    static CountDownLatch deliveryLatch;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        // 1. Start Redis via Testcontainers
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redis.start();

        String redisHost = redis.getHost();
        int redisPort = redis.getMappedPort(6379);
        jedisPool = new JedisPool(redisHost, redisPort);

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 2. Start embedded webhook receiver
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

        // 3. Start the events Spring Boot application pointing at Testcontainers Redis
        app = new org.springframework.boot.builder.SpringApplicationBuilder(EventsApplication.class)
                .properties(
                        "redis.host=" + redisHost,
                        "redis.port=" + redisPort,
                        "redis.password=",
                        "webhook.secret.encryption-key=",
                        "dispatch.pending.timeout-seconds=1",
                        "dispatch.retry.poll-interval-ms=60000",
                        "dispatch.http.timeout-seconds=5",
                        "dispatch.http.connect-timeout-seconds=2",
                        "dispatch.max-delivery-age-ms=86400000",
                        "events.retention.event-ttl-days=1",
                        "events.retention.delivery-ttl-days=1",
                        "events.retention.cleanup-interval-ms=3600000",
                        "server.port=0" // random port
                )
                .run();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (app != null) app.close();
        if (webhookServer != null) webhookServer.stop(0);
        if (jedisPool != null) jedisPool.close();
        if (redis != null) redis.stop();
    }

    @Test
    @Order(1)
    @DisplayName("Full pipeline: event → delivery → webhook with HMAC signature")
    void fullPipeline_eventDeliveredWithHmacSignature() throws Exception {
        deliveryLatch = new CountDownLatch(1);
        receivedWebhooks.clear();

        try (Jedis jedis = jedisPool.getResource()) {
            // 1. Write subscription to Redis (simulates admin server)
            Map<String, Object> subscription = Map.ofEntries(
                    Map.entry("subscription_id", SUBSCRIPTION_ID),
                    Map.entry("tenant_id", TENANT_ID),
                    Map.entry("url", "http://localhost:" + webhookPort + "/webhook"),
                    Map.entry("event_types", List.of("tenant.created")),
                    Map.entry("status", "ACTIVE"),
                    Map.entry("disable_after_failures", 10),
                    Map.entry("consecutive_failures", 0),
                    Map.entry("retry_policy", Map.of(
                            "max_retries", 3,
                            "initial_delay_ms", 1000,
                            "backoff_multiplier", 2.0,
                            "max_delay_ms", 10000
                    ))
            );
            jedis.set("webhook:" + SUBSCRIPTION_ID, objectMapper.writeValueAsString(subscription));
            jedis.sadd("webhooks:" + TENANT_ID, SUBSCRIPTION_ID);

            // 2. Write signing secret
            jedis.set("webhook:secret:" + SUBSCRIPTION_ID, SIGNING_SECRET);

            // 3. Write event to Redis (simulates admin server)
            String eventId = "evt_inttest_" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> event = Map.ofEntries(
                    Map.entry("event_id", eventId),
                    Map.entry("event_type", "tenant.created"),
                    Map.entry("category", "tenant"),
                    Map.entry("timestamp", Instant.now().toString()),
                    Map.entry("tenant_id", TENANT_ID),
                    Map.entry("source", "integration-test"),
                    Map.entry("data", Map.of("tenant_id", TENANT_ID, "new_status", "ACTIVE"))
            );
            jedis.set("event:" + eventId, objectMapper.writeValueAsString(event));

            // 4. Write PENDING delivery
            String deliveryId = "del_inttest_" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> delivery = Map.ofEntries(
                    Map.entry("delivery_id", deliveryId),
                    Map.entry("subscription_id", SUBSCRIPTION_ID),
                    Map.entry("event_id", eventId),
                    Map.entry("event_type", "tenant.created"),
                    Map.entry("status", "PENDING"),
                    Map.entry("attempted_at", Instant.now().toString()),
                    Map.entry("attempts", 0)
            );
            jedis.set("delivery:" + deliveryId, objectMapper.writeValueAsString(delivery));

            // 5. LPUSH to dispatch queue (triggers DispatchLoop)
            jedis.lpush("dispatch:pending", deliveryId);

            // 6. Wait for delivery (max 10s)
            boolean delivered = deliveryLatch.await(10, TimeUnit.SECONDS);
            assertThat(delivered).as("Webhook should be delivered within 10s").isTrue();

            // 7. Verify webhook received
            assertThat(receivedWebhooks).hasSize(1);
            Map<String, String> webhook = receivedWebhooks.get(0);

            // Verify headers
            assertThat(webhook.get("event_id")).isEqualTo(eventId);
            assertThat(webhook.get("event_type")).isEqualTo("tenant.created");
            assertThat(webhook.get("content_type")).isEqualTo("application/json");
            assertThat(webhook.get("user_agent")).startsWith("cycles-server-events/");

            // Verify HMAC signature
            String signature = webhook.get("signature");
            assertThat(signature).startsWith("sha256=");
            String body = webhook.get("body");
            String expectedSig = computeHmac(body, SIGNING_SECRET);
            assertThat(signature).isEqualTo(expectedSig);

            // Verify body contains event data
            assertThat(body).contains(eventId);
            assertThat(body).contains("tenant.created");
            assertThat(body).contains(TENANT_ID);

            // 8. Verify delivery record updated to SUCCESS
            String deliveryJson = jedis.get("delivery:" + deliveryId);
            assertThat(deliveryJson).contains("\"status\":\"SUCCESS\"");
            assertThat(deliveryJson).contains("\"response_status\":200");

            // 9. Verify TTL is set on delivery key
            long ttl = jedis.ttl("delivery:" + deliveryId);
            assertThat(ttl).as("Delivery should have TTL set").isGreaterThan(0);
        }
    }

    @Test
    @Order(2)
    @DisplayName("Stale delivery is auto-failed, not delivered")
    void staleDelivery_autoFailed() throws Exception {
        receivedWebhooks.clear();

        try (Jedis jedis = jedisPool.getResource()) {
            String eventId = "evt_stale_" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> event = Map.of(
                    "event_id", eventId, "event_type", "tenant.created",
                    "category", "tenant", "timestamp", Instant.now().toString(),
                    "tenant_id", TENANT_ID, "source", "test");
            jedis.set("event:" + eventId, objectMapper.writeValueAsString(event));

            // Create delivery with old timestamp (>24h ago)
            String deliveryId = "del_stale_" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> delivery = Map.of(
                    "delivery_id", deliveryId, "subscription_id", SUBSCRIPTION_ID,
                    "event_id", eventId, "event_type", "tenant.created",
                    "status", "PENDING",
                    "attempted_at", Instant.now().minusMillis(86400001).toString(), // 24h + 1ms ago
                    "attempts", 0);
            jedis.set("delivery:" + deliveryId, objectMapper.writeValueAsString(delivery));
            jedis.lpush("dispatch:pending", deliveryId);

            // Wait for processing
            Thread.sleep(3000);

            // Should NOT have been delivered (stale)
            assertThat(receivedWebhooks).isEmpty();

            // Should be marked FAILED
            String deliveryJson = jedis.get("delivery:" + deliveryId);
            assertThat(deliveryJson).contains("\"status\":\"FAILED\"");
            assertThat(deliveryJson).contains("expired");
        }
    }

    @Test
    @Order(3)
    @DisplayName("Delivery to unreachable URL retries then fails")
    void unreachableUrl_retriesThenFails() throws Exception {
        try (Jedis jedis = jedisPool.getResource()) {
            // Create subscription pointing to unreachable URL
            String badSubId = "whsub_bad_001";
            Map<String, Object> badSub = Map.ofEntries(
                    Map.entry("subscription_id", badSubId),
                    Map.entry("tenant_id", TENANT_ID),
                    Map.entry("url", "http://localhost:1/webhook"), // port 1 — unreachable
                    Map.entry("event_types", List.of("tenant.created")),
                    Map.entry("status", "ACTIVE"),
                    Map.entry("disable_after_failures", 10),
                    Map.entry("consecutive_failures", 0),
                    Map.entry("retry_policy", Map.of(
                            "max_retries", 1, // Only 1 retry for fast test
                            "initial_delay_ms", 100,
                            "backoff_multiplier", 1.0,
                            "max_delay_ms", 100
                    ))
            );
            jedis.set("webhook:" + badSubId, objectMapper.writeValueAsString(badSub));
            jedis.sadd("webhooks:" + TENANT_ID, badSubId);

            String eventId = "evt_fail_" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> event = Map.of(
                    "event_id", eventId, "event_type", "tenant.created",
                    "category", "tenant", "timestamp", Instant.now().toString(),
                    "tenant_id", TENANT_ID, "source", "test");
            jedis.set("event:" + eventId, objectMapper.writeValueAsString(event));

            String deliveryId = "del_fail_" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> delivery = Map.of(
                    "delivery_id", deliveryId, "subscription_id", badSubId,
                    "event_id", eventId, "event_type", "tenant.created",
                    "status", "PENDING",
                    "attempted_at", Instant.now().toString(),
                    "attempts", 0);
            jedis.set("delivery:" + deliveryId, objectMapper.writeValueAsString(delivery));
            jedis.lpush("dispatch:pending", deliveryId);

            // Wait for initial attempt + retry cycle
            Thread.sleep(8000);

            String deliveryJson = jedis.get("delivery:" + deliveryId);
            // Should be RETRYING or FAILED (depending on timing of retry scheduler)
            assertThat(deliveryJson).matches(".*\"status\":\"(RETRYING|FAILED)\".*");

            // Clean up
            jedis.srem("webhooks:" + TENANT_ID, badSubId);
            jedis.del("webhook:" + badSubId);
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
