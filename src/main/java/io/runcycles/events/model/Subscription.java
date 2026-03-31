package io.runcycles.events.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Subscription {

    @JsonProperty("subscription_id")
    private String subscriptionId;

    @JsonProperty("tenant_id")
    private String tenantId;

    @JsonProperty("url")
    private String url;

    @JsonProperty("event_types")
    private List<String> eventTypes;

    @JsonProperty("event_categories")
    private List<String> eventCategories;

    @JsonProperty("scope_filter")
    private String scopeFilter;

    @JsonProperty("headers")
    private Map<String, String> headers;

    @JsonProperty("status")
    private String status;

    @JsonProperty("retry_policy")
    private RetryPolicy retryPolicy;

    @JsonProperty("disable_after_failures")
    private Integer disableAfterFailures;

    @JsonProperty("consecutive_failures")
    private Integer consecutiveFailures;

    @JsonProperty("last_triggered_at")
    private Instant lastTriggeredAt;

    @JsonProperty("last_success_at")
    private Instant lastSuccessAt;

    @JsonProperty("last_failure_at")
    private Instant lastFailureAt;
}
