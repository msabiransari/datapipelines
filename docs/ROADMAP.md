# ROADMAP

**Status:** living document — consolidated future work from across all specs
**Owner:** datapipelines.co core
**Purpose:** Single source of truth for what's been explicitly deferred, tentatively planned, or formally rejected. Prevents "what about X?" re-litigation and gives a clear view of the project's evolution.

---

## How to use this document

Every spec has an "Open Questions / Future Additions" section. Those items are **consolidated here** by target version, with the source spec tagged. When a feature moves from ROADMAP into a spec, it's removed here.

Items are organized by **target version**:
- **v1.1** — small, additive enhancements likely to land soon after v1 ship.
- **v2** — meaningful feature work, multi-spec scope, planned but not scheduled.
- **Long-term / undated** — directionally interesting, no commitment.
- **Operator responsibility** — explicitly NOT our work; deployers handle these.
- **Rejected** — deliberately not doing, with reasoning on record.

---

## 1. Rejected (with reasoning)

These were considered and explicitly rejected. Documented here so we don't re-litigate.

| Item | Reasoning | Source conversation |
|---|---|---|
| **SQL parser (Apache Calcite or custom)** | Pipelines use templated SQL generation (Freemarker), not parsing. Calcite's optimizer would fight our "stage in tempdb then join" execution model. We don't need SQL parsing for any v1 use case. | Pipeline design discussion |
| **`parallel_id` field on nodes** | `depends_on` is mathematically complete for DAG parallelism (two nodes run in parallel iff neither is reachable from the other). A second parallelism source-of-truth would create reconciliation bugs. Per-source concurrency limits handle resource-grouping use cases. | Pipeline v1.1 review |
| **All-string-for-numbers wire encoding** | Type-based is correct: types whose entire value space fits in IEEE 754 (`INTEGER`, `DECIMAL(p≤15)`, `REAL`, `DOUBLE`) serialize as numbers; types that can exceed it (`BIGINTEGER`, `BIGDECIMAL`) serialize as strings. All-string would force unnecessary parsing on every numeric column. | Type System review |
| **`category` field in schema envelope** | Type name itself is the contract (`BIG*` prefix signals "string on wire"). Adding `category` was over-engineering — clients switch on type name and get the wire encoding for free. | Type System review |
| **OAuth for v1** | Self-hosted, internal-users-only deployment. OAuth adds authorization server, redirect flows (impossible for non-browser agents like Claude Desktop), token refresh, client registration — overkill for the deployment model. API keys per-agent are sufficient. | MCP/Auth design |
| **Per-value wire encoding (DECIMAL as number if value fits, else string)** | Wire format must be stable per-column. Value-based switching would mean the same column has different wire encodings per row — joins break, parsers break, schemas break. Mapping is by type and precision, never by value. | Type System §4 |

---

## 2. v1.1 Candidates

Small, additive, likely to land soon after v1 ship. Rough priority order.

| Feature | Source spec | Notes |
|---|---|---|
| **Schema introspection tools** (`datasources_get_schema`, `datasources_get_tables`, `datasources_get_columns`) | mcp-server §12, datasources §14 | Needed for LLM-assisted pipeline authoring. Without these, agents can't author SQL templates — they don't know source schemas. High leverage. |
| **Parameterized SQL output** (templates emit `{sql, params}` for prepared statements) | templates §13 | Closes the SQL injection gap. Templates currently render to raw SQL strings; parameterized output is safer for user-controlled values. |
| **Auto-create target table for write-back** (`output.auto_create: true`) | pipeline-contract §18 | For `output.target: "datasource"`: emit `CREATE TABLE IF NOT EXISTS` from ResultSet metadata before INSERT. Saves a preceding DDL node in the common case. |
| **DuckDB as staging engine** (`settings.tempdb.engine: "DUCKDB"`) | pipeline-contract §18, staging §14 | Better for analytical workloads (large joins, wide aggregations). DuckDB is internally parallel, sidesteps the single-connection serialization concern. |
| **Template `engine` field** (default `freemarker`, future `pebble`/`handlebars`/etc.) | templates §13 | Additive field; supports alternative engines without breaking v1 templates. |
| **H2 connection pool for staging** | dag-executor §13, staging §9.3 | Only if profiling shows staging serialization is a bottleneck. Default v1 is single-connection. |
| **KMS integration for credential encryption** (AWS KMS, GCP KMS, HashiCorp Vault) | datasources §14, deployment | Replace file-based master key with KMS-sourced key. Enterprise-friendly. |
| **Background datasource health checks** | datasources §14 | Scheduled polling with UI indicators. Catches dead datasources before pipeline execution. |
| **SSH tunnel / bastion host support** | datasources §14 | Common enterprise requirement for datasources behind bastions. |
| **`tags` field on pipelines, templates, datasources** | (cross-cutting) | For organization, filtering, MCP discovery. Optional field, no execution semantics. |

