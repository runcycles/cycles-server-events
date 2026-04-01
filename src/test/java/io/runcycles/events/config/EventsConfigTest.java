package io.runcycles.events.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class EventsConfigTest {

    @Test
    void configValues_loaded() {
        EventsConfig config = new EventsConfig();
        ReflectionTestUtils.setField(config, "pendingTimeoutSeconds", 5);
        ReflectionTestUtils.setField(config, "retryPollIntervalMs", 5000L);
        ReflectionTestUtils.setField(config, "httpTimeoutSeconds", 30);
        ReflectionTestUtils.setField(config, "httpConnectTimeoutSeconds", 5);

        assertThat(config.getPendingTimeoutSeconds()).isEqualTo(5);
        assertThat(config.getRetryPollIntervalMs()).isEqualTo(5000L);
        assertThat(config.getHttpTimeoutSeconds()).isEqualTo(30);
        assertThat(config.getHttpConnectTimeoutSeconds()).isEqualTo(5);
    }
}
