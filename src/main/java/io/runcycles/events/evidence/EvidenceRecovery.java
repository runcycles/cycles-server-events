package io.runcycles.events.evidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On startup, returns any in-flight evidence records orphaned by a previous
 * crash (left in {@code evidence:processing} between claim and ack) to
 * {@code evidence:pending} for reprocessing. Reprocessing is safe because
 * envelopes are content-addressed (idempotent).
 *
 * <p>NOTE (multi-replica): a shared processing list means a startup recovery on
 * one instance can also re-queue records another instance is actively
 * processing — harmless (idempotent) but causes some churn. Per-consumer
 * recovery (Redis Streams consumer groups + XCLAIM by idle time) is the v0.2
 * upgrade if that churn matters.
 */
@Component
public class EvidenceRecovery {

    private static final Logger LOG = LoggerFactory.getLogger(EvidenceRecovery.class);

    private final EvidenceQueueConsumer consumer;

    public EvidenceRecovery(EvidenceQueueConsumer consumer) {
        this.consumer = consumer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            long recovered = consumer.recover();
            if (recovered > 0) {
                LOG.warn("recovered {} orphaned evidence record(s) from {} back to pending",
                        recovered, "evidence:processing");
            }
        } catch (RuntimeException e) {
            LOG.error("evidence recovery on startup failed: {}", e.getMessage());
        }
    }
}
