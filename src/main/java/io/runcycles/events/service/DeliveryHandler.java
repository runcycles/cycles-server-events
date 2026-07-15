package io.runcycles.events.service;

import static io.runcycles.events.logging.LogSanitizer.safe;

import io.runcycles.events.metrics.CyclesMetrics;
import io.runcycles.events.model.Actor;
import io.runcycles.events.model.ActorType;
import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.DeliveryStatus;
import io.runcycles.events.model.DispatcherEventTask;
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
import io.runcycles.events.repository.SubscriptionRepository.TerminalFailureUpdate;
import io.runcycles.events.transport.Transport;
import io.runcycles.events.transport.TransportResult;
import io.runcycles.events.transport.webhook.WebhookUrlGuard;
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
    static final String REASON_SSRF_BLOCKED = "ssrf_blocked";
    static final String REASON_MISSING_SIGNING_SECRET = "missing_signing_secret";

    private final DeliveryRepository deliveryRepository;
    private final EventRepository eventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final DeliveryQueueRepository queueRepository;
    private final Transport transport;
    private final CyclesMetrics metrics;
    private final EventPayloadValidator validator;
    private final WebhookUrlGuard urlGuard;
    private final long maxDeliveryAgeMs;

    public DeliveryHandler(DeliveryRepository deliveryRepository, EventRepository eventRepository,
                           SubscriptionRepository subscriptionRepository, DeliveryQueueRepository queueRepository,
                           Transport transport,
                           CyclesMetrics metrics,
                           EventPayloadValidator validator,
                           WebhookUrlGuard urlGuard,
                           @Value("${dispatch.max-delivery-age-ms:86400000}") long maxDeliveryAgeMs) {
        this.deliveryRepository = deliveryRepository;
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.queueRepository = queueRepository;
        this.transport = transport;
        this.metrics = metrics;
        this.validator = validator;
        this.urlGuard = urlGuard;
        if (maxDeliveryAgeMs <= 0) {
            throw new IllegalArgumentException("dispatch max delivery age must be positive");
        }
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

        // A crash after persisting RETRYING but before adding/acking the retry
        // queue leaves the record in processing. Recovery may requeue it before
        // its backoff expires; restore the ZSET schedule instead of sending early.
        if (status == DeliveryStatus.RETRYING && delivery.getNextRetryAt() != null
                && delivery.getNextRetryAt().isAfter(Instant.now())) {
            queueRepository.scheduleRetry(deliveryId, delivery.getNextRetryAt().toEpochMilli());
            LOG.debug("Webhook retry schedule restored without early delivery: delivery_id={} event_id={} subscription_id={} next_retry_at={} trace_id={}",
                    safe(deliveryId), safe(delivery.getEventId()), safe(delivery.getSubscriptionId()),
                    delivery.getNextRetryAt(), safe(delivery.getTraceId()));
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

        // Persist correlation on every terminal path that has an authoritative
        // source event, including config/security failures before transport.
        if (delivery.getTraceId() == null && event.getTraceId() != null) {
            delivery.setTraceId(event.getTraceId());
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

        // Last-mile webhook ownership boundary (governance WEBHOOK SUBSCRIPTION
        // INVARIANT 2; issue runcycles/cycles-server-admin#209). The admin plane
        // blocks admin-only events onto concrete-tenant subscriptions at
        // subscription-write and at ENQUEUE, but the actual HTTP send, retries,
        // and recovered-processing redeliveries all happen HERE and never re-pass
        // the enqueue gate. Re-evaluate the boundary against the CURRENT event +
        // subscription immediately before the send so it also covers deliveries
        // queued BEFORE this version deployed. A concrete-tenant-owned
        // subscription (owner present and != "__system__") receiving an admin-only
        // or unclassifiable event (fail-closed: type OR category admin-only, or
        // neither classifiable) is a confidentiality leak — DROP as terminal
        // (distinct boundary-skipped reason, NOT a retryable failure: the event is
        // policy-ineligible for this endpoint and re-sending will never make it
        // eligible). Per-event: a mixed subscription still receives its
        // tenant-accessible events; only the admin-only ones are skipped.
        // Classify on the RELOADED Event (authoritative), never the possibly
        // stale Delivery.event_type snapshot — the boundary decision AND its
        // reported signal use event.getEventType()/getCategory().
        if (WebhookOwnershipBoundary.isBlocked(event.getEventType(), event.getCategory(), sub.getTenantId())) {
            metrics.recordDeliveryBoundarySkipped(sub.getTenantId(), event.getEventType(), event.getCategory());
            LOG.warn("Webhook delivery blocked by ownership boundary (#209): delivery_id={} event_id={} event_type={} category={} subscription_id={} tenant_id={} trace_id={} — a concrete-tenant subscription cannot receive admin-only or unclassifiable events",
                    safe(delivery.getDeliveryId()), safe(delivery.getEventId()), safe(event.getEventType()),
                    safe(event.getCategory()), safe(sub.getSubscriptionId()), safe(sub.getTenantId()),
                    safe(effectiveTraceId(delivery, event)));
            markFailed(delivery, "Delivery blocked by webhook ownership boundary (#209): "
                    + "concrete-tenant subscription cannot receive admin-only event");
            return;
        }

        // Delivery-time SSRF guard: re-validate the target URL against the
        // CURRENT admin webhook-security config. A CONFIRMED violation is a
        // permanent fail, no retry (the target is policy-blocked, not
        // unhealthy) and no consecutive-failure increment (the endpoint was
        // never contacted, so this says nothing about its health — and a
        // config tightening must not auto-disable subscriptions as a side
        // effect). An INDETERMINATE config (Redis read/parse failure inside
        // the guard) is NOT a violation: rethrow so the delivery stays
        // un-acked in dispatch:processing and the stale-processing recovery
        // retries it — a transient config blip must never permanently drop
        // a valid delivery.
        String violation;
        try {
            violation = urlGuard.check(sub.getUrl());
        } catch (RuntimeException configIndeterminate) {
            LOG.warn("Webhook security config indeterminate; leaving delivery for retry: delivery_id={} event_id={} subscription_id={} tenant_id={} trace_id={}",
                    safe(delivery.getDeliveryId()), safe(delivery.getEventId()),
                    safe(sub.getSubscriptionId()), safe(sub.getTenantId()),
                    safe(effectiveTraceId(delivery, event)));
            throw configIndeterminate;
        }
        if (violation != null) {
            metrics.recordDeliveryFailure(sub.getTenantId(), delivery.getEventType(), REASON_SSRF_BLOCKED, 0);
            LOG.warn("Webhook delivery blocked by security policy: delivery_id={} event_id={} event_type={} subscription_id={} tenant_id={} reason={} trace_id={}",
                    safe(delivery.getDeliveryId()), safe(delivery.getEventId()), safe(delivery.getEventType()),
                    safe(sub.getSubscriptionId()), safe(sub.getTenantId()), safe(violation),
                    safe(effectiveTraceId(delivery, event)));
            markFailed(delivery, "Delivery blocked by webhook security policy: " + violation);
            return;
        }

        String secret = subscriptionRepository.getSigningSecret(delivery.getSubscriptionId());
        if (secret == null || secret.isBlank()) {
            metrics.recordDeliveryFailure(sub.getTenantId(), delivery.getEventType(),
                    REASON_MISSING_SIGNING_SECRET, 0);
            markFailed(delivery, "Webhook signing secret is missing; refusing unsigned delivery");
            LOG.error("Webhook delivery blocked because signing secret is missing: delivery_id={} event_id={} subscription_id={} tenant_id={} trace_id={}",
                    safe(delivery.getDeliveryId()), safe(delivery.getEventId()),
                    safe(sub.getSubscriptionId()), safe(sub.getTenantId()),
                    safe(effectiveTraceId(delivery, event)));
            return;
        }

        delivery.setAttempts(delivery.getAttempts() != null ? delivery.getAttempts() + 1 : 1);
        metrics.recordDeliveryAttempt(sub.getTenantId(), delivery.getEventType());
        TransportResult result = transport.deliver(event, sub, secret, delivery);

        if (result.isSuccess()) {
            handleSuccess(delivery, sub, result);
        } else {
            handleFailure(delivery, sub, event, result);
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
        delivery.setErrorMessage(null);
        delivery.setNextRetryAt(null);
        deliveryRepository.update(delivery);

        metrics.recordDeliverySuccess(sub.getTenantId(), delivery.getEventType(),
                result.getStatusCode(), result.getLatencyMs());

        LOG.info("Webhook delivery succeeded: delivery_id={} event_id={} event_type={} subscription_id={} tenant_id={} status={} latency_ms={} attempts={} trace_id={}",
                safe(delivery.getDeliveryId()), safe(delivery.getEventId()), safe(delivery.getEventType()),
                safe(sub.getSubscriptionId()), safe(sub.getTenantId()), result.getStatusCode(),
                result.getLatencyMs(), delivery.getAttempts(), safe(delivery.getTraceId()));
    }

    private void handleFailure(Delivery delivery, Subscription sub, Event event, TransportResult result) {
        RetryPolicy policy = sub.getRetryPolicy() != null ? sub.getRetryPolicy() : RetryPolicy.builder().build();
        int maxRetries = policy.getMaxRetries() != null ? policy.getMaxRetries() : 5;

        String reason = failureReason(result.getStatusCode());
        metrics.recordDeliveryFailure(sub.getTenantId(), delivery.getEventType(), reason, result.getLatencyMs());

        if (delivery.getAttempts() > maxRetries) {
            String requestId = event.getRequestId();
            DeliveryStatus originalStatus = delivery.getStatus();
            Integer originalResponseStatus = delivery.getResponseStatus();
            Integer originalResponseTimeMs = delivery.getResponseTimeMs();
            String originalErrorMessage = delivery.getErrorMessage();
            Instant originalCompletedAt = delivery.getCompletedAt();
            Instant originalNextRetryAt = delivery.getNextRetryAt();
            Instant failedAt = Instant.now();
            delivery.setResponseStatus(result.getStatusCode() > 0 ? result.getStatusCode() : null);
            delivery.setResponseTimeMs(result.getLatencyMs() >= 0 ? result.getLatencyMs() : null);
            delivery.setStatus(DeliveryStatus.FAILED);
            delivery.setErrorMessage(result.getErrorMessage());
            delivery.setCompletedAt(failedAt);
            delivery.setNextRetryAt(null);
            DispatcherEventTask failureTask = buildDeliveryFailedTask(sub, delivery, result, requestId);
            DispatcherEventTask disableTask = buildWebhookDisabledTask(sub, delivery, requestId);
            int defaultDisableAfter = sub.getDisableAfterFailures() != null ? sub.getDisableAfterFailures() : 10;
            TerminalFailureUpdate update;
            try {
                update = subscriptionRepository.finalizeDeliveryFailure(
                        sub.getSubscriptionId(), delivery, failedAt, defaultDisableAfter,
                        disableTask, failureTask);
            } catch (RuntimeException transactionFailure) {
                // The Redis transaction did not commit; keep the in-memory object
                // aligned with the still-recoverable persisted state.
                delivery.setStatus(originalStatus);
                delivery.setResponseStatus(originalResponseStatus);
                delivery.setResponseTimeMs(originalResponseTimeMs);
                delivery.setErrorMessage(originalErrorMessage);
                delivery.setCompletedAt(originalCompletedAt);
                delivery.setNextRetryAt(originalNextRetryAt);
                throw transactionFailure;
            }
            if (!update.deliveryFound()) return;
            if (update.disabledNow()) {
                if (update.previousStatus() != null) {
                    disableTask.event().getData().put("previous_status", update.previousStatus().name());
                }
                metrics.recordSubscriptionAutoDisabled(sub.getTenantId(), REASON_CONSECUTIVE_FAILURES);
                LOG.warn("Webhook subscription auto-disabled: subscription_id={} tenant_id={} failures={} disable_after={} delivery_id={} event_id={} trace_id={}",
                        safe(sub.getSubscriptionId()), safe(sub.getTenantId()), update.consecutiveFailures(),
                        defaultDisableAfter, safe(delivery.getDeliveryId()), safe(delivery.getEventId()),
                        safe(delivery.getTraceId()));
                publishOutboxed(disableTask);
            }
            logPermanentFailure(delivery, result.getErrorMessage());
            publishOutboxed(failureTask);
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

    private boolean markFailed(Delivery delivery, String errorMessage) {
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setErrorMessage(errorMessage);
        delivery.setCompletedAt(Instant.now());
        delivery.setNextRetryAt(null);
        deliveryRepository.update(delivery);
        logPermanentFailure(delivery, errorMessage);
        return true;
    }

    private void logPermanentFailure(Delivery delivery, String errorMessage) {
        LOG.warn("Webhook delivery permanently failed: delivery_id={} event_id={} event_type={} subscription_id={} attempts={} response_status={} trace_id={} error={}",
                safe(delivery.getDeliveryId()), safe(delivery.getEventId()), safe(delivery.getEventType()),
                safe(delivery.getSubscriptionId()), delivery.getAttempts(), delivery.getResponseStatus(),
                safe(delivery.getTraceId()), safe(errorMessage));
    }

    /** Build the webhook.disabled Event staged atomically with auto-disable. */
    private DispatcherEventTask buildWebhookDisabledTask(Subscription sub, Delivery delivery,
                                                         String requestId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subscription_id", sub.getSubscriptionId());
        data.put("tenant_id", sub.getTenantId());
        data.put("new_status", WebhookStatus.DISABLED.name());
        data.put("changed_fields", List.of());
        data.put("disable_reason", "consecutive_failures_exceeded_threshold");

        String correlationId = "webhook_auto_disable:" + sub.getSubscriptionId()
                + ":" + delivery.getDeliveryId();
        Event event = Event.builder()
                .eventType(EventType.WEBHOOK_DISABLED.getValue())
                .category(EventCategory.WEBHOOK.getValue())
                .tenantId(sub.getTenantId())
                .actor(Actor.builder().type(ActorType.SYSTEM.getValue()).build())
                .source("cycles-events")
                .data(data)
                .correlationId(correlationId)
                .requestId(requestId)
                .traceId(delivery.getTraceId())
                .build();
        return DispatcherEventTask.create("webhook-disabled:" + sub.getSubscriptionId()
                + ":" + delivery.getDeliveryId(), event);
    }

    /** Build the terminal meta-event staged atomically with delivery FAILED. */
    private DispatcherEventTask buildDeliveryFailedTask(Subscription sub, Delivery delivery,
                                                         TransportResult result, String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subscription_id", sub.getSubscriptionId());
        details.put("tenant_id", sub.getTenantId());
        details.put("delivery_id", delivery.getDeliveryId());
        details.put("event_id", delivery.getEventId());
        if (delivery.getEventType() != null) {
            details.put("event_type", delivery.getEventType());
        }
        details.put("attempts", delivery.getAttempts());
        if (result.getStatusCode() > 0) {
            details.put("last_response_status", result.getStatusCode());
        }
        if (result.getErrorMessage() != null) {
            details.put("error", result.getErrorMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("component", "webhook_dispatcher");
        data.put("message", "Webhook delivery failed after " + delivery.getAttempts()
                + " attempts: subscription " + sub.getSubscriptionId());
        data.put("severity", "warning");
        data.put("details", details);

        Event event = Event.builder()
                .eventType(EventType.SYSTEM_WEBHOOK_DELIVERY_FAILED.getValue())
                .category(EventCategory.SYSTEM.getValue())
                .tenantId("__system__")
                .actor(Actor.builder().type(ActorType.SYSTEM.getValue()).build())
                .source("cycles-events")
                .data(data)
                .correlationId("webhook_delivery_failed:" + sub.getSubscriptionId()
                        + ":" + delivery.getDeliveryId())
                .requestId(requestId)
                .traceId(delivery.getTraceId())
                .build();
        return DispatcherEventTask.create("delivery-failed:" + sub.getSubscriptionId()
                + ":" + delivery.getDeliveryId(), event);
    }

    /** Best-effort inline publish; the atomic outbox remains on any failure. */
    private void publishOutboxed(DispatcherEventTask task) {
        try {
            eventRepository.save(task.event());
            if (eventRepository.ackDispatcherEvent(task.taskId())) {
                metrics.recordDispatcherEventPublished(task.event().getEventType());
            }
        } catch (Exception e) {
            metrics.recordDispatcherEventDeferred(task.event().getEventType(), "inline_publish_failure");
            LOG.warn("Dispatcher Event inline publish failed; durable outbox will retry: task_id={} event_id={} event_type={} correlation_id={} trace_id={}",
                    safe(task.taskId()), safe(task.event().getEventId()), safe(task.event().getEventType()),
                    safe(task.event().getCorrelationId()), safe(task.event().getTraceId()), e);
        }
    }

    private static String effectiveTraceId(Delivery delivery, Event event) {
        if (delivery.getTraceId() != null) {
            return delivery.getTraceId();
        }
        return event.getTraceId();
    }

    private static String failureReason(int statusCode) {
        if (statusCode >= 400 && statusCode < 500) return REASON_HTTP_4XX;
        if (statusCode >= 500 && statusCode < 600) return REASON_HTTP_5XX;
        return REASON_TRANSPORT_ERROR;
    }
}
