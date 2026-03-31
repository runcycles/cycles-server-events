package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@Repository
public class SubscriptionRepository {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionRepository.class);

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    public SubscriptionRepository(JedisPool jedisPool, ObjectMapper objectMapper) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
    }

    public Subscription findById(String subscriptionId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get("webhook:" + subscriptionId);
            if (data == null) return null;
            return objectMapper.readValue(data, Subscription.class);
        } catch (Exception e) {
            LOG.error("Failed to read subscription: {}", subscriptionId, e);
            return null;
        }
    }

    public String getSigningSecret(String subscriptionId) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get("webhook:secret:" + subscriptionId);
        }
    }

    public void update(Subscription sub) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(sub);
            jedis.set("webhook:" + sub.getSubscriptionId(), json);
        } catch (Exception e) {
            LOG.error("Failed to update subscription: {}", sub.getSubscriptionId(), e);
        }
    }
}
