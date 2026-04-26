package io.runcycles.events.transport.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.Event;
import io.runcycles.events.model.Subscription;
import io.runcycles.events.transport.Transport;
import io.runcycles.events.transport.TransportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class WebhookTransport implements Transport {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookTransport.class);

    private final ObjectMapper objectMapper;
    private final PayloadSigner payloadSigner;
    private final TraceContext traceContext;
    private final HttpClient httpClient;
    private final String userAgent;

    private final int timeoutSeconds;

    public WebhookTransport(ObjectMapper objectMapper, PayloadSigner payloadSigner,
                            TraceContext traceContext,
                            @Value("${dispatch.http.timeout-seconds:30}") int timeoutSeconds,
                            @Value("${dispatch.http.connect-timeout-seconds:5}") int connectTimeoutSeconds,
                            @Autowired(required = false) BuildProperties buildProperties) {
        this.objectMapper = objectMapper;
        this.payloadSigner = payloadSigner;
        this.traceContext = traceContext;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
        String version = buildProperties != null ? buildProperties.getVersion() : "0.1.25.12";
        this.userAgent = "cycles-server-events/" + version;
    }

    @Override
    public String type() {
        return "webhook";
    }

    @Override
    public TransportResult deliver(Event event, Subscription subscription, String signingSecret, Delivery delivery) {
        long start = System.currentTimeMillis();
        try {
            String payload = objectMapper.writeValueAsString(event);
            String traceId = traceContext.resolveOrMintTraceId(event);
            // Preserve inbound sampling decision when the originating HTTP
            // request carried a valid traceparent; otherwise the spec
            // requires defaulting to "01" (sampled). See cycles-protocol-v0
            // §CORRELATION AND TRACING, cycles-governance-admin v0.1.25.28.
            String traceFlags = (delivery != null
                    && Boolean.TRUE.equals(delivery.getTraceparentInboundValid()))
                    ? delivery.getTraceFlags() : null;
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(subscription.getUrl()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", userAgent)
                    .header("X-Cycles-Event-Id", event.getEventId())
                    .header("X-Cycles-Event-Type", event.getEventType())
                    .header("X-Cycles-Trace-Id", traceId)
                    .header("traceparent", traceContext.buildTraceparent(traceId, traceFlags))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(payload));

            if (event.getRequestId() != null && !event.getRequestId().isBlank()) {
                reqBuilder.header("X-Request-Id", event.getRequestId());
            }
            if (signingSecret != null && !signingSecret.isBlank()) {
                reqBuilder.header("X-Cycles-Signature", payloadSigner.sign(payload, signingSecret));
            }
            if (subscription.getHeaders() != null) {
                subscription.getHeaders().forEach(reqBuilder::header);
            }

            HttpResponse<Void> response = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.discarding());
            int elapsed = (int) (System.currentTimeMillis() - start);
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            return TransportResult.builder()
                    .success(success)
                    .statusCode(response.statusCode())
                    .latencyMs(elapsed)
                    .errorMessage(success ? null : "HTTP " + response.statusCode())
                    .build();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            int elapsed = (int) (System.currentTimeMillis() - start);
            LOG.warn("Webhook delivery failed to {}: {}", subscription.getUrl(), e.getMessage());
            return TransportResult.builder()
                    .success(false)
                    .statusCode(0)
                    .latencyMs(elapsed)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
}
