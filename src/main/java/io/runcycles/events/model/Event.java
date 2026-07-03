package io.runcycles.events.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Event {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("event_type")
    private String eventType;

    /**
     * OPEN STRING on the wire, like {@code event_type}: categories are additive
     * (spec enum EXTENSIBILITY; v0.1.25.34 added "webhook"), and this service
     * re-serializes the SAME object as the outbound webhook body — a closed enum
     * here either poison-pills unknown values (throwing creator) or corrupts the
     * delivered payload (nulling a required field). Resolve to
     * {@link EventCategory} via {@link EventCategory#fromValue} only for local
     * vocabulary checks; never on the wire path.
     */
    @JsonProperty("category")
    private String category;

    @JsonProperty("timestamp")
    private Instant timestamp;

    @JsonProperty("tenant_id")
    private String tenantId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("scope")
    private String scope;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("actor")
    private Actor actor;

    @JsonProperty("source")
    private String source;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("data")
    private Map<String, Object> data;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("correlation_id")
    private String correlationId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("request_id")
    private String requestId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("trace_id")
    private String traceId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("metadata")
    private Map<String, Object> metadata;
}
