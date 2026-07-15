package io.runcycles.events.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.events.metrics.CyclesMetrics;
import io.runcycles.events.model.DispatcherEventTask;
import io.runcycles.events.model.Event;
import io.runcycles.events.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DispatcherEventOutboxPublisherTest {

    @Mock private EventRepository eventRepository;

    private SimpleMeterRegistry registry;
    private CyclesMetrics metrics;
    private DispatcherEventOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CyclesMetrics(registry, false);
        publisher = new DispatcherEventOutboxPublisher(eventRepository, metrics, 10, 30_000, 5_000);
    }

    @Test
    void publishesAndAcknowledgesClaimedTask() {
        DispatcherEventTask task = task("task-1", "webhook.disabled");
        when(eventRepository.findDueDispatcherEvents(anyLong(), eq(10))).thenReturn(List.of("task-1"));
        when(eventRepository.tryClaimDispatcherEvent(eq("task-1"), anyString(), eq(30_000L))).thenReturn(true);
        when(eventRepository.findDispatcherEventTask("task-1")).thenReturn(task);
        when(eventRepository.ackClaimedDispatcherEvent(eq("task-1"), anyString())).thenReturn(true);

        publisher.publishDue();

        verify(eventRepository).save(task.event());
        verify(eventRepository).ackClaimedDispatcherEvent(eq("task-1"), anyString());
        assertThat(registry.find(CyclesMetrics.DISPATCHER_EVENT_PUBLISHED)
                .tag("event_type", "webhook.disabled").counter().count()).isEqualTo(1.0);
    }

    @Test
    void publishFailureDefersWithoutAcknowledgingTask() {
        DispatcherEventTask task = task("task-2", "system.webhook_delivery_failed");
        when(eventRepository.findDueDispatcherEvents(anyLong(), eq(10))).thenReturn(List.of("task-2"));
        when(eventRepository.tryClaimDispatcherEvent(eq("task-2"), anyString(), eq(30_000L))).thenReturn(true);
        when(eventRepository.findDispatcherEventTask("task-2")).thenReturn(task);
        doThrow(new IllegalStateException("redis unavailable")).when(eventRepository).save(task.event());

        assertThatCode(publisher::publishDue).doesNotThrowAnyException();

        verify(eventRepository).deferDispatcherEvent(eq("task-2"), anyLong());
        verify(eventRepository).releaseDispatcherEventClaim(eq("task-2"), anyString());
        verify(eventRepository, never()).ackClaimedDispatcherEvent(anyString(), anyString());
        assertThat(registry.find(CyclesMetrics.DISPATCHER_EVENT_DEFERRED)
                .tags("event_type", "system.webhook_delivery_failed", "reason", "publish_failure")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void concurrentInlineAckDoesNotRecreateOrDoubleCountTask() {
        DispatcherEventTask task = task("task-race", "webhook.disabled");
        when(eventRepository.findDueDispatcherEvents(anyLong(), eq(10))).thenReturn(List.of("task-race"));
        when(eventRepository.tryClaimDispatcherEvent(eq("task-race"), anyString(), eq(30_000L)))
                .thenReturn(true);
        when(eventRepository.findDispatcherEventTask("task-race")).thenReturn(task);
        when(eventRepository.ackClaimedDispatcherEvent(eq("task-race"), anyString()))
                .thenReturn(false);

        publisher.publishDue();

        verify(eventRepository).save(task.event());
        verify(eventRepository, never()).deferDispatcherEvent(anyString(), anyLong());
        verify(eventRepository).releaseDispatcherEventClaim(eq("task-race"), anyString());
        assertThat(registry.find(CyclesMetrics.DISPATCHER_EVENT_PUBLISHED).counter()).isNull();
    }

    @Test
    void scanFailureDoesNotEscapeScheduler() {
        when(eventRepository.findDueDispatcherEvents(anyLong(), eq(10)))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatCode(publisher::publishDue).doesNotThrowAnyException();

        verify(eventRepository, never()).tryClaimDispatcherEvent(anyString(), anyString(), anyLong());
        assertThat(registry.find(CyclesMetrics.DISPATCHER_EVENT_DEFERRED)
                .tags("event_type", CyclesMetrics.TAG_UNKNOWN, "reason", "scan_failure")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void claimFailureDoesNotEscapeScheduler() {
        when(eventRepository.findDueDispatcherEvents(anyLong(), eq(10))).thenReturn(List.of("task-3"));
        when(eventRepository.tryClaimDispatcherEvent(eq("task-3"), anyString(), eq(30_000L)))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatCode(publisher::publishDue).doesNotThrowAnyException();

        verify(eventRepository, never()).findDispatcherEventTask(anyString());
        assertThat(registry.find(CyclesMetrics.DISPATCHER_EVENT_DEFERRED)
                .tags("event_type", CyclesMetrics.TAG_UNKNOWN, "reason", "claim_failure")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void leaseMissLeavesTaskForTheCurrentOwner() {
        when(eventRepository.findDueDispatcherEvents(anyLong(), eq(10))).thenReturn(List.of("task-owned"));
        when(eventRepository.tryClaimDispatcherEvent(eq("task-owned"), anyString(), eq(30_000L)))
                .thenReturn(false);

        publisher.publishDue();

        verify(eventRepository, never()).findDispatcherEventTask(anyString());
        verify(eventRepository, never()).releaseDispatcherEventClaim(anyString(), anyString());
    }

    @Test
    void taskAlreadyAcknowledgedByInlinePublisherIsCleanedUp() {
        when(eventRepository.findDueDispatcherEvents(anyLong(), eq(10))).thenReturn(List.of("task-gone"));
        when(eventRepository.tryClaimDispatcherEvent(eq("task-gone"), anyString(), eq(30_000L)))
                .thenReturn(true);
        when(eventRepository.findDispatcherEventTask("task-gone")).thenReturn(null);
        when(eventRepository.ackClaimedDispatcherEvent(eq("task-gone"), anyString())).thenReturn(true);

        publisher.publishDue();

        verify(eventRepository, never()).save(any());
        verify(eventRepository, never()).releaseDispatcherEventClaim(anyString(), anyString());
    }

    @Test
    void readDeferAndReleaseFailuresRemainContained() {
        when(eventRepository.findDueDispatcherEvents(anyLong(), eq(10))).thenReturn(List.of("task-broken"));
        when(eventRepository.tryClaimDispatcherEvent(eq("task-broken"), anyString(), eq(30_000L)))
                .thenReturn(true);
        when(eventRepository.findDispatcherEventTask("task-broken"))
                .thenThrow(new IllegalStateException("corrupt task"));
        doThrow(new IllegalStateException("cannot defer"))
                .when(eventRepository).deferDispatcherEvent(eq("task-broken"), anyLong());
        doThrow(new IllegalStateException("cannot release"))
                .when(eventRepository).releaseDispatcherEventClaim(eq("task-broken"), anyString());

        assertThatCode(publisher::publishDue).doesNotThrowAnyException();

        assertThat(registry.find(CyclesMetrics.DISPATCHER_EVENT_DEFERRED)
                .tags("event_type", CyclesMetrics.TAG_UNKNOWN, "reason", "publish_failure")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void deterministicTaskIdentityMakesReplayIdempotent() {
        DispatcherEventTask first = task("same-transition", "webhook.disabled");
        Event prepopulated = Event.builder()
                .eventId("evt_random")
                .eventType("webhook.disabled")
                .tenantId("t-1")
                .build();
        DispatcherEventTask replay = DispatcherEventTask.create("same-transition", prepopulated);

        assertThat(first.event().getEventId()).isEqualTo(replay.event().getEventId());
        assertThat(first.event().getEventId()).matches("evt_[0-9a-f]{16}");
    }

    @Test
    void constructorRejectsInvalidLimits() {
        assertThatThrownBy(() -> new DispatcherEventOutboxPublisher(
                eventRepository, metrics, 0, 30_000, 5_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatcherEventOutboxPublisher(
                eventRepository, metrics, 10, 0, 5_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatcherEventOutboxPublisher(
                eventRepository, metrics, 10, 30_000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void taskConstructorValidatesInputsAndDefaultsTimestamp() {
        Event event = Event.builder().eventType("webhook.disabled").build();

        assertThatThrownBy(() -> new DispatcherEventTask(null, Instant.now(), event))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatcherEventTask(" ", Instant.now(), event))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatcherEventTask("task", Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DispatcherEventTask("task", null, event).createdAt()).isNotNull();
    }

    private static DispatcherEventTask task(String id, String type) {
        return DispatcherEventTask.create(id, Event.builder()
                .eventType(type)
                .tenantId("t-1")
                .build());
    }
}
