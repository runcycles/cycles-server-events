package io.runcycles.events.evidence;

import static io.runcycles.events.logging.LogSanitizer.safe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Returns in-flight evidence records orphaned by a previous crash (left in
 * {@code evidence:processing} between claim and ack) to
 * {@code evidence:pending} for reprocessing. Reprocessing is safe because
 * envelopes are content-addressed (idempotent).
 *
 * Recovery is idle-gated and periodic, so work younger than the configured
 * lease remains with its live replica and a young startup orphan is not
 * stranded permanently.
 */
@Component
@ConditionalOnEvidenceConfigured
public class EvidenceRecovery {

    private static final Logger LOG = LoggerFactory.getLogger(EvidenceRecovery.class);

    private final EvidenceQueueConsumer consumer;
    private final long recoveryIdleMs;
    private final int recoveryBatchSize;

    public EvidenceRecovery(
            EvidenceQueueConsumer consumer,
            @Value("${cycles.evidence.queue.recovery-idle-ms:120000}") long recoveryIdleMs,
            @Value("${cycles.evidence.queue.recovery-batch-size:100}") int recoveryBatchSize) {
        this.consumer = consumer;
        if (recoveryIdleMs <= 0 || recoveryBatchSize <= 0) {
            throw new IllegalArgumentException("evidence recovery idle time and batch size must be positive");
        }
        this.recoveryIdleMs = recoveryIdleMs;
        this.recoveryBatchSize = recoveryBatchSize;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recover("startup");
    }

    @Scheduled(fixedDelayString = "${cycles.evidence.queue.recovery-interval-ms:30000}")
    public void recoverPeriodically() {
        recover("periodic");
    }

    private void recover(String trigger) {
        try {
            long recovered = consumer.recoverStale(
                    System.currentTimeMillis(), recoveryIdleMs, recoveryBatchSize);
            if (recovered > 0) {
                LOG.warn("Recovered stale evidence records back to pending: recovered={} idle_ms={} batch_size={} trigger={} processing_queue=evidence:processing pending_queue=evidence:pending",
                        recovered, recoveryIdleMs, recoveryBatchSize, trigger);
            }
        } catch (RuntimeException e) {
            LOG.error("Evidence recovery failed: idle_ms={} batch_size={} trigger={} processing_queue=evidence:processing pending_queue=evidence:pending error={}",
                    recoveryIdleMs, recoveryBatchSize, trigger, safe(e.getMessage()), e);
        }
    }
}
