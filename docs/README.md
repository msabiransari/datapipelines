# datapipelines.co — Specification Index

Spec-first project: these documents ARE the product definition; implementation follows them. Consistency is enforced mechanically — any change here must pass `../scripts/docs-audit.sh` (exit 0) before merging. The August 2026 consistency campaign and its ratified decisions are recorded in [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md).

## What the product is

A self-hosted server that executes **declarative JSON pipelines** — DAGs of templated-SQL nodes — against heterogeneous databases, staging intermediate results in a per-execution in-memory H2, and returning results through a uniform Redis-backed cursor. It is **MCP-native**: LLM agents author and execute pipelines as first-class clients alongside the REST API and the browser UI.

```
                 ┌────────────── auth (OIDC users · API-key agents · scopes) ──────────────┐
  Browser UI ────┤                                                                          │
  REST client ───┼──► pipeline-contract ──► dag-executor ──► datasources (JDBC dialects)    │
  MCP agent ─────┤        │    │                 │      └──► staging (per-execution H2)     │
                 │        │    └── templates (Freemarker SQL, versioned, libraries)         │
                 │        └─────── type-system (canonical types, per-dialect mappers)       │
                 │                                                                          │
                 └── results/events → Redis (cursor, TTL) · metadata → Postgres (Flyway) ───┘
```

## Reading order

New to the project? Read in this order: **type-system → pipeline-contract → templates → datasources → staging → dag-executor** (the engine), then **rest-api → mcp-server → auth** (the surfaces), then the rest as needed.

## The documents

### Contract authorities (read these before anything that cites them)

| Doc | Status | One line |
|---|---|---|
| [type-system.md](type-system.md) | v1.1 frozen | 11 canonical logical types, wire encodings (`BIG*` = string), per-dialect JDBC mapping tables, UTC normalization |
| [pipeline-contract.md](pipeline-contract.md) | v1.2 frozen | Pipeline/Node JSON schema, caller-node result model, validation rules, **the single error-code catalog (§13)** |
| [enums.md](enums.md) | v1.1 living | Every enum's wire value + serialization convention; error-code *domain* registry |
| [configuration.md](configuration.md) | v1.1 | **The only place config keys are defined** — YAML path, env derivation rule, defaults, validation |
| [auth.md](auth.md) | v2.4 | Generic OIDC login, internal JWT, API keys, **the scope↔operation matrix (§7.6)**, Spring Security chain |
| [metadata-db.md](metadata-db.md) | v1.1 frozen | **The only doc that writes DDL** — full Postgres schema, Flyway V1 source, operational jobs |

### Engine

| Doc | Status | One line |
|---|---|---|
| [templates.md](templates.md) | v1.3 frozen | Freemarker SQL templates: versioning, library imports (`{id, version, alias}`), SSTI hardening, parse-only save validation |
| [datasources.md](datasources.md) | v1.4 | Named connections, 7 dialects, Hikari/JDBC property passthrough (§5.6 refusal sets), AES-GCM credential storage |
| [staging.md](staging.md) | v1.5 frozen | Per-execution in-memory H2: lifecycle, identifier safety, mutex-guarded connection behind `withConnection`, memory limits |
| [dag-executor.md](dag-executor.md) | v1.2 | Coroutine executor: topological execution, fail-fast, cancellation (Redis flag), result materialization |

### Surfaces

| Doc | Status | One line |
|---|---|---|
| [rest-api.md](rest-api.md) | v1.3 frozen | Endpoints, envelopes, SSE execution stream, **uniform result-delivery cursor (§7)**, auth/user-admin endpoints (§16) |
| [mcp-server.md](mcp-server.md) | v1.2 frozen | Streamable HTTP MCP: 15 tools, resources, prompts — a thin adapter over REST |
| [ui-screens.md](ui-screens.md) | v1.1 | 12 CRUD screens: Thymeleaf + htmx, `/partials/**` convention, standard states |
| [pipeline-editor.md](pipeline-editor.md) | v1.2 | Cytoscape execution/visualization surface: vendored assets, SSE wiring, canvas a11y model |

### Operations

| Doc | Status | One line |
|---|---|---|
| [deployment.md](deployment.md) | v1.2 | Docker image, compose/k8s, multi-instance model, Redis requirements, graceful shutdown, sizing |
| [observability.md](observability.md) | v1.1 draft | JSON logs + correlation IDs, metric naming/cardinality rules, redaction (normative), health endpoints |
| [module-structure.md](module-structure.md) | v1.2 | Gradle modules, exhaustive dependency table, persistence ownership, version catalog + implementation gates |

### Meta

| Doc | Purpose |
|---|---|
| [ROADMAP.md](ROADMAP.md) | Deferred (v1.1/v2), rejected-with-reasoning, operator responsibilities — prevents re-litigation |
| [SPEC-REVIEW-2026-08.md](SPEC-REVIEW-2026-08.md) | The 2026-08 consistency campaign: findings, ratified decisions D1–D15, per-doc resolutions |
| [../DEVELOPMENT.md](../DEVELOPMENT.md) | Developer setup: local infra, OIDC setup, build/run/test, git workflow |

## House rules (cross-cutting)

- **Single authority per fact.** Config keys → configuration.md. DDL → metadata-db.md. Error codes → pipeline-contract §13. Enum wire values → enums.md. Scopes per operation → auth §7.6. Other docs link; they never restate.
- **Universal save-time validation.** Nothing invalid ever reaches the database (pipeline-contract §2.8).
- **Custom headers carry the `DP-` prefix** (rest-api §3.6).
- **Docs are the contract.** Frozen docs change additively only; every change lands with a Change Log row and a green `docs-audit.sh`.