---

## 3. v2 Features

Larger feature work, multi-spec scope. Planned but not scheduled.

### 3.1 Type system expansions

| Feature | Source spec |
|---|---|
| Nested types (`STRUCT`, `ARRAY`, `MAP`) with schema-declared shapes | type-system §12 |
| Geospatial types (`GEOMETRY`, `GEOGRAPHY`) with declared SRID | type-system §12 |
| Intervals (`INTERVAL_YEAR_MONTH`, `INTERVAL_DAY_TIME`) | type-system §12 |
| First-class `UUID` type | type-system §12 |
| First-class `ENUM` type with declared allowed values | type-system §12 |
| First-class `JSON` type with declared schema | type-system §12 |
| `BIT_STRING` type | type-system §12 |
| Schema introspection REST endpoint (`/types`, `/schema`) | type-system §12 |

### 3.2 Pipeline model expansions

| Feature | Source spec |
|---|---|
| **Calculators** — pre-execution transformers that read Context and write additional keys (`quarter` from `date`, etc.) | pipeline-contract §18 |
| **Non-SQL node types** — `EXPRESSION` (no SQL, transform via expression language), `HTTP` (call external API, stage response) | pipeline-contract §18, dag-executor §13 |
| **Conditional execution** — skip nodes based on a Context expression (`when: "${include_cancelled} == true"`) | pipeline-contract §18, dag-executor §13 |
| **Per-node retry policies** (`{"retries": 3, "backoff": "exponential"}`) | pipeline-contract §18, dag-executor §13 |
| **Streaming between nodes** — pipe rows instead of full materialization | pipeline-contract §18, dag-executor §13, staging §14 |
| **Pipeline-level scheduling** — cron-style declarations | pipeline-contract §18 |
| **Additional `output.target` values** — `kafka`, `s3`, `email`, `webhook` | pipeline-contract §18, enums §3 |
| **Partial-result mode** — return whatever data was staged before a node failed | dag-executor §13 |
| **Cycle support (iterative pipelines)** — bounded loops for ML convergence algorithms | dag-executor §13 |
| **Async / scheduled execution** — trigger pipelines, return immediately, deliver via webhook later | dag-executor §13 |
| **Cross-pipeline calls** — invoke pipeline A from pipeline B's node | (not yet in any spec) |

### 3.3 Templates

| Feature | Source spec |
|---|---|
| Multi-dialect templates — dialect-conditional sections (`<#if dialect == "ORACLE">...`) | templates §13 |
| Template testing framework — declarative test cases (`given context, render should match expected SQL`) | templates §13 |
| Template composition visualizer — UI showing how imports resolve into final SQL | templates §13 |
| Library template marketplace — shareable libraries across deployments | (not yet in any spec) |

### 3.4 Datasources

| Feature | Source spec |
|---|---|
| Read-only enforcement at datasource level | datasources §14 |
| Datasource groups / failover — primary + replica, auto-failover | datasources §14 |
| OAuth / IAM auth for cloud databases (Snowflake, BigQuery) | datasources §14 |
| Snowflake, BigQuery, Redshift dialect support | enums §5, type-system §12 |

### 3.5 Staging

| Feature | Source spec |
|---|---|
| Hybrid staging — H2 for small state, DuckDB for large joins | staging §14 |
| Spill-to-disk when memory limit hit | staging §14 |
| Indexing hints — let templates declare `CREATE INDEX` for staging tables | staging §14 |
| Persistent staging for debugging — preserve for N minutes post-execution | staging §14 |

### 3.6 REST API + SSE

| Feature | Source spec |
|---|---|
| Streaming result delivery via SSE (`data_chunk` events) | rest-api §14, enums §11 |
| GraphQL endpoint mirroring REST surface | rest-api §14 |
| Webhook callbacks — register URL, receive execution events | rest-api §14 |
| Result caching — TTL-based, keyed by `pipeline_id + version + parameters hash` | rest-api §14 |

