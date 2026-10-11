# oasdiff settings for the `breaking-changes` CI job

`severity-levels.txt` lowers two rules to info:

- **A new value in a response enum** (a new event type, a new status).
- **A new alternative in a response `oneOf`** (a new kind of object an event can carry, such as
  subscriptions and invoices in 0.3.0).

Responses are an open set (ADR-0026): Yoon may add event types, statuses and event object kinds in
a minor version. Clients keep parsing values they do not know (`clients/generate.sh` patches PHP and
Python, Java uses `enumUnknownDefaultCase`, JS has no runtime checks) and each client has a test for
it. A new **kind of event object** only appears for applications that use the feature that emits it
(e.g. billing), and those must upgrade their client first — said in the CHANGELOG.

Everything else still fails the build: removing an enum value or a `oneOf` alternative, new
required inputs, removed fields or routes, narrowed types.
