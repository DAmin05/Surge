# Working on Surge

- `docs/design.md` is the agreed spec. Code must match it; change it (and add an ADR in
  `docs/adr/` for real decisions) in the same PR as any divergence.
- One PR per roadmap week into `main`; `main` stays green. PR description: exit
  criterion, how CI checks it, ADRs added.
- JVM versions live only in `gradle/libs.versions.toml`. `libs/contracts` holds generated
  stubs and event DTOs only; no shared business logic between services.
- Checks: `make test` (Java, Rust, Python), `make up` (stack + health check).
