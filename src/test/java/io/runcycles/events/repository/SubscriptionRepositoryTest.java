package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.events.config.CryptoService;
import io.runcycles.events.model.Subscription;
import io.runcycles.events.model.DispatcherEventTask;
import io.runcycles.events.model.Event;
import io.runcycles.events.model.WebhookStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        assertThatThrownBy(() -> repository.findById("sub-bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read webhook subscription");
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
    void getSigningSecret_redisError_throws() {
        when(jedis.get("webhook:secret:sub-fail")).thenThrow(new RuntimeException("redis error"));

        assertThatThrownBy(() -> repository.getSigningSecret("sub-fail"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read webhook signing secret");
    }

    @Test
    void getSigningSecret_encryptedValueWithoutKey_throws() {
        when(jedis.get("webhook:secret:sub-enc")).thenReturn("enc:someBase64Data");

        assertThatThrownBy(() -> repository.getSigningSecret("sub-enc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read webhook signing secret");
    }

    @Test
    void updateDeliveryState_mergesOnlyOperationalFields() throws Exception {
        String existing = """
                {"subscription_id":"sub-1","url":"https://example.com/webhook",
                 "status":"ACTIVE","consecutive_failures":0,"name":"My Webhook",
                 "metadata":{"exact_counter":9007199254740993}}
                """.strip();
        when(jedis.get("webhook:sub-1")).thenReturn(existing);
        when(jedis.eval(anyString(), eq(List.of("webhook:sub-1")), anyList())).thenReturn(1L);

        Instant now = Instant.now();
        repository.updateDeliveryState("sub-1", 3, now, now, null, null);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> args = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(jedis).eval(anyString(), eq(List.of("webhook:sub-1")), args.capture());
        assertThat(args.getValue().getFirst()).isEqualTo(existing);
        com.fasterxml.jackson.databind.JsonNode updated = objectMapper.readTree(args.getValue().get(1));
        assertThat(updated.path("consecutive_failures").asInt()).isEqualTo(3);
        assertThat(updated.path("last_triggered_at").asText()).isEqualTo(now.toString());
        assertThat(updated.path("last_success_at").asText()).isEqualTo(now.toString());
        assertThat(updated.path("name").asText()).isEqualTo("My Webhook");
        assertThat(updated.path("metadata").path("exact_counter").asText())
                .isEqualTo("9007199254740993");
    }

    @Test
    void updateDeliveryState_updatesStatus_whenProvided() throws Exception {
        String existing = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("subscription_id", "sub-1")
                .put("status", "ACTIVE")
                .put("consecutive_failures", 9));
        when(jedis.get("webhook:sub-1")).thenReturn(existing);
        when(jedis.eval(anyString(), eq(List.of("webhook:sub-1")), anyList())).thenReturn(1L);

        Instant now = Instant.now();
        repository.updateDeliveryState("sub-1", 10, now, null, now, WebhookStatus.DISABLED);

        verify(jedis).eval(anyString(), eq(List.of("webhook:sub-1")), argThat(args ->
                args.getFirst().equals(existing)
                        && args.get(1).contains("\"consecutive_failures\":10")
                        && args.get(1).contains("\"status\":\"DISABLED\"")
                        && args.get(1).contains(now.toString())));
    }

    @Test
    void updateDeliveryState_subscriptionNotFound() {
        when(jedis.get("webhook:sub-missing")).thenReturn(null);

        boolean updated = repository.updateDeliveryState("sub-missing", 1, Instant.now(), null, Instant.now(), null);

        assertThat(updated).isFalse();
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }

    @Test
    void updateDeliveryState_redisError_throws() {
        when(jedis.get("webhook:sub-fail"))
                .thenThrow(new RuntimeException("redis error"));

        assertThatThrownBy(() ->
                repository.updateDeliveryState("sub-fail", 1, Instant.now(), null, Instant.now(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to update webhook subscription delivery state");
    }

    @Test
    void recordDeliveryFailure_returnsAtomicTransitionResult() {
        String existing = """
                {"subscription_id":"sub-1","status":"ACTIVE",
                 "consecutive_failures":10,"disable_after_failures":10}
                """.strip();
        when(jedis.get("webhook:sub-1")).thenReturn(existing);
        when(jedis.eval(anyString(), eq(List.of("webhook:sub-1",
                EventRepository.dispatcherOutboxTaskKey("disable-test"),
                EventRepository.DISPATCHER_OUTBOX_PENDING_KEY)), anyList())).thenReturn(1L);
        Instant now = Instant.parse("2026-07-15T12:00:00Z");

        SubscriptionRepository.FailureUpdate result =
                repository.recordDeliveryFailure("sub-1", now, 10, disableTask());

        assertThat(result.found()).isTrue();
        assertThat(result.consecutiveFailures()).isEqualTo(11);
        assertThat(result.disabledNow()).isTrue();
        assertThat(result.previousStatus()).isEqualTo(WebhookStatus.ACTIVE);
        verify(jedis).eval(anyString(), eq(List.of("webhook:sub-1",
                EventRepository.dispatcherOutboxTaskKey("disable-test"),
                EventRepository.DISPATCHER_OUTBOX_PENDING_KEY)), argThat(args ->
                args.getFirst().equals(existing)
                        && args.get(1).contains("\"consecutive_failures\":11")
                        && args.get(1).contains("\"status\":\"DISABLED\"")
                        && args.get(1).contains(now.toString())));
    }

    @Test
    void recordDeliveryFailure_handlesMissingSubscription() {
        when(jedis.get("webhook:missing")).thenReturn(null);

        SubscriptionRepository.FailureUpdate result =
                repository.recordDeliveryFailure("missing", Instant.now(), 10, disableTask());

        assertThat(result.found()).isFalse();
        assertThat(result.disabledNow()).isFalse();
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }

    @Test
    void recordDeliveryFailureDoesNotDisableUntilCountExceedsThreshold() {
        String existing = """
                {"subscription_id":"sub-1","status":"ACTIVE",
                 "consecutive_failures":9,"disable_after_failures":10}
                """.strip();
        when(jedis.get("webhook:sub-1")).thenReturn(existing);
        when(jedis.eval(anyString(), eq(List.of("webhook:sub-1")), anyList())).thenReturn(1L);

        SubscriptionRepository.FailureUpdate result = repository.recordDeliveryFailure(
                "sub-1", Instant.parse("2026-07-15T12:00:00Z"), 10, disableTask());

        assertThat(result.consecutiveFailures()).isEqualTo(10);
        assertThat(result.disabledNow()).isFalse();
        verify(jedis).eval(anyString(), eq(List.of("webhook:sub-1")), argThat(args ->
                args.get(1).contains("\"consecutive_failures\":10")
                        && !args.get(1).contains("\"status\":\"DISABLED\"")));
    }

    @Test
    void recordDeliveryFailure_retriesAfterConcurrentAdminWrite() {
        String beforeAdminWrite = """
                {"subscription_id":"sub-1","status":"ACTIVE","consecutive_failures":0,
                 "disable_after_failures":10,"url":"https://old.example/hook"}
                """.strip();
        String afterAdminWrite = """
                {"subscription_id":"sub-1","status":"ACTIVE","consecutive_failures":4,
                 "disable_after_failures":10,"url":"https://new.example/hook"}
                """.strip();
        when(jedis.get("webhook:sub-1")).thenReturn(beforeAdminWrite, afterAdminWrite);
        when(jedis.eval(anyString(), eq(List.of("webhook:sub-1")), anyList()))
                .thenReturn(0L, 1L);

        SubscriptionRepository.FailureUpdate result = repository.recordDeliveryFailure(
                "sub-1", Instant.parse("2026-07-15T12:00:00Z"), 10, disableTask());

        assertThat(result.consecutiveFailures()).isEqualTo(5);
        verify(jedis, times(2)).eval(anyString(), eq(List.of("webhook:sub-1")), anyList());
        verify(jedis).eval(anyString(), eq(List.of("webhook:sub-1")), argThat(args ->
                args.getFirst().equals(afterAdminWrite)
                        && args.get(1).contains("https://new.example/hook")
                        && args.get(1).contains("\"consecutive_failures\":5")));
    }

    @Test
    void recordDeliveryFailureSaturatesCounterAndFallsBackFromInvalidStoredThreshold() {
        String existing = """
                {"subscription_id":"sub-1","status":"ACTIVE",
                 "consecutive_failures":2147483647,"disable_after_failures":0}
                """.strip();
        when(jedis.get("webhook:sub-1")).thenReturn(existing);
        when(jedis.eval(anyString(), eq(List.of("webhook:sub-1",
                EventRepository.dispatcherOutboxTaskKey("disable-test"),
                EventRepository.DISPATCHER_OUTBOX_PENDING_KEY)), anyList())).thenReturn(1L);

        SubscriptionRepository.FailureUpdate result = repository.recordDeliveryFailure(
                "sub-1", Instant.parse("2026-07-15T12:00:00Z"), 10, disableTask());

        assertThat(result.consecutiveFailures()).isEqualTo(Integer.MAX_VALUE);
        assertThat(result.disabledNow()).isTrue();
    }

    @Test
    void rejectsInvalidOperationalUpdateInputs() {
        assertThatThrownBy(() -> repository.updateDeliveryState("", 0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.updateDeliveryState("sub-1", -1, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.recordDeliveryFailure("sub-1", Instant.now(), 0, disableTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.recordDeliveryFailure("sub-1", null, 10, disableTask()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DispatcherEventTask disableTask() {
        return DispatcherEventTask.create("disable-test", Event.builder()
                .eventType("webhook.disabled")
                .data(new LinkedHashMap<>())
                .build());
    }
}
