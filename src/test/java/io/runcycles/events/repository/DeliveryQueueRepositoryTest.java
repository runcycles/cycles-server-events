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
        verify(jedis).zadd(eq("dispatch:processing:claimed_at"), anyDouble(), eq("del-1"));
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

        verify(jedis).eval(anyString(),
                eq(List.of("dispatch:processing", "dispatch:processing:claimed_at")),
                eq(List.of("del-1")));
    }

    @Test
    void recoverStaleProcessing_marksUntrackedEntriesWithoutRequeueing() {
        when(jedis.lrange("dispatch:processing", 0, -1)).thenReturn(List.of("del-1"));
        when(jedis.zscore("dispatch:processing:claimed_at", "del-1")).thenReturn(null);

        long recovered = repository.recoverStaleProcessing(10_000L, 120_000L);

        assertThat(recovered).isZero();
        verify(jedis).zadd("dispatch:processing:claimed_at", 10_000L, "del-1");
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }

    @Test
    void recoverStaleProcessing_requeuesOnlyIdleEntries() {
        when(jedis.lrange("dispatch:processing", 0, -1)).thenReturn(List.of("fresh", "stale"));
        when(jedis.zscore("dispatch:processing:claimed_at", "fresh")).thenReturn(9_500.0);
        when(jedis.zscore("dispatch:processing:claimed_at", "stale")).thenReturn(1_000.0);
        when(jedis.eval(anyString(),
                eq(List.of("dispatch:processing", "dispatch:processing:claimed_at", "dispatch:pending")),
                eq(List.of("stale")))).thenReturn(1L);

        long recovered = repository.recoverStaleProcessing(10_000L, 5_000L);

        assertThat(recovered).isEqualTo(1L);
        verify(jedis, never()).eval(anyString(), anyList(), eq(List.of("fresh")));
        verify(jedis).eval(anyString(),
                eq(List.of("dispatch:processing", "dispatch:processing:claimed_at", "dispatch:pending")),
                eq(List.of("stale")));
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
