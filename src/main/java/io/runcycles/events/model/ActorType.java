package io.runcycles.events.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum ActorType {
    ADMIN("admin"),
    API_KEY("api_key"),
    SYSTEM("system"),
    SCHEDULER("scheduler");

    private static final Logger LOG = LoggerFactory.getLogger(ActorType.class);

    private final String value;

    ActorType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Tolerant parse: unknown values map to {@code null} instead of throwing.
     * actor.type is producer-controlled and additive (the admin plane already
     * models actor types this enum does not carry); a throwing creator would
     * turn such events into poison-pills on the delivery path. The dispatcher
     * never branches on actor.type, so dropping the value is safe.
     */
    @JsonCreator
    public static ActorType fromValue(String value) {
        if (value == null) return null;
        for (ActorType t : values()) {
            if (t.value.equals(value)) return t;
        }
        LOG.warn("Unknown actor type '{}' — not in local vocabulary; treating as absent",
                value.replace('\r', '_').replace('\n', '_'));
        return null;
    }
}
