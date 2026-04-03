package io.runcycles.events.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
}
