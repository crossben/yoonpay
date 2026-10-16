# @yoonpay/mcp — Yoon for AI assistants (in progress)

An [MCP](https://modelcontextprotocol.io) server that lets an AI assistant (Claude Desktop,
Claude Code, Cursor…) read one Yoon application's data through the `/v1` API. Design:
[ADR-0032](../../docs/adr/0032-mcp-server.md). **Not published yet.**

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

The opt-in write tools come in the next milestone.

## Settings (environment)
| Variable | |
| --- | --- |
| `YOON_URL` | Your gateway, e.g. `https://pay.example.com` |
| `YOON_API_KEY` | A key just for the assistant: `apps add-key <app>`; revoke it alone with `apps revoke-key <prefix>` |
| `YOON_MCP_WRITES` | `true` to list the write tools (later milestone; never refunds or payouts) |
| `YOON_MCP_SHOW_CONTACTS` | `true` to return phone numbers and e-mails unmasked |

The API key never appears in a tool result: anything sent back to the assistant is scrubbed.

## Develop
```sh
npm install && npm test && npm run build
YOON_URL=http://localhost:8080 YOON_API_KEY=yk_… node dist/cli.js   # speaks MCP on stdin/stdout
```
