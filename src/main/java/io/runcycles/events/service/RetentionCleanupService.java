package io.runcycles.events.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.Set;

/**
 * Periodic cleanup of expired ZSET index entries for events and deliveries.
 * STRING keys (event:{id}, delivery:{id}) are handled by Redis TTL/EXPIRE.
 * ZSET members (events:*, deliveries:*) need explicit trimming since ZSET
 * members don't support per-member TTL.
 */
@Service
public class RetentionCleanupService {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionCleanupService.class);

    private final JedisPool jedisPool;
    private final long eventTtlMs;
    private final long deliveryTtlMs;

    public RetentionCleanupService(JedisPool jedisPool,
            @Value("${events.retention.event-ttl-days:90}") int eventTtlDays,
            @Value("${events.retention.delivery-ttl-days:14}") int deliveryTtlDays) {
        this.jedisPool = jedisPool;
        this.eventTtlMs = eventTtlDays * 86400000L;
        this.deliveryTtlMs = deliveryTtlDays * 86400000L;
    }

    @Scheduled(fixedRateString = "${events.retention.cleanup-interval-ms:3600000}")
    public void cleanup() {
        try {
            long now = System.currentTimeMillis();
            long eventCutoff = now - eventTtlMs;
            long deliveryCutoff = now - deliveryTtlMs;

            try (Jedis jedis = jedisPool.getResource()) {
                // Trim global event index
                long removedAll = jedis.zremrangeByScore("events:_all", "-inf", String.valueOf(eventCutoff));
                if (removedAll > 0) {
                    LOG.info("Cleaned {} expired entries from events:_all", removedAll);
                }

                // Trim per-tenant event indexes (scan for events:* keys)
                trimZsetsByPattern(jedis, "events:*", eventCutoff, "events:_all");

                // Trim per-subscription delivery indexes
                trimZsetsByPattern(jedis, "deliveries:*", deliveryCutoff, null);
            }
        } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
            LOG.warn("Redis connection error in retention cleanup: {}", e.getMessage());
        } catch (Exception e) {
            LOG.error("Error in retention cleanup", e);
        }
    }

    private void trimZsetsByPattern(Jedis jedis, String pattern, long cutoffMs, String skipKey) {
        String cursor = "0";
        ScanParams params = new ScanParams().match(pattern).count(100);
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            for (String key : result.getResult()) {
                if (key.equals(skipKey)) continue;
                long removed = jedis.zremrangeByScore(key, "-inf", String.valueOf(cutoffMs));
                if (removed > 0) {
                    LOG.debug("Cleaned {} expired entries from {}", removed, key);
                }
            }
            cursor = result.getCursor();
        } while (!"0".equals(cursor));
    }
}
