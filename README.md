# cycles-server-events

Event delivery service for the Cycles ecosystem. Consumes events from Redis and delivers them to webhook endpoints with HMAC-SHA256 signing, exponential backoff retry, and auto-disable on consecutive failures.

## Architecture

```
cycles-server-admin / cycles-server
    │ save event + create PENDING delivery
    │ LPUSH dispatch:pending
    ▼
Redis ──BRPOP──► cycles-server-events
                    │
                    ├── Load event + subscription + secret
                    ├── Select transport (webhook)
                    ├── HTTP POST + X-Cycles-Signature
                    ├── On success: mark SUCCESS, reset failures
                    └── On failure: schedule retry or auto-disable
```

## Quick Start

```bash
docker compose up
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_HOST` | localhost | Redis hostname |
| `REDIS_PORT` | 6379 | Redis port |
| `REDIS_PASSWORD` | (empty) | Redis password |
| `dispatch.pending.timeout-seconds` | 5 | BRPOP timeout |
| `dispatch.retry.poll-interval-ms` | 5000 | Retry check interval |
| `dispatch.http.timeout-seconds` | 30 | HTTP request timeout |

## Transport Layer

Pluggable transport interface. Currently implements `webhook` (HTTP POST). Future transports: Slack, PagerDuty, SQS, email.

```java
public interface Transport {
    String type();
    TransportResult deliver(Event event, Subscription subscription, String signingSecret);
}
```

## Redis Keys (shared with cycles-server-admin)

| Key | Type | Description |
|-----|------|-------------|
| `dispatch:pending` | LIST | Delivery IDs awaiting processing |
| `dispatch:retry` | ZSET | Delivery IDs scheduled for retry (score = next_retry_at) |
| `delivery:{id}` | String | Delivery record JSON |
| `event:{id}` | String | Event record JSON |
| `webhook:{id}` | String | Subscription JSON |
| `webhook:secret:{id}` | String | HMAC signing secret |

## Webhook Delivery Headers

```
Content-Type: application/json
X-Cycles-Signature: sha256=<hmac-hex>
X-Cycles-Event-Id: evt_abc123
X-Cycles-Event-Type: budget.exhausted
User-Agent: cycles-server-events/0.1.0
```

## Delivery Status Lifecycle

```
PENDING ──HTTP POST──► SUCCESS
    │
    └──fail──► RETRYING ──retry──► SUCCESS
                   │
                   └──max retries──► FAILED
                                       │
                                       └──consecutive──► subscription DISABLED
```
