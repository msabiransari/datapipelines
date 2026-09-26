# Scheduler Specification

**Status:** v1.0 (slice 1 of #9 — the core; the Schedules page, notifications, bindings and the application credential are later slices)
**Owner:** datapipelines.co core
**Depends on:** [REST API §20](rest-api.md#20-schedules), [Auth](auth.md), [DAG Executor](dag-executor.md), [Metadata DB §4.22–§4.25](metadata-db.md#422-schedules), [Configuration §3.29](configuration.md#329-scheduler-9)
**Design record:** [scheduler design revision](superpowers/specs/2026-09-22-scheduler-design-revision.md) (ratified 2026-09-25) — the why; this page is the what
**Last updated:** 2026-09-25

---

## 1. Purpose

A **schedule** runs a job at the occurrences of a cron pattern in a timezone. In v1 the one job is a **pipeline run**: the schedule names a pipeline, follows its current version and passes literal parameter values. This spec defines:

- when a schedule fires, including across daylight-saving changes (§3);
- what happens to occurrences missed while no instance was dispatching, while a run was still going, or while the schedule was paused (§4);
- what a **run** is, the states it passes through and why it ends where it ends (§5);
- who a schedule runs as and what that identity may do (§6);
- how many scheduled runs execute at once (§7);
- how instances divide the work and how a deploy ends in-flight runs (§8).

A schedule is **not** a versioned artifact. It has no draft or release, is never promoted and is edited in place; its revision number exists only so two people editing it cannot overwrite each other. Nothing about a schedule is exposed over MCP.

---

## 2. Concepts

| Term | Meaning |
|---|---|
| **Schedule** | A named, workspace-owned row: a cron pattern, an IANA timezone, a missed-run policy, and an executor payload (§2.1). Names are folder paths, like pipelines — `finance/daily/revenue` |
| **Occurrence** | One instant the pattern matches, as a UTC instant. Occurrences are computed by ONE function (§3), used by the dispatcher and by every preview |
| **Run** | One attempt to act on an occurrence (or on a Run now). Every run is a row that exists before anything is launched, and it ends in exactly one final state (§5) |
| **Trail** | A run's append-only history: one row per thing that happened to it — recorded, claimed, started, finished, retried for capacity, blocked, updated |
| **Execution** | The pipeline execution a run launched. The run carries its id; the execution's own events stay in the execution record ([REST API §10.3A](rest-api.md#103a-durable-event-record-json)) and are never copied into the trail |
| **Executor** | The component that knows what a payload means. The scheduler stores the payload, validates it through the executor, and passes it back at run time; it never reads a pipeline itself |
| **Blocked** | A schedule that fires nothing until a person unblocks it, because a run's outcome is unknown or the executor refused in a way a person must fix (§5.2) |

### 2.1 The pipeline payload

```json
{ "pipeline": "finance/daily/revenue", "version": "current" }
```

Nothing else is accepted in v1. `current` follows the pipeline's **current-version pointer** at each run ([Versioning §3.4](versioning.md#34-current_version-is-sticky-and-event-driven-d60)) — wherever a person points it, including at a draft under the development posture. `latest` is refused by name (it is not a selector), and so is a version number. Each run freezes what it actually ran — `{pipeline_id, version, body_sha256}` — before it launches, so a later release, switch or draft edit never changes a run that was already admitted. If the draft is edited between that freeze and the launch, the run is refused `not_started` / `version_changed` rather than run on a body nobody reviewed.

A schedule's **parameters** are literal values for the pipeline's declared parameters, checked by the same binder an interactive run uses — at save, again at unblock, and again when each run is prepared. A value that no longer binds (the pipeline's parameters changed) refuses the run and blocks the schedule; it is never silently dropped.

---

## 3. Occurrences and the DST rule

Patterns are **five-field Unix cron** — `minute hour day-of-month month day-of-week`. A six-field (seconds) pattern is refused; so is the library's disabled pattern `-`. The timezone is an **IANA region id** (`America/New_York`); a fixed offset (`+02:00`) is refused, because it cannot follow a region's daylight-saving rules.

The pattern is matched on the **local wall clock** of the timezone, and each matching local time becomes a UTC instant by these rules:

| Local time | Becomes |
|---|---|
| Ordinary | The one instant it names |
| Inside a **spring-forward gap** (it never happens on the clock) | The first instant after the gap — the transition itself. A daily job never silently skips a day |
| Inside a **fall-back overlap** (it happens twice) | Its **first** pass only, for every pattern — an hourly job does not run twice in the repeated hour |
| Several local times mapping to one instant (e.g. `*/15 2 * * *` across a whole gap) | One occurrence |

Measured in America/New_York (the unit suite's cases; the library's own cron evaluation, which the scheduler does not use for timing, is shown for contrast):

| Pattern | Day | This scheduler | The library alone |
|---|---|---|---|
| `30 2 * * *` | 2026-03-08 (spring forward) | once, 03:00 EDT (07:00Z) | skips the day |
| `30 1 * * *` | 2026-11-01 (fall back) | once, 01:30 EDT | once |
| `*/30 1 * * *` | 2026-11-01 (fall back) | twice — 01:00 and 01:30 EDT | four times |

`GET /schedules/preview` ([REST API §20.3](rest-api.md#203-preview-a-pattern)) shows the next occurrences of any pattern with their local time and offset, from the same function, before anything is saved.

**Save-time guards.** A pattern whose consecutive occurrences come closer than `datapipelines.scheduler.min-interval-seconds` (default 300 s) anywhere in the 14 days after the save is refused (`schedule.validation.interval_too_short`). The 14-day horizon covers every weekday twice; a pattern whose only tight pair straddles a month end beyond it is not caught, and the per-workspace cap (`max-schedules-per-workspace`, default 100) still bounds it.

---

## 4. Missed occurrences, overlap and Run now

A dispatcher tick (§8) records what fell due since the schedule's `next_due_at`. What it records depends on how late it is and on the schedule's **missed-run policy**:

| Situation | Recorded |
|---|---|
| Due, and the tick is within `lateness-seconds` (default 600) of it | A `queued` run (`origin = cron`) |
| Several were due — an outage, or no dispatching instance — under `skip` (the default) | ONE `skipped` / `missed` summary row for everything before the latest, however many; the latest still runs if it is within its lateness window, else it joins the summary |
| The same, under `latest` | The latest missed occurrence runs once (`origin = catch_up`) if it is at most `catch-up-max-age-seconds` (default 24 h) old; the rest are summarized as missed |
| Due while a run of the schedule is still queued, starting or running | `skipped` / `overlap` — at most one active run per schedule, across every instance |
| Due while the schedule is paused or blocked | Nothing. Paused and blocked time is not "missed": resume and unblock recompute `next_due_at` from now |
| Due while the workspace is deactivated | Nothing while deactivated; on reactivation the backlog is handled as an outage, by the policy |

Editing a schedule's pattern or timezone also recomputes from now — the old pattern's occurrences are not missed.

**Run now** ([REST API §20.12](rest-api.md#2012-run-now)) records a `manual` run with the request time as its reference time. It runs like any other run — as the system identity (§6), through the same capacity and admission — and is allowed on a paused schedule (a person asked for it), refused on a blocked one (`409 schedule.blocked`) and refused while another run is active (`409 schedule.run.overlap`). It is not a retry: re-running an occurrence whose outcome was unknown is deliberately not offered, because a new execution may repeat effects.

---

## 5. Runs: states and reasons

A run is admitted by a worker: capacity first (§7), then the executor prepares it (resolves `current`, checks the parameters, freezes the snapshot), then the start is **claimed** — a conditional write that only one worker can win — and only then launched. That order is what makes the states below honest: nothing is ever launched without a committed claim, and a claim is never made without capacity.

| State | Reasons | Meaning |
|---|---|---|
| `queued` | — | Recorded, waiting for admission |
| `starting` | — | Claimed; the launch is in progress |
| `running` | — | The execution's record exists |
| `succeeded` | — | The execution succeeded |
| `failed` | `execution_failed` | The execution failed (a node failure or the execution timeout) |
| `cancelled` | `cancelled` | Someone cancelled the execution |
| `aborted` | `shutdown` | An instance shut down under it (§8.2) |
| `unknown` | `instance_lost`, `start_unconfirmed`, `start_failed`, `execution_missing` | Nobody can say whether its work happened: its instance died mid-run; a claim whose execution never appeared; a launch that threw; a running execution whose record vanished. **Blocks the schedule** |
| `not_started` | `capacity`, `executor_unavailable`, `pointer_null`, `target_not_found`, `payload_invalid`, `parameters_invalid`, `version_changed`, `workspace_inactive`, `authority_refused`, `start_refused`, `record_unwritable` | Definitively never started — nothing ran |
| `skipped` | `missed`, `overlap`, `schedule_paused`, `schedule_blocked`, `schedule_deleted` | By policy; never attempted |

### 5.1 Not started means nothing ran

A scheduled launch **fails closed**: the execution may begin only after its `RUNNING` record is written, under an id minted at the claim. So a launch that could not write its record is `not_started` / `record_unwritable` — no node ran — rather than a run in an unknown state. Every `not_started` run still has its trail, which says why.

### 5.2 What blocks

`unknown` always blocks: a scheduler that fired again after an unknown outcome could run a non-idempotent load twice. Most executor refusals block too, because a person has to fix something — the pipeline has no current version (`pointer_null`), it is gone (`target_not_found`), the saved payload or parameters no longer validate, the system identity was refused (`authority_refused`), the launch path refused before recording (`start_refused`), or no executor is registered under the run's id (`executor_unavailable`).

Four do **not** block, because nothing about the schedule is wrong: `capacity` (the instance was busy), `record_unwritable` (the metadata database refused one write), `workspace_inactive` (a reactivated workspace is handled as an outage) and `version_changed` (a draft edited between preparation and launch; the next occurrence prepares afresh).

A block names its cause (`blocked.reason`) and the run that caused it (`blocked.run_id`); the first cause is kept until a person unblocks. **Unblock** re-validates the saved payload and parameters, then recomputes `next_due_at` from now; resume never clears a block.

### 5.3 A late truth about an unknown run

An `unknown` run is watched for 24 hours. If its execution later reports a real terminal — a worker that was stalled, not dead, finished after all — the SAME run is updated to that state and its trail gains `updated_after_unknown`. The schedule stays blocked: an answer arriving late is information for the person, not permission to resume.

---

## 6. Who a schedule runs as

A schedule fires as the **system identity** — the one `System` account every deployment provisions ([Auth §4.5](auth.md#45-the-system-service-account-r7)) — never as the person who created it. So a creator who is demoted to viewer, removed from the workspace or deactivated does not stop their schedules, and a schedule never gains or loses power when its creator's role changes.

The system identity holds exactly three permissions, in every workspace: `pipeline.read`, `pipeline.execute` and `execution.read`. It holds no role, no instance permission and no credential: there is no API key, no session and no password for it, and a request that tried to present it is refused. Every launch still ASKS for `pipeline.execute` — a scheduled run is not exempt from permissions because it is internal.

What people may do (auth.md §7.6): every member **reads** schedules (a promoter through the promoter lens); authors, workspace admins and super admins **create, edit, pause, resume, unblock, delete and Run now**. Executions a schedule fired are visible to every member with `execution.read`, not only to their executor — visibility, not ownership: cancelling one still needs `execution.cancel_all`.

Isolation is per workspace: a schedule, its runs and the pipeline it names all belong to one workspace, and an id from another answers `404`.

---

## 7. Capacity and budget

At most `datapipelines.scheduler.max-concurrent-runs` (default 4) scheduled executions run at once **per instance**. The limit is taken before the start is claimed; a run that finds no slot is retried every 30 seconds within its lateness window, each retry on its trail and counted (`datapipelines.scheduler.capacity.retries`), and ends `not_started` / `capacity` when the window closes. The instance-wide execution ceiling (`datapipelines.executor.max-concurrent-executions-per-instance`) still applies on top, shared with people's runs.

A scheduled execution has the instance's ordinary execution timeout (`datapipelines.executor.execution-timeout-seconds`) and asks for the maximum result TTL (`datapipelines.result.ttl-max-seconds`). A scheduled pipeline's durable output is what it writes to its targets; its caller result, if any, is inspection material that expires within the hour.

---

## 8. Instances, deploys and failure

### 8.1 One dispatcher, any number of instances

Every instance serves the schedule REST routes. An instance with `datapipelines.scheduler.enabled: true` also dispatches; `false` is **API mode**. Among dispatching instances there is ONE dispatcher: the tick is a single cluster-wide task, and each tick locks the due schedules it handles, so two instances never record the same occurrence twice and never launch one occurrence twice (an occurrence is a unique row, and a start claim is a conditional write). Run tasks are spread over the dispatching instances' worker threads (`threads`, default 2); a worker holds a thread only while it admits and launches, never for the pipeline's runtime.

If an instance dies, its in-progress tasks are revived elsewhere after six missed heartbeats (`heartbeat-interval-seconds`, default 30 s). A revived task never launches again: it finds the claim, and either the execution exists (the run is `running`) or it does not (the run is `unknown`, and the schedule blocks).

### 8.2 Deploys and shutdown

On shutdown the instance first stops admitting — a run not yet claimed stays `queued` and is picked up by another instance or after restart — then waits up to `shutdown-wait-seconds` (default 5) for launches in progress to reach "started", and only then do the local executions drain. A scheduled execution cut off by the drain ends `aborted` / `shutdown`: a conclusive outcome that does not block. Under `skip` that occurrence does not run again.

The drain can need 20 seconds. A container runtime whose stop grace is shorter (Docker's default is 10 s) kills the process first; the executions it cut off then end as lost instances, which the scheduler records as `unknown`, blocking. Give the application container a stop grace period of at least 30 seconds; the shipped `deploy/compose.yml` does not set one yet (a follow-up tracked on #9).

### 8.3 Watching it

Metrics ([Observability §4.1](observability.md#41-metric-naming)): `datapipelines.scheduler.occurrences{outcome}`, `datapipelines.scheduler.runs.finished{state}`, `datapipelines.scheduler.capacity.retries` and `datapipelines.scheduler.runs.in_flight`. Log events: [Observability §3.4E](observability.md#34e-the-scheduler-events-9) — `scheduler.schedule_blocked` is the one that needs a person. There is no scheduler health component: an API-mode instance never starts the scheduler, and a stalled dispatcher is not a reason to restart a pod.

---

## 9. Not in slice 1

- **The Schedules page.** Slice 1 is the REST surface; the page (list, form, history with the merged messages) is slice 2.
- **Notifications** — mail on start, failure, unknown and blocked — slice 4.
- **Parameter bindings** (values computed per run, e.g. "the day before the occurrence") — slice 3. Slice 1's parameters are literal.
- **An application credential.** Slice 1's routes are session-only; organizations that offer scheduling to their own customers call them from a backend with a management credential in slice 5.
- **Background one-time runs** of a pipeline — slice 6.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-25 | v1.0 | scheduler lane 1 (#9) | Initial spec for slice 1: occurrences and the DST rule, missed/overlap/Run now, run states and reasons with what blocks, the system identity, capacity, one dispatcher across instances, shutdown. Written from the ratified design revision and the shipped code. |
