---
area: transforms
layer: reference
purpose: Evaluating a transform — templates_evaluate, the run-time pool and bounds, the TRANSFORM node, the refusal family.
---

# Evaluating transforms

## The probe: `templates_evaluate`

`templates_render` refuses a transform type with `template.render_not_applicable`; the probe
is **`templates_evaluate`** (`{ id, version?, input, now? }` → `{ output, rejects, invariants
}`), and a successful evaluate of a draft counts as its render for the render-before-you-run
check. A `javascript` or `sql`/`html` id passed to evaluate is `template.contract_invalid`
(`rule: type_not_transform`); an unknown one is `template.not_found`.

The bounds: one case ≤ `datapipelines.transform.evaluate-timeout-seconds`, the suite ≤
`datapipelines.transform.suite-timeout-seconds`; time catches recursion, depth catches
nesting.

## The node

```json
{
  "id": "shape_orders",
  "description": "Order lines at the answer's grain.",
  "type": "TRANSFORM",
  "template": { "id": "acme/shape/order_lines.jsonata", "version": 3 },
  "inputs": { "orders": "stg_orders", "tz": "$org_timezone", "min_total": "$min_total" },
  "output": { "target": "tempdb", "table": "order_lines", "rejects": "order_lines_rejected" },
  "strict": false,
  "depends_on": ["stage_orders"]
}
```

The node pins a transform template and **conforms to the pinned contract at save** — the mode
is the contract's, never the node's. `inputs` follows the CALCULATOR convention: `"$key"` is a
Context key of any tier, anything else is a tempdb table a node this one depends on stages;
the key set must equal the contract's input names. `source` is forbidden — a TRANSFORM is
tempdb-only. Per mode:

- **`row` / `table`** — `output` is `{ "target": "tempdb", "table", "rejects"? }` or
  `{ "target": "caller" }`. `rejects` is required exactly when the contract declares it.
- **`value`** — no `output` block; the node writes `context_key` (every calculator rule: name
  shape, one writer, never shadowing a parameter, topology-ordered). The key is an implicit
  optional execute input: supplied, the node is skipped with `provided_by: "caller"`.
- **`strict: true`** — only with a rejects contract: a non-empty rejects table then fails the
  node with `pipeline.transform.rejects_strict`. Default is partition.

Three rules that bite at save time: **R3** — a `target` on `value` mode is
`transform_output_shape` (a value-mode TRANSFORM writes a Context key only). **R4** — a key
whose writer declares `output.kind: object` binds ONLY to another TRANSFORM's `inputs`; a SQL
`:bind`, calculator input or `PIPELINE` parameter naming it is `transform_object_key_bound`.
**R5** — `rejects` on a `caller` output, or a rejects contract on a caller node, is
`transform_rejects_on_caller` (rejects are never silently dropped). The rest of the family:
`transform_template_type` (pin is not jsonata/javascript), `transform_source_forbidden`,
`transform_input_unknown` (a table no ancestor stages, or a `$key` nobody writes),
`transform_input_contract` (key set ≠ contract, or a value input's type does not fit),
`transform_output_shape`, `transform_rejects_undeclared` / `_missing`,
`transform_strict_without_rejects`.

## At run

The executor evaluates through the same pool and gate as `templates_evaluate`: `row` streams
the input in `result-batch-size` batches (never whole in the JVM), `table`/`value` refuse
over-`max-input-rows` inputs BEFORE loading, and invariants read the **written** tempdb tables
back (over `max-input-rows` is `pipeline.transform.invariants_too_large`; no invariants
declared means no read-back and no bound). The refusal family is `pipeline.transform.*`:
`input_contract_violation`, `input_too_large`, `evaluation_failed`, `timeout`, `resource_limit`,
`pool_exhausted`, `row_shape_mismatch`, `value_type_mismatch`, `precision_lost`,
`value_too_large`, `invariant_failed`, `invariants_too_large`, `rejects_strict`.
`pipelines_execute_node` REFUSES a TRANSFORM (`use: templates_evaluate`); node stats carry
`rows_in`, `rows_out`, `rows_rejected` and `invariants_checked`.
