package io.runcycles.events.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.events.metrics.CyclesMetrics;
import io.runcycles.events.model.*;
import io.runcycles.events.repository.DeliveryQueueRepository;
import io.runcycles.events.repository.DeliveryRepository;
import io.runcycles.events.repository.EventRepository;
import io.runcycles.events.repository.SubscriptionRepository;
import io.runcycles.events.transport.Transport;
import io.runcycles.events.transport.TransportResult;
import io.runcycles.events.validation.EventPayloadValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryHandlerTest {

    @Mock private DeliveryRepository deliveryRepository;
    @Mock private EventRepository eventRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private DeliveryQueueRepository queueRepository;
    @Mock private Transport transport;

    private SimpleMeterRegistry registry;
    private CyclesMetrics metrics;
    private EventPayloadValidator validator;
    private DeliveryHandler handler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CyclesMetrics(registry);
        validator = new EventPayloadValidator(metrics);
        handler = new DeliveryHandler(deliveryRepository, eventRepository,
                subscriptionRepository, queueRepository, transport, metrics, validator, 86400000L);
    }

    private double counter(String name, String... tags) {
        io.micrometer.core.instrument.Counter c = registry.find(name).tags(tags).counter();
        return c == null ? 0.0 : c.count();
    }

    private Delivery pendingDelivery() {
        return Delivery.builder()
                .deliveryId("del-1")
                .subscriptionId("sub-1")
                .eventId("evt-1")
                .eventType("tenant.created")
                .status(DeliveryStatus.PENDING)
                .attempts(0)
                .build();
    }

    private Event testEvent() {
        return Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
    }

    private Subscription activeSubscription() {
        return Subscription.builder()
                .subscriptionId("sub-1")
                .tenantId("t-1")
                .url("https://example.com/webhook")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of("tenant.created"))
                .consecutiveFailures(0)
                .disableAfterFailures(10)
                .retryPolicy(RetryPolicy.builder().build())
                .build();
    }

    private TransportResult successResult() {
        return TransportResult.builder().success(true).statusCode(200).latencyMs(50).build();
    }

    private TransportResult failureResult() {
        return TransportResult.builder().success(false).statusCode(500).latencyMs(100)
                .errorMessage("HTTP 500").build();
    }

    // --- Happy path ---

    @Test
    void handle_successfulDelivery() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), eq("secret"))).thenReturn(successResult());

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getCompletedAt()).isNotNull();
        assertThat(delivery.getResponseStatus()).isEqualTo(200);
        verify(deliveryRepository).update(delivery);
        // Partial update: resets consecutive failures, sets success timestamps
        verify(subscriptionRepository).updateDeliveryState(
                eq("sub-1"), eq(0), any(Instant.class), any(Instant.class), isNull(), isNull());
    }

    @Test
    void handle_retryingDelivery_succeeds() {
        Delivery delivery = pendingDelivery();
        delivery.setStatus(DeliveryStatus.RETRYING);
        delivery.setAttempts(2);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), isNull())).thenReturn(successResult());

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        assertThat(delivery.getAttempts()).isEqualTo(3);
    }

    // --- Delivery not found / wrong status ---

    @Test
    void handle_deliveryNotFound() {
        when(deliveryRepository.findById("del-missing")).thenReturn(null);

        handler.handle("del-missing");

        verify(eventRepository, never()).findById(anyString());
        verify(transport, never()).deliver(any(), any(), any());
    }

    @Test
    void handle_deliveryAlreadySuccess() {
        Delivery delivery = pendingDelivery();
        delivery.setStatus(DeliveryStatus.SUCCESS);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);

        handler.handle("del-1");

        verify(eventRepository, never()).findById(anyString());
        verify(transport, never()).deliver(any(), any(), any());
    }

    @Test
    void handle_deliveryAlreadyFailed() {
        Delivery delivery = pendingDelivery();
        delivery.setStatus(DeliveryStatus.FAILED);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);

        handler.handle("del-1");

        verify(transport, never()).deliver(any(), any(), any());
    }

    // --- Missing dependencies ---

    @Test
    void handle_eventNotFound() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(null);

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorMessage()).contains("Event not found");
        verify(deliveryRepository).update(delivery);
    }

    @Test
    void handle_subscriptionNotFound() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        when(subscriptionRepository.findById("sub-1")).thenReturn(null);

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorMessage()).contains("Subscription not found");
    }

    @Test
    void handle_subscriptionNotActive_paused() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setStatus(WebhookStatus.PAUSED);
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorMessage()).contains("not active");
    }

    @Test
    void handle_subscriptionNotActive_disabled() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setStatus(WebhookStatus.DISABLED);
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    // --- Failure + retry ---

    @Test
    void handle_transportFailure_schedulesRetry() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getNextRetryAt()).isNotNull();
        verify(queueRepository).scheduleRetry(eq("del-1"), anyLong());
    }

    @Test
    void handle_transportFailure_exponentialBackoff() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(2); // will become 3 after increment
        delivery.setStatus(DeliveryStatus.RETRYING);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setRetryPolicy(RetryPolicy.builder()
                .initialDelayMs(1000)
                .backoffMultiplier(2.0)
                .maxDelayMs(60000)
                .maxRetries(5)
                .build());
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        // After increment: attempts=3, delay = 1000 * 2^(3-1) = 4000
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(queueRepository).scheduleRetry(eq("del-1"), captor.capture());
        long scheduledAt = captor.getValue();
        long now = System.currentTimeMillis();
        // delay should be ~4000ms (1000 * 2^2)
        assertThat(scheduledAt - now).isBetween(3500L, 5000L);
    }

    @Test
    void handle_transportFailure_maxDelayCapped() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(9); // will become 10
        delivery.setStatus(DeliveryStatus.RETRYING);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setRetryPolicy(RetryPolicy.builder()
                .initialDelayMs(1000)
                .backoffMultiplier(2.0)
                .maxDelayMs(5000)
                .maxRetries(20)
                .build());
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(queueRepository).scheduleRetry(eq("del-1"), captor.capture());
        long scheduledAt = captor.getValue();
        long now = System.currentTimeMillis();
        // delay capped at maxDelayMs=5000
        assertThat(scheduledAt - now).isBetween(4500L, 6000L);
    }

    @Test
    void handle_transportFailure_maxRetriesExhausted() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5); // will become 6 > maxRetries(5)
        delivery.setStatus(DeliveryStatus.RETRYING);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getCompletedAt()).isNotNull();
        verify(queueRepository, never()).scheduleRetry(anyString(), anyLong());
    }

    @Test
    void handle_transportFailure_defaultRetryPolicy() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setRetryPolicy(null); // use defaults
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RETRYING);
        verify(queueRepository).scheduleRetry(eq("del-1"), anyLong());
    }

    // --- Auto-disable ---

    @Test
    void handle_consecutiveFailures_incrementsCounter() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5); // will become 6 > maxRetries(5), exhausts retries
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(3);
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        // Partial update: failures=4, not yet disabled (null status = no change)
        verify(subscriptionRepository).updateDeliveryState(
                eq("sub-1"), eq(4), any(Instant.class), isNull(), any(Instant.class), isNull());
    }

    @Test
    void handle_consecutiveFailures_autoDisables() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5); // will become 6 > maxRetries(5), exhausts retries
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(9); // will become 10 == disableAfterFailures
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        // Partial update: failures=10, status=DISABLED
        verify(subscriptionRepository).updateDeliveryState(
                eq("sub-1"), eq(10), any(Instant.class), isNull(), any(Instant.class),
                eq(WebhookStatus.DISABLED));
    }

    @Test
    void handle_consecutiveFailures_defaultThreshold10() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setDisableAfterFailures(null); // defaults to 10
        sub.setConsecutiveFailures(9);
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        verify(subscriptionRepository).updateDeliveryState(
                eq("sub-1"), eq(10), any(Instant.class), isNull(), any(Instant.class),
                eq(WebhookStatus.DISABLED));
    }

    @Test
    void handle_consecutiveFailures_nullInitialCount() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(null); // null starts at 0
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        // null → 0 + 1 = 1
        verify(subscriptionRepository).updateDeliveryState(
                eq("sub-1"), eq(1), any(Instant.class), isNull(), any(Instant.class), isNull());
    }

    // --- Signing secret ---

    @Test
    void handle_noSigningSecret() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), isNull())).thenReturn(successResult());

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        verify(transport).deliver(any(), eq(sub), isNull());
    }

    // --- Null attempts ---

    @Test
    void handle_nullAttempts_setsToOne() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(null);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(successResult());

        handler.handle("del-1");

        assertThat(delivery.getAttempts()).isEqualTo(1);
    }

    // --- Staleness check ---

    @Test
    void handle_staleDelivery_marksFailed() {
        Delivery delivery = pendingDelivery();
        delivery.setAttemptedAt(Instant.now().minusMillis(86400001)); // 24h + 1ms ago
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorMessage()).contains("expired");
        verify(transport, never()).deliver(any(), any(), any());
    }

    @Test
    void handle_freshDelivery_notStale() {
        Delivery delivery = pendingDelivery();
        delivery.setAttemptedAt(Instant.now().minusMillis(1000)); // 1s ago
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(successResult());

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
    }

    // --- Metrics (v0.1.25.6) ---

    @Test
    void metrics_successIncrementsAttemptsAndSuccess() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("s");
        when(transport.deliver(any(), any(), any())).thenReturn(successResult());

        handler.handle("del-1");

        assertThat(counter(CyclesMetrics.DELIVERY_ATTEMPTS,
                "tenant", "t-1", "event_type", "tenant.created")).isEqualTo(1.0);
        assertThat(counter(CyclesMetrics.DELIVERY_SUCCESS,
                "tenant", "t-1", "event_type", "tenant.created",
                "status_code_family", "2xx")).isEqualTo(1.0);
        assertThat(registry.find(CyclesMetrics.DELIVERY_LATENCY)
                .tag("outcome", "success").timer().count()).isEqualTo(1L);
    }

    @Test
    void metrics_http5xxFailureIncrementsFailedAndRetried() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult()); // 500

        handler.handle("del-1");

        assertThat(counter(CyclesMetrics.DELIVERY_FAILED,
                "tenant", "t-1", "event_type", "tenant.created", "reason", "http_5xx"))
                .isEqualTo(1.0);
        assertThat(counter(CyclesMetrics.DELIVERY_RETRIED,
                "tenant", "t-1", "event_type", "tenant.created")).isEqualTo(1.0);
    }

    @Test
    void metrics_http4xxFailureTaggedAs4xx() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        TransportResult r = TransportResult.builder().success(false).statusCode(422).latencyMs(25)
                .errorMessage("unprocessable").build();
        when(transport.deliver(any(), any(), any())).thenReturn(r);

        handler.handle("del-1");

        assertThat(counter(CyclesMetrics.DELIVERY_FAILED,
                "tenant", "t-1", "event_type", "tenant.created", "reason", "http_4xx"))
                .isEqualTo(1.0);
    }

    @Test
    void metrics_transportErrorTaggedAsTransportError() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        TransportResult r = TransportResult.builder().success(false).statusCode(0).latencyMs(0)
                .errorMessage("connection refused").build();
        when(transport.deliver(any(), any(), any())).thenReturn(r);

        handler.handle("del-1");

        assertThat(counter(CyclesMetrics.DELIVERY_FAILED,
                "tenant", "t-1", "event_type", "tenant.created", "reason", "transport_error"))
                .isEqualTo(1.0);
    }

    @Test
    void metrics_staleIncrementsStaleOnly() {
        Delivery delivery = pendingDelivery();
        delivery.setAttemptedAt(Instant.now().minusMillis(86400001));
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);

        handler.handle("del-1");

        assertThat(counter(CyclesMetrics.DELIVERY_STALE,
                "tenant", CyclesMetrics.TAG_UNKNOWN)).isEqualTo(1.0);
        // stale path does NOT double-count into failed_total
        assertThat(registry.find(CyclesMetrics.DELIVERY_FAILED).counters()).isEmpty();
    }

    @Test
    void metrics_eventNotFoundTaggedAsEventNotFound() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(null);

        handler.handle("del-1");

        assertThat(counter(CyclesMetrics.DELIVERY_FAILED,
                "tenant", CyclesMetrics.TAG_UNKNOWN,
                "event_type", "tenant.created",
                "reason", "event_not_found")).isEqualTo(1.0);
    }

    @Test
    void metrics_subscriptionNotFoundTaggedAsSubscriptionNotFound() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        when(subscriptionRepository.findById("sub-1")).thenReturn(null);

        handler.handle("del-1");

        assertThat(counter(CyclesMetrics.DELIVERY_FAILED,
                "tenant", CyclesMetrics.TAG_UNKNOWN,
                "event_type", "tenant.created",
                "reason", "subscription_not_found")).isEqualTo(1.0);
    }

    @Test
    void metrics_subscriptionInactiveTaggedAsInactive() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setStatus(WebhookStatus.PAUSED);
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);

        handler.handle("del-1");

        assertThat(counter(CyclesMetrics.DELIVERY_FAILED,
                "tenant", "t-1",
                "event_type", "tenant.created",
                "reason", "subscription_inactive")).isEqualTo(1.0);
    }

    @Test
    void metrics_autoDisabledIncrementsCounter() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5); // becomes 6 > maxRetries(5), exhausts retries
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(9); // will become 10 == disableAfterFailures
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        assertThat(counter(CyclesMetrics.SUBSCRIPTION_AUTO_DISABLED,
                "tenant", "t-1", "reason", "consecutive_failures")).isEqualTo(1.0);
    }

    @Test
    void validator_warningOnMalformedEvent_doesNotBlockDelivery() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        // Malformed event: missing required tenant_id + source
        Event malformed = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .build();
        when(eventRepository.findById("evt-1")).thenReturn(malformed);
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(successResult());

        handler.handle("del-1");

        // Delivery still succeeded (validation never blocks)
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        // Validator warning fired
        assertThat(counter(CyclesMetrics.EVENT_VALIDATION_WARNINGS,
                "event_type", "tenant.created", "rule", "missing_required")).isEqualTo(1.0);
    }

    @Test
    void metrics_noAutoDisableBelowThreshold() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(3); // becomes 4, still below 10
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        assertThat(registry.find(CyclesMetrics.SUBSCRIPTION_AUTO_DISABLED).counters()).isEmpty();
    }
}
