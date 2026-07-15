package io.runcycles.events.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import redis.clients.jedis.JedisPool;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisConfigTest {

    @Test
    void jedisPool_createdWithoutPassword() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "host", "localhost");
        ReflectionTestUtils.setField(config, "port", 6379);
        ReflectionTestUtils.setField(config, "password", "");

        JedisPool pool = config.jedisPool();

        assertThat(pool).isNotNull();
        pool.close();
    }

    @Test
    void jedisPool_createdWithPassword() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "host", "localhost");
        ReflectionTestUtils.setField(config, "port", 6379);
        ReflectionTestUtils.setField(config, "password", "my-redis-password");

        JedisPool pool = config.jedisPool();

        assertThat(pool).isNotNull();
        pool.close();
    }

    @Test
    void jedisPool_supportsAclUsernameAndTlsConfiguration() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "host", "redis.example.com");
        ReflectionTestUtils.setField(config, "port", 6380);
        ReflectionTestUtils.setField(config, "username", "events-worker");
        ReflectionTestUtils.setField(config, "password", "secret");
        ReflectionTestUtils.setField(config, "tlsEnabled", true);

        JedisPool pool = config.jedisPool();

        assertThat(pool).isNotNull();
        pool.close();
    }

    @Test
    void invalidRedisPortFailsAtStartup() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "host", "localhost");
        ReflectionTestUtils.setField(config, "port", 0);
        ReflectionTestUtils.setField(config, "password", "");

        org.assertj.core.api.Assertions.assertThatThrownBy(config::jedisPool)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis.port");
    }

    @Test
    void blankAndNullRedisHostsFailAtStartup() {
        RedisConfig blank = validConfig();
        ReflectionTestUtils.setField(blank, "host", " ");
        assertThatThrownBy(blank::jedisPool)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis.host");

        RedisConfig missing = validConfig();
        ReflectionTestUtils.setField(missing, "host", null);
        assertThatThrownBy(missing::jedisPool)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis.host");
    }

    @Test
    void portAboveTcpRangeFailsAtStartup() {
        RedisConfig config = validConfig();
        ReflectionTestUtils.setField(config, "port", 65_536);

        assertThatThrownBy(config::jedisPool)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis.port");
    }

    @Test
    void ordinaryRedisTimeoutsMustBePositive() {
        RedisConfig noConnectTimeout = validConfig();
        ReflectionTestUtils.setField(noConnectTimeout, "connectTimeoutMs", 0);
        assertThatThrownBy(noConnectTimeout::jedisPool)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive");

        RedisConfig noSocketTimeout = validConfig();
        ReflectionTestUtils.setField(noSocketTimeout, "socketTimeoutMs", 0);
        assertThatThrownBy(noSocketTimeout::jedisPool)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void blockingCommandTimeoutsMustBePositive() {
        RedisConfig noDispatchTimeout = validConfig();
        ReflectionTestUtils.setField(noDispatchTimeout, "dispatchPendingTimeoutSeconds", 0);
        assertThatThrownBy(noDispatchTimeout::jedisPool)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must exceed");

        RedisConfig noEvidenceTimeout = validConfig();
        ReflectionTestUtils.setField(noEvidenceTimeout, "evidenceQueueTimeoutSeconds", 0);
        assertThatThrownBy(noEvidenceTimeout::jedisPool)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must exceed");
    }

    @Test
    void blockingSocketTimeoutMustBeFiniteAndLongerThanBlockingCommands() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "host", "localhost");
        ReflectionTestUtils.setField(config, "port", 6379);
        ReflectionTestUtils.setField(config, "blockingSocketTimeoutMs", 5_000);
        ReflectionTestUtils.setField(config, "dispatchPendingTimeoutSeconds", 5);

        org.assertj.core.api.Assertions.assertThatThrownBy(config::jedisPool)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must exceed");
    }

    @Test
    void zeroBlockingSocketTimeoutIsRejectedInsteadOfAllowingInfiniteRead() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "host", "localhost");
        ReflectionTestUtils.setField(config, "port", 6379);
        ReflectionTestUtils.setField(config, "blockingSocketTimeoutMs", 0);

        org.assertj.core.api.Assertions.assertThatThrownBy(config::jedisPool)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void objectMapper_configuredCorrectly() throws Exception {
        RedisConfig config = new RedisConfig();
        ObjectMapper mapper = config.objectMapper();

        assertThat(mapper).isNotNull();

        // Verify dates serialized as ISO-8601 strings, not timestamps
        Instant now = Instant.parse("2026-01-15T10:30:00Z");
        String json = mapper.writeValueAsString(now);
        assertThat(json).contains("2026-01-15");
        assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
    }

    private static RedisConfig validConfig() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "host", "localhost");
        ReflectionTestUtils.setField(config, "port", 6379);
        return config;
    }
}
