package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.events.model.Delivery;
import io.runcycles.events.model.DeliveryStatus;
import io.runcycles.events.repository.DeliveryQueueRepository.ClaimedDelivery;
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
    void updateOwned_existingKeyPreservesTtlAtomically() throws Exception {
        Delivery delivery = Delivery.builder()
                .deliveryId("del-1")
                .status(DeliveryStatus.FAILED)
                .completedAt(Instant.now())
                .build();
        ClaimedDelivery claim = new ClaimedDelivery("del-1", "token");
        when(jedis.eval(anyString(), eq(java.util.List.of("delivery:del-1",
                "dispatch:processing:claim_owner")), anyList())).thenReturn(1L);

        assertThat(repository.updateOwned(delivery, claim)).isTrue();

        verify(jedis).eval(anyString(), eq(java.util.List.of("delivery:del-1",
                "dispatch:processing:claim_owner")), anyList());
    }

    @Test
    void updateOwned_rejectsSupersededMissingOrTerminalState() throws Exception {
        Delivery delivery = Delivery.builder()
                .deliveryId("del-1")
                .status(DeliveryStatus.FAILED)
                .completedAt(Instant.now())
                .build();
        ClaimedDelivery claim = new ClaimedDelivery("del-1", "token");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-1L, -2L, -3L);

        assertThat(repository.updateOwned(delivery, claim)).isFalse();
        assertThat(repository.updateOwned(delivery, claim)).isFalse();
        assertThat(repository.updateOwned(delivery, claim)).isFalse();
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-4L);
        assertThatThrownBy(() -> repository.updateOwned(delivery, claim))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("stored webhook delivery has an invalid status");
        verify(jedis, never()).set(anyString(), anyString());
    }

    @Test
    void updateOwned_serializationError() {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        DeliveryRepository brokenRepo = new DeliveryRepository(jedisPool, brokenMapper);
        Delivery delivery = Delivery.builder().deliveryId("del-1")
                .status(DeliveryStatus.FAILED).build();

        try {
            when(brokenMapper.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("fail") {});
        } catch (Exception e) {
            // won't happen
        }

        assertThatThrownBy(() -> brokenRepo.updateOwned(
                delivery, new ClaimedDelivery("del-1", "token")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to update owned webhook delivery");
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }

    @Test
    void updateOwnedRejectsInvalidOrMismatchedInputs() {
        Delivery delivery = Delivery.builder().deliveryId("del-1")
                .status(DeliveryStatus.FAILED).build();
        assertThatThrownBy(() -> repository.updateOwned(null,
                new ClaimedDelivery("del-1", "token")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.updateOwned(delivery, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.updateOwned(delivery,
                new ClaimedDelivery("different", "token")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.updateOwned(
                Delivery.builder().build(), new ClaimedDelivery("del-1", "token")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.updateOwned(
                Delivery.builder().deliveryId(" ").build(),
                new ClaimedDelivery("del-1", "token")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.updateOwned(
                Delivery.builder().deliveryId("del-1").status(DeliveryStatus.SUCCESS).build(),
                new ClaimedDelivery("del-1", "token")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }

    @Test
    void updateOwnedAndScheduleRetryIsOneFencedRedisTransition() throws Exception {
        Delivery delivery = Delivery.builder().deliveryId("del-1")
                .status(DeliveryStatus.RETRYING).build();
        ClaimedDelivery claim = new ClaimedDelivery("del-1", "token");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        assertThat(repository.updateOwnedAndScheduleRetry(delivery, claim, 1234L)).isTrue();

        verify(jedis).eval(anyString(), eq(java.util.List.of("delivery:del-1",
                        "dispatch:processing:claim_owner", "dispatch:retry")),
                eq(java.util.List.of(objectMapper.writeValueAsString(delivery),
                        "del-1", "token", "1234")));
        assertThatThrownBy(() -> repository.updateOwnedAndScheduleRetry(delivery, claim, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.updateOwnedAndScheduleRetry(
                Delivery.builder().deliveryId("del-1").status(DeliveryStatus.FAILED).build(),
                claim, 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryTransitionReportsSupersededOwnerAndWrapsSerializationFailure() throws Exception {
        Delivery delivery = Delivery.builder().deliveryId("del-1")
                .status(DeliveryStatus.RETRYING).build();
        ClaimedDelivery claim = new ClaimedDelivery("del-1", "token");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-1L);
        assertThat(repository.updateOwnedAndScheduleRetry(delivery, claim, 1L)).isFalse();
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-4L);
        assertThatThrownBy(() -> repository.updateOwnedAndScheduleRetry(delivery, claim, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("stored webhook delivery has an invalid status");

        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        DeliveryRepository brokenRepo = new DeliveryRepository(jedisPool, brokenMapper);
        when(brokenMapper.writeValueAsString(delivery)).thenThrow(
                new com.fasterxml.jackson.core.JsonProcessingException("fail") { });
        assertThatThrownBy(() -> brokenRepo.updateOwnedAndScheduleRetry(delivery, claim, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to persist owned webhook retry");
    }
}
