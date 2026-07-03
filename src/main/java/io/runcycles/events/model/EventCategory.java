package io.runcycles.events.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum EventCategory {
    BUDGET("budget"),
    TENANT("tenant"),
    API_KEY("api_key"),
    POLICY("policy"),
    RESERVATION("reservation"),
    SYSTEM("system"),
    WEBHOOK("webhook");

    private static final Logger LOG = LoggerFactory.getLogger(EventCategory.class);

    private final String value;

    EventCategory(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Tolerant parse: unknown values map to {@code null} (field treated as
     * absent) instead of throwing. The spec's enum EXTENSIBILITY rule makes
     * this a consumer MUST — new categories are additive (v0.1.25.34 added
     * "webhook"), and a throwing creator turns every event carrying a newer
     * category into a poison-pill that fails the delivery and counts against
     * the subscription's consecutive-failure budget. This dispatcher never
     * branches on category, so dropping the value is safe.
     */
    @JsonCreator
    public static EventCategory fromValue(String value) {
        if (value == null) return null;
        for (EventCategory c : values()) {
            if (c.value.equals(value)) return c;
        }
        LOG.warn("Unknown event category '{}' — not in local vocabulary; treating as absent",
                value.replace('\r', '_').replace('\n', '_'));
        return null;
    }
}
