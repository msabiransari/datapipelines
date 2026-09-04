# Design: Calculators — Configurable Pure Transformations (Context + `CALC` Node)

**Status:** RATIFIED 2026-09-04 (owner) — the shape in §0 supersedes §4–§5 and Appendix A's row kinds; implementation prompt 072. Full review with reasoning: the private store, `reviews/2026-09-04-calculators-design-review.md`.
**Date:** 2026-09-04
**Scope:** the "calculators" direction — a server-side catalog of configurable,
pure transformation functions exposed (a) as a pipeline-level block that derives
new execution-Context keys from declared parameters, and (b) as a new `CALC`
node type that applies per-row/column transformations to staged data. Both are
authored declaratively (JSON), so the same surface serves UI configuration and
agent (MCP) authoring.

**Authority note:** this document records the *design decisions*. The normative
contract text lands as amendments to `docs/pipeline-contract.md`, `docs/enums.md`,
`docs/dag-executor.md`, and `docs/mcp-server.md` at implementation time — **in the
same commit as the corresponding code constants**, because the enum table and the
§12/§13 error-code catalog are drift-tested on main (standing MISTAKES.md rule).
Error codes below are PROPOSED until they enter pipeline-contract §13. The
calculator catalog (Appendix A) gets its own drift guard (`CalculatorRegistrySpecDriftTest`)
the moment it is implemented, mirroring `NodeTypeSpecDriftTest`.

---

## 0. Ratified shape (owner rulings, 2026-09-04) — this section is normative; §1–§9 and Appendix A are the draft it was distilled from

**R-CALC = (a), with the owner's refinements.** Context calculators only; **no `CALC` row
engine** (SQL and library templates do row transforms — see §0.7); **MCP-only authoring, no
editor UI** (R10 stands; the picker is a roadmap item under "UI authoring"). Rejection reasons
for the row engine are in the private review (`reviews/2026-09-04-calculators-design-review.md`).

### 0.1 Org values are configuration — `datapipelines.org.*`

Organisation facts live in `application.yml`, validated at boot like every other key
(`ConfigValidator`, one new check), documented in configuration.md §7:

```yaml
datapipelines:
  org:
    currency:
      name: ${DATAPIPELINES_ORG_CURRENCY_NAME:Dollar}
      symbol: ${DATAPIPELINES_ORG_CURRENCY_SYMBOL:$}
    fiscal-start-date: ${DATAPIPELINES_ORG_FISCAL_START_DATE:01-01}   # MM-DD; month names refused
    week-start: ${DATAPIPELINES_ORG_WEEK_START:monday}                  # monday | sunday
    timezone: ${DATAPIPELINES_ORG_TIMEZONE:UTC}                          # IANA id
```

Restart-to-change is accepted: these change once a decade. A bad value stops the server.

### 0.2 Everything is Context — one namespace, tiered precedence

Every value a node can bind is a typed Context key (`[a-z_][a-z0-9_]*`, §6.1). Sources, lowest
precedence first:

1. **org config** — yml path minus prefix, dots and dashes → `_`: `org_currency_name`,
   `org_currency_symbol`, `org_fiscal_start_date` (DATE-like `MM-DD` string typed `STRING`; the
   calculator kinds parse it), `org_week_start`, `org_timezone`;
2. **platform keys** — always present: `current_date` (DATE, in `org_timezone`),
   `current_timestamp` (TIMESTAMP), `execution_id` (STRING);
3. **pipeline `parameters`** — declaring a key that an org value also provides IS the
   override, visible in the body;
4. **execute-time inputs** — for declared parameters only (unchanged);
5. **calculator outputs** — may shadow org/platform keys; **never** a declared parameter
   (D6 stays: save-time `calculator_output_collision`).

Binding rule unchanged (§12.6): `:key` yes, `${key}` refused for every tier. Org keys are
never secrets.

### 0.3 Calculators are NODES — `type: "CALCULATOR"`

```json
{ "id": "fiscal_q", "type": "CALCULATOR",
  "kind": "fiscal_quarter",
  "inputs": { "fiscal_start": "$org_fiscal_start_date", "date": "$current_date" },
  "context_key": "run_fiscal_quarter",
  "depends_on": [] }
```

