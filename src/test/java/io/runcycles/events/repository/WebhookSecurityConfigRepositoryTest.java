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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void corruptValue_throwsIndeterminate_notSilentDefaults() {
        // Review finding: substituting restrictive defaults on a read/parse
        // failure let a corrupt value permanently fail valid deliveries via
        // the guard's no-retry policy block. Indeterminate must THROW so the
        // delivery is retried instead.
        when(jedis.get("config:webhook-security")).thenReturn("{not json");

        assertThatThrownBy(() -> repository.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("indeterminate");
    }

    @Test
    void redisFailure_throwsIndeterminate_notSilentDefaults() {
        when(jedisPool.getResource()).thenThrow(new IllegalStateException("pool exhausted"));

        assertThatThrownBy(() -> repository.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("indeterminate");
    }

    @Test
    void absentConfigUsesHardenedDeliveryDefaults() {
        // Delivery is the final SSRF enforcement boundary. Its absent-config
        // fallback includes special-use ranges beyond the historical admin
        // defaults so an absent config cannot weaken egress policy.
        WebhookSecurityConfig config = WebhookSecurityConfig.builder().build();
        assertThat(config.getBlockedCidrRanges()).containsExactly(
                "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10",
                "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
                "169.254.0.0/16", "::/128", "::1/128", "fe80::/10", "fc00::/7");
        assertThat(config.getAllowHttp()).isFalse();
    }

    @Test
    void blankValue_returnsDefaults() {
        when(jedis.get("config:webhook-security")).thenReturn("  ");

        WebhookSecurityConfig config = repository.get();

        assertThat(config.getAllowHttp()).isFalse();
    }
}
