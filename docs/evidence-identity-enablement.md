# CyclesEvidence — identity enablement (runbook)

> How to turn CyclesEvidence **on** in an environment. Until the identity is
> configured, no usable signed evidence is produced: `cycles-server` fail-opens
> (records still queue, but responses carry no `cycles_evidence` ref), and the
> `cycles-server-events` signer stays disabled when `EVIDENCE_SERVER_ID` is
> blank. If `server_id` is set but the signing key is unset, it signs with a
> **throwaway** key.
> See [Startup behavior](#startup-behavior-so-you-can-read-the-logs) for the exact
> per-variable modes. Nothing else breaks.

## What "on" means

CyclesEvidence spans **two** services that must agree on one public identity:

| Service | Role | Needs |
|---|---|---|
| **cycles-server** | producer — emits source records, computes `evidence_id` **synchronously**, returns `cycles_evidence` on responses | `EVIDENCE_SERVER_ID`, `EVIDENCE_SIGNING_SIGNER_DID` (both **public**) |
| **cycles-server-events** | signer — JCS-canonicalizes, Ed25519-**signs**, and **stores** the envelope in the shared store (`cycles-server` serves it via `GET /v1/evidence/{id}` — this worker does not serve) | `EVIDENCE_SERVER_ID`, `EVIDENCE_SIGNING_SIGNER_DID`, **`EVIDENCE_SIGNING_PRIVATE_KEY_HEX`** (private) |

The private key lives **only** on the events worker. `cycles-server` never signs — it
only reproduces the `evidence_id` content hash, which needs the public identity alone.

## The three variables

| Env var | Spring property | cycles-server | events worker | secrecy |
|---|---|---|---|---|
| `EVIDENCE_SERVER_ID` | `cycles.evidence.server-id` | ✅ | ✅ | public |
| `EVIDENCE_SIGNING_SIGNER_DID` | `cycles.evidence.signing.signer-did` | ✅ | ✅ | public (Ed25519 pubkey, 64 hex) |
| `EVIDENCE_SIGNING_PRIVATE_KEY_HEX` | `cycles.evidence.signing.private-key-hex` | ❌ never | ✅ | **secret** (Ed25519 seed, 64 hex) |

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
`signer_did`** path; the `did:cycles`/JWKS form is the v0.2 layer tracked in
[cycles-protocol#103](https://github.com/runcycles/cycles-protocol/issues/103).

## Startup behavior (so you can read the logs)

**cycles-server** (`EvidenceEmitter`): if `server-id` **and** `signer-did` are both set →
computes `evidence_id` and returns `cycles_evidence`. If either is blank → records are
still queued, but **no** `evidence_id` / `cycles_evidence` (fail-open, silent).

**cycles-server-events** — `server_id` (`EvidenceWorker`) is checked **independently** of the signing key:
- `EVIDENCE_SERVER_ID` **blank** → the evidence signer is disabled. It does **not** claim records from `evidence:pending`, does **not** sign with an ephemeral key, and does **not** dead-letter records. Set `EVIDENCE_SERVER_ID` to enable signing.

**cycles-server-events** signing key (`LocalEvidenceSigningKey`, evaluated once `server_id` is set):
- both private-key + signer-did set → loads them, validates the pair, logs
  `evidence signing key loaded from configuration (signer_did=…)`.
- **neither** set → generates an **EPHEMERAL** key and logs a `WARN`:
  *"emitted evidence will NOT verify across restarts"*. Fine for dev; **never** production.
- **exactly one** set → **throws** (`evidence signing is half-configured …`). Fail fast.

## Provisioning steps

1. **Generate one Ed25519 keypair** (raw 32-byte seed + 32-byte public key, hex).
   Example (Node, tweetnacl):
   ```js
   const nacl = require('tweetnacl');
   const kp = nacl.sign.keyPair();
   const hex = b => Buffer.from(b).toString('hex');
   console.log('private (seed) =', hex(kp.secretKey.slice(0, 32)));  // EVIDENCE_SIGNING_PRIVATE_KEY_HEX
   console.log('public  (did)  =', hex(kp.publicKey));               // EVIDENCE_SIGNING_SIGNER_DID
   ```
   (Or any Ed25519 tool — the seed is the first 32 bytes; the DID is the 32-byte public key.)
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
3. Confirm `evidence:failed` (dead-letter list) is **not** growing — a growing dead-letter
   list with an id-mismatch reason means `EVIDENCE_SERVER_ID`/`SIGNER_DID` differ between the
   two services (coherence rule 1/2).

## Rotation (today, pre-#103)

Rotating the signing key today invalidates verification of envelopes signed by the old key
(there is no published key history yet — that is exactly what cycles-protocol#103 / v0.2
adds). Until #103 lands, treat a rotation as: pinned/consumer-captured old keys still verify
their old envelopes; new envelopes use the new key. Coordinate rotations with evidence consumers.

---
_See also: `OPERATIONS.md` → Configuration tuning (the `EVIDENCE_*` env vars).
The signer-key **rotation/history** model is specified by cycles-protocol#103 (v0.2)._
