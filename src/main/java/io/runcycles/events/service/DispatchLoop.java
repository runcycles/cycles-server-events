package io.runcycles.events.service;

import static io.runcycles.events.logging.LogSanitizer.safe;

import io.runcycles.events.repository.DeliveryQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class DispatchLoop {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchLoop.class);

    private final DeliveryQueueRepository queueRepository;
    private final DeliveryHandler deliveryHandler;
    private final int timeoutSeconds;
    private final long orderingLeaseMs;
    private final long contentionBackoffMs;
    private volatile long nextOrderingAttemptNanos;

    public DispatchLoop(DeliveryQueueRepository queueRepository, DeliveryHandler deliveryHandler,
                        @Value("${dispatch.pending.timeout-seconds:5}") int timeoutSeconds,
                        @Value("${dispatch.ordering.lease-ms:120000}") long orderingLeaseMs,
                        @Value("${dispatch.http.timeout-seconds:30}") int httpTimeoutSeconds,
                        @Value("${dispatch.ordering.contention-backoff-ms:500}") long contentionBackoffMs) {
        this.queueRepository = queueRepository;
        this.deliveryHandler = deliveryHandler;
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("dispatch pending timeout must be positive");
        }
        if (httpTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("dispatch HTTP timeout must be positive");
        }
        if (contentionBackoffMs <= 0 || contentionBackoffMs > 60_000) {
            throw new IllegalArgumentException(
                    "dispatch ordering contention backoff must be between 1 and 60000 ms");
        }
        long minimumLeaseMs = (timeoutSeconds + (long) httpTimeoutSeconds) * 1_000L;
        if (orderingLeaseMs <= minimumLeaseMs) {
            throw new IllegalArgumentException(
                    "dispatch ordering lease must exceed pending plus HTTP timeouts");
        }
        this.timeoutSeconds = timeoutSeconds;
        this.orderingLeaseMs = orderingLeaseMs;
        this.contentionBackoffMs = contentionBackoffMs;
    }

    @Scheduled(fixedDelayString = "${dispatch.loop.delay-ms:25}")
    public void processNext() {
        if (System.nanoTime() < nextOrderingAttemptNanos) return;
        // A fresh token per invocation prevents an over-time invocation from
        // deleting a newer lease acquired by the same replica after expiry.
        String orderingOwner = UUID.randomUUID().toString();
        try {
            if (!queueRepository.tryAcquireOrderingLock(orderingOwner, orderingLeaseMs)) {
                deferOrderingAttempt();
                return;
            }
            nextOrderingAttemptNanos = 0;
            try {
                String deliveryId = queueRepository.claimPending(timeoutSeconds);
                if (deliveryId != null) {
                    deliveryHandler.handle(deliveryId);
                    queueRepository.ack(deliveryId);
                }
            } finally {
                queueRepository.releaseOrderingLock(orderingOwner);
            }
        } catch (JedisConnectionException e) {
            deferOrderingAttempt();
            LOG.warn("Dispatch loop Redis connection failure: queue=dispatch:pending processing_queue=dispatch:processing timeout_seconds={} error={}",
                    timeoutSeconds, safe(e.getMessage()));
        } catch (Exception e) {
            deferOrderingAttempt();
            LOG.error("Dispatch loop failed before ack: queue=dispatch:pending processing_queue=dispatch:processing timeout_seconds={} ordering_lease_ms={}",
                    timeoutSeconds, orderingLeaseMs, e);
        }
    }

    private void deferOrderingAttempt() {
        long minimum = Math.max(1L, contentionBackoffMs / 2L);
        long delayMs = ThreadLocalRandom.current().nextLong(minimum, contentionBackoffMs + 1L);
        nextOrderingAttemptNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
    }
}
