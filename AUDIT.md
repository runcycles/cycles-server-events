# Cycles Protocol v0.1.25 — Events Server Implementation Audit

**Date:** 2026-04-18 (v0.1.25.8 — admin-spec v0.1.25.28 alignment: extend correlation/tracing onto `WebhookDelivery`. Add three optional fields to `Delivery` model (`trace_id`, `trace_flags`, `traceparent_inbound_valid`); `TraceContext.buildTraceparent` now accepts a `trace_flags` byte so outbound `traceparent` preserves inbound sampling decisions when `traceparent_inbound_valid=true`; `Transport.deliver` gains a `Delivery` parameter so the transport can read the sampling hints. Proactive `trace_id` stamping: `DeliveryHandler` copies `Event.trace_id` onto the persisted `Delivery` record when admin hasn't set one, filling the gap while `cycles-server-admin` catches up to spec v0.1.25.28 (no overwrite if admin has already stamped).), 2026-04-18 (v0.1.25.7 — admin-spec v0.1.25.27 alignment: three-tier correlation/tracing. Add `Event.trace_id` (optional, `^[0-9a-f]{32}$`); new `TraceContext` helper resolves-or-mints trace-id and builds W3C `traceparent` v00 with fresh span-id per delivery; WebhookTransport emits `X-Cycles-Trace-Id` + `traceparent` on every outbound POST and forwards `X-Request-Id` when event carries `request_id`; EventPayloadValidator gains non-fatal `trace_id_shape` rule. Documents negative findings for spec v0.1.25.19–.26 (admin-plane-only changes that do not affect the dispatcher).), 2026-04-16 (v0.1.25.6 — admin-spec v0.1.25.18 alignment: add `BUDGET_RESET_SPENT`; add `cycles_webhook_*` Micrometer counters + latency timer mirroring `cycles-server` v0.1.25.10; add non-fatal `EventPayloadValidator` mirroring `cycles-server-admin` v0.1.25.12; parity refactor adopting dotted metric names, `tags(...)` helper, tenant-tag toggle, `UNKNOWN` sentinel; add `CHANGELOG.md` + `OPERATIONS.md` for doc parity), 2026-04-08 (v0.1.25.5 — force HTTP/1.1 outbound transport to fix h2c body drop, #16), 2026-04-07 (v0.1.25.4 — partial subscription update to avoid overwriting admin config), 2026-04-03 (v0.1.25.3 — Prometheus registry dependency; typed `DeliveryStatus`/`WebhookStatus` enums), 2026-04-01 (v0.1.25.1 initial implementation — dispatch loop, delivery handler, retry scheduler, AES-256-GCM secret encryption, TTL-based retention, E2E integration test).

**Spec:** `cycles-governance-admin-v0.1.25.yaml` (OpenAPI 3.1.0, v0.1.25.28) — authoritative source at `cycles-protocol` repo; served from `cycles-server-admin`.

**Service:** Spring Boot 3.5.11 / Java 21 / Jedis 5.2.0 / Micrometer Prometheus registry. Redis-driven webhook dispatcher (no inbound API surface of its own).

**Downstream docs:**
- [`CHANGELOG.md`](CHANGELOG.md) — release notes for consumers (Keep-a-Changelog format)
- [`OPERATIONS.md`](OPERATIONS.md) — operator runbook (metrics, alerts, SLOs, incident playbook)
- [`README.md`](README.md) — quickstart, architecture, configuration

---

## Service Overview

| Field | Value |
|-------|-------|
| Service | cycles-server-events |
| Version | 0.1.25.8 |
| Java | 21 |
| Spring Boot | 3.5.11 |
| Spec Authority | [complete-budget-governance-v0.1.25.yaml](https://github.com/runcycles/cycles-server-admin/blob/main/complete-budget-governance-v0.1.25.yaml) |

## Test Coverage

| Metric | Value |
|--------|-------|
| Total tests | 199 |
| Unit tests | 195 |
| Integration tests | 4 (WebhookDeliveryIntegrationTest) |
| JaCoCo minimum | 95% line coverage (enforced) |

## Source File Inventory (22 classes)

| Layer | File | Tests |
|-------|------|-------|
| App | EventsApplication.java | EventsApplicationTest (1) |
| Config | RedisConfig.java | RedisConfigTest (3) |
| Config | EventsConfig.java | EventsConfigTest (1) |
| Config | CryptoService.java | CryptoServiceTest (9) |
| Metrics | CyclesMetrics.java | CyclesMetricsTest (17) |
| Model | Event.java | ModelTest (31 total) |
| Model | EventType.java (41 types) | ModelTest |
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
| Service | DeliveryHandler.java | DeliveryHandlerTest (36) |
| Service | DispatchLoop.java | DispatchLoopTest (4) |
| Service | RetryScheduler.java | RetrySchedulerTest (3) |
| Service | RetentionCleanupService.java | RetentionCleanupServiceTest (3) |
| Transport | Transport.java (interface) | (via WebhookTransportTest) |
| Transport | TransportResult.java | ModelTest |
| Transport | PayloadSigner.java | PayloadSignerTest (5) |
| Transport | WebhookTransport.java | WebhookTransportTest (19) |
| Transport | TraceContext.java | TraceContextTest (11) |
| Validation | EventPayloadValidator.java | EventPayloadValidatorTest (24) |
| Integration | - | WebhookDeliveryIntegrationTest (4) |

*Note: Surefire excludes \*IntegrationTest by default. `mvn verify` runs unit tests only; `mvn verify -Pintegration-tests` includes integration (removes exclusion).*

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
| Actuators isolated to separate management port (0.1.25.9) | PASS |

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
| management.server.port | 9980 | MANAGEMENT_PORT | OK (0.1.25.9: actuators off public port) |

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
| 41 event types across 6 categories (v0.1.25.18 incl. budget.reset_spent) | PASS |
| Enum serialization (lowercase) | PASS - ActorType, EventCategory, EventType |
| Status fields use enums | PASS - DeliveryStatus, WebhookStatus (not string literals) |
| Subscription model fields | PASS - all spec fields present |
| HMAC-SHA256 webhook signing | PASS |
| Retry with exponential backoff | PASS |
| Event TTL 90 days | PASS |
| Event.trace_id optional + `^[0-9a-f]{32}$` (spec v0.1.25.27) | PASS |
| Outbound webhook headers X-Cycles-Trace-Id + traceparent always present (spec v0.1.25.27 + cycles-protocol-v0.yaml) | PASS |
| Outbound X-Request-Id forwarded when Event.request_id present | PASS |
| `@JsonIgnoreProperties(ignoreUnknown=true)` on Event — tolerates additive spec evolution | PASS |
| `WebhookDelivery.trace_id` / `trace_flags` / `traceparent_inbound_valid` optional on wire (spec v0.1.25.28) | PASS |
| Outbound `traceparent` preserves inbound sampling when `traceparent_inbound_valid=true`, else defaults to `01` | PASS |
| `DeliveryHandler` proactively stamps `Event.trace_id` → `Delivery.trace_id` when admin has not pre-populated | PASS |
| Admin-authored `Delivery.trace_id` is never overwritten by the dispatcher | PASS |
| Wire-verified against `cycles-server-admin` v0.1.25.31 — field names, JSON types, enum values, `@JsonIgnoreProperties` strictness all compatible | PASS |
| Wire-verified against `cycles-server` v0.1.25.14 runtime — `EventEmitterRepository.createDelivery` writes identical field set; all 6 emitted `EventType`s (BUDGET_EXHAUSTED, BUDGET_OVER_LIMIT_ENTERED, BUDGET_DEBT_INCURRED, RESERVATION_DENIED, RESERVATION_EXPIRED, RESERVATION_COMMIT_OVERAGE) present in events-server's vocabulary | PASS |

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
| 2026-04-16 | 0.1.25.6 | Add BUDGET_RESET_SPENT to EventType enum (admin-spec v0.1.25.18 alignment; 40→41 types) |
| 2026-04-16 | 0.1.25.6 | Add cycles.webhook.* Micrometer domain counters + delivery_latency timer (mirrors cycles-server v0.1.25.10) |
| 2026-04-16 | 0.1.25.6 | Add non-fatal event-payload shape validation (warn + metric; mirrors cycles-server-admin v0.1.25.12 commit bc9f075) |
| 2026-04-16 | 0.1.25.6 | Parity refactor: adopt cycles-server's dotted metric names, tags() helper, tenant-tag cardinality toggle, and UNKNOWN sentinel. Rename payload-validation metric to cycles.webhook.events.payload.invalid{type, rule} for alignment with admin's cycles_admin_events_payload_invalid_total{type, expected_class} |
| 2026-04-16 | 0.1.25.6 | Docs: note admin v0.1.25.16 dual-auth on 6 tenant webhook REST endpoints (no code change; this service reads Redis directly) |
| 2026-04-16 | 0.1.25.6 | Docs: make README JAR run command version-agnostic (target/cycles-server-events-*.jar) |
| 2026-04-16 | 0.1.25.6 | Bump version to 0.1.25.6 |
| 2026-04-18 | 0.1.25.7 | Add `Event.trace_id` (optional `^[0-9a-f]{32}$`) — spec v0.1.25.27 three-tier correlation model |
| 2026-04-18 | 0.1.25.7 | Add `TraceContext` helper — resolves event `trace_id` or mints fresh 128-bit id; builds W3C `traceparent` v00 with fresh span-id per delivery |
| 2026-04-18 | 0.1.25.7 | WebhookTransport emits `X-Cycles-Trace-Id` + `traceparent` on every delivery (always-required per cycles-protocol-v0.yaml:261-266); forwards `X-Request-Id` when `event.request_id` present |
| 2026-04-18 | 0.1.25.7 | EventPayloadValidator: new non-fatal `trace_id_shape` rule — warns + increments metric when `trace_id` present but doesn't match `^[0-9a-f]{32}$` |
| 2026-04-18 | 0.1.25.7 | `@JsonIgnoreProperties(ignoreUnknown=true)` on `Event` to stay forward-compatible with additive spec evolution |
| 2026-04-18 | 0.1.25.7 | Bump version to 0.1.25.7 |
| 2026-04-18 | 0.1.25.8 | Add `Delivery.trace_id` / `trace_flags` / `traceparent_inbound_valid` optional fields — spec v0.1.25.28 |
| 2026-04-18 | 0.1.25.8 | `TraceContext.buildTraceparent(traceId, traceFlags)` — honors inbound sampling byte, falls back to `01` on null/blank/malformed |
| 2026-04-18 | 0.1.25.8 | `Transport.deliver` gains `Delivery` parameter; WebhookTransport reads `delivery.traceFlags` only when `traceparent_inbound_valid=true`, else defaults `01` |
| 2026-04-18 | 0.1.25.8 | Proactive `trace_id` stamping in `DeliveryHandler`: copies `Event.trace_id` onto `Delivery.trace_id` when admin has not pre-set; never overwrites admin-authored values |
| 2026-04-18 | 0.1.25.8 | Bump version to 0.1.25.8 |
| 2026-04-18 | 0.1.25.8 | Wire-verified against `cycles-server-admin` v0.1.25.31 (shipped 2026-04-18). Admin's `WebhookDispatchService.createDelivery` writes `trace_id` + `trace_flags` + `traceparent_inbound_valid` from `TraceContextFilter` request attributes (fallback `event.trace_id`). Events-server's `Delivery` model reads them unchanged. Admin's `WebhookDelivery` is `@JsonIgnoreProperties(ignoreUnknown=false)` (strict); events-server's field set matches exactly — safe. Integration test `inboundTraceFlagsPreserved` extended to mirror admin's exact write format and to assert admin-authored `trace_id` survives the dispatcher's write-back. |
| 2026-04-18 | 0.1.25.8 | Wire-verified against `cycles-server` v0.1.25.14 runtime. `EventEmitterRepository.createDelivery` writes delivery records to the same Redis keyspace (`delivery:<id>` / `dispatch:pending` list) using the same `WebhookDelivery` shape; trace fields propagate from runtime request via transient `Event.traceFlags`/`traceparentInboundValid` @JsonIgnore fields into `WebhookDelivery`. Runtime's `WebhookDelivery` has no strict `@JsonIgnoreProperties(ignoreUnknown=false)` — events-server's write-back is safe (strictest consumer is still admin, which already passes). All 6 `EventType`s emitted by cycles-server (BUDGET_EXHAUSTED, BUDGET_OVER_LIMIT_ENTERED, BUDGET_DEBT_INCURRED, RESERVATION_DENIED, RESERVATION_EXPIRED, RESERVATION_COMMIT_OVERAGE) are recognised by events-server's 41-type vocabulary. No code changes required. |

### Not applicable to events server (v0.1.25.19 → v0.1.25.27 negative findings)

Captured explicitly so a future reviewer doesn't re-litigate the gap analysis:

| Spec ver | Change | Why events server is unaffected |
|---|---|---|
| v0.1.25.19 | `BudgetLedger.tenant_id` on wire | Events already carry `tenant_id`; no dependency on BudgetLedger serialization. |
| v0.1.25.20 | `sort_by` / `sort_dir` on admin lists | Admin-read-only endpoints. |
| v0.1.25.21 | `search` on admin lists + tenants/webhooks bulk-action | Admin-read/mutate only. `system.webhook_status_changed` events from bulk PAUSE/RESUME/DELETE already dispatchable under `WebhookStatus` enum. |
| v0.1.25.22 | Editorial cleanup | No wire or schema change. |
| v0.1.25.23 | `COUNT_MISMATCH` + `LIMIT_EXCEEDED` in `ErrorCode` enum | Dispatcher does not emit `ErrorResponse`. |
| v0.1.25.24 | Audit log filter DSL upgrade | Admin-read only. |
| v0.1.25.25 | Audit `__admin__` / `__unauth__` sentinel split | Events server does not write audit entries. |
| v0.1.25.26 | `POST /v1/admin/budgets/bulk-action` | `BUDGET_RESET_SPENT` already landed in events-server v0.1.25.6; admin emits one event per row, dispatcher delivers unchanged. |

## Last Audited

- **Date:** 2026-04-18
- **Version:** 0.1.25.8
- **Build:** PASS (195 unit tests, 95%+ coverage enforced)
- **Integration test:** PASS (4 Testcontainers Redis tests — incl. end-to-end `traceparent_inbound_valid=true` trace-flags preservation)
- **Total:** 199 tests

## Cross-Repo Spec Drift Notes (informational)

Changes in sibling repos between v0.1.25.5 and v0.1.25.18 that did **not**
require code changes here, but are worth knowing:

- **admin v0.1.25.13** — CORS allowedMethods + PUT (admin-plane only).
- **admin v0.1.25.14** — dual-auth on createBudget/createPolicy/updatePolicy (admin-plane).
- **admin v0.1.25.15** — canonical `ScopeValidator` (admin write-time validation;
  scopes stored in Redis are unchanged; pass-through here).
- **admin v0.1.25.16** — dual-auth (ApiKeyAuth + AdminKeyAuth) on 6 tenant-scoped
  webhook REST endpoints; adds `actor_type=admin_on_behalf_of` audit metadata on
  PATCH/DELETE/test. This service reads subscriptions from Redis and does not call
  those REST endpoints, so no code change was required. README updated with a
  note so operators know this is available.
- **admin v0.1.25.17** — cjson round-trip sweep for ApiKey/Policy/Tenant reads
  (admin-plane persistence; no effect here).
- **admin v0.1.25.12** — runtime event-payload shape validation (warn + metric;
  commit bc9f075 in cycles-server-admin, `EventService.validatePayloadShape`).
  Mirrored here at v0.1.25.6 via `EventPayloadValidator` +
  `cycles_webhook_events_payload_invalid_total`. Approach differs: admin uses
  Jackson `convertValue` round-trip through its typed payload DTOs
  (`EventPayloadTypeMapping`); we apply hand-rolled rules because admin's DTOs
  live in a module we don't depend on. Metric tag schema
  (`type`, `rule`) parallels admin's (`type`, `expected_class`).
- **server v0.1.25.10** — `cycles.*` Micrometer domain counters. Mirrored at
  v0.1.25.6 via `CyclesMetrics` + `cycles.webhook.*` counter family,
  adopting cycles-server's exact idiom: dotted source names (Prometheus
  normalises to `_total` on scrape), `tags(String tenant, String... kvs)`
  helper, `cycles.metrics.tenant-tag.enabled` toggle for high-cardinality
  deployments, `UNKNOWN` sentinel for null/blank tag values. Added a Timer
  for outbound webhook latency — deliberate deviation since cycles-server
  relies on Spring's auto-emitted `http.server.requests` which only covers
  inbound traffic.
- **server / admin v0.1.25.18** — `budget.reset_spent` event type and
  `EventDataBudgetLifecycle` additions (`spent`, `reserved`, `spent_override_provided`).
  `Event.data` is `Map<String,Object>` so the new payload fields pass through
  serialization untouched; only the `EventType` vocabulary needed the new
  `BUDGET_RESET_SPENT` value (added at v0.1.25.6).
