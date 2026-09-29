# ADR-0007 — No telemetry by default; opt-in usage ping only

- Status: accepted
- Date: 2026-09-29

## Decision
Yoon sends nothing anywhere by default. An opt-in `YOON_USAGE_PING=true` sends,
once a day: a random instance id, the version, enabled provider names and a
coarse payment-count bucket. Never keys, amounts, phone numbers or hostnames.
The payload is documented and logged when sent.

## Why
A hidden phone-home destroys trust in a payment gateway, may breach
data-protection law, and would not catch licence violators — AGPL lets them
remove it. Adoption is measured through consenting channels: the opt-in ping,
registry download counts, `ADOPTERS.md` and commercial-licence enquiries.
