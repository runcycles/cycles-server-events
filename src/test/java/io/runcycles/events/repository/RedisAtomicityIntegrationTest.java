package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.events.config.CryptoService;
import io.runcycles.events.evidence.EvidenceQueueConsumer;
import io.runcycles.events.evidence.EvidenceQueueConsumer.ClaimedEvidence;
import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.DeliveryStatus;
import io.runcycles.events.model.DispatcherEventTask;
import io.runcycles.events.model.Event;
import io.runcycles.events.metrics.CyclesMetrics;
import io.runcycles.events.repository.DeliveryQueueRepository.ClaimedDelivery;
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
        int calls = 50;
        try (Jedis jedis = pool.getResource()) {
            jedis.set("webhook:concurrent", """
                    {"subscription_id":"concurrent","status":"ACTIVE",
                     "consecutive_failures":0,"disable_after_failures":25,
                     "url":"https://operator-edited.example/hook"}
                    """);
            for (int i = 0; i < calls; i++) {
                String id = "concurrent-" + i;
                jedis.set("delivery:" + id, mapper.writeValueAsString(Delivery.builder()
                        .deliveryId(id)
                        .subscriptionId("concurrent")
                        .eventId("event-" + i)
                        .eventType("tenant.created")
                        .status(DeliveryStatus.PENDING)
                        .attempts(5)
                        .build()));
                jedis.hset("dispatch:processing:claim_owner", id, "claim-" + id);
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<SubscriptionRepository.TerminalFailureUpdate>> tasks = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            int sequence = i;
            tasks.add(() -> {
                start.await();
                String id = "concurrent-" + sequence;
                Delivery failed = Delivery.builder()
                        .deliveryId(id)
                        .subscriptionId("concurrent")
                        .eventId("event-" + sequence)
                        .eventType("tenant.created")
                        .status(DeliveryStatus.FAILED)
                        .attempts(6)
                        .completedAt(Instant.now())
                        .build();
                return repository.finalizeDeliveryFailure(
                        "concurrent", new ClaimedDelivery(id, "claim-" + id), failed,
                        Instant.now(), 10,
                        DispatcherEventTask.create("disable-" + id, Event.builder()
                                .eventType("webhook.disabled")
                                .data(new java.util.LinkedHashMap<>())
                                .build()),
                        DispatcherEventTask.create("failure-" + id, Event.builder()
                                .eventType("system.webhook_delivery_failed")
                                .build()));
            });
        }
        List<Future<SubscriptionRepository.TerminalFailureUpdate>> futures = new ArrayList<>();
        for (Callable<SubscriptionRepository.TerminalFailureUpdate> task : tasks) {
            futures.add(executor.submit(task));
        }
        start.countDown();

        int disableTransitions = 0;
        for (Future<SubscriptionRepository.TerminalFailureUpdate> future : futures) {
            SubscriptionRepository.TerminalFailureUpdate result = future.get();
            assertThat(result.deliveryFound()).isTrue();
            if (result.disabledNow()) disableTransitions++;
        }
        executor.shutdownNow();

        try (Jedis jedis = pool.getResource()) {
            JsonNode stored = mapper.readTree(jedis.get("webhook:concurrent"));
            assertThat(stored.path("consecutive_failures").asInt()).isEqualTo(calls);
            assertThat(stored.path("status").asText()).isEqualTo("DISABLED");
            assertThat(stored.path("url").asText())
                    .isEqualTo("https://operator-edited.example/hook");
            List<String> staged = jedis.zrange(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY, 0, -1)
                    .stream()
                    .filter(id -> id.startsWith("disable-concurrent-")
                            || id.startsWith("failure-concurrent-"))
                    .toList();
            assertThat(staged.stream().filter(id -> id.startsWith("disable-")).count()).isEqualTo(1);
            assertThat(staged.stream().filter(id -> id.startsWith("failure-")).count()).isEqualTo(calls);
            for (int i = 0; i < calls; i++) {
                assertThat(mapper.readTree(jedis.get("delivery:concurrent-" + i)).path("status").asText())
                        .isEqualTo("FAILED");
            }
        }
        assertThat(disableTransitions).isEqualTo(1);
    }

    @Test
    void deliveryUpdatePreservesTtlAndNeverResurrectsDeletedRecord() throws Exception {
        DeliveryRepository repository = new DeliveryRepository(pool, mapper);
        Delivery delivery = Delivery.builder().deliveryId("ttl").status(DeliveryStatus.FAILED).build();
        try (Jedis jedis = pool.getResource()) {
            jedis.psetex("delivery:ttl", 60_000, "{\"delivery_id\":\"ttl\",\"status\":\"PENDING\"}");
            jedis.hset("dispatch:processing:claim_owner", "ttl", "claim-ttl");
        }

        assertThat(repository.updateOwned(delivery,
                new ClaimedDelivery("ttl", "claim-ttl"))).isTrue();

        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.pttl("delivery:ttl")).isBetween(1L, 60_000L);
            jedis.del("delivery:ttl");
        }
        assertThat(repository.updateOwned(delivery,
                new ClaimedDelivery("ttl", "claim-ttl"))).isFalse();
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
            jedis.hset("dispatch:processing:claim_owner", "terminal-atomic", "claim-terminal");
        }

        SubscriptionRepository.TerminalFailureUpdate result = repository.finalizeDeliveryFailure(
                "paused-sub", new ClaimedDelivery("terminal-atomic", "claim-terminal"), failed,
                Instant.parse("2026-07-15T12:00:00Z"), 10,
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
                events, new CyclesMetrics(registry, false), 10, 30_000, 1_000, 100, 10_000);

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
    void claimedOutboxTaskCannotBeAcknowledgedByInlinePublisher() throws Exception {
        String taskId = "outbox-owned-ack";
        String owner = "publisher-a";
        DispatcherEventTask task = DispatcherEventTask.create(taskId,
                Event.builder().eventType("system.webhook_delivery_failed").build());
        EventRepository events = new EventRepository(pool, mapper);
        try (Jedis jedis = pool.getResource()) {
            jedis.del(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY,
                    EventRepository.dispatcherOutboxTaskKey(taskId),
                    "dispatcher:event-outbox:lock:" + taskId,
                    "dispatcher:event-outbox:attempts");
            jedis.set(EventRepository.dispatcherOutboxTaskKey(taskId), mapper.writeValueAsString(task));
            jedis.zadd(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY,
                    System.currentTimeMillis() - 1, taskId);
        }

        assertThat(events.tryClaimDispatcherEvent(taskId, owner, 30_000)).isTrue();
        assertThat(events.ackDispatcherEvent(taskId)).isFalse();
        assertThat(events.ackClaimedDispatcherEvent(taskId, owner)).isTrue();
        assertThat(events.ackClaimedDispatcherEvent(taskId, owner)).isFalse();
        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.exists(EventRepository.dispatcherOutboxTaskKey(taskId))).isFalse();
            assertThat(jedis.zscore(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY, taskId)).isNull();
            assertThat(jedis.exists("dispatcher:event-outbox:lock:" + taskId)).isFalse();
        }
    }

    @Test
    void poisonOutboxTaskMovesToBoundedDeadLetterQueue() throws Exception {
        String taskId = "outbox-poison";
        String malformedTask = "{not-json";
        try (Jedis jedis = pool.getResource()) {
            jedis.del(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY,
                    EventRepository.DISPATCHER_OUTBOX_FAILED_KEY,
                    EventRepository.dispatcherOutboxTaskKey(taskId),
                    "dispatcher:event-outbox:lock:" + taskId,
                    "dispatcher:event-outbox:attempts");
            jedis.set(EventRepository.dispatcherOutboxTaskKey(taskId), malformedTask);
            jedis.zadd(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY,
                    System.currentTimeMillis() - 1, taskId);
        }
        EventRepository events = new EventRepository(pool, mapper);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DispatcherEventOutboxPublisher publisher = new DispatcherEventOutboxPublisher(
                events, new CyclesMetrics(registry, false), 10, 30_000, 1_000, 1, 10);

        publisher.publishDue();

        try (Jedis jedis = pool.getResource()) {
            List<String> failed = jedis.lrange(EventRepository.DISPATCHER_OUTBOX_FAILED_KEY, 0, -1);
            assertThat(failed).hasSize(1);
            JsonNode deadLetter = mapper.readTree(failed.getFirst());
            assertThat(deadLetter.path("task_id").asText()).isEqualTo(taskId);
            assertThat(deadLetter.path("payload").asText()).isEqualTo(malformedTask);
            assertThat(jedis.exists(EventRepository.dispatcherOutboxTaskKey(taskId))).isFalse();
            assertThat(jedis.zscore(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY, taskId)).isNull();
            assertThat(jedis.hexists("dispatcher:event-outbox:attempts", taskId)).isFalse();
            assertThat(jedis.exists("dispatcher:event-outbox:lock:" + taskId)).isFalse();
        }
        assertThat(registry.find(CyclesMetrics.DISPATCHER_EVENT_DEAD_LETTERED)
                .tag("event_type", CyclesMetrics.TAG_UNKNOWN)
                .tag("reason", "attempts_exhausted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void youngCrashOrphansBecomeRecoverableOnPeriodicPass() {
        DeliveryQueueRepository deliveries = new DeliveryQueueRepository(pool);
        long now = 100_000L;
        try (Jedis jedis = pool.getResource()) {
            jedis.del("dispatch:pending", "dispatch:processing", "dispatch:processing:claimed_at",
                    "dispatch:processing:claim_owner");
            jedis.lpush("dispatch:processing", "delivery-orphan");
            jedis.zadd("dispatch:processing:claimed_at", now, "delivery-orphan");
            jedis.hset("dispatch:processing:claim_owner", "delivery-orphan", "claim-orphan");
        }

        assertThat(deliveries.recoverStaleProcessing(now + 10, 100)).isZero();
        assertThat(deliveries.recoverStaleProcessing(now + 101, 100)).isEqualTo(1);
        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.lrange("dispatch:pending", 0, -1)).containsExactly("delivery-orphan");
        }
    }

    @Test
    void staleDeliveryAckCannotRemoveSuccessorClaim() {
        DeliveryQueueRepository deliveries = new DeliveryQueueRepository(pool);
        try (Jedis jedis = pool.getResource()) {
            jedis.del("dispatch:pending", "dispatch:processing", "dispatch:processing:claimed_at",
                    "dispatch:processing:claim_owner");
            jedis.lpush("dispatch:pending", "owned-delivery");
        }

        ClaimedDelivery first = deliveries.claimPending(1);
        assertThat(first).isNotNull();
        assertThat(deliveries.recoverStaleProcessing(System.currentTimeMillis() + 1_000, 100))
                .isEqualTo(1);
        ClaimedDelivery successor = deliveries.claimPending(1);
        assertThat(successor).isNotNull();
        assertThat(successor.claimToken()).isNotEqualTo(first.claimToken());

        assertThat(deliveries.ack(first)).isFalse();
        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.lrange("dispatch:processing", 0, -1)).containsExactly("owned-delivery");
            assertThat(jedis.hget("dispatch:processing:claim_owner", "owned-delivery"))
                    .isEqualTo(successor.claimToken());
        }
        assertThat(deliveries.ack(successor)).isTrue();
    }

    @Test
    void sameDeliveryTerminalTransitionAppliesExactlyOnceUnderContention() throws Exception {
        SubscriptionRepository repository = new SubscriptionRepository(pool, mapper, new CryptoService(""));
        String deliveryId = "same-terminal";
        ClaimedDelivery claim = new ClaimedDelivery(deliveryId, "same-owner");
        try (Jedis jedis = pool.getResource()) {
            jedis.set("delivery:" + deliveryId, mapper.writeValueAsString(Delivery.builder()
                    .deliveryId(deliveryId).subscriptionId("same-sub").eventId("same-event")
                    .eventType("tenant.created").status(DeliveryStatus.PENDING).attempts(5).build()));
            jedis.set("webhook:same-sub", """
                    {"subscription_id":"same-sub","status":"ACTIVE",
                     "consecutive_failures":0,"disable_after_failures":10}
                    """);
            jedis.hset("dispatch:processing:claim_owner", deliveryId, claim.claimToken());
        }

        int calls = 32;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SubscriptionRepository.TerminalFailureUpdate>> futures = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                Delivery failed = Delivery.builder().deliveryId(deliveryId)
                        .subscriptionId("same-sub").eventId("same-event")
                        .eventType("tenant.created").status(DeliveryStatus.FAILED)
                        .attempts(6).completedAt(Instant.now()).build();
                return repository.finalizeDeliveryFailure("same-sub", claim, failed,
                        Instant.now(), 10,
                        DispatcherEventTask.create("same-disable", Event.builder()
                                .eventType("webhook.disabled")
                                .data(new java.util.LinkedHashMap<>()).build()),
                        DispatcherEventTask.create("same-failure", Event.builder()
                                .eventType("system.webhook_delivery_failed").build()));
            }));
        }
        start.countDown();
        int applied = 0;
        for (Future<SubscriptionRepository.TerminalFailureUpdate> future : futures) {
            if (future.get().applied()) applied++;
        }
        executor.shutdownNow();

        assertThat(applied).isEqualTo(1);
        try (Jedis jedis = pool.getResource()) {
            assertThat(mapper.readTree(jedis.get("delivery:" + deliveryId)).path("status").asText())
                    .isEqualTo("FAILED");
            assertThat(mapper.readTree(jedis.get("webhook:same-sub"))
                    .path("consecutive_failures").asInt()).isEqualTo(1);
            assertThat(jedis.zscore(EventRepository.DISPATCHER_OUTBOX_PENDING_KEY,
                    "same-failure")).isNotNull();
        }
    }

    @Test
    void staleDeliveryOwnerCannotOverwriteSuccessorOrScheduleRetry() throws Exception {
        SubscriptionRepository subscriptions = new SubscriptionRepository(pool, mapper, new CryptoService(""));
        DeliveryRepository deliveries = new DeliveryRepository(pool, mapper);
        String deliveryId = "successor-fence";
        ClaimedDelivery stale = new ClaimedDelivery(deliveryId, "old-owner");
        ClaimedDelivery successor = new ClaimedDelivery(deliveryId, "new-owner");
        try (Jedis jedis = pool.getResource()) {
            jedis.set("delivery:" + deliveryId, mapper.writeValueAsString(Delivery.builder()
                    .deliveryId(deliveryId).subscriptionId("successor-sub").eventId("source")
                    .eventType("tenant.created").status(DeliveryStatus.PENDING).attempts(1).build()));
            jedis.set("webhook:successor-sub", """
                    {"subscription_id":"successor-sub","status":"ACTIVE",
                     "consecutive_failures":0,"disable_after_failures":10}
                    """);
            jedis.hset("dispatch:processing:claim_owner", deliveryId, successor.claimToken());
            jedis.zrem("dispatch:retry", deliveryId);
        }

        Delivery staleSuccess = Delivery.builder().deliveryId(deliveryId)
                .subscriptionId("successor-sub").eventId("source")
                .eventType("tenant.created").status(DeliveryStatus.SUCCESS).attempts(2).build();
        assertThat(subscriptions.finalizeDeliverySuccess("successor-sub", stale,
                staleSuccess, Instant.now()).applied()).isFalse();
        Delivery staleRetry = Delivery.builder().deliveryId(deliveryId)
                .subscriptionId("successor-sub").eventId("source")
                .eventType("tenant.created").status(DeliveryStatus.RETRYING).attempts(2).build();
        assertThat(deliveries.updateOwnedAndScheduleRetry(staleRetry, stale, 1234L)).isFalse();

        Delivery failed = Delivery.builder().deliveryId(deliveryId)
                .subscriptionId("successor-sub").eventId("source")
                .eventType("tenant.created").status(DeliveryStatus.FAILED).attempts(2).build();
        assertThat(subscriptions.finalizeDeliveryFailure("successor-sub", successor, failed,
                Instant.now(), 10,
                DispatcherEventTask.create("successor-disable", Event.builder()
                        .eventType("webhook.disabled").data(new java.util.LinkedHashMap<>()).build()),
                DispatcherEventTask.create("successor-failure", Event.builder()
                        .eventType("system.webhook_delivery_failed").build())).applied()).isTrue();

        try (Jedis jedis = pool.getResource()) {
            assertThat(mapper.readTree(jedis.get("delivery:" + deliveryId)).path("status").asText())
                    .isEqualTo("FAILED");
            assertThat(jedis.zscore("dispatch:retry", deliveryId)).isNull();
            assertThat(mapper.readTree(jedis.get("webhook:successor-sub"))
                    .path("consecutive_failures").asInt()).isEqualTo(1);
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
                    "evidence:test:processing:claimed_at", "evidence:test:processing:claim_owner",
                    "evidence:test:processing:payload");
            jedis.lpush("evidence:test:processing", record);
            jedis.zadd("evidence:test:processing:claimed_at", now, record);
            jedis.hset("evidence:test:processing:claim_owner", record, "legacy-owner");
        }

        assertThat(evidence.recoverStale(now + 10, 100, 10)).isZero();
        assertThat(evidence.recoverStale(now + 101, 100, 10)).isEqualTo(1);
    }

    @Test
    void evidenceAckAndDeadLetterScriptsExecuteAgainstRealRedis() {
        EvidenceQueueConsumer evidence = new EvidenceQueueConsumer(
                pool, "evidence:lua:pending", "evidence:lua:processing", "evidence:lua:failed", 2);
        String valid = "{\"artifact_type\":\"reserve\",\"sequence\":1}";
        String poison = "{ not valid json";
        try (Jedis jedis = pool.getResource()) {
            jedis.del("evidence:lua:pending", "evidence:lua:processing",
                    "evidence:lua:processing:claimed_at", "evidence:lua:processing:claim_owner",
                    "evidence:lua:processing:payload", "evidence:lua:failed");
            jedis.lpush("evidence:lua:pending", valid);
        }

        ClaimedEvidence validClaim = evidence.claim(1);
        assertThat(validClaim.recordJson()).isEqualTo(valid);
        assertThat(evidence.ack(validClaim)).isTrue();
        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.llen("evidence:lua:processing")).isZero();
            assertThat(jedis.zscore("evidence:lua:processing:claimed_at",
                    validClaim.claimToken())).isNull();
            jedis.lpush("evidence:lua:pending", poison);
        }

        ClaimedEvidence poisonClaim = evidence.claim(1);
        assertThat(poisonClaim.recordJson()).isEqualTo(poison);
        assertThat(evidence.deadLetterAndAck(poisonClaim)).isTrue();
        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.lrange("evidence:lua:failed", 0, -1)).containsExactly(poison);
            assertThat(jedis.llen("evidence:lua:processing")).isZero();
            assertThat(jedis.zscore("evidence:lua:processing:claimed_at",
                    poisonClaim.claimToken())).isNull();
        }
    }

    @Test
    void staleEvidenceOwnerCannotAckOrDeadLetterSuccessorClaim() {
        EvidenceQueueConsumer evidence = new EvidenceQueueConsumer(
                pool, "evidence:fence:pending", "evidence:fence:processing",
                "evidence:fence:failed", 10);
        String record = "{\"artifact_type\":\"reserve\",\"sequence\":7}";
        try (Jedis jedis = pool.getResource()) {
            jedis.del("evidence:fence:pending", "evidence:fence:processing",
                    "evidence:fence:processing:claimed_at", "evidence:fence:processing:claim_owner",
                    "evidence:fence:processing:payload", "evidence:fence:failed");
            jedis.lpush("evidence:fence:pending", record);
        }

        ClaimedEvidence stale = evidence.claim(1);
        assertThat(evidence.recoverStale(System.currentTimeMillis() + 1_000, 100, 10))
                .isEqualTo(1);
        ClaimedEvidence successor = evidence.claim(1);
        assertThat(successor.claimToken()).isNotEqualTo(stale.claimToken());

        assertThat(evidence.ack(stale)).isFalse();
        assertThat(evidence.deadLetterAndAck(stale)).isFalse();
        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.lrange("evidence:fence:processing", 0, -1))
                    .containsExactly(successor.claimToken());
            assertThat(jedis.lrange("evidence:fence:failed", 0, -1)).isEmpty();
        }
        assertThat(evidence.ack(successor)).isTrue();
    }

    @Test
    void identicalEvidencePayloadsReceiveIndependentProcessingIdentities() {
        EvidenceQueueConsumer evidence = new EvidenceQueueConsumer(
                pool, "evidence:duplicate:pending", "evidence:duplicate:processing",
                "evidence:duplicate:failed", 10);
        String record = "{\"artifact_type\":\"reserve\",\"same\":true}";
        try (Jedis jedis = pool.getResource()) {
            jedis.del("evidence:duplicate:pending", "evidence:duplicate:processing",
                    "evidence:duplicate:processing:claimed_at",
                    "evidence:duplicate:processing:claim_owner",
                    "evidence:duplicate:processing:payload", "evidence:duplicate:failed");
            jedis.lpush("evidence:duplicate:pending", record, record);
            // Simulate recovery observing the BLMOVE member before claim-ID
            // installation; the claim script must not leak these raw-key entries.
            jedis.zadd("evidence:duplicate:processing:claimed_at", 1, record);
            jedis.hset("evidence:duplicate:processing:claim_owner", record, "__orphan__");
        }

        ClaimedEvidence first = evidence.claim(1);
        ClaimedEvidence second = evidence.claim(1);
        assertThat(first.recordJson()).isEqualTo(record);
        assertThat(second.recordJson()).isEqualTo(record);
        assertThat(first.claimToken()).isNotEqualTo(second.claimToken());
        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.lrange("evidence:duplicate:processing", 0, -1))
                    .containsExactlyInAnyOrder(first.claimToken(), second.claimToken());
            assertThat(jedis.zscore("evidence:duplicate:processing:claimed_at", record)).isNull();
            assertThat(jedis.hget("evidence:duplicate:processing:claim_owner", record)).isNull();
        }
        assertThat(evidence.ack(first)).isTrue();
        assertThat(evidence.ack(second)).isTrue();
    }

    @Test
    void boundedRecoveryStartsAtOldestWorkSoTheTailCannotStarve() {
        DeliveryQueueRepository deliveries = new DeliveryQueueRepository(pool);
        long now = 300_000L;
        try (Jedis jedis = pool.getResource()) {
            jedis.del("dispatch:pending", "dispatch:processing", "dispatch:processing:claimed_at",
                    "dispatch:processing:claim_owner");
            jedis.rpush("dispatch:processing", "oldest-stale");
            jedis.zadd("dispatch:processing:claimed_at", now - 1_000, "oldest-stale");
            jedis.hset("dispatch:processing:claim_owner", "oldest-stale", "oldest-owner");
            for (int i = 0; i < 1_000; i++) {
                String fresh = "fresh-" + i;
                jedis.lpush("dispatch:processing", fresh);
                jedis.zadd("dispatch:processing:claimed_at", now, fresh);
                jedis.hset("dispatch:processing:claim_owner", fresh, "owner-" + i);
            }
        }

        assertThat(deliveries.recoverStaleProcessing(now, 100)).isEqualTo(1);
        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.lrange("dispatch:pending", 0, -1)).containsExactly("oldest-stale");
            assertThat(jedis.llen("dispatch:processing")).isEqualTo(1_000);
        }
    }
}
