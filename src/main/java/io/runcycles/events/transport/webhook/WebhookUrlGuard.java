package io.runcycles.events.transport.webhook;

import static io.runcycles.events.logging.LogSanitizer.safe;

import io.runcycles.events.model.WebhookSecurityConfig;
import io.runcycles.events.repository.WebhookSecurityConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Delivery-time SSRF guard. Re-validates the subscription URL against the
 * CURRENT admin webhook-security config immediately before each outbound
 * POST. Admin-side validation runs only at subscription create/update,
 * which leaves three gaps closed here:
 *
 * <ul>
 *   <li>DNS rebinding / target drift: the hostname's resolution can change
 *       between subscription creation and delivery (re-resolving here
 *       narrows the window to a single request; full resolve-and-pin is
 *       not expressible with java.net.http and is documented as residual
 *       risk in AUDIT.md).</li>
 *   <li>Config tightened after creation: blocking a CIDR or revoking
 *       {@code allow_http} now applies to EXISTING subscriptions at their
 *       next delivery, not just to new ones.</li>
 *   <li>Legacy subscriptions created before admin-side validation
 *       existed.</li>
 * </ul>
 *
 * <p>Wire-policy semantics follow the admin plane's {@code WebhookUrlValidator}
 * (same config key, CIDR matching incl. IPv4-mapped IPv6, and glob dialect).
 * This delivery-side parser is deliberately stricter for malformed CIDRs:
 * an indeterminate blocklist is rejected rather than partially ignored.
 *
 * <p>Returns a violation reason string, or {@code null} when the URL is
 * allowed. Unresolvable hosts are treated as violations when CIDR ranges
 * are configured (fail-closed, mirroring admin): without resolution the
 * ranges cannot be checked. A config read/parse failure propagates as
 * {@link IllegalStateException} from the repository — an INDETERMINATE
 * policy is deliberately not converted into either an allow or a block;
 * the caller retries the delivery once the config is readable.
 */
