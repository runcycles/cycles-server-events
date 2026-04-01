package io.runcycles.events.transport.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final HttpClient httpClient;
    private final String userAgent;

    public WebhookTransport(ObjectMapper objectMapper, PayloadSigner payloadSigner,
                            @Value("${dispatch.http.timeout-seconds:30}") int timeoutSeconds,
                            @Value("${dispatch.http.connect-timeout-seconds:5}") int connectTimeoutSeconds,
                            @Autowired(required = false) BuildProperties buildProperties) {
        this.objectMapper = objectMapper;
        this.payloadSigner = payloadSigner;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
        String version = buildProperties != null ? buildProperties.getVersion() : "0.1.25.1";
        this.userAgent = "cycles-server-events/" + version;
    }

    @Override
    public String type() {
        return "webhook";
    }

    @Override
    public TransportResult deliver(Event event, Subscription subscription, String signingSecret) {
        long start = System.currentTimeMillis();
        try {
            String payload = objectMapper.writeValueAsString(event);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(subscription.getUrl()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", userAgent)
                    .header("X-Cycles-Event-Id", event.getEventId())
                    .header("X-Cycles-Event-Type", event.getEventType())
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(payload));

            if (signingSecret != null && !signingSecret.isBlank()) {
                reqBuilder.header("X-Cycles-Signature", payloadSigner.sign(payload, signingSecret));
            }
            if (subscription.getHeaders() != null) {
                subscription.getHeaders().forEach(reqBuilder::header);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            int elapsed = (int) (System.currentTimeMillis() - start);
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            return TransportResult.builder()
                    .success(success)
                    .statusCode(response.statusCode())
                    .latencyMs(elapsed)
                    .errorMessage(success ? null : "HTTP " + response.statusCode())
                    .build();
        } catch (Exception e) {
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
