package io.runcycles.events.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.args.ListDirection;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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
    void claimPending_movesDeliveryToProcessingAndReturnsDeliveryId() {
        when(jedis.blmove("dispatch:pending", "dispatch:processing",
                ListDirection.RIGHT, ListDirection.LEFT, 5.0)).thenReturn("del-1");

        String result = repository.claimPending(5);

        assertThat(result).isEqualTo("del-1");
    }

    @Test
    void claimPending_timeout_returnsNull() {
        when(jedis.blmove("dispatch:pending", "dispatch:processing",
                ListDirection.RIGHT, ListDirection.LEFT, 5.0)).thenReturn(null);

        String result = repository.claimPending(5);

        assertThat(result).isNull();
    }

    @Test
    void ack_removesDeliveryFromProcessing() {
        repository.ack("del-1");

        verify(jedis).lrem("dispatch:processing", 1L, "del-1");
    }

    @Test
    void recoverProcessing_movesAllInFlightBackToPending() {
        when(jedis.lmove("dispatch:processing", "dispatch:pending",
                ListDirection.LEFT, ListDirection.RIGHT)).thenReturn("del-1", "del-2", null);

        long recovered = repository.recoverProcessing();

        assertThat(recovered).isEqualTo(2L);
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
        when(jedis.eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), eq(List.of("del-1"))))
                .thenReturn(1L);
        when(jedis.eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), eq(List.of("del-2"))))
                .thenReturn(1L);

        List<String> result = repository.popRetryReady(1700000000000L, 100);

        assertThat(result).containsExactly("del-1", "del-2");
        verify(jedis).eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), eq(List.of("del-1")));
        verify(jedis).eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), eq(List.of("del-2")));
    }

    @Test
    void popRetryReady_concurrentWorker_skipsAlreadyRemovedItems() {
        when(jedis.zrangeByScore("dispatch:retry", "-inf", "1700000000000", 0, 100))
                .thenReturn(Arrays.asList("del-1", "del-2"));
        when(jedis.eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), eq(List.of("del-1"))))
                .thenReturn(1L);
        when(jedis.eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), eq(List.of("del-2"))))
                .thenReturn(0L); // already removed by another worker

        List<String> result = repository.popRetryReady(1700000000000L, 100);

        assertThat(result).containsExactly("del-1");
        verify(jedis).eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), eq(List.of("del-1")));
        verify(jedis).eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), eq(List.of("del-2")));
    }

    @Test
    void popRetryReady_noReadyItems() {
        when(jedis.zrangeByScore("dispatch:retry", "-inf", "1700000000000", 0, 100))
                .thenReturn(Collections.emptyList());

        List<String> result = repository.popRetryReady(1700000000000L, 100);

        assertThat(result).isEmpty();
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }
}
