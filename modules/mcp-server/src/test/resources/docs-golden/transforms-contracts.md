
# The transform contract, invariants and tests

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
  canonicalisation — key order and decimal spelling never decide equality) or
  `refusal: "<code>"` (`expect_shape`). A case may pin the clock with `"now": "<ISO-8601>"`;
  absent, `$now()` and `$millis()` refuse (the clock is an input, not an ambient read).
- **`invariants`** — JSONata over `{ rows, rejects, inputs }`, must be `true` on every case
  (and every real execution). Compiled at save (`invariant_invalid`); in row mode the table
  input appears under its contract name.

## Implements — the rule this transform computes

A workspace rule you or an earlier session recorded as a learned fact — a `definition`,
`exclusion` or `preference` ("rainy" thresholds, "tips are not revenue") — and the transform
that computes it are one decision. Cite the rule on the version:

```json
{ "implements": ["<fact id of the definition>"] }
```

- **Only WORKSPACE rules of this workspace.** The ids come from `semantics_list` or the
  `definitions` on `datasources_list` / `datasources_get` (`datasources-semantics`). Anything
  else — another workspace's, a DATASOURCE fact (a transform never sees a datasource), a typo
  — is `template.implements_unresolved` (`details.reason`: `unknown` / `malformed` /
  `too_many`, at most 50). On `sql`/`html` the field is `template.blocks_not_allowed`.
- **Not content.** `implements` is outside `body_hash`: it never opens a draft on its own. On
  `templates_update`, **omitted keeps** the citations of the version you edit (a new draft
  inherits the released one's); `[]` clears them. An update whose body equals the released
  version and that states `implements` writes the citations ON the released version — no
  draft.
- **Find before you write.** Every WORKSPACE fact on `semantics_list` and every rule under
  `definitions` carries `implemented_by: [{template_id, version}]`; `templates_list
  {"implements": "<fact id>"}` lists the templates whose listed version cites it. A rule
  someone already implemented is a pin, not a new template.
- **Drift.** When a cited fact is retired or superseded, every citing version reads
  `needs_review: true` on `templates_get` / `templates_list`, and `retired_facts` names the
  retired id and its `superseded_by`. Nothing changes the transform for you: read the
  successor, re-verify the body against it, then `templates_update` citing the successor (on a
  released version that opens no draft). A pipeline release pinning a `needs_review` version
  is not refused — the human sees a `pipeline.release.template_needs_review` warning.

Refusal codes a save can answer: `template.validation.freemarker_forbidden` (detail `imports`
/ `is_library` / `body`), `template.contract_invalid` (detail names the rule),
`template.invariant_invalid`, `template.test_failed`, `template.blocks_not_allowed` (the
blocks on `sql`/`html`), `template.render_not_applicable`, `transform.js.unavailable`.
