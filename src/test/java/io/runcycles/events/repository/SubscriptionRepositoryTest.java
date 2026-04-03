package io.runcycles.events.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.events.config.CryptoService;
import io.runcycles.events.model.Subscription;
import io.runcycles.events.model.WebhookStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionRepositoryTest {

    @Mock
    private JedisPool jedisPool;
    @Mock
    private Jedis jedis;

    private ObjectMapper objectMapper;
    private SubscriptionRepository repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        CryptoService cryptoService = new CryptoService(""); // pass-through mode
        repository = new SubscriptionRepository(jedisPool, objectMapper, cryptoService);
    }

    @Test
    void findById_found() throws Exception {
        Subscription sub = Subscription.builder()
                .subscriptionId("sub-1")
                .tenantId("t-1")
                .url("https://example.com/webhook")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of("tenant.created"))
                .build();
        when(jedis.get("webhook:sub-1")).thenReturn(objectMapper.writeValueAsString(sub));

        Subscription result = repository.findById("sub-1");

        assertThat(result).isNotNull();
        assertThat(result.getSubscriptionId()).isEqualTo("sub-1");
        assertThat(result.getUrl()).isEqualTo("https://example.com/webhook");
        assertThat(result.getStatus()).isEqualTo(WebhookStatus.ACTIVE);
    }

    @Test
    void findById_notFound() {
        when(jedis.get("webhook:sub-missing")).thenReturn(null);

        Subscription result = repository.findById("sub-missing");

        assertThat(result).isNull();
    }

    @Test
    void findById_deserializationError() {
        when(jedis.get("webhook:sub-bad")).thenReturn("%%%bad-json");

        Subscription result = repository.findById("sub-bad");

        assertThat(result).isNull();
    }

    @Test
    void getSigningSecret_found() {
        when(jedis.get("webhook:secret:sub-1")).thenReturn("my-secret-key");

        String result = repository.getSigningSecret("sub-1");

        assertThat(result).isEqualTo("my-secret-key");
    }

    @Test
    void getSigningSecret_notFound() {
        when(jedis.get("webhook:secret:sub-missing")).thenReturn(null);

        String result = repository.getSigningSecret("sub-missing");

        assertThat(result).isNull();
    }

    @Test
    void getSigningSecret_decryptsEncryptedValue() {
        // Simulate an encrypted secret stored by admin service
        CryptoService encryptor = new CryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        String encrypted = encryptor.encrypt("my-secret");
        when(jedis.get("webhook:secret:sub-enc")).thenReturn(encrypted);
        // Create repo with same key for decryption
        CryptoService decryptor = new CryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        SubscriptionRepository encRepo = new SubscriptionRepository(jedisPool, objectMapper, decryptor);

        String result = encRepo.getSigningSecret("sub-enc");

        assertThat(result).isEqualTo("my-secret");
    }

    @Test
    void getSigningSecret_redisError_returnsNull() {
        when(jedis.get("webhook:secret:sub-fail")).thenThrow(new RuntimeException("redis error"));

        String result = repository.getSigningSecret("sub-fail");

        assertThat(result).isNull();
    }

    @Test
    void update_success() throws Exception {
        Subscription sub = Subscription.builder()
                .subscriptionId("sub-1")
                .status(WebhookStatus.ACTIVE)
                .build();

        repository.update(sub);

        verify(jedis).set(eq("webhook:sub-1"), anyString());
    }

    @Test
    void update_serializationError() {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        SubscriptionRepository brokenRepo = new SubscriptionRepository(jedisPool, brokenMapper, new CryptoService(""));
        Subscription sub = Subscription.builder().subscriptionId("sub-1").build();

        try {
            when(brokenMapper.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("fail") {});
        } catch (Exception e) {
            // won't happen
        }

        brokenRepo.update(sub);
        verify(jedis, never()).set(anyString(), anyString());
    }
}
