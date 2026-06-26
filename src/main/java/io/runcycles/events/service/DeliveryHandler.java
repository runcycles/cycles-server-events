package io.runcycles.events.service;

import static io.runcycles.events.logging.LogSanitizer.safe;

import io.runcycles.events.metrics.CyclesMetrics;
import io.runcycles.events.model.Actor;
import io.runcycles.events.model.ActorType;
import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.DeliveryStatus;
import io.runcycles.events.model.Event;
import io.runcycles.events.model.EventCategory;
import io.runcycles.events.model.EventType;
import io.runcycles.events.model.RetryPolicy;
import io.runcycles.events.model.Subscription;
import io.runcycles.events.model.WebhookStatus;
import io.runcycles.events.repository.DeliveryQueueRepository;
import io.runcycles.events.repository.DeliveryRepository;
import io.runcycles.events.repository.EventRepository;
import io.runcycles.events.repository.SubscriptionRepository;
import io.runcycles.events.transport.Transport;
import io.runcycles.events.transport.TransportResult;
import io.runcycles.events.validation.EventPayloadValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeliveryHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryHandler.class);

    // Failure reason codes for cycles_webhook_delivery_failed_total
    static final String REASON_EVENT_NOT_FOUND = "event_not_found";
    static final String REASON_SUBSCRIPTION_NOT_FOUND = "subscription_not_found";
    static final String REASON_SUBSCRIPTION_INACTIVE = "subscription_inactive";
    static final String REASON_HTTP_4XX = "http_4xx";
    static final String REASON_HTTP_5XX = "http_5xx";
    static final String REASON_TRANSPORT_ERROR = "transport_error";
    static final String REASON_CONSECUTIVE_FAILURES = "consecutive_failures";

    private final DeliveryRepository deliveryRepository;
    private final EventRepository eventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final DeliveryQueueRepository queueRepository;
    private final Transport transport;
    private final CyclesMetrics metrics;
    private final EventPayloadValidator validator;
    private final long maxDeliveryAgeMs;

    public DeliveryHandler(DeliveryRepository deliveryRepository, EventRepository eventRepository,
                           SubscriptionRepository subscriptionRepository, DeliveryQueueRepository queueRepository,
                           Transport transport,
                           CyclesMetrics metrics,
                           EventPayloadValidator validator,
                           @Value("${dispatch.max-delivery-age-ms:86400000}") long maxDeliveryAgeMs) {
        this.deliveryRepository = deliveryRepository;
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.queueRepository = queueRepository;
        this.transport = transport;
        this.metrics = metrics;
        this.validator = validator;
        this.maxDeliveryAgeMs = maxDeliveryAgeMs;
    }

    public void handle(String deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId);
        if (delivery == null) {
            LOG.warn("Webhook delivery not found: delivery_id={}", safe(deliveryId));
            return;
        }

        DeliveryStatus status = delivery.getStatus();
        if (status != DeliveryStatus.PENDING && status != DeliveryStatus.RETRYING) {
            LOG.debug("Webhook delivery skipped because status is terminal or in-flight: delivery_id={} status={} event_id={} subscription_id={} trace_id={}",
                    safe(deliveryId), status, safe(delivery.getEventId()), safe(delivery.getSubscriptionId()),
                    safe(delivery.getTraceId()));
            return;
        }

        // Skip stale deliveries (e.g., after prolonged outage)
        Instant attemptedAt = delivery.getAttemptedAt();
        if (attemptedAt == null) {
            LOG.warn("Webhook delivery has null attempted_at; treating as fresh: delivery_id={} event_id={} event_type={} subscription_id={} trace_id={}",
                    safe(deliveryId), safe(delivery.getEventId()), safe(delivery.getEventType()),
                    safe(delivery.getSubscriptionId()), safe(delivery.getTraceId()));
            attemptedAt = Instant.now();
        }
        long ageMs = System.currentTimeMillis() - attemptedAt.toEpochMilli();
        if (ageMs > maxDeliveryAgeMs) {
            metrics.recordDeliveryStale(null); // tenant unknown — subscription not yet loaded
            markFailed(delivery, "Delivery expired: " + (ageMs / 3600000) + "h old (max " + (maxDeliveryAgeMs / 3600000) + "h)");
            return;
        }

        Event event = eventRepository.findById(delivery.getEventId());
        if (event == null) {
            metrics.recordDeliveryFailure(null, delivery.getEventType(), REASON_EVENT_NOT_FOUND, 0);
            LOG.warn("Webhook delivery cannot load event: delivery_id={} event_id={} event_type={} subscription_id={} trace_id={}",
                    safe(delivery.getDeliveryId()), safe(delivery.getEventId()), safe(delivery.getEventType()),
                    safe(delivery.getSubscriptionId()), safe(delivery.getTraceId()));
            markFailed(delivery, "Event not found: " + delivery.getEventId());
            return;
        }

        // Non-fatal shape check (warn + metric, never blocks delivery)
        validator.validate(event);

        Subscription sub = subscriptionRepository.findById(delivery.getSubscriptionId());
        if (sub == null) {
            metrics.recordDeliveryFailure(null, delivery.getEventType(), REASON_SUBSCRIPTION_NOT_FOUND, 0);
            LOG.warn("Webhook delivery cannot load subscription: delivery_id={} event_id={} event_type={} subscription_id={} tenant_id={} trace_id={}",
                    safe(delivery.getDeliveryId()), safe(delivery.getEventId()), safe(delivery.getEventType()),
                    safe(delivery.getSubscriptionId()), safe(event.getTenantId()), safe(effectiveTraceId(delivery, event)));
            markFailed(delivery, "Subscription not found");
            return;
        }
        if (sub.getStatus() != WebhookStatus.ACTIVE) {
            metrics.recordDeliveryFailure(sub.getTenantId(), delivery.getEventType(), REASON_SUBSCRIPTION_INACTIVE, 0);
            LOG.warn("Webhook delivery skipped because subscription is inactive: delivery_id={} event_id={} event_type={} subscription_id={} tenant_id={} status={} trace_id={}",
                    safe(delivery.getDeliveryId()), safe(delivery.getEventId()), safe(delivery.getEventType()),
                    safe(sub.getSubscriptionId()), safe(sub.getTenantId()), sub.getStatus(),
                    safe(effectiveTraceId(delivery, event)));
            markFailed(delivery, "Subscription not active: " + sub.getStatus());
            return;
        }

        String secret = subscriptionRepository.getSigningSecret(delivery.getSubscriptionId());

        delivery.setAttempts(delivery.getAttempts() != null ? delivery.getAttempts() + 1 : 1);
        // Proactive trace_id stamping on the Delivery record (spec
        // v0.1.25.28). Fills the gap while admin hasn't yet populated
        // trace_id on delivery creation — the persisted Delivery
        // becomes self-correlated for admin's readback without a
        // cross-service round trip. Only write when the event actually
        // carries a trace_id; otherwise leave the field null (OPTIONAL
        // on the spec wire). Never overwrite a value admin has already
        // set: admin-authored stamps remain authoritative.
        if (delivery.getTraceId() == null && event.getTraceId() != null) {
            delivery.setTraceId(event.getTraceId());
        }
        metrics.recordDeliveryAttempt(sub.getTenantId(), delivery.getEventType());
        TransportResult result = transport.deliver(event, sub, secret, delivery);

        if (result.isSuccess()) {
            handleSuccess(delivery, sub, result);
        } else {
            handleFailure(delivery, sub, result);
        }
    }

    private void handleSuccess(Delivery delivery, Subscription sub, TransportResult result) {
        Instant now = Instant.now();
        subscriptionRepository.updateDeliveryState(
                sub.getSubscriptionId(), 0, now, now, null, null);

        delivery.setStatus(DeliveryStatus.SUCCESS);
        delivery.setResponseStatus(result.getStatusCode());
        delivery.setResponseTimeMs(result.getLatencyMs());
        delivery.setCompletedAt(Instant.now());
        deliveryRepository.update(delivery);

        metrics.recordDeliverySuccess(sub.getTenantId(), delivery.getEventType(),
                result.getStatusCode(), result.getLatencyMs());

        LOG.info("Webhook delivery succeeded: delivery_id={} event_id={} event_type={} subscription_id={} tenant_id={} status={} latency_ms={} attempts={} trace_id={}",
                safe(delivery.getDeliveryId()), safe(delivery.getEventId()), safe(delivery.getEventType()),
                safe(sub.getSubscriptionId()), safe(sub.getTenantId()), result.getStatusCode(),
                result.getLatencyMs(), delivery.getAttempts(), safe(delivery.getTraceId()));
    }

    private void handleFailure(Delivery delivery, Subscription sub, TransportResult result) {
        RetryPolicy policy = sub.getRetryPolicy() != null ? sub.getRetryPolicy() : RetryPolicy.builder().build();
        int maxRetries = policy.getMaxRetries() != null ? policy.getMaxRetries() : 5;

        String reason = failureReason(result.getStatusCode());
        metrics.recordDeliveryFailure(sub.getTenantId(), delivery.getEventType(), reason, result.getLatencyMs());

        if (delivery.getAttempts() > maxRetries) {
            incrementConsecutiveFailures(sub, delivery);
            markFailed(delivery, result.getErrorMessage());
            return;
        }

        // Schedule retry with exponential backoff
        int initialDelay = policy.getInitialDelayMs() != null ? policy.getInitialDelayMs() : 1000;
        double multiplier = policy.getBackoffMultiplier() != null ? policy.getBackoffMultiplier() : 2.0;
        int maxDelay = policy.getMaxDelayMs() != null ? policy.getMaxDelayMs() : 60000;
        long delay = Math.min((long) (initialDelay * Math.pow(multiplier, delivery.getAttempts() - 1)), maxDelay);
        long nextRetryAt = System.currentTimeMillis() + delay;

        delivery.setStatus(DeliveryStatus.RETRYING);
        delivery.setResponseStatus(result.getStatusCode());
        delivery.setResponseTimeMs(result.getLatencyMs());
        delivery.setErrorMessage(result.getErrorMessage());
        delivery.setNextRetryAt(Instant.ofEpochMilli(nextRetryAt));
        deliveryRepository.update(delivery);
        queueRepository.scheduleRetry(delivery.getDeliveryId(), nextRetryAt);

        metrics.recordDeliveryRetried(sub.getTenantId(), delivery.getEventType());

        LOG.info("Webhook delivery scheduled for retry: delivery_id={} event_id={} event_type={} subscription_id={} tenant_id={} status={} attempts={} max_retries={} next_retry_at={} latency_ms={} trace_id={} reason={}",
                safe(delivery.getDeliveryId()), safe(delivery.getEventId()), safe(delivery.getEventType()), safe(sub.getSubscriptionId()),
                safe(sub.getTenantId()), result.getStatusCode(), delivery.getAttempts(), maxRetries,
                delivery.getNextRetryAt(), result.getLatencyMs(), safe(delivery.getTraceId()), safe(reason));
    }

    private void markFailed(Delivery delivery, String errorMessage) {
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setErrorMessage(errorMessage);
        delivery.setCompletedAt(Instant.now());
        deliveryRepository.update(delivery);
        LOG.warn("Webhook delivery permanently failed: delivery_id={} event_id={} event_type={} subscription_id={} attempts={} response_status={} trace_id={} error={}",
                safe(delivery.getDeliveryId()), safe(delivery.getEventId()), safe(delivery.getEventType()),
                safe(delivery.getSubscriptionId()), delivery.getAttempts(), delivery.getResponseStatus(),
                safe(delivery.getTraceId()), safe(errorMessage));
    }

    private void incrementConsecutiveFailures(Subscription sub, Delivery delivery) {
        int failures = (sub.getConsecutiveFailures() != null ? sub.getConsecutiveFailures() : 0) + 1;
        int disableAfter = sub.getDisableAfterFailures() != null ? sub.getDisableAfterFailures() : 10;
        // Read from the snapshot loaded in handle(); admin could have flipped
        // status to PAUSED between that load and now, in which case the emitted
        // previous_status is one flip behind. The final persisted status is
        // authoritative (updateDeliveryState below writes DISABLED), so this
        // only affects the audit-trail Event's previous_status — acceptable.
        WebhookStatus previousStatus = sub.getStatus();
        WebhookStatus newStatus = null;
        if (failures >= disableAfter) {
            newStatus = WebhookStatus.DISABLED;
        }

        Instant now = Instant.now();
        boolean updated = subscriptionRepository.updateDeliveryState(
                sub.getSubscriptionId(), failures, now, null, now, newStatus);

        if (updated && newStatus == WebhookStatus.DISABLED) {
            // Safe-once: handle() gates on status == ACTIVE before this path, so once
            // updateDeliveryState persists DISABLED, subsequent deliveries short-circuit.
            metrics.recordSubscriptionAutoDisabled(sub.getTenantId(), REASON_CONSECUTIVE_FAILURES);
            LOG.warn("Webhook subscription auto-disabled: subscription_id={} tenant_id={} failures={} disable_after={} delivery_id={} event_id={} trace_id={}",
                    safe(sub.getSubscriptionId()), safe(sub.getTenantId()), failures, disableAfter,
                    safe(delivery.getDeliveryId()), safe(delivery.getEventId()), safe(delivery.getTraceId()));
            emitWebhookDisabled(sub, delivery, previousStatus);
        }
    }

    /**
     * Emit webhook.disabled Event per spec v0.1.25.33 WebhookSubscription.
     * FAILURE HANDLING. Swallows any emit failure — the subscription status
     * flip is the source of truth and must not be blocked by the audit
     * trail write. Logged at WARN for observability.
     */
    private void emitWebhookDisabled(Subscription sub, Delivery delivery, WebhookStatus previousStatus) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("subscription_id", sub.getSubscriptionId());
            data.put("tenant_id", sub.getTenantId());
            if (previousStatus != null) {
                data.put("previous_status", previousStatus.name());
            }
            data.put("new_status", WebhookStatus.DISABLED.name());
            data.put("changed_fields", List.of());
            data.put("disable_reason", "consecutive_failures_exceeded_threshold");

            // scope=null to match admin's WebhookAdminController.emitWebhookLifecycleEvent
            // convention on all webhook.* lifecycle emits — keeps operator
            // scope-filter queries returning a consistent set regardless of
            // which plane wrote the Event.
            Event event = Event.builder()
                    .eventType(EventType.WEBHOOK_DISABLED.getValue())
                    .category(EventCategory.WEBHOOK)
                    .tenantId(sub.getTenantId())
                    .actor(Actor.builder().type(ActorType.SYSTEM).build())
                    .source("cycles-events")
                    .data(data)
                    .correlationId("webhook_auto_disable:" + sub.getSubscriptionId()
                            + ":" + delivery.getDeliveryId())
                    .traceId(delivery.getTraceId())
                    .build();
            eventRepository.save(event);
        } catch (Exception e) {
            LOG.warn("Failed to emit webhook.disabled Event after subscription status flip: subscription_id={} tenant_id={} delivery_id={} event_id={} correlation_id={} trace_id={}",
                    safe(sub.getSubscriptionId()), safe(sub.getTenantId()), safe(delivery.getDeliveryId()),
                    safe(delivery.getEventId()),
                    safe("webhook_auto_disable:" + sub.getSubscriptionId() + ":" + delivery.getDeliveryId()),
                    safe(delivery.getTraceId()), e);
        }
    }

    private static String effectiveTraceId(Delivery delivery, Event event) {
        if (delivery.getTraceId() != null) {
            return delivery.getTraceId();
        }
        return event != null ? event.getTraceId() : null;
    }

    private static String failureReason(int statusCode) {
        if (statusCode >= 400 && statusCode < 500) return REASON_HTTP_4XX;
        if (statusCode >= 500 && statusCode < 600) return REASON_HTTP_5XX;
        return REASON_TRANSPORT_ERROR;
    }
}
