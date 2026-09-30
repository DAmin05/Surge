# 0003 — Ed25519 tokens, keys generated once, `kid` keysets for rotation

**Status:** Accepted

## Context

The gateway checks every request. A network call per request to validate a token would
make Admission a bottleneck and a single point of failure, so tokens must be verifiable
locally. Two token types exist: the anonymous **session** (who you are) and the
**admission** token (you got through the waiting room for event X).

Generating keys at container start looks convenient but breaks things: a restart
invalidates every live token, and two services starting separately can end up with
different keys.

## Decision

- Tokens are JWTs signed with **Ed25519**: small signatures, fast verification, and
  only Admission holds a private key. The gateway gets public keys only.
- **Separate key pairs per token type.** Every token carries `typ` and a `kid`; the
  gateway rejects a token whose `typ` doesn't match the route, so a session token can't
  be replayed as an admission token.
- **Keys are generated once** (`make keys` / the `keygen` one-shot container) into the
  gitignored `secrets/` volume, and never overwritten if present.
- The gateway loads a **keyset: current + previous** per type. `make rotate-keys` adds a
  new key and demotes the current one, so tokens signed just before a rotation stay
  valid until they expire (admission tokens live 10 minutes).
- Tokens are multi-use until `exp`. Revocation is a `jti` denylist delivered to every
  gateway over the `token-revocations` topic and held in memory until `exp`.

## Consequences

- No per-request dependency on Admission; gateways scale horizontally.
- Rotation is zero-downtime as long as rotations are at least one token lifetime apart.
- Single-use tokens are not supported. That's fine: the token only controls entry to the
  sale, and the per-user seat limit plus the Postgres claim protect the real guarantees.
