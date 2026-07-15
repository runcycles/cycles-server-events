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

## [0.1.25.24] — 2026-07-15

### Fixed

- Periodic, age-gated webhook and evidence recovery prevents quick-restart
  orphans while avoiding active work owned by another replica.
- Subscription failure increments and ACTIVE/PAUSED auto-disable transitions are
  atomic and follow the spec's "exceeds threshold" rule. Required disable events
  are staged once per transition and published idempotently under concurrency.
- Evidence store outages now retain work for retry instead of dead-lettering
  transient failures. Poison-record DLQ movement is atomic.
- Producer/worker evidence identity drift retains the source in-flight and
  briefly pauses new claims instead of draining recoverable records into the DLQ.
- Evidence payloads and completed envelopes are validated against the bundled
  authoritative `cycles-evidence-v0.2` OpenAPI 3.1 / JSON Schema 2020-12
  components before storage, including nested mirror and cross-field rules.
- Persisted evidence is RFC 8785 JCS-canonical, matching the bytes expected at
  the evidence fetch endpoint.
- Terminal delivery state records the final attempt and clears obsolete retry
  and error fields.
- Missing webhook signing secrets created by a subscription/secret write race
  are retained for recovery instead of being permanently failed. Missing or
  undecryptable secrets fail closed before HTTP and are never sent unsigned.
- Delivery acknowledgements carry an owner token, closing the stale-worker
  window where a recovered predecessor could remove its successor's in-flight
  entry. Startup also rejects a recovery idle threshold at or below the global
  ordering lease.
- Fractional evidence numbers are parsed as arbitrary-precision decimals before
  binary64 safety validation, so excess precision is rejected rather than
  silently rounded and signed.
- Dispatcher outbox acknowledgement is owner-aware and counted once. Repeatedly
  unpublishable tasks now exhaust a bounded retry budget and move to a bounded
  operator DLQ instead of retrying forever.
- Recovered `RETRYING` deliveries restore their Redis retry schedule when the
  backoff has not elapsed, closing the persist-before-ZSET crash window without
  sending early.

### Changed

- Cross-replica claim/send and retention-maintenance leases prevent simultaneous
  sends and duplicate cleanup work. Retry backoff retains at-least-once semantics
  and can still complete an earlier event after a later event.
- Retry promotion is a single bounded Redis Lua operation.
- Redis supports ACL usernames, TLS, and validated connection timeouts.
- Blocking Redis claims now have a finite socket timeout; ordering-lease
  contention uses randomized backoff rather than tight scheduler polling.
- Terminal delivery state and both required dispatcher meta-events are staged in
  one Redis transaction. A leased durable outbox publishes deterministic event
  IDs and retries safely after crashes or Redis failures.
- RFC 8785 canonicalization rejects numbers that binary64 would round, preventing
  silent mathematical changes in signed evidence.
- Webhook CIDR configuration uses a strict literal-only parser and rejects
  malformed prefixes, extra path segments, hostnames, and IPv6 zone identifiers.
- The absent-config webhook egress fallback now also blocks `0.0.0.0/8`,
  `100.64.0.0/10`, and `fe80::/10`. Indeterminate security configuration emits
  a dedicated alertable metric while deliveries remain recoverable.
- Evidence lifecycle metrics now constrain `artifact_type` to the five-value
  protocol vocabulary, and malformed source JSON is logged without payload
  excerpts.
- Evidence claim scheduling now backs off on Redis, identity, signing, and store
  failures, preventing tight reconnect/log loops and rapid in-flight growth.
- CI now makes the real-Redis profile and 95% line / 95% branch gates blocking.
- Expanded failure-path and boundary tests to raise measured branch coverage
  above 95%, including Redis CAS outcomes, terminal delivery transactions,
  evidence source validation, webhook transport failures, CIDR parsing, and
  retention error handling.
- Real-Redis tests now execute delivery owner acknowledgement, dispatcher outbox
  ownership/DLQ scripts, evidence ack/DLQ scripts, and the production terminal
  failure transaction under contention.

## [0.1.25.23] — 2026-07-11

### Security

