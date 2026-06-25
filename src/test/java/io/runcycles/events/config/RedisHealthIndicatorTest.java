package io.runcycles.events.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisHealthIndicatorTest {

    private final JedisPool jedisPool = mock(JedisPool.class);
    private final Jedis jedis = mock(Jedis.class);
    private final RedisHealthIndicator indicator = new RedisHealthIndicator(jedisPool);

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
    }

    @Test
    void health_upWhenPingReturnsPong() {
        when(jedis.ping()).thenReturn("PONG");

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void health_downWhenPingReturnsUnexpectedValue() {
        when(jedis.ping()).thenReturn("NOPE");

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void health_downWhenRedisThrows() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("redis down"));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
