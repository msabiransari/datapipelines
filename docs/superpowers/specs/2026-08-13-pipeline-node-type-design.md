# Design: `PIPELINE` Node Type — Pipeline Composition

**Status:** approved design, pre-implementation (owner-approved 2026-08-13)
**Scope:** spec 1 of 2 from the semantic-layer direction
([semantic-layer-research.md Part five](../../semantic-layer-research.md)) —
composition ships first because it is independently useful and creates the
"components" the learned semantic layer (spec 2, separate cycle) will build on.
**Authority note:** this document records the *design decisions*. The normative
contract text lands as amendments to `docs/pipeline-contract.md`, `docs/enums.md`,
`docs/configuration.md`, `docs/metadata-db.md`, and `docs/dag-executor.md` at
implementation time — **in the same commit as the corresponding code constants**,
because the enum table and the §12/§13 error-code catalog are drift-tested on main
(standing MISTAKES.md rule). Error codes below are PROPOSED until they enter
pipeline-contract §13.

---

## 1. Summary

A fourth `NodeType`, `PIPELINE`, lets a pipeline node execute another pipeline and
consume its result. Composition is by **invocation, not inlining**: the child runs
as a real, separate execution (own execution record, own tempdb, own stats, own SSE
stream), started through the internal execution service — never HTTP — with its
caller-node ResultSet streamed **directly** back to the parent executor, bypassing
the Redis result cursor.

Rationale (owner decision, reversing an earlier inline-expansion draft): separate
executions do not mix five components' nodes into one DAG's stats, cannot collide
in the shared tempdb, are individually debuggable and addressable with machinery
that already exists (`executions_get`, execution history, SSE), and parallel
PIPELINE nodes get genuinely parallel staging instead of contending for the
parent's single H2 connection.

## 2. Decisions record

| # | Fork | Decision | Rationale |
|---|---|---|---|
| D1 | One spec or two | Two; node type first | Independently useful; unblocks components; contract change lives near its authority doc |
| D2 | Execution model | Child execution via internal invocation | Debuggability, isolation, stats separation, reuse of existing execution machinery; metadata-db linkage is a small additive cost |
| D3 | Child shape / output | Mirror node semantics | Caller-output child → rows become the node's output; zero-caller child → side-effect node, ordering-only dependency |
| D4 | Result delivery | New `direct` delivery mode, internal-only | Child result streams to the parent executor; nothing materialized to Redis; not re-fetchable afterwards (re-run is the recovery path) |
| D5 | Version reference | `{name, version}` pinned, required | `name` is the documented cross-pipeline identifier (pipeline-contract §3.2); pinning mirrors template refs; no "latest" |
| D6 | Child visibility | Children appear in execution history | Auditability beats tidiness; "hide children" is a UI filter concern, deferred |
| D7 | Deletion lifecycle | Mirror templates: soft-delete does NOT affect existing pinned references; blocks only NEW references at save time | Corrected 2026-08-13 from an earlier block-while-referenced proposal after verifying templates.md ("Deleting a template (soft delete) does not affect existing versions") — pin-based referencing implies the same rule |
| D8 | Cancellation | Family-wide propagation via `root_execution_id` | Cancelling a parent must not orphan running children |
| D9 | Auth | No new scopes; child runs under the parent's principal; authorization checked on the parent only | Same trust model as templates; v1 is internal-users-only |

## 3. Node shape

```json
{
  "id": "revenue",
  "description": "Monthly revenue component.",
  "type": "PIPELINE",
  "pipeline": {"name": "monthly_revenue", "version": 4},
  "parameters": {"start_date": "${start_date}", "region": "EU"},
  "output": {"target": "tempdb", "table": "stg_revenue"},
  "depends_on": []
}
```

Field rules (amend pipeline-contract §4.6/§4.7):

