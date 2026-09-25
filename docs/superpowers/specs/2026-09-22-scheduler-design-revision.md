# Scheduler revision: durable occurrences, scheduler keys and REST-backed UI

**Status:** draft for Fable review; not locked for implementation or normative.
**Date:** 2026-09-22. **Delivery:** [GitHub issue #9](https://github.com/msabiransari/datapipelines/issues/9).
**Supersedes:** the implementation details in the [September 7 design](2026-09-07-scheduler-design.md).
**Not shipped:** this document changes no runtime behavior, supported key kinds, calculator catalog or API.
**Distribution:** contributor design material; not packaged as product documentation.
**Review:** pre-implementation findings recorded in §9 (2026-09-23); open items, not decisions.

The owner asked to resolve the exactly-once claim, introduce scheduler keys with associated
roles, stop schedules when their key is removed, use REST and a UI whose operations call
REST, fix stale implementation details, and use calculators for schedule-relative dates.
The owner subsequently asked for a notification email list (start/stop/error/unknown), a
dedicated module and Schedules page, hierarchical names, modification by authors/admins,
independently created reusable scheduler keys, association counts for scheduler and API
keys, and following the selected pipeline version rather than routinely changing version pins.

Latest owner decisions, incorporated for Fable's review:

- The symbolic version value is **`current`**, not `latest`.
- Schedules have no draft/released/discarded lifecycle and are **never promoted**. They are
  created directly through the UI or REST, including by applications offering scheduling
  to their clients. There is no release step for a schedule.
- Keep operational controls and **blocked** state distinct from an artifact lifecycle.
- There is **no new human role** for scheduling.
- The scheduler is **pipeline-agnostic**: it stores an executor payload and passes it through.
  Pipeline version selection, keyword interpretation and parameter semantics belong to the
  pipeline execution layer, not to the scheduler module.

Those directions are requirements. Detailed recommendations below (including the keyword
wire format and application authentication model) remain subject to review. Fable reviews
this draft before the owner locks it for implementation; this is not a dispatch brief.

## 1. What remains from the ratified design

- Use db-scheduler with the existing PostgreSQL service, rather than build a timer/lease engine.
  `16.12.0` was the version researched on September 7, not a current dependency assertion.
  Verify the supported stable version, license, starter compatibility and APIs at implementation.
- Schedules are workspace-scoped entities in folders, separate from pipeline definitions.
- Delegate payload validation to its executor at save and admission. The pipeline executor
  accepts `version: "current"`; optional exact numeric versions remain a proposal (§3.1).
- Five-field Unix cron, an explicit IANA timezone, presets and a next-five-occurrences preview
  using the same parser as execution.
- Reuse pipeline execution history, node statistics, cancellation and stale-execution handling.
- Default missed-run policy is `skip`; optional catch-up runs one missed occurrence, not an
  unbounded backlog. Section 3 narrows this to work that has never started.
- Skip overlap for the same schedule; do not accumulate concurrent runs of it.
- One application image with scheduler mode `both | api | worker`, default `both`.
  This is deployment configuration, not an authorization role. API mode does not dispatch
  scheduled work; it does not silently remove existing synchronous execution routes.
- First release includes the complete Schedules UI and REST surface, plus background runs.
- Lifecycle email notifications are in the first delivery (§6.1). Reports, standalone email
  jobs, automatic pipeline retries/backoff and bulk backfill remain out. Schedule promotion
  is explicitly not part of the model, rather than merely deferred.

Do not reserve report/email enum values with unsupported behavior. Add future job kinds with
their implementations. Historical prompt `092` must not be dispatched unchanged; use issue
number 9 for new delivery records under current repository policy.

### 1.1 No artifact lifecycle; operational controls only

A schedule is mutable operational configuration, created and edited in place through the
same REST contract used by the UI and external applications. It has no artifact versions,
release/discard/restore workflow, promotion package or promotion endpoint. The numeric
`revision` is solely optimistic concurrency/audit metadata, not a releasable schedule version.

Proposal: store `enabled` (manual pause/resume) and `blocked_reason`/`blocked_at` separately.
Display **Enabled**, **Paused** or **Blocked** as an operational condition, not a content
status; blocked takes display precedence and can coexist with manual pause. Resuming a
paused schedule cannot clear an unresolved block. An unblock/resume must revalidate the
key and executor payload. Run outcomes such as running/failed/unknown still belong to
individual occurrences; they are not a lifecycle of the schedule definition.

## 2. Guarantee: one occurrence record, at most one automatic launch attempt

Remove the promise of end-to-end exactly-once execution. A scheduler cannot atomically commit
both a remote datasource write and its own success record. Example: a pipeline commits an
INSERT, then its worker dies before recording success. Replaying it may duplicate rows.

Recommended first-version guarantee:

1. Each cron occurrence has one durable identity: `(schedule_id, scheduled_at_utc)`.
2. Duplicate engine deliveries cannot create a second occurrence or automatically launch the
   same occurrence again after its durable start claim.
3. A run with an uncertain execution/side-effect outcome is visible and is not automatically
   retried, even when catch-up is enabled.
4. Manual recovery is explicit and audited. It is never presented as proof that no previous
   external writes occurred. Exactly-once destination effects require destination idempotency
   or transactions designed by that pipeline.

This deliberately trades automatic completion after some crashes for protection against
silent replay. It does not mean every scheduled occurrence executes successfully.

### 2.1 Admission and crash boundaries

Use PostgreSQL transactions for occurrence insertion, schedule-level overlap admission and
the queued-to-starting claim. Do not hold a database transaction across the pipeline's runtime.
Workers must acquire execution capacity before claiming a start; capacity waits are not
evidence that the pipeline has begun. Save a stable execution correlation/id before launch
and reconcile against the existing execution record during recovery.

| Durable state / evidence | Recovery |
|---|---|
| Queued, no start claim | Safe to re-evaluate authorization, timing and overlap policy; no pipeline has been admitted to start. |
| Starting claim committed, execution not yet visible | Ambiguous admission boundary; do not automatically launch again. An acknowledged run can be lost here by design. |
| Execution running, heartbeat lost | Request cancellation and reconcile; absence of heartbeat does not prove that the old worker stopped. |
| Execution terminal with a persisted outcome | Reconcile schedule history from it; do not rerun the pipeline merely to repair scheduler bookkeeping. |
| No conclusive terminal outcome | Mark `unknown`, block future starts of this schedule pending resolution, retain evidence. |

Blocking on an uncertain run prevents a future occurrence from overlapping a possibly live
old worker. Resuming requires an authorized person to resolve the incident after verifying
the previous worker has stopped and examining destination effects. This does not claim
that a database lease can fence writes at every external system.

If launch cannot join the admission transaction, explicitly test the gap rather than
claiming the transactions are shared. Internal persistence retries are acceptable only
when they cannot call pipeline execution a second time.

### 2.2 Library behavior that needs explicit integration

Upstream documents automatic revival for dead tasks, a retry default for failed one-time
tasks, and recurring schedules that choose their next future time after completion. Its
client operations also need transaction-aware connection handling to join an application
transaction. Override/reconcile these behaviors for our admission policy; library defaults
alone do not supply the product guarantee or a history row for every overlapping tick.
[db-scheduler documentation, consulted 2026-09-22](https://github.com/kagkarlsson/db-scheduler).

Prove the chosen adapter records overlap/missed intervals without an unbounded replay loop.
A missed-range summary may compact a long outage; do not fabricate individual executions.
The precise recurring-task versus short dispatcher/one-time-task implementation remains a
bounded integration spike, not a reason to write our own scheduling engine.

## 3. Missed occurrences, overlap and manual runs

- **Normal delay:** a poll slightly after the due instant is normal. Define and expose an
  allowed-lateness policy so default `skip` does not skip every occurrence. Exact default
  and capacity-wait treatment must be chosen from the engine spike before implementation.
- **Skip:** occurrences outside the accepted lateness window are recorded as missed/skipped;
  the next future occurrence remains scheduled.
- **Run now catch-up:** select only the latest eligible missed, never-started occurrence.
  Keep its original scheduled instant and timezone as its identity/reference time. Mark it
  as catch-up; do not invent a new `now()` identity to bypass deduplication.
- **No overlap:** atomically admit at most one active run of a schedule across instances.
  Due occurrences while it is active are skipped, including manually requested runs.
  Unknown prior work blocks admission rather than clearing this guard.
- **Run now button:** creates an explicit manual run with its own idempotency key; it is not
  catch-up or retry. Pass the saved executor payload and assigned scheduler identity. Freeze request time
  in the schedule's timezone for its date reference.
- **Rerun an uncertain/failed occurrence:** deferred as an automatic feature. A future explicit
  replay must link to the original occurrence and preserve its input/time snapshot. The UI
  must explain that a new execution may repeat effects.

For DST, occurrence identities are UTC instants and display includes local offset. The
parser's gap/repeated-hour behavior must be measured, documented and identical in preview
and firing. Do not claim that timezone support alone settles the once-or-twice local-hour
policy. This remains an explicit review item.

### 3.1 Pipeline executor contract: `current`; snapshot each run

Owner decision: **`current` is the valid symbolic version value**, replacing `latest`.
The pipeline payload contains `version: "current"`; `pipeline@current` is shorthand in
discussion, not a name the scheduler parses. Do not introduce `FOLLOW_CURRENT_RELEASE`
or a second “latest” mode. An exact positive numeric RELEASED version can remain an advanced
option, proposed for review. This selector is part of the pipeline execution contract,
although a schedule carries it unchanged inside its opaque job payload.

[Versioning §3.4](../../versioning.md#34-current_version-is-sticky-and-event-driven-d60)
defines `current_version` as a deliberately selected pointer, not `MAX(version)`: a human
can switch to an older release, and an import does not normally move it. In development it
can even point to a draft. Ordinary no-version execute uses the working version, which can
also be a draft. Neither path may be assumed to mean “newest release.”

`current` follows that selected pointer and respects deliberate rollback/promotion switches
of the **pipeline**, without promoting schedules. The recommended pipeline executor policy
for this first background-job adapter remains RELEASED-only: refuse a null, discarded or
DRAFT target; never silently fall back to another version. The development DRAFT-pointer
case is still a policy for Fable/owner review, not settled by renaming the selector. Enforce
any such restriction in the pipeline adapter, never in the scheduler. Existing ordinary
no-version execution and published endpoints retain their documented behavior.

Pipeline adapter responsibilities:

1. During admission preparation, resolve `current` once, capture the immutable version and
   resolve/validate the pipeline parameter payload. Do not launch with a missing version.
2. Persist the selected version and typed inputs in the executor-owned prepared execution
   record before a launch claim can dispatch it. The scheduler persists only its opaque
   preparation/execution reference. Later releases, pointer changes and schedule edits
   cannot change an admitted run. History shows the requested selector and actual version.
3. If no eligible release exists or required parameters changed incompatibly, record a
   pre-start error through the generic executor result, with an actionable block reason;
   the scheduler blocks the schedule and notifies its recipients.
   Never silently run the old version or drop invalid inputs. Resume is an explicit action.
4. Catch-up resolves the selected release at admission, not a reconstructed historical
   release, but keeps the original occurrence's reference time. Show both facts in history.
5. Coordinate selection with concurrent version lifecycle operations, and use the existing
   explicit-version execution/validation boundary. Dependencies retain their established
   versioning rules; selecting a root release does not invent transitive dependency pins.

## 4. Scheduler keys and authorization

Owner direction: introduce scheduler keys, associate a role, assign a key to a schedule,
and stop the schedule if that key is removed. Recommended concrete model:

- A dedicated `scheduler` credential kind, pinned to one workspace; do not reuse a
  login-minted user/MCP key, endpoint key or promotion key.
- A schedule holds a non-null `scheduler_key_id`. Workers resolve the credential record
  internally, not by storing/replaying its plaintext secret in task payloads.
- Create named scheduler keys independently on the Keys page, then select one in one or
  more schedules. An unused key is valid. Do not require a new key for every schedule.
  An optional inline “Create key” action uses the same REST operation as the Keys page.
  Show every bound schedule and the revocation impact before revoking a shared key.
- Associate an execution-role ceiling chosen from existing roles, not free-form permissions.
  Proposed allowed roles are viewer or author, default viewer: viewers already may execute
  pipelines. A viewer role does **not** make a pipeline's datasource writes read-only.
- Recommended first version remains issuer-bound: effective authority is the intersection
  of the key's role ceiling, its issuer's current workspace authority, the schedule binding
  and the worker-only execution capability. Compare matrix permissions, not numeric role ranks.
- Issuer demotion cannot leave stronger authority behind; later promotion does not raise the
  key's ceiling. User/workspace deactivation, membership removal, expiration or revocation
  blocks new starts. An independent service-account identity is a separate design option,
  not silently introduced here.
- Scheduler keys authorize executing their bound jobs only; executor-specific target and
  version restrictions are enforced by the executor adapter. These keys cannot create
  schedules, author/release content, manage keys, promote or call arbitrary REST operations.
  Reject this kind on ordinary public authentication paths unless explicitly designed later.
- The browser uses the human's session plus normal CSRF protection. Selecting a key id is
  not authentication: REST must verify authority to assign it and its workspace/bindings.

This extends the current [key model](../../auth.md); it is not current runtime behavior.
Creation of a dedicated scheduler key is a proposed new operation, not a blanket grant to
mint other key kinds. Owner decision: no sixth human workspace role for scheduling;
the key kind restricts its execution capability; the existing roles decide human actions.

**Authentication is not encryption.** Encryption protects a stored secret; possession and
verification of a credential authenticate a caller; authorization decides what it may do.
An in-process worker need not decrypt a bearer token and authenticate to its own REST API.
Recommendation: an internal-only scheduler key is a revocable authorization record with
identity, issuer, role ceiling and bindings, without a usable bearer secret. It still needs
an explicitly constructed trusted execution principal, live authorization checks and audit
attribution. Its id alone never authenticates an external caller. If the unified key schema
currently requires secret material, adapt that model explicitly rather than minting unused
copyable secrets. An independently deployed remote worker would need its own authenticated
transport; “inside a container” does not make an exposed endpoint trusted.

### 4.1 Removal and running work

Removal means durable revocation with history retained. Atomically revoke the key, block
all bound schedules and cancel their unstarted work. Serialize revocation and final launch
admission against the same authorization state; do not rely on a cached key lookup for
admission. A metadata-database failure refuses a new start.

If admission won the race first, work is already admitted: request cancellation of running
executions and recheck at dispatch/node boundaries as appropriate. Revocation is not a
rollback of effects already committed or a guarantee that every driver interrupts instantly.
Show cancellation progress and retain uncertain outcomes. Never resume automatically with
some other key, and never auto-mint a replacement for a revoked scheduler key.

Assigning a replacement key requires an authorized edit and explicit resume. Rotation
changes authorization without rewriting historical key/issuer attribution. Permission
restoration or user reactivation should not silently re-enable blocked schedules.

### 4.2 Proposed action matrix (requires owner review)

| Action | Viewer | Author | Promoter | Workspace admin | Super admin |
|---|---|---|---|---|---|
| Read schedule metadata | yes | yes | no | yes | yes |
| Read run details/results | own | own | no | workspace | workspace |
| Create scheduler key | no | own, execution ceiling only | no | workspace | workspace |
| Create schedule using an authorized existing key | no | own | no | workspace | workspace |
| Edit / pause / resume / delete schedule; Run now | no | own | no | workspace | workspace |
| Revoke scheduler key | no | own | no | workspace | workspace |
| Assign/reuse a key, including replacement | no | own authorized key | no | workspace | workspace |
| Resolve an unknown run and unblock | no | own, after reconciliation | no | workspace | workspace |

“Own” is based on durable issuer/ownership attribution; assigning a key must not grant
access to another person's results. Record creator, requesting actor (for manual runs),
credential issuer, key id and effective role distinctly. Keep existing executor capacity
and own-result visibility rules. Final REST operation names, key caps and row reachability
must land together with ScopeMatrix, auth §7.6 and RoleWalk expectations at implementation.
This table adds no runtime permission by itself.

The owner has settled the role floor: only authors and admins may modify schedules, never
viewers or promoters. Author ownership restrictions above are still a recommendation, not
an owner decision. Cross-author/team editing and sharing another issuer's key need an
explicit delegation policy before delivery; naming something `finance/...` does not grant
that team exclusive access or permission to impersonate another key's issuer.

### 4.3 Key association counts

On the shared Keys page, scheduler keys show **Used by N schedules**, linking to their
authorized usage list. Count current non-deleted associations, including paused/blocked
schedules; optionally show enabled count separately. Zero is useful, not an error. Retained
historical run attribution does not keep the current association count inflated.

Apply the same discoverability to API keys (the existing `endpoint` kind): show **N endpoint
bindings**, with linked paths. The current row model already reads bound paths through
`EndpointKeyBindingRepository`; do not create a second source of binding truth. A path
prefix can cover multiple routes and a more-specific binding can override it, so a binding
count is not a count of effective accessible APIs or request traffic. Label it precisely.
API keys do not become valid scheduler credentials as a side effect of these UI statistics.

Counts and drill-downs are workspace- and visibility-scoped, served by REST, and computed
in batches rather than a query per key. No hidden schedule/path may leak through a total.
Association counts are distinct from optional usage telemetry such as last execution or
request counts. Revocation confirmation explains the affected resources; it does not
delete schedules or endpoints. Existing API-key creation/revocation roles remain unchanged.

## 5. Pipeline-agnostic scheduler; executor-owned parameter resolution

Owner boundary: the scheduler stores the job payload and passes it to an executor. It does
not load pipeline definitions, interpret parameters/keywords, resolve `current`, construct
a pipeline DAG or depend on pipeline/calculator classes. Mentioning pipeline-specific
behavior in this design specifies the first adapter, not responsibilities of scheduler core.

| Component | Responsibility |
|---|---|
| Scheduler | Timing, occurrence identity, opaque payload snapshot, trusted time/identity context, overlap/admission coordination, operational controls, generic job outcomes and notifications |
| Pipeline executor adapter | Target authorization, pipeline/version lookup, keyword/calculator evaluation, type validation, prepared execution snapshot, launch/cancel and outcome reconciliation |
| UI / REST composition layer | Pipeline-specific form options and previews through the adapter; schedule management through the generic scheduler service |

Recommend a small registered executor port, with only the pipeline adapter in the first
delivery. The scheduler recognizes an allowlisted executor identifier and payload schema
version, not arbitrary class names, URLs, code or future unsupported job types. Generic
payload size/depth limits and executor registration checks stay in scheduler core;
domain validation is delegated, not omitted. Do not log opaque payloads: “opaque” does not
mean non-sensitive, safe to render, or trusted.

### 5.1 Frozen execution context supplied by the scheduler

| Proposed context field | Meaning |
|---|---|
| `scheduled_at` | Original UTC occurrence time; absent for an unscheduled manual run |
| `reference_timestamp` | Original occurrence time for cron/catch-up; accepted request time for Run now/background execution |
| `reference_timezone` | Schedule's captured IANA timezone; execution-layer org fallback for unscheduled runs |
| `started_at` | Actual execution start instant, recorded later, independent of the logical reference |

Persist the frozen occurrence context, original payload, schedule revision and key/actor
attribution. Pass trusted context separately from client payload; the client cannot replace
the schedule's time, workspace, identity or occurrence id by embedding matching fields.
The pipeline execution layer derives its dates and propagates context to composed children.

Example: a New York run due September 22 at 23:55 starts September 23 at 00:05.
Its logical Today is September 22 and Yesterday is September 21. A fresh manual Run now on
September 23 uses that day's reference. Logical scheduled time, rather than actual-start
time, remains the recommended keyword clock for review.

Do not silently change existing pipeline `current_date` (org-zone date at execution start)
or `current_timestamp`. New bindings refer to the separate frozen context. Authoritative
occurrence metadata stays outside overridable SQL Context. The execution layer owns any
new context-name collision checks and calculator-output override validation.

### 5.2 Keywords belong to the pipeline execution contract

Recommend one pipeline input resolver shared by scheduled and direct execution, not a
scheduler-specific expression engine and not per-pipeline custom keyword code. Resolve
explicit bindings **before ordinary input coercion/validation and before any node runs**.
The pipeline nodes then receive typed values, e.g. `as_of_date: "2026-09-22"` as a DATE input.
Existing requests containing ordinary literal parameters remain valid and unchanged.

Offer **Fixed value / Today / Yesterday / Calculator** in the pipeline job form. Treat
TODAY/YESTERDAY as reserved keyword identifiers only inside an explicit binding, never by
searching arbitrary strings for magic words. Proposed envelope/wire spelling for review
(not an implemented schedule API or pipeline input contract):

```json
{
  "job": {
    "executor": "pipeline",
    "payload_schema_version": 1,
    "payload": {
      "pipeline_id": "<pipeline-id>",
      "version": "current",
      "parameter_bindings": {
        "as_of_date": {"source": "keyword", "name": "TODAY"},
        "previous_date": {"source": "keyword", "name": "YESTERDAY"},
        "label": {"source": "literal", "value": "TODAY"}
      }
    }
  }
}
```

The scheduler stores `payload` unchanged and passes it with context. Only the pipeline
adapter knows that `version` is a selector or that `parameter_bindings` exists. The ordinary
literal-parameter field and explicit bindings must not both supply the same parameter;
the pipeline resolver rejects ambiguity instead of inventing another precedence rule.

Back the keywords with pure functions: proposed `date_in_zone(timestamp, timezone) → DATE`
for TODAY, existing `add_days(date, -1)` for YESTERDAY. Reuse the
[calculator catalog](../../calculators.md), including type validation. A later/general
calculator binding can use that same registry, with explicit literal/context inputs, not
arbitrary scripts or a second calculator implementation. Unknown keyword/kind, incompatible
types or invalid bindings fail before launch. The keyword registry and public schema must
land together with their docs/tests; these examples add no shipped support.

Subtract a calendar day, not 24 hours. Timestamp windows should use zoned local day
boundaries converted to instants and half-open SQL predicates (`>= start AND < end`).
Do not reinterpret existing inclusive DATE bounds or repurpose `tz_shift`. Those are
pipeline input/calculator semantics, not scheduler algorithms.

### 5.3 Preparation and execution through a narrow port

Proposed operations are validation/preview, prepare, start, inspect and cancel; exact
interface signatures remain for implementation review. At save, delegate domain validation
to the chosen adapter without resolving away `current` or dates for all future runs.
Preview delegates too and is illustrative, not a version reservation.

At admission, preparation validates live execution authority, resolves the version/inputs
and durably freezes an executor-owned execution snapshot. It returns an opaque prepared
reference tied to the occurrence id. Preparation is idempotent for that occurrence and
must not execute pipeline nodes or perform datasource side effects. The scheduler persists
the reference and atomically claims the one launch attempt (§2), then dispatches it through
the port; live authority is checked again at launch. Preparation retries reuse the saved
snapshot. They must not reinterpret `current` or TODAY after a snapshot exists.

If preparation and scheduler claim cannot share a transaction, prove recovery across both
boundaries: recover a prepared reference by occurrence id, safely abandon unclaimed orphan
preparations, and never auto-relaunch a claimed start. The port exposes a standardized
failure/outcome and whether configuration requires blocking, plus execution correlation;
the scheduler does not parse pipeline error-code strings to decide business policy.
Scheduler key/workspace checks and executor target authorization both remain mandatory.

This moves responsibility, not guarantees: immutable admitted inputs, version history,
conservative unknown recovery and no automatic replay still apply. Results and actual
pipeline versions live in executor-owned records, reached through the generic execution
reference; the scheduler does not acquire a pipeline schema just to display them.

### 5.4 The executor and the execution message log (owner requirement 2026-09-25)

**The executor.** The scheduler dispatches an occurrence to a registered executor and never
interprets what the executor does. The first executor is the pipeline executor (§3.1, §5.3);
reports, and any later job kind, are further executors registered under their own id with
their own payload schema. `schedules.executor_id` names the executor; a run's execution
reference is opaque to the scheduler; run state and reason come back through the port's
standardized outcome (§5.3). Nothing pipeline-shaped enters scheduler tables or code.

**Every execution keeps its messages; a scheduled run keeps them too.** Verified on main
(2026-09-25): the execution engine publishes every event through one `EventEmitter`
(`modules/dag`, dag-executor.md §10), and the implementation fans each event out to three
places — the live SSE stream when a client is attached, the Redis replay log that outlives
completion by one hour, and the durable `execution_events` record (metadata-db §4.7:
`execution_id`, monotonic `event_id`, `event_type`, `timestamp`, `payload_json`). The nine
event kinds are `execution_started`, `node_started`, `node_progress`, `node_completed`,
`node_failed`, `pipeline_completed`, `pipeline_failed`, `execution_aborted`, `data_ready`.
Rows are kept `datapipelines.executions.event-retention-days` (default 7) past completion;
the execution row itself outlives its events. Requirements:

- A scheduled run launches through the same lifecycle path as an interactive run (§7), so
  the emitter runs and the durable record is written **without any streaming consumer**.
  No dispatch path may bypass the emitter; a fake executor in the acceptance suite proves the
  contract, and the pipeline executor's scheduled run proves the record row for row against
  an interactive run of the same pipeline.
- **The durable record is readable for its whole retention.** The execution detail page
  already renders the durable rows as node-operation history (`ExecutionDetailController`);
  `GET /api/v1/executions/{id}/events` replays only the Redis copy and answers
  `410 result.expired` after the hour (rest-api.md §6.8). The first delivery makes the REST
  read answer the durable rows as JSON (paged by `event_id`, `execution.read`) once the
  Redis log is gone; the pipeline's own events stay in `execution_events`, written by the
  pipeline as today, untouched by the scheduler.
- The record holds exactly what the wire carries: the existing wire redaction applies
  (no secrets, no result rows, no bearer material); resolved parameter values appear as
  they do on the wire today. Retention and the executor's access rules are those of the
  execution, not the schedule; deleting a schedule keeps its runs and their events.
- **Two logs, one per owner, merged on read (owner 2026-09-25).** The scheduler keeps its
  own append-only trail, `schedule_run_events` (§7): one row per transition of a run —
  occurrence created, queued, claimed, dispatched, execution started (carrying the execution
  reference), not started with its reason, retry within the lateness window, missed,
  cancelled, reconciled with the execution's terminal state, unknown, notification enqueued /
  sent / failed — each with its time, the worker and a reason code; the run row's `state` +
  `reason` (§10 R6) is the projection of the latest row. The pipeline's events stay in
  `execution_events`, and a future executor (reports) writes its own execution log the same
  way. Nothing is copied between the logs. The run detail's **Messages** pane reads both —
  the scheduler trail by run id, the execution's events by the execution reference — and
  shows them merged in time order with a source label; a run whose execution never started
  shows the scheduler trail alone, which is exactly the case an operator needs to read.

## 6. REST and UI

Owner direction, explicitly clarified on 2026-09-22: **scheduler only, REST plus UI**.
The UI uses REST for every schedule operation; there are no scheduler MCP tools.
Existing MCP functionality elsewhere in Datapipelines remains in scope for those features.

The page shell may remain server-rendered. Listing, details, form options, cron preview,
creation, edits, pause/resume, key selection/revocation, Run now and run history all use
authenticated REST contracts. Do not add a parallel htmx-partial mutation/service route.
Human browser calls use the existing session authentication and CSRF mechanism.

Proposed routes: schedules CRUD, preview, pause/resume, run-now and runs under
`/api/v1/schedules`; one-time background admission under the pipeline REST resource.
Schedule mutations use a revision/ETag to prevent stale overwrites. Create, Run now and async
admission require a workspace/caller-scoped idempotency token to survive application/browser
retries; replaying a token with a different request is refused. Scheduled and
manual run origin is stored explicitly, independent of the execution trigger enum.

**Applications are first-class REST clients.** Organizations may expose scheduling to
their customers through their own applications; creation is not restricted to interactive
browser sessions. All schedules are created directly, not imported through promotion.
The application's schedule-management credential is separate from the worker's scheduler
key. Scheduler keys remain execution-only and never authenticate schedule CRUD requests.

Before implementation, specify the supported machine authentication contract and its
ScopeMatrix rows. The existing `endpoint`/“API key” only grants its published endpoint
bindings; it must not silently gain management access. Prefer a workspace-scoped,
least-privilege management credential mapped to existing author/admin authority, without
adding a human role. Whether an existing credential kind can safely express this or a
separate application credential is required remains an explicit auth-design review item;
REST support is required, not deferred by leaving the credential unspecified.

Recommend server-to-server integration: the organization's application authenticates its
customer, verifies customer ownership and uses its management credential from the backend.
Never expose a shared organization credential in a client browser. Datapipelines still
enforces workspace, owner/delegation and authorized scheduler-key assignment on every call;
a caller-supplied customer id or folder prefix is not proof of tenant authority. A shared
application principal may otherwise see all schedules it owns, so external customer
isolation and actor attribution must be defined and tested, not assumed from naming.
Direct end-customer credentials would require an explicit delegated-identity design.

UI scope is complete in the first delivery: a dedicated **Schedules** page listing all
schedules the caller may see in the active workspace, folder explorer/search, registered
executor job form, presets/custom cron, timezone, next five occurrences including offset,
notification email list,
assigned reusable key and role, pause/resume, blocked reason, run history and execution links.
Show due time separately from actual start. Unknown outcomes require clear recovery actions.
Use the existing visual system, empty/loading/error states and light/dark validation.
The pipeline job form supplies `current` (optional numeric pin), pipeline-owned parameter
bindings and executor preview details. These are UI/adapter capabilities, not knowledge
compiled into scheduler core. Do not show schedule draft/release/promotion controls.

Use exactly the established pipeline/template hierarchical-name validator and browsing
conventions, e.g. `finance/daily/revenue`, unique within the workspace. Rename/move preserves
schedule identity, run history and key bindings. Folders organize work; they are not a new
namespace or security boundary. Hide editing controls for viewers/promoters, and enforce
the same author/admin restriction server-side; hiding a button is not authorization.

The parameter-set engine is not yet bound to pipelines; the pipeline adapter should use
the declared pipeline inputs and shared calculator functions. Do not make scheduler core
depend on it, or make delivery depend on the entire new parameter UI engine.

### 6.1 Lifecycle notification emails

Owner requirement (2026-09-22, reconfirmed 2026-09-25): a list-of-email-addresses field per
schedule, stored with the schedule, with a notice on every status change of the job —
start/stop/error/unknown below, plus the schedule-level blocked notice. Proposal: `notification_emails: []` means off; a non-empty list
receives all four event types initially. Per-event toggles can follow if needed.

| Event | Meaning / proposed subject label |
|---|---|
| start | Execution is confirmed running, not merely queued: “Started”. |
| stop | A successful completion: “Completed”; or confirmed cancellation: “Stopped — cancelled”. Include the outcome so success and cancellation are not conflated. |
| error | Failed execution or admission/parameter/version refusal: “Failed” or “Could not start”. |
| unknown | Recovery cannot determine the outcome: “Outcome unknown — action required”. Never report this as a confirmed stop. |

This mapping of “stop” is a recommendation for review. A normal run produces start and
one conclusive terminal event; failure uses error rather than a redundant stop email.
Unknown may be followed by a reconciled outcome, explicitly labelled as an update about
the same run. Do not fabricate a start for a run that failed before launch. Schedule pause,
key revocation and ordinary skipped ticks are control/skip events, not evidence a job
stopped. Send a separate schedule-level “Schedule blocked” notice on an actual transition
to blocked (including key removal), so future work cannot silently cease; do not resend on
every poll. Whether ordinary manual pause/resume also sends mail remains optional.

- Validate and deduplicate recipient mailboxes server-side; reject header injection and
  configure limits on recipients, email size, sending rate and retry backlog. Send separately
  per recipient so the list is not disclosed to every recipient. Editing recipients requires
  schedule-modification authority. External recipients are intentional outbound disclosure;
  support deployment-level recipient/domain restrictions, enforced again before sending.
- Content is minimal: schedule/run identity, generic execution reference, event/outcome,
  due/start/end times with timezone, sanitized reason code and an authenticated UI link.
  An executor may supply explicitly allowlisted safe summary fields such as the actual
  pipeline version; scheduler templates never inspect arbitrary payload/result fields.
  No result rows, SQL, parameter values, stack traces, credentials or bearer links. Escape
  names in HTML and validate header fields. Do not log bodies or raw recipient lists.
- Persist an outbox event atomically with each durable scheduler state transition, with a
  unique `(event_id, recipient)` delivery identity. Execution-state reconciliation must
  recover missed scheduler transitions and enqueue their events, not rerun the job.
  Snapshot recipients when the event is created; historical events are not redirected to
  a newly added address. Revocation blocks pipeline launches, not already-authorized minimal
  lifecycle notices. Deployment security restrictions still apply at actual delivery.
- Send asynchronously through the existing configured SMTP transport, with bounded retries
  and visible pending/accepted/failed/uncertain delivery state. Email failure never changes
  the pipeline outcome or retries the pipeline. Mail disabled/unconfigured is prominently
  visible in the form and schedule details, never described as successful delivery.
- Deduplicate event creation, retain a stable Message-ID per delivery, and include event
  time/sequence because email arrival order is not guaranteed. SMTP acceptance is not proof
  of inbox delivery; a crash/timeout after acceptance can make a retry duplicate a message.
  Do not promise exactly-once email. Use bounded automatic retries for known safe transient
  failures; surface ambiguous sends for explicit resend with duplicate warning. See
  [SMTP reliability discussion, RFC 5321 §6.1](https://www.rfc-editor.org/rfc/rfc5321#section-6.1).

Existing account mail lives in `modules/auth` (`MailNotifier`, `MailSender`, `MailMessage`).
Its password-notice claim policy is not a general durable scheduler outbox. Reuse the
transport/configuration via an adapter and minimally generalize its auth-specific message
kind contract if needed; do not duplicate SMTP settings or reuse account-notice identities.

### 6.2 Dedicated scheduler module

Add `modules/scheduler` as a library within the existing application image, not a separate
service or deployment requirement. It owns schedule domain/services, repositories, the
db-scheduler adapter, occurrence/recovery policies, the generic executor port, opaque payload
storage and notification outbox/dispatch. It does **not** own pipeline parameter binding,
keyword evaluation or version resolution. Keep db-scheduler types behind its boundary.

Keep integration seams in their established homes:

- `web`: thin REST controllers and the Schedules/Keys UI; every scheduler operation calls REST.
- `auth`: key kinds, role ceilings, liveness and permission matrix; no scheduler-specific
  authorization fork. Do not make auth depend on scheduler repositories to populate a UI count.
- `calculators`: reusable pure functions used by the pipeline execution input resolver;
  scheduler has no dependency on this module.
- Pipeline execution/application layer: owns `current`, keyword/binding resolution,
  prepared execution snapshots and existing execution authorization/lifecycle APIs. The
  adapter invokes this layer; do not create a second pipeline runner.
- `app`: composition/configuration and Flyway migrations under the existing convention.
  A notification adapter reuses the shared mail transport; scheduler-specific templates and
  event policy belong with the scheduler, not in the account-notice dispatcher.

Recommended wiring: scheduler defines a generic executor port; the composition-layer
pipeline adapter implements it and depends on the existing application/execution services.
Keep the adapter outside `modules/scheduler` (in `app`, or a thin adapter module if needed).
Scheduler must not depend on `application`, `pipeline-contract`, `dag` or `calculators`;
it may use shared infrastructure/auth contracts without learning pipeline semantics.
Pipeline execution must not depend on scheduler just to understand keywords: time context
and input-resolution contracts belong on the execution/shared side and the adapter maps
the scheduler's generic context to them. Add dependency guards and prove the scheduler can
be tested with a fake non-pipeline executor. No second production job type is required.

## 7. Persistence and implementation corrections

Migration number: allocate the next available number when implemented. V15 is already the
lake registry and must not be reused. Historical prompt 092 and its old paths/counts are
superseded by this revision and the current repository working agreements.

Logical schema, not executable DDL:

- `schedules`: workspace, hierarchical name, concurrency revision, registered executor id,
  payload schema version, opaque JSON payload, notification email list, cron/timezone,
  missed-run policy, key FK, creator, enabled flag, blocked reason/time, timestamps and
  next due time. No pipeline/version/parameter-specific columns or lifecycle status.
  Name unique within workspace; deletion retains run history. No schedule artifact-version
  table, release metadata or promotion schema. Payloads follow existing sensitive-input
  storage/access/retention rules, not a public free-form metadata field.
- `schedule_runs`: id, optional schedule FK (null for ad-hoc), origin, original UTC due
  time, schedule revision, frozen opaque payload/time context, key/actor attribution,
  opaque preparation/execution reference, state, reason, worker/admission token and
  lifecycle timestamps. The pipeline executor stores actual version and resolved inputs
  in its own execution records, not additional scheduler-owned columns.
- Unique `(schedule_id, scheduled_at)` for cron/catch-up occurrences; separate uniqueness
  for manual idempotency tokens. Null schedule ids cannot deduplicate ad-hoc requests.
- Proposed run states: queued, starting, running, succeeded, failed, aborted, skipped,
  unknown. Recovery is recorded as an event/metadata, not a misleading successful reclaim.
- `scheduled_tasks`: library-owned shape, created through our Flyway migration from the
  exact selected library version's PostgreSQL DDL.
- Key/auth schema changes: the scheduler kind, associated role/binding, lifecycle and
  attribution fields must be specified with the key implementation, not guessed here.
- Notification events/deliveries: durable event identity, run or schedule-transition identity,
  recipient snapshot, sequence, payload metadata, attempt state, retry timing and Message-ID.
  Define retention with execution history; never delete queued deliveries silently.
- `schedule_run_events` (§5.4): append-only, `run_id` FK, monotonic `seq` per run, `kind`,
  `reason`, `at`, worker/instance, small `details` JSON (execution reference, retry number,
  notification event id); never updated; retained with the run's history. The pipeline's
  events stay in `execution_events` (metadata-db §4.7) and are not duplicated;
  `schedule_runs.execution_id` is the join the merged read uses.

Use `SCHEDULE` as the proposed execution-trigger spelling consistently; reconcile the old
`SCHEDULED` future placeholder when code/enums/DDL land together. Add scheduler key-kind
execution attribution at the same time. Preserve origin distinctions for manual/cron runs.

One service coordinates schedule/task persistence using proven transaction participation.
Avoid duplicated authorization/execution logic: REST calls the application service;
trusted worker dispatch goes through the same execution authorization and lifecycle path
without requiring a browser or streaming consumer. Do not bypass permissions merely
because a launch is internal.

Operational settings retained as proposals: two worker threads, 10-second polling,
30-second heartbeats, six missed heartbeats, 60-second shutdown wait. Detection includes
polling and reconciliation delay; heartbeat × count is not a strict recovery deadline.
Configuration keys and health/metrics enter their normative catalogs with implementation.

## 8. Acceptance and unresolved decisions

Required evidence before describing the scheduler as complete:

- Two instances deliver duplicate callbacks for the same due instant: one occurrence and
  at most one admitted launch. Do not rely on an uncontrolled race or assert fair picking.
- Use an accelerated internal trigger/controlled clock for frequent tests; public cron
  remains five-field/minute precision. Test that seconds syntax is refused publicly.
- Crash before claim, after claim/before launch, during work, after remote commit and before
  success bookkeeping. Assert conservative recovery and no automatic repeated effects.
- Lose heartbeat while an old worker remains alive: unknown blocks subsequent admission.
- Key revocation versus admission race, queued cancellation, running cancellation, expiry,
  role loss, removed membership, deactivated workspace, rebinding and explicit resume.
- Key ids, plaintext absence, key-kind rejection on ordinary APIs, action matrix/own-result
  visibility and CSRF tested through the real REST/UI paths.
- Delayed start across midnight, catch-up, month/year boundaries, DST gap/overlap, calendar
  yesterday and frozen context inherited by a child pipeline.
- Skip versus latest-only catch-up, overlap across workers, capacity pressure, repeated
  Run now requests and pause/edit/delete races.
- Browser flow: create → preview → assign key → run → history → revoke key → blocked.
  Verify all operations use REST and permissions are enforced there.
- Create one scheduler key, reuse it on multiple schedules, verify counts include paused
  schedules, revoke once and observe all affected schedules blocked. API-key association
  counts correctly describe prefix bindings, not requests or presumed effective routes.
- Follow-release schedule picks a new release without an edit, respects the selected
  pointer policy, rejects a draft/null target, and stores the actual version. Concurrent
  release/edit and incompatible input changes cannot silently alter an admitted run.
- The pipeline adapter accepts `version: "current"` and rejects `"latest"`; scheduler
  round-trips the payload without interpreting either value. Exact numeric versions, if
  retained, are validated by the adapter. Test a deliberate pointer rollback as well as
  a new release, and prove no-version direct execution keeps its existing behavior.
- Fixed STRING `TODAY` stays literal; Today/Yesterday presets produce DATE values from the
  frozen schedule-zone reference, validated against the selected pipeline version.
- Direct and scheduled pipeline execution use the same keyword/input resolver. A fake
  non-pipeline executor proves scheduler timing, notifications and recovery work without
  pipeline classes, keyword knowledge or pipeline-shaped database columns.
- Crash during preparation, after its snapshot but before scheduler claim, and after claim:
  recover by occurrence id without changing the snapshot or replaying admitted execution.
- Rename/move a schedule without losing its history or bindings; direct viewer/promoter
  REST mutations fail even if a caller constructs requests outside the UI.
- UI and authenticated application REST both create immediately usable schedules, with no
  release step. No schedule promotion endpoint/package or artifact-version state exists.
  Pausing and blocking remain independent operational controls; resume cannot bypass a block.
- Application create retries create one schedule; changed payload under the same idempotency
  token is refused. Test unauthorized workspace/customer access, key assignment, payload
  visibility and revocation. A scheduler key cannot call management REST. Prove the selected
  machine-auth path without a browser session and without widening endpoint-key authority.
- Real SMTP test sink: start, success, cancellation, failure, pre-start refusal, unknown and
  reconciliation; multiple recipients; blocked-key notice; disabled mail; transient and
  ambiguous send failures. Duplicate recovery callbacks enqueue one event; mail trouble
  never reruns a job. Verify recipient/content isolation and outbox crash recovery.
- API-mode instance does not dispatch tasks; worker instance does. Source/target evidence
  proves actual pipeline effects, not only scheduler history rows.

- A scheduled run of a pipeline leaves the same `execution_events` rows as an interactive run
  of the same pipeline (count and kinds), with no SSE client attached, and a complete
  `schedule_run_events` trail; the run detail merges both in time order with a source label;
  a run refused before launch shows its scheduler trail alone; the durable execution rows are
  readable through REST after the Redis hour and until the retention cutoff.

### 8.1 Fable review before implementation lock

Review these unresolved details, without reopening the explicit owner decisions above:

1. **Executor boundary:** generic port, preparation/claim transaction boundaries, orphan
   recovery, dependency guards and executor-owned history. No pipeline semantics in scheduler.
2. **Authorization:** issuer-bound versus independent execution identity, role ceilings,
   cross-author key/schedule delegation, application management credential, tenant isolation
   and the proposed action matrix. No new human role; application REST is required.
3. **Pipeline input contract:** exact binding syntax, shared keyword resolution, numeric-pin
   option, the development DRAFT-pointer policy for `current`, frozen logical time and
   existing Context compatibility. `current` is decided; `latest` is not a competing mode.
4. **Operations:** no-replay guarantee, unknown recovery/unblock procedure, lateness and DST
   behavior, block-versus-pause presentation, notification stop semantics and mail recovery.
5. **Delivery proof:** library adapter/transaction integration and the acceptance scenarios
   above, including application-only creation and no schedule release/promotion lifecycle.

After Fable review, incorporate findings and obtain the owner's implementation lock.
Do not treat this draft, or the superseded prompt 092, as approval to start implementation.

Current product auth, calculator and execution documents remain authoritative for shipped
behavior. Add new keys, kinds, roles/operations, routes and error codes to their catalogs
only in the same commits as their implementations and drift guards.

## 9. Pre-implementation review findings (2026-09-23)

Recorded so they are not lost. **Nothing in this section is a decision**; recommendations are
labelled as such. Checked against main at `be0305b3` (v0.0.1rc) and against the db-scheduler
16.12.0 sources and jar. Item ids are stable for discussion.

**Permissions come first (owner, 2026-09-23).** The permission discussion produced its own design
record, [permissions and keys](2026-09-23-permissions-and-keys-design.md) (#215), which lands before
the scheduler. Its §8 lists what the scheduler inherits:
- A scheduler key is its own identity, with the `viewer` role, created by authors and above. This
  settles B4, B5 and B6 below.
- Schedule permissions are named `schedule.*`.
- Under D2, any author may modify any schedule unless ruled otherwise, which conflicts with the
  "own" column in §4.2.
- B7 (the application credential for schedule management) and B14 (what viewers see) stay open for
  the scheduler round.

### 9.1 Statements to correct against main (no ruling needed)

- **A1. The Keys page and key minting are admin-only today.** `GET /api-keys` and every minting
  route declare `MANAGE_API_KEYS`: viewer ✗, author ✗, promoter ✗, workspace admin ✓, super admin
  ✓ (auth §7.6; D17: associating a credential is the workspace admin's verb). §4 and §4.2 let
  authors create and revoke scheduler keys there. That needs a new operation row or a change of
  plan (B5).
- **A2. The name grammar is not reachable from the scheduler.** The validator is `internal` to
  `pipeline-contract` (`PipelineNameGrammar.kt`); only the published pattern is public. §6
  requires reusing it and §6.2 forbids `scheduler → pipeline-contract`. Move the grammar to a
  layer-0 home (the parameter engine needs the same), or allow the dependency.
- **A3. The composition root is `web/config`, not `app`.** `app` depends only on `web`. The
  in-process, non-streaming runner to reuse is `web/pipelines/RecordingExecutionRunner` (runs to
  completion, records the row and events, takes the trigger as a parameter). Put the adapter in
  `web`, or move that runner into `application` first.
- **A4. The dependency guard exists:** module-structure §4.2 plus `allowedInternalDependencies` in
  the root `build.gradle.kts`. `:modules:scheduler` needs a row in both.
- **A5. A crash is recorded as a conclusive abort today.** `StaleExecutionSweeper` writes `ABORTED`
  with `pipeline.execution.instance_lost` after three missed execution heartbeats (15 s each), even
  for a live but stalled worker. `ExecutionRepository.complete()` has no `status = 'RUNNING'`
  guard, so that worker can later overwrite `ABORTED` with `SUCCESS` or `FAILED`. §2.1's table
  would read the crash as terminal: `instance_lost` must map to `unknown`, and reconciliation must
  expect a later status change (B2).
- **A6. There is no "acquire capacity" API.** The `ExecutionSlots` slot is taken inside
  `PipelineExecutor.execute`, and admission rejects rather than queues. The rejection is thrown
  before `execution_started` (no row, no event, no node), so it is a definitive "never started"
  that may be re-queued; no executor change is needed (B2, B3).
- **A7. Shutdown order.** `ExecutionDrainLifecycle` (maximum lifecycle phase) cancels every local
  execution on SIGTERM (`ABORTED`, reason `shutdown`). The starter's `Scheduler` stops through its
  bean `destroyMethod`, after the drain, so it keeps picking work while the drain cancels it. The
  dispatcher must stop admitting first. The proposed 60 s shutdown wait exceeds Spring's 30 s
  per-phase timeout, the Helm grace period (30 s) and the compose default (no
  `stop_grace_period`, so Docker's 10 s).
- **A8. db-scheduler serializes task data with Java serialization by default** (core and starter).
  Require the Jackson serializer, or a UUID-string payload with a custom serializer, and a test
  that fails if the default is used.
- **A9. DST behavior is measured** (§9.3). `CronStyle.UNIX` already refuses a seconds field.
- **A10. `api_keys.key_hash` is NOT NULL** and key ids carry the bearer prefix `dpk_`. `AuthCache`
  keeps key records and memberships for 60 s per JVM, so an uncached admission check means reading
  the key and membership rows inside the admission transaction.
- **A11. No existing credential fits an application.** No role can mint a `user` key over REST
  (`auth.key_kind_not_mintable`); there is at most one live `user` key per (user, workspace); it is
  minted at login and copyable once (#213); and the `author` scope that schedule management needs
  also mutates pipelines and templates (B7).
- **A12. Results and timeouts are sized for interactive runs.** Result TTL defaults to 300 s (max
  3600 s) and `execution-timeout-seconds` is 600, so a night run's caller result is gone by
  morning. Either schedule only pipelines whose outputs go to datasource or lake targets, or
  define a budget for background runs.
- **A13. There is no carrier for a frozen reference time.** Every child execution mints its own
  start instant and its own `current_date`; `ExecuteRequest` has no reference field. The
  pipeline's own calculators keep reading `$current_date` (org timezone, actual start), so a late
  or catch-up run can see two different "todays". Name the new `ExecuteRequest` and
  `NodeExecutionContext` field.
- **A14. A missing execution row does not prove "not started".** `WebEventEmitter` swallows a
  failed `RUNNING`-row insert and the execution continues without a row. Recommendation: on the
  scheduled path, abort before the first node when the row cannot be written. With the pre-minted
  `ExecuteRequest.executionId`, "no row" then proves "no side effects".
- **A15. Idempotency is Redis-backed** (`SET NX`, 24 h, lost on a Redis restart), and §7's
  `schedules` has no idempotency column. Make the create and Run now tokens durable in Postgres;
  keep the `Idempotency-Key` header name.
- **A16. Docs to update with the implementation:** versioning §3.4 says a schedule "records a
  refused run" on a NULL pointer, where §3.1 blocks instead; enums §18 still lists `SCHEDULED`.
- **A17. Migration numbers.** #213 adds a migration, and the parameter engine and transform work
  want the next number too. Allocate at merge time.
- **A18. A DRAFT is not immutable under its number**, and `pipeline_executions` records no body
  hash, so a draft run's version does not identify what ran (B8).
- **A19. Starter defaults.** `threads` defaults to 10. `db-scheduler.enabled=false` removes the
  `Scheduler` bean, which is also the client, so API mode needs a no-op `DbSchedulerStarter` (it is
  `@ConditionalOnMissingBean`) or a separately built `SchedulerClient`. State which loops run in
  which role: dispatcher, reconciler, outbox sender, and the existing sweepers (every replica
  today).

### 9.2 Open decisions, with recommendations

**Permissions and keys (to discuss first)**

- **B4. Where scheduler keys are stored.** (a) `api_keys` with `kind = 'scheduler'` and a nullable
  `key_hash` under a CHECK, every bearer lookup failing closed on NULL; (b) a separate
  `scheduler_keys` table in `auth`. Recommendation: (b). No bearer path can match it, the
  credentials table keeps its NOT NULL, and no `dpk_` id looks like a secret. The Keys page lists
  both.
- **B5. Who mints scheduler keys.** (a) Admins only, keeping D17: an author's schedule then runs as
  the admin and, under D11, the author cannot see its results. (b) A new operation for an author's
  own scheduler keys (✗ ✓ ✗ ✓ ✓): authors assign only keys they issued; admins may assign any key,
  with a warning that the runs belong to the key's issuer. Recommendation: (b).
- **B6. The role ceiling changes nothing today.** Running a pipeline needs only `EXECUTE_PIPELINE`,
  which viewers hold. Either state what an `author` ceiling adds, or fix the ceiling at viewer and
  drop the selector. An issuer demoted to viewer keeps their schedules running but can no longer
  manage them.
- **B7. Application credential for REST.** (a) A service member's login-minted `user` key: works
  today, but it is human-shaped, one per workspace, copyable once and over-privileged. (b) A new
  admin-minted `application` kind with its own scope (schedules plus read). Recommendation: (b) if
  customer-facing scheduling is a launch requirement; otherwise (a) now with (b) tracked.
  Server-side isolation is per workspace only; "external customer isolation" has no mechanism yet
  and should leave v1 acceptance unless a delegation design is added.
- **B14. What viewers see.** Parameter values in the payload and `notification_emails` (personal
  data) are more than metadata, while D11 hides other people's run parameters. Recommendation:
  viewers see name, cron, timezone, state and next due time; owners and admins also see the
  payload and recipients.

**Engine, admission and recovery**

- **B1. Engine shape.** A recurring task per schedule computes its next run from completion time
  (ticks during a long run leave no record), holds a thread for the whole run, duplicates the cron
  in `task_data`, and fixes the library's DST behavior. Recommendation: one dispatcher task that
  selects due `schedules` rows `FOR UPDATE SKIP LOCKED`, inserts occurrences with `ON CONFLICT DO
  NOTHING`, advances `next_due_at` with one occurrence function shared with the preview, and
  enqueues a one-time `occurrence:<id>` task in the same transaction. The occurrence task claims,
  starts the execution asynchronously and returns; a reconciler maps execution outcomes back; the
  execution heartbeat and sweeper are the only liveness clock. The spike then proves transaction
  participation and the A7 ordering.
- **B2. State machine and guarantee wording.** Add the missing states (missed; could not start;
  cancelled versus aborted; a catch-up marker) as `state` plus `reason`, with one mapping table:
  `SUCCESS` → succeeded, `FAILED` → failed, `ABORTED`/cancelled → cancelled, `ABORTED`/shutdown →
  aborted, `ABORTED`/`instance_lost` → unknown (blocks), concurrency and pre-start refusals → not
  started. Reword §2 to "at most one automatic launch that may have begun work" and list the
  definitive not-started outcomes.
- **B3. Capacity.** Scheduled runs count against the key issuer's per-user limit (10), shared with
  that person's interactive runs, and are rejected rather than queued. Recommendation: retry within
  the lateness window, plus a separate per-instance budget for scheduled runs.
- **B11. Deploys.** Every deploy aborts in-flight scheduled runs; under `skip` that occurrence is
  lost. Decide: accept it with a notice, have the deploy script wait or warn, or deploy `api` and
  `worker` instances separately. Add `stop_grace_period` to the compose file either way.

**Time**

- **B9. DST rule.** Recommendation: an occurrence in a spring-forward gap runs at the first valid
  instant after it; an occurrence in a repeated hour runs once (first pass) for every pattern; the
  preview uses the same function. The alternative is to accept and document the library's
  behavior (§9.3).
- **B10. Lateness and missed runs.** A configurable default lateness (proposal: 10 min).
  Occurrences due while paused or blocked are not missed and are not caught up on resume. A cron
  or timezone edit recomputes from now. Catch-up has a maximum age. Rename the catch-up value
  `run_now` to `latest`. Run now while a run is active answers 409 rather than recording a skip.

**Pipeline adapter and scope**

- **B8. `current` naming a DRAFT.** Recommendation: RELEASED-only for every scheduled origin (A18),
  at the cost of differing from D63, where endpoints serve a draft pointer in development.
- **B13. Background runs are unspecified:** the principal (a person, not a key) and its re-check at
  launch, the `triggered_via` value, roles, notifications and result TTL (A12). Recommendation: a
  slice of its own.
- **B15. Reverse lookup.** With an opaque payload nothing can answer "which schedules run this
  pipeline?". Recommendation: the adapter returns a generic `target_ref` (for example
  `pipeline:<uuid>`) at save, stored in an indexed column.
- **B16. Binding scope.** Recommendation: v1 supports literals, `TODAY` and `YESTERDAY`; pipelines
  derive other windows from a bound date with their own calculators; general calculator bindings
  come later.

**Notifications**

- **B12. Defaults and safety.** All four events by default means 192 emails a day per recipient
  for a 15-minute schedule. Recommendation: per-event toggles in v1, defaulting to failed, unknown
  and blocked. Keep scheduler mail off unless explicitly enabled, with an empty recipient-domain
  allowlist under the development posture, because a local instance whose environment carries
  production SMTP settings would otherwise mail real recipients. Build links from the configured
  base URL, never the request host.

**Persistence and catalogs**

- **B17. Deletion and names.** Pipelines use a plain `UNIQUE (workspace_id, name)` and no soft
  delete. For schedules choose soft delete plus a partial unique index on live rows, or
  `schedule_runs.schedule_id ON DELETE SET NULL` with a name snapshot on the run. Deleting during a
  run blocks new starts and leaves the running execution alone unless the caller also cancels.
- **B18. Catalogs to fill before dispatch:** error codes (the §13 count moves), configuration keys,
  metrics and health, retention of runs and outbox rows, a minimum cron interval (proposal: 5 min)
  and a maximum number of schedules per workspace.

### 9.3 Measured DST behavior

db-scheduler 16.12.0, `CronSchedule(pattern, America/New_York, CronStyle.UNIX)`, each next time
computed from the previous one:

| Pattern | Date | Occurrences |
|---|---|---|
| `30 2 * * *` | 2026-03-08 (spring forward) | none: the day is skipped |
| `30 1 * * *` | 2026-11-01 (fall back) | one: 01:30 EDT |
| `*/30 1 * * *` | 2026-11-01 (fall back) | four: 01:00 and 01:30, EDT and EST |
| `0 30 2 * * *` | any | refused: six fields |

### 9.4 Proposed delivery slices

1. Core: module and dependency (A8), tables, dispatcher (B1), state machine (B2), a minimal
   scheduler key (B4, B5), the pipeline adapter with `current`, RELEASED-only and literal
   parameters, REST, and the two-instance, crash-window and DST tests. This freezes the port and
   the tables.
2. The Schedules UI on that REST contract.
3. Bindings: `TODAY`/`YESTERDAY` and the frozen reference context through children (A13).
4. Notification outbox and email (B12).
5. Application credential (B7) and key association counts (§4.3).
6. Background runs (B13).

Collisions: #213 (key service, Keys page, a migration), the parameter engine (module table, next
migration, and the name grammar if A2 moves it), transform nodes (`dag`, `templates`). Slices 1
and 5 touch `auth`.

### 9.5 Confirmed against main

db-scheduler 16.12.0 is still the latest release and is built against Spring Boot 3.5.14 (ours is
3.5.16). The starter wraps the DataSource in `TransactionAwareDataSourceProxy`. `ManualScheduler`
and `SettableClock` ship in the main jar. §2.2's description of retry, revival and next-run
behavior is accurate. `current_version` behaves as §3.1 says (D60, D63). `add_days`, `tz_shift`,
`current_date` and `current_timestamp` exist; so do `EndpointKeyBindingRepository`, `MailSender`
and `MailMessage`. V15 is the lake registry. Viewers execute, and runs are own-only under D11. The
guards the spec names exist, and the pipeline editor already calls `/api/v1` from the browser with
the CSRF header.

### 9.6 Second review: blockers, proposed defaults and order (2026-09-23)

A second reviewer (the orchestrator session) read §9 and sent the proposals below. They are
recorded here as received, reworded only for this public document. **Nothing in this subsection is
ruled.** The owner will take it up after the permissions round (#215) lands.

**What changed after §9 was written:**
- #213 merged, so the key-surface collision is gone and V32 is taken.
- The transform engine (#7, lane 7a) merged, and lane 7b is in flight with V33. The first scheduler
  slice therefore shares files with the transform lanes.

**Five rulings that block the first prompt, with the reviewer's recommendations:**
1. **Engine shape (B1).** One dispatcher task, as in B1.
2. **Run states (B2).** A state plus a reason, one mapping table, `instance_lost` → unknown (which
   blocks the schedule), and the reworded guarantee. Accept as written.
3. **Where scheduler keys are stored (B4).** A separate table, not a new kind in `api_keys`.
4. **Who creates scheduler keys (B5).** A new permission for authors to create their own keys;
   admins may assign any key, with a warning that the runs belong to that key's owner.
5. **The application credential for the schedule API (B7).** For the first release, a
   service-account member using its login key, with an admin-created application key tracked as a
   follow-up. Isolation is per workspace only, so external-customer isolation leaves v1 acceptance.

**Defaults to write in unless the owner objects:**
- **Capacity:** a separate slot budget for scheduled runs; a run refused for capacity retries within
  its lateness window.
- **Pipeline version:** RELEASED only.
- **Daylight saving:** a run in the spring-forward gap runs just after the gap; a run in the repeated
  autumn hour runs once.
- **Lateness:** fixed at 10 minutes. Runs due while paused are not missed. The catch-up value is
  renamed `latest`. Run now during an active run answers 409.
- **Notifications:** per-event toggles defaulting to failed, unknown and blocked. Email is off unless
  explicitly enabled, so a local instance cannot mail real recipients through production SMTP
  settings. Links use the configured base URL.
- **Visibility:** viewers see a schedule's name, timing and state; parameter values and recipient
  lists are for the owner and admins.
- **Pipeline link:** each schedule stores an indexed pipeline reference, so a pipeline can show which
  schedules run it.
- **First-release bindings:** literals plus `TODAY` and `YESTERDAY`.
- **Deletion:** soft delete; a running execution is left alone unless the caller asks to cancel it.
- **Limits:** a 5-minute minimum interval, a cap on schedules per workspace, and the error, config
  and metric catalogs written before code.
- **Deploys:** accept that a deploy aborts in-flight scheduled runs, with a clear history row; add a
  shutdown grace period to the compose file.
- **Execution role:** the role selector is dropped, since running needs only viewer. Background
  ad-hoc runs become their own later slice.
- **Name grammar:** moves into the `typesystem` module (A2's first option).

**Order against the transform lanes (the reviewer's proposal):**
- The core slice cannot start before 7b merges: both edit the scope matrix, the auth role table and
  the migration numbering.
- After 7b, the remaining shared files are the error-code count, the configuration doc and the module
  dependency table, which can be split at merge.
- It recommends running the scheduler core beside lane 7c. The alternative is starting after 7e,
  which keeps the strict sequence at the cost of several lane cycles.

**Owner's decision the same day:** security first. The permissions round (#215) follows 7b, and
lanes 7c–7e are held. The scheduler, including these blockers and the parallel-versus-sequential
question, comes after #215.

**Reconciliation with the ratified permissions record (#215), for the scheduler round:**
- **Blocker 4 is largely answered.** Authors and above create scheduler keys
  (`scheduler_key.create`), and every scheduler key is its own identity. Runs belong to the key's
  identity, not to an admin or to the author, so the "author cannot see their own schedule's
  results" problem does not arise in that form. The assignment warning becomes moot, because the
  creator no longer matters. What remains is who may read a schedule's runs besides the
  `execution.read_all` holders.
- **Blocker 3 stays open** (permissions record §8). A separate table gives the strongest guarantee
  that no bearer lookup matches a scheduler key. An `api_keys` row with no secret for kind
  `scheduler` keeps one key model and one role CHECK; that is cheaper now than when B4 was written.
- **Blocker 5 has a cleaner option now.** A service-account member is a machine using a human-kind
  account, whose MCP key (capped at author) can also edit pipelines. With role-carrying keys, a
  pre-created key role holding the `schedule.*` permissions, offered to API keys, gives an
  application a least-privilege credential without a new key kind.
- **The execution-role default** matches #215: scheduler keys offer `viewer` only.
- **The visibility default** needs restating as permissions. D2 makes ownership no authorization
  dimension, so "the owner" cannot gate a read. Instead, for example, `schedule.read` for metadata
  and a separate permission for parameter values and recipients.
- **The §9.1 corrections A1, A10 and A11** describe the key model #215 replaces. Re-verify every
  §9.1 item against main after #215 merges, before the spec is fixed.
- **The proposed order predates #215.** "After 7b, beside 7c" becomes "after #215".

## 10. Owner rulings of 2026-09-25 (answers to §9.2 / §9.6; normative once ratified)

Taken in the orchestrator session on 2026-09-25 with the review's recommendations in hand;
the store note `notes/2026-09-25-scheduler-rulings.md` carries the consequences in full.

| # | Hole | Ruling |
|---|---|---|
| R1 | Engine shape (B1) | One dispatcher recurring task: due schedules selected `FOR UPDATE SKIP LOCKED`, an occurrence row per fire (`ON CONFLICT (schedule_id, scheduled_at) DO NOTHING`), `next_due_at` advanced by the one occurrence function the preview also uses (our DST rule), a one-time `occurrence:<id>` task enqueued in the same transaction; the task starts the execution asynchronously and returns; a reconciler maps execution terminal states onto runs. |
| R2 | Credential (B4/B7, §4) | **No scheduler keys and no key row.** Schedules fire under a per-instance `system` identity (`users.kind = system`, no bearer secret) holding the permission to execute pipelines — and later reports — and nothing more. §4's scheduler-key model, §4.2's matrix and §4.3's counts are withdrawn; runs are attributed to the schedule. |
| R3 | Run visibility (B5) | Members by role: a scheduled run is visible to every member whose role reaches `execution.read` / `execution.result.read` for the workspace; own-only (D11) stays for interactive runs. |
| R4 | Capacity (B3) | A per-instance `scheduler.max-concurrent-runs` budget in the configuration catalog, plus retry within the lateness window (with a metric); beyond the window the run is recorded not started. |
| R5 | Origins (B8) | Follow the `current` pointer, drafts included (D63 consistency). Because a draft is mutable under its version number, the run records the fired version number and the body hash read at fire time. |
| R6 | Run states (B2) | `state` + `reason`, one normative mapping table: SUCCESS→succeeded, FAILED→failed, ABORTED/cancelled→cancelled, ABORTED/shutdown→aborted, ABORTED/instance_lost→unknown (blocks the schedule), admission and pre-start refusals→not started; missed vs overlap-skip and catch-up as reasons. §2's guarantee reads "at most one automatic launch that may have begun work". |
| R7 | Parameters (#194) | v1 binds literal parameter values per schedule, validated as an interactive run's are; the parameter engine plugs in later as another value source. |
| R8 | Roles | `schedule.create/update/pause/delete` = author, workspace_admin, super_admin; `schedule.read` = every member including viewer; the promoter lens shows released-pipeline schedules only. A creator demoted to viewer keeps their schedules running but cannot manage them. The execution-role ceiling selector (B6) is dropped. |
| R9 | Isolation | The "external customer isolation" acceptance line is dropped from v1: isolation is per workspace. |
| R10 | Execution messages | §5.4: two logs, one per owner — the scheduler's own append-only `schedule_run_events` trail, the pipeline's events in `execution_events` as today — merged on read by the run detail's Messages pane; the durable execution rows readable through REST for their retention. |
| R11 | Notifications | §6.1 stands as the owner's requirement: recipients stored on the schedule; a notice on every status change. |

Sequencing: keys v2 (#233) lands first (the identity kinds and role catalog the system identity
rides on); then lane 1 = this record's amendment (§9.1's A1–A19 folded in, this section made
normative) + migration + the system identity + dispatcher/occurrences/reconciler with the two
spike proofs of R1; §9.4's slices follow once lane 1 freezes the contract.
