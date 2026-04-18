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
    void buildTraceparent_nullTraceFlags_defaultsToSampledFlag() {
        String traceId = "0123456789abcdef0123456789abcdef";

        String traceparent = context.buildTraceparent(traceId, null);

        assertThat(traceparent).matches("^00-0123456789abcdef0123456789abcdef-[0-9a-f]{16}-01$");
    }

    @Test
    void buildTraceparent_validTraceFlags_passesThrough() {
        String traceId = "0123456789abcdef0123456789abcdef";

        // trace-flags "00" means sampled=0 — upstream explicitly chose not to sample.
        String traceparent = context.buildTraceparent(traceId, "00");

        assertThat(traceparent).matches("^00-0123456789abcdef0123456789abcdef-[0-9a-f]{16}-00$");
    }

    @Test
    void buildTraceparent_blankTraceFlags_fallsBackTo01() {
        String traceparent = context.buildTraceparent(
                "0123456789abcdef0123456789abcdef", "");

        assertThat(traceparent).endsWith("-01");
    }

    @Test
    void buildTraceparent_malformedTraceFlags_fallsBackTo01() {
        // Uppercase, wrong length, and non-hex all fall back.
        assertThat(context.buildTraceparent(
                "0123456789abcdef0123456789abcdef", "FF")).endsWith("-01");
        assertThat(context.buildTraceparent(
                "0123456789abcdef0123456789abcdef", "0")).endsWith("-01");
        assertThat(context.buildTraceparent(
                "0123456789abcdef0123456789abcdef", "zz")).endsWith("-01");
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
