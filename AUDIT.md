# Cycles Protocol v0.1.25 — Events Server Implementation Audit

## Implementation History

### 2026-07-11 — v0.1.25.23: last-mile webhook ownership/category boundary at delivery (#209)

SECURITY. The load-bearing delivery half of issue
runcycles/cycles-server-admin#209. Governance authority:
`cycles-governance-admin-v0.1.25.yaml` **WEBHOOK SUBSCRIPTION INVARIANT 2**
— a subscription owned by a CONCRETE tenant (`tenant_id` present and
`!= "__system__"`) MUST NOT be delivered an admin-only event (categories
`api_key` / `policy` / `webhook` / `system`; tenant-accessible are
`budget` / `reservation` / `tenant`).

The admin service enforces the invariant at subscription create/update
(`WebhookCategoryBoundaryValidator`) and at ENQUEUE
(`WebhookDispatchService.isBlockedByOwnershipBoundary`). But this worker
performs the actual HTTP POST, retries, and recovered-processing
redeliveries, and it dequeues from the shared Redis queue without
re-checking the boundary. So the durable guarantee had a hole: deliveries
**already queued before this version deployed**, plus every retry
(`dispatch:retry`) and every recovered/orphaned redelivery (startup
`recoverStaleProcessing` re-queues stale `dispatch:processing` back to
`dispatch:pending`), bypassed the check. All three send paths converge on
the single `DeliveryHandler.handle()` funnel — `DispatchLoop` calls it for
pending/retry, and recovery just re-queues into pending for the same loop —
so enforcing the boundary once inside `handle()`, immediately before the
`transport.deliver(...)` call, covers initial + retry + recovered by
construction and is evaluated at SEND time (so pre-upgrade queued
deliveries are caught). ROLLING-DEPLOY caveat: this is a per-worker
guarantee — airtight only once ALL delivery workers run this version (or
remaining `0.1.25.22`-or-earlier workers are stopped/drained). During a
mixed-version rollout an old worker can still claim and send a violating
queued delivery; called out plainly in the CHANGELOG for operators.

Classification (`WebhookOwnershipBoundary`, new, self-contained, I/O-free):
mirrors admin's fail-closed intent but against THIS service's own models
(no dependency on admin classes). Owner: `isSystemOwner(tenantId)` =
`tenantId == null || "__system__".equals(tenantId)` — a blank/whitespace
owner is CONCRETE (matches admin `WebhookSubscription.isSystemOwner`).

Codex-review correction (REVISE-MAJOR round): the first cut classified via
the `EventCategory`/`EventType` ENUMS and treated a record as unclassifiable
only when BOTH dimensions were unknown (`type == null && category == null`).
That FAILS OPEN on a single dimension and is dangerous under VERSION SKEW:
this worker deliberately preserves unknown event strings byte-for-byte, so a
newly-introduced admin event type (a future `system.*` / `api_key.*`)
reaches an old worker before its enum knows the value, resolves to `null`,
and — with the other dimension happening to be tenant-accessible — slipped
through. The predicate now classifies by RAW STRING allowlist, fail-closed
per SUPPLIED dimension: a concrete-tenant delivery is ALLOWED only if every
supplied (non-null) dimension positively classifies AND at least one does —
the `event_type` must start with a tenant NAMESPACE prefix (`budget.` /
`reservation.` / `tenant.`) and the `category` must be exactly one of
`{budget, reservation, tenant}`. BLOCKED if a supplied type is not in a
tenant namespace, OR a supplied category is not in the tenant set, OR neither
dimension positively classifies (blank / unknown / typeless). A future
`budget.new_thing` is correctly allowed (positive namespace match); a
future/unknown `system.*` / `api_key.*` is blocked even though the enum
cannot resolve it. `null` = absent (not a violation by itself);
present-but-blank/whitespace = supplied violation = blocked. The tenant
category set and type-namespace prefixes are DERIVED from
`EventCategory.isTenantAccessible()` (single source of truth, to which
`EventType.isTenantAccessible()` now delegates), relying on the governance
alignment that a tenant type namespace is the tenant category value + `.`.
The block decision AND its emitted metric/log signal use the freshly
RELOADED `Event`, never the possibly-stale `Delivery.event_type` snapshot.

