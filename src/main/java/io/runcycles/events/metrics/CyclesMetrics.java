package io.runcycles.events.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralised Micrometer instrumentation for webhook dispatch operations.
 *
 * <p>Mirrors the conventions of cycles-server's {@code CyclesMetrics}
 * (v0.1.25.10): all metric names use the dotted namespace
 * ({@code cycles.webhook.*}) which Micrometer's Prometheus registry rewrites
 * to {@code cycles_webhook_*_total} on scrape. Tag choices prioritise
 * operational signal while keeping cardinality bounded: the only inherently
 * high-card tag is {@code tenant}, toggleable via
 * {@code cycles.metrics.tenant-tag.enabled} (default {@code true}) — same
 * semantics as cycles-server so deployments can flip it consistently across
 * services when the per-tenant series count would stress Prometheus.
 *
 * <p>Null or blank tag values are normalised to the sentinel
 * {@link #TAG_UNKNOWN} ({@code "UNKNOWN"}) to keep series names stable and
 * consistent with cycles-server dashboards.
 *
 * <p><b>Deviation from cycles-server</b>: this class exposes a Timer
 * ({@link #DELIVERY_LATENCY}) for outbound webhook latency. cycles-server
 * does not — it relies on Spring's auto-emitted
 * {@code http.server.requests} for inbound latency. This service is the
 * HTTP <em>client</em>, so {@code http.server.requests} does not cover its
 * primary I/O surface; an explicit Timer is necessary.
 */
@Component
public class CyclesMetrics {

    // Dotted source names (Prometheus scrape rewrites to cycles_webhook_*_total).
    public static final String DELIVERY_ATTEMPTS = "cycles.webhook.delivery.attempts";
    public static final String DELIVERY_SUCCESS = "cycles.webhook.delivery.success";
    public static final String DELIVERY_FAILED = "cycles.webhook.delivery.failed";
    public static final String DELIVERY_RETRIED = "cycles.webhook.delivery.retried";
    public static final String DELIVERY_STALE = "cycles.webhook.delivery.stale";
    public static final String SUBSCRIPTION_AUTO_DISABLED = "cycles.webhook.subscription.auto_disabled";
    public static final String DELIVERY_LATENCY = "cycles.webhook.delivery.latency";

    /**
     * Parallel to cycles-server-admin's {@code cycles_admin_events_payload_invalid_total}
     * (v0.1.25.12, commit bc9f075) — same intent, scoped to the webhook-delivery
     * plane. Emitted by {@code EventPayloadValidator}.
     */
    public static final String EVENTS_PAYLOAD_INVALID = "cycles.webhook.events.payload.invalid";

    public static final String TAG_UNKNOWN = "UNKNOWN";
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    private final MeterRegistry registry;
    private final boolean tenantTagEnabled;

    public CyclesMetrics(MeterRegistry registry,
                         @Value("${cycles.metrics.tenant-tag.enabled:true}") boolean tenantTagEnabled) {
        this.registry = registry;
        this.tenantTagEnabled = tenantTagEnabled;
    }

    // ---- Delivery lifecycle ----

    public void recordDeliveryAttempt(String tenant, String eventType) {
        registry.counter(DELIVERY_ATTEMPTS,
                tags(tenant, "event_type", eventType))
                .increment();
    }

    public void recordDeliverySuccess(String tenant, String eventType, int statusCode, long latencyMs) {
        registry.counter(DELIVERY_SUCCESS,
                tags(tenant,
                        "event_type", eventType,
                        "status_code_family", statusFamily(statusCode)))
                .increment();
        recordLatency(tenant, eventType, OUTCOME_SUCCESS, latencyMs);
    }

    public void recordDeliveryFailure(String tenant, String eventType, String reason, long latencyMs) {
        registry.counter(DELIVERY_FAILED,
                tags(tenant,
                        "event_type", eventType,
                        "reason", reason))
                .increment();
        if (latencyMs > 0) {
            // Only record latency when a transport round-trip actually occurred;
            // upstream failures (event_not_found, etc.) have no meaningful latency.
            recordLatency(tenant, eventType, OUTCOME_FAILURE, latencyMs);
        }
    }

    public void recordDeliveryRetried(String tenant, String eventType) {
        registry.counter(DELIVERY_RETRIED,
                tags(tenant, "event_type", eventType))
                .increment();
    }

    public void recordDeliveryStale(String tenant) {
        registry.counter(DELIVERY_STALE,
                tags(tenant))
                .increment();
    }

    public void recordSubscriptionAutoDisabled(String tenant, String reason) {
        registry.counter(SUBSCRIPTION_AUTO_DISABLED,
                tags(tenant, "reason", reason))
                .increment();
    }

    // ---- Event payload validation ----

    /**
     * Emitted by {@code EventPayloadValidator} for each non-fatal shape
     * discrepancy found on an ingested event. Tag schema intentionally
     * parallels cycles-server-admin's {@code cycles_admin_events_payload_invalid_total}:
     * admin uses {@code type} + {@code expected_class} (its Jackson round-trip
     * yields a target class), we use {@code type} + {@code rule} (our
     * rule-based validator yields a rule name).
     */
    public void recordEventsPayloadInvalid(String type, String rule) {
        // No tenant dimension on this counter — the discrepancy is about the
        // event payload shape, not tenant-specific traffic.
        registry.counter(EVENTS_PAYLOAD_INVALID,
                Tags.of("type", normalise(type), "rule", normalise(rule)))
                .increment();
    }

    // ---- Internals ----

    private void recordLatency(String tenant, String eventType, String outcome, long latencyMs) {
        if (latencyMs < 0) return;
        Timer timer = Timer.builder(DELIVERY_LATENCY)
                .tags(tags(tenant, "event_type", eventType, "outcome", outcome))
                .register(registry);
        timer.record(Duration.ofMillis(latencyMs));
    }

    /**
     * Builds the tag list with normalisation + conditional tenant inclusion.
     * Mirrors cycles-server's {@code CyclesMetrics#tags(String, String...)}.
     */
    private Tags tags(String tenant, String... kvs) {
        List<Tag> list = new ArrayList<>((kvs.length / 2) + 1);
        if (tenantTagEnabled) {
            list.add(Tag.of("tenant", normalise(tenant)));
        }
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            list.add(Tag.of(kvs[i], normalise(kvs[i + 1])));
        }
        return Tags.of(list);
    }

    private static String normalise(String s) {
        return (s == null || s.isBlank()) ? TAG_UNKNOWN : s;
    }

    private static String statusFamily(int status) {
        if (status >= 200 && status < 300) return "2xx";
        if (status >= 300 && status < 400) return "3xx";
        if (status >= 400 && status < 500) return "4xx";
        if (status >= 500 && status < 600) return "5xx";
        return TAG_UNKNOWN;
    }
}
