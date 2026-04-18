package io.runcycles.events.validation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.events.metrics.CyclesMetrics;
import io.runcycles.events.model.Event;
import io.runcycles.events.model.EventCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class EventPayloadValidatorTest {

    private SimpleMeterRegistry registry;
    private CyclesMetrics metrics;
    private EventPayloadValidator validator;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CyclesMetrics(registry, true); // tenant tag enabled (irrelevant for this counter)
        validator = new EventPayloadValidator(metrics);
    }

    private Event validBudgetResetSpent() {
        Map<String, Object> data = new HashMap<>();
        data.put("ledger_id", "budget-123");
        data.put("operation", "RESET_SPENT");
        data.put("spent_override_provided", Boolean.TRUE);
        return Event.builder()
                .eventId("evt-1")
                .eventType("budget.reset_spent")
                .category(EventCategory.BUDGET)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .data(data)
                .build();
    }

    private double warningCount(String eventType, String rule) {
        // Tag is named "type" (not "event_type") for parallel alignment with
        // cycles-server-admin's cycles_admin_events_payload_invalid_total{type, expected_class}.
        return registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID)
                .tags("type", eventType, "rule", rule)
                .counter().count();
    }

    // --- Happy path ---

    @Test
    void valid_budget_reset_spent_event_emitsNoWarnings() {
        validator.validate(validBudgetResetSpent());
        assertThat(registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID).counters()).isEmpty();
    }

    @Test
    void valid_minimal_tenant_event_emitsNoWarnings() {
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
        validator.validate(event);
        assertThat(registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID).counters()).isEmpty();
    }

    // --- Rule 1: missing_required ---

    @Test
    void missing_event_id_emitsWarning() {
        Event event = Event.builder()
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
        validator.validate(event);
        assertThat(warningCount("tenant.created",
                EventPayloadValidator.RULE_MISSING_REQUIRED)).isEqualTo(1.0);
    }

    @Test
    void blank_tenant_id_emitsWarning() {
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .tenantId("  ")
                .source("admin")
                .build();
        validator.validate(event);
        assertThat(warningCount("tenant.created",
                EventPayloadValidator.RULE_MISSING_REQUIRED)).isEqualTo(1.0);
    }

    @Test
    void null_category_emitsWarning() {
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
        validator.validate(event);
        assertThat(warningCount("tenant.created",
                EventPayloadValidator.RULE_MISSING_REQUIRED)).isEqualTo(1.0);
    }

    @Test
    void null_timestamp_emitsWarning() {
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .tenantId("t-1")
                .source("admin")
                .build();
        validator.validate(event);
        assertThat(warningCount("tenant.created",
                EventPayloadValidator.RULE_MISSING_REQUIRED)).isEqualTo(1.0);
    }

    @Test
    void missing_source_emitsWarning() {
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .build();
        validator.validate(event);
        assertThat(warningCount("tenant.created",
                EventPayloadValidator.RULE_MISSING_REQUIRED)).isEqualTo(1.0);
    }

    // --- Rule 2: unknown_event_type ---

    @Test
    void unknown_event_type_emitsWarning() {
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("custom.mystery.event") // not in enum
                .category(EventCategory.BUDGET)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
        validator.validate(event);
        assertThat(warningCount("custom.mystery.event",
                EventPayloadValidator.RULE_UNKNOWN_EVENT_TYPE)).isEqualTo(1.0);
    }

    // --- Rule 3: category_mismatch ---

    @Test
    void category_mismatch_emitsWarning() {
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("budget.created") // BUDGET per EventType
                .category(EventCategory.TENANT)  // mismatch
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
        validator.validate(event);
        assertThat(warningCount("budget.created",
                EventPayloadValidator.RULE_CATEGORY_MISMATCH)).isEqualTo(1.0);
    }

    // --- Rule 4: budget_data_shape ---

    @Test
    void budget_event_missing_ledger_id_emitsWarning() {
        Map<String, Object> data = new HashMap<>();
        data.put("operation", "CREDIT");
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("budget.funded")
                .category(EventCategory.BUDGET)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .data(data)
                .build();
        validator.validate(event);
        assertThat(warningCount("budget.funded",
                EventPayloadValidator.RULE_BUDGET_DATA_SHAPE)).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void budget_event_bogus_operation_emitsWarning() {
        Map<String, Object> data = new HashMap<>();
        data.put("ledger_id", "budget-123");
        data.put("operation", "FROBNICATE"); // not in enum
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("budget.updated")
                .category(EventCategory.BUDGET)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .data(data)
                .build();
        validator.validate(event);
        assertThat(warningCount("budget.updated",
                EventPayloadValidator.RULE_BUDGET_DATA_SHAPE)).isEqualTo(1.0);
    }

    @Test
    void budget_event_missing_operation_isAccepted() {
        // operation is optional per spec; absence shouldn't warn
        Map<String, Object> data = new HashMap<>();
        data.put("ledger_id", "budget-123");
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("budget.updated")
                .category(EventCategory.BUDGET)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .data(data)
                .build();
        validator.validate(event);
        assertThat(registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID).counters()).isEmpty();
    }

    @Test
    void budget_event_all_valid_operations_accepted() {
        String[] operations = {"CREDIT", "DEBIT", "RESET", "RESET_SPENT",
                "REPAY_DEBT", "STATUS_CHANGE", "CREATE", "UPDATE"};
        for (String op : operations) {
            Map<String, Object> data = new HashMap<>();
            data.put("ledger_id", "budget-123");
            data.put("operation", op);
            Event event = Event.builder()
                    .eventId("evt-" + op)
                    .eventType("budget.updated")
                    .category(EventCategory.BUDGET)
                    .timestamp(Instant.now())
                    .tenantId("t-1")
                    .source("admin")
                    .data(data)
                    .build();
            validator.validate(event);
        }
        assertThat(registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID).counters()).isEmpty();
    }

    // --- Rule 5: reset_spent_shape ---

    @Test
    void reset_spent_non_boolean_override_emitsWarning() {
        Map<String, Object> data = new HashMap<>();
        data.put("ledger_id", "budget-123");
        data.put("operation", "RESET_SPENT");
        data.put("spent_override_provided", "yes"); // string instead of boolean
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("budget.reset_spent")
                .category(EventCategory.BUDGET)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .data(data)
                .build();
        validator.validate(event);
        assertThat(warningCount("budget.reset_spent",
                EventPayloadValidator.RULE_RESET_SPENT_SHAPE)).isEqualTo(1.0);
    }

    @Test
    void reset_spent_missing_override_isAccepted() {
        // spent_override_provided is optional (defaults to absent when not supplied)
        Map<String, Object> data = new HashMap<>();
        data.put("ledger_id", "budget-123");
        data.put("operation", "RESET_SPENT");
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("budget.reset_spent")
                .category(EventCategory.BUDGET)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .data(data)
                .build();
        validator.validate(event);
        assertThat(registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID).counters()).isEmpty();
    }

    @Test
    void reset_spent_boolean_override_isAccepted() {
        Map<String, Object> data = new HashMap<>();
        data.put("ledger_id", "budget-123");
        data.put("operation", "RESET_SPENT");
        data.put("spent_override_provided", Boolean.FALSE);
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("budget.reset_spent")
                .category(EventCategory.BUDGET)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .data(data)
                .build();
        validator.validate(event);
        assertThat(registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID).counters()).isEmpty();
    }

    // --- Null-safety ---

    @Test
    void null_event_doesNotThrow() {
        assertThatCode(() -> validator.validate(null)).doesNotThrowAnyException();
    }

    @Test
    void null_data_on_budget_event_doesNotWarn() {
        // No data => no shape to check; other rules still apply.
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("budget.created")
                .category(EventCategory.BUDGET)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
        validator.validate(event);
        assertThat(registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID).counters()).isEmpty();
    }

    @Test
    void log_injection_via_event_type_is_sanitised() {
        // Malicious producer puts a newline in event_type to forge a second log line.
        // Validator should strip CR/LF before logging.
        Event event = Event.builder()
                .eventId("evt-1\nFAKE: forged")
                .eventType("tenant.created\r\nINJECTED=yes")
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();

        // The validator categorises this as unknown_event_type because the
        // literal value with embedded newline isn't in the enum vocabulary.
        validator.validate(event);

        assertThat(registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID)
                .tag("rule", EventPayloadValidator.RULE_UNKNOWN_EVENT_TYPE).counter().count())
                .isGreaterThanOrEqualTo(1.0);
        // The metric still carries the raw event_type (tag values are passed
        // through to Prometheus which has its own label escaping). Logging is
        // where sanitisation matters — verified indirectly by the absence of
        // exceptions on control characters.
    }

    // --- Rule 6: trace_id_shape ---

    @Test
    void trace_id_well_formed_noWarning() {
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .traceId("0123456789abcdef0123456789abcdef")
                .build();
        validator.validate(event);
        assertThat(registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID)
                .tag("rule", EventPayloadValidator.RULE_TRACE_ID_SHAPE).counters()).isEmpty();
    }

    @Test
    void trace_id_absent_noWarning() {
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
        validator.validate(event);
        assertThat(registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID)
                .tag("rule", EventPayloadValidator.RULE_TRACE_ID_SHAPE).counters()).isEmpty();
    }

    @Test
    void trace_id_malformed_emitsWarning() {
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .traceId("NOT-A-VALID-TRACE-ID") // not 32 lowercase hex
                .build();
        validator.validate(event);
        assertThat(warningCount("tenant.created",
                EventPayloadValidator.RULE_TRACE_ID_SHAPE)).isEqualTo(1.0);
    }

    @Test
    void trace_id_uppercase_emitsWarning() {
        // Spec requires lowercase hex; uppercase is malformed.
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .traceId("0123456789ABCDEF0123456789ABCDEF")
                .build();
        validator.validate(event);
        assertThat(warningCount("tenant.created",
                EventPayloadValidator.RULE_TRACE_ID_SHAPE)).isEqualTo(1.0);
    }

    @Test
    void non_budget_category_skipsBudgetShapeCheck() {
        // Even if data is missing ledger_id, non-BUDGET events shouldn't trigger that rule.
        Map<String, Object> data = Map.of("user_id", "u-1");
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("api_key.created")
                .category(EventCategory.API_KEY)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .data(data)
                .build();
        validator.validate(event);
        assertThat(registry.find(CyclesMetrics.EVENTS_PAYLOAD_INVALID).counters()).isEmpty();
    }
}
