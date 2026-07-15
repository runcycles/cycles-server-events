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
 * dispatch. When the key is absent, the delivery boundary uses a hardened
 * superset of the historical admin defaults (special-use/private ranges
 * blocked and HTTPS required). Stored values are consumed as authored by the
 * admin plane. Read/parse failures remain indeterminate and are retried.
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
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "172.16.0.0/12",
        "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
        "::1/128", "fe80::/10", "fc00::/7"
    );

    @JsonProperty("allowed_url_patterns")
    private List<String> allowedUrlPatterns;

    @JsonProperty("allow_http")
    @Builder.Default
    private Boolean allowHttp = false;
}
