package io.runcycles.events.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import redis.clients.jedis.JedisPool;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

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
}
