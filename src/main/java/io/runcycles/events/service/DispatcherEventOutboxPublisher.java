package io.runcycles.events.service;

import static io.runcycles.events.logging.LogSanitizer.safe;

import io.runcycles.events.metrics.CyclesMetrics;
import io.runcycles.events.model.DispatcherEventTask;
import io.runcycles.events.repository.EventRepository;
import io.runcycles.events.repository.EventRepository.OutboxFailureResult;
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
    private final int maxAttempts;
    private final int failedMaxLen;

    public DispatcherEventOutboxPublisher(
            EventRepository eventRepository,
            CyclesMetrics metrics,
            @Value("${dispatch.event-outbox.batch-size:25}") int batchSize,
            @Value("${dispatch.event-outbox.claim-lease-ms:30000}") long claimLeaseMs,
            @Value("${dispatch.event-outbox.retry-delay-ms:5000}") long retryDelayMs,
            @Value("${dispatch.event-outbox.max-attempts:100}") int maxAttempts,
            @Value("${dispatch.event-outbox.failed-max-len:10000}") int failedMaxLen) {
        if (batchSize <= 0 || claimLeaseMs <= 0 || retryDelayMs <= 0
                || maxAttempts <= 0 || failedMaxLen <= 0) {
            throw new IllegalArgumentException("dispatcher event outbox limits must be positive");
        }
        this.eventRepository = eventRepository;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.claimLeaseMs = claimLeaseMs;
        this.retryDelayMs = retryDelayMs;
        this.maxAttempts = maxAttempts;
        this.failedMaxLen = failedMaxLen;
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
        boolean claimResolved = false;
        try {
            task = eventRepository.findDispatcherEventTask(taskId);
            if (task == null) {
                // The inline publisher may have acknowledged it after this scan.
                claimResolved = eventRepository.ackClaimedDispatcherEvent(taskId, owner);
                LOG.debug("Cleaned dispatcher outbox index entry without a task payload: task_id={} claim_resolved={}",
                        safe(taskId), claimResolved);
                return;
            }
            eventRepository.save(task.event());
            claimResolved = eventRepository.ackClaimedDispatcherEvent(taskId, owner);
            if (!claimResolved) {
                // The lease expired and a successor completed the same
                // deterministic task. The event save is idempotent; do not
                // recreate or count the task again.
                return;
            }
            metrics.recordDispatcherEventPublished(task.event().getEventType());
            LOG.info("Published durable dispatcher event: task_id={} event_id={} event_type={} correlation_id={}",
                    safe(taskId), safe(task.event().getEventId()), safe(task.event().getEventType()),
                    safe(task.event().getCorrelationId()));
        } catch (RuntimeException e) {
            String eventType = task != null && task.event() != null ? task.event().getEventType() : null;
            boolean deadLettered = false;
            boolean deferred = false;
            try {
                OutboxFailureResult failure = eventRepository.recordDispatcherEventFailure(
                        taskId, owner, System.currentTimeMillis() + retryDelayMs,
                        maxAttempts, failedMaxLen);
                claimResolved = failure.claimResolved();
                if (failure.deadLettered()) {
                    deadLettered = true;
                    metrics.recordDispatcherEventDeadLettered(eventType, "attempts_exhausted");
                    LOG.error("Dispatcher event outbox task dead-lettered after bounded retries: task_id={} event_type={} attempts={} failed_queue={}",
                            safe(taskId), safe(eventType), failure.attempts(),
                            EventRepository.DISPATCHER_OUTBOX_FAILED_KEY);
                } else if (failure.claimResolved()) {
                    deferred = true;
                }
            } catch (RuntimeException deferFailure) {
                deferred = true;
                LOG.error("Failed to defer dispatcher event outbox task; task remains discoverable at its prior score: task_id={} event_type={} error={}",
                        safe(taskId), safe(eventType), safe(deferFailure.getMessage()), deferFailure);
            }
            if (deferred) {
                metrics.recordDispatcherEventDeferred(eventType, "publish_failure");
                LOG.warn("Dispatcher event outbox publish deferred: task_id={} event_type={} error={}",
                        safe(taskId), safe(eventType), safe(e.getMessage()));
            } else if (!deadLettered) {
                LOG.debug("Dispatcher event outbox publish result discarded after claim loss: task_id={} event_type={}",
                        safe(taskId), safe(eventType));
            }
        } finally {
            if (!claimResolved) {
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
