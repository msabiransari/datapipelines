# Design: the scheduler — schedules in folders, fired exactly once across instances, with every run a first-class execution

**Status:** RATIFIED 2026-09-07 (owner rulings R-S1–R-S5); implementation prompt 092. Requirements and the technology
comparison live in the orchestration store (`design-records/2026-09-07-scheduler-requirements.md`).
**Not packaged into the product** (`docs/superpowers/` is excluded from the jar). The product spec lands in
`docs/scheduler.md` at 092.

## 0. Decisions (ratified)
- **R-S1 — engine: db-scheduler 16.12.0** (Apache-2.0; `db-scheduler-spring-boot-starter` 16.12.0). Verified on the jar
  2026-09-07: `CronSchedule(pattern, ZoneId, CronStyle.UNIX|QUARTZ|CRON4J|SPRING|SPRING53)`, cron-utils shaded in,
  `PersistentCronSchedule`, one table, `SELECT … FOR UPDATE SKIP LOCKED` pick, per-execution heartbeat, dead-execution
  policy. The core has no Spring dependency: two `Scheduler`s on one Postgres in one JVM is the cluster test.
  Rejected: Quartz 2.5.2 (11 tables, row-lock clustering, misfire semantics — heavy), JobRunr 8.8.2 (LGPL core + commercial
  Pro — open-core), ShedLock 7.10.0 (locking only, no history).
- **R-S2 — 5-field Unix cron + explicit timezone per schedule**, presets in the UI (prefilled strings), a live "next
  five fire times in that zone" preview computed by the same parser the engine uses.
- **R-S3 — history is the execution model.** A scheduled pipeline run is a `pipeline_executions` row with
  `triggered_via = 'SCHEDULE'` (CHECK widening, the V3/V11 pattern) — node stats, Events, cancellation and the stale
  sweeper apply unchanged. `schedule_runs(schedule_id, fire_time UNIQUE, execution_id?, outcome, reclaimed_from?)` ties fire
  → run and carries non-pipeline kinds later.
- **R-S4 — default catch-up policy `skip`**; `run_now` opt-in per schedule. A missed window never fires twice.
- **R-S5 — `datapipelines.scheduler.role = both | api | worker`**, default `both`. Every instance may create schedules
  and enqueue ad-hoc runs; only `both`/`worker` start the engine and execute. Same image, one variable (075).

## 1. Scope of the first round (092)
Kind `pipeline` only: a released pipeline version, its parameters, run on a cron; ad-hoc/async runs of any pipeline
through the same engine. Kinds `report` and `email` are reserved in the enum and the table, with no behaviour.

## 2. Model
```
schedules (V15)
  id UUID PK
  workspace_id UUID NOT NULL FK         -- schedules are workspace-scoped like pipelines
  name TEXT NOT NULL                     -- folder path, 2–10 segments, same grammar as pipelines (077); UNIQUE(workspace_id, name)
  display_name TEXT NOT NULL
  kind TEXT NOT NULL CHECK (kind IN ('pipeline','report','email'))
  pipeline_id UUID NULL FK, pipeline_version INT NULL   -- kind=pipeline: a RELEASED version, pinned (no "latest")
  parameters_json JSONB NOT NULL DEFAULT '{}'           -- validated against the pinned version's declared parameters at save
  cron TEXT NOT NULL, timezone TEXT NOT NULL             -- Unix 5-field; IANA zone
  catch_up TEXT NOT NULL DEFAULT 'skip' CHECK (catch_up IN ('skip','run_now'))
  enabled BOOLEAN NOT NULL DEFAULT TRUE
  created_by UUID NOT NULL, created_at, updated_at, last_fire_at NULL, next_fire_at NULL (denormalised for the list)
schedule_runs (V15)
  id UUID PK, schedule_id FK, fire_time TIMESTAMPTZ NOT NULL, UNIQUE(schedule_id, fire_time)
  execution_id UUID NULL FK pipeline_executions, outcome TEXT CHECK (queued|running|succeeded|failed|aborted|skipped|reclaimed)
  picked_by TEXT (instance id), started_at, finished_at, error_code NULL, error_message NULL
pipeline_executions: chk_triggered_via widened with 'SCHEDULE' (V15); triggered_by = the schedule's created_by
scheduled_tasks: db-scheduler's own table, created by V15 from the library's documented DDL (pinned to 16.12.0)
```
One db-scheduler **recurring task** per enabled schedule: instance id = `schedule:<uuid>`, data = the schedule id only.
On fire, the task re-reads the row (an edit or pause needs no re-registration; a deleted row = task removed on next
pick). Enable/disable/cron edits call the engine's reschedule/remove through ONE service (`ScheduleService`), the only
writer of both tables in one transaction.
**Ad-hoc** = a one-time task `run:<uuid>` with a `schedule_runs` row whose `schedule_id` is NULL and whose `execution_id`
is set when picked; the API returns the run id immediately.

