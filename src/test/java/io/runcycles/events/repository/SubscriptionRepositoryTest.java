package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.events.config.CryptoService;
import io.runcycles.events.model.Subscription;
import io.runcycles.events.model.WebhookStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionRepositoryTest {

    @Mock
    private JedisPool jedisPool;
    @Mock
    private Jedis jedis;

    private ObjectMapper objectMapper;
    private SubscriptionRepository repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        CryptoService cryptoService = new CryptoService(""); // pass-through mode
        repository = new SubscriptionRepository(jedisPool, objectMapper, cryptoService);
    }

    @Test
    void findById_found() throws Exception {
        Subscription sub = Subscription.builder()
                .subscriptionId("sub-1")
                .tenantId("t-1")
                .url("https://example.com/webhook")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of("tenant.created"))
                .build();
        when(jedis.get("webhook:sub-1")).thenReturn(objectMapper.writeValueAsString(sub));

        Subscription result = repository.findById("sub-1");

        assertThat(result).isNotNull();
        assertThat(result.getSubscriptionId()).isEqualTo("sub-1");
        assertThat(result.getUrl()).isEqualTo("https://example.com/webhook");
        assertThat(result.getStatus()).isEqualTo(WebhookStatus.ACTIVE);
    }

    @Test
    void findById_notFound() {
        when(jedis.get("webhook:sub-missing")).thenReturn(null);

        Subscription result = repository.findById("sub-missing");

        assertThat(result).isNull();
    }

    @Test
    void findById_deserializationError() {
        when(jedis.get("webhook:sub-bad")).thenReturn("%%%bad-json");

        Subscription result = repository.findById("sub-bad");

        assertThat(result).isNull();
    }

    @Test
    void getSigningSecret_found() {
        when(jedis.get("webhook:secret:sub-1")).thenReturn("my-secret-key");

        String result = repository.getSigningSecret("sub-1");

        assertThat(result).isEqualTo("my-secret-key");
    }

    @Test
    void getSigningSecret_notFound() {
        when(jedis.get("webhook:secret:sub-missing")).thenReturn(null);

        String result = repository.getSigningSecret("sub-missing");

        assertThat(result).isNull();
    }

    @Test
    void getSigningSecret_decryptsEncryptedValue() {
        // Simulate an encrypted secret stored by admin service
        CryptoService encryptor = new CryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        String encrypted = encryptor.encrypt("my-secret");
        when(jedis.get("webhook:secret:sub-enc")).thenReturn(encrypted);
        // Create repo with same key for decryption
        CryptoService decryptor = new CryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        SubscriptionRepository encRepo = new SubscriptionRepository(jedisPool, objectMapper, decryptor);

        String result = encRepo.getSigningSecret("sub-enc");

        assertThat(result).isEqualTo("my-secret");
    }

    @Test
    void getSigningSecret_redisError_returnsNull() {
        when(jedis.get("webhook:secret:sub-fail")).thenThrow(new RuntimeException("redis error"));

        String result = repository.getSigningSecret("sub-fail");

        assertThat(result).isNull();
    }

    @Test
    void updateDeliveryState_mergesOnlyOperationalFields() throws Exception {
        // Simulate existing subscription in Redis with admin-managed config
        String existing = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("subscription_id", "sub-1")
                .put("url", "https://example.com/webhook")
                .put("status", "ACTIVE")
                .put("consecutive_failures", 0)
                .put("name", "My Webhook"));
        when(jedis.get("webhook:sub-1")).thenReturn(existing);

        Instant now = Instant.now();
        repository.updateDeliveryState("sub-1", 3, now, now, null, null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jedis).set(eq("webhook:sub-1"), captor.capture());
        ObjectNode written = (ObjectNode) objectMapper.readTree(captor.getValue());
        // Operational fields updated
        assertThat(written.get("consecutive_failures").asInt()).isEqualTo(3);
        assertThat(written.get("last_triggered_at").asText()).isEqualTo(now.toString());
        assertThat(written.get("last_success_at").asText()).isEqualTo(now.toString());
        // Admin-managed fields preserved
        assertThat(written.get("url").asText()).isEqualTo("https://example.com/webhook");
        assertThat(written.get("name").asText()).isEqualTo("My Webhook");
        // Status not changed (null passed)
        assertThat(written.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void updateDeliveryState_updatesStatus_whenProvided() throws Exception {
        String existing = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("subscription_id", "sub-1")
                .put("status", "ACTIVE")
                .put("consecutive_failures", 9));
        when(jedis.get("webhook:sub-1")).thenReturn(existing);

        Instant now = Instant.now();
        repository.updateDeliveryState("sub-1", 10, now, null, now, WebhookStatus.DISABLED);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jedis).set(eq("webhook:sub-1"), captor.capture());
        ObjectNode written = (ObjectNode) objectMapper.readTree(captor.getValue());
        assertThat(written.get("status").asText()).isEqualTo("DISABLED");
        assertThat(written.get("consecutive_failures").asInt()).isEqualTo(10);
        assertThat(written.has("last_success_at")).isFalse(); // null not written
        assertThat(written.get("last_failure_at").asText()).isEqualTo(now.toString());
    }

    @Test
    void updateDeliveryState_subscriptionNotFound() {
        when(jedis.get("webhook:sub-missing")).thenReturn(null);

        repository.updateDeliveryState("sub-missing", 1, Instant.now(), null, Instant.now(), null);

        verify(jedis, never()).set(eq("webhook:sub-missing"), anyString());
    }

    @Test
    void updateDeliveryState_redisError_doesNotThrow() {
        when(jedis.get("webhook:sub-fail")).thenThrow(new RuntimeException("redis error"));

        // Should not throw
        repository.updateDeliveryState("sub-fail", 1, Instant.now(), null, Instant.now(), null);
    }
}