@Component
public class WebhookUrlGuard {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookUrlGuard.class);

    private final WebhookSecurityConfigRepository configRepository;

    public WebhookUrlGuard(WebhookSecurityConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    /** @return violation reason, or {@code null} when the URL passes the current config. */
    public String check(String url) {
        if (url == null || url.isBlank()) {
            return "URL is required";
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return "Malformed URL";
        }
        WebhookSecurityConfig config = configRepository.get();
        String scheme = uri.getScheme();
        boolean isHttps = "https".equalsIgnoreCase(scheme);
        boolean isHttp = "http".equalsIgnoreCase(scheme);
        if (!Boolean.TRUE.equals(config.getAllowHttp()) && !isHttps) {
            return "HTTPS required";
        }
        if (!isHttps && !isHttp) {
            return "Only HTTP(S) URLs are allowed";
        }
        String host = uri.getHost();
        if (host == null) {
            return "No host in URL";
        }
        List<CidrRange> blockedRanges = parseCidrRanges(config.getBlockedCidrRanges());
        if (!blockedRanges.isEmpty()) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress addr : addresses) {
                    for (CidrRange range : blockedRanges) {
                        if (range.contains(addr)) {
                            return "Resolves to blocked IP: " + addr.getHostAddress();
                        }
                    }
                }
            } catch (UnknownHostException e) {
                return "Cannot resolve hostname: " + host;
            }
        }
        List<String> patterns = config.getAllowedUrlPatterns();
        if (patterns != null && !patterns.isEmpty()) {
            boolean matched = patterns.stream().anyMatch(p -> matchesGlob(url, p));
            if (!matched) {
                return "URL does not match any allowed pattern";
            }
        }
        return null;
    }

    private List<CidrRange> parseCidrRanges(List<String> cidrStrings) {
        if (cidrStrings == null || cidrStrings.isEmpty()) {
            return List.of();
        }
        List<CidrRange> ranges = new ArrayList<>(cidrStrings.size());
        for (String cidr : cidrStrings) {
            if (cidr == null || cidr.isBlank()) {
                throw new IllegalStateException("blocked_cidr_ranges contains a blank entry");
            }
            CidrRange parsed = CidrRange.parse(cidr);
            if (parsed == null) {
                // A malformed blocklist is an indeterminate security policy,
                // never permission to silently ignore part of the policy.
                throw new IllegalStateException("blocked_cidr_ranges contains invalid CIDR: " + safe(cidr));
            }
            ranges.add(parsed);
        }
        return ranges;
    }

    static class CidrRange {
        private final byte[] network;
        private final int prefixLength;
        private final boolean isIpv4;

        CidrRange(byte[] network, int prefixLength, boolean isIpv4) {
            this.network = network;
            this.prefixLength = prefixLength;
            this.isIpv4 = isIpv4;
        }

        static CidrRange parse(String cidr) {
            try {
                String[] parts = cidr.split("/", -1);
                if (parts.length > 2 || parts[0].isBlank()
                        || (parts.length == 2 && parts[1].isBlank())) {
                    return null;
                }
                InetAddress addr = parseAddressLiteral(parts[0]);
                int maxPrefix = addr.getAddress().length * 8;
                if (parts.length == 2 && !parts[1].chars().allMatch(Character::isDigit)) {
                    return null;
                }
                int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : maxPrefix;
                if (prefix > maxPrefix) {
                    LOG.warn("Invalid webhook CIDR config rejected: config_field=blocked_cidr_ranges cidr={} prefix_length={} max_prefix_length={}",
                        safe(cidr), prefix, maxPrefix);
                    return null;
                }
                return new CidrRange(addr.getAddress(), prefix, addr instanceof Inet4Address);
            } catch (Exception e) {
                LOG.warn("Invalid webhook CIDR config rejected: config_field=blocked_cidr_ranges cidr={} exception_class={} error={}",
                    safe(cidr), e.getClass().getSimpleName(), safe(e.getMessage()));
                return null;
            }
        }

        private static InetAddress parseAddressLiteral(String value) throws UnknownHostException {
            if (value.indexOf(':') >= 0) {
                // A colon distinguishes an IPv6 literal from a DNS hostname.
                // Zone identifiers are inappropriate in a portable CIDR policy.
                if (value.indexOf('%') >= 0 || !value.matches("[0-9A-Fa-f:.]+")) {
                    throw new UnknownHostException("not an IPv6 address literal");
                }
                return InetAddress.getByName(value);
            }
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) {
                throw new UnknownHostException("not an IP address literal");
            }
            byte[] bytes = new byte[4];
            for (int i = 0; i < octets.length; i++) {
                if (octets[i].isEmpty() || !octets[i].chars().allMatch(Character::isDigit)) {
                    throw new UnknownHostException("not an IPv4 address literal");
                }
                int parsed = Integer.parseInt(octets[i]);
                if (parsed > 255) {
                    throw new UnknownHostException("IPv4 octet out of range");
                }
                bytes[i] = (byte) parsed;
            }
            return InetAddress.getByAddress(bytes);
        }

        boolean contains(InetAddress address) {
            byte[] addrBytes = address.getAddress();
            // Handle IPv4-mapped IPv6 addresses (::ffff:x.x.x.x) against IPv4 CIDR ranges
            if (isIpv4 && address instanceof Inet6Address) {
                if (isIpv4Mapped(addrBytes)) {
                    addrBytes = new byte[] { addrBytes[12], addrBytes[13], addrBytes[14], addrBytes[15] };
                } else {
                    return false;
                }
            }
            if (addrBytes.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((addrBytes[fullBytes] & mask) != (network[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isIpv4Mapped(byte[] ipv6Bytes) {
            // ::ffff:x.x.x.x — bytes 0-9 are 0, bytes 10-11 are 0xFF
            for (int i = 0; i < 10; i++) {
                if (ipv6Bytes[i] != 0) return false;
            }
            return ipv6Bytes[10] == (byte) 0xFF && ipv6Bytes[11] == (byte) 0xFF;
        }
    }

    private static final Pattern GLOB_META = Pattern.compile("[+?()\\[\\]{}|^$\\\\]");

    boolean matchesGlob(String url, String pattern) {
        // Escape all regex metacharacters except * and ., then convert glob wildcards
        String escaped = GLOB_META.matcher(pattern).replaceAll("\\\\$0");
        String regex = escaped.replace(".", "\\.").replace("*", ".*");
        try {
            return url.matches(regex);
        } catch (Exception e) {
            return false;
        }
    }
}