- `type: "PIPELINE"` — new `NodeType` enum constant (enums.md §2).
- `pipeline` — required: `{name, version}`. `name` per `[a-z0-9_]+`; `version` a
  positive integer pinning an existing, immutable pipeline version.
  Self-reference (`name` = the containing pipeline's own name) is invalid.
- `source` and `template` — **forbidden** on PIPELINE nodes (mirrors "output
  forbidden on DML/DDL").
- `parameters` — optional map filling the child's declared parameters. Each value
  is either a typed literal obeying the child parameter's §6.3 wire encoding, or
  the string form `${parent_param}` referencing one of the parent's declared
  parameters. No expressions, no concatenation — a value is a literal or a
  reference, nothing in between (v1).
- `output` — standard §4.7 block, permitted only when the pinned child has a
  caller node. Zero-caller child ⇒ `output` must be absent; the node is
  side-effect-only and downstream `depends_on` gives ordering.
- `depends_on` — unchanged.

## 4. Execution semantics

### 4.1 Invocation

The DAG executor executes a PIPELINE node by calling the **internal execution
service method** (the same path `pipelines_execute` reaches after transport/auth
peel-off) with: the pinned pipeline version, the resolved parameter map, delivery
mode `direct`, the parent's principal, and lineage fields (§5). No HTTP, no
idempotency-key machinery, no rate-limiter involvement (but see §4.4).

### 4.2 Result delivery modes

Execution requests carry a delivery mode:

- `cursor` (default; today's behavior) — first page inline in `data_ready`,
  remainder via the Redis-backed cursor with TTL.
- `direct` — the caller-node ResultSet streams synchronously to the invoker;
  **nothing is written to Redis**; there is no cursor and the result is not
  re-fetchable after consumption. Accepted **only** from internal invocation;
  REST/MCP requests declaring `direct` are rejected (they have SSE + cursor
  already).

The parent executor consumes the `direct` stream and lands the rows per the
node's `output` block: staged into the parent's tempdb under `output.table`,
returned to the parent's caller, or streamed onward to a datasource target —
identical post-node behavior to a DQL node's ResultSet.

Zero-caller child: the parent waits for child completion; there is no stream;
success/failure is the node's outcome.

### 4.3 Failure and cancellation

- Child execution failure fails the parent's PIPELINE node **fail-fast** with the
  child's error code and the child execution id in the node error detail — the
  debugging trail leads to a real execution record.
- Cancellation propagates family-wide: the Redis cancellation flag is honored for
  every execution sharing the parent's `root_execution_id`; `DELETE
  /executions/{id}` on an ancestor cancels descendants. Children cannot be left
  running after their parent stops.

### 4.4 Guards

- **Depth.** New config key (name finalized in configuration.md's authority
  table; working name `datapipelines.pipelines.max-composition-depth`, default
  `5`). Checked twice: statically at save time (pins are immutable, so the
  reference tree is fully computable) and at run time via a depth counter in the
  internal invoke (defense in depth per the §12.2 crash-safety posture; iterative
  traversal, never recursive in graph depth).
- **Cycles.** Impossible by construction: a reference pins an *existing,
  immutable* version at save time, so the (name, version) reference graph is
  temporally acyclic. The save-time traversal still runs iteratively with the
  depth bound as backstop.
- **Fan-out / concurrency.** Child executions count against the per-user
  concurrent-execution cap; when the cap is reached, PIPELINE nodes wait for a
  slot rather than overflow. A composition cannot multiply itself past the
  executor's limits.

> **Correction (2026-08-13, plan):** children do not take concurrency slots —
> root executions only; a waiting parent holding a slot while children queue
> would deadlock. Bounds: depth ≤ max-composition-depth, 1000-node cap per
> pipeline.

## 5. Metadata DB (additive migration)

`pipeline_executions` gains three nullable columns + one index:

| Column | Type | Meaning |
|---|---|---|
| `parent_execution_id` | UUID, FK → `pipeline_executions.id` | The execution that spawned this one (NULL for roots) |
| `parent_node_id` | text | The PIPELINE node id in the parent that spawned this execution |
| `root_execution_id` | UUID, indexed | The top ancestor; equals own id for roots. One indexed query returns the whole family; cancellation keys off it |

Parent node stats gain `child_execution_id`, so UI and API link parent node →
child execution directly. Migration decision: `root_execution_id` is **backfilled
to own id** for existing rows and NOT NULL going forward — family queries and
cancellation never special-case NULL. `parent_execution_id`/`parent_node_id`
stay NULL for roots.

## 6. Validation (amend pipeline-contract §12, new subsection; codes PROPOSED)

| Proposed code | Check |
|---|---|
| `pipeline.validation.pipeline_not_found` | `pipeline.name` exists in the registry |
| `pipeline.validation.pipeline_version_not_found` | Pinned `version` exists for that name |
| `pipeline.validation.pipeline_self_reference` | Node does not reference its containing pipeline |
| `pipeline.validation.pipeline_reference_deleted` | Referenced pipeline is not soft-deleted (blocks NEW references only — D7) |
| `pipeline.validation.pipeline_node_has_source` | PIPELINE node has no `source` |
| `pipeline.validation.pipeline_node_has_template` | PIPELINE node has no `template` |
| `pipeline.validation.pipeline_parameter_unmapped` | Every required-without-default child parameter is supplied |
| `pipeline.validation.pipeline_parameter_unknown` | Every supplied key exists in the child's `parameters` |
| `pipeline.validation.pipeline_parameter_type_mismatch` | Literals obey the child parameter's wire encoding; `${ref}` targets a parent parameter of a compatible type |
| `pipeline.validation.pipeline_output_on_sideeffect_child` | `output` absent when the pinned child has zero caller nodes |
| `pipeline.validation.composition_too_deep` | Static reference-tree depth ≤ configured max |

Also amended: §12.4's `type_invalid` row (enum gains `PIPELINE`), and §13.4 gains
two runtime codes in its existing `pipeline.node.*` format: proposed
`pipeline.node.child_execution_failed` (500 — child execution failed; detail
carries the child's error code and execution id) and
`pipeline.node.composition_depth_exceeded` (500 — runtime depth backstop hit;
indicates a save-time validation gap, since static depth should catch it first).

## 7. Surfaces

- **REST / MCP:** no new endpoints, no new scopes. `pipelines_create`/`update`
  accept the node type; execution history shows child executions as normal rows
  with a spawned-by marker (D6). Scope check on parent execute only (D9).
- **SSE:** parent stream emits the PIPELINE node's lifecycle events carrying
  `child_execution_id`; the child has its own stream, linkable from the parent's
  events. No nested/merged streams in v1.
- **Editor UI:** PIPELINE nodes render as a distinct node kind with a link to
  the child pipeline. No inline sub-graph rendering in v1 (deferred, like the
  template composition visualizer).

## 8. Testing

- **pipeline-contract:** validation tests per §6 row; enum drift test updated
  same-commit as the enums.md amendment.
- **dag / app:** internal invocation, `direct` streaming into all three output
  targets, zero-caller wait, failure propagation (code + child execution id),
  cancellation family propagation, depth guard (static + runtime), fan-out cap
  behavior, lineage columns written correctly.
- **integration-tests:** one E2E — parent → cross-source child → grandchild,
  asserting rows, lineage links, and per-execution stats separation.
- **docs-audit** green after every doc amendment; FULL build on catalog/enum doc
  changes (standing rule).

## 9. Explicitly out of scope (deferred)

- Inline sub-graph rendering in the editor.
- Child-result caching/memoization (natural follow-on: a persisted child
  execution is a cache key — ROADMAP §3.6 territory).
- `direct` delivery for external (REST/MCP) callers.
- "Hide child executions" history filter.
- The learned semantic layer itself — spec 2, separate design cycle.