Terminal handling: a blocked delivery is DROPPED as terminal via the
existing `markFailed` (status `FAILED`, distinct error message
`"Delivery blocked by webhook ownership boundary (#209): …"`), the SAME
terminal-not-retryable pattern the delivery-time SSRF guard uses — the
event is policy-ineligible for this endpoint and re-sending can never make
it eligible, so it must NOT retry forever. It never contacts the endpoint,
never schedules a retry, and never increments the subscription's
consecutive-failure counter (a boundary skip says nothing about endpoint
health). Emits WARN with `subscription_id`/`tenant_id`/`event_type`/
`category`/`trace_id` and a new metric
`cycles_webhook_delivery_boundary_skipped_total{event_type,category}`
(parallels admin's `cycles_admin_webhook_dispatched_total{result="boundary_skipped"}`).
Placed AFTER the existing status-ACTIVE gate and BEFORE the SSRF guard, so
a blocked event short-circuits with no signing-secret fetch and no URL/DNS
check.

Design note — DeliveryStatus enum reuse: the boundary skip reuses the
existing terminal `FAILED` status (with a distinct error message + distinct
metric) rather than introducing a new `DeliveryStatus` value. `Delivery`
records are written here and read back by the admin plane; adding an enum
value would be a cross-service wire change to the shared Redis contract for
no security benefit, since `FAILED` is already terminal and non-retryable
(`handle()` early-returns on any non-`PENDING`/`RETRYING` status). This
follows the SSRF-blocked precedent exactly. The distinct signal for
operators/dashboards lives in the dedicated metric and the WARN log, not in
the status enum.

Requirement #2 (delivery-time status re-check) was already satisfied: the
pre-existing `sub.getStatus() != ACTIVE` gate in `handle()` drops
PAUSED/DISABLED subscriptions as terminal, closing the concurrent-disable
TOCTOU. Kept as-is for consistency.

Coordinated with cycles-server-admin #209 (write-path + enqueue-path +
reconciler) / #210 and governance INVARIANT 2 revisions
(v0.1.25.38–.41, `cycles-protocol` main). Tests: unit coverage for the
predicate (`WebhookOwnershipBoundaryTest`) across every classification
branch — including the version-skew fail-closed cases (future
admin-namespace type + tenant category → blocked; unknown/future category +
tenant type → blocked; blank supplied type/category → blocked; future
tenant-namespaced `budget.new_thing` → allowed) and the raw-string helper
semantics. `DeliveryHandlerTest` cases for the queued-before-upgrade drop,
the retry path, the type/category-inconsistent fail-closed block, the
version-skew future-admin-type block, classification on the RELOADED event
(stale `Delivery.event_type` snapshot must not be used), a concrete-tenant
sub still receiving tenant-accessible events, `__system__` and null-owner
subs still receiving admin events, and the SSRF-guard-not-reached
short-circuit. `RecoveredDeliveryBoundaryTest` composes
`DispatchRecovery → DispatchLoop → DeliveryHandler.handle()` end-to-end to
pin the load-bearing recovered-processing call graph (recovered admin
delivery to a concrete tenant is dropped terminal, not sent, not retried,
still acked). Jacoco gate ≥95% retained.

### 2026-07-04 — v0.1.25.22: delivery-time SSRF guard + management-port posture

Closes the "flagged, not changed" security item from the v0.1.25.21 audit.
`WebhookUrlGuard` re-validates the subscription URL against the CURRENT
admin webhook-security config (`config:webhook-security`) immediately
before every outbound POST. Semantics are a line-for-line port of admin's
create/update-time `WebhookUrlValidator` — same config key, same
restrictive defaults when the key is absent/unreadable (private ranges
blocked, HTTPS required; a config-read failure can only tighten, never
loosen), same CIDR matching incl. IPv4-mapped IPv6, same glob dialect —
so a URL admitted by admin under a given config is admitted here under
the same config. Gaps closed: target drift/DNS rebinding after creation
(narrowed to a single request's resolve-then-connect window — full
resolve-and-pin is not expressible with java.net.http; residual TOCTOU
accepted and documented here), config tightened after creation, and
legacy subscriptions predating admin validation.

Design choices: a blocked delivery fails PERMANENTLY (`ssrf_blocked`
metric reason, no retry — the target is policy-blocked, not unhealthy)
and does NOT increment the subscription's consecutive-failure counter (a
config tightening must not auto-disable subscriptions as a side effect;
the endpoint was never contacted, so the failure says nothing about its
health). Unresolvable hosts fail closed when CIDR ranges are configured,
mirroring admin. Review hardening (pre-release): the first cut substituted
the restrictive defaults on ANY config read failure, which converted a
transient Redis blip into a permanent no-retry policy block for
subscriptions relying on `allow_http`/custom CIDR config. Indeterminate is
now distinct from denial — a read/parse failure throws, the handler leaves
the delivery un-acked, and the stale-processing recovery retries it;
defaults apply only to the legitimately-absent key (never stored), the
same baseline admin validates against. Integration suite seeds the same permissive config the
nightly workflow sets via the admin API; a dedicated integration test
proves the restrictive defaults block an http/loopback target without
contacting it.

