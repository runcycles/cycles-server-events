package io.runcycles.events.transport.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.Event;
import io.runcycles.events.model.EventCategory;
import io.runcycles.events.model.Subscription;
import io.runcycles.events.model.WebhookStatus;
import io.runcycles.events.transport.TransportResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookTransportTest {

    private HttpServer server;
    private int port;
    private WebhookTransport transport;
    private ObjectMapper objectMapper;
    private PayloadSigner payloadSigner;
    private TraceContext traceContext;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        payloadSigner = new PayloadSigner();
        traceContext = new TraceContext();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();

        transport = new WebhookTransport(objectMapper, payloadSigner, traceContext, 5, 2, null);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private Event testEvent() {
        return Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .tenantId("t-1")
                .source("admin")
                .build();
    }

    private Subscription testSubscription(String path) {
        return Subscription.builder()
                .subscriptionId("sub-1")
                .tenantId("t-1")
                .url("http://localhost:" + port + path)
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of("tenant.created"))
                .build();
    }

    @Test
    void type_returnsWebhook() {
        assertThat(transport.type()).isEqualTo("webhook");
    }

    @Test
    void deliver_success_200() {
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        TransportResult result = transport.deliver(testEvent(), testSubscription("/ok"), null, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatusCode()).isEqualTo(200);
        assertThat(result.getLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    void deliver_success_201() {
        server.createContext("/created", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });

        TransportResult result = transport.deliver(testEvent(), testSubscription("/created"), null, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatusCode()).isEqualTo(201);
    }

    @Test
    void deliver_failure_400() {
        server.createContext("/bad", exchange -> {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });

        TransportResult result = transport.deliver(testEvent(), testSubscription("/bad"), null, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatusCode()).isEqualTo(400);
        assertThat(result.getErrorMessage()).isEqualTo("HTTP 400");
    }

    @Test
    void deliver_failure_500() {
        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        TransportResult result = transport.deliver(testEvent(), testSubscription("/error"), null, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatusCode()).isEqualTo(500);
    }

    @Test
    void deliver_withSigningSecret_validSignature() {
        String secret = "test-secret";
        AtomicReference<String> capturedSignature = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();

        server.createContext("/signed", exchange -> {
            capturedSignature.set(exchange.getRequestHeaders().getFirst("X-Cycles-Signature"));
            byte[] body = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new String(body, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        TransportResult result = transport.deliver(testEvent(), testSubscription("/signed"), secret, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(capturedSignature.get()).isNotNull();
        // Verify the signature matches what PayloadSigner would produce
        String expectedSig = payloadSigner.sign(capturedBody.get(), secret);
        assertThat(capturedSignature.get()).isEqualTo(expectedSig);
    }

    @Test
    void deliver_withoutSigningSecret_noSignatureHeader() {
        AtomicReference<String> capturedSignature = new AtomicReference<>("NOT_SET");

        server.createContext("/unsigned", exchange -> {
            capturedSignature.set(exchange.getRequestHeaders().getFirst("X-Cycles-Signature"));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        transport.deliver(testEvent(), testSubscription("/unsigned"), null, null);

        assertThat(capturedSignature.get()).isNull();
    }

    @Test
    void deliver_withBlankSigningSecret_noSignatureHeader() {
        AtomicReference<String> capturedSignature = new AtomicReference<>("NOT_SET");

        server.createContext("/blank", exchange -> {
            capturedSignature.set(exchange.getRequestHeaders().getFirst("X-Cycles-Signature"));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        transport.deliver(testEvent(), testSubscription("/blank"), "   ", null);

        assertThat(capturedSignature.get()).isNull();
    }

    @Test
    void deliver_setsStandardHeaders() {
        ConcurrentHashMap<String, String> capturedHeaders = new ConcurrentHashMap<>();

        server.createContext("/headers", exchange -> {
            capturedHeaders.put("Content-Type", exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedHeaders.put("User-Agent", exchange.getRequestHeaders().getFirst("User-Agent"));
            capturedHeaders.put("X-Cycles-Event-Id", exchange.getRequestHeaders().getFirst("X-Cycles-Event-Id"));
            capturedHeaders.put("X-Cycles-Event-Type", exchange.getRequestHeaders().getFirst("X-Cycles-Event-Type"));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        transport.deliver(testEvent(), testSubscription("/headers"), null, null);

        assertThat(capturedHeaders.get("Content-Type")).isEqualTo("application/json");
        assertThat(capturedHeaders.get("User-Agent")).startsWith("cycles-server-events/");
        assertThat(capturedHeaders.get("X-Cycles-Event-Id")).isEqualTo("evt-1");
        assertThat(capturedHeaders.get("X-Cycles-Event-Type")).isEqualTo("tenant.created");
    }

    @Test
    void deliver_withCustomHeaders() {
        AtomicReference<String> capturedCustom = new AtomicReference<>();

        server.createContext("/custom", exchange -> {
            capturedCustom.set(exchange.getRequestHeaders().getFirst("X-Custom-Header"));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        Subscription sub = testSubscription("/custom");
        sub.setHeaders(Map.of("X-Custom-Header", "custom-value"));

        transport.deliver(testEvent(), sub, null, null);

        assertThat(capturedCustom.get()).isEqualTo("custom-value");
    }

    @Test
    void deliver_connectionRefused() {
        Subscription sub = Subscription.builder()
                .subscriptionId("sub-1")
                .url("http://localhost:1") // port 1 — connection refused
                .status(WebhookStatus.ACTIVE)
                .build();

        TransportResult result = transport.deliver(testEvent(), sub, null, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatusCode()).isEqualTo(0);
        // Error message may be null or contain connection error details depending on JDK
        assertThat(result.getLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void deliver_nullHeaders_noException() {
        server.createContext("/null-headers", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        Subscription sub = testSubscription("/null-headers");
        sub.setHeaders(null);

        TransportResult result = transport.deliver(testEvent(), sub, null, null);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void deliver_withTraceId_propagatesHeader() {
        ConcurrentHashMap<String, String> captured = new ConcurrentHashMap<>();

        server.createContext("/trace", exchange -> {
            captured.put("X-Cycles-Trace-Id", exchange.getRequestHeaders().getFirst("X-Cycles-Trace-Id"));
            captured.put("traceparent", exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        Event e = testEvent();
        e.setTraceId("0123456789abcdef0123456789abcdef");

        transport.deliver(e, testSubscription("/trace"), null, null);

        assertThat(captured.get("X-Cycles-Trace-Id")).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(captured.get("traceparent"))
                .matches("^00-0123456789abcdef0123456789abcdef-[0-9a-f]{16}-01$");
    }

    @Test
    void deliver_withoutTraceId_mintsFreshHeader() {
        ConcurrentHashMap<String, String> captured = new ConcurrentHashMap<>();

        server.createContext("/mint", exchange -> {
            captured.put("X-Cycles-Trace-Id", exchange.getRequestHeaders().getFirst("X-Cycles-Trace-Id"));
            captured.put("traceparent", exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        transport.deliver(testEvent(), testSubscription("/mint"), null, null);

        assertThat(captured.get("X-Cycles-Trace-Id")).matches("^[0-9a-f]{32}$");
        assertThat(captured.get("traceparent")).matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$");
        // And the trace-id inside the traceparent matches the header
        String hdr = captured.get("X-Cycles-Trace-Id");
        assertThat(captured.get("traceparent")).startsWith("00-" + hdr + "-");
    }

    @Test
    void deliver_withRequestId_propagatesHeader() {
        AtomicReference<String> captured = new AtomicReference<>("NOT_SET");

        server.createContext("/req", exchange -> {
            captured.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        Event e = testEvent();
        e.setRequestId("req-abc-123");

        transport.deliver(e, testSubscription("/req"), null, null);

        assertThat(captured.get()).isEqualTo("req-abc-123");
    }

    @Test
    void deliver_withoutRequestId_noHeader() {
        AtomicReference<String> captured = new AtomicReference<>("NOT_SET");

        server.createContext("/no-req", exchange -> {
            captured.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        transport.deliver(testEvent(), testSubscription("/no-req"), null, null);

        assertThat(captured.get()).isNull();
    }

    @Test
    void deliver_withDeliveryTraceFlags_honorsInboundSampling() {
        // Spec v0.1.25.28: when the originating HTTP request presented a
        // valid traceparent, the dispatcher MUST preserve its trace-flags
        // byte on the outbound traceparent instead of defaulting to 01.
        AtomicReference<String> captured = new AtomicReference<>();

        server.createContext("/flags-honor", exchange -> {
            captured.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        Event e = testEvent();
        e.setTraceId("0123456789abcdef0123456789abcdef");
        Delivery delivery = Delivery.builder()
                .traceparentInboundValid(Boolean.TRUE)
                .traceFlags("00") // upstream explicitly chose sampled=0
                .build();

        transport.deliver(e, testSubscription("/flags-honor"), null, delivery);

        assertThat(captured.get())
                .matches("^00-0123456789abcdef0123456789abcdef-[0-9a-f]{16}-00$");
    }

    @Test
    void deliver_withInboundInvalid_defaultsTo01() {
        // traceparent_inbound_valid=false → trace_flags must be ignored;
        // the dispatcher defaults to 01.
        AtomicReference<String> captured = new AtomicReference<>();

        server.createContext("/flags-default", exchange -> {
            captured.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        Event e = testEvent();
        e.setTraceId("0123456789abcdef0123456789abcdef");
        Delivery delivery = Delivery.builder()
                .traceparentInboundValid(Boolean.FALSE)
                .traceFlags("00") // MUST be ignored because inbound was invalid
                .build();

        transport.deliver(e, testSubscription("/flags-default"), null, delivery);

        assertThat(captured.get()).endsWith("-01");
    }

    @Test
    void deliver_nullDelivery_defaultsTo01() {
        // Ad-hoc delivery paths (e.g., webhook test POST) may pass null.
        AtomicReference<String> captured = new AtomicReference<>();

        server.createContext("/null-delivery", exchange -> {
            captured.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        transport.deliver(testEvent(), testSubscription("/null-delivery"), null, null);

        assertThat(captured.get()).endsWith("-01");
    }
}
