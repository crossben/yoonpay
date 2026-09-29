# ADR-0005 — No Lombok

- Status: accepted
- Date: 2026-09-29

## Decision
No Lombok. Java records for DTOs, value objects and events; plain classes
elsewhere.

## Why
- Records cover most of what Lombok would generate.
- Lombok hooks into compiler internals and regularly breaks on new JDKs.
- In a payments codebase reviewers must see exactly what `equals` and
  `toString` do — a generated `toString` can leak a secret into a log.
- `yoon-core` must depend on nothing.
