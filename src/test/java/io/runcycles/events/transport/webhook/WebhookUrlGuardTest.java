package io.runcycles.events.transport.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.runcycles.events.model.WebhookSecurityConfig;
import io.runcycles.events.repository.WebhookSecurityConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

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
        guard = new WebhookUrlGuard(configRepository, false);
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
    void loopbackIpv4_blockedByBaselineWhenAdminRangesAreEmpty() {
        when(configRepository.get()).thenReturn(permissive());
        String v = guard.check("https://127.0.0.1/hook");
        assertThat(v).startsWith("Resolves to blocked IP:");
    }

    @Test
    void localhostHostname_blockedByDefaults() {
        String v = guard.check("https://localhost/hook");
        assertThat(v).startsWith("Resolves to blocked IP:");
    }

    @Test
    void everyBaselineRangeIsBlockedWhenAdminRangesAreEmpty() {
        when(configRepository.get()).thenReturn(permissive());
        assertThat(guard.check("https://0.0.0.1/hook")).startsWith("Resolves to blocked IP:");
        assertThat(guard.check("https://10.1.2.3/hook")).startsWith("Resolves to blocked IP:");
        assertThat(guard.check("https://100.64.0.1/hook")).startsWith("Resolves to blocked IP:");
        assertThat(guard.check("https://192.168.1.1/hook")).startsWith("Resolves to blocked IP:");
        assertThat(guard.check("https://172.16.0.9/hook")).startsWith("Resolves to blocked IP:");
        assertThat(guard.check("https://169.254.169.254/latest/meta-data")).startsWith("Resolves to blocked IP:");
        assertThat(guard.check("https://[fe80::1]/hook")).startsWith("Resolves to blocked IP:");
        assertThat(guard.check("https://[fd00::1]/hook")).startsWith("Resolves to blocked IP:");
    }

    @Test
    void ipv6LoopbackAndUnspecifiedBlockedByBaseline() {
        when(configRepository.get()).thenReturn(permissive());
        assertThat(guard.check("https://[::]/hook")).startsWith("Resolves to unspecified IP:");
        assertThat(guard.check("https://[::1]/hook")).startsWith("Resolves to blocked IP:");
    }

    @Test
    void unspecifiedIpv6IsAlwaysBlockedEvenWhenConfiguredRangesAreEmpty() {
        when(configRepository.get()).thenReturn(permissive());
        assertThat(guard.check("https://[::]/hook")).startsWith("Resolves to unspecified IP:");
    }

    @Test
    void decimalEncodedIpv4LoopbackIsBlocked() {
        when(configRepository.get()).thenReturn(permissive());
        assertThat(guard.check("https://2130706433/hook")).startsWith("Resolves to blocked IP:");
    }

    @Test
    void ipv4MappedIpv6_blockedAgainstIpv4Range() {
        when(configRepository.get()).thenReturn(permissive());
        assertThat(guard.check("https://[::ffff:10.0.0.1]/hook")).startsWith("Resolves to blocked IP:");
    }

    @Test
    void publicIp_passesDefaults() {
        when(configRepository.get()).thenReturn(permissive());
        // TEST-NET-3 (RFC 5737) — a literal public-range IP, no DNS involved.
        assertThat(guard.check("https://203.0.113.10/hook")).isNull();
    }

    @Test
    void emptyAdminRanges_doNotDisableBaseline() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true).blockedCidrRanges(List.of()).build());
        assertThat(guard.check("https://127.0.0.1/hook")).startsWith("Resolves to blocked IP:");
    }

    @Test
    void nullAdminRanges_doNotDisableBaseline() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true).blockedCidrRanges(null).build());
        assertThat(guard.check("https://127.0.0.1/hook")).startsWith("Resolves to blocked IP:");
    }

    @Test
    void configuredAdminRangesAreAdditiveToBaseline() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(List.of("203.0.113.0/24"))
                .build());

        assertThat(guard.check("https://203.0.113.10/hook")).startsWith("Resolves to blocked IP:");
        assertThat(guard.check("https://127.0.0.1/hook")).startsWith("Resolves to blocked IP:");
    }

    @Test
    void allowPrivateNetworksDisablesOnlyBaselineKeepsUnspecifiedCheckAndWarns() {
        Logger logger = (Logger) LoggerFactory.getLogger(WebhookUrlGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            guard = new WebhookUrlGuard(configRepository, true);
            when(configRepository.get()).thenReturn(permissive());

            assertThat(guard.check("https://127.0.0.1/hook")).isNull();
            assertThat(guard.check("https://169.254.169.254/latest/meta-data")).isNull();
            assertThat(guard.check("https://[::1]/hook")).isNull();
            assertThat(guard.check("https://[::]/hook")).startsWith("Resolves to unspecified IP:");
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("baseline private-network denylist is DISABLED");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void allowPrivateNetworksDoesNotOverrideAdminRanges() {
        guard = new WebhookUrlGuard(configRepository, true);
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(List.of("127.0.0.0/8"))
                .build());

        assertThat(guard.check("https://127.0.0.1/hook")).startsWith("Resolves to blocked IP:");
    }

    @Test
    void allowPrivateNetworksWithNoAdminRangesPreservesUnresolvableHostBehavior() {
        guard = new WebhookUrlGuard(configRepository, true);
        when(configRepository.get()).thenReturn(permissive());

        assertThat(guard.check("https://definitely-not-a-real-host.invalid/hook")).isNull();
    }

    @Test
    void unresolvableHost_blockedWhenRangesConfigured() {
        when(configRepository.get()).thenReturn(permissive());
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

        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(List.of(" "))
                .build());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> guard.check("https://203.0.113.10/hook"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank entry");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "10.0.0.0/not-a-prefix",
            "10.0.0.0/33",
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

    @Test
    void cidrParserCoversStrictStructuralBoundaries() {
        assertThat(WebhookUrlGuard.CidrRange.parse(null)).isNull();
        assertThat(WebhookUrlGuard.CidrRange.parse("")).isNull();
        assertThat(WebhookUrlGuard.CidrRange.parse("/8")).isNull();
        assertThat(WebhookUrlGuard.CidrRange.parse("10.0.0.1/")).isNull();
        assertThat(WebhookUrlGuard.CidrRange.parse("10.0.0.1/8/ignored")).isNull();
        assertThat(WebhookUrlGuard.CidrRange.parse("fe80::zz/64")).isNull();
        assertThat(WebhookUrlGuard.CidrRange.parse("10..0.1/8")).isNull();
        assertThat(WebhookUrlGuard.CidrRange.parse("203.0.113.10")).isNotNull();
    }

    @Test
    void cidrContainsCoversExactPartialFamilyAndMappedIpv6Cases() throws Exception {
        WebhookUrlGuard.CidrRange exact = WebhookUrlGuard.CidrRange.parse("203.0.113.10");
        assertThat(exact).isNotNull();
        assertThat(exact.contains(java.net.InetAddress.getByName("203.0.113.10"))).isTrue();
        assertThat(exact.contains(java.net.InetAddress.getByName("203.0.113.11"))).isFalse();

        WebhookUrlGuard.CidrRange partial = WebhookUrlGuard.CidrRange.parse("203.0.113.0/25");
        assertThat(partial.contains(java.net.InetAddress.getByName("203.0.113.1"))).isTrue();
        assertThat(partial.contains(java.net.InetAddress.getByName("203.0.113.129"))).isFalse();
        assertThat(partial.contains(java.net.InetAddress.getByName("2001:db8::1"))).isFalse();

        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = 10;
        mapped[15] = 1;
        java.net.Inet6Address mappedAddress = java.net.Inet6Address.getByAddress(null, mapped, -1);
        assertThat(WebhookUrlGuard.CidrRange.parse("10.0.0.0/8").contains(mappedAddress)).isTrue();

        byte[] nonMappedPrefix = mapped.clone();
        nonMappedPrefix[0] = 1;
        assertThat(WebhookUrlGuard.CidrRange.parse("10.0.0.0/8").contains(
                java.net.Inet6Address.getByAddress(null, nonMappedPrefix, -1))).isFalse();
        byte[] missingFirstFf = mapped.clone();
        missingFirstFf[10] = 0;
        assertThat(WebhookUrlGuard.CidrRange.parse("10.0.0.0/8").contains(
                java.net.Inet6Address.getByAddress(null, missingFirstFf, -1))).isFalse();
        byte[] missingSecondFf = mapped.clone();
        missingSecondFf[11] = 0;
        assertThat(WebhookUrlGuard.CidrRange.parse("10.0.0.0/8").contains(
                java.net.Inet6Address.getByAddress(null, missingSecondFf, -1))).isFalse();
    }

    // --- allowed URL patterns ---

    @Test
    void allowedPatterns_gateUrls() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(List.of())
                .allowedUrlPatterns(List.of("https://203.0.113.*/*"))
                .build());
        assertThat(guard.check("https://203.0.113.10/receive")).isNull();
        assertThat(guard.check("https://198.51.100.10/receive"))
                .isEqualTo("URL does not match any allowed pattern");
    }

    @Test
    void nullAllowedPatternsSkipPatternGate() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(List.of())
                .allowedUrlPatterns(null)
                .build());

        assertThat(guard.check("https://203.0.113.10/hook")).isNull();
    }

    @Test
    void emptyAllowedPatternsSkipPatternGate() {
        when(configRepository.get()).thenReturn(WebhookSecurityConfig.builder()
                .allowHttp(true)
                .blockedCidrRanges(List.of())
                .allowedUrlPatterns(List.of())
                .build());

        assertThat(guard.check("https://203.0.113.10/hook")).isNull();
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
