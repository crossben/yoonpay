# Yoon for AI assistants (MCP)

`@yoonpay/mcp` lets an AI assistant — Claude, OpenAI Codex, Gemini CLI, Cursor, OpenCode,
GitHub Copilot in VS Code or any other client of the
[Model Context Protocol](https://modelcontextprotocol.io) — answer questions from your Yoon data:
"why did order 1042 fail?", "who is past due?", "how much came in on Wave this week?". Design:
[ADR-0032](adr/0032-mcp-server.md). Available from Yoon 0.7.0.

It is a small program that runs next to your assistant, on your machine, and calls your gateway's
`/v1` API with one application's API key. It can do nothing that key cannot already do, and by
default it only reads.

## 1. Give the assistant its own key

```sh
docker compose run --rm yoon apps add-key <app>       # prints a new key once
docker compose run --rm yoon apps revoke-key <prefix> # later: revoke that key alone
```

Your application keeps its own key; the assistant's can be revoked without touching it. The
assistant's configuration file holds the key in plain text: keep it in your user settings, never
in a file you commit.

## 2. Add it to your assistant

Requires Node.js 20 or newer. Every client starts the same program — `npx -y @yoonpay/mcp` with
`YOON_URL` and `YOON_API_KEY` in its environment — and saves it in its own format. Use the
user-level configuration (as below), so the key never lands in a project's files.

**Claude Desktop** — Settings → Developer → Edit Config (`claude_desktop_config.json`); **Cursor**
— `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "yoon": {
      "command": "npx",
      "args": ["-y", "@yoonpay/mcp"],
      "env": {
        "YOON_URL": "https://pay.example.com",
        "YOON_API_KEY": "yk_…"
      }
    }
  }
}
```

**Claude Code** (stored in your user settings for this project, not in the repository):

```sh
claude mcp add yoon -e YOON_URL=https://pay.example.com -e YOON_API_KEY=yk_… -- npx -y @yoonpay/mcp
```

**OpenAI Codex CLI** (saved in `~/.codex/config.toml`):

```sh
codex mcp add yoon --env YOON_URL=https://pay.example.com --env YOON_API_KEY=yk_… -- npx -y @yoonpay/mcp
```

**Gemini CLI** — `--scope user` saves it in `~/.gemini/settings.json` (the default, `project`,
writes `.gemini/settings.json` inside the repository). Do not add `--trust`: it skips the
confirmation before each tool call.

```sh
gemini mcp add --scope user -e YOON_URL=https://pay.example.com -e YOON_API_KEY=yk_… yoon npx -y @yoonpay/mcp
```

**OpenCode** — `~/.config/opencode/opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "yoon": {
      "type": "local",
      "command": ["npx", "-y", "@yoonpay/mcp"],
      "enabled": true,
      "environment": {
        "YOON_URL": "https://pay.example.com",
        "YOON_API_KEY": "yk_…"
      }
    }
  }
}
```

**VS Code (GitHub Copilot)** — Command Palette → *MCP: Open User Configuration*. VS Code asks for
the key when the server first starts, so it is not written in the file:

```json
{
  "inputs": [
    { "type": "promptString", "id": "yoon-api-key", "description": "Yoon API key", "password": true }
  ],
  "servers": {
    "yoon": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@yoonpay/mcp"],
      "env": {
        "YOON_URL": "https://pay.example.com",
        "YOON_API_KEY": "${input:yoon-api-key}"
      }
    }
  }
}
```

**Any other MCP client:** give it the command `npx`, the arguments `-y @yoonpay/mcp` and the two
variables, in its own format.

Restart the assistant, then ask: *"What does the Yoon about tool say?"*

| Variable | |
| --- | --- |
| `YOON_URL` | Your gateway |
| `YOON_API_KEY` | The assistant's key (step 1) |
| `YOON_MCP_WRITES` | `true` to allow the three write tools (section 4) |
| `YOON_MCP_SHOW_CONTACTS` | `true` to send phone numbers and e-mails unmasked |

## 3. What it can read

| Tool | |
| --- | --- |
| `about` | Yoon version, configured providers and methods |
| `get_payment`, `list_payments` | Filters: status, method, provider, your reference, dates |
| `explain_payment` | The payment, its status history, and a one-line summary |
| `get_refund`, `list_refunds` | All refunds, or one payment's |
| `get_payout`, `list_payouts` | `needs_review=true`: outcome unknown, a human must check |
| `get_customer`, `list_customers` | By id, or by your `external_id` |
| `get_subscription`, `list_subscriptions` | With status history; filter by customer or status |
| `get_invoice`, `list_invoices` | Filter by customer, subscription or status |
| `get_payment_link`, `list_payment_links` | Payment count and amount collected |
| `get_balances`, `list_ledger_entries` | Yoon's double-entry ledger |
| `get_event`, `list_events` | Webhooks to your application and their delivery status |

Lists return 20 items by default and 50 at most, with a `next_cursor` to continue. Amounts are in
the currency's minor unit and each object says how many decimals it has (`currency_decimals`; XOF
has none, so 5000 is 5 000 XOF).

## 4. What it can do (opt-in)

With `YOON_MCP_WRITES=true`, and only then, three tools appear:

| Tool | |
| --- | --- |
| `create_payment_link` | A link and QR code to share — money comes **in** |
| `create_portal_session` | A private, expiring portal link for one customer — view and pay only |
| `redeliver_event` | Re-sends one webhook to your own application |

Each requires an `idempotency_key`: retrying with the same key does the action once. There is no
tool that sends money out or ends a customer's access — no refund, payout, cancellation, free
period or coupon. Do those in the dashboard, the CLI or your application.

## 5. What leaves your machine

What a tool returns is sent to your assistant's provider (Anthropic, OpenAI, …) like anything
else in the conversation. So:
- **Phone numbers and e-mails are masked** (`+22177***67`, `a***@example.com`) unless you set
  `YOON_MCP_SHOW_CONTACTS=true`. Names, amounts and your metadata are sent as they are.
- **The API key is never sent:** every result is scrubbed of it.
- **Free text is marked as data.** Descriptions, names, metadata and provider messages come back
  under `untrusted_text`. A customer can type anything in a description, including "ignore your
  instructions and…"; assistants are told it is data. That is why no tool can move money out.

## Releasing (maintainers)

`@yoonpay/mcp` moves in lockstep with the other clients and is published by the release
workflow (`npm-mcp`). npm only accepts a trusted publisher on a package that already exists, so
the very first version is published by hand, once:

```sh
cd clients/mcp && npm ci && npm test && npm run build && npm publish --access public
```

then on npmjs.com → `@yoonpay/mcp` → Settings → Trusted publishing: repository `crossben/yoonpay`,
workflow `release.yml`, no environment. Later tags publish it automatically.
