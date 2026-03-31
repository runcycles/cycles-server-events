package io.runcycles.events.model;

import io.runcycles.events.transport.TransportResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelTest {

    @Test
    void delivery_builderAndGetters() {
        Instant now = Instant.now();
        Delivery delivery = Delivery.builder()
                .deliveryId("del-1")
                .subscriptionId("sub-1")
                .eventId("evt-1")
                .eventType("tenant.created")
                .status("PENDING")
                .attemptedAt(now)
                .completedAt(now)
                .attempts(3)
                .responseStatus(200)
                .responseTimeMs(50)
                .errorMessage("none")
                .nextRetryAt(now)
                .build();

        assertThat(delivery.getDeliveryId()).isEqualTo("del-1");
        assertThat(delivery.getSubscriptionId()).isEqualTo("sub-1");
        assertThat(delivery.getEventId()).isEqualTo("evt-1");
        assertThat(delivery.getEventType()).isEqualTo("tenant.created");
        assertThat(delivery.getStatus()).isEqualTo("PENDING");
        assertThat(delivery.getAttemptedAt()).isEqualTo(now);
        assertThat(delivery.getCompletedAt()).isEqualTo(now);
        assertThat(delivery.getAttempts()).isEqualTo(3);
        assertThat(delivery.getResponseStatus()).isEqualTo(200);
        assertThat(delivery.getResponseTimeMs()).isEqualTo(50);
        assertThat(delivery.getErrorMessage()).isEqualTo("none");
        assertThat(delivery.getNextRetryAt()).isEqualTo(now);
    }

    @Test
    void delivery_equalsAndHashCode() {
        Delivery d1 = Delivery.builder().deliveryId("del-1").status("PENDING").build();
        Delivery d2 = Delivery.builder().deliveryId("del-1").status("PENDING").build();
        Delivery d3 = Delivery.builder().deliveryId("del-2").status("PENDING").build();

        assertThat(d1).isEqualTo(d2);
        assertThat(d1.hashCode()).isEqualTo(d2.hashCode());
        assertThat(d1).isNotEqualTo(d3);
    }

    @Test
    void delivery_toString() {
        Delivery d = Delivery.builder().deliveryId("del-1").build();
        assertThat(d.toString()).contains("del-1");
    }

    @Test
    void delivery_setters() {
        Delivery d = new Delivery();
        d.setDeliveryId("del-1");
        d.setStatus("SUCCESS");
        assertThat(d.getDeliveryId()).isEqualTo("del-1");
        assertThat(d.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void event_builderAndGetters() {
        Instant now = Instant.now();
        Actor actor = Actor.builder().type(ActorType.ADMIN).keyId("key-1").sourceIp("127.0.0.1").build();
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(now)
                .tenantId("t-1")
                .scope("tenant:t-1")
                .actor(actor)
                .source("admin")
                .data(Map.of("key", "value"))
                .correlationId("cor-1")
                .requestId("req-1")
                .metadata(Map.of("meta", "data"))
                .build();

        assertThat(event.getEventId()).isEqualTo("evt-1");
        assertThat(event.getEventType()).isEqualTo("tenant.created");
        assertThat(event.getCategory()).isEqualTo(EventCategory.TENANT);
        assertThat(event.getTimestamp()).isEqualTo(now);
        assertThat(event.getTenantId()).isEqualTo("t-1");
        assertThat(event.getScope()).isEqualTo("tenant:t-1");
        assertThat(event.getActor()).isEqualTo(actor);
        assertThat(event.getSource()).isEqualTo("admin");
        assertThat(event.getData()).containsEntry("key", "value");
        assertThat(event.getCorrelationId()).isEqualTo("cor-1");
        assertThat(event.getRequestId()).isEqualTo("req-1");
        assertThat(event.getMetadata()).containsEntry("meta", "data");
    }

    @Test
    void subscription_builderAndGetters() {
        Instant now = Instant.now();
        RetryPolicy policy = RetryPolicy.builder().build();
        Subscription sub = Subscription.builder()
                .subscriptionId("sub-1")
                .tenantId("t-1")
                .name("My Webhook")
                .description("Receives tenant events")
                .url("https://example.com/hook")
                .eventTypes(List.of("tenant.created"))
                .eventCategories(List.of("tenant"))
                .scopeFilter("tenant:*")
                .headers(Map.of("X-Custom", "val"))
                .status("ACTIVE")
                .retryPolicy(policy)
                .disableAfterFailures(10)
                .consecutiveFailures(0)
                .lastTriggeredAt(now)
                .lastSuccessAt(now)
                .lastFailureAt(now)
                .createdAt(now)
                .updatedAt(now)
                .metadata(Map.of("env", "prod"))
                .build();

        assertThat(sub.getSubscriptionId()).isEqualTo("sub-1");
        assertThat(sub.getTenantId()).isEqualTo("t-1");
        assertThat(sub.getName()).isEqualTo("My Webhook");
        assertThat(sub.getDescription()).isEqualTo("Receives tenant events");
        assertThat(sub.getUrl()).isEqualTo("https://example.com/hook");
        assertThat(sub.getEventTypes()).containsExactly("tenant.created");
        assertThat(sub.getEventCategories()).containsExactly("tenant");
        assertThat(sub.getScopeFilter()).isEqualTo("tenant:*");
        assertThat(sub.getHeaders()).containsEntry("X-Custom", "val");
        assertThat(sub.getStatus()).isEqualTo("ACTIVE");
        assertThat(sub.getRetryPolicy()).isEqualTo(policy);
        assertThat(sub.getDisableAfterFailures()).isEqualTo(10);
        assertThat(sub.getConsecutiveFailures()).isEqualTo(0);
        assertThat(sub.getLastTriggeredAt()).isEqualTo(now);
        assertThat(sub.getLastSuccessAt()).isEqualTo(now);
        assertThat(sub.getLastFailureAt()).isEqualTo(now);
        assertThat(sub.getCreatedAt()).isEqualTo(now);
        assertThat(sub.getUpdatedAt()).isEqualTo(now);
        assertThat(sub.getMetadata()).containsEntry("env", "prod");
    }

    @Test
    void retryPolicy_defaults() {
        RetryPolicy policy = RetryPolicy.builder().build();

        assertThat(policy.getMaxRetries()).isEqualTo(5);
        assertThat(policy.getInitialDelayMs()).isEqualTo(1000);
        assertThat(policy.getBackoffMultiplier()).isEqualTo(2.0);
        assertThat(policy.getMaxDelayMs()).isEqualTo(60000);
    }

    @Test
    void retryPolicy_customValues() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(10)
                .initialDelayMs(500)
                .backoffMultiplier(3.0)
                .maxDelayMs(120000)
                .build();

        assertThat(policy.getMaxRetries()).isEqualTo(10);
        assertThat(policy.getInitialDelayMs()).isEqualTo(500);
        assertThat(policy.getBackoffMultiplier()).isEqualTo(3.0);
        assertThat(policy.getMaxDelayMs()).isEqualTo(120000);
    }

    @Test
    void actor_builderAndGetters() {
        Actor actor = Actor.builder()
                .type(ActorType.API_KEY)
                .keyId("key-1")
                .sourceIp("10.0.0.1")
                .build();

        assertThat(actor.getType()).isEqualTo(ActorType.API_KEY);
        assertThat(actor.getKeyId()).isEqualTo("key-1");
        assertThat(actor.getSourceIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void actorType_allValues() {
        ActorType[] values = ActorType.values();
        assertThat(values).containsExactly(
                ActorType.ADMIN, ActorType.API_KEY, ActorType.SYSTEM, ActorType.SCHEDULER);
        assertThat(ActorType.valueOf("ADMIN")).isEqualTo(ActorType.ADMIN);
    }

    @Test
    void actorType_jsonValueSerializesLowercase() {
        assertThat(ActorType.ADMIN.getValue()).isEqualTo("admin");
        assertThat(ActorType.API_KEY.getValue()).isEqualTo("api_key");
        assertThat(ActorType.SYSTEM.getValue()).isEqualTo("system");
        assertThat(ActorType.SCHEDULER.getValue()).isEqualTo("scheduler");
    }

    @Test
    void actorType_fromValue() {
        assertThat(ActorType.fromValue("admin")).isEqualTo(ActorType.ADMIN);
        assertThat(ActorType.fromValue("api_key")).isEqualTo(ActorType.API_KEY);
        assertThat(ActorType.fromValue("system")).isEqualTo(ActorType.SYSTEM);
        assertThat(ActorType.fromValue("scheduler")).isEqualTo(ActorType.SCHEDULER);
    }

    @Test
    void eventCategory_allValues() {
        EventCategory[] values = EventCategory.values();
        assertThat(values).containsExactly(
                EventCategory.BUDGET, EventCategory.TENANT, EventCategory.API_KEY,
                EventCategory.POLICY, EventCategory.RESERVATION, EventCategory.SYSTEM);
    }

    @Test
    void eventCategory_jsonValueSerializesLowercase() {
        assertThat(EventCategory.BUDGET.getValue()).isEqualTo("budget");
        assertThat(EventCategory.TENANT.getValue()).isEqualTo("tenant");
        assertThat(EventCategory.API_KEY.getValue()).isEqualTo("api_key");
        assertThat(EventCategory.POLICY.getValue()).isEqualTo("policy");
        assertThat(EventCategory.RESERVATION.getValue()).isEqualTo("reservation");
        assertThat(EventCategory.SYSTEM.getValue()).isEqualTo("system");
    }

    @Test
    void eventCategory_fromValue() {
        assertThat(EventCategory.fromValue("budget")).isEqualTo(EventCategory.BUDGET);
        assertThat(EventCategory.fromValue("api_key")).isEqualTo(EventCategory.API_KEY);
    }

    @Test
    void deliveryStatus_allValues() {
        DeliveryStatus[] values = DeliveryStatus.values();
        assertThat(values).containsExactly(
                DeliveryStatus.PENDING, DeliveryStatus.SUCCESS,
                DeliveryStatus.FAILED, DeliveryStatus.RETRYING);
    }

    @Test
    void webhookStatus_allValues() {
        WebhookStatus[] values = WebhookStatus.values();
        assertThat(values).containsExactly(
                WebhookStatus.ACTIVE, WebhookStatus.PAUSED, WebhookStatus.DISABLED);
    }

    @Test
    void eventType_allValues() {
        EventType[] values = EventType.values();
        assertThat(values.length).isEqualTo(40);
        // Spot-check a few
        assertThat(EventType.valueOf("BUDGET_CREATED")).isNotNull();
        assertThat(EventType.valueOf("TENANT_CREATED")).isNotNull();
        assertThat(EventType.valueOf("API_KEY_CREATED")).isNotNull();
        assertThat(EventType.valueOf("SYSTEM_HIGH_LATENCY")).isNotNull();
    }

    @Test
    void transportResult_builderAndGetters() {
        TransportResult result = TransportResult.builder()
                .success(true)
                .statusCode(200)
                .latencyMs(42)
                .errorMessage(null)
                .build();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatusCode()).isEqualTo(200);
        assertThat(result.getLatencyMs()).isEqualTo(42);
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    void transportResult_failure() {
        TransportResult result = TransportResult.builder()
                .success(false)
                .statusCode(500)
                .latencyMs(100)
                .errorMessage("Internal Server Error")
                .build();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Internal Server Error");
    }

    @Test
    void transportResult_equalsAndHashCode() {
        TransportResult r1 = TransportResult.builder().success(true).statusCode(200).latencyMs(10).build();
        TransportResult r2 = TransportResult.builder().success(true).statusCode(200).latencyMs(10).build();

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    }
}
