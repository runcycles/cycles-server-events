package io.runcycles.events.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.events.metrics.CyclesMetrics;
import io.runcycles.events.model.*;
import io.runcycles.events.repository.DeliveryQueueRepository;
import io.runcycles.events.repository.DeliveryQueueRepository.ClaimedDelivery;
import io.runcycles.events.repository.DeliveryRepository;
import io.runcycles.events.repository.EventRepository;
import io.runcycles.events.repository.SubscriptionRepository;
import io.runcycles.events.repository.SubscriptionRepository.DeliverySuccessUpdate;
import io.runcycles.events.repository.SubscriptionRepository.TerminalFailureUpdate;
import io.runcycles.events.transport.Transport;
import io.runcycles.events.transport.TransportResult;
import io.runcycles.events.transport.webhook.WebhookUrlGuard;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryHandlerTest {

    @Mock private DeliveryRepository deliveryRepository;
    @Mock private EventRepository eventRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private DeliveryQueueRepository queueRepository;
    @Mock private Transport transport;
    @Mock private WebhookUrlGuard urlGuard;

    private SimpleMeterRegistry registry;
    private CyclesMetrics metrics;
    private EventPayloadValidator validator;
    private DeliveryHandler handler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CyclesMetrics(registry, true); // tenant tag enabled
        validator = new EventPayloadValidator(metrics);
        handler = new DeliveryHandler(deliveryRepository, eventRepository,
                subscriptionRepository, queueRepository, transport, metrics, validator,
                urlGuard, 86400000L);
        lenient().when(deliveryRepository.updateOwned(any(Delivery.class), any(ClaimedDelivery.class)))
                .thenReturn(true);
        lenient().when(deliveryRepository.updateOwnedAndScheduleRetry(
                any(Delivery.class), any(ClaimedDelivery.class), anyLong())).thenReturn(true);
        lenient().when(queueRepository.scheduleRetryOwned(any(ClaimedDelivery.class), anyLong()))
                .thenReturn(true);
        lenient().when(subscriptionRepository.finalizeDeliverySuccess(
                        anyString(), any(ClaimedDelivery.class), any(Delivery.class), any()))
                .thenReturn(new DeliverySuccessUpdate(true, true, true));
        lenient().when(subscriptionRepository.finalizeDeliveryFailure(
                        anyString(), any(ClaimedDelivery.class), any(Delivery.class), any(),
                        anyInt(), any(), any()))
                .thenReturn(new TerminalFailureUpdate(true, true, true, 1, false,
                        WebhookStatus.ACTIVE));
        // Mockito's default null return = "URL allowed"; individual tests
        // stub a violation reason to exercise the blocked path.
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
                .category("tenant")
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

    @Test
    void supersededSuccessIsNotCounted() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(successResult());
        when(subscriptionRepository.finalizeDeliverySuccess(anyString(), any(), any(), any()))
                .thenReturn(new DeliverySuccessUpdate(false, true, true));

        handler.handle(claimed("del-1"));

        assertThat(counter(CyclesMetrics.DELIVERY_SUCCESS,
                "tenant", "t-1", "event_type", "tenant.created",
                "status_code_family", "2xx")).isZero();
    }

    @Test
    void supersededRetryWithNullPolicyFieldsIsNotCountedOrScheduled() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setRetryPolicy(RetryPolicy.builder().maxRetries(null).initialDelayMs(null)
                .backoffMultiplier(null).maxDelayMs(null).build());
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());
        when(deliveryRepository.updateOwnedAndScheduleRetry(any(), any(), anyLong()))
                .thenReturn(false);

        handler.handle(claimed("del-1"));

        assertThat(counter(CyclesMetrics.DELIVERY_RETRIED,
                "tenant", "t-1", "event_type", "tenant.created")).isZero();
    }

    @Test
    void supersededRetryScheduleRestorationIsIgnored() {
        Delivery delivery = pendingDelivery();
        delivery.setStatus(DeliveryStatus.RETRYING);
        delivery.setNextRetryAt(Instant.now().plusSeconds(60));
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(queueRepository.scheduleRetryOwned(any(), anyLong())).thenReturn(false);

        handler.handle(claimed("del-1"));

        verify(eventRepository, never()).findById(anyString());
    }

    @Test
    void supersededPolicyFailureDoesNotIncrementOutcomeMetric() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(null);
        when(deliveryRepository.updateOwned(any(), any())).thenReturn(false);

        handler.handle(claimed("del-1"));

        assertThat(counter(CyclesMetrics.DELIVERY_FAILED,
                "tenant", CyclesMetrics.TAG_UNKNOWN, "event_type", "tenant.created",
                "reason", DeliveryHandler.REASON_EVENT_NOT_FOUND)).isZero();
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
        when(transport.deliver(any(), any(), eq("secret"), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getCompletedAt()).isNotNull();
        assertThat(delivery.getResponseStatus()).isEqualTo(200);
        verify(subscriptionRepository).finalizeDeliverySuccess(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(Instant.class));
    }

    @Test
    void handle_retryingDelivery_succeeds() {
        Delivery delivery = pendingDelivery();
        delivery.setStatus(DeliveryStatus.RETRYING);
        delivery.setAttempts(2);
        delivery.setErrorMessage("previous 503");
        delivery.setNextRetryAt(Instant.now().minusSeconds(1));
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), eq("secret"), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        assertThat(delivery.getAttempts()).isEqualTo(3);
        assertThat(delivery.getErrorMessage()).isNull();
        assertThat(delivery.getNextRetryAt()).isNull();
    }

    private static ClaimedDelivery claimed(String deliveryId) {
        return new ClaimedDelivery(deliveryId, "claim-token");
    }

    @Test
    void handle_recoveredRetryBeforeBackoffRestoresScheduleWithoutSending() {
        Delivery delivery = pendingDelivery();
        delivery.setStatus(DeliveryStatus.RETRYING);
        delivery.setAttempts(2);
        delivery.setNextRetryAt(Instant.now().plusSeconds(60));
        long retryAt = delivery.getNextRetryAt().toEpochMilli();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);

        handler.handle(claimed("del-1"));

        verify(queueRepository).scheduleRetryOwned(claimed("del-1"), retryAt);
        verify(eventRepository, never()).findById(anyString());
        verify(transport, never()).deliver(any(), any(), any(), any());
        assertThat(delivery.getAttempts()).isEqualTo(2);
    }

    @Test
    void constructorRejectsNonPositiveMaxDeliveryAge() {
        assertThatThrownBy(() -> new DeliveryHandler(deliveryRepository, eventRepository,
                subscriptionRepository, queueRepository, transport, metrics, validator, urlGuard, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delivery age");
    }

    @Test
    void handle_successfulDelivery_subscriptionStateFailureDoesNotPersistTerminalDelivery() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), eq("secret"), any())).thenReturn(successResult());
        when(subscriptionRepository.finalizeDeliverySuccess(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any()))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> handler.handle(claimed("del-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis down");

        verify(deliveryRepository, never()).updateOwned(any(), any());
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
    }

    // --- Delivery not found / wrong status ---

    @Test
    void handle_deliveryNotFound() {
        when(deliveryRepository.findById("del-missing")).thenReturn(null);

        handler.handle(claimed("del-missing"));

        verify(eventRepository, never()).findById(anyString());
        verify(transport, never()).deliver(any(), any(), any(), any());
    }

    @Test
    void handle_deliveryAlreadySuccess() {
        Delivery delivery = pendingDelivery();
        delivery.setStatus(DeliveryStatus.SUCCESS);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);

        handler.handle(claimed("del-1"));

        verify(eventRepository, never()).findById(anyString());
        verify(transport, never()).deliver(any(), any(), any(), any());
    }

    @Test
    void handle_deliveryAlreadyFailed() {
        Delivery delivery = pendingDelivery();
        delivery.setStatus(DeliveryStatus.FAILED);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);

        handler.handle(claimed("del-1"));

        verify(transport, never()).deliver(any(), any(), any(), any());
    }

    // --- Missing dependencies ---

    @Test
    void handle_eventNotFound() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(null);

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorMessage()).contains("Event not found");
        verify(deliveryRepository).updateOwned(eq(delivery), eq(claimed("del-1")));
    }

    @Test
    void handle_subscriptionNotFound() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        when(subscriptionRepository.findById("sub-1")).thenReturn(null);

        handler.handle(claimed("del-1"));

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

        handler.handle(claimed("del-1"));

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

        handler.handle(claimed("del-1"));

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
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getNextRetryAt()).isNotNull();
        verify(deliveryRepository).updateOwnedAndScheduleRetry(eq(delivery), eq(claimed("del-1")), anyLong());
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
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        // After increment: attempts=3, delay = 1000 * 2^(3-1) = 4000
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(deliveryRepository).updateOwnedAndScheduleRetry(eq(delivery), eq(claimed("del-1")), captor.capture());
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
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(deliveryRepository).updateOwnedAndScheduleRetry(eq(delivery), eq(claimed("del-1")), captor.capture());
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
        delivery.setResponseStatus(429);
        delivery.setNextRetryAt(Instant.now().minusSeconds(1));
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getCompletedAt()).isNotNull();
        assertThat(delivery.getResponseStatus()).isEqualTo(500);
        assertThat(delivery.getResponseTimeMs()).isEqualTo(100);
        assertThat(delivery.getNextRetryAt()).isNull();
        verify(deliveryRepository, never()).updateOwnedAndScheduleRetry(any(), any(), anyLong());
    }

    @Test
    void handle_transportFailure_defaultRetryPolicy() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setRetryPolicy(null); // use defaults
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RETRYING);
        verify(deliveryRepository).updateOwnedAndScheduleRetry(eq(delivery), eq(claimed("del-1")), anyLong());
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
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        verify(subscriptionRepository).finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(Instant.class), eq(10), any(), any());
    }

    @Test
    void handle_consecutiveFailures_autoDisables() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5); // will become 6 > maxRetries(5), exhausts retries
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(10); // will become 11 > disableAfterFailures
        when(subscriptionRepository.finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(), eq(10), any(), any()))
                .thenReturn(new TerminalFailureUpdate(true, true, true, 11, true, WebhookStatus.ACTIVE));
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        verify(subscriptionRepository).finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(Instant.class), eq(10), any(), any());
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
        when(subscriptionRepository.finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(), eq(10), any(), any()))
                .thenReturn(new TerminalFailureUpdate(true, true, true, 11, true, WebhookStatus.ACTIVE));
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        verify(subscriptionRepository).finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(Instant.class), eq(10), any(), any());
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
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        verify(subscriptionRepository).finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(Instant.class), eq(10), any(), any());
    }

    // --- Signing secret ---

    @Test
    void handle_noSigningSecret() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        Event event = testEvent();
        event.setTraceId("0123456789abcdef0123456789abcdef");
        when(eventRepository.findById("evt-1")).thenReturn(event);
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);

        assertThatThrownBy(() -> handler.handle(claimed("del-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not yet available");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getErrorMessage()).isNull();
        assertThat(delivery.getTraceId()).isEqualTo("0123456789abcdef0123456789abcdef");
        verify(deliveryRepository, never()).updateOwned(any(), any());
        verify(transport, never()).deliver(any(), any(), any(), any());
        assertThat(counter(CyclesMetrics.DELIVERY_FAILED,
                "tenant", "t-1", "event_type", "tenant.created",
                "reason", "missing_signing_secret")).isEqualTo(1.0);
    }

    @Test
    void handle_signingSecretFailure_failsClosedBeforeTransport() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1"))
                .thenThrow(new IllegalStateException("decrypt failed"));

        assertThatThrownBy(() -> handler.handle(claimed("del-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decrypt failed");
        verify(transport, never()).deliver(any(), any(), any(), any());
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
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getAttempts()).isEqualTo(1);
    }

    // --- Staleness check ---

    @Test
    void handle_staleDelivery_marksFailed() {
        Delivery delivery = pendingDelivery();
        delivery.setAttemptedAt(Instant.now().minusMillis(86400001)); // 24h + 1ms ago
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorMessage()).contains("expired");
        verify(transport, never()).deliver(any(), any(), any(), any());
    }

    @Test
    void handle_freshDelivery_notStale() {
        Delivery delivery = pendingDelivery();
        delivery.setAttemptedAt(Instant.now().minusMillis(1000)); // 1s ago
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

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
        when(transport.deliver(any(), any(), any(), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

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
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult()); // 500

        handler.handle(claimed("del-1"));

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
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        TransportResult r = TransportResult.builder().success(false).statusCode(422).latencyMs(25)
                .errorMessage("unprocessable").build();
        when(transport.deliver(any(), any(), any(), any())).thenReturn(r);

        handler.handle(claimed("del-1"));

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
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        TransportResult r = TransportResult.builder().success(false).statusCode(0).latencyMs(0)
                .errorMessage("connection refused").build();
        when(transport.deliver(any(), any(), any(), any())).thenReturn(r);

        handler.handle(claimed("del-1"));

        assertThat(counter(CyclesMetrics.DELIVERY_FAILED,
                "tenant", "t-1", "event_type", "tenant.created", "reason", "transport_error"))
                .isEqualTo(1.0);
    }

    @Test
    void metrics_staleIncrementsStaleOnly() {
        Delivery delivery = pendingDelivery();
        delivery.setAttemptedAt(Instant.now().minusMillis(86400001));
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);

        handler.handle(claimed("del-1"));

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

        handler.handle(claimed("del-1"));

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

        handler.handle(claimed("del-1"));

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

        handler.handle(claimed("del-1"));

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
        sub.setConsecutiveFailures(10); // will become 11 > disableAfterFailures
        when(subscriptionRepository.finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(), eq(10), any(), any()))
                .thenReturn(new TerminalFailureUpdate(true, true, true, 11, true, WebhookStatus.ACTIVE));
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

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
                .category("tenant")
                .timestamp(Instant.now())
                .build();
        when(eventRepository.findById("evt-1")).thenReturn(malformed);
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

        // Delivery still succeeded (validation never blocks)
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        // Validator warning fired — parallel tag schema to cycles-server-admin's
        // cycles_admin_events_payload_invalid_total{type, expected_class}
        assertThat(counter(CyclesMetrics.EVENTS_PAYLOAD_INVALID,
                "type", "tenant.created", "rule", "missing_required")).isEqualTo(1.0);
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
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        assertThat(registry.find(CyclesMetrics.SUBSCRIPTION_AUTO_DISABLED).counters()).isEmpty();
    }

    // --- Proactive trace_id stamping (spec v0.1.25.28) ---

    @Test
    void handle_stampsEventTraceIdOntoDelivery() {
        // Admin hasn't populated Delivery.trace_id yet; dispatcher
        // proactively copies it from the Event before persisting, so
        // admin's GET /webhooks/deliveries readback has trace_id
        // without a cross-service round trip.
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        Event e = testEvent();
        e.setTraceId("0123456789abcdef0123456789abcdef");
        when(eventRepository.findById("evt-1")).thenReturn(e);
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getTraceId()).isEqualTo("0123456789abcdef0123456789abcdef");
        verify(subscriptionRepository).finalizeDeliverySuccess(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any());
    }

    @Test
    void handle_preservesAdminAuthoredTraceIdOnDelivery() {
        // If admin has already stamped trace_id (future v0.1.25.31+),
        // the dispatcher MUST NOT overwrite it even if Event carries a
        // different value — admin-authored stamps are authoritative.
        Delivery delivery = pendingDelivery();
        delivery.setTraceId("admin-stamped-aaaaaaaaaaaaaaaaaaa0");
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        Event e = testEvent();
        e.setTraceId("event-trace-bbbbbbbbbbbbbbbbbbbbb0");
        when(eventRepository.findById("evt-1")).thenReturn(e);
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getTraceId()).isEqualTo("admin-stamped-aaaaaaaaaaaaaaaaaaa0");
    }

    // --- webhook.disabled emit on auto-disable (spec v0.1.25.33) ---

    @Test
    void autoDisable_emitsWebhookDisabledEvent_withConformingPayload() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5); // becomes 6 > maxRetries(5), exhausts retries
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(10); // becomes 11 > disableAfterFailures
        when(subscriptionRepository.finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(), eq(10), any(), any()))
                .thenReturn(new TerminalFailureUpdate(true, true, true, 11, true, WebhookStatus.ACTIVE));
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        // Two emits on this path: webhook.disabled (auto-disable) and
        // system.webhook_delivery_failed (retries exhausted).
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository, times(2)).save(captor.capture());
        Event emitted = captor.getAllValues().stream()
                .filter(e -> "webhook.disabled".equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertThat(emitted.getEventType()).isEqualTo("webhook.disabled");
        assertThat(emitted.getCategory()).isEqualTo("webhook");
        assertThat(emitted.getTenantId()).isEqualTo("t-1");
        // scope null matches admin's WebhookAdminController.emitWebhookLifecycleEvent
        // convention on all webhook.* lifecycle emits.
        assertThat(emitted.getScope()).isNull();
        assertThat(emitted.getSource()).isEqualTo("cycles-events");
        assertThat(emitted.getCorrelationId()).isEqualTo("webhook_auto_disable:sub-1:del-1");
        assertThat(emitted.getActor()).isNotNull();
        assertThat(emitted.getActor().getType()).isEqualTo("system");
        assertThat(emitted.getData()).containsEntry("subscription_id", "sub-1");
        assertThat(emitted.getData()).containsEntry("tenant_id", "t-1");
        assertThat(emitted.getData()).containsEntry("previous_status", "ACTIVE");
        assertThat(emitted.getData()).containsEntry("new_status", "DISABLED");
        assertThat(emitted.getData()).containsEntry("changed_fields", List.of());
        assertThat(emitted.getData()).containsEntry("disable_reason",
                "consecutive_failures_exceeded_threshold");
    }

    @Test
    void autoDisable_copiesDeliveryTraceIdOntoEmittedEvent() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5);
        delivery.setTraceId("0123456789abcdef0123456789abcdef");
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(9);
        when(subscriptionRepository.finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(), eq(10), any(), any()))
                .thenReturn(new TerminalFailureUpdate(true, true, true, 11, true, WebhookStatus.ACTIVE));
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(e -> assertThat(e.getTraceId())
                        .isEqualTo("0123456789abcdef0123456789abcdef"));
    }

    @Test
    void belowThreshold_doesNotEmitWebhookDisabled() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(3); // becomes 4, still below 10
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        // Retries exhausted still emits the delivery-failed meta-alert,
        // but no webhook.disabled below the auto-disable threshold.
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType())
                .isEqualTo("system.webhook_delivery_failed");
    }

    // --- system.webhook_delivery_failed emit on retries exhausted (protocol spec retry contract) ---

    @Test
    void retriesExhausted_emitsSystemWebhookDeliveryFailed_withConformingPayload() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5); // becomes 6 > maxRetries(5), exhausts retries
        delivery.setTraceId("0123456789abcdef0123456789abcdef");
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(0); // stays far below auto-disable
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(captor.capture());
        Event emitted = captor.getValue();
        assertThat(emitted.getEventType()).isEqualTo("system.webhook_delivery_failed");
        assertThat(emitted.getCategory()).isEqualTo("system");
        // System events use the __system__ sentinel per the standard event payload schema
        assertThat(emitted.getTenantId()).isEqualTo("__system__");
        assertThat(emitted.getSource()).isEqualTo("cycles-events");
        assertThat(emitted.getActor()).isNotNull();
        assertThat(emitted.getActor().getType()).isEqualTo("system");
        assertThat(emitted.getCorrelationId()).isEqualTo("webhook_delivery_failed:sub-1:del-1");
        assertThat(emitted.getTraceId()).isEqualTo("0123456789abcdef0123456789abcdef");
        // EventDataSystem shape
        assertThat(emitted.getData()).containsEntry("component", "webhook_dispatcher");
        assertThat(emitted.getData()).containsEntry("severity", "warning");
        assertThat(emitted.getData().get("message").toString())
                .contains("failed after 6 attempts");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> details =
                (java.util.Map<String, Object>) emitted.getData().get("details");
        assertThat(details).containsEntry("subscription_id", "sub-1");
        assertThat(details).containsEntry("tenant_id", "t-1");
        assertThat(details).containsEntry("delivery_id", "del-1");
        assertThat(details).containsEntry("event_id", "evt-1");
        assertThat(details).containsEntry("event_type", "tenant.created");
        assertThat(details).containsEntry("attempts", 6);
        assertThat(details).containsEntry("last_response_status", 500);
        assertThat(details).containsEntry("error", "HTTP 500");
    }

    @Test
    void retriesExhausted_propagatesOriginatingRequestId_ontoEmittedEvents() {
        // Spec (CORRELATION AND TRACING): request_id MUST be populated on every
        // event causally downstream of an HTTP request, including deferred work.
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        Event origin = testEvent();
        origin.setRequestId("req_abc123");
        when(eventRepository.findById("evt-1")).thenReturn(origin);
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(10); // becomes 11 → auto-disable too
        when(subscriptionRepository.finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(), eq(10), any(), any()))
                .thenReturn(new TerminalFailureUpdate(true, true, true, 11, true, WebhookStatus.ACTIVE));
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(e -> assertThat(e.getRequestId()).isEqualTo("req_abc123"));
    }

    // --- delivery-time SSRF guard ---

    @Test
    void ssrfBlocked_permanentFail_noTransport_noConsecutiveFailureIncrement() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(urlGuard.check("https://example.com/webhook"))
                .thenReturn("Resolves to blocked IP: 10.0.0.5");

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorMessage())
                .isEqualTo("Delivery blocked by webhook security policy: Resolves to blocked IP: 10.0.0.5");
        verify(deliveryRepository).updateOwned(eq(delivery), eq(claimed("del-1")));
        // Never contacted the endpoint, never scheduled a retry
        verify(transport, never()).deliver(any(), any(), any(), any());
        verify(deliveryRepository, never()).updateOwnedAndScheduleRetry(any(), any(), anyLong());
        // Policy block says nothing about endpoint health — no consecutive-failure
        // increment, no auto-disable side effect from a config tightening.
        verify(subscriptionRepository, never()).finalizeDeliveryFailure(
                anyString(), any(), any(), any(), anyInt(), any(), any());
        assertThat(counter(CyclesMetrics.DELIVERY_FAILED,
                "tenant", "t-1", "event_type", "tenant.created", "reason", "ssrf_blocked"))
                .isEqualTo(1.0);
    }

    @Test
    void ssrfConfigIndeterminate_propagates_noPermanentFail_noAck() {
        // Review finding: a transient config read failure must NOT become a
        // permanent policy block. The guard propagates IllegalStateException;
        // the handler rethrows so DispatchLoop skips ack and the
        // stale-processing recovery retries the delivery later.
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(urlGuard.check("https://example.com/webhook"))
                .thenThrow(new IllegalStateException("Webhook security config indeterminate"));

        assertThatThrownBy(() -> handler.handle(claimed("del-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("indeterminate");

        // Delivery untouched: no FAILED write, no transport contact, no retry
        // scheduling, no consecutive-failure accounting.
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        verify(deliveryRepository, never()).updateOwned(any(), any());
        verify(transport, never()).deliver(any(), any(), any(), any());
        verify(deliveryRepository, never()).updateOwnedAndScheduleRetry(any(), any(), anyLong());
        verify(subscriptionRepository, never()).finalizeDeliveryFailure(
                anyString(), any(), any(), any(), anyInt(), any(), any());
        assertThat(counter(CyclesMetrics.SECURITY_CONFIG_INDETERMINATE)).isEqualTo(1.0);
    }

    @Test
    void ssrfAllowed_deliveryProceeds() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(urlGuard.check("https://example.com/webhook")).thenReturn(null);
        when(transport.deliver(any(), any(), any(), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        verify(transport).deliver(any(), any(), any(), any());
    }

    @Test
    void retryScheduled_doesNotEmitSystemWebhookDeliveryFailed() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(1); // becomes 2 <= maxRetries(5) → retry, not exhausted
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RETRYING);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void retriesExhausted_emitFailure_doesNotAffectFailedStatus() {
        // Inline publication is best-effort because the atomic outbox remains
        // durable after the FAILED transition commits.
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(0);
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());
        doThrow(new RuntimeException("Redis down")).when(eventRepository).save(any());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(subscriptionRepository).finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(Instant.class), eq(10), any(), any());
    }

    @Test
    void autoDisable_emitFailure_doesNotRevertStatusFlipOrMetric() {
        // Inline publish failure does not revert the atomic state+outbox commit.
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(9);
        when(subscriptionRepository.finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(), eq(10), any(), any()))
                .thenReturn(new TerminalFailureUpdate(true, true, true, 11, true, WebhookStatus.ACTIVE));
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());
        doThrow(new RuntimeException("Redis down")).when(eventRepository).save(any());

        handler.handle(claimed("del-1"));

        // Atomic increment/transition still reached the repository
        verify(subscriptionRepository).finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(Instant.class), eq(10), any(), any());
        // Metric still incremented
        assertThat(counter(CyclesMetrics.SUBSCRIPTION_AUTO_DISABLED,
                "tenant", "t-1", "reason", "consecutive_failures")).isEqualTo(1.0);
    }

    @Test
    void autoDisable_subscriptionStateFailureDoesNotMarkFailedOrEmitDisabledEvent() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(9);
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());
        when(subscriptionRepository.finalizeDeliveryFailure(
                eq("sub-1"), eq(claimed("del-1")), eq(delivery), any(), eq(10), any(), any()))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> handler.handle(claimed("del-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis down");

        verify(eventRepository, never()).save(any());
        verify(deliveryRepository, never()).updateOwned(any(), any());
        assertThat(counter(CyclesMetrics.SUBSCRIPTION_AUTO_DISABLED,
                "tenant", "t-1", "reason", "consecutive_failures")).isZero();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void handle_eventWithoutTraceId_leavesDeliveryTraceIdNull() {
        // Non-HTTP-originated events (e.g., sweepers) may lack trace_id.
        // The dispatcher MUST NOT invent one at stamping time; the
        // outbound traceparent gets a freshly-minted id via TraceContext
        // but the persisted Delivery record stays null so downstream
        // correlation isn't misattributed to a trace that never existed.
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent()); // no trace_id
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getTraceId()).isNull();
    }

    // --- Last-mile webhook ownership boundary (#209, WEBHOOK SUBSCRIPTION INVARIANT 2) ---

    private Event adminOnlyEvent() {
        // api_key.* — an admin-only event (type AND category admin).
        return Event.builder()
                .eventId("evt-1")
                .eventType("api_key.revoked")
                .category("api_key")
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
    }

    private Delivery adminOnlyDelivery() {
        Delivery d = pendingDelivery();
        d.setEventType("api_key.revoked");
        return d;
    }

    @Test
    void ownershipBoundary_concreteTenantSub_adminOnlyEvent_droppedTerminal_notSent_notRetried() {
        // A delivery already sitting in the queue (queued BEFORE this version)
        // for a concrete-tenant subscription carrying an admin-only event must
        // be dropped at SEND time — terminal, never sent, never retried.
        Delivery delivery = adminOnlyDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(adminOnlyEvent());
        Subscription sub = activeSubscription(); // tenant_id = "t-1" (concrete)
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorMessage()).contains("ownership boundary");
        verify(deliveryRepository).updateOwned(eq(delivery), eq(claimed("del-1")));
        // Never contacted the endpoint, never scheduled a retry, never touched
        // subscription health (a policy skip says nothing about endpoint health).
        verify(transport, never()).deliver(any(), any(), any(), any());
        verify(deliveryRepository, never()).updateOwnedAndScheduleRetry(any(), any(), anyLong());
        verify(subscriptionRepository, never()).finalizeDeliveryFailure(
                anyString(), any(), any(), any(), anyInt(), any(), any());
        // Signing secret is never even fetched — no I/O before the drop.
        verify(subscriptionRepository, never()).getSigningSecret(anyString());
        assertThat(counter(CyclesMetrics.DELIVERY_BOUNDARY_SKIPPED,
                "tenant", "t-1", "event_type", "api_key.revoked", "category", "api_key"))
                .isEqualTo(1.0);
    }

    @Test
    void ownershipBoundary_enforcedOnRetryPath() {
        // RETRYING deliveries flow through the same handle() funnel — the
        // boundary must catch a retry of an admin-only event too.
        Delivery delivery = adminOnlyDelivery();
        delivery.setStatus(DeliveryStatus.RETRYING);
        delivery.setAttempts(2);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(adminOnlyEvent());
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(transport, never()).deliver(any(), any(), any(), any());
        verify(deliveryRepository, never()).updateOwnedAndScheduleRetry(any(), any(), anyLong());
    }

    @Test
    void ownershipBoundary_typeCategoryInconsistent_failClosed_blocked() {
        // Tenant-accessible TYPE but admin-only CATEGORY (category is an
        // independent cross-plane field). Fail-closed: block on either dimension.
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")   // tenant-accessible type
                .category("webhook")           // admin-only category
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(event);
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorMessage()).contains("ownership boundary");
        verify(transport, never()).deliver(any(), any(), any(), any());
    }

    @Test
    void ownershipBoundary_concreteTenantSub_tenantAccessibleEvent_delivered() {
        // Do NOT over-block: a concrete-tenant sub still receives its
        // tenant-accessible (budget/reservation/tenant) events.
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent()); // tenant.created
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        verify(transport).deliver(any(), any(), any(), any());
        assertThat(counter(CyclesMetrics.DELIVERY_BOUNDARY_SKIPPED,
                "tenant", "t-1", "event_type", "tenant.created", "category", "tenant"))
                .isZero();
    }

    @Test
    void ownershipBoundary_systemOwnedSub_receivesAdminEvent() {
        // __system__-owned (operator) subscriptions legitimately receive
        // admin-only events — the boundary must NOT block them.
        Delivery delivery = adminOnlyDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(adminOnlyEvent());
        Subscription sub = activeSubscription();
        sub.setTenantId("__system__");
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        verify(transport).deliver(any(), any(), any(), any());
    }

    @Test
    void ownershipBoundary_nullOwnerSub_treatedAsSystem_receivesAdminEvent() {
        // A null owning tenant_id is system-owned per isSystemOwner semantics.
        Delivery delivery = adminOnlyDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(adminOnlyEvent());
        Subscription sub = activeSubscription();
        sub.setTenantId(null);
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(successResult());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        verify(transport).deliver(any(), any(), any(), any());
    }

    @Test
    void ownershipBoundary_checkedBeforeSsrfGuard_noUrlCheck() {
        // The boundary short-circuits before the SSRF guard — a blocked event
        // never triggers a URL check (no wasted I/O / DNS).
        Delivery delivery = adminOnlyDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(adminOnlyEvent());
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());

        handler.handle(claimed("del-1"));

        verify(urlGuard, never()).check(anyString());
    }

    @Test
    void ownershipBoundary_classifiesOnReloadedEvent_notDeliverySnapshot() {
        // The Delivery row carries a STALE tenant-accessible event_type snapshot,
        // but the reloaded Event is admin-only. Classification MUST use the
        // authoritative reloaded Event → blocked. Guards against regressing to a
        // Delivery.event_type-based check.
        Delivery delivery = pendingDelivery();
        delivery.setEventType("tenant.created"); // stale, tenant-accessible snapshot
        Event reloaded = adminOnlyEvent();        // api_key.revoked / api_key (admin)
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(reloaded);
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorMessage()).contains("ownership boundary");
        verify(transport, never()).deliver(any(), any(), any(), any());
        // Reported signal reflects the reloaded (authoritative) event type.
        assertThat(counter(CyclesMetrics.DELIVERY_BOUNDARY_SKIPPED,
                "tenant", "t-1", "event_type", "api_key.revoked", "category", "api_key"))
                .isEqualTo(1.0);
    }

    @Test
    void ownershipBoundary_versionSkew_futureAdminTypeWithTenantCategory_blocked() {
        // A future admin event type this worker's enum has NOT learned, carrying a
        // tenant-accessible category. The raw-namespace allowlist blocks it even
        // though the enum can't resolve the type (fail-closed under version skew).
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("system.brand_new_event") // admin namespace, unknown to enum
                .category("tenant")                  // tenant-accessible category
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(event);
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(transport, never()).deliver(any(), any(), any(), any());
    }

    @Test
    void blankSigningSecretFailsClosedLikeMissingSecret() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(" ");

        assertThatThrownBy(() -> handler.handle(claimed("del-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not yet available");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        verify(deliveryRepository, never()).updateOwned(any(), any());
        verify(transport, never()).deliver(any(), any(), any(), any());
    }

    @Test
    void terminalTransportErrorOmitsUnavailableOptionalFailureDetails() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5);
        delivery.setEventType(null);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(TransportResult.builder()
                .success(false).statusCode(0).latencyMs(-1).errorMessage(null).build());
        when(eventRepository.ackDispatcherEvent(anyString())).thenReturn(true);

        handler.handle(claimed("del-1"));

        assertThat(delivery.getResponseStatus()).isNull();
        assertThat(delivery.getResponseTimeMs()).isNull();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(counter(CyclesMetrics.DISPATCHER_EVENT_PUBLISHED,
                "event_type", "system.webhook_delivery_failed")).isEqualTo(1.0);
    }

    @Test
    void terminalFailureHandlesDeliveryDisappearanceWithoutPublishing() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());
        when(subscriptionRepository.finalizeDeliveryFailure(
                anyString(), any(ClaimedDelivery.class), any(), any(), anyInt(), any(), any()))
                .thenReturn(new TerminalFailureUpdate(false, false, true, 1, false, WebhookStatus.ACTIVE));

        handler.handle(claimed("del-1"));

        verify(eventRepository, never()).save(any());
    }

    @Test
    void autoDisableWithoutPreviousStatusStillPublishesDurableEvents() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(5);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        when(subscriptionRepository.findById("sub-1")).thenReturn(activeSubscription());
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(failureResult());
        when(subscriptionRepository.finalizeDeliveryFailure(
                anyString(), any(ClaimedDelivery.class), any(), any(), anyInt(), any(), any()))
                .thenReturn(new TerminalFailureUpdate(true, true, true, 11, true, null));

        handler.handle(claimed("del-1"));

        verify(eventRepository, times(2)).save(any());
    }

    @Test
    void explicitRetryPolicyAndOutOfRangeHttpStatusUseConfiguredBackoff() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription subscription = activeSubscription();
        subscription.setRetryPolicy(RetryPolicy.builder()
                .maxRetries(10)
                .initialDelayMs(123)
                .backoffMultiplier(1.5)
                .maxDelayMs(456)
                .build());
        when(subscriptionRepository.findById("sub-1")).thenReturn(subscription);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn("secret");
        when(transport.deliver(any(), any(), any(), any())).thenReturn(TransportResult.builder()
                .success(false).statusCode(600).latencyMs(1).errorMessage("HTTP 600").build());

        handler.handle(claimed("del-1"));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(counter(CyclesMetrics.DELIVERY_FAILED,
                "tenant", "t-1", "event_type", "tenant.created", "reason", "transport_error"))
                .isEqualTo(1.0);
        verify(deliveryRepository).updateOwnedAndScheduleRetry(eq(delivery), eq(claimed("del-1")), anyLong());
    }
}
