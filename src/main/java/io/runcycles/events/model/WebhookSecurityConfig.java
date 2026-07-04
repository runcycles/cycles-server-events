package io.runcycles.events.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Read-side mirror of the admin plane's webhook security config
 * ({@code PUT /v1/admin/config/webhook-security}, stored at Redis key
 * {@code config:webhook-security}). The dispatcher re-validates the
 * subscription URL against this config at DELIVERY time — admin-side
 * validation happens only at subscription create/update, which leaves
 * DNS-rebinding / config-tightened-after-creation / legacy-subscription
 * gaps if the dispatcher trusts the stored URL blindly.
 *
 * <p>Cross-plane read model: {@code ignoreUnknown = true} (like Event and
 * Delivery) so an admin-side config field added later never poisons
 * dispatch. Defaults mirror the admin model exactly — used when the key is
 * absent or unreadable, and they are the restrictive baseline (private
 * ranges blocked, HTTPS required).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookSecurityConfig {

    @JsonProperty("blocked_cidr_ranges")
    @Builder.Default
    private List<String> blockedCidrRanges = List.of(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        "127.0.0.0/8", "169.254.0.0/16", "::1/128", "fc00::/7"
    );

    @JsonProperty("allowed_url_patterns")
    private List<String> allowedUrlPatterns;

    @JsonProperty("allow_http")
    @Builder.Default
    private Boolean allowHttp = false;
}
