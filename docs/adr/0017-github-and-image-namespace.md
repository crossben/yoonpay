# ADR-0017 — Repository and images under `crossben`

- Status: accepted (supersedes the GitHub and image part of ADR-0008)
- Date: 2026-09-30

## Decision
The repository lives at `github.com/crossben/yoonpay`. Images are published as
`ghcr.io/crossben/yoon` and `docker.io/<DOCKERHUB_USERNAME>/yoon`; the release workflow derives
both from the repository owner and the Docker Hub secret, so moving the repository later needs
no workflow change. `YOON_SOURCE_URL` defaults to the real repository, which `/v1/about` shows
to users (AGPL-3.0 §13).

Unchanged from ADR-0008: product name **Yoon**, Packagist `yoonpay/yoon-php`, Maven groupId
`dev.yoonpay` (requires owning `yoonpay.dev`), npm `@yoonpay`.

## Why
The GitHub token a workflow receives can only publish packages under the repository's owner.
