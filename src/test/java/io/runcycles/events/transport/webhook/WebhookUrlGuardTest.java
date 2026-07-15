package io.runcycles.events.transport.webhook;

import io.runcycles.events.model.WebhookSecurityConfig;
import io.runcycles.events.repository.WebhookSecurityConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookUrlGuardTest {

    @Mock private WebhookSecurityConfigRepository configRepository;

    private WebhookUrlGuard guard;

    @BeforeEach
    void setUp() {
        guard = new WebhookUrlGuard(configRepository);
        // Restrictive defaults (private ranges blocked, HTTPS required) unless
        // a test overrides — mirrors the absent-config-key posture.
        lenient().when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder().build());
    }

    private WebhookSecurityConfig permissive() {
        return WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(List.of())
                .build();
    }

    // --- scheme rules ---

    @Test
    void nullOrBlankUrl_blocked() {
        assertThat(guard.check(null)).isEqualTo("URL is required");
        assertThat(guard.check("  ")).isEqualTo("URL is required");
    }

    @Test
    void httpBlocked_whenAllowHttpFalse() {
        assertThat(guard.check("http://example.com/hook")).isEqualTo("HTTPS required");
    }

    @Test
    void httpAllowed_whenAllowHttpTrue() {
        when(configRepository.get()).thenReturn(permissive());
        assertThat(guard.check("http://example.com/hook")).isNull();
    }

    @Test
    void schemeMatchingIsCaseInsensitivePerUriRules() {
        when(configRepository.get()).thenReturn(permissive());
        assertThat(guard.check("HTTPS://203.0.113.10/hook")).isNull();
    }

    @Test
    void nonHttpScheme_blocked_evenWithAllowHttp() {
        when(configRepository.get()).thenReturn(permissive());
        assertThat(guard.check("ftp://example.com/hook")).isEqualTo("Only HTTP(S) URLs are allowed");
        assertThat(guard.check("file:///etc/passwd")).isEqualTo("Only HTTP(S) URLs are allowed");
    }

    @Test
    void missingHost_blocked() {
        when(configRepository.get()).thenReturn(permissive());
        assertThat(guard.check("https:///path-only")).isEqualTo("No host in URL");
    }

    @Test
    void malformedUrl_blocked() {
        assertThat(guard.check("https://exa mple.com/")).isEqualTo("Malformed URL");
    }

    // --- blocked CIDR ranges (default set) ---

    @Test
    void loopbackIpv4_blockedByDefaults() {
        String v = guard.check("https://127.0.0.1/hook");
        assertThat(v).startsWith("Resolves to blocked IP:");
    }

    @Test
    void localhostHostname_blockedByDefaults() {
        String v = guard.check("https://localhost/hook");
        assertThat(v).startsWith("Resolves to blocked IP:");
    }

    @Test
    void privateRanges_blockedByDefaults() {
        assertThat(guard.check("https://10.1.2.3/hook")).startsWith("Resolves to blocked IP:");
        assertThat(guard.check("https://192.168.1.1/hook")).startsWith("Resolves to blocked IP:");
        assertThat(guard.check("https://172.16.0.9/hook")).startsWith("Resolves to blocked IP:");
        assertThat(guard.check("https://169.254.169.254/latest/meta-data")).startsWith("Resolves to blocked IP:");
    }

    @Test
    void ipv6Loopback_blockedByDefaults() {
        assertThat(guard.check("https://[::1]/hook")).startsWith("Resolves to blocked IP:");
    }

    @Test
    void ipv4MappedIpv6_blockedAgainstIpv4Range() {
        assertThat(guard.check("https://[::ffff:10.0.0.1]/hook")).startsWith("Resolves to blocked IP:");
    }

    @Test
    void publicIp_passesDefaults() {
        // TEST-NET-3 (RFC 5737) — a literal public-range IP, no DNS involved.
        assertThat(guard.check("https://203.0.113.10/hook")).isNull();
    }

    @Test
    void emptyBlockedRanges_skipsResolution() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true).blockedCidrRanges(List.of()).build());
        // Would be unresolvable + loopback under defaults; both checks skipped.
        assertThat(guard.check("https://127.0.0.1/hook")).isNull();
    }

    @Test
    void nullBlockedRangesSkipResolution() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true).blockedCidrRanges(null).build());
        assertThat(guard.check("https://127.0.0.1/hook")).isNull();
    }

    @Test
    void unresolvableHost_blockedWhenRangesConfigured() {
        String v = guard.check("https://definitely-not-a-real-host.invalid/hook");
        assertThat(v).startsWith("Cannot resolve hostname:");
    }

    @Test
    void invalidCidrEntries_failClosedAsIndeterminatePolicy() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(List.of("not-a-cidr", "10.0.0.0/99", "127.0.0.0/8"))
                .build());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> guard.check("https://127.0.0.1/hook"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid CIDR");
    }

    @Test
    void cidrWithIgnoredTrailingSegmentIsRejected() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(List.of("10.0.0.0/8/ignored"))
                .build());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> guard.check("https://203.0.113.10/hook"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid CIDR");
    }

    @Test
    void hostnameIsRejectedWhereCidrRequiresAddressLiteral() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(List.of("localhost/8"))
                .build());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> guard.check("https://203.0.113.10/hook"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid CIDR");
    }

    @Test
    void blankCidrEntryIsRejectedAsIndeterminatePolicy() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(java.util.Arrays.asList("127.0.0.0/8", null))
                .build());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> guard.check("https://203.0.113.10/hook"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank entry");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "10.0.0.0/not-a-prefix",
            "10.0.0.0/999999999999999999999",
            "10.0.0/8",
            "10.0.x.1/8",
            "10.0.0.256/8",
            "fe80::1%eth0/64"
    })
    void malformedAddressLiteralOrPrefixIsRejected(String cidr) {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(List.of(cidr))
                .build());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> guard.check("https://203.0.113.10/hook"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid CIDR");
    }

    // --- allowed URL patterns ---

    @Test
    void allowedPatterns_gateUrls() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(List.of())
                .allowedUrlPatterns(List.of("https://*.example.com/*"))
                .build());
        assertThat(guard.check("https://hooks.example.com/receive")).isNull();
        assertThat(guard.check("https://evil.com/receive"))
                .isEqualTo("URL does not match any allowed pattern");
    }

    @Test
    void globEscapesRegexMetacharacters() {
        assertThat(guard.matchesGlob("https://a.example.com/x", "https://*.example.com/*")).isTrue();
        // '.' must be literal — "exampleXcom" must not match
        assertThat(guard.matchesGlob("https://a.exampleXcom/x", "https://*.example.com/*")).isFalse();
        // regex metacharacters in the pattern are inert
        assertThat(guard.matchesGlob("https://e.com/a+b", "https://e.com/a+b")).isTrue();
    }
}
