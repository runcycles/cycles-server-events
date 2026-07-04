package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.model.WebhookSecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Read-only view of the admin plane's webhook security config
 * ({@code config:webhook-security} — written by
 * {@code PUT /v1/admin/config/webhook-security} on cycles-server-admin).
 *
 * <p>Failure posture: absent key OR any read/parse failure returns the
 * built-in defaults (private ranges blocked, HTTPS required). Defaults are
 * the restrictive baseline, so a transient Redis blip or a corrupt config
 * value can only tighten delivery-time checks, never loosen them — and a
 * corrupt config must not poison the dispatch loop the way a throwing
 * reader would.
 */
@Repository
public class WebhookSecurityConfigRepository {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookSecurityConfigRepository.class);
    static final String CONFIG_KEY = "config:webhook-security";

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    public WebhookSecurityConfigRepository(JedisPool jedisPool, ObjectMapper objectMapper) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
    }

    public WebhookSecurityConfig get() {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get(CONFIG_KEY);
            if (data == null || data.isBlank()) {
                return WebhookSecurityConfig.builder().build();
            }
            return objectMapper.readValue(data, WebhookSecurityConfig.class);
        } catch (Exception e) {
            LOG.warn("Failed to read webhook security config; using restrictive defaults: config_key={}",
                    CONFIG_KEY, e);
            return WebhookSecurityConfig.builder().build();
        }
    }
}
