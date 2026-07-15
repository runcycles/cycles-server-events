package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.events.config.CryptoService;
import io.runcycles.events.evidence.EvidenceQueueConsumer;
import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.DeliveryStatus;
import io.runcycles.events.model.DispatcherEventTask;
import io.runcycles.events.model.Event;
import io.runcycles.events.metrics.CyclesMetrics;
import io.runcycles.events.service.DispatcherEventOutboxPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisAtomicityIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis:7-alpine@sha256:6ab0b6e7381779332f97b8ca76193e45b0756f38d4c0dcda72dbb3c32061ab99"))
            .withExposedPorts(6379);

    private static JedisPool pool;
    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        pool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
        mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) pool.close();
    }

    @Test
    void concurrentFailuresAreNeverLostAndDisableExactlyOnce() throws Exception {
        SubscriptionRepository repository = new SubscriptionRepository(pool, mapper, new CryptoService(""));
        try (Jedis jedis = pool.getResource()) {
            jedis.set("webhook:concurrent", """
                    {"subscription_id":"concurrent","status":"ACTIVE",
                     "consecutive_failures":0,"disable_after_failures":25,
                     "url":"https://operator-edited.example/hook"}
                    """);
        }

        int calls = 50;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<SubscriptionRepository.FailureUpdate>> tasks = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            tasks.add(() -> {
                start.await();
                return repository.recordDeliveryFailure("concurrent", Instant.now(), 10,
                        DispatcherEventTask.create("disable-concurrent", Event.builder()
                                .eventType("webhook.disabled")
                                .data(new java.util.LinkedHashMap<>())
                                .build()));
            });
        }
        List<Future<SubscriptionRepository.FailureUpdate>> futures = new ArrayList<>();
        for (Callable<SubscriptionRepository.FailureUpdate> task : tasks) {
            futures.add(executor.submit(task));
        }
        start.countDown();

        int disableTransitions = 0;
        for (Future<SubscriptionRepository.FailureUpdate> future : futures) {
            if (future.get().disabledNow()) disableTransitions++;
        }
        executor.shutdownNow();

        try (Jedis jedis = pool.getResource()) {
            JsonNode stored = mapper.readTree(jedis.get("webhook:concurrent"));
            assertThat(stored.path("consecutive_failures").asInt()).isEqualTo(calls);
            assertThat(stored.path("status").asText()).isEqualTo("DISABLED");
            assertThat(stored.path("url").asText())
                    .isEqualTo("https://operator-edited.example/hook");
            assertThat(jedis.zscore(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY,
                    "disable-concurrent")).isNotNull();
            assertThat(jedis.exists(EventRepository.dispatcherOutboxTaskKey(
                    "disable-concurrent"))).isTrue();
        }
        assertThat(disableTransitions).isEqualTo(1);
    }

    @Test
    void deliveryUpdatePreservesTtlAndNeverResurrectsDeletedRecord() throws Exception {
        DeliveryRepository repository = new DeliveryRepository(pool, mapper);
        Delivery delivery = Delivery.builder().deliveryId("ttl").status(DeliveryStatus.SUCCESS).build();
        try (Jedis jedis = pool.getResource()) {
            jedis.psetex("delivery:ttl", 60_000, "{\"delivery_id\":\"ttl\",\"status\":\"PENDING\"}");
        }

        repository.update(delivery);

        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.pttl("delivery:ttl")).isBetween(1L, 60_000L);
            jedis.del("delivery:ttl");
        }
        repository.update(delivery);
        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.exists("delivery:ttl")).isFalse();
        }
    }

    @Test
    void terminalFailureAtomicallyStagesBothRequiredEventsAndDisablesPausedSubscription() throws Exception {
        SubscriptionRepository repository = new SubscriptionRepository(pool, mapper, new CryptoService(""));
        Delivery failed = Delivery.builder()
                .deliveryId("terminal-atomic")
                .subscriptionId("paused-sub")
                .eventId("source-event")
                .eventType("tenant.created")
                .status(DeliveryStatus.FAILED)
                .attempts(6)
                .completedAt(Instant.parse("2026-07-15T12:00:00Z"))
                .build();
        DispatcherEventTask disableTask = DispatcherEventTask.create("disable-paused",
                Event.builder().eventType("webhook.disabled")
                        .data(new java.util.LinkedHashMap<>()).build());
        DispatcherEventTask failureTask = DispatcherEventTask.create("failure-terminal",
                Event.builder().eventType("system.webhook_delivery_failed").build());
        try (Jedis jedis = pool.getResource()) {
            jedis.set("delivery:terminal-atomic", mapper.writeValueAsString(
                    Delivery.builder().deliveryId("terminal-atomic").subscriptionId("paused-sub")
                            .eventId("source-event").eventType("tenant.created")
                            .status(DeliveryStatus.PENDING).attempts(5).build()));
            jedis.set("webhook:paused-sub", """
                    {"subscription_id":"paused-sub","status":"PAUSED",
                     "consecutive_failures":10,"disable_after_failures":10}
                    """);
        }

        SubscriptionRepository.TerminalFailureUpdate result = repository.finalizeDeliveryFailure(
                "paused-sub", failed, Instant.parse("2026-07-15T12:00:00Z"), 10,
                disableTask, failureTask);

        assertThat(result.deliveryFound()).isTrue();
        assertThat(result.disabledNow()).isTrue();
        assertThat(result.previousStatus()).isEqualTo(io.runcycles.events.model.WebhookStatus.PAUSED);
        try (Jedis jedis = pool.getResource()) {
            assertThat(mapper.readTree(jedis.get("delivery:terminal-atomic")).path("status").asText())
                    .isEqualTo("FAILED");
            JsonNode subscription = mapper.readTree(jedis.get("webhook:paused-sub"));
            assertThat(subscription.path("status").asText()).isEqualTo("DISABLED");
            assertThat(subscription.path("consecutive_failures").asInt()).isEqualTo(11);
            assertThat(jedis.zrange(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY, 0, -1))
                    .contains("disable-paused", "failure-terminal");
            JsonNode stagedDisable = mapper.readTree(jedis.get(
                    EventRepository.dispatcherOutboxTaskKey("disable-paused")));
            assertThat(stagedDisable.path("event").path("data").path("previous_status").asText())
                    .isEqualTo("PAUSED");
        }
    }

    @Test
    void durableOutboxPublishesAndAcknowledgesWithDeterministicEventId() throws Exception {
        String taskId = "publisher-integration";
        Event event = Event.builder()
                .eventType("system.webhook_delivery_failed")
                .category("system")
                .tenantId("__system__")
                .source("cycles-events")
                .build();
        DispatcherEventTask task = DispatcherEventTask.create(taskId, event);
        try (Jedis jedis = pool.getResource()) {
            jedis.del(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY);
            for (String key : jedis.keys("dispatcher:event-outbox:task:*")) {
                jedis.del(key);
            }
            jedis.set(EventRepository.dispatcherOutboxTaskKey(taskId), mapper.writeValueAsString(task));
            jedis.zadd(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY,
                    System.currentTimeMillis() - 1, taskId);
        }
        EventRepository events = new EventRepository(pool, mapper);
        ReflectionTestUtils.setField(events, "eventTtlDays", 90);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DispatcherEventOutboxPublisher publisher = new DispatcherEventOutboxPublisher(
                events, new CyclesMetrics(registry, false), 10, 30_000, 1_000);

        publisher.publishDue();

        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.get("event:" + event.getEventId()))
                    .contains("system.webhook_delivery_failed");
            assertThat(jedis.exists(EventRepository.dispatcherOutboxTaskKey(taskId))).isFalse();
            assertThat(jedis.zscore(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY, taskId)).isNull();
        }
        assertThat(registry.find(CyclesMetrics.DISPATCHER_EVENT_PUBLISHED)
                .tag("event_type", "system.webhook_delivery_failed").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void youngCrashOrphansBecomeRecoverableOnPeriodicPass() {
        DeliveryQueueRepository deliveries = new DeliveryQueueRepository(pool);
        long now = 100_000L;
        try (Jedis jedis = pool.getResource()) {
            jedis.del("dispatch:pending", "dispatch:processing", "dispatch:processing:claimed_at");
            jedis.lpush("dispatch:processing", "delivery-orphan");
            jedis.zadd("dispatch:processing:claimed_at", now, "delivery-orphan");
        }

        assertThat(deliveries.recoverStaleProcessing(now + 10, 100)).isZero();
        assertThat(deliveries.recoverStaleProcessing(now + 101, 100)).isEqualTo(1);
        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.lrange("dispatch:pending", 0, -1)).containsExactly("delivery-orphan");
        }
    }

    @Test
    void evidenceRecoveryDoesNotStealFreshReplicaWork() {
        EvidenceQueueConsumer evidence = new EvidenceQueueConsumer(
                pool, "evidence:test:pending", "evidence:test:processing", "evidence:test:failed", 100);
        String record = "{\"artifact_type\":\"reserve\"}";
        long now = 200_000L;
        try (Jedis jedis = pool.getResource()) {
            jedis.del("evidence:test:pending", "evidence:test:processing",
                    "evidence:test:processing:claimed_at");
            jedis.lpush("evidence:test:processing", record);
            jedis.zadd("evidence:test:processing:claimed_at", now, record);
        }

        assertThat(evidence.recoverStale(now + 10, 100, 10)).isZero();
        assertThat(evidence.recoverStale(now + 101, 100, 10)).isEqualTo(1);
    }

    @Test
    void boundedRecoveryStartsAtOldestWorkSoTheTailCannotStarve() {
        DeliveryQueueRepository deliveries = new DeliveryQueueRepository(pool);
        long now = 300_000L;
        try (Jedis jedis = pool.getResource()) {
            jedis.del("dispatch:pending", "dispatch:processing", "dispatch:processing:claimed_at");
            jedis.rpush("dispatch:processing", "oldest-stale");
            jedis.zadd("dispatch:processing:claimed_at", now - 1_000, "oldest-stale");
            for (int i = 0; i < 1_000; i++) {
                String fresh = "fresh-" + i;
                jedis.lpush("dispatch:processing", fresh);
                jedis.zadd("dispatch:processing:claimed_at", now, fresh);
            }
        }

        assertThat(deliveries.recoverStaleProcessing(now, 100)).isEqualTo(1);
        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.lrange("dispatch:pending", 0, -1)).containsExactly("oldest-stale");
            assertThat(jedis.llen("dispatch:processing")).isEqualTo(1_000);
        }
    }
}
