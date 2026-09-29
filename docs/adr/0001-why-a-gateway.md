# ADR-0001 — Yoon is a self-hosted gateway, not a package per language

- Status: accepted
- Date: 2026-09-29

## Context
Applications in West Africa integrate PayDunya, DexPay, NabooPay… separately, in
every project and language, and each re-solves webhook verification,
idempotency, status mapping and stuck payments.

## Decision
Provider logic lives once, in a Java server. Applications use one HTTP API
through thin clients generated from one OpenAPI contract.

## Why
1. N languages × M providers is too much code to keep correct; a provider API
   change would need N patches.
2. Provider webhooks need a server to receive, verify and re-notify.
3. Reconciliation (stuck payments, stuck payouts) needs state and a scheduler.

## Consequences
Users run a service (Docker + Postgres). Yoon is not an aggregator: users keep
their own merchant accounts and keys. An embedded Spring starter is deferred
until after v1.
