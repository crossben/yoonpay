# ADR-0004 — Spring Data JDBC, not JPA

- Status: accepted
- Date: 2026-09-29

## Decision
Spring Data JDBC for aggregates, `JdbcClient` for locking queries
(`FOR UPDATE`, `SKIP LOCKED`) and reports. Schema owned by Flyway.

## Why
JPA's lazy loading, dirty checking and deferred flushes make it unclear when a
status change reaches the database. Yoon depends on explicit writes: row locks
when settling, append-only `payment_events`, and outbox rows written in the same
transaction as the state change.
