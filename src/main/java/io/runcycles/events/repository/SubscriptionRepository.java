package io.runcycles.events.repository;

import static io.runcycles.events.logging.LogSanitizer.safe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.runcycles.events.config.CryptoService;
import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.DispatcherEventTask;
import io.runcycles.events.model.Subscription;
import io.runcycles.events.model.WebhookStatus;
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

    private static final String COMPARE_AND_SET_LUA = """
            local current = redis.call('GET', KEYS[1])
            if not current then return -1 end
            if current ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'KEEPTTL')
            if ARGV[3] == '1' then
              redis.call('SET', KEYS[2], ARGV[4], 'NX')
              redis.call('ZADD', KEYS[3], ARGV[5], ARGV[6])
            end
            return 1
            """;

    private static final String FINALIZE_DELIVERY_FAILURE_LUA = """
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

    /** Atomically merges only events-owned operational fields in Redis. */
    public boolean updateDeliveryState(String subscriptionId, int consecutiveFailures,
                                       Instant lastTriggeredAt, Instant lastSuccessAt,
                                       Instant lastFailureAt, WebhookStatus status) {
        if (subscriptionId == null || subscriptionId.isBlank() || consecutiveFailures < 0) {
            throw new IllegalArgumentException("subscription id and non-negative failure count are required");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "webhook:" + subscriptionId;
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                String current = jedis.get(key);
                if (current == null) {
                    LOG.warn("Webhook subscription not found during delivery-state update: subscription_id={} status={} consecutive_failures={} last_triggered_at={} last_success_at={} last_failure_at={}",
                            safe(subscriptionId), status, consecutiveFailures, lastTriggeredAt, lastSuccessAt, lastFailureAt);
                    return false;
                }
                ObjectNode sub = requireObject(current);
                sub.put("consecutive_failures", consecutiveFailures);
                putInstant(sub, "last_triggered_at", lastTriggeredAt);
                putInstant(sub, "last_success_at", lastSuccessAt);
                putInstant(sub, "last_failure_at", lastFailureAt);
                if (status != null) sub.put("status", status.name());
                String updated = objectMapper.writeValueAsString(sub);
                Object result = jedis.eval(COMPARE_AND_SET_LUA,
                        java.util.List.of(key), java.util.List.of(current, updated));
                if (Long.valueOf(1L).equals(result)) return true;
                if (Long.valueOf(-1L).equals(result)) return false;
            }
            throw new IllegalStateException("subscription changed too frequently to update delivery state");
        } catch (Exception e) {
            LOG.error("Failed to update webhook subscription delivery state: subscription_id={} status={} consecutive_failures={} last_triggered_at={} last_success_at={} last_failure_at={}",
                    safe(subscriptionId), status, consecutiveFailures, lastTriggeredAt, lastSuccessAt, lastFailureAt, e);
            throw new IllegalStateException("Failed to update webhook subscription delivery state", e);
        }
    }

    /**
     * Atomically increments the authoritative Redis failure count and performs
     * the ACTIVE/PAUSED-to-DISABLED transition exactly once. The returned transition
     * decides whether the caller emits the disable event.
     */
    public FailureUpdate recordDeliveryFailure(String subscriptionId, Instant occurredAt,
                                                int defaultDisableAfter,
                                                DispatcherEventTask disableEventTask) {
        if (subscriptionId == null || subscriptionId.isBlank() || occurredAt == null
                || defaultDisableAfter <= 0 || disableEventTask == null) {
            throw new IllegalArgumentException(
                    "subscription id, occurrence time, positive disable threshold, and disable event task are required");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "webhook:" + subscriptionId;
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                String current = jedis.get(key);
                if (current == null) return new FailureUpdate(false, 0, false, null);
                ObjectNode sub = requireObject(current);
                JsonNode failureNode = sub.path("consecutive_failures");
                long currentFailures = failureNode.isIntegralNumber() && failureNode.canConvertToLong()
                        ? Math.max(0, failureNode.longValue()) : 0;
                int failures = currentFailures >= Integer.MAX_VALUE
                        ? Integer.MAX_VALUE : (int) currentFailures + 1;
                JsonNode thresholdNode = sub.path("disable_after_failures");
                int threshold = thresholdNode.isIntegralNumber() && thresholdNode.canConvertToInt()
                        && thresholdNode.intValue() > 0
                        ? thresholdNode.intValue() : defaultDisableAfter;
                String previous = sub.path("status").asText("");
                boolean disableEligible = "ACTIVE".equals(previous) || "PAUSED".equals(previous);
                // Spec wording is explicit: disable only after the count EXCEEDS
                // disable_after_failures, not merely when it reaches the value.
                boolean disabledNow = disableEligible && failures > threshold;
                sub.put("consecutive_failures", failures);
                sub.put("last_triggered_at", occurredAt.toString());
                sub.put("last_failure_at", occurredAt.toString());
                if (disabledNow) sub.put("status", WebhookStatus.DISABLED.name());
                String updated = objectMapper.writeValueAsString(sub);
                if (disabledNow && disableEventTask.event().getData() != null) {
                    disableEventTask.event().getData().put("previous_status", previous);
                }
                String taskJson = disabledNow ? objectMapper.writeValueAsString(disableEventTask) : "";
                java.util.List<String> keys = new java.util.ArrayList<>();
                keys.add(key);
                if (disabledNow) {
                    keys.add(EventRepository.dispatcherOutboxTaskKey(disableEventTask.taskId()));
                    keys.add(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY);
                }
                Object result = jedis.eval(COMPARE_AND_SET_LUA,
                        keys, java.util.List.of(current, updated, disabledNow ? "1" : "0", taskJson,
                                Long.toString(disableEventTask.createdAt().toEpochMilli()),
                                disableEventTask.taskId()));
                if (Long.valueOf(-1L).equals(result)) {
                    return new FailureUpdate(false, 0, false, null);
                }
                if (Long.valueOf(1L).equals(result)) {
                    return new FailureUpdate(true, failures, disabledNow, parseStatus(previous));
                }
            }
            throw new IllegalStateException("subscription changed too frequently to record delivery failure");
        } catch (Exception e) {
            LOG.error("Failed to atomically record webhook subscription failure: subscription_id={} occurred_at={} default_disable_after={}",
                    safe(subscriptionId), occurredAt, defaultDisableAfter, e);
            throw new IllegalStateException("Failed to record webhook subscription failure", e);
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
            Delivery failedDelivery,
            Instant occurredAt,
            int defaultDisableAfter,
            DispatcherEventTask disableEventTask,
            DispatcherEventTask deliveryFailedEventTask) {
        if (subscriptionId == null || subscriptionId.isBlank() || failedDelivery == null
                || failedDelivery.getDeliveryId() == null || failedDelivery.getDeliveryId().isBlank()
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
                    return new TerminalFailureUpdate(false, false, 0, false, null);
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
                        EventRepository.dispatcherOutboxTaskKey(disableEventTask.taskId()));
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
                        disableEventTask.taskId());
                Object result = jedis.eval(FINALIZE_DELIVERY_FAILURE_LUA, keys, args);
                if (Long.valueOf(-2L).equals(result)) {
                    return new TerminalFailureUpdate(false, subscriptionFound, 0, false, null);
                }
                if (result instanceof Long code && code >= 1L && code <= 3L) {
                    return new TerminalFailureUpdate(true, subscriptionFound, failures,
                            disabledNow, previousStatus);
                }
            }
            throw new IllegalStateException("delivery or subscription changed too frequently to finalize failure");
        } catch (Exception e) {
            LOG.error("Failed to atomically finalize webhook delivery failure: delivery_id={} subscription_id={} occurred_at={} default_disable_after={}",
                    safe(failedDelivery.getDeliveryId()), safe(subscriptionId), occurredAt, defaultDisableAfter, e);
            throw new IllegalStateException("Failed to finalize webhook delivery failure", e);
        }
    }

    private ObjectNode requireObject(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalStateException("webhook subscription must be a JSON object");
        }
        return object;
    }

    private static void putInstant(ObjectNode node, String field, Instant value) {
        if (value != null) node.put(field, value.toString());
    }

    private static WebhookStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return WebhookStatus.valueOf(value);
        } catch (IllegalArgumentException unknownFutureStatus) {
            return null;
        }
    }

    public record FailureUpdate(boolean found, int consecutiveFailures,
                                boolean disabledNow, WebhookStatus previousStatus) {
    }

    public record TerminalFailureUpdate(boolean deliveryFound, boolean subscriptionFound,
                                        int consecutiveFailures, boolean disabledNow,
                                        WebhookStatus previousStatus) {
    }
}
