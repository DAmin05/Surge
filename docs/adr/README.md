# Architecture decision records

Short records of decisions that shaped Surge, so the reasoning survives the code.
One file per decision, numbered, never rewritten: a later ADR supersedes an earlier one.

| # | Decision | Status |
|---|---|---|
| [0001](0001-postgres-is-the-source-of-truth.md) | Postgres, not Redis, is the source of truth for seat ownership | Accepted |
| [0002](0002-schema-per-service.md) | One Postgres, one schema and role set per service | Accepted |
| [0003](0003-token-signing-keys.md) | Ed25519 tokens, keys generated once, `kid` keysets for rotation | Accepted |

Template: **Context** (what forces the decision) → **Decision** → **Consequences**
(what gets easier, what gets harder, what we'll watch).
