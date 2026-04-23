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

## [0.1.25.11] — 2026-04-23

### Added

- **Dispatcher emits `webhook.disabled` Event on auto-disable.** Implements the
  dispatcher half of the spec v0.1.25.33 webhook lifecycle contract (operator
  lifecycle emits were wired into `cycles-server-admin` v0.1.25.39). When a
  subscription's consecutive-failure counter crosses
  `disable_after_failures`, `DeliveryHandler` now writes an audit-trail Event
  to the shared Redis store alongside the existing status flip to `DISABLED`
  and the `cycles_subscription_auto_disabled_total` metric increment.
  - `event_type` = `webhook.disabled`, `category` = `webhook`.
  - `correlation_id` = `webhook_auto_disable:<subscription_id>:<delivery_id>`
    — the triggering delivery's id is the "failure batch" identifier the
    spec calls for, letting operators pivot from the auto-disable Event to
    the final failed delivery and from there to the upstream event via
    existing `GET /v1/admin/events?correlation_id=…` and
    `GET /v1/admin/webhooks/deliveries?…` JOINs.
  - Payload conforms to `EventDataWebhookLifecycle`: includes
    `subscription_id`, `tenant_id`, `previous_status`, `new_status`
    (`DISABLED`), empty `changed_fields`, and
    `disable_reason="consecutive_failures_exceeded_threshold"`.
  - `actor.type` = `system`; `source` = `cycles-events`.
  - `trace_id` is copied from the triggering Delivery when present (same
    precedence rule as spec v0.1.25.28 trace stamping on the Delivery
    record itself).
  - Emit is best-effort: any Redis write failure is logged at WARN but does
    **not** revert the status flip or the metric. The subscription state
    transition is the source of truth; the audit trail is additive.

### Changed

- **`EventType` enum gains `WEBHOOK_DISABLED("webhook.disabled", WEBHOOK)`**
  and **`EventCategory` enum gains `WEBHOOK("webhook")`**. Both additive, no
  wire break for consumers that ignore unknown enum values (standard
  Jackson/OpenAPI codegen behaviour).
- **`EventRepository` gains `save(Event)`.** Mirrors the admin-side Lua
  script pattern verbatim so dispatcher-emitted Events land under the same
  Redis key shape admin reads from: `event:<id>` with TTL plus ZADD on the
  per-tenant and global indexes plus optional SADD on
  `events:correlation:<cid>`. TTL is governed by the same
  `events.retention.event-ttl-days` config (default 90). No change to
  existing `findById`.

### Compatibility

- Additive-only change. No config migration. Existing deployments continue
  to run unchanged; the new Event simply starts appearing under
  `GET /v1/admin/events?event_type=webhook.disabled` when auto-disable
  fires. `cycles-server-admin` v0.1.25.39 is the minimum admin version for
  the operator-side lifecycle emits; no admin version bump is required to
  consume the dispatcher-side emit since admin reads the shared Redis store
  directly.

## [0.1.25.10] — 2026-04-19

### Changed

- **Spring Boot 3.5.11 → 3.5.13 + Tomcat 10.1.54 pin.** Supply-chain CVE
  cleanup. Closes four HIGH/CRITICAL CVEs on `tomcat-embed-core`:
  CVE-2026-29145 (CRITICAL) and CVE-2026-29129 (HIGH) via Spring Boot
  3.5.13's managed Tomcat 10.1.53, plus CVE-2026-34483 (HIGH) and
  CVE-2026-34487 (HIGH) via explicit `<tomcat.version>10.1.54</tomcat.version>`
  pin until Spring Boot ships 10.1.54+ as its managed version. No code
  changes; all 195 tests pass.

### Note

- v0.1.25.9 (actuators moved to a separate management port, see entry
  below) and v0.1.25.10 both landed on main during the release
  consolidation window and ship together as v0.1.25.10. The .9 entry
  is preserved below for historical accuracy of the change set; image
  tag `ghcr.io/runcycles/cycles-server-events:0.1.25.10` includes both
  changes.

## [0.1.25.9] — 2026-04-18

### Changed

