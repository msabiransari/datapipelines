# Observability Specification

**Status:** v1.76 draft (to be elaborated before production hardening — the rules marked **normative** below are already binding)
**Owner:** datapipelines.co core
**Depends on:** all other specs
**Last updated:** 2026-11-13

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
4. **Tracing via OpenTelemetry is a design target.** Vendor-neutral instrumentation and export remain deferred (§5); the current build does not enable them.
5. **Health endpoints for orchestrators.** Root-level `/health` and `/ready` for k8s liveness/readiness probes. The payload contract is owned by [REST API §11](rest-api.md#11-health--diagnostics); §6 here restates it only as far as needed for operators.
6. **No sensitive data in logs — enforced at the encoder, not by convention.** Passwords, API keys, JDBC URLs with credentials, encryption keys, query result values are scrubbed by the logging pipeline itself, so no logger call site can bypass the rule (§9.2).

---

## 3. Logging

### 3.1 Format

JSON via Spring Boot's structured logging (`logging.structured.format`'s `logstash` format) with the house redacting customizer — **no new dependency** (#337). Every log entry:

```json
{
  "@timestamp": "2026-09-30T14:30:00.123456789-04:00",
  "level": "INFO",
  "logger": "co.datapipelines.dag.PipelineExecutor",
  "thread": "dag-executor-3",
  "message": "Node completed",
  "correlation_id": "uuid",
  "execution_id": "uuid",
  "pipeline_id": "uuid",
  "node_id": "fetch_orders",
  "duration_ms": 1266,
  "rows_out": 12453
}
```

Standard fields: `@timestamp`, `level`, `logger`, `thread`, `message` — the `logstash` format's `logger_name`/`thread_name` are renamed to `logger`/`thread` by the binding. MDC entries and SLF4J key-value pairs are flattened at the root (so `correlation_id` and `execution_id` sit beside the standard fields, not nested), which is what makes the example's context fields the same shape the code writes. The format also carries `@version`, `level_value`, `tags` (marker names) and `stack_trace` (when a throwable is logged); they are additional, never a replacement for the standard fields.

The switch is `datapipelines.observability.logging.format` ([configuration.md §3.15](configuration.md#315-observability)): `json` selects this line, `console` selects the human-readable development format (§3.5). It is resolved before logging initialises, and the value set is closed — anything else refuses startup. Under `json` the binding also switches Spring's banner off (`spring.main.banner-mode=off`, bound after any operator setting): the banner prints to stdout before logging exists, and stdout under `json` is a stream of records — every non-blank line of a normal startup is one JSON object (#337-b, pinned by a normal-boot test). Under `console` the banner stays.

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
- Propagated through async work: a coroutine `ThreadContextElement` built from the REQUEST's ids carries `correlation_id` and `execution_id` across every suspension of the execution's coroutines (installed once in `PipelineExecutor.execute`, re-added explicitly at the per-node deadline scope, whose fresh `CoroutineScope` inherits no elements), and every pool whose threads run inside node work captures the submitter's MDC at submission and restores the thread's state after — the script-evaluation, selector-statement (and its abandon thread), template-render, template-parse and cancel-re-issue threads (#337).
- Included in every log line in the request's call tree — as the `correlation_id` / `execution_id` members under `json`, and as `correlation_id=<id>` / `execution_id=<id>` fields before the message under `console` (`-` when the event has no such context, never an id left over from an earlier event; the console names exactly these two MDC keys and never dumps the MDC) (#337-b).
- Pipeline executions add `execution_id` and propagate it to every node-execution log.

**Deliberately NOT propagated, by design** (#337) — a line there must not wear a caller's id, because the unit of work outlives or spans callers: the persistence batching writer's threads and the shutdown drain (one batch spans many callers), the scheduled jobs' scheduler (`dp-scheduled`) and the SSE log/heartbeat schedulers (no request), and the mail worker (a claim row is picked up long after the request that queued it returned).

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
| `staging` | H2 instance created/closed, staging operation success (table name + row count), memory-limit warnings; the DuckDB scratch sweep's INFO naming the paths it removed for a provably dead owner, and its one WARN naming every leftover path nobody can vouch for and their total bytes — counted against `scratch-bytes`, never deleted ([Staging §8.5](staging.md#85-the-duckdb-engine), #803); paths and byte counts only |
| `dag` | execution started/completed/failed, node started/completed/failed, cancellation; the crash sweep's and the event retention's ticks — a failed tick names the store failure by `error` (its class) and `sql_state`, never its message (§3.4G's rule, #321) |
| `templates` | template registered (id + version), render failures |
| `mcp-server` | tool calls (tool name + caller), transport errors |
| `web` | request log (method, path, status, duration), CORS preflight, SSE connections opened/closed |

The tables below catalogue thirteen namespaces: `audit`, `dashboard`, `datasource`, `endpoint`, `execution`, `pipeline`, `shutdown`, `parameter`, `lake`, `mail`, `persistence`, `scheduler` and `retention`. All thirteen currently catalogued namespaces are enforced by `ObservabilityEventCatalogParityTest` (#439, #443): the `event=<namespace>.<name>` literals under `modules/<module>/src/main/kotlin` and the backticked names in these tables' event column must agree in both directions. Per-namespace non-vacuity floors prevent an empty extraction from passing. This is a source-text check (comments count), not an AST or runtime inventory; uncatalogued code namespaces remain outside it. Computed event identities in files logging an enforced namespace are refused. The two exact retained `audit_log write failed` formats in `AuditLogger.kt` (§3.4G) carry an audit-row identity in `event={}` rather than a structured-log identity; each exception is pinned to that file and required exactly once. `audit_event={}` is a separate field.

#### 3.4A The pool-retirement events (094)

A datasource that is edited or deleted has its pool RETIRED, not closed on the spot: it leaves the live map immediately, stops handing out connections, and closes once the statements already running on it finish ([Datasources §5.2](datasources.md#52-pool-lifecycle)). Four pool events name that lifecycle; `pool_hard_closed` is its non-routine outcome. Three more (#818d) report the close itself, which the reaper's pass hands to a `dp-pool-close` thread and never waits on, and the pass's own time. This section also catalogues bootstrap and invalidation failures under `datasource.*`.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `datasource.pool_invalidated_remotely` | A peer instance saved or deleted the datasource and this instance retired its pool on the message | `datasource`, `origin` |
| INFO | `datasource.pool_reconciled` | The (re)subscription comparison found a pool built from a row that has since changed or gone, and retired it | `datasource`, `reason` |
| **WARN** | **`datasource.pool_hard_closed`** | **The retirement ceiling fired while connections were still out — the pool was closed anyway and those statements lost their connection** | `datasource`, `active_connections`, `ceiling_seconds` |
| WARN | `datasource.pool_close_stuck` | A retired pool's close was still running at its own deadline after the reaper handed it to its `dp-pool-close` thread (#840): that pool's HikariCP `connectionTimeout` (its shutdown's first wait), Hikari's three fixed 10 s shutdown waits, 5 s for a lake generation's owner release and, for a managed lake generation only, the bound of its reader-pin release (the metadata pool's `connectionTimeout` plus the 5 s statement bound of the pin DELETE plus the driver's 10 s cancel wait on a server that never answers, #837). 65 s at HikariCP's default 30 s; 90 s for a managed generation on a 30 s lake pool with the metadata pool's shipped 10 s acquire bound (§3.13, #837). Logged once per close and counted (`datapipelines.datasource.pool.close_stuck`, §4.1); the close thread is left to finish and the close is never retried. The next pass and every other close go on regardless. Logged from the JDK's `CompletableFutureDelayScheduler` thread, not the stuck `dp-pool-close-<n>` thread | `datasource`, `generation` (`-` for a non-lake pool), `elapsed_seconds`, `active_connections` (at the hand-off), `deadline_seconds` (the deadline this close was held to; #840), `message` |
| WARN | `datasource.pool_close_failed` | A retired pool's close threw on its `dp-pool-close` thread; the pool is left to the JVM. The close contains its own driver failures, so this means a defect, not a database fault. One line per close, no retry | `datasource`, `generation`, `error` (class, §3.4G), `message` |
| WARN | `datasource.reaper_pass_slow` | One pool reaper pass on `dp-reaper` took longer than its 1 s bound, the bound the managed reads lifetime rule counts twice in the reaper delay ([Datasources §8C.10](datasources.md#8c10-the-managed-lake-datasource)). One line and one increment per slow pass (`datapipelines.datasource.pool.reaper_pass_slow`, §4.1); the pass's work is already done. A steady rate means the rule's bound is wrong for this instance | `elapsed_ms`, `bound_ms` |
| INFO | `datasource.pool_reconcile_on_subscribe` | The invalidation channel was (re)subscribed and the reconcile ran | `channel`, `retired` |
| WARN | `datasource.pool_invalidation_publish_failed` | A committed datasource change could not be published to Redis; peers may keep stale pools. One line per failed publish, with no retry queue | `datasource`, `message` (exception message), `reason` (static explanation) |
| WARN | `datasource.pool_invalidation_malformed` | One pub/sub message could not be parsed and was dropped; the subscriber remains alive. One line per malformed message | `reason=dropped`, `message` (first 200 characters of the parser exception's message) |
| WARN | `datasource.pool_reconcile_failed` | The live-pool comparison at boot/reconnect subscription threw; retry is at the next (re)subscription, not a periodic tick | `message` (exception message), `reason` (static explanation) |
| INFO | `datasource.bootstrap_start` | Before applying each configured bootstrap datasource file, after resolving the bootstrap actor; no configured files means no line | `file`, `actor_user_id`, `actor_provider` |
| INFO | `datasource.bootstrap_registered` | An absent datasource was validated and saved; one line per newly registered entry during boot | `name`, `dialect`, `readonly`, `scope=global` |
| INFO | `datasource.bootstrap_skipped` | One bootstrap entry already exists (soft-deleted rows count), or another instance won the insert race; its row is kept. Credential reconciliation and any eligible lake seed still follow the already-present case | `name`, `reason` (`already_present`/`concurrent_registration`), `message` (static explanation) |
| WARN | `datasource.bootstrap_credential_resynced` | For one existing entry, the stored credential failed authentication and the file credential worked; only the credential was replaced. One line per resync, not for unchanged working credentials | `name`, `env_key` (environment-variable name or `<literal>`, never the credential), `message` (static explanation) |
| WARN | `datasource.bootstrap_managed_name` | A bootstrap entry names the managed lake datasource (#818, [Datasources §8C.10](datasources.md#8c10-the-managed-lake-datasource)). Configuration owns that row, so the entry is skipped and its credential is neither probed nor written | `name` |
| ERROR | `datasource.bootstrap_credential_broken` | Both stored and file credentials failed authentication for one entry; the row stays unchanged and boot continues. Fix the named environment variable/database role or edit the datasource | `name`, `env_key` (variable name or `<literal>`), `message` (static explanation) |
| ERROR | `datasource.bootstrap_credential_unreadable` | The stored credential could not be decrypted; it is not replaced, the row stays unchanged and boot continues. Check the deployment encryption key | `name`, `message` (static explanation; no key value) |
| INFO | `datasource.bootstrap_lake_seed_skipped` | A bootstrap entry has a lake import but its datasource is soft-deleted; one line per skipped seed during boot | `name`, `reason=soft_deleted`, `message` (static explanation) |
| INFO | `datasource.bootstrap_lake_imported` | The lake table import returned, for one eligible bootstrap entry; repeated boots can find all tables already registered | `name`, `registered`, `already_registered` (counts) |
| INFO | `datasource.bootstrap_lake_seeded` | An eligible entry's lake seeder returned, even if the idempotent import added nothing; one line per completed seed during boot | `name` |
| INFO | `datasource.bootstrap_complete` | The registrar finished one file without a propagated failure; counts describe that invocation, not the deployment total. Broken credentials can be counted while boot continues | `file`, `registered`, `skipped`, `resynced`, `broken`, `lake_seeded` (counts) |

**The pool-retirement WARN `pool_hard_closed` is the one to alert on for lost connections.** Bootstrap credential ERRORs need operator repair; the resync WARN reports a credential replacement. The invalidation failure lines retain exception-message text (the malformed-message line caps it at 200 characters); they do not follow §3.4G's class/SQLSTATE-only shape, and their remaining review is tracked in #329. `pool_hard_closed` means a statement outlived `datapipelines.datasources.retire-ceiling-seconds`, or for a managed lake generation `managed-retire-ceiling-seconds` (#824; `ceiling_seconds` is the ceiling that pool was retired under) ([Configuration §3.26](configuration.md#326-datasource-pools)) — either a genuinely hung query, or a ceiling set below what this deployment's statements really take. It carries the number of connections it took down, which the matching `datapipelines.datasource.pool.hard_closed` counter deliberately does not (an unbounded tag value, §4.3).

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

A `LAKE` datasource's pool owns one embedded DuckDB instance per pool generation ([Datasources §5.2](datasources.md#52-pool-lifecycle)): a retained owner connection is opened at pool build, initialized once, and released after the Hikari pool has shut down. Ten events name that lifecycle and its failures (the managed datasource's own events follow the table); the ERROR is the one that means a pool needs rebuilding, and the WARNs are what the operator should read.

| Level | `event=` | When | Fields |
|---|---|---|---|
| WARN | `lake.reader_lease_lost` | A reader heartbeat failed or returned fewer rows than expected; the generation retires and refuses new reads | `datasource`, `generation`, `cause` (class or short count, never an exception message) |
| WARN | `lake.reader_lease_overlong` | A physically live reader exceeds one twenty-fourth of its protection window; the pin remains live until deadline | `datasource`, `generation` |
| WARN | `lake.reader_lease_round_slow` | The dedicated keeper round exceeded its 5 s cadence | — |
| INFO | `lake.instance_opened` | A pool generation opened and initialized its instance owner — one per pool build | `datasource`, `generation` |
| INFO | `lake.instance_closed` | The generation's release completed, after the Hikari pool's shutdown; `retained_owner_closed` says whether the driver actually closed the retained owner connection (`false` ⇒ a `lake.instance_owner_close_failed` WARN preceded it) — the instance is freed once the last handle drops | `datasource`, `generation`, `retained_owner_closed` |
| INFO | `lake.instance_readers_interrupted` | A generation's close interrupted every physical connection still registered to it, one thread per handle, before Hikari's shutdown (#818d, [Datasources §5.2](datasources.md#52-pool-lifecycle)). A computing query stops at the engine's next interrupt check; one blocked in an object-store request stops when that request gives up. Only this generation's handles are reached. `inline` counts handles interrupted on the closing thread because no new thread could start | `datasource`, `generation`, `handles`, `inline` |
| **ERROR** | **`lake.instance_owner_lost`** | **The retained owner connection is closed while the pool is still live (a driver fault, not a retire); every new lease is refused with SQLSTATE `08003` until the pool is rebuilt — logged once per generation** | `datasource`, `generation`, `message` |
| WARN | `lake.instance_owner_close_failed` | The driver refused to close the retained owner connection at release — an `SQLException` or a nonfatal `RuntimeException`, contained the same way; one attempt, no retry, nothing propagates (the release runs inside the shared manager's loop over every pool, and one lake's driver fault must not stop the next pool's cleanup); the physical connection is the driver's residual, reported once | `datasource`, `generation`, `error`, `message` |
| WARN | `lake.instance_handle_exhausted` | A physical connection refused every close the ownership protocol permits — its holder's own attempt, the generation's one attempt, or the one hand-off retry — and no actor is left: it stays registered and counted in `liveDuplicates` as the generation's residual. Logged by whichever actor made the last attempt, with the driver's error | `datasource`, `generation`, `attempts`, `error`, `message` |
| DEBUG | `lake.instance_refused_duplicate_close_failed` | A duplicate created just as its generation retired was refused at registration and its creator's close of it failed; the handle stays registered — the release (if it has not run) or the creator's own retry (if it has) takes it, and exhaustion is reported by `lake.instance_handle_exhausted` | `datasource`, `generation`, `error` |
| WARN | `lake.instance_handles_closed` | At the generation's release, physical connections were still registered to it — ones HikariCP never accepted (their creation straddled the shutdown and the closed bag refused them), abandoned to a borrower at its shutdown ceiling, or whose earlier close the driver refused — and the generation released them. Since #818d a pool's close interrupts every registered handle first (`lake.instance_readers_interrupted`, which closes them), so on that path this line appears only for a handle whose interrupt close the driver refused or that was mid-close on another thread. The counts distinguish what happened: `closed` (the generation's own close was confirmed), `already_closed` (found closed by something the wrapper never saw, e.g. an executor-driven abort), `in_flight` (a borrower's close was mid-flight on another thread; the last word was handed to it), `close_failures` (the driver refused — the honest residual, still registered) | `datasource`, `generation`, `handles`, `closed`, `already_closed`, `in_flight`, `close_failures`, `error` (the last driver refusal, empty when none), `message` |
| WARN | `lake.view_failed` | One registered table's view could not be created at instance init (109 §A); it is recorded on the table's registry row and skipped, the surviving views serve | `datasource`, `table`, `error`, `message` |
| WARN | `lake.view_outcome_record_failed` | The registry write of a view outcome threw; the instance still serves the surviving views, or an all-refused registry still refuses the pool build; the row keeps its previous state | `datasource`, `table`, `error` (exception class), `sql_state`, `message` (static explanation) |

**The managed lake datasource (#818).** The datasource that serves managed lake tables ([Datasources §8C.10](datasources.md#8c10-the-managed-lake-datasource)) is a `LAKE` datasource, so the events above apply to it too. Nine more events name its reads: the row's sync from configuration at boot, how long a generation lives, and which tables it serves.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `lake.managed_datasource_registered` | Boot created the managed datasource's row from configuration | `datasource`, `credential_kind` |
| INFO | `lake.managed_datasource_resynced` | Boot found the row different from configuration and rewrote it; its pools retire here and on peers | `datasource`, `credential_kind` |
| INFO | `lake.managed_registry_backfilled` | Boot wrote registry rows for managed tables defined before the managed datasource existed | `datasource`, `rows` |
| INFO | `lake.managed_datasource_granted` | Boot or a freshness tick granted the managed datasource to workspaces that could not see it | `datasource`, `workspaces` |
| INFO | `lake.generation_expired` | A managed generation passed `reads.max-generation-age-seconds` and was retired, either when a lease was asked for (`trigger=lease`) or by the pool reaper's pass with no borrower (`trigger=reaper`, #818c); the next lease builds from the catalog. The retirement issues no statement | `datasource`, `trigger`, `age_seconds`, `max_age_seconds` |
| INFO | `lake.reaper_retired` | A pool reaper pass retired one or more managed generations past their age bound; logged once per pass, only when the count is not zero. The count is at most the number of live managed generations (one per managed datasource) | `generations` |
| INFO | `lake.generation_stale` | The freshness tick found the catalog changed since the generation was built and retired it | `datasource`, `tables_stale` |
| INFO | `lake.freshness_tick_retired` | A freshness tick retired one or more generations; logged once per tick, only when the count is not zero | `generations` |
| WARN | `lake.freshness_read_failed` | The freshness tick's catalog read failed, including a read cancelled at the tick's 5 s statement bound (`sql_state=57014`). The tick skips that datasource, and the live generation serves until its age bound | `datasource`, `sql_state`, `error`, `message` |
| WARN | `lake.freshness_tick_failed` | A step of the freshness tick (`refresh` or `regrant`) failed, for example its statement was cancelled at the 5 s bound. The tick's next step, the next tick and the other scheduled jobs still run | `step`, `error` (the class), `sql_state`, `message` |
| INFO | `lake.table_unavailable_omitted` | A generation was built without a view for a managed table: archived or purged, no catalog row, no lifecycle row, or the lake disabled. A node that reads it fails with `lake.table.unavailable` | `datasource`, `table`, `reason` |

**`freshness_read_failed` is the one to read** if it repeats: the catalog cannot be read, so no new generation can be built either, and once the live one reaches its age bound, leases fail with `lake.unavailable`. No credential or key appears in any of these lines; `credential_kind` is `password` or `none`, never a value.

**`instance_owner_lost` is the one to alert on**: it is a datasource whose engine went away underneath a live pool, and it does not self-heal — retire-and-rebuild (a datasource save, a table registration, or the reconcile) is the remedy, and the message says so. `view_failed` is the existing per-table isolation working (the table's row carries the error; the UI shows it); a burst of them after a registry change is a bad location or credential, not the engine. Nothing here is an audit row.

#### 3.4D The promoter lens event (178)

A promoter sees only released, not-yet-promoted pipelines and templates ([Auth §11A.1](auth.md#11a1-the-404-rule)); deciding that needs the higher environment's inventory, read through a per-workspace cache (`datapipelines.deployment.promotion.inventory-cache-ttl-seconds`, [Configuration §3.19](configuration.md#319-deployment)). When the target cannot be read the lens FAILS CLOSED — every promoter read is empty, the screens say why — and one event says so per window.

| Level | `event=` | When | Fields |
|---|---|---|---|
| WARN | `pipeline.promotion.lens_unavailable` | The lens's inventory probe for a workspace failed — target unreachable, malformed, refusing, or no target configured — and the failure is now cached for one TTL window, during which every lensed read in that workspace answers nothing. Logged on the PROBE, never on the cached answers, so it is once per window by construction; `reason` is the transport class or configuration reason (`ConnectException`, `malformed_response`, `no_target_configured`), never the key | `target` (base URL), `reason`, `workspace`, `code` (the §13 code the promotion page would have shown) |
| INFO | `pipeline.promotion.pushing` | After building and validating a promotion batch, immediately before pushing it; one line per push attempt, not evidence the target accepted it | `target` (base URL), `workspace`, `roots`, `templates`, `sets`, `pipelines`, `endpoints`, `visualizations`, `dashboards` (counts) |
| INFO | `pipeline.promotion.target_refused` | The target returned a non-success HTTP response with a JSON body; one line per refusal read, including inventory reads. Its code is re-raised, or a missing code becomes a malformed-response error | `target` (base URL), `status`, `code` (`(none)` when absent); no response message/body |

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
| WARN | `scheduler.outcome_evidence_unreadable` (#336) | A finished run's persisted outcome evidence could not be read: the execution row's `error_json` (the run lands `unknown` / `outcome_unreadable` — it may have said `instance_lost`), or the last `execution_aborted` event's payload (the run stays `aborted`; only the reason is lost) | `execution`, `evidence` (`error_json`/`abort_payload`), `error` |

| INFO | `scheduler.notification.enqueued` (#442b) | A run transition (or a block) wrote its notice: one event row and its deliveries, in the transition's own transaction | `schedule_id`, `run_id`, `notification_id`, `notice` (the checkbox), `outcome`, `queued`, `refused` (`<code>:<count>`, or `0`) |
| WARN | `scheduler.notification.backlog_full` (#442b) | More than `notifications.backlog-max` deliveries were pending: the notice's remaining deliveries were written `failed` / `backlog_full` | `notification_id`, `backlog_max` |
| INFO | `scheduler.notification.sent` (#442b) | The SMTP relay accepted one delivery — not proof it reached an inbox | `delivery_id`, `notification_id`, `schedule_id`, `run_id`, `notice`, `domain` (the recipient's domain only), `attempts`, `code` (`-`) |
| INFO | `scheduler.notification.retry` (#442b) | A send provably never reached the relay (connect, 4xx, credentials); the delivery is `pending` again after the backoff | the same fields; `code` is the sanitized reason |
| WARN | `scheduler.notification.failed` (#442b) | A delivery is finally `failed`: refused (5xx, invalid address), out of tries, or found with mail off or its domain outside a narrowed allowlist at send | the same fields |
| WARN | `scheduler.notification.uncertain` (#442b) | A delivery may have been sent — the connection failed after the message went out, or a claim a dead instance left was recovered (`code=interrupted`). Never retried automatically; a person may resend | the same fields (a recovered claim adds `message`) |
| ERROR | `scheduler.notification.render_failed` (#442b) | A notice could not be rendered (a defect); the delivery is `failed` / `render_failed`, nothing was sent | `delivery_id`, `schedule_id`, `run_id`, `error` (the exception class) |
| ERROR | `scheduler.notification.port_failed` (#442b) | The mail port threw instead of answering (a defect); the delivery is `uncertain` / `interrupted` | `delivery_id`, `schedule_id`, `run_id`, `error` (the exception class) |
| WARN | `scheduler.notification.settle_lost` (#442b) | A send finished after its claim had already been recovered as interrupted; the recovered state stands | `delivery_id`, `schedule_id`, `run_id`, `message` |
| INFO | `scheduler.conversion.converted` (#786 §D.5) | The upgrade's in-place conversion pinned a schema-1 (`"version": "current"`) pipeline schedule to the exact version its pipeline's CURRENT names, at startup before dispatch: one line per converted schedule — the schedule half of the conversion report ([Deployment §8](deployment.md)). Only the payload's `version` and `payload_schema_version` changed | `id`, `name`, `pipeline`, `pipeline_version` (the exact pin), `status` (that version's status: `RELEASED`, or `DRAFT` — a draft pin keeps refusing where drafts do not run), `queued_runs` (how many of its queued runs were pinned in the same transaction — each to its own pipeline's version, see `scheduler.conversion.converted_run`) |
| INFO | `scheduler.conversion.converted_run` (#786 §D.5, round 7) | One line per queued run the conversion pinned, after its schedule's line. A run's payload is its own frozen copy, so after an edit of the schedule's target it can name a different pipeline than its schedule: it is pinned to the version ITS pipeline's CURRENT names, never the schedule's | `id` (the run), `schedule`, `pipeline` (the run's own), `pipeline_version`, `status` |
| INFO | `scheduler.conversion.summary` (#786 §D.5) | One line per startup after the schedule conversion ran; `converted=0` on every later startup (the selector is schema 1) | `converted`, `released`, `draft`, `queued_runs` |
| ERROR | `scheduler.conversion.refused` (#786 §E.0) | A live schema-1 schedule's pipeline, or the pipeline one of its schema-1 queued runs names, cannot be pinned exactly; the server refuses to start and nothing is written. One line per schedule or run | `kind=schedule` with `id` (the schedule), or `kind=run` with `id` (the run) and `schedule`; `name` (the schedule's), `pipeline` (the target that failed), `reason` (`missing_pipeline`, `no_versions`, `no_current`) |
| ERROR | `scheduler.conversion.refused_summary` (#786 §E.0) | Follows the per-schedule lines once; the startup then fails with a non-zero exit (after the API lines, if any: every step's precondition is read before any writes) | `refused`, `fix` (static: release the pipeline or delete the schedule on the previous version, then upgrade) |

The notification lines carry ids, the notice and the recipient's DOMAIN; never an address, a subject or a body. A notice's history of record is the run's trail (`notification_enqueued` / `_sent` / `_failed` / `_uncertain`, whose details carry no domain either) and its delivery rows ([Metadata DB §4.40](metadata-db.md#440-schedule_notification_deliveries)).

A `scheduler.schedule_blocked` needs a person. A steady `scheduler.run_not_started` means `max-concurrent-runs` is too low for this instance's schedules, or runs are too long for their cadence — `datapipelines.scheduler.capacity.retries` (§4.1) is the leading signal.

#### 3.4F The legacy endpoint events (#274, #286)

A `published_endpoints` row whose stored path fails today's grammar is RETIRED, never fatal ([Metadata DB §4.13](metadata-db.md#413-published_endpoints)): never served, listed flagged, unpublishing it is the fix. The first two lines say so to an operator; both carry a COUNT, never a path. The remaining rows describe serving, registry invalidation and key-budget failures.

| Level | `event=` | When | Fields |
|---|---|---|---|
| WARN | `endpoint.legacy_rows` | Once per JVM, on the first repository query that meets a legacy row. `at_least` is THAT query's count — a floor, not the deployment's total (after V41 the registry warm-up reads enabled rows only, so the first sighting is usually one workspace's listing) | `at_least` |
| WARN | `endpoint.promotion_legacy_omitted` | A promotion batch was built and legacy rows over its promoted pipelines were left out — the target's grammar would refuse them | `count` |
| WARN | `endpoint.key_budget_exhausted` | A metered API-caller key exceeded its instance-local fixed-window budget; one line per refused request, answered 429 with Retry-After, before serving | `key_id`, `path` (request application path), `limit` (`requests/windowSeconds` followed by `s`) |
| WARN | `endpoint.key_budget_table_saturated` | A new key cannot enter the bounded tracking table even after expired windows were removed; one line per unmetered admission, with the saturation counter incremented | `size`, `message` (static explanation) |
| WARN | `endpoint.pipeline_not_released` | A matched live endpoint has no servable pipeline version; one line per refusal, answered 503. Check its pipeline release | `path` (stored pattern), `pipeline_id`, `message` (static explanation) |
| ERROR | `endpoint.registry_pattern_unparseable` | A registry reload built fewer matchable endpoints than enabled rows: unparseable or reserved-category rows are omitted, surviving endpoints still serve. One line per affected reload | `loaded`, `matchable`, `reason` (static explanation); no path |
| WARN | `endpoint.registry_invalidation_publish_failed` | A committed endpoint change could not be published to Redis; one line per failed publish. Peers may serve their previous snapshot until their next reload | `message` (exception message), `reason` (static explanation) |
| WARN | `endpoint.registry_invalidation_malformed` | A pub/sub message could not be parsed and was dropped; one line per malformed message, subscriber stays alive | `reason=dropped`, `message` (first 200 characters of the parser exception's message) |
| INFO | `endpoint.registry_invalidated_remotely` | A parsed message from another instance dropped this instance's snapshot; one line per peer message, own-origin messages are ignored. The next request reloads | `origin`, `message` (static explanation) |
| INFO | `endpoint.conversion.converted` | (#786 §E.1) The upgrade's in-place conversion gave a pre-V58 API its version 1 at startup, before serving: one line per converted API — the conversion report ([Deployment §8](deployment.md)) | `path` (stored pattern), `pipeline`, `pipeline_version` (the exact pin: the legacy pipeline's CURRENT), `status` (`RELEASED` iff that pipeline version is RELEASED, else `DRAFT`) |
| INFO | `endpoint.conversion.summary` | (#786 §E.1) One line per startup after the conversion ran; `converted=0` on every later startup (the selector is legacy-only) | `converted`, `released`, `draft` |
| ERROR | `endpoint.conversion.refused` | (#786 §E.0) A pre-V58 API's pipeline cannot be pinned exactly; the server refuses to start and nothing is written. One line per API | `kind=api`, `id`, `path`, `pipeline` (name, or the legacy id when the row is gone), `reason` (`missing_pipeline`, `no_versions`, `no_current`) |
| ERROR | `endpoint.conversion.refused_summary` | (#786 §E.0) Follows the per-API lines once; the startup then fails with a non-zero exit | `refused`, `fix` (static: release the pipeline or delete the API on the previous version, then upgrade) |

Neither legacy-row line is an alert: the API console lists every legacy row with its reason. A steady `promotion_legacy_omitted` means an endpoint that will not reach the target until someone republishes it at a legal path.

The invalidation WARNs retain exception-message text; only the malformed-message diagnostic is capped. They are not §3.4G's class/SQLSTATE-only shape (#329). A sustained key-budget saturation rate means new keys are being admitted unmetered.

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
| WARN | `persistence.hook_failed` | A metrics hook threw; the writer contained it — the item's outcome is unchanged, only that hook's own count is lost. At most once per 10 s per writer | `writer`, `hook`, `cause` |
| ERROR | `persistence.writer_died` | A writer thread ended on an `Error`; its batch was failed so no caller waits on it, and its partition's later items are written directly by their callers | `writer`, `partition` |
| DEBUG | `audit.write` | Every audit row, the path it takes: `transactional` (inside the caller's transaction, on its connection), `batched` (the audit writer), `direct` (no writer — the default, `datapipelines.persistence.audit.enabled: false`, or `enabled: false`) | `audit_event`, `path` |
| INFO | `shutdown.persistence_drain_started` | Shutdown, after the web server's graceful drain: every writer starts its bounded drain, in parallel | `writers` |
| INFO | `persistence.drained` / `shutdown.persistence_drained` | A writer flushed everything it held | `writer` (`lost`, `in_flight` on the second) |
| WARN | `persistence.drain_incomplete` | A writer's drain reached `shutdown-drain-ms` with items left: `lost` were never written (only `submit` items can be — nothing awaits them), `in_flight` were inside a commit and may or may not have landed | `writer`, `lost`, `in_flight`, `drain_ms` |

A failure is named by `cause` (the exception's simple class name) and `sql_state` (the first non-null SQLSTATE among at most 16 causes, unchanged only if it matches ASCII `^[0-9A-Za-z]{1,5}$`; `invalid` for any other shape, without trying later states, and `none` when no state is found) — never by the exception's message or its cause chain, which a store fills with the refused row (Postgres's DETAIL and CONTEXT; PgJDBC's batch exception quotes the bound values). `23xxx` is a row the store refuses, `08xxx` a connection lost (#266b). The emitter and the audit sink keep their own lines too — `Durable event … not written (kind)`, `SSE event log append failed … (replay will be incomplete)`, `audit_log write failed … kind=…` (on the direct path `… cause=… sql_state=…`) — the ones operators have always searched for. A steady `persistence.saturated` or a rising `datapipelines.persistence.fallbacks{reason=timeout}` (§4.1) means the store cannot keep up; `batch_retried` with `kind=poison` means rows the store refuses, which no retry will fix.

The same class/SQLState rule applies to both lake outcome-recorder failure paths (§3.4C), the write-back rollback warning and the example-content import failure line (#329, first slice): their `error` field uses `FailureShape.cause`, with `sql_state=none` when absent, and no exception message or throwable attachment. This slice leaves the remaining #329 inventory for separate review.

#### 3.4H The scheduled jobs' thread (#316)

Every `@Scheduled` job but the pool reaper — the stale-execution sweep (every 15 s), the managed lake's freshness tick (every `reads.freshness-tick-seconds`, 60 s by default; #818), the dashboard refresh sweep (every 60 s), the hourly retention sweep and, only with the lake enabled, the lake settlement tick ([Lake §8](lake.md#8-settlement-and-recovery); 60 s after the last ends) — runs on ONE thread of its own, `dp-scheduled` (`SweepSchedulingConfiguration`'s `taskScheduler`), and nothing else runs there. Their lines therefore carry `thread: dp-scheduled` (§3.1): `execution.swept` / `execution.sweep_failed`, `execution.events_purged` / `execution.event_retention_failed`, `keys.purged`, the `audit.retention*` lines and `retention.step_failed` (§7), the dashboard and parameter housekeeping lines (§3.4J, §3.4M), and the freshness tick's `lake.generation_stale`. **The pool reaper (every 5 s) runs on a single thread of its own, `dp-reaper` (`PoolReaperSchedulingConfiguration`'s `poolReaperTaskScheduler`; #818d)**, so no other job's statement can delay it: its lines carry `thread: dp-reaper` — §3.4A's `datasource.pool_hard_closed` and `datasource.reaper_pass_slow`, and §3.4C's `lake.reaper_retired` with the `lake.generation_expired trigger=reaper` lines of its pass (#818c: the pass retires a managed generation past its age bound before it reaps, and neither half issues a statement). The pass hands every close to a `dp-pool-close` thread, so the closes' own lines (`lake.instance_readers_interrupted`, `lake.instance_closed`, the Hikari shutdown, `datasource.pool_close_failed`) carry `dp-pool-close-<n>`, and `datasource.pool_close_stuck` the JDK's `CompletableFutureDelayScheduler`. The reaper's scheduler stops in the same shutdown phase with the same bounded await, and logs neither shutdown line below. Until #316 they carried `dp-sse-log`: the jobs ran on the SSE log streamer's thread ([REST API §10.3](rest-api.md#103-replay-sse-stream)'s replays), so a slow tick stalled every replay and a burst of replays delayed the sweep.

**The jobs' waits on the metadata database are bounded in wall-clock time (#837).** Two waits sit outside a statement's `queryTimeout`, and each has its own bound. *The acquire:* a job waits for a pooled connection at most `spring.datasource.hikari.connection-timeout` (10 s, [Configuration §3.13](configuration.md#313-server)); a tick that cannot get one fails through its own failed-tick line with `error=CannotGetJdbcConnectionException`. *A server that accepts and never answers* (a paused container, a partition): a statement's cancel is a second connection that server never reads, so every statement of the four jobs built on the bounded template, and of the lake settlement tick through the lake runtime's own template with the same contract (#858), also runs on a connection whose network timeout is its statement bound plus 5 s. Without it a statement against such a server never ends. With it the read fails, the driver closes the connection, the pool discards it, and the job logs its failed-tick line with `sql_state=08006`. The failure is reported when the driver's own cancel attempt on the hung server gives up (`cancelSignalTimeout`, 10 s in pgjdbc), so the statement ends at its statement bound plus 10 s (measured against a paused PostgreSQL container, pgjdbc 42.7.14). The socket bound is a code constant, not a key, and it is set per statement: migrations, request handlers and every other statement keep the driver's default, and the pool restores its own value when a connection goes back. Inside a transaction the connection keeps the bound until the transaction ends, so a transaction must not mix statements of a bounded template with a longer one on another (the lake runtime's transactions are five-second ones, whose statements Spring caps at the time left). The acquire is a separate wait, before the statement starts, and is not part of the statement's end. The worst wait of ONE statement is the acquire plus that end:

| Job (log events) | Statement bound | Socket bound | A hung server ends the statement at (bound + 10 s) | One statement, worst wait (10 s acquire + that) |
|---|---|---|---|---|
| Stale-execution sweep (`execution.sweep_failed`) | 10 s | 15 s | 20 s | 30 s |
| Dashboard refresh sweep (`dashboard.refresh_sweep_failed`) | 10 s | 15 s | 20 s | 30 s |
| Managed lake freshness tick (two steps; the grant repair's statements, its lock's included, share ONE 5 s budget, #841 R4) | 5 s | 10 s | 15 s | 25 s |
| Retention sweep (`retention.step_failed`, six isolated steps) | 25 s | 30 s | 35 s | 45 s |
| Lake settlement tick (`lake.settle.sweep_deferred`) | 5 s per statement | 10 s | 15 s | 25 s |

A tick of several statements or steps can wait that long per statement: the retention sweep's six steps each fail on their own and the next one starts, so against a dead server the sweep can run for the sum, and a SIGTERM during it is cut at the shutdown phase below. The pool reaper issues no statement and is not in the table.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `shutdown.scheduled_jobs_stopped` | Shutdown, after the execution drain and every writer's drain (§3.4G): the jobs' scheduler has stopped and its thread has ended. No tick STARTS once the application begins to close; a tick already running finished before this line, while the metadata pool was still open | `thread` |
| WARN | `shutdown.scheduled_jobs_incomplete` | The same point, but a tick was still running — it outlived `spring.lifecycle.timeout-per-shutdown-phase` (30 s by default) and a further `await_ms`. It is cut when the metadata pool closes, and the next tick, on this instance or another, redoes it: every job is idempotent. Since #326 the four metadata jobs' statements carry a JDBC bound (the sweep's 10 s, the retention's 25 s — under this 30 s phase), and since #636 the four housekeeping statements do too (the dashboard refresh sweep's 10 s; the retention tick's 25 s for the dashboard refreshes' DELETE and the parameter evaluations' sweep UPDATE and retention DELETE), so a tick hung on a lock fails at its bound or was cancelled at its bound (`sql_state=57014`) before it can outlive the phase. Against a server that never answers, the cancel cannot land: since #837 the socket bound lets the statement end (the sweep's at 20 s, the dashboard sweep's at 20 s, the freshness tick's at 15 s and, since #858, the lake settlement tick's at 15 s, each after up to the 10 s acquire), but the retention sweep's 35 s can outlive the phase, and the tick is then cut when the pool closes. The pool reaper is not among these jobs: it has its own `dp-reaper` scheduler and issues no statement (#818d) | `thread`, `await_ms` |

#### 3.4I The executor's retention events (#316 review)

The executor's two scheduled jobs on the `dp-scheduled` thread (§3.4H) — the stale-execution sweep (`StaleExecutionSweeper`, every 15 s) and the hourly retention sweep over `execution_events` (`ExecutionEventRetention`) — log these. The two failure lines name the store failure by `error` (its class) and `sql_state`, never its message (#321, the §3.4G rule; `error=` is the scheduled-job mould's field name — `audit.retention_failed` — where §3.4G's writers say `cause=`). Catalogued at the 316 merge: §3.4H cited two of these names before any table defined them, and the docs audit (whose `execution` event family joined in the same commit — `execution` is also a permission family) refused the citation.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `execution.events_purged` | A retention tick deleted at least one `execution_events` row for executions completed before the cutoff (nothing is logged for an empty tick) | `count`, `cutoff` |
| WARN | `execution.idempotency_release_failed` | One release attempt for a never-started REST or MCP execution failed; the original refusal still reaches the client and the key expires by TTL | `user`, `execution`, `error` (class only; no key or exception message) |
| WARN | `execution.sweep_failed` | The stale sweep's UPDATE threw, or was cancelled at its bound (`sql_state=57014`; the 10 s `SWEEP_STATEMENT_TIMEOUT_SECONDS`, #326); the tick is skipped and the next one retries. Counted as `datapipelines.scheduled_jobs.tick_failed{job=stale_sweep}` (§4.1) | `cutoff`, `heartbeat_cutoff`, `error`, `sql_state` |
| WARN | `execution.event_retention_failed` | The retention tick's DELETE threw, or was cancelled at its bound (`sql_state=57014`; the 25 s `RETENTION_STATEMENT_TIMEOUT_SECONDS`, #326) — the DELETE is one statement, so a cancel deletes nothing; the next tick retries. Counted as `datapipelines.scheduled_jobs.tick_failed{job=event_retention}` (§4.1) |
| ERROR | `retention.step_failed` | One step of the hourly retention sweep threw past its own handling (`step` = `execution_events`, `dashboard_refreshes`, `parameter_evaluation_sweep`, `parameter_evaluations`, `keys` or `audit_log`); the sweep runs its remaining steps and the next tick retries. For `keys` this is the keys purge's only failure line: its statements carry the retention bound, and a cancel (`sql_state=57014`) rolls the whole purge transaction back. Counted as `tick_failed` under the step's job (§4.1): `execution_events` → `job=event_retention`, `dashboard_refreshes` → `job=dashboard_refresh_retention`, `parameter_evaluation_sweep` → `job=parameter_evaluation_sweep`, `parameter_evaluations` → `job=parameter_evaluation_retention`, `keys` → `job=keys_purge`, `audit_log` → `job=audit_retention`. Every step but `keys` counts the store faults it catches itself (its own WARN line); this line and its count are only what escaped that catch, so no failure is counted twice (#636) | `step`, `error`, `sql_state` | `cutoff`, `error`, `sql_state` |
| INFO | `execution.swept` | A stale-execution sweep (15 s fixed delay on `dp-scheduled`) marked at least one stale RUNNING row ABORTED with `pipeline.execution.instance_lost`; silent for zero rows. A stale heartbeat, or an old start with no heartbeat, triggers the UPDATE | `count`, `cutoff`, `heartbeat_cutoff`, `message` (static explanation) |

#### 3.4J The dashboard refresh events (#10 L2)

The dashboard runtime ([Dashboards §5](dashboards.md#5-the-runtime)) logs at these points. The WARN/ERROR failure rows name their cause by class (`error=`) and, for a store, by SQLState — never by message (the 321 rule, §3.4G). The two DEBUG stream-write diagnostics attach a throwable; its message and stack remain diagnostic text, so this catalogue does not claim every dashboard log message is sanitized. No row below deliberately logs a selection, binding value, result row or execution message as a field.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `dashboard.refresh_finished` | A refresh ended (any status) | `refresh_id`, `status`, `targets` |
| WARN | `dashboard.refresh_never_admissible` | A refresh was refused at once because its distinct executions exceed the smaller of `max-executions-per-refresh` and the executor's instance ceiling, so it can never be admitted here (#762): the 429 `dashboard.refresh.saturated` carries `details.reason=never_admissible`, no refresh row is written, and the refusal counts under `reason=never_admissible` (§4.1). One line per refused attempt; `key` is the configuration key to raise ([Configuration §3.34](configuration.md#334-the-dashboard-runtime-10-l2)) | `workspace_id`, `dashboard_id`, `invocations`, `ceiling`, `key` |
| ERROR | `dashboard.refresh_work_failed` | The refresh's work threw unexpectedly; the row is still closed `FAILED` | `refresh_id`, `error` |
| ERROR | `dashboard.refresh_end_failed` | A terminal step (`finish` — the row write — or `audit`) threw; the remaining steps and the last frame still ran | `refresh_id`, `step`, `error` |
| ERROR | `dashboard.refresh_link_failed` | The `dashboard_refresh_executions` link could not be written for a started source; the execution carries on unlinked | `refresh_id`, `error` |
| WARN | `dashboard.source_not_started` | A source's RUNNING row could not be written (fail-closed): the source did not run | `refresh_id`, `source`, `error` |
| INFO | `dashboard.refresh_stream_cut` | A stream was cut because its subscriber's standing no longer holds (P4); the refresh keeps running | `refresh_id`, `verdict` |
| INFO | `dashboard.refresh_client_gone` | A stream's client vanished before `refresh_completed`; the disconnect grace begins | `refresh_id`, `grace_seconds` |
| INFO | `dashboard.refresh_grace_elapsed` | The grace elapsed: the refresh is aborted | `refresh_id`, `action=abort` |
| WARN | `dashboard.refresh_abort_cancel_failed` | The abort route could not cancel one of the refresh's executions through the executor | `refresh_id`, `error` |
| WARN | `dashboard.refresh_abort_flag_unreadable` / `dashboard.refresh_abort_flag_not_cleared` | Redis could not be read / cleared for the refresh-level abort flag (`dp:refresh-abort:{refresh_id}`); an unreadable flag reads as "not requested" and the next poll retries | `refresh_id`, `error` |
| WARN | `dashboard.refresh_swept` | The stale sweep closed a `RUNNING` refresh past its deadline as `TIMED_OUT` (metadata-db §8.4) | `refresh_id` |
| WARN | `dashboard.refresh_sweep_failed` | The sweep's UPDATE threw, or was cancelled at its bound (`sql_state=57014`; the 10 s `REFRESH_SWEEP_STATEMENT_TIMEOUT_SECONDS`, #636) — the UPDATE is one statement, so a cancel closes nothing; the next tick retries. Counted as `datapipelines.scheduled_jobs.tick_failed{job=dashboard_refresh_sweep}` (§4.1) | `error`, `sql_state` |
| INFO | `dashboard.refreshes_purged` | The retention step deleted finished refreshes (nothing is logged for an empty tick) | `count`, `retention_days` |
| WARN | `dashboard.refresh_retention_failed` | The retention DELETE threw, or was cancelled at its bound (`sql_state=57014`; the 25 s `RETENTION_STATEMENT_TIMEOUT_SECONDS`, #636) — the DELETE is one statement, so a cancel deletes nothing; the next tick retries. Counted as `datapipelines.scheduled_jobs.tick_failed{job=dashboard_refresh_retention}` (§4.1) | `error`, `sql_state` |
| WARN | `dashboard.refresh_start_marker_unwritable` | Redis failed while registering one in-flight start marker or its bounded per-principal index; the start proceeds without a reliable marker. A pre-row abort can therefore answer 404 | `refresh_id`, `error` (exception class) |
| WARN | `dashboard.refresh_start_marker_unreadable` | One Redis marker lookup failed; it returns no marker and grants no abort authority on the fault. One line per failed lookup | `refresh_id`, `error` (exception class) |
| WARN | `dashboard.refresh_start_marker_not_cleared` | One Redis cleanup of the start marker/index failed; their TTLs expire the residue. One line per failed clear | `refresh_id`, `error` (exception class) |
| WARN | `dashboard.refresh_stream_authority_failed` | The subscriber-authority check threw; access is treated as revoked with no execution link (fail-closed). One line per failed check | `error` (exception class) |
| WARN | `dashboard.refresh_tick_failed` | The registry heartbeat/disconnect-grace tick threw; the timer survives and the next tick retries at `datapipelines.sse.heartbeat-interval-seconds`. One line per failed tick | `error` (exception class) |
| DEBUG | `dashboard.refresh_stream_gone` | An SSE frame or heartbeat write threw IOException, or a heartbeat found the emitter already completed; marks the stream disconnected. One line per failed write; throwable attached | `refresh_id`, `while` (frame event name or `heartbeat`), `state=completed` on the already-completed heartbeat variant |
| DEBUG | `dashboard.refresh_stream_closed` | A frame write found the emitter already completed (IllegalStateException); marks the stream disconnected. One line per failed frame write; throwable attached | `refresh_id`, `while` (frame event name) |

#### 3.4K The live-write outage events (#336)

`pipeline_executions.heartbeat_at` is the stale-execution sweep's primary signal ([Metadata DB §8.3](metadata-db.md#83-stale-execution-sweep)): a store that refuses the live writes makes a LIVE execution look dead to the sweep on another instance. `JdbcExecutionProgress` logs its write failures by §3.2's degradation rule — **once per execution per outage**, on the healthy → failing transition, with an INFO when a write answers again — because a heartbeating execution writes at a fixed cadence and a per-refusal line would be a second outage in the log pipeline. The failures between the transition lines are counted by the metric, not logged.

| Level | `event=` | When | Fields |
|---|---|---|---|
| WARN | `execution.progress_write_failed` | An execution's first refused live write after a healthy one — a heartbeat or a progress write (`kind` says which); a steady presence means the metadata store is refusing writes while executions run, and the stale sweep on another instance is reading their heartbeats going stale | `execution`, `kind`, `error`, `sql_state` |
| INFO | `execution.progress_write_recovered` | A live write answered again after the outage — one line, per execution | `execution` |

A refusal is named by `error` (its class) and `sql_state` (`FailureShape`, the §3.4G rule), never the driver's message.

#### 3.4L The selector-lease cleanup events (#336)

`ReadOnlyStatementLease`'s cleanup endings ([Datasources §5.3](datasources.md#53-lease-lifecycle)) log one WARN per refused ending — a statement close, a connection close, or a statement cancel in `abandon()`. The cancelled-connection path's discard runs unconditionally; the WARN is evidence, not a reason to keep a connection in service.

| Level | `event=` | When | Fields |
|---|---|---|---|
| WARN | `datasource.lease_cleanup_failed` | A selector lease's statement close, connection close or statement cancel was refused; a refused connection close DISCARDS the connection (never returned to service) | `datasource`, `operation` (`statement_close`/`connection_close`/`statement_cancel`), `error`, `sql_state` |

#### 3.4M The parameter evaluation events (#376)

The parameter-set evaluation history ([REST API §21.5](rest-api.md#215-the-observed-evaluation), [Metadata DB §8.1](metadata-db.md#81-execution-event-cleanup) retention, [§8.5](metadata-db.md#85-stale-parameter-evaluation-sweep) sweep) logs at these points. Every failure line names its cause by class (`error=`) and, for a store, by SQLState (`sql_state=`) — never by message (the 321 rule, §3.4G); NO line carries a selection, a binding value, a row or a query's text. A history write that fails is logged at ERROR and the evaluation carries on unrecorded: that is the #376 design — the record is a side channel of the evaluation, never its master — so a steady presence of `parameter.evaluation_record_failed` means the metadata store is refusing the history writes while evaluations still answer.

| Level | `event=` | When | Fields |
|---|---|---|---|
| ERROR | `parameter.evaluation_record_failed` | One history write was refused by the store (`DataAccessException`); the evaluation continues unrecorded for that write | `write` (`start`/`query_queued`/`query_ended`/`end`/`abandoned_queries`), `evaluation_id`, `workspace_id`, `parameter_set_id`, `error`, `sql_state` (`none` when no SQLState is in the cause chain — `FailureShape`'s spelling, never `null`) |
| WARN | `parameter.evaluation_defect` | The evaluator hit an unexpected `RuntimeException` (a defect, never an author's problem); the record closes `FAILED` with no code and the exception is rethrown | `evaluation_id`, `error` |
| ERROR | `parameter.evaluation_failed` | The observed (page) route's evaluation threw a non-domain exception; its failure frame carried the stand-in code only, so the cause is logged here by class and SQLState, with no throwable attached (its message can carry SQL or a value); the stack is the `parameter.evaluation_failed_cause` DEBUG row's. A `parameter.evaluation_defect` precedes it when the throw came from the evaluator itself | `evaluation_id`, `error` (the class's binary name), `sql_state` (`FailureShape`'s spelling; `none` when absent) |
| DEBUG | `parameter.evaluation_failed_cause` | The throwable behind a `parameter.evaluation_failed` line, stack attached (diagnosis only; DEBUG is off by default) | `evaluation_id` |
| INFO | `parameter.evaluation_refused` | The observed route's evaluation was refused as a whole (a domain error — timeout, `response_too_large`); its `evaluation_failed` frame was already written | `evaluation_id`, `code` |
| INFO | `parameter.evaluation_ended` | The stream's last frame: the evaluation ended (any outcome) and the stream closed | `evaluation_id`, `outcome`, `delivered` (whether the last frame reached a connected, still-authorised client) |
| WARN | `parameter.evaluation_observer_failed` | The stream observer threw while receiving an event; the stream is closed and the evaluation carries on (the stream is a side channel) | `while` (the event's class), `error` |
| INFO | `parameter.evaluation_stream_cut` | A stream was cut because its subscriber's standing no longer holds (expired session, revoked key, lost permission); the evaluation keeps running | `evaluation_id`, `verdict` |
| WARN | `parameter.evaluation_stream_authority_failed` | The authority's check threw; the stream is treated as revoked (fail-closed) | `error` |
| INFO | `parameter.evaluation_client_gone` | A stream's client vanished before the evaluation ended; the disconnect grace begins | `evaluation_id`, `grace_seconds` |
| INFO | `parameter.evaluation_grace_elapsed` | The grace elapsed with no client: the evaluation is aborted | `evaluation_id`, `action=abort` |
| WARN | `parameter.evaluation_tick_failed` | The stream registry's heartbeat/grace tick threw; the timer survives and the next tick retries | `error` |
| DEBUG | `parameter.evaluation_stream_gone` | A frame or heartbeat write found the client gone (`IOException`; with `state=completed`, the emitter was already completed); the stream is marked disconnected. Carries the exception's stack trace | `evaluation_id`, `while` (the frame's SSE event name, or `heartbeat`), `state=completed` on one heartbeat variant |
| DEBUG | `parameter.evaluation_stream_closed` | A frame write found the emitter already complete (`IllegalStateException`); the stream is marked disconnected. Carries the exception's stack trace | `evaluation_id`, `while` (the frame's SSE event name) |
| WARN | `parameter.evaluation_swept` | The stale sweep closed a `RUNNING` record past its deadline as `INCOMPLETE` (metadata-db §8.5); one line per record | `evaluation_id`, `message` |
| WARN | `parameter.evaluation_sweep_failed` | The sweep's UPDATE threw, or was cancelled at its bound (`sql_state=57014`; the 25 s `RETENTION_STATEMENT_TIMEOUT_SECONDS`, #636) — the UPDATE is one statement, so a cancel closes nothing; the next tick retries. Counted as `datapipelines.scheduled_jobs.tick_failed{job=parameter_evaluation_sweep}` (§4.1) | `error`, `sql_state` |
| INFO | `parameter.evaluations_purged` | The retention step deleted finished evaluation records (nothing is logged for an empty tick); the plural twin of `dashboard.refreshes_purged` | `count`, `retention_days` |
| WARN | `parameter.evaluation_retention_failed` | The retention DELETE threw, or was cancelled at its bound (`sql_state=57014`; the 25 s `RETENTION_STATEMENT_TIMEOUT_SECONDS`, #636) — the batch is one statement, so a cancel deletes nothing; the next tick retries. Counted as `datapipelines.scheduled_jobs.tick_failed{job=parameter_evaluation_retention}` (§4.1) | `error`, `sql_state` |
| WARN | `parameter.evaluation_retention_incomplete` | A retention batch came back full (a backlog): records older than the cutoff remain and the next tick takes the next batch | `count`, `message` |
| ERROR | `parameter.selector_statement_abandoned` | The evaluate's await was cancelled (its `withTimeout` deadline, or the caller — #375's disconnect-grace abort, a shutdown) while the selector statement still ran; the statement is cancelled best-effort, its connection discarded, the worker keeps its slot until the driver returns (`datapipelines.parameters.selectors.abandoned` counts it). One line, no stack trace, never the SQL or a bind | `set`, `parameter`, `datasource`, `cause` (`deadline`/`caller`) |

#### 3.4N The audit retention events (#310, #443)

Audit retention is the last step of the hourly fixed-delay retention sweep on `dp-scheduled` (§3.4H, §7). Each tick reads one cutoff from the database clock and deletes bounded batches. These lines describe that tick's committed deletions, including a tick that subsequently fails; they are not audit-row contents. Failures carry class and SQLSTATE under §3.4G's rule. A continuing incomplete/failed pattern means retention is falling behind or the metadata store is unavailable.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `audit.retention` | Once per tick that deleted at least one row, including a tick that later failed; silent for zero deletions | `purged`, `cutoff`, `batches` |
| WARN | `audit.retention_failed` | A metadata-DB operation threw, or was cancelled at its bound (`sql_state=57014`; the cutoff read and every batch carry the retention's 25 s bound, #326 — a cancelled batch rolls back alone); the tick stops, prior batches stay committed and counted, the next tick retries. Once per failed tick | `purged`, `batches`, `cutoff` (null if reading it failed), `error` (exception class), `sql_state` |
| WARN | `audit.retention_incomplete` | The tick reached its batch ceiling or time budget after a full batch, rather than observing a short batch. Once per bounded tick; the next tick continues with a fresh cutoff, without an additional query proving the backlog size | `purged`, `cutoff`, `batches`, `reason` (`batch_ceiling`/`time_budget`), `message` (static explanation) |

`audit.write` is catalogued in §3.4G; these three rows complete the four-name `audit` namespace.

#### 3.4O The execution shutdown drain events (#443)

These lines occur once per execution-drain stop, after scheduler admission closes and before the web server's graceful shutdown and persistence drain (§3.4G). Readiness flips before cancellation; the drain then cancels local executions through their ordinary statement/job path and waits for deregistration, retrying cancellation during the wait. The default flush bound is 20 s, with 100 ms polls. This is a lifecycle sequence, not a periodic job. The persistence and scheduled-job shutdown outcomes remain in §3.4G/H.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `shutdown.readiness_refused` | Readiness was published REFUSING_TRAFFIC before cancelling work; `/ready` now reports 503 | `message` (static explanation) |
| INFO | `shutdown.drain_cancelled` | The first cancellation pass returned; `live` is the registry's remaining count at logging time, not the number cancelled | `live`, `reason=shutdown` |
| INFO | `shutdown.drain_complete` | The bounded wait ended with no local executions registered; their deregistration follows terminal bookkeeping attempts, not a guarantee every store write succeeded | — |
| WARN | `shutdown.drain_incomplete` | Executions remain registered at the flush deadline; their rows may remain RUNNING after exit and need the stale sweep. One alternative outcome to `drain_complete` | `live`, `flush_timeout_ms`, `message` (static explanation) |

#### 3.4P The lake reference index backfill events (#778)

At boot the index backfill scans each RELEASED pipeline and parameter-set version that has no scan row at the current scanner version ([Datasources §8C.9](datasources.md#8c9-which-versions-read-which-table--the-lake-reference-index)). It scans one version per transaction, a page at a time, and stops when a page scans nothing. A version whose scan throws gets no row: it is logged once by identity, and the next boot tries it again. The rendered SQL and the parser's message are never logged. The promotion check is not affected by a failure here: it reads only the versions a batch lands, and those are scanned inside the batch's own transaction.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `lake.reference_index.backfill` | Once per boot, after the pass ends; `scanned` counts the scan rows written, `failed` the versions whose scan threw | `scanned`, `failed`, `scanner_version` |
| WARN | `lake.reference_index.backfill_failed` | One version's scan threw. Its transaction rolled back, it was left unscanned, and the pass went on to the next version | `family`, `entity_id`, `version`, `error_type` (exception class only) |

#### 3.4Q The managed lake boot check (#785)

When `datapipelines.lake.enabled=true`, `LakeBootCheck` runs once after Flyway ([Configuration §7](configuration.md#7-config-validation), [Module structure §5.21](module-structure.md#521-lake)). A passing check logs one line; a failing one logs nothing of its own and refuses startup.

| Level | `event=` | When | Fields |
|---|---|---|---|
| INFO | `lake.boot.ok` | Every check passed: the catalog shape, the four V53 triggers, the control row, the store reachable and honouring `If-None-Match: *` | `bucket`, `prefix` |
| INFO | `lake.egress_proxy_verified` | #845: before the managed datasource is registered, the configured egress proxy declared the configured max-age at `/dp-egress` and a `HEAD` of the bucket through it reached the store (any store answer counts; only a proxy-generated 502/503/504 does not) | `proxy` (`host:port`), `max_age_seconds`, `reader_end_by_ms` (the bound a reader against a store that trickles on every attempt ends within: `LakeHttpBounds.egressReaderEndByMillis`, (`http_retries` + 1) × max-age + slack, [Lake §9](lake.md#9-reads)), `store` (the bucket URI, no credential), `store_status`, `duration_ms` |
| WARN | `lake.egress_proxy_absent` | #845: `datapipelines.lake.reads.egress-proxy` is empty, so managed reads reach the store directly with no connection max-age ([Lake §9](lake.md#9-reads)) | — |

**The refusals are startup failures, not log events.** Each is an `IllegalStateException` whose message starts with its id, so the operator greps the boot failure for it: `lake.boot.catalog_shape`, `lake.boot.trigger_missing`, `lake.boot.control_row`, `lake.boot.storage_unreachable`, `lake.boot.conditional_put`, `lake.boot.egress_proxy` (#845: the configured egress proxy is malformed, does not answer within its 2 s connect / 5 s read bound, declares another max-age, or answers a 502/503/504 of its own for the store), and `lake.boot.datasource_name_taken` (#818, raised when the managed datasource is registered: another datasource holds `datapipelines.lake.name`) (what each means: Configuration §7). No credential, key, or object body appears in any of them.

#### 3.4R The lake table definition apply events (#798)

`LakeDefinitionApplier` brings a managed lake table's Iceberg state to its current released definition ([Lake §3](lake.md#3-apply-states)). Every apply or verify attempt that reached the table logs exactly one line, under the applier's logger, when it ends. An attempt that found nothing to do (the version is no longer current, or the row is not in a state the call moves) logs nothing. Each line carries `operation` (`lake.definition.apply` or `lake.definition.verify`), `table_id`, `namespace`, `table`, `version` and `operation_id`. For a verify, `version` is the table's current definition version and `operation_id` is the id of the interrupted attempt it settles. An apply step that fails with an exception the catalog does not classify — any step of the apply, the commit included — also logs one `lake.definition.apply_unclassified` ERROR line where the failure is caught, before the attempt's ending line (#811, #822); a second failure of the same attempt (a lease release after a pre-commit failure) logs its own. The definition body, the table location and every `datapipelines.lake.storage` value are never logged.

| Level | `event=` | When | Fields (beyond the common six) |
|---|---|---|---|
| INFO | `lake.definition.applied` | The table holds the version, and the row is `applied`. `committed=false` means the table already matched and no Iceberg commit was made | `committed` |
| WARN | `lake.definition.apply_failed` | A definite refusal (an unsupported change, drift, an Iceberg validation refusal) was recorded, and the row is `failed` | `code` (a `lake.definition.*` code, [Pipeline Contract §13.25](pipeline-contract.md#1325-managed-lake)) |
| INFO | `lake.definition.apply_deferred` | Nothing was committed, and a later attempt applies the table. With `reason=namespace`, `table` or `deadline`, a lease could not be taken and the row is unchanged (`code=lake.definition.table_busy`). With `reason=none`, the row is `pending` again: `code` names the catalog refusal (the fence, restoring mode), is `lake.definition.apply_unclassified` after an `apply_unclassified` line from a step before the commit, or is `null` when a verify found that the interrupted attempt committed nothing | `code`, `reason` |
| WARN | `lake.definition.apply_unknown` | The attempt cannot say what it left: the commit's outcome is unknown, or DP's metadata store failed at or after the `applying` mark so the row could not be stamped. An `applying` row stays until `verifyApplying` settles it from the revision log | `code` (`lake.catalog.outcome_unknown`, or `lake.definition.apply_unclassified` after an `apply_unclassified` line) |
| ERROR | `lake.definition.apply_unclassified` | A step of the apply failed with an exception that is not one of the catalog's refusals; nothing is thrown. Before the commit the row is `pending` and the attempt ends with `apply_deferred`; from the commit on, or when the row could not be stamped, it ends with `apply_unknown`; after a failed lease release or session close the attempt's own ending line stands. `operation` is always `lake.definition.apply`; `namespace` and `table` are `null` when the table's own row could not be read | `step` (where the failure was caught: `deadline`, `pre_commit`, `session_open`, `metadata_store`, `lease_release`, `session_close`, `commit`), `cause`, `sql_state` (the exception's class and first SQLSTATE, §3.4G; never its message or stack) |
| INFO | `lake.definition.apply_superseded` | The attempt's conditional stamp changed no row, because a newer attempt owns the table. This attempt recorded nothing | — |

The five audit events of the same lifecycle (`lake.definition.released`, `.applied`, `.apply_failed`, `.discarded`, `.draft_purged`, [Enums §15](enums.md#15-authauditevent--auth-audit-log-events)) are separate rows in `audit_log`. A log line is not an audit row, and the audit row does not replace the log line.

#### 3.4S Lake delivery, publication and settle events (#799)

Delivery storage failures report the exception class and first SQLSTATE using §3.4G's safe shape. SDK messages, cause chains, credentials, storage endpoints and object paths are never logged by this boundary.

| Level | Event | When | Fields |
|---|---|---|---|
| WARN | `lake.delivery.listing_failed` | The trusted S3 listing failed at the SDK boundary. The caller receives a fixed message with no SDK cause and may retry | `cause`, `sql_state` |
| WARN | `lake.delivery.admission_failed` | Metadata admission failed before staging; safe fixed retryable refusal | `execution_id`, `cause`, `sql_state` |
| INFO | `lake.delivery.landed` | Slot/scratch, trusted manifest and every target's ledger receipt were admitted | `execution_id`, `targets`, `files`, `bytes`, `deadline`, `cause=none`, `sql_state=none` |
| INFO | `lake.delivery.already_published` | Permanent duplicate evidence skips this target's append | `delivery_id`, `operation_id`, `message="already published"`, `cause=none`, `sql_state=none` |
| INFO | `lake.append.started` | Grant and table lease admitted the conditional PUBLISHING claim | `delivery_id`, `operation_id`, `table_id`, `deadline`, `cause=none`, `sql_state=none` |
| INFO | `lake.delivery.concluded` | Delivery and attempt stamps committed together; zero-row stale transitions emit nothing | `table_id`, `delivery_id`, `operation_id`, `outcome`, `settled`, `cause=none`, `sql_state=none` |
| WARN | `lake.append.failed` | Publication unwound after recording a safe failed/unknown verdict where possible | `delivery_id`, `operation_id`, `cause`, `sql_state` |
| WARN | `lake.append.stamp_failed` | Outcome stamping failed; permanent revision evidence and the sweep recover the verdict | `delivery_id`, `operation_id`, `cause`, `sql_state` |
| WARN | `lake.append.lease_release_failed` | Bounded lease release failed; expiry/takeover remains authoritative | `delivery_id`, `operation_id`, `cause`, `sql_state` |
| WARN | `lake.append.heartbeat_failed` | Periodic metadata renewal failed; the watch loses authority and later mutations stop | `operation_id`, `cause`, `sql_state` |
| WARN | `lake.append.settlement_deferred` | Immediate settlement failed and leaves recovery to a later sweep | `delivery_id`, `operation_id`, `cause`, `sql_state` |
| WARN | `lake.delivery.cleanup_deferred` | Pre-publication cleanup could not stamp every receipt; resources were released and terminal executions are sweepable | `execution_id`, `cause`, `sql_state` |
| INFO | `lake.settle.sweep` | A normal-mode tick finished its rotating table page, or stopped where its work budget ran out | `tables` (managed tables visited), `rows` (deliveries visited), `changed` (deliveries moved to a terminal state), `exhausted` (the tick's budget was spent), `deferred_budget`, `deferred_stuck`, `deferred_failed` (the tables whose recovery stopped short because the budget was spent, a metadata read could not be waited for, or an exception), `cause=none`, `sql_state=none` |
| WARN | `lake.settle.metadata_read_stuck` | A metadata read outlived the tick's budget and was abandoned on its own thread. It holds no lease and no connection and stops itself at its next chunk from the store ([Lake §8](lake.md#8-settlement-and-recovery)); every table's verification and repair are deferred until it ends | `table_id`, `elapsed_ms`, `stuck_total` (abandoned reads since boot), `cause=none`, `sql_state=none` |
| INFO | `lake.settle.metadata_read_ended` | A later tick found the abandoned read finished: its result was discarded and reads resume | `table_id`, `elapsed_ms` (the read's whole life), `cause` (`abandoned` when it stopped itself after the tick abandoned it, the failure's class when it failed, `none` when it completed), `sql_state` |
| WARN | `lake.settle.deferred` | One table's recovery failed; later eligible tables/pages remain recoverable | `table_id`, `cause`, `sql_state` |
| WARN | `lake.settle.sweep_deferred` | The bounded sweep failed outside an individual table, including a held control-row lock | `cause`, `sql_state` |
| WARN | `lake.append.recipe_write_failed` | The immutable recipe PUT failed before catalog publication. No storage message or cause chain crosses the boundary | `cause`, `sql_state` |
| WARN | `lake.file.delete_refused` | Iceberg requested a file deletion. The production FileIO records the key and refuses it; files remain for the maintenance protected-graph check, including manifest-merge cleanup | `path`, `cause=LibraryDeleteRefusedException`, `sql_state=none` |

`lake.delivery.published`, `.failed`, `.unknown` and `.settled` are separate audit events
([Enums §15](enums.md#15-authauditevent--auth-audit-log-events)), written strictly in the same metadata transaction
as their conditional state/attempt changes. Their details contain only `table_id`, `delivery_id`, `operation_id`
and `outcome`. A lost acknowledgement can record UNKNOWN followed by PUBLISHED and SETTLED without a second CAS.
Ingestion storage setup failures use the staging boundary's fixed message and log only execution id and class;
generated secret SQL, credentials and driver messages never reach an execution event or recipe.

### 3.5 Log destination

- **Stdout** by default — collected by container runtime (Docker / k8s) and shipped to the operator's log aggregator (CloudWatch, Stackdriver, Loki, ELK, etc.).
- **No file logging in container.** Operators choose the aggregation strategy.
- **Local dev**: human-readable console output — the `console` value of `datapipelines.observability.logging.format` ([configuration.md §3.15](configuration.md#315-observability)), the value `deploy/env/defaults.env` ships for local runs (#337).

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
| `datapipelines.executions.concurrent` | gauge | (none) | Currently-running executions; bound by the execution-slot bean, once per instance |
| `datapipelines.executions.lifecycle_write_failed` | counter | (none) | (#311) The terminal UPDATE of `pipeline_executions` failed, outlived its bound, or matched no row (the RUNNING insert failed — #325) (`datapipelines.executor.lifecycle-write-timeout-seconds`, [Configuration §3.2](configuration.md#32-executor)), or was deferred behind a RUNNING insert that outlived its bound (#325 — counted once when deferred; the deferred UPDATE's own failure or 0 rows is a WARN, not a second count; [DAG Executor §10.1](dag-executor.md#101-how-an-emitted-event-becomes-durable-266)): the row's outcome is unconfirmed, so this counter is how an operator tells an unrecorded outcome apart from silence. The companion WARN names the execution id. The RUNNING insert's failure is deliberately NOT counted here — for scheduled runs it is the fail-closed refusal the scheduler records (`record_unwritable`), for interactive ones a WARN — the counter exists because a terminal row that stays RUNNING otherwise looks exactly like a dead instance |
| `datapipelines.scheduled_jobs.tick_failed` | counter | `job` (`stale_sweep`/`event_retention`/`audit_retention`/`keys_purge`/`dashboard_refresh_sweep`/`dashboard_refresh_retention`/`parameter_evaluation_sweep`/`parameter_evaluation_retention`) | (#326, #636) A tick of one of the eight metadata `@Scheduled` jobs failed — a statement that threw, or one cancelled at its JDBC bound (`sql_state=57014` in the companion line tells the two apart): the stale sweep's UPDATE (10 s, `execution.sweep_failed`), the `execution_events` DELETE (25 s, `execution.event_retention_failed`), the audit retention's cutoff read and batches (25 s, `audit.retention_failed`), the keys purge's FK read and two DELETEs (25 s, `retention.step_failed step=keys`), the dashboard refresh sweep's UPDATE (10 s, `dashboard.refresh_sweep_failed`), the dashboard refreshes' retention DELETE (25 s, `dashboard.refresh_retention_failed`), the parameter evaluations' sweep UPDATE (25 s, `parameter.evaluation_sweep_failed`) and retention DELETE (25 s, `parameter.evaluation_retention_failed`). A retention-tick step's failure that escapes its own catch is counted once by `retention.step_failed` instead, never twice. The bounds are code constants beside the cadences, not configuration keys ([Configuration §3.11](configuration.md#311-execution-history)). The tag is the closed `ScheduledJob` set; each job registers its series when it is built, so a deployment reports `0` rather than an absent series. A steady `stale_sweep` rate means crashed instances' rows are not being reaped |
| `datapipelines.executions.progress_write_failed` | counter | (none) | (#336) A live heartbeat or progress write failed while the execution runs — the row's `heartbeat_at` and `node_stats_json` lag the live work, and another instance's stale sweep reads the stale heartbeat as instance loss ([Metadata DB §8.3](metadata-db.md#83-stale-execution-sweep)). Counts every refusal; the WARN is once per execution per outage (§3.4K), so between the transition lines the counter is the only evidence. No tags: one closed condition |
| `datapipelines.nodes.duration` | timer | `pipeline_id`, `node_id`, `source` | Per-node duration |
| `datapipelines.nodes.rows_out` | counter | `pipeline_id`, `node_id` | Rows emitted by node |
| `datapipelines.staging.rows` | counter | (none) | Total rows staged across all executions |
| `datapipelines.staging.scratch.capacity` | gauge | (none) | (#806) The DuckDB staging scratch aggregate, `datapipelines.lake.admission.scratch-bytes`, in bytes ([Staging §8.5](staging.md#85-the-duckdb-engine)). Constant for the process. Bound once at startup; every gauge of this family is read at the scrape itself, never through a health probe |
| `datapipelines.staging.scratch.reserved` | gauge | (none) | (#806) Bytes held by the reservations of running DuckDB staging databases (and unopened delivery admissions); falls back when they release. Leftovers are **not** included — add `…scratch.leftover` to see what the aggregate has given away |
| `datapipelines.staging.scratch.leftover` | gauge | (none) | (#806) Bytes of leftover files a crashed process left that the factory's sweep counted against the aggregate (#803). **`NaN` until the sweep has counted** — it runs before the first DuckDB admission, so a process that has not yet used DuckDB staging reports `NaN`, never `0`; `0` means swept and nothing counted. A snapshot: once counted it does not change in this process. Separate from `…scratch.reserved` |
| `datapipelines.staging.scratch.usable` | gauge | (none) | (#806) Usable bytes of the file store the scratch directory lives on — of its nearest existing parent while the directory does not exist yet (it is created at the first DuckDB open). Read at every scrape; **`NaN` when the reading throws**, and the next good scrape reports the number again — never `0` for unknown, never a stale value. The directory's path is never a tag or a value (§6.4) |
| `datapipelines.script.evaluations.abandoned` | gauge | (none) | (#708 renamed from the un-prefixed `script.evaluations.abandoned`) Script evaluations abandoned past their wall clock plus `datapipelines.script.abandon-grace-seconds` ([Configuration §3.28](configuration.md)): the evaluation pool's bulkhead keeps the thread's slot until it really ends, and each one also logs at ERROR ([DAG executor §5.3](dag-executor.md#53-concurrency-controls)). A cumulative count over the process's life; any rise means a runaway script outlived its budget |
| `datapipelines.parameters.selectors.abandoned` | gauge | (none) | (#708 renamed from the un-prefixed `parameters.selectors.abandoned`) Selector statements abandoned at their evaluate's deadline — cancelled best-effort, the connection discarded, the worker keeping its slot until the driver returns; each one also logs `parameter.selector_statement_abandoned` (§3.4M). A cumulative count over the process's life; a scrape also releases the dead worker threads |
| `datapipelines.datasource.pool.retired` | counter | `datasource_name` | Pools taken out of the live map by an edit, a delete, a peer's invalidation message or a reconcile ([Datasources §5.2](datasources.md#52-pool-lifecycle)). One increment per retirement, not per connection |
| `datapipelines.datasource.pool.hard_closed` | counter | `datasource_name` | Retired pools closed at their ceiling (`retire-ceiling-seconds`, or `managed-retire-ceiling-seconds` for a managed lake generation, #824) with connections **still out** — a statement outlived the ceiling and lost its connection. A subset of `retired`; a non-zero rate means either a genuinely hung query or a ceiling set below what this deployment's statements really take. Each one also logs the §3.4A `datasource.pool_hard_closed` WARN |
| `datapipelines.lake.reader_lease.lost` | counter | (none) | Generations losing reader protection, once each; the generation retires, executing readers may fail after TTL |
| `datapipelines.lake.reader_lease.overlong` | counter | (none) | Live readers older than the hour slice, once per generation; pins remain protected |
| `datapipelines.lake.reader_lease.round_slow` | counter | (none) | Keeper rounds longer than the 5 s cadence |
| `datapipelines.datasource.pool.close_stuck` | counter | `datasource_name` | (#818d) Retired pools whose close was still running at their own deadline (§3.4A's `deadline_seconds`; #840) after the reaper handed it off. Each one also logs the §3.4A `datasource.pool_close_stuck` WARN. A non-zero rate means a driver or Hikari shutdown that does not return; the close is never retried |
| `datapipelines.datasource.pool.reaper_pass_slow` | counter | (none) | (#818d) Pool reaper passes that ran past their 1 s bound, the one the managed reads lifetime rule counts on. Each one also logs the §3.4A `datasource.reaper_pass_slow` WARN. No tag: the pass is per instance, not per pool |
| `http.server.requests` | timer | `method`, `uri`, `status`, `outcome` | HTTP request duration. **Spring Boot's own metric — unprefixed.** `uri` is the templated path (`/api/v1/executions/{id}`), never the expanded one. |
| `datapipelines.dashboard.refreshes` | counter | `status` (`COMPLETED`/`PARTIAL`/`FAILED`/`ABORTED`/`TIMED_OUT`) | (#10 L2) Dashboard refreshes by terminal status, counted when the refresh ends ([Dashboards §5](dashboards.md#5-the-runtime)). A refresh refused at admission is not one — see `…refresh.refused` |
| `datapipelines.dashboard.refresh.duration` | timer | `status` | A refresh's wall-clock time from admission to its terminal row |
| `datapipelines.dashboard.refresh.refused` | counter | `reason` (`saturated`/`never_admissible`/`stream_limit`) | Refreshes refused before they started: `saturated` is the busy `429 dashboard.refresh.saturated` (no room now, or the start marker at its bound; no row written), `never_admissible` the 429 whose `details.reason` is `never_admissible` — retrying cannot succeed until an operator raises the named setting (#771) — and `stream_limit` the per-user stream cap |
| `datapipelines.dashboard.refreshes.active` | gauge | (none) | Refreshes admitted and not yet ended on THIS instance (the admission counters are JVM-local, [Configuration §3.34](configuration.md)) |
| `datapipelines.dashboard.executions.reserved` | gauge | (none) | Dashboard executions reserved by those refreshes, against `max-concurrent-dashboard-executions-per-instance` |
| `datapipelines.promotion.lens.inventory` | counter | `outcome` (`hit`/`miss`/`unreachable`) | The promoter lens's inventory reads (178, §3.4D): `hit` served from the per-workspace cache window, `miss` probed the target and got an inventory, `unreachable` probed and failed (the failure is then cached for the window, so `unreachable` counts windows, not reads). A steady `unreachable` rate with no `miss` is a dead target; `hit`/`miss` ≫ 1 is the cache doing its job |
| `datapipelines.auth.login_rate_limit.saturated` | counter | (none) | Login rate-limit admissions made with the tracked-client table FULL — the limiter's fail-open branch ([Auth §11.5](auth.md#115-other-auth-configuration-keys)). The table is bounded at 10,000 client addresses; past that a new client is admitted unmetered rather than the map grown, so a spoofed-IP flood cannot exhaust the heap. Registered eagerly, so a healthy deployment reports `0` rather than an absent series. Any sustained non-zero rate means the login surface is unmetered for new clients right now. No tags: it is one closed condition, and §4.3 forbids inventing a dimension that is not a bounded set |
| `datapipelines.auth.secret_verification.failed` | counter | `cause` (`memory` \| `encoding` \| `other`) | (#847) Password and API-key secret verifications that failed NATIVELY — not a wrong secret: Argon2 could not allocate its memory block (`memory`), the stored encoded hash does not decode (`encoding`), or any other non-mismatch native code (`other`; the raw code is never a tag, §4.3). Each one answered `auth.secret_verification.unavailable` (503) and logged one ERROR line. All three series are registered eagerly, so a healthy deployment reports `0`. A sustained `memory` rate is native-memory pressure on the hashing path; any `encoding` is a corrupted stored hash an operator must repair. A JVM or container OOM-kill is not counted here — it ends the process before any answer |
| `datapipelines.web.endpoint_key_budget.saturated` | counter | (none) | (#224; documented by #708) The serve path's per-key request budget ([Configuration §3.22](configuration.md)) admitting a NEW `api_caller` key unmetered because its tracked-key table is FULL — the budget's fail-open branch, the `login_rate_limit.saturated` shape. The table is bounded at 10,000 key ids; at the ceiling rolled-over windows are swept first, and only a table still full counts here. Each one also logs `event=endpoint.key_budget_table_saturated`. Registered eagerly, so a deployment reports `0` rather than an absent series; any non-zero rate means new keys are reaching published endpoints without a budget. No tags: one closed condition |
| `datapipelines.audit.retention.purged` | counter | (none) | `audit_log` rows deleted by the audit-log retention job (§7, #310) — the sum of every tick's `purged=`, a batch that failed mid-tick included (its committed rows are gone). Registered when the job is built, so a deployment reports `0` rather than an absent series. The steady-state rate is roughly the write rate a retention window ago; a flat line on a deployment older than `datapipelines.audit.retention-days` means the job is not running |

**Result delivery, SSE and idempotency** ([REST API §7](rest-api.md#7-result-delivery), D9):

| Metric | Type | Tags | Description |
|---|---|---|---|
| `datapipelines.result.bytes_written` | counter | (none) | Bytes materialized into the Redis result store. Paired with `result.max-size-bytes` — the ratio of writes near the cap is the signal that a deployment is using result delivery as a bulk-data path (an explicit NOT-goal). |
| `datapipelines.result.writes` | counter | `outcome` (`stored`/`too_large`/`storage_unavailable`) | Result-store write attempts; the failure outcomes correspond 1:1 to `result.too_large` and `result.storage_unavailable`. |
| `datapipelines.result.cursor.reads` | counter | `format` (json/arrow/csv), `outcome` (`hit`/`expired`/`not_found`) | Cursor endpoint reads. `expired` rising relative to `hit` means the TTL is set too low for how clients actually page. |
| `datapipelines.sse.streams.active` | gauge | (none) | Currently-open SSE execution streams |
| `datapipelines.sse.stream.duration` | timer | `close_reason` (`completed`/`failed`/`aborted`/`client_disconnect`/`revoked`/`expired`) | Lifetime of an SSE stream. `client_disconnect` here is what feeds the disconnect-grace cancellation path (D7). `revoked` (#230, security-assurance P4) is a stream cut by the subscriber-authority re-judgement — a policy cut whose execution deliberately KEEPS running, never a disconnect, so the cancellation path never sees it. `expired` (#263) is the same policy cut where the refusal was the subscriber's validated token passing its `exp` — the same final comment and the same kept-running execution, a different credential fact, so an expired token is never counted as a standing revocation or a disconnect. The timer covers the LIVE stream; the log-served streams (the §10.3 replay and the idempotent-retry follow) record no duration, and carry the same reason in their cut's log line instead — `close_reason=expired` or `close_reason=revoked`, from the one verdict that refused (#293). |
| `datapipelines.idempotency.cache.hits` | counter | (none) | Requests served from a stored idempotent response |
| `datapipelines.idempotency.conflicts` | counter | (none) | `idempotency.key_reused_for_different_request` rejections |
| `datapipelines.web.request_body_drain` | counter | `outcome` (`completed`/`expired`/`saturated`/`client_gone`), `route` (`screenshot`/`platform`) | (#561, #559) Refused-body drains: eight fixed series registered at construction. Expiry and saturation log WARN `event=request.body_drain_expired` / `event=request.body_drain_saturated` with `declared_bytes`, `read_bytes`, `elapsed_ms`, and the closed route tag; no body, header, path or client string. Declarations above the byte bound do not attempt or count a drain. Expiry, saturation and over-bound declarations flush the 413 then return normally. Fixed context-wide swallowAbortedUploads=false makes every 413 close without reading the remainder, throwing or dispatching /error (70 s per expired drain / 560 thread-seconds for eight at the default timeout; no body read on the other two paths, at most one connector timeout). Completed drains have no declared remainder; client_gone has already failed I/O and is counted without another log: a context valve marks only the committed 413 read failure as reported before host error-page handling. |

**Scheduler** (#9, [Scheduler](scheduler.md)) — the scheduler's state is watched here, not through a health component (§6.1):

| Metric | Type | Tags | Description |
|---|---|---|---|
| `datapipelines.scheduler.occurrences` | counter | `outcome` (`queued`/`catch_up`/`missed`/`overlap`) | Occurrences the dispatcher recorded: `queued` a run, `catch_up` a run under the `latest` policy, `missed` one summary row per outage (however many occurrences it covers), `overlap` an occurrence skipped because a run was still active. A flat line while schedules exist means no instance is dispatching |
| `datapipelines.scheduler.runs.finished` | counter | `state` (`succeeded`/`failed`/`cancelled`/`aborted`/`unknown`/`not_started`/`skipped`) | Runs reaching a final state. A run whose `unknown` is later settled by the real outcome counts once for each |
| `datapipelines.scheduler.capacity.retries` | counter | (none) | Admissions refused for capacity and retried 30 s later (R4). Rising means `max-concurrent-runs` is saturated |
| `datapipelines.scheduler.notifications.sent` | counter | (none) | #442b: schedule notices the SMTP relay accepted, one per recipient |
| `datapipelines.scheduler.notifications.failed` | counter | (none) | #442b: deliveries finally `failed` at send — refused, out of tries, or mail off / domain narrowed out at send. Deliveries refused at enqueue (allowlist, backlog) are rows, not this counter |
| `datapipelines.scheduler.notifications.uncertain` | counter | (none) | #442b: deliveries that may have been sent (the connection failed after the data, or an interrupted claim). Each needs a person's look before a resend |
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

- Micrometer's Prometheus endpoint at `/actuator/prometheus` (`micrometer-registry-prometheus`, in `app` since #708) — served on a **separate management port** (`management.server.port`, framework wiring key), never on the application port. The management port exposes exactly two endpoints, `health` and `prometheus` (`management.endpoints.web.exposure.include`), and both answer **anonymously there**: the port, not a credential, is the boundary (#231 item 1, decided by the [failure-flow and telemetry record](superpowers/specs/2026-10-25-failure-flow-and-telemetry-design.md) §17 row 11). It binds `127.0.0.1` unless an operator sets `management.server.address` ([configuration.md §3.14](configuration.md#314-framework-wiring-keys)); the Kubernetes opt-open pairs it with Service/NetworkPolicy isolation ([deployment.md §9](deployment.md)). A scraper therefore carries no key — a key in a scrape config would outlive every rotation discipline. The permit is scoped to the management context, to `GET`/`HEAD`, and to the index plus those two paths ([auth.md §8.6.1](auth.md)); it is NOT in auth.md §8.3's public list, every other management path still needs a session, and no `/actuator` path is reachable on the app port. Rationale for the separate port (2026-08-07 security review MEDIUM-7): §4.3's metric tags include `pipeline_id`, `datasource_name` and `template_id` — an unauthenticated metrics endpoint on the app port would publish the internal inventory that §6's bare-UP/DOWN health design exists to protect.
- Cardinality: the `executions.*` and `nodes.*` meters carry `pipeline_id` (and `node_id`), so the scrape holds per-pipeline series — bounded by the catalogue the deployment stores (§4.3), and growing with it. Budget the scraper's series limit for the pipeline count, not for a constant.
- Note the asymmetry: `/health`, `/ready` and `/info` are served at the **root** of the application port, not under `/actuator` and not under `/api/v1` (§6).
- Other backends (Datadog, New Relic, CloudWatch) selectable via Micrometer registry config.
- Actuator/management exposure is configured with Spring Boot's own `management.*` keys (`management.server.port`, `management.server.address`, `management.endpoints.web.exposure.include`) — datapipelines defines no key of its own for it ([configuration.md §3.14](configuration.md#314-framework-wiring-keys) registers those framework paths; [§3.15](configuration.md#315-observability) holds this product's own observability keys).

### 4.3 Cardinality discipline

High-cardinality tags avoided:
- ❌ Don't tag by `user_id`, `execution_id`, `correlation_id`, exception class, method name, or any raw URI/SQL string.
- ✅ Do tag by `pipeline_id`, `node_id`, `datasource_name`, `template_id` (bounded counts), and by closed enum-valued dimensions (`status`, `reason`, `outcome`, `format`).
- Per-execution metrics live in the execution record (database), not in the metrics system.

**Normative rule:** every tag value must come from a closed set — an enum, or an entity ID whose population is bounded by what the deployment stores. A tag whose values are derived from *code shape* (class, method, stack frame) or from *input text* is forbidden; that detail belongs in the log line and the trace span, which are indexed for it. §8.1 applies this rule to error counting.

---

## 5. Tracing

Not provided. Deferred — see the failure-flow record §12 (`docs/superpowers/specs/2026-10-25-failure-flow-and-telemetry-design.md`); the sections below describe the design that would return with it.

### 5.1 OpenTelemetry

**Implementation status:** the tracing wiring below is a design target, not shipped behavior. The application has no OpenTelemetry SDK/exporter or consumer for the reserved tracing switches; setting them does not enable tracing. See [Configuration §3.15](configuration.md#315-observability) and the deferred export work in #270.

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
- **No `diskSpace` component.** Boot's default disk-space indicator stays disabled. Its old reason — "nothing writes to local disk" — stopped being true with DuckDB staging (#795), which writes each execution's database, WAL and spill to the scratch directory (`<java.io.tmpdir>/dp-staging`, [Staging §8.5](staging.md#85-the-duckdb-engine)). Boot's indicator watches the wrong path with the wrong threshold, and as a `/health` component a full volume would restart a healthy pod; the scratch directory has its own signal instead (next bullet).
- **DuckDB scratch: `duckdb_scratch` on the management port, not a `/health` component** (#806, owner ruling 2026-11-12). The indicator answers in the aggregate of the management port's `/actuator/health` — `UP`, or `OUT_OF_SERVICE`/`DOWN`, which make that endpoint answer `503` — and is **status only**: no details, no path, no number, whatever `show-details` later says. It is never one of `/health`'s three components and never reaches `/ready`, so a full scratch volume never fails readiness or takes an instance out of rotation; the figures are the `datapipelines.staging.scratch.*` gauges (§4.1). The first matching row wins: any reading throws, or the path can be confirmed neither present nor absent → `DOWN`; the path is not a directory, or is a directory the process cannot write → `OUT_OF_SERVICE`; the directory is absent and its nearest existing parent is not a directory the process can write (it could not be created) → `OUT_OF_SERVICE`; usable bytes (of the directory, or of that parent) below one default reservation (`staging-memory-limit × staging-expansion-factor`, 3 GB at the defaults) → `OUT_OF_SERVICE`; otherwise `UP` — one reservation exactly is `UP`. **What `UP` means:** the volume has room for one default reservation and the directory can be written or created. It does **not** say a database can open — the staging module's own checks (a symbolic link, another owner, group or other permissions) and the in-memory aggregate can still refuse; aggregate exhaustion never changes the status and is read from the gauges. The threshold and rows are the #806 brief's policy, not an owner ruling. Each probe reads a fixed handful of `stat`s and one file-store figure: no database is opened, no directory walked, nothing created. The path appears in the server log only: one WARN `event=staging.scratch.health` on the transition into a non-`UP` status (path, usable bytes, threshold, reason), one INFO on the return to `UP`, never one per probe.

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

Only `/health`, `/ready` and `/info` are exposed without auth on the application port — nothing under `/actuator` is routable there. `/actuator/health` and `/actuator/prometheus` are served anonymously on the management port; the port, not a credential, is the boundary (§4.2) — it binds loopback by default and is confined to the cluster network by deployment topology; every other actuator endpoint is disabled, and the index's links are the only other anonymous read. Health output carries no credential, hostname or JDBC URL — component values are bare `UP`/`DOWN` strings precisely so an unauthenticated probe surface cannot leak topology (§9.2).

---

## 7. Audit Log

Already covered in [Auth spec §10](auth.md#10-audit-log) (event catalog §10.1, log shape §10.2). The audit log is **separate from the general application log**:

- Append-only table in the metadata DB.
- Structured events (auth events, admin actions) — the catalog in Auth §10.1 is authoritative; there are no password or lockout events, because authentication is OIDC-only.
- Retention governed by `datapipelines.audit.retention-days` ([configuration.md](configuration.md))
- **Delivery (#266).** A row is committed before `AuditEventSink.log` returns — by default the direct INSERT (on the caller's own connection inside its transaction); with `datapipelines.persistence.audit.enabled: true`, batched outside a transaction ([Auth §10.1A](auth.md#101a-delivery-durable-before-log-returns-266)). The same holds for every execution event ([DAG Executor §10.1](dag-executor.md#101-how-an-emitted-event-becomes-durable-266)).
- **The loss window, stated.** No awaited row — audit or event — is ever acknowledged before it is durable, so a crash cannot lose a row whose caller proceeded: a caller interrupted mid-write had not proceeded (for an execution, the outcome is "not completed", never "completed without a record"). The batching writers' non-awaited `submit` path is the one exception — its queue at the moment of a crash, and whatever the shutdown drain could not write by `shutdown-drain-ms` (`persistence.drain_incomplete`, `failures{kind=drain_lost}`) — and it has no production caller yet (the dashboard's refresh event, D27, will be its first).

**Retention (#310).** The retention sweep's last step (`AuditLogRetention`, hourly, [Auth §10.3](auth.md#103-retention), [Metadata DB §8.2](metadata-db.md#82-audit-log-retention)) deletes the rows older than the database's `NOW()` minus [`datapipelines.audit.retention-days`](configuration.md#312-audit) (30–3650, default 365), in batches of at most 5,000, at most 50 batches or 2 seconds a tick. A tick that deleted anything logs one INFO line `event=audit.retention purged=<n> cutoff=<iso> batches=<k>`; a tick that stopped with rows left logs a WARN `event=audit.retention_incomplete … reason=batch_ceiling|time_budget` and the next tick continues; a metadata-DB fault logs a WARN `event=audit.retention_failed purged=<n> batches=<k> cutoff=<iso> error=<class> sql_state=<state>` with what it had already committed — the class and SQLState, never the store's message (§3.4G). Counts and the cutoff only — never a row's content. The meter is `datapipelines.audit.retention.purged` (§4.1). A step of the sweep that throws anything else logs ERROR `event=retention.step_failed step=<execution_events|keys|audit_log>` and the tick continues with the next step.

The audit log captures **who did what when** for compliance / forensic purposes. The general log captures **what happened in the system** for debugging.

**What the audit log records, and what it does not (#164).** It records authentication, administration, lifecycle verbs and MCP tool calls (`mcp.tool.called`, and `mcp.tool.write` for the mutating ones). REST reads — `GET` on `/api/v1/*` and on published endpoints — produce NO audit row, by design: an MCP key is refused on every REST route (`endpoint.key_kind_refused`, [Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)), so an agent's reads travel over MCP, where every tool call is a row. "What did the agent see" is answered by the `mcp.tool.called` rows, not by REST. The one write an agent performs over REST, the visualization test screenshot upload (credentialed by the single-use `DP-Upload-Token`, never a key), is audited as `visualization.test.screenshot_uploaded` ([Auth §10.1](auth.md#101-events)), with the run's starter as the actor.

---

## 8. Error Reporting

### 8.1 Uncaught exceptions

Uncaught exceptions in any thread / coroutine:
- Logged at ERROR with full stack trace, correlation ID, and (where applicable) `execution_id` / `node_id`.
- Counted by `datapipelines.errors.total{domain}` — **one tag only**. `domain` is the first segment of the error code that the failure maps to (`pipeline`, `template`, `datasource`, `auth`, `result`, `rate_limit`, `idempotency`; `internal` for a failure with no mapped code), per the `{domain}.{entity}.{failure}` scheme in [enums §16](enums.md).
- Exception class and method are deliberately **not** tags: both are unbounded, code-shape-derived dimensions that §4.3 forbids. That detail is already carried by the ERROR log line's stack trace and the trace span's exception event, which are the right places to search it.
- For pipeline-execution coroutines, fails the execution with the mapped `pipeline.*` code ([dag-executor §8.2](dag-executor.md)); a cancelled execution is `execution_aborted`, counted by `datapipelines.executions.aborted{reason}` (§4.1), not by `errors.total`.

A client disconnect is not an uncaught exception (#562): when the client has gone away mid-response, both `Throwable` backstops (`ApiExceptionHandler`, `UiExceptionHandler`) log one DEBUG line, `disconnected {method} {uri}: {exception class}`, with no stack trace, write no response (no envelope, no page, no toast), and it is not counted as an error. Exactly two exceptions are recognised, anywhere in the first eight links of the cause chain: `AsyncRequestNotUsableException` (a streamed answer whose client went away) and Tomcat's `ClientAbortException` (an aborted fetch). Both arise only from writing to the client's own response. A broken-pipe or connection-reset `IOException` is recognised only when one of them wraps it. On its own, or inside anything else, it is a failure on one of our connections (the pool, a datasource, object storage, an HTTP client), as is an `EOFException`: those keep the ERROR line, the stack and the 500. Every other exception that reaches the backstops keeps the ERROR line and stack above.

### 8.2 External error reporting (optional)

- Sentry / Bugsnag / Rollbar integration via Spring Boot's `spring-boot-starter-actuator` + vendor SDK.
- Configured per deployment (disabled by default in v1).

---

## 9. Configuration & Redaction

> Inbound links land here from [MCP Server §6.3](mcp-server.md#63-tool-result-schema); correlation-ID propagation itself is specified in **§3.3**.

### 9.1 Configuration keys

[configuration.md](configuration.md) is the single authority for config keys — YAML path, env var, default and description all live there (D8). This spec **references keys by name and never restates a default.** The observability keys are defined in [configuration.md §3.15](configuration.md#315-observability):

| Key | What it controls here |
|---|---|
| `datapipelines.observability.logging.format` | `json` (§3.1) vs human-readable console (§3.5, dev). Bound at startup (#337): resolved before logging initialises, the value set is closed, and an unknown value refuses startup. |

Two families deliberately have **no** datapipelines-namespaced key, because their frameworks already own the surface and duplicating it would create a second authority:

- **Log levels** — Spring Boot's own `logging.level.*` (e.g. `logging.level.co.datapipelines.dag=DEBUG`). §3.2 states which levels carry which meaning.
- **Actuator/Prometheus exposure and path** — Spring Boot's `management.*` (§4.2): `management.server.port`, `management.server.address` and `management.endpoints.web.exposure.include`, registered in [configuration.md §3.14](configuration.md#314-framework-wiring-keys).

Sampling rates (§5.4) would be set with the OpenTelemetry SDK's own `otel.traces.sampler.*` properties — deferred with §5; the application reads no tracing key today.

### 9.2 Redaction — normative

Redaction is **not configurable and not opt-in**. There is no `redaction.enabled` key: a switch that can turn secret-scrubbing off is a switch that will be off in some deployment. The mechanism is two layers inside the logging pipeline, so that **no logger call site can bypass it** — a developer cannot leak a secret by choosing the wrong logging idiom, only by inventing a key name that is not on the list. (#337 builds both on Spring Boot's structured-logging customizer point — no `logstash-logback-encoder`, no `logback-spring.xml` converter entry; the console format's one redaction word is registered by the house encoder itself; every behavioural rule of this section is unchanged.)

1. **Field filter in the JSON encoder.** The house JSON members customizer applies a value processor to EVERY member the formatter writes — MDC entries, SLF4J key-value pairs, and any member added later. Any member whose key matches the sensitive-key list is emitted as `"***"` — the key is kept (its presence is itself diagnostic), the value never is.
2. **The rendered text, message and stack trace.** The same redactor rewrites the two shapes a secret takes inside free text — `key=value` and `"key": "value"` — wherever text is rendered: on the structured format's `message` member, in the stack-trace printer's output (a driver's `SQLException` quoting the JDBC URL is the realistic one), and under the console format in the pattern's redaction word (`%dpRedact`, a house converter over the rendered message, correlation fields and exception word). Both formats call the SAME single recognizer (`RedactionScanner`, reached through `LogRedactor.scrubText`), so they cannot drift. **Recognition is one left-to-right pass over the ORIGINAL text, and overlapping spans merge:** every key start is tried against the original characters, spans that overlap become one region, and the region is replaced once, by the replacement of the span that started it — so a secret-shaped fragment INSIDE a value (`{"password":"x secret=abc\"tail"}`, `password="{\"api_key\":\"…\"}"`) is part of that value and can never end the outer value early, and a nested match whose own value runs past the end of the one holding it (`"secret": secret=  tail`) extends the mask to its end. Nothing is rewritten until the pass is over. The work is linear in the text length and a failed match is never rescanned (a repeated `password_password_…` token with no value costs the same per character as any other text); the tests hold it to a counted number of scan steps rather than a timing. The shapes are matched **case-insensitively in both forms** (`{"PASSWORD":"…"}` is masked like `password=…`), by the same key rule, and the WHOLE value is masked with its delimiters kept: a quoted value (double- or single-quoted) runs to its first UNESCAPED closing quote, so an escaped quote or backslash inside it (`\"`, `\\`) never ends it; a quote never closed masks to the end of its line only; a bare token ends at whitespace or a delimiter; the JSON form also masks a bare scalar (`"secret":12345`). The stack trace is redacted as a whole **before** its 8192-character output bound is applied, so a value the bound would have cut is already masked.

   **Known limits of the text layer** (the field filter in layer 1 has none of them — a member is judged by its key): a value that is an object or array under a sensitive key (its own members are matched by their own keys); a JSON key whose quotes are themselves escaped (`\"password\":` — text that was JSON-encoded before it was logged); Python-style single-quoted keys (`'password': 'x'`); the unquoted colon form (`password: x`); and a secret under a key that is not on the list. Text matching over-matches on purpose — `dbPassword=` and `"client-secret"` are masked though they are not `*_<key>` compounds — and the member matcher is boundary-strict (`db_password_v2` matches, `dbpassword` does not).

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
| 2026-11-13 | v1.76 | 806 (#806) | **§4.1**: four untagged byte gauges, `datapipelines.staging.scratch.capacity`, `.reserved`, `.leftover` (`NaN` until the leftover sweep has counted) and `.usable` (`NaN` when the reading throws), read at every scrape. **§6.1**: the `diskSpace` bullet's "nothing writes to local disk" reasoning corrected (DuckDB staging, #795), and the DuckDB scratch signal described — the status-only `duckdb_scratch` indicator on the management port's `/actuator/health`, never a `/health` component, its rows, and what its `UP` does and does not establish (owner ruling 2026-11-12 for the surface). §6.4 unchanged: the indicator carries no detail. |
| 2026-11-13 | v1.75 | 803 (#803) — renumbered at merge after #822's v1.73 and #814's v1.74 | **§3.4**: the `staging` row names the DuckDB scratch sweep's INFO (paths removed for a provably dead owner) and WARN (leftover paths nobody can vouch for, with their total bytes, counted against `scratch-bytes`). |
| 2026-11-13 | v1.74 | 814 (#814) | **§3.4R**: the lake definition lifecycle has five audit events — `lake.definition.discarded` and `.draft_purged` join `.released`, `.applied` and `.apply_failed` ([Enums §15](enums.md#15-authauditevent--auth-audit-log-events)). No log line added. |
| 2026-11-13 | v1.73 | 822 (#822) | **§3.4R** `lake.definition.apply_unclassified` is logged by any step of the apply, not only the commit, and gains `step` (`deadline`, `pre_commit`, `session_open`, `metadata_store`, `lease_release`, `session_close`, `commit`); the `apply_deferred` and `apply_unknown` rows say which ending follows it. |
| 2026-11-12 | v1.72 | 847 (#847) | **§4.1**: `datapipelines.auth.secret_verification.failed` (counter, `cause` = `memory` \| `encoding` \| `other`, registered eagerly) — a native secret-verification failure that is not a wrong secret, answered 503 `auth.secret_verification.unavailable`. |
| 2026-11-12 | v1.71 | 325 (#325) | **§4.1**: `datapipelines.executions.lifecycle_write_failed` also counts — once — a terminal write deferred behind a RUNNING insert that outlived its bound; the deferred UPDATE's own failure or 0 rows is a WARN, not a second count. |
| 2026-11-11 | v1.70 | #786 U2 | **§3.4**: four `scheduler.conversion.*` rows — the upgrade's in-place schedule conversion (`converted`, `summary`) and its E.0 refusal (`refused` with `kind=schedule`, `refused_summary`). |
| 2026-11-10 | v1.69 | 841 (#841 R4) | **§3.4H**: the freshness tick's row names two steps: the grant repair's statements, the writer lock's included, share one 5 s budget and each carries the 10 s socket bound, so a step queued behind a held lock still ends at 5 s. No new event or metric. |
| 2026-11-08 | v1.68 | 858 (#858) | **§3.4H**: the lake settlement tick's statements carry the socket bound too, through the lake runtime's own template: its row reads a 5 s statement bound, a 10 s socket bound, a 15 s end against a hung server and 25 s with the 10 s acquire, named as a separate wait; the shutdown row lists its 15 s end. No new event or metric. |
| 2026-11-08 | v1.67 | 833 (#833, #824, #840) managed generation bounds | **§3.4A**: `datasource.pool_close_stuck` is reported at each pool's own deadline, from its `connectionTimeout` plus, for a managed lake generation, its reader-pin release bound (was a constant 65 s), and gains `deadline_seconds`. `datasource.pool_hard_closed` reports a managed generation at the managed retire ceiling (`datasources.managed-retire-ceiling-seconds`, #824). **§4.1**: `datapipelines.datasource.pool.close_stuck` counts against that per-pool deadline. |
| 2026-11-08 | v1.66 | 851 (#851) | §3.4S `lake.settle.metadata_read_stuck`: the abandoned read stops itself at its next chunk; `lake.settle.metadata_read_ended` gains `cause=abandoned` for that end. No event added or renamed. |
| 2026-11-07 | v1.65 | 837 (#837) | **§3.4H**: the lake settlement tick is named among the jobs on `dp-scheduled`; the new paragraph and table state the two wall-clock bounds on the jobs' metadata waits (the pool's 10 s acquire, a socket bound of statement + 5 s on each bounded statement) and each job's worst wait; the shutdown row notes the retention sweep can outlive the phase against a dead server. No new event or metric. |
| 2026-11-07 | v1.64 | 845 (#845) managed reads egress proxy | **§3.4Q** gains `lake.egress_proxy_verified` (INFO: the proxy declared the configured max-age and reached the store, with the probe's duration) and `lake.egress_proxy_absent` (WARN: no proxy configured, reads are uncapped), and lists the `lake.boot.egress_proxy` refusal. No credential appears in either line or in the refusal. |
| 2026-11-06 | v1.63 | 818e (#818) | Reader-lease loss, overlong and slow-round events and counters; pins follow physical closure. |
| 2026-11-05 | v1.62 | 818d (#818) managed reader lifetime enforced | **§3.4A**: new `datasource.pool_close_stuck` (WARN, a close past its 65 s deadline on `dp-pool-close`, never retried), `datasource.pool_close_failed` (WARN) and `datasource.reaper_pass_slow` (WARN, a pass past its 1 s bound). **§3.4C**: new `lake.instance_readers_interrupted` (INFO, interrupt-first before Hikari's shutdown). **§3.4H**: the pool reaper runs on its own `dp-reaper` thread; the closes on `dp-pool-close`. **§4.1**: `datapipelines.datasource.pool.close_stuck` and `datapipelines.datasource.pool.reaper_pass_slow`. |
| 2026-11-05 | v1.61 | 818c (#818) managed reader lifetime | **§3.4C**: `lake.generation_expired` gains `trigger` (`lease` or `reaper`): the pool reaper now retires a managed generation past its age bound with no borrower; new `lake.reaper_retired` (INFO, `generations`), one line per reaper pass that retired any. **§3.4H**: the reaper's pass includes that retirement and still issues no statement. |
| 2026-11-04 | v1.60 | 818b (#818) managed reads corrections | **§3.4C**: `lake.freshness_read_failed` carries `sql_state`; new `lake.freshness_tick_failed` (WARN, `step`, `error`, `sql_state`), one line per failed tick step, after which the tick's next step and the next tick still run. |
| 2026-11-04 | v1.59 | 818 (#818) managed lake reads | **§3.4C** gains the managed datasource's events: `lake.managed_datasource_registered` / `_resynced` / `managed_registry_backfilled` / `managed_datasource_granted` (boot and tick), `lake.generation_expired` (age bound), `lake.generation_stale` and `lake.freshness_tick_retired` (the tick and its count), `lake.freshness_read_failed` (WARN) and `lake.table_unavailable_omitted`. **§3.4Q** lists `lake.boot.datasource_name_taken`. **§3.4A** gains `datasource.bootstrap_managed_name` (WARN): a bootstrap entry naming the managed datasource is skipped and its credential is never probed or written. The brief's `lake.generation_not_built` is not an event: a failed build read is the `lake.unavailable` error. |
| 2026-11-05 | v1.58 | 799 (#799) immutable recipes — renumbered at merge after #799's v1.57 | §3.4S records the sanitized recipe PUT failure; no SDK message, cause chain or storage configuration is logged. |
| 2026-11-04 | v1.57 | 799 (#799) trusted listing — renumbered at merge after #799c's v1.56 | New §3.4S records the safe `lake.delivery.listing_failed` SDK boundary event. |
| 2026-11-06 | v1.56 | 799c (#799, #843) whole-tick settlement bound | §3.4S `lake.settle.sweep` gains `deferred_budget`, `deferred_stuck` and `deferred_failed`; new `lake.settle.metadata_read_stuck` and `lake.settle.metadata_read_ended` for the metadata read that left the tick's thread. |
| 2026-11-06 | v1.55 | 799b (#799) bounded settlement | §3.4S `lake.settle.sweep` gains `rows`, `changed` and `exhausted`: each tick's visited deliveries, terminal transitions and whether its work budget ran out ([Lake §8](lake.md#8-settlement-and-recovery)). No event added or renamed. |
| 2026-11-05 | v1.54 | 799 (#799) library deletion refusal | §3.4S records the production FileIO refusal and retained key, including Iceberg transaction cleanup after manifest merges. |
| 2026-11-06 | v1.53 | #708 the Prometheus scrape | **§4.1 is cut to the meters that exist** (the failure-flow and telemetry record §7, §17 row 13): the 13 documented-only rows are deleted — `staging.bytes`, `staging.active_tables`, `datasource.pool.active`/`.pending`/`.timeout_total`, `templates.render.duration`, `templates.cache.hits`/`.misses`, `mcp.tool.calls`, `auth.login.attempts`, `auth.api_key.validations`, `result.expiries`, `result.size` — and `datapipelines.web.endpoint_key_budget.saturated` gains its row; the two gauges `script.evaluations.abandoned` and `parameters.selectors.abandoned` are renamed under the `datapipelines.` prefix. `MeterCatalogueParityTest` now holds §4.1 to the code both ways (43 coded names + `http.server.requests` = 44 rows at lane delivery). **§4.2 / §6.4**: `micrometer-registry-prometheus` ships; `health` and `prometheus` answer anonymously on the management port (#231 item 1, record §17 row 11), with the per-pipeline cardinality note. **§5**: one sentence — tracing is not provided, deferred (record §12). **§9.1**: the two `tracing.*` keys are removed (record §17 row 12, no deprecation window). |
| 2026-11-05 | v1.52 | 771 (#771) the never-admissible refusal counts under its own reason | **§4.1** `datapipelines.dashboard.refresh.refused` gains the `never_admissible` value of its `reason` tag: #762's once-never-admissible 429 now counts under `reason=never_admissible`, the busy refusal under `saturated` alone — one series per reason, no new tag or counter. **§3.4J**'s `dashboard.refresh_never_admissible` row names the new tag value. |
| 2026-11-02 | v1.48 | 778 (#778) the lake reference index backfill | New **§3.4P**: the boot backfill's `lake.reference_index.backfill` (INFO, counts) and `lake.reference_index.backfill_failed` (WARN, version identity and exception class; no SQL, no parser message). |
| 2026-11-02 | v1.49 | 785 (#785) managed lake boot check | New **§3.4Q** (numbered at merge after #778's §3.4P / v1.48): the `lake.boot.ok` INFO line and the five `lake.boot.*` refusal ids the after-Flyway boot check leads its startup failure with (defined here so a citation of one is checked; they are not log events). |
| 2026-11-04 | v1.51 | 810 (#811) unclassified apply failure | **§3.4R** gains `lake.definition.apply_unclassified` (ERROR, `cause` + `sql_state`). It is logged where an unclassified failure of the commit step is caught, before the attempt's `apply_unknown` line, whose `code` is then `lake.definition.apply_unclassified`. |
| 2026-11-03 | v1.50 | 798 (#798) lake table definition apply | New **§3.4R** (after #785's §3.4Q / v1.49): one line per apply or verify attempt that reached the table, with the events `lake.definition.applied` (INFO), `apply_failed` (WARN), `apply_deferred` (INFO, lease busy or back to pending), `apply_unknown` (WARN, the row stays applying) and `apply_superseded` (INFO). No body, location or storage value is logged. |
| 2026-11-02 | v1.49 | 785 (#785) managed lake boot check | New **§3.4Q** (numbered at merge after #778's §3.4P / v1.48): the `lake.boot.ok` INFO line and the five `lake.boot.*` refusal ids the after-Flyway boot check leads its startup failure with (defined here so a citation of one is checked; they are not log events). |
| 2026-11-02 | v1.48 | 778 (#778) the lake reference index backfill | New **§3.4P**: the boot backfill's `lake.reference_index.backfill` (INFO, counts) and `lake.reference_index.backfill_failed` (WARN, version identity and exception class; no SQL, no parser message). |
| 2026-11-01 | v1.47 | 762 (#762) never-admissible dashboard refresh | **§3.4J** gains `dashboard.refresh_never_admissible`: the WARN a refresh logs when it is refused at once because it can never be admitted on this instance (`workspace_id`, `dashboard_id`, `invocations`, `ceiling`, `key`). |
| 2026-10-24 | v1.46 | 442b (#442) schedule notification delivery | **§3.4E** gains the nine `scheduler.notification.*` events (`enqueued`, `backlog_full`, `sent`, `retry`, `failed`, `uncertain`, `render_failed`, `port_failed`, `settle_lost`) and the rule that they carry the recipient's domain, never an address; **§4.1** gains `datapipelines.scheduler.notifications.{sent,failed,uncertain}`. |
| 2026-10-21 | v1.45 | #637 retention event-catalogue parity | **§3.4's namespace sentence** now lists thirteen namespaces, including `retention`; `ObservabilityEventCatalogParityTest` enforces the existing `retention.step_failed` row with a non-vacuity floor of one. No event added or renamed. |
| 2026-10-21 | v1.44 | 636 (#636) the housekeeping statements' bounds | **§3.4J** `dashboard.refresh_sweep_failed` and `dashboard.refresh_retention_failed`, and **§3.4M** `parameter.evaluation_sweep_failed` and `parameter.evaluation_retention_failed`, gain "or was cancelled at its bound (`sql_state=57014`)" with their bound (the refresh sweep's 10 s, the retention tick's 25 s) and their `tick_failed` job; **§4.1** `tick_failed` lists eight `job` values; **§3.4I** `retention.step_failed` counts every step under its job, only what escaped the step's own catch; **§3.4H** names the dashboard refresh sweep among the `dp-scheduled` jobs and the four bounds in `shutdown.scheduled_jobs_incomplete` (the pool reaper is the one unbounded job left). No event added or renamed. |
| 2026-10-20 | v1.43 | 326 (#326) the scheduled jobs' statement bounds | **§3.4I** `execution.sweep_failed` and `execution.event_retention_failed`, and **§3.4N** `audit.retention_failed`, gain "or was cancelled at its bound (`sql_state=57014`)"; **§3.4I** gains the `retention.step_failed` row, cited by §3.4H and §7 but defined nowhere until now; **§3.4H**'s `shutdown.scheduled_jobs_incomplete` row says the bounds sit under the 30 s shutdown phase and names the steps that are not bounded. **§4.1** gains `datapipelines.scheduled_jobs.tick_failed` (counter, tag `job` from a closed set of four). The bounds are code constants (sweep 10 s, retention 25 s), not keys. No log line's fields change. |
| 2026-10-20 | v1.42 | #569 | The concurrency gauge is bound by the execution-slot bean, once per instance. Executor construction does not register it; rebinding the same pool is a no-op and a different pool fails fast. |
| 2026-10-17 | v1.41 | 562 (#562) a client disconnect is not an uncaught exception | **§8.1** gains the rule: a client that went away mid-response is recognised at both `Throwable` backstops — one DEBUG line with method, URI and class, no stack trace, no response written, not counted. Exactly two exceptions count, in the cause chain: `AsyncRequestNotUsableException` and `ClientAbortException`. A broken-pipe or connection-reset `IOException` counts only inside one of them; any other I/O failure, `EOFException` included, keeps its ERROR line and 500. Until now each disconnect logged `500 … unhandled …` at ERROR with a stack for a 500 no client could receive. Every other exception's ERROR line is unchanged. |
| 2026-10-08 | v1.40 | #443 event-catalogue parity | §3.4 adds 37 rows to complete the seven remaining catalogued namespaces; all twelve are enforced with count floors. Audit retention and execution shutdown receive additive §3.4N/O tables. Computed event fields are refused except the two exact, count-pinned legacy audit-row formats; production logs and §3.4G FailureShape/SQLSTATE prose are unchanged. |
| 2026-10-06 | v1.39 | #446 bounded driver SQLSTATE (2026-10-06T03:09:15Z) | §3.4G defines the one-to-five ASCII letter/digit shape, fixed `invalid` replacement and preserved `none` fallback. Valid states pass unchanged; rejected driver values are never logged. |
| 2026-10-04 | v1.38 | 329a (#329), store-failure log shapes | §3.4C lake outcome-recorder field list gains `sql_state` and defines `error` as the exception class; §3.4G records the narrow reuse of its rule at four existing failure lines. Event names and failure behavior stay unchanged; the remaining inventory stays open on #329. |
| 2026-10-03 | v1.37 | #403 failed pre-start release | **§3.4I** gains `execution.idempotency_release_failed`: one WARN per failed cleanup attempt, user/execution ids and error class only; the original refusal is preserved. |
| 2026-10-03 | v1.36 | 439 (#439) an event-catalogue parity guard | **§3.4's intro** states that `ObservabilityEventCatalogParityTest` fails the build when an `event=<namespace>.<name>` literal in `modules/<module>/src/main/kotlin` and the event column of these tables disagree for the namespaces whose tables are already complete (`parameter`, `lake`, `mail`, `persistence`, `scheduler`); its non-vacuity floor pins each namespace's count, and the catalogued namespaces still drifting are tracked in #443. No event row changes. |
| 2026-10-03 | v1.35 | 393 (#393) a throwing metrics hook is contained | **§3.4G** gains `persistence.hook_failed` (WARN; `writer`, `hook`, `cause` — the hook's method name and the exception's simple class name, never its message): every `BatchingHooks` call in `BatchingWriter` goes through one guarded call, so a hook that throws can no longer strand a claimed entry, make `commit` re-write a batch the store already holds, or end a writer thread. The item's outcome stands; the hook still runs before the caller's release (#363). At most once per 10 s per writer, on the interval `persistence.saturated` uses. |
| 2026-10-03 | v1.34 | 429 (#429) the two `parameter.*` failure lines follow the class-and-SQLState rule — renumbered at merge after 425's v1.33 | **§3.4M**: `parameter.evaluation_failed` no longer attaches the throwable (its message and stack could carry SQL or a value) — it names `error` (binary class name) and `sql_state`, and the stack moves to ONE new DEBUG row, `parameter.evaluation_failed_cause` (`evaluation_id`); `parameter.evaluation_record_failed` spells an absent SQLState `none` (`FailureShape`'s spelling), where it logged the string `null`. |
| 2026-10-03 | v1.33 | 425 (#425, #385) the selector abandon event namespaced and worded | §3.4M gains its 19th row: `parameter.selector_statement_abandoned` (the bare `event=selector_statement_abandoned` of `SelectorPool.abandon`, which the audit's namespaced extraction could not catalogue). The line gains `cause` (`deadline` when the cancellation is the evaluate's own `withTimeout`, `caller` otherwise — #375's disconnect-grace abort, a shutdown) and no longer says "the evaluate's deadline passed" for a cancellation that was not one; it stays ONE error line with no throwable and no SQL or bind. |
| 2026-10-03 | v1.32 | 418 (#418) the parameter evaluation events catalogued | New **§3.4M**: the 18 `event=parameter.*` names the code logs (#376's history writes, the sweep and retention, the observed stream and its grace), each with its level, trigger and fields; a failed history write is ERROR and the evaluation continues unrecorded. The docs audit's §3.4 event extraction admits the `parameter` namespace in the same commit (`scripts/docs-audit.sh`; the `execution`/`dashboard` precedent) — without it every backticked name fails check C against the error-code catalog. metadata-db §8.1/§8.5 cite the rows. |
| 2026-10-02 | v1.31 | 235 (#164) the audit boundary stated | **§7** states the rule: the audit log records authentication, administration, lifecycle verbs and MCP tool calls; REST reads produce no audit row by design (an MCP key is refused on REST); the one agent write over REST, the screenshot upload, is audited as `visualization.test.screenshot_uploaded`. |
| 2026-09-30 | v1.30 | 337-c (#337) redaction composed on the original text | **§9.2**: the rendered-text layer is ONE left-to-right recognizer over the original text, shared by the JSON `message`/`stack_trace` members and the console format — not an assignment pass followed by a JSON pass (and, on the console, two nested `%replace` calls). The delivered composition let the first pass rewrite text inside a quoted value (consuming an escape with it), so the second pass read an orphaned quote as the end of the outer value and a secret tail survived (`{"password":"x secret=abc\"tail"}`); a secret-shaped fragment inside a value is now part of that value, and overlapping spans merge into one masked region (a nested match that outruns its container extends the mask). A failed match is no longer rescanned from every later key start (a repeated `password_…` token with no value was quadratic, measured 4 s at 72 KB); the bound is a counted-step test. The console pattern's redaction word `%dpRedact` is a house logback converter registered by the encoder. The sensitive-key list, the never-redacted set, `***`-keeps-key, the redact-before-bound order and the no-disable rule are unchanged (drift-tested); the known limits are unchanged. |
| 2026-09-30 | v1.29 | 337-b (#337) redaction and console correlation completed | **§9.2**: the rendered-text layer is now stated as built — case-insensitive in BOTH text forms by one key rule (the JSON form was case-sensitive), the whole value masked with delimiters kept (escaped quotes and backslashes never end a quoted value; single-quoted values; bare JSON scalars; a never-closed quote masks to the end of its line), the stack trace redacted BEFORE its 8192-character bound — and its known limits are listed rather than implied (object/array values, escaped-quote JSON keys, Python-style keys, the unquoted colon form). The member matcher now also accepts the prefixed-and-suffixed compound the text matcher always masked (`db_password_v2`). **§3.3**: the console format prints `correlation_id=` / `execution_id=` before the message (`-` when absent; the two named MDC keys, no MDC dump). **§3.1**: under `json` the binding switches Spring's banner off so a normal startup's stdout is JSON lines only (pinned by a normal-boot test). The scrubber's key pattern lost its prefix group loop: measured on the delivered pattern, a 4 KB `a_a_a_…` token overflowed the stack inside the logging call and a 50 KB word before `=` took ~15 s. The sensitive-key list, the never-redacted set, `***`-keeps-key and the no-disable rule are unchanged (drift-tested). |
| 2026-09-30 | v1.28 | 337 (#337) structured local logging | **§3.1 shipped**: the JSON line is real — Boot 3.5's structured `logstash` format with a house redacting members customizer, `logger_name`/`thread_name` renamed to the standard fields, MDC and key-value pairs flattened at the root; §3.1's mechanism sentence no longer names `logstash-logback-encoder` (the D1 deviation, no new dependency; every behavioural rule kept). **§3.3 shipped**: the propagation bullets name what was built — a request-built `ThreadContextElement` (`correlation_id` + `execution_id`) over the execution's coroutines and per-pool submitter-MDC capture — replacing "Spring's `TaskDecorator`" (the D3 mechanism deviation), plus the normative NOT-propagated list (batching writer and drain, the schedulers, the mail worker). **§9.2's mechanism sentences amended** to the built two layers: the members value processor and the rendered-text scrub (message member, stack-trace printer, console `%replace`) — the record's `logstash-logback-encoder` field filter and `logback-spring.xml` `MessageConverter` are gone, the list and the never-redacted set unchanged and drift-tested against the code. **§9.1**: the logging-format row states the binding (resolved before logging initialises, closed value set, unknown refuses startup) and the `tracing.enabled` row says declared-and-not-wired; the §3.14 anchor text corrected to §3.15. **§3.5**: local dev's console format is the switch's `console` value, not a logback-spring.xml profile. `logging.format` values: `json` prod default (compose passes it), `console` dev (`defaults.env`), both now actually bound. |
| 2026-09-30 | v1.27 | lane 336 (#336 D7) | New **§3.4K the live-write outage events**: `execution.progress_write_failed` (WARN, once per execution per outage — §3.2's rule at heartbeat cadence) and `execution.progress_write_recovered` (INFO), named by class and SQLState through `FailureShape`. §4.1 gains `datapipelines.executions.progress_write_failed` (counter, no tags): a live heartbeat/progress write refused — the state that makes a LIVE execution look dead to another instance's stale sweep. Renumbered at merge: L2 retains §3.4J. New **§3.4L**: the selector-lease cleanup WARN `datasource.lease_cleanup_failed` (operation-named, class + SQLState — a refused connection close discards the connection). |
| 2026-09-29 | v1.26 | L2 (#10) the dashboard runtime | New **§3.4J** — the refresh events (`dashboard.refresh_finished`, the failure lines by class and SQLState only, the stream cut / grace lines, the sweep and retention lines) — and five **§4.1 rows**: `datapipelines.dashboard.refreshes` (by terminal status), `…refresh.duration`, `…refresh.refused` (`saturated` / `stream_limit`), and the two gauges `…refreshes.active` and `…executions.reserved`. |
| 2026-09-29 | v1.25 | 321 (#321) the scheduled jobs' failure lines — renumbered at merge: 306/316 and their reviews took v1.21–v1.24 | §3.4's `dag` row states the rule for the crash sweep's and the event retention's failure lines: a failed tick logs `error=<class> sql_state=<state>` where it logged the store's message (`FailureShape`, the §3.4G rule; `dag` gains the `persistence` edge for it, module-structure §4.2). The event names are not cited here: on this base the docs audit reads `execution.*` as a permission family (#307), and the events' own table arrives with the 316 merge (§3.4I). §7's retention paragraph said `audit.retention_failed … message=…`; the line has logged `error=`/`sql_state=` since #310 — corrected. |
| 2026-09-29 | v1.24 | the 316 merge's review (#316) | New **§3.4I**: `execution.events_purged` catalogued — §3.4H cited it and the docs audit's citation check (#307, on main since 4b91c387; not on the lane's base) refused the uncatalogued name; #321 adds the two failure rows. |
| 2026-09-29 | v1.23 | 316 (#316) the scheduled jobs' own thread — renumbered at merge: 306 took v1.21 and its pass v1.22 | New **§3.4H**: every `@Scheduled` job runs on `dp-scheduled`, the jobs' own scheduler, so their lines (named there) carry that `thread` — until #316 they carried `dp-sse-log`, the SSE log streamer's; two shutdown lines, `shutdown.scheduled_jobs_stopped` (INFO, after both drains) and `shutdown.scheduled_jobs_incomplete` (WARN, a tick outlived the shutdown wait). |
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
| 2026-08-07 | v1.1 draft | consistency campaign | Applied [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) §2.14. **[M]** §6 health payload/paths realigned to the canonical [rest-api §11.1](rest-api.md#111-health-check) — root-level `/health`,`/ready`,`/info`, top-level `version`, snake_case components `{database, redis, h2_factory}`, `diskSpace` removed with rationale. **[M]** Stale metrics purged: `auth.login.attempts{outcome=locked}` dropped (OIDC-only, no local passwords, no lockout) with outcomes remapped to the auth §10.1 audit events; `datapipelines.http.server.requests` → `http.server.requests` (Spring Boot's own unprefixed metric) plus a rule on which metrics keep framework names. **[D9]** Result/SSE/idempotency metrics added: `result.bytes_written`, `result.writes{outcome}`, `result.cursor.reads{format,outcome}`, `result.expiries`, `result.size`, `sse.streams.active`, `sse.stream.duration{close_reason}`, `idempotency.cache.hits`, `idempotency.conflicts`. **[D7]** `executions.aborted{reason}` registered (matches dag-executor §15.3). **[M]** §8.1 `errors.total{class, method}` → `{domain}`, with §4.3 gaining the normative closed-set tag rule that forbids code-shape tags. **[M]** §9 rewritten as Configuration & Redaction: local YAML block deleted (config keys now referenced from [configuration.md §3.14](configuration.md#315-observability) per **D8**), redaction respecified as a non-optional two-layer mechanism (JSON-encoder field filter + `MessageConverter`) over an explicit sensitive-key list, plus §9.3 forbidding `jdbc_url`/credentials in error `details` across SSE, REST and MCP. **[D10]** `X-Correlation-Id` → `DP-Correlation-Id`. **[M]** Correlation propagation past the HTTP boundary made normative — echoed in every SSE event payload, `_meta` on MCP results ([mcp-server §6.3](mcp-server.md#63-tool-result-schema)). **[M]** Cross-ref fixed: audit log → [auth §10](auth.md#10-audit-log) (was §9). Draft status kept honest: dashboards/alerting/SLOs still to be elaborated, but §3.3, §4.1/§4.3, §6 and §9.2 are marked normative (§1). |
| 2026-08-05 | v1.0 draft | initial draft | Initial observability spec sketch — logs, metrics, traces, health, audit log |
