package io.runcycles.events.repository;

import static io.runcycles.events.logging.LogSanitizer.safe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.model.Delivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@Repository
public class DeliveryRepository {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryRepository.class);
    private static final String UPDATE_EXISTING_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'KEEPTTL')
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

    public void update(Delivery delivery) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(delivery);
            String key = "delivery:" + delivery.getDeliveryId();
            Object updated = jedis.eval(UPDATE_EXISTING_LUA,
                    java.util.List.of(key), java.util.List.of(json));
            if (!Long.valueOf(1L).equals(updated)) {
                LOG.warn("Webhook delivery disappeared before update; refusing to resurrect it: delivery_id={} status={}",
                        safe(delivery.getDeliveryId()), delivery.getStatus());
            }
        } catch (Exception e) {
            LOG.error("Failed to update webhook delivery: delivery_id={} event_id={} event_type={} subscription_id={} status={} attempts={} trace_id={}",
                    safe(delivery != null ? delivery.getDeliveryId() : null),
                    safe(delivery != null ? delivery.getEventId() : null),
                    safe(delivery != null ? delivery.getEventType() : null),
                    safe(delivery != null ? delivery.getSubscriptionId() : null),
                    delivery != null ? delivery.getStatus() : null,
                    delivery != null ? delivery.getAttempts() : null,
                    safe(delivery != null ? delivery.getTraceId() : null),
                    e);
            throw new IllegalStateException("Failed to update webhook delivery", e);
        }
    }

}
