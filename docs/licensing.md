# Licensing

| Part | Licence |
| --- | --- |
| `yoon-core`, `yoon-server`, `yoon-providers/*`, `yoon-testkit` | [AGPL-3.0](../LICENSE) |
| `clients/php`, `clients/java`, `examples/` | [Apache-2.0](../clients/LICENSE) |

A **commercial licence** for the server is available to organisations that cannot use AGPL-3.0.

## What AGPL-3.0 asks of you — plainly

This is guidance, not legal advice.

- **Running Yoon unmodified**, for your own applications or your clients': no obligation beyond
  keeping the licence notices. `GET /v1/about` points users to the source.
- **Modifying Yoon and using it only for your own applications**: nobody else interacts with it,
  so you owe no source to anyone.
- **Modifying Yoon and letting others use it over a network** — for example offering it as a
  payment service to other merchants: you must offer those users the source of your modified
  version. Set `YOON_SOURCE_URL` to where it is published; `/v1/about` shows it.
- **Distributing a modified Yoon** (images, binaries): distribute its source under AGPL-3.0.
- **Your applications** that call Yoon over HTTP, or use the Apache-2.0 clients, are not affected
  by the AGPL.

## Enforcement

When a licence violation is reported, the project will:

1. confirm it with evidence;
2. write to the organisation privately, explaining what is needed;
3. allow 30 days to comply (AGPL-3.0 §8 restores the licence of a first-time violator who does);
4. offer the commercial licence as an alternative;
5. involve a lawyer only if all of that fails.

The aim is compliance, not litigation. The project name is covered separately by
[TRADEMARKS.md](../TRADEMARKS.md).
