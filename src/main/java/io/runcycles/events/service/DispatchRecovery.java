package io.runcycles.events.service;

import static io.runcycles.events.logging.LogSanitizer.safe;

import io.runcycles.events.repository.DeliveryQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
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
        if (recoveryIdleMs <= 0) {
            throw new IllegalArgumentException("dispatch recovery idle time must be positive");
        }
        this.recoveryIdleMs = recoveryIdleMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recover("startup");
    }

    /**
     * Recovery must be continuous, not startup-only. A process can restart
     * before an orphan has crossed the idle threshold; without this poll the
     * orphan would remain in {@code dispatch:processing} until another restart.
     */
    @Scheduled(fixedDelayString = "${dispatch.processing.recovery-interval-ms:30000}")
    public void recoverPeriodically() {
        recover("periodic");
    }

    private void recover(String trigger) {
        try {
            long recovered = queueRepository.recoverStaleProcessing(System.currentTimeMillis(), recoveryIdleMs);
            if (recovered > 0) {
                LOG.warn("Recovered stale webhook deliveries back to pending: recovered={} idle_ms={} trigger={} processing_queue=dispatch:processing pending_queue=dispatch:pending",
                        recovered, recoveryIdleMs, trigger);
            }
        } catch (RuntimeException e) {
            LOG.error("Webhook delivery recovery failed: idle_ms={} trigger={} processing_queue=dispatch:processing pending_queue=dispatch:pending error={}",
                    recoveryIdleMs, trigger, safe(e.getMessage()), e);
        }
    }
}