- **Actuators moved to a separate management port (9980).** Previously, the
  `health`, `info`, and `prometheus` actuator endpoints were served on the
  public API port `7980` alongside the dispatch control surface. They now bind
  to a dedicated `management.server.port` (default `9980`, override via
  `MANAGEMENT_PORT` env var) so they're never reachable from the public API
  port. The exposure list (`health,info,prometheus`) is unchanged — only the
  port. Clears CodeQL `java/spring-boot-exposed-actuators-config` and aligns
  the service with the standard defense-in-depth deployment pattern: expose
  7980 via public ingress / external ClusterIP, keep 9980 on an
  internal-only ClusterIP scraped by Prometheus.

### Migration

- **Prometheus scrape configs must update their target port** from `7980` →
  `9980` (or whatever `MANAGEMENT_PORT` is set to). See the Monitoring section
  of `README.md` for the updated scrape example.
- **In-cluster healthchecks** (kubelet probes, Docker `HEALTHCHECK`) must hit
  `:9980/actuator/health` instead of `:7980/actuator/health`. The published
  Docker image's `HEALTHCHECK` has already been updated.
- **No wire-format change.** Event payloads, signature scheme, and Redis key
  contract are unchanged.

## [0.1.25.8] — 2026-04-18

### Added

- **Cross-surface correlation on the `WebhookDelivery` schema.** Aligns
  with admin-spec `cycles-governance-admin-v0.1.25.yaml` info.version
  `0.1.25.28`, which closes the gap left by v0.1.25.27 by extending
  `trace_id` correlation onto `WebhookDelivery`. Three new OPTIONAL
  fields on the `Delivery` model:
  - `trace_id` (`^[0-9a-f]{32}$`) — captured at dispatch time from
    the originating Event so operators can JOIN a delivery record with
    the event that produced it, the audit entry for the originating
    HTTP request, and sibling deliveries in the same fan-out.
  - `trace_flags` (`^[0-9a-f]{2}$`) — the W3C Trace Context
    trace-flags byte to use when constructing the outbound
    `traceparent` header, preserving the inbound sampling decision.
  - `traceparent_inbound_valid` (boolean) — whether the originating
    request presented a valid W3C `traceparent`. When `true`, the
    dispatcher honours `trace_flags`; when `false`/null, it defaults
    to `01` (sampled) per cycles-protocol-v0 §CORRELATION AND TRACING.
- **`TraceContext.buildTraceparent(traceId, traceFlags)`** — second
  argument threads the sampling byte through to the outbound header.
  Invalid / null / blank / non-2-hex values fall back to `01`, so the
  existing always-required header contract remains unconditionally
  satisfied.
- **Proactive `trace_id` stamping on the `Delivery` record.**
  `DeliveryHandler` copies `Event.trace_id` onto `Delivery.trace_id`
  before persisting, when the delivery didn't already carry one. This
  fills the gap while `cycles-server-admin` hasn't yet caught up to
  spec v0.1.25.28 — admin's `GET /v1/admin/webhooks/deliveries`
  readback now has a populated `trace_id` without a cross-service
  round trip. Admin-authored values are never overwritten (forward
  compatibility with the eventual admin v0.1.25.31+).

### Changed

- `Transport.deliver` signature gains a `Delivery` parameter so the
  transport can read the sampling hints. Callers with no delivery
  context (ad-hoc webhook-test POSTs, unit tests exercising just the
  transport) pass `null`; the transport treats that as
  `traceparent_inbound_valid=false` and uses `01`.
- `WebhookTransport` version fallback bumped from `0.1.25.7` to
  `0.1.25.8` (used when `BuildProperties` is unavailable, e.g., tests).

### Unchanged

- All existing outbound headers (`X-Cycles-Trace-Id`, `traceparent`,
  `X-Request-Id`, `X-Cycles-Signature`, etc.) remain in place with
  byte-identical wire format when `delivery` is null or missing the
  new fields — which is true of every existing delivery record today
  because admin hasn't yet implemented its half of spec v0.1.25.28.
- HMAC-SHA256 canonical string, Redis schema, metric names, tag
  schemas, retry policy.

### Spec-impl wiring note

`cycles-server-admin` v0.1.25.31 (shipped 2026-04-18) implemented the
admin-side half of spec v0.1.25.28. Admin's
`WebhookDispatchService.createDelivery` now writes `trace_id` +
`trace_flags` + `traceparent_inbound_valid` on every delivery record
from its `TraceContextFilter` request attributes (fallback to
`event.trace_id` when off-request). Events-server v0.1.25.8 consumes
those fields unchanged — **field names, JSON types, enum values, and
`@JsonIgnoreProperties` strictness are all wire-compatible**, verified
by the `inboundTraceFlagsPreserved` integration test which mirrors
admin's exact write format.

