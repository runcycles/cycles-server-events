package io.runcycles.events.repository;

import static io.runcycles.events.logging.LogSanitizer.safe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.runcycles.events.config.CryptoService;
import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.DeliveryStatus;
import io.runcycles.events.model.DispatcherEventTask;
import io.runcycles.events.model.Subscription;
import io.runcycles.events.model.WebhookStatus;
import io.runcycles.events.repository.DeliveryQueueRepository.ClaimedDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;

@Repository
public class SubscriptionRepository {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionRepository.class);
    private static final int MAX_CAS_ATTEMPTS = 128;

    private static final String FINALIZE_DELIVERY_FAILURE_LUA = """
            if redis.call('HGET', KEYS[6], ARGV[13]) ~= ARGV[14] then return -3 end
            local delivery = redis.call('GET', KEYS[1])
            if not delivery then return -2 end
            if delivery ~= ARGV[1] then return 0 end
            local subscription = redis.call('GET', KEYS[2])
            if ARGV[3] == '0' then
              if subscription then return 0 end
            elseif not subscription or subscription ~= ARGV[4] then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'KEEPTTL')
            redis.call('SET', KEYS[3], ARGV[5], 'NX')
            redis.call('ZADD', KEYS[4], ARGV[6], ARGV[7])
            if ARGV[3] == '1' then
              redis.call('SET', KEYS[2], ARGV[8], 'KEEPTTL')
              if ARGV[9] == '1' then
                redis.call('SET', KEYS[5], ARGV[10], 'NX')
                redis.call('ZADD', KEYS[4], ARGV[11], ARGV[12])
                return 2
              end
              return 1
            end
            return 3
            """;

    private static final String FINALIZE_DELIVERY_SUCCESS_LUA = """
            if redis.call('HGET', KEYS[3], ARGV[6]) ~= ARGV[7] then return -3 end
            local delivery = redis.call('GET', KEYS[1])
            if not delivery then return -2 end
            if delivery ~= ARGV[1] then return 0 end
            local subscription = redis.call('GET', KEYS[2])
            if ARGV[3] == '0' then
              if subscription then return 0 end
            elseif not subscription or subscription ~= ARGV[4] then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'KEEPTTL')
            if ARGV[3] == '1' then
              redis.call('SET', KEYS[2], ARGV[5], 'KEEPTTL')
              return 1
            end
            return 2
            """;

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;
    private final CryptoService cryptoService;

    public SubscriptionRepository(JedisPool jedisPool, ObjectMapper objectMapper, CryptoService cryptoService) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
    }

    public Subscription findById(String subscriptionId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get("webhook:" + subscriptionId);
            if (data == null) return null;
            return objectMapper.readValue(data, Subscription.class);
        } catch (Exception e) {
            LOG.error("Failed to read webhook subscription: subscription_id={}", safe(subscriptionId), e);
            throw new IllegalStateException("Failed to read webhook subscription", e);
        }
    }

    public String getSigningSecret(String subscriptionId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String encrypted = jedis.get("webhook:secret:" + subscriptionId);
            return cryptoService.decrypt(encrypted);
        } catch (Exception e) {
            LOG.error("Failed to read webhook signing secret: subscription_id={} secret_present=unknown",
                    safe(subscriptionId), e);
            throw new IllegalStateException("Failed to read webhook signing secret", e);
        }
    }

    /**
     * Atomically commits the exhausted-retry delivery state, subscription
     * failure counter/optional disable transition, and every mandatory
     * dispatcher Event outbox task. This is the terminal failure transaction;
     * there is no state/event crash window between its parts.
     */
    public TerminalFailureUpdate finalizeDeliveryFailure(
            String subscriptionId,
            ClaimedDelivery claim,
            Delivery failedDelivery,
            Instant occurredAt,
            int defaultDisableAfter,
            DispatcherEventTask disableEventTask,
            DispatcherEventTask deliveryFailedEventTask) {
        if (subscriptionId == null || subscriptionId.isBlank() || failedDelivery == null
                || failedDelivery.getDeliveryId() == null || failedDelivery.getDeliveryId().isBlank()
                || failedDelivery.getStatus() != DeliveryStatus.FAILED
                || !subscriptionId.equals(failedDelivery.getSubscriptionId())
                || !validClaimFor(claim, failedDelivery.getDeliveryId())
                || occurredAt == null || defaultDisableAfter <= 0
                || disableEventTask == null || deliveryFailedEventTask == null) {
            throw new IllegalArgumentException("terminal delivery failure transaction inputs are required");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String deliveryKey = "delivery:" + failedDelivery.getDeliveryId();
            String subscriptionKey = "webhook:" + subscriptionId;
            String failedDeliveryJson = objectMapper.writeValueAsString(failedDelivery);
            String deliveryTaskJson = objectMapper.writeValueAsString(deliveryFailedEventTask);
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                String currentDelivery = jedis.get(deliveryKey);
                if (currentDelivery == null) {
                    return new TerminalFailureUpdate(false, false, false, 0, false, null);
                }
                if (!isTransitionableDelivery(currentDelivery)) {
                    return new TerminalFailureUpdate(false, true, false, 0, false, null);
                }
                String currentSubscription = jedis.get(subscriptionKey);
                boolean subscriptionFound = currentSubscription != null;
                int failures = 0;
                boolean disabledNow = false;
                WebhookStatus previousStatus = null;
                String updatedSubscription = "";
                String disableTaskJson = "";
                if (subscriptionFound) {
                    ObjectNode sub = requireObject(currentSubscription);
                    JsonNode failureNode = sub.path("consecutive_failures");
                    long currentFailures = failureNode.isIntegralNumber() && failureNode.canConvertToLong()
                            ? Math.max(0, failureNode.longValue()) : 0;
                    failures = currentFailures >= Integer.MAX_VALUE
                            ? Integer.MAX_VALUE : (int) currentFailures + 1;
                    JsonNode thresholdNode = sub.path("disable_after_failures");
                    int threshold = thresholdNode.isIntegralNumber() && thresholdNode.canConvertToInt()
                            && thresholdNode.intValue() > 0
                            ? thresholdNode.intValue() : defaultDisableAfter;
                    String previous = sub.path("status").asText("");
                    previousStatus = parseStatus(previous);
                    boolean disableEligible = "ACTIVE".equals(previous) || "PAUSED".equals(previous);
                    disabledNow = disableEligible && failures > threshold;
                    sub.put("consecutive_failures", failures);
                    sub.put("last_triggered_at", occurredAt.toString());
                    sub.put("last_failure_at", occurredAt.toString());
                    if (disabledNow) {
                        sub.put("status", WebhookStatus.DISABLED.name());
                        if (disableEventTask.event().getData() != null) {
                            disableEventTask.event().getData().put("previous_status", previous);
                        }
                        disableTaskJson = objectMapper.writeValueAsString(disableEventTask);
                    }
                    updatedSubscription = objectMapper.writeValueAsString(sub);
                }

                java.util.List<String> keys = java.util.List.of(
                        deliveryKey,
                        subscriptionKey,
                        EventRepository.dispatcherOutboxTaskKey(deliveryFailedEventTask.taskId()),
                        EventRepository.DISPATCHER_OUTBOX_PENDING_KEY,
                        EventRepository.dispatcherOutboxTaskKey(disableEventTask.taskId()),
                        DeliveryQueueRepository.PROCESSING_CLAIM_OWNER_KEY);
                java.util.List<String> args = java.util.List.of(
                        currentDelivery,
                        failedDeliveryJson,
                        subscriptionFound ? "1" : "0",
                        subscriptionFound ? currentSubscription : "",
                        deliveryTaskJson,
                        Long.toString(deliveryFailedEventTask.createdAt().toEpochMilli()),
                        deliveryFailedEventTask.taskId(),
                        updatedSubscription,
                        disabledNow ? "1" : "0",
                        disableTaskJson,
                        Long.toString(disableEventTask.createdAt().toEpochMilli()),
                        disableEventTask.taskId(),
                        claim.deliveryId(),
                        claim.claimToken());
                Object result = jedis.eval(FINALIZE_DELIVERY_FAILURE_LUA, keys, args);
                if (Long.valueOf(-2L).equals(result)) {
                    return new TerminalFailureUpdate(false, false, subscriptionFound, 0, false, null);
                }
                if (Long.valueOf(-3L).equals(result)) {
                    return new TerminalFailureUpdate(false, false, subscriptionFound, 0, false, null);
                }
                if (result instanceof Long code && code >= 1L && code <= 3L) {
                    return new TerminalFailureUpdate(true, true, subscriptionFound, failures,
                            disabledNow, previousStatus);
                }
            }
            throw new CasExhaustedException(
                    "delivery or subscription changed too frequently to finalize failure");
        } catch (CasExhaustedException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to atomically finalize webhook delivery failure: delivery_id={} subscription_id={} occurred_at={} default_disable_after={}",
                    safe(failedDelivery.getDeliveryId()), safe(subscriptionId), occurredAt, defaultDisableAfter, e);
            throw new IllegalStateException("Failed to finalize webhook delivery failure", e);
        }
    }

    /**
     * Atomically commits SUCCESS and resets the subscription's operational
     * delivery fields while the caller still owns the processing claim.
     */
    public DeliverySuccessUpdate finalizeDeliverySuccess(
            String subscriptionId,
            ClaimedDelivery claim,
            Delivery successfulDelivery,
            Instant occurredAt) {
        if (subscriptionId == null || subscriptionId.isBlank() || successfulDelivery == null
                || successfulDelivery.getDeliveryId() == null
                || successfulDelivery.getDeliveryId().isBlank()
                || successfulDelivery.getStatus() != DeliveryStatus.SUCCESS
                || !subscriptionId.equals(successfulDelivery.getSubscriptionId())
                || !validClaimFor(claim, successfulDelivery.getDeliveryId())
                || occurredAt == null) {
            throw new IllegalArgumentException("successful delivery transaction inputs are required");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String deliveryKey = "delivery:" + successfulDelivery.getDeliveryId();
            String subscriptionKey = "webhook:" + subscriptionId;
            String successfulDeliveryJson = objectMapper.writeValueAsString(successfulDelivery);
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                String currentDelivery = jedis.get(deliveryKey);
                if (currentDelivery == null) {
                    return new DeliverySuccessUpdate(false, false, false);
                }
                if (!isTransitionableDelivery(currentDelivery)) {
                    return new DeliverySuccessUpdate(false, true, false);
                }
                String currentSubscription = jedis.get(subscriptionKey);
                boolean subscriptionFound = currentSubscription != null;
                String updatedSubscription = "";
                if (subscriptionFound) {
                    ObjectNode sub = requireObject(currentSubscription);
                    sub.put("consecutive_failures", 0);
                    sub.put("last_triggered_at", occurredAt.toString());
                    sub.put("last_success_at", occurredAt.toString());
                    updatedSubscription = objectMapper.writeValueAsString(sub);
                }
                Object result = jedis.eval(FINALIZE_DELIVERY_SUCCESS_LUA,
                        java.util.List.of(deliveryKey, subscriptionKey,
                                DeliveryQueueRepository.PROCESSING_CLAIM_OWNER_KEY),
                        java.util.List.of(currentDelivery, successfulDeliveryJson,
                                subscriptionFound ? "1" : "0",
                                subscriptionFound ? currentSubscription : "",
                                updatedSubscription, claim.deliveryId(), claim.claimToken()));
                if (Long.valueOf(-2L).equals(result)) {
                    return new DeliverySuccessUpdate(false, false, subscriptionFound);
                }
                if (Long.valueOf(-3L).equals(result)) {
                    return new DeliverySuccessUpdate(false, false, subscriptionFound);
                }
                if (Long.valueOf(1L).equals(result) || Long.valueOf(2L).equals(result)) {
                    return new DeliverySuccessUpdate(true, true, subscriptionFound);
                }
            }
            throw new CasExhaustedException(
                    "delivery or subscription changed too frequently to finalize success");
        } catch (CasExhaustedException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to atomically finalize webhook delivery success: delivery_id={} subscription_id={} occurred_at={}",
                    safe(successfulDelivery.getDeliveryId()), safe(subscriptionId), occurredAt, e);
            throw new IllegalStateException("Failed to finalize webhook delivery success", e);
        }
    }

    private ObjectNode requireObject(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalStateException("webhook subscription must be a JSON object");
        }
        return object;
    }

    private boolean isTransitionableDelivery(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        if (!node.isObject() || !node.path("status").isTextual()) {
            throw new IllegalStateException("webhook delivery must be an object with a valid status");
        }
        DeliveryStatus status;
        try {
            status = DeliveryStatus.valueOf(node.path("status").textValue());
        } catch (IllegalArgumentException invalidStatus) {
            throw new IllegalStateException("webhook delivery has an unknown status", invalidStatus);
        }
        return status == DeliveryStatus.PENDING || status == DeliveryStatus.RETRYING;
    }

    private static boolean validClaimFor(ClaimedDelivery claim, String deliveryId) {
        return claim != null && claim.deliveryId().equals(deliveryId);
    }

    private static WebhookStatus parseStatus(String value) {
        if (value.isBlank()) return null;
        try {
            return WebhookStatus.valueOf(value);
        } catch (IllegalArgumentException unknownFutureStatus) {
            return null;
        }
    }

    public record TerminalFailureUpdate(boolean applied, boolean deliveryFound,
                                        boolean subscriptionFound,
                                        int consecutiveFailures, boolean disabledNow,
                                        WebhookStatus previousStatus) {
    }

    public record DeliverySuccessUpdate(boolean applied, boolean deliveryFound,
                                        boolean subscriptionFound) {
    }

    private static final class CasExhaustedException extends IllegalStateException {
        private CasExhaustedException(String message) {
            super(message);
        }
    }
}
