# Contributing to Yoon

Thank you for helping. Start with [CLAUDE.md](CLAUDE.md): it lists the rules the code relies
on (money safety, the ledger, the API contract), which apply to humans and AI agents alike.

## Contributor License Agreement

Yoon's server is AGPL-3.0 and is also offered under a commercial licence. To keep that possible,
every contributor signs a **CLA** before their first pull request is merged; the CLA bot will ask
you on the pull request. You keep the copyright to your work; the CLA lets the project relicense
it.

## Before you open a pull request

- `./mvnw verify` passes (needs Docker for Testcontainers).
- If you touched `api/openapi.yaml`: run `./clients/generate.sh` and commit the result.
- If you touched `clients/php`: `composer install && vendor/bin/phpunit` there.
- New behaviour comes with tests; a new decision with an ADR in `docs/adr/`.
- The README says what users need to know.

## Adding a payment provider

1. A module `yoon-providers/yoon-provider-<id>` implementing `PaymentProvider` and
   `PaymentProviderFactory`, using `ProviderHttp` (no vendor SDK, no Spring).
2. A `<Id>Status` class mapping every raw status, with a parameterised table test.
3. WireMock tests: happy path, refusal, 5xx, timeout, refused connection, every callback variant.
4. A page `docs/providers/<id>.md`: capabilities, credentials, status tables, quirks, evidence.
5. The factory bean in `ProvidersConfiguration`.

If core must change for your provider, the SPI is wrong: propose the SPI change in an issue first.

## Security issues

Never in public issues: see [SECURITY.md](SECURITY.md).
