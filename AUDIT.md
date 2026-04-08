# AUDIT.md - cycles-server-events

## Service Overview

| Field | Value |
|-------|-------|
| Service | cycles-server-events |
| Version | 0.1.25.5 |
| Java | 21 |
| Spring Boot | 3.5.11 |
| Spec Authority | [complete-budget-governance-v0.1.25.yaml](https://github.com/runcycles/cycles-server-admin/blob/main/complete-budget-governance-v0.1.25.yaml) |

## Test Coverage

| Metric | Value |
|--------|-------|
| Total tests | 119 |
| Unit tests | 116 |
| Integration tests | 3 (WebhookDeliveryIntegrationTest) |
| JaCoCo minimum | 95% line coverage (enforced) |
| Test-to-source ratio | 1.73:1 |

## Source File Inventory (19 classes)

| Layer | File | Tests |
|-------|------|-------|
| App | EventsApplication.java | EventsApplicationTest (1) |
| Config | RedisConfig.java | RedisConfigTest (3) |
| Config | EventsConfig.java | EventsConfigTest (1) |
| Config | CryptoService.java | CryptoServiceTest (9) |
| Model | Event.java | ModelTest (25 total) |
| Model | EventType.java (40 types) | ModelTest |
| Model | EventCategory.java | ModelTest |
| Model | Actor.java, ActorType.java | ModelTest |
| Model | Delivery.java, DeliveryStatus.java | ModelTest |
| Model | Subscription.java, WebhookStatus.java | ModelTest |
| Model | RetryPolicy.java | ModelTest |
| Model | WebhookThresholdConfig.java | ModelTest |
| Repository | EventRepository.java | EventRepositoryTest (3) |
| Repository | DeliveryRepository.java | DeliveryRepositoryTest (6) |
| Repository | SubscriptionRepository.java | SubscriptionRepositoryTest (11) |
| Repository | DeliveryQueueRepository.java | DeliveryQueueRepositoryTest (8) |
| Service | DeliveryHandler.java | DeliveryHandlerTest (22) |
| Service | DispatchLoop.java | DispatchLoopTest (4) |
| Service | RetryScheduler.java | RetrySchedulerTest (3) |
| Service | RetentionCleanupService.java | RetentionCleanupServiceTest (3) |
| Transport | Transport.java (interface) | (via WebhookTransportTest) |
| Transport | TransportResult.java | ModelTest |
| Transport | PayloadSigner.java | PayloadSignerTest (5) |
| Transport | WebhookTransport.java | WebhookTransportTest (12) |
| Integration | - | WebhookDeliveryIntegrationTest (3) |

*Note: Surefire excludes \*IntegrationTest by default. `mvn verify` runs 114 unit tests; `mvn verify -Pintegration-tests` runs all 117 (removes exclusion).*

## Security Audit

| Check | Status |
|-------|--------|
| No hardcoded secrets in source | PASS |
| All credentials via environment variables | PASS |
| AES-256-GCM encryption for signing secrets at rest | PASS |
| HMAC-SHA256 webhook payload signing | PASS |
| Signing secrets never logged | PASS |
| 32-byte key length enforced (CryptoService) | PASS |
| Random IV per encryption (12 bytes) | PASS |
| Backward-compatible plaintext fallback | PASS |
| No TODO/FIXME/HACK in source | PASS |

## Configuration Audit

| Property | Default | Env Override | Status |
|----------|---------|-------------|--------|
| server.port | 7980 | - | OK |
| redis.host | localhost | REDIS_HOST | OK |
| redis.port | 6379 | REDIS_PORT | OK |
| redis.password | (empty) | REDIS_PASSWORD | OK |
| webhook.secret.encryption-key | (empty) | WEBHOOK_SECRET_ENCRYPTION_KEY | OK |
| dispatch.pending.timeout-seconds | 5 | - | OK |
| dispatch.retry.poll-interval-ms | 5000 | - | OK |
| dispatch.retry.batch-size | 100 | RETRY_BATCH_SIZE | OK |
| dispatch.http.timeout-seconds | 30 | - | OK |
| dispatch.http.connect-timeout-seconds | 5 | - | OK |
| dispatch.max-delivery-age-ms | 86400000 | MAX_DELIVERY_AGE_MS | OK |
| events.retention.event-ttl-days | 90 | EVENT_TTL_DAYS | OK |
| events.retention.delivery-ttl-days | 14 | DELIVERY_TTL_DAYS | OK |
| events.retention.cleanup-interval-ms | 3600000 | RETENTION_CLEANUP_INTERVAL_MS | OK |
| spring.task.scheduling.pool.size | 3 | - | OK |
| management.endpoints.web.exposure.include | health,info,prometheus | - | OK |

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| spring-boot-starter-web | 3.5.11 | REST + embedded Tomcat |
| spring-boot-starter-actuator | 3.5.11 | Health + metrics |
| jedis | 5.2.0 | Redis client |
| jackson-datatype-jsr310 | (parent) | Java time serialization |
| lombok | (parent) | Compile-time only |
| spring-boot-starter-test | 3.5.11 | Test framework |
| testcontainers | 1.20.4 | Integration test Redis |
| micrometer-registry-prometheus | (parent) | Prometheus metrics endpoint |
| jacoco | 0.8.12 | Coverage enforcement |

## Resilience Patterns

| Pattern | Implementation |
|---------|---------------|
| Exponential backoff | `delay = min(initialDelay * multiplier^(attempts-1), maxDelay)` |
| Auto-disable webhooks | After N consecutive failures (default 10) |
| Stale delivery pruning | Deliveries > 24h auto-failed |
| Redis connection errors | Caught and logged, schedulers continue |
| Concurrent safety | BRPOP atomic consumption, multi-instance safe |
| TTL-based retention | Events 90d, deliveries 14d, ZSET indexes trimmed hourly |

## Spec Compliance (v0.1.25)

| Requirement | Status |
|-------------|--------|
| 40 event types across 6 categories | PASS |
| Enum serialization (lowercase) | PASS - ActorType, EventCategory, EventType |
| Status fields use enums | PASS - DeliveryStatus, WebhookStatus (not string literals) |
| Subscription model fields | PASS - all spec fields present |
| HMAC-SHA256 webhook signing | PASS |
| Retry with exponential backoff | PASS |
| Event TTL 90 days | PASS |

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-03-31 | 0.1.25.1 | Initial implementation: dispatch loop, delivery handler, retry scheduler |
| 2026-03-31 | 0.1.25.1 | v0.1.25 spec compliance (enum serialization, Subscription fields) |
| 2026-03-31 | 0.1.25.1 | AES-256-GCM encryption for webhook signing secrets |
| 2026-03-31 | 0.1.25.1 | TTL and retention for event/delivery Redis keys |
| 2026-03-31 | 0.1.25.1 | CI-friendly ${revision} versioning |
| 2026-04-01 | 0.1.25.1 | E2E integration test with Testcontainers |
| 2026-04-01 | 0.1.25.1 | Graceful Redis connection error handling in scheduled services |
| 2026-04-01 | 0.1.25.1 | Release audit: fix README version refs (0.1.0 -> 0.1.25.1), test count (92 -> 113) |
| 2026-04-01 | 0.1.25.1 | Code validation: fix duplicate delivery bug, missing exception handler, atomic TTL, config timeout, pool health checks, scheduler pool, response body discard |
| 2026-04-03 | 0.1.25.3 | Fix: add micrometer-registry-prometheus dependency for /actuator/prometheus endpoint |
| 2026-04-03 | 0.1.25.3 | Use DeliveryStatus/WebhookStatus enums instead of string literals for type safety |
| 2026-04-03 | 0.1.25.3 | Bump version to 0.1.25.3 |
| 2026-04-07 | 0.1.25.4 | Fix: partial subscription update to prevent overwriting admin config changes |
| 2026-04-07 | 0.1.25.4 | Bump version to 0.1.25.4 |
| 2026-04-08 | 0.1.25.5 | Fix: force HTTP/1.1 in WebhookTransport to prevent h2c upgrade body drop (#16) |
| 2026-04-08 | 0.1.25.5 | Bump version to 0.1.25.5 |

## Last Audited

- **Date:** 2026-04-08
- **Version:** 0.1.25.5
- **Build:** PASS (116 unit tests, 0 failures, 95%+ coverage)
- **Integration test:** PASS (3 tests with Testcontainers Redis)
- **Total:** 119 tests (116 unit + 3 integration)
