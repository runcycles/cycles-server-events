package io.runcycles.events.transport;

import io.runcycles.events.model.Event;
import io.runcycles.events.model.Subscription;

public interface Transport {

    String type();

    TransportResult deliver(Event event, Subscription subscription, String signingSecret);
}