OPERATIONS.md now records the management-port posture as a deliberate
exception: 9980 is unauthenticated by design — the separate management
port is the isolation mechanism (unlike runtime/admin, whose actuators
share the API port and are admin-key-gated). Companion PRs de-publish
9980 from the host in the full-stack **prod** compose examples in
cycles-server and cycles-server-admin, which contradicted this posture.

### 2026-07-03 — v0.1.25.21: spec-conformance audit + retry-contract and enum-tolerance fixes

End-to-end audit of the dispatcher against cycles-protocol-v0.yaml's WEBHOOK
EVENT GUIDANCE and the governance spec's Event/WebhookDelivery schemas. The
outbound wire contract verified conformant by hand: all required delivery
headers with exact construction (traceparent `00-{trace}-{fresh-span}-{flags}`
with the inbound-flags preservation rule, HMAC `sha256=`+lowercase-hex over
raw body bytes), retry defaults (5 retries, 1s ×2.0 capped 60s) with
per-subscription overrides, auto-disable at 10 consecutive failures emitting
`webhook.disabled`, stale auto-fail at 24h, retention TTLs (90d events / 14d
deliveries / hourly ZSET trim), and the v0.1.25.28 trace fields on Delivery.

Three conformance gaps found and fixed (313 tests green, JaCoCo ≥95%):

1. **`system.webhook_delivery_failed` was never emitted** — the spec's retry
   contract requires it when retries are exhausted; the enum value existed
   unused. Now emitted from the give-up branch with an `EventDataSystem`
   payload and `tenant_id=__system__`. Save-only, loop-safe, best-effort.
2. **Closed `category`/`actor.type` enums were poison-pills.** Throwing
   `@JsonCreator`s failed the whole delivery on unrecognized values — the
   defect class spec v0.1.25.34 (`EventCategory`+`webhook`) demonstrated —
   and burned the subscription's consecutive-failure budget on events the
   subscriber never got. First cut mapped unknowns to null, but review
   caught that this corrupts the outbound payload instead: the dispatcher
   re-serializes the same `Event` object as the webhook body, so a nulled
   `category` (a required field) would reach subscribers. Final shape:
   both fields are OPEN STRINGS on the wire, mirroring `event_type` —
   unknown values round-trip verbatim to subscribers; enum resolution is a
   local-vocabulary helper only, and unknown categories surface as a new
   `unknown_category` validator WARN + metric. Also added the four
   `*_via_tenant_cascade` EventTypes (spec v0.1.25.35) so cascade events
   validate cleanly instead of warning as unknown.
3. **`request_id` propagation** — dispatcher-emitted events are causally
   downstream of the originating HTTP request, so they now carry the
   originating event's `request_id` per CORRELATION AND TRACING.

Flagged, not changed: no delivery-time SSRF/URL re-validation (admin validates
at subscription create/update; delivery-time re-checks would need the shared
`WebhookSecurityConfig` and are a behavior change — tracked as a follow-up
decision); per-tenant ordering holds for initial dispatch (single-threaded
global FIFO) but retries re-enter out of order, which any backoff scheme
implies — noted as a spec ambiguity rather than "fixed".

### 2026-06-26 — v0.1.25.20: production readiness follow-up

Addresses the follow-up production/security/ops review against the admin
deployment hardening pattern. Subscription delivery-state writes now fail
closed: `updateDeliveryState` returns `false` only when the subscription
disappeared, and throws on Redis, JSON, or write failures. `DeliveryHandler`
now persists subscription success/failure state before writing terminal
delivery state, and emits `webhook.disabled` metrics/audit events only after
the `DISABLED` state write succeeds. This prevents a claimed delivery from
being acked after losing auto-disable counters or status.

Webhook recovery is now age-gated for multi-replica deployments. Claims add a
timestamp in `dispatch:processing:claimed_at`; ack removes both the processing
list entry and timestamp; recovery requeues only entries older than
`DISPATCH_PROCESSING_RECOVERY_IDLE_MS` (default 120s). Entries first observed
without a timestamp get a full idle window before recovery, closing the
BLMOVE-to-ZADD race without requeueing another live replica's active delivery
during rolling deploys.

Deployment defaults now match the admin production hardening posture more
closely: the Docker entrypoint uses `exec java ... ${JAVA_OPTS:-} -jar app.jar`,
tenant-labelled custom metrics default off with
`CYCLES_METRICS_TENANT_TAG_ENABLED=false`, and docs/config tables document the
new recovery and JVM tuning knobs. Version bump: `pom.xml` `<revision>` ->
`0.1.25.20`; fallback webhook `User-Agent` and README examples are aligned.

