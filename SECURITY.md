# Security policy

Yoon moves money. Please report vulnerabilities privately.

## Reporting

Use **GitHub's private vulnerability reporting** on this repository (Security → Report a
vulnerability). Do not open a public issue.

Include what you found, how to reproduce it, and what an attacker could do. You will get an
acknowledgement within 3 working days and an assessment within 10.

## Scope

In scope: the server (`yoon-core`, `yoon-server`, provider modules), the Docker image, the
deployment files in `deploy/`, and the client libraries in `clients/`.

Especially interesting: anything that could settle an unpaid payment, pay out twice, let one
application read another's data, forge a webhook that Yoon or a client accepts, or leak
credentials or phone numbers.

## Supported versions

Security fixes go to the latest minor release (`0.1.x` today).

## Disclosure

We will agree on a disclosure date with you, publish a GitHub security advisory with the fix,
and credit you unless you prefer otherwise.
