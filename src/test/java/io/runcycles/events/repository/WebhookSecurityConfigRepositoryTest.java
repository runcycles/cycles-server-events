package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.model.WebhookSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookSecurityConfigRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;

    private WebhookSecurityConfigRepository repository;

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        repository = new WebhookSecurityConfigRepository(jedisPool, new ObjectMapper());
    }

    @Test
    void absentKey_returnsRestrictiveDefaults() {
        when(jedis.get("config:webhook-security")).thenReturn(null);

        WebhookSecurityConfig config = repository.get();

        assertThat(config.getAllowHttp()).isFalse();
        assertThat(config.getBlockedCidrRanges()).contains("127.0.0.0/8", "10.0.0.0/8", "::1/128");
        assertThat(config.getAllowedUrlPatterns()).isNull();
    }

    @Test
    void storedConfig_parsed() {
        when(jedis.get("config:webhook-security"))
                .thenReturn("{\"allow_http\":true,\"blocked_cidr_ranges\":[],\"allowed_url_patterns\":[\"https://*.example.com/*\"]}");

        WebhookSecurityConfig config = repository.get();

        assertThat(config.getAllowHttp()).isTrue();
        assertThat(config.getBlockedCidrRanges()).isEmpty();
        assertThat(config.getAllowedUrlPatterns()).containsExactly("https://*.example.com/*");
    }

    @Test
    void unknownFieldFromNewerAdmin_toleratedNotFatal() {
        when(jedis.get("config:webhook-security"))
                .thenReturn("{\"allow_http\":true,\"blocked_cidr_ranges\":[],\"future_field\":123}");

        WebhookSecurityConfig config = repository.get();

        assertThat(config.getAllowHttp()).isTrue();
    }

    @Test
    void corruptValue_fallsBackToRestrictiveDefaults() {
        when(jedis.get("config:webhook-security")).thenReturn("{not json");

        WebhookSecurityConfig config = repository.get();

        assertThat(config.getAllowHttp()).isFalse();
        assertThat(config.getBlockedCidrRanges()).isNotEmpty();
    }

    @Test
    void redisFailure_fallsBackToRestrictiveDefaults() {
        when(jedisPool.getResource()).thenThrow(new IllegalStateException("pool exhausted"));

        WebhookSecurityConfig config = repository.get();

        assertThat(config.getAllowHttp()).isFalse();
        assertThat(config.getBlockedCidrRanges()).isNotEmpty();
    }

    @Test
    void defaults_matchAdminPlaneDefaults() {
        // The two ends must agree on the absent-config baseline, or a URL
        // admitted at create time could be blocked at delivery time (or
        // vice versa) with no config present.
        WebhookSecurityConfig config = WebhookSecurityConfig.builder().build();
        assertThat(config.getBlockedCidrRanges()).containsExactly(
                "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                "127.0.0.0/8", "169.254.0.0/16", "::1/128", "fc00::/7");
        assertThat(config.getAllowHttp()).isFalse();
    }

    @Test
    void blankValue_returnsDefaults() {
        when(jedis.get("config:webhook-security")).thenReturn("  ");

        WebhookSecurityConfig config = repository.get();

        assertThat(config.getAllowHttp()).isFalse();
    }
}
