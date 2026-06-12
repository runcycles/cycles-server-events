package io.runcycles.events.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.args.ListDirection;

/**
 * Reliable consumer of the dedicated CyclesEvidence source queue.
 *
 * <p>Uses the BLMOVE reliable-queue pattern instead of a destructive BRPOP: a
 * record is atomically moved from {@code evidence:pending} to an in-flight
 * {@code evidence:processing} list when {@link #claim claimed}, and removed from
 * it only once the envelope is durably stored ({@link #ack}) or dead-lettered.
 * A crash between claim and ack therefore leaves the record in
 * {@code evidence:processing}, where {@link #recover()} returns it to
 * {@code evidence:pending} on the next startup — so an audit record is never
 * silently lost in a crash window. Reprocessing is safe because envelopes are
 * content-addressed (same id → same bytes → same store key, idempotent).
 */
@Repository
public class EvidenceQueueConsumer {

    private final JedisPool jedisPool;
    private final String pendingKey;
    private final String processingKey;
    private final String failedKey;
    private final int failedMaxLen;

    public EvidenceQueueConsumer(
            JedisPool jedisPool,
            @Value("${cycles.evidence.queue.pending-key:evidence:pending}") String pendingKey,
            @Value("${cycles.evidence.queue.processing-key:evidence:processing}") String processingKey,
            @Value("${cycles.evidence.queue.failed-key:evidence:failed}") String failedKey,
            @Value("${cycles.evidence.queue.failed-max-len:10000}") int failedMaxLen) {
        this.jedisPool = jedisPool;
        this.pendingKey = pendingKey;
        this.processingKey = processingKey;
        this.failedKey = failedKey;
        this.failedMaxLen = failedMaxLen;
    }

    /** Atomically claim one record: block-move it from the tail of pending (FIFO)
     *  to the head of the processing list and return it, or {@code null} on
     *  timeout. The record stays in processing until {@link #ack}, so a crash
     *  before ack is recoverable. */
    public String claim(int timeoutSeconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.blmove(pendingKey, processingKey,
                    ListDirection.RIGHT, ListDirection.LEFT, timeoutSeconds);
        }
    }

    /** Acknowledge a processed record by removing it from the processing list. */
    public void ack(String record) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lrem(processingKey, 1, record);
        }
    }

    /** Recover records orphaned in-flight by a crash: move everything left in the
     *  processing list back to pending for reprocessing. Returns the count. */
    public long recover() {
        long moved = 0;
        try (Jedis jedis = jedisPool.getResource()) {
            while (jedis.lmove(processingKey, pendingKey, ListDirection.LEFT, ListDirection.RIGHT) != null) {
                moved++;
            }
        }
        return moved;
    }

    /** Move a record that could not be built/signed to the dead-letter queue
     *  ({@code evidence:failed}) so it is auditable and replayable, not lost.
     *  Bounded to {@code failed-max-len} (newest kept) to cap memory growth. */
    public void deadLetter(String recordJson) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lpush(failedKey, recordJson);
            if (failedMaxLen > 0) {
                jedis.ltrim(failedKey, 0, failedMaxLen - 1);
            }
        }
    }
}
