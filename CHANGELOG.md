# Changelog

All notable changes to `cycles-server-events` are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions use
[Semantic-ish Versioning](https://semver.org/) with a fourth "patch-of-patch"
segment for same-day follow-ups.

This file is for **downstream consumers** — people pulling the Docker image or
JAR, and operators running the service. For internal engineering history (root
cause analyses, rejected alternatives, test-strategy decisions) see
[`AUDIT.md`](AUDIT.md). For operator-facing guidance (metrics, alerts, SLOs,
runbooks) see [`OPERATIONS.md`](OPERATIONS.md).

This service has **no public HTTP API** of its own — it's a Redis-driven
webhook dispatcher. "Wire format" below refers to the outbound webhook POST
request shape (headers, signature scheme, event payload JSON) and the Redis
key/queue contract it shares with `cycles-server-admin` and `cycles-server`.
Both are stable within a minor version (`0.1.x`); breaking changes would
require a minor bump. Additive fields (new optional event-payload fields, new
enum values, new optional subscription fields) are **not** considered
breaking.

## [0.1.25.6] — 2026-04-16

### Added

- `BUDGET_RESET_SPENT` event type in the `EventType` vocabulary
  (`budget.reset_spent`). Aligns with admin-spec v0.1.25.18 so events with
  this type no longer hit the `EventType.fromValue` exception path. The
  payload's `EventDataBudgetLifecycle` additions (`spent`, `reserved`,
  `spent_override_provided`) flow through unchanged because `Event.data` is
  `Map<String,Object>`.
- Seven domain-level Prometheus counters plus one latency timer, all under
  the `cycles_webhook_*` namespace. See [`OPERATIONS.md`](OPERATIONS.md) for
  the full inventory and alerting recipes. Short summary:
  - `cycles_webhook_delivery_attempts_total`
  - `cycles_webhook_delivery_success_total`
  - `cycles_webhook_delivery_failed_total`
  - `cycles_webhook_delivery_retried_total`
  - `cycles_webhook_delivery_stale_total`
  - `cycles_webhook_subscription_auto_disabled_total`
  - `cycles_webhook_events_payload_invalid_total`
  - `cycles_webhook_delivery_latency_seconds` (timer)
- Configuration flag `cycles.metrics.tenant-tag.enabled` (default `true`) —
  set to `false` in deployments with many thousands of tenants to keep
  Prometheus cardinality bounded. Same property name and default as
  `cycles-server` so operators can flip both services together.
- Non-fatal event-payload shape validation (`EventPayloadValidator`). Runs
  on every ingested event before delivery. Emits a WARN log and increments
  `cycles_webhook_events_payload_invalid_total{type, rule}` on each rule
  violation. Mirrors the warn+metric pattern from `cycles-server-admin`
  v0.1.25.12 (`EventService.validatePayloadShape`). Never throws, never
  drops, never blocks delivery.

### Changed

- Internal metric registration idioms refactored to match `cycles-server`'s
  `CyclesMetrics` conventions: dotted source names (Prometheus normalises
  to `_total`), `tags(String tenant, String... kvs)` helper, uppercase
  `UNKNOWN` sentinel for null/blank tag values. No impact on scrape output
  naming; Prometheus series are unchanged.

### Wire format

Unchanged. Upgrading from v0.1.25.5 requires no subscriber-side changes.
New events arriving with `event_type=budget.reset_spent` are delivered via
the same POST contract as any other event.

### Notes for upgraders

- New counters appear on your next Prometheus scrape. No config change
  needed; they are on by default. See [`OPERATIONS.md`](OPERATIONS.md) for
  the ready-to-paste alert rules.
- The payload validator emits WARN logs on events that don't match the
  admin spec. If your Redis stream carries events from a producer that is
  drifting (missing fields, unknown `event_type`, non-boolean
  `spent_override_provided`), these warnings will fire. The event is still
  delivered to subscribers — investigate the producer, not this service.
- Informational: `cycles-server-admin` v0.1.25.16 added dual-auth
  (ApiKeyAuth + AdminKeyAuth) on six tenant-scoped webhook REST endpoints
  with `actor_type=admin_on_behalf_of` audit metadata. This service reads
  subscriptions from Redis and does not call those REST endpoints — no
  code or config change is required. Noted for operator awareness.

## [0.1.25.5] — 2026-04-08

### Fixed

- Outbound webhook deliveries to HTTP/2 reverse proxies that silently
  upgrade `http://` to `h2c` were losing the request body. Transport now
  forces `HttpClient.Version.HTTP_1_1` on the Java `HttpClient`, bypassing
  the h2c upgrade path. Closes `cycles-server-events#16`.

### Wire format

Unchanged on the application layer. At the transport layer, outbound
requests now negotiate HTTP/1.1 only; no HTTP/2 (h2) or cleartext HTTP/2
(h2c) upgrades. Webhook receivers behind HTTP/1.1-only reverse proxies
were unaffected; receivers behind HTTP/2-capable proxies gain consistent
body delivery.

