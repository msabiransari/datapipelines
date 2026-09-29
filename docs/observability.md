# Observability Specification

**Status:** v1.22 draft (to be elaborated before production hardening — the rules marked **normative** below are already binding)
**Owner:** datapipelines.co core
**Depends on:** all other specs
**Last updated:** 2026-09-29

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
| `WARN` | Degraded operation (rate-limited request, retry attempted, type-mapping fallback to STRING). The rate limiter's own unavailability is logged **once per outage per instance** — on the healthy → unavailable transition, with an `INFO` when Redis answers again — never once per refused request: at the full request rate that line is a second outage, in the log pipeline. Requests refused meanwhile answer `429 rate_limit.unavailable` ([REST API §12.3](rest-api.md#123-when-the-limiter-itself-is-unavailable)). |
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
| Composition | A child execution spawned by a `PIPELINE` node inherits its parent's correlation ID — it is **not** regenerated per execution. One `SELECT … WHERE correlation_id = ?` over `pipeline_executions` therefore returns the whole family, and every descendant's logs and SSE payloads quote the same ID as the request that started it ([Pipeline Contract §8.5](pipeline-contract.md#85-pipeline-nodes)). `root_execution_id` joins one family; correlation ID joins everything one *request* caused, which is the wider question an operator actually starts from. |

### 3.4 What's logged per module

| Module | Key log events |
|---|---|
| `auth` | login success/failure, key issuance/revocation (via audit log, not general log); the §3.4B mail events |
| `datasources` | datasource registered/updated/deleted, pool built/retired/reconciled, connection acquisition failures, and the §3.4A pool hard-close WARN |
| `staging` | H2 instance created/closed, staging operation success (table name + row count), memory-limit warnings |
| `dag` | execution started/completed/failed, node started/completed/failed, cancellation |
| `templates` | template registered (id + version), render failures |
| `mcp-server` | tool calls (tool name + caller), transport errors |
| `web` | request log (method, path, status, duration), CORS preflight, SSE connections opened/closed |

#### 3.4A The pool-retirement events (094)

A datasource that is edited or deleted has its pool RETIRED, not closed on the spot: it leaves the live map immediately, stops handing out connections, and closes once the statements already running on it finish ([Datasources §5.2](datasources.md#52-pool-lifecycle)). Four events name that lifecycle, and the third is the only one that is not routine.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `datasource.pool_invalidated_remotely` | A peer instance saved or deleted the datasource and this instance retired its pool on the message | `datasource`, `origin` |
| INFO | `datasource.pool_reconciled` | The (re)subscription comparison found a pool built from a row that has since changed or gone, and retired it | `datasource`, `reason` |
| **WARN** | **`datasource.pool_hard_closed`** | **The retirement ceiling fired while connections were still out — the pool was closed anyway and those statements lost their connection** | `datasource`, `active_connections`, `ceiling_seconds` |
| INFO | `datasource.pool_reconcile_on_subscribe` | The invalidation channel was (re)subscribed and the reconcile ran | `channel`, `retired` |

**The WARN is the one to alert on.** Every other line is the mechanism working. `pool_hard_closed` means a statement outlived `datapipelines.datasources.retire-ceiling-seconds` ([Configuration §3.26](configuration.md#326-datasource-pools)) — either a genuinely hung query, or a ceiling set below what this deployment's statements really take. It carries the number of connections it took down, which the matching `datapipelines.datasource.pool.hard_closed` counter deliberately does not (an unbounded tag value, §4.3).

#### 3.4B The mail events (137)

The notices of [Auth §5A.8](auth.md#5a8-mail-the-welcome-mail-and-the-new-user-notice) leave two kinds of trace: the AUDIT rows `mail.sent` / `mail.failed` ([Enums §15](enums.md#15-authauditevent--auth-audit-log-events)), and these structured log lines. None carries an address beyond its domain, and none ever carries a body.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `mail.configured` / `mail.disabled` | Boot: which `MailSender` was wired — the transport (host, port, starttls, the from DOMAIN, the ops-to COUNT, whether a stream header is set) or the no-op | see When |
| INFO | `mail.skipped` | Mail is not configured and a notice was asked for — one line per skipped send, the no-op sender's only output | `kind`, `domain` |
| INFO | `mail.accepted` | The transport accepted a notice (the `mail.sent` audit row's log twin) | `kind`, `domain`, `message_id` |
| DEBUG | `mail.already_claimed` | A second attempt for one message identity found the claim row and stopped — the "never twice" rule working | `kind`, `user`, `act` |
| **WARN** | **`mail.send_failed`** | **The transport refused or failed a notice; the same error is on the `mail_sends` row and in the `mail.failed` audit row** | `kind`, `user`, `error` |
| WARN | `mail.send_retry` | A connect failure (nothing delivered) is being retried in place (158, #121 — bounded: 3 attempts); the row is marked from the FINAL outcome | `kind`, `attempt`, `error` |
| WARN | `mail.dispatch_failed` | The pool task itself threw (a claim-row update failed, not the transport) | `kind`, `user`, `error` |

**`send_failed` is the one to alert on** — a welcome mail that failed is a user who cannot log in until an admin resets. The admin screen shows the failure too; this line is for the operator who watches logs rather than screens.

#### 3.4C The LAKE instance events (152)

A `LAKE` datasource's pool owns one embedded DuckDB instance per pool generation ([Datasources §5.2](datasources.md#52-pool-lifecycle)): a retained owner connection is opened at pool build, initialized once, and released after the Hikari pool has shut down. Nine events name that lifecycle and its failures; the ERROR is the one that means a pool needs rebuilding, and the WARNs are what the operator should read.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `lake.instance_opened` | A pool generation opened and initialized its instance owner — one per pool build | `datasource`, `generation` |
| INFO | `lake.instance_closed` | The generation's release completed, after the Hikari pool's shutdown; `retained_owner_closed` says whether the driver actually closed the retained owner connection (`false` ⇒ a `lake.instance_owner_close_failed` WARN preceded it) — the instance is freed once the last handle drops | `datasource`, `generation`, `retained_owner_closed` |
| **ERROR** | **`lake.instance_owner_lost`** | **The retained owner connection is closed while the pool is still live (a driver fault, not a retire); every new lease is refused with SQLSTATE `08003` until the pool is rebuilt — logged once per generation** | `datasource`, `generation`, `message` |
| WARN | `lake.instance_owner_close_failed` | The driver refused to close the retained owner connection at release — an `SQLException` or a nonfatal `RuntimeException`, contained the same way; one attempt, no retry, nothing propagates (the release runs inside the shared manager's loop over every pool, and one lake's driver fault must not stop the next pool's cleanup); the physical connection is the driver's residual, reported once | `datasource`, `generation`, `error`, `message` |
| WARN | `lake.instance_handle_exhausted` | A physical connection refused every close the ownership protocol permits — its holder's own attempt, the generation's one attempt, or the one hand-off retry — and no actor is left: it stays registered and counted in `liveDuplicates` as the generation's residual. Logged by whichever actor made the last attempt, with the driver's error | `datasource`, `generation`, `attempts`, `error`, `message` |
| DEBUG | `lake.instance_refused_duplicate_close_failed` | A duplicate created just as its generation retired was refused at registration and its creator's close of it failed; the handle stays registered — the release (if it has not run) or the creator's own retry (if it has) takes it, and exhaustion is reported by `lake.instance_handle_exhausted` | `datasource`, `generation`, `error` |
| WARN | `lake.instance_handles_closed` | At the generation's release, physical connections were still registered to it — ones HikariCP never accepted (their creation straddled the shutdown and the closed bag refused them), abandoned to a borrower at its shutdown ceiling, or whose earlier close the driver refused — and the generation released them. The counts distinguish what happened: `closed` (the generation's own close was confirmed), `already_closed` (found closed by something the wrapper never saw, e.g. an executor-driven abort), `in_flight` (a borrower's close was mid-flight on another thread; the last word was handed to it), `close_failures` (the driver refused — the honest residual, still registered) | `datasource`, `generation`, `handles`, `closed`, `already_closed`, `in_flight`, `close_failures`, `error` (the last driver refusal, empty when none), `message` |
| WARN | `lake.view_failed` | One registered table's view could not be created at instance init (109 §A); it is recorded on the table's registry row and skipped, the surviving views serve | `datasource`, `table`, `error`, `message` |
| WARN | `lake.view_outcome_record_failed` | The registry write of a view outcome threw; the instance still serves the surviving views, the row keeps its previous state | `datasource`, `table`, `error`, `message` |

**`instance_owner_lost` is the one to alert on**: it is a datasource whose engine went away underneath a live pool, and it does not self-heal — retire-and-rebuild (a datasource save, a table registration, or the reconcile) is the remedy, and the message says so. `view_failed` is the existing per-table isolation working (the table's row carries the error; the UI shows it); a burst of them after a registry change is a bad location or credential, not the engine. Nothing here is an audit row.

#### 3.4D The promoter lens event (178)

A promoter sees only released, not-yet-promoted pipelines and templates ([Auth §11A.1](auth.md#11a1-the-404-rule)); deciding that needs the higher environment's inventory, read through a per-workspace cache (`datapipelines.deployment.promotion.inventory-cache-ttl-seconds`, [Configuration §3.19](configuration.md#319-deployment)). When the target cannot be read the lens FAILS CLOSED — every promoter read is empty, the screens say why — and one event says so per window.

| Level | `event=` | When | Fields |
|---|---|---|---|
| WARN | `pipeline.promotion.lens_unavailable` | The lens's inventory probe for a workspace failed — target unreachable, malformed, refusing, or no target configured — and the failure is now cached for one TTL window, during which every lensed read in that workspace answers nothing. Logged on the PROBE, never on the cached answers, so it is once per window by construction; `reason` is the transport class or configuration reason (`ConnectException`, `malformed_response`, `no_target_configured`), never the key | `target` (base URL), `reason`, `workspace`, `code` (the §13 code the promotion page would have shown) |

Sustained repeats every window mean the sender's promotion target is down or misconfigured: promoters see empty lists everywhere until it is back, and the promotion page shows the same code. Not an audit row.

#### 3.4E The scheduler events (#9)

The scheduler ([Scheduler](scheduler.md)) logs its own work under `scheduler.*`. A person's actions on a schedule are AUDIT rows ([Enums §15](enums.md#15-authauditevent--auth-audit-log-events), `schedule.*`), and every run keeps its own trail in the database — these lines are for the operator watching the instance, never the history of record. Ids only; never a payload or a parameter value.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `scheduler.started` | This instance began dispatching, once the application is ready | `threads`, `tick_seconds` |
| INFO | `scheduler.api_mode` | `datapipelines.scheduler.enabled=false`: this instance serves the REST routes but never dispatches | — |
| INFO | `scheduler.dispatched` | A dispatcher tick recorded something (silent otherwise) | `queued`, `catch_up`, `missed`, `overlap` — the counts of that tick |
| INFO | `scheduler.reconciled` | A reconciler tick moved at least one run (silent otherwise) | `changed`, `watched` |
| INFO | `scheduler.run_not_started` | A run waited out its lateness window for a capacity slot and ended `not_started` / `capacity` | `run_id`, `reason`, `attempts` |
| WARN | `scheduler.schedule_blocked` | A schedule became blocked — an `unknown` run, or an executor refusal that asks for it; nothing fires from it until a person unblocks it | `schedule_id`, `run_id`, `reason` |
| ERROR | `scheduler.start_failed` | The executor's launch threw — the run is `unknown` / `start_failed` and its schedule blocked, because nobody can say whether work began | `run_id`, `execution_id`, `message` |
| WARN | `scheduler.start_refused` | The launch path refused BEFORE the execution's record existed — the run is `not_started` / `start_refused` and blocks (#253). The run's trail carries only the catalogued code; this line is where the raw exception stays | `run_id`, `execution_id`, `message` |
| ERROR | `scheduler.inspect_refused` | The system identity was refused `execution.read` in a workspace — the reconciler cannot see outcomes there, and its runs stay `running` until it can. A configuration defect: the identity's fixed set is code, not data | `workspace_id` |
| INFO | `scheduler.admission_closed` | Shutdown: admission closed and no launch was in progress, so the execution drain may start | — |
| WARN | `scheduler.admission_wait_expired` | Shutdown: launches were still in progress when `shutdown-wait-seconds` ran out; their runs reconcile from the execution record | `launching`, `wait_ms` |

A `scheduler.schedule_blocked` needs a person. A steady `scheduler.run_not_started` means `max-concurrent-runs` is too low for this instance's schedules, or runs are too long for their cadence — `datapipelines.scheduler.capacity.retries` (§4.1) is the leading signal.

#### 3.4F The legacy endpoint events (#274, #286)

A `published_endpoints` row whose stored path fails today's grammar is RETIRED, never fatal ([Metadata DB §4.13](metadata-db.md#413-published_endpoints)): never served, listed flagged, unpublishing it is the fix. Two lines say so to an operator; both carry a COUNT, never a path.

| Level | `event=` | When | Fields |
|---|---|---|---|
| WARN | `endpoint.legacy_rows` | Once per JVM, on the first repository query that meets a legacy row. `at_least` is THAT query's count — a floor, not the deployment's total (after V41 the registry warm-up reads enabled rows only, so the first sighting is usually one workspace's listing) | `at_least` |
| WARN | `endpoint.promotion_legacy_omitted` | A promotion batch was built and legacy rows over its promoted pipelines were left out — the target's grammar would refuse them | `count` |

Neither is an alert: the API console lists every legacy row with its reason. A steady `promotion_legacy_omitted` means an endpoint that will not reach the target until someone republishes it at a legal path.

#### 3.4G The persistence events (#266)

The batching writers in front of the audit log, the execution-event record and the replay log ([DAG Executor §10.1](dag-executor.md#101-how-an-emitted-event-becomes-durable-266), [Auth §10.1A](auth.md#101a-delivery-durable-before-log-returns-266), [Configuration §3.32](configuration.md#332-persistence-batching-266)) log under `persistence.*`; `writer` is the store (`audit`, `execution_events`, `replay_log`). An item is named by its ids only (`item`, `failed_items`: execution and event id, or audit event name, user id and key id) — never a payload or `details` (§9.2).

| Level | `event=` | When | Fields |
|---|---|---|---|
| WARN | `persistence.batch_retried` | A batch threw; it was retried one item at a time, in order | `writer`, `batch`, `committed`, `failed`, `failed_items` (each with its kind), `cause`, `sql_state` |
| WARN | `persistence.write_failed` | A batch of ONE item threw (no retry is possible) | `writer`, `kind`, `item`, `cause`, `sql_state` |
| WARN | `persistence.direct_write_failed` | A caller's own write (after `record-max-wait-ms`, a full queue or shutdown) threw | `writer`, `kind`, `item`, `cause`, `sql_state` |
| WARN | `persistence.indeterminate` | The emitter stopped waiting after `2 × record-max-wait-ms` while a write of the item was still running — it may yet land | `writer`, `items`, `wait_ms` |
| WARN | `persistence.direct_write_abandoned` | The emitter's direct write never started within its bound and never will — the item is NOT written | `writer`, `item` |
| WARN | `persistence.saturated` | The queue is full: callers are writing directly, `submit` is refusing. At most once per 10 s per writer — a state, not an event per item | `writer`, `queued`, `max_events`, `queued_bytes`, `max_bytes` |
| ERROR | `persistence.writer_died` | A writer thread ended on an `Error`; its batch was failed so no caller waits on it, and its partition's later items are written directly by their callers | `writer`, `partition` |
| DEBUG | `audit.write` | Every audit row, the path it takes: `transactional` (inside the caller's transaction, on its connection), `batched` (the audit writer), `direct` (no writer — the default, `datapipelines.persistence.audit.enabled: false`, or `enabled: false`) | `audit_event`, `path` |
| INFO | `shutdown.persistence_drain_started` | Shutdown, after the web server's graceful drain: every writer starts its bounded drain, in parallel | `writers` |
| INFO | `persistence.drained` / `shutdown.persistence_drained` | A writer flushed everything it held | `writer` (`lost`, `in_flight` on the second) |
| WARN | `persistence.drain_incomplete` | A writer's drain reached `shutdown-drain-ms` with items left: `lost` were never written (only `submit` items can be — nothing awaits them), `in_flight` were inside a commit and may or may not have landed | `writer`, `lost`, `in_flight`, `drain_ms` |

A failure is named by `cause` (the exception's simple class name) and `sql_state` (the first SQLState in its cause chain, `none` without one) — never by the exception's message or its cause chain, which a store fills with the refused row (Postgres's DETAIL and CONTEXT; PgJDBC's batch exception quotes the bound values). `23xxx` is a row the store refuses, `08xxx` a connection lost (#266b). The emitter and the audit sink keep their own lines too — `Durable event … not written (kind)`, `SSE event log append failed … (replay will be incomplete)`, `audit_log write failed … kind=…` (on the direct path `… cause=… sql_state=…`) — the ones operators have always searched for. A steady `persistence.saturated` or a rising `datapipelines.persistence.fallbacks{reason=timeout}` (§4.1) means the store cannot keep up; `batch_retried` with `kind=poison` means rows the store refuses, which no retry will fix.

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
| `datapipelines.executions.lifecycle_write_failed` | counter | (none) | (#311) The terminal UPDATE of `pipeline_executions` failed, outlived its bound, or matched no row (the RUNNING insert abandoned or never landed — #325) (`datapipelines.executor.lifecycle-write-timeout-seconds`, [Configuration §3.2](configuration.md#32-executor)): the row is left RUNNING for the stale sweep to reap, so this counter is how an operator tells an unrecorded outcome apart from silence. The companion WARN names the execution id. The RUNNING insert's failure is deliberately NOT counted here — for scheduled runs it is the fail-closed refusal the scheduler records (`record_unwritable`), for interactive ones a WARN — the counter exists because a terminal row that stays RUNNING otherwise looks exactly like a dead instance |
| `datapipelines.nodes.duration` | timer | `pipeline_id`, `node_id`, `source` | Per-node duration |
| `datapipelines.nodes.rows_out` | counter | `pipeline_id`, `node_id` | Rows emitted by node |
| `datapipelines.staging.rows` | counter | (none) | Total rows staged across all executions |
| `datapipelines.staging.bytes` | counter | (none) | Total bytes staged |
| `datapipelines.staging.active_tables` | gauge | (none) | Current H2 tables across all in-flight executions |
| `datapipelines.datasource.pool.active` | gauge | `datasource_name` | Active connections in pool |
| `datapipelines.datasource.pool.pending` | gauge | `datasource_name` | Threads waiting for a connection |
| `datapipelines.datasource.pool.timeout_total` | counter | `datasource_name` | Pool-acquisition timeouts |
| `datapipelines.datasource.pool.retired` | counter | `datasource_name` | Pools taken out of the live map by an edit, a delete, a peer's invalidation message or a reconcile ([Datasources §5.2](datasources.md#52-pool-lifecycle)). One increment per retirement, not per connection |
| `datapipelines.datasource.pool.hard_closed` | counter | `datasource_name` | Retired pools closed at the `retire-ceiling-seconds` ceiling with connections **still out** — a statement outlived the ceiling and lost its connection. A subset of `retired`; a non-zero rate means either a genuinely hung query or a ceiling set below what this deployment's statements really take. Each one also logs the §3.4A `datasource.pool_hard_closed` WARN |
| `datapipelines.templates.render.duration` | timer | `template_id`, `template_version` | Template render time |
| `datapipelines.templates.cache.hits` | counter | (none) | Template cache hits |
| `datapipelines.templates.cache.misses` | counter | (none) | Template cache misses |
| `http.server.requests` | timer | `method`, `uri`, `status`, `outcome` | HTTP request duration. **Spring Boot's own metric — unprefixed.** `uri` is the templated path (`/api/v1/executions/{id}`), never the expanded one. |
| `datapipelines.mcp.tool.calls` | counter | `tool_name`, `status` | MCP tool invocations |
| `datapipelines.promotion.lens.inventory` | counter | `outcome` (`hit`/`miss`/`unreachable`) | The promoter lens's inventory reads (178, §3.4D): `hit` served from the per-workspace cache window, `miss` probed the target and got an inventory, `unreachable` probed and failed (the failure is then cached for the window, so `unreachable` counts windows, not reads). A steady `unreachable` rate with no `miss` is a dead target; `hit`/`miss` ≫ 1 is the cache doing its job |
| `datapipelines.auth.login.attempts` | counter | `outcome` (`success`/`domain_not_allowed`/`user_inactive`/`oidc_error`) | Login attempts. Outcomes mirror the audit events in [Auth §10.1](auth.md#101-events). There is **no** lockout outcome: authentication is OIDC-only, the product stores no local passwords, and no lockout mechanism exists to count. |
| `datapipelines.auth.api_key.validations` | counter | `outcome` (success/invalid/expired/revoked) | API key validations |
| `datapipelines.auth.login_rate_limit.saturated` | counter | (none) | Login rate-limit admissions made with the tracked-client table FULL — the limiter's fail-open branch ([Auth §11.5](auth.md#115-other-auth-configuration-keys)). The table is bounded at 10,000 client addresses; past that a new client is admitted unmetered rather than the map grown, so a spoofed-IP flood cannot exhaust the heap. Registered eagerly, so a healthy deployment reports `0` rather than an absent series. Any sustained non-zero rate means the login surface is unmetered for new clients right now. No tags: it is one closed condition, and §4.3 forbids inventing a dimension that is not a bounded set |
| `datapipelines.audit.retention.purged` | counter | (none) | `audit_log` rows deleted by the audit-log retention job (§7, #310) — the sum of every tick's `purged=`, a batch that failed mid-tick included (its committed rows are gone). Registered when the job is built, so a deployment reports `0` rather than an absent series. The steady-state rate is roughly the write rate a retention window ago; a flat line on a deployment older than `datapipelines.audit.retention-days` means the job is not running |

**Result delivery, SSE and idempotency** ([REST API §7](rest-api.md#7-result-delivery), D9):

| Metric | Type | Tags | Description |
|---|---|---|---|
| `datapipelines.result.bytes_written` | counter | (none) | Bytes materialized into the Redis result store. Paired with `result.max-size-bytes` — the ratio of writes near the cap is the signal that a deployment is using result delivery as a bulk-data path (an explicit NOT-goal). |
| `datapipelines.result.writes` | counter | `outcome` (`stored`/`too_large`/`storage_unavailable`) | Result-store write attempts; the failure outcomes correspond 1:1 to `result.too_large` and `result.storage_unavailable`. |
| `datapipelines.result.cursor.reads` | counter | `format` (json/arrow/csv), `outcome` (`hit`/`expired`/`not_found`) | Cursor endpoint reads. `expired` rising relative to `hit` means the TTL is set too low for how clients actually page. |
| `datapipelines.result.expiries` | counter | (none) | Results that reached TTL without ever being read past the inline first page. |
| `datapipelines.result.size` | distribution summary | (none) | Result size in bytes — percentiles inform `result.max-size-bytes` tuning. |
| `datapipelines.sse.streams.active` | gauge | (none) | Currently-open SSE execution streams |
| `datapipelines.sse.stream.duration` | timer | `close_reason` (`completed`/`failed`/`aborted`/`client_disconnect`/`revoked`/`expired`) | Lifetime of an SSE stream. `client_disconnect` here is what feeds the disconnect-grace cancellation path (D7). `revoked` (#230, security-assurance P4) is a stream cut by the subscriber-authority re-judgement — a policy cut whose execution deliberately KEEPS running, never a disconnect, so the cancellation path never sees it. `expired` (#263) is the same policy cut where the refusal was the subscriber's validated token passing its `exp` — the same final comment and the same kept-running execution, a different credential fact, so an expired token is never counted as a standing revocation or a disconnect. The timer covers the LIVE stream; the log-served streams (the §10.3 replay and the idempotent-retry follow) record no duration, and carry the same reason in their cut's log line instead — `close_reason=expired` or `close_reason=revoked`, from the one verdict that refused (#293). |
| `datapipelines.idempotency.cache.hits` | counter | (none) | Requests served from a stored idempotent response |
| `datapipelines.idempotency.conflicts` | counter | (none) | `idempotency.key_reused_for_different_request` rejections |

**Scheduler** (#9, [Scheduler](scheduler.md)) — the scheduler's state is watched here, not through a health component (§6.1):

| Metric | Type | Tags | Description |
|---|---|---|---|
| `datapipelines.scheduler.occurrences` | counter | `outcome` (`queued`/`catch_up`/`missed`/`overlap`) | Occurrences the dispatcher recorded: `queued` a run, `catch_up` a run under the `latest` policy, `missed` one summary row per outage (however many occurrences it covers), `overlap` an occurrence skipped because a run was still active. A flat line while schedules exist means no instance is dispatching |
| `datapipelines.scheduler.runs.finished` | counter | `state` (`succeeded`/`failed`/`cancelled`/`aborted`/`unknown`/`not_started`/`skipped`) | Runs reaching a final state. A run whose `unknown` is later settled by the real outcome counts once for each |
| `datapipelines.scheduler.capacity.retries` | counter | (none) | Admissions refused for capacity and retried 30 s later (R4). Rising means `max-concurrent-runs` is saturated |
| `datapipelines.scheduler.runs.in_flight` | gauge | (none) | Scheduled executions holding a capacity slot on this instance, at most `max-concurrent-runs` |

**Persistence batching** (#266, §3.4G) — one set per batching writer, every metric tagged `store` (`audit`/`execution_events`/`replay_log`), bound by `WebMetrics.bindPersistence`. The `audit` set is bound even while the audit writer is switched off (the default, #266b) and then reads zero — zero batches means the direct path, not a missing meter:

| Metric | Type | Tags | Description |
|---|---|---|---|
| `datapipelines.persistence.batch.size` | distribution summary | `store` | Items per committed batch (singles retried after a failed batch count as batches of one) |
| `datapipelines.persistence.batch.duration` | timer | `store` | The store's time to commit one batch |
| `datapipelines.persistence.lag` | timer | `store` | Per item, enqueue to durable — the time a caller waited for its own row |
| `datapipelines.persistence.queue.depth` | gauge | `store` | Items admitted and not yet finished (queued or inside a commit); bounded by `queue-max-events` |
| `datapipelines.persistence.queue.bytes` | gauge | `store` | The same in bytes; bounded by `queue-max-bytes` |
| `datapipelines.persistence.failures` | counter | `store`, `kind` (`poison`/`write_failed`/`indeterminate`/`abandoned`/`drain_lost`) | Items not written, or not known to be. `poison`: the store refused the row (skipped, its batch committed). `write_failed`: the store was unavailable. `indeterminate`: the emitter's bounded wait ended with the write still running. `abandoned`: the emitter's direct write never started. `drain_lost`: left at the shutdown drain's deadline |
| `datapipelines.persistence.fallbacks` | counter | `store`, `reason` (`timeout`/`saturated`/`stopped`) | Items a CALLER wrote itself: its batch took longer than `record-max-wait-ms`, the queue stayed full, or the writer had stopped |
| `datapipelines.persistence.batches.retried` | counter | `store` | Batches that threw and were retried one item at a time |
| `datapipelines.persistence.dropped` | counter | `store` | `submit` calls refused (queue full or stopped) — the only items ever dropped without a caller waiting; no production caller yet |

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
- **No scheduler component.** db-scheduler's own indicator is disabled (`management.health.db-scheduler.enabled: false`, [Configuration §3.29](configuration.md#329-scheduler-9)): it reports `DOWN` on an API-mode instance, which never starts the scheduler, and a stalled dispatcher is not a reason to restart a pod. Watch §4.1's scheduler metrics instead.
- **No `diskSpace` component.** Boot's default disk-space indicator is disabled: nothing in this architecture writes to local disk (logs go to stdout §3.5, staging is in-memory H2, results live in Redis), so a disk-space signal would report on something the service does not depend on — and, being `DOWN` on a full container filesystem, would restart a perfectly healthy pod.

### 6.2 `/ready`

Readiness probe. Returns `200 OK` when the service can serve traffic (everything in `/health` plus warm-up complete), `503` otherwise.

Returns 503 during startup (until Spring Boot signals ready) and during shutdown (draining) — the drain window is where in-flight executions are given up to `execution-timeout-seconds` before stragglers are cancelled ([deployment.md](deployment.md), D7).

### 6.3 `/info`

Build info at the application-port root. No auth required. Key names are the contract:

| Key | Value | Presence |
|---|---|---|
| `version` | Build version string | Always |
| `build_time` | Build timestamp (ISO 8601 instant), supplied at build time (`-Pdatapipelines.buildTime=<instant>`; `app.sh`'s image build passes the moment it builds) | **Absent when not supplied** — same rule as `commit`. A plain Gradle build no longer stamps `Instant.now()`: that made the app's jar new on every invocation and re-ran everything downstream of it (#292). |
| `commit` | Commit hash, supplied at build time (`-Pdatapipelines.commit=<sha>`) | **Absent when not supplied** — never `"unknown"`. Clients must not assume the field exists; an operator correlating a deploy to a revision needs it true or absent, never plausibly wrong. |

Bare values only — no hostnames, no paths, same discipline as `/health` (§6.4).

### 6.4 Actuator security

Only `/health`, `/ready` and `/info` are exposed without auth on the application port — nothing under `/actuator` is routable there. `/actuator/health` (and, with the metrics registry, `/actuator/prometheus`) is exposed on the separate management port only (§4.2), confined to the cluster network by deployment topology; every other actuator endpoint is disabled. Health output carries no credential, hostname or JDBC URL — component values are bare `UP`/`DOWN` strings precisely so an unauthenticated probe surface cannot leak topology (§9.2).

---

## 7. Audit Log

Already covered in [Auth spec §10](auth.md#10-audit-log) (event catalog §10.1, log shape §10.2). The audit log is **separate from the general application log**:

- Append-only table in the metadata DB.
- Structured events (auth events, admin actions) — the catalog in Auth §10.1 is authoritative; there are no password or lockout events, because authentication is OIDC-only.
- Retention governed by `datapipelines.audit.retention-days` ([configuration.md](configuration.md))
- **Delivery (#266).** A row is committed before `AuditEventSink.log` returns — by default the direct INSERT (on the caller's own connection inside its transaction); with `datapipelines.persistence.audit.enabled: true`, batched outside a transaction ([Auth §10.1A](auth.md#101a-delivery-durable-before-log-returns-266)). The same holds for every execution event ([DAG Executor §10.1](dag-executor.md#101-how-an-emitted-event-becomes-durable-266)).
- **The loss window, stated.** No awaited row — audit or event — is ever acknowledged before it is durable, so a crash cannot lose a row whose caller proceeded: a caller interrupted mid-write had not proceeded (for an execution, the outcome is "not completed", never "completed without a record"). The batching writers' non-awaited `submit` path is the one exception — its queue at the moment of a crash, and whatever the shutdown drain could not write by `shutdown-drain-ms` (`persistence.drain_incomplete`, `failures{kind=drain_lost}`) — and it has no production caller yet (the dashboard's refresh event, D27, will be its first).

**Retention (#310).** The retention sweep's last step (`AuditLogRetention`, hourly, [Auth §10.3](auth.md#103-retention), [Metadata DB §8.2](metadata-db.md#82-audit-log-retention)) deletes the rows older than the database's `NOW()` minus [`datapipelines.audit.retention-days`](configuration.md#312-audit) (30–3650, default 365), in batches of at most 5,000, at most 50 batches or 2 seconds a tick. A tick that deleted anything logs one INFO line `event=audit.retention purged=<n> cutoff=<iso> batches=<k>`; a tick that stopped with rows left logs a WARN `event=audit.retention_incomplete … reason=batch_ceiling|time_budget` and the next tick continues; a metadata-DB fault logs a WARN `event=audit.retention_failed purged=<n> batches=<k> cutoff=<iso> message=…` with what it had already committed. Counts and the cutoff only — never a row's content. The meter is `datapipelines.audit.retention.purged` (§4.1). A step of the sweep that throws anything else logs ERROR `event=retention.step_failed step=<execution_events|keys|audit_log>` and the tick continues with the next step.

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
| 2026-09-29 | v1.22 | the 306 merge's security pass (#311) | §4.1: `lifecycle_write_failed` also counts a terminal UPDATE that matched no row (#325); the emitter's lifecycle failure lines log class + SQLState at WARN (§3.4G), never the driver's message. |
| 2026-09-29 | v1.21 | 306/#311 (the execution row's order and bounds) — numbered after 310's v1.20 | §4.1 gains `datapipelines.executions.lifecycle_write_failed` (counter, no tags): the terminal UPDATE of `pipeline_executions` failed or outlived its bound (`datapipelines.executor.lifecycle-write-timeout-seconds`), the row is left RUNNING for the stale sweep, and the counter is the difference between that and a dead instance. The RUNNING insert's failure is deliberately uncounted here (fail-closed for scheduled runs, WARN for interactive). |
| 2026-09-29 | v1.20 | 310 (#310) audit-log retention — numbered after 297's v1.18 and 266b's v1.19 | §4.1 gains `datapipelines.audit.retention.purged` (counter, no tags). §7 gains the retention paragraph: the job, its three log events (`audit.retention` INFO, `audit.retention_incomplete` and `audit.retention_failed` WARN), the sweep's per-step `retention.step_failed` ERROR, and the meter — the "retention governed by" line was true of the documentation only until #310 |
| 2026-09-29 | v1.19 | 266 (#266) persistence batching, with its correction round 266b — renumbered from v1.18, which 297 takes | New **§3.4G the persistence events** (`persistence.batch_retried`, `write_failed`, `direct_write_failed`, `indeterminate`, `direct_write_abandoned`, `saturated` — at most once per 10 s — `writer_died`, `drained`, `drain_incomplete`, and the two `shutdown.persistence_*` lines); §4.1 gains the **persistence batching** table (`datapipelines.persistence.*`: batch size and duration, lag, queue depth and bytes, failures by `kind`, fallbacks by `reason`, retried batches, dropped submissions — every one tagged `store`); §7 states the delivery rule and the loss window (only the non-awaited `submit` path, no caller yet) and marks audit retention as documented-not-implemented. 266b: a failure is logged by `cause` (class) and `sql_state`, never by the store's message, which quotes the refused row; the audit writer ships off (`audit.enabled: false`), so `audit.write` reads `direct` by default and the `audit` meters read zero. |
| 2026-09-28 | v1.18 | 297 (#292) build time opt-in | §6.3's `build_time` becomes **absent when not supplied**, like `commit`: the build stamps a time only with `-Pdatapipelines.buildTime=<instant>` (`app.sh`'s image build passes it), because the plugin's default `Instant.now()` made the app's jar new on every Gradle invocation and re-ran its tests and the whole browser suite each time. |
| 2026-09-28 | v1.17 | 298 (#293) the log-served streams' cut | §4.1's `datapipelines.sse.stream.duration` row: the timer covers the live stream; the log-served streams (replay, idempotent-retry follow) record no duration and tag their cut's log line `close_reason=expired` or `close_reason=revoked` from the same verdict that refused (#271's one-judgement rule) — before, every log-served cut read as a revocation. |
| 2026-09-28 | v1.16 | 286 (#286) | New **§3.4F the legacy endpoint events**: `endpoint.legacy_rows` (#274's once-per-JVM WARN, its field now `at_least` — the first query's count is a floor) and `endpoint.promotion_legacy_omitted` (#286 — a promotion batch left legacy rows out; before #274 the batch threw, after it the omission was silent). Both carry a count, never a path. |
| 2026-09-26 | v1.15 | 262 (#263) | §4.1 `datapipelines.sse.stream.duration`'s `close_reason` closed set gains **`expired`**: the same policy cut as `revoked` — the subscriber's re-judgement refuses a write, the final comment is the same static string, the execution keeps running — where the refusal was the subscriber's validated token passing its `exp` (#263), so an expired token is never counted as a standing revocation or a `client_disconnect`. |
| 2026-09-26 | v1.14 | 230 (#230, security-assurance P4) | §4.2 `datapipelines.sse.stream.duration`'s `close_reason` closed set gains **`revoked`**: a stream cut by the subscriber-authority re-judgement before a write. Recorded BEFORE the terminal question — a cut stream has no terminal event of its own, and counting it as `client_disconnect` would feed D7's cancellation story a reader it never had; the execution deliberately keeps running (P4's first half). |
| 2026-09-25 | v1.13 | scheduler lane 1 (#9) | New **§3.4E the scheduler events** (`scheduler.started` / `api_mode` / `dispatched` / `reconciled` / `run_not_started` / `schedule_blocked` / `start_failed` / `inspect_refused` / `admission_closed` / `admission_wait_expired`) and four scheduler metrics in §4.1 (`occurrences{outcome}`, `runs.finished{state}`, `capacity.retries`, `runs.in_flight`). §6.1: no scheduler health component — db-scheduler's indicator is disabled. `docs-audit.sh` check C does not extract `scheduler.*` yet (#252; the same gap v1.7 names for `lake.*`). |
| 2026-09-17 | v1.12 | 158 (#121) mail connect retry | §3.4B gains `mail.send_retry` (WARN, `kind` + `attempt` + `error`): a connect-failed notice is retried in place (bounded, 3 attempts) before the claim row is marked — the retry line is per attempt, `mail.send_failed` remains the terminal one. |
| 2026-09-17 | v1.11 | 152 R152-8 retained-owner containment (#128) | `lake.instance_owner_close_failed` now covers a nonfatal `RuntimeException` too (v1.10's catch was `SQLException`-only, and the escape crossed into the shared manager's pool loop); `lake.instance_closed` gains `retained_owner_closed` so a refused physical close is never reported as a closure. |
| 2026-09-17 | v1.10 | 152 R152-5/6/7 ownership protocol (#128) | §3.4C gains `lake.instance_handle_exhausted` (WARN — the counted residual: every permitted close refused, no actor left) and `lake.instance_refused_duplicate_close_failed` (DEBUG — a duplicate refused at registration whose creator's close failed; still owned). `lake.instance_handles_closed`'s `in_flight` is now true by construction (the hand-off is decided in the closer's own failure transition). |
| 2026-09-17 | v1.9 | 152 R152-3/4 confirmed closure (#128) | `lake.instance_handles_closed` now reports `closed` / `already_closed` / `in_flight` / `close_failures` separately — a handle leaves the generation's registry only when the driver has CONFIRMED its close, so the counts say what actually closed, never what was merely attempted. |
| 2026-09-17 | v1.8 | 152 R152-2 handle accounting (#128) | §3.4C gains `lake.instance_handles_closed` (WARN, `handles` + `close_failures`): a generation now closes, at its own release, every physical connection it created that HikariCP never accepted or abandoned — a non-zero count is the rare shutdown race, not a leak. |
| 2026-09-16 | v1.7 | 152 LAKE instance events (#128) | New **§3.4C the LAKE instance events**: `lake.instance_opened` / `lake.instance_closed` (one per pool generation), the ERROR `lake.instance_owner_lost` (a live pool's engine went away; leases refused with `08003` until rebuild), the WARNs `lake.instance_owner_close_failed`, `lake.view_failed` (109 §A, previously undocumented) and `lake.view_outcome_record_failed`. `docs-audit.sh` check C does not yet extract the `lake.*` namespace (its alternation is `scripts/`, outside lane 152's fence) — a follow-up. |
| 2026-09-14 | v1.6 | 137 mail notices | New **§3.4B the mail events**: `mail.configured` / `mail.disabled` at boot, `mail.skipped` (mail off), `mail.already_claimed`, and the WARNs `mail.send_failed` / `mail.dispatch_failed`; domains and counts only, never an address or a body. `docs-audit.sh` check C now extracts `mail.*` log events from this section and `mail.*` audit events from Enums §15, so a misspelt name fails the audit. |
| 2026-08-05 | v1.0 draft | initial draft | Initial observability spec sketch — logs, metrics, traces, health, audit log |
| 2026-08-07 | v1.1 draft | consistency campaign | Applied [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) §2.14. **[M]** §6 health payload/paths realigned to the canonical [rest-api §11.1](rest-api.md#111-health-check) — root-level `/health`,`/ready`,`/info`, top-level `version`, snake_case components `{database, redis, h2_factory}`, `diskSpace` removed with rationale. **[M]** Stale metrics purged: `auth.login.attempts{outcome=locked}` dropped (OIDC-only, no local passwords, no lockout) with outcomes remapped to the auth §10.1 audit events; `datapipelines.http.server.requests` → `http.server.requests` (Spring Boot's own unprefixed metric) plus a rule on which metrics keep framework names. **[D9]** Result/SSE/idempotency metrics added: `result.bytes_written`, `result.writes{outcome}`, `result.cursor.reads{format,outcome}`, `result.expiries`, `result.size`, `sse.streams.active`, `sse.stream.duration{close_reason}`, `idempotency.cache.hits`, `idempotency.conflicts`. **[D7]** `executions.aborted{reason}` registered (matches dag-executor §15.3). **[M]** §8.1 `errors.total{class, method}` → `{domain}`, with §4.3 gaining the normative closed-set tag rule that forbids code-shape tags. **[M]** §9 rewritten as Configuration & Redaction: local YAML block deleted (config keys now referenced from [configuration.md §3.14](configuration.md#315-observability) per **D8**), redaction respecified as a non-optional two-layer mechanism (JSON-encoder field filter + `MessageConverter`) over an explicit sensitive-key list, plus §9.3 forbidding `jdbc_url`/credentials in error `details` across SSE, REST and MCP. **[D10]** `X-Correlation-Id` → `DP-Correlation-Id`. **[M]** Correlation propagation past the HTTP boundary made normative — echoed in every SSE event payload, `_meta` on MCP results ([mcp-server §6.3](mcp-server.md#63-tool-result-schema)). **[M]** Cross-ref fixed: audit log → [auth §10](auth.md#10-audit-log) (was §9). Draft status kept honest: dashboards/alerting/SLOs still to be elaborated, but §3.3, §4.1/§4.3, §6 and §9.2 are marked normative (§1). |
