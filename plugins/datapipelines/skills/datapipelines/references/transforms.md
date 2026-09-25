# Transforms — JSONata templates with a contract, invariants and tests

Open when a pipeline's logic does not fit SQL: a nested shape, per-row business logic, or a
value SQL cannot express. A **transform template** is a pure function over staged data with a
declared contract and a test suite — write the empty and wrong-data cases before the
right-data one.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

## The two types

A template's `type` is `jsonata` or `javascript` (the transform types) beside `sql`/`html`.
A transform sets `engine: "none"` (evaluated, never rendered) and takes **no** `dialect`, no
`imports`, no `is_library`; a Freemarker construct (`${`, `<#`, `<@`) in its body is refused
with `template.validation.freemarker_forbidden`. `javascript` exists in the vocabulary but is
refused at save with `transform.js.unavailable` until round two — author `jsonata` today.

`templates_render` refuses a transform type with `template.render_not_applicable`; its probe
is **`templates_evaluate`** (`{ id, version?, input, now? }` → `{ output, rejects, invariants }`),
and a successful evaluate of a draft counts as its render for the render-before-you-run check.
A `javascript` or `sql`/`html` id passed to evaluate is `template.contract_invalid`
(`rule: type_not_transform`); an unknown one is `template.not_found`.

## The three blocks (required on every transform version)

`contract`, `invariants`, `tests` — bound strictly (a typo is `template.contract_invalid`,
`rule: unknown_field`, never a silent drop) and inside the version's `body_hash`.

```json
{
  "contract": {
    "mode": "row",
    "inputs": {
      "orders": { "kind": "table", "columns": [
        { "name": "order_id", "type": "INTEGER" },
        { "name": "amount_cents", "type": "INTEGER" },
        { "name": "customer_id", "type": "STRING", "nullable": true } ] },
      "tz": { "kind": "value", "type": "STRING" }
    },
    "output": { "kind": "table", "columns": [
      { "name": "order_id", "type": "INTEGER" },
      { "name": "amount", "type": "DECIMAL", "precision": 12, "scale": 2 },
      { "name": "customer_id", "type": "STRING" } ] },
    "rejects": true
  },
  "invariants": [
    { "name": "one_to_one",
      "expr": "$count(rows) + $count(rejects) = $count(inputs.orders)",
      "message": "every input row is accepted or rejected, never lost" }
  ],
  "tests": [
    { "name": "empty input",
      "input": { "rows": [], "inputs": { "tz": "UTC" } },
      "expect": { "output": { "rows": [], "rejects": [] } } },
    { "name": "wrong shape is refused",
      "input": { "rows": [ { "order_id": "x" } ], "inputs": { "tz": "UTC" } },
      "expect": { "refusal": "pipeline.transform.input_contract_violation" } }
  ]
}
```

- **`mode`** — `row` (the body reads `rows`, one batch at a time; exactly ONE table input),
  `table` or `value` (the body reads `inputs.<name>`; the output is one value or
  `{ "kind": "object" }`). Mismatches: `row_mode_inputs`, `mode_output_mismatch`,
  `rejects_without_table`.
- **Types are declared, never inferred.** Names are `LogicalType` wire names (`INTEGER`,
  `BIGINTEGER`, `DECIMAL`, `BIGDECIMAL`, `STRING`, `BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP`;
  `BINARY`/`NULL` refused, `type_unsupported`). Wire forms: `BIGINTEGER` and `BIGDECIMAL` are
  JSON **strings**; `DECIMAL` is a JSON **number** and an output is ROUNDED half-even to its
  declared scale; precision/scale follow the type system's rules (`precision_scale_invalid`).
- **`tests`** — at least one case whose every table input and `rows` are empty
  (`empty_case_missing`), and in `row` mode a case lists NO table under `inputs`
  (`row_case_lists_table`). `expect` is exactly one of `output` (compared after
  canonicalisation — key order and decimal spelling never decide equality) or `refusal: "<code>"`
  (`expect_shape`). A case may pin the clock with `"now": "<ISO-8601>"`; absent, `$now()` and
  `$millis()` refuse (the clock is an input, not an ambient read).
- **`invariants`** — JSONata over `{ rows, rejects, inputs }`, must be `true` on every case
  (and every real execution). Compiled at save (`invariant_invalid`); in row mode the table
  input appears under its contract name.

## The authoring loop

1. `templates_create` with the type, the body, and the three blocks — **save runs the suite**:
   every case on the bounded evaluation pool, output gated, invariants checked. The first
   failure is `template.test_failed` naming the case (the diff is bounded; a wrong refusal
   code is a failure too).
