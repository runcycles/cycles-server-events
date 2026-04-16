package io.runcycles.events.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CyclesMetricsTest {

    private SimpleMeterRegistry registry;
    private CyclesMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CyclesMetrics(registry);
    }

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

    @Test
    void recordEventValidationWarning_incrementsCounter() {
        metrics.recordEventValidationWarning("budget.reset_spent", "reset_spent_shape");

        assertThat(registry.find(CyclesMetrics.EVENT_VALIDATION_WARNINGS)
                .tags("event_type", "budget.reset_spent", "rule", "reset_spent_shape")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void nullTenant_taggedAsUnknown() {
        metrics.recordDeliveryAttempt(null, "tenant.created");

        assertThat(registry.find(CyclesMetrics.DELIVERY_ATTEMPTS)
                .tags("tenant", CyclesMetrics.TAG_UNKNOWN, "event_type", "tenant.created")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void blankEventType_taggedAsUnknown() {
        metrics.recordDeliveryAttempt("acme", "  ");

        assertThat(registry.find(CyclesMetrics.DELIVERY_ATTEMPTS)
                .tags("tenant", "acme", "event_type", CyclesMetrics.TAG_UNKNOWN)
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void statusFamily_mapsAllBuckets() {
        metrics.recordDeliverySuccess("t", "e", 201, 1);
        metrics.recordDeliverySuccess("t", "e", 302, 1); // counted under 3xx
        metrics.recordDeliverySuccess("t", "e", 404, 1); // counted under 4xx
        metrics.recordDeliverySuccess("t", "e", 503, 1); // counted under 5xx
        metrics.recordDeliverySuccess("t", "e", 199, 1); // out-of-range -> unknown

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

        // Counter still incremented
        assertThat(registry.find(CyclesMetrics.DELIVERY_SUCCESS).counter().count()).isEqualTo(1.0);
        // But timer not registered for negative values
        assertThat(registry.find(CyclesMetrics.DELIVERY_LATENCY).timers()).isEmpty();
    }
}
