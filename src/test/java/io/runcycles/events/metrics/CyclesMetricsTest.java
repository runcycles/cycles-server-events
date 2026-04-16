package io.runcycles.events.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CyclesMetricsTest {

    private SimpleMeterRegistry registry;
    private CyclesMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CyclesMetrics(registry, true); // tenant tag enabled by default
    }

    // ---- Delivery lifecycle ----

    @Test
    void recordDeliveryAttempt_incrementsCounter() {
        metrics.recordDeliveryAttempt("acme", "budget.reset_spent");

        Counter c = registry.find(CyclesMetrics.DELIVERY_ATTEMPTS)
                .tags("tenant", "acme", "event_type", "budget.reset_spent")
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    void recordDeliveryAttempt_multipleIncrements() {
        metrics.recordDeliveryAttempt("acme", "tenant.created");
        metrics.recordDeliveryAttempt("acme", "tenant.created");
        metrics.recordDeliveryAttempt("acme", "tenant.created");

        assertThat(registry.find(CyclesMetrics.DELIVERY_ATTEMPTS)
                .tags("tenant", "acme", "event_type", "tenant.created")
                .counter().count()).isEqualTo(3.0);
    }

    @Test
    void recordDeliverySuccess_incrementsCounterAndTimer() {
        metrics.recordDeliverySuccess("acme", "policy.created", 200, 42);

        Counter c = registry.find(CyclesMetrics.DELIVERY_SUCCESS)
                .tags("tenant", "acme", "event_type", "policy.created", "status_code_family", "2xx")
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);

        Timer t = registry.find(CyclesMetrics.DELIVERY_LATENCY)
                .tags("tenant", "acme", "event_type", "policy.created", "outcome", "success")
                .timer();
        assertThat(t).isNotNull();
        assertThat(t.count()).isEqualTo(1L);
    }

    @Test
    void recordDeliveryFailure_incrementsCounterAndTimerWhenLatencyPositive() {
        metrics.recordDeliveryFailure("acme", "budget.created", "http_5xx", 100);

        assertThat(registry.find(CyclesMetrics.DELIVERY_FAILED)
                .tags("tenant", "acme", "event_type", "budget.created", "reason", "http_5xx")
                .counter().count()).isEqualTo(1.0);

        assertThat(registry.find(CyclesMetrics.DELIVERY_LATENCY)
                .tags("tenant", "acme", "event_type", "budget.created", "outcome", "failure")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void recordDeliveryFailure_skipsTimerWhenLatencyZero() {
        // Upstream failures (event_not_found) have no transport round-trip -> no latency
        metrics.recordDeliveryFailure("acme", "budget.created", "event_not_found", 0);

        assertThat(registry.find(CyclesMetrics.DELIVERY_FAILED)
                .tags("tenant", "acme", "event_type", "budget.created", "reason", "event_not_found")
                .counter().count()).isEqualTo(1.0);

        assertThat(registry.find(CyclesMetrics.DELIVERY_LATENCY)
                .tags("tenant", "acme", "event_type", "budget.created", "outcome", "failure")
                .timer()).isNull();
    }

    @Test
    void recordDeliveryRetried_incrementsCounter() {
        metrics.recordDeliveryRetried("acme", "api_key.revoked");

        assertThat(registry.find(CyclesMetrics.DELIVERY_RETRIED)
                .tags("tenant", "acme", "event_type", "api_key.revoked")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordDeliveryStale_incrementsCounter() {
        metrics.recordDeliveryStale("acme");

        assertThat(registry.find(CyclesMetrics.DELIVERY_STALE)
                .tag("tenant", "acme")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordSubscriptionAutoDisabled_incrementsCounter() {
        metrics.recordSubscriptionAutoDisabled("acme", "consecutive_failures");

        assertThat(registry.find(CyclesMetrics.SUBSCRIPTION_AUTO_DISABLED)
                .tags("tenant", "acme", "reason", "consecutive_failures")
                .counter().count()).isEqualTo(1.0);
    }

    // ---- Event payload validation ----

    @Test
    void recordEventsPayloadInvalid_incrementsCounter() {
        metrics.recordEventsPayloadInvalid("budget.reset_spent", "reset_spent_shape");

        // Parallel to admin's cycles_admin_events_payload_invalid_total{type, expected_class}
        assertThat(registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID)
                .tags("type", "budget.reset_spent", "rule", "reset_spent_shape")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordEventsPayloadInvalid_hasNoTenantDimension() {
        // Intentional: payload-shape validity is about the event, not the tenant
        metrics.recordEventsPayloadInvalid("budget.created", "budget_data_shape");

        Counter c = registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID)
                .tag("type", "budget.created").counter();
        assertThat(c).isNotNull();
        assertThat(c.getId().getTag("tenant")).isNull();
    }

    // ---- Normalisation ----

    @Test
    void nullTenant_normalisesToUppercaseUNKNOWN() {
        metrics.recordDeliveryAttempt(null, "tenant.created");

        assertThat(registry.find(CyclesMetrics.DELIVERY_ATTEMPTS)
                .tags("tenant", "UNKNOWN", "event_type", "tenant.created")
                .counter().count()).isEqualTo(1.0);
        assertThat(CyclesMetrics.TAG_UNKNOWN).isEqualTo("UNKNOWN"); // cycles-server parity
    }

    @Test
    void blankEventType_normalisesToUNKNOWN() {
        metrics.recordDeliveryAttempt("acme", "  ");

        assertThat(registry.find(CyclesMetrics.DELIVERY_ATTEMPTS)
                .tags("tenant", "acme", "event_type", CyclesMetrics.TAG_UNKNOWN)
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void statusFamily_mapsAllBuckets() {
        metrics.recordDeliverySuccess("t", "e", 201, 1);
        metrics.recordDeliverySuccess("t", "e", 302, 1); // 3xx
        metrics.recordDeliverySuccess("t", "e", 404, 1); // 4xx
        metrics.recordDeliverySuccess("t", "e", 503, 1); // 5xx
        metrics.recordDeliverySuccess("t", "e", 199, 1); // out-of-range -> UNKNOWN

        assertThat(registry.find(CyclesMetrics.DELIVERY_SUCCESS)
                .tag("status_code_family", "2xx").counter().count()).isEqualTo(1.0);
        assertThat(registry.find(CyclesMetrics.DELIVERY_SUCCESS)
                .tag("status_code_family", "3xx").counter().count()).isEqualTo(1.0);
        assertThat(registry.find(CyclesMetrics.DELIVERY_SUCCESS)
                .tag("status_code_family", "4xx").counter().count()).isEqualTo(1.0);
        assertThat(registry.find(CyclesMetrics.DELIVERY_SUCCESS)
                .tag("status_code_family", "5xx").counter().count()).isEqualTo(1.0);
        assertThat(registry.find(CyclesMetrics.DELIVERY_SUCCESS)
                .tag("status_code_family", CyclesMetrics.TAG_UNKNOWN).counter().count()).isEqualTo(1.0);
    }

    @Test
    void negativeLatency_skipsTimer() {
        metrics.recordDeliverySuccess("acme", "tenant.created", 200, -1);

        assertThat(registry.find(CyclesMetrics.DELIVERY_SUCCESS).counter().count()).isEqualTo(1.0);
        assertThat(registry.find(CyclesMetrics.DELIVERY_LATENCY).timers()).isEmpty();
    }

    // ---- Tenant-tag cardinality toggle (cycles-server parity) ----

    @Nested
    class TenantTagDisabled {

        private SimpleMeterRegistry r;
        private CyclesMetrics m;

        @BeforeEach
        void setUp() {
            r = new SimpleMeterRegistry();
            m = new CyclesMetrics(r, false); // high-cardinality deployment: tenant omitted
        }

        @Test
        void attempt_omitsTenantTag() {
            m.recordDeliveryAttempt("acme", "tenant.created");

            Counter c = r.find(CyclesMetrics.DELIVERY_ATTEMPTS)
                    .tag("event_type", "tenant.created").counter();
            assertThat(c).isNotNull();
            assertThat(c.count()).isEqualTo(1.0);
            assertThat(c.getId().getTag("tenant")).isNull();
        }

        @Test
        void autoDisabled_omitsTenantTag() {
            m.recordSubscriptionAutoDisabled("acme", "consecutive_failures");

            Counter c = r.find(CyclesMetrics.SUBSCRIPTION_AUTO_DISABLED)
                    .tag("reason", "consecutive_failures").counter();
            assertThat(c).isNotNull();
            assertThat(c.getId().getTag("tenant")).isNull();
        }

        @Test
        void latency_omitsTenantTag() {
            m.recordDeliverySuccess("acme", "tenant.created", 200, 50);

            Timer t = r.find(CyclesMetrics.DELIVERY_LATENCY)
                    .tags("event_type", "tenant.created", "outcome", "success").timer();
            assertThat(t).isNotNull();
            assertThat(t.getId().getTag("tenant")).isNull();
        }
    }
}
