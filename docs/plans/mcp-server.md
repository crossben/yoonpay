# Plan — MCP server for AI agents

Design: [ADR-0032](../adr/0032-mcp-server.md). Status: **accepted 2026-10-16** (choices 1A, 2A,
3A: writes opt-in and never money out; contacts masked; a separate ordinary key).

## Milestones (each: tests + docs + CHANGELOG, then stop for review/commit)
1. **Package skeleton:** `clients/mcp` (TypeScript, `@modelcontextprotocol/sdk`, stdio), config
   from the environment, `about` tool, key-never-in-output test, build in CI.
2. **Read tools:** payments (with `explain_payment`), refunds, payouts, customers, subscriptions,
   invoices, payment links, balances and ledger, events; amounts with exponent; paging cap;
   contact masking; customer text returned as `untrusted_text`.
3. **Writes (if chosen):** `create_payment_link`, `create_portal_session`, `redeliver_event`
   behind `YOON_MCP_WRITES=true`, required `idempotency_key`.
4. **Release + docs:** release workflow publishes `@yoonpay/mcp` with the other clients;
   `docs/mcp.md` (Claude Desktop, Claude Code, Cursor configs), README, website page; tried by
   hand with Claude Code against the sandbox server (`devtools/`).

## Progress
- M1 package skeleton: `clients/mcp`, stdio, config from the environment, `about`, API key
  scrubbed from every result, CI job (Node 20/22/24) — done (6 tests; checked over stdio).

## Tests beyond the usual
- Without `YOON_MCP_WRITES`, write tools are not listed and cannot be called.
- No tool output, error or log contains the API key; phones/e-mails masked unless unmasked.
- A payment description containing instructions comes back only inside `untrusted_text`.
- A retried write with the same `idempotency_key` makes one link.