- **Last-mile webhook ownership boundary (issue
  runcycles/cycles-server-admin#209).** The dispatcher now re-evaluates
  governance **WEBHOOK SUBSCRIPTION INVARIANT 2**
  (`cycles-governance-admin-v0.1.25`) against the current event +
  subscription **immediately before every outbound POST** — initial
  delivery, retries, and recovered/orphaned-processing redeliveries all
  funnel through the single `DeliveryHandler.handle()` send path. A
  subscription owned by a **concrete tenant** (`tenant_id` present and
  `!= "__system__"`) MUST NOT be delivered an **admin-only** event. The
  admin plane already blocks admin-only selectors at subscription-write and
  at ENQUEUE, but the actual HTTP send happens here and never re-passes the
  enqueue gate — so **deliveries queued before this version deployed**, and
  **every retry/redelivery**, previously bypassed the boundary. Enforcing it
  at send time closes that hole regardless of what is already queued or
  stored. **Rolling-deploy note:** this is a per-worker guarantee — it takes
  effect only once **all** delivery workers are upgraded to this version (or
  any remaining `0.1.25.22`-or-earlier workers are stopped/drained). During a
  mixed-version rollout an old worker can still claim and send a violating
  queued delivery; the boundary is airtight only after the fleet is fully
  upgraded.

  Classification is **fail-closed** and self-contained (implemented against
  this service's own `Event`/`EventType`/`EventCategory`/`Subscription`
  models, matching admin's semantics exactly), and uses a **raw-string
  allowlist** rather than an enum lookup so it stays correct under version
  skew (a future admin event type this worker's enum has not learned is still
  blocked). A concrete-tenant delivery is **allowed only if** every supplied
  selector dimension is positively tenant-accessible by raw string: the
  **type** must start with a tenant namespace (`budget.` / `reservation.` /
  `tenant.`) and the **category** must be exactly one of `budget` /
  `reservation` / `tenant`. It is **blocked** if a supplied type is not in a
  tenant namespace, or a supplied category is not in the tenant set, or
  neither dimension positively classifies (a blank / unknown / typeless
  record). Admin-only namespaces/categories are `api_key` / `policy` /
  `webhook` / `system`. `__system__`-owned (operator) subscriptions still
  receive admin-only events, and concrete-tenant subscriptions still receive
  their tenant-accessible events — the decision is **per event**, made on the
  freshly reloaded `Event` (never a stale `Delivery.event_type` snapshot).

  A blocked delivery is dropped as **terminal** (`FAILED`, distinct
  `ownership boundary (#209)` error message; same terminal-not-retryable
  treatment as the SSRF policy block — re-sending can never make an
  ineligible event eligible), logged at WARN with
  `subscription_id`/`tenant_id`/`event_type`/`category`, and counted on the
  new `cycles_webhook_delivery_boundary_skipped_total` metric
  (`event_type`, `category` tags). It never contacts the endpoint, never
  schedules a retry, and never touches the subscription's consecutive-failure
  health. Coordinated with cycles-server-admin #209/#210 and the governance
  INVARIANT 2 spec revisions (v0.1.25.38–.41).

- **Delivery-time subscription-status re-check (already enforced, retained).**
  A non-`ACTIVE` (PAUSED/DISABLED) subscription at send time is dropped as
  terminal, closing the concurrent-disable TOCTOU where a subscription is
  disabled after enqueue.

## [0.1.25.22] — 2026-07-04

### Added

- **Delivery-time SSRF guard.** The dispatcher now re-validates the
  subscription URL against the CURRENT admin webhook-security config
  (`config:webhook-security`, managed via
  `PUT /v1/admin/config/webhook-security`) immediately before every
  outbound POST — scheme rules (`allow_http`), resolved-IP checks against
  `blocked_cidr_ranges` (incl. IPv4-mapped IPv6), and
  `allowed_url_patterns` globs. Validation semantics are a line-for-line
  port of the admin plane's create/update-time `WebhookUrlValidator`, so
  the two ends cannot disagree under the same config. Closes the gaps
  admin-side-only validation leaves open: DNS rebinding / target drift
  after creation, config tightened after creation, and legacy
  subscriptions that predate admin validation. A blocked delivery fails
  permanently (`ssrf_blocked` metric reason, no retry) and does NOT count
  against the subscription's consecutive-failure budget — a policy block
  says nothing about endpoint health, and a config tightening must not
  auto-disable subscriptions as a side effect. A config READ/PARSE failure
  is treated as INDETERMINATE, not as a denial: the delivery is left
  un-acked and retried by the stale-processing recovery once the config is
  readable again (review hardening — a transient Redis blip must never
  permanently drop a valid delivery). The restrictive defaults apply only
  when the config key is legitimately absent (never stored).

### Changed

- OPERATIONS.md now records the management-port security posture as a
  deliberate exception: 9980 is unauthenticated by design (the separate
  port is the isolation mechanism) — bind it internal-only, never publish
  on a host or ingress.

### Compatibility

- **Behavioral change for policy-violating targets only.** Subscriptions
  whose URL passes the current webhook-security config are unaffected. A
  subscription that violates the CURRENT config (created before admin
  validation existed, or before a config tightening) now permanently fails
  at dispatch instead of being delivered. With no config stored, the
  restrictive defaults apply (HTTPS required; loopback/private ranges
  blocked) — deployments that rely on http/loopback targets (e.g. local
  smoke stacks) must set `allow_http` / clear `blocked_cidr_ranges` via
  the admin config endpoint, exactly as the org nightly workflow already
  does. Residual DNS-rebinding TOCTOU (resolve-then-connect is not
  resolve-and-pin) is documented in AUDIT.md.

## [0.1.25.21] — 2026-07-03

### Added

- **`system.webhook_delivery_failed` meta-alert now emitted** when a delivery
  exhausts all retries, closing the gap against the protocol spec's retry
  contract ("delivery marked FAILED, system.webhook_delivery_failed event
  emitted"). Payload follows the admin spec's `EventDataSystem` shape
  (`component=webhook_dispatcher`, `severity=warning`, subscription/delivery
  context in `details`) with `tenant_id=__system__`. Save-only (no delivery
  fan-out happens in this service), so a failing meta-event cannot loop;
  emit failures are swallowed and logged.
- The four `*_via_tenant_cascade` EventTypes (spec v0.1.25.35) added to the
  local vocabulary, so cascade events emitted by the admin tenant-close
  cascade no longer trip the `unknown_event_type` payload-validation warning.
- Dispatcher-emitted events (`webhook.disabled`,
  `system.webhook_delivery_failed`) now carry the originating event's
  `request_id`, per the CORRELATION AND TRACING contract's requirement that
  events causally downstream of an HTTP request propagate `request_id`
  across queue boundaries.

### Fixed

- **Unknown `category` / `actor.type` values no longer poison deliveries —
  and are delivered verbatim.** Both fields previously deserialized through
  closed enums whose `@JsonCreator`s threw on unrecognized values, so an
  event carrying a newer category (exactly what happened when spec
  v0.1.25.34 added `webhook`) or an actor type this service doesn't model
  (e.g. the admin plane's `admin_on_behalf_of`) failed deserialization,
  errored the delivery, and counted against the subscription's
  consecutive-failure budget — a poison-pill that could auto-disable
  innocent subscriptions. Because the dispatcher re-serializes the same
  `Event` object as the outbound webhook body, a null-on-unknown mapping
  would have corrupted the delivered payload instead; both fields are now
  OPEN STRINGS on the wire (like `event_type` always was), so subscribers
  receive exactly what the producer wrote. Enum resolution remains as a
  local-vocabulary helper; an unrecognized category surfaces as a new
  `unknown_category` payload-validation WARN + metric, observability-only.

### Compatibility

- Additive only: one new emitted event type, `request_id` now populated on
  dispatcher-emitted events, and more tolerant inbound enum parsing. No
  outbound webhook wire-format, signature, Redis key/queue, or retry-schedule
  change.

## [0.1.25.20] — 2026-06-26

### Changed

- **Webhook processing recovery is now multi-replica safe.** Claimed delivery IDs
  are timestamped in `dispatch:processing:claimed_at`, and recovery only requeues
  `dispatch:processing` entries older than
  `DISPATCH_PROCESSING_RECOVERY_IDLE_MS` (default 120s). Rolling deploys no
  longer drain another live replica's active in-flight delivery.
- Docker entrypoint now uses `exec java ... ${JAVA_OPTS:-} -jar app.jar`, so
  operators can append JVM flags without replacing the image entrypoint.
- Tenant-labelled webhook metrics now default off via
  `CYCLES_METRICS_TENANT_TAG_ENABLED=false`; enable only when per-tenant
  Prometheus drill-down is worth the cardinality.

### Fixed

- Subscription delivery-state write failures now fail closed. Redis/JSON/write
  errors from `updateDeliveryState` throw, leaving the claimed delivery unacked
  instead of marking a delivery terminal while silently losing subscription
  counters or auto-disable state.
- `webhook.disabled` audit events and auto-disable metrics are emitted only
  after the `DISABLED` subscription state write succeeds.
- Synced the fallback webhook `User-Agent` and README examples to
  `cycles-server-events/0.1.25.20`.

## [0.1.25.19] — 2026-06-26

### Changed

- **Jedis 7.5.0 → 7.5.2** — aligns with `cycles-server` and picks up the latest
  7.5.x Redis-client patches. No code, Redis-data, webhook-wire, or spec change.

## [0.1.25.18] — 2026-06-25

### Changed

- **Webhook dispatcher now uses a reliable claim/ack queue.** Delivery IDs are
  atomically claimed with `BLMOVE dispatch:pending -> dispatch:processing` and
  acked with `LREM` only after handler state changes and retry scheduling are
  durable. Startup recovery moves orphaned `dispatch:processing` entries back to
  `dispatch:pending`, closing the crash-loss window from destructive `BRPOP`.
- Retry promotion from `dispatch:retry` to `dispatch:pending` is now a single
  Redis Lua `ZREM` + `LPUSH` operation, preserving the concurrent-worker
  duplicate guard without a remove-before-push crash window.
- Release image scanning now builds with `pull: true` and `no-cache: true`, then
  pushes that exact locally scanned image instead of doing a second cached
  rebuild.

### Fixed

- Redis, JSON, and decrypt failures in delivery/event/subscription repositories
  now propagate instead of being treated as missing records. This leaves claimed
  deliveries unacked for recovery instead of silently converting infrastructure
  trouble into permanent `event_not_found` or `subscription_not_found` outcomes.
- Encrypted webhook signing secrets now fail closed when
  `WEBHOOK_SECRET_ENCRYPTION_KEY` is missing or wrong; the dispatcher will not
  deliver an unsigned webhook after a decrypt failure.
- CyclesEvidence signing now requires a configured
  `EVIDENCE_SIGNING_PRIVATE_KEY_HEX` + `EVIDENCE_SIGNING_SIGNER_DID` pair when
  `EVIDENCE_SERVER_ID` is set. Ephemeral signing is available only for
  development with `EVIDENCE_ALLOW_EPHEMERAL_SIGNING_KEY=true`.
- Added a Redis `PING` health indicator and moved the container healthcheck to
  `/actuator/health/readiness`; liveness remains process-only.
- Added webhook lifecycle event vocabulary for `webhook.created`,
  `webhook.updated`, `webhook.paused`, `webhook.resumed`, and `webhook.deleted`
  so valid admin lifecycle events do not trigger `unknown_event_type` warnings.
- Subscription custom headers can no longer duplicate reserved delivery headers
  such as `Content-Type`, `User-Agent`, `X-Cycles-*`, `X-Request-Id`, or
  `traceparent`.

### Documentation

- Updated README, OPERATIONS, evidence identity runbook, and AUDIT notes for the
  reliable queue, Redis readiness, no-public-API deployment posture, explicit
  evidence dev-mode flag, and 47-event vocabulary.

### Compatibility

- Outbound webhook payloads and standard headers remain compatible.
- Redis producers still write delivery IDs to `dispatch:pending`; this release
  adds the consumer-owned `dispatch:processing` in-flight list for recovery.
- Docker image metadata now exposes only management port `9980`; the worker's
  `7980` app port still exists internally but serves no public API.

## [0.1.25.17] — 2026-06-24

### Fixed

- Webhook transport failure logs now use the effective trace id minted or
  resolved for outbound headers, so failures remain correlated when the source
  event did not carry `trace_id`.
- Flattened CR/LF characters in dynamic log fields for transport failures,
  retry/permanent-failure lifecycle logs, delivery success/skip/auto-disable
  logs, repository failures, scheduler Redis warnings, retention cleanup
  warnings, evidence sink ids, and evidence worker failure logs.
- Added focused `LogSanitizerTest` coverage for null, CR/LF, and non-string
  values.
- Synced the `WebhookTransport` fallback user-agent version to `0.1.25.17`.

### Compatibility

- No webhook wire, Redis queue/key, evidence envelope, event payload, or spec
  change.

## [0.1.25.16] — 2026-06-24

### Changed

- **Ops logging context review.** Webhook delivery, retry scheduler, dispatch
  loop, retention cleanup, Redis repository, event payload validation, and
  evidence worker logs now include stable operational identifiers such as
  `delivery_id`, `event_id`, `event_type`, `subscription_id`, `tenant_id`,
  `correlation_id`, `request_id`, `trace_id`, retry counts, queue names, and
  evidence source metadata where available.
- Webhook transport failures no longer log the raw subscriber URL; they log the
  subscription, tenant, event, delivery, target host, latency, trace id, and
  exception class instead.
- Evidence dead-letter and ack-failure logs avoid dumping source payloads while
  preserving safe triage fields (`artifact_type`, `evidence_id`, `trace_id`,
  `issued_at_ms`).
- Container Trivy SARIF gates now set `limit-severities-for-sarif: true`, so
  PR and release scans block on the declared `HIGH,CRITICAL` severities instead
  of failing on lower-severity fixable findings included in all-severities SARIF
  output.

### Compatibility

- No outbound webhook wire change.
- No Redis key, queue, or payload-shape change.

## [0.1.25.15] — 2026-06-23

### Fixed

- **Unconfigured CyclesEvidence is now a disabled mode.** A blank
  `EVIDENCE_SERVER_ID` prevents the evidence signer worker, envelope builder,
  and local signing key from being created, so deployments that have not enabled
  evidence no longer claim source records or dead-letter them solely because
  `server_id` is absent.
- **Retention cleanup no longer fails on non-ZSET Redis keys.** The cleanup scan
  for `events:*` can match correlation sets such as `events:correlation:*`;
  these are now skipped instead of receiving `ZREMRANGEBYSCORE` and producing
  `WRONGTYPE Operation against a key holding the wrong kind of value`.

### Documentation

- Updated README, operations notes, and the evidence identity runbook to state
  that blank `EVIDENCE_SERVER_ID` disables signing instead of dead-lettering.
- Documented that already-queued `evidence:pending` records remain pending when
  signing is disabled, and that the events worker intentionally gates on
  `server_id` while signer configuration is validated separately.

### Validation

- `mvn -B verify` passes (279 tests; JaCoCo coverage gate met).

## [0.1.25.12] — 2026-04-26

### Changed

- **Spring Boot 3.5.13 → 3.5.14.** Patch upgrade picking up upstream security
  hardening (constant-time comparison for remote DevTools secret,
  `RandomValuePropertySource` switched to `SecureRandom`, hostname
  verification applied consistently for Cassandra/RabbitMQ SSL) and bug
  fixes (`ApplicationPidFileWriter`/`ApplicationTemp` symlink handling,
  Cassandra `CqlSessionBuilder` configuration). No application-level code
  changes required.
- **Jedis 5.2.0 → 6.2.0** (major). All call sites use stable APIs
  (`JedisPool`, `Jedis`, `SetParams`, `ScanParams`/`ScanResult`,
  `JedisConnectionException`); Jedis 6.1.0 explicitly restored binary
  compatibility for `SetParams` (#4225 upstream). All 205 tests including
  the testcontainers Redis integration test pass against 6.2.0.
- **Drop `<tomcat.version>10.1.54</tomcat.version>` override.** Spring Boot
  3.5.14's BOM now manages Tomcat 10.1.54 directly, so the explicit pin
  added in 0.1.25.10 (for CVE-2026-34483 / CVE-2026-34487) is redundant.
  Same effective Tomcat version, smaller pom diff for future Spring Boot
  bumps.
- **CI: `aquasecurity/trivy-action` 0.35.0 → 0.36.0** (Trivy 0.70.0
  internally) and **`dependabot/fetch-metadata` v2 → v3** (Node 24
  runtime). Both used only by the PR container scan and Dependabot
  auto-merge workflows respectively — no runtime impact.
- `WebhookTransport` hardcoded version fallback `"0.1.25.8"` → `"0.1.25.12"`
  to match the current `pom.xml` revision (only used when
  `BuildProperties` are unavailable, e.g. in unit tests).

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
