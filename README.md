[![CI](https://github.com/runcycles/cycles-server-events/actions/workflows/ci.yml/badge.svg)](https://github.com/runcycles/cycles-server-events/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Coverage](https://img.shields.io/badge/coverage-95%25+-brightgreen)](https://github.com/runcycles/cycles-server-events/actions)

# Runcycles Event Server

Event delivery service for the Cycles ecosystem. Consumes events from Redis and delivers them to webhook endpoints with HMAC-SHA256 signing, exponential backoff retry, auto-disable on consecutive failures, and AES-256-GCM secret encryption at rest.

**Spec:** [complete-budget-governance-v0.1.25.yaml](https://github.com/runcycles/cycles-server-admin/blob/main/complete-budget-governance-v0.1.25.yaml)

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
Redis ──BRPOP──► cycles-server-events (DispatchLoop)
                    │
                    ├── DeliveryHandler: load delivery + event + subscription
                    ├── SubscriptionRepository: decrypt signing secret (AES-256-GCM)
                    ├── WebhookTransport: HTTP POST with HMAC-SHA256 signature
                    ├── On success: mark SUCCESS, reset consecutive failures
                    ├── On failure + retries left: exponential backoff → RETRYING
                    ├── On failure + retries exhausted: FAILED + increment consecutive failures
                    └── On consecutive failures >= threshold: subscription → DISABLED
```

Event sources (per spec `source` field): `cycles-admin`, `cycles-server`, `expiry-sweeper`, `anomaly-detector`

### Why a separate service?

| Concern | Admin / Runtime Servers | Events Service |
|---------|------------------------|----------------|
| Workload | Synchronous CRUD + reservation ops | Asynchronous delivery, variable latency |
| Scaling | Scale with API traffic | Scale with webhook volume |
| Failure isolation | Servers stay responsive during delivery backlog | Delivery retries don't block API |
| Concurrency | Multiple instances | Multiple instances safe (BRPOP is atomic) |

## Quick Start

### Full stack (with admin + runtime server)

```bash
# From cycles-server-admin directory
docker compose -f docker-compose.full-stack.yml up
```

Services: Redis (6379), Admin (7979), Runtime Server (7878), Events (7980)

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
| `WEBHOOK_SECRET_ENCRYPTION_KEY` | (empty) | AES-256-GCM key for signing secret encryption (base64-encoded 32 bytes). If empty, secrets stored/read as plaintext (backward compatible). |
| `dispatch.pending.timeout-seconds` | 5 | BRPOP blocking timeout (seconds) |
| `dispatch.retry.poll-interval-ms` | 5000 | Retry queue poll interval (ms) |
| `RETRY_BATCH_SIZE` | 100 | Max retries to requeue per poll cycle |
| `dispatch.http.timeout-seconds` | 30 | HTTP request timeout for webhook delivery |
| `dispatch.http.connect-timeout-seconds` | 5 | HTTP connect timeout |
| `MAX_DELIVERY_AGE_MS` | 86400000 | Maximum delivery age (ms). Deliveries older than this after events service outage are auto-failed instead of delivered stale. Default: 24 hours. |
| `EVENT_TTL_DAYS` | 90 | Redis TTL for `event:{id}` keys (days). Spec: "90 days hot." |
| `DELIVERY_TTL_DAYS` | 14 | Redis TTL for `delivery:{id}` keys (days). |
| `RETENTION_CLEANUP_INTERVAL_MS` | 3600000 | How often to trim expired ZSET index entries (ms). Default: 1 hour. |

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
| `X-Cycles-Signature` | `sha256=<hex>` | HMAC-SHA256 of body (if signing secret configured) |
| `X-Cycles-Event-Id` | `evt_abc123...` | For deduplication (at-least-once delivery) |
| `X-Cycles-Event-Type` | `budget.exhausted` | Event type for routing |
| `User-Agent` | `cycles-server-events/0.1.25.8` | Service identifier |
| `X-Cycles-Trace-Id` | `<32-hex-lowercase>` | W3C trace-id (spec v0.1.25.27) — always present |
| `traceparent` | `00-<trace-id>-<16-hex-span>-<flags>` | W3C Trace Context v00 — always present. `<flags>` preserves upstream sampling when `WebhookDelivery.traceparent_inbound_valid=true` (spec v0.1.25.28), else `01` |
| `X-Request-Id` | `<request-id>` | Originating HTTP request id — present when `event.request_id` is populated |
| Custom headers | Per subscription | From `WebhookSubscription.headers` map |

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
                                                    └──consecutive >= threshold──► subscription DISABLED
```

## Redis Keys (shared with cycles-server-admin)

| Key | Type | Written By | Read By | Description |
|-----|------|-----------|---------|-------------|
| `dispatch:pending` | LIST | Admin (LPUSH) | Events (BRPOP) | Delivery IDs awaiting processing |
| `dispatch:retry` | ZSET | Events (ZADD) | Events (ZRANGEBYSCORE) | Retry queue (score = timestamp) |
| `delivery:{id}` | STRING | Admin (SET) | Events (GET/SET) | Delivery record JSON |
| `event:{id}` | STRING | Admin (SET) | Events (GET) | Event record JSON |
| `webhook:{id}` | STRING | Admin (SET) | Events (GET/SET) | Subscription JSON |
| `webhook:secret:{id}` | STRING | Admin (SET, encrypted) | Events (GET, decrypts) | AES-256-GCM encrypted signing secret |

### Concurrent safety

Multiple events service instances can safely BRPOP from the same `dispatch:pending` list — BRPOP is atomic, so each delivery is processed by exactly one consumer. No distributed locking needed.

### TTL and retention

| Key | TTL | Cleanup |
|-----|-----|---------|
| `event:{id}` | 90 days (configurable) | Auto-expire via Redis EXPIRE |
| `delivery:{id}` | 14 days (configurable) | Auto-expire via Redis EXPIRE |
| `events:{tenantId}`, `events:_all` | N/A (ZSET) | Hourly trim via RetentionCleanupService |
| `deliveries:{subId}` | N/A (ZSET) | Hourly trim via RetentionCleanupService |
| `dispatch:pending` | Self-draining | Consumed by BRPOP |
| `dispatch:retry` | Self-draining | Entries move to pending when ready |

### Resilience: events service down

If `cycles-server-events` is not running or not deployed:

1. **Admin and runtime servers are unaffected** — event emission is fire-and-forget, wrapped in try-catch, never blocks the API response
2. **Events and deliveries accumulate in Redis** — `event:{id}` keys (90-day TTL), `delivery:{id}` keys (14-day TTL), `dispatch:pending` list grows
3. **Redis memory is bounded** — TTLs ensure keys auto-expire even if never consumed
4. **When the events service restarts:**
   - Stale deliveries (older than `MAX_DELIVERY_AGE_MS`, default 24h) are immediately marked FAILED — they won't be delivered late
   - Fresh deliveries are processed normally via BRPOP
   - RetentionCleanupService trims orphaned ZSET index entries hourly
5. **No data loss for events** — event records persist in Redis for 90 days regardless of delivery status

### Admin dual-auth on tenant webhook endpoints (informational)

As of admin-spec v0.1.25.16, six tenant-scoped webhook REST endpoints on `cycles-server-admin` accept both `ApiKeyAuth` and `AdminKeyAuth`:
`GET /v1/webhooks`, `GET/PATCH/DELETE /v1/webhooks/{id}`, `POST /v1/webhooks/{id}/test`, `GET /v1/webhooks/{id}/deliveries`.
Admin-initiated updates record `actor_type=admin_on_behalf_of` in audit metadata (vs `api_key` for tenant-initiated).

**No functional impact on this service** — `cycles-server-events` reads subscriptions from Redis directly and does not call those admin HTTP endpoints. Noted here for observability and ops awareness.

## Event Types (41)

| Category | Count | Types |
|----------|-------|-------|
| `budget` | 16 | created, updated, funded, debited, reset, **reset_spent**, debt_repaid, frozen, unfrozen, closed, threshold_crossed, exhausted, over_limit_entered, over_limit_exited, debt_incurred, burn_rate_anomaly |
| `reservation` | 5 | denied, denial_rate_spike, expired, expiry_rate_spike, commit_overage |
| `tenant` | 6 | created, updated, suspended, reactivated, closed, settings_changed |
| `api_key` | 6 | created, revoked, expired, permissions_changed, auth_failed, auth_failure_rate_spike |
| `policy` | 3 | created, updated, deleted |
| `system` | 5 | store_connection_lost, store_connection_restored, high_latency, webhook_delivery_failed, webhook_test |

`budget.reset_spent` (v0.1.25.6, admin-spec v0.1.25.18) is emitted for billing-period rollovers and is distinct from `budget.reset` (which is a ceiling resize that preserves spent). Consumers can route these separately. The payload's `spent_override_provided` flag indicates whether `spent` was explicitly supplied (migration / proration / correction) vs defaulted to 0 (routine rollover).

## Transport Layer

Pluggable transport interface. Currently implements `webhook` (HTTP POST).

```java
public interface Transport {
    String type();
    TransportResult deliver(Event event, Subscription subscription, String signingSecret);
}
```

## Monitoring

Spring Actuator endpoints are exposed on the service port (7980), powered by Micrometer with a Prometheus registry:

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Liveness check (UP/DOWN) |
| `GET /actuator/info` | Build info (version, artifact) |
| `GET /actuator/prometheus` | Prometheus-format metrics for scraping |

Prometheus scrape config example:

```yaml
scrape_configs:
  - job_name: cycles-server-events
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['localhost:7980']
```

In addition to Spring Boot's auto-emitted `http_server_requests_seconds` (which covers the actuator endpoints, not the outbound webhook traffic), this service exposes eight domain-level meters under the `cycles_webhook_*` namespace — seven counters plus one latency timer. Operators can alert on fleet-wide failure rates, stale-delivery backlogs, subscription auto-disables, and payload-validator warnings without grepping logs.

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
# Build and run unit tests (195 unit tests, 95%+ line coverage enforced by JaCoCo)
mvn verify

# Run all tests including integration (requires Docker for Testcontainers Redis)
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