### 2026-06-26 — v0.1.25.19: dependency alignment

Upgrades Jedis 7.5.0 to 7.5.2, aligning with `cycles-server` and picking up the
latest 7.5.x client patches. The application uses stable Jedis APIs. No Spring
Boot bump (3.5.16 brings no used-dependency or security change). No code,
Redis-data, webhook-wire, or spec change.

### 2026-06-25 — v0.1.25.18: operational readiness hardening

Webhook dispatch now uses the same reliable-queue shape as the evidence worker:
`BLMOVE dispatch:pending -> dispatch:processing`, `LREM` ack only after the
handler returns, and startup recovery that moves orphaned processing entries back
to pending. Retry promotion from `dispatch:retry` to `dispatch:pending` is now a
single Redis Lua operation, removing the crash window between `ZREM` and `LPUSH`.

Delivery, event, subscription, and signing-secret repository failures no longer
turn Redis/JSON/decrypt problems into normal "missing record" control flow.
Missing keys still return `null`, but infrastructure and parsing failures throw,
which leaves claimed delivery IDs unacked for recovery instead of silently
dropping work. Encrypted webhook secrets now fail closed when
`WEBHOOK_SECRET_ENCRYPTION_KEY` is missing or wrong; the transport is not called
with a null secret after decrypt failure.

Operational probes now include a custom Jedis `PING` health indicator. Docker
health uses `/actuator/health/readiness`, and readiness includes Redis while
liveness remains process-only. Docs now state that this worker has no public HTTP
API and 7980 should not be published on an ingress.

CyclesEvidence signing no longer generates an ephemeral key by default when
`EVIDENCE_SERVER_ID` is set. Production startup requires a configured
`EVIDENCE_SIGNING_PRIVATE_KEY_HEX` + `EVIDENCE_SIGNING_SIGNER_DID` pair; the
old throwaway-key path is available only with
`EVIDENCE_ALLOW_EPHEMERAL_SIGNING_KEY=true` for development. The event vocabulary
now includes the full six-event webhook lifecycle (`created`, `updated`,
`paused`, `resumed`, `deleted`, `disabled`) to prevent validator noise on
admin-emitted lifecycle events. Release image building now scans a fresh
`no-cache`/`pull` image and pushes that exact scanned local image.

Version bump: `pom.xml` `<revision>` -> `0.1.25.18`; validation:
`mvn verify` passed with 293 tests and the JaCoCo gate met.

### 2026-06-24 — v0.1.25.17: webhook trace logging and log sanitization follow-up

Follow-up to the ops-log-context PR review. `WebhookTransport` now logs the
effective trace id it resolves or mints for outbound webhook headers, not only
the original `event.trace_id`. This keeps transport-failure logs joinable even
when the incoming event lacked a trace id and the transport had to mint one for
`X-Cycles-Trace-Id` / `traceparent`.

Dynamic operator-log fields that can carry exception text, subscriber metadata,
or evidence-source metadata are flattened before logging (`CR`/`LF` -> space).
The same sanitizer covers transport failures, retry scheduling, permanent
failure logs, scheduler Redis warnings, retention cleanup warnings, delivery /
event / subscription repository failures, evidence sink ids, and evidence
dead-letter/ack-failure logs. Final review follow-up also applies it to
`DeliveryHandler` success/skip/auto-disable logs and adds a focused
`LogSanitizerTest`. No outbound webhook payload/header contract change, Redis
contract change, evidence envelope change, or spec change.

### 2026-06-24 — v0.1.25.16: ops-focused logging context review

Reviewed production `INFO`, `WARN`, and `ERROR` logging from an operator
triage perspective and tightened the logs that were either too generic or too
revealing. Webhook delivery state transitions now name the delivery, event,
event type, subscription, tenant, retry counters, response status/latency, and
trace id where available. The retry scheduler, dispatch loop, retention cleanup,
and Redis repositories now include operation and queue/key-family context in
failure logs.

Webhook transport failures no longer log raw subscriber URLs, avoiding path or
query-token leakage while preserving `target_host`, subscription, tenant, event,
delivery, trace, latency, and exception class for triage. Event payload
validation warnings gained tenant/scope/correlation/request/trace context.
Evidence worker dead-letter and ack-failure logs no longer dump source records;
they report safe source metadata (`artifact_type`, `evidence_id`, `trace_id`,
`issued_at_ms`, parseable flag) and keep payload bodies out of logs.

