package io.runcycles.events.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    @Test
    void returnsNullForNullInput() {
        assertThat(LogSanitizer.safe(null)).isNull();
    }

    @Test
    void flattensCarriageReturnAndLineFeed() {
        assertThat(LogSanitizer.safe("first\r\nsecond")).isEqualTo("first  second");
    }

    @Test
    void rendersNonStringValues() {
        assertThat(LogSanitizer.safe(42)).isEqualTo("42");
    }
}
