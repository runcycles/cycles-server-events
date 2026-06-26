package io.runcycles.events.service;

import static io.runcycles.events.logging.LogSanitizer.safe;

import io.runcycles.events.repository.DeliveryQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final long recoveryIdleMs;

    public DispatchRecovery(DeliveryQueueRepository queueRepository,
                            @Value("${dispatch.processing.recovery-idle-ms:120000}") long recoveryIdleMs) {
        this.queueRepository = queueRepository;
        this.recoveryIdleMs = recoveryIdleMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            long recovered = queueRepository.recoverStaleProcessing(System.currentTimeMillis(), recoveryIdleMs);
            if (recovered > 0) {
                LOG.warn("Recovered stale webhook deliveries back to pending: recovered={} idle_ms={} processing_queue=dispatch:processing pending_queue=dispatch:pending",
                        recovered, recoveryIdleMs);
            }
        } catch (RuntimeException e) {
            LOG.error("Webhook delivery recovery on startup failed: idle_ms={} processing_queue=dispatch:processing pending_queue=dispatch:pending error={}",
                    recoveryIdleMs, safe(e.getMessage()), e);
        }
    }
}
