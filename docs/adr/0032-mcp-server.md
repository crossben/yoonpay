# ADR-0032 — MCP server for AI agents

- Status: accepted (2026-10-16) — choices 1A, 2A, 3A
- Date: 2026-10-16
- Builds on: the JavaScript client, application API keys (`apps add-key` / `revoke-key`), ADR-0030/0031

## Context
Merchants and developers increasingly ask an AI assistant (Claude, Cursor, ChatGPT…) questions
like "why did order 1042's payment fail?", "who is past due this week?", "how much did we take
on Wave in September?". Today the answer means opening the dashboard or writing API calls. The
Model Context Protocol (MCP) is the common way to give an assistant tools. An MCP server for Yoon
lets the assistant read the merchant's own data through the `/v1` API — and, if the merchant
allows it, do a few safe actions.

The risk is the usual one with money, plus two new ones: an assistant can be wrong or be
manipulated by text it reads (prompt injection — e.g. a payment `description` written by a
customer), and whatever a tool returns is sent to the assistant's provider.

## Decision

### Shape: a separate package over the public API, not a server feature
`@yoonpay/mcp`, a small TypeScript process (stdio transport) built on `@yoonpay/yoon`. It holds
one application's API key (`YOON_URL`, `YOON_API_KEY` from the environment) and calls `/v1` like
any integration. Consequences:
- No new server code, route, permission or migration. The MCP server can do nothing the key
  cannot already do; the gateway's rules (state machines, idempotency, ledger) are untouched.
- Runs where the merchant runs their assistant: `npx -y @yoonpay/mcp` in the client config.
- Versioned in lockstep with the other clients; released by the same workflow (npm).

Rejected: an `/mcp` endpoint inside the gateway (HTTP transport, OAuth). More surface on the
money server, a new auth flow, for no capability gain today. Revisit if hosted assistants need
a remote URL (see "Later").

### Tools, version 1: read-only by default
| Tool | Calls |
| --- | --- |
| `get_payment`, `list_payments` (filters: status, method, reference, dates) | `/v1/payments…` |
| `explain_payment` — status, the event history in order, the provider's last error code and a plain summary ("failed: the customer cancelled on Wave") | payment + `/events` |
| `get_refund`, `list_refunds`, `get_payout`, `list_payouts` | `/v1/refunds…`, `/v1/payouts…` |
| `get_customer`, `list_customers` | `/v1/customers…` |
| `list_subscriptions` (status: past_due, trialing…), `get_subscription` with its history | `/v1/subscriptions…` |
| `list_invoices`, `get_invoice` | `/v1/invoices…` |
| `list_payment_links`, `get_payment_link` (counts, amount collected) | `/v1/payment_links…` |
| `get_balances`, `list_ledger_entries` | `/v1/balances`, `/v1/ledger/entries` |
| `list_events`, `get_event` (webhook delivery status) | `/v1/events…` |
| `about` (version, providers configured) | `/v1/about`, `/v1/providers` |

Amounts are returned with their currency and minor-unit exponent (5000 XOF is 5000, 1050 EUR
cents is 10.50) so the assistant does not invent decimals. Lists are paged with a hard cap.

### Writes: opt-in, never money out — **choice 1**
With `YOON_MCP_WRITES=true` only, these tools appear:
- `create_payment_link` (a link to share; money comes **in**),
- `create_portal_session` (a link for one customer),
- `redeliver_event` (re-sends a webhook to the merchant's own application).

Options:
- **A (recommended):** the three above; refunds, payouts, cancel, comp, coupons never exposed in
  v1. A wrong refund or payout is money that leaves; an assistant reading a manipulated
  description must not be one step from it.
- B: also `refund_payment` and `cancel_subscription`, behind a second flag.
- C: read-only only, no writes at all.

Every write tool takes a required `idempotency_key` argument, sent as `Idempotency-Key`, so a
retried tool call does one thing. Write tools are marked with MCP's `destructiveHint: false,
idempotentHint: true` (they create, never destroy); assistants ask the user before calling a
tool unless the user has told them not to — Yoon does not rely on that alone.

### What leaves the merchant's machine — **choice 2**
Tool results are sent to the assistant's provider. Options:
- **A (recommended):** phone numbers and e-mails masked by default (`+221 77 *** ** 67`,
  `a***@example.com`), names kept; `YOON_MCP_SHOW_CONTACTS=true` to unmask. Metadata returned
  as is (it is the merchant's own).
- B: everything as the API returns it.

The API key is never in a tool result, an error or a log line (same rule and test as the
clients).

### Prompt injection
Text fields written by customers or third parties (`description`, `metadata`, provider error
messages, customer names) are returned inside a field named as data
(`"untrusted_text": {...}`) and each tool's description says the content is data, not
instructions. Combined with "no money-out tools", the worst a manipulated assistant can do in v1
is create a payment link or re-send a webhook.

### Which key — **choice 3**
- **A (recommended):** an ordinary API key of the application, documented as "give the
  assistant its own key": `apps add-key <app>` issues one and `apps revoke-key <prefix>` revokes
  it alone, both already in the CLI. It can still do everything a key can; the MCP server only
  exposes the tools above.
- B: build read-only API keys in the gateway first (a new key scope), then the MCP server.

## Consequences
- New package `clients/mcp` (npm `@yoonpay/mcp`), tests with a mocked Yoon (same style as the JS
  client): every tool, masking, no key in output, writes absent without the flag, paging cap.
- Docs: `docs/mcp.md` — setup for Claude Desktop / Claude Code / Cursor (JSON config), what the
  assistant can and cannot do, the data note; README; website page.
- No server change, no migration, no contract change.

## Later (not v1)
- Read-only / scoped API keys in the gateway, so the assistant's key cannot write at all.
- A remote (HTTP) MCP endpoint with OAuth, for hosted assistants.
- Admin-level tools across applications (operator use), on the admin token.