### 3.7 MCP Server

| Feature | Source spec |
|---|---|
| Dynamic per-pipeline tools — register `pipeline_execute_{name}` for agent-exposed pipelines | mcp-server §12 |
| Resource subscriptions (`resources/subscribe`) — live updates when pipelines/templates change | mcp-server §12 |
| Result streaming via MCP — stream execution events through MCP transport as notifications | mcp-server §12 |

### 3.8 Auth

| Feature | Source spec |
|---|---|
| SSO / SAML / OIDC — enterprise identity provider integration | auth §14 |
| MFA — TOTP-based second factor | auth §14 |
| Per-datasource ACLs — fine-grained access beyond scopes | auth §14 |
| Service accounts — non-user principals for automation | auth §14 |
| Key rotation workflow — issue new + deprecate old with overlap window | auth §14 |
| IP allowlisting per key | auth §14 |
| Key use alerts — notify user when key used from new IP | auth §14 |
| WebAuthn / passkeys — passwordless login | auth §14 |
| Per-tenant isolation (when SaaS materializes) | auth §14 |

### 3.9 Build / Module Structure

| Feature | Source spec |
|---|---|
| Module extraction — publish `typesystem` to Maven Central for client SDKs | module-structure §12 |
| Gradle configuration-cache + build-cache sharing across CI | module-structure §12 |
| Per-concern version catalog splits (DB drivers, web libs, etc.) | module-structure §12 |

### 3.10 Observability

| Feature | Source spec |
|---|---|
| Distributed tracing across pipeline-to-pipeline calls (depends on §3.2 cross-pipeline) | observability §10 |
| OpenTelemetry collector reference configs (Loki, Tempo, Prometheus, Grafana) | observability §10 |

### 3.11 Deployment

| Feature | Source spec |
|---|---|
| Federated deployments — multiple instances sharing state | deployment §11 |
| Air-gapped deployment support (explicitly tested, no phone-home) | deployment §11 |
| Managed / SaaS deployment (commercial offering) | deployment §11 |

---

## 4. Long-term / Undated

Directionally interesting; no commitment.

| Feature | Notes |
|---|---|
| Arrow as default wire format | If Arrow IPC adoption grows, could become default with JSON as fallback |
| Multi-platform (KMP) `typesystem` | Publish type definitions consumable by JS/.NET for typed client SDKs. Probably never needed. |
| Polyglot modules | Python SDK or CLI in `python/` directory at repo root |
| Real-time alerting | Operator responsibility (Alertmanager); we may ship reference configs |
| Custom dashboards | Operator responsibility (Grafana); we may ship reference dashboards |
| HA Postgres in Helm chart | Operator responsibility; recommend managed Postgres |
| Backup automation | Operator responsibility; we provide runbook |
| MCP roots support | Not applicable — we are not a filesystem tool |
| MCP sampling support | Rare for this product; agents do their own LLM work |

---

## 5. Operator Responsibilities (Explicitly NOT Our Work)

Things deployers handle, not us. Listed here so we don't accidentally scope-creep into them.

| Concern | Why operator's job |
|---|---|
| TLS termination at load balancer | Cert management is environment-specific |
| Postgres backup + restore drills | Managed Postgres services handle this; bare-metal operators have their own runbooks |
| Network egress policy (firewall, NetworkPolicy) | Environment-specific |
| Log aggregation (CloudWatch, Loki, ELK) | Vendor / environment choice |
| Metrics backend (Prometheus, Datadog) | Vendor choice |
| Alert rules and on-call rotation | Organization-specific |
| Container orchestration tuning | Environment-specific |
| Secrets management (Vault, AWS Secrets Manager) | Environment-specific |
| Capacity planning | Per-deployment |

---

## 6. Decision Log (Items Moved Out of ROADMAP)

When something moves from ROADMAP into a shipped spec, log it here so we can trace the evolution.

| Date | Item | Moved to | Notes |
|---|---|---|---|
| (empty — v1 not yet shipped) | | | |

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial ROADMAP: consolidated future work from all 12 specs, organized by version (v1.1 / v2 / long-term), rejected items with reasoning, operator responsibilities |
