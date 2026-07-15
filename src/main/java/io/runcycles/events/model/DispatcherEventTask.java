package io.runcycles.events.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Durable outbox record for dispatcher-generated protocol events.
 *
 * <p>The task id is stable for the state transition that created it. The Event
 * id is derived from that task id, making a publish replay idempotent even when
 * Redis persisted the Event but the worker crashed before acknowledging the
 * outbox task.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatcherEventTask(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("event") Event event) {

    public DispatcherEventTask {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("dispatcher event task id is required");
        }
        if (event == null) {
            throw new IllegalArgumentException("dispatcher event is required");
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        // Enforce the replay invariant even for a deserialized or accidentally
        // pre-populated Event; a caller-supplied random id would defeat outbox
        // idempotency after save-before-ack crashes.
        event.setEventId(deterministicEventId(taskId));
        if (event.getTimestamp() == null) {
            event.setTimestamp(createdAt);
        }
    }

    public static DispatcherEventTask create(String taskId, Event event) {
        return new DispatcherEventTask(taskId, Instant.now(), event);
    }

    static String deterministicEventId(String taskId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(taskId.getBytes(StandardCharsets.UTF_8));
            return "evt_" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
