# oasdiff settings for the `breaking-changes` CI job

`severity-levels.txt` lowers one rule: **a new value in a response enum** (a new event type, a new
status) is reported as info instead of a warning. Response enums are an open set (ADR-0026): every
client keeps parsing values it does not know (`clients/generate.sh` patches PHP and Python, Java
uses `enumUnknownDefaultCase`, JS has no runtime checks), and each client has a test for it.

Everything else still fails the build, including **removing** an enum value or adding one to a
request parameter.
