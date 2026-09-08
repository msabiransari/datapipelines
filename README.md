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
- **Eight SQL dialects** — PostgreSQL, MySQL, SQL Server, Oracle, DuckDB,
  SQLite, H2 and `LAKE` — behind one canonical type system, so results and
  templates are portable across engines.
- **dp-lake** — read Parquet and Apache Iceberg tables on S3 (or any
  S3-compatible store) **in place**: no warehouse, no load step, DuckDB as the
  engine and the server's own `dp-catalog` registry as the catalog, because the
  engine cannot list a bucket. A lake table is one node in a pipeline, so it
  joins your Postgres and MySQL like any other source
  ([`docs/datasources.md` §8C](docs/datasources.md)).
- **Per-execution staging** — intermediate results flow through an isolated
  in-memory H2 database per execution; cross-datasource joins without
  landing data anywhere.
- **MCP-native** — agents get tools for datasources, schema introspection
  (live JDBC metadata, so they stop hallucinating table names), templates,
  pipelines, executions, results, published endpoints and the lake registry.
- **The agent boundary** — there is deliberately no tool that registers a
  datasource and none that mints a key. Registering a datasource means handing
  over a live database credential, and a credential passed through a tool call
  transits the agent's context, its transcript and whatever the client logs. A
  human registers the datasource in the UI (or an operator over REST, or the
  bootstrap file); the agent uses it **by name** and never sees the password
  ([`docs/mcp-server.md` §6.2.22](docs/mcp-server.md)).
- **REST + UI** — a full REST API with a uniform cursor for results, SSE
  execution streams, and a browser UI (pipeline editor with DAG
  visualization, execution history, template explorer, light and dark themes).
- **One API section** — `/api-console` is everything a program uses to talk to
  a workspace, in one place: the published `GET /api/x/**` endpoints, the MCP
  connection block with the live tool count, and the keys page where keys are
  issued and revoked — Kind (`user` \| `endpoint` \| `server`) → Scope → Name →
  Expiry → Bindings, the secret shown exactly once
  ([`docs/ui-screens.md` §4.18](docs/ui-screens.md), [`docs/auth.md` §7.7](docs/auth.md)).
- **Governed by default** — generic OIDC login for humans, API keys for
  agents, a fail-closed scope matrix (`admin ⊃ author ⊃ execute ⊃ read`),
  encrypted datasource credentials, and every endpoint authenticated unless
  explicitly allowlisted.

## Quick start

```bash
./app.sh --start --demo nyc,trade,lake   # builds and runs the whole stack, with sample data
```

Then open `http://localhost:8080` and sign in with the login it prints.

`--demo` takes any subset of the three published sample-data families:
**`nyc`** (NYC taxi trips on Postgres, NOAA weather on MySQL, a SQLite
reference — six example pipelines), **`trade`** (US Census trade on DuckDB, UN
Comtrade on MySQL, Fed H.10 rates on SQLite) and **`lake`** (dp-lake: 24 months
of NYC rideshare trips as Parquet and Iceberg on S3, read in place — nothing is
downloaded, and the four-engine showcase pipeline needs egress to S3 when it
runs). `./app.sh --start --demo nyc` alone is the smallest useful stack. Every
artifact's checksum is verified before an engine is touched
([`docs/deployment.md` Appendix B](docs/deployment.md)).

Deploying it for real is two variables — your name for the environment, and how
careful the product should be:

```bash
./app.sh --scaffold                          # writes deploy/secrets.env, every secret generated
$EDITOR deploy/secrets.env                   # uncomment DATAPIPELINES_ENV=prod and
                                             # DATAPIPELINES_POSTURE=hardened; set your URL
docker compose -f deploy/compose.yml \
  --env-file deploy/env/defaults.env \
  --env-file deploy/secrets.env up -d
```

Two env files, in that order, under every loader — and only two: `deploy/env/defaults.env`
is tracked and carries every non-secret default, including the pinned sample-data versions,
so what a deployment loads is visible in the repository; `deploy/secrets.env` is git-ignored
and holds your credentials and your differences. `./app.sh --scaffold` writes the second one
from `deploy/secrets.env.example`, generating every secret, and names all the variables it
does not set. `--start` scaffolds it for you if it is missing.

Full setup and the variable reference: [`docs/environments.md`](docs/environments.md),
[DEVELOPMENT.md](DEVELOPMENT.md), and the specs under [`docs/`](docs/).

### Give your agent the skill

The product is MCP-native, and the manual an agent needs to author pipelines ships WITH the
server. In Claude Code, one plugin brings the skill and the MCP connection together:

```
/plugin marketplace add msabiransari/datapipelines
/plugin install datapipelines@datapipelines
```

Any other agent reads the skill as a plain `.agents/skills/` directory — every deployment
serves its own copy, unauthenticated, so the manual always matches the version you are
running:

```bash
curl -sS https://your-deployment/skill.md -o .agents/skills/datapipelines/SKILL.md
```

`SKILL.md` maps the reference files it comes with; each one is at `/skill/<name>.md`. The
server also briefs a connecting agent at handshake time and serves the same manual at
`datapipelines://docs/skill` — see [`docs/mcp-server.md` §15](docs/mcp-server.md#15-the-skill-how-an-agent-learns-this-server).

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
