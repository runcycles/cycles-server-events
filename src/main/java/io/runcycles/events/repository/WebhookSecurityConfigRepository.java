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
 * <p>Failure posture (review-hardened): an ABSENT/blank key is a legitimate
 * state ("no config ever stored") and returns the built-in restrictive
 * defaults. The delivery boundary deliberately uses a hardened superset of the
 * historical admin fallback so an absent key cannot weaken egress filtering.
 * A read or parse FAILURE, however, is INDETERMINATE and throws: silently
 * substituting defaults here would let a transient Redis blip or corrupt value
 * permanently fail valid deliveries (the SSRF guard treats a violation as a
 * no-retry policy block). The caller leaves the delivery un-acked instead, and
 * stale-processing recovery retries it once the config is readable again.
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

    /**
     * @return the stored config, or the restrictive defaults when no config
     *         has ever been stored (absent/blank key)
     * @throws IllegalStateException when the config cannot be read or parsed
     *         — the current policy is unknown, which is NOT the same as
     *         "policy denies this URL"
     */
    public WebhookSecurityConfig get() {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get(CONFIG_KEY);
            if (data == null || data.isBlank()) {
                return WebhookSecurityConfig.builder().build();
            }
            return objectMapper.readValue(data, WebhookSecurityConfig.class);
        } catch (Exception e) {
            LOG.warn("Webhook security config indeterminate (read/parse failure): config_key={}",
                    CONFIG_KEY, e);
            throw new IllegalStateException("Webhook security config indeterminate", e);
        }
    }
}
