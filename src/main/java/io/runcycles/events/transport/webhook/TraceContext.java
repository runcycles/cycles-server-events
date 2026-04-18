package io.runcycles.events.transport.webhook;

import io.runcycles.events.model.Event;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Trace-context helper for outbound webhook deliveries.
 *
 * <p>Implements the cross-surface correlation contract from
 * {@code cycles-protocol-v0.yaml} lines 256-277: every outbound webhook
 * POST carries {@code X-Cycles-Trace-Id} (always required) and a W3C
 * {@code traceparent} header.
 *
 * <p>Events server has no inbound HTTP surface, so it can never receive a
 * {@code traceparent} upstream. Behaviour:
 * <ul>
 *   <li>If the Event row carries a valid 32-hex {@code trace_id}, that is
 *       the outbound trace-id (produced upstream by
 *       {@code cycles-server-admin}, per spec v0.1.25.27).</li>
 *   <li>If absent or malformed, mint a fresh 128-bit trace-id from
 *       {@link SecureRandom} so the dispatcher never drops the
 *       "Always required" header contract.</li>
 * </ul>
 *
 * <p>Span-id is ALWAYS freshly generated for the outbound delivery per
 * spec line 266 ("MUST be freshly generated for the outbound delivery,
 * NOT reused from inbound"). trace-flags defaults to {@code 01} (sampled)
 * since the dispatcher has no inbound W3C parent to inherit from.
 */
@Component
public class TraceContext {

    static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
    static final Pattern SPAN_ID_PATTERN = Pattern.compile("^[0-9a-f]{16}$");
    static final Pattern TRACE_FLAGS_PATTERN = Pattern.compile("^[0-9a-f]{2}$");
    private static final String DEFAULT_TRACE_FLAGS = "01";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final SecureRandom random = new SecureRandom();

    /**
     * Return the event's trace-id if present and well-formed, otherwise
     * mint a fresh 128-bit id. Never returns null or a malformed value.
     */
    public String resolveOrMintTraceId(Event event) {
        if (event != null) {
            String candidate = event.getTraceId();
            if (candidate != null && TRACE_ID_PATTERN.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return mintHex(16);
    }

    /**
     * Build a W3C Trace Context v00 {@code traceparent} header value:
     * {@code 00-{traceId}-{freshSpanId}-{traceFlags-or-01}}.
     * Falls back to {@link #DEFAULT_TRACE_FLAGS} ("01", sampled) when
     * {@code traceFlags} is null, blank, or not 2 lowercase hex chars.
     */
    public String buildTraceparent(String traceId, String traceFlags) {
        String flags = (traceFlags != null && TRACE_FLAGS_PATTERN.matcher(traceFlags).matches())
                ? traceFlags : DEFAULT_TRACE_FLAGS;
        return "00-" + traceId + "-" + freshSpanId() + "-" + flags;
    }

    /** Fresh 64-bit span-id as 16 lowercase hex characters. */
    public String freshSpanId() {
        return mintHex(8);
    }

    private String mintHex(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        char[] out = new char[bytes * 2];
        for (int i = 0; i < bytes; i++) {
            int v = buf[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }
}
