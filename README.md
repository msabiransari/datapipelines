# datapipelines.co

**Self-hosted, MCP-native data pipeline server.** Define pipelines as
declarative JSON — DAGs of SQL nodes templated with FreeMarker — and execute
them against your own databases. Built for the agent era: AI agents connect
over the [Model Context Protocol](https://modelcontextprotocol.io) to
discover your datasources, ground themselves in real schemas, author
pipelines, run them, and read results — through the same governed, scoped
API humans use.

## What it does

- **Declarative pipelines** — a pipeline is a JSON document: nodes, edges,
  parameters. Each node renders a FreeMarker SQL template and runs it
  against a datasource or the in-memory staging area. No orchestration code.
- **Seven SQL dialects** — PostgreSQL, MySQL, SQL Server, Oracle, DuckDB,
  SQLite, H2 — behind one canonical type system, so results and templates
  are portable across engines.
- **Per-execution staging** — intermediate results flow through an isolated
  in-memory H2 database per execution; cross-datasource joins without
  landing data anywhere.
- **MCP-native** — agents get tools for datasources, schema introspection
  (live JDBC metadata, so they stop hallucinating table names), templates,
  pipelines, executions, and results.
- **REST + UI** — a full REST API with a uniform cursor for results, SSE
  execution streams, and a browser UI (pipeline editor with DAG
  visualization, execution history, template editor).
- **Governed by default** — generic OIDC login for humans, API keys for
  agents, a fail-closed scope matrix (`admin ⊃ author ⊃ execute ⊃ read`),
  encrypted datasource credentials, and every endpoint authenticated unless
  explicitly allowlisted.

## Quick start

```bash
cd deploy
cp .env.example .env       # fill in secrets — see the comments in the file
docker compose up
```

Then open `http://localhost:8080`. Full setup, configuration reference, and
development instructions: [DEVELOPMENT.md](DEVELOPMENT.md) and the specs
under [`docs/`](docs/).

## Documentation

The `docs/` directory is the product specification and is load-bearing:
error codes, MCP tool schemas, and auth scopes documented there are enforced
against the code by drift tests. Start with:

- [`docs/pipeline-contract.md`](docs/pipeline-contract.md) — the pipeline JSON contract
- [`docs/mcp-server.md`](docs/mcp-server.md) — MCP tools, resources, prompts
- [`docs/rest-api.md`](docs/rest-api.md) — REST surface
- [`docs/auth.md`](docs/auth.md) — authentication and the scope matrix

The marketing site is served by the app itself at `/` (template and assets under `modules/web/src/main/resources/{templates/site,static/site}`), and the spec set renders in-product at `/docs` for signed-in users — see [`docs/deployment.md`](docs/deployment.md) for the static-export fallback.

## Using, deploying, contributing

Deploy and use it freely, as-is, commercial use included — no permission
needed. The project is licensed under the **AGPL-3.0** ([LICENSE](LICENSE)):
if you modify it and offer it over a network, you must publish your
modifications under the same license. In practice, the easy path for changes
is upstream: open a PR ([CONTRIBUTING.md](CONTRIBUTING.md), one-time
[CLA](CLA.md)). All changes land through pull requests reviewed by the
project owner.

Need changes, integrations, or help running it in production?
**Consulting is available from the author** — open an issue or reach out.