- New `NodeType.CALCULATOR` (enums.md §2 — `EXPRESSION`/`HTTP` stay reserved). No
  `template`, `source`, or §4.7 `output` block: the node writes ONE typed value to the Context
  under `context_key` (deliberately not named `output`).
- **`$name` is a reference** to a Context key; any other string/number/boolean/array is a
  literal typed against the kind's input. `"fiscal_start": "09-15"` is a per-pipeline override
  without touching config.
- **Sequencing is topology.** A `$ref` to another calculator's `context_key`, and a SQL node
  binding `:that_key`, are valid only if the referencing node `depends_on` the producer
  (directly or transitively) — else save-time `calculator_input_unordered`. One writer per
  `context_key` per pipeline. The draft's pipeline-level `calculators` array (§4) and D5's
  array order are withdrawn.
- Runtime: executes at its DAG position, records its value in the run's Context snapshot,
  reports through SSE/history like any node; failure = the 057 failure record.
- Purity (D3) unchanged: kinds read the Context and their inputs only. Variadic inputs (C1)
  use a JSON array.

### 0.4 The catalog — context kinds only (≈20), `modules/calculators`

A new module with **no** dependency on `datasources`, `dag` or `web` (C12; purity by build
file). Kinds, each `inputs → output`: `quarter_of_year`, `fiscal_year`, `fiscal_quarter`,
`period_start`, `period_end`, `prior_period`, `date_trunc`, `iso_week`, `iso_year`,
`day_of_week`, `days_in_month`, `date_diff`, `add_days`, `add_months`, `add_business_days`
(weekend + holiday list as literal inputs), `date_parse`, `date_format`, `tz_shift`, `round`,
`percent_change`, `coalesce` (variadic), `if_null`, `map`. Config-free where the draft had
config: inputs carry it (a kind is a function, not a form). Grammars are contract (C10):
`DateTimeFormatter` patterns, IANA zones. Drift guard `CalculatorRegistrySpecDriftTest`
against the catalog doc from day one; additive-only (D4).

### 0.5 Recorded, refused, and visible

- **Snapshot:** `pipeline_executions.parameters_json` persists the FULL resolved Context the
  nodes saw — org keys, platform keys, parameters, inputs, calculator outputs (C5 generalised).
- **Promotion:** the receiver's import dry-render refuses a pipeline that binds an `org_*` key
  the target deployment's yml does not define (`pipeline.import.context_key_missing`); never a
  silent default in prod.
- **Agents:** `calculators_list` / `calculators_get` (READ scope) return kinds with typed
  input/output schemas; `pipelines_get` returns CALCULATOR nodes as authored; `executions_get`
  shows each calculator node's computed value. `SKILL.md` documents the node, `$` references,
  the org/platform keys, and that row-level transforms are SQL (library macros), not
  calculators.

### 0.6 Editor — read-only rendering only (R10)

The graph must render a CALCULATOR node as a card (badge + glyph per the 059 contract,
`kind → context_key` as its facts line); the inspector shows kind, inputs with resolved
references, `context_key`, and the computed value after a run. **No picker, no form, no
editing.** The schema-driven picker (D9) is the first item under UI authoring on the roadmap.

### 0.7 Deferred, by name

- **Row calculators / `CALC` node** — not planned; rows are transformed in SQL on the engine
  or in tempdb, reused through library templates (templates.md §6).
- **`output.target = "context"` for DQL nodes** — a scalar query (exactly one row × one column
  at runtime, else `pipeline.node.scalar_shape_violation`; typed via the type system; one
  writer per key; topology-ordered like calculators) lifts a value into the Context so
  `scalar node → calculator → SQL` chains work. Modelled as a fourth output target, not a
  boolean. Future.
- **Editor picker / calculator authoring UI** — with UI authoring (R10).
- **Standard macro library** shipped with the demo (`lib/dates`, `lib/math`) — SQL-side reuse;
  a sample-data round, not this one.

---

## 1. Summary

Repetitive data-shaping code — "compute the quarter for `run_date`", "parse this
number column", "percent change vs last month" — is rewritten per pipeline, per
engine dialect, forever. Calculators replace it with a **declared, validated,
dialect-agnostic catalog** of pure functions:

- **Context calculators** (pipeline-level `calculators` block) read declared
  parameters, write derived keys back into the execution Context, and become
  template-bindable exactly like parameters (`:run_quarter`). This lands in the
  seam pipeline-contract §7.1 step 5 already reserves, and closes the ROADMAP
  "Calculators" entry.
