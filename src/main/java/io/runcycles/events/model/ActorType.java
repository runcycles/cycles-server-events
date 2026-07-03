package io.runcycles.events.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ActorType {
    ADMIN("admin"),
    API_KEY("api_key"),
    SYSTEM("system"),
    SCHEDULER("scheduler");

    private final String value;

    ActorType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Local-vocabulary resolution helper — NOT a Jackson creator.
     * {@code Actor.type} is an open string on the wire (see
     * {@code Event.category} for the rationale: producer-controlled, additive,
     * and re-serialized verbatim into the outbound webhook body). Returns
     * {@code null} for unknown values.
     */
    public static ActorType fromValue(String value) {
        if (value == null) return null;
        for (ActorType t : values()) {
            if (t.value.equals(value)) return t;
        }
        return null;
    }
}
