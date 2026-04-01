package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.model.Delivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

@Repository
public class DeliveryRepository {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryRepository.class);

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
            LOG.error("Failed to read delivery: {}", deliveryId, e);
            return null;
        }
    }

    public void update(Delivery delivery) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(delivery);
            String key = "delivery:" + delivery.getDeliveryId();
            long ttl = jedis.ttl(key);
            if (ttl > 0) {
                jedis.set(key, json, SetParams.setParams().ex(ttl));
            } else {
                jedis.set(key, json);
            }
        } catch (Exception e) {
            LOG.error("Failed to update delivery: {}", delivery.getDeliveryId(), e);
        }
    }
}