2. Iterate with `templates_evaluate` per case — same engine, same pool, same input check —
   and fix what it surfaces. `templates_get` returns the blocks back.
3. A human releases from the UI; **release re-runs the suite** on the version being released.

Refusal codes you will meet: `template.validation.freemarker_forbidden` (detail `imports` /
`is_library` / `body`), `template.contract_invalid` (detail names the rule),
`template.invariant_invalid`, `template.test_failed`, `template.blocks_not_allowed` (the
blocks on `sql`/`html`), `template.render_not_applicable`, `transform.js.unavailable`, and
the engine's own (`pipeline.transform.evaluation_failed` / `timeout` / `resource_limit` /
`pool_exhausted`), the input check's `pipeline.transform.input_contract_violation`, and the
type gate's `pipeline.transform.row_shape_mismatch` / `value_type_mismatch` /
`precision_lost` / `value_too_large`. The bounds: one case ≤
`datapipelines.transform.evaluate-timeout-seconds`, the suite ≤ `suite-timeout-seconds`;
time catches recursion, depth catches nesting.

## Implements — the rule this transform computes

A workspace rule you or an earlier session recorded as a learned fact — a `definition`,
`exclusion` or `preference` ("rainy = `precipitation_mm >= 2.5`", "tips are not revenue") —
and the transform that computes it are one decision. Cite the rule on the version:

```json
{ "implements": ["<fact id of the definition>"] }
```

- **Only WORKSPACE rules of this workspace.** The ids come from `semantics_list` or the
  `definitions` on `datasources_list` / `datasources_get`. Anything else — another
  workspace's, a DATASOURCE fact (a transform never sees a datasource), a typo — is
  `template.implements_unresolved` (`details.reason`: `unknown` / `malformed` / `too_many`,
  at most 50). On `sql`/`html` the field is `template.blocks_not_allowed`.
- **Not content.** `implements` is outside `body_hash`: it never opens a draft on its own.
  On `templates_update`, **omitted keeps** the citations of the version you edit (a new draft
  inherits the released one's); `[]` clears them. An update whose body equals the released
  version and that states `implements` writes the citations ON the released version — no draft.
- **Find before you write.** Every WORKSPACE fact on `semantics_list` and every rule under
  `definitions` carries `implemented_by: [{template_id, version}]`; `templates_list
  {"implements": "<fact id>"}` lists the templates whose listed version cites it. A rule
  someone already implemented is a pin, not a new template.
- **Drift.** When a cited fact is retired or superseded, every citing version reads
  `needs_review: true` on `templates_get` / `templates_list`, and `retired_facts` names the
  retired id and its `superseded_by`. Nothing changes the transform for you: read the
  successor, re-verify the body against it, then `templates_update` citing the successor (on a
  released version that opens no draft). A pipeline release pinning a `needs_review` version is
  not refused — the human sees a `pipeline.release.template_needs_review` warning.

## The node

```json
{
  "id": "shape_orders",
  "description": "Order lines at reporting grain.",
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
Context key of any tier, anything else is a tempdb table a node this one depends on stages; the
key set must equal the contract's input names. `source` is forbidden — a TRANSFORM is
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
`:bind`, calculator input or `PIPELINE` parameter naming it is
`transform_object_key_bound`. **R5** — `rejects` on a `caller` output, or a rejects contract
on a caller node, is `transform_rejects_on_caller` (rejects are never silently dropped). The
rest of the family: `transform_template_type` (pin is not jsonata/javascript),
`transform_source_forbidden`, `transform_input_unknown` (a table no ancestor stages, or a
`$key` nobody writes), `transform_input_contract` (key set ≠ contract, or a value input's type
does not fit), `transform_output_shape`, `transform_rejects_undeclared` / `_missing`,
`transform_strict_without_rejects`.

At run, the executor evaluates through the same pool and gate as `templates_evaluate`:
`row` streams the input in `result-batch-size` batches (never whole in the JVM), `table`/`value`
refuse over-`max-input-rows` inputs BEFORE loading, and invariants read the **written** tempdb
tables back (over `max-input-rows` is `pipeline.transform.invariants_too_large`; no invariants
declared means no read-back and no bound). The refusal family is `pipeline.transform.*`:
`input_contract_violation`, `input_too_large`, `evaluation_failed`, `timeout`,
`resource_limit`, `pool_exhausted`, `row_shape_mismatch`, `value_type_mismatch`,
`precision_lost`, `value_too_large`, `invariant_failed`, `invariants_too_large`,
`rejects_strict`. `pipelines_execute_node` REFUSES a TRANSFORM (`use: templates_evaluate`);
node stats carry `rows_in`, `rows_out`, `rows_rejected` and `invariants_checked`.
