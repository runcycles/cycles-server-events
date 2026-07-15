package io.runcycles.events.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.events.metrics.CyclesMetrics;
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
import io.runcycles.events.transport.webhook.WebhookUrlGuard;
import io.runcycles.events.validation.EventPayloadValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Composed test pinning the load-bearing recovered-processing call graph
 * end-to-end (not just the {@link DeliveryHandler} unit): a stale
 * {@code dispatch:processing} delivery is recovered back to
 * {@code dispatch:pending} by {@link DispatchRecovery}, then claimed by
 * {@link DispatchLoop} which invokes the REAL {@link DeliveryHandler}, where the
 * last-mile ownership boundary (#209) drops a concrete-tenant admin-only
 * delivery as terminal — never sent, never retried, and the loop still acks it.
 *
 * <p>The queue repository is mocked (its Redis Lua move-back is out of scope for
 * a unit-level composition); the point is that the recovered id flows through
 * the same {@code DispatchLoop → DeliveryHandler.handle()} path as an initial
 * delivery, so the boundary check placed inside {@code handle()} covers it.
 */
@ExtendWith(MockitoExtension.class)
class RecoveredDeliveryBoundaryTest {

    @Mock private DeliveryQueueRepository queueRepository;
    @Mock private DeliveryRepository deliveryRepository;
    @Mock private EventRepository eventRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private Transport transport;
    @Mock private WebhookUrlGuard urlGuard;

    private SimpleMeterRegistry registry;
    private CyclesMetrics metrics;
    private DeliveryHandler handler;
    private DispatchLoop dispatchLoop;
    private DispatchRecovery recovery;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CyclesMetrics(registry, true);
        EventPayloadValidator validator = new EventPayloadValidator(metrics);
        handler = new DeliveryHandler(deliveryRepository, eventRepository,
                subscriptionRepository, queueRepository, transport, metrics, validator,
                urlGuard, 86400000L);
        dispatchLoop = new DispatchLoop(queueRepository, handler, 5, 120_000L, 30, 500);
        when(queueRepository.tryAcquireOrderingLock(anyString(), anyLong())).thenReturn(true);
        recovery = new DispatchRecovery(queueRepository, 120000L);
    }

    private double counter(String name, String... tags) {
        io.micrometer.core.instrument.Counter c = registry.find(name).tags(tags).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    void recoveredProcessing_backToPending_dispatchLoop_boundaryBlocksConcreteTenantAdminDelivery() {
        // Recovery reports a stale delivery moved back to pending; the loop then
        // claims that same id from pending.
        when(queueRepository.recoverStaleProcessing(anyLong(), anyLong())).thenReturn(1L);
        when(queueRepository.claimPending(5)).thenReturn("del-1");

        Delivery delivery = Delivery.builder()
                .deliveryId("del-1")
                .subscriptionId("sub-1")
                .eventId("evt-1")
                .eventType("api_key.revoked")
                .status(DeliveryStatus.PENDING)
                .attempts(0)
                .build();
        when(deliveryRepository.findById("del-1")).thenReturn(delivery);
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("api_key.revoked")
                .category("api_key")
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
        when(eventRepository.findById("evt-1")).thenReturn(event);
        Subscription sub = Subscription.builder()
                .subscriptionId("sub-1")
                .tenantId("t-1") // concrete tenant
                .url("https://example.com/webhook")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of("api_key.revoked"))
                .consecutiveFailures(0)
                .disableAfterFailures(10)
                .retryPolicy(RetryPolicy.builder().build())
                .build();
        when(subscriptionRepository.findById("sub-1")).thenReturn(sub);

        // Startup recovery re-queues the stale delivery, then the loop runs it.
        recovery.recoverOnStartup();
        dispatchLoop.processNext();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorMessage()).contains("ownership boundary");
        verify(transport, never()).deliver(any(), any(), any(), any());
        verify(queueRepository, never()).scheduleRetry(anyString(), anyLong());
        // Terminal handling still acks the recovered delivery so it leaves the queue.
        verify(queueRepository).ack("del-1");
        // Never contacts endpoint health machinery.
        verify(subscriptionRepository, never()).updateDeliveryState(
                anyString(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any());
        assertThat(counter(CyclesMetrics.DELIVERY_BOUNDARY_SKIPPED,
                "tenant", "t-1", "event_type", "api_key.revoked", "category", "api_key"))
                .isEqualTo(1.0);
    }
}
