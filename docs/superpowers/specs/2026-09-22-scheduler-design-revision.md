# Scheduler revision: durable occurrences, a system identity and REST-backed UI

**Status:** RATIFIED 2026-09-25 (scheduler lane 1, #9). Normative for implementation. The owner's
rulings of 2026-09-25 (§10, R1–R11) are normative; the pre-implementation review's corrections
§9.1 A1–A19 are folded into the body, each place marked **(A#)**; the rulings are cited **(R#)**;
the four decisions the owner took when lane 1 asked (§11, L1–L4) are cited **(L#)**, and the review's
§9.6 defaults this record adopts where no ruling speaks are cited **(D-9.6)**. §4's scheduler-key
model is **withdrawn** (R2) and replaced by the system identity. Where §9 (kept as history) and
the body disagree, the body wins.
**Date:** 2026-09-22 (draft); ratified 2026-09-25. **Delivery:** [GitHub issue #9](https://github.com/msabiransari/datapipelines/issues/9), in the slices of §9.4.
**Supersedes:** the implementation details in the [September 7 design](2026-09-07-scheduler-design.md).
**Shipped by slice 1:** the scheduler core (module, tables, dispatcher, occurrences, runs and their
trail, the system identity, the pipeline executor adapter, REST). The Schedules UI, bindings,
notifications, the application credential and background runs are later slices (§9.4); a statement
below about one of those describes that slice's contract, not slice 1's runtime.
**Distribution:** contributor design material; not packaged as product documentation.
**Review:** pre-implementation findings recorded in §9 (2026-09-23), answered by §10 and §11.

The owner asked to resolve the exactly-once claim, introduce scheduler keys with associated
roles, stop schedules when their key is removed, use REST and a UI whose operations call
REST, fix stale implementation details, and use calculators for schedule-relative dates.
The owner subsequently asked for a notification email list (start/stop/error/unknown), a
dedicated module and Schedules page, hierarchical names, modification by authors/admins,
independently created reusable scheduler keys, association counts for scheduler and API
keys, and following the selected pipeline version rather than routinely changing version pins.

Owner decisions of 2026-09-22, kept by the ratification:

- The symbolic version value is **`current`**, not `latest`.
- Schedules have no draft/released/discarded lifecycle and are **never promoted**. They are
  created directly through the UI or REST, including by applications offering scheduling
  to their clients. There is no release step for a schedule.
- Keep operational controls and **blocked** state distinct from an artifact lifecycle.
- There is **no new human role** for scheduling.
- The scheduler is **pipeline-agnostic**: it stores an executor payload and passes it through.
  Pipeline version selection, keyword interpretation and parameter semantics belong to the
  pipeline execution layer, not to the scheduler module.

Those directions are requirements. Two of the owner's earlier requests are superseded by the
2026-09-25 rulings: **scheduler keys** (independently created, reusable, with associated roles
and association counts) are replaced by one system identity (R2, §4), and the per-key
**association counts** go with them (§4.3 withdrawn; the API-key binding count remains a slice 5
item). The keyword wire format (§5.2) is slice 3's contract and the application credential
(§6, B7) slice 5's; both remain subject to that slice's review.

## 1. What remains from the ratified design

- Use db-scheduler with the existing PostgreSQL service, rather than build a timer/lease engine.
  **`16.12.0`** (core, `db-scheduler-spring-boot-starter`, `db-scheduler-spring-common`), verified
  the current release on 2026-09-25 against `repo1.maven.org` metadata (latest = release =
  16.12.0, published 2026-05-29; the search index still says 15.6.0 and is stale), Apache-2.0,
  starter built against Spring Boot 3.5.14 (ours: 3.5.16). Pinned exactly in the catalog.
- Schedules are workspace-scoped entities in folders, separate from pipeline definitions.
- Delegate payload validation to its executor at save and admission. The pipeline executor
  accepts `version: "current"`; optional exact numeric versions remain a proposal (§3.1).
- Five-field Unix cron, an explicit IANA timezone, presets and a next-five-occurrences preview
  using the same parser as execution.
- Reuse pipeline execution history, node statistics, cancellation and stale-execution handling.
- Default missed-run policy is `skip`; optional catch-up runs one missed occurrence, not an
  unbounded backlog. Section 3 narrows this to work that has never started.
- Skip overlap for the same schedule; do not accumulate concurrent runs of it.
- One application image. Whether an instance DISPATCHES is deployment configuration
  (`datapipelines.scheduler.enabled`, default `true`), not an authorization role **(A19)**. An
  instance with it `false` is an API-mode instance: it keeps db-scheduler's `Scheduler` bean —
  which is also the client its REST calls enqueue through — but supplies a no-op
  `DbSchedulerStarter`, so it never polls, dispatches, claims or reconciles; its REST surface and
  the existing synchronous execution routes are unchanged. A worker-only instance (dispatch
  without serving) is a deployment topology, not a scheduler mode, and is out of scope. The
  loops by role: the dispatcher, the occurrence worker and the reconciler run only where
  `enabled`; the existing sweeper, pool reaper and event retention run on every replica as
  today (they are idempotent — the sweeper's KDoc says why).
- The feature's first release includes the complete Schedules UI and REST surface, delivered in
  the slices of §9.4: slice 1 the core and REST, slice 2 the UI, slice 3 bindings, slice 4
  notifications, slice 5 the application credential, slice 6 background runs **(B13)**.
- Lifecycle email notifications are in the first release (§6.1, slice 4). Reports, standalone
  email jobs, automatic pipeline retries/backoff and bulk backfill remain out. Schedule promotion
  is explicitly not part of the model, rather than merely deferred.

Do not reserve report/email enum values with unsupported behavior. Add future job kinds with
their implementations. Historical prompt `092` must not be dispatched unchanged; use issue
number 9 for new delivery records under current repository policy.

### 1.1 No artifact lifecycle; operational controls only

A schedule is mutable operational configuration, created and edited in place through the
same REST contract used by the UI and external applications. It has no artifact versions,
release/discard/restore workflow, promotion package or promotion endpoint. The numeric
`revision` is solely optimistic concurrency/audit metadata, not a releasable schedule version.

`schedules` stores `enabled` (manual pause/resume) and `blocked_reason`/`blocked_at`
separately. Display **Enabled**, **Paused** or **Blocked** as an operational condition, not a
content status; blocked takes display precedence and can coexist with manual pause. Resuming a
paused schedule cannot clear an unresolved block: that is its own verb, **unblock**
(`POST /api/v1/schedules/{id}/unblock`, `schedule.pause`), which revalidates the executor
payload through the adapter (§5.3) before it clears the block, records who cleared it on the
trail of the run that caused the block (`schedules.blocked_run_id`), and recomputes
`next_due_at` from now (occurrences due while
blocked are not missed, D-9.6). There is no key to revalidate (R2). Run outcomes such as
running/failed/unknown still belong to individual occurrences; they are not a lifecycle of the
schedule definition.

## 2. Guarantee: one occurrence record, at most one automatic launch that may have begun work

Remove the promise of end-to-end exactly-once execution. A scheduler cannot atomically commit
both a remote datasource write and its own success record. Example: a pipeline commits an
INSERT, then its worker dies before recording success. Replaying it may duplicate rows.

The guarantee **(R6)**:

1. Each cron occurrence has one durable identity: `(schedule_id, scheduled_at)` (UTC), unique in
   `schedule_runs`.
2. Duplicate engine deliveries cannot create a second occurrence, and **at most one automatic
   launch that may have begun work** exists per occurrence: the launch is guarded by a durable
   start claim (`queued → starting`, a conditional UPDATE), and nothing re-launches a claimed run.
3. A run is **definitively not started** — and may therefore be retried within its lateness
   window, or recorded `not_started` / `skipped` without doubt — only in these cases: capacity was refused
   before the claim (R4); the executor's preparation refused it (a null pointer, a missing
   target, invalid parameters, an inactive workspace, the system identity's authority); the
   schedule was paused, blocked or deleted before the claim; or the executor's `start` returned
   "not started" because no execution record could be written, which by the fail-closed rule
   below (A14) means no node ran.
4. A run with an uncertain execution/side-effect outcome is `unknown`, is visible, blocks its
   schedule, and is not automatically retried, even when catch-up is enabled.
5. Manual recovery is explicit and recorded — unblock (§1.1) names the person on the trail of
   the run that caused the block. It is never presented as proof that no
   previous external writes occurred. Exactly-once destination effects require destination
   idempotency or transactions designed by that pipeline.

This deliberately trades automatic completion after some crashes for protection against
silent replay. It does not mean every scheduled occurrence executes successfully.

### 2.1 Admission and crash boundaries

Use PostgreSQL transactions for occurrence insertion, schedule-level overlap admission and
the queued-to-starting claim. Do not hold a database transaction across the pipeline's runtime.
Workers acquire execution capacity **before** claiming a start (R4): the executor's slot
admission is reject-not-queue and throws before `execution_started` — no row, no event, no node
(A6) — so a refusal is a definitive "never started"; `ExecutionSlots` gains an
acquire-before-claim lease so the slot the worker takes is the slot the execution runs under.
The worker mints the execution reference (a UUID) and records it in the claim, **before** launch;
the pipeline executor starts under exactly that id (`ExecuteRequest.executionId`), so recovery
reconciles against the existing execution record by it.

**Fail closed at launch (A14).** On the scheduled path the adapter's `start` returns "started"
only once the execution's `RUNNING` row is durably written, and aborts the execution before its
first node when that row cannot be written (today's interactive emitter logs the failed insert
and runs on — correct for an interactive stream, wrong here). "No execution row" therefore
proves "no node ran" for a scheduled run.

| Durable state / evidence | Recovery |
|---|---|
| `queued`, no start claim | Safe to re-evaluate authorization, timing and overlap policy; no pipeline has been admitted to start. A revived occurrence task simply runs the admission again. |
| `starting` claim committed, execution not yet visible | Ambiguous admission boundary; never launch again. When the claiming worker's occurrence task is revived (its heartbeat stopped) or the reconciler finds the claim older than the start grace (db-scheduler's dead-execution window, six heartbeats — 3 min at the defaults) with no execution record, the run becomes `unknown` / `start_unconfirmed` and blocks; an execution that appears later under the recorded reference is an update about the same run. |
| Execution running, heartbeat lost | The existing sweeper marks it `ABORTED` / `pipeline.execution.instance_lost` after three missed execution heartbeats (≈45 s) even for a live but stalled worker **(A5)**; the reconciler maps that to `unknown` (R6) and blocks. Absence of heartbeat does not prove that the old worker stopped. |
| Execution terminal with a persisted outcome | Reconcile schedule history from it (R6's table, §7.1); do not rerun the pipeline merely to repair scheduler bookkeeping. `ExecutionRepository.complete()` has no `status = 'RUNNING'` guard, so a live worker can later overwrite `ABORTED/instance_lost` with `SUCCESS` or `FAILED` (A5): the reconciler keeps watching an `unknown` run's execution for 24 h and records a later real terminal as an update about the same run (its state follows; the schedule stays blocked until a person unblocks it). |
| No conclusive terminal outcome | Mark `unknown`, block future starts of this schedule pending resolution, retain evidence. |

Blocking on an uncertain run prevents a future occurrence from overlapping a possibly live
old worker. Resuming requires an authorized person to resolve the incident after verifying
the previous worker has stopped and examining destination effects (unblock, §1.1). This does
not claim that a database lease can fence writes at every external system.

**Transaction participation (R1 spike 1).** The dispatcher inserts an occurrence and enqueues its
one-time task with `scheduleIfNotExists` in ONE `metadataTransactionManager` transaction: the
starter wraps the metadata `DataSource` in `TransactionAwareDataSourceProxy`, so the client's
statement joins the transaction. Proven, not assumed: a rolled-back transaction leaves neither
the run row nor a `scheduled_tasks` row, beside a committed control (§8). The launch itself does
not join a transaction — it is a separate step after the committed claim, and the claim is what
makes a second call to pipeline execution impossible.

### 2.2 Library behavior that needs explicit integration

Upstream documents automatic revival for dead tasks, a retry default for failed one-time
tasks, and recurring schedules that choose their next future time after completion. Its
client operations also need transaction-aware connection handling to join an application
transaction. Override/reconcile these behaviors for our admission policy; library defaults
alone do not supply the product guarantee or a history row for every overlapping tick.
[db-scheduler documentation, consulted 2026-09-22](https://github.com/kagkarlsson/db-scheduler).

The engine is **one dispatcher** (R1) — db-scheduler supplies the durable queue, cluster-wide
picking and dead-task detection, and none of its per-schedule timing:

- **Tasks.** Three, all owned by `modules/scheduler`: the recurring `schedule-dispatcher` and
  `schedule-reconciler` (fixed delay, `datapipelines.scheduler.tick-interval-seconds`; a recurring
  task has one instance cluster-wide, so a tick never runs twice at once), and the one-time
  `schedule-run` whose instance id and data are the run id. No cron string lives in `task_data`;
  `schedules` is the only source of timing.
- **Serialization (A8).** The starter's default is Java serialization
  (`DbSchedulerConfigurationSupport.SPRING_JAVA_SERIALIZER`; core's `SchedulerBuilder` too) — an
  unsafe-deserialization surface and a rename hazard. The scheduler supplies the JSON serializer
  through the starter's `DbSchedulerCustomizer`, task data is only a run id string, and a test
  fails if the configured serializer is ever the Java one.
- **Threads (A19).** The starter defaults to 10; ours come from `datapipelines.scheduler.threads`
  (default 2). No task holds a thread for a pipeline's runtime: the occurrence task starts the
  execution asynchronously and returns.
- **Retry and revival.** A one-time task that throws is retried by the library after 5 min, and a
  dead execution (heartbeat stopped) is revived. Both are safe by construction: the occurrence
  handler's claim is a conditional UPDATE, so a retried or revived delivery of a claimed run
  launches nothing (it records `unknown` / `start_unconfirmed` for a claim with no execution,
  §2.1). Business outcomes never surface as a thrown exception: capacity waits reschedule the
  task explicitly; refusals complete it.
- **Recurring semantics.** Irrelevant here: the dispatcher and reconciler are fixed-delay
  housekeeping; a schedule's own next time is computed by the ONE occurrence function (§3.2),
  never by `CronSchedule.getNextExecutionTime` from completion time.

Overlap and missed intervals are recorded without a replay loop: a dispatcher tick records at
most one run for the latest due occurrence and at most one **missed-range summary** row for
everything before it (§3), however long the outage. Individual executions are never
fabricated.

## 3. Missed occurrences, overlap and manual runs

- **Normal delay and the lateness window:** a poll slightly after the due instant is normal. A
  run may be admitted until its `admit_by` instant: the occurrence instant plus
  `datapipelines.scheduler.lateness-seconds` (default 600, i.e. 10 min — D-9.6/B10; one
  instance-wide value, no per-schedule override in v1). A catch-up or manual run's window opens
  when it is recorded. A capacity refusal retries within the window (every 30 s, counted by the
  `datapipelines.scheduler.capacity.retries` metric, R4); beyond it the run is `not_started` /
  `capacity`.
- **Skip** (`missed_run_policy = skip`, the default): an occurrence the dispatcher first sees
  beyond its window is recorded `skipped` / `missed`; the next future occurrence remains
  scheduled. Everything due before the latest occurrence of a tick is compacted into ONE
  `skipped` / `missed` summary row (keyed by the first missed instant, `details` carrying the
  count and the last instant).
- **Latest catch-up** (`missed_run_policy = latest`; the draft's `run_now` value is renamed so it
  cannot be confused with the Run now button, D-9.6/B10): the latest missed, never-started
  occurrence is queued as `origin = catch_up` when it is at most
  `datapipelines.scheduler.catch-up-max-age-seconds` old (default 86 400, 24 h); earlier ones are
  summarized as missed. It keeps its original scheduled instant and timezone as its
  identity/reference time; no new `now()` identity bypasses deduplication.
- **Paused, blocked, edited:** occurrences due while a schedule is paused or blocked are not
  missed and are not caught up — resume and unblock recompute `next_due_at` from now; so does an
  edit of the cron or timezone (old-pattern occurrences are not missed) (D-9.6/B10). A schedule in
  a DEACTIVATED workspace is not dispatched (its occurrences are not recorded); on reactivation
  the stale `next_due_at` is handled as an outage (summary + policy), which is honest: the
  workspace was down.
- **No overlap:** at most one active (`queued`, `starting`, `running`) run per schedule across
  instances — a partial unique index on `schedule_runs`, and every insertion path holds the
  schedule row's lock. A due occurrence while one is active is recorded `skipped` / `overlap`.
  **Run now while a run is active answers `409 schedule.run.overlap`** rather than recording a
  skip (D-9.6/B10). Unknown prior work blocks the schedule rather than clearing this guard.
- **Run now button:** creates an explicit manual run (`origin = manual`, `requested_by` = the
  person) with its own optional `Idempotency-Key`, durable in Postgres **(L1, A15)**; it is not
  catch-up or retry. It passes the saved executor payload and fires under the system identity
  (R2). Its reference time is the accepted request time in the schedule's timezone. It is
  allowed on a paused schedule (a person asked for it) and refused on a blocked one
  (`409 schedule.blocked`). Its permission is `schedule.run` **(L2)**.
- **Rerun an uncertain/failed occurrence:** deferred as an automatic feature. A future explicit
  replay must link to the original occurrence and preserve its input/time snapshot. The UI
  must explain that a new execution may repeat effects.
- **Guards (B18, L4):** a cron whose consecutive local occurrences come closer together than
  `datapipelines.scheduler.min-interval-seconds` (default 300) anywhere in the 14 days after the
  save instant is refused at save (`schedule.validation.interval_too_short`; the horizon covers
  every weekday twice — an exotic pattern whose only tight pair straddles a month end beyond it
  is not caught, and the per-workspace cap below still bounds it), and a workspace holds at most
  `datapipelines.scheduler.max-schedules-per-workspace` live schedules (default 100;
  `schedule.limit.per_workspace`).

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
of the **pipeline**, without promoting schedules. **It follows the pointer wherever it points,
drafts included (R5)** — the owner chose D63 consistency over the review's RELEASED-only
recommendation (B8). Because a DRAFT is mutable under its version number and
`pipeline_executions` records no body hash (A18), the adapter's prepared snapshot records the
fired version number AND the SHA-256 of the version body read at fire time, so a run's history
stays reproducible. A NULL pointer is refused at admission (`not_started` / `pointer_null`, the
schedule blocks); `latest` is refused at save; exact numeric versions are not accepted in v1
(the payload's `version` must be the string `"current"`). The adapter never silently falls back
to another version. Existing ordinary no-version execution and published endpoints retain
their documented behavior.

Pipeline adapter responsibilities:

1. At save, validate the payload shape (`{"pipeline": "<name>", "version": "current"}`, nothing
   else), that the named pipeline exists in the schedule's workspace, and the schedule's literal
   `parameters` against the declared parameters of the version `current` names at save — the
   same binder an interactive run uses (R7). It returns a generic `target_ref`
   (`pipeline:<name>`, B15) the scheduler stores in an indexed column so "which schedules run this
   pipeline?" is answerable without the scheduler learning pipeline semantics.
2. During admission preparation, resolve `current` once, re-validate the parameters against that
   version's declaration, and freeze the snapshot `{pipeline_id, version, body_sha256}` on the
   run row (`schedule_runs.prepared`, an executor-owned JSON the scheduler stores and never
   reads). Preparation executes nothing. Later releases, pointer changes and schedule edits
   cannot change an admitted run; a retried admission reuses a saved snapshot.
3. If no eligible version exists or the parameters no longer bind, return a pre-start refusal
   through the port's standardized outcome with an actionable block reason; the scheduler
   records the run `not_started` and blocks the schedule (notification is slice 4). Never
   silently run the old version or drop invalid inputs. Unblock is an explicit action.
4. Catch-up resolves `current` at admission, not a reconstructed historical version, but keeps
   the original occurrence's reference time. History shows both.
5. Launch under the snapshot's version by the existing explicit-version path
   (`PipelineService.findExecutable(id, version)`); a body whose hash no longer matches the
   snapshot (a draft edited between preparation and launch) is refused `not_started` /
   `version_changed`. Dependencies retain their established versioning rules; selecting a root
   version does not invent transitive dependency pins.

### 3.2 The occurrence function and the DST rule (R1, B9)

Occurrence identities are UTC instants and display includes the local offset. ONE function
computes them, and both the dispatcher and the preview call it — the library's
`CronSchedule.getNextExecutionTime` is never used for timing, because it anchors on completion
time and carries the library's DST behaviour (§9.3). The function:

1. **Parses** the five-field pattern with the library's own `CronSchedule` in `CronStyle.UNIX`
   (so a six-field pattern is refused for free: "expects one of [5]"; the library's disabled
   pattern `-` is refused explicitly).
2. **Matches fields on the local clock**: it asks the parsed pattern for its next matching
   instant in a fixed UTC frame, which yields the next matching LOCAL date-time with no zone
   rules applied.
3. **Converts** each local date-time into the schedule's IANA zone by **our DST rule**:
   - a local time that falls in a **gap** (spring forward) runs at the first valid instant after
     the gap — the transition instant — because a daily job that silently skips a day is a
     missing day of loaded data;
   - a local time that falls in an **overlap** (fall back) runs **once**, at its first pass (the
     earlier offset), for every pattern;
   - several local times that map to one instant (e.g. `*/15 2 * * *` across a whole gap) yield
     one occurrence.
4. **Is measured** against A9's case (§9.3) in its unit suite: `30 2 * * *` in America/New_York
   runs at 03:00 EDT (07:00Z) on 2026-03-08 where the library skips the day; `30 1 * * *` runs
   once on 2026-11-01; `*/30 1 * * *` runs twice that morning (01:00 and 01:30 EDT) where the
   library runs four times.

## 4. The system identity and authorization (R2, R3, R8 — replaces the scheduler-key model)

**Withdrawn (R2, 2026-09-25):** the draft's scheduler keys — a `scheduler` credential kind, a
schedule's `scheduler_key_id`, keys created on the Keys page, issuer-bound authority with a role
ceiling, key revocation blocking bound schedules (§4.1), the key action matrix (§4.2) and the
per-key association counts (§4.3). The review's A1, A10 and A11 described that model and are moot
(§9.1). Nothing below mints, stores or checks a key.

**Schedules fire under the system identity.** It is the existing **system service account**
(`UserService.provisionSystemActor()`, auth.md §4.5): one `users` row (`kind = system`,
`provider = system`, an address under the reserved `.invalid` TLD), provisioned at boot, which
promotion, the retention job and the stale-execution sweeper already stamp their writes with. No
new identity, no key row, no bearer secret. (R2's "per-instance" means per deployment: one row
serves every replica, and every replica's worker fires under it.) It is **never a member** of any workspace and can
**never authenticate**: no OIDC provider may be named `system`, the local-password paths refuse
it, it has no `api_keys` row, and a session token presented for it is refused like a deactivated
account's (§8's security evidence proves each).

**Its authority is a fixed set, answered through the seam.** A scheduled launch is built with an
`AuthenticatedPrincipal` for that row (`AuthMethod.SYSTEM`) whose workspace context is the
schedule's workspace, marked as the system actor's. Its permission questions go to
`PermissionResolver`'s **system arm** — the resolver knows the ACTOR, not a role — which holds
exactly `pipeline.execute`, `pipeline.read` and `execution.read` (the launch, the pinned
version's read, the reconciler's execution read) in any workspace, and nothing else. `ScopeMatrix`
and `AuthenticatedPrincipal.holds` do not special-case it: they reach the arm through the
context. The set is fixed in code (`RolePermissions.SYSTEM_ACTOR`), not a role column of auth.md
§7.6, so `RoleWalkE2eTest` never sees it; a unit test pins the set exactly and goes red when a
test resolver grants one more. It "executes pipelines — and later reports — and nothing more":
a later executor adds its own permission to the set with that executor.

**Attribution.** A scheduled execution records `executed_by` = the system actor,
`executed_by_key_kind` = NULL and `triggered_via = SCHEDULE`; the schedule, its creator and (for
Run now) the requesting person are recorded on the `schedule_runs` row, which is the
attribution. `ExecutedByKeyKind` gains nothing: there is no key.

**Run visibility (R3).** A scheduled run is visible to every member of its workspace whose role
reaches `execution.read` (metadata, events) / `execution.result.read` (the result) — the
pipeline's own reach. D11's own-only rule stays for interactive runs: `ExecutionVisibility` and
the own-runs listing gain one schedule-attributed branch (`triggered_via = SCHEDULE`) and nothing
else. A scheduled run is nobody's "own" run, so cancelling one needs `execution.cancel_all`.

**Roles (R8, L2).**

| Action | viewer | author | promoter | ws_admin | super_admin | Permission |
|---|---|---|---|---|---|---|
| Create a schedule | ✗ | ✓ | ✗ | ✓ | ✓ | `schedule.create` |
| Edit a schedule | ✗ | ✓ | ✗ | ✓ | ✓ | `schedule.update` |
| Pause, resume, unblock | ✗ | ✓ | ✗ | ✓ | ✓ | `schedule.pause` |
| Delete a schedule | ✗ | ✓ | ✗ | ✓ | ✓ | `schedule.delete` |
| Run now | ✗ | ✓ | ✗ | ✓ | ✓ | `schedule.run` (L2) |
| List, detail, upcoming, preview, runs, a run's trail | ✓ | ✓ | lens | ✓ | ✓ | `schedule.read` |
| A run's execution, its events and result | own + scheduled | own + scheduled | ✗ | ✓ | ✓ | `execution.read`, `execution.result.read` (R3) |
| A schedule FIRES | — | — | — | — | — | the system identity's fixed set |

The promoter **lens** on `schedule.read` shows the schedules whose target pipeline the lens
admits (released objects newer than the promotion target's, #178), decided by the adapter over
`target_ref`. **A creator demoted to viewer keeps their schedules RUNNING** — the system identity
fires them — but can no longer manage them; this is intended (R8, B6), and a test says so. The
execution-role ceiling selector is dropped (R8). Viewers see a schedule's payload and parameters
(B14): R3 already shows every member the scheduled runs' resolved parameters, so hiding them on
the schedule would protect nothing. The notification recipient list (slice 4) is decided with
that slice.

**No MCP tools** (owner, 2026-09-22) and **no key reaches the schedules REST surface** in slice 1:
keys v2 confines an `mcp` key to `/mcp` and an `endpoint` key to the published tree, so the
routes are session-only until slice 5's application credential (B7) lands. Isolation is per
workspace (R9): a schedule names only its own workspace's pipelines, and every read is
workspace-scoped in its SQL.

## 5. Pipeline-agnostic scheduler; executor-owned parameter resolution

Owner boundary: the scheduler stores the job payload and passes it to an executor. It does
not load pipeline definitions, interpret parameters/keywords, resolve `current`, construct
a pipeline DAG or depend on pipeline/calculator classes. Mentioning pipeline-specific
behavior in this design specifies the first adapter, not responsibilities of scheduler core.

| Component | Responsibility |
|---|---|
| Scheduler | Timing, occurrence identity, opaque payload snapshot, trusted time/identity context, overlap/admission coordination, capacity admission, operational controls, the run trail, generic job outcomes and (slice 4) notifications |
| Pipeline executor adapter | Target authorization, pipeline/version lookup, keyword/calculator evaluation, type validation, prepared execution snapshot, launch/cancel and outcome reconciliation |
| UI / REST composition layer | Pipeline-specific form options and previews through the adapter; schedule management through the generic scheduler service |

A small registered executor port (`JobExecutor`, §5.3), with only the pipeline adapter in the
first delivery (executor id `pipeline`, payload schema version 1). The scheduler recognizes an
allowlisted executor identifier and payload schema version, not arbitrary class names, URLs,
code or future unsupported job types. Generic payload size/depth limits (16 KiB, depth 8) and
executor registration checks stay in scheduler core; domain validation is delegated, not
omitted. Do not log opaque payloads: “opaque” does not mean non-sensitive, safe to render, or
trusted. `scheduled_tasks` rows carry run ids only — never a payload, parameters or a body.

### 5.1 Frozen execution context supplied by the scheduler

| Proposed context field | Meaning |
|---|---|
| `scheduled_at` | Original UTC occurrence time; absent for an unscheduled manual run |
| `reference_timestamp` | Original occurrence time for cron/catch-up; accepted request time for Run now/background execution |
| `reference_timezone` | Schedule's captured IANA timezone; execution-layer org fallback for unscheduled runs |
| `started_at` | Actual execution start instant, recorded later, independent of the logical reference |

Persist the frozen occurrence context, original payload, parameters, schedule revision and
actor attribution (the system identity, the schedule, and a manual run's requesting person) on
the run row. Pass trusted context separately from client payload; the client cannot replace
the schedule's time, workspace, identity or occurrence id by embedding matching fields.
The pipeline execution layer derives its dates and propagates context to composed children.

**Slice 1 records the context; slice 3 carries it (A13).** There is no carrier yet: every child
execution mints its own start instant and its own `current_date` (`SubPipelineExecutionRunner`),
and `ExecuteRequest` has no reference-time field. Slice 1 therefore freezes `scheduled_at`,
`reference_at` and `reference_timezone` on the run row and hands them to the adapter, and the
pipeline sees exactly what an interactive run sees: `$current_date` is the org-timezone date at
the ACTUAL start. A late or catch-up run can thus see two "todays" — the bound TODAY (slice 3,
schedule timezone, logical time) and the pipeline's own `$current_date`. Slice 3 adds the
`ExecuteRequest` / `NodeExecutionContext` field that carries the reference to children.

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

The port (`JobExecutor`, in `modules/scheduler`) has five operations; their outcomes are
standardized values, and the scheduler never parses an executor's error-code strings to decide
policy:

| Operation | When | Returns |
|---|---|---|
| `validate(workspace, payload, parameters)` | save, and unblock | valid with a `target_ref`, or a refusal (an existing catalogued error the surface renders) |
| `prepare(admission)` | admission, before the claim | a prepared snapshot (opaque JSON), or not-started with a reason and whether to block |
| `start(launch)` | after the committed claim | started (the execution reference recorded at the claim), or not-started with a reason and whether to block; anything else is thrown and recorded `unknown` |
| `inspect(workspace, references)` | the reconciler, in batches | per reference: running, finished (a run state + reason per R6's table), or absent |
| `lensAdmits(lens, targetRefs)` | the promoter's `schedule.read` | which targets the caller's lens admits |

At save, domain validation is delegated to the adapter without resolving away `current` for
all future runs. The preview is the scheduler's own occurrence function (§3.2) and needs no
adapter.

At admission the scheduler first acquires capacity (R4), then asks the adapter to prepare:
preparation checks the system identity's live authority through the seam (§4), resolves the
version and binds the parameters, and returns the snapshot. It executes no node and touches no
datasource. The scheduler writes the snapshot and the minted execution reference IN the claim
UPDATE (`queued → starting`), so a snapshot exists exactly when a claim does and there is no
orphan preparation to abandon: a crash before the claim leaves a `queued` run whose next
delivery prepares again (against the pointer as it is then — nothing was admitted), and a crash
after it leaves a `starting` run that is never re-launched (§2.1). The adapter then starts the
execution through the existing explicit-version path and the same authorization it asks at
preparation; live authority is checked again at launch.

The adapter lives in `web` (A3), beside the runner it reuses: `web/pipelines/RecordingExecutionRunner`
("run to completion in-process, record the row and the events"; trigger is a parameter), with a
fail-closed emitter for the scheduled path (A14). No second pipeline runner is created. The
system identity's authority and executor target authorization both remain mandatory; there is
no key check (R2).

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
  `410 result.expired` after the hour (rest-api.md §10.3). Slice 1 adds the durable read on
  the same route: `GET /api/v1/executions/{id}/events?format=json` answers the durable rows as
  a JSON page (ordered by `event_id`, `after` + `limit` ≤ 500, `execution.read` with the same
  visibility as the metadata read, session-only like it) for as long as they are retained —
  within the hour too — and `410 result.expired` (reason `event_record_expired`) once the
  retention job has removed them; without `format=json` the route is the SSE replay, unchanged.
  The pipeline's own events stay in `execution_events`, written by the pipeline as today,
  untouched by the scheduler.
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
creation, edits, pause/resume, Run now and run history all use authenticated REST contracts.
Do not add a parallel htmx-partial mutation/service route. Human browser calls use the
existing session authentication and CSRF mechanism.

**The routes (slice 1)**, each landing with its auth.md §7.6 row, its `RoleWalkE2eTest`
expectation and its §13 codes in the same commit (AGENTS.md):

| Route | Permission | Contract |
|---|---|---|
| `GET /api/v1/schedules` | `schedule.read` | The workspace's live schedules, `prefix` / `offset` / `limit`; the promoter lens applies |
| `POST /api/v1/schedules` | `schedule.create` | Create; optional `Idempotency-Key` (L1) |
| `GET /api/v1/schedules/preview` | `schedule.read` | `cron`, `timezone`, `count` ≤ 20: the next occurrences from the ONE function (§3.2), with offsets — the form's preview before a save |
| `GET /api/v1/schedules/{id}` | `schedule.read` | Detail; `ETag` = the revision |
| `PUT /api/v1/schedules/{id}` | `schedule.update` | Edit (rename/move included); `If-Match` required |
| `DELETE /api/v1/schedules/{id}` | `schedule.delete` | Soft delete; `If-Match` required |
| `POST /api/v1/schedules/{id}/pause` | `schedule.pause` | Pause |
| `POST /api/v1/schedules/{id}/resume` | `schedule.pause` | Resume (never clears a block) |
| `POST /api/v1/schedules/{id}/unblock` | `schedule.pause` | Clear a block after revalidation (§1.1) |
| `POST /api/v1/schedules/{id}/run` | `schedule.run` | Run now; optional `Idempotency-Key` (L1, L2) |
| `GET /api/v1/schedules/{id}/upcoming` | `schedule.read` | The saved schedule's next `count` ≤ 20 occurrences |
| `GET /api/v1/schedules/{id}/runs` | `schedule.read` | Its runs, newest first, with `execution_id` |
| `GET /api/v1/schedules/{id}/runs/{runId}` | `schedule.read` | One run with its trail (`schedule_run_events`) |
| `GET /api/v1/executions/{id}/events?format=json` | `execution.read` | The durable execution record (§5.4) |

Schedule mutations carry the revision: `GET` answers it as the `ETag`, `PUT` and `DELETE`
require it in `If-Match` and a stale one is `409 schedule.revision_conflict`. Create and Run now
accept an optional `Idempotency-Key` held **durably in Postgres** (L1, A15: the Redis
`IdempotencyStore` is lost on a Redis restart, deployment.md §4.2.1): a unique
`(workspace, creator, key)` on `schedules` and `(schedule, requester, key)` on `schedule_runs`,
each with a hash of the request; a replay with the same request answers the original, a replay
with a different one is refused `idempotency.key_reused_for_different_request`. Scheduled and
manual run origin is stored explicitly (`schedule_runs.origin`), independent of the execution
trigger enum. One-time background admission under the pipeline REST resource is slice 6 (B13).

**Applications are first-class REST clients** — in slice 5. Organizations may expose scheduling
to their customers through their own applications; creation is not restricted to interactive
browser sessions. All schedules are created directly, not imported through promotion. Slice 1's
routes are **session-only** (§4): keys v2 confines an `mcp` key to `/mcp` and an `endpoint` key
to the published tree, and no existing kind is a least-privilege management credential (A11).
Slice 5 specifies the application credential (B7) and its ScopeMatrix rows; the §9.6
reconciliation's option — a pre-created key role holding the `schedule.*` permissions, offered
to API keys — is its starting point. The existing `endpoint` key only grants its published
endpoint bindings and does not silently gain management access.

Recommend server-to-server integration: the organization's application authenticates its
customer, verifies customer ownership and uses its management credential from the backend.
Never expose a shared organization credential in a client browser. Datapipelines enforces the
workspace on every call; a caller-supplied customer id or folder prefix is not proof of tenant
authority. **Isolation is per workspace (R9)**: external-customer isolation is not a v1
acceptance line; a delegation design, if ever, is a later record. Direct end-customer
credentials would require an explicit delegated-identity design.

UI scope (slice 2): a dedicated **Schedules** page listing all schedules the caller may see in
the active workspace, folder explorer/search, registered executor job form, presets/custom
cron, timezone, next five occurrences including offset, notification email list (slice 4),
pause/resume/unblock, blocked reason, run history with the merged Messages pane (§5.4) and
execution links. Show due time separately from actual start. Unknown outcomes require clear
recovery actions. Use the existing visual system, empty/loading/error states and light/dark
validation. The pipeline job form supplies `current`, pipeline-owned parameter values (slice
3 adds bindings) and executor preview details. These are UI/adapter capabilities, not knowledge
compiled into scheduler core. Do not show schedule draft/release/promotion controls.

Use exactly the established pipeline/template hierarchical-name validator
(`PipelineNameGrammar`, the published object — `scheduler → pipeline-contract` is allowed for
it, A2) and browsing conventions, e.g. `finance/daily/revenue`, unique among the workspace's
live schedules. Rename/move preserves schedule identity and run history. Folders organize work;
they are not a new namespace or security boundary. Hide editing controls for viewers/promoters,
and enforce the same author/admin restriction server-side; hiding a button is not
authorization.

The parameter-set engine is not yet bound to pipelines; the pipeline adapter uses the declared
pipeline inputs (R7: literal values per schedule, validated as an interactive run's are; the
engine plugs in later as another value source). Scheduler core does not depend on it, and
delivery does not depend on the parameter UI engine.

### 6.1 Lifecycle notification emails

**Slice 4 (R11).** Slice 1 sends no mail and stores no recipient list; the requirement below
stands as the owner's (R11), and the review's B12 defaults — per-event toggles defaulting to
failed, unknown and blocked; scheduler mail off unless explicitly enabled; an empty
recipient-domain allowlist under the development posture; links built from the configured base
URL, never the request host (#207) — are slice 4's to rule on. Slice 1's run trail is where
slice 4's outbox events will attach (§5.4).

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
the same run. Do not fabricate a start for a run that failed before launch. Schedule pause
and ordinary skipped ticks are control/skip events, not evidence a job stopped. Send a
separate schedule-level “Schedule blocked” notice on an actual transition to blocked (an
unknown run, a pre-start refusal that blocks), so future work cannot silently cease; do not resend on
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
  a newly added address. Deployment security restrictions still apply at actual delivery.
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
storage and (slice 4) the notification outbox/dispatch. It does **not** own pipeline parameter
binding, keyword evaluation or version resolution. db-scheduler types stay behind its boundary:
the module's own `@AutoConfiguration` declares its three tasks, the serializer customizer, its
repositories and services; no other module imports a `com.github.kagkarlsson` type.

**Dependencies (A2, A4).** module-structure §4.2 gains the row `scheduler | typesystem,
pipeline-contract` and `web`'s row gains `scheduler` (the same table is
`allowedInternalDependencies` in the root build; an unlisted edge fails the build).
`pipeline-contract` is allowed for exactly one thing — the published `PipelineNameGrammar` —
and a scheduler-module test fails on any other `co.datapipelines.pipeline` import, so the
"no pipeline semantics in the scheduler" boundary is enforced, not hoped. The scheduler takes
no `auth`, `dag`, `application` or `calculators` edge: the system principal, the capacity lease
and the executor live on the adapter's side of the port.

Keep integration seams in their established homes:

- `web`: thin REST controllers (and, slice 2, the Schedules UI); every scheduler operation calls
  REST. The pipeline executor adapter, the capacity gate over `ExecutionSlots` and the scheduler
  wiring live in `web` — its `config` package is the composition root (**A3**: `app` depends only
  on `web` and holds `main()`, configuration and the migrations, no wiring).
- `auth`: the system principal, the `PermissionResolver` system arm, the `schedule.*`
  permissions and their role columns; no scheduler-specific authorization fork. auth does not
  depend on scheduler.
- `dag`: `ExecutionSlots`' acquire-before-claim lease (R4, A6) and the `SCHEDULE` trigger.
- `calculators`: reusable pure functions used by the pipeline execution input resolver (slice
  3); scheduler has no dependency on this module.
- Pipeline execution/application layer: owns `current`, keyword/binding resolution, prepared
  execution snapshots and existing execution authorization/lifecycle APIs. The adapter invokes
  this layer; no second pipeline runner is created.
- `app`: Flyway migrations (V38). A notification adapter (slice 4) reuses the shared mail
  transport; scheduler-specific templates and event policy belong with the scheduler, not in
  the account-notice dispatcher.

Pipeline execution does not depend on scheduler to understand keywords: time context and
input-resolution contracts belong on the execution/shared side and the adapter maps the
scheduler's generic context to them. A **fake non-pipeline executor** in the scheduler module's
own suite proves the scheduler works without pipeline classes. No second production job type is
required.

## 7. Persistence and implementation corrections

Migration **V38** (A17: numbers are allocated at merge time; V36 = 7e, V37 = keys v2 were the
last on the base, and no open lane claims V38). V15 is the lake registry. Historical prompt 092
and its old paths/counts are superseded by this revision and the current repository working
agreements. DDL authority is metadata-db §4; this is the logical shape:

- `schedules`: workspace, hierarchical name, concurrency `revision`, registered `executor_id`,
  `payload_schema_version`, opaque JSON `payload`, literal `parameters` JSON (R7 — executor
  inputs, opaque to the scheduler, validated by the executor), the executor's `target_ref`
  (B15, indexed), `cron` + `timezone`, `missed_run_policy` (`skip` | `latest`), `enabled`,
  `blocked_reason` / `blocked_at` / `blocked_run_id`, `next_due_at`, `created_by`, `updated_by`,
  the create idempotency key + request hash (L1), timestamps, and **soft delete** (`deleted_at`,
  `deleted_by`, D-9.6/B17): the name is unique among LIVE schedules of a workspace (a partial
  unique index), a deleted schedule keeps its runs and their trail, and its name can be reused.
  No notification column in slice 1 (slice 4). No pipeline/version-specific columns or lifecycle
  status; no artifact-version table, release metadata or promotion schema. Payloads and
  parameters follow the existing sensitive-input storage/access/retention rules.
- `schedule_runs`: id, `schedule_id` (NULL only for slice 6's ad-hoc runs), workspace, `origin`
  (`cron` | `catch_up` | `manual`), `scheduled_at` (the UTC occurrence; NULL for a manual run),
  the frozen context (`reference_at`, `reference_timezone`), `admit_by` (the lateness window's
  end), `schedule_revision`, the frozen `executor_id` / `payload_schema_version` / `payload` /
  `parameters`, the executor-owned `prepared` snapshot (R5: `{pipeline_id, version,
  body_sha256}` for the pipeline executor — stored, never read, by the scheduler), actor
  attribution (`actor_user_id` = the system identity; `requested_by` = a manual run's person),
  the opaque `execution_id` reference (minted at the claim), `state` + `reason` (§7.1), `worker`,
  capacity `attempts`, the Run now idempotency key + request hash (L1), `trail_seq`, and
  lifecycle timestamps (`created_at`, `claimed_at`, `started_at`, `finished_at`, `updated_at`).
  Unique `(schedule_id, scheduled_at)` for cron/catch-up occurrences; unique
  `(schedule_id, requested_by, idempotency_key)` for Run now; a partial unique index allowing at
  most one active (`queued` / `starting` / `running`) run per schedule.
- `schedule_run_events` (§5.4, R10): append-only (an UPDATE trigger refuses any change), keyed
  `(run_id, seq)` with `seq` monotonic per run (drawn from `schedule_runs.trail_seq` under the
  run row's lock), `kind`, `reason`, `at`, the `worker`, small `details` JSON (execution
  reference, retry number, summary counts); retained with the run's history. The pipeline's events
  stay in `execution_events` (metadata-db §4.7) and are not duplicated; `schedule_runs.execution_id`
  is the join the merged read uses. Slice 4's notification kinds widen the kind CHECK then.
- `scheduled_tasks`: library-owned, created by V38 from db-scheduler 16.12.0's exact PostgreSQL
  DDL (`db-scheduler/src/test/resources/postgresql_tables.sql` at tag `v16.12.0`, copied verbatim
  with its three indexes).
- `pipeline_executions.triggered_via` gains `SCHEDULE` (the CHECK is widened in V38). The old
  `SCHEDULED` future placeholder in enums.md §18 is replaced by it, and versioning.md §3.4's
  "records a refused run" line is corrected to "records a `not_started` run and blocks" (A16).
  No key-kind attribution is added (R2).
- Notification events/deliveries: slice 4 (durable event identity, run or schedule-transition
  identity, recipient snapshot, sequence, payload metadata, attempt state, retry timing and
  Message-ID; retention with execution history; never delete queued deliveries silently).

### 7.1 Run states — the normative mapping (R6)

`state` + `reason`; the run row is the projection of the latest trail row.

| State | Reason(s) | Meaning / source |
|---|---|---|
| `queued` | — | Recorded and enqueued; no start claim |
| `starting` | — | The start claim is committed (execution reference recorded); the launch is in progress |
| `running` | — | The execution's `RUNNING` row exists (the adapter's `start` confirmed it) |
| `succeeded` | — | Execution `SUCCESS` |
| `failed` | `execution_failed` | Execution `FAILED` (a node failure or the execution timeout) |
| `cancelled` | `cancelled` | Execution `ABORTED`, abort reason `cancelled` |
| `aborted` | `shutdown` (`client_disconnect` for completeness) | Execution `ABORTED` by the shutdown drain |
| `unknown` | `instance_lost`, `start_unconfirmed`, `start_failed` | `ABORTED` / `pipeline.execution.instance_lost` (A5); a claim whose execution never appeared (§2.1); a `start` that threw. **Blocks the schedule.** A later real terminal is an update about the same run |
| `not_started` | `capacity`, `pointer_null`, `target_not_found`, `parameters_invalid`, `version_changed`, `workspace_inactive`, `authority_refused`, `record_unwritable` | Definitively never started (§2 item 3). The executor's refusals may ask to block (all but `capacity` and `record_unwritable` do) |
| `skipped` | `missed`, `overlap`, `schedule_paused`, `schedule_blocked`, `schedule_deleted` | By policy; never attempted. `missed` rows may summarize a range |

The trail's kinds: `recorded` (occurrence or manual request, with its origin), `capacity_retry`,
`claimed`, `execution_started`, `not_started`, `skipped`, `finished` (a terminal reconciled
from the execution), `unknown`, `updated_after_unknown`, `unblocked`.

### 7.2 Services, configuration and shutdown

One service coordinates schedule/task persistence using proven transaction participation (§2.1,
spike 1). REST calls the schedule service; the dispatcher, occurrence worker and reconciler are
JOBS no transport names (security-assurance B5 — `ArchitectureGuardTest`'s job list names
them). Trusted worker dispatch goes through the same execution authorization and lifecycle
path, without requiring a browser or streaming consumer; it does not bypass permissions merely
because a launch is internal (§4).

**Budget of a scheduled run (A12, L3).** Scheduled runs keep the instance's execution timeout
(`datapipelines.executor.execution-timeout-seconds`, 600) and ask for the maximum result TTL
(`datapipelines.result.ttl-max-seconds`, 3600). A scheduled pipeline's durable output
is its targets (a datasource or lake write); a caller result is inspection material that expires
within the hour. A background budget is slice 6's.

**Configuration** (configuration.md §3, `datapipelines.scheduler.*`; the draft's "two worker
threads, 10-second polling, 30-second heartbeats, six missed heartbeats" stand, the 60 s
shutdown wait does not — A7):

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | This instance dispatches (false = API mode, §1) |
| `threads` | `2` | db-scheduler worker threads (A19) |
| `polling-interval-seconds` | `10` | db-scheduler's poll for due tasks |
| `heartbeat-interval-seconds` | `30` | db-scheduler's execution heartbeat; a task is dead after six missed |
| `tick-interval-seconds` | `10` | The dispatcher's and the reconciler's fixed delay |
| `max-concurrent-runs` | `4` | Scheduled executions in flight per instance (R4); the system identity's slot budget |
| `lateness-seconds` | `600` | The admission window (§3) |
| `catch-up-max-age-seconds` | `86400` | The `latest` policy's reach (§3) |
| `shutdown-wait-seconds` | `5` | How long shutdown waits for in-flight launches, and db-scheduler's `shutdown-max-wait` |
| `min-interval-seconds` | `300` | B18 guard (L4) |
| `max-schedules-per-workspace` | `100` | B18 guard (L4) |

**Shutdown order (A7, R1 spike 2).** `ExecutionDrainLifecycle` cancels every local execution on
SIGTERM (`ABORTED`, reason `shutdown`). The starter's `Scheduler` bean stops only through its
`destroyMethod`, which runs AFTER every lifecycle bean — so left alone it would keep picking work
while the drain cancels it. The scheduler therefore has its own `SmartLifecycle` at a HIGHER
phase than the drain: it stops admitting (new claims are refused and their tasks rescheduled, so
a queued run is never lost), pauses db-scheduler's polling, and waits up to
`shutdown-wait-seconds` for in-flight launches to reach "started" — only then does the drain
cancel, and a run launched in that window ends `aborted` / `shutdown`, never lost. The drain
keeps its place above the web server's graceful shutdown (`DEFAULT_PHASE - 1024`). Every bound
is under Spring's 30 s per-phase timeout and the drain's 20 s flush. A deploy therefore aborts
in-flight scheduled runs: the outcome is conclusive (`aborted` / `shutdown`), nothing blocks,
and under `skip` that occurrence simply does not run again (B11 — accepted in v1 with a clear
history row; `stop_grace_period` in the compose file is a deployment follow-up tracked on #9,
because the drain's 20 s can overrun Docker's 10 s default and a SIGKILL there leaves
`instance_lost` results, which become `unknown` and block).

**Health and metrics.** db-scheduler's own health indicator is disabled
(`management.health.db-scheduler.enabled: false`): it reports DOWN until the scheduler starts,
which an API-mode instance never does, and a dispatcher stall must not restart a pod. The
scheduler's state is visible through metrics (observability.md): occurrences recorded by
outcome, runs finished by state, capacity retries (R4), and in-flight scheduled runs.

## 8. Acceptance

Required evidence before describing the scheduler as complete. Each line names the slice that
owes it; **[1]** is slice 1's core subset (the brief's A.8), proven by tests in the lane that
delivers it.

- **[1]** Two instances deliver duplicate callbacks for the same due instant: one occurrence and
  at most one admitted launch — with a controlled clock (`ManualScheduler` / `SettableClock`,
  which ship in db-scheduler's main jar), never an uncontrolled race or an assertion of fair
  picking.
- **[1]** Public cron is five-field, minute precision: seconds syntax is refused publicly.
- **[1]** Crash before claim, after claim/before launch, during work, after remote commit and
  before success bookkeeping: conservative recovery, no automatic repeated effects.
- **[1]** Lose the execution heartbeat while an old worker remains alive: `unknown` blocks
  subsequent admission, and a later real terminal updates the same run.
- **[1]** The two spike proofs of R1: `scheduleIfNotExists` participates in a
  `metadataTransactionManager` transaction (a rolled-back transaction leaves no task, a
  committed control leaves one); a run mid-launch on SIGTERM ends `aborted`, never lost.
- **[1]** DST gap/overlap and month/year boundaries against the occurrence function, with A9's
  measured case; delayed start across midnight; skip versus latest-only catch-up; overlap across
  workers; capacity pressure (retry within the window, `not_started` beyond it).
- **[1]** A fake non-pipeline executor proves scheduler timing and recovery without pipeline
  classes or pipeline-shaped columns; a run refused before launch has a trail and no execution.
- **[1]** A scheduled run of a pipeline leaves the same `execution_events` rows as an interactive
  run of the same pipeline (count and kinds), with no SSE client attached, and a complete
  `schedule_run_events` trail; the durable execution rows are readable through REST after the
  Redis hour and until the retention cutoff.
- **[1]** The pipeline adapter accepts `version: "current"` and rejects `"latest"`; the scheduler
  round-trips the payload without interpreting either value; a follow-the-pointer schedule picks
  a new version without an edit (drafts included, R5), records the fired version and body hash,
  blocks on a NULL pointer; literal parameters are validated as an interactive run's are.
- **[1]** The system identity holds exactly its fixed set (a test resolver granting one more turns
  the unit test red), cannot authenticate over REST or `/mcp`, has no key row; its runs are
  visible to the workspace's members by role (R3), a creator demoted to viewer keeps their
  schedules running (R8), and the §7.6 rows are walked by `RoleWalkE2eTest`.
- **[1]** Rename/move a schedule without losing its history; direct viewer/promoter REST
  mutations fail even if a caller constructs requests outside the UI; create and Run now retries
  under one idempotency key act once, and a changed request under the same key is refused.
- **[1]** Pausing and blocking remain independent operational controls; resume cannot bypass a
  block; unblock revalidates. No schedule promotion endpoint/package or artifact-version state
  exists.
- **[1]** An API-mode instance does not dispatch tasks; a worker instance does. The migration
  applies up and down on a copy of the demo database.
- **[2]** Browser flow: create → preview → run → history (merged Messages pane, source labels) →
  blocked → unblock. All operations use REST and permissions are enforced there; CSRF through the
  real UI path.
- **[3]** Fixed STRING `TODAY` stays literal; Today/Yesterday presets produce DATE values from the
  frozen schedule-zone reference, validated against the selected pipeline version; the frozen
  context is inherited by a child pipeline; direct and scheduled execution share one resolver.
- **[4]** Real SMTP test sink: start, success, cancellation, failure, pre-start refusal, unknown
  and reconciliation; multiple recipients; the blocked notice; disabled mail; transient and
  ambiguous send failures. Duplicate recovery callbacks enqueue one event; mail trouble never
  reruns a job. Recipient/content isolation and outbox crash recovery.
- **[5]** The application credential: an application creates immediately usable schedules without
  a browser session and without widening endpoint-key authority; unauthorized workspace access
  is refused.
- **[6]** Background runs (B13).
- Source/target evidence proves actual pipeline effects, not only scheduler history rows (every
  slice that runs a pipeline).

The draft's key-shaped acceptance lines (revocation races, key ids and plaintext absence,
key-kind rejection, key reuse and association counts) are withdrawn with §4's model (R2).

### 8.1 Review before implementation lock — done

The Fable review the draft waited for happened as §9 (2026-09-23), the owner answered its
blockers as §10 (2026-09-25), and lane 1 asked the owner the four questions the rulings left open
(§11). This record was ratified on those answers. Current product auth, calculator and execution
documents remain authoritative for shipped behavior; new keys, roles/permissions, routes and
error codes enter their catalogs only in the same commits as their implementations and drift
guards.

## 9. Pre-implementation review findings (2026-09-23)

**Kept as history (ratification, 2026-09-25).** §9.1's corrections are folded into the body as
follows; §9.2's decisions are answered by §10 and §11; §9.6's defaults are adopted where §10 and
§11 are silent, each cited **(D-9.6)** in the body. Where this section and the body disagree, the
body wins.

| Item | Where it is folded, or why it is moot |
|---|---|
| A1 | **Moot (R2).** The Keys-page minting it corrected belonged to §4's withdrawn scheduler-key model; no schedule verb mints a key. |
| A2 | §6, §6.2 — `scheduler → pipeline-contract` is allowed for the published `PipelineNameGrammar` only, and a module test refuses any other pipeline import. |
| A3 | §5.3, §6.2 — the adapter lives in `web` beside `RecordingExecutionRunner`; `web/config` is the composition root. |
| A4 | §6.2 — the module-structure §4.2 row and `allowedInternalDependencies`. |
| A5 | §2.1, §7.1 — `ABORTED` / `instance_lost` maps to `unknown` (blocks); a later real terminal is an update about the same run. |
| A6 | §2.1 — the slot refusal precedes `execution_started`, so it is a definitive "never started"; R4 adds the acquire-before-claim lease so capacity is taken before the claim. |
| A7 | §7.2 — the scheduler's own lifecycle stops admitting before the drain; the 60 s wait is removed; B11 accepted for v1. |
| A8 | §2.2 — the JSON serializer, with a test that fails on the Java one. |
| A9 | §3.2 — measured, and replaced by our rule (R1). |
| A10 | **Moot (R2).** `key_hash`, the `dpk_` prefix and `AuthCache`'s key TTL concerned a scheduler key that does not exist; admission reads the schedule row under its lock and asks the system identity's fixed set, and no cached key lookup is involved. |
| A11 | **Answered by §10 (R2, R9) and §11.** No existing credential fits an application: slice 1's routes are session-only and the application credential is slice 5 (B7). |
| A12 | §7.2 — the existing timeout and the maximum result TTL (L3). |
| A13 | §5.1 — slice 1 records the frozen context; slice 3 carries it to children. |
| A14 | §2.1 — the scheduled path fails closed when the `RUNNING` row cannot be written. |
| A15 | §3, §6 — idempotency is durable in Postgres (L1). |
| A16 | §7 — `SCHEDULE` replaces the `SCHEDULED` placeholder; versioning §3.4 is corrected. |
| A17 | **Answered.** The number is allocated at merge time; on lane 1's base it is V38 (§7). |
| A18 | §3.1 — answered by R5: the prepared snapshot records the fired version and the body hash. |
| A19 | §1, §2.2, §7.2 — the `threads` key; API mode keeps the client with a no-op starter; the loops by role. |

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

## 10. Owner rulings of 2026-09-25 (answers to §9.2 / §9.6; NORMATIVE — ratified 2026-09-25)

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

## 11. Lane 1's questions and the adopted defaults (2026-09-25)

Four decisions §10 left open changed the schema or the API, so lane 1 asked the owner before
ratifying. The answers are normative.

| # | Question | Ruling |
|---|---|---|
| L1 | Where the Create and Run now idempotency token lives (the draft's §6 and A15 said Postgres; the lane brief said the Redis `IdempotencyStore`) | **Durable in Postgres**: an optional `Idempotency-Key`, a unique column per `(workspace, creator)` on `schedules` and per `(schedule, requester)` on `schedule_runs`, with the request's hash; the Redis store is not used. |
| L2 | Which permission Run now declares (the brief listed four new permissions) | **A fifth, `schedule.run`** (author, workspace admin, super admin), by the catalog's granularity rule; unblock rides `schedule.pause`. |
| L3 | The result TTL and timeout of a scheduled run (A12) | **The existing limits, documented**: the instance's execution timeout and the maximum result TTL; no scheduler-specific keys and no executor change in slice 1. |
| L4 | The review's B18 guards | **In slice 1, configurable**: `min-interval-seconds` (300) and `max-schedules-per-workspace` (100), each with its error code. |

**Adopted without a separate ruling (D-9.6)** — §9.6 listed them as "defaults to write in unless
the owner objects", §10 overrode none of them, and the lane brief assumed them: the 10-minute
lateness window; occurrences due while paused or blocked are not missed and resume/unblock
recompute from now; the catch-up value `latest` with a 24 h reach; Run now during an active run
answers 409; the DST rule (a gap runs just after it, a fold runs once); soft delete with a
partial unique name index; the indexed target reference (B15), which also carries the promoter
lens; deploys abort in-flight scheduled runs with a clear history row (B11). Viewers see a
schedule's parameters (B14), because R3 already shows every member the scheduled runs' resolved
parameters.

**Superseded §9.6 defaults**: RELEASED-only (R5 follows the pointer, drafts included); the name
grammar moving to `typesystem` (the lane brief allows `scheduler → pipeline-contract` for the
published grammar, A2); per-event notification toggles and scheduler mail defaults (slice 4's).
