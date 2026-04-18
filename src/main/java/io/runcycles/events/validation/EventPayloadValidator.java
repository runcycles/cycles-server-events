package io.runcycles.events.validation;

import io.runcycles.events.metrics.CyclesMetrics;
import io.runcycles.events.model.Event;
import io.runcycles.events.model.EventCategory;
import io.runcycles.events.model.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Non-fatal event-payload shape validator.
 *
 * <p>Mirrors the warn+metric pattern introduced in cycles-server-admin
 * v0.1.25.12 (commit bc9f075, {@code EventService.validatePayloadShape}):
 * this validator <strong>never throws</strong>, <strong>never drops</strong>
 * an event, and <strong>never blocks delivery</strong>. It emits a WARN log
 * line and increments {@code cycles_webhook_events_payload_invalid_total}
 * for each shape discrepancy found, so operators can observe drift between
 * the producer (admin/runtime) and this dispatcher without destabilising
 * the at-least-once delivery contract.
 *
 * <p><b>Approach difference vs admin</b>: admin uses Jackson
 * {@code ObjectMapper.convertValue(data, expected_class)} round-tripping
 * against its {@code EventPayloadTypeMapping} registry of typed
 * {@code EventDataBudgetLifecycle}/{@code EventDataTenantLifecycle}/etc.
 * DTOs — which live in the admin-service-model module that this repo does
 * not depend on. To avoid cross-module coupling, this validator applies
 * hand-rolled rules keyed on the spec fields we care about in the
 * webhook-dispatch plane. Tag schema on the metric
 * ({@code type}, {@code rule}) intentionally parallels admin's
 * ({@code type}, {@code expected_class}) so dashboards can pivot between
 * the two services.
 *
 * <p>Validation rules (all informational):
 * <ol>
 *   <li><b>missing_required</b>: any of event_id, event_type, category,
 *       timestamp, tenant_id, source is null or blank
 *       (spec: cycles-governance-admin-v0.1.25.yaml line 1897).</li>
 *   <li><b>unknown_event_type</b>: event_type string doesn't map to any
 *       {@link EventType} enum value.</li>
 *   <li><b>category_mismatch</b>: event_type resolves to an EventType whose
 *       declared category differs from the event's category field.</li>
 *   <li><b>budget_data_shape</b>: BUDGET-category event missing ledger_id in
 *       data, or supplying an operation value outside the spec enum set
 *       (CREDIT, DEBIT, RESET, RESET_SPENT, REPAY_DEBT, STATUS_CHANGE,
 *       CREATE, UPDATE).</li>
 *   <li><b>reset_spent_shape</b>: budget.reset_spent event has a non-boolean
 *       spent_override_provided field.</li>
 *   <li><b>trace_id_shape</b>: trace_id is present but does not match the
 *       W3C Trace Context pattern {@code ^[0-9a-f]{32}$}
 *       (spec: cycles-governance-admin-v0.1.25.yaml line 1546,
 *       landed in spec v0.1.25.27).</li>
 * </ol>
 */
@Component
public class EventPayloadValidator {

    private static final Logger LOG = LoggerFactory.getLogger(EventPayloadValidator.class);

    static final String RULE_MISSING_REQUIRED = "missing_required";
    static final String RULE_UNKNOWN_EVENT_TYPE = "unknown_event_type";
    static final String RULE_CATEGORY_MISMATCH = "category_mismatch";
    static final String RULE_BUDGET_DATA_SHAPE = "budget_data_shape";
    static final String RULE_RESET_SPENT_SHAPE = "reset_spent_shape";
    static final String RULE_TRACE_ID_SHAPE = "trace_id_shape";

    /** Per spec EventDataBudgetLifecycle.operation enum (yaml line 1981). */
    private static final Set<String> VALID_BUDGET_OPERATIONS = Set.of(
            "CREDIT", "DEBIT", "RESET", "RESET_SPENT", "REPAY_DEBT",
            "STATUS_CHANGE", "CREATE", "UPDATE");

    /** Per spec Event.trace_id pattern (yaml line 1546). */
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    private final CyclesMetrics metrics;

    public EventPayloadValidator(CyclesMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Validate the event in place. Returns silently; emits WARN + metric for
     * each rule violation. Does not mutate the event.
     */
    public void validate(Event event) {
        if (event == null) return;

        String eventType = event.getEventType();
        String eventIdForLog = event.getEventId() == null ? "<no-id>" : event.getEventId();

        // Rule 1: required fields
        if (isBlank(event.getEventId())
                || isBlank(event.getEventType())
                || event.getCategory() == null
                || event.getTimestamp() == null
                || isBlank(event.getTenantId())
                || isBlank(event.getSource())) {
            warn(eventIdForLog, eventType, RULE_MISSING_REQUIRED,
                    "one or more required top-level fields missing");
        }

        // Rule 2 + 3: event_type resolution + category consistency
        EventType resolved = null;
        if (!isBlank(eventType)) {
            try {
                resolved = EventType.fromValue(eventType);
            } catch (IllegalArgumentException e) {
                warn(eventIdForLog, eventType, RULE_UNKNOWN_EVENT_TYPE,
                        "event_type not in local vocabulary");
            }
        }
        if (resolved != null && event.getCategory() != null
                && resolved.getCategory() != event.getCategory()) {
            warn(eventIdForLog, eventType, RULE_CATEGORY_MISMATCH,
                    "category " + event.getCategory() + " does not match "
                            + resolved + " (expected " + resolved.getCategory() + ")");
        }

        // Rule 6: trace_id format (optional field; only warn if present and malformed)
        String traceId = event.getTraceId();
        if (traceId != null && !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            warn(eventIdForLog, eventType, RULE_TRACE_ID_SHAPE,
                    "trace_id does not match ^[0-9a-f]{32}$");
        }

        // Rule 4 + 5: BUDGET-category data shape
        if (resolved != null && resolved.getCategory() == EventCategory.BUDGET) {
            Map<String, Object> data = event.getData();
            if (data != null) {
                if (!(data.get("ledger_id") instanceof String s) || s.isBlank()) {
                    warn(eventIdForLog, eventType, RULE_BUDGET_DATA_SHAPE,
                            "budget event data missing ledger_id");
                }
                Object op = data.get("operation");
                if (op != null && !(op instanceof String opStr
                        && VALID_BUDGET_OPERATIONS.contains(opStr))) {
                    warn(eventIdForLog, eventType, RULE_BUDGET_DATA_SHAPE,
                            "budget event operation '" + op + "' not in spec enum set");
                }
                if (resolved == EventType.BUDGET_RESET_SPENT) {
                    Object flag = data.get("spent_override_provided");
                    if (flag != null && !(flag instanceof Boolean)) {
                        warn(eventIdForLog, eventType, RULE_RESET_SPENT_SHAPE,
                                "spent_override_provided must be boolean, got "
                                        + flag.getClass().getSimpleName());
                    }
                }
            }
        }
    }

    private void warn(String eventId, String eventType, String rule, String detail) {
        // Sanitise producer-supplied strings before logging: strip CR/LF so a
        // malicious or malformed event_type/event_id can't inject fake log
        // lines. Same mitigation cycles-server-admin applied in v0.1.25.8
        // (commit ad77546, "log injection + comments").
        LOG.warn("Event payload validation warning: event_id={} event_type={} rule={} detail={}",
                stripCrLf(eventId), stripCrLf(eventType), rule, detail);
        metrics.recordEventsPayloadInvalid(eventType, rule);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String stripCrLf(String s) {
        if (s == null) return null;
        return s.replace('\r', '_').replace('\n', '_');
    }
}
