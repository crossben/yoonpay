# @yoonpay/mcp — Yoon for AI assistants (in progress)

An [MCP](https://modelcontextprotocol.io) server that lets an AI assistant (Claude Desktop,
Claude Code, Cursor…) read one Yoon application's data through the `/v1` API. Design:
[ADR-0032](../../docs/adr/0032-mcp-server.md). **Not published yet.**

Today it has one tool, `about`. The read tools (payments, subscriptions, invoices…) and the few
opt-in write tools come in the next milestones.

## Settings (environment)
| Variable | |
| --- | --- |
| `YOON_URL` | Your gateway, e.g. `https://pay.example.com` |
| `YOON_API_KEY` | A key just for the assistant: `apps add-key <app>`; revoke it alone with `apps revoke-key <prefix>` |
| `YOON_MCP_WRITES` | `true` to list the write tools (later milestone; never refunds or payouts) |
| `YOON_MCP_SHOW_CONTACTS` | `true` to return phone numbers and e-mails unmasked (later milestone) |

The API key never appears in a tool result: anything sent back to the assistant is scrubbed.

## Develop
```sh
npm install && npm test && npm run build
YOON_URL=http://localhost:8080 YOON_API_KEY=yk_… node dist/cli.js   # speaks MCP on stdin/stdout
```
