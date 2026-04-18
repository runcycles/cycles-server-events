# Operations guide

Operator-facing runbook for running `cycles-server-events` in production.
Covers metrics, alerting recipes, SLOs, dashboards, and an incident playbook.

Assumes you are already deploying via the published Docker image
(`ghcr.io/runcycles/cycles-server-events:<version>`) with Prometheus scraping
`/actuator/prometheus` on port **7980**. If you haven't set that up yet, see
the Monitoring section of [`README.md`](README.md) first.

Also worth reading: [`cycles-server/OPERATIONS.md`](https://github.com/runcycles/cycles-server/blob/main/OPERATIONS.md)
and [`cycles-server-admin/OPERATIONS.md`](https://github.com/runcycles/cycles-server-admin/blob/main/OPERATIONS.md).
Metrics naming, tag-cardinality controls, and the tenant-tag toggle are
intentionally consistent across all three services so dashboards and
alert rules carry over.

## Table of contents

1. [Metrics inventory](#metrics-inventory)
2. [Alerts worth paging on](#alerts-worth-paging-on)
3. [SLO definitions](#slo-definitions)
4. [Dashboards](#dashboards)
5. [Incident playbook](#incident-playbook)
6. [Configuration tuning](#configuration-tuning)
7. [Getting help](#getting-help)

---

## Metrics inventory

All domain metrics live under the `cycles_webhook_*` namespace (source names
use the dotted `cycles.webhook.*` form; Micrometer's Prometheus registry
normalises to underscored `_total`-suffixed names on scrape). Spring Boot
auto-metrics (`http_server_requests_seconds` for the actuator endpoints,
`jvm_*`, `process_*`, `logback_events_total`) are also emitted and worth
scraping.

This service is the HTTP **client** for webhook delivery. Spring's
`http_server_requests_seconds` only covers *inbound* traffic to `/actuator`,
so outbound latency is tracked by a dedicated domain timer
(`cycles_webhook_delivery_latency_seconds`). This is a deliberate deviation
from `cycles-server`, which is a pure HTTP server and doesn't need one.

### Delivery lifecycle

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_webhook_delivery_attempts_total` | `tenant`, `event_type` | Every HTTP POST attempt to a subscriber endpoint. Increments before the wire call, so it counts intended attempts (including ones that will fail at the transport layer). |
| `cycles_webhook_delivery_success_total` | `tenant`, `event_type`, `status_code_family` | Delivery returned 2xx. `status_code_family=2xx`; other families appear only on the failed counter. |
| `cycles_webhook_delivery_failed_total` | `tenant`, `event_type`, `reason` | Delivery failed. `reason=http_4xx`/`http_5xx`/`transport_error` (HTTP or connection layer), or `event_not_found`/`subscription_not_found`/`subscription_inactive` (upstream resolution layer). |
| `cycles_webhook_delivery_retried_total` | `tenant`, `event_type` | Delivery requeued onto `dispatch:retry` for exponential-backoff retry. Increments once per scheduled retry; the actual retry attempt will increment `cycles_webhook_delivery_attempts_total` again when it fires. |
| `cycles_webhook_delivery_stale_total` | `tenant` | Delivery's `attempted_at` exceeded `MAX_DELIVERY_AGE_MS` (default 24h) and was auto-failed without a delivery attempt. Indicates the service was offline for longer than the stale threshold. `tenant` is `UNKNOWN` here because the subscription isn't loaded on the stale path. |
| `cycles_webhook_subscription_auto_disabled_total` | `tenant`, `reason` | Subscription flipped to `DISABLED` after consecutive failures. `reason=consecutive_failures`. |
| `cycles_webhook_delivery_latency_seconds` | `tenant`, `event_type`, `outcome` | End-to-end outbound HTTP latency. `outcome=success`/`failure`. Only recorded when a transport round-trip actually occurred (upstream failures like `event_not_found` have no meaningful latency and are skipped). |

### Event payload validation

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_webhook_events_payload_invalid_total` | `type`, `rule` | Non-fatal event-payload shape discrepancy. Never blocks delivery; emits a WARN log at the same time. Tag schema parallels `cycles-server-admin`'s `cycles_admin_events_payload_invalid_total{type, expected_class}` so dashboards can pivot between the two services. `rule` values: `missing_required`, `unknown_event_type`, `category_mismatch`, `budget_data_shape`, `reset_spent_shape`. |

### Reason codes

Tag values on the `reason` label of `cycles_webhook_delivery_failed_total`:

- **Transport-layer**: `http_4xx`, `http_5xx`, `transport_error` (connection
  refused, timeout, TLS failure — anything where the HTTP status was not
  captured).
- **Upstream-layer** (failures before the HTTP call happens):
  `event_not_found` (Redis `event:{id}` is missing/expired),
  `subscription_not_found` (Redis `webhook:{id}` is missing),
  `subscription_inactive` (subscription status is `PAUSED` or `DISABLED`).

Stale deliveries (age > `MAX_DELIVERY_AGE_MS`) increment only
`cycles_webhook_delivery_stale_total` — they do **not** double-count into
`failed_total`. Alert on both when you want full failure visibility.

### Tag-cardinality control

The `tenant` tag is the only high-card dimension. For deployments with
thousands of tenants, disable it:

```properties
cycles.metrics.tenant-tag.enabled=false
```

Same property name and default (`true`) as `cycles-server`. When disabled,
per-tenant drill-down is lost but the time-series count drops to
O(event_type × reason × status_code_family) — bounded and small. Flip both
services together to keep dashboards consistent.

Null or blank tag values are normalised to the literal sentinel `UNKNOWN`
(uppercase, matching `cycles-server`).

---

## Alerts worth paging on

Copy-paste these into your `prometheus.rules.yml` and tune thresholds to
your actual traffic. The spirit is "wake someone up only when the system
is behaving unlike itself", not "alert on every failed webhook."

### Availability

```yaml
- alert: CyclesServerEventsDown
  expr: up{job="cycles-server-events"} == 0
  for: 2m
  labels: {severity: page}
  annotations:
    summary: cycles-server-events is down
    runbook: https://github.com/runcycles/cycles-server-events/blob/main/OPERATIONS.md#incident-playbook
```

### Delivery failure rate

Per-subscriber failures are expected — subscriber endpoints have their own
uptime problems. What you care about is *sustained* failure rates across
the whole fleet.

**Denominator note**: `cycles_webhook_delivery_attempts_total` increments
only when an actual HTTP POST is about to be issued (i.e., after upstream
resolution succeeds). Upstream-layer failures (`reason=event_not_found`,
`subscription_not_found`, `subscription_inactive`) still increment
`cycles_webhook_delivery_failed_total` but do **not** increment `attempts`,
so a naive `failed/attempts` ratio overstates the HTTP-layer problem when
upstream failures dominate. The recipe below restricts the numerator to
transport-layer reasons to make the ratio meaningful; pair it with a
separate alert on upstream failures (next block).

```yaml
- alert: CyclesWebhookDeliveryFailureRateHigh
  # >20% of *HTTP* delivery attempts failing over 15 minutes = something
  # systemic at the transport layer (DNS, TLS, outbound egress, or
  # widespread subscriber 5xx).
  expr: |
    sum(rate(cycles_webhook_delivery_failed_total{reason=~"http_4xx|http_5xx|transport_error"}[15m]))
      / sum(rate(cycles_webhook_delivery_attempts_total[15m]))
    > 0.20
  for: 15m
  labels: {severity: ticket}
  annotations:
    summary: "fleet-wide webhook HTTP-layer failure rate > 20%"
    description: "Check Redis, outbound DNS, and the full reason breakdown:
                  sum by (reason) (rate(cycles_webhook_delivery_failed_total[15m]))"
```

### Upstream-layer failures (Redis-side data corruption / drift)

`event_not_found` / `subscription_not_found` should be near-zero in a
healthy system — they mean a delivery was queued for a record that no
longer exists in Redis. Usually a stale test fixture or a TTL mis-set.

```yaml
- alert: CyclesWebhookUpstreamFailures
  expr: |
    sum(rate(cycles_webhook_delivery_failed_total{reason=~"event_not_found|subscription_not_found"}[10m]))
    > 0.05
  for: 10m
  labels: {severity: ticket}
  annotations:
    summary: "webhook deliveries losing events or subscriptions from Redis"
    description: "Either TTLs expired under a backlog (check cycles_webhook_delivery_stale_total)
                  or admin-side deletes raced dispatch. Inspect the delivery log."
```

### Stale deliveries (service-was-down signal)

Any stale delivery means the service was offline long enough for a queued
delivery's `attempted_at` to exceed `MAX_DELIVERY_AGE_MS` (default 24h).
After a restart following a prolonged outage this spike is expected once;
if it keeps happening, the service is restarting too often or the threshold
is too low for your recovery SLA.

```yaml
- alert: CyclesWebhookStaleSpike
  expr: |
    sum(increase(cycles_webhook_delivery_stale_total[30m])) > 10
  for: 5m
  labels: {severity: ticket}
  annotations:
    summary: ">10 stale deliveries in the last 30 min — recent outage?"
    description: "Cross-check with uptime and the restart log. If this is the
                  startup burst after a maintenance window, silence for an hour."
```

### Subscription auto-disables

Auto-disable means N consecutive failures to one subscriber. Usually the
subscriber's fault, not ours — but someone should notice so the tenant can
repair their endpoint.

```yaml
- alert: CyclesWebhookSubscriptionAutoDisabled
  expr: |
    sum(increase(cycles_webhook_subscription_auto_disabled_total[1h])) > 0
  for: 1m
  labels: {severity: info}
  annotations:
    summary: "{{ $value }} subscription(s) auto-disabled in the last hour"
    description: "These subscribers had 10+ consecutive delivery failures.
                  Notify the tenant or re-enable via PATCH /v1/webhooks/{id} status=ACTIVE."
```

### Payload validation warnings (producer-bug signal)

The validator is observability-only; a spike means a producer
(`cycles-server-admin` or `cycles-server`) is emitting events that don't
match the admin spec. Not a page — it's a ticket to open against the
producer.

```yaml
- alert: CyclesWebhookEventsPayloadInvalid
  expr: |
    sum(rate(cycles_webhook_events_payload_invalid_total[15m])) > 0.1
  for: 15m
  labels: {severity: ticket}
  annotations:
    summary: "payload validator firing > 0.1/s for 15m — producer drift"
    description: "Break down by rule: sum by (type, rule) (rate(cycles_webhook_events_payload_invalid_total[15m])).
                  File against the producer (cycles-server-admin or cycles-server), not this service."
```

### Redis connectivity (infers from missing traffic)

The dispatcher blocks on BRPOP; when Redis is unavailable, attempts go to
zero. Pair with the `up` check to distinguish "Redis down" from "no
traffic."

```yaml
- alert: CyclesWebhookAttemptsStopped
  # Zero attempts for 5 min during business hours = Redis connectivity
  # problem (or no producer traffic, which is also worth noticing).
  expr: |
    sum(rate(cycles_webhook_delivery_attempts_total[5m])) == 0
    and on() hour() >= 8 and hour() <= 20
  for: 5m
  labels: {severity: ticket}
  annotations:
    summary: "no webhook delivery attempts for 5 min during business hours"
    description: "Check Redis health and whether producers are emitting events."
```

---

## SLO definitions

Starting point — adjust to your SLA with customers.

| SLO | Target | Source |
|---|---|---|
| Availability (`up == 1`) | 99.9% over 30d | `up{job="cycles-server-events"}` |
| Delivery success rate | ≥ 99% of attempts 2xx over 30d | `rate(cycles_webhook_delivery_success_total[30d]) / rate(cycles_webhook_delivery_attempts_total[30d])`, excluding `reason=~"subscription_inactive\|subscription_not_found"` (those are admin-state issues, not our fault) |
| Outbound delivery p99 latency | ≤ 2s for success-path | `histogram_quantile(0.99, sum by (le) (rate(cycles_webhook_delivery_latency_seconds_bucket{outcome="success"}[5m])))` (requires `percentiles-histogram` — see below) |
| Zero stale on steady state | 0 over 24h in the absence of service restarts | `increase(cycles_webhook_delivery_stale_total[24h])` |
| Payload validation rate | 0 `cycles_webhook_events_payload_invalid_total` outside explicit producer incidents | `rate(cycles_webhook_events_payload_invalid_total[1h])` |

**Note on delivery failures that aren't our fault:** a subscriber's
endpoint returning 5xx or being unreachable is *expected* — the service
retries and eventually auto-disables. It must **not** count against your
delivery-success SLO if the tenant's endpoint is the culprit. Exclude
`reason=subscription_inactive` when computing success ratios, and consider
segmenting by tenant when reporting to detect per-tenant drag.

**Enabling percentile histograms** (opt-in, increases scrape size):

```properties
# application.properties
management.metrics.distribution.percentiles-histogram.cycles.webhook.delivery.latency=true
```

Without this setting, `histogram_quantile` on the delivery latency bucket
returns `NaN`. A mean-latency fallback works without any configuration:

```yaml
- alert: CyclesWebhookMeanLatencyHigh
  expr: |
    (sum(rate(cycles_webhook_delivery_latency_seconds_sum{outcome="success"}[5m])))
    / (sum(rate(cycles_webhook_delivery_latency_seconds_count{outcome="success"}[5m])))
    > 1.0
  for: 10m
  labels: {severity: ticket}
```

---

## Dashboards

We don't ship a Grafana dashboard JSON yet, but a minimum-viable dashboard
should have:

**Row 1 — Delivery rates (by outcome):**
```promql
sum(rate(cycles_webhook_delivery_attempts_total[1m]))
sum(rate(cycles_webhook_delivery_success_total[1m]))
sum by (reason) (rate(cycles_webhook_delivery_failed_total[1m]))
```

**Row 2 — Failure reason breakdown:**
```promql
sum by (reason) (rate(cycles_webhook_delivery_failed_total[5m]))
```

**Row 3 — Retry + stale + auto-disable:**
```promql
sum(rate(cycles_webhook_delivery_retried_total[5m]))
sum(rate(cycles_webhook_delivery_stale_total[5m]))
sum(rate(cycles_webhook_subscription_auto_disabled_total[1h]))
```

**Row 4 — Per-tenant delivery health (top 10 by attempts):**
```promql
topk(10, sum by (tenant) (rate(cycles_webhook_delivery_attempts_total[5m])))
topk(10, sum by (tenant) (rate(cycles_webhook_delivery_failed_total[5m])))
```

**Row 5 — Outbound latency:**
```promql
histogram_quantile(0.99, sum by (le) (rate(cycles_webhook_delivery_latency_seconds_bucket{outcome="success"}[5m])))
# Or, without histogram buckets enabled:
sum(rate(cycles_webhook_delivery_latency_seconds_sum{outcome="success"}[5m]))
  / sum(rate(cycles_webhook_delivery_latency_seconds_count{outcome="success"}[5m]))
```

**Row 6 — Payload validator (producer-drift signal):**
```promql
sum by (type, rule) (rate(cycles_webhook_events_payload_invalid_total[5m]))
```

**Row 7 — Redis queue depth (external scrape via `redis_exporter`, not this service):**
```promql
redis_list_length{key="dispatch:pending"}
redis_zset_length{key="dispatch:retry"}
```

A `redis_exporter` instance scraping the same Redis your dispatcher talks
to gives you queue-depth visibility that this service deliberately does
*not* emit itself (avoiding double-counting and keeping the service
focused on delivery concerns).

Contributions of a packaged dashboard JSON are welcome.

---

## Incident playbook

### Symptom: delivery failure rate spiking fleet-wide

1. Check Redis: `redis-cli -h <host> PING`. If that hangs, skip to "Redis
   unavailable."
2. Break down by reason:
   ```promql
   sum by (reason) (rate(cycles_webhook_delivery_failed_total[10m]))
   ```
   - `transport_error` dominant → DNS, TLS, or outbound egress problem.
     Check the container's network path.
   - `http_5xx` dominant across multiple tenants → likely a shared
     infrastructure problem on the subscriber side (common reverse proxy,
     shared DDoS target). Not our fault, but worth noting in status page.
   - `subscription_inactive` dominant → large batch of subscriptions was
     paused/disabled on the admin side (operator action?). Cross-check
     with admin's audit log.
3. Check the application logs for `Unhandled exception:` stack traces.

### Symptom: Redis unavailable

The dispatcher's BRPOP call blocks; new deliveries sit in
`dispatch:pending` until Redis comes back. Existing in-flight deliveries
fail on their final write-back.

1. Confirm Redis health: `redis-cli PING`, check disk/memory on the
   Redis host.
2. Check `jvm_memory_used_bytes` on the dispatcher side — if memory is
   growing during the outage, the Jedis pool may be leaking connections
   while connect attempts pile up.
3. Once Redis recovers, the service resumes without restart
   (`JedisConnectionException` is caught in the scheduled services; they
   skip the tick and try again on next poll). If it doesn't pick up,
   `kill -15 <pid>` and let your orchestrator restart it.
4. After recovery, expect a one-time spike on
   `cycles_webhook_delivery_stale_total` for any delivery whose
   `attempted_at` now exceeds `MAX_DELIVERY_AGE_MS` — silence the
   `CyclesWebhookStaleSpike` alert for ~1h.

### Symptom: delivery queue backing up

`redis_list_length{key="dispatch:pending"}` climbing without a
corresponding climb in `cycles_webhook_delivery_attempts_total`.

1. Is the dispatcher running? Check `up{job="cycles-server-events"}`.
2. Is it receiving work? `cycles_webhook_delivery_attempts_total` rate
   over the last 5 min.
3. Is it blocked on slow subscribers? Check
   `cycles_webhook_delivery_latency_seconds_sum` rate. A tenant with a
   slow endpoint can hold a dispatcher thread for the full HTTP timeout
   (default 30s).
4. Remediation: scale dispatcher instances horizontally (BRPOP is atomic;
   N instances safely parallelise), or lower
   `dispatch.http.timeout-seconds` so one slow subscriber doesn't block
   the queue.

### Symptom: a specific subscription was auto-disabled

`increase(cycles_webhook_subscription_auto_disabled_total[1h]) > 0`

1. Subscription hit 10 consecutive failures. Find which one:
   ```bash
   redis-cli KEYS "webhook:*" | xargs -I{} redis-cli GET {} | \
     jq -r 'select(.status=="DISABLED") | .subscription_id + " " + .url'
   ```
2. Inspect the delivery log for that subscription:
   ```bash
   redis-cli ZRANGE deliveries:<subscription_id> 0 -1
   # then GET delivery:{id} for each to see response codes / error messages
   ```
3. Notify the tenant that their webhook endpoint is down. Once they fix
   it, admin can PATCH the subscription back to `status=ACTIVE`.
4. This service doesn't re-enable on its own — that's intentional, to
   prevent thundering herds after a long outage.

### Symptom: payload validator firing

```promql
sum by (type, rule) (rate(cycles_webhook_events_payload_invalid_total[15m]))
```

The validator is observability-only; events still deliver. File a ticket
against the **producer**, not this service:

- `missing_required` → producer dropping a required field
  (event_id/event_type/category/timestamp/tenant_id/source). Usually a
  builder that forgot to set one.
- `unknown_event_type` → producer emitting an event type not yet mirrored
  into this service's `EventType` enum. See the AUDIT.md drift-notes
  section for how to add one.
- `category_mismatch` → producer set `category` inconsistently with
  `event_type`. Almost always a bug in the producer's event-builder.
- `budget_data_shape` / `reset_spent_shape` → payload field present but
  wrong type (e.g., `spent_override_provided` as a string instead of
  boolean). Jackson tolerance on this service lets it pass; strict
  consumers downstream will reject.
- `trace_id_shape` → producer wrote a `trace_id` that doesn't match
  `^[0-9a-f]{32}$` (spec v0.1.25.27). Delivery still happens — the
  dispatcher mints a fresh trace-id so the outbound `X-Cycles-Trace-Id`
  header stays well-formed — but downstream auditors won't be able to
  correlate the event back to its originating request. File the ticket
  against the producer (almost always `cycles-server-admin`).

### Symptom: stale spike after a clean startup

If `cycles_webhook_delivery_stale_total` fires but you haven't had an
outage, check:
- `MAX_DELIVERY_AGE_MS` may be set too low for your workload. Default
  24h; legitimate deployments with intentional multi-day delays need a
  higher value.
- Producer may be backdating deliveries (e.g., replaying historical
  events). Check the producer's `attempted_at` assignment.

---

## Configuration tuning

All configurable via environment variables (most) or `application.properties`.
Defaults are sensible for most deployments; tune these when the defaults
don't fit.

| Property / Env Var | Default | When to change |
|---|---|---|
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | `localhost`, `6379`, (empty) | Always set for production. |
| `WEBHOOK_SECRET_ENCRYPTION_KEY` | (empty) | Set to a base64-encoded 32-byte key to enable AES-256-GCM for webhook signing secrets at rest. Must match the key configured in `cycles-server-admin`. If empty, secrets are stored/read as plaintext (backward-compatible, not recommended for production). |
| `dispatch.http.timeout-seconds` | `30` | Lower if one slow subscriber is blocking the queue and you can tolerate more retries. Raise if you have legitimately slow subscribers that need more time. |
| `dispatch.http.connect-timeout-seconds` | `5` | Rarely needs tuning. Lower if your egress is fast and you want to fail faster on unreachable DNS. |
| `MAX_DELIVERY_AGE_MS` | `86400000` (24h) | Raise if you legitimately replay multi-day-old events. Lower (with caution) if you'd rather drop old deliveries than burden subscribers with stale data. |
| `EVENT_TTL_DAYS` | `90` | Matches spec "90 days hot" recommendation. Lower only if Redis memory is tight. |
| `DELIVERY_TTL_DAYS` | `14` | Two weeks of delivery history. Lower if Redis memory is tight; raise if compliance review needs a longer window. |
| `RETRY_BATCH_SIZE` | `100` | Max retries requeued per poll cycle. Raise on spiky workloads where retries cluster. |
| `dispatch.retry.poll-interval-ms` | `5000` | How often `RetryScheduler` drains `dispatch:retry`. Lower for tighter retry latency at the cost of slightly more Redis load. |
| `RETENTION_CLEANUP_INTERVAL_MS` | `3600000` (1h) | How often `RetentionCleanupService` trims expired ZSET entries. Rarely needs tuning. |
| `cycles.metrics.tenant-tag.enabled` | `true` | Set `false` if you have thousands of tenants and Prometheus cardinality is stressed. Flip both this service and `cycles-server` together for dashboard consistency. |
| `spring.task.scheduling.pool.size` | `3` | Size of the scheduled-task executor (dispatch loop + retry scheduler + cleanup). Don't lower below 3 — each needs its own thread to avoid starvation. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Add more actuator endpoints if needed, but `prometheus` is the one ops cares about. |

## Getting help

- Bug reports / feature requests:
  https://github.com/runcycles/cycles-server-events/issues
- Release notes: [`CHANGELOG.md`](CHANGELOG.md)
- Engineering history & rationale: [`AUDIT.md`](AUDIT.md)
- Sibling service runbooks:
  - https://github.com/runcycles/cycles-server/blob/main/OPERATIONS.md
  - https://github.com/runcycles/cycles-server-admin/blob/main/OPERATIONS.md
