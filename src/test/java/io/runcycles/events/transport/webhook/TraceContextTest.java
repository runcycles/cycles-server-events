package io.runcycles.events.transport.webhook;

import io.runcycles.events.model.Event;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    private final TraceContext context = new TraceContext();

    @Test
    void resolveOrMintTraceId_validTraceIdOnEvent_passesThrough() {
        Event event = Event.builder()
                .traceId("0123456789abcdef0123456789abcdef")
                .build();

        assertThat(context.resolveOrMintTraceId(event))
                .isEqualTo("0123456789abcdef0123456789abcdef");
    }

    @Test
    void resolveOrMintTraceId_nullTraceIdOnEvent_mintsFresh() {
        Event event = Event.builder().build();

        String traceId = context.resolveOrMintTraceId(event);

        assertThat(traceId).matches("^[0-9a-f]{32}$");
    }

    @Test
    void resolveOrMintTraceId_malformedTraceId_mintsFresh() {
        Event event = Event.builder().traceId("NOT-A-VALID-ID").build();

        String traceId = context.resolveOrMintTraceId(event);

        assertThat(traceId).matches("^[0-9a-f]{32}$");
        assertThat(traceId).isNotEqualTo("NOT-A-VALID-ID");
    }

    @Test
    void resolveOrMintTraceId_uppercaseHex_mintsFresh() {
        // Spec requires LOWERCASE hex only.
        Event event = Event.builder()
                .traceId("0123456789ABCDEF0123456789ABCDEF")
                .build();

        String traceId = context.resolveOrMintTraceId(event);

        assertThat(traceId).matches("^[0-9a-f]{32}$");
        assertThat(traceId).isNotEqualTo("0123456789ABCDEF0123456789ABCDEF");
    }

    @Test
    void resolveOrMintTraceId_nullEvent_mintsFresh() {
        String traceId = context.resolveOrMintTraceId(null);

        assertThat(traceId).matches("^[0-9a-f]{32}$");
    }

    @Test
    void buildTraceparent_hasW3CShape() {
        String traceId = "0123456789abcdef0123456789abcdef";

        String traceparent = context.buildTraceparent(traceId);

        assertThat(traceparent).matches("^00-0123456789abcdef0123456789abcdef-[0-9a-f]{16}-01$");
    }

    @Test
    void freshSpanId_isSixteenLowercaseHex() {
        String spanId = context.freshSpanId();

        assertThat(spanId).matches("^[0-9a-f]{16}$");
    }

    @Test
    void freshSpanId_entropyAcrossCalls() {
        // 1000 span-ids (each 64 bits) collision probability is negligible.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(context.freshSpanId());
        }

        assertThat(seen).hasSize(1000);
    }
}
