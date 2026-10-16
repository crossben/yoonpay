# @yoonpay/mcp — Yoon for AI assistants

An [MCP](https://modelcontextprotocol.io) server that lets an AI assistant (Claude Desktop,
Claude Code, Cursor…) read one Yoon application's data through the `/v1` API. Design:
[ADR-0032](../../docs/adr/0032-mcp-server.md). From Yoon 0.7.0. **Setup for Claude Desktop, Claude
Code and Cursor: [docs/mcp.md](../../docs/mcp.md).**

## Tools (all read-only)
| Tool | |
| --- | --- |
| `about` | Yoon version, configured providers and methods |
| `get_payment`, `list_payments` | Filters: status, method, provider, your reference, dates |
| `explain_payment` | The payment, its status history, and a one-line summary built from Yoon's own fields |
| `get_refund`, `list_refunds` | All refunds, or one payment's |
| `get_payout`, `list_payouts` | `needs_review=true`: outcome unknown, a human must check |
| `get_customer`, `list_customers` | By id, or by your `external_id` |
| `get_subscription`, `list_subscriptions` | With status history; filter by customer or status |
| `get_invoice`, `list_invoices` | Filter by customer, subscription or status |
| `get_payment_link`, `list_payment_links` | Payment count and amount collected |
| `get_balances`, `list_ledger_entries` | Yoon's double-entry ledger |
| `get_event`, `list_events` | Webhooks to your application and their delivery status |

Lists return 20 items by default, 50 at most, and a `next_cursor` to continue.

What the assistant receives:
- **Contacts masked:** `+22177***67`, `a***@example.com` (unless `YOON_MCP_SHOW_CONTACTS=true`).
- **Free text marked as data:** descriptions, names, metadata and provider messages come back under
  `untrusted_text`, so text a customer typed is not taken for instructions.
- **Amounts in minor units** with `currency_decimals` (XOF: 0, so 5000 is 5 000 XOF).

## Write tools (only with `YOON_MCP_WRITES=true`)
| Tool | |
| --- | --- |
| `create_payment_link` | A link and QR code to share; money comes **in** |
| `create_portal_session` | A private, expiring portal link for one customer — view and pay only |
| `redeliver_event` | Re-sends one webhook to your own application |

Each takes a required `idempotency_key`: retrying with the same key does the action once. There is
no tool that sends money out or ends a customer's access — no refund, payout, cancellation, comp or
coupon — so a manipulated assistant can at worst create a link or re-send a webhook.

## Settings (environment)
| Variable | |
| --- | --- |
| `YOON_URL` | Your gateway, e.g. `https://pay.example.com` |
| `YOON_API_KEY` | A key just for the assistant: `apps add-key <app>`; revoke it alone with `apps revoke-key <prefix>` |
| `YOON_MCP_WRITES` | `true` to list the three write tools below (never refunds or payouts) |
| `YOON_MCP_SHOW_CONTACTS` | `true` to return phone numbers and e-mails unmasked |

The API key never appears in a tool result: anything sent back to the assistant is scrubbed.

## Develop
```sh
npm install && npm test && npm run build
YOON_URL=http://localhost:8080 YOON_API_KEY=yk_… node dist/cli.js   # speaks MCP on stdin/stdout
```
