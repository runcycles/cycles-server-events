package io.runcycles.events.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RetryPolicy {

    @JsonProperty("max_retries")
    @Builder.Default
    private Integer maxRetries = 5;

    @JsonProperty("initial_delay_ms")
    @Builder.Default
    private Integer initialDelayMs = 1000;

    @JsonProperty("backoff_multiplier")
    @Builder.Default
    private Double backoffMultiplier = 2.0;

    @JsonProperty("max_delay_ms")
    @Builder.Default
    private Integer maxDelayMs = 60000;
}
