# ADR-0006 — AGPL-3.0 server, Apache-2.0 clients, dual licensing

- Status: accepted
- Date: 2026-09-29

## Decision
- `yoon-core`, providers, `yoon-server`: **AGPL-3.0**.
- Clients (`clients/php`, `clients/java`): **Apache-2.0**, always — they are
  linked into users' applications, and a copyleft client would bind those apps.
- A **commercial licence** is offered to companies that cannot accept AGPL.
- Every external contributor signs a **CLA** (CLA Assistant, CI-enforced) so the
  owner can relicense and enforce.
- The name "Yoon" and logo are protected by trademark (`TRADEMARKS.md`).

## Consequences
The repository stays private until launch; the CLA text must be reviewed by a
lawyer before the first external PR. The enforcement policy is published in
`docs/licensing.md` before the repository goes public.
