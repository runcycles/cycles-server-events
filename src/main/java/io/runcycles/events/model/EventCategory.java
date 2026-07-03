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
