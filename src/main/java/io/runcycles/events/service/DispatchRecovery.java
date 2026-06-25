package io.runcycles.events.service;

import static io.runcycles.events.logging.LogSanitizer.safe;

import io.runcycles.events.repository.DeliveryQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Recovers webhook deliveries left in-flight by a previous crash.
 */
@Component
public class DispatchRecovery {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchRecovery.class);

    private final DeliveryQueueRepository queueRepository;

    public DispatchRecovery(DeliveryQueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            long recovered = queueRepository.recoverProcessing();
            if (recovered > 0) {
                LOG.warn("Recovered orphaned webhook deliveries back to pending: recovered={} processing_queue=dispatch:processing pending_queue=dispatch:pending",
                        recovered);
            }
        } catch (RuntimeException e) {
            LOG.error("Webhook delivery recovery on startup failed: processing_queue=dispatch:processing pending_queue=dispatch:pending error={}",
                    safe(e.getMessage()), e);
        }
    }
}