## 3. Exactly once, and what happens when an instance dies
1. The engine's lock guarantees one picker per fire. 2. `schedule_runs(schedule_id, fire_time)` is UNIQUE and inserted
BEFORE the execution starts — a reclaim that re-fires the same window hits the constraint and records `reclaimed` instead
of running twice. 3. Heartbeat: the engine marks the execution dead after `heartbeat × N`; the dead-execution handler
(a) marks the run `reclaimed` with `reclaimed_from = picked_by`, (b) the orphaned `pipeline_executions` row is aborted by
the existing stale-execution sweeper (016) with `execution_aborted` reason `instance_lost`, (c) `catch_up` decides:
`skip` → next cron; `run_now` → one new run for the SAME fire_time is impossible (UNIQUE) so a run for `now()` is
created, marked `catch_up = true`. 4. Concurrency: a fire while the previous run of the SAME schedule is still running
is `skipped` with reason `overlap` (no piling up); the executor's per-instance/per-user slots apply to scheduled runs too
(`triggered_by` = schedule owner).

## 4. Execution path
`ScheduledRunTask.execute` → `ScheduleService.fire(scheduleId, fireTime)` → resolve pipeline version + parameters →
`ExecutionStreamLauncher.launch(ExecuteRequest(trigger = SCHEDULE, …))` (the existing path REST/MCP use; the writeback
targets and DML nodes already exist — the scheduler adds no execution semantics). The run's outcome mirrors the execution's
terminal state into `schedule_runs`. No streaming consumer: the run is observed through history (§5).

## 5. Surfaces
- **UI**: `/schedules` — the explorer (folder tree, same component as pipelines), detail (schedule facts, next five
  fires in its zone, Runs table: fire time, outcome chip, execution link, duration, picked_by), create/edit form
  (pipeline → released version → cron with presets + preview → timezone → parameters → catch-up → enabled), pause /
  resume / run now / delete. Nav: **Operate › Schedules** (079 sidebar). Ad-hoc runs appear under the pipeline's
  executions as today with `via = schedule`.
- **REST** `/api/v1/schedules` CRUD + `/pause` `/resume` `/run-now` + `/runs`; `POST /api/v1/pipelines/{id}/run-async`
  → `{run_id, execution_id?}` (ad-hoc). Scopes: `read` lists; `author` creates/edits (a schedule runs as its creator —
  it may not outlive the creator's scope: a deactivated creator disables the schedule with an event).
- **MCP** `schedules_create/list/get/pause/resume/run_now/delete` (7 tools; every count site + drift tests) and
  `pipelines_run_async`.
- **Health**: the scheduler's state (role, running, last poll, overdue count) in `/health`; metrics
  `scheduler.fires`, `scheduler.reclaims`, `scheduler.skipped`.

## 6. Configuration (`configuration.md §3.x`)
`datapipelines.scheduler.role` (both|api|worker), `threads` (default 2), `polling-interval` (10 s), `heartbeat-interval`
(30 s), `missed-heartbeats-limit` (6), `shutdown-max-wait` (60 s) — mapped onto the starter's `db-scheduler.*`; hardened
posture changes nothing. `DATAPIPELINES_SCHEDULER_ROLE` in `defaults.env`.

## 7. Tests (the round's gate)
- Unit: cron/timezone/preset/preview (DST transitions in `America/New_York` and `Europe/London`), catch-up policy,
  overlap skip, parameter validation against the pinned version, folder grammar.
- **Two-instance fire test**: two `Scheduler`s on one Testcontainers Postgres, one every-second schedule, 50 fires →
  exactly 50 `schedule_runs`, 0 duplicates, both instances picked some.
- **Kill test**: stop one scheduler mid-run (no shutdown) → the run is `reclaimed` within `heartbeat × limit`, the
  execution `aborted(instance_lost)` by the sweeper, `skip` fires nothing until the next cron; `run_now` fires once.
- Role test: an `api`-role instance creates a schedule and never fires it; a `worker` fires it.
- E2E: the demo's `nyc/mobility/mobility_briefing` scheduled every minute with `output.target = datasource` into the
  demo Postgres (a `DML`-free writeback); after two fires the target table has two batches and `/schedules` shows two
  succeeded runs with execution links. Browser: create → preview → pause → run now.

## 8. Not in this round
Reports and email (enum reserved), per-schedule notifications, retries with backoff (a failed run is a failed run —
the next cron fires), schedule import/promotion (055 D8 stands: promotion carries no schedules), workers with different
executor limits (R-S5 seam only).
