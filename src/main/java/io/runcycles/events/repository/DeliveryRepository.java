package io.runcycles.events.repository;

import static io.runcycles.events.logging.LogSanitizer.safe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.DeliveryStatus;
import io.runcycles.events.repository.DeliveryQueueRepository.ClaimedDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@Repository
public class DeliveryRepository {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryRepository.class);
    private static final String UPDATE_OWNED_LUA = """
            if redis.call('HGET', KEYS[2], ARGV[2]) ~= ARGV[3] then return -1 end
            local current = redis.call('GET', KEYS[1])
            if not current then return -2 end
            local decoded = cjson.decode(current)
            local status = decoded['status']
            if status == 'SUCCESS' or status == 'FAILED' then return -3 end
            if status ~= 'PENDING' and status ~= 'RETRYING' then return -4 end
            redis.call('SET', KEYS[1], ARGV[1], 'KEEPTTL')
            return 1
            """;

    private static final String UPDATE_OWNED_AND_SCHEDULE_RETRY_LUA = """
            if redis.call('HGET', KEYS[2], ARGV[2]) ~= ARGV[3] then return -1 end
            local current = redis.call('GET', KEYS[1])
            if not current then return -2 end
            local decoded = cjson.decode(current)
            local status = decoded['status']
            if status == 'SUCCESS' or status == 'FAILED' then return -3 end
            if status ~= 'PENDING' and status ~= 'RETRYING' then return -4 end
            redis.call('SET', KEYS[1], ARGV[1], 'KEEPTTL')
            redis.call('ZADD', KEYS[3], ARGV[4], ARGV[2])
            return 1
            """;

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    public DeliveryRepository(JedisPool jedisPool, ObjectMapper objectMapper) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
    }

    public Delivery findById(String deliveryId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get("delivery:" + deliveryId);
            if (data == null) return null;
            return objectMapper.readValue(data, Delivery.class);
        } catch (Exception e) {
            LOG.error("Failed to read webhook delivery: delivery_id={}", safe(deliveryId), e);
            throw new IllegalStateException("Failed to read webhook delivery", e);
        }
    }

    /**
     * Persist a non-terminal-to-terminal delivery transition only while the
     * caller still owns the processing claim. The owner check and write are one
     * Redis operation, so stale workers cannot overwrite a recovered successor.
     *
     * @return {@code true} only when this claim applied the transition
     */
    public boolean updateOwned(Delivery delivery, ClaimedDelivery claim) {
        requireOwnedInputs(delivery, claim);
        if (delivery.getStatus() != DeliveryStatus.FAILED) {
            throw new IllegalArgumentException("owned terminal update requires FAILED status");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(delivery);
            String key = "delivery:" + delivery.getDeliveryId();
            Object updated = jedis.eval(UPDATE_OWNED_LUA,
                    java.util.List.of(key, DeliveryQueueRepository.PROCESSING_CLAIM_OWNER_KEY),
                    java.util.List.of(json, claim.deliveryId(), claim.claimToken()));
            if (Long.valueOf(-4L).equals(updated)) {
                throw new IllegalStateException("stored webhook delivery has an invalid status");
            }
            if (!Long.valueOf(1L).equals(updated)) {
                LOG.warn("Webhook delivery transition rejected because the claim was superseded, the record disappeared, or its state was already terminal: delivery_id={} status={} result={}",
                        safe(delivery.getDeliveryId()), delivery.getStatus(), updated);
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.error("Failed to update owned webhook delivery: delivery_id={} event_id={} event_type={} subscription_id={} status={} attempts={} trace_id={}",
                    safe(delivery.getDeliveryId()), safe(delivery.getEventId()),
                    safe(delivery.getEventType()), safe(delivery.getSubscriptionId()),
                    delivery.getStatus(), delivery.getAttempts(), safe(delivery.getTraceId()),
                    e);
            throw new IllegalStateException("Failed to update owned webhook delivery", e);
        }
    }

    /** Atomically persist RETRYING and place the same owned delivery in the retry ZSET. */
    public boolean updateOwnedAndScheduleRetry(Delivery delivery, ClaimedDelivery claim,
                                               long nextRetryAtMillis) {
        requireOwnedInputs(delivery, claim);
        if (delivery.getStatus() != DeliveryStatus.RETRYING) {
            throw new IllegalArgumentException("owned retry update requires RETRYING status");
        }
        if (nextRetryAtMillis < 0) {
            throw new IllegalArgumentException("next retry time must be non-negative");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(delivery);
            Object updated = jedis.eval(UPDATE_OWNED_AND_SCHEDULE_RETRY_LUA,
                    java.util.List.of("delivery:" + delivery.getDeliveryId(),
                            DeliveryQueueRepository.PROCESSING_CLAIM_OWNER_KEY,
                            DeliveryQueueRepository.RETRY_KEY),
                    java.util.List.of(json, claim.deliveryId(), claim.claimToken(),
                            Long.toString(nextRetryAtMillis)));
            if (Long.valueOf(-4L).equals(updated)) {
                throw new IllegalStateException("stored webhook delivery has an invalid status");
            }
            if (!Long.valueOf(1L).equals(updated)) {
                LOG.warn("Webhook retry transition rejected because the claim was superseded, the record disappeared, or its state was already terminal: delivery_id={} result={}",
                        safe(delivery.getDeliveryId()), updated);
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.error("Failed to atomically persist and schedule owned webhook retry: delivery_id={} event_id={} subscription_id={} next_retry_at_ms={}",
                    safe(delivery.getDeliveryId()), safe(delivery.getEventId()),
                    safe(delivery.getSubscriptionId()), nextRetryAtMillis, e);
            throw new IllegalStateException("Failed to persist owned webhook retry", e);
        }
    }

    private static void requireOwnedInputs(Delivery delivery, ClaimedDelivery claim) {
        java.util.Objects.requireNonNull(delivery, "delivery is required");
        java.util.Objects.requireNonNull(claim, "delivery claim is required");
        if (delivery.getDeliveryId() == null || delivery.getDeliveryId().isBlank()
                || !delivery.getDeliveryId().equals(claim.deliveryId())) {
            throw new IllegalArgumentException("matching delivery and owned claim are required");
        }
    }

}
