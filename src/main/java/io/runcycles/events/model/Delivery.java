package io.runcycles.events.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Delivery {

    @JsonProperty("delivery_id")
    private String deliveryId;

    @JsonProperty("subscription_id")
    private String subscriptionId;

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("status")
    private DeliveryStatus status;

    @JsonProperty("attempted_at")
    private Instant attemptedAt;

    @JsonProperty("completed_at")
    private Instant completedAt;

    @JsonProperty("attempts")
    private Integer attempts;

    @JsonProperty("response_status")
    private Integer responseStatus;

    @JsonProperty("response_time_ms")
    private Integer responseTimeMs;

    @JsonProperty("error_message")
    private String errorMessage;

    @JsonProperty("next_retry_at")
    private Instant nextRetryAt;

    /**
     * W3C Trace Context trace-id (32 lowercase hex) captured from the
     * originating Event at dispatch time. Populated by the dispatcher
     * even when the upstream admin server hasn't yet stamped it on the
     * delivery record itself, so admin's GET /webhooks/deliveries
     * readback can JOIN with Event / AuditLogEntry on trace_id.
     * Spec: cycles-governance-admin-v0.1.25.yaml info.version 0.1.25.28.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("trace_id")
    private String traceId;

    /**
     * W3C Trace Context trace-flags byte (2 lowercase hex) to use when
     * constructing the outbound traceparent header. Preserves the inbound
     * sampling decision when the originating HTTP request carried a valid
     * traceparent; the dispatcher falls back to "01" (sampled) when this
     * field is absent or {@link #traceparentInboundValid} is not TRUE.
     * Spec: cycles-governance-admin-v0.1.25.yaml info.version 0.1.25.28.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("trace_flags")
    private String traceFlags;

    /**
     * Whether the originating HTTP request presented a valid W3C
     * traceparent. When TRUE, {@link #traceFlags} is used on the outbound
     * delivery; when FALSE or null, the dispatcher defaults trace-flags
     * to "01". Spec: cycles-governance-admin-v0.1.25.yaml info.version
     * 0.1.25.28.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("traceparent_inbound_valid")
    private Boolean traceparentInboundValid;
}
