package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.events.config.CryptoService;
import io.runcycles.events.model.Delivery;
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
    void rejectsInvalidOperationalUpdateInputs() {
        assertThatThrownBy(() -> repository.updateDeliveryState(null, 0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.updateDeliveryState("", 0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.updateDeliveryState("sub-1", -1, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateDeliveryStateHandlesCasDisappearance() {
        String existing = "{\"subscription_id\":\"sub-1\"}";
        when(jedis.get("webhook:sub-1")).thenReturn(existing);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-1L);

        assertThat(repository.updateDeliveryState("sub-1", 0, null, null, null, null)).isFalse();
    }

    @Test
    void updateDeliveryStateRejectsNonObjectAndBoundsCasRetries() {
        when(jedis.get("webhook:bad")).thenReturn("[]");
        assertThatThrownBy(() -> repository.updateDeliveryState("bad", 0, null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to update webhook subscription delivery state");

        reset(jedis);
        when(jedis.get("webhook:busy")).thenReturn("{\"subscription_id\":\"busy\"}");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        assertThatThrownBy(() -> repository.updateDeliveryState("busy", 0, null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to update webhook subscription delivery state")
                .hasRootCauseMessage("subscription changed too frequently to update delivery state");
        verify(jedis, times(128)).eval(anyString(), anyList(), anyList());
    }

    @Test
    void finalizeDeliveryFailureRejectsEveryInvalidInputPosition() {
        Delivery valid = failedDelivery();
        Instant now = Instant.now();
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                null, valid, now, 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "", valid, now, 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", null, now, 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", Delivery.builder().build(), now, 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", Delivery.builder().deliveryId(" ").build(), now, 10,
                disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", valid, null, 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", valid, now, 0, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", valid, now, 10, null, deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", valid, now, 10, disableTask(), null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jedisPool, never()).getResource();
    }

    @Test
    void finalizeDeliveryFailureHandlesMissingDeliveryAndSubscription() {
        Delivery delivery = failedDelivery();
        when(jedis.get("delivery:del-1")).thenReturn(null);
        SubscriptionRepository.TerminalFailureUpdate missingDelivery = repository.finalizeDeliveryFailure(
                "sub-1", delivery, Instant.now(), 10, disableTask(), deliveryFailedTask());
        assertThat(missingDelivery.deliveryFound()).isFalse();

        reset(jedis);
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(3L);
        SubscriptionRepository.TerminalFailureUpdate missingSubscription = repository.finalizeDeliveryFailure(
                "sub-1", delivery, Instant.now(), 10, disableTask(), deliveryFailedTask());
        assertThat(missingSubscription.deliveryFound()).isTrue();
        assertThat(missingSubscription.subscriptionFound()).isFalse();
        assertThat(missingSubscription.consecutiveFailures()).isZero();
    }

    @Test
    void finalizeDeliveryFailureAtomicallyDisablesActiveSubscription() {
        Delivery delivery = failedDelivery();
        String subscription = """
                {"subscription_id":"sub-1","status":"ACTIVE",
                 "consecutive_failures":10,"disable_after_failures":10}
                """.strip();
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(subscription);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(2L);

        SubscriptionRepository.TerminalFailureUpdate result = repository.finalizeDeliveryFailure(
                "sub-1", delivery, Instant.parse("2026-07-15T12:00:00Z"), 10,
                disableTask(), deliveryFailedTask());

        assertThat(result.deliveryFound()).isTrue();
        assertThat(result.subscriptionFound()).isTrue();
        assertThat(result.consecutiveFailures()).isEqualTo(11);
        assertThat(result.disabledNow()).isTrue();
        assertThat(result.previousStatus()).isEqualTo(WebhookStatus.ACTIVE);
    }

    @Test
    void finalizeDeliveryFailureFallsBackForMalformedSubscriptionFields() {
        Delivery delivery = failedDelivery();
        String subscription = """
                {"subscription_id":"sub-1","status":"DISABLED",
                 "consecutive_failures":"bad","disable_after_failures":0}
                """.strip();
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(subscription);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        SubscriptionRepository.TerminalFailureUpdate result = repository.finalizeDeliveryFailure(
                "sub-1", delivery, Instant.now(), 10, disableTask(), deliveryFailedTask());

        assertThat(result.consecutiveFailures()).isEqualTo(1);
        assertThat(result.disabledNow()).isFalse();
        assertThat(result.previousStatus()).isEqualTo(WebhookStatus.DISABLED);
    }

    @Test
    void finalizeDeliveryFailureHandlesLuaDisappearanceRetryAndExhaustion() {
        Delivery delivery = failedDelivery();
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-2L);
        assertThat(repository.finalizeDeliveryFailure(
                "sub-1", delivery, Instant.now(), 10, disableTask(), deliveryFailedTask()).deliveryFound())
                .isFalse();

        reset(jedis);
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L, 3L);
        assertThat(repository.finalizeDeliveryFailure(
                "sub-1", delivery, Instant.now(), 10, disableTask(), deliveryFailedTask()).deliveryFound())
                .isTrue();

        reset(jedis);
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", delivery, Instant.now(), 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to finalize webhook delivery failure")
                .hasRootCauseMessage("delivery or subscription changed too frequently to finalize failure");
        verify(jedis, times(128)).eval(anyString(), anyList(), anyList());
    }

    @Test
    void finalizeFailureSaturatesCounterFallsBackFromOversizedThresholdAndAllowsNullDisableData() {
        Delivery delivery = failedDelivery();
        String subscription = """
                {"subscription_id":"sub-1","status":"PAUSED",
                 "consecutive_failures":2147483647,"disable_after_failures":2147483648}
                """.strip();
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(subscription);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(2L);

        SubscriptionRepository.TerminalFailureUpdate result = repository.finalizeDeliveryFailure(
                "sub-1", delivery, Instant.now(), 10, disableTask(null), deliveryFailedTask());

        assertThat(result.consecutiveFailures()).isEqualTo(Integer.MAX_VALUE);
        assertThat(result.disabledNow()).isTrue();
        assertThat(result.previousStatus()).isEqualTo(WebhookStatus.PAUSED);
    }

    @Test
    void finalizeFailureRetriesUnexpectedLuaCodesAndShapes() {
        Delivery delivery = failedDelivery();
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(4L, "unexpected", 3L);

        SubscriptionRepository.TerminalFailureUpdate result = repository.finalizeDeliveryFailure(
                "sub-1", delivery, Instant.now(), 10, disableTask(), deliveryFailedTask());

        assertThat(result.deliveryFound()).isTrue();
        verify(jedis, times(3)).eval(anyString(), anyList(), anyList());
    }

    @Test
    void finalizeFailureRejectsNonObjectSubscriptionState() {
        Delivery delivery = failedDelivery();
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\"}");
        when(jedis.get("webhook:sub-1")).thenReturn("[]");

        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", delivery, Instant.now(), 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to finalize webhook delivery failure");
    }

    private static DispatcherEventTask disableTask() {
        return disableTask(new LinkedHashMap<>());
    }

    private static DispatcherEventTask disableTask(LinkedHashMap<String, Object> data) {
        return DispatcherEventTask.create("disable-test", Event.builder()
                .eventType("webhook.disabled")
                .data(data)
                .build());
    }

    private static DispatcherEventTask deliveryFailedTask() {
        return DispatcherEventTask.create("delivery-failed-test", Event.builder()
                .eventType("system.webhook_delivery_failed")
                .data(new LinkedHashMap<>())
                .build());
    }

    private static Delivery failedDelivery() {
        return Delivery.builder()
                .deliveryId("del-1")
                .subscriptionId("sub-1")
                .eventId("evt-1")
                .eventType("tenant.created")
                .build();
    }
}
