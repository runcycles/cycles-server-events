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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        assertThatThrownBy(() -> repository.findById("del-bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read webhook delivery");
    }

    @Test
    void update_existingKeyPreservesTtlAtomically() throws Exception {
        Delivery delivery = Delivery.builder()
                .deliveryId("del-1")
                .status(DeliveryStatus.SUCCESS)
                .completedAt(Instant.now())
                .build();
        when(jedis.eval(anyString(), eq(java.util.List.of("delivery:del-1")), anyList())).thenReturn(1L);

        repository.update(delivery);

        verify(jedis).eval(anyString(), eq(java.util.List.of("delivery:del-1")), anyList());
    }

    @Test
    void update_missingKeyDoesNotResurrectIt() throws Exception {
        Delivery delivery = Delivery.builder()
                .deliveryId("del-1")
                .status(DeliveryStatus.SUCCESS)
                .completedAt(Instant.now())
                .build();
        when(jedis.eval(anyString(), eq(java.util.List.of("delivery:del-1")), anyList())).thenReturn(0L);

        repository.update(delivery);

        verify(jedis).eval(anyString(), eq(java.util.List.of("delivery:del-1")), anyList());
        verify(jedis, never()).set(anyString(), anyString());
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

        assertThatThrownBy(() -> brokenRepo.update(delivery))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to update webhook delivery");
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }

    @Test
    void updateNullDeliveryUsesNullSafeFailureDiagnostics() {
        assertThatThrownBy(() -> repository.update(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to update webhook delivery");
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }
}
