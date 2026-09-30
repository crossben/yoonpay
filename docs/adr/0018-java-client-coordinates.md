# ADR-0018 — Java client published as `io.github.crossben:yoon-java`

- Status: accepted (supersedes the Maven part of ADR-0008)
- Date: 2026-09-30

## Decision
The Java client is published to Maven Central as `io.github.crossben:yoon-java`. Maven Central
verifies `io.github.<user>` namespaces through the GitHub account; `dev.yoonpay` would need the
`yoonpay.dev` domain, which is not owned yet. Java package names stay `dev.yoonpay.client` —
Central does not require them to match the groupId, and changing them later would break users.

Server modules keep `dev.yoonpay`: they are never published to Maven Central (the server ships as
a Docker image).

`clients/java/pom.xml` carries Central's required metadata and a `release` profile (sources,
javadoc, GPG signing, `central-publishing-maven-plugin` with manual publish).

## Consequences
If `yoonpay.dev` is bought later, moving to `dev.yoonpay` means a relocation POM — not worth it
for a client that is already published. Decide before the first Central release.
