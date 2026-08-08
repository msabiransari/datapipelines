# Observability Specification

**Status:** v1.1 draft (to be elaborated before production hardening — the rules marked **normative** below are already binding)
**Owner:** datapipelines.co core
**Depends on:** all other specs
**Last updated:** 2026-08-07

---

## 1. Purpose

Observability for datapipelines.co covers **logs**, **metrics**, **traces**, and **health endpoints** — the signals an operator uses to detect, diagnose, and resolve issues in production.

This spec is **lighter than the others** because observability is cross-cutting and mostly follows industry-standard patterns. It documents the specific decisions made for v1 and the conventions modules follow. Dashboards, alert rules, SLOs and log-retention policy are still to be elaborated before production hardening — this remains a **draft** in that sense.

Four parts of it are **normative** today and binding on implementation:

| Normative area | Where |
|---|---|
| Correlation-id propagation (`DP-Correlation-Id`, SSE payloads, MCP `_meta`) | §3.3 |
| Metric names, tag sets and cardinality limits | §4.1, §4.3 |
| Health payload and paths (owned by [REST API §11.1](rest-api.md#111-health-check)) | §6 |
| Log redaction mechanism and the sensitive-key list | §9.2 |

---

## 2. Design Principles

1. **Structured logs only.** Every log entry is JSON with stable field names. No `println`, no ad-hoc string formatting in logs.
2. **Correlation IDs everywhere.** Every request carries a correlation ID (`DP-Correlation-Id`, [REST API §3.4](rest-api.md#34-correlation), registered in the `DP-` header registry [§3.6](rest-api.md#36-custom-header-registry)) through every log line, trace span, SSE event payload and MCP tool result. Pipeline executions extend this to per-node correlation. Correlation ID is *never* a metric tag (§4.3).
3. **Metrics via Micrometer.** Spring Boot's default micrometer integration; no custom metrics framework.
4. **Tracing via OpenTelemetry.** Vendor-neutral; instrumented into every module.
5. **Health endpoints for orchestrators.** Root-level `/health` and `/ready` for k8s liveness/readiness probes. The payload contract is owned by [REST API §11](rest-api.md#11-health--diagnostics); §6 here restates it only as far as needed for operators.
6. **No sensitive data in logs — enforced at the encoder, not by convention.** Passwords, API keys, JDBC URLs with credentials, encryption keys, query result values are scrubbed by the logging pipeline itself, so no logger call site can bypass the rule (§9.2).

---

## 3. Logging

### 3.1 Format

JSON via `logstash-logback-encoder`. Every log entry:

```json
{
  "@timestamp": "2026-08-05T14:30:00.123Z",
  "level": "INFO",
  "logger": "co.datapipelines.dag.PipelineExecutor",
  "thread": "executor-worker-3",
  "message": "Node completed",
  "correlation_id": "uuid",
  "execution_id": "uuid",
  "pipeline_id": "uuid",
  "node_id": "fetch_orders",
  "duration_ms": 1266,
  "rows_out": 12453
}
```

Standard fields: `@timestamp`, `level`, `logger`, `thread`, `message`. Context fields added via MDC (SLF4J's Mapped Diagnostic Context).

### 3.2 Levels

| Level | When used |
|---|---|
| `ERROR` | Failures requiring operator attention (H2 cleanup failed, datasource pool exhausted, uncaught exception). |
| `WARN` | Degraded operation (rate-limited request, retry attempted, type-mapping fallback to STRING). |
| `INFO` | Noteworthy events (pipeline execution started/completed, datasource registered). |
| `DEBUG` | Diagnostic detail (template rendered SQL, H2 table created). Disabled in production. |
| `TRACE` | Very fine-grained (per-row processing). Disabled in production. |

### 3.3 Correlation ID propagation

- Set on inbound request from the `DP-Correlation-Id` header, or generated if absent; echoed back on the response ([REST API §3.4](rest-api.md#34-correlation)).
- Stored in MDC at request start, cleared at request end.
- Propagated through async work via Spring's `TaskDecorator` (for thread pools) and Kotlin coroutine context (for executor work).
- Included in every log line in the request's call tree.
- Pipeline executions add `execution_id` and propagate it to every node-execution log.

**Normative — propagation past the HTTP boundary.** A correlation ID that stops at the response header is useless for the two asynchronous surfaces this system exposes:

| Surface | Rule |
|---|---|
| SSE | **Every** event payload emitted on an execution stream carries `correlation_id` — the correlation ID of the request that started the execution — for all event types in [REST API §6.4](rest-api.md#64-event-types), including `execution_aborted`. A client that only ever sees the stream can still quote an ID to an operator. |
| MCP | Every tool result, success or error, carries `correlation_id` in its `_meta` — already normative in [MCP Server §6.3](mcp-server.md#63-tool-result-schema); not restated here. |
| Traces | Same value appears as the `correlation_id` span attribute (§5.2), which is what joins logs to traces. |

### 3.4 What's logged per module

| Module | Key log events |
|---|---|
| `auth` | login success/failure, key issuance/revocation (via audit log, not general log) |
| `datasources` | datasource registered/updated/deleted, pool initialized/drained, connection acquisition failures |
| `staging` | H2 instance created/closed, staging operation success (table name + row count), memory-limit warnings |
| `dag` | execution started/completed/failed, node started/completed/failed, cancellation |
| `templates` | template registered (id + version), render failures |
| `mcp-server` | tool calls (tool name + caller), transport errors |
| `web` | request log (method, path, status, duration), CORS preflight, SSE connections opened/closed |

### 3.5 Log destination

- **Stdout** by default — collected by container runtime (Docker / k8s) and shipped to the operator's log aggregator (CloudWatch, Stackdriver, Loki, ELK, etc.).
- **No file logging in container.** Operators choose the aggregation strategy.
- **Local dev**: human-readable console output (via `logback-spring.xml` dev profile).

---

## 4. Metrics

### 4.1 Metric naming

All **application** metrics are prefixed with `datapipelines.`. Framework-supplied metrics keep the names their instrumentation gives them — notably Spring Boot's `http.server.requests` and HikariCP's `hikaricp.*`, which are **not** renamed into the `datapipelines.` namespace (renaming them would break every off-the-shelf dashboard and Boot's own auto-configuration).

Tag sets below are the complete, normative set for each metric — adding a tag is a spec change, not an implementation detail (§4.3).

| Metric | Type | Tags | Description |
|---|---|---|---|
| `datapipelines.executions.total` | counter | `status` (success/failed/aborted), `pipeline_id` | Execution count |
| `datapipelines.executions.aborted` | counter | `reason` (`client_disconnect`/`cancelled`/`shutdown`) | Aborted executions by trigger — the three D7 paths ([dag-executor §15.3](dag-executor.md#153-monitoring), [enums `ExecutionStatus`](enums.md)) |
| `datapipelines.executions.duration` | timer | `pipeline_id` | Execution wall-clock duration |
| `datapipelines.executions.concurrent` | gauge | (none) | Currently-running executions |
| `datapipelines.nodes.duration` | timer | `pipeline_id`, `node_id`, `source` | Per-node duration |
| `datapipelines.nodes.rows_out` | counter | `pipeline_id`, `node_id` | Rows emitted by node |
| `datapipelines.staging.rows` | counter | (none) | Total rows staged across all executions |
| `datapipelines.staging.bytes` | counter | (none) | Total bytes staged |
| `datapipelines.staging.active_tables` | gauge | (none) | Current H2 tables across all in-flight executions |
| `datapipelines.datasource.pool.active` | gauge | `datasource_name` | Active connections in pool |
| `datapipelines.datasource.pool.pending` | gauge | `datasource_name` | Threads waiting for a connection |
| `datapipelines.datasource.pool.timeout_total` | counter | `datasource_name` | Pool-acquisition timeouts |
| `datapipelines.templates.render.duration` | timer | `template_id`, `template_version` | Template render time |
| `datapipelines.templates.cache.hits` | counter | (none) | Template cache hits |
| `datapipelines.templates.cache.misses` | counter | (none) | Template cache misses |
| `http.server.requests` | timer | `method`, `uri`, `status`, `outcome` | HTTP request duration. **Spring Boot's own metric — unprefixed.** `uri` is the templated path (`/api/v1/executions/{id}`), never the expanded one. |
| `datapipelines.mcp.tool.calls` | counter | `tool_name`, `status` | MCP tool invocations |
| `datapipelines.auth.login.attempts` | counter | `outcome` (`success`/`domain_not_allowed`/`user_inactive`/`oidc_error`) | Login attempts. Outcomes mirror the audit events in [Auth §10.1](auth.md#101-events). There is **no** lockout outcome: authentication is OIDC-only, the product stores no local passwords, and no lockout mechanism exists to count. |
| `datapipelines.auth.api_key.validations` | counter | `outcome` (success/invalid/expired/revoked) | API key validations |

**Result delivery, SSE and idempotency** ([REST API §7](rest-api.md#7-result-delivery), D9):

| Metric | Type | Tags | Description |
|---|---|---|---|
| `datapipelines.result.bytes_written` | counter | (none) | Bytes materialized into the Redis result store. Paired with `result.max-size-bytes` — the ratio of writes near the cap is the signal that a deployment is using result delivery as a bulk-data path (an explicit NOT-goal). |
| `datapipelines.result.writes` | counter | `outcome` (`stored`/`too_large`/`storage_unavailable`) | Result-store write attempts; the failure outcomes correspond 1:1 to `result.too_large` and `result.storage_unavailable`. |
| `datapipelines.result.cursor.reads` | counter | `format` (json/arrow/csv), `outcome` (`hit`/`expired`/`not_found`) | Cursor endpoint reads. `expired` rising relative to `hit` means the TTL is set too low for how clients actually page. |
| `datapipelines.result.expiries` | counter | (none) | Results that reached TTL without ever being read past the inline first page. |
| `datapipelines.result.size` | distribution summary | (none) | Result size in bytes — percentiles inform `result.max-size-bytes` tuning. |
| `datapipelines.sse.streams.active` | gauge | (none) | Currently-open SSE execution streams |
| `datapipelines.sse.stream.duration` | timer | `close_reason` (`completed`/`failed`/`aborted`/`client_disconnect`) | Lifetime of an SSE stream. `client_disconnect` here is what feeds the disconnect-grace cancellation path (D7). |
| `datapipelines.idempotency.cache.hits` | counter | (none) | Requests served from a stored idempotent response |
| `datapipelines.idempotency.conflicts` | counter | (none) | `idempotency.key_reused_for_different_request` rejections |

### 4.2 Exposure

- Micrometer's Prometheus endpoint at `/actuator/prometheus` — served on a **separate management port** (`management.server.port`, framework wiring key), never on the application port. Scrapers reach it over the cluster-internal network; it is NOT in auth.md §8.3's public list and no `/actuator` path is reachable on the app port without auth. Rationale (2026-08-07 security review MEDIUM-7): §4.3's metric tags include `pipeline_id`, `datasource_name` and `template_id` — an unauthenticated metrics endpoint on the app port would publish the internal inventory that §6's bare-UP/DOWN health design exists to protect.
- Note the asymmetry: `/health`, `/ready` and `/info` are served at the **root** of the application port, not under `/actuator` and not under `/api/v1` (§6).
- Other backends (Datadog, New Relic, CloudWatch) selectable via Micrometer registry config.
- Actuator/management exposure is configured with Spring Boot's own `management.*` keys — datapipelines defines no key of its own for it ([configuration.md §3.14](configuration.md#314-framework-wiring-keys) registers those framework paths; [§3.15](configuration.md#315-observability) holds this product's own observability keys).

### 4.3 Cardinality discipline

High-cardinality tags avoided:
- ❌ Don't tag by `user_id`, `execution_id`, `correlation_id`, exception class, method name, or any raw URI/SQL string.
- ✅ Do tag by `pipeline_id`, `node_id`, `datasource_name`, `template_id` (bounded counts), and by closed enum-valued dimensions (`status`, `reason`, `outcome`, `format`).
- Per-execution metrics live in the execution record (database), not in the metrics system.

**Normative rule:** every tag value must come from a closed set — an enum, or an entity ID whose population is bounded by what the deployment stores. A tag whose values are derived from *code shape* (class, method, stack frame) or from *input text* is forbidden; that detail belongs in the log line and the trace span, which are indexed for it. §8.1 applies this rule to error counting.

---

## 5. Tracing

### 5.1 OpenTelemetry

OpenTelemetry SDK initialized in `app`. Auto-instrumentation via the `opentelemetry-spring-boot-starter`:

- HTTP server spans for every inbound request (REST + MCP).
- JDBC spans for every DB query (via `opentelemetry-jdbc-instrumentation`).
- HikariCP spans for connection acquisition.
- Custom spans for template rendering and node execution.

### 5.2 Span attributes

Every span carries:
- `correlation_id` — request correlation.
- `user_id` (on auth'd requests).
- `pipeline_id`, `execution_id`, `node_id` (on pipeline-execution spans).
- `datasource_name` (on datasource-related spans).

### 5.3 Exporter

- **OTLP** exporter (HTTP/gRPC to collector) — vendor-neutral.
- Operator configures the collector endpoint (`OTEL_EXPORTER_OTLP_ENDPOINT`).
- No vendor lock-in.

### 5.4 Sampling

- Default: probabilistic sampling at 10% for general traffic, 100% for pipeline executions (high-value, low-volume).
- Configurable via `otel.traces.sampler.*` properties.

---

## 6. Health Endpoints

> **Authority:** the payload and the paths are defined by [REST API §11](rest-api.md#11-health--diagnostics). This section is the operator-facing reading of that contract; where the two ever differ, rest-api wins.

All three endpoints are served at the **root** — `/health`, `/ready`, `/info` — not under `/api/v1` and not under `/actuator`. Probe configuration (k8s `livenessProbe`/`readinessProbe`) targets those paths directly.

### 6.1 `/health`

Liveness probe. Returns `200 OK` with the service status. No auth required.

```json
{
  "status": "UP",
  "version": "1.2.3",
  "components": {
    "database": "UP",
    "redis": "UP",
    "h2_factory": "UP"
  }
}
```

- `version` is top-level, not a component.
- Component keys are `snake_case` and the set is exactly these three: `database` (metadata DB connectivity), `redis` (result store / idempotency / event log connectivity), `h2_factory` (can create a staging H2 instance).
- **No `diskSpace` component.** Boot's default disk-space indicator is disabled: nothing in this architecture writes to local disk (logs go to stdout §3.5, staging is in-memory H2, results live in Redis), so a disk-space signal would report on something the service does not depend on — and, being `DOWN` on a full container filesystem, would restart a perfectly healthy pod.

### 6.2 `/ready`

Readiness probe. Returns `200 OK` when the service can serve traffic (everything in `/health` plus warm-up complete), `503` otherwise.

Returns 503 during startup (until Spring Boot signals ready) and during shutdown (draining) — the drain window is where in-flight executions are given up to `execution-timeout-seconds` before stragglers are cancelled ([deployment.md](deployment.md), D7).

### 6.3 `/info`

Build info at the application-port root. No auth required. Key names are the contract:

| Key | Value | Presence |
|---|---|---|
| `version` | Build version string | Always |
| `build_time` | Build timestamp (ISO 8601 instant) | Always |
| `commit` | Commit hash, supplied at build time (`-Pdatapipelines.commit=<sha>`) | **Absent when not supplied** — never `"unknown"`. Clients must not assume the field exists; an operator correlating a deploy to a revision needs it true or absent, never plausibly wrong. |

Bare values only — no hostnames, no paths, same discipline as `/health` (§6.4).

### 6.4 Actuator security

Only `/health`, `/ready` and `/info` are exposed without auth on the application port — nothing under `/actuator` is routable there. `/actuator/health` (and, with the metrics registry, `/actuator/prometheus`) is exposed on the separate management port only (§4.2), confined to the cluster network by deployment topology; every other actuator endpoint is disabled. Health output carries no credential, hostname or JDBC URL — component values are bare `UP`/`DOWN` strings precisely so an unauthenticated probe surface cannot leak topology (§9.2).

---

## 7. Audit Log

Already covered in [Auth spec §10](auth.md#10-audit-log) (event catalog §10.1, log shape §10.2). The audit log is **separate from the general application log**:

- Append-only table in the metadata DB.
- Structured events (auth events, admin actions) — the catalog in Auth §10.1 is authoritative; there are no password or lockout events, because authentication is OIDC-only.
- Retention governed by `datapipelines.audit.retention-days` ([configuration.md](configuration.md)).

The audit log captures **who did what when** for compliance / forensic purposes. The general log captures **what happened in the system** for debugging.

---

## 8. Error Reporting

### 8.1 Uncaught exceptions

Uncaught exceptions in any thread / coroutine:
- Logged at ERROR with full stack trace, correlation ID, and (where applicable) `execution_id` / `node_id`.
- Counted by `datapipelines.errors.total{domain}` — **one tag only**. `domain` is the first segment of the error code that the failure maps to (`pipeline`, `template`, `datasource`, `auth`, `result`, `rate_limit`, `idempotency`; `internal` for a failure with no mapped code), per the `{domain}.{entity}.{failure}` scheme in [enums §16](enums.md).
- Exception class and method are deliberately **not** tags: both are unbounded, code-shape-derived dimensions that §4.3 forbids. That detail is already carried by the ERROR log line's stack trace and the trace span's exception event, which are the right places to search it.
- For pipeline-execution coroutines, fails the execution with the mapped `pipeline.*` code ([dag-executor §8.2](dag-executor.md)); a cancelled execution is `execution_aborted`, counted by `datapipelines.executions.aborted{reason}` (§4.1), not by `errors.total`.

### 8.2 External error reporting (optional)

- Sentry / Bugsnag / Rollbar integration via Spring Boot's `spring-boot-starter-actuator` + vendor SDK.
- Configured per deployment (disabled by default in v1).

---

## 9. Configuration & Redaction

> Inbound links land here from [MCP Server §6.3](mcp-server.md#63-tool-result-schema); correlation-ID propagation itself is specified in **§3.3**.

### 9.1 Configuration keys

[configuration.md](configuration.md) is the single authority for config keys — YAML path, env var, default and description all live there (D8). This spec **references keys by name and never restates a default.** The observability keys are defined in [configuration.md §3.14](configuration.md#315-observability):

| Key | What it controls here |
|---|---|
| `datapipelines.observability.logging.format` | `json` (§3.1) vs human-readable console (§3.5, dev) |
| `datapipelines.observability.tracing.enabled` | Whether the OpenTelemetry SDK and exporter are wired at all (§5) |
| `datapipelines.observability.tracing.endpoint` | OTLP collector endpoint (§5.3) — the standard `OTEL_EXPORTER_OTLP_ENDPOINT` env var |

Two families deliberately have **no** datapipelines-namespaced key, because their frameworks already own the surface and duplicating it would create a second authority:

- **Log levels** — Spring Boot's own `logging.level.*` (e.g. `logging.level.co.datapipelines.dag=DEBUG`). §3.2 states which levels carry which meaning.
- **Actuator/Prometheus exposure and path** — Spring Boot's `management.*` (§4.2).

Sampling rates (§5.4) are set with the OpenTelemetry SDK's own `otel.traces.sampler.*` properties.

### 9.2 Redaction — normative

Redaction is **not configurable and not opt-in**. There is no `redaction.enabled` key: a switch that can turn secret-scrubbing off is a switch that will be off in some deployment. The mechanism is two layers inside the logging pipeline, so that **no logger call site can bypass it** — a developer cannot leak a secret by choosing the wrong logging idiom, only by inventing a key name that is not on the list.

1. **Field filter in the JSON encoder.** `logstash-logback-encoder` is configured with a field-name filter over structured fields (MDC entries, key-value pairs, `StructuredArguments`, and marker-attached objects). Any field whose key matches the sensitive-key list is emitted as `"***"` — the key is kept (its presence is itself diagnostic), the value never is.
2. **A `MessageConverter` over the rendered message.** Registered in `logback-spring.xml` in place of the stock `%message`/`%msg` converter, it scans the formatted message text for `key=value` and `"key": "value"` occurrences of the same list and rewrites the value to `***`. This catches the case the field filter cannot: a secret interpolated into a message string or arriving inside an exception message (a driver's `SQLException` quoting the JDBC URL is the realistic one).

Both layers read the same **sensitive-key list** (case-insensitive, matched on the whole key and on `*_<key>` / `<key>_*` compounds):

| Key | Why |
|---|---|
| `password` | Datasource credentials, OIDC client secrets |
| `secret` | JWT secret, OIDC `client-secret` |
| `api_key` | `dpk_...` plaintext key, only ever legitimately returned once at creation |
| `authorization` | Bearer / `DP-API-Key` header values |
| `jdbc_url` | Carries credentials inline for several drivers, and leaks internal topology even when it does not |
| `encryption_key` | `DATAPIPELINES_DB_ENCRYPTION_KEY` |

**Never redacted, by design:** `correlation_id`, `execution_id`, `pipeline_id`, `node_id`, `datasource_name`, `user_id`. These are the fields that make an incident diagnosable; scrubbing them would defeat the purpose of structured logging.

**Row values are never logged at all** — not at INFO, and at TRACE only under the per-row diagnostic logger that §3.2 marks as disabled in production. Redaction is a backstop for accidents, not a licence to log result data.

### 9.3 Redaction beyond logs — error payloads

The same secrecy rule binds anything that leaves the process, not just log output. **Normative:** an error `details` map must never carry `jdbc_url`, a password, or a raw connection string — in an SSE event payload, in a REST error envelope, or in an MCP tool result. Failures that are *about* a datasource identify it by `datasource_name` and, where useful, an `underlying_error` string that has itself passed the redaction list.

[REST API §6.4.4 `node_failed`](rest-api.md#644-node_failed) already complies: its `details` carries `datasource_name` + `underlying_error` and no URL. The rule is stated here so the next event type added does not have to rediscover it.

This is a construction rule, not a filter — the redacting encoder covers logs, and error envelopes are built by hand, so the two must be kept honest independently.

---

## 10. What's Out of Scope for v1

- **Distributed tracing across pipelines** (e.g., tracing a pipeline execution that triggers another pipeline) — v1 has no pipeline-to-pipeline calls, so not needed.
- **Custom dashboards** — operator responsibility (Grafana templates provided as documentation, not shipped code).
- **Per-tenant metrics** — single-tenant v1.
- **Real-time alerting** — operator responsibility (Alertmanager / equivalent).

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 draft | initial draft | Initial observability spec sketch — logs, metrics, traces, health, audit log |
| 2026-08-07 | v1.1 draft | consistency campaign | Applied [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) §2.14. **[M]** §6 health payload/paths realigned to the canonical [rest-api §11.1](rest-api.md#111-health-check) — root-level `/health`,`/ready`,`/info`, top-level `version`, snake_case components `{database, redis, h2_factory}`, `diskSpace` removed with rationale. **[M]** Stale metrics purged: `auth.login.attempts{outcome=locked}` dropped (OIDC-only, no local passwords, no lockout) with outcomes remapped to the auth §10.1 audit events; `datapipelines.http.server.requests` → `http.server.requests` (Spring Boot's own unprefixed metric) plus a rule on which metrics keep framework names. **[D9]** Result/SSE/idempotency metrics added: `result.bytes_written`, `result.writes{outcome}`, `result.cursor.reads{format,outcome}`, `result.expiries`, `result.size`, `sse.streams.active`, `sse.stream.duration{close_reason}`, `idempotency.cache.hits`, `idempotency.conflicts`. **[D7]** `executions.aborted{reason}` registered (matches dag-executor §15.3). **[M]** §8.1 `errors.total{class, method}` → `{domain}`, with §4.3 gaining the normative closed-set tag rule that forbids code-shape tags. **[M]** §9 rewritten as Configuration & Redaction: local YAML block deleted (config keys now referenced from [configuration.md §3.14](configuration.md#315-observability) per **D8**), redaction respecified as a non-optional two-layer mechanism (JSON-encoder field filter + `MessageConverter`) over an explicit sensitive-key list, plus §9.3 forbidding `jdbc_url`/credentials in error `details` across SSE, REST and MCP. **[D10]** `X-Correlation-Id` → `DP-Correlation-Id`. **[M]** Correlation propagation past the HTTP boundary made normative — echoed in every SSE event payload, `_meta` on MCP results ([mcp-server §6.3](mcp-server.md#63-tool-result-schema)). **[M]** Cross-ref fixed: audit log → [auth §10](auth.md#10-audit-log) (was §9). Draft status kept honest: dashboards/alerting/SLOs still to be elaborated, but §3.3, §4.1/§4.3, §6 and §9.2 are marked normative (§1). |