No outbound webhook wire change, Redis contract change, evidence envelope
change, or spec change. The same PR also aligns the PR/release Trivy SARIF
gates with `cycles-server-admin` by setting `limit-severities-for-sarif: true`
so the blocking scan honors the declared `HIGH,CRITICAL` filter instead of
failing on lower-severity fixable findings that are still present in a full
SARIF upload. Version bump: `pom.xml` `<revision>` -> `0.1.25.16`.

### 2026-06-23 — v0.1.25.15: unconfigured CyclesEvidence is a supported disabled mode

A blank `EVIDENCE_SERVER_ID` now prevents the evidence signer worker, envelope builder, and local signing key beans from being created. Webhook-only deployments no longer generate ephemeral signing identities or consume and dead-letter source records solely because `server_id` is absent.

`EvidenceWorker` no longer carries a second runtime `enabled` flag; the Spring condition is the deployment switch. A direct worker constructed with blank `serverId` remains fail-closed at build time and dead-letters rather than signing an invalid envelope. `EvidenceConfigurationConditionTest` covers both Spring startup modes and now asserts the unconfigured context has not failed. Docs and config comments state that blank `EVIDENCE_SERVER_ID` disables signing, that the events worker intentionally gates on `server_id` while signer config is validated separately, and that records already in `evidence:pending` stay pending if signing is later disabled.

The same slice fixes the startup log's unrelated retention cleanup error: `RetentionCleanupService` scans broad patterns (`events:*`, `deliveries:*`) but now checks Redis key type before trimming. Non-ZSET matches such as `events:correlation:*` are skipped, and a `WRONGTYPE` race on a key no longer aborts the rest of the cleanup pass. Version bump: `pom.xml` `<revision>` -> `0.1.25.15`; validation: `mvn -B verify` passed with 279 tests and the JaCoCo gate met.

### 2026-06-15 — signer key resolution authority loop reference verifier

Adds end-to-end validation that a key resolved from the published JWKS authenticates the envelopes the signer actually produces. This covers the v0.2 authority layer: cycles-protocol#113 `getEvidenceJwks`, cycles-server#194 publishing, and the design thread on cycles-protocol#103 / aeoess#43.

`JwksAuthorityVerifier` is a test utility that acts as a spec-faithful reference for consumers: given an envelope plus a resolved JWK Set, it reports exactly one of the five dispositions. It reuses production `CyclesEvidenceCanonicalizer` and `EnvelopeSigner`, applies raw-hex resolution, enforces the window gate (`cycles_nbf_ms <= issued_at_ms` and absent/null `cycles_exp_ms` or `issued_at_ms < cycles_exp_ms`), and selects a deterministic single match.

`JwksAuthorityLoopTest` runs the verifier over all 13 golden fixtures plus `cycles-jwks.json`, which publishes signer `ec52b49b...fc43`. The test covers `authentic`, `binding_only`, `signer_resolution_failed`, `signer_authority_failed`, and `signature_invalid`, including tampered signatures and payloads. Review fixes tightened integral window validation, 32-byte `x` validation, and `binding_only` documentation. Test-only change: 22 tests, no production, wire, spec, or JaCoCo-main impact.

### 2026-06-14 — evidence identity keygen helper

Adds `tools/EvidenceKeygen.java`, a single-file JDK source-launch helper for self-hosted operators enabling CyclesEvidence. It generates an Ed25519 keypair and prints `EVIDENCE_SERVER_ID`, `EVIDENCE_SIGNING_SIGNER_DID`, and `EVIDENCE_SIGNING_PRIVATE_KEY_HEX` in the 64-hex formats the signer validates.

Both key values use the raw 32-byte DER tail, matching `LocalEvidenceSigningKey.rawTailHex` and round-tripping through the `EnvelopeSigner` SPKI/PKCS#8 prefix path. A sign/verify probe runs before printing so the helper cannot emit a pair the worker would reject at startup. `tools/README.md` documents the helper plus an OpenSSL alternative; the enablement runbook points operators to it. Operator tooling only; no service, wire, or spec change.

### 2026-06-14 — evidence worker integration coverage

Extends `EvidenceWorkerIntegrationTest` from the single `reserve` round trip to all five artifact types: `decide`, `reserve`, `commit`, `release`, and `error`. The shared helper LPUSHes source records shaped like cycles-server `EvidenceEmitter` output, lets the live scheduled worker claim, build, sign, and store them, then reads the envelope back from Redis.

Each case asserts artifact-type payload mapping, `server_id`/`signer_did`, recomputed 64-hex `evidence_id`, Ed25519 signature verification, and per-type shape. This gives signer-tier end-to-end coverage across every shape cycles-server emits. Test-only change; no wire or behavior change.

### 2026-06-12 — v0.1.25.14: evidence worker cross-check and review fixes

