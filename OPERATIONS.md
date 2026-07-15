# Operations guide

Operator-facing runbook for running `cycles-server-events` in production.
Covers metrics, alerting recipes, SLOs, dashboards, and an incident playbook.

Assumes you are already deploying via the published Docker image
(`ghcr.io/runcycles/cycles-server-events:<version>`) with Prometheus scraping
`/actuator/prometheus` on the **management port (9980)**. This worker has no
public HTTP API; do not publish 7980 on an ingress. Use
`/actuator/health/readiness` for dependency-aware readiness, and see the
Monitoring section of [`README.md`](README.md) for endpoint details.

**Management-port security posture (deliberate exception).** Unlike
`cycles-server` and `cycles-server-admin` — whose actuator endpoints share
the API port and are therefore gated behind `X-Admin-API-Key` since their
v0.1.25.45-era hardening — this worker's actuator lives on a **separate
management port (9980) with no authentication**. The separate port IS the
isolation mechanism: bind it to an internal-only network (Prometheus
scrapes over the container network), never publish it on a host or ingress,
and treat any reachable 9980 from outside the deployment network as a
misconfiguration. Container healthchecks probe `127.0.0.1:9980` in-container
and need no host publish. If your environment cannot provide network-level
isolation for 9980, front it with an authenticating proxy — this worker
deliberately ships no Spring Security of its own (it has no API surface to
protect and no key-management plane to validate against).

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
7. [Pre-release drift checklist](#pre-release-drift-checklist)
8. [Getting help](#getting-help)

---

## Metrics inventory

Domain metrics live under the `cycles_webhook_*` and `cycles_evidence_*` namespaces (source names
use dotted `cycles.webhook.*` / `cycles.evidence.*` forms; Micrometer's Prometheus registry
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

The `tenant` tag listed here is emitted only when
`CYCLES_METRICS_TENANT_TAG_ENABLED=true`; production defaults omit it.

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_webhook_delivery_attempts_total` | `tenant`, `event_type` | Every HTTP POST attempt to a subscriber endpoint. Increments before the wire call, so it counts intended attempts (including ones that will fail at the transport layer). |
| `cycles_webhook_delivery_success_total` | `tenant`, `event_type`, `status_code_family` | Delivery returned 2xx. `status_code_family=2xx`; other families appear only on the failed counter. |
| `cycles_webhook_delivery_failed_total` | `tenant`, `event_type`, `reason` | Delivery failed. `reason=http_4xx`/`http_5xx`/`transport_error` (HTTP or connection layer), or `event_not_found`/`subscription_not_found`/`subscription_inactive` (upstream resolution layer). |
| `cycles_webhook_delivery_retried_total` | `tenant`, `event_type` | Delivery requeued onto `dispatch:retry` for exponential-backoff retry. Increments once per scheduled retry; the actual retry attempt will increment `cycles_webhook_delivery_attempts_total` again when it fires. |
| `cycles_webhook_delivery_stale_total` | `tenant` | Delivery's `attempted_at` exceeded `MAX_DELIVERY_AGE_MS` (default 24h) and was auto-failed without a delivery attempt. Indicates the service was offline for longer than the stale threshold. `tenant` is `UNKNOWN` here because the subscription isn't loaded on the stale path. |
| `cycles_webhook_delivery_dead_lettered_total` | `reason` | A deterministically corrupt delivery record moved to the bounded `dispatch:failed` quarantine. `reason=corrupt_record`; any increase requires inspection and repair. |
| `cycles_webhook_subscription_auto_disabled_total` | `tenant`, `reason` | Subscription flipped to `DISABLED` after consecutive failures. `reason=consecutive_failures`. |
| `cycles_webhook_delivery_latency_seconds` | `tenant`, `event_type`, `outcome` | End-to-end outbound HTTP latency. `outcome=success`/`failure`. Only recorded when a transport round-trip actually occurred (upstream failures like `event_not_found` have no meaningful latency and are skipped). |
| `cycles_webhook_dispatcher_event_published_total` | `event_type` | Durable dispatcher meta-event task published and acknowledged. Event types are bounded to `webhook.disabled` and `system.webhook_delivery_failed`. |
| `cycles_webhook_dispatcher_event_deferred_total` | `event_type`, `reason` | Meta-event publication retained for retry after `inline_publish_failure`, `publish_failure`, `scan_failure`, or `claim_failure`. |
| `cycles_webhook_dispatcher_event_dead_lettered_total` | `event_type`, `reason` | A repeatedly unpublishable meta-event task exhausted its bounded retry budget and moved to `dispatcher:event-outbox:failed`. Any increase requires operator inspection. |
| `cycles_webhook_security_config_indeterminate_total` | none | Delivery-time SSRF policy could not be parsed or loaded. The delivery is retained for recovery; a sustained increase can stop all webhook sends. |

### Event payload validation

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_webhook_events_payload_invalid_total` | `type`, `rule` | Non-fatal event-payload shape discrepancy. Never blocks delivery; emits a WARN log at the same time. Tag schema parallels `cycles-server-admin`'s `cycles_admin_events_payload_invalid_total{type, expected_class}` so dashboards can pivot between the two services. `rule` values: `missing_required`, `unknown_event_type`, `category_mismatch`, `budget_data_shape`, `reset_spent_shape`. |

### Evidence lifecycle

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_evidence_claimed_total` | `artifact_type` | Evidence source records claimed from the reliable queue. |
| `cycles_evidence_stored_total` | `artifact_type` | Canonical signed envelopes durably stored. |
| `cycles_evidence_dead_lettered_total` | `artifact_type`, `reason` | Deterministically invalid source records atomically moved to the DLQ. |
| `cycles_evidence_retry_deferred_total` | `artifact_type`, `reason` | Work retained for recovery (`identity_mismatch`, `build_failure`, `store_failure`, `ack_failure`, `ack_conflict`, `queue_unavailable`, `claim_failure`, `dlq_move_conflict`, or `dlq_move_failure`). Identity/signing/store failures also pause new claims for `EVIDENCE_INFRASTRUCTURE_BACKOFF_MS` (30 seconds by default) to limit in-flight growth. |

### Reason codes

Tag values on the `reason` label of `cycles_webhook_delivery_failed_total`:

- **Transport-layer**: `http_4xx`, `http_5xx`, `transport_error` (connection
  refused, timeout, TLS failure — anything where the HTTP status was not
  captured).
- **Upstream-layer** (failures before the HTTP call happens):
  `event_not_found` (Redis `event:{id}` is missing/expired),
  `subscription_not_found` (Redis `webhook:{id}` is missing),
  `subscription_inactive` (subscription status is `PAUSED` or `DISABLED`),
  `ssrf_blocked` (the current delivery target violates webhook egress policy),
  or `missing_signing_secret` (delivery was retained for recovery rather than
  sent unsigned; a persistent secret/configuration gap eventually reaches the
  normal stale-delivery age gate).

Stale deliveries (age > `MAX_DELIVERY_AGE_MS`) increment only
`cycles_webhook_delivery_stale_total` — they do **not** double-count into
`failed_total`. Alert on both when you want full failure visibility.

### Tag-cardinality control

The `tenant` tag is the only high-card dimension. It is disabled by default for
production cardinality control. Enable it only when per-tenant drill-down is
worth the additional time series:

```properties
cycles.metrics.tenant-tag.enabled=true
```

When disabled, per-tenant drill-down is lost but the time-series count drops to
O(event_type × reason × status_code_family) — bounded and small. Flip sibling
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

### Security configuration and durable-outbox integrity

A malformed `blocked_cidr_ranges` entry intentionally fails closed. Because the
same configuration is evaluated for every target, one bad entry can pause the
fleet until corrected. A poison dispatcher meta-event is bounded and moved to a
DLQ instead of warning every five seconds forever.

```yaml
- alert: CyclesWebhookSecurityConfigIndeterminate
  expr: increase(cycles_webhook_security_config_indeterminate_total[5m]) > 0
  for: 1m
  labels: {severity: page}
  annotations:
    summary: "webhook egress security configuration is unreadable"
    description: "Inspect config:webhook-security, especially every blocked_cidr_ranges entry. Deliveries are retained, not sent."

- alert: CyclesWebhookDispatcherEventDeadLettered
  expr: increase(cycles_webhook_dispatcher_event_dead_lettered_total[15m]) > 0
  for: 1m
  labels: {severity: ticket}
  annotations:
    summary: "dispatcher meta-event moved to its dead-letter queue"
    description: "Inspect LRANGE dispatcher:event-outbox:failed 0 10 and correct the malformed or persistently unpublishable task before replay."

- alert: CyclesWebhookDeliveryDeadLettered
  expr: increase(cycles_webhook_delivery_dead_lettered_total{reason="corrupt_record"}[15m]) > 0
  for: 1m
  labels: {severity: page}
  annotations:
    summary: "corrupt webhook delivery moved to quarantine"
    description: "Inspect LRANGE dispatch:failed 0 10 and the retained delivery:{id}; repair the foreign/corrupt writer before replay."
```

### Redis connectivity (infers from missing traffic)

The dispatcher claims with BLMOVE; when Redis is unavailable, attempts go to
zero and readiness goes DOWN because the Redis health indicator cannot `PING`.
Pair traffic alerts with the readiness endpoint to distinguish "Redis down"
from "no producer traffic."

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
`reason=subscription_inactive` when computing success ratios. If
`CYCLES_METRICS_TENANT_TAG_ENABLED=true`, segment by tenant when reporting to
detect per-tenant drag.

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

**Row 4 — Per-tenant delivery health (requires `CYCLES_METRICS_TENANT_TAG_ENABLED=true`):**
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
redis_list_length{key="dispatch:processing"}
redis_zset_length{key="dispatch:retry"}
redis_zset_length{key="dispatcher:event-outbox:pending"}
redis_list_length{key="dispatcher:event-outbox:failed"}
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

The dispatcher claims work with `BLMOVE dispatch:pending -> dispatch:processing`.
When Redis is unavailable, new deliveries sit in `dispatch:pending`; deliveries
that were already claimed remain in `dispatch:processing` until ack or stale
recovery.

1. Confirm Redis health: `redis-cli PING`, check disk/memory on the
   Redis host.
2. Check `jvm_memory_used_bytes` on the dispatcher side — if memory is
   growing during the outage, the Jedis pool may be leaking connections
   while connect attempts pile up.
3. Once Redis recovers, the service resumes without restart
   (`JedisConnectionException` is caught in the scheduled services; they
   skip the tick and try again on next poll). If a pod crashed while a
   delivery was in-flight, recovery moves `dispatch:processing` entries back
   to `dispatch:pending` only after `DISPATCH_PROCESSING_RECOVERY_IDLE_MS`.
   This avoids requeueing another live replica's active delivery during a
   rolling deploy.
4. After recovery, expect a one-time spike on
   `cycles_webhook_delivery_stale_total` for any delivery whose
   `attempted_at` now exceeds `MAX_DELIVERY_AGE_MS` — silence the
   `CyclesWebhookStaleSpike` alert for ~1h.

### Symptom: delivery queue backing up

`redis_list_length{key="dispatch:pending"}` or
`redis_list_length{key="dispatch:processing"}` climbing without a corresponding
climb in `cycles_webhook_delivery_attempts_total`.

1. Is the dispatcher running? Check `up{job="cycles-server-events"}`.
2. Is it receiving work? `cycles_webhook_delivery_attempts_total` rate
   over the last 5 min.
3. Is it blocked on slow subscribers? Check
   `cycles_webhook_delivery_latency_seconds_sum` rate. A tenant with a
   slow endpoint can hold a dispatcher thread for the full HTTP timeout
   (default 30s).
4. The global `dispatch:ordering:lock` deliberately permits only one claim/send
   section fleet-wide. At the 30-second HTTP timeout the worst-case ceiling is
   roughly two webhook deliveries per minute, and adding replicas improves
   failover only. Lower `dispatch.http.timeout-seconds`, repair the slow target,
   or raise `MAX_DELIVERY_AGE_MS` while draining an accepted backlog. Parallel
   delivery requires a future per-subscription/tenant ordering design; do not
   remove the global lock ad hoc.

### Symptom: security policy is indeterminate

`cycles_webhook_security_config_indeterminate_total` is increasing and delivery
attempts have stopped.

1. Read `GET config:webhook-security` and validate every
   `blocked_cidr_ranges` value as a literal IPv4/IPv6 CIDR. Blank values,
   hostnames, zone identifiers, and malformed prefixes are rejected.
2. Correct the config through the admin plane. Do not delete the key merely to
   bypass validation unless accepting the documented hardened fallback is an
   explicit incident decision.
   The events-side absent-key fallback intentionally blocks `0.0.0.0/8`,
   `100.64.0.0/10`, `fe80::/10`, and `::/128` in addition to the current admin
   fallback. Until the admin service aligns those defaults, a URL accepted by
   admin without a stored config can still fail closed here at delivery.
3. No delivery replay is normally needed: affected records remain in
   `dispatch:processing` and return to pending after the recovery idle window.

### Symptom: corrupt delivery in the quarantine

`cycles_webhook_delivery_dead_lettered_total{reason="corrupt_record"}` increased.

1. Inspect `LRANGE dispatch:failed 0 10`. Each wrapper contains
   `delivery_id`, `quarantined_at_ms`, `reason`, and the raw stored `payload`.
2. Read the retained `GET delivery:{delivery_id}` and identify the foreign or
   incompatible writer. Valid statuses are `PENDING`, `RETRYING`, `SUCCESS`,
   and `FAILED`; normal delivery JSON must deserialize to the current model.
3. Repair the source value first. To replay, `LPUSH dispatch:pending
   {delivery_id}` and remove the matching quarantine wrapper only after the
   delivery reaches a terminal state. Never replay the raw wrapper itself.
4. The quarantine keeps the newest `DISPATCH_FAILED_MAX_LEN` entries (10000 by
   default), so alerting is required before older evidence is trimmed.

### Symptom: dispatcher meta-event in the DLQ

1. Inspect `LRANGE dispatcher:event-outbox:failed 0 10`. Each entry is a JSON
   wrapper containing `task_id` and the original serialized `payload` (empty if
   the payload had already disappeared).
2. Fix the serialization or Redis event-save failure. The default retry budget
   is 100 attempts and the DLQ retains the newest 10000 entries.
3. Replay a repaired task by restoring
   `dispatcher:event-outbox:task:{task_id}` and `ZADD`ing its ID to
   `dispatcher:event-outbox:pending`; deterministic event IDs make replay
   idempotent. Remove the DLQ copy only after the published counter increments.

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
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD` | `localhost`, `6379`, (empty), (empty) | Always set for production; use a least-privilege ACL identity. Jedis sets client name `cycles-server-events`, so the ACL must include `+client|setname`. |
| `REDIS_TLS_ENABLED` | `false` | Enable for off-host production Redis. JVM trust configuration must trust the Redis certificate. |
| `REDIS_CONNECT_TIMEOUT_MS` / `REDIS_SOCKET_TIMEOUT_MS` / `REDIS_BLOCKING_SOCKET_TIMEOUT_MS` | `2000` / `5000` / `10000` | Connection, ordinary-command, and blocking-command socket bounds. The blocking timeout must exceed every BLMOVE command timeout so an unavailable peer cannot pin a scheduler thread forever. |
| `WEBHOOK_SECRET_ENCRYPTION_KEY` | (empty) | Set to a base64-encoded 32-byte key to enable AES-256-GCM for webhook signing secrets at rest. Must match the key configured in `cycles-server-admin`. If empty, secrets are stored/read as plaintext (backward-compatible, not recommended for production). |
| `dispatch.http.timeout-seconds` | `30` | Lower if one slow subscriber is blocking the queue and you can tolerate more retries. Raise if you have legitimately slow subscribers that need more time. |
| `dispatch.http.connect-timeout-seconds` | `5` | Rarely needs tuning. Lower if your egress is fast and you want to fail faster on unreachable DNS. |
| `DISPATCH_LOOP_DELAY_MS` | `25` | Pause after each dispatch invocation. This is not the empty-queue wait; BLMOVE can block for `dispatch.pending.timeout-seconds`. |
| `DISPATCH_PROCESSING_RECOVERY_IDLE_MS` | `180000` | Minimum age before an in-flight delivery is requeued. It must exceed both `DISPATCH_ORDERING_LEASE_MS` and the configured pending-plus-HTTP timeout duration; startup rejects unsafe combinations. |
| `DISPATCH_PROCESSING_RECOVERY_INTERVAL_MS` | `30000` | How often replicas recheck for stale in-flight deliveries. |
| `DISPATCH_FAILED_MAX_LEN` | `10000` | Number of newest corrupt-delivery wrappers retained in `dispatch:failed`. Raise only with a Redis memory budget and alerting in place. |
| `DISPATCH_ORDERING_LEASE_MS` | `120000` | Global claim/send critical-section TTL. It preserves initial-claim order but caps fleet throughput at one in-flight send; replicas are standby/failover. It must exceed BLMOVE plus HTTP and Redis state-write time. |
| `DISPATCH_ORDERING_CONTENTION_BACKOFF_MS` | `500` | Randomized half-to-full backoff after a global ordering-lease miss or Redis error. Raise if idle replicas create noticeable lease traffic. |
| `DISPATCH_EVENT_OUTBOX_POLL_INTERVAL_MS` / `DISPATCH_EVENT_OUTBOX_BATCH_SIZE` | `1000` / `25` | Poll cadence and maximum durable dispatcher meta-event tasks considered per pass. |
| `DISPATCH_EVENT_OUTBOX_CLAIM_LEASE_MS` / `DISPATCH_EVENT_OUTBOX_RETRY_DELAY_MS` | `30000` / `5000` | Per-task owner lease and next-attempt delay. Keep the lease above normal Redis event-save latency. |
| `DISPATCH_EVENT_OUTBOX_MAX_ATTEMPTS` / `DISPATCH_EVENT_OUTBOX_FAILED_MAX_LEN` | `100` / `10000` | Attempts before an unpublishable task moves to the bounded DLQ, and the number of newest DLQ payloads retained. |
| `MAX_DELIVERY_AGE_MS` | `86400000` (24h) | Raise if you legitimately replay multi-day-old events. Lower (with caution) if you'd rather drop old deliveries than burden subscribers with stale data. |
| `EVENT_TTL_DAYS` | `90` | Matches spec "90 days hot" recommendation. Lower only if Redis memory is tight. |
| `DELIVERY_TTL_DAYS` | `14` | Two weeks of delivery history. Lower if Redis memory is tight; raise if compliance review needs a longer window. |
| `RETRY_BATCH_SIZE` | `100` | Max retries requeued per poll cycle. Raise on spiky workloads where retries cluster. |
| `dispatch.retry.poll-interval-ms` | `5000` | How often `RetryScheduler` drains `dispatch:retry`. Lower for tighter retry latency at the cost of slightly more Redis load. |
| `RETENTION_CLEANUP_INTERVAL_MS` | `3600000` (1h) | How often `RetentionCleanupService` trims expired ZSET entries. Rarely needs tuning. |
| `RETENTION_LOCK_LEASE_MS` | `300000` (5m) | Cross-replica cleanup lease. Keep above the expected cleanup duration. An owner-token release cannot delete a successor's lease. |
| `CYCLES_METRICS_TENANT_TAG_ENABLED` | `false` | Set `true` only if per-tenant Prometheus drill-down is worth the added cardinality. Flip sibling services together for dashboard consistency. |
| `JAVA_OPTS` | (empty) | Extra JVM options appended after the Docker image defaults, for example heap sizing or GC logging flags. |
| `spring.task.scheduling.pool.size` (`SCHEDULING_POOL_SIZE`) | `5` | Size of the scheduled-task executor. `DispatchLoop` always runs a continuous webhook BLMOVE claim loop; `EvidenceWorker` adds a second BLMOVE loop only when `EVIDENCE_SERVER_ID` is set. **Don't lower below 5** when evidence is enabled or webhook retries/cleanup can starve behind the blocking loops (see "Scheduler pool" under the runbook below). |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Add more actuator endpoints if needed, but `prometheus` is the one ops cares about. |
| `MANAGEMENT_PORT` | `9980` | Separate port actuators bind to — keep this on an internal-only network. This worker has no public HTTP API; do not publish 7980 on an ingress. |
| `EVIDENCE_SERVER_ID` | (empty) | CyclesEvidence `server_id`. Blank disables the evidence signer. When set, it must be an absolute URI and **byte-identical to `cycles-server`'s** value (incl. the `/v1` base), or mismatched work remains in-flight while new claims pause briefly. See the enablement runbook below. |
| `EVIDENCE_SIGNING_SIGNER_DID` | (empty) | Public Ed25519 key (64 hex) stamped as `signer_did`. Must be the public half of `EVIDENCE_SIGNING_PRIVATE_KEY_HEX` **and** identical to `cycles-server`'s value. |
| `EVIDENCE_SIGNING_PRIVATE_KEY_HEX` | (empty) | **Secret** — Ed25519 seed (64 hex) this worker signs with. Lives **only** here, never on `cycles-server`. Required when evidence is enabled unless `EVIDENCE_ALLOW_EPHEMERAL_SIGNING_KEY=true`. Setting only one of the signing pair fails startup. |
| `EVIDENCE_ALLOW_EPHEMERAL_SIGNING_KEY` | `false` | Development-only escape hatch. When `true`, evidence can start without a configured signing pair and will generate a per-process ephemeral signer. Keep `false` in production. |
| `EVIDENCE_PENDING_KEY` / `EVIDENCE_PROCESSING_KEY` / `EVIDENCE_FAILED_KEY` | `evidence:pending` / `evidence:processing` / `evidence:failed` | Source, in-flight, and bounded poison-record queue keys. Change only for coordinated migrations or isolated environments. |
| `EVIDENCE_POP_TIMEOUT_SECONDS` | `5` | Evidence BLMOVE timeout. It must remain below `REDIS_BLOCKING_SOCKET_TIMEOUT_MS`. |
| `EVIDENCE_RECOVERY_IDLE_MS` / `EVIDENCE_RECOVERY_INTERVAL_MS` | `120000` / `30000` | Minimum age and polling interval for returning stale evidence work from processing to pending. Keep the idle threshold above normal schema-validation, signing, and store latency. |
| `EVIDENCE_RECOVERY_BATCH_SIZE` | `100` | Bounded number of in-flight evidence records inspected per recovery pass. |
| `EVIDENCE_FAILED_MAX_LEN` | `10000` | Positive DLQ bound; poison records beyond the bound evict the oldest entries. |
| `EVIDENCE_LOOP_DELAY_MS` / `EVIDENCE_QUEUE_FAILURE_BACKOFF_MS` | `25` / `1000` | Scheduler cadence and claim pause after Redis queue/ack failures. Raise the backoff if a Redis outage still produces excessive connection logs. |
| `EVIDENCE_INFRASTRUCTURE_BACKOFF_MS` | `30000` | Claim pause after identity, signing, or store failures. This bounds how quickly a systemic outage can move pending records into processing. |
| `EVIDENCE_STORE_BACKEND` / `EVIDENCE_STORE_KEY_PREFIX` / `EVIDENCE_STORE_TTL_SECONDS` | `redis` / `evidence:envelope:` / `0` | Content-addressed envelope backend, Redis key namespace, and TTL. Keep `0` for archival evidence unless a separate durable archive exists. |

Turning CyclesEvidence **on** (the shared identity across `cycles-server` + this
worker, key generation, coherence rules, and end-to-end verification) is documented
in [docs/evidence-identity-enablement.md](docs/evidence-identity-enablement.md).

## Pre-release drift checklist

Each of these has caused an actual drift incident in this repo's
history; they are cheap to verify and painful to discover after a tag
is published. Run through the list before opening the release PR,
before tagging, and after `release.yml` finishes. A five-minute check
here prevents the multi-release consolidation windows we've had to
patch around (see the v0.1.25.10 consolidation note in `CHANGELOG.md`).

### Before opening the release PR

- **`pom.xml` `<revision>` is bumped to the target version.** Grep the
  root `pom.xml` and any module `pom.xml` for a stale `<revision>`. A
  PR that edits code + CHANGELOG but forgets the pom bump will build
  and publish the *prior* version — silently.
- **`CHANGELOG.md` top entry heading matches the pom revision exactly.**
  `## [X.Y.Z.W] — YYYY-MM-DD` — bracketed version, em-dash, ISO date.
  Version must be string-equal to `<revision>`, not a near-match
  (`0.1.25.10` ≠ `0.1.25.10.0`). If a prior version landed on main
  without its own CHANGELOG entry (the v0.1.25.9 → .10 drift), either
  backfill the missing entry in a separate PR *first* or document the
  consolidation explicitly in the current entry's body (the .10 note
  is the template). Do not silently skip versions.
- **`AUDIT.md` has a matching section for this version.** Header date
  should match the CHANGELOG date. The rest is narrative — what
  changed, why, what was considered and rejected, trade-offs. Missing
  AUDIT entries are a CLAUDE.md invariant violation ("always update
  AUDIT.md files when making changes").
- **No `Unreleased` placeholder in the release commit.** If the
  CHANGELOG carried an `## [Unreleased]` section, its contents should
  have moved into the new `[X.Y.Z.W]` entry; the `[Unreleased]` heading
  can stay empty for the next cycle or be omitted entirely.

### Before tagging

- **Every commit on main since the last tag has a home in CHANGELOG.**
  `git log --oneline <last-tag>..HEAD` vs. the top `[X.Y.Z.W]` entry's
  bullet list. Dependabot bumps, compose updates, and chore commits
  often fall through the cracks — either list them under `### Changed`
  / `### Internal` or consolidate with a single "plus N transitive
  dependency bumps" line.
- **No un-tagged intermediate version on main.** Before tagging
  `vX.Y.Z.W`, confirm the previous pom revision (`vX.Y.Z.W-1`) was
  either (a) tagged + released, or (b) explicitly folded into the
  current entry's CHANGELOG body. The v0.1.25.10 drift was exactly
  this — pom had moved to .10, but .9 was never tagged.
- **Tag message matches the release.** `git tag -a vX.Y.Z.W -m "..."`
  — the message body should be a short prose paragraph matching the
  CHANGELOG entry's headline, not a raw copy-paste of bullets.

### After `release.yml` finishes

- **Published image tag matches pom revision exactly.** Pull
  `ghcr.io/runcycles/cycles-server-events:X.Y.Z.W` and
  `docker image inspect` to confirm the label / manifest. If
  `release.yml` published a `:latest` tag, confirm `:latest` and
  `:X.Y.Z.W` resolve to the same digest.
- **Release notes on GitHub match CHANGELOG.** `gh release view
  vX.Y.Z.W` — the body should be the CHANGELOG entry verbatim (or
  close), not a raw autogenerated commit list.
- **Next cycle: bump pom immediately.** To prevent a repeat of the
  .9→.10 drift, bump `<revision>` to the *next* planned version as
  soon as the release is cut, even if no work is scheduled yet. This
  makes "un-tagged pom revision on main" a louder signal — the next
  PR will either add a CHANGELOG entry at the new version or roll the
  pom back, not silently ship under the new number.

## CyclesEvidence signing worker

The `EvidenceWorker` consumes source records cycles-server pushes to
`evidence:pending`, signs a `cycles-evidence/v0.1` envelope for each, and hands
it to the sink. Operational notes:

- **`EVIDENCE_SERVER_ID` enables the signer.** Blank means the evidence signer
  is disabled; it will not claim from `evidence:pending`, and source records are
  not dead-lettered by this service. Current cycles-server producers also stop
  queueing new evidence source records when the public evidence identity is
  incomplete. Records that were already in `evidence:pending` before disabling
  remain there until evidence is re-enabled or an operator drains them manually.
  Set it to the deployment's stable Cycles server URI to turn signing on.
- **Signing key must be configured when evidence is enabled.** Set BOTH
  `EVIDENCE_SIGNING_PRIVATE_KEY_HEX` and `EVIDENCE_SIGNING_SIGNER_DID` (a paired
  Ed25519 key). If both are unset, startup fails by default. For local
  development only, `EVIDENCE_ALLOW_EPHEMERAL_SIGNING_KEY=true` generates an
  **ephemeral** key per process and logs a WARN, so signatures will not survive a
  restart and multiple un-configured replicas would each sign with a different
  `signer_did`. Provision the key once and set it identically on every replica.
- **Reliable queue.** The worker claims records with `BLMOVE evidence:pending →
  evidence:processing`, replaces the raw list member with a unique claim ID, and
  retains the raw payload in `evidence:processing:payload`. Ack and DLQ scripts
  remove only the owned claim ID after the envelope is stored (or atomically
  dead-lettered). This keeps byte-identical source records independent and fences
  workers that resume after recovery. Idle in-flight records are
  periodically moved back to `evidence:pending` and reprocessed (safe — envelopes
  are content-addressed/idempotent). A non-empty
  `evidence:processing` on a healthy steady-state service is a smell (stuck
  worker); a brief spike after a crash is normal recovery. Claim timestamps prevent
  a starting replica from requeueing another healthy replica's active work.
- **Rolling upgrade into 0.1.25.24.** The processing-list member changed from raw
  JSON to an internal claim ID. Drain `evidence:processing` and stop or disable
  evidence signing on every older replica before starting 0.1.25.24 workers;
  do not run old and new evidence consumers concurrently. Pending raw records are
  unchanged and can remain queued during the rollout.
- **Identity drift is recoverable.** If the producer-stamped `evidence_id` does
  not match the worker's result, the record stays in `evidence:processing` and
  new claims pause for 30 seconds. Reconcile `EVIDENCE_SERVER_ID` and
  `EVIDENCE_SIGNING_SIGNER_DID`; stale recovery will then replay the record.
- **Dead-letter queue.** Only deterministically invalid source JSON/schema data
  is LPUSH'd to `evidence:failed` (not dropped). Monitor `LLEN evidence:failed`;
  a growing value means producer output violates the evidence contract. Inspect
  with `LRANGE evidence:failed 0 10` and replay after fixing.
- **Dispatcher meta-event outbox.** Final webhook retry exhaustion atomically
  stages `system.webhook_delivery_failed` and, on the qualifying transition,
  `webhook.disabled`. Monitor `ZCARD dispatcher:event-outbox:pending` and the
  three `cycles_webhook_dispatcher_event_*` metrics. Tasks use deterministic
  event IDs and are safe to replay. Publish failures retry up to
  `DISPATCH_EVENT_OUTBOX_MAX_ATTEMPTS`, then move to the bounded
  `dispatcher:event-outbox:failed` list for operator action.
- **Scheduler pool.** `SCHEDULING_POOL_SIZE` (default 5) must stay >= the number
  of continuous blocking loops (`DispatchLoop`, plus `EvidenceWorker` when
  evidence is enabled) plus headroom for periodic tasks, or webhook retries and
  cleanup can starve.
- **Envelope store.** Built envelopes are persisted content-addressed at
  `evidence:envelope:<evidence_id>` (`EVIDENCE_STORE_KEY_PREFIX`). `EVIDENCE_STORE_TTL_SECONDS`
  defaults to 0 (no expiry — envelopes are an archival record); set a positive TTL
  only for non-archival deployments. `EVIDENCE_STORE_BACKEND=redis` by default; an
  s3/gcs backend can replace it without code changes. cycles-server serves these by
  id. Fetch one with `GET evidence:envelope:<id>`; size the store for retention
  (one envelope per reserve/commit/release/decide, kept for the retention horizon).

## Getting help

- Bug reports / feature requests:
  https://github.com/runcycles/cycles-server-events/issues
- Release notes: [`CHANGELOG.md`](CHANGELOG.md)
- Engineering history & rationale: [`AUDIT.md`](AUDIT.md)
- Sibling service runbooks:
  - https://github.com/runcycles/cycles-server/blob/main/OPERATIONS.md
  - https://github.com/runcycles/cycles-server-admin/blob/main/OPERATIONS.md
