package io.runcycles.events.transport;

import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.Event;
import io.runcycles.events.model.Subscription;

public interface Transport {

    String type();

    /**
     * Deliver the event to the subscription's transport target.
     *
     * <p>The {@code delivery} parameter carries W3C Trace Context
     * sampling hints ({@link Delivery#getTraceFlags()} and
     * {@link Delivery#getTraceparentInboundValid()}) so the transport
     * can construct a spec-conformant outbound traceparent header. Pass
     * {@code null} when no delivery record exists (e.g., ad-hoc webhook
     * test POSTs); the transport MUST treat that as
     * {@code traceparent_inbound_valid=false} and default trace-flags
     * to {@code "01"}.
     *
     * <p>Spec reference: cycles-governance-admin-v0.1.25.yaml
     * info.version 0.1.25.28 ({@code WebhookDelivery.trace_flags}
     * and {@code WebhookDelivery.traceparent_inbound_valid}).
     */
    TransportResult deliver(Event event, Subscription subscription, String signingSecret, Delivery delivery);
}
