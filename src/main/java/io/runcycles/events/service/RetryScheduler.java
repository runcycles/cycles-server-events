package io.runcycles.events.service;

import io.runcycles.events.repository.DeliveryQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.List;

@Service
public class RetryScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(RetryScheduler.class);

    private final DeliveryQueueRepository queueRepository;
    private final int batchSize;

    public RetryScheduler(DeliveryQueueRepository queueRepository,
                          @Value("${dispatch.retry.batch-size:100}") int batchSize) {
        this.queueRepository = queueRepository;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedRateString = "${dispatch.retry.poll-interval-ms:5000}")
    public void processRetries() {
        try {
            List<String> requeued = queueRepository.popRetryReady(System.currentTimeMillis(), batchSize);
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
