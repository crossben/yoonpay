# ADR-0009 — Contract-first API, enforced by tests

- Status: accepted
- Date: 2026-09-29

## Decision
`api/openapi.yaml` is written by hand and is the source of truth for the public
API. It is served unchanged at `/openapi.yaml` and rendered by Swagger UI at
`/docs`. No specification is generated from code.

Two tests keep code and contract identical:
- `ContractCoverageTest`: the set of `/v1` routes in the controllers must equal
  the set of operations in the spec — an undocumented route fails the build.
- `ApiTest`: every response in every API test is validated against the spec
  (swagger-request-validator) — a field, status or type that drifts fails.

## Why
Clients in other languages are generated from this file; it must describe what
the server does, not what annotations happened to say. Code-generated specs
tend to leak internals and change silently.

## Consequences
Adding or changing an endpoint means editing `api/openapi.yaml` in the same
change. Swagger UI is served from the `org.webjars:swagger-ui` jar; springdoc
is not used. `YOON_SWAGGER_UI_ENABLED` is reserved for turning `/docs` off later.
