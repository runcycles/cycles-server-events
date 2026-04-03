package io.runcycles.events.service;

import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.DeliveryStatus;
import io.runcycles.events.model.Event;
import io.runcycles.events.model.RetryPolicy;
import io.runcycles.events.model.Subscription;
import io.runcycles.events.model.WebhookStatus;
import io.runcycles.events.repository.DeliveryQueueRepository;
import io.runcycles.events.repository.DeliveryRepository;
import io.runcycles.events.repository.EventRepository;
import io.runcycles.events.repository.SubscriptionRepository;
import io.runcycles.events.transport.Transport;
import io.runcycles.events.transport.TransportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class DeliveryHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryHandler.class);

    private final DeliveryRepository deliveryRepository;
    private final EventRepository eventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final DeliveryQueueRepository queueRepository;
    private final Transport transport;
    private final long maxDeliveryAgeMs;

    public DeliveryHandler(DeliveryRepository deliveryRepository, EventRepository eventRepository,
                           SubscriptionRepository subscriptionRepository, DeliveryQueueRepository queueRepository,
                           Transport transport,
                           @Value("${dispatch.max-delivery-age-ms:86400000}") long maxDeliveryAgeMs) {
        this.deliveryRepository = deliveryRepository;
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.queueRepository = queueRepository;
        this.transport = transport;
        this.maxDeliveryAgeMs = maxDeliveryAgeMs;
    }

    public void handle(String deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId);
        if (delivery == null) {
            LOG.warn("Delivery not found: {}", deliveryId);
            return;
        }

        DeliveryStatus status = delivery.getStatus();
        if (status != DeliveryStatus.PENDING && status != DeliveryStatus.RETRYING) {
            LOG.debug("Delivery {} already in state {}, skipping", deliveryId, status);
            return;
        }

        // Skip stale deliveries (e.g., after prolonged outage)
        Instant attemptedAt = delivery.getAttemptedAt();
        if (attemptedAt == null) {
            LOG.warn("Delivery {} has null attemptedAt, treating as fresh", deliveryId);
            attemptedAt = Instant.now();
        }
        long ageMs = System.currentTimeMillis() - attemptedAt.toEpochMilli();
        if (ageMs > maxDeliveryAgeMs) {
            markFailed(delivery, "Delivery expired: " + (ageMs / 3600000) + "h old (max " + (maxDeliveryAgeMs / 3600000) + "h)");
            return;
        }

        Event event = eventRepository.findById(delivery.getEventId());
        if (event == null) {
            markFailed(delivery, "Event not found: " + delivery.getEventId());
            return;
        }

        Subscription sub = subscriptionRepository.findById(delivery.getSubscriptionId());
        if (sub == null) {
            markFailed(delivery, "Subscription not found");
            return;
        }
        if (sub.getStatus() != WebhookStatus.ACTIVE) {
            markFailed(delivery, "Subscription not active: " + sub.getStatus());
            return;
        }

        String secret = subscriptionRepository.getSigningSecret(delivery.getSubscriptionId());

        delivery.setAttempts(delivery.getAttempts() != null ? delivery.getAttempts() + 1 : 1);
        TransportResult result = transport.deliver(event, sub, secret);

        if (result.isSuccess()) {
            handleSuccess(delivery, sub, result);
        } else {
            handleFailure(delivery, sub, result);
        }
    }

    private void handleSuccess(Delivery delivery, Subscription sub, TransportResult result) {
        delivery.setStatus(DeliveryStatus.SUCCESS);
        delivery.setResponseStatus(result.getStatusCode());
        delivery.setResponseTimeMs(result.getLatencyMs());
        delivery.setCompletedAt(Instant.now());
        deliveryRepository.update(delivery);

        sub.setConsecutiveFailures(0);
        sub.setLastTriggeredAt(Instant.now());
        sub.setLastSuccessAt(Instant.now());
        subscriptionRepository.update(sub);

        LOG.info("Delivery {} succeeded (HTTP {})", delivery.getDeliveryId(), result.getStatusCode());
    }

    private void handleFailure(Delivery delivery, Subscription sub, TransportResult result) {
        RetryPolicy policy = sub.getRetryPolicy() != null ? sub.getRetryPolicy() : RetryPolicy.builder().build();
        int maxRetries = policy.getMaxRetries() != null ? policy.getMaxRetries() : 5;

        if (delivery.getAttempts() > maxRetries) {
            markFailed(delivery, result.getErrorMessage());
            incrementConsecutiveFailures(sub);
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

        LOG.info("Delivery {} failed (attempt {}/{}), retry at {}",
                delivery.getDeliveryId(), delivery.getAttempts(), maxRetries, delivery.getNextRetryAt());
    }

    private void markFailed(Delivery delivery, String errorMessage) {
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setErrorMessage(errorMessage);
        delivery.setCompletedAt(Instant.now());
        deliveryRepository.update(delivery);
        LOG.warn("Delivery {} permanently failed: {}", delivery.getDeliveryId(), errorMessage);
    }

    private void incrementConsecutiveFailures(Subscription sub) {
        int failures = (sub.getConsecutiveFailures() != null ? sub.getConsecutiveFailures() : 0) + 1;
        sub.setConsecutiveFailures(failures);
        sub.setLastFailureAt(Instant.now());
        sub.setLastTriggeredAt(Instant.now());

        int disableAfter = sub.getDisableAfterFailures() != null ? sub.getDisableAfterFailures() : 10;
        if (failures >= disableAfter) {
            sub.setStatus(WebhookStatus.DISABLED);
            LOG.warn("Subscription {} auto-disabled after {} consecutive failures",
                    sub.getSubscriptionId(), failures);
        }
        subscriptionRepository.update(sub);
    }
}
