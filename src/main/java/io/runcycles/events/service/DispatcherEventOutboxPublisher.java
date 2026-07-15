package io.runcycles.events.service;

import static io.runcycles.events.logging.LogSanitizer.safe;

import io.runcycles.events.metrics.CyclesMetrics;
import io.runcycles.events.model.DispatcherEventTask;
import io.runcycles.events.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Publishes dispatcher protocol events from the durable Redis outbox. */
@Component
public class DispatcherEventOutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(DispatcherEventOutboxPublisher.class);

    private final EventRepository eventRepository;
    private final CyclesMetrics metrics;
    private final int batchSize;
    private final long claimLeaseMs;
    private final long retryDelayMs;

    public DispatcherEventOutboxPublisher(
            EventRepository eventRepository,
            CyclesMetrics metrics,
            @Value("${dispatch.event-outbox.batch-size:25}") int batchSize,
            @Value("${dispatch.event-outbox.claim-lease-ms:30000}") long claimLeaseMs,
            @Value("${dispatch.event-outbox.retry-delay-ms:5000}") long retryDelayMs) {
        if (batchSize <= 0 || claimLeaseMs <= 0 || retryDelayMs <= 0) {
            throw new IllegalArgumentException("dispatcher event outbox limits must be positive");
        }
        this.eventRepository = eventRepository;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.claimLeaseMs = claimLeaseMs;
        this.retryDelayMs = retryDelayMs;
    }

    @Scheduled(fixedDelayString = "${dispatch.event-outbox.poll-interval-ms:1000}")
    public void publishDue() {
        List<String> due;
        try {
            due = eventRepository.findDueDispatcherEvents(System.currentTimeMillis(), batchSize);
        } catch (RuntimeException e) {
            metrics.recordDispatcherEventDeferred(null, "scan_failure");
            LOG.warn("Dispatcher event outbox scan failed: error={}", safe(e.getMessage()));
            return;
        }
        for (String taskId : due) {
            publishClaimed(taskId);
        }
    }

    private void publishClaimed(String taskId) {
        String owner = UUID.randomUUID().toString();
        try {
            if (!eventRepository.tryClaimDispatcherEvent(taskId, owner, claimLeaseMs)) return;
        } catch (RuntimeException e) {
            metrics.recordDispatcherEventDeferred(null, "claim_failure");
            LOG.warn("Dispatcher event outbox claim failed: task_id={} error={}", safe(taskId), safe(e.getMessage()));
            return;
        }

        DispatcherEventTask task = null;
        boolean acknowledged = false;
        try {
            task = eventRepository.findDispatcherEventTask(taskId);
            if (task == null) {
                // The inline publisher may have acknowledged it after this scan.
                acknowledged = eventRepository.ackClaimedDispatcherEvent(taskId, owner);
                return;
            }
            eventRepository.save(task.event());
            acknowledged = eventRepository.ackClaimedDispatcherEvent(taskId, owner);
            if (!acknowledged) {
                // Another publisher (normally the terminal handler's inline
                // fast path) completed the same deterministic task. The event
                // save is idempotent; do not recreate or count the task again.
                return;
            }
            metrics.recordDispatcherEventPublished(task.event().getEventType());
            LOG.info("Published durable dispatcher event: task_id={} event_id={} event_type={} correlation_id={}",
                    safe(taskId), safe(task.event().getEventId()), safe(task.event().getEventType()),
                    safe(task.event().getCorrelationId()));
        } catch (RuntimeException e) {
            String eventType = task != null && task.event() != null ? task.event().getEventType() : null;
            metrics.recordDispatcherEventDeferred(eventType, "publish_failure");
            try {
                eventRepository.deferDispatcherEvent(taskId, System.currentTimeMillis() + retryDelayMs);
            } catch (RuntimeException deferFailure) {
                LOG.error("Failed to defer dispatcher event outbox task; task remains discoverable at its prior score: task_id={} event_type={} error={}",
                        safe(taskId), safe(eventType), safe(deferFailure.getMessage()), deferFailure);
            }
            LOG.warn("Dispatcher event outbox publish deferred: task_id={} event_type={} error={}",
                    safe(taskId), safe(eventType), safe(e.getMessage()));
        } finally {
            if (!acknowledged) {
                try {
                    eventRepository.releaseDispatcherEventClaim(taskId, owner);
                } catch (RuntimeException releaseFailure) {
                    LOG.warn("Dispatcher event outbox claim release failed; lease will expire: task_id={} error={}",
                            safe(taskId), safe(releaseFailure.getMessage()));
                }
            }
        }
    }
}
