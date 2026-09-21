# Parameter engine — design record (2026-09-21, draft 1 for owner review)

Owner's intent (2026-09-21): "populate input controls … used to feed its selection into
charts/dashboards as parameters. We don't have dashboards or charts yet. Build an engine for
parameters independent of anything and make it available as a decoupled offering." This record
turns the owner's design conversation of 2026-09-21 (fourteen turns, recovered from the Codex
session `~/parameters-definition-conversation.jsonl`; every ruling restated in §0) plus the four
decisions taken in the follow-up session into one buildable specification: the entity, its JSON,
the selector-SQL contract, the expression language, the evaluate protocol, the modules, the
surfaces, the roles, the codes, the config keys, the guards and the lane split.

Every file, symbol, count and rule cited here was read from the tree on 2026-09-21 at `b34bdddf`
(main; lane 180 then landed `0dd342f9` — auth only — and touched none of the files cited). Where this record deviates from a ruling made in the conversation, the deviation is
named in §0 with its reason; nothing else is new.

---

## 0. Decisions

Rulings the owner made in conversation (P1–P17) and the four taken in the follow-up (P18–P21).
Two deviations from the conversation's wording (P8's third column, P13's invalidation outcome)
are marked **deviation** and carry their reason.

| # | Decision |
|---|---|
| P1 | The engine is a **standalone parameter-definition engine**, decoupled from dashboards, charts and HTML. Agents create *definitions* through MCP; the server stores and serves them as generic JSON; a client runtime renders them however it likes (a multi-value parameter may become checkboxes, a multi-select, pills). |
| P2 | The durable contract describes **selection semantics, not widgets**: value type × cardinality × value source × constraints. There is no per-widget catalogue. A `format` hint (P20) is presentation only and never changes meaning. |
| P3 | Parameters **depend on zero or more parameters**; a change anywhere makes the client submit and the server re-evaluate. **The client is a pure renderer**: it runs no SQL and no dependency logic. |
| P4 | Three concepts, kept apart: the **definition** (durable, MCP-authored), the **runtime state** (server-evaluated per submission), the **selection payload** (the values currently chosen). |
| P5 | The server owns options, defaults, hidden/disabled state and validation; it re-validates every submitted value against the newly computed options on every evaluation. |
| P6 | Every selector option — constant or SQL-backed — is `{value, display_value, is_default}`. `display_value` is presentation text; `value` is the canonical typed value; **the server never infers one from the other**. |
| P7 | No option marked default ⇒ the **first option in order** is the default; more than one marked ⇒ refused. Selector SQL must carry a deterministic `ORDER BY`. |
| P8 | The three columns of selector SQL are `value`, `display_value`, `is_default`. **Deviation:** the conversation named the third `default`; `DEFAULT` is a reserved word in PostgreSQL, MySQL, SQL Server and Oracle and would force quoting in every dialect — `is_default` is the alias. |
| P9 | `default_value` on a parameter is its resolved default: one typed value (SINGLE), an array (MULTI), a scalar for an input. It is separate from `options[].is_default`. |
| P10 | **Reuse the type system.** A parameter declares one of the 10 declarable `LogicalType`s (`NULL` excluded); wire encoding is type-system §3.1; coercion is the existing strict `ParameterCoercion`, moved out of `pipeline-contract` so that pipelines and parameters share ONE implementation (§2.3). |
| P11 | Type compatibility of a selector's `value` column: **same type ⇒ accept; explicitly listed lossless widening ⇒ accept; anything lossy, ambiguous or value-inferred ⇒ refuse.** Decided from result-set METADATA, never by inspecting rows; an author changes the outcome with an explicit `CAST`. |
| P12 | `hidden_expression` / `disabled_expression` are server-evaluated boolean predicates stored as a **JSON expression AST** (§7). SpEL is rejected (persisted, MCP-authored expressions; restriction is configuration-and-review work; coercion/null/truthiness surprises; text-based dependency extraction; Spring-version coupling). GraalJS is rejected for this core feature (arbitrary code, isolation, discovery, implicit coercion, non-determinism, attack surface). A textual syntax may compile to the same AST later. |
| P13 | Dependencies form ONE directed acyclic graph: `depends_on` + the binds of selector SQL + the `ref`s of expressions. Cycles (direct or indirect) and dangling references are refused at save. **Deviation** (invalidation): a SELECT whose submitted value is no longer among the recomputed options is **reset to its default and flagged `reset: true`** rather than reported as an error — a legitimate parent change causes this on every cascade, and a form full of errors the user did not cause is wrong. Errors are reserved for what the client did wrong (§5.4). |
| P14 | Clients get both views; **`dependents` is the only functional field** ("is a change to this parameter a submit candidate?"); `depends_on` is informational (highlighting, diagrams). |
| P15 | The client submits **the current value of every parameter in the set** — never the changed selector alone, never its subtree. The server evaluates the whole graph in topological order and returns the whole refreshed state. |
| P16 | No graph library. **Reuse the house `Dag<T>`** and `kotlinx.coroutines`, following `PipelineExecutor`'s pattern (schedule every node, each awaits its parents; never wave scheduling). |
| P17 | **Option B for placement:** the generic `Dag` primitive moves **byte-identical** into its own leaf module; the mature executor is not touched. (Owner 2026-09-21: "I don't want to touch a mature implementation which is working fine.") |
| P18 | The persisted entity is a **parameter set**, named with the folder-path grammar pipelines and templates use, **versioned exactly like templates** (draft / release / purge / discard / restore / switch / import; promotion by name). |
| P19 | Input formatting is **validation, and validation is refusal**: a value that violates a declared constraint is rejected with a per-parameter error; the server never rounds, trims or normalises what the user typed. Constraints ride on the definition so a renderer can mask input up front. |
| P20 | Roles: **read** every role (promoter through the lens); **author** (create/update/release/purge) author + admins; **evaluate** every role that may execute a pipeline (viewer, author, admins; promoter ✗); **promote** promoter + admins. Three `RestOperation` rows (§9.3). |
| P21 | Round one ships **MCP tools + REST API, no UI**. The reference renderer lands with dashboards (#10). |
| P22 | **Selector SQL is a pinned template**, never inline SQL: `source.template = {id, version}` (the node shape of pipeline-contract §4.1), rendered through `TemplateEngine`, parents bound as `:name`, `${parent}` refused at save, pins released before the set releases, and the templates used-by / delete-guard reverse scans extended to parameter sets (§6, §8.4). |

---

## 1. Scope

**In:** the `graph` and `parameters` modules (§2); the parameter-set entity and its JSON (§3);
save-time validation (§4); the evaluate protocol (§5); the selector template contract (§6); the
expression AST (§7); persistence, lifecycle, promotion and the templates reverse arrow (§8); six
MCP tools, the REST routes, the three matrix rows and audit (§9); the `parameter.*` error family
(§10); configuration keys (§11); the guards and the falsification list (§12).

**Out, by name (§13):** any UI page; dashboards (#10); binding a set to a pipeline's
`parameters`; a textual expression syntax; GraalJS; partial/patch submissions; option search,
typeahead or pagination; label localisation; per-user saved selections.

**Why now.** Dashboards (#10) need controls; a control engine that is a dashboard feature would be
rebuilt for the next consumer (a published endpoint's parameter form, a report). Building it as
its own aggregate with its own lifecycle means the dashboard work is a renderer and a binding, not
an engine.

---

## 2. Modules

### 2.1 `modules/graph` — the DAG primitive, moved (P17)

`modules/dag/src/main/kotlin/co/datapipelines/dag/Dag.kt` (230 lines: `Dag<T>`, `DagBuilder<T>`,
the private iterative `CycleSearch`) has **zero imports** — Kotlin stdlib only — and is imported by
exactly two production files, both in `modules/dag` (`executor/ExecutableNode.kt`,
`executor/PipelineExecutor.kt`). `modules/dag` itself is the executor: it lists
`pipeline-contract`, `templates`, `datasources`, `staging`, Redis and JDBC, so nothing below
`application` can depend on it without inheriting that set.

The move:

- `Dag.kt` and `dag/DagTest.kt` (184 lines, kotest + junit only) move to `modules/graph` under
  the **same package `co.datapipelines.dag`**. `git diff -M --stat` on the commit must show two
  100% renames and nothing else in those files; the two importing files in the executor do not
  change.
- `modules/graph/build.gradle.kts`: the common conventions plugin, no internal dependencies, the
  house test deps.
- `modules/dag/build.gradle.kts` gains `implementation(project(":modules:graph"))`.
- `settings.gradle.kts` includes `:modules:graph` (and `:modules:parameters`).
- The KDoc's "dag-executor.md §3" citation stays; dag-executor.md §3 gains one line saying where
  the class now lives. module-structure.md §3 tree, §3.1 matrix and §4.2 table gain the row.

### 2.2 `modules/parameters` — the engine

Owns: the definition model and its Jackson binding, `ParameterSetValidator`, the expression
evaluator, `ParameterEvaluator` (the runtime), `SelectorRunner` (renders the pinned template and
runs it), `ParameterSetRepository` + lifecycle verbs, `ParameterErrorCodes`,
`ParametersProperties`. It is a single-aggregate module: nothing cross-aggregate lives in it
(the reverse-arrow scan and promotion are `application`'s, §8.4/§8.5).

### 2.3 Coercion extraction (P10)

`ParameterCoercion` is `internal object ParameterCoercion` in
`modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/ParameterCoercion.kt`;
`ParameterWireEncoder` (public) is beside it. Both move to `modules/typesystem`, package
`co.datapipelines.typesystem`, `ParameterCoercion` made public; `pipeline-contract` keeps calling
them (import changes only). `ParameterWireEncoderTest` and the coercion tests move with them.
The `Outcome.Rejected` messages are unchanged — `pipeline.execution.invalid_parameter_type`'s
wording is asserted by existing tests.

### 2.4 The layering table (module-structure.md §4.2 — edited FIRST, then the root build's `allowedInternalDependencies`)

| Module | Allowed internal dependencies (exhaustive) | change |
|---|---|---|
| `graph` | *(none)* | **new**, layer 0 beside `typesystem` and `calculators` |
| `dag` | `typesystem`, `calculators`, `pipeline-contract`, `templates`, `datasources`, `staging`, **`graph`** | + `graph` |
| `parameters` | `typesystem`, `graph`, `pipeline-contract`, `templates`, `datasources` | **new**, layer 2 beside `templates` (`pipeline-contract` only for a type it actually uses — declared explicitly, §4.2's rule) |
| `application` | … existing …, **`parameters`** | + `parameters` (promotion, the reverse arrow) |
| `mcp-server` | … existing …, **`parameters`** | + `parameters` |
| `web` | … existing …, **`parameters`**, **`graph`** only if a type is used | + `parameters` |

`verifyModuleDependencies` (root `build.gradle.kts:189`) fails the build on any edge outside the
table; `ArchitectureGuardTest` keeps `parameters` free of `co.datapipelines.web.*` /
`co.datapipelines.mcp.*` imports. `parameters` takes `spring-boot-starter-jdbc` for its
repository (module-structure §3.1 rule 1) and `kotlinx-coroutines-core` for the evaluator; no
Redis (rule 3).

---

## 3. The parameter set

### 3.1 The set

```json
{
  "name": "acme/sales/region_filters",
  "display_name": "Region filters",
  "description": "Country → state → city cascade plus a minimum order amount.",
  "parameters": [ … §3.2, in display order … ]
}
```

- `name`: the folder-path grammar of pipelines and templates, character for character — 2–10
  `/`-separated segments, each `[a-z0-9][a-z0-9_.-]{0,63}`, ≤ 200 chars (`PipelineNameGrammar`,
  `TemplateValidation` — one shared constant, not a third copy). Unique per workspace **forever**
  (versioning §3.2). No rename. `test/` is the sanctioned scratch root; a new root needs
  `confirm_new_root: true` on the agent surface (the `pipeline.validation.new_root_requires_confirmation` shape).
- `parameters` is an **ordered array** (display order is part of the definition and is what a
  renderer lays out); names are unique within the set; at most
  `datapipelines.parameters.max-parameters-per-set` entries (§11).

### 3.2 One parameter

```json
{
  "name": "state",
  "label": "State",
  "description": "States of the selected country.",
  "type": "STRING",
  "kind": "SELECT",
  "cardinality": "SINGLE",
  "required": true,
  "default_value": null,
  "source": { "template": { "id": "acme/sales/states_of_country.sql", "version": 3 },
              "datasource": "warehouse" },
  "depends_on": ["country"],
  "hidden_expression": null,
  "disabled_expression": { "op": "is_null", "arg": { "ref": "country" } },
  "constraints": null,
  "format": null
}
```

| field | rule | refusal code (§10) |
|---|---|---|
| `name` | `[a-z_][a-z0-9_]*`, 1–63 (pipeline-contract §6.1's grammar, the same constant) | `parameter.validation.name_invalid` |
| `label` | required, 1–120 chars | `parameter.validation.label_invalid` |
| `description` | optional, ≤ 2000 chars | `parameter.validation.description_too_long` |
| `type` | one of `BOOLEAN`, `INTEGER`, `BIGINTEGER`, `DECIMAL`, `BIGDECIMAL`, `STRING`, `BINARY`, `DATE`, `TIME`, `TIMESTAMP` (`LogicalType` minus `NULL`) | `parameter.validation.type_invalid` |
| `precision`, `scale` | exactly pipeline-contract §6.2 / §12.7: `precision` required for `DECIMAL`, optional (= unbounded) for `BIGDECIMAL`; `scale` required for `BIGDECIMAL` and for exact `DECIMAL` | `parameter.validation.precision_missing`, `parameter.validation.scale_missing` |
| `kind` | `INPUT` (free value) or `SELECT` (from options) | `parameter.validation.kind_invalid` |
| `cardinality` | `SINGLE` or `MULTI`; `MULTI` only with `kind: SELECT` | `parameter.validation.cardinality_invalid` |
| `required` | boolean; a `required` parameter that resolves to no value and no options is an evaluate error (§5.4), not a save error | — |
| `default_value` | wire-encoded for `type`; `SINGLE`/`INPUT` → one value, `MULTI` → array; must survive the FULL coercion (§12.7's `default_type_mismatch` rule); for `SELECT` with `constants` it must be among the option values; for `SELECT` with a template it is checked at evaluate, not save (the options depend on parents) | `parameter.validation.default_type_mismatch`, `parameter.validation.default_not_an_option` |
| `source` | required iff `kind: SELECT`; exactly one of `constants` (§3.3) or `template` + `datasource` (§3.4) | `parameter.validation.source_missing`, `parameter.validation.source_ambiguous`, `parameter.validation.source_on_input` |
| `depends_on` | parameter names in the same set; must be a **superset** of every `:bind` the selector template uses and every `ref` in the two expressions (discovery is never load-bearing — P12); no self reference; the set's graph must be acyclic | `parameter.validation.dependency_unknown`, `parameter.validation.bind_undeclared`, `parameter.validation.ref_undeclared`, `parameter.validation.dependency_cycle` |
| `hidden_expression`, `disabled_expression` | a §7 AST or `null` (= `false`) | `parameter.validation.expression_invalid` and the §7 codes |
| `constraints` | `INPUT` only (§3.5) | `parameter.validation.constraints_on_select` |
| `format` | optional, from the closed catalogue (§3.6) | `parameter.validation.format_invalid` |

### 3.3 Constants source

```json
"source": { "constants": [
  { "value": "USA", "display_value": "United States", "is_default": true },
  { "value": "CAN", "display_value": "Canada",        "is_default": false }
] }
```

`value` is wire-encoded for the parameter's `type` and coerced at save; `display_value` is a
non-empty string; `is_default` is a boolean, at most one `true` (P7); values are unique; the list
is non-empty and at most `max-options-per-selector` long. Order is the display order and the
default order.

### 3.4 Template source (P22)

```json
"source": { "template": { "id": "acme/sales/states_of_country.sql", "version": 3 },
            "datasource": "warehouse" }
```

`template` is the pipeline node's reference shape (`{id, version}`, both required; `id` is the
template's `name`, resolved in the active workspace). `datasource` is a datasource name visible
from the workspace (bound or global). The template must be `type='sql'` with a `dialect` equal to
the datasource's dialect. The SQL contract is §6.

### 3.5 Constraints (`INPUT` only; P19)

```json
"constraints": { "min": 0, "max": 1000000, "min_length": null, "max_length": null, "pattern": null }
```

| key | applies to | rule |
|---|---|---|
| `min`, `max` | `INTEGER`, `BIGINTEGER`, `DECIMAL`, `BIGDECIMAL`, `DATE`, `TIME`, `TIMESTAMP` | wire-encoded in the parameter's own type, coerced at save, inclusive; `min ≤ max` |
| `min_length`, `max_length` | `STRING`, `BINARY` (decoded bytes) | non-negative ints, `min_length ≤ max_length`; `max_length` defaults to `datapipelines.parameters.max-input-length` (§11) when absent — an unbounded string is never accepted |
| `pattern` | `STRING` | a `java.util.regex` pattern, implicitly anchored (`^…$`), ≤ 256 chars, **refused if it uses backreferences, lookahead/lookbehind, possessive or atomic groups** (a static scan — the ReDoS surface); tested against inputs bounded by `max_length` |

A constraint on a type it does not apply to is `parameter.validation.constraint_not_applicable`.
**Decimal places are the type's `scale`, not a constraint**: a `DECIMAL(12,2)` amount submitted
as `12.345` is refused at evaluate with `parameter.evaluate.constraint_violation`
(`details.reason = "scale"`), exactly as `-5` against `min: 0` is (`details.reason = "min"`).
Nothing is rounded (P19). "Digits only" needs no rule: numeric types are number-on-wire (or
string-on-wire for the BIG types with a numeric grammar), and the existing strict coercion
refuses anything else with `parameter.evaluate.invalid_value_type`.

### 3.6 Format (presentation hint, closed catalogue)

```json
"format": { "kind": "currency" }
```

`kind ∈ {plain, currency, percent}`. The server validates the kind, stores the object and returns
it untouched; it changes nothing about validation or binding. `currency` tells a renderer to
show the deployment's currency, which the evaluate response carries once at the top (`org`,
§5.3) from `datapipelines.org.currency-symbol` / `-name` (configuration §3.21) so no second call
is needed. Decimal places always come from `scale`. New kinds are additive.

**The owner's example, complete:**

```json
{ "name": "min_order_amount", "label": "Minimum order amount", "type": "DECIMAL",
  "precision": 12, "scale": 2, "kind": "INPUT", "cardinality": "SINGLE", "required": false,
  "default_value": 0, "constraints": { "min": 0 }, "format": { "kind": "currency" } }
```

---

## 4. Save-time validation (the order the validator runs)

1. Structural: JSON shape, the §3.2 field rules, the set-level rules (§3.1).
2. Graph: build `Dag<ParameterDefinition>` from `depends_on`; dangling → `dependency_unknown`;
   cycle → `dependency_cycle` with `details.cycle` (the `Cycle detected: a -> b -> a` path the
   builder already produces).
3. Expressions (§7): parse, cap depth/nodes, every `ref` ∈ `depends_on`, every literal coerced to
   its ref's type, cardinality rules.
4. Template pins (§6.1): resolve `{id, version}`; `type='sql'`; dialect = datasource dialect;
   the `${}` scan with the declared set = `depends_on` (the existing
   `template.validation.parameter_interpolated`); every `:name` in the body ∈ `depends_on`
   (`bind_undeclared`).
5. **Dry render** each selector template against a context of the parents' defaults (top-down,
   defaults where present, type-appropriate samples otherwise — templates §7.2's rule); a render
   failure is `parameter.validation.template_render_failed`.
6. **Metadata execution** of each rendered selector with `maxRows = 1` (§6.3): the three columns
   exist; `value`'s canonical type passes §6.4; `display_value` is `STRING`; `is_default` is
   `BOOLEAN`; the rendered SQL contains an `ORDER BY` (§6.2). A datasource that cannot be
   reached is a refusal — `parameter.validation.datasource_unreachable` — because a set whose
   selectors cannot be proven is not saved (owner's refuse-at-the-entry-point principle).
   Consequence, stated: a set cannot be saved while its datasource is down.
7. Persist (§8).

Steps 5–6 run for `update` as well as `create`; they are skipped for a body whose `body_hash`
is unchanged.

---

## 5. Evaluate — the runtime protocol

### 5.1 Request

`POST /api/v1/parameter-sets/{name}/evaluate` (and `parameter_sets_evaluate`):

```json
{ "version": 4,
  "selections": { "country": "USA", "state": "NY", "city": null, "min_order_amount": 250.00 } }
```

- `version` optional: a released version number, or absent = the served version (`current_version`);
  a DRAFT may be evaluated by its author with `version` = the draft's number (the working-version
  read rule, versioning §7.1). An MCP evaluate of a draft set whose pinned DRAFT template was
  updated after the key's last `templates_render` of it is refused with
  `parameter.evaluate.template_unrendered` — the 139 gate's twin, MCP-only.
- `selections`: **every parameter's current value** (P15), wire-encoded for its type; `MULTI` as
  an array; `null` or an absent key = *no selection* (first render sends `{}`). A key that names
  no parameter of the set is `parameter.evaluate.unknown_parameter` (the whole request is refused —
  a client that sends unknown keys is wrong, not the user).

### 5.2 Algorithm

1. Coerce every supplied value through `ParameterCoercion` for its type; a wire-form failure is
   recorded on that parameter (`invalid_value_type`) and treated as *no selection* for the
   cascade, so the rest of the form still answers.
2. Build the `Dag` (from the stored, validated definition — never re-validated here).
3. Launch one coroutine per parameter (P16, the executor's pattern): each awaits its parents'
   completion, then evaluates itself; selector queries pass through a `Semaphore` of
   `max-concurrent-selector-queries`; the whole evaluate runs under `withTimeout(evaluate-timeout-seconds)`.
4. Per parameter, in this order: (a) `hidden` and `disabled` from the expressions over the
   parents' **resolved** values; (b) options — `constants` verbatim, or the template rendered
   against `{parents' resolved values} ∪ org tier ∪ platform tier` and run (§6.3); (c) the value:
   - hidden or disabled ⇒ **server-owned** — the submission is ignored, the value is the default
     (P5; a hidden tenant filter still binds for its children and still appears in `values`);
   - `SELECT`: the submitted value(s) if every one is among the options, else the default
     (`is_default` row, else first row; `default_value` when among the options takes precedence)
     with `reset: true` (P13) — a `MULTI` keeps the surviving members and resets only when none survive;
   - `INPUT`: the submitted value if it passes the constraints, else recorded as an error and
     the default used for the cascade;
   - `required` and still no value (and, for `SELECT`, no options) ⇒ `required_missing`.
5. A datasource or statement failure on one selector records the datasource code on that
   parameter, its options are empty, and its dependents evaluate against *no selection* — the
   response is always whole. A deadline hit fails the whole request with
   `parameter.evaluate.timeout` (one answer, never a half-form).

### 5.3 Response

```json
{ "name": "acme/sales/region_filters", "version": 4, "valid": true,
  "org": { "currency_symbol": "$", "currency_name": "USD" },
  "values": { "country": "USA", "state": "NY", "city": "New York", "min_order_amount": 250.00 },
  "parameters": [
    { …the §3.2 definition…,
      "dependents": ["state", "city"],
      "state": { "value": "USA", "hidden": false, "disabled": false, "reset": false,
                 "options": [ { "value": "USA", "display_value": "United States", "is_default": true }, … ],
                 "errors": [] } },
    { …"name": "state"…, "dependents": ["city"],
      "state": { "value": "NY", "hidden": false, "disabled": false, "reset": false, "options": [ … ], "errors": [] } },
    { …"name": "min_order_amount"…, "dependents": [],
      "state": { "value": 250.00, "hidden": false, "disabled": false, "reset": false, "options": null,
                 "errors": [] } }
  ] }
```

- `values` is the **consumer payload** (P4): one canonical wire value per parameter (arrays for
  `MULTI`; `null` when none), hidden and disabled included. It is what a future dashboard hands
  to a pipeline's `parameters`.
- `parameters[]` carries the full definition (a renderer is stateless — P3) plus `dependents`
  (P14) and `state`. `options` is `null` for `INPUT`. `errors[]` entries are
  `{code, message, details}` in the house envelope shape.
- `valid` = no parameter has an error. A renderer that submits to a consumer should refuse while
  `valid` is false; the server does not remember anything between calls.

### 5.4 What is an error and what is not

| situation | outcome |
|---|---|
| wrong wire form / not coercible | `parameter.evaluate.invalid_value_type` on the parameter |
| constraint violated (min/max/length/pattern/scale) | `parameter.evaluate.constraint_violation`, `details.reason` names the rule |
| required, nothing resolvable | `parameter.evaluate.required_missing` |
| SELECT value not among recomputed options | **not an error** — `reset: true`, default applied (P13) |
| submitted value for a hidden/disabled parameter | **not an error** — ignored (P5) |
| selector datasource unreachable / statement failed / over the option cap | the datasource's own code, or `parameter.evaluate.too_many_options`, on that parameter |
| unknown key in `selections` | whole request `parameter.evaluate.unknown_parameter` (400) |
| deadline | whole request `parameter.evaluate.timeout` (504-class in the house mapping the lane confirms in rest-api §4) |

---

## 6. The selector template contract (P22)

### 6.1 The pin

A selector's SQL is a **released or draft template version** pinned by `{id, version}`. The
release rules are templates' (§8.2): a set releases only when every pin is RELEASED.

### 6.2 The SQL

```sql
SELECT state_code   AS value,
       state_name   AS display_value,
       state_code = 'NY' AS is_default
FROM   dim_state
WHERE  country_code = :country
ORDER  BY state_name
```

- Exactly the three logical columns, by alias (`value`, `display_value`, `is_default`); extra
  columns are refused at save (`parameter.validation.selector_columns_invalid`) — a selector is
  not a preview.
- Parents are **binds** (`:country`), never `${country}` (templates §4.5; the save-time scan
  refuses interpolation of any name in `depends_on`). A `MULTI` parent binds as a list:
  `WHERE region IN (:regions)`. `SqlRunner.statement` binds each positional value with
  `StatementCreatorUtils.setParameterValue` and does not expand a `Collection`, so
  `SelectorRunner` expands `:name` for list values itself (spring-jdbc's
  `NamedParameterUtils.substituteNamedParameters` emits `?, ?, ?` for an `Iterable`; the value
  array is flattened to match) and the lane pins that with a test against the pinned jar, the
  `NamedParameterTranslationTest` way. An empty `MULTI` selection binds as an empty list, which
  the runner turns into `IN (NULL)` — no rows — rather than a syntax error.
- The org tier and platform tier keys of pipeline-contract §7.2 are in the render context
  (`:org_currency_symbol`, `:current_date`, …); `execution_id` is absent (there is none).
- `ORDER BY` is required (P7); the check is textual on the **rendered** SQL, after the
  `SqlStatementClassifier` gate (single `SELECT`/`WITH`, read-only — the same gate `sql_probe` runs).
- Row cap: `max-options-per-selector` (§11); the runner uses `maxRows = cap + 1` and refuses on
  overflow (`too_many_options`) — options are never truncated silently. Statement timeout: the
  datasource's `query_timeout_seconds`, clamped to `selector-query-timeout-seconds`.

### 6.3 Execution

`SelectorRunner` renders via `TemplateEngine.render(ref, context)` and executes through one
`ConnectionLease` on the datasource's pool with the `SqlRunner` discipline (`queryTimeout`,
`fetchSize`, `maxRows`), reading rows through `ResultRowReader` so `value` arrives as a canonical
typed value and the schema through `ResultRowReader.schemaOf(rs.metaData, dialect)`. Every
selector query is an audit-visible read on the datasource exactly as a `datasources_preview_rows` is.

### 6.4 Type compatibility of `value` (P11)

Compared from the result-set **metadata** (`ColumnSchema.type`, `precision`, `scale`), never from rows:

| selector column type → declared parameter type | outcome |
|---|---|
| identical | accept |
| `INTEGER` → `BIGINTEGER`, `DECIMAL`, `BIGDECIMAL` | accept (widening) |
| `BIGINTEGER` → `BIGDECIMAL` | accept |
| `DECIMAL(p,s)` (exact, scale present) → `BIGDECIMAL(P,S)` with `P ≥ p`, `S ≥ s` (or `P` unbounded) | accept |
| `DECIMAL` with **omitted scale** (approximate source) → any exact declared type | **refuse** — the source is a float; the author casts |
| `STRING` → `DATE`/`TIME`/`TIMESTAMP`/numeric | **refuse** — parsing text by inspecting rows is exactly the guess P11 forbids |
| `TIMESTAMP` → `DATE`, `BIGINTEGER` → `INTEGER`, `BIGDECIMAL` → `DECIMAL` | **refuse** — lossy |
| `NULL` (all-null column, type-system §8.1) | **refuse** — `selector_value_type_mismatch` with `details.hint = "CAST the column"` |
| anything else | **refuse** |

Code: `parameter.validation.selector_value_type_mismatch` at save; the same check runs at
evaluate (schemas drift) and reports `parameter.evaluate.selector_value_type_mismatch` on the parameter.

---

## 7. The expression AST (P12)

### 7.1 Grammar

```
expr    := logical | test | compare
logical := {"op": "and", "args": [expr, …]} | {"op": "or", "args": [expr, …]} | {"op": "not", "arg": expr}
test    := {"op": "is_null",  "arg": ref} | {"op": "is_empty", "arg": ref}
compare := {"op": "eq" | "neq", "left": ref, "right": literal}
         | {"op": "in", "left": ref, "right": [literal, …]}
         | {"op": "contains", "left": ref, "right": literal}
ref     := {"ref": "<parameter name in depends_on>"}
literal := {"literal": <wire value for the ref's type>}
```

`and`/`or` take 1..n args; `in`'s list is 1..256 literals. Caps: depth ≤ `max-expression-depth`
(16), nodes ≤ `max-expression-nodes` (128).

### 7.2 Static rules (save time)

- Every `ref` names a parameter in this parameter's `depends_on` (`ref_undeclared`) — never itself.
- `eq`/`neq`/`in` require a `SINGLE` ref; `contains` requires a `MULTI` ref; `is_empty` requires
  `MULTI`, `is_null` requires `SINGLE` (`parameter.validation.expression_cardinality`).
- Every literal is coerced to the ref's `LogicalType` at save (`parameter.validation.expression_literal_type`);
  a `BINARY` ref may only be tested with `is_null` (`expression_type_unsupported`).
- The two expressions are evaluated **after** the parents are resolved and **before** this
  parameter's own options are computed (§5.2 step 4a) — an expression may never reference the
  parameter it sits on.

### 7.3 Runtime semantics

- Values compared are the parents' **resolved** canonical values (post-reset, post-default).
- Any `compare` whose ref is `null` (SINGLE) or empty (MULTI) is `false`; `is_null`/`is_empty`
  are the only tests for absence. `not(false)` is `true`. `and`/`or` are strict (no short-circuit
  semantics are observable — there are no side effects).
- Equality is the canonical value's equality: `BigDecimal` by `compareTo` (so `1.0 == 1.00`),
  temporal by instant/date/time, `STRING` exact (case-sensitive, no trimming).
- The evaluator is a recursive interpreter over the parsed tree; no parsing at evaluate time
  (the tree is stored as validated JSON).

---

## 8. Persistence, lifecycle, promotion, the reverse arrow

### 8.1 Tables (next free `V__` migration in `modules/app`; V31 is the latest on main)

`parameter_sets` = the `templates` table's shape: `id UUID PK`, `workspace_id`, `name` (UNIQUE per
workspace), `display_name`, `description`, `current_version INTEGER NULL` (the sticky pointer,
D60), `created_at/updated_at/created_by`. `parameter_set_versions` = `template_versions`' shape
minus the template-only columns: `(parameter_set_id, version) PK`, `body_json JSONB NOT NULL`
(the §3 document with `name` omitted), `status` (`DRAFT`/`RELEASED`/`DISCARDED`, the same CHECK),
`body_hash` (SHA-256 of the canonical body), the release/discard/via/created stamps and their
CHECK constraints, the one-draft partial unique index. metadata-db.md gains §4.21/§4.22.

### 8.2 Lifecycle

The full versioning §3.5 verb table, with the `parameter.version.*` twins of `template.version.*`
(§10): draft create (copy-on-write), draft write (hash-preconditioned, `parameter.version.conflict`),
release, purge, discard, restore, switch, import. Release preconditions: every pinned template
version RELEASED (`parameter.release.template_not_released`; `release_pinned_templates=true`
cascades exactly as 142), every referenced datasource visible from the workspace. Entity status is
derived (no `is_deleted`). `datapipelines.deployment.authoring-enabled=false` refuses authoring
writes with `parameter.authoring.disabled`.

### 8.3 Promotion

By name, like templates: export carries the set body plus the manifest of pinned template
versions; import lands RELEASED, refuses a missing template pin with
`parameter.import.missing_template`, and datasource names resolve per environment (portable by
design — pipeline-contract §11). The promoter lens (auth §11A.1) applies: released and newer than
the target's.

### 8.4 The templates reverse arrow (new work P22 pulls in)

Today `TemplateUsageService` and the `409 template.in_use` delete guard scan **pipeline** versions
only (templates §5.4). With template-backed selectors, a template pinned by a parameter set
alone would be deletable, breaking the set's release. Therefore, in this workstream:

- **Used-by** (`templates_used_by`, the template screen's counts, `pipelines_get`'s
  `upgrade_available` twin on `parameter_sets_get`): rows of the shape
  `{parameter_set, parameter, set_version, pinned_version}` beside the pipeline rows, scanning
  the set's working version.
- **Delete guard**: the any-version scan covers `parameter_set_versions`;
  `details.referencing_parameter_sets` beside `referencing_pipelines`.
- Both are cross-aggregate, so the combined scan lives in `application`
  (`TemplateUsage` composed from the templates module's pipeline scan and a `parameters`
  scanner); `templates` itself is not edited beyond consuming the composed answer.

### 8.5 Audit

Every MCP call already writes `mcp.tool.called` (the dispatcher's `AuditLogger`); the REST
routes ride the existing interceptor. No new audit event kinds. `parameter_sets_evaluate` is a
read of the datasources it touches and is visible as such.

---

## 9. Surfaces and roles

### 9.1 MCP tools (`McpToolCatalog.NAMES` 41 → **47**; every pinned count moves in the same commit: `McpServerWiringTest`, `DatasourcesCreateRemovedTest`, `ScopeMatrixSpecDriftTest`, `McpServerAutoConfiguration`'s KDoc, mcp-server.md §6, the skill's `tools.md`)

| tool | mutating | min scope | min permission | what |
|---|---|---|---|---|
| `parameter_sets_list` | no | READ | VIEW | prefix listing, the pipelines/templates shape; promoter lens |
| `parameter_sets_get` | no | READ | VIEW | working version + `upgrade_available` per pinned template |
| `parameter_sets_create` | yes | AUTHOR | AUTHOR | §3 body; `confirm_new_root` |
| `parameter_sets_update` | yes | AUTHOR | AUTHOR | hash-preconditioned draft write |
| `parameter_sets_evaluate` | no | EXECUTE | EXECUTE | §5 |
| `parameter_sets_purge_draft` | yes | AUTHOR | AUTHOR | versioning §5.4 |

Release, discard, restore, switch and promotion are REST/UI verbs for pipelines and templates
today (no `templates_release` tool exists); parameter sets follow — no MCP release tool in round one.

### 9.2 REST (`/api/v1/parameter-sets`, mirroring the templates routes in rest-api.md)

`GET /` (list), `POST /` (create), `GET /{name}` (working version), `PUT /{name}` (draft write),
`GET /{name}/versions`, `POST /{name}/release`, `DELETE /{name}/draft` (purge),
`POST /{name}/versions/{v}/discard`, `.../restore`, `.../switch`, `GET /{name}/export`,
`POST /import`, **`POST /{name}/evaluate`**. Envelopes and codes per rest-api §4.

### 9.3 Roles (AGENTS.md §4.9 — each row lands with its handler, its `ScopeMatrix` entries, its auth.md §7.6 row and its `RoleWalkE2eTest` expectation in ONE commit)

| operation | endpoints / tools | viewer | author | promoter | ws_admin | super_admin | `RestOperation` |
|---|---|---|---|---|---|---|---|
| Read parameter sets | `GET` under `/api/v1/parameter-sets`; `parameter_sets_list/get` | ✓ | ✓ | lens | ✓ | ✓ | `READ_RESOURCES` (existing row; its endpoints cell gains the routes) |
| Author parameter sets | `POST`/`PUT`/`DELETE` under `/api/v1/parameter-sets`, `/import`; `parameter_sets_create/update/purge_draft` | ✗ | ✓ | ✗ | ✓ | ✓ | **`MUTATE_PARAMETER_SETS`** `(Scope.AUTHOR, Permission.AUTHOR)` — new |
| Evaluate | `POST /api/v1/parameter-sets/{name}/evaluate`; `parameter_sets_evaluate` | ✓ | ✓ | ✗ | ✓ | ✓ | **`EVALUATE_PARAMETER_SET`** `(Scope.EXECUTE, Permission.EXECUTE)` — new |
| Release / switch | `POST /{name}/release`, `.../switch` | ✗ | ✓ | ✗ | ✓ | ✓ | `RELEASE_VERSION`, `SWITCH_SERVED_VERSION` (existing rows, endpoints cells extended) |
| Promote | the promotion page's parameter-set rows | ✗ | page | ✓ | ✓ | ✓ | `PROMOTE_VERSION`, `PROMOTION_READ` (existing) |

Key scope for evaluate is `execute` — a `read`-scoped key may list a set but not run its
selectors (the same asymmetry auth §7.6 states for pipelines). `MatrixRowReachabilityTest`
requires each new row to be declared by a handler; `ReadFloorTest` requires every `GET` to declare
the lowest admitting row; `RequiredScopeCoverageTest` / `RequiredScopeKonsistTest` require
`@RequiredScope` on every handler.

---

## 10. Error codes — pipeline-contract.md **§13.16 Parameter sets** (new section; `ParameterErrorCodes` in `modules/parameters`; a `ParameterErrorCodesSpecDriftTest` parses §13.16 the way `PipelineErrorCodesSpecDriftTest` parses §13; the skill's `references/error-codes.md` gains the family and `:modules:mcp-server:skillArtifacts` runs in the same commit — `SkillDistributionTest`)

| code | HTTP | when |
|---|---|---|
| `parameter.validation.name_invalid` | 400 | set name fails the folder grammar (`details.reason`: `folder_required` / `grammar`) or a parameter name fails §6.1's |
| `parameter.validation.new_root_requires_confirmation` | 400 | agent surface only; the pipelines/templates shape |
| `parameter.validation.duplicate_name` | 409 | set name exists in the workspace (discarded included) |
| `parameter.validation.duplicate_parameter` | 400 | two parameters share a name |
| `parameter.validation.too_many_parameters` | 400 | over `max-parameters-per-set` |
| `parameter.validation.label_invalid`, `description_too_long`, `type_invalid`, `precision_missing`, `scale_missing`, `kind_invalid`, `cardinality_invalid`, `default_type_mismatch`, `default_not_an_option`, `source_missing`, `source_ambiguous`, `source_on_input`, `constraints_on_select`, `constraint_not_applicable`, `constraint_invalid`, `pattern_invalid`, `format_invalid` | 400 | §3.2–§3.6 |
| `parameter.validation.option_invalid`, `option_duplicate`, `multiple_defaults`, `too_many_options` | 400 | §3.3 constants |
| `parameter.validation.dependency_unknown`, `dependency_self`, `dependency_cycle`, `bind_undeclared`, `ref_undeclared` | 400 | §3.2 `depends_on`; `dependency_cycle` carries `details.cycle` |
| `parameter.validation.expression_invalid`, `expression_depth_exceeded`, `expression_too_large`, `expression_cardinality`, `expression_literal_type`, `expression_type_unsupported` | 400 | §7 |
| `parameter.validation.template_not_found`, `template_version_not_found`, `template_type_mismatch`, `template_dialect_mismatch`, `template_render_failed` | 400 | §4 steps 4–5 (the §12.6 twins) |
| `parameter.validation.datasource_not_found`, `datasource_unreachable` | 400 | §3.4, §4 step 6 |
| `parameter.validation.selector_columns_invalid`, `selector_value_type_mismatch`, `selector_order_by_missing` | 400 | §6 |
| `parameter.evaluate.unknown_parameter` | 400 | §5.1 |
| `parameter.evaluate.invalid_value_type`, `constraint_violation`, `required_missing`, `too_many_options`, `selector_value_type_mismatch` | 200 (in `errors[]`) | §5.4 — per-parameter, the request itself succeeds |
| `parameter.evaluate.template_unrendered` | 400 | MCP-only, §5.1 |
| `parameter.evaluate.timeout` | 504 (house mapping confirmed by the lane against rest-api §4) | §5.2 step 5 |
| `parameter.not_found` | 404 | name (or name+version) unknown, hidden by the lens, or discarded on a read/mutate path |
| `parameter.in_use` | 409 | reserved for the future consumer binding (§13); **not declared in round one** — listed so the family's shape is complete |
| `parameter.version.conflict`, `not_draft`, `not_released`, `not_discarded`, `last_release`, `not_eligible`, `confirm_mismatch` | 409 / 400 | the `template.version.*` twins, same meanings |
| `parameter.release.template_not_released` | 409 | §8.2, `details.pins_not_released` |
| `parameter.import.missing_template` | 400 | §8.3 |
| `parameter.authoring.disabled` | 403 | §8.2 |

`template.in_use` (existing) gains `details.referencing_parameter_sets` (§8.4) — no new code.

---

## 11. Configuration (`datapipelines.parameters.*`, configuration.md §3 gains the block; `ParametersConfigKeysSpecDriftTest` the way the other `*ConfigKeysSpecDriftTest`s pin theirs; `ConfigValidatorCheckCountTest`'s count moves)

| key | default (proposed; the lane may tune, the key is the contract) | bounds |
|---|---|---|
| `max-parameters-per-set` | 64 | 1–256 |
| `max-options-per-selector` | 1000 | 1–10000 |
| `max-input-length` | 4096 | 1–65536 |
| `max-expression-depth` | 16 | 1–64 |
| `max-expression-nodes` | 128 | 1–1024 |
| `evaluate-timeout-seconds` | 30 | 1–300 |
| `selector-query-timeout-seconds` | 10 | 1–`evaluate-timeout-seconds` |
| `max-concurrent-selector-queries` | 4 | 1–32 |

Bound to `ParametersProperties` (`@ConfigurationProperties`, module-structure §8.3) and mirrored
in the domain `ParametersConfig` — both literals live as named constants (MISTAKES: the
`mapOf(… to 180)` detekt trap), and `ConfigValidator` checks the bounds.

---

## 12. Testing requirements (every guard shown red once; the lane's handback lists each falsification)

| layer | what | red-once proof |
|---|---|---|
| `graph` | `DagTest` runs unchanged from its new home | the rename commit alone — `git diff -M` 100% |
| `typesystem` | coercion/encoder tests moved; `pipeline-contract` still green | flip one coercion rule, both modules red |
| `parameters` unit | validator (every §10 validation code reached by a fixture), expression parser + evaluator (every op, both cardinalities, null/empty rules, caps), type-compatibility table (§6.4, every row), reset semantics, hidden/disabled ownership, `MULTI` list expansion (against the pinned spring-jdbc, incl. the empty list) | one fixture per row; delete the rule → its fixture red |
| `parameters` unit — evaluator concurrency | two independent selectors run concurrently (measured with a latch, never a sleep — MISTAKES "synchronise on the event"); a child never starts before its parent completes; the semaphore bounds in-flight queries; the deadline fails the whole request | remove the await → the ordering test red |
| `parameters` integration (`*IntegrationTest`, Postgres container) | repository + every §3.5 lifecycle verb; hash precondition; one-draft index | as templates' |
| `application` | reverse arrow: used-by rows and the delete guard cover a set-only pin | delete the set scanner → guard lets the delete through, test red |
| `web` + `mcp-server` | handler/tool tests; `MatrixRowReachabilityTest`, `ReadFloorTest`, `RequiredScopeCoverageTest` green with the new rows; catalog count pins | remove `@RequiredScope` → red |
| E2E (`tests/integration-tests`) | the country → state → city cascade over a real H2/Postgres datasource through **REST and MCP**: first render, parent change resets children with `reset: true`, hidden/disabled from expressions, an `INPUT` with `scale: 2` + `min: 0` refusing `12.345` and `-1`, a `MULTI` parent binding into `IN (:regions)`, an unreachable datasource answering a whole form; release refused on a draft pin; `RoleWalkE2eTest` rows; `PromoterLensSweepTest` and `WorkspaceIsolationSweepTest` extended | the sweeps' non-vacuity floors |
| drift | `ParameterErrorCodesSpecDriftTest`, `ScopeMatrixSpecDriftTest`, `ParametersConfigKeysSpecDriftTest`, `SkillDistributionTest`, `verifyModuleDependencies`, `ArchitectureGuardTest` | add a code to the doc → red until the constant exists |
| coverage | the module floors the conventions plugin sets (Kover) | — |

Every gate runs through `scripts/gate.sh` on the merge SHA (memory: gate every merge); the E2E
module runs with `-Pdp.test.forks.e2e=1` once before handback (MISTAKES: fork-count blind spot).

---

## 13. Out of scope, by name (and who owns each next)

| item | next owner |
|---|---|
| Any UI page (list, editor, reference renderer) | dashboards (#10) or its own issue |
| Dashboards, charts | #10 |
| Binding a parameter set to a pipeline's `parameters` / an endpoint's inputs (`parameter.in_use` is reserved for it) | the consumer design |
| A textual expression syntax compiling to §7's AST; GraalJS | a later design; the AST is the contract either way |
| Partial / patch submissions (transport-level, semantics unchanged) | only if payload size becomes a measured problem |
| Option search, typeahead, pagination | when a selector exceeds the cap in practice |
| Label localisation | — |
| Per-user saved selections / bookmarks | dashboards |
| An MCP release/discard/restore tool | none exists for pipelines or templates either; one decision for all three |

---

## 14. Open items (the lane confirms; none blocks dispatch)

1. The exact HTTP status for `parameter.evaluate.timeout` per rest-api §4's existing mapping of
   executor deadlines (the doc, not memory, decides).
2. Whether `readonly` datasource classification (datasources.md §7D) already covers the selector
   runner's gate or a second classifier call is needed — reuse `SqlStatementClassifier` either way.
3. `TemplateRef`'s package (templates vs pipeline-contract) decides whether `parameters` declares
   `pipeline-contract` at all; the §2.4 row allows it, the build file lists it only if compiled against.
4. The V-number: the next free one at merge time (V31 is main's latest; in-flight lanes may claim
   numbers first).

---

## 15. Lane split and build order (one-then-parallel)

| lane | scope | depends on |
|---|---|---|
| **A** (first, alone) | §2.1 `graph` move + §2.3 coercion extraction + the §2.4 table rows + `settings.gradle.kts`. Pure refactor, zero behaviour change, full gate. | — |
| **B** | `parameters` model, validator, expression AST, `Dag` build, repository + lifecycle + migration, `ParameterErrorCodes` + §13.16 + drift test, config keys | A |
| **C** | `SelectorRunner` (template render, binds, list expansion, metadata check, caps), `ParameterEvaluator` (coroutines, semaphore, deadline, reset/hidden/disabled semantics) | B |
| **D** | surfaces: six MCP tools + catalog pins + skill docs + `skillArtifacts`; REST routes; the three matrix rows (§9.3) with auth.md §7.6 + `RoleWalkE2eTest`; the reverse arrow in `application` (§8.4); promotion; E2E cascade | C |

B and C may run in parallel only after A has frozen the module layout and B has frozen the
model (`ParameterDefinition` + `ParameterErrorCodes`); D is serial after C. Each lane prompt
carries the roles section (AGENTS.md §4.9) and the security-review list (memory: security pass
on every handback).

---

## Change log

| date | version | change |
|---|---|---|
| 2026-09-21 | draft 1 | Recovered from the owner's Codex conversation of the same day; four follow-up decisions (P18–P21) and the template-backed selector ruling (P22) added; every code fact re-verified on `b34bdddf`. |