Adds a producer `evidence_id` cross-check to `EvidenceWorker.build`. The worker compares the producer-stamped id from cycles-server against the id it recomputes and dead-letters mismatches caused by server-id or signer-did drift. Records without a producer `evidence_id` keep the legacy skip behavior.

The worker now separates store from ack: if storage succeeds but ack fails, the record remains in processing for recovery instead of being dead-lettered after persistence. `artifact_type` upper-casing now uses `Locale.ROOT`, avoiding locale-sensitive enum lookup failures. Four new worker tests cover cross-check match/mismatch, stored-then-ack-fails, and locale-independent enum parsing; `mvn verify` passed with 253 tests and the 95% JaCoCo gate.

### 2026-06-12 — reliable evidence queue

Replaces destructive BRPOP consumption with a BLMOVE reliable-queue pattern. `EvidenceQueueConsumer.claim` atomically moves a record from `evidence:pending` to `evidence:processing`, and the worker `ack`s only after the envelope is durably stored or dead-lettered. A crash between claim and store leaves the record recoverable.

`EvidenceRecovery` returns orphaned in-flight records to pending on startup. Reprocessing is safe because envelopes are content-addressed and idempotent. Adds `cycles.evidence.queue.processing-key`, updates operations docs, and covers claim/ack/recover/dead-letter plus worker ack behavior and Redis round trips. No wire or spec change.

### 2026-06-12 — evidence store

Adds the `EvidenceStore` interface and Redis-backed default implementation. `RedisEvidenceStore` stores envelopes at `evidence:envelope:<id>` with optional TTL (`cycles.evidence.store.ttl-seconds`, default archival/no expiry), and `StoringEvidenceSink` becomes the primary sink.

The worker now persists each built envelope so cycles-server can serve it by id in a later slice. Tests cover Redis SET/SETEX, sink delegation, and an integration round trip that reads the persisted envelope and verifies it. No wire or spec change.

### 2026-06-12 — evidence worker

Connects the producer queue to the signer. `EvidenceQueueConsumer` reads `evidence:pending` and maintains a bounded `evidence:failed` dead-letter list. `EvidenceWorker` validates source records, builds and signs envelopes with `server_id` and `signer_did`, and sends them to the sink.

Invalid records are dead-lettered instead of being signed into empty envelopes. Scheduling pool size increased from 3 to 5 to cover the additional continuous worker loop, and operations docs gained a CyclesEvidence section. The slice added 37 evidence unit tests plus Redis integration coverage; full verify passed with the JaCoCo gate.

### 2026-06-12 — evidence envelope builder

Adds `EvidenceArtifactType` and `CyclesEvidenceEnvelopeBuilder`. The builder assembles cycles-evidence/v0.1 envelopes, nests the body under `payload.<artifact_type>`, derives `evidence_id`, signs with Ed25519, and omits blank `trace_id`.

Seven tests cover builder behavior, including reproducing the fixture `evidence_id` values for all five artifact types. No wire or spec change.

### 2026-06-12 — evidence signing key seam

Adds the `EvidenceSigningKey` interface and `LocalEvidenceSigningKey`. The local implementation accepts `cycles.evidence.signing.private-key-hex` plus `signer-did`, validates configured pairs with a sign/verify probe, generates an ephemeral development key when neither value is set, and fails fast when only one value is supplied.

This isolates local signing behind a seam so a KMS implementation can replace it later. Six tests cover configured, generated, and invalid-key paths. No wire or spec change.

### 2026-06-12 — evidence canonicalizer and signer foundation

Adds `CyclesEvidenceCanonicalizer` and `EnvelopeSigner`. The canonicalizer implements the cycles-evidence-v0.1 recipe using RFC 8785 JCS plus SHA-256 for `evidence_id`, and derives signing bytes from JCS with `evidence_id` populated and `signature` empty. `EnvelopeSigner` signs and verifies Ed25519 with fixed SPKI/PKCS#8 DER prefixes around raw-hex keys.

`CyclesEvidenceCanonicalizerTest` reproduces all 13 reference fixture ids and verifies their signatures against the APS verifier. Fifteen tests; no wire or spec change.

### 2026-05-25 — v0.1.25.13: Apache Tomcat CVE patch

Reintroduces the `<tomcat.version>10.1.55</tomcat.version>` override to close Tomcat CVEs that landed against `tomcat-embed-core` 10.1.54. Covered CVEs include CVE-2026-43515, CVE-2026-43512, CVE-2026-41293, CVE-2026-43513, CVE-2026-42498, CVE-2026-41284, and CVE-2026-43514.

Property override only; no code, wire, or spec change. Remove once Spring Boot manages Tomcat 10.1.55 or newer.

