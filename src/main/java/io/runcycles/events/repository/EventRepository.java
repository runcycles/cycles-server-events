package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.events.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@Repository
public class EventRepository {

    private static final Logger LOG = LoggerFactory.getLogger(EventRepository.class);

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    public EventRepository(JedisPool jedisPool, ObjectMapper objectMapper) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
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
