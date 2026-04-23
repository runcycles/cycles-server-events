package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
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
            LOG.error("Failed to save event", e);
            throw new RuntimeException("Failed to save event", e);
        }
    }

    public Event findById(String eventId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get("event:" + eventId);
            if (data == null) return null;
            return objectMapper.readValue(data, Event.class);
        } catch (Exception e) {
            LOG.error("Failed to read event: {}", eventId, e);
            return null;
        }
    }
}
