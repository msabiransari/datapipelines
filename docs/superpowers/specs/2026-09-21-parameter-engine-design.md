# Parameter engine — design record (2026-09-21, draft 5.1 — the 2026-09-26 rulings and renderer notes)

**Updated 2026-09-25:** the owner's clarification supersedes the earlier hidden/disabled
value rule. These states govern control interaction; every current selected value is read,
submitted and validated normally, including explicit client-side modifications. The runtime
does not preserve or restore originally served values. These states do not cause submitted
values to be ignored or reset to defaults.
Dashboard-specific server-side state and outgoing-value overrides are documented in the
[dashboard draft §4.3](2026-09-25-dashboard-authoring-design-draft.md#43-server-side-parameter-state-and-outgoing-value-overrides).

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

Rulings the owner made in conversation (P1–P17), the six taken in the follow-ups (P18–P23; P23 was rewritten the same day when the owner rejected a `FIXED` kind), and the eight of 2026-09-26 (P24–P31) that answered the second review (Astra's twelve items, `/tmp/parameter-spec-review-2026-09-26.md`, and the orchestrator's six of 2026-09-21).
Two deviations from the conversation's wording (P8's third column, P13's invalidation outcome)
are marked **deviation** and carry their reason.

| # | Decision |
|---|---|
| P1 | The engine is a **standalone parameter-definition engine**, decoupled from dashboards, charts and HTML. Agents create *definitions* through MCP; the server stores and serves them as generic JSON; a client runtime renders them however it likes (a multi-value parameter may become checkboxes, a multi-select, pills). |
| P2 | The durable contract describes **selection semantics, not widgets**: value type × cardinality × value source × constraints. There is no per-widget catalogue. The definition also carries a **presentation block** (P23c, §3.7) — a control hint and a display format from a closed catalogue — so a renderer can pick a calendar for a date or checkboxes for a multi-select; presentation never changes meaning, binding or validation. |
| P3 | Parameters **depend on zero or more parameters**; a change anywhere makes the client submit and the server re-evaluate. **The client is a pure renderer**: it runs no SQL and no dependency logic. |
| P4 | Three concepts, kept apart: the **definition** (durable, MCP-authored), the **runtime state** (server-evaluated per submission), the **selection payload** (the values currently chosen). |
| P5 | The server owns options, defaults, hidden/disabled state and validation; it re-validates every submitted value against the newly computed options on every evaluation. **Clarified 2026-09-25:** hidden/disabled governs control interaction; it does not freeze, discard or reset selections. Current submitted values, including explicit client-side modifications, use the same validation and resolution rules regardless of these states. |
| P6 | Every selector option — constant or SQL-backed — is `{value, display_value, is_default}`. `display_value` is presentation text; `value` is the canonical typed value; **the server never infers one from the other**. |
| P7 | No option marked default ⇒ the **first option in order** is the default; more than one marked ⇒ refused. Selector SQL must carry an `ORDER BY` (the check proves the clause is present, not that the order is total; "first" is the first row the database returned). Since P26 the first option is priority 3 of the selection order. |
| P8 | The three columns of selector SQL are `value`, `display_value`, `is_default`. **Deviation:** the conversation named the third `default`; `DEFAULT` is a reserved word in PostgreSQL, MySQL, SQL Server and Oracle and would force quoting in every dialect — `is_default` is the alias. |
| P9 | `default_value` on a parameter is its resolved default: one typed value (SINGLE), an array (MULTI), a scalar for an input. It is separate from `options[].is_default`. |
| P10 | **Reuse the type system.** A parameter declares one of the 10 declarable `LogicalType`s (`NULL` excluded); wire encoding is type-system §3.1; coercion is the existing strict `ParameterCoercion`, moved out of `pipeline-contract` so that pipelines and parameters share ONE implementation (§2.3). |
| P11 | Type compatibility of a selector's `value` column: **same type ⇒ accept; explicitly listed lossless widening ⇒ accept; anything lossy, ambiguous or value-inferred ⇒ refuse.** Decided from result-set METADATA, never by inspecting rows; an author changes the outcome with an explicit `CAST`. |
| P12 | `hidden_expression` / `disabled_expression` are server-evaluated boolean predicates stored as a **JSON expression AST** (§7). SpEL is rejected (persisted, MCP-authored expressions; restriction is configuration-and-review work; coercion/null/truthiness surprises; text-based dependency extraction; Spring-version coupling). GraalJS is rejected for this core feature (arbitrary code, isolation, discovery, implicit coercion, non-determinism, attack surface). A textual syntax may compile to the same AST later. |
| P13 | Dependencies form ONE directed acyclic graph: `depends_on` + the binds of selector SQL + the `ref`s of expressions. Cycles (direct or indirect) and dangling references are refused at save. Invalidation (confirmed by the owner 2026-09-26, replacing the draft-4 deviation's wording): a submitted value that is no longer among the recomputed options is **never an error** — it walks the selection priority (P26) to the configured default, else the first option, and is flagged `reset: true` with its `origin`; a legitimate parent change causes this on every cascade. Errors are reserved for what the client did wrong (§5.4). |
| P14 | Clients get both views, **both informational** since P27 (every change is a full submission, so no client decides what to submit): `dependents` and `depends_on` serve highlighting and diagrams. |
| P15 | The client reads and submits **the current value of every parameter in the set at submission time**, including hidden/disabled parameters and explicit host-component/client-code changes — never the changed selector alone, never its subtree, never only editable controls. It does not restore originally served values. The server evaluates the whole graph in topological order and returns the whole refreshed state. |
| P16 | No graph library. **Reuse the house `Dag<T>`** and `kotlinx.coroutines`, following `PipelineExecutor`'s pattern (schedule every node, each awaits its parents; never wave scheduling). |
| P17 | **Option B for placement:** the generic `Dag` primitive moves **byte-identical** into its own leaf module; the mature executor is not touched. (Owner 2026-09-21: "I don't want to touch a mature implementation which is working fine.") |
| P18 | The persisted entity is a **parameter set**, named with the folder-path grammar pipelines and templates use, **versioned exactly like templates** (draft / release / purge / discard / restore / switch / import; promotion by name). Addressed like **pipelines** (P24). |
| P19 | Input formatting is **validation, and validation is refusal**: a value that violates a declared constraint is rejected with a per-parameter error; the server never rounds, trims or normalises what the user typed. Constraints ride on the definition so a renderer can mask input up front. |
| P20 | Roles: **read** every role (promoter through the lens); **author** (create/update/release/purge) author + admins; **evaluate** every role that may execute a pipeline (viewer, author, admins; promoter ✗); **promote** promoter + admins. Expressed as per-action permission rows of the keys-v2 catalog (§9.3), never as the retired `RestOperation`/scope model. |
| P21 | Round one ships **MCP tools + REST API, no UI**. The reference renderer lands with dashboards (#10). |
| P22 | **Selector SQL is a pinned template**, never inline SQL: `source.template = {id, version}` (the node shape of pipeline-contract §4.1), rendered through `TemplateEngine`, parents bound as `:name`, `${parent}` refused at save, pins released before the set releases, and the templates used-by / delete-guard reverse scans extended to parameter sets (§6, §8.4). |
| P23 | **Two kinds of control, both hard-coded or database-fed, and the definition says how to render** (owner 2026-09-21, third follow-up; a `FIXED` kind proposed in draft 2 was rejected). (a) **`INPUT`** — a free value the user types, **always `SINGLE`**; its initial value is either hard-coded (`default_value`) or comes from the database (a pinned template returning one row, §3.4). (b) **`SELECT`** — a value the user picks, `SINGLE` or `MULTI`; its options are either hard-coded (`source.constants`, §3.3) or come from the database (`source.template`, §3.4). SQL is for data that changes; it is never required. (c) The definition carries **`presentation`** (§3.7): a `control` hint validated against kind × cardinality × type (an `INPUT` of type `DATE` says `calendar`; a `MULTI` select says `dropdown`, `checkboxes` or `list`) and a display `format` (currency/percent for numerics, a pattern for temporal types). A renderer may honour or ignore it; the server never reads it for anything but validation and echo. |

| P24 | **Addressing mirrors pipelines** (owner 2026-09-26): a set gets a UUID at creation; REST routes carry it in the path (`/api/v1/parameter-sets/{id}/…`); listing and browse are by name and `prefix`; the MCP get/update/evaluate tools take the id as `pipelines_get` does; the export carries the id and import keeps it (`PipelineImportService`'s rule), so the id is stable across environments. Names never travel in a path segment (rest-api §9.6). |
| P25 | **`required` means a value must arrive; `default` is a hint** (owner 2026-09-26). A required parameter with no value is `required_missing` on evaluate and a `400` at every consumer. The server never substitutes a default for something the client sent as cleared. Two signals: a key **absent** from `selections` means "initialise me" and walks the selection priority (P26); an explicit **`null`** means "the user cleared it" — for a `MULTI` too (the renderer contract sends `null`, never `{}` or `[]`; the server accepts `[]` as `null` for robustness) — an error on a required parameter, an empty value that flows as `NULL` on an optional one. The first render sends `{}`; every later submission sends every key. |
| P26 | **The selection priority**, per parameter, in topological order, `SINGLE` and `MULTI` alike (owner 2026-09-26): **1** the client's selection when it is valid against the recomputed options (a `MULTI` keeps its valid members); **2** the configured default when it is among the options (`default_value`, else the `is_default` option; for a `MULTI` the default members among the options); **3** the first option in order (for a `MULTI`, as the only member). A client value that fits none of the options walks to 2 then 3 and is flagged `reset: true`; it is never an error. An `INPUT` has no options: the client's value (against the constraints, an error when violated), else the sourced row, else `default_value`, else `null` (`required_missing` when required). Every parameter's state carries `value`, `origin` (`client` / `default` / `first` / `source` / `none`), `computed_default` (what 2-then-3 would give now) and `reset`. |
| P27 | **The submission model** (owner 2026-09-26): the parameters endpoint is called on the first render (`{}`) and on every change to any control, and the client sends **every** parameter each time; the server re-renders the whole set. The consumer's apply/submit button goes to the consumer (pipeline execute, dashboard execute), never to the parameters endpoint. The client runs no dependency logic at all — not even "clear the dependents": a stale child walks P26 on the server. |
| P28 | **One validator, strict, at every place a parameter value arrives** (owner 2026-09-26): a shared `ParameterValueValidator` in `modules/typesystem` beside the moved coercion — declaration (type, precision, scale, required, default, constraints, cardinality) + value → accepted typed value or a refusal — used by the business API (published endpoints, values arriving as query strings), the execution API (`POST /api/v1/pipelines/{id}/execute`), evaluate, and later dashboard execute. The coercion is strict everywhere: the BIG-number `trim()` is retired for pipelines too — a **deliberate break** recorded in rest-api's change log, the tests that asserted trimming re-pinned. Pipeline declarations gain optional `constraints` and a list `cardinality` in the same model, adopted by the dashboard round; the engine's `type` set is the pipelines' `type` set, so a consumer needs no translation. |
| P29 | **`MULTI` binds** (owner 2026-09-26): the list never reaches the template (P22); its **size** does, as `<name>_count`, and a library macro (`<@in_list column="region" bind="regions" chunk=500/>`) emits `(region IN (:regions__1) OR region IN (:regions__2))` with the runner binding the slices — the skill teaches the pattern. Caps, the most restrictive engines' floors: **1,000 values per `MULTI` parameter** (Oracle's per-list ceiling, which the macro clears) and **2,000 binds per statement** (SQL Server's 2,100), both refused at evaluate with an error naming the cap. |
| P30 | **Binds resolve by namespace** (from Astra's item 3, consistent with pipeline-contract §7.2): a `:name` in selector SQL is first looked up among the set's parameters — then it must be in `depends_on`; otherwise, if it is one of §7.2's org or platform keys, it is served from the tier and needs no dependency; otherwise `bind_undeclared`. A parameter named like a tier key shadows it, exactly as a declared pipeline parameter does. `execution_id` is absent. |
| P31 | **Bounded work and capacity** (from Astra's items 4, 8, 10): the decimal widening rule preserves integer digits too (§6.4); the constants' invariants are enforced over every database row at evaluate (§6.2); regex constraints run over a read-counting `CharSequence` with a step budget; a selector's `Statement.cancel()` fires on the deadline and the lease is returned; the semaphore is instance-wide; `max-options-per-selector` defaults to 200 and a total response budget refuses oversized answers (§11). |

---

## 1. Scope

**In:** the `graph` and `parameters` modules (§2); the parameter-set entity, its JSON and its presentation block (§3);
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

**P28 (2026-09-26): strict, and one validator.** The moved coercion loses the BIG-number
`trim()` — a value with surrounding whitespace is refused everywhere, pipelines included; the
tests that asserted trimming are re-pinned to refusal and rest-api's change log records the
deliberate break. Beside it, `ParameterValueValidator` (typesystem) takes a declaration — `type`,
`precision`, `scale`, `required`, `default`, `constraints` (§3.5's set), `cardinality` — and a wire
value, and answers an accepted typed value or a refusal with the reason (`invalid_value_type`,
`constraint_violation` + `details.reason`, `required_missing`). Its callers: the published
endpoints (query-string values — format strictness, decoded first), `POST /api/v1/pipelines/{id}/execute`,
evaluate (§5), and later dashboard execute. Pipelines' `parameters` declarations (pipeline-contract
§6.1) gain optional `constraints` and `cardinality` (`SINGLE` default, `MULTI` bound as a list
through the same expansion as §6.2) in the same model — declared in lane A's contract, adopted by
the dashboard round; nothing declared today changes meaning.

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
  "presentation": { "control": "dropdown" }
}
```

| field | rule | refusal code (§10) |
|---|---|---|
| `name` | `[a-z_][a-z0-9_]*`, 1–63 (pipeline-contract §6.1's grammar, the same constant) | `parameter.validation.name_invalid` |
| `label` | required, 1–120 chars | `parameter.validation.label_invalid` |
| `description` | optional, ≤ 2000 chars | `parameter.validation.description_too_long` |
| `type` | one of `BOOLEAN`, `INTEGER`, `BIGINTEGER`, `DECIMAL`, `BIGDECIMAL`, `STRING`, `BINARY`, `DATE`, `TIME`, `TIMESTAMP` (`LogicalType` minus `NULL`) | `parameter.validation.type_invalid` |
| `precision`, `scale` | exactly pipeline-contract §6.2 / §12.7: `precision` required for `DECIMAL`, optional (= unbounded) for `BIGDECIMAL`; `scale` required for `BIGDECIMAL` and for exact `DECIMAL` | `parameter.validation.precision_missing`, `parameter.validation.scale_missing` |
| `kind` | `INPUT` (a value the user types; initial value hard-coded via `default_value` **or** from a database via `source.template`) or `SELECT` (a value the user picks; options hard-coded via `source.constants` **or** from a database via `source.template`) — P23 | `parameter.validation.kind_invalid` |
| `cardinality` | `SINGLE` or `MULTI`; an `INPUT` is **always `SINGLE`** (P23a); a `SELECT` is either | `parameter.validation.cardinality_invalid` |
| `required` | boolean (P25). A value **must arrive** for a required parameter — absent walks the priority (P26), an explicit `null`/`[]` is `required_missing`; a required parameter that resolves to nothing (no client value, no default, no options / no sourced row) is `required_missing` at evaluate, not a save error. An optional parameter cleared by the client stays empty and flows as `NULL` | — |
| `default_value` | wire-encoded for `type`; `SINGLE` → one value, `MULTI` → array; must pass the full `ParameterValueValidator` check at save (the §12.7 `default_type_mismatch` rule AND the parameter's own constraints — `min: 0` with `default_value: -1` is refused, `default_invalid`). It is a **hint** (P25): priority 2 of P26, never applied to a value the client cleared. For an `INPUT` it is the hard-coded initial value and the fallback when a template source returns no row. For a `SELECT` with `constants` it must be among the option values; with a template it is checked at evaluate (the options depend on parents) and walks to priority 3 when absent from them | `parameter.validation.default_type_mismatch`, `parameter.validation.default_invalid`, `parameter.validation.default_not_an_option` |
| `source` | `SELECT`: required, exactly one of `constants` (§3.3) or `template` + `datasource` (§3.4). `INPUT`: optional, `template` + `datasource` only (the database-fed initial value, §3.4); `constants` is refused on an `INPUT` — its hard-coded value is `default_value` | `parameter.validation.source_missing`, `parameter.validation.source_ambiguous`, `parameter.validation.source_not_allowed` |
| `depends_on` | parameter names in the same set; must be a **superset** of every `:bind` the source template uses that names a parameter of the set and every `ref` in the two expressions (P30: a bind naming an org/platform tier key needs no dependency; discovery is never load-bearing — P12); no self reference; the set's graph must be acyclic; a parameter may be named like a tier key and then shadows it (§7.2's precedence) | `parameter.validation.dependency_unknown`, `parameter.validation.bind_undeclared`, `parameter.validation.ref_undeclared`, `parameter.validation.dependency_cycle` |
| `hidden_expression`, `disabled_expression` | a §7 AST or `null` (= `false`) | `parameter.validation.expression_invalid` and the §7 codes |
| `constraints` | `INPUT` only (§3.5) | `parameter.validation.constraints_on_select` |
| `presentation` | optional; `control` + `format` from the closed catalogues (§3.7), validated against kind × cardinality × type | `parameter.validation.presentation_invalid`, `parameter.validation.control_not_applicable`, `parameter.validation.format_invalid` |

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

This is the **default way to populate a selector** (P23a): a list an author (or agent) writes into
the definition — statuses, regions, a yes/no pair, anything stable. The template form (§3.4) is
for options that change and must come from a database. A constants selector needs no datasource,
no template and no query at evaluate time; the two forms are interchangeable to a renderer, which
sees the same `{value, display_value, is_default}` rows either way.

### 3.4 Template source (P22)

```json
"source": { "template": { "id": "acme/sales/states_of_country.sql", "version": 3 },
            "datasource": "warehouse" }
```

`template` is the pipeline node's reference shape (`{id, version}`, both required; `id` is the
template's `name`, resolved in the active workspace). `datasource` is a datasource name visible
from the workspace (bound or global). The template must be `type='sql'` with a `dialect` equal to
the datasource's dialect. The SQL contract is §6 — two shapes, decided by `kind`:

- **`SELECT`**: the query returns the **option rows** (`value`, `display_value`, `is_default`; §6.2).
- **`INPUT`**: the query returns the **initial value** — at most one row with a single `value`
  column (§6.2a): the first day of the current fiscal period, the customer's price floor, the
  last run's end date. Zero rows ⇒ `default_value` applies; two or more ⇒ refused. Parents
  bind exactly as for a selector, so a database-fed input can depend on a selection.

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

### 3.6 The owner's amount example, complete

```json
{ "name": "min_order_amount", "label": "Minimum order amount", "type": "DECIMAL",
  "precision": 12, "scale": 2, "kind": "INPUT", "cardinality": "SINGLE", "required": false,
  "default_value": 0, "constraints": { "min": 0 },
  "presentation": { "control": "number", "format": { "kind": "currency" } } }
```

### 3.7 Presentation (P23c) — how a renderer may show it, never what it means

```json
"presentation": { "control": "calendar", "format": { "pattern": "dd MMM yyyy" } }
```

Both keys are optional. The server validates them against the closed catalogues below, stores
them, and echoes them in every evaluate response with the **derived default filled in** when a
key is absent — so a renderer always receives a `control`, and a set authored with none still
renders sensibly. Nothing in validation, binding, evaluation or `values` reads them.

**`control`** — allowed per kind × cardinality (× type where noted); the first entry is the
derived default:

| kind / cardinality | allowed `control` values |
|---|---|
| `INPUT` (always `SINGLE`), type `STRING` / `BINARY` | `text`, `textarea` |
| `INPUT`, numeric types (`INTEGER`, `BIGINTEGER`, `DECIMAL`, `BIGDECIMAL`) | `number`, `text` |
| `INPUT`, `BOOLEAN` | `toggle`, `checkbox` |
| `INPUT`, `DATE` | `calendar`, `text` |
| `INPUT`, `TIME` | `clock`, `text` |
| `INPUT`, `TIMESTAMP` | `datetime`, `text` |
| `SELECT` / `SINGLE` | `dropdown`, `radio`, `list` |
| `SELECT` / `MULTI` | `dropdown`, `checkboxes`, `list` |

A `control` outside its row is `parameter.validation.control_not_applicable`. The catalogue is
additive: a new value is a change-log row, never a bump.

**`format`** — display only; the wire value is always the canonical §3.1 form:

| type family | `format` shape | rule |
|---|---|---|
| numeric | `{"kind": "plain" \| "currency" \| "percent"}` | `currency` tells a renderer to use the deployment's currency, carried once per response as `org` (§5.3, from `datapipelines.org.currency-symbol` / `-name`, configuration §3.21). Decimal places come from `scale`, never from here. |
| `DATE`, `TIME`, `TIMESTAMP` | `{"pattern": "<java DateTimeFormatter pattern>"}` | ≤ 64 chars; must compile (`DateTimeFormatter.ofPattern`) and, for `DATE`, use no time fields (and vice versa) — `parameter.validation.format_pattern_invalid`. The client formats for display and parses back; what it submits is still ISO. |
| `STRING`, `BOOLEAN`, `BINARY` | none | any `format` is `format_invalid` |

The owner's examples read as: `type: DATE` + `control: calendar` + `format.pattern` → a calendar
widget; `SELECT`/`MULTI` + `control: checkboxes` (or `dropdown`, or `list`) → the same options,
rendered three ways.

---

## 4. Save-time validation (the order the validator runs)

1. Structural: JSON shape, the §3.2 field rules, the set-level rules (§3.1).
2. Graph: build `Dag<ParameterDefinition>` from `depends_on`; dangling → `dependency_unknown`;
   cycle → `dependency_cycle` with `details.cycle` (the `Cycle detected: a -> b -> a` path the
   builder already produces).
3. Expressions (§7): parse, cap depth/nodes, every `ref` ∈ `depends_on`, every literal coerced to
   its ref's type, cardinality rules.
4. Template pins (§6.1): resolve `{id, version}`; `type='sql'`; dialect = datasource dialect;
   the `${}` scan with the declared set = `depends_on` ∪ the tier keys (the existing
   `template.validation.parameter_interpolated`); every `:name` in the body resolves by P30 —
   a parameter of the set (then ∈ `depends_on`, else `bind_undeclared`), else a §7.2 org/platform
   key (served from the tier), else `bind_undeclared`; a `<name>_count` bind is the size of a
   `MULTI` parent (P29) and follows its parameter.
5. **Dry render** each selector template against a context of the parents' defaults (top-down,
   defaults where present, type-appropriate samples otherwise — templates §7.2's rule); a render
   failure is `parameter.validation.template_render_failed`.
6. **Metadata execution** of each rendered source template with `maxRows = 2` (§6.3): for a
   `SELECT` the three columns exist, `value`'s canonical type passes §6.4, `display_value` is
   `STRING`, `is_default` is `BOOLEAN`, and the rendered SQL contains an `ORDER BY` (§6.2); for an
   `INPUT` exactly the `value` column exists, its type passes §6.4, and the query yields at most
   one row against the parents' defaults (§6.2a). A datasource that cannot be
   reached is a refusal — `parameter.validation.datasource_unreachable` — because a set whose
   sources cannot be proven is not saved (owner's refuse-at-the-entry-point principle).
   Consequence, stated: a set cannot be saved while its datasource is down.
7. Persist (§8).

Steps 5–6 run for `update` as well as `create`; they are skipped for a body whose `body_hash`
is unchanged.

---

## 5. Evaluate — the runtime protocol

### 5.1 Request

`POST /api/v1/parameter-sets/{id}/evaluate` (and `parameter_sets_evaluate` by id):

```json
{ "version": 4,
  "selections": { "country": "USA", "state": "NY", "city": null, "min_order_amount": 250.00 } }
```

- `version` optional: a released version number, or absent = the served version (`current_version`);
  a DRAFT may be evaluated by its author with `version` = the draft's number (the working-version
  read rule, versioning §7.1). An MCP evaluate of a draft set whose pinned DRAFT template was
  updated after the key's last `templates_render` of it is refused with
  `parameter.evaluate.template_unrendered` — the 139 gate's twin, MCP-only.
- `selections` (P25, P27): the first render sends `{}` — every parameter **absent**, the whole set
  initialises. Every later call sends **every** parameter's current value, wire-encoded for its
  type, `MULTI` as an array: a value the user chose, the value the last response gave it, or
  `null` when the user **cleared** it (a `MULTI` too — `[]` is accepted and treated as `null`).
  Absent means "initialise me" and walks the priority (P26); `null` means "cleared" and does not. A key that names no parameter of the set is
  `parameter.evaluate.unknown_parameter` (400 — the whole request is refused; a client that sends
  unknown keys is wrong, not the user). A `MULTI` list longer than `max-multi-bind-values` is
  `too_many_values` on that parameter. Duplicate members in a `MULTI` are `invalid_value_type`.
- Every parameter's current value is read and submitted, hidden/disabled included, and explicit
  host-component or client-code changes with them: the flags never authorise omitting a value or
  substituting the originally served one.
- There is no `reset` list and no client dependency logic (P27): the server re-renders the whole
  set on every call, and a child whose value no longer fits walks the priority on the server.

### 5.2 Algorithm

1. Every supplied selection passes `ParameterValueValidator` (P28) for its declaration; a wire-form
   failure is recorded on that parameter (`invalid_value_type`) and treated as *absent* for the
   cascade, so the rest of the form still answers.
2. Build the `Dag` (from the stored, validated definition — never re-validated here).
3. Launch one coroutine per parameter (P16, the executor's pattern): each awaits its parents'
   completion, then evaluates itself; selector queries pass through an **instance-wide**
   `Semaphore` of `max-concurrent-selector-queries` (P31); the whole evaluate runs under
   `withTimeout(evaluate-timeout-seconds)`, and every selector statement is armed with
   `Statement.cancel()` at the deadline, its lease returned in `finally` — the deadline bounds
   blocking JDBC, not only the coroutine (P31).
4. Per parameter, in this order, on the parents' **effective** values:
   (a) `hidden` and `disabled` from the expressions — interaction flags only (P5); (b) the
   source — a `SELECT`'s options (`constants` verbatim, or the template rendered against
   `{parents' effective values} ∪ {<name>_count per MULTI parent} ∪ org tier ∪ platform tier`
   and run, §6.3; every returned row checked against the constants' invariants, §6.2) or an
   `INPUT`'s sourced row (at most one); (c) **the value, by the selection priority (P26)**:
   - `SELECT`, `SINGLE`: **1** the submitted value if it is among the options (`origin: client`);
     else **2** the configured default if among them (`default_value`, else the `is_default` row;
     `origin: default`); else **3** the first option (`origin: first`). A submitted value that
     fits none of the options walks to 2/3 with `reset: true`. An explicit `null` is **cleared**:
     `required_missing` when required, else `value: null`, `origin: none`. No options at all:
     `required_missing` when required, else `null`.
   - `SELECT`, `MULTI`: **1** the submitted members that are among the options (all of them
     valid ⇒ `origin: client`; some dropped ⇒ the survivors, `reset: true`); none surviving (or
     absent) ⇒ **2** the configured default members among the options; else **3** the first option
     as the only member. `[]` is cleared, as above.
   - `INPUT`: **1** the submitted value if it passes the constraints (an error otherwise, the
     parameter then treated as absent below); absent ⇒ **2** the sourced row when the input has
     a source (`origin: source`), else `default_value` (`origin: default`); else `null`
     (`required_missing` when required). An explicit `null` is cleared, as above.
   - In every case `computed_default` is what steps 2-then-3 give right now — for an `INPUT`
     the sourced row else `default_value` — so a renderer can offer "reset to computed" for a
     typed value that keeps winning after its parents changed (Astra's item 7, P26).
   - The parameter's **effective value** for its children is its `value` — the cleared or
     required-missing case binds `NULL` (an empty `MULTI` binds as §6.2 says), which the child's
     SQL handles; an errored `INPUT` binds its `computed_default`, so the form below stays whole.
5. A datasource or statement failure on one selector records the datasource code on that
   parameter, its options are empty, and its dependents evaluate against `NULL` — the response
   is always whole. A deadline hit fails the whole request with `parameter.evaluate.timeout`
   (one answer, never a half-form). A response over `max-evaluate-response-bytes` is
   `parameter.evaluate.response_too_large` (the options are never truncated silently; the
   author lowers the caps or the selector's rows).

Changing hidden/disabled state alone never changes a selection. Option invalidation, explicit
clearing and validation failures follow the rules above for every parameter, hidden or not.
Hiding a control does not suppress its errors or remove it from the dependency graph.

**The owner's three scenarios (2026-09-26), country → state → city, twenty parameters:**
(a) nothing passed — every parameter absent; country picks its default else first, its states
are pulled, state picks its default if among them else first, cities follow; the client gets a
fully chosen form and submits it back unchanged on the next change. (b) three of twenty passed
— the three are validated where they sit; below each, children compute their options from the
accepted value and pick by priority; a child that is also a parent is evaluated once, after its
parents and before its children; the seventeen absent ones pick by priority. (c) all passed and
a child no longer fits (New Jersey under Canada) — no error: the child walks to its default else
first, `reset: true`, and its own children follow from that value.

### 5.3 Response

```json
{ "id": "3f2a…", "name": "acme/sales/region_filters", "version": 4, "valid": true,
  "org": { "currency_symbol": "$", "currency_name": "USD" },
  "values": { "country": "USA", "state": "NY", "city": "New York", "min_order_amount": 250.00 },
  "parameters": [
    { …the §3.2 definition…,
      "dependents": ["state", "city"],
      "state": { "value": "USA", "origin": "client", "computed_default": "USA", "reset": false,
                 "hidden": false, "disabled": false,
                 "options": [ { "value": "USA", "display_value": "United States", "is_default": true }, … ],
                 "errors": [] } },
    { …"name": "state"…, "dependents": ["city"],
      "state": { "value": "NY", "origin": "first", "computed_default": "NY", "reset": true,
                 "hidden": false, "disabled": false, "options": [ … ], "errors": [] } },
    { …"name": "min_order_amount"…, "dependents": [],
      "state": { "value": 250.00, "origin": "client", "computed_default": 0, "reset": false,
                 "hidden": false, "disabled": false, "options": null, "errors": [] } }
  ] }
```

- `values` is the **consumer payload** (P4): one canonical wire value per parameter (arrays for
  `MULTI`; `null` when cleared or unresolved), hidden and disabled included — the values as
  chosen by the priority, so a consumer sees exactly what the form shows. A dashboard uses it
  to construct pipeline inputs, then applies any configured server-side outgoing-value
  overrides at the pipeline binding boundary (the dashboard draft §4.3); those overrides belong
  to the consumer, not this evaluator.
- `parameters[]` carries the full definition (a renderer is stateless — P3), `presentation`
  always present with derived defaults (§3.7), `dependents` and `depends_on` (both
  informational, P14), and `state`: `value`, `origin` (`client` / `default` / `first` / `source` /
  `none`), `computed_default`, `reset`, `hidden`, `disabled`, `options` (`null` for `INPUT`),
  `errors[]` in the house envelope shape. Options are echoed **once**, in `state.options`; a
  `constants` source is not repeated in the definition's echo.
- `valid` = no parameter has an error. A consumer **refuses** while `valid` is false and validates
  the outgoing values again with the shared validator (P28); the server remembers nothing
  between calls. On the first render a required `INPUT` with no default is `required_missing`
  and `valid: false` — expected until the user types, and a renderer shows it as pending, not as
  a fault.

### 5.4 What is an error and what is not

| situation | outcome |
|---|---|
| wrong wire form / not coercible / duplicate `MULTI` members | `parameter.evaluate.invalid_value_type` on the parameter |
| constraint violated (min/max/length/pattern/scale) | `parameter.evaluate.constraint_violation`, `details.reason` names the rule |
| required and nothing resolvable — cleared, or no default and no options / no row | `parameter.evaluate.required_missing` (`details.reason`: `cleared` / `no_default` / `no_options`) |
| a `SELECT` value no longer among the recomputed options | **not an error** — walks the priority, `reset: true`, `origin` says which step (P13, P26) |
| an optional parameter cleared | **not an error** — `value: null`, `origin: none`, binds `NULL` |
| a `MULTI` longer than `max-multi-bind-values`; a statement over `max-binds-per-statement` | `parameter.evaluate.too_many_values` / `parameter.evaluate.too_many_binds` on the parameter |
| selector datasource unreachable / statement failed / over the option cap / rows violating the invariants | the datasource's own code, `parameter.evaluate.too_many_options`, or `parameter.evaluate.selector_rows_invalid` (`details.reason`: `duplicate_value` / `null_value` / `empty_label` / `multiple_defaults`) on that parameter |
| a datasource no longer visible from the workspace (a grant revoked since release) | `datasource.not_found` on that parameter (re-resolved on every evaluate — P31) |
| unknown key in `selections` | whole request `parameter.evaluate.unknown_parameter` (400) |
| an `INPUT`'s source template returned two or more rows | `parameter.evaluate.input_source_multiple_rows` on the parameter; `default_value` used |
| the response over `max-evaluate-response-bytes` | whole request `parameter.evaluate.response_too_large` |
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
- **Large lists (P29).** The list itself never reaches the template; its size does, as
  `:regions_count` (an `INTEGER` bind, one per `MULTI` parent, named `<name>_count`). The
  library macro `<@in_list column="region" bind="regions" chunk=500/>` renders
  `(region IN (:regions__1) OR region IN (:regions__2))` for a list of 1,000 and the runner binds
  the slices; the skill teaches the pattern (the Oracle ceiling of 1,000 expressions per list is
  what it clears). Caps enforced at evaluate, each with its own code: `max-multi-bind-values`
  (default 1,000) per parameter and `max-binds-per-statement` (default 2,000 — SQL Server's
  2,100 floor) per rendered statement, counting every expanded placeholder.
- **Row invariants at evaluate (P31).** Every returned row is checked after canonical
  conversion exactly as `constants` are at save: `value` non-null and unique, `display_value`
  a non-empty string, `is_default` a boolean, at most one default — a violation is
  `selector_rows_invalid` on the parameter (the two-row save check proves the shape, never
  the invariants). `value` and `display_value` are capped at `max-option-value-chars` /
  `max-option-label-chars` (§11).
- The org tier and platform tier keys of pipeline-contract §7.2 are in the render context
  (`:org_currency_symbol`, `:current_date`, …) and need no `depends_on` entry (P30: a bind
  resolves first against the set's parameters, then against the tiers); `execution_id` is
  absent (there is none). A parameter named like a tier key shadows it, §7.2's precedence.
- `ORDER BY` is required (P7); the check is textual on the **rendered** SQL, after the
  `SqlStatementClassifier` gate (single `SELECT`/`WITH`, read-only — the same gate `sql_probe` runs).
  It proves a clause is present, not that the order is total: the author supplies the
  tie-breaker, and "the first option" is the first row the database returned.
- Row cap: `max-options-per-selector` (§11); the runner uses `maxRows = cap + 1` and refuses on
  overflow (`too_many_options`) — options are never truncated silently. Statement timeout: the
  datasource's `query_timeout_seconds`, clamped to `selector-query-timeout-seconds`.

### 6.2a The `INPUT` shape (a database-fed initial value)

```sql
SELECT MIN(order_date) AS value
FROM   orders
WHERE  customer_region = :region
```

Exactly one column, `value`, whose metadata type passes §6.4 against the input's declared type;
at most one row (the runner uses `maxRows = 2` and refuses a second row —
`input_source_multiple_rows`); zero rows ⇒ `default_value`. No `ORDER BY` requirement (there is
no "first" to pick). Binds, the read-only gate, the timeout and the org/platform tiers are as
for a selector. `display_value` / `is_default` are refused here (`selector_columns_invalid`
names the extra column).

### 6.3 Execution

`SelectorRunner` renders via `TemplateEngine.render(ref, context)` and executes through one
`ConnectionLease` on the datasource's pool with the `SqlRunner` discipline (`queryTimeout`,
`fetchSize`, `maxRows`), reading rows through `ResultRowReader` so `value` arrives as a canonical
typed value and the schema through `ResultRowReader.schemaOf(rs.metaData, dialect)`. Every
selector query is an audit-visible read on the datasource exactly as a `datasources_preview_rows` is.

### 6.4 Type compatibility of `value` (P11, P31)

Compared from the result-set **metadata** (`ColumnSchema.type`, `precision`, `scale`), never from rows.
"Identical" means the full descriptor — type, precision and scale. A widening must preserve **both**
the fractional digits and the integer digits: for `DECIMAL(p,s) → (P,S)`, `S ≥ s` **and**
`P − S ≥ p − s` (Astra's counterexample: `DECIMAL(6,2)` → `BIGDECIMAL(6,4)` satisfies `P ≥ p, S ≥ s`
and cannot hold `9999.99`). Every value read is then validated against the declared descriptor
by the shared validator (P28), so a metadata mismatch the driver under-reports still refuses.

| selector column type → declared parameter type | outcome |
|---|---|
| identical descriptor | accept |
| `INTEGER` → `BIGINTEGER` | accept |
| `INTEGER` → `DECIMAL(P,S)` / `BIGDECIMAL(P,S)` | accept only when `P − S ≥ 10` (Int32's digits), or `P` unbounded |
| `BIGINTEGER` → `BIGDECIMAL` | accept only with `P` unbounded |
| `DECIMAL(p,s)` (exact) → `DECIMAL(P,S)` / `BIGDECIMAL(P,S)` | accept when `S ≥ s` and `P − S ≥ p − s`, or `P` unbounded with `S ≥ s` |
| `DECIMAL` with **omitted scale** (approximate source) → any exact declared type | **refuse** — the source is a float; the author casts |
| `STRING` → `DATE`/`TIME`/`TIMESTAMP`/numeric | **refuse** — parsing text by inspecting rows is exactly the guess P11 forbids |
| `TIMESTAMP` → `DATE`, `BIGINTEGER` → `INTEGER`, `BIGDECIMAL` → `DECIMAL`, any narrowing of `P − S` or `S` | **refuse** — lossy |
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

### 8.1 Tables (the next free `V__` migration in `modules/app` at dispatch — V38 is main's latest on 2026-09-26, so V39 unless a lane claims it first; the id column is the UUID P24 addresses)

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

### 9.1 MCP tools (the count read from the tree at dispatch — `McpToolCatalog.NAMES` + 6; every pinned count moves in the same commit: `McpServerWiringTest`, `DatasourcesCreateRemovedTest`, `ScopeMatrixSpecDriftTest`, `McpServerAutoConfiguration`'s KDoc, mcp-server.md, the rendered manual's structure guard from 242a)

Addressing is the pipelines' (P24): `list` by name/prefix, everything else by the set's **id**.

| tool | mutating | permission (§9.3) | what |
|---|---|---|---|
| `parameter_sets_list` | no | `parameter_set.read` | prefix listing by name, the pipelines/templates shape; promoter lens; each row carries the id |
| `parameter_sets_get` | no | `parameter_set.read` | by id: the working version + `upgrade_available` per pinned template |
| `parameter_sets_create` | yes | `parameter_set.create` | §3 body; `confirm_new_root`; answers the new id |
| `parameter_sets_update` | yes | `parameter_set.update` | by id; hash-preconditioned draft write |
| `parameter_sets_evaluate` | no | `parameter_set.evaluate` | by id; §5 |
| `parameter_sets_purge_draft` | yes | `parameter_set.version.manage` | by id; versioning §5.4 |

Release, discard, restore, switch and promotion are REST/UI verbs for pipelines and templates
today (no `templates_release` tool exists); parameter sets follow — no MCP release tool in round one.

### 9.2 REST (`/api/v1/parameter-sets`, mirroring the **pipelines** routes — P24)

`POST /` (create; answers the id), `GET /?prefix=` (list / one tree level by name), `GET /{id}`
(working version), `PUT /{id}` (draft write, hash precondition), `GET /{id}/versions`,
`GET /{id}/versions/{v}`, `POST /{id}/release`, `POST /{id}/draft/discard` (purge the draft),
`POST /{id}/versions/{v}/discard`, `POST /{id}/versions/{v}/restore`, `DELETE /{id}/versions/{v}`
(purge a version), `POST /{id}/current` (switch), `DELETE /{id}` (the entity purge),
`GET /{id}/export`, `POST /import` (keeps the exported id — `PipelineImportService`'s rule),
**`POST /{id}/evaluate`**. A multi-segment name is proven through the real HTTP stack in the E2E
(it appears only in bodies and `?prefix=`). Envelopes and codes per rest-api §4.

### 9.3 Roles — per-action permission rows of the keys-v2 catalog (auth.md §7.6; AGENTS.md: each row lands with its handler, its `RolePermissions` entries, its §7.6 row and its `RoleWalkE2eTest` expectation in ONE commit)

The template rows are the mould (`template.read` … `template.switch_version`, `template.evaluate`);
no `RestOperation`, no key scope — the retired model is not recreated (Astra's item 2).

| permission | routes / tools | viewer | author | promoter | ws_admin | super_admin | mcp:author | mcp:promoter | mcp:ws_admin |
|---|---|---|---|---|---|---|---|---|---|
| `parameter_set.read` | every `GET`; `parameter_sets_list`, `parameter_sets_get` | ✓ | ✓ | lens | ✓ | ✓ | ✓ | lens | ✓ |
| `parameter_set.create` | `POST /`; `parameter_sets_create` | ✗ | ✓ | ✗ | ✓ | ✓ | ✓ | ✗ | ✓ |
| `parameter_set.update` | `PUT /{id}`; `parameter_sets_update` | ✗ | ✓ | ✗ | ✓ | ✓ | ✓ | ✗ | ✓ |
| `parameter_set.version.manage` | draft/version discard, restore, purge; `parameter_sets_purge_draft` | ✗ | ✓ | ✗ | ✓ | ✓ | ✓ | ✗ | ✓ |
| `parameter_set.delete` | `DELETE /{id}` | ✗ | ✓ | ✗ | ✓ | ✓ | ✓ | ✗ | ✓ |
| `parameter_set.import` | `POST /import` | ✗ | ✓ | ✗ | ✓ | ✓ | ✓ | ✗ | ✓ |
| `parameter_set.release` | `POST /{id}/release` | ✗ | ✓ | ✗ | ✓ | ✓ | ✓ | ✗ | ✓ |
| `parameter_set.switch_version` | `POST /{id}/current` | ✗ | ✓ | ✗ | ✓ | ✓ | ✓ | ✗ | ✓ |
| `parameter_set.evaluate` | `POST /{id}/evaluate`; `parameter_sets_evaluate` | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ | ✗ | ✓ |

Promotion rides the existing `promotion.read` / `promotion.promote` rows (the promotion page
gains parameter-set rows, by name, the export carrying the id). The two transport key roles
(`api_caller`, `promotion_receiver`) hold no row: an `endpoint` or `server` key never reaches
`/api/v1/parameter-sets` (`ScopeInterceptor.reachableBy`). The guards: `ScopeMatrixSpecDriftTest`
(the catalog and every column against §7.6), `MatrixRowReachabilityTest` (each row declared by a
handler), `ReadFloorTest` (every `GET` on its lowest admitting row), `RequiredScopeCoverageTest` /
`RequiredScopeKonsistTest` (`@RequiredScope` on every handler), `PermissionSeamE2eTest` (the
isolated-permission witness reaches the nine new pairs), `RoleWalkE2eTest` (the doc row is the
expectation).

---

## 10. Error codes — pipeline-contract.md **§13.20 Parameter sets** (new section — §13.16 is the MCP surface and §13.19 schedules, Astra's item 12; `ParameterErrorCodes` in `modules/parameters`; a `ParameterErrorCodesSpecDriftTest` parses §13.20 the way `PipelineErrorCodesSpecDriftTest` parses §13; the skill's `references/error-codes.md` gains the family and `:modules:mcp-server:skillArtifacts` runs in the same commit — `SkillDistributionTest`)

| code | HTTP | when |
|---|---|---|
| `parameter.validation.name_invalid` | 400 | set name fails the folder grammar (`details.reason`: `folder_required` / `grammar`) or a parameter name fails §6.1's |
| `parameter.validation.new_root_requires_confirmation` | 400 | agent surface only; the pipelines/templates shape |
| `parameter.validation.duplicate_name` | 409 | set name exists in the workspace (discarded included) |
| `parameter.validation.duplicate_parameter` | 400 | two parameters share a name |
| `parameter.validation.too_many_parameters` | 400 | over `max-parameters-per-set` |
| `parameter.validation.label_invalid`, `description_too_long`, `type_invalid`, `precision_missing`, `scale_missing`, `kind_invalid`, `cardinality_invalid`, `default_type_mismatch`, `default_not_an_option`, `source_missing`, `source_ambiguous`, `source_not_allowed`, `constraints_on_select`, `constraint_not_applicable`, `constraint_invalid`, `pattern_invalid`, `format_invalid` | 400 | §3.2–§3.7 |
| `parameter.validation.presentation_invalid`, `control_not_applicable`, `format_pattern_invalid` | 400 | §3.7 |
| `parameter.validation.option_invalid`, `option_duplicate`, `multiple_defaults`, `too_many_options` | 400 | §3.3 constants |
| `parameter.validation.dependency_unknown`, `dependency_self`, `dependency_cycle`, `bind_undeclared`, `ref_undeclared` | 400 | §3.2 `depends_on`; `dependency_cycle` carries `details.cycle` |
| `parameter.validation.expression_invalid`, `expression_depth_exceeded`, `expression_too_large`, `expression_cardinality`, `expression_literal_type`, `expression_type_unsupported` | 400 | §7 |
| `parameter.validation.template_not_found`, `template_version_not_found`, `template_type_mismatch`, `template_dialect_mismatch`, `template_render_failed` | 400 | §4 steps 4–5 (the §12.6 twins) |
| `parameter.validation.datasource_not_found`, `datasource_unreachable` | 400 | §3.4, §4 step 6 |
| `parameter.validation.selector_columns_invalid`, `selector_value_type_mismatch`, `selector_order_by_missing` | 400 | §6 |
| `parameter.validation.default_invalid` | 400 | a default (hard-coded, a marked option, a sourced row at save's dry run) fails the parameter's own constraints — `min: 0` with `default_value: -1` (P25, Astra's item 5) |
| `parameter.evaluate.unknown_parameter` | 400 | §5.1 — a whole-request refusal (the caller's error) |
| `parameter.evaluate.too_many_values`, `too_many_binds`, `selector_rows_invalid` | inline, on the parameter | §5.1 (a `MULTI` over `max-multi-bind-values`), §6.2 (a statement over `max-binds-per-statement`; a row violating the option invariants — `details.reason`) |
| `parameter.evaluate.response_too_large` | 413 | §5.2 step 5 — a whole-request refusal over `max-evaluate-response-bytes` |
| `parameter.evaluate.invalid_value_type`, `constraint_violation`, `required_missing`, `too_many_options`, `input_source_multiple_rows`, `selector_value_type_mismatch` | 200 (in `errors[]`) | §5.4 — per-parameter, the request itself succeeds |
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

## 11. Configuration (`datapipelines.parameters.*`, configuration.md §3 gains the block; `ParametersConfigKeysSpecDriftTest` the way the other `*ConfigKeysSpecDriftTest`s pin theirs; `ConfigValidatorCheckCountTest` bumps)

| key | default (proposed; the lane may tune, the key is the contract) | bounds |
|---|---|---|
| `max-parameters-per-set` | 64 | 1–256 |
| `max-options-per-selector` | **200** (was 1000 — the response budget below is the real lever; P31) | 1–10000 |
| `max-multi-bind-values` | 1000 (P29 — Oracle's per-list floor, cleared by the macro) | 1–1000 |
| `max-binds-per-statement` | 2000 (P29 — SQL Server's 2,100 floor, every expanded placeholder counted) | 1–2000 |
| `max-option-value-chars` | 1024 | 1–65536 |
| `max-option-label-chars` | 256 | 1–4096 |
| `max-input-length` | 4096 | 1–65536 |
| `max-expression-depth` | 16 | 1–64 |
| `max-expression-nodes` | 128 | 1–1024 |
| `max-regex-steps` | 100000 (P31 — the read budget of the counting `CharSequence` a `pattern` runs over; past it the match is refused as `constraint_violation`, `details.reason = "pattern_budget"`) | 1000–10000000 |
| `evaluate-timeout-seconds` | 30 | 1–300 |
| `selector-query-timeout-seconds` | 10 | 1–`evaluate-timeout-seconds` |
| `max-concurrent-selector-queries` | 4 (instance-wide — one semaphore per process, like the execution slots) | 1–32 |
| `max-evaluate-response-bytes` | 4194304 (4 MiB; P31 — `response_too_large` over it, never a truncated form) | 65536–67108864 |

Bound to `ParametersProperties` (`@ConfigurationProperties`, module-structure §8.3) and mirrored
in the domain `ParametersConfig` — both literals live as named constants (MISTAKES: the
`mapOf(… to 180)` detekt trap), and `ConfigValidator` checks the bounds. `selector-cache-ttl-seconds`
(the 2026-09-21 review's fix 3) is **not** in round one: an options cache would need
authority-aware keys and invalidation; the E2E prints the query count per evaluate instead, and
the knob lands when the number says it must.

---

## 12. Testing requirements (every guard shown red once; the lane's handback lists each falsification)

| layer | what | red-once proof |
|---|---|---|
| `graph` | `DagTest` runs unchanged from its new home | the rename commit alone — `git diff -M` 100% |
| `typesystem` | coercion/encoder tests moved; `pipeline-contract` still green | flip one coercion rule, both modules red |
| `parameters` unit | validator (every §10 validation code reached by a fixture), expression parser + evaluator (every op, both cardinalities, null/empty rules, caps), type-compatibility table (§6.4, every row), reset semantics, hidden/disabled selection retention and validation, `MULTI` list expansion (against the pinned spring-jdbc, incl. the empty list) | one fixture per row; delete the rule → its fixture red |
| `parameters` unit — evaluator concurrency | two independent selectors run concurrently (measured with a latch, never a sleep — MISTAKES "synchronise on the event"); a child never starts before its parent completes; the semaphore bounds in-flight queries; the deadline fails the whole request | remove the await → the ordering test red |
| `parameters` integration (`*IntegrationTest`, Postgres container) | repository + every §3.5 lifecycle verb; hash precondition; one-draft index | as templates' |
| `application` | reverse arrow: used-by rows and the delete guard cover a set-only pin | delete the set scanner → guard lets the delete through, test red |
| `web` + `mcp-server` | handler/tool tests; `MatrixRowReachabilityTest`, `ReadFloorTest`, `RequiredScopeCoverageTest` green with the new rows; catalog count pins | remove `@RequiredScope` → red |
| `typesystem` — the shared validator | `ParameterValueValidatorTest`: every declaration field × every refusal; the four call sites (published endpoint query strings, `POST …/execute`, evaluate, a dashboard-shaped consumer stub) refuse the same value with the same code; `" 12.50 "` refused everywhere — the trim's old tests re-pinned to refusal | reintroduce the trim → four reds |
| `parameters` unit — the selection priority (P26) | the owner's three scenarios (§5.2) as fixtures over a 20-parameter fixture with a parent-that-is-a-child; absent vs `null` vs `[]` on required and optional; a stale child walks to default then first with `reset: true`; a `MULTI`'s survivors, then default members, then the first option alone; an `INPUT`'s client → sourced row → `default_value` → `required_missing`; `computed_default` on every state | flip step 2 and 3 → the fixtures name the step |
| `parameters` unit — capacity and bounds | §6.4's counterexamples (`DECIMAL(6,2)` → `(6,4)` refused, `INTEGER` → `DECIMAL(9,0)` refused, `→ (10,0)` accepted); a `pattern` past `max-regex-steps` refused within the budget (a nested-quantifier fixture, adversarial input, elapsed printed); a `MULTI` of 1,001 and a statement of 2,001 placeholders refused; a response over the budget refused; a selector whose `Statement` blocks is cancelled at the deadline and its lease returned (a latch, never a sleep) | each bound one off → green (the guard is the bound, not a coincidence) |
| `parameters` integration — row invariants | a selector returning a duplicate value, a null value, an empty label, two defaults — each `selector_rows_invalid` with its reason; a revoked datasource grant between release and evaluate → `datasource.not_found` on the parameter | — |
| E2E (`tests/integration-tests`) | the country → state → city cascade over a real H2/Postgres datasource through **REST and MCP** with a multi-segment name proven through the real HTTP stack (bodies and `?prefix=` only — P24): first render, parent change resets children with `reset: true`, hidden/disabled from expressions, an `INPUT` with `scale: 2` + `min: 0` refusing `12.345` and `-1`, a `MULTI` parent binding into `IN (:regions)`, a `constants` selector beside a template one, a database-fed `INPUT` (`start_date` from a one-row template depending on `country`: picked from the row when the client sends it absent, kept when the client submits a value while its `computed_default` follows the country, `required_missing` when the client clears it with `null`), every `presentation` echoed with derived defaults filled in, an unreachable datasource answering a whole form; release refused on a draft pin; `RoleWalkE2eTest` rows; `PromoterLensSweepTest` and `WorkspaceIsolationSweepTest` extended | the sweeps' non-vacuity floors |
| drift | `ParameterErrorCodesSpecDriftTest`, `ScopeMatrixSpecDriftTest`, `ParametersConfigKeysSpecDriftTest`, `SkillDistributionTest`, `verifyModuleDependencies`, `ArchitectureGuardTest` | add a code to the doc → red until the constant exists |
| coverage | the module floors the conventions plugin sets (Kover) | — |

The hidden/disabled cases must include non-default valid selections for both `INPUT` and
`SELECT`, including `MULTI`: each survives submission and binds into dependent parameters.
Toggle only hidden/disabled state and prove the value is unchanged. Invalid submissions
still follow the ordinary validation/reset rules. Exercise these cases through REST and
MCP as well as the evaluator, so a transport cannot silently omit non-editable values.
Also change a hidden/disabled parameter explicitly in client code and prove the new current
value is submitted and evaluated rather than replaced with the originally served value.

Every gate runs through `scripts/gate.sh` on the merge SHA (memory: gate every merge); the E2E
module runs with `-Pdp.test.forks.e2e=1` once before handback (MISTAKES: fork-count blind spot).

---

## 13. Out of scope, by name (and who owns each next)

| item | next owner |
|---|---|
| Any UI page (list, editor, reference renderer) | dashboards (#10) or its own issue |
| Dashboards, charts | #10 |
| Binding a parameter set to a pipeline's `parameters` / an endpoint's inputs (`parameter.in_use` is reserved for it) | the consumer design — with the one contract change it needs named here: pipeline parameters are scalar today, so a `MULTI` selection binds to nothing until the pipeline declaration's list `cardinality` (P28) is adopted; a values map is never passed through as-is |
| A scheduled consumer of a parameter set: the default-time origin (occurrence time vs actual start), the timezone, a frozen resolved-value snapshot | the scheduler's later slice (scheduler revision §5, §6 — slice 1 uses literal pipeline inputs, unchanged by this engine) |
| A selector options cache (`selector-cache-ttl-seconds`) | when the E2E's printed query count says so; authority-aware keys and invalidation are its price |
| Server-side dashboard hide/show and enable/disable overrides, and outgoing parameter-value overrides before pipeline execution | [dashboard draft §4.3](2026-09-25-dashboard-authoring-design-draft.md#43-server-side-parameter-state-and-outgoing-value-overrides); the parameter engine remains decoupled |
| A textual expression syntax compiling to §7's AST; GraalJS | a later design; the AST is the contract either way |
| Partial / patch submissions (transport-level, semantics unchanged) | only if payload size becomes a measured problem |
| Option search, typeahead, pagination | when a selector exceeds the cap in practice |
| Label localisation | — |
| Per-user saved selections / bookmarks | dashboards |
| An MCP release/discard/restore tool | none exists for pipelines or templates either; one decision for all three |
| A caller-supplied "fixed" parameter kind (a value nobody picks, injected by the embedder) | **considered and rejected by the owner 2026-09-21** (draft 2's `FIXED`); a consumer passes such values straight to the pipeline's `parameters`, not through the form |

---

## 13a. The renderer and host contract — notes for the round after dashboards (owner, 2026-09-26)

Not built here; kept so the contract round starts from the owner's list, not from memory. The
reference renderer lands with dashboards (#10, P21); the hosting application implements the
host side.

1. **Clearing.** When the user clears a dropdown (or a multi-select), the client sends `null`
   for that parameter — never `{}`, never `[]`. The server treats `[]` as `null` (§5.1) so a
   renderer that slips is not punished, but the contract says `null`.
2. **Rendering a `MULTI` with no hint.** When a definition carries no `presentation.control`, a
   renderer picks the derived default (§3.7: `dropdown`). On the AUTHORING side, the agent must
   **ask the person** whether a multi-selection is a dropdown, checkboxes or a list before
   creating it without a hint — a rule of the agent manual's `parameters` area (lane D writes it),
   the same shape as `confirm_new_root`.
3. **The submit hook.** The renderer exposes a "submit" event (the apply button, or whatever
   control the host chooses) so the dashboard runtime can take the current `values` (§5.3), send
   them to the consumer (dashboard execute) and refresh the dashboard. The parameters endpoint
   is never the target of this event (P27).
4. **The parent-changed hook.** The renderer exposes a "parameter changed" event (any control:
   input, dropdown, checkbox, radio) so the dashboard runtime can submit every parameter to
   evaluate (P27) and re-render the parameter widget from the response — the whole set, with
   `reset`/`origin`/`computed_default` driving what the renderer shows.
5. **Layout and location.** The dashboard defines where the parameter widget sits (layout,
   position, collapse); the hosting application renders it there. The contract for that — the
   widget's slot, sizing and the events above — is written **after the dashboard
   implementation**, when the two sides exist to test against.

---

## 14. Open items (the lane confirms; none blocks dispatch)

1. The exact HTTP status for `parameter.evaluate.timeout` per rest-api §4's existing mapping of
   executor deadlines (the doc, not memory, decides).
2. Whether `readonly` datasource classification (datasources.md §7D) already covers the selector
   runner's gate or a second classifier call is needed — reuse `SqlStatementClassifier` either way.
3. `TemplateRef`'s package (templates vs pipeline-contract) decides whether `parameters` declares
   `pipeline-contract` at all; the §2.4 row allows it, the build file lists it only if compiled against.
4. The V-number: the next free one at merge time (V38 is main's latest on 2026-09-26; in-flight
   lanes may claim numbers first).
5. The MCP tool count and every pinned literal: read from the tree at dispatch, never from this
   record (Astra's item 12).
6. Whether the published-endpoint place (query-string values) needs a decoding step before the
   shared validator or the validator accepts the string forms directly — the lane reads
   `EndpointServeController` and says which.

---

## 15. Lane split and build order (one-then-parallel)

| lane | scope | depends on |
|---|---|---|
| **A** (first, alone) | §2.1 `graph` move + §2.3 coercion extraction **made strict** (the trim retired — the one behaviour change, recorded as a deliberate break in rest-api's change log with the re-pinned tests) + `ParameterValueValidator` in `typesystem` wired into the two existing places (published endpoints, `POST …/execute`) + the pipeline declaration's optional `constraints`/`cardinality` (declared, not yet consumed) + the §2.4 table rows + `settings.gradle.kts`. Full gate. | — |
| **B** | `parameters` model, validator, expression AST, `Dag` build, repository + lifecycle + migration, `ParameterErrorCodes` + §13.20 + drift test, config keys | A |
| **C** | `SelectorRunner` (template render, binds, list expansion, metadata check, caps), `ParameterEvaluator` (coroutines, the instance-wide semaphore, the deadline with `Statement.cancel()`, the P26 selection priority, the row invariants, the caps) | B |
| **D** | surfaces: six MCP tools by id + catalog pins + the rendered manual's `parameters` area (242a's `DocSet`, not `skillArtifacts`) + the `in_list` macro in the skill and the ask-before-creating-a-MULTI-without-a-hint rule (§13a.2); REST routes by id; the nine permission rows (§9.3) with auth.md §7.6 + `RoleWalkE2eTest`; the reverse arrow in `application` (§8.4); promotion; E2E cascade | C |

B and C may run in parallel only after A has frozen the module layout and B has frozen the
model (`ParameterDefinition` + `ParameterErrorCodes`); D is serial after C. Each lane prompt
carries the roles section (AGENTS.md §4.9) and the security-review list (memory: security pass
on every handback).

---

## Change log

| date | version | change |
|---|---|---|
| 2026-09-26 | draft 5.1 | Cleared is `null` for `MULTI` too (`[]` accepted as `null`); new §13a — the renderer and host contract notes for the round after dashboards: clearing, the ask-before-a-MULTI-without-a-hint authoring rule (lane D's skill), the submit and parameter-changed hooks for the dashboard runtime, the widget's layout and location left to that round. |
| 2026-09-26 | draft 5 | The second review answered (Astra's twelve items + the orchestrator's six of 2026-09-21) with eight rulings, P24–P31: addressing mirrors pipelines (a stable UUID, `POST /{id}/evaluate`); `required` means a value must arrive and `default` is a hint, absent vs `null`/`[]`; the selection priority client → default → first for `SINGLE`, `MULTI` and (client → sourced row → `default_value`) `INPUT`, a stale child never an error (`reset: true`, `origin`, `computed_default`); every change submits every parameter and the client runs no dependency logic (`dependents` informational too); one strict shared validator at the four places, the trim retired everywhere (a deliberate break); `MULTI` binds via `<name>_count` + the `in_list` macro with caps 1,000 per parameter and 2,000 per statement; binds resolve by namespace (parameter, else tier key); decimal widening preserves integer digits; row invariants at evaluate; a read-counting regex budget, `Statement.cancel()` at the deadline, an instance-wide semaphore, options cap 200 and a 4 MiB response budget; §13.20, V39, the tool count read at dispatch; the options cache and the scheduler/dashboard bindings named out of scope. |
| 2026-09-25 | draft 4 | Owner corrections: hidden/disabled governs control interaction; always read and submit current selected values, including explicit client-side changes. No preservation/restoration of originally served values or hidden/disabled-based omission, ignoring or defaulting. Updated P5/P15, evaluate semantics and acceptance coverage. Dashboard state and outgoing pipeline-value overrides are independently optional server-side consumer responsibilities, linked in §13. |
| 2026-09-21 | draft 1 | Recovered from the owner's Codex conversation of the same day; four follow-up decisions (P18–P21) and the template-backed selector ruling (P22) added; every code fact re-verified on `b34bdddf`. |
| 2026-09-21 | draft 3 | P23 rewritten after the owner rejected `FIXED`: two kinds only — `INPUT` (always `SINGLE`; initial value hard-coded or from a one-row template, §3.4/§6.2a) and `SELECT` (`SINGLE`/`MULTI`; options hard-coded or from a template); `format` grows into a `presentation` block (§3.7: `control` per kind × cardinality × type, `format` incl. temporal patterns) echoed with derived defaults; the `inputs` map, step 0 and the seven `FIXED` codes are gone; `input_source_multiple_rows` added; §13 records the rejection. |
| 2026-09-21 | draft 2 | P23 — hard-coded values both ways: §3.3 states that `constants` is the default way to populate a selector and SQL is optional; new `kind: FIXED` (§3.7) with the evaluate request's `inputs` map (§5.1), step 0 of the algorithm (§5.2), the response shape (§5.3), five whole-request codes (§5.4, §10) and two save-time codes; the E2E row (§12) covers both. |
