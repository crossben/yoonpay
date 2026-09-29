# ADR-0002 — Money is a `long` in minor units plus an ISO currency code

- Status: accepted
- Date: 2026-09-29

## Decision
A small `Money` record in `yoon-core`: `long amount` (minor units) + ISO 4217
currency code. JSON carries integers (`"amount": 5000`). No floating point
anywhere. No Moneta (JSR 354).

## Why
XOF has no minor unit, so 5 000 XOF is `5000`. A `long` is exact, fast, maps to
`BIGINT`, and keeps `yoon-core` dependency-free. Moneta adds a dependency and
rounding contexts Yoon does not need.

## Consequences
Currencies with minor units (later) use their ISO exponent; conversion and
formatting are the client's concern.
