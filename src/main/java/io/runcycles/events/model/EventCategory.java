package io.runcycles.events.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum EventCategory {
    BUDGET("budget"),
    TENANT("tenant"),
    API_KEY("api_key"),
    POLICY("policy"),
    RESERVATION("reservation"),
    SYSTEM("system"),
    WEBHOOK("webhook");

    private final String value;

    EventCategory(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Tenant-accessible categories per governance WEBHOOK SUBSCRIPTION
     * INVARIANT 2 (cycles-governance-admin-v0.1.25): a CONCRETE-tenant-owned
     * subscription may only carry budget / reservation / tenant selectors.
     * The complement (api_key, policy, webhook, system) is admin-only.
     * Mirrors cycles-server-admin's {@code EventCategory.isTenantAccessible()}
     * so the delivery-side boundary stays in lockstep with the admin write
     * path and dispatch gate.
     */
    public boolean isTenantAccessible() {
        return this == BUDGET || this == RESERVATION || this == TENANT;
    }

    /**
     * Local-vocabulary resolution helper — NOT a Jackson creator.
     * {@code Event.category} is an open string on the wire (spec enum
     * EXTENSIBILITY: categories are additive, and the dispatcher re-serializes
     * the same object as the outbound webhook body, so the original value must
     * survive unknown-to-us categories byte-for-byte). Returns {@code null}
     * for unknown values; {@code EventPayloadValidator} turns that into the
     * {@code unknown_category} WARN + metric.
     */
    public static EventCategory fromValue(String value) {
        if (value == null) return null;
        for (EventCategory c : values()) {
            if (c.value.equals(value)) return c;
        }
        return null;
    }
}
