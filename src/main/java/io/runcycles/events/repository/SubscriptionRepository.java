package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.runcycles.events.config.CryptoService;
import io.runcycles.events.model.Subscription;
import io.runcycles.events.model.WebhookStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;

@Repository
public class SubscriptionRepository {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionRepository.class);

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;
    private final CryptoService cryptoService;

    public SubscriptionRepository(JedisPool jedisPool, ObjectMapper objectMapper, CryptoService cryptoService) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
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
            String encrypted = jedis.get("webhook:secret:" + subscriptionId);
            return cryptoService.decrypt(encrypted);
        } catch (Exception e) {
            LOG.error("Failed to read signing secret: {}", subscriptionId, e);
            return null;
        }
    }

    /**
     * Partial update: re-reads the subscription from Redis, merges only the
     * operational fields that the events service modifies, and writes back.
     * This prevents overwriting admin config changes (url, headers, event_types, etc.)
     * that may have been applied between our initial read and this write.
     */
    public void updateDeliveryState(String subscriptionId, int consecutiveFailures,
                                     Instant lastTriggeredAt, Instant lastSuccessAt,
                                     Instant lastFailureAt, WebhookStatus status) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "webhook:" + subscriptionId;
            String existing = jedis.get(key);
            if (existing == null) {
                LOG.warn("Subscription {} not found during partial update", subscriptionId);
                return;
            }
            ObjectNode node = (ObjectNode) objectMapper.readTree(existing);
            node.put("consecutive_failures", consecutiveFailures);
            if (lastTriggeredAt != null) {
                node.put("last_triggered_at", lastTriggeredAt.toString());
            }
            if (lastSuccessAt != null) {
                node.put("last_success_at", lastSuccessAt.toString());
            }
            if (lastFailureAt != null) {
                node.put("last_failure_at", lastFailureAt.toString());
            }
            if (status != null) {
                node.put("status", status.name());
            }
            jedis.set(key, objectMapper.writeValueAsString(node));
        } catch (Exception e) {
            LOG.error("Failed to update delivery state for subscription: {}", subscriptionId, e);
        }
    }
}
