---
area: scripts
layer: reference
purpose: Evaluating a script — templates_evaluate, the run-time pool and bounds, the SCRIPT node, the refusal family.
---

# Evaluating scripts

## The probe: `templates_evaluate`

`templates_render` refuses a script type with `template.render_not_applicable`; the probe
is **`templates_evaluate`** (`{ id, version?, input, now? }` → `{ output, rejects, invariants, console
}`), and a successful evaluate of a draft counts as its render for the render-before-you-run
check. A `sql`/`html` id passed to evaluate is `template.contract_invalid`
(`rule: type_not_script`); an unknown one is `template.not_found`.

`console` is always a list of `{ level, text }` (empty when unused); lines captured before
an evaluation fails remain in `details.console`. A missing isolate refuses JavaScript with
`script.js.unavailable`.

The bounds: one case ≤ `datapipelines.script.evaluate-timeout-seconds`; a suite still running
at `datapipelines.script.suite-timeout-seconds` fails (`details.timeout = suite`), though the
call can return somewhat later; the JSONata depth bound
`datapipelines.script.max-depth` (default `${script_max_depth}`) counts
every evaluate entry and exit — nested expressions and non-tail lambda recursion alike, and
set-level work over hundreds of rows evaluates at the default (#260) — while a tail-recursive
lambda is trampolined by the library, so the wall clock is the bound that catches that shape. A
body whose STATIC bracket nesting exceeds 64 levels is refused before the library parses it — at
save and validate as `template.validation.syntax_error` (details `kind: DEPTH`, in the failure
list; an invariant as `invariant_invalid`), at run as `pipeline.script.resource_limit` — the
library's parser recurses per level and a deeper body can overflow the compiling thread's stack.
Input size here: the `rows` batch and each table input are capped at
`datapipelines.script.max-input-rows` (`${script_max_input_rows}` rows) before any evaluation.

## The node

```json
{
  "id": "shape_orders",
  "description": "Order lines at the answer's grain.",
  "type": "SCRIPT",
  "template": { "id": "acme/shape/order_lines.jsonata", "version": 3 },
  "inputs": { "orders": "stg_orders", "tz": "$org_timezone", "min_total": "$min_total" },
  "output": { "target": "tempdb", "table": "order_lines", "rejects": "order_lines_rejected" },
  "strict": false,
  "depends_on": ["stage_orders"]
}
```

The node pins a script template and **conforms to the pinned contract at save** — the mode
is the contract's, never the node's. `inputs` follows the CALCULATOR convention: `"$key"` is a
Context key of any tier, anything else is a tempdb table a node this one depends on stages;
the key set must equal the contract's input names. `source` is forbidden — a SCRIPT is
tempdb-only. Per mode:

- **`row` / `table`** — `output` is `{ "target": "tempdb", "table", "rejects"? }` or
  `{ "target": "caller" }`. `rejects` is required exactly when the contract declares it.
- **`value`** — no `output` block; the node writes `context_key` (every calculator rule: name
  shape, one writer, never shadowing a parameter, topology-ordered). The key is an implicit
  optional execute input: supplied at execute time or mapped by a parent PIPELINE node's
  `parameters` (literal or scalar `${name}`), the node is skipped with `provided_by: "caller"`.
  The pinned output contract still gates the supplied value; object/list references are refused.
- **`strict: true`** — only with a rejects contract: a non-empty rejects table then fails the
  node with `pipeline.script.rejects_strict`. Default is partition.

Three rules that bite at save time: **R3** — a `target` on `value` mode is
`script_output_shape` (a value-mode SCRIPT writes a Context key only). **R4** — a key
whose writer declares `output.kind: object` binds ONLY to another SCRIPT's `inputs`; a SQL
`:bind`, calculator input or `PIPELINE` parameter naming it is `script_object_key_bound`.
**R5** — `rejects` on a `caller` output, or a rejects contract on a caller node, is
`script_rejects_on_caller` (rejects are never silently dropped). The rest of the family:
`script_template_type` (pin is not jsonata/javascript), `script_source_forbidden`,
`script_input_unknown` (a table no ancestor stages, or a `$key` nobody writes),
`script_input_contract` (key set ≠ contract, or a value input's type does not fit),
`script_output_shape`, `script_rejects_undeclared` / `_missing`,
`script_strict_without_rejects`.

## At run

The executor evaluates through the same pool and gate as `templates_evaluate`. The input cap
is BY PATH: `templates_evaluate` and every test case cap the `rows` batch and each table
input against `datapipelines.script.max-input-rows` (`${script_max_input_rows}` rows) before
any evaluation; at run a `table`/`value` node counts each table input before loading it
(past the cap: `pipeline.script.input_too_large`), while a `row` node streams its single
input in `datapipelines.staging.h2.result-batch-size` batches — never whole in the JVM —
and has NO row cap of its own.

The declared invariants read the **written** tempdb tables back, and the cap they answer to
is on the COMBINED count, not on each table: the written outputs and rejects plus the table
inputs (row mode adds the streamed input, and — for a caller output — the buffered caller
rows and rejects) must together fit `datapipelines.script.max-input-rows`. An input and an
output each just over half the cap fail together, though each alone is under it; past the
combined count is `pipeline.script.invariants_too_large`. A `table` caller output's gated
rows count with its inputs (a tempdb output is counted once, as its table). No invariants declared
means no invariant read-back or combined read-back row bound; the table/value input caps
above still apply. The run also stays bounded by
`datapipelines.script.max-value-bytes`, `datapipelines.script.max-string-bytes`,
`datapipelines.script.max-depth` (default `${script_max_depth}`), the wall clock and the
JavaScript caps (`datapipelines.script.js.*`).

The pool is ONE per process: the save/release suite, `templates_evaluate`, the editor preview
and every SCRIPT node of every execution draw from the same slots, and each row-mode batch
and each invariant is its own evaluation taking one. At most `${script_pool_size}`
evaluations run at once, and at most `${script_pool_queue}` are admitted in total (running
plus waiting) — a submission past that is refused at once with `pipeline.script.pool_exhausted`,
not queued. The admission just inside the bound does NOT fail: it waits for a running slot
for at most its own wall clock, and a SCRIPT node's budget there is its deadline minus the
cancellation grace, so the wait spends the node's wall clock. An evaluation past its wall
clock plus grace is abandoned but keeps its slot until its thread ends. `pool_exhausted`
fails the node, and the executor's fail-fast aborts the siblings. Raising
`datapipelines.script.pool-size` alone is not a fix: the shared JavaScript isolate is sized
`datapipelines.script.pool-size × datapipelines.script.js.max-heap-mb + 256` MiB, so each
extra slot multiplies the memory.

The refusal family is `pipeline.script.*`:
`input_contract_violation`, `input_too_large`, `evaluation_failed`, `timeout`, `resource_limit`,
`pool_exhausted`, `row_shape_mismatch`, `value_type_mismatch`, `precision_lost`,
`value_too_large`, `invariant_failed`, `invariants_too_large`, `rejects_strict`.
`pipelines_execute_node` REFUSES a SCRIPT (`use: templates_evaluate`); node stats carry
`rows_in`, `rows_out`, `rows_rejected` and `invariants_checked`.
