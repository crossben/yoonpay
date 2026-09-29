# ADR-0013 — Operator API behind a single admin token

- Status: accepted
- Date: 2026-09-29

## Decision
`/admin/v1/**` (dead letters, payouts needing review) is protected by one
bearer token, `YOON_ADMIN_TOKEN` (at least 32 characters), compared in constant
time. Without it the admin API returns 404. There is no admin UI in v1.

## Why
A self-hosted instance has one operator. A single secret in the environment is
simple to deploy and rotate, and adds no user store. Spring Security can
replace it if multi-user administration is ever needed.

## Consequences
Rotating the token is a restart with a new value. Admin actions are recorded
in each resource's status history with cause `admin` and the operator's note.