### 2026-04-26 — v0.1.25.12: dependency hygiene

Upgrades Spring Boot 3.5.13 to 3.5.14 and Jedis 5.2.0 to 6.2.0. The application uses stable Jedis APIs (`JedisPool`, `Jedis`, `SetParams`, `ScanParams`, `ScanResult`, and `JedisConnectionException`), and Jedis 6.1.0 restored binary compatibility for `SetParams`.

Drops the explicit Tomcat 10.1.54 override because Spring Boot 3.5.14 manages that version. CI updates Trivy action 0.35.0 to 0.36.0 and Dependabot fetch metadata v2 to v3. `WebhookTransport` fallback version was synced to 0.1.25.12.

### 2026-04-23 — v0.1.25.11: auto-disable webhook lifecycle event

Implements the dispatcher half of the v0.1.25.33 webhook lifecycle contract. When `DeliveryHandler.incrementConsecutiveFailures` crosses `disable_after_failures`, the dispatcher writes a `webhook.disabled` Event to the shared Redis store alongside the existing status flip and metric.

`EventRepository.save` mirrors the admin-side Lua script, and the event uses a deterministic correlation id, `EventDataWebhookLifecycle` payload, system actor, `cycles-events` source, and copied trace id when present. Redis write failure is best-effort and logged without reverting the subscription status change. Operator-initiated lifecycle emits remain in `cycles-server-admin`.

### 2026-04-19 — v0.1.25.10: supply-chain CVE fix

Upgrades Spring Boot 3.5.11 to 3.5.13 and pins Tomcat 10.1.54, closing four high/critical Tomcat CVEs: CVE-2026-29145, CVE-2026-29129, CVE-2026-34483, and CVE-2026-34487. No code changes; all 195 tests passed.

### 2026-04-18 — v0.1.25.8: extend correlation and tracing onto deliveries

Adds optional `trace_id`, `trace_flags`, and `traceparent_inbound_valid` fields to `Delivery`. `TraceContext.buildTraceparent` now accepts trace flags so outbound `traceparent` preserves inbound sampling decisions when the inbound traceparent is valid, and `Transport.deliver` receives the full `Delivery` object for those hints.

`DeliveryHandler` proactively copies `Event.trace_id` onto persisted Delivery records when admin has not stamped one, bridging the gap while `cycles-server-admin` catches up to the spec. Existing values are not overwritten.

### 2026-04-18 — v0.1.25.7: three-tier correlation and tracing

Adds optional `Event.trace_id`, a `TraceContext` helper that resolves or mints trace ids and builds W3C `traceparent` v00 values, and outbound `X-Cycles-Trace-Id` / `traceparent` headers. `WebhookTransport` also forwards `X-Request-Id` when the event carries `request_id`.

`EventPayloadValidator` gained a non-fatal `trace_id_shape` rule, and the audit documents negative findings for admin-plane-only spec changes that do not affect the dispatcher.

### 2026-04-16 — v0.1.25.6: budget reset event, metrics, and validation parity

Adds `BUDGET_RESET_SPENT`, webhook Micrometer counters and latency timers mirroring cycles-server, and non-fatal `EventPayloadValidator` behavior mirroring cycles-server-admin. The change also adopts dotted metric names, a shared `tags(...)` helper, tenant-tag toggling, the `UNKNOWN` sentinel, plus downstream `CHANGELOG.md` and `OPERATIONS.md` docs.

### 2026-04-08 — v0.1.25.5: force HTTP/1.1 outbound transport

Forces outbound webhook transport to HTTP/1.1 to fix h2c body drops reported in #16.

### 2026-04-07 — v0.1.25.4: partial subscription update

Changes subscription updates to avoid overwriting admin-owned configuration fields.

### 2026-04-03 — v0.1.25.3: Prometheus and typed status enums

Adds the Prometheus registry dependency and typed `DeliveryStatus` / `WebhookStatus` enums.

### 2026-04-01 — v0.1.25.1: initial implementation

Initial Redis-driven dispatcher implementation: dispatch loop, delivery handler, retry scheduler, AES-256-GCM secret encryption, TTL-based retention, and end-to-end integration coverage.

**Spec:** `cycles-governance-admin-v0.1.25.yaml` (OpenAPI 3.1.0, v0.1.25.34) — authoritative source at `cycles-protocol` repo; served from `cycles-server-admin`. v0.1.25.33 introduced the `webhook.*` lifecycle EventTypes and `EventDataWebhookLifecycle` schema; v0.1.25.34 added the `webhook` value to `EventCategory`. This service implements only the dispatcher-emission half (auto-disable → `webhook.disabled`); the operator-plane emits live in `cycles-server-admin` v0.1.25.39.