- **Row calculators** (new `CALC` node type) read a table on `source`, apply a
  list of column mappings, and land the result per the standard `output` block
  (tempdb / caller / datasource). This is the safe, config-only sibling of the
  ROADMAP's `EXPRESSION` node type, which stays reserved for a future free-form
  expression language.

Rationale: the two layers are different shapes of the same value. Context
calculators kill parameter-derivation boilerplate (`run_date` → `quarter`,
fiscal anchors, comparison dates); row calculators kill per-dialect SQL for
transforms (`DATE_TRUNC` vs `TRUNC` vs `strftime` — the catalog erases the
dialect entirely). Keeping both declarative (a kind + typed inputs + JSON config,
no free-form grammar) means every use is validatable at save time and renderable
as a plain form in the UI.

## 2. Decisions record

| # | Fork | Decision | Rationale |
|---|---|---|---|
| D1 | Layer coverage | Both: pipeline-level `calculators` block + `CALC` NodeType | The motivating example is parameter derivation; percent-change-like transforms need data. ROADMAP already tracks both seams ("Calculators" and non-SQL node types); this design satisfies both without inventing a third. |
| D2 | Row-layer naming | New `NodeType` value `CALC`; `EXPRESSION` stays reserved | `EXPRESSION` (enums.md §2 "Reserved for future") implies a free-form expression language — grammar, security review, sandboxing. `CALC` is declarative and fully validated; no silent repurposing of a reserved enum value. |
| D3 | Purity | Pure functions only: config + inputs → outputs. No DB/network/registry reads. Determinism documented per kind. | Testability and replayability; no execution-time dependencies; lookup-requiring cases (FX rates, holiday tables, postal maps) stay SQL joins. UUID/hash kinds are pure but non-deterministic — that is a per-kind documented property, not a purity violation. |
| D4 | Registry governance | Server-side code registry keyed by `kind`; additive-only; no per-kind versioning | A kind's behavior change is a breaking change to every pipeline using it; the escape hatch is a new kind name. Backward-compatible config extensions allowed. Mirrors enum-catalog governance; the catalog doc is drift-guarded. |
| D5 | Context chaining | Left-to-right evaluation; each calculator may reference declared parameters and *earlier* calculators' outputs only | Acyclic by construction — no topo-sort machinery, cycles are unrepresentable (a forward reference is an unknown-input error). |
| D6 | Template visibility | Calculator outputs join the parameter declaration space; templates bind them as `:name` values; `${}` interpolation of them refused at save time | Same value-vs-structure rule as declared parameters (pipeline-contract §7.4/§12.6). Save-time dry-render knows the derived keys because calculators are declared in the pipeline JSON. |
| D7 | Row failure policy | Fail-fast node in v1; no skip-row | Strict-on-ingress philosophy (§6.3's strict coercion): one malformed row should not be silently dropped. Skip-row needs error-count config and observability — deferred. |
| D8 | Type checking | Static where input schemas are resolvable at save time (datasource schema metadata, tempdb staging schemas); runtime backstop always | Save-time type errors are 400s before any execution; the runtime check covers schemas unknown at save time (e.g., tables created by DDL nodes). |
| D9 | Config transport | Per-kind JSON config schema is the single grammar for REST, MCP, and UI forms | Schema-driven forms render from the registry — no second config grammar for the browser. |

## 3. Catalog & registry contract

### 3.1 Registry entry shape

Every calculator is a code-defined registry entry:

```json
{
  "kind": "quarter_of_year",
  "display_name": "Quarter of year",
  "layer": "context" | "row" | "both",
  "inputs":  [{"name": "date", "type": "DATE", "required": true}],
  "output":  {"type": "STRING"},
  "config_schema": { "<JSON schema for the per-kind config object>" }
}
```

- `kind` — `[a-z_][a-z0-9_]*`, unique, never renamed or reused (D4).
- `inputs`/`output` — canonical logical types (Type System §3), not per-dialect
  SQL types. The catalog is dialect-agnostic; the executor's type mapper converts.
- `config_schema` — JSON schema for the optional config object; absent = no config.
- *(review 2026-09-04, C1)* `inputs` may carry `"variadic": true` on its LAST entry; a variadic
  input is supplied as a JSON **array** of values/references in the pipeline JSON (`coalesce`,
  `surrogate_key`, `natural_key` need this — the name→value map alone cannot express them).
- `layer` — where the kind may be used. `both` kinds (date parsing, rounding,
  hashing) work identically in either layer.

### 3.2 Purity & determinism

- No file, network, datasource, or registry reads at evaluation time. Config must
  carry everything a kind needs (e.g., the holiday list for `add_business_days`
  is a config array — D3).
- Deterministic by default. Non-deterministic kinds (`uuid_v4`, `uuid_v7`,
  salted hashes) document it; pipeline versioning's `body_hash` is unaffected
  because calculators are part of the body, not their outputs.

### 3.3 Registry growth policy

Adding a kind is additive and cheap; changing one is forbidden (D4). The catalog
is the drift-guarded doc (Appendix A) + the code registry; both must agree, and
the guard must fail the build on disagreement.

## 4. Context calculators

### 4.1 Pipeline-level block

A new optional top-level author-owned field `calculators`, an ordered array:

```json
"parameters": { "run_date": {"type": "DATE", "required": true} },
"calculators": [
  {
    "id": "qtr",
    "kind": "quarter_of_year",
    "inputs": {"date": "run_date"},
    "config": {},
    "output": "run_quarter"
  },
  {
    "id": "qend",
    "kind": "quarter_end",
    "inputs": {"date": "run_date"},
    "output": "run_quarter_end"
  }
]
```

Field rules (amend pipeline-contract §6-family):

- `id` — `[a-z_][a-z0-9_]*`, unique among calculators; names error details.
- `kind` — a registry kind with `layer` `context` or `both`.
- `inputs` — map of the kind's input names to values. Each value is either a
  declared parameter name (the value is taken from the Context) or a typed
  literal obeying the kind input's wire encoding. No expressions.
- `config` — object validated against `config_schema`; omitted = defaults.
- `output` — the Context key this calculator writes. Must obey the parameter
  naming rules ([pipeline-contract §6.1]); must not collide with a declared
  parameter name or another calculator's output.

### 4.2 Semantics

- Evaluation order is array order, left-to-right (D5). `inputs` values may
  reference any declared parameter and any *earlier* calculator's `output`;
  referencing a later output or an unknown name is a save-time error.
- Outputs are typed Kotlin values written to the Context after parameter
  defaulting and before node execution — the exact hook pipeline-contract §7.1
  step 5 describes. They never appear in the pipeline JSON as declared
  parameters; the declaration space for template validation is `parameters` ∪
  calculator outputs (D6).
- Templates reference outputs as `:name` bind parameters; `${}` interpolation
  of a calculator output is refused at save time (same rule as declared
  parameters, §12.6).
- *(review 2026-09-04, C4)* Outputs are **not settable at execute time**: an execution input
  naming a calculator output is refused (`parameter_unknown`, or a dedicated
  `calculator_output_not_settable`) — otherwise a caller overrides a derived value.
- *(review 2026-09-04, C5)* Derived values are **recorded with the execution** beside the
  resolved parameters (`pipeline_executions.parameters_json` already persists those);
  non-deterministic kinds (`uuid_*`) make this mandatory for replay and lineage.
- *(review 2026-09-04, C6)* `PIPELINE` nodes: a child sees only what its own `parameters`
  declare; a parent maps a calculator output into a child parameter like any Context value.
- Input type mismatch is checked at save time (literal inputs) and at runtime
  (parameter values — a runtime parameter's type is already validated against
  its declaration, so this is a backstop).

## 5. `CALC` node (row calculators)

### 5.1 Shape

```json
{
  "id": "calc_qtr",
  "description": "Add order_quarter and order_month columns.",
  "type": "CALC",
  "source": "tempdb",
  "input": {"table": "stg_orders"},
  "mappings": [
    {"column": "order_quarter", "kind": "quarter_of_year",
     "inputs": {"date": "order_date"}, "config": {}},
    {"column": "order_month", "kind": "date_trunc",
     "inputs": {"date": "order_date"}, "config": {"unit": "month"}}
  ],
  "output": {"target": "tempdb", "table": "stg_orders_enriched"},
  "depends_on": ["raw_orders"]
}
```

Field rules (amend pipeline-contract §4):

- `type: "CALC"` — new `NodeType` enum constant (enums.md §2). `template` is
  **forbidden** on CALC nodes (no SQL is authored); `source`, `depends_on`, and
  `output` follow the existing §4 rules (output block identical to DQL's §4.7).
- `input` — required: `{table: string}` naming the table read on `source`
  (`tempdb` staging or an external datasource table). Must reference a table the
  DAG makes available at that point — the same availability rule downstream SQL
  gets, enforced by topology.
- `mappings` — ordered, non-empty array. Each mapping adds or overrides one
  output `column`: `kind` (registry kind with `layer` `row` or `both`), `inputs`
  (input-name → column name in the input table, or a typed literal), `config`.
- Mappings apply left-to-right: a later mapping may reference an earlier
  mapping's output column (row-local chaining, D5 in row form).
- Input rows are otherwise copied verbatim; row order and all unmapped columns
  are preserved.

### 5.2 Execution semantics

*(review 2026-09-04, C8/C9 — apply only if R-CALC keeps a `CALC` node)* v1 restricts `source`
to `tempdb` (an external-datasource `source` pulls a whole table through the application).
Whole-column kinds (§5.3) need a memory story before any build: a row cap with a typed
refusal, or R-CALC option (b) push-down — under (b) both problems disappear.

The executor reads `input.table` from `source`, streams rows through the mapping
list (each mapping = one registry evaluation per row), and lands the enriched
ResultSet per `output` — reusing the existing staging/write-back/caller paths
exactly as a DQL node's ResultSet is handled. No SQL is generated, so the node
is dialect- and engine-agnostic (DuckDB, H2, PG, all of them).

### 5.3 Window kinds

Ranking, lag/lead, moving averages, and column statistics need row order or a
window. These kinds require an `order_by` in `config`, validated by
`config_schema`:

```json
{"column": "rank_by_revenue", "kind": "rank",
 "inputs": {"value": "revenue"},
 "config": {"order_by": {"column": "revenue", "direction": "desc"}}}
```

Direction enum: `asc` | `desc` (new enum, enums.md catalog). Column-statistic
kinds (`mean`, `median`, `percentile`, `z_score`, regression) apply their whole-
column result to every row (window semantics, no partition in v1).

### 5.4 Type checking

- Save time: input column types resolved from the datasource's schema metadata
  or the staging engine's schema map; unmapped/mismatched → 400. Literal inputs
  checked directly against the kind's input types.
- Runtime: per-row backstop (D8) — a schema drift or DDL-created table with
  unknown types fails the node with a typed error, never a silent cast.

## 6. Validation & error codes (PROPOSED)

Save-time validation (amend pipeline-contract §12; all 400s):

| Proposed code | Check |
|---|---|
| `pipeline.validation.calculator_unknown` | `kind` exists in the registry and its `layer` permits this use |
| `pipeline.validation.calculator_config_invalid` | `config` satisfies the kind's `config_schema` |
| `pipeline.validation.calculator_input_unknown` | Every `inputs` reference (param, earlier output, or input-table column) exists |
| `pipeline.validation.calculator_input_type_mismatch` | Resolvable input types match the kind's declared input types; literals obey wire encoding |
| `pipeline.validation.calculator_output_collision` | `output` key collides with a declared parameter or another output; `column` collides with a mapping in the same node and existing input columns handled per §5.1 override rule |
| `pipeline.validation.calculator_output_name_invalid` | `output`/`column` obey naming rules |
| `pipeline.validation.calculator_window_config_required` | Window kinds carry the required `order_by` |
| `pipeline.validation.calc_node_has_template` | CALC node carries no `template` |
| `pipeline.validation.calc_node_input_missing` | CALC node has the required `input` block |
| `pipeline.validation.calculator_pattern_invalid` | *(review 2026-09-04, C3)* a config-carried pattern (`regex_*`, `mask_format`, `json_extract` path) fails to compile, exceeds the length cap, or is rejected by the bounded matcher |

Also amended: §12.4's `type_invalid` row (enum gains `CALC`).

*(review 2026-09-04, C3 — security)* Patterns in config are authored by any `author`-scoped
principal and are a ReDoS surface. Regex evaluation runs under a per-evaluation time budget
(or a linear-time engine) and a pattern length cap; `json_extract` uses a bounded path
grammar. *(C10 — grammars are contract)* `date_parse`/`date_format` `format` = Java
`DateTimeFormatter` patterns; `tz_shift` zones = IANA ids; `regex_*` = Java regex (bounded).
The drift guard pins the grammar names.

*(review 2026-09-04, C11)* Runtime codes surface through the 057 failure record (SSE
`node_failed` + `executions_get`), not as HTTP responses; the 500 column follows §13.4's
convention only.

Runtime (amend §13.4, `pipeline.node.*` format):

| Proposed code | Status | Meaning |
|---|---|---|
| `pipeline.node.calculator_failed` | 500 | A context or row calculator evaluation failed; detail carries the calculator `id`/`column`, `kind`, and the underlying reason |
| `pipeline.node.calculator_input_table_not_found` | 500 | A CALC node's `input.table` is not available on `source` at execution time |
| `pipeline.node.calculator_type_error` | 500 | Runtime type backstop fired (D8) — a save-time-unresolvable schema turned out incompatible |

## 7. Surfaces

### 7.1 REST / MCP

- `pipelines_create` / `pipelines_update` accept the `calculators` block and
  `CALC` nodes through the existing body path; §6 validations apply on save; the
  portable body (seven author-owned fields) gains `calculators` as an eighth —
  exported, imported, and included in `body_hash` like the others.
- **New MCP tools (READ scope):** `calculators_list` — the full catalog
  (kind, layer, inputs/output types, config schema, display metadata) for
  agents to author against; `calculators_get` — a single kind's entry. No new
  scopes; listing is a read like `templates_list`.
- Execution: no new endpoints. `CALC` node runs appear in history/stats like any
  node; SSE node events unchanged.
- The agent-facing datapipelines skill must land with the code (versioning.md
  §12 standing rule): catalog lookup, the block/node JSON shapes, and the §6
  validation codes.

### 7.2 Editor UI

*(review 2026-09-04, C7 — rewritten: the previous text was authoring UI, which R10 /
pipeline-editor §11.1 defer; the editor is read-only in v1.)*

- **Read-only rendering in v1.** The pipeline editor's settings sidebar renders the declared
  `calculators` block beside Parameters (id · kind · inputs · output, in evaluation order,
  with a line saying order IS evaluation order). If a `CALC` node exists (R-CALC), its card
  and inspector render the input table and the mappings table read-only.
- **Authoring is MCP/REST** (`pipelines_create`/`pipelines_update`), as for every other
  pipeline field.
- **Schema-driven forms** (a server-rendered partial generator walking `config_schema`, one
  grammar for UI + MCP + REST) are the design for the day UI authoring lands (ROADMAP §2,
  "Pipeline CRUD in the UI"); they are not built in v1 and nothing here depends on them.

### 7.3 Documentation

The catalog (Appendix A) becomes a user-facing doc in the product documentation
suite once implemented; drift-guarded against the registry from day one.

## 8. Testing

- **Registry drift test:** `CalculatorRegistrySpecDriftTest` — every kind in the
  code registry appears in the catalog doc, and vice versa (the `NodeTypeSpecDriftTest`
  pattern). Falsified by adding/removing a kind on either side.
- *(review 2026-09-04, C12)* The registry lives in a new `modules/calculators` with **no**
  dependency on `datasources`, `dag` or `web`; the purity guard is then a build-file
  assertion in the `ArchitectureGuardTest` shape, not an allowlist audit.
- **Purity guard:** a test that no calculator kind's evaluation path touches
  datasource/network/registry ports (the pure-function contract, D3) — enforced
  by structure (a sealed evaluation interface with no I/O ports) plus an
  allowlist audit of the registry module's dependencies.
- **Per-kind unit tests:** typed inputs → typed outputs, boundary values, config
  validation via `config_schema`; one falsification per kind family (e.g.,
  `fiscal_quarter` with a negative fiscal-start-month config must fail schema).
- **Context calculators:** chaining order, forward-reference refusal, output
  collision, save-time dry-render sees derived keys (template referencing
  `:run_quarter` validates), runtime type backstop.
- **CALC node:** all three output targets, row/order preservation, mapping
  chaining within a node, window kinds with/without `order_by`, input-table
  availability by topology, fail-fast on row error (D7).
- **integration-tests:** one E2E per layer — a context calculator feeding a
  template bind + a CALC node enriching staged rows into a caller result;
  plus the docs-audit and FULL-build gates on every catalog doc amendment
  (standing rule).

## 9. Explicitly out of scope (deferred)

- Free-form `EXPRESSION` nodes and the `HTTP` node type — untouched reserved
  enum values.
- Skip-row / error-count row policies (D7).
- `partition_by` for window kinds.
- Lookup-backed calculators (FX, holiday tables, postal maps) — SQL territory.
- A standalone calculator library/browse screen.
- Calculator-specific observability beyond standard node stats.

---

## Appendix A: Catalog v1 (proposed)

Types use canonical names (I = INTEGER, BI = BIGINTEGER, D = DECIMAL, BD =
BIGDECIMAL, B = BOOLEAN, S = STRING, DATE, TIME, TS = TIMESTAMP). Layer: C =
context, R = row, BOTH. All kinds are additive; names are proposals for review.

### A.1 Calendar & time

| kind | Layer | Inputs → Output | Config |
|---|---|---|---|
| `quarter_of_year` | BOTH | DATE → S | (none) |
| `fiscal_year` | BOTH | DATE → I | `fiscal_start_month` (1–12, default 1) |
| `fiscal_quarter` | BOTH | DATE → I | `fiscal_start_month` |
| `period_start` / `period_end` | BOTH | DATE → DATE | `unit`: `week`\|`month`\|`quarter`\|`year`; `mode`: `calendar`\|`fiscal`; `fiscal_start_month` (mode=fiscal only) |
| `prior_period` | C | DATE → DATE | `unit` (as above); `offset` (default 1); `fiscal_start_month` |
| `date_trunc` | BOTH | DATE\|TS → DATE\|TS | `unit`: `day`\|`week`\|`month`\|`quarter`\|`year`\|`hour`\|`minute` |
| `iso_week` / `iso_year` | BOTH | DATE → I | (none) |
| `day_of_week` / `day_of_year` | BOTH | DATE → I | `first_day`: `monday`\|`sunday` (day_of_week only) |
| `days_in_month` / `days_in_quarter` / `days_in_year` | BOTH | DATE → I | (none) |
| `date_diff` | BOTH | DATE, DATE → I | `unit`: `day`\|`week`\|`month`\|`quarter`\|`year` |
| `add_days` / `add_months` / `add_years` | BOTH | DATE, I → DATE | (none) |
| `add_business_days` | C | DATE, I → DATE | `weekend_days` (array, default sat/sun); `holidays` (array of ISO dates, config-carried — D3) |
| `business_days_between` | C | DATE, DATE → I | `weekend_days`; `holidays` |
| `age_years` / `age_months` | BOTH | DATE, DATE → I | (none) |
| `date_parse` | BOTH | S → DATE\|TS | `format` (required); `output`: `date`\|`timestamp` |
| `date_format` | BOTH | DATE\|TS → S | `format` (required) |
| `tz_shift` | BOTH | TS → TS | `from_zone`, `to_zone` (IANA, required) |
| `epoch_to_date` / `date_to_epoch` | BOTH | BI ↔ DATE\|TS | `unit`: `seconds`\|`millis` |

### A.2 Numeric & financial

| kind | Layer | Inputs → Output | Config |
|---|---|---|---|
| `round` | BOTH | D\|BD → D\|BD | `places` (default 0); `mode`: `half_up`\|`half_even`\|`floor`\|`ceil` |
| `clamp` | BOTH | D\|BD, D\|BD, D\|BD → D\|BD | (none) — inputs are value, min, max |
| `floor` / `ceil` / `abs` | BOTH | D\|BD → D\|BD | (none) |
| `percent_change` | R | D\|BD, D\|BD → D\|BD | (none) — current, previous |
| `percent_of_total` | R | D\|BD → D\|BD | (none) — window over column |
| `cagr` | C | D\|BD, D\|BD, D → D\|BD | (none) — begin, end, periods |
| `pmt` / `ipmt` / `ppmt` | C | D\|BD, D\|BD, I → D\|BD | (none) — rate, principal, periods |
| `compound_value` | C | D\|BD, D\|BD, I → D\|BD | (none) — principal, rate, periods |
| `depreciation` | C | D\|BD, D\|BD, I, I → D\|BD | `method`: `straight_line`\|`declining_balance`\|`sum_of_years` |
| `prorate` | BOTH | D\|BD, DATE, DATE → D\|BD | `unit`: `day`\|`month` (amount × fraction of period elapsed) |
| `moving_average` | R | D\|BD → D\|BD | `window` (required), `order_by` (required) |
| `running_total` | R | D\|BD → D\|BD | `order_by` (required) |
| `rank` / `dense_rank` / `row_number` | R | any → I | `order_by` (required), `direction` |
| `lag` / `lead` | R | any → any | `offset` (default 1), `order_by` (required), `default` literal |
| `quantile_bucket` | R | D\|BD → I | `buckets` (required); `mode`: `equal_width`\|`equal_count`; `order_by` |
| `mean` / `median` / `percentile` / `std_dev` / `variance` / `z_score` | R | D\|BD → D\|BD | `percentile` value (percentile only) — whole-column window |
| `linreg_slope` / `linreg_intercept` / `linreg_r2` | R | D\|BD, D\|BD → D\|BD | (none) — y, x; whole-column window |
| `unit_convert` | BOTH | D\|BD → D\|BD | `from`, `to` from the fixed units table (temperature/distance/weight/data-size) |
| `base_convert` | BOTH | S → S | `from_base`, `to_base` (2–36) |

### A.3 String & parsing

| kind | Layer | Inputs → Output | Config |
|---|---|---|---|
| `to_lower` / `to_upper` / `to_title` | BOTH | S → S | (none) |
| `to_snake_case` / `to_camel_case` | BOTH | S → S | (none) |
| `slugify` | BOTH | S → S | `separator` (default `-`), `max_length` |
| `trim` | BOTH | S → S | `side`: `both`\|`leading`\|`trailing` |
| `pad` | BOTH | S, I → S | `side`, `char` (default space) |
| `substring` | BOTH | S, I, I → S | (none) — value, start, length |
| `split` | BOTH | S, I → S | `delimiter` (required) — value, element index |
| `regex_extract` | BOTH | S → S | `pattern` (required), `group` (default 0) |
| `regex_replace` | BOTH | S → S | `pattern`, `replacement` (required) |
| `mask_format` | BOTH | S → S | `mask` (required — phone/SSN/IBAN templates) |
| `parse_number` | BOTH | S → D\|BD | `locale` (default en_US), `thousands_sep`, `decimal_sep` |
| `coalesce` | BOTH | any… → any | (none) — variadic, first non-null |
| `hash_md5` / `hash_sha256` | BOTH | S → S | (none) — *(review 2026-09-04, C2)* no `salt`: a salt in pipeline JSON is a secret in the body (exported, promoted, hashed); for surrogate keys only, never for security |
| `base64_encode` / `base64_decode` | BOTH | S\|BINARY ↔ S | (none) |
| `url_encode` / `url_decode` | BOTH | S ↔ S | (none) |
| `levenshtein` | R | S, S → I | `case_sensitive` (default true) |
| `json_extract` | BOTH | S → S | `path` (required jsonpath) — pure, over a string input |

### A.4 Identity & keys

| kind | Layer | Inputs → Output | Config |
|---|---|---|---|
| `uuid_v4` / `uuid_v7` | C | (none) → S | (none) — documented non-deterministic |
| `surrogate_key` | R | any… → S | `hash`: `md5`\|`sha256`; `separator` — variadic over business-key columns |
| `natural_key` | R | any… → S | `separator` (default `\|`) |
| `sequence` | R | (none) → BI | `start` (default 1), `step` (default 1), `order_by` |

### A.5 Conditional & mapping (config-carried, D3)

| kind | Layer | Inputs → Output | Config |
|---|---|---|---|
| `map` | BOTH | any → any | `values`: array of `{from, to}` (in config, not a lookup table) |
| `range_classify` | BOTH | D\|BD → S | `ranges`: ordered array of `{upper_bound, label}` |
| `if_null` | BOTH | any → any | `default` literal (required) |
| `bool_and` / `bool_or` / `bool_not` | BOTH | B, B → B (not: B → B) | (none) |

### A.6 Geography (pure math)

| kind | Layer | Inputs → Output | Config |
|---|---|---|---|
| `haversine_distance` | BOTH | D, D, D, D → D | `unit`: `km`\|`mi` — lat1, lon1, lat2, lon2 |
| `geohash_encode` | BOTH | D, D → S | `precision` (default 9) |
| `geohash_decode` | BOTH | S → S | (none) — returns `"lat,lon"` at precision |
