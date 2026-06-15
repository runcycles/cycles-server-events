# Operator tools

Standalone helpers for running this service. They are **not** part of the Maven
build or the service runtime — plain source files you run directly.

## `EvidenceKeygen.java` — CyclesEvidence identity keygen (v0.1)

Generates an Ed25519 signing keypair and prints the three environment variables
that turn CyclesEvidence **on**, in the exact formats the reference signer
validates. Use this when enabling evidence on a self-hosted deployment.

There is no hosted Cycles and no central signing key: **you** own the identity.
This helper needs nothing but your own `server_id`.

### Run

Needs a JDK 17+ (you already have one — you run this service on it). No build,
no dependencies:

```sh
java tools/EvidenceKeygen.java https://cycles.example.com/v1
```

The argument is **your deployment's canonical base URL, including `/v1`** — the
value you will set as `EVIDENCE_SERVER_ID`. It must be byte-identical on both
services. (Omit it and the tool still generates a key but emits a placeholder
for `EVIDENCE_SERVER_ID`.)

### Output

```
# ----------------------------------------------------------------------
# CyclesEvidence identity (v0.1, raw-hex signer_did). Generated locally.
# ...
EVIDENCE_SERVER_ID=https://cycles.example.com/v1
EVIDENCE_SIGNING_SIGNER_DID=653aff9f…0176d74
# v vv SECRET — events worker only, capture into your secret manager v vv
EVIDENCE_SIGNING_PRIVATE_KEY_HEX=…
```

| Variable | Where it goes | Secrecy |
|---|---|---|
| `EVIDENCE_SERVER_ID` | **both** cycles-server + cycles-server-events | public |
| `EVIDENCE_SIGNING_SIGNER_DID` | **both** services | public (Ed25519 pubkey, 64 hex) |
| `EVIDENCE_SIGNING_PRIVATE_KEY_HEX` | **cycles-server-events only** | **secret** (Ed25519 seed, 64 hex) |

Then follow [`docs/evidence-identity-enablement.md`](../docs/evidence-identity-enablement.md)
for placement, the coherence rules, and how to read the startup logs.

### Why it matches the server

Both values are the **raw 32-byte tail of the DER encoding**, lowercase-hex —
the same extraction `LocalEvidenceSigningKey` uses, and the same 32-byte
seed / pubkey that `EnvelopeSigner` re-wraps with the fixed Ed25519 DER
prefixes. Before printing, the tool **reconstructs both keys from the emitted
hex through those exact prefixes and runs the sign/verify pair probe** the
worker enforces at startup — validating the literal bytes it hands you,
independent of which provider generated them, so it cannot emit a pair the
service would reject.

### `SECURITY`

The last line is your signing **secret**. Run the tool on a trusted host,
capture the output straight into your secret manager, and never commit it or
paste it into chat, logs, or tickets. Re-running the tool makes a **new** key
(a new `signer_did`); rotating the key changes the identity, so coordinate it
across both services.

### Language-agnostic alternative (OpenSSL)

If you would rather not invoke a JDK, the same two values can come from OpenSSL
(the raw key is the last 32 bytes of each DER encoding):

```sh
openssl genpkey -algorithm ed25519 -out evidence-signing.pem      # keep this file SECRET
# EVIDENCE_SIGNING_PRIVATE_KEY_HEX (32-byte seed):
openssl pkey -in evidence-signing.pem        -outform DER | tail -c 32 | xxd -p -c 32
# EVIDENCE_SIGNING_SIGNER_DID (32-byte public key):
openssl pkey -in evidence-signing.pem -pubout -outform DER | tail -c 32 | xxd -p -c 32
```

`EvidenceKeygen.java` is preferred because it self-validates the pair; the
OpenSSL path does not.
