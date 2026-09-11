# Security policy

## Reporting a vulnerability

Email **datapipelines.co@gmail.com** with the subject `Security: <short description>`. Do not
open a public issue for a vulnerability.

Include what you can of: the release tag or commit (`git describe` on the checkout you run), the surface (REST, MCP, the UI, a datasource driver, the deployment scripts), steps
to reproduce, and the impact you believe it has. A minimal reproduction is worth more than a
long report.

**You will get an acknowledgement within 72 hours**, and a status update at least weekly until
the issue is resolved. Fixes ship as a release with a note in the changelog; you will be
credited unless you ask not to be.

## Supported versions

The latest tagged release and `main`. Older tags receive no fixes — upgrade.

## Scope

In scope: this repository — the application, the MCP server, the published-endpoint surface,
the deployment scripts and compose files, the sample-data loaders. Out of scope: the third-party
databases and drivers themselves (report those upstream), the hosted demo's public sample data,
and findings that require a compromised host or an operator's credentials.

The security model the product claims is written down, so you can check a report against it:
[`docs/auth.md`](docs/auth.md) (keys, scopes, workspaces, the public-path allowlist, the 404
rule), [`docs/deployment.md` §9](docs/deployment.md) (the hardening checklist) and the
[security page](https://datapipelines.co/security) on the site.

## What we ask

Give us reasonable time to fix before disclosing; do not access, modify or exfiltrate data that
is not yours (the demo deployment's sample data is public and fair game to query); do not run
denial-of-service tests against a deployment you do not operate.
