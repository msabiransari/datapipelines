# Observability Specification

**Status:** v1 draft (deferred — to be elaborated before production hardening)
**Owner:** datapipelines.co core
**Depends on:** all other specs
**Last updated:** 2026-08-05

---

## 1. Purpose

Observability for datapipelines.co covers **logs**, **metrics**, **traces**, and **health endpoints** — the signals an operator uses to detect, diagnose, and resolve issues in production.

This spec is **lighter than the others** because observability is cross-cutting and mostly follows industry-standard patterns. It documents the specific decisions made for v1 and the conventions modules follow.

---

## 2. Design Principles

1. **Structured logs only.** Every log entry is JSON with stable field names. No `println`, no ad-hoc string formatting in logs.
2. **Correlation IDs everywhere.** Every request carries a correlation ID through every log line, metric tag, and trace span. Pipeline executions extend this to per-node correlation.
3. **Metrics via Micrometer.** Spring Boot's default micrometer integration; no custom metrics framework.
4. **Tracing via OpenTelemetry.** Vendor-neutral; instrumented into every module.
5. **Health endpoints for orchestrators.** `/health` and `/ready` for k8s liveness/readiness probes.
6. **No sensitive data in logs.** Passwords, API keys, JDBC URLs with credentials, query result values — never logged.

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

- Set on inbound request from `X-Correlation-Id` header, or generated if absent.
- Stored in MDC at request start, cleared at request end.
- Propagated through async work via Spring's `TaskDecorator` (for thread pools) and Kotlin coroutine context (for `Dispatchers.IO` work).
- Included in every log line in the request's call tree.
- Pipeline executions add `execution_id` and propagate it to every node-execution log.

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

All metrics prefixed with `datapipelines.`. Examples:

| Metric | Type | Tags | Description |
|---|---|---|---|
| `datapipelines.executions.total` | counter | `status` (success/failed/aborted), `pipeline_id` | Execution count |
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
| `datapipelines.http.server.requests` | timer | `method`, `uri`, `status` | HTTP request duration (Spring Boot default) |
| `datapipelines.mcp.tool.calls` | counter | `tool_name`, `status` | MCP tool invocations |
| `datapipelines.auth.login.attempts` | counter | `outcome` (success/failure/locked) | Login attempts |
| `datapipelines.auth.api_key.validations` | counter | `outcome` (success/invalid/expired/revoked) | API key validations |

### 4.2 Exposure

- Micrometer's Prometheus endpoint at `/actuator/prometheus` (or similar for other backends).
- Spring Boot Actuator enabled; only `/health`, `/ready`, `/info`, `/prometheus` exposed by default.
- Other backends (Datadog, New Relic, CloudWatch) selectable via Micrometer registry config.

### 4.3 Cardinality discipline

High-cardinality tags avoided:
- ❌ Don't tag by `user_id`, `execution_id`, `correlation_id`.
- ✅ Do tag by `pipeline_id`, `node_id`, `datasource_name`, `template_id` (bounded counts).
- Per-execution metrics live in the execution record (database), not in the metrics system.

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

### 6.1 `/health`

Liveness probe. Returns 200 if the JVM is running.

```json
{
  "status": "UP",
  "components": {
    "db": "UP",                       // metadata DB connectivity
    "redis": "UP",                    // Redis (claim-check cache) connectivity
    "h2Factory": "UP",                // can create H2 instances (sanity check)
    "diskSpace": "UP"
  }
}
```

### 6.2 `/ready`

Readiness probe. Returns 200 if the service can serve traffic (everything in `/health` plus warm-up complete).

Returns 503 during startup (until Spring Boot signals ready) and during shutdown (draining).

### 6.3 `/info`

Build info: version, commit hash, build timestamp. No auth required.

### 6.4 Actuator security

Only `/health`, `/ready`, `/info`, `/prometheus` exposed without auth. All other actuator endpoints either disabled or behind admin scope.

---

## 7. Audit Log

Already covered in [Auth spec §9](auth.md#9-audit-log). The audit log is **separate from the general application log**:

- Append-only table in the metadata DB.
- Structured events (auth events, admin actions).
- Retained per deployment policy (default: 1 year).

The audit log captures **who did what when** for compliance / forensic purposes. The general log captures **what happened in the system** for debugging.

---

## 8. Error Reporting

### 8.1 Uncaught exceptions

Uncaught exceptions in any thread / coroutine:
- Logged at ERROR with full stack trace.
- Reported via Micrometer's `datapipelines.errors.total{class, method}` counter.
- For pipeline-execution coroutines, fails the execution with `pipeline.execution.aborted`.

### 8.2 External error reporting (optional)

- Sentry / Bugsnag / Rollbar integration via Spring Boot's `spring-boot-starter-actuator` + vendor SDK.
- Configured per deployment (disabled by default in v1).

---

## 9. Configuration

```yaml
datapipelines:
  observability:
    logging:
      format: json                    # json | console
      level: INFO                     # root level
      levels:
        co.datapipelines: INFO
        co.datapipelines.dag: DEBUG   # finer detail on executor
        org.springframework: WARN
      redaction:
        enabled: true                 # scrub known-sensitive patterns
        patterns:
          - password
          - api_key
          - jdbc_url_password
    metrics:
      enabled: true
      prometheus:
        enabled: true
        path: /actuator/prometheus
    tracing:
      enabled: false                  # off by default; opt-in
      exporter: otlp
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT}
      sampling:
        general: 0.1
        pipeline-execution: 1.0
```

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
