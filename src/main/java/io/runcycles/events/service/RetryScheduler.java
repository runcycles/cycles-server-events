package io.runcycles.events.service;

import io.runcycles.events.repository.DeliveryQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.List;

@Service
public class RetryScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(RetryScheduler.class);

    private final DeliveryQueueRepository queueRepository;

    public RetryScheduler(DeliveryQueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    @Scheduled(fixedRateString = "${dispatch.retry.poll-interval-ms:5000}")
    public void processRetries() {
        try {
            List<String> requeued = queueRepository.popRetryReady(System.currentTimeMillis(), 100);
            if (!requeued.isEmpty()) {
                LOG.info("Requeued {} deliveries for retry", requeued.size());
            }
        } catch (JedisConnectionException e) {
            LOG.warn("Redis connection error in retry scheduler: {}", e.getMessage());
        } catch (Exception e) {
            LOG.error("Error in retry scheduler", e);
        }
    }
}
