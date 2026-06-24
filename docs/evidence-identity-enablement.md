# CyclesEvidence — identity enablement (runbook)

> How to turn CyclesEvidence **on** in an environment. Until the identity is
> configured, no usable signed evidence is produced: `cycles-server` fail-opens
> without queueing source records or returning a `cycles_evidence` ref, and the
> `cycles-server-events` signer stays disabled when `EVIDENCE_SERVER_ID` is
> blank. If `server_id` is set but the signing key is unset, it signs with a
> **throwaway** development key.
> See [Startup behavior](#startup-behavior-so-you-can-read-the-logs) for the exact
> per-variable modes. Nothing else breaks.

## What "on" means

CyclesEvidence spans **two** services that must agree on one public identity:

| Service | Role | Needs |
|---|---|---|
| **cycles-server** | producer + public serving tier — emits source records, computes `evidence_id` **synchronously**, returns `cycles_evidence` on responses, serves envelopes/JWKS | `EVIDENCE_SERVER_ID`, `EVIDENCE_SIGNING_SIGNER_DID` (both **public**) |
| **cycles-server-events** | signer — JCS-canonicalizes, Ed25519-**signs**, and **stores** the envelope in the shared store (`cycles-server` serves it via `GET /v1/evidence/{id}` — this worker does not serve) | `EVIDENCE_SERVER_ID`, `EVIDENCE_SIGNING_SIGNER_DID`, **`EVIDENCE_SIGNING_PRIVATE_KEY_HEX`** (private) |

The private key lives **only** on the events worker. `cycles-server` never signs — it
only reproduces the `evidence_id` content hash, which needs the public identity alone.

## The three variables

| Env var | Spring property | cycles-server | events worker | secrecy |
|---|---|---|---|---|
| `EVIDENCE_SERVER_ID` | `cycles.evidence.server-id` | ✅ | ✅ | public |
| `EVIDENCE_SIGNING_SIGNER_DID` | `cycles.evidence.signing.signer-did` | ✅ | ✅ | public (Ed25519 pubkey, 64 hex) |
| `EVIDENCE_SIGNING_PRIVATE_KEY_HEX` | `cycles.evidence.signing.private-key-hex` | ❌ never | ✅ | **secret** (Ed25519 seed, 64 hex) |

These are the required signing variables. JWKS publication and rotation history add
public `cycles-server` variables later in this runbook:
`EVIDENCE_SIGNING_KID`, `EVIDENCE_SIGNING_NBF_MS`, and
`EVIDENCE_SIGNING_RETIRED_KEYS`.

`EVIDENCE_SIGNING_KID` is **not** a key and is not used for signing. It is the
public JWK `kid` label that `cycles-server` publishes in
`GET /v1/.well-known/cycles-jwks.json`, so verifiers can identify a key in the
JWKS, especially across rotations. If omitted, `cycles-server` derives a stable
default from the first 16 hex chars of `EVIDENCE_SIGNING_SIGNER_DID`.
`cycles-server-events` does not read `EVIDENCE_SIGNING_KID` today; setting it
there is harmless but redundant.

### Coherence rules (these are load-bearing)

1. **`EVIDENCE_SERVER_ID` must be byte-identical on both services.** It is stamped
   into the envelope AND is the base of `cycles_evidence_url`. If the two services
   disagree, `cycles-server`'s precomputed `evidence_id` won't match what the worker
   builds → the worker's id cross-check fails → the record **dead-letters** (`evidence:failed`).
2. **`EVIDENCE_SIGNING_SIGNER_DID` must be byte-identical on both** — same reason
   (`signer_did` is part of the hashed envelope).
3. **On the worker, `EVIDENCE_SIGNING_SIGNER_DID` must be the public half of
   `EVIDENCE_SIGNING_PRIVATE_KEY_HEX`.** The worker validates this at startup and
   **fails fast** (`IllegalStateException: … do not form a valid Ed25519 pair`).
4. **`EVIDENCE_SERVER_ID` is the canonical deployment base *including* `/v1`** —
   e.g. `https://cycles.example.com/v1`. `cycles_evidence_url` = `{server_id}/evidence/{id}`,
   and `GET /v1/evidence/{id}` is served from that base; do **not** double-add `/v1`.

## Generating the identity

You own the key — there is no hosted Cycles and no central signer. Generate an
Ed25519 keypair and the three env vars with the bundled helper
([`tools/EvidenceKeygen.java`](../tools/EvidenceKeygen.java)):

```sh
java tools/EvidenceKeygen.java https://cycles.example.com/v1
```

It prints `EVIDENCE_SERVER_ID`, `EVIDENCE_SIGNING_SIGNER_DID`, and the secret
`EVIDENCE_SIGNING_PRIVATE_KEY_HEX` in the exact 64-hex formats validated above.
Before printing, it reconstructs the keys from the emitted hex (the same DER
reconstruction `EnvelopeSigner` uses) and runs the sign/verify pair probe
coherence rule 3 enforces — so the values it hands you can't fail the worker's
startup check. Capture the secret straight into your secret
manager; never commit or paste it. (Prefer OpenSSL? See
[`tools/README.md`](../tools/README.md).) This is the **v0.1 raw-hex
`signer_did`** path emitted by this worker. The v0.2 signer-key resolution
layer is now normative and `cycles-server` can publish a JWKS for raw-hex
active/retired keys; this worker still stamps raw-hex `signer_did` values.

## Startup behavior (so you can read the logs)

**cycles-server** (`EvidenceEmitter`): if `server-id` **and** `signer-did` are both set →
computes `evidence_id` and returns `cycles_evidence`. If either is blank → records are
not queued and there is **no** `evidence_id` / `cycles_evidence` (fail-open, silent).

**cycles-server-events** — `server_id` (`EvidenceWorker`) is checked **independently** of the signing key:
- `EVIDENCE_SERVER_ID` **blank** → the evidence signer is disabled. It does **not** claim records from `evidence:pending`, does **not** sign with an ephemeral key, and does **not** dead-letter records. Set `EVIDENCE_SERVER_ID` to enable signing.
- Records already present in `evidence:pending` before disabling remain there until evidence is re-enabled or an operator drains them manually; the worker does not treat disablement as permission to delete audit source records.

**cycles-server-events** signing key (`LocalEvidenceSigningKey`, evaluated once `server_id` is set):
- both private-key + signer-did set → loads them, validates the pair, logs
  `evidence signing key loaded from configuration (signer_did=…)`.
- **neither** set → generates an **EPHEMERAL** key and logs a `WARN`:
  *"emitted evidence will NOT verify across restarts"*. Fine for dev; **never** production.
- **exactly one** set → **throws** (`evidence signing is half-configured …`). Fail fast.

## Provisioning steps

1. **Generate one Ed25519 keypair** (raw 32-byte seed + 32-byte public key, hex).
   Prefer the bundled helper above. If you use another library, be explicit about
   its key encoding. Example (Node, tweetnacl, where `secretKey` is
   `seed || publicKey`):
   ```js
   const nacl = require('tweetnacl');
   const kp = nacl.sign.keyPair();
   const hex = b => Buffer.from(b).toString('hex');
   console.log('private (seed) =', hex(kp.secretKey.slice(0, 32)));  // EVIDENCE_SIGNING_PRIVATE_KEY_HEX
   console.log('public  (did)  =', hex(kp.publicKey));               // EVIDENCE_SIGNING_SIGNER_DID
   ```
   Do not assume "first 32 bytes" for every tool or file format: PEM/DER keys
   are encoded containers. For OpenSSL, use the raw DER-tail extraction in
   [`tools/README.md`](../tools/README.md).
2. **Pick `EVIDENCE_SERVER_ID`** = the deployment's canonical base URL incl. `/v1`.
3. **Set on `cycles-server-events`** (the only place the private key goes — into its secret store):
   `EVIDENCE_SERVER_ID`, `EVIDENCE_SIGNING_SIGNER_DID`, `EVIDENCE_SIGNING_PRIVATE_KEY_HEX`.
4. **Set on `cycles-server`** (public only): `EVIDENCE_SERVER_ID`, `EVIDENCE_SIGNING_SIGNER_DID`
   — **identical** values to the worker's. Do **not** set the private key here.
5. **Restart both.** Confirm the worker logs `evidence signing key loaded from configuration`
   (not the EPHEMERAL warning).

## Verify end-to-end

1. Make a reservation; the response should carry `cycles_evidence { evidence_id, cycles_evidence_url }`.
2. `GET` the `cycles_evidence_url` (it is `{server_id}/evidence/{evidence_id}`) — expect the
   signed envelope (`200`, `application/json`). A transient `404` immediately after is normal
   (async signing); retry.
3. Confirm `evidence:failed` (dead-letter list) is **not** growing. A growing
   list plus worker logs containing `evidence_id cross-check failed` means
   `EVIDENCE_SERVER_ID`/`SIGNER_DID` differ between the two services (coherence
   rule 1/2). The dead-letter entry itself is the source record; the reason is
   in the worker log.

## JWKS and rotation

`cycles-server-events` still signs envelopes with the raw-hex `signer_did`
configured above. `cycles-server` can publish the v0.2 public key set at
`{server_id}/.well-known/cycles-jwks.json` (for example,
`https://cycles.example.com/v1/.well-known/cycles-jwks.json`) so consumers can
establish signer authority and retain rotation history.

For a first-time key, optional public `cycles-server` settings are:

- `EVIDENCE_SIGNING_KID` — stable JWK key id. If omitted, `cycles-server`
  derives one from the first 16 hex chars of `EVIDENCE_SIGNING_SIGNER_DID`.
- `EVIDENCE_SIGNING_NBF_MS` — epoch-ms validity start for the active key.
  Default `0` is appropriate only for a never-rotated deployment.

For rotation:

1. Generate a new keypair.
2. Put the new `EVIDENCE_SIGNING_PRIVATE_KEY_HEX` and
   `EVIDENCE_SIGNING_SIGNER_DID` on `cycles-server-events`; put the same public
   `EVIDENCE_SIGNING_SIGNER_DID` on `cycles-server`.
3. Set `EVIDENCE_SIGNING_NBF_MS` on `cycles-server` to the rotation epoch ms.
4. Append the old public key to `EVIDENCE_SIGNING_RETIRED_KEYS` on
   `cycles-server`, as a JSON array entry:
   ```json
   [
     {
       "signer_did": "<old-public-64-hex>",
       "kid": "<old-kid>",
       "nbf_ms": 1810000000000,
       "exp_ms": 1812345678000
     }
   ]
   ```
   Replace the millisecond values with the old key's validity start and the
   rotation time. `exp_ms` is exclusive and should match the new active key's
   `nbf_ms`.
5. Restart both services and verify the JWKS endpoint contains the new active
   key plus the retired key entry.

If retired-key history is not published, old envelopes can still have their
signature bytes checked against the embedded raw `signer_did`, but signer
authority depends on consumers having pinned or captured the old public key.
Coordinate rotations with evidence consumers.

---
_See also: `OPERATIONS.md` → Configuration tuning (the `EVIDENCE_*` env vars).
The signer-key **rotation/history** model is specified by
`cycles-evidence-v0.2.yaml`._
