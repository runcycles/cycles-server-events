package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.events.model.Event;
import io.runcycles.events.model.EventCategory;
import io.runcycles.events.model.DispatcherEventTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class EventRepositoryTest {

    @Mock
    private JedisPool jedisPool;
    @Mock
    private Jedis jedis;

    private ObjectMapper objectMapper;
    private EventRepository repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        repository = new EventRepository(jedisPool, objectMapper);
        // @Value is applied by Spring at runtime; in the unit-test harness
        // the field stays at its default (0), so explicitly seed the TTL
        // to match the production default (90 days) for assertion stability.
        ReflectionTestUtils.setField(repository, "eventTtlDays", 90);
    }

    @Test
    void findById_found() throws Exception {
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category("tenant")
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
        when(jedis.get("event:evt-1")).thenReturn(objectMapper.writeValueAsString(event));

        Event result = repository.findById("evt-1");

        assertThat(result).isNotNull();
        assertThat(result.getEventId()).isEqualTo("evt-1");
        assertThat(result.getEventType()).isEqualTo("tenant.created");
        assertThat(result.getCategory()).isEqualTo("tenant");
    }

    @Test
    void findById_notFound() {
        when(jedis.get("event:evt-missing")).thenReturn(null);

        Event result = repository.findById("evt-missing");

        assertThat(result).isNull();
    }

    @Test
    void findById_deserializationError() {
        when(jedis.get("event:evt-bad")).thenReturn("{invalid json}}}");

        assertThatThrownBy(() -> repository.findById("evt-bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read event");
    }

    @Test
    void save_mintsEventIdAndTimestamp_andEvaluatesLuaWithExpectedKeysAndArgs() {
        Event event = Event.builder()
                .eventType("webhook.disabled")
                .category("webhook")
                .tenantId("t-1")
                .source("cycles-events")
                .correlationId("webhook_auto_disable:sub-1:del-1")
                .data(Map.of("subscription_id", "sub-1"))
                .build();

        repository.save(event);

        assertThat(event.getEventId()).isNotNull().startsWith("evt_");
        assertThat(event.getTimestamp()).isNotNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jedis).eval(anyString(), keysCaptor.capture(), argsCaptor.capture());

        List<String> keys = keysCaptor.getValue();
        assertThat(keys).hasSize(4);
        assertThat(keys.get(0)).isEqualTo("event:" + event.getEventId());
        assertThat(keys.get(1)).isEqualTo("events:t-1");
        assertThat(keys.get(2)).isEqualTo("events:_all");
        assertThat(keys.get(3)).isEqualTo("events:correlation:webhook_auto_disable:sub-1:del-1");

        List<String> args = argsCaptor.getValue();
        assertThat(args).hasSize(4);
        // args[0] = JSON; args[1] = score (ms); args[2] = id; args[3] = ttlSeconds
        assertThat(args.get(0)).contains("\"webhook.disabled\"");
        assertThat(Long.parseLong(args.get(1))).isEqualTo(event.getTimestamp().toEpochMilli());
        assertThat(args.get(2)).isEqualTo(event.getEventId());
        assertThat(args.get(3)).isEqualTo(String.valueOf(90L * 86400L));
    }

    @Test
    void save_withoutCorrelationId_omitsCorrelationKey() {
        Event event = Event.builder()
                .eventType("webhook.disabled")
                .category("webhook")
                .tenantId("t-1")
                .source("cycles-events")
                .build();

        repository.save(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(jedis).eval(anyString(), keysCaptor.capture(), anyList());
        assertThat(keysCaptor.getValue()).hasSize(3);
    }

    @Test
    void save_preservesCallerProvidedEventIdAndTimestamp() {
        Instant fixed = Instant.parse("2026-04-23T12:00:00Z");
        Event event = Event.builder()
                .eventId("evt_preset")
                .timestamp(fixed)
                .eventType("webhook.disabled")
                .category("webhook")
                .tenantId("t-1")
                .source("cycles-events")
                .build();

        repository.save(event);

        assertThat(event.getEventId()).isEqualTo("evt_preset");
        assertThat(event.getTimestamp()).isEqualTo(fixed);
    }

    @Test
    void save_redisFailure_wrappedAsRuntimeException() {
        when(jedis.eval(anyString(), anyList(), anyList()))
                .thenThrow(new RuntimeException("Redis down"));
        Event event = Event.builder()
                .eventType("webhook.disabled")
                .category("webhook")
                .tenantId("t-1")
                .source("cycles-events")
                .build();

        assertThatThrownBy(() -> repository.save(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to save event");
    }

    @Test
    void save_blankCorrelationId_omitsCorrelationKey() {
        Event event = Event.builder()
                .eventType("webhook.disabled")
                .category("webhook")
                .tenantId("t-1")
                .source("cycles-events")
                .correlationId("   ")
                .build();

        repository.save(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(jedis).eval(anyString(), keysCaptor.capture(), anyList());
        assertThat(keysCaptor.getValue()).hasSize(3);
    }

    @Test
    void dispatcherOutboxClaimUsesFiniteOwnerLease() {
        when(jedis.set(eq("dispatcher:event-outbox:lock:task-1"), eq("owner-1"), any()))
                .thenReturn("OK");

        assertThat(repository.tryClaimDispatcherEvent("task-1", "owner-1", 30_000)).isTrue();

        verify(jedis).set(eq("dispatcher:event-outbox:lock:task-1"), eq("owner-1"), any());
    }

    @Test
    void dispatcherOutboxTaskRoundTripsFromRedis() throws Exception {
        DispatcherEventTask task = DispatcherEventTask.create("task-1", Event.builder()
                .eventType("webhook.disabled").tenantId("t-1").build());
        when(jedis.get(EventRepository.dispatcherOutboxTaskKey("task-1")))
                .thenReturn(objectMapper.writeValueAsString(task));

        DispatcherEventTask loaded = repository.findDispatcherEventTask("task-1");

        assertThat(loaded.taskId()).isEqualTo("task-1");
        assertThat(loaded.event().getEventId()).isEqualTo(task.event().getEventId());
    }

    @Test
    void inlineOutboxAckReportsWhetherItCompletedAPendingTask() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L, 0L);

        assertThat(repository.ackDispatcherEvent("task-1")).isTrue();
        assertThat(repository.ackDispatcherEvent("task-1")).isFalse();
    }

    @Test
    void invalidOutboxClaimArgumentsFailBeforeRedis() {
        assertThatThrownBy(() -> repository.tryClaimDispatcherEvent(null, "owner", 30_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.tryClaimDispatcherEvent("", "owner", 30_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.tryClaimDispatcherEvent("task-1", null, 30_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.tryClaimDispatcherEvent("task-1", "", 30_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.tryClaimDispatcherEvent("task-1", "owner", 0))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jedis, never()).set(anyString(), anyString(), any());
    }

    @Test
    void saveNullEventReportsSafeContext() {
        assertThatThrownBy(() -> repository.save(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to save event");
    }

    @Test
    void dispatcherOutboxClaimCanLoseContention() {
        when(jedis.set(eq("dispatcher:event-outbox:lock:task-1"), eq("owner-1"), any()))
                .thenReturn(null);

        assertThat(repository.tryClaimDispatcherEvent("task-1", "owner-1", 30_000)).isFalse();
    }

    @Test
    void findDueDispatcherEventsHonorsLimitAndSkipsNonPositiveLimit() {
        assertThat(repository.findDueDispatcherEvents(100L, 0)).isEmpty();
        verify(jedisPool, never()).getResource();

        when(jedis.zrangeByScore(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY,
                Double.NEGATIVE_INFINITY, 100.0, 0, 2)).thenReturn(List.of("task-1", "task-2"));
        assertThat(repository.findDueDispatcherEvents(100L, 2)).containsExactly("task-1", "task-2");
    }

    @Test
    void dispatcherOutboxTaskHandlesMissingAndMalformedRecords() {
        when(jedis.get(EventRepository.dispatcherOutboxTaskKey("missing"))).thenReturn(null);
        assertThat(repository.findDispatcherEventTask("missing")).isNull();

        when(jedis.get(EventRepository.dispatcherOutboxTaskKey("bad"))).thenReturn("not-json");
        assertThatThrownBy(() -> repository.findDispatcherEventTask("bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read dispatcher event outbox task");
    }

    @Test
    void claimedOutboxAckReportsOwnershipAndMaintenanceMethodsUseExpectedKeys() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L, 0L);

        assertThat(repository.ackClaimedDispatcherEvent("task-1", "owner-1")).isTrue();
        assertThat(repository.ackClaimedDispatcherEvent("task-1", "owner-2")).isFalse();
        repository.deferDispatcherEvent("task-1", 123L);
        repository.releaseDispatcherEventClaim("task-1", "owner-1");

        verify(jedis).zadd(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY, 123L, "task-1");
        verify(jedis).eval(anyString(),
                eq(List.of("dispatcher:event-outbox:lock:task-1")), eq(List.of("owner-1")));
    }

}
