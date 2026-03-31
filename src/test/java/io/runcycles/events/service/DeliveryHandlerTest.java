package io.runcycles.events.service;

import io.runcycles.events.model.*;
import io.runcycles.events.repository.DeliveryQueueRepository;
import io.runcycles.events.repository.DeliveryRepository;
import io.runcycles.events.repository.EventRepository;
import io.runcycles.events.repository.SubscriptionRepository;
import io.runcycles.events.transport.Transport;
import io.runcycles.events.transport.TransportResult;
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

    private DeliveryHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DeliveryHandler(deliveryRepository, eventRepository,
                subscriptionRepository, queueRepository, transport);
    }

    private Delivery pendingDelivery() {
        return Delivery.builder()
                .deliveryId("del-1")
                .subscriptionId("sub-1")
                .eventId("evt-1")
                .eventType("tenant.created")
                .status("PENDING")
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
                .status("ACTIVE")
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

        assertThat(delivery.getStatus()).isEqualTo("SUCCESS");
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getCompletedAt()).isNotNull();
        assertThat(delivery.getResponseStatus()).isEqualTo(200);
        verify(deliveryRepository).update(delivery);
        assertThat(sub.getConsecutiveFailures()).isEqualTo(0);
        verify(subscriptionRepository).update(sub);
    }

    @Test
    void handle_retryingDelivery_succeeds() {
        Delivery delivery = pendingDelivery();
        delivery.setStatus("RETRYING");
        delivery.setAttempts(2);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), isNull())).thenReturn(successResult());

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo("SUCCESS");
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
        delivery.setStatus("SUCCESS");
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);

        handler.handle("del-1");

        verify(eventRepository, never()).findById(anyString());
        verify(transport, never()).deliver(any(), any(), any());
    }

    @Test
    void handle_deliveryAlreadyFailed() {
        Delivery delivery = pendingDelivery();
        delivery.setStatus("FAILED");
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

        assertThat(delivery.getStatus()).isEqualTo("FAILED");
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

        assertThat(delivery.getStatus()).isEqualTo("FAILED");
        assertThat(delivery.getErrorMessage()).contains("Subscription not found");
    }

    @Test
    void handle_subscriptionNotActive_paused() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setStatus("PAUSED");
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo("FAILED");
        assertThat(delivery.getErrorMessage()).contains("not active");
    }

    @Test
    void handle_subscriptionNotActive_disabled() {
        Delivery delivery = pendingDelivery();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setStatus("DISABLED");
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo("FAILED");
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

        assertThat(delivery.getStatus()).isEqualTo("RETRYING");
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getNextRetryAt()).isNotNull();
        verify(queueRepository).scheduleRetry(eq("del-1"), anyLong());
    }

    @Test
    void handle_transportFailure_exponentialBackoff() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(2); // will become 3 after increment
        delivery.setStatus("RETRYING");
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
        delivery.setStatus("RETRYING");
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
        delivery.setAttempts(4); // will become 5 == maxRetries
        delivery.setStatus("RETRYING");
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        assertThat(delivery.getStatus()).isEqualTo("FAILED");
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

        assertThat(delivery.getStatus()).isEqualTo("RETRYING");
        verify(queueRepository).scheduleRetry(eq("del-1"), anyLong());
    }

    // --- Auto-disable ---

    @Test
    void handle_consecutiveFailures_incrementsCounter() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(4); // exhaust retries
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(3);
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        assertThat(sub.getConsecutiveFailures()).isEqualTo(4);
        assertThat(sub.getStatus()).isEqualTo("ACTIVE"); // not yet disabled
        verify(subscriptionRepository).update(sub);
    }

    @Test
    void handle_consecutiveFailures_autoDisables() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(4); // exhaust retries
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(9); // will become 10 == disableAfterFailures
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        assertThat(sub.getConsecutiveFailures()).isEqualTo(10);
        assertThat(sub.getStatus()).isEqualTo("DISABLED");
        verify(subscriptionRepository).update(sub);
    }

    @Test
    void handle_consecutiveFailures_defaultThreshold10() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(4);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setDisableAfterFailures(null); // defaults to 10
        sub.setConsecutiveFailures(9);
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        assertThat(sub.getStatus()).isEqualTo("DISABLED");
    }

    @Test
    void handle_consecutiveFailures_nullInitialCount() {
        Delivery delivery = pendingDelivery();
        delivery.setAttempts(4);
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        when(eventRepository.findById("evt-1")).thenReturn(testEvent());
        Subscription sub = activeSubscription();
        sub.setConsecutiveFailures(null); // null starts at 0
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);
        when(subscriptionRepository.getSigningSecret("sub-1")).thenReturn(null);
        when(transport.deliver(any(), any(), any())).thenReturn(failureResult());

        handler.handle("del-1");

        assertThat(sub.getConsecutiveFailures()).isEqualTo(1);
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

        assertThat(delivery.getStatus()).isEqualTo("SUCCESS");
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
}
