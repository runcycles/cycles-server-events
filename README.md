[![CI](https://github.com/runcycles/cycles-server-events/actions/workflows/ci.yml/badge.svg)](https://github.com/runcycles/cycles-server-events/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Coverage](https://img.shields.io/badge/coverage-95%25+-brightgreen)](https://github.com/runcycles/cycles-server-events/actions)

# Cycles Event Server — Event Delivery and Evidence Signing

Cycles Event Server is the asynchronous processing tier for the Cycles stack. It consumes Redis-backed work streams produced by `cycles-server`, persists operational records, and performs the out-of-band work that should not block the runtime reservation path: webhook fan-out and CyclesEvidence signing.

It currently runs two independent workers:

1. **Webhook delivery** — consumes Cycles events and delivers them to subscriber endpoints using signed, at-least-once HTTP delivery. Deliveries include HMAC-SHA256 signatures, retry with exponential backoff, delivery-attempt tracking, automatic subscription disablement after repeated failures, decryption of webhook signing secrets, and forwarding of administrator-configured custom headers. Custom-header persistence is owned by the admin producer and is not encrypted by this worker.

2. **CyclesEvidence signing** — consumes evidence source records emitted by `cycles-server`, validates every nested request/response against the bundled authoritative v0.2 schema, builds a `cycles-evidence/v0.1` envelope, signs it with Ed25519, and stores its RFC 8785 canonical bytes by content address. `cycles-server` then serves the signed evidence at `GET /v1/evidence/{id}`. The evidence private signing key is isolated in this service; `cycles-server` does not need access to it. See [CyclesEvidence signing](#cyclesevidence-signing) and the [identity enablement runbook](docs/evidence-identity-enablement.md).

The event server is not the runtime budget authority. It does not reserve, commit, release, or enforce budget. Its role is to turn Cycles state changes into durable, auditable delivery and identity artifacts: webhook deliveries, replayable event history, delivery diagnostics, trace-correlated records, and signed evidence envelopes.

**Specs:** [`cycles-governance-admin-v0.1.25.yaml`](https://github.com/runcycles/cycles-protocol/blob/main/cycles-governance-admin-v0.1.25.yaml) for events, webhooks, delivery records, and replay · [`cycles-evidence-v0.2.yaml`](https://github.com/runcycles/cycles-protocol/blob/main/cycles-evidence-v0.2.yaml) for the evidence envelope and signer authority model. The compatible wire discriminator remains `cycles-evidence/v0.1`.

## Architecture

```
cycles-server-admin                    cycles-server (runtime)
    │ tenant/budget CRUD,                  │ reservation ops,
    │ api_key/policy lifecycle             │ budget thresholds,
    │                                      │ rate spike detection
    │                                      │
    └──────────┐              ┌────────────┘
               ▼              ▼
         EventService.emit() → save event + find matching subscriptions
         WebhookDispatchService → create PENDING delivery + LPUSH dispatch:pending
               │
               ▼
Redis ──BLMOVE──► cycles-server-events (DispatchLoop)
                    │
                    ├── DeliveryHandler: load delivery + event + subscription
                    ├── WebhookUrlGuard: delivery-time SSRF re-validation against
                    │     config:webhook-security (blocked → permanent FAIL, no retry)
                    ├── SubscriptionRepository: decrypt signing secret (AES-256-GCM)
                    ├── WebhookTransport: HTTP POST with HMAC-SHA256 signature
                    ├── On success: mark SUCCESS, reset consecutive failures
                    ├── On failure + retries left: exponential backoff → RETRYING
                    ├── On failure + retries exhausted: FAILED + increment consecutive failures
                    ├── Ack: LREM dispatch:processing only after state/retry is durable
                     └── When consecutive failures exceed threshold: subscription → DISABLED
```

Event sources (per spec `source` field): `cycles-admin`, `cycles-server`,
`cycles-events`, `expiry-sweeper`, `anomaly-detector`

### Why a separate service?

| Concern | Admin / Runtime Servers | Events Service |
|---------|------------------------|----------------|
| Workload | Synchronous CRUD + reservation ops | Asynchronous delivery, variable latency |
| Scaling | Scale with API traffic | Scale with webhook volume |
| Failure isolation | Servers stay responsive during delivery backlog | Delivery retries don't block API |
| Concurrency | Multiple instances | Multiple instances safe (BLMOVE claim + ack) |

## CyclesEvidence signing

Alongside webhook delivery, this service is the **signing tier** for CyclesEvidence — tamper-evident, content-addressed audit envelopes for every budget decision (see the [concept docs](https://docs.runcycles.io/) and [cycles-evidence-v0.2.yaml](https://github.com/runcycles/cycles-protocol/blob/main/cycles-evidence-v0.2.yaml)).

```
cycles-server (producer)                       cycles-server-events (signer)
  emits {decide|reserve|commit|release|error}     EvidenceWorker (reliable queue: BLMOVE)
  source record + computes evidence_id ──LPUSH──►   ├── validate full nested payload against evidence v0.2
  (returns cycles_evidence on the response)         ├── CyclesEvidenceEnvelopeBuilder: build cycles-evidence/v0.1
                                                     ├── recompute evidence_id; CROSS-CHECK vs producer's
                                                    │     stamped id → retain in-flight + pause on drift
                                          evidence:pending ├── EnvelopeSigner: Ed25519 sign (PRIVATE KEY lives here)
                                                     └── EvidenceStore: SET canonical evidence:envelope:<id> ◄── cycles-server
                                                                                                        serves GET /v1/evidence/{id}
```

Why the signer is in this tier: the expensive work (JCS canonicalization + Ed25519 signing) is asynchronous and must not block a reservation, and the **private signing key** is isolated to this service — `cycles-server` only ever holds the public identity (it reproduces the `evidence_id` content hash synchronously, never signs). The worker recomputes the id and, on mismatch, **retains the record in-flight and pauses new claims briefly**. This fails closed without destroying evidence while operators reconcile producer/worker identity.

**Configure it:** the shared public identity (`EVIDENCE_SERVER_ID`, `EVIDENCE_SIGNING_SIGNER_DID`) plus this service's **private key** (`EVIDENCE_SIGNING_PRIVATE_KEY_HEX`) — full provisioning, coherence rules, and verification steps are in [`docs/evidence-identity-enablement.md`](docs/evidence-identity-enablement.md). If `EVIDENCE_SERVER_ID` is blank, the evidence signer is disabled and does not claim or dead-letter source records.

## Quick Start

### Full stack (with admin + runtime server)

```bash
# From cycles-server-admin directory
docker compose -f docker-compose.full-stack.yml up
```

Services: Redis (6379), Admin (7979), Runtime Server (7878), Events worker (9980 management; 7980 internal/no API)

### Standalone (requires existing Redis)

```bash
REDIS_HOST=localhost REDIS_PORT=6379 java -jar target/cycles-server-events-*.jar
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_HOST` | localhost | Redis hostname |
| `REDIS_PORT` | 6379 | Redis port |
| `REDIS_PASSWORD` | (empty) | Redis password |
| `REDIS_USERNAME` | (empty) | Redis ACL username |
| `REDIS_TLS_ENABLED` | false | Enable TLS for Redis connections |
| `REDIS_CONNECT_TIMEOUT_MS` / `REDIS_SOCKET_TIMEOUT_MS` | 2000 / 5000 | Positive Redis connection and non-blocking command timeouts |
| `REDIS_BLOCKING_SOCKET_TIMEOUT_MS` | 10000 | Finite socket timeout for blocking Redis claims. Must exceed the longest configured BLMOVE timeout. |
| `WEBHOOK_SECRET_ENCRYPTION_KEY` | (empty) | AES-256-GCM key for signing secret encryption (base64-encoded 32 bytes). If empty, secrets stored/read as plaintext (backward compatible). |
| `dispatch.pending.timeout-seconds` | 5 | BLMOVE blocking timeout (seconds) |
| `DISPATCH_LOOP_DELAY_MS` | 25 | Delay between dispatch-loop invocations after a claim completes or times out. |
| `DISPATCH_PROCESSING_RECOVERY_IDLE_MS` | 180000 | Minimum age before an in-flight `dispatch:processing` delivery is considered stale and requeued. Startup fails unless this is strictly greater than `DISPATCH_ORDERING_LEASE_MS`. |
| `DISPATCH_PROCESSING_RECOVERY_INTERVAL_MS` | 30000 | Periodic stale-processing recovery interval; prevents quick-restart orphans from remaining stranded |
| `DISPATCH_ORDERING_LEASE_MS` | 120000 | Fleet-wide claim/send lease that preserves initial-claim FIFO order. Must exceed BLMOVE plus HTTP timeouts; replicas provide failover, not webhook throughput. |
| `DISPATCH_ORDERING_CONTENTION_BACKOFF_MS` | 500 | Bounded randomized delay after a global dispatch-lease miss; prevents idle replicas from hammering Redis. |
| `DISPATCH_EVENT_OUTBOX_POLL_INTERVAL_MS` / `DISPATCH_EVENT_OUTBOX_BATCH_SIZE` | 1000 / 25 | Poll interval and bounded batch for durable dispatcher-generated meta-events. |
| `DISPATCH_EVENT_OUTBOX_CLAIM_LEASE_MS` / `DISPATCH_EVENT_OUTBOX_RETRY_DELAY_MS` | 30000 / 5000 | Per-task publication lease and retry deferral for dispatcher meta-events. |
| `DISPATCH_EVENT_OUTBOX_MAX_ATTEMPTS` / `DISPATCH_EVENT_OUTBOX_FAILED_MAX_LEN` | 100 / 10000 | Attempts before a poison meta-event task moves to the bounded `dispatcher:event-outbox:failed` DLQ, and the maximum retained DLQ entries. |
| `dispatch.retry.poll-interval-ms` | 5000 | Retry queue poll interval (ms) |
| `RETRY_BATCH_SIZE` | 100 | Max retries to requeue per poll cycle |
| `dispatch.http.timeout-seconds` | 30 | HTTP request timeout for webhook delivery |
| `dispatch.http.connect-timeout-seconds` | 5 | HTTP connect timeout |
| `MAX_DELIVERY_AGE_MS` | 86400000 | Maximum delivery age (ms). Deliveries older than this after events service outage are auto-failed instead of delivered stale. Default: 24 hours. |
| `EVENT_TTL_DAYS` | 90 | Redis TTL for `event:{id}` keys (days). Spec: "90 days hot." |
| `DELIVERY_TTL_DAYS` | 14 | Redis TTL for `delivery:{id}` keys (days). |
| `RETENTION_CLEANUP_INTERVAL_MS` | 3600000 | How often to trim expired ZSET index entries (ms). Default: 1 hour. |
| `RETENTION_LOCK_LEASE_MS` | 300000 | Cross-replica retention-cleanup lease; keep above the expected cleanup duration |
| `CYCLES_METRICS_TENANT_TAG_ENABLED` | false | Include tenant IDs as Prometheus metric labels. Keep false in production unless per-tenant drill-down is worth the cardinality. |
| `SCHEDULING_POOL_SIZE` | 5 | Scheduled-task executor size. Do not lower below 5 when evidence signing is enabled. |
| `JAVA_OPTS` | (empty) | Extra JVM options appended after image defaults by the Docker entrypoint. |
| `EVIDENCE_SERVER_ID` | (empty) | CyclesEvidence `server_id`. Blank disables the evidence signer. When set, it must be an absolute URI and **byte-identical to `cycles-server`'s** value (incl. the `/v1` base), or the worker retains the mismatched record in-flight and pauses new claims. See the [enablement runbook](docs/evidence-identity-enablement.md). |
| `EVIDENCE_SIGNING_SIGNER_DID` | (empty) | Public Ed25519 key (64 hex), the public half of `EVIDENCE_SIGNING_PRIVATE_KEY_HEX`, **identical to `cycles-server`'s** value. |
| `EVIDENCE_SIGNING_PRIVATE_KEY_HEX` | (empty) | **Secret** — Ed25519 seed (64 hex) this service signs with; lives **only** here. Required when `EVIDENCE_SERVER_ID` is set unless ephemeral dev mode is explicitly allowed; setting only one of the signing pair fails startup. |
| `EVIDENCE_ALLOW_EPHEMERAL_SIGNING_KEY` | false | Development-only escape hatch. When `true` and evidence is enabled without a signing pair, generates an ephemeral key that will not verify across restarts. Keep `false` in production. |
| `EVIDENCE_PENDING_KEY` / `EVIDENCE_PROCESSING_KEY` / `EVIDENCE_FAILED_KEY` | evidence:pending / evidence:processing / evidence:failed | Source, in-flight, and bounded poison-record queue keys. |
| `EVIDENCE_POP_TIMEOUT_SECONDS` | 5 | Evidence BLMOVE blocking timeout; keep below `REDIS_BLOCKING_SOCKET_TIMEOUT_MS`. |
| `EVIDENCE_RECOVERY_IDLE_MS` / `EVIDENCE_RECOVERY_INTERVAL_MS` | 120000 / 30000 | Age threshold and polling interval for recovering evidence work orphaned in `evidence:processing` |
| `EVIDENCE_RECOVERY_BATCH_SIZE` | 100 | Maximum processing records inspected per recovery pass |
| `EVIDENCE_FAILED_MAX_LEN` | 10000 | Positive maximum length of the evidence poison-record DLQ |
| `EVIDENCE_LOOP_DELAY_MS` / `EVIDENCE_QUEUE_FAILURE_BACKOFF_MS` | 25 / 1000 | Scheduler delay and Redis claim/ack outage backoff; prevents tight retry/log loops. |
| `EVIDENCE_INFRASTRUCTURE_BACKOFF_MS` | 30000 | Claim pause after identity, signing, or store failures; limits in-flight growth during systemic outages. |
| `EVIDENCE_STORE_BACKEND` / `EVIDENCE_STORE_KEY_PREFIX` / `EVIDENCE_STORE_TTL_SECONDS` | redis / evidence:envelope: / 0 | Content-addressed evidence storage backend, Redis key prefix, and archival TTL (`0` means no expiry). |

The delivery-time URL guard reads `config:webhook-security`. If that key is
absent, its hardened fallback blocks private, loopback, link-local,
carrier-grade NAT, unspecified IPv4 and IPv6, and unique-local IPv6 ranges,
including `0.0.0.0/8`, `::/128`, `100.64.0.0/10`, and `fe80::/10`. The
unspecified address is also rejected semantically even if a stored blocklist
omits it. A malformed configured CIDR is
treated as indeterminate rather than silently skipped: the delivery remains
in-flight for recovery and `cycles_webhook_security_config_indeterminate_total`
increments. Host resolution is checked immediately before delivery, but the JDK
HTTP client performs its own connection-time DNS lookup; deployments requiring
DNS-rebinding resistance must enforce the same egress policy in a proxy or
network firewall.

The Redis client sets the connection name `cycles-server-events`; a
least-privilege Redis ACL therefore needs `+client|setname` in addition to the
commands and key patterns used by this worker.

### Generating the encryption key

```bash
openssl rand -base64 32
```

The same key must be configured in both `cycles-server-admin` and `cycles-server-events`. Admin encrypts secrets on write; events decrypts on read.

## Signing Secret Lifecycle

```
1. Client creates subscription (POST /v1/admin/webhooks)
   └── optionally provides signing_secret, or admin auto-generates one (whsec_...)

2. Admin stores encrypted secret in Redis
   └── webhook:secret:{subscriptionId} = AES-256-GCM(secret, WEBHOOK_SECRET_ENCRYPTION_KEY)
   └── Returns plaintext secret to client ONCE in WebhookCreateResponse (never again)

3. Events service reads + decrypts secret on each delivery
   └── CryptoService.decrypt(redis.get("webhook:secret:{id}"))
   └── Backward compatible: plaintext secrets (no "enc:" prefix) returned as-is
   └── Missing secrets are treated as transient and left for stale-claim recovery
   └── Fail closed: missing or undecryptable secrets are never delivered unsigned

4. PayloadSigner computes HMAC-SHA256(JSON payload, decrypted secret)
   └── Sent as X-Cycles-Signature: sha256=<hex> header

5. Webhook receiver verifies signature using their copy of the secret
```

## HMAC-SHA256 Signature Verification

The `X-Cycles-Signature` header contains `sha256=<hex>` where `<hex>` is the HMAC-SHA256 of the raw JSON request body using the subscription's signing secret as the key.

**Why HMAC?** Without it, anyone who discovers a webhook URL can send fake events. HMAC proves both identity (shared secret) and integrity (body hash). Same standard used by GitHub, Stripe, and Slack webhooks.

**Verification example (Python):**

```python
import hmac, hashlib

def verify(body: bytes, secret: str, signature: str) -> bool:
    expected = "sha256=" + hmac.new(
        secret.encode(), body, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, signature)
```

## Webhook Delivery Headers

| Header | Value | Description |
|--------|-------|-------------|
| `Content-Type` | `application/json` | Always JSON |
| `X-Cycles-Signature` | `sha256=<hex>` | HMAC-SHA256 of body; a delivery is deferred or failed closed if the secret cannot be loaded |
| `X-Cycles-Event-Id` | `evt_abc123...` | For deduplication (at-least-once delivery) |
| `X-Cycles-Event-Type` | `budget.exhausted` | Event type for routing |
| `User-Agent` | `cycles-server-events/<build-version>` | Service identifier |
| `X-Cycles-Trace-Id` | `<32-hex-lowercase>` | W3C trace-id (spec v0.1.25.27) — always present |
| `traceparent` | `00-<trace-id>-<16-hex-span>-<flags>` | W3C Trace Context v00 — always present. `<flags>` preserves upstream sampling when `WebhookDelivery.traceparent_inbound_valid=true` (spec v0.1.25.28), else `01` |
| `X-Request-Id` | `<request-id>` | Originating HTTP request id — present when `event.request_id` is populated |
| Custom headers | Per subscription | From `WebhookSubscription.headers` map |

Custom headers are additive only. Reserved delivery headers such as `Content-Type`,
`User-Agent`, `X-Cycles-*`, `X-Request-Id`, and `traceparent` are ignored if a
subscription tries to set them.

## Retry Policy

Default: 5 retries with exponential backoff (1s, 2s, 4s, 8s, 16s), capped at 60s max delay.

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| `max_retries` | 5 | 0-10 | Retries after initial failure (6 total attempts) |
| `initial_delay_ms` | 1000 | 100-60000 | Delay before first retry |
| `backoff_multiplier` | 2.0 | 1.0-10.0 | Multiplier per retry |
| `max_delay_ms` | 60000 | 1000-3600000 | Maximum delay cap |

Auto-disable: after `disable_after_failures` (default 10) consecutive delivery failures, the subscription status is set to `DISABLED`. Reset to 0 on any successful delivery.

## Delivery Status Lifecycle

```
PENDING ──HTTP 2xx──► SUCCESS (reset consecutive_failures)
    │
    └──non-2xx──► RETRYING ──retry──► SUCCESS
                      │
                      └──max retries exceeded──► FAILED
                                                    │
                                                    └──consecutive > threshold──► subscription DISABLED
```

## Redis Keys (shared with cycles-server-admin)

| Key | Type | Written By | Read By | Description |
|-----|------|-----------|---------|-------------|
| `dispatch:pending` | LIST | Admin (LPUSH), Events retry scheduler (LPUSH) | Events (BLMOVE) | Delivery IDs awaiting processing |
| `dispatch:processing` | LIST | Events (BLMOVE) | Events (LREM/recovery) | In-flight delivery IDs claimed but not yet acked |
| `dispatch:processing:claimed_at` | ZSET | Events (ZADD/ZREM) | Events recovery | Claim timestamps used to recover only stale in-flight deliveries |
| `dispatch:processing:claim_owner` | HASH | Events claim Lua | Events ack/recovery Lua | Per-delivery claim generation; a stale worker cannot ack a successor's claim |
| `dispatch:retry` | ZSET | Events (ZADD) | Events (ZRANGEBYSCORE) | Retry queue (score = timestamp) |
| `evidence:processing` | LIST | Events evidence claim Lua | Events evidence ack/recovery Lua | Unique evidence claim IDs; raw payloads are not used as processing identities |
| `evidence:processing:claimed_at` | ZSET | Events evidence claim Lua | Events evidence recovery Lua | Evidence claim timestamps keyed by unique claim ID |
| `evidence:processing:claim_owner` | HASH | Events evidence claim Lua | Events evidence ack/DLQ/recovery Lua | Evidence claim generations used to fence stale workers |
| `evidence:processing:payload` | HASH | Events evidence claim Lua | Events evidence ack/DLQ/recovery Lua | Raw source payload retained by claim ID so identical records remain independent |
| `delivery:{id}` | STRING | Admin (SET) | Events (GET/SET) | Delivery record JSON |
| `event:{id}` | STRING | Admin (SET) | Events (GET) | Event record JSON |
| `webhook:{id}` | STRING | Admin (SET) | Events (GET/SET) | Subscription JSON |
| `webhook:secret:{id}` | STRING | Admin (SET, encrypted) | Events (GET, decrypts) | AES-256-GCM encrypted signing secret |
| `dispatcher:event-outbox:pending` | ZSET | Events terminal-state Lua | Events outbox publisher | Due dispatcher meta-event task IDs (score = next attempt time) |
| `dispatcher:event-outbox:task:{id}` | STRING | Events terminal-state Lua | Events outbox publisher | Durable `webhook.disabled` or `system.webhook_delivery_failed` publication task |
| `dispatcher:event-outbox:lock:{id}` | STRING | Events outbox publisher | Events outbox publisher | Short owner-token publication lease; removed on ack or expiry |
| `dispatcher:event-outbox:attempts` | HASH | Events outbox publisher | Events outbox publisher | Publish-failure count used for bounded poison-task retries |
| `dispatcher:event-outbox:failed` | LIST | Events outbox publisher | Operators | Bounded DLQ of `{task_id,payload}` wrappers for malformed or repeatedly unpublishable tasks |

### Concurrent safety

Multiple events service instances can safely share `dispatch:pending`. One global Redis lease deliberately serializes the complete claim/send section across the fleet, preventing simultaneous sends while preserving FIFO order for initial claims. This caps webhook throughput at one in-flight HTTP request (about two deliveries/minute when every endpoint consumes the 30-second timeout); extra replicas provide failover, not throughput. Delayed retries can still complete after later events, as expected for at-least-once delivery with backoff. Each claim receives a unique owner token, and every delivery state write, retry enqueue, and ack verifies that token atomically, so a worker waking after stale recovery cannot alter or erase its successor's work. Recovery is age-gated and periodic: only entries older than `DISPATCH_PROCESSING_RECOVERY_IDLE_MS` are moved back to pending, and startup validates that threshold is above both the ordering lease and the worst-case claim/send duration.

Final retry exhaustion uses one Redis transaction to persist the failed delivery, increment/disable the subscription, and stage both required dispatcher meta-events. A background publisher uses deterministic event IDs and per-task leases, so a crash or Redis error can replay safely without losing or multiplying the logical event. Meta-events are persisted for observation but are not recursively fanned out as webhook deliveries.

### TTL and retention

| Key | TTL | Cleanup |
|-----|-----|---------|
| `event:{id}` | 90 days (configurable) | Auto-expire via Redis EXPIRE |
| `delivery:{id}` | 14 days (configurable) | Auto-expire via Redis EXPIRE |
| `events:{tenantId}`, `events:_all` | N/A (ZSET) | Hourly trim via RetentionCleanupService |
| `deliveries:{subId}` | N/A (ZSET) | Hourly trim via RetentionCleanupService |
| `dispatch:pending` | Self-draining | Claimed by BLMOVE into `dispatch:processing` |
| `dispatch:processing` | Self-draining | Acked by LREM; stale entries recovered to pending after the idle window |
| `dispatch:retry` | Self-draining | Entries move to pending when ready |
| `dispatcher:event-outbox:pending`, `task:*`, `lock:*`, `attempts` | Self-draining | Deterministic tasks remain until durably saved, acknowledged, or moved to the bounded DLQ after retry exhaustion |
| `dispatcher:event-outbox:failed` | Bounded to 10000 by default | Operators inspect and replay or discard poison meta-event tasks |

### Resilience: events service down

If `cycles-server-events` is not running or not deployed:

1. **Admin and runtime servers are unaffected** — event emission is fire-and-forget, wrapped in try-catch, never blocks the API response
2. **Events and deliveries accumulate in Redis** — `event:{id}` keys (90-day TTL), `delivery:{id}` keys (14-day TTL), `dispatch:pending` list grows
3. **Redis memory is bounded** — TTLs ensure keys auto-expire even if never consumed
4. **When the events service restarts:**
   - Stale deliveries (older than `MAX_DELIVERY_AGE_MS`, default 24h) are immediately marked FAILED — they won't be delivered late
   - Orphaned in-flight deliveries in `dispatch:processing` are recovered to `dispatch:pending` after `DISPATCH_PROCESSING_RECOVERY_IDLE_MS`
   - Fresh deliveries are processed normally via BLMOVE claim + ack
   - RetentionCleanupService trims orphaned ZSET index entries hourly
5. **No data loss for events** — event records persist in Redis for 90 days regardless of delivery status

### Admin dual-auth on tenant webhook endpoints (informational)

As of admin-spec v0.1.25.16, six tenant-scoped webhook REST endpoints on `cycles-server-admin` accept both `ApiKeyAuth` and `AdminKeyAuth`:
`GET /v1/webhooks`, `GET/PATCH/DELETE /v1/webhooks/{id}`, `POST /v1/webhooks/{id}/test`, `GET /v1/webhooks/{id}/deliveries`.
Admin-initiated updates record `actor_type=admin_on_behalf_of` in audit metadata (vs `api_key` for tenant-initiated).

**No functional impact on this service** — `cycles-server-events` reads subscriptions from Redis directly and does not call those admin HTTP endpoints. Noted here for observability and ops awareness.

## Event Types (51)

| Category | Count | Types |
|----------|-------|-------|
| `budget` | 17 | created, updated, funded, debited, reset, **reset_spent**, debt_repaid, frozen, unfrozen, closed, **closed_via_tenant_cascade**, threshold_crossed, exhausted, over_limit_entered, over_limit_exited, debt_incurred, burn_rate_anomaly |
| `reservation` | 6 | denied, denial_rate_spike, expired, expiry_rate_spike, commit_overage, **released_via_tenant_cascade** |
| `tenant` | 6 | created, updated, suspended, reactivated, closed, settings_changed |
| `api_key` | 7 | created, revoked, **revoked_via_tenant_cascade**, expired, permissions_changed, auth_failed, auth_failure_rate_spike |
| `policy` | 3 | created, updated, deleted |
| `system` | 5 | store_connection_lost, store_connection_restored, high_latency, webhook_delivery_failed, webhook_test |
| `webhook` | 7 | created, updated, paused, resumed, deleted, disabled, **disabled_via_tenant_cascade** |

`budget.reset_spent` (v0.1.25.6, admin-spec v0.1.25.18) is emitted for billing-period rollovers and is distinct from `budget.reset` (which is a ceiling resize that preserves spent). Consumers can route these separately. The payload's `spent_override_provided` flag indicates whether `spent` was explicitly supplied (migration / proration / correction) vs defaulted to 0 (routine rollover).

## Transport Layer

Pluggable transport interface. Currently implements `webhook` (HTTP POST).

```java
public interface Transport {
    String type();
    TransportResult deliver(Event event, Subscription subscription, String signingSecret, Delivery delivery);
}
```

## Monitoring

Spring Actuator endpoints run on a **separate management port (9980)**. This worker has no public HTTP API; do not publish 7980 on an ingress. Keep 9980 on an internal-only ClusterIP / network and scrape from there.

| Endpoint | Description |
|----------|-------------|
| `GET :9980/actuator/health/liveness` | Process liveness check |
| `GET :9980/actuator/health/readiness` | Readiness check, including Redis `PING` |
| `GET :9980/actuator/health` | Aggregate health |
| `GET :9980/actuator/info` | Build info (version, artifact) |
| `GET :9980/actuator/prometheus` | Prometheus-format metrics for scraping |

Prometheus scrape config example:

```yaml
scrape_configs:
  - job_name: cycles-server-events
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['localhost:9980']
```

Override the management port via the `MANAGEMENT_PORT` env var if 9980 collides.

In addition to Spring Boot's auto-emitted `http_server_requests_seconds`, the service exposes webhook delivery meters under `cycles_webhook_*` and evidence lifecycle counters under `cycles_evidence_*`. Operators can alert on delivery failures, stale backlogs, subscription auto-disables, invalid payloads, evidence DLQ growth, and infrastructure-deferred evidence retries without grepping logs.

Full metric inventory, tag semantics, ready-to-paste Prometheus alert rules, SLO definitions, dashboard queries, and an incident playbook live in [`OPERATIONS.md`](OPERATIONS.md).

## Webhook Payload Example

The webhook POST body is the full event JSON. Null fields are omitted.

```json
{
  "event_id": "evt_abc123",
  "event_type": "budget.exhausted",
  "category": "budget",
  "timestamp": "2026-04-01T12:00:00Z",
  "tenant_id": "t_xyz789",
  "source": "runtime",
  "data": {
    "budget_id": "bdg_001",
    "current_balance": 0,
    "limit": 10000
  },
  "actor": {
    "type": "api_key",
    "key_id": "key_abc",
    "source_ip": "10.0.1.42"
  },
  "correlation_id": "req_def456"
}
```

## Build & Test

```bash
# Run the fast unit suite (515 tests in the current suite)
mvn test

# Run all 540 tests including integration; enforces 95% line / 95% branch coverage
# (requires Docker for Testcontainers Redis)
mvn verify -Pintegration-tests

# Run
REDIS_HOST=localhost REDIS_PORT=6379 java -jar target/cycles-server-events-*.jar
```

## Documentation

- [`CHANGELOG.md`](CHANGELOG.md) — release notes for downstream consumers (Docker / JAR / operators)
- [`OPERATIONS.md`](OPERATIONS.md) — operator runbook: metrics inventory, alert recipes, SLOs, incident playbook
- [`AUDIT.md`](AUDIT.md) — engineering history, audit posture, and cross-repo drift notes
- Sibling services (same conventions, dashboards carry over):
  - [`cycles-server`](https://github.com/runcycles/cycles-server) — runtime reservation + budget authority
  - [`cycles-server-admin`](https://github.com/runcycles/cycles-server-admin) — admin plane (tenants, budgets, webhooks, API keys)

## License

Apache License 2.0
