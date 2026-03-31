package io.runcycles.events.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
class DeliveryQueueRepositoryTest {

    @Mock
    private JedisPool jedisPool;
    @Mock
    private Jedis jedis;

    private DeliveryQueueRepository repository;

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        repository = new DeliveryQueueRepository(jedisPool);
    }

    @Test
    void popPending_returnsDeliveryId() {
        when(jedis.brpop(5, "dispatch:pending")).thenReturn(Arrays.asList("dispatch:pending", "del-1"));

        String result = repository.popPending(5);

        assertThat(result).isEqualTo("del-1");
    }

    @Test
    void popPending_timeout_returnsNull() {
        when(jedis.brpop(5, "dispatch:pending")).thenReturn(null);

        String result = repository.popPending(5);

        assertThat(result).isNull();
    }

    @Test
    void popPending_emptyList_returnsNull() {
        when(jedis.brpop(5, "dispatch:pending")).thenReturn(Collections.emptyList());

        String result = repository.popPending(5);

        assertThat(result).isNull();
    }

    @Test
    void popPending_singleElement_returnsNull() {
        when(jedis.brpop(5, "dispatch:pending")).thenReturn(Collections.singletonList("dispatch:pending"));

        String result = repository.popPending(5);

        assertThat(result).isNull();
    }

    @Test
    void scheduleRetry() {
        repository.scheduleRetry("del-1", 1700000000000L);

        verify(jedis).zadd("dispatch:retry", 1700000000000L, "del-1");
    }

    @Test
    void popRetryReady_withReadyItems() {
        when(jedis.zrangeByScore("dispatch:retry", "-inf", "1700000000000", 0, 100))
                .thenReturn(Arrays.asList("del-1", "del-2"));

        List<String> result = repository.popRetryReady(1700000000000L, 100);

        assertThat(result).containsExactly("del-1", "del-2");
        verify(jedis).zrem("dispatch:retry", "del-1");
        verify(jedis).zrem("dispatch:retry", "del-2");
        verify(jedis).lpush("dispatch:pending", "del-1");
        verify(jedis).lpush("dispatch:pending", "del-2");
    }

    @Test
    void popRetryReady_noReadyItems() {
        when(jedis.zrangeByScore("dispatch:retry", "-inf", "1700000000000", 0, 100))
                .thenReturn(Collections.emptyList());

        List<String> result = repository.popRetryReady(1700000000000L, 100);

        assertThat(result).isEmpty();
        verify(jedis, never()).zrem(anyString(), anyString());
        verify(jedis, never()).lpush(anyString(), anyString());
    }
}
