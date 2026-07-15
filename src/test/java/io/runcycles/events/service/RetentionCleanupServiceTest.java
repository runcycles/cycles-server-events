package io.runcycles.events.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class RetentionCleanupServiceTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;

    private RetentionCleanupService service;

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        lenient().when(jedis.type(anyString())).thenReturn("zset");
        lenient().when(jedis.set(eq("maintenance:events-retention:lock"), anyString(),
                any(redis.clients.jedis.params.SetParams.class))).thenReturn("OK");
        service = new RetentionCleanupService(jedisPool, 90, 14, 300_000L);
    }

    @Test
    void cleanup_trimsGlobalEventIndex() {
        // SCAN returns no keys (just events:_all trimmed directly)
        when(jedis.zremrangeByScore(eq("events:_all"), eq("-inf"), anyString())).thenReturn(5L);
        ScanResult<String> emptyResult = new ScanResult<>("0", List.of());
        when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(emptyResult);

        service.cleanup();

        verify(jedis).zremrangeByScore(eq("events:_all"), eq("-inf"), anyString());
    }

    @Test
    void cleanup_trimsTenantEventIndex() {
        when(jedis.zremrangeByScore(eq("events:_all"), eq("-inf"), anyString())).thenReturn(0L);
        // SCAN for events:* returns tenant key
        ScanResult<String> eventsScan = new ScanResult<>("0", List.of("events:tenant-1"));
        when(jedis.scan(eq("0"), argThat(p -> true)))
                .thenReturn(eventsScan)        // first scan for events:*
                .thenReturn(new ScanResult<>("0", List.of())); // second scan for deliveries:*
        when(jedis.zremrangeByScore(eq("events:tenant-1"), eq("-inf"), anyString())).thenReturn(3L);

        service.cleanup();

        verify(jedis).zremrangeByScore(eq("events:tenant-1"), eq("-inf"), anyString());
    }

    @Test
    void cleanup_skipsNonZsetKeysMatchedByBroadPatterns() {
        when(jedis.zremrangeByScore(eq("events:_all"), eq("-inf"), anyString())).thenReturn(0L);
        when(jedis.scan(eq("0"), any(ScanParams.class)))
                .thenReturn(new ScanResult<>("0", List.of("events:tenant-1", "events:correlation:cid-1")))
                .thenReturn(new ScanResult<>("0", List.of("deliveries:sub-1")));
        when(jedis.type("events:correlation:cid-1")).thenReturn("set");
        when(jedis.zremrangeByScore(eq("events:tenant-1"), eq("-inf"), anyString())).thenReturn(3L);
        when(jedis.zremrangeByScore(eq("deliveries:sub-1"), eq("-inf"), anyString())).thenReturn(2L);

        service.cleanup();

        verify(jedis).zremrangeByScore(eq("events:tenant-1"), eq("-inf"), anyString());
        verify(jedis, never()).zremrangeByScore(eq("events:correlation:cid-1"), eq("-inf"), anyString());
        verify(jedis).zremrangeByScore(eq("deliveries:sub-1"), eq("-inf"), anyString());
    }

    @Test
    void cleanup_wrongTypeRaceDoesNotAbortRemainingKeys() {
        when(jedis.zremrangeByScore(eq("events:_all"), eq("-inf"), anyString())).thenReturn(0L);
        when(jedis.scan(eq("0"), any(ScanParams.class)))
                .thenReturn(new ScanResult<>("0", List.of("events:tenant-1")))
                .thenReturn(new ScanResult<>("0", List.of("deliveries:sub-1")));
        when(jedis.zremrangeByScore(eq("events:tenant-1"), eq("-inf"), anyString()))
                .thenThrow(new JedisDataException("WRONGTYPE Operation against a key holding the wrong kind of value"));
        when(jedis.zremrangeByScore(eq("deliveries:sub-1"), eq("-inf"), anyString())).thenReturn(2L);

        service.cleanup();

        verify(jedis).zremrangeByScore(eq("deliveries:sub-1"), eq("-inf"), anyString());
    }

    @Test
    void cleanup_exception_doesNotThrow() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Redis down"));

        // Should not throw
        service.cleanup();
    }

    @Test
    void cleanup_connectionExceptionUsesAvailabilityFailurePath() {
        when(jedisPool.getResource()).thenThrow(
                new redis.clients.jedis.exceptions.JedisConnectionException("Redis down"));

        service.cleanup();
    }

    @Test
    void cleanup_standbyReplicaSkipsWhenLeaseIsOwnedElsewhere() {
        when(jedis.set(eq("maintenance:events-retention:lock"), anyString(),
                any(redis.clients.jedis.params.SetParams.class))).thenReturn(null);

        service.cleanup();

        verify(jedis, never()).zremrangeByScore(anyString(), anyString(), anyString());
    }

    @Test
    void cleanupReleasesOnlyItsPerRunLeaseToken() {
        when(jedis.scan(eq("0"), any(ScanParams.class)))
                .thenReturn(new ScanResult<>("0", List.of()));

        service.cleanup();

        org.mockito.ArgumentCaptor<String> token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jedis).set(eq("maintenance:events-retention:lock"), token.capture(),
                any(redis.clients.jedis.params.SetParams.class));
        verify(jedis).eval(anyString(), eq(List.of("maintenance:events-retention:lock")),
                eq(List.of(token.getValue())));
    }

    @Test
    void constructorRejectsInvalidRetentionConfiguration() {
        assertThatThrownBy(() -> new RetentionCleanupService(jedisPool, 0, 14, 300_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionCleanupService(jedisPool, 90, 0, 300_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionCleanupService(jedisPool, 90, 14, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cleanupSkipsGlobalIndexDuringPatternScanAndFollowsCursor() {
        when(jedis.scan(eq("0"), any(ScanParams.class)))
                .thenReturn(new ScanResult<>("next", List.of("events:_all", "events:tenant-1")))
                .thenReturn(new ScanResult<>("0", List.of()));
        when(jedis.scan(eq("next"), any(ScanParams.class)))
                .thenReturn(new ScanResult<>("0", List.of("events:tenant-2")));

        service.cleanup();

        verify(jedis, times(1)).zremrangeByScore(eq("events:_all"), eq("-inf"), anyString());
        verify(jedis).zremrangeByScore(eq("events:tenant-1"), eq("-inf"), anyString());
        verify(jedis).zremrangeByScore(eq("events:tenant-2"), eq("-inf"), anyString());
        verify(jedis).scan(eq("next"), any(ScanParams.class));
    }

    @Test
    void cleanupHandlesNullAndNonWrongTypeDataErrorsThroughOuterBoundary() {
        when(jedis.scan(eq("0"), any(ScanParams.class)))
                .thenReturn(new ScanResult<>("0", List.of("events:null-message")))
                .thenReturn(new ScanResult<>("0", List.of()));
        when(jedis.zremrangeByScore(eq("events:null-message"), eq("-inf"), anyString()))
                .thenThrow(new JedisDataException((String) null));
        service.cleanup();

        reset(jedis);
        when(jedis.set(eq("maintenance:events-retention:lock"), anyString(),
                any(redis.clients.jedis.params.SetParams.class))).thenReturn("OK");
        when(jedis.type(anyString())).thenReturn("zset");
        when(jedis.scan(eq("0"), any(ScanParams.class)))
                .thenReturn(new ScanResult<>("0", List.of("events:other-error")))
                .thenReturn(new ScanResult<>("0", List.of()));
        when(jedis.zremrangeByScore(eq("events:other-error"), eq("-inf"), anyString()))
                .thenThrow(new JedisDataException("BUSY Redis is busy"));
        service.cleanup();
    }
}
