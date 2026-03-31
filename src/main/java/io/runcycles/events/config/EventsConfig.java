package io.runcycles.events.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventsConfig {

    @Value("${dispatch.pending.timeout-seconds:5}")
    private int pendingTimeoutSeconds;

    @Value("${dispatch.retry.poll-interval-ms:5000}")
    private long retryPollIntervalMs;

    @Value("${dispatch.http.timeout-seconds:30}")
    private int httpTimeoutSeconds;

    @Value("${dispatch.http.connect-timeout-seconds:5}")
    private int httpConnectTimeoutSeconds;

    public int getPendingTimeoutSeconds() {
        return pendingTimeoutSeconds;
    }

    public long getRetryPollIntervalMs() {
        return retryPollIntervalMs;
    }

    public int getHttpTimeoutSeconds() {
        return httpTimeoutSeconds;
    }

    public int getHttpConnectTimeoutSeconds() {
        return httpConnectTimeoutSeconds;
    }
}
