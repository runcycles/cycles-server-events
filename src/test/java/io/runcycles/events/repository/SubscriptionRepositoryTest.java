package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.events.config.CryptoService;
import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.Subscription;
import io.runcycles.events.model.DispatcherEventTask;
import io.runcycles.events.model.Event;
import io.runcycles.events.model.WebhookStatus;
import io.runcycles.events.repository.DeliveryQueueRepository.ClaimedDelivery;
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
        CryptoService cryptoService = new CryptoService("", true); // explicit pass-through mode
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
        CryptoService encryptor = new CryptoService(
                java.util.Base64.getEncoder().encodeToString(new byte[32]), false);
        String encrypted = encryptor.encrypt("my-secret");
        when(jedis.get("webhook:secret:sub-enc")).thenReturn(encrypted);
        // Create repo with same key for decryption
        CryptoService decryptor = new CryptoService(
                java.util.Base64.getEncoder().encodeToString(new byte[32]), false);
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
    void finalizeDeliveryFailureRejectsEveryInvalidInputPosition() {
        Delivery valid = failedDelivery();
        Instant now = Instant.now();
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                null, claimFor(valid), valid, now, 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "", claimFor(valid), valid, now, 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", null, valid, now, 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", claimFor(null), null, now, 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", claimFor(Delivery.builder().build()), Delivery.builder().build(), now, 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", claimFor(Delivery.builder().deliveryId(" ").build()), Delivery.builder().deliveryId(" ").build(), now, 10,
                disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", claimFor(valid), valid, null, 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", claimFor(valid), valid, now, 0, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", claimFor(valid), valid, now, 10, null, deliveryFailedTask()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", claimFor(valid), valid, now, 10, disableTask(), null))
                .isInstanceOf(IllegalArgumentException.class);
        Delivery wrongStatus = failedDelivery();
        wrongStatus.setStatus(io.runcycles.events.model.DeliveryStatus.RETRYING);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", claimFor(wrongStatus), wrongStatus, now, 10,
                disableTask(), deliveryFailedTask())).isInstanceOf(IllegalArgumentException.class);
        Delivery wrongSubscription = failedDelivery();
        wrongSubscription.setSubscriptionId("other");
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", claimFor(wrongSubscription), wrongSubscription, now, 10,
                disableTask(), deliveryFailedTask())).isInstanceOf(IllegalArgumentException.class);
        verify(jedisPool, never()).getResource();
    }

    @Test
    void finalizeDeliveryFailureHandlesMissingDeliveryAndSubscription() {
        Delivery delivery = failedDelivery();
        when(jedis.get("delivery:del-1")).thenReturn(null);
        SubscriptionRepository.TerminalFailureUpdate missingDelivery = repository.finalizeDeliveryFailure(
                "sub-1", claimFor(delivery), delivery, Instant.now(), 10, disableTask(), deliveryFailedTask());
        assertThat(missingDelivery.deliveryFound()).isFalse();

        reset(jedis);
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(3L);
        SubscriptionRepository.TerminalFailureUpdate missingSubscription = repository.finalizeDeliveryFailure(
                "sub-1", claimFor(delivery), delivery, Instant.now(), 10, disableTask(), deliveryFailedTask());
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
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(subscription);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(2L);

        SubscriptionRepository.TerminalFailureUpdate result = repository.finalizeDeliveryFailure(
                "sub-1", claimFor(delivery), delivery, Instant.parse("2026-07-15T12:00:00Z"), 10,
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
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(subscription);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        SubscriptionRepository.TerminalFailureUpdate result = repository.finalizeDeliveryFailure(
                "sub-1", claimFor(delivery), delivery, Instant.now(), 10, disableTask(), deliveryFailedTask());

        assertThat(result.consecutiveFailures()).isEqualTo(1);
        assertThat(result.disabledNow()).isFalse();
        assertThat(result.previousStatus()).isEqualTo(WebhookStatus.DISABLED);
    }

    @Test
    void finalizeDeliveryFailureClampsNegativeCounterAndHandlesMissingStatus() {
        Delivery delivery = failedDelivery();
        when(jedis.get("delivery:del-1"))
                .thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn("""
                {"subscription_id":"sub-1","consecutive_failures":-3,
                 "disable_after_failures":10}
                """.strip());
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        SubscriptionRepository.TerminalFailureUpdate result = repository.finalizeDeliveryFailure(
                "sub-1", claimFor(delivery), delivery, Instant.now(), 10,
                disableTask(), deliveryFailedTask());

        assertThat(result.consecutiveFailures()).isEqualTo(1);
        assertThat(result.previousStatus()).isNull();
        assertThat(result.disabledNow()).isFalse();
    }

    @Test
    void finalizeDeliveryFailureHandlesLuaDisappearanceRetryAndExhaustion() {
        Delivery delivery = failedDelivery();
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-2L);
        assertThat(repository.finalizeDeliveryFailure(
                "sub-1", claimFor(delivery), delivery, Instant.now(), 10, disableTask(), deliveryFailedTask()).deliveryFound())
                .isFalse();

        reset(jedis);
        when(jedis.get("delivery:del-1"))
                .thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-3L);
        SubscriptionRepository.TerminalFailureUpdate superseded = repository.finalizeDeliveryFailure(
                "sub-1", claimFor(delivery), delivery, Instant.now(), 10,
                disableTask(), deliveryFailedTask());
        assertThat(superseded.applied()).isFalse();
        assertThat(superseded.deliveryFound()).isFalse();

        reset(jedis);
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L, 3L);
        assertThat(repository.finalizeDeliveryFailure(
                "sub-1", claimFor(delivery), delivery, Instant.now(), 10, disableTask(), deliveryFailedTask()).deliveryFound())
                .isTrue();

        reset(jedis);
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", claimFor(delivery), delivery, Instant.now(), 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("delivery or subscription changed too frequently to finalize failure")
                .hasNoCause();
        verify(jedis, times(128)).eval(anyString(), anyList(), anyList());
    }

    @Test
    void finalizeFailureSaturatesCounterFallsBackFromOversizedThresholdAndAllowsNullDisableData() {
        Delivery delivery = failedDelivery();
        String subscription = """
                {"subscription_id":"sub-1","status":"PAUSED",
                 "consecutive_failures":2147483647,"disable_after_failures":2147483648}
                """.strip();
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(subscription);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(2L);

        SubscriptionRepository.TerminalFailureUpdate result = repository.finalizeDeliveryFailure(
                "sub-1", claimFor(delivery), delivery, Instant.now(), 10, disableTask(null), deliveryFailedTask());

        assertThat(result.consecutiveFailures()).isEqualTo(Integer.MAX_VALUE);
        assertThat(result.disabledNow()).isTrue();
        assertThat(result.previousStatus()).isEqualTo(WebhookStatus.PAUSED);
    }

    @Test
    void finalizeFailureRetriesUnexpectedLuaCodesAndShapes() {
        Delivery delivery = failedDelivery();
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(4L, "unexpected", 3L);

        SubscriptionRepository.TerminalFailureUpdate result = repository.finalizeDeliveryFailure(
                "sub-1", claimFor(delivery), delivery, Instant.now(), 10, disableTask(), deliveryFailedTask());

        assertThat(result.deliveryFound()).isTrue();
        verify(jedis, times(3)).eval(anyString(), anyList(), anyList());
    }

    @Test
    void finalizeFailureRejectsNonObjectSubscriptionState() {
        Delivery delivery = failedDelivery();
        when(jedis.get("delivery:del-1")).thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn("[]");

        assertThatThrownBy(() -> repository.finalizeDeliveryFailure(
                "sub-1", claimFor(delivery), delivery, Instant.now(), 10, disableTask(), deliveryFailedTask()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to finalize webhook delivery failure");
    }

    @Test
    void finalizeDeliverySuccessAtomicallyUpdatesDeliveryAndOperationalFields() throws Exception {
        Delivery success = successfulDelivery();
        String currentDelivery = "{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}";
        String currentSubscription = """
                {"subscription_id":"sub-1","status":"ACTIVE","consecutive_failures":4,
                 "name":"preserved","last_failure_at":"2026-07-14T00:00:00Z"}
                """.strip();
        when(jedis.get("delivery:del-1")).thenReturn(currentDelivery);
        when(jedis.get("webhook:sub-1")).thenReturn(currentSubscription);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        Instant now = Instant.parse("2026-07-15T12:00:00Z");

        SubscriptionRepository.DeliverySuccessUpdate result = repository.finalizeDeliverySuccess(
                "sub-1", claimFor(success), success, now);

        assertThat(result.applied()).isTrue();
        assertThat(result.deliveryFound()).isTrue();
        assertThat(result.subscriptionFound()).isTrue();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> args = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(jedis).eval(anyString(), eq(List.of("delivery:del-1", "webhook:sub-1",
                "dispatch:processing:claim_owner")), args.capture());
        JsonNode updated = objectMapper.readTree(args.getValue().get(4));
        assertThat(updated.path("consecutive_failures").asInt()).isZero();
        assertThat(updated.path("last_triggered_at").asText()).isEqualTo(now.toString());
        assertThat(updated.path("last_success_at").asText()).isEqualTo(now.toString());
        assertThat(updated.path("last_failure_at").asText()).isEqualTo("2026-07-14T00:00:00Z");
        assertThat(updated.path("name").asText()).isEqualTo("preserved");
    }

    @Test
    void finalizeDeliverySuccessHandlesMissingTerminalAndMissingSubscriptionStates() {
        Delivery success = successfulDelivery();
        when(jedis.get("delivery:del-1")).thenReturn(null);
        assertThat(repository.finalizeDeliverySuccess("sub-1", claimFor(success), success,
                Instant.now()).deliveryFound()).isFalse();

        reset(jedis);
        when(jedis.get("delivery:del-1"))
                .thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"FAILED\"}");
        SubscriptionRepository.DeliverySuccessUpdate superseded = repository.finalizeDeliverySuccess(
                "sub-1", claimFor(success), success, Instant.now());
        assertThat(superseded.applied()).isFalse();
        assertThat(superseded.deliveryFound()).isTrue();

        reset(jedis);
        when(jedis.get("delivery:del-1"))
                .thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"RETRYING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(2L);
        SubscriptionRepository.DeliverySuccessUpdate missingSubscription =
                repository.finalizeDeliverySuccess("sub-1", claimFor(success), success, Instant.now());
        assertThat(missingSubscription.applied()).isTrue();
        assertThat(missingSubscription.subscriptionFound()).isFalse();
    }

    @Test
    void finalizeDeliverySuccessReportsLuaDisappearanceAndSupersededOwner() {
        Delivery success = successfulDelivery();
        when(jedis.get("delivery:del-1"))
                .thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-2L, -3L);

        assertThat(repository.finalizeDeliverySuccess("sub-1", claimFor(success), success,
                Instant.now()).deliveryFound()).isFalse();
        SubscriptionRepository.DeliverySuccessUpdate superseded = repository.finalizeDeliverySuccess(
                "sub-1", claimFor(success), success, Instant.now());
        assertThat(superseded.applied()).isFalse();
        assertThat(superseded.deliveryFound()).isFalse();
    }

    @Test
    void finalizeDeliverySuccessRetriesCasConflictAndBoundsAttempts() {
        Delivery success = successfulDelivery();
        when(jedis.get("delivery:del-1"))
                .thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L, "unexpected", 2L);
        assertThat(repository.finalizeDeliverySuccess("sub-1", claimFor(success), success,
                Instant.now()).applied()).isTrue();

        reset(jedis);
        when(jedis.get("delivery:del-1"))
                .thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn(null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                "sub-1", claimFor(success), success, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("delivery or subscription changed too frequently to finalize success")
                .hasNoCause();
        verify(jedis, times(128)).eval(anyString(), anyList(), anyList());
    }

    @Test
    void finalizeDeliverySuccessRejectsInvalidInputsAndMalformedState() {
        Delivery success = successfulDelivery();
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                null, claimFor(success), success, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                " ", claimFor(success), success, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                "sub-1", null, success, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                "sub-1", claimFor(success), null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        Delivery missingId = Delivery.builder().status(
                io.runcycles.events.model.DeliveryStatus.SUCCESS).build();
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                "sub-1", new ClaimedDelivery("del-1", "token"), missingId, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        Delivery blankId = Delivery.builder().deliveryId(" ").status(
                io.runcycles.events.model.DeliveryStatus.SUCCESS).build();
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                "sub-1", new ClaimedDelivery("del-1", "token"), blankId, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                "sub-1", new ClaimedDelivery("other", "token"), success, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                "sub-1", claimFor(success), success, null))
                .isInstanceOf(IllegalArgumentException.class);
        Delivery wrongStatus = successfulDelivery();
        wrongStatus.setStatus(io.runcycles.events.model.DeliveryStatus.RETRYING);
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                "sub-1", claimFor(wrongStatus), wrongStatus, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        Delivery wrongSubscription = successfulDelivery();
        wrongSubscription.setSubscriptionId("other");
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                "sub-1", claimFor(wrongSubscription), wrongSubscription, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);

        when(jedis.get("delivery:del-1")).thenReturn("[]");
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                "sub-1", claimFor(success), success, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("webhook delivery must be an object with a valid status");

        reset(jedis);
        when(jedis.get("delivery:del-1"))
                .thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"FUTURE\"}");
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                "sub-1", claimFor(success), success, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("webhook delivery has an unknown status");

        reset(jedis);
        when(jedis.get("delivery:del-1"))
                .thenReturn("{\"delivery_id\":\"del-1\",\"status\":\"PENDING\"}");
        when(jedis.get("webhook:sub-1")).thenReturn("[]");
        assertThatThrownBy(() -> repository.finalizeDeliverySuccess(
                "sub-1", claimFor(success), success, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to finalize webhook delivery success");
    }

    private static DispatcherEventTask disableTask() {
        return disableTask(new LinkedHashMap<>());
    }

    private static ClaimedDelivery claimFor(Delivery delivery) {
        String deliveryId = delivery != null && delivery.getDeliveryId() != null
                && !delivery.getDeliveryId().isBlank() ? delivery.getDeliveryId() : "del-1";
        return new ClaimedDelivery(deliveryId, "claim-token");
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
                .status(io.runcycles.events.model.DeliveryStatus.FAILED)
                .build();
    }

    private static Delivery successfulDelivery() {
        return Delivery.builder()
                .deliveryId("del-1")
                .subscriptionId("sub-1")
                .eventId("evt-1")
                .eventType("tenant.created")
                .status(io.runcycles.events.model.DeliveryStatus.SUCCESS)
                .attempts(1)
                .build();
    }
}