The proactive `Delivery.trace_id` stamping in this release remains
useful as a rolling-upgrade safety net: in-flight delivery records
written by a pre-v0.1.25.31 admin still get their `trace_id` back-filled
from `Event.trace_id` so admin's `GET /v1/admin/webhooks/deliveries`
readback is consistent across the rollout window.

## [0.1.25.7] — 2026-04-18

### Added

- **Cross-surface correlation — `trace_id` and W3C Trace Context headers
  on every outbound webhook delivery.** Aligns with admin-spec
  `cycles-governance-admin-v0.1.25.yaml` info.version `0.1.25.27`, which
  adds `Event.trace_id` (optional, `^[0-9a-f]{32}$`) as the JOIN key across
  an HTTP request, its audit entry, and all events emitted as side effects
  of that request. The authoritative header contract lives in
  `cycles-protocol-v0.yaml:256-277`: outbound webhook POSTs MUST carry
  `X-Cycles-Trace-Id` AND `traceparent` (W3C Trace Context v00).
  - New `Event.trace_id` field, snake-case JSON, `@JsonInclude(NON_NULL)`.
    Older Event rows (pre-v0.1.25.27) that lack the field are tolerated;
    `@JsonIgnoreProperties(ignoreUnknown=true)` on the model keeps the
    deserializer forward-compatible with further additive spec evolution.
  - New `TraceContext` helper (transport layer). Resolves the event's
    `trace_id` if present and well-formed; otherwise mints a fresh 128-bit
    id via `SecureRandom` so the outbound-header "always required"
    contract is unconditionally honoured. The W3C `traceparent` is
    assembled with a **freshly generated span-id per delivery** (never
    reused from any inbound source) and `trace-flags=01` — the dispatcher
    has no inbound W3C parent to inherit from.
  - `WebhookTransport` now emits three new headers on every POST:
    - `X-Cycles-Trace-Id: <32-hex-lowercase>` (always present)
    - `traceparent: 00-<trace_id>-<16-hex-span>-01` (always present)
    - `X-Request-Id: <event.request_id>` (present only when the Event
      carries `request_id`; follows the spec v0.1.25.27 strengthened
      contract that `request_id` MUST be propagated across
      thread / queue / process boundaries when it originated upstream)
- **Non-fatal `trace_id_shape` validation rule** in
  `EventPayloadValidator`. If a producer writes a malformed `trace_id`
  (anything other than exactly 32 lowercase hex characters), the validator
  emits a WARN log line and increments
  `cycles_webhook_events_payload_invalid_total{type, rule="trace_id_shape"}`
  — same observability pattern as the existing rules. Delivery is never
  blocked or dropped; the dispatcher falls back to minting a fresh id so
  the outbound header stays well-formed regardless of producer drift.

### Changed

- `Event` model gains `@JsonIgnoreProperties(ignoreUnknown = true)`,
  matching `Subscription`'s defensive posture. Spring Boot's default
  `ObjectMapper` already has `FAIL_ON_UNKNOWN_PROPERTIES=false`, so this is
  belt-and-braces insurance against alternate mapper configurations (e.g.,
  tests constructing their own ObjectMapper).
- `WebhookTransport` constructor now takes `TraceContext` as a required
  dependency. The hard-coded version fallback (used when `BuildProperties`
  is unavailable, e.g., in tests) is bumped from `0.1.25.6` to `0.1.25.7`.

### Unchanged

- HMAC-SHA256 signing algorithm and the canonical string (raw JSON body
  bytes) — no change per `cycles-protocol-v0.yaml:279-285`.
- Redis schema, key naming, queue contract, TTL / retention policy.
- Metric names, tag schema, and default tenant-tag behaviour.
- All pre-existing headers (`Content-Type`, `User-Agent`,
  `X-Cycles-Event-Id`, `X-Cycles-Event-Type`, `X-Cycles-Signature`, and
  subscription-configured custom headers) — only additions.

### Spec parity gap analysis (for the paper trail)

Spec v0.1.25.19 through v0.1.25.26 landed between the v0.1.25.6 freeze and
this release. None of them require changes in the events-server
dispatcher; the [`AUDIT.md`](AUDIT.md) "Not applicable to events server"
table documents the reasoning per version so a future reviewer doesn't
re-litigate.

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
