package io.runcycles.events.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Domain-specific counters and timer for webhook dispatch observability.
 *
 * <p>Naming mirrors cycles-server's CyclesMetrics (v0.1.25.10):
 * all metric names are prefixed "cycles_webhook_" and counters use the
 * "_total" suffix.
 *
 * <p>Tag conventions:
 * <ul>
 *   <li><code>tenant</code>: owner tenant id; {@link #TAG_UNKNOWN} when a
 *       subscription hasn't loaded yet (e.g., stale delivery or missing event).</li>
 *   <li><code>event_type</code>: the canonical event.type string
 *       (e.g., <code>budget.reset_spent</code>).</li>
 *   <li><code>reason</code>: why a delivery or subscription transition occurred
 *       (<code>http_4xx</code>, <code>http_5xx</code>, <code>transport_error</code>,
 *       <code>event_not_found</code>, <code>subscription_not_found</code>,
 *       <code>subscription_inactive</code>, <code>consecutive_failures</code>).</li>
 *   <li><code>status_code_family</code>: HTTP status bucket on success
 *       (<code>2xx</code>).</li>
 *   <li><code>outcome</code>: success | failure (timer only).</li>
 * </ul>
 *
 * <p>Scraped at <code>/actuator/prometheus</code> by the Prometheus registry
 * autowired via spring-boot-starter-actuator + micrometer-registry-prometheus.
 *
 * <p>Double-counting rule: the <code>stale</code> path increments only
 * {@code cycles_webhook_delivery_stale_total} (not {@code failed_total}).
 * Transport-level failures (HTTP or connection) increment
 * {@code cycles_webhook_delivery_failed_total} with an appropriate
 * <code>reason</code> tag.
 */
@Component
public class CyclesMetrics {

    public static final String DELIVERY_ATTEMPTS = "cycles_webhook_delivery_attempts_total";
    public static final String DELIVERY_SUCCESS = "cycles_webhook_delivery_success_total";
    public static final String DELIVERY_FAILED = "cycles_webhook_delivery_failed_total";
    public static final String DELIVERY_RETRIED = "cycles_webhook_delivery_retried_total";
    public static final String DELIVERY_STALE = "cycles_webhook_delivery_stale_total";
    public static final String SUBSCRIPTION_AUTO_DISABLED = "cycles_webhook_subscription_auto_disabled_total";
    public static final String DELIVERY_LATENCY = "cycles_webhook_delivery_latency_seconds";

    public static final String EVENT_VALIDATION_WARNINGS = "cycles_webhook_event_validation_warnings_total";

    public static final String TAG_UNKNOWN = "unknown";
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    private final MeterRegistry registry;

    public CyclesMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDeliveryAttempt(String tenant, String eventType) {
        registry.counter(DELIVERY_ATTEMPTS,
                "tenant", safe(tenant),
                "event_type", safe(eventType)
        ).increment();
    }

    public void recordDeliverySuccess(String tenant, String eventType, int statusCode, long latencyMs) {
        registry.counter(DELIVERY_SUCCESS,
                "tenant", safe(tenant),
                "event_type", safe(eventType),
                "status_code_family", statusFamily(statusCode)
        ).increment();
        recordLatency(tenant, eventType, OUTCOME_SUCCESS, latencyMs);
    }

    public void recordDeliveryFailure(String tenant, String eventType, String reason, long latencyMs) {
        registry.counter(DELIVERY_FAILED,
                "tenant", safe(tenant),
                "event_type", safe(eventType),
                "reason", safe(reason)
        ).increment();
        if (latencyMs > 0) {
            // Only record latency when a transport round-trip actually occurred
            // (upstream failures like event_not_found have no meaningful latency).
            recordLatency(tenant, eventType, OUTCOME_FAILURE, latencyMs);
        }
    }

    public void recordDeliveryRetried(String tenant, String eventType) {
        registry.counter(DELIVERY_RETRIED,
                "tenant", safe(tenant),
                "event_type", safe(eventType)
        ).increment();
    }

    public void recordDeliveryStale(String tenant) {
        registry.counter(DELIVERY_STALE,
                "tenant", safe(tenant)
        ).increment();
    }

    public void recordSubscriptionAutoDisabled(String tenant, String reason) {
        registry.counter(SUBSCRIPTION_AUTO_DISABLED,
                "tenant", safe(tenant),
                "reason", safe(reason)
        ).increment();
    }

    /**
     * Emitted by {@code EventPayloadValidator} for each non-fatal shape
     * discrepancy found on an ingested event. Never increments when the event
     * is well-formed.
     */
    public void recordEventValidationWarning(String eventType, String rule) {
        registry.counter(EVENT_VALIDATION_WARNINGS,
                "event_type", safe(eventType),
                "rule", safe(rule)
        ).increment();
    }

    // --- internals ---

    private void recordLatency(String tenant, String eventType, String outcome, long latencyMs) {
        if (latencyMs < 0) return;
        Timer timer = Timer.builder(DELIVERY_LATENCY)
                .tags(Tags.of(
                        "tenant", safe(tenant),
                        "event_type", safe(eventType),
                        "outcome", outcome))
                .register(registry);
        timer.record(Duration.ofMillis(latencyMs));
    }

    private static String safe(String value) {
        return (value == null || value.isBlank()) ? TAG_UNKNOWN : value;
    }

    private static String statusFamily(int status) {
        if (status >= 200 && status < 300) return "2xx";
        if (status >= 300 && status < 400) return "3xx";
        if (status >= 400 && status < 500) return "4xx";
        if (status >= 500 && status < 600) return "5xx";
        return TAG_UNKNOWN;
    }
}
