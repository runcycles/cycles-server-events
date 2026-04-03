package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.DeliveryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import redis.clients.jedis.params.SetParams;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryRepositoryTest {

    @Mock
    private JedisPool jedisPool;
    @Mock
    private Jedis jedis;

    private ObjectMapper objectMapper;
    private DeliveryRepository repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        repository = new DeliveryRepository(jedisPool, objectMapper);
    }

    @Test
    void findById_found() throws Exception {
        Delivery delivery = Delivery.builder()
                .deliveryId("del-1")
                .subscriptionId("sub-1")
                .eventId("evt-1")
                .status(DeliveryStatus.PENDING)
                .attempts(0)
                .build();
        String json = objectMapper.writeValueAsString(delivery);
        when(jedis.get("delivery:del-1")).thenReturn(json);

        Delivery result = repository.findById("del-1");

        assertThat(result).isNotNull();
        assertThat(result.getDeliveryId()).isEqualTo("del-1");
        assertThat(result.getSubscriptionId()).isEqualTo("sub-1");
        assertThat(result.getStatus()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void findById_notFound() {
        when(jedis.get("delivery:del-missing")).thenReturn(null);

        Delivery result = repository.findById("del-missing");

        assertThat(result).isNull();
    }

    @Test
    void findById_deserializationError() {
        when(jedis.get("delivery:del-bad")).thenReturn("not-valid-json{{{");

        Delivery result = repository.findById("del-bad");

        assertThat(result).isNull();
    }

    @Test
    void update_noTtl_plainSet() throws Exception {
        Delivery delivery = Delivery.builder()
                .deliveryId("del-1")
                .status(DeliveryStatus.SUCCESS)
                .completedAt(Instant.now())
                .build();
        when(jedis.ttl("delivery:del-1")).thenReturn(-1L); // no TTL

        repository.update(delivery);

        verify(jedis).set(eq("delivery:del-1"), anyString());
        verify(jedis, never()).set(anyString(), anyString(), any(SetParams.class));
    }

    @Test
    void update_withTtl_preservesTtlAtomically() throws Exception {
        Delivery delivery = Delivery.builder()
                .deliveryId("del-1")
                .status(DeliveryStatus.SUCCESS)
                .completedAt(Instant.now())
                .build();
        when(jedis.ttl("delivery:del-1")).thenReturn(86400L); // 1 day remaining

        repository.update(delivery);

        verify(jedis).set(eq("delivery:del-1"), anyString(), any(SetParams.class));
    }

    @Test
    void update_serializationError() {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        DeliveryRepository brokenRepo = new DeliveryRepository(jedisPool, brokenMapper);
        Delivery delivery = Delivery.builder().deliveryId("del-1").build();

        try {
            when(brokenMapper.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("fail") {});
        } catch (Exception e) {
            // won't happen
        }

        // Should not throw — error is logged
        brokenRepo.update(delivery);
        verify(jedis, never()).set(anyString(), anyString());
        verify(jedis, never()).set(anyString(), anyString(), any(SetParams.class));
    }
}