**Service:** Spring Boot 3.5.14 / Java 21 / Jedis 6.2.0 / Micrometer Prometheus registry. Redis-driven webhook dispatcher (no inbound API surface of its own). · tomcat-embed-core 10.1.55 pin (SB 3.5.14 still manages 10.1.54; pin re-introduced 2026-05-25 for Apache Tomcat CVE-2026-43512 / -43513 / -43514 / -43515 / -42498 / -41284 / -41293)

**Downstream docs:**
- [`CHANGELOG.md`](CHANGELOG.md) — release notes for consumers (Keep-a-Changelog format)
- [`OPERATIONS.md`](OPERATIONS.md) — operator runbook (metrics, alerts, SLOs, incident playbook)
- [`README.md`](README.md) — quickstart, architecture, configuration

---

## Service Overview

| Field | Value |
|-------|-------|
| Service | cycles-server-events |
| Version | 0.1.25.12 |
| Java | 21 |
| Spring Boot | 3.5.14 |
| Spec Authority | [complete-budget-governance-v0.1.25.yaml](https://github.com/runcycles/cycles-server-admin/blob/main/complete-budget-governance-v0.1.25.yaml) |

## Test Coverage

| Metric | Value |
|--------|-------|
| Total tests | 205 |
| Unit tests | 201 |
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
| Encrypted webhook secrets fail closed without the decrypt key | PASS |
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
| dispatch.processing.recovery-idle-ms | 120000 | DISPATCH_PROCESSING_RECOVERY_IDLE_MS | OK |
| dispatch.retry.poll-interval-ms | 5000 | - | OK |
| dispatch.retry.batch-size | 100 | RETRY_BATCH_SIZE | OK |
| dispatch.http.timeout-seconds | 30 | - | OK |
| dispatch.http.connect-timeout-seconds | 5 | - | OK |
| dispatch.max-delivery-age-ms | 86400000 | MAX_DELIVERY_AGE_MS | OK |
| events.retention.event-ttl-days | 90 | EVENT_TTL_DAYS | OK |
| events.retention.delivery-ttl-days | 14 | DELIVERY_TTL_DAYS | OK |
| events.retention.cleanup-interval-ms | 3600000 | RETENTION_CLEANUP_INTERVAL_MS | OK |
| spring.task.scheduling.pool.size | 5 | SCHEDULING_POOL_SIZE | OK |
| cycles.metrics.tenant-tag.enabled | false | CYCLES_METRICS_TENANT_TAG_ENABLED | OK |
| management.endpoints.web.exposure.include | health,info,prometheus | - | OK |
| management.server.port | 9980 | MANAGEMENT_PORT | OK (0.1.25.9: actuators off public port) |
| management.endpoint.health.group.readiness.include | readinessState,redis | - | OK |
| cycles.evidence.signing.allow-ephemeral | false | EVIDENCE_ALLOW_EPHEMERAL_SIGNING_KEY | OK |

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| spring-boot-starter-web | 3.5.15 | Embedded worker/management web server |
| spring-boot-starter-actuator | 3.5.15 | Health + metrics |
| jedis | 7.5.2 | Redis client |
| jackson-datatype-jsr310 | (parent) | Java time serialization |
| lombok | (parent) | Compile-time only |
| spring-boot-starter-test | 3.5.15 | Test framework |
| testcontainers | 1.20.4 | Integration test Redis |
| micrometer-registry-prometheus | (parent) | Prometheus metrics endpoint |
| jacoco | 0.8.15 | Coverage enforcement |

## Resilience Patterns

| Pattern | Implementation |
|---------|---------------|
| Exponential backoff | `delay = min(initialDelay * multiplier^(attempts-1), maxDelay)` |
| Auto-disable webhooks | After N consecutive failures (default 10) |
| Stale delivery pruning | Deliveries > 24h auto-failed |
| Redis connection errors | Caught and logged, schedulers continue |
| Concurrent safety | BLMOVE claim + LREM ack; stale recovery age-gated by `dispatch:processing:claimed_at` |
| TTL-based retention | Events 90d, deliveries 14d, ZSET indexes trimmed hourly |

## Spec Compliance (v0.1.25)

| Requirement | Status |
|-------------|--------|
| 47 event types across 7 categories (incl. webhook lifecycle and budget.reset_spent) | PASS |
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
- **Build:** PASS (201 unit tests, 95%+ coverage enforced)
- **Integration test:** PASS (4 Testcontainers Redis tests — incl. end-to-end `traceparent_inbound_valid=true` trace-flags preservation)
- **Total:** 205 tests

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
