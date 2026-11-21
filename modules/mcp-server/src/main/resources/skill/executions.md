---
area: executions
layer: guide
purpose: Runs — execute, read and page results, cancel, and diagnose a failure.
---

# Executions — runs, results and failures

An **execution** is one run of a pipeline: the nodes execute in `depends_on` order, each
staging or shipping its rows, until a terminal state — `SUCCESS`, `FAILED`, `ABORTED` or
`OUTCOME_UNKNOWN` (see "When a write's outcome is unknown"). The
final result carries `node_stats` (per-node status, durations, row counts, errors) — the
authoritative per-node record.

History (`executions_list`) lists the runs you fired, and every run a schedule fired — a
scheduled run is nobody's own, so every role that reads executions sees it; the workspace's
other interactive runs are visible only through a workspace-admin-role key.

## Running

`pipelines_execute` is a **single blocking call** — it returns only when the execution reaches
a terminal state or the execution timeout (default ${execution_timeout_seconds} s) aborts it.
There are no progress notifications; a three-minute pipeline is one three-minute tool call.
With no `version` argument it runs the **working version** — your draft when one exists, else
the latest release — so testing your own work needs no version argument, and a draft run is
the expected test loop: history marks it (`draft_run`) and it never counts as validation for a
human's release.

**Cancellation is requested, not awaited.** `executions_cancel` cancels a RUNNING execution
your own MCP calls started — the same-credential rule: `triggered_via` MCP, your user, and an
audit row pairing THIS key with the execution's correlation id. An execution started over REST,
the UI, or another key of your user is refused (the refusal says which rule fired) — for those,
ask the person to cancel in the UI. Poll `executions_get` for the terminal `ABORTED`.

Abandoned calls run to completion — `/mcp` has no disconnect callback; cancel explicitly or let
the timeout handle it. The MCP execute tool has no idempotency key (REST does): unsure a call
landed? Check `executions_list` before refiring. `/mcp` and `/api/v1` share a per-user rate
limiter — back off on `429`.

## Reading results

The answer carries the inline first page, `total_rows`, `has_more`, `ttl_seconds` and the
cursor. Page the remainder with `executions_get_result` (`offset`/`limit`) **within the TTL** —
afterwards the result is gone (`result.expired`); what paging is for and what a truncated tool
result does to a reply is the pipelines guide's step 6. Zero-caller pipelines return stats
with no rows — a valid design, not a failure.

## When a write's outcome is unknown

A run whose writes share one database transaction publishes nothing — no result, no
`SUCCESS` — until its single commit is confirmed. A run that ends **`OUTCOME_UNKNOWN`**
(`error.code` `pipeline.execution.local_write_commit_failed`) means the commit was attempted
and its acknowledgement was lost: the writes may or may not exist. **Never re-run the
pipeline to find out** — a re-run can write twice. Check the target database, or tell the
person with the execution id, before any deliberate new run. `executions_get_result` answers
`result.execution_failed` for it. A `SUCCESS` carrying `result_unavailable: true` committed;
only its result was not delivered (`result.storage_unavailable`; `pipelines_execute` returns
it as `result_unavailable: true` with no rows), and the writes are not repeated by re-running. Until the root settles, a child run of the same family stays
`RUNNING` and its event history shows no terminal event; afterwards it ends in one terminal
event carrying the root's outcome. The wait for a commit is bounded by the run's own time
budget: a commit that never answers ends the run `OUTCOME_UNKNOWN` at that budget.

## When a run fails

`executions_get` (and `pipelines_execute`'s own failure result) carries the FULL failure record
in `error` — the same object the UI shows and `error_json` stores. The reading discipline —
validation is your bug versus the world's state, the caused_by chain whose LAST entry is the
root cause, quoting the correlation id when you escalate — is the core's error-recovery rule;
the fields this surface adds:

- `error.sql` — the rendered SQL in `:name` form, exactly as it failed (bound values are
  never in it; they are in the execution's `parameters`).
- `error.node` — the datasource, dialect and pinned template that failed.
- `error-detail=full` (the default here) ships the exception chain and stack frames with the
  error; a deployment may set `structured`, in which case `error.exception` and `error.sql`
  are absent and you work from the code, message, node context and correlation id.

For the walk from failure to fix — `executions_get` → failing node's `node_stats` →
`pipelines_get` → `templates_get` → `templates_render` with the failed run's parameters →
proposed fix — follow the `debug_failed_execution` prompt. Timeouts have their own budgets and
reading order: `pipelines-dag` § Timeouts.

## References — open when

- **`executions-tools`** — the area's tools, generated from their shipped descriptions.
- **`pipelines-dag`** — a node timed out: the three budgets and which one to reach for.
- **`pipelines-verification`** — the run succeeded and the numbers must be challenged.
