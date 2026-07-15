package io.runcycles.events.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.args.ListDirection;

import java.util.List;

/**
 * Reliable consumer of the dedicated CyclesEvidence source queue.
 *
 * <p>Uses the BLMOVE reliable-queue pattern instead of a destructive BRPOP: a
 * record is atomically moved from {@code evidence:pending} to an in-flight
 * {@code evidence:processing} list when {@link #claim claimed}, and removed from
 * it only once the envelope is durably stored ({@link #ack}) or dead-lettered.
 * A crash between claim and ack therefore leaves the record in
 * {@code evidence:processing}, where {@link #recoverStale(long, long, int)}
 * returns it to {@code evidence:pending} after its claim becomes stale — so an
 * audit record is never silently lost in a crash window. Reprocessing is safe
 * because envelopes are content-addressed (same id → same bytes → same store
 * key, idempotent).
 */
@Repository
public class EvidenceQueueConsumer {

    private static final String RECOVER_STALE_BATCH_LUA = """
            local scan_count = math.min(tonumber(ARGV[1]), redis.call('LLEN', KEYS[1]))
            local now = tonumber(ARGV[2])
            local cutoff = tonumber(ARGV[3])
            local moved = 0
            for i = 1, scan_count do
              local record = redis.call('LMOVE', KEYS[1], KEYS[1], 'RIGHT', 'LEFT')
              if not record then break end
              local claimed_at = redis.call('ZSCORE', KEYS[2], record)
              if not claimed_at then
                redis.call('ZADD', KEYS[2], now, record)
              elseif tonumber(claimed_at) <= cutoff then
                if redis.call('LREM', KEYS[1], 1, record) > 0 then
                  redis.call('ZREM', KEYS[2], record)
                  redis.call('LPUSH', KEYS[3], record)
                  moved = moved + 1
                end
              end
            end
            return moved
            """;

    private final JedisPool jedisPool;
    private final String pendingKey;
    private final String processingKey;
    private final String processingClaimedAtKey;
    private final String failedKey;
    private final int failedMaxLen;

    public EvidenceQueueConsumer(
            JedisPool jedisPool,
            @Value("${cycles.evidence.queue.pending-key:evidence:pending}") String pendingKey,
            @Value("${cycles.evidence.queue.processing-key:evidence:processing}") String processingKey,
            @Value("${cycles.evidence.queue.failed-key:evidence:failed}") String failedKey,
            @Value("${cycles.evidence.queue.failed-max-len:10000}") int failedMaxLen) {
        if (pendingKey == null || pendingKey.isBlank()
                || processingKey == null || processingKey.isBlank()
                || failedKey == null || failedKey.isBlank()) {
            throw new IllegalArgumentException("evidence queue keys must not be blank");
        }
        if (failedMaxLen <= 0) {
            throw new IllegalArgumentException("evidence failed queue maximum length must be positive");
        }
        this.jedisPool = jedisPool;
        this.pendingKey = pendingKey;
        this.processingKey = processingKey;
        this.processingClaimedAtKey = processingKey + ":claimed_at";
        this.failedKey = failedKey;
        this.failedMaxLen = failedMaxLen;
    }

    /** Atomically claim one record: block-move it from the tail of pending (FIFO)
     *  to the head of the processing list and return it, or {@code null} on
     *  timeout. The record stays in processing until {@link #ack}, so a crash
     *  before ack is recoverable. */
    public String claim(int timeoutSeconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            String record = jedis.blmove(pendingKey, processingKey,
                    ListDirection.RIGHT, ListDirection.LEFT, timeoutSeconds);
            if (record != null) {
                jedis.zadd(processingClaimedAtKey, System.currentTimeMillis(), record);
            }
            return record;
        }
    }

    /** Acknowledge a processed record by removing it from the processing list. */
    public void ack(String record) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.eval("""
                    redis.call('LREM', KEYS[1], 1, ARGV[1])
                    redis.call('ZREM', KEYS[2], ARGV[1])
                    return 1
                    """, List.of(processingKey, processingClaimedAtKey), List.of(record));
        }
    }

    /**
     * Recover only records whose claim lease has expired. Work owned by another
     * healthy replica remains untouched. The recovery scan is bounded.
     */
    public long recoverStale(long nowMillis, long idleMillis, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("evidence recovery limit must be positive");
        }
        int boundedLimit = limit;
        long cutoff = nowMillis - Math.max(0, idleMillis);
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(RECOVER_STALE_BATCH_LUA,
                    List.of(processingKey, processingClaimedAtKey, pendingKey),
                    List.of(Integer.toString(boundedLimit), Long.toString(nowMillis), Long.toString(cutoff)));
            return result instanceof Long count ? count : 0L;
        }
    }

    /** Atomically move an in-flight poison record to the bounded DLQ. */
    public boolean deadLetterAndAck(String recordJson) {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval("""
                    if redis.call('LREM', KEYS[2], 1, ARGV[1]) == 0 then
                      redis.call('ZREM', KEYS[3], ARGV[1])
                      return 0
                    end
                    redis.call('LPUSH', KEYS[1], ARGV[1])
                    local max_len = tonumber(ARGV[2])
                    if max_len > 0 then redis.call('LTRIM', KEYS[1], 0, max_len - 1) end
                    redis.call('ZREM', KEYS[3], ARGV[1])
                    return 1
                    """, List.of(failedKey, processingKey, processingClaimedAtKey),
                    List.of(recordJson, Integer.toString(failedMaxLen)));
            return Long.valueOf(1L).equals(result);
        }
    }
}
