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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void orderingLockUsesOwnedLeaseAndCompareDeleteRelease() {
        when(jedis.set(eq("dispatch:ordering:lock"), eq("owner-1"),
                any(redis.clients.jedis.params.SetParams.class))).thenReturn("OK");

        assertThat(repository.tryAcquireOrderingLock("owner-1", 60_000)).isTrue();
        repository.releaseOrderingLock("owner-1");

        verify(jedis).set(eq("dispatch:ordering:lock"), eq("owner-1"),
                any(redis.clients.jedis.params.SetParams.class));
        verify(jedis).eval(anyString(), eq(List.of("dispatch:ordering:lock")), eq(List.of("owner-1")));
    }

    @Test
    void orderingLockRejectsInvalidOwnerAndLease() {
        assertThatThrownBy(() -> repository.tryAcquireOrderingLock("", 60_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.tryAcquireOrderingLock("owner-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jedisPool, never()).getResource();
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
        when(jedis.eval(anyString(),
                eq(List.of("dispatch:processing", "dispatch:processing:claimed_at", "dispatch:pending")),
                eq(List.of("1000", "10000", "-110000")))).thenReturn(0L);

        long recovered = repository.recoverStaleProcessing(10_000L, 120_000L);

        assertThat(recovered).isZero();
    }

    @Test
    void recoverStaleProcessing_requeuesOnlyIdleEntries() {
        when(jedis.eval(anyString(),
                eq(List.of("dispatch:processing", "dispatch:processing:claimed_at", "dispatch:pending")),
                eq(List.of("1000", "10000", "5000")))).thenReturn(1L);

        long recovered = repository.recoverStaleProcessing(10_000L, 5_000L);

        assertThat(recovered).isEqualTo(1L);
    }

    @Test
    void scheduleRetry() {
        repository.scheduleRetry("del-1", 1700000000000L);

        verify(jedis).zadd("dispatch:retry", 1700000000000L, "del-1");
    }

    @Test
    void popRetryReady_withReadyItems() {
        when(jedis.eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")),
                eq(List.of("1700000000000", "100"))))
                .thenReturn(Arrays.asList("del-1", "del-2"));

        List<String> result = repository.popRetryReady(1700000000000L, 100);

        assertThat(result).containsExactly("del-1", "del-2");
        verify(jedis).eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")),
                eq(List.of("1700000000000", "100")));
    }

    @Test
    void popRetryReady_decodesBinaryLuaResults() {
        when(jedis.eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), anyList()))
                .thenReturn(Arrays.asList("del-1".getBytes(java.nio.charset.StandardCharsets.UTF_8), "del-2"));

        List<String> result = repository.popRetryReady(1700000000000L, 100);

        assertThat(result).containsExactly("del-1", "del-2");
    }

    @Test
    void popRetryReady_noReadyItems() {
        when(jedis.eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), anyList()))
                .thenReturn(Collections.emptyList());

        List<String> result = repository.popRetryReady(1700000000000L, 100);

        assertThat(result).isEmpty();
        verify(jedis).eval(anyString(), eq(List.of("dispatch:retry", "dispatch:pending")), anyList());
    }
}
