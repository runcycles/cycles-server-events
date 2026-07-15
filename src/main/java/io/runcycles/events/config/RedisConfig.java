package io.runcycles.events.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.SslOptions;

@Configuration
public class RedisConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RedisConfig.class);

    @Value("${redis.host:localhost}")
    private String host;

    @Value("${redis.port:6379}")
    private int port;

    @Value("${redis.password:}")
    private String password;

    @Value("${redis.username:}")
    private String username = "";

    @Value("${redis.tls.enabled:false}")
    private boolean tlsEnabled;

    @Value("${redis.connect-timeout-ms:2000}")
    private int connectTimeoutMs = 2000;

    @Value("${redis.socket-timeout-ms:5000}")
    private int socketTimeoutMs = 5000;

    @Value("${redis.blocking-socket-timeout-ms:10000}")
    private int blockingSocketTimeoutMs = 10000;

    @Value("${dispatch.pending.timeout-seconds:5}")
    private int dispatchPendingTimeoutSeconds = 5;

    @Value("${cycles.evidence.queue.timeout-seconds:5}")
    private int evidenceQueueTimeoutSeconds = 5;

    @Bean
    public JedisPool jedisPool() {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("redis.host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("redis.port must be between 1 and 65535");
        }
        if (connectTimeoutMs <= 0 || socketTimeoutMs <= 0 || blockingSocketTimeoutMs <= 0) {
            throw new IllegalStateException("Redis timeouts must be positive");
        }
        long longestBlockingCommandMs = 1000L
                * Math.max(dispatchPendingTimeoutSeconds, evidenceQueueTimeoutSeconds);
        if (dispatchPendingTimeoutSeconds <= 0 || evidenceQueueTimeoutSeconds <= 0
                || blockingSocketTimeoutMs <= longestBlockingCommandMs) {
            throw new IllegalStateException(
                    "redis.blocking-socket-timeout-ms must exceed every Redis blocking command timeout");
        }
        LOG.info("Cycles Events Redis pool initializing: host={} port={} username_configured={} password_configured={} tls_enabled={}",
                host, port, username != null && !username.isBlank(),
                password != null && !password.isBlank(), tlsEnabled);
        GenericObjectPoolConfig<Jedis> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(50);
        config.setMaxIdle(10);
        config.setMinIdle(5);
        config.setTestOnBorrow(true);
        config.setTestWhileIdle(true);
        DefaultJedisClientConfig.Builder client = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(connectTimeoutMs)
                .socketTimeoutMillis(socketTimeoutMs)
                // Bound network-blackhole stalls while still allowing BLMOVE to
                // reach its normal server-side timeout first.
                .blockingSocketTimeoutMillis(blockingSocketTimeoutMs)
                .clientName("cycles-server-events");
        if (tlsEnabled) client.sslOptions(SslOptions.defaults());
        if (username != null && !username.isBlank()) client.user(username);
        if (password != null && !password.isBlank()) client.password(password);
        return new JedisPool(config, new HostAndPort(host, port), client.build());
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
