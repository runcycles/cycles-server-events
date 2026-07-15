package io.runcycles.events.repository;

import static io.runcycles.events.logging.LogSanitizer.safe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.model.DispatcherEventTask;
import io.runcycles.events.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class EventRepository {

    private static final Logger LOG = LoggerFactory.getLogger(EventRepository.class);

    public static final String DISPATCHER_OUTBOX_PENDING_KEY = "dispatcher:event-outbox:pending";
    private static final String DISPATCHER_OUTBOX_TASK_PREFIX = "dispatcher:event-outbox:task:";
    private static final String DISPATCHER_OUTBOX_LOCK_PREFIX = "dispatcher:event-outbox:lock:";

    private static final String ACK_CLAIMED_OUTBOX_TASK_LUA = """
            if redis.call('GET', KEYS[3]) ~= ARGV[2] then return 0 end
            redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('DEL', KEYS[2])
            redis.call('DEL', KEYS[3])
            return 1
            """;

    private static final String RELEASE_OUTBOX_LOCK_LUA = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    // Mirrors admin-side cycles-server-admin EventRepository.SAVE_EVENT_LUA so
    // dispatcher-emitted events land in the same Redis shape the admin plane
    // reads from (event:<id>, events:<tenantId>, events:_all, correlation set).
    private static final String SAVE_EVENT_LUA =
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
        "redis.call('EXPIRE', KEYS[1], ARGV[4])\n" +
        "redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])\n" +
        "redis.call('ZADD', KEYS[3], ARGV[2], ARGV[3])\n" +
        "if KEYS[4] then\n" +
        "    redis.call('SADD', KEYS[4], ARGV[3])\n" +
        "    redis.call('EXPIRE', KEYS[4], ARGV[4])\n" +
        "end\n" +
        "return 1\n";

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    @Value("${events.retention.event-ttl-days:90}")
    private int eventTtlDays;

    public EventRepository(JedisPool jedisPool, ObjectMapper objectMapper) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
    }

    public void save(Event event) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (event.getEventId() == null) {
                event.setEventId("evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            }
            if (event.getTimestamp() == null) {
                event.setTimestamp(Instant.now());
            }
            String json = objectMapper.writeValueAsString(event);
            String score = String.valueOf(event.getTimestamp().toEpochMilli());
            String id = event.getEventId();

            List<String> keys = new ArrayList<>();
            keys.add("event:" + id);
            keys.add("events:" + event.getTenantId());
            keys.add("events:_all");
            if (event.getCorrelationId() != null && !event.getCorrelationId().isBlank()) {
                keys.add("events:correlation:" + event.getCorrelationId());
            }

            String ttlSeconds = String.valueOf(eventTtlDays * 86400L);
            jedis.eval(SAVE_EVENT_LUA, keys, List.of(json, score, id, ttlSeconds));
        } catch (Exception e) {
            LOG.error("Failed to save event: event_id={} event_type={} tenant_id={} scope={} correlation_id={} request_id={} trace_id={}",
                    safe(event != null ? event.getEventId() : null),
                    safe(event != null ? event.getEventType() : null),
                    safe(event != null ? event.getTenantId() : null),
                    safe(event != null ? event.getScope() : null),
                    safe(event != null ? event.getCorrelationId() : null),
                    safe(event != null ? event.getRequestId() : null),
                    safe(event != null ? event.getTraceId() : null),
                    e);
            throw new RuntimeException("Failed to save event", e);
        }
    }

    /** Stable Redis key used by state-transition Lua scripts to stage a task. */
    public static String dispatcherOutboxTaskKey(String taskId) {
        return DISPATCHER_OUTBOX_TASK_PREFIX + taskId;
    }

    /** Remove a task after an inline idempotent publish completed. */
    public boolean ackDispatcherEvent(String taskId) {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval("""
                    local pending = redis.call('ZREM', KEYS[1], ARGV[1])
                    local task = redis.call('DEL', KEYS[2])
                    if pending > 0 or task > 0 then return 1 end
                    return 0
                    """, List.of(DISPATCHER_OUTBOX_PENDING_KEY, dispatcherOutboxTaskKey(taskId)),
                    List.of(taskId));
            return Long.valueOf(1L).equals(result);
        }
    }

    /** Find due task ids without removing them; a per-task lease controls ownership. */
    public List<String> findDueDispatcherEvents(long nowMillis, int limit) {
        if (limit <= 0) return List.of();
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.zrangeByScore(DISPATCHER_OUTBOX_PENDING_KEY,
                    Double.NEGATIVE_INFINITY, nowMillis, 0, limit);
        }
    }

    public boolean tryClaimDispatcherEvent(String taskId, String owner, long leaseMillis) {
        if (taskId == null || taskId.isBlank() || owner == null || owner.isBlank() || leaseMillis <= 0) {
            throw new IllegalArgumentException("outbox task id, owner, and positive lease are required");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String claimed = jedis.set(DISPATCHER_OUTBOX_LOCK_PREFIX + taskId, owner,
                    redis.clients.jedis.params.SetParams.setParams().nx().px(leaseMillis));
            return "OK".equals(claimed);
        }
    }

    public DispatcherEventTask findDispatcherEventTask(String taskId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(dispatcherOutboxTaskKey(taskId));
            if (json == null) return null;
            return objectMapper.readValue(json, DispatcherEventTask.class);
        } catch (Exception e) {
            LOG.error("Failed to read dispatcher event outbox task: task_id={}", safe(taskId), e);
            throw new IllegalStateException("Failed to read dispatcher event outbox task", e);
        }
    }

    public void deferDispatcherEvent(String taskId, long nextAttemptAtMillis) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zadd(DISPATCHER_OUTBOX_PENDING_KEY, nextAttemptAtMillis, taskId);
        }
    }

    public boolean ackClaimedDispatcherEvent(String taskId, String owner) {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(ACK_CLAIMED_OUTBOX_TASK_LUA,
                    List.of(DISPATCHER_OUTBOX_PENDING_KEY, dispatcherOutboxTaskKey(taskId),
                            DISPATCHER_OUTBOX_LOCK_PREFIX + taskId),
                    List.of(taskId, owner));
            return Long.valueOf(1L).equals(result);
        }
    }

    public void releaseDispatcherEventClaim(String taskId, String owner) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.eval(RELEASE_OUTBOX_LOCK_LUA,
                    List.of(DISPATCHER_OUTBOX_LOCK_PREFIX + taskId), List.of(owner));
        }
    }

    public Event findById(String eventId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get("event:" + eventId);
            if (data == null) return null;
            return objectMapper.readValue(data, Event.class);
        } catch (Exception e) {
            LOG.error("Failed to read event: event_id={}", safe(eventId), e);
            throw new IllegalStateException("Failed to read event", e);
        }
    }
}
