package io.runcycles.events.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetentionCleanupServiceTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;

    private RetentionCleanupService service;

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        service = new RetentionCleanupService(jedisPool, 90, 14);
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
    void cleanup_exception_doesNotThrow() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Redis down"));

        // Should not throw
        service.cleanup();
    }
}
