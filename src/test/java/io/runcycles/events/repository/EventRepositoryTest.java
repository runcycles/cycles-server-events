package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.events.model.Event;
import io.runcycles.events.model.EventCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventRepositoryTest {

    @Mock
    private JedisPool jedisPool;
    @Mock
    private Jedis jedis;

    private ObjectMapper objectMapper;
    private EventRepository repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        repository = new EventRepository(jedisPool, objectMapper);
    }

    @Test
    void findById_found() throws Exception {
        Event event = Event.builder()
                .eventId("evt-1")
                .eventType("tenant.created")
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .tenantId("t-1")
                .source("admin")
                .build();
        when(jedis.get("event:evt-1")).thenReturn(objectMapper.writeValueAsString(event));

        Event result = repository.findById("evt-1");

        assertThat(result).isNotNull();
        assertThat(result.getEventId()).isEqualTo("evt-1");
        assertThat(result.getEventType()).isEqualTo("tenant.created");
        assertThat(result.getCategory()).isEqualTo(EventCategory.TENANT);
    }

    @Test
    void findById_notFound() {
        when(jedis.get("event:evt-missing")).thenReturn(null);

        Event result = repository.findById("evt-missing");

        assertThat(result).isNull();
    }

    @Test
    void findById_deserializationError() {
        when(jedis.get("event:evt-bad")).thenReturn("{invalid json}}}");

        Event result = repository.findById("evt-bad");

        assertThat(result).isNull();
    }
}