## [0.1.25.4] — 2026-04-07

### Fixed

- `SubscriptionRepository.updateDeliveryState` was rewriting the full
  subscription JSON on every delivery attempt, racing with admin-side
  PATCH writes. Under contention the admin's newer `url`, `headers`, or
  `event_types` could be overwritten by our stale in-memory copy. Switched
  to a partial update that reads the current JSON, merges only the
  delivery-state fields we own (`consecutive_failures`,
  `last_triggered_at`, `last_success_at`, `last_failure_at`, `status`),
  and writes back.

## [0.1.25.3] — 2026-04-03

### Added

- `micrometer-registry-prometheus` dependency so `/actuator/prometheus`
  returns scrapeable output. Without this, the endpoint was 404 despite
  `management.endpoints.web.exposure.include` listing `prometheus`.

### Changed

- `Delivery.status` and `Subscription.status` fields now use the typed
  `DeliveryStatus` / `WebhookStatus` enums instead of string literals.
  Jackson round-trip compatible with prior wire format via `@JsonValue`
  on the enums.

## [0.1.25.1] — 2026-04-01

### Added

- Initial implementation. Redis-driven webhook dispatcher consuming
  `dispatch:pending` via BRPOP, delivering events to subscribers with
  HMAC-SHA256 signing and exponential-backoff retry. Three core loops:
  `DispatchLoop` (BRPOP + delegate), `RetryScheduler` (ZSET drain to
  ready), `RetentionCleanupService` (hourly ZSET trim).
- v0.1.25 spec compliance: enum serialization (lowercase snake_case
  `event_type`, `actor_type`, `event_category`), full `WebhookSubscription`
  field set.
- AES-256-GCM encryption for webhook signing secrets at rest, behind the
  `WEBHOOK_SECRET_ENCRYPTION_KEY` environment variable (random 12-byte IV
  per encryption, 32-byte key enforced). Backward compatible: secrets
  stored in Redis without the `enc:` prefix are returned as plaintext.
- TTL-based retention: 90-day TTL on `event:{id}` keys, 14-day TTL on
  `delivery:{id}` keys. ZSET indexes (`events:{tenantId}`, `events:_all`,
  `deliveries:{subscriptionId}`) trimmed hourly.
- End-to-end integration test with Testcontainers Redis and an embedded
  HTTP server that verifies signature + headers on delivered requests.
- Graceful `JedisConnectionException` handling in scheduled services —
  Redis outages log a warning and skip the tick, not crash the scheduler
  thread.

### Fixed

- Duplicate delivery bug when the same delivery ID appeared twice in the
  retry ZSET. Fixed by atomic ZREM + LPUSH in a single `MULTI`/`EXEC`.
- Missing `@ExceptionHandler` on the ObjectMapper Redis boot path meant a
  corrupt subscription JSON crashed the dispatcher. Added handler +
  integration test coverage.
- Atomic SET + EXPIRE via `SETEX` (was two ops with a tiny race window
  where a key could persist past its intended TTL).
- Configurable HTTP connect/request timeouts via
  `dispatch.http.connect-timeout-seconds` /
  `dispatch.http.timeout-seconds` (was hard-coded).
- Jedis pool health check + dedicated scheduler thread pool
  (`spring.task.scheduling.pool.size=3`) so retry + cleanup + dispatch
  scheduled methods don't starve each other.
- `HttpResponse.BodyHandlers.discarding()` on the webhook POST response
  so large response bodies from misbehaving receivers don't pin memory.

### Performance

- `@Scheduled(fixedDelay=1)` on `DispatchLoop.processNext` with BRPOP's
  configurable server-side blocking timeout. The fixedDelay controls
  pause-between-polls only; the actual blocking wait happens in Redis.
  Result: a single dispatcher instance can empty the pending queue as
  fast as Redis can deliver, with bounded CPU when the queue is empty.

---

## Archive

v0.1.25.0 and earlier are pre-release. First tagged release is v0.1.25.1
(2026-04-01). v0.1.25.2 was skipped — the repo went 0.1.25.1 → 0.1.25.3
after a doc-only fast follow-up was folded into 0.1.25.3 before tagging.

[0.1.25.6]: https://github.com/runcycles/cycles-server-events/compare/v0.1.25.5...v0.1.25.6
[0.1.25.5]: https://github.com/runcycles/cycles-server-events/compare/v0.1.25.4...v0.1.25.5
[0.1.25.4]: https://github.com/runcycles/cycles-server-events/compare/v0.1.25.3...v0.1.25.4
[0.1.25.3]: https://github.com/runcycles/cycles-server-events/compare/v0.1.25.1...v0.1.25.3
[0.1.25.1]: https://github.com/runcycles/cycles-server-events/releases/tag/v0.1.25.1
