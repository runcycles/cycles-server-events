package io.runcycles.events.service;

import io.runcycles.events.repository.DeliveryQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DispatchLoop {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchLoop.class);

    private final DeliveryQueueRepository queueRepository;
    private final DeliveryHandler deliveryHandler;
    private final int timeoutSeconds;

    public DispatchLoop(DeliveryQueueRepository queueRepository, DeliveryHandler deliveryHandler,
                        @Value("${dispatch.pending.timeout-seconds:5}") int timeoutSeconds) {
        this.queueRepository = queueRepository;
        this.deliveryHandler = deliveryHandler;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelay = 0)
    public void processNext() {
        try {
            String deliveryId = queueRepository.popPending(timeoutSeconds);
            if (deliveryId != null) {
                deliveryHandler.handle(deliveryId);
            }
        } catch (Exception e) {
            LOG.error("Error in dispatch loop", e);
        }
    }
}
