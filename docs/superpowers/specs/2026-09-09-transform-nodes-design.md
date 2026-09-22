# Design: TRANSFORM nodes — JSONata and JavaScript as template types, one node type, one contract, tested like code

**Status:** v0.2, DRAFT for owner ratification (2026-09-17). Supersedes v0.1 (2026-09-09) in
place; the six framing decisions of v0.1 stand, three are sharpened, and eight are new
(§0). Every choice below was made in conversation with the owner on 2026-09-17 and is
recorded with its reason. GitHub #7 (JSONata) and #8 (JavaScript) point here.

**Not packaged into the product.** `modules/web` copies an explicit allowlist of root
`docs/*.md` into the jar; the `docs/superpowers/` tree is outside it (`DocsCatalog` names it
as excluded). The product spec lands in `pipeline-contract.md` (node type, §13 codes),
`templates.md` (the two types, the contract blocks), `dag-executor.md` (the engine seam,
limits), `metadata-db.md` (the columns), `configuration.md` (the knobs) and the skill when
the implementation round ships — that round's prompt cites this record.

**Provenance of external facts.** The GraalVM sandboxing guide (`graalvm.org/latest`) was
read on 2026-09-09 and again on 2026-09-17 (§4.4); Maven Central and the
`dashjoin/jsonata-java` README on 2026-09-09. Version numbers are recorded as the floor the
implementer verifies against, never as the pin.

---

## 0. Decisions

| # | Decision | Rationale |
|---|---|---|
| **D-T1** | **No new entities.** `jsonata` and `javascript` are two new values of `TemplateType` (today `sql`, `html`). The UI and the skill present three faces — SQL, HTML, Transform — over one entity. | The lifecycle is where the safety lives: draft-first, hash preconditions, the pin rule, `in_use` on purge, the audited verbs, the RBAC matrix, export and promotion. A second entity is a second, slightly different copy, and the difference is where the next incident hides. A face costs a type filter, a reference doc and an editor pane. |
| **D-T2** | **One node type, `TRANSFORM`.** The language is the pinned template's type. The node declares `inputs` and `output`; the mode comes from the template's contract. | The contract (inputs, staging, typing, deadline, refusals) is identical across languages; only the evaluator differs. |
| **D-T3** (sharpened) | **Engines behind one seam, in-process.** `dashjoin/jsonata-java` in round one; GraalJS as a polyglot isolate in round two. Out-of-process is never the default. | Both run in the JVM the executor owns and cancel through its deadline. The seam is a standalone service with three callers (§4.1). |
| **D-T4** (sharpened) | **A transform is a pure function driven by the executor.** It receives one JSON object and returns one JSON value. It has no handle to tempdb, no `emit`, no `query`, no network, filesystem, process, host classes or clock. Nothing is exported into a script. | The owner's 2026-09-17 ruling: iteration is the executor's job (`row` mode), and data acquisition belongs to a later family of ingestion nodes. Reproducibility becomes a theorem — the output is a function of the template version and its inputs — and the sandbox has no host entry points to audit. |
| **D-T5** (sharpened) | **Output types are declared and enforced; nothing is inferred and nothing is coerced.** The template's contract declares every input and the output; a value that does not fit is a refusal with a row number. Decimals cross as strings with declared scale. | T186's lesson at a new boundary: the wire has fewer types than the tables. With a mandatory contract the inference step of v0.1 disappears. |
| **D-T6** | **Order after this feature:** scheduler → lake materialization → alerts → metrics + dashboards → embedding with row-level security (the Tableau map, §13). | Unchanged. |
| **D-T7** | **A transform is code, so it carries a contract, invariants and tests on its version, inside the body hash.** Save runs the suite; release re-runs it. | The owner's reason for extending the semantic layer to transforms was testing: no data, wrong data and right data must all behave, and the assertions must stay in place. A test outside the version can be edited to pass against a body it no longer describes. |
| **D-T8** | **Scoped exception to D3.** SQL and HTML templates keep D3 exactly (no schema of their own; the pipeline's `parameters` is the single declaration point, proven by dry-render). The two transform types declare, and the pipeline conforms: a node's `inputs` must satisfy the pinned contract at pipeline save. | A SQL fragment only means something inside a pipeline; a transform is reused across pipelines and later dashboards and must be testable alone. Two declaration points drift only when neither is authoritative; here the template is. |
| **D-T9** | **No Freemarker on transform bodies.** Both types set `engine` to `none`; `${`, `<#` and `<@` in a body are refused at save. Deferred with an entry condition (§11). | JavaScript has no bind mechanism: `${param}` in a JS body is a caller-supplied string spliced into executable source — the injection SQL's `:name` rule exists to prevent. Everything a conditional render could do, an `if` inside the function does, with the input object as the value channel. Compile-once-per-version and a test suite that covers the real program both depend on the body never varying. |
| **D-T10** | **One value namespace: the Context.** The input object carries `rows`, `inputs` and `meta` — no `params`, no `context`. Every value a script reads is named in the node's `inputs` map (`$key` = a Context key of any tier; otherwise a staged table) and validated at save, the CALCULATOR convention. | The contract has one namespace with five tiers; v0.1 invented a split it does not have. A misspelt key in an unvalidated channel returns nothing in both languages; in `inputs` it is a save-time refusal. |
| **D-T11** | **Validation partitions or fails, chosen per node.** A contract may declare `rejects`; the function then returns `{ rows, rejects }`, both written to tempdb. `strict: true` on the node fails it on any reject. Default is partition. | A report of what could not be imported is more useful than a dead run; the chart-payload and business-logic cases get fail-fast with one flag. Nothing is inferred from the data. |
| **D-T12** | **Tests assert exact canonical-JSON equality or a refusal code; invariants run on every case and every real execution.** No tolerances. | A test that needs tolerance is a sign the output should be a declared DECIMAL with a scale. Invariants-only proves plausibility, not correctness. |
| **D-T13** | **Invariants are JSONata booleans, whatever the template's language.** | Assertions stay data, not code — the instinct behind release checks — so every transform's guarantees render in one place and cannot have side effects. |
| **D-T14** | **Two implementation rounds: JSONata first with the whole contract machinery; JavaScript second.** | The seam and the conformance suite are proven on the engine with no sandbox story before the engine with one arrives; the JS round measures serialisation cost on a working system rather than estimating it. |

---

## 1. Scope

**Round one (this record's gate, §10):** the two template types in the model; `engine: none`;
the contract, invariants and tests blocks with save/release gating; `implements` and the
drift marker; the `ScriptEngine` seam with the JSONata engine and the conformance suite;
the `TRANSFORM` node with all three modes, rejects and strict; the type gate and the codes;
`templates_evaluate`; the skill rule and reference; the explorer faces, the transform editor
face, the node card.

**Round two:** the JavaScript engine (§4.4) — the isolate artifact and its platform matrix,
the sandbox proofs, the boot-time unavailable path, the fallback knob, `console.log`
handling, the batch-serialisation measurement, the JavaScript editor pane.

**Out, by name (§11):** ingestion nodes; the dashboard runtime; Freemarker on transform
bodies; custom JSONata functions; contracts or tests on SQL/HTML templates; templates
calling templates; cross-batch state in `row` mode; user-installed modules.

---

## 2. Model

### 2.1 The types

`TemplateType.JSONATA("jsonata")`, `TemplateType.JAVASCRIPT("javascript")`. A transform
version's `engine` is `none` (the existing field; today always `freemarker`). Every rule that
applies to `sql`/`html` applies unchanged: folder-path names, draft-first, hash
preconditions, the pin rule at release, `template.in_use` on purge, the RBAC matrix, the
audited verbs, export and promotion.

**Body contracts.** JSONata: the body is one expression evaluated against the input object
(`$`). JavaScript (round two): the body defines `function transform(input) { … }`; anything
else it defines is private; no modules, no `require`, no `export`. A body is parsed at save
through the engine (`template.validation.syntax_error`, the existing code). A body containing
a Freemarker construct is refused with `template.validation.freemarker_forbidden` (D-T9).

**Compiled once.** A released version's body never varies, so the engine compiles it once
and caches by `(id, version)`; a draft is recompiled on each evaluate.

### 2.2 The three blocks (transform types only; D-T7)

Stored on `template_versions` as `contract_json`, `invariants_json`, `tests_json` — nullable,
with a CHECK that they are non-null exactly when `type IN ('jsonata','javascript')` — and
**included in the version's `body_hash`** (canonical JSON of body + the three blocks). SQL and
HTML versions carry nulls and hash the body alone, as today. Migration number assigned at
implementation (check the V-number against every open lane before choosing it — the V20
collision lesson).

```json
{
  "contract": {
    "mode": "row",
    "inputs": {
      "orders":    { "kind": "table", "columns": [
                       { "name": "order_id",     "type": "BIGINT" },
                       { "name": "amount_cents", "type": "BIGINT" },
                       { "name": "customer_id",  "type": "STRING", "nullable": true } ] },
      "tz":        { "kind": "value", "type": "STRING" },
      "min_total": { "kind": "value", "type": "DECIMAL", "precision": 12, "scale": 2 }
    },
    "output": { "kind": "table", "columns": [
                  { "name": "order_id",    "type": "BIGINT" },
                  { "name": "amount",      "type": "DECIMAL", "precision": 12, "scale": 2 },
                  { "name": "customer_id", "type": "STRING" } ] },
    "rejects": true
  },
  "invariants": [
    { "name": "customer_present",
      "expr": "$count(rows[customer_id = null]) = 0",
      "message": "every accepted row carries a customer id" },
    { "name": "one_to_one",
      "expr": "$count(rows) + $count(rejects) = $count(inputs.orders)",
      "message": "every input row is accepted or rejected, never lost" }
  ],
  "tests": [
    { "name": "empty input",
      "input":  { "rows": [], "inputs": { "orders": [], "tz": "UTC", "min_total": "0.00" } },
      "expect": { "output": { "rows": [], "rejects": [] } } },
    { "name": "missing customer is rejected",
      "input":  { "rows": [ { "order_id": 1, "amount_cents": 1250, "customer_id": null } ],
                  "inputs": { "orders": [ { "order_id": 1, "amount_cents": 1250, "customer_id": null } ],
                              "tz": "UTC", "min_total": "0.00" } },
      "expect": { "output": { "rows": [],
                              "rejects": [ { "row": { "order_id": 1, "amount_cents": 1250, "customer_id": null },
                                             "reason": "customer_id missing" } ] } } },
    { "name": "wrong shape is refused",
      "input":  { "rows": [ { "order_id": "x" } ], "inputs": { "orders": [ { "order_id": "x" } ], "tz": "UTC", "min_total": "0.00" } },
      "expect": { "refusal": "pipeline.transform.input_contract_violation" } }
  ]
}
```

**`contract`**

| Field | Rule |
|---|---|
| `mode` | `row` \| `table` \| `value`. The template is written for one mode: a `row` body reads `rows`; a `table`/`value` body reads `inputs.<name>`. `row` requires exactly one `table` input (`template.contract_invalid`, detail `row_mode_inputs`). |
| `inputs` | Name → `{ kind: "table", columns[] }` or `{ kind: "value", type, precision?, scale? }`. Names obey §6.1's `[a-z_][a-z0-9_]*`. At least one input. `columns[]` entries are `{ name, type, precision?, scale?, nullable? }` in the type system's logical types; `nullable` defaults to false. BINARY/BLOB is refused as an input column type (`transform_input_type_unsupported`). |
| `output` | `{ kind: "table", columns[] }` for `row`/`table`; `{ kind: "value", type, … }` or `{ kind: "object" }` for `value` (an object is written as-is to the Context — needed for nested API responses and chart payloads). `mode`/`output.kind` mismatch is `contract_invalid`. |
| `rejects` | Boolean, default false; legal only with a table output. When true the function returns `{ rows: [...], rejects: [ { row, reason } ] }` where `row` fits the single table input's columns (`row` mode) or the output columns (`table` mode) and `reason` is a non-empty string. When false the function returns a plain array (or one value). |

**`invariants`** — a list, possibly empty, of `{ name, expr, message }`. `expr` is a JSONata
expression over `{ rows, rejects, inputs }` (§5.4) that must evaluate to boolean `true`.
Each `expr` is compiled at save (`template.invariant_invalid` on a syntax error) and run on
every test case's output; a non-boolean result on any case is `invariant_invalid` with the
case named.

**`tests`** — a non-empty list of `{ name, input, expect }`. `input` is the input object of
§3.2 with `meta` optional (the runner supplies `{ node_id: "test", context_key: null }`).
`expect` is exactly one of `{ output }` — the function's return value, compared after
canonicalisation (§5.5) — or `{ refusal: "<code>" }` — the run must refuse with that code
(a type-gate refusal or an engine refusal; an invariant failure is `pipeline.transform.invariant_failed`).
**One case whose every table input and `rows` are empty is mandatory** (`template.contract_invalid`,
detail `empty_case_missing`): a transform with no test for zero rows does not save.

### 2.3 `implements` (outside the body hash)

A transform version may cite learned facts it implements: a list of fact ids, stored in a
join table `template_implements (template_id, version, fact_id)` so the reverse lookup is an
index read. It is **not** part of `body_hash` — citing a fact is a claim about meaning, not a
change of behaviour — and it is editable through `templates_update` on a draft or a released
version alike (the existing precondition applies; `implements` alone never bumps the version).
A cited fact must be visible from the active workspace and be a `WORKSPACE` fact of kind
`definition`, `exclusion` or `preference` (`template.implements_unresolved` otherwise).
`DATASOURCE` facts are excluded by rule: a transform never sees a datasource, so it cannot
implement a fact about one.

### 2.4 One behaviour per type

A `TemplateTypeBehaviour` per `TemplateType` owns: body validation, whether `dialect` is
required or forbidden, which blocks are required or refused, what `render`/`preview`
mean, and the test runner. The template services call the behaviour; no `when (type)`
accumulates in a service. The existing SQL/HTML conditionals move behind the same interface
in the same round (a refactor with parity tests, not a behaviour change).

---

## 3. The node

### 3.1 Shape

```json
{
  "id": "shape_orders",
  "description": "Order lines at reporting grain; cents to currency; refunds preserved.",
  "type": "TRANSFORM",
  "template": { "id": "acme/shape/order_lines.jsonata", "version": 3 },
  "inputs": { "orders": "stg_orders", "tz": "$org_timezone", "min_total": "$min_total" },
  "output": { "target": "tempdb", "table": "order_lines", "rejects": "order_lines_rejected" },
  "strict": false,
  "depends_on": ["stage_orders"]
}
```

| Field | Rule |
|---|---|
| `template` | Pinned like every pin; its type must be `jsonata` or `javascript` (`pipeline.validation.transform_template_type`). |
| `source` | **Forbidden** (`transform_source_forbidden`). A TRANSFORM is tempdb-only: it reads what upstream nodes staged plus the Context, and writes tempdb, the Context or the caller. It never touches a datasource — no dialect question, the read-only rule trivially true, every run reproducible from staged inputs. |
| `inputs` | The CALCULATOR convention. `"$name"` is a Context key of any tier; anything else is a tempdb table a node this one depends on stages. The key set must equal the contract's input names (`transform_input_contract`, detail names the missing/extra input); a table name no upstream stages or a `$key` nobody writes is `transform_input_unknown`; a value input whose Context type does not fit the contract's type is `transform_input_contract`. |
| `mode` | **Not on the node** — it is the contract's. (v0.1 put it here; a redundant copy is a drift point.) |
| `output` | Table output: `{ target: "tempdb", table, rejects? }` or `{ target: "caller" }` (the caller-node rules of §9 unchanged; `rejects` is refused on `caller`). Value/object output: `{ context_key }` obeying every calculator rule — §6.1 name shape, one writer per key, never shadowing a declared parameter (`calculator_output_collision` reused), topology-ordered. `rejects` present without the contract declaring it — or absent when it does — is `transform_rejects_undeclared` / `transform_rejects_missing`. |
| `strict` | Boolean, default false. Legal only when the contract declares rejects (`transform_strict_without_rejects`). |
| `depends_on` | Sequencing is topology, as for calculators: a `$key` written by another node is bindable only from a node that depends on its writer (`calculator_input_unordered` reused). |

**Every key a `value`-mode TRANSFORM writes is an implicit optional execute input**, exactly
like a calculator's (§4.10): supplied by the caller, the node is skipped and its stats carry
`provided_by: "caller"`; the supplied value is checked against the contract's output type.

### 3.2 The input object

```json
{ "rows": [ … ],                       // row mode only: one batch of the single table input
  "inputs": { "<name>": [ … ] | value }, // table inputs as arrays of objects; value inputs as JSON
  "meta":  { "node_id": "shape_orders", "context_key": null } }
```

`meta` is filled by the caller (the executor: `node_id`, `context_key`; the test runner:
`"test"`; a future dashboard runtime: whatever it needs), passed through untouched and never
read by the engine. It exists so one reusable template pinned by many nodes can stamp its
output with the key it runs as. Column names are the staged names; values are the JSON
projection of §6.

---

## 4. The engine seam and its callers

### 4.1 The seam

```kotlin
interface ScriptEngine {
    val type: TemplateType
    fun compile(body: String): CompiledScript                      // parse only
    fun evaluate(script: CompiledScript, input: Any, limits: EvaluationLimits): Any?
}
data class EvaluationLimits(val wallClock: Duration, val maxDepth: Int, val maxHeapBytes: Long?, val maxStatements: Long?)
```

Its own module (`modules/scripting`), depending on `typesystem` only. Input and output are
JSON-shaped values (maps, lists, scalars). The seam knows nothing of modes, contracts,
invariants, Context or tempdb. Three callers:

- **The node runner** (`modules/dag`): builds the input object, evaluates per batch or once,
  runs the type gate and the invariants, writes tempdb or the Context.
- **The template service** (`modules/templates` + `application`): the test runner at save and
  release; `templates_evaluate`; the editor preview.
- **The dashboard runtime** (future, out of scope): named so its design cites this seam rather
  than reopening it.

**Invariants** are evaluated by the JSONata engine through the same seam with the same
limits, whatever the template's type.

**One conformance suite** parameterised over every engine: input shape; each mode; the type
table both ways; each limit firing; cancellation from outside; a syntax error at compile;
determinism (same input → same output twice). Engine-specific tests cover engine-specific
facts only.

### 4.2 JSONata — `com.dashjoin:jsonata` (round one)

Floor 0.9.10 (Apache-2.0; Maven Central, read 2026-09-09 — the implementer pins the current
stable after verifying it). Pure Java, in-process, thread-safe; input and output cross as Java
maps/lists with no serialisation. Bounds: the library's `Frame.setRuntimeBounds(timeoutMs,
maxRecursionDepth)` carries `wallClock` and `maxDepth`; the node deadline of §4.11
(`settings.timeout_seconds`, lane 108) is the outer bound and cancels the thread. No custom
Java functions registered (a template's meaning is identical on every deployment). Numbers
are int/long/double — the reason for §6's decimal rule.

### 4.3 Bounds that are the executor's

- **Wall clock** — the node deadline is the outer bound for every mode; the engine's own
  timeout is set slightly lower so the engine reports first (`pipeline.transform.timeout`)
  and the executor's cancel (`pipeline.node.timeout`) is the backstop.
- **Rows** — `table`/`value` refuse when any table input exceeds
  `datapipelines.transform.max-input-rows` (default 100 000) BEFORE loading
  (`input_too_large`); `row` mode streams and has no cap on the input, but its invariants do
  (§5.4).
- **Value size** — a `value`/object output above `datapipelines.transform.max-value-bytes`
  (default 1 MB) is refused (`value_too_large`): a Context value is not a table.
- **Probe and tests** — `datapipelines.transform.evaluate-timeout-seconds` (default 10)
  bounds one `templates_evaluate` call and one test case; a suite is bounded by
  `datapipelines.transform.suite-timeout-seconds` (default 60).
- **Concurrency** — one evaluation per node at a time; a node is one unit of work.

### 4.4 JavaScript — GraalJS polyglot isolate (round two; facts kept here so they are not re-derived)

Read from the GraalVM sandboxing and embedding guides on 2026-09-09, re-read 2026-09-17:

- Stock OpenJDK 21 runs GraalJS either in interpreter-only fallback mode or through a
  **polyglot isolate** (`org.graalvm.polyglot:js-isolate-community`, floor 25.3.4.1;
  per-platform artifacts `linux-amd64`, `linux-aarch64`, `darwin-aarch64`, `windows-amd64`;
  glibc — matches the `eclipse-temurin:21-jre` image).
- **Sandboxing on Community requires the isolate** (since GraalVM 25.1). Policies
  `CONSTRAINED → ISOLATED → UNTRUSTED` build on each other; `ISOLATED`/`UNTRUSTED` need the
  isolate. The decision is **`SandboxPolicy.UNTRUSTED`**, one shared `Engine`, one `Context`
  per evaluation, `MaxCPUTime` = the node wall clock, `MaxHeapMemory` =
  `datapipelines.transform.js.max-heap-mb` (default 256), `MaxStatements` as a runaway-loop
  backstop, `MaxThreads` = 1, stdout/stderr to bounded buffers.
- **Under `UNTRUSTED`, host entry points are explicit only** (`@HostAccess.Export`), callback
  time counts toward the CPU limit, and **guest access to host arrays, lists, maps, buffers,
  iterables and iterators is disallowed**. Consequence (2026-09-17): nothing is exported
  (D-T4 needs nothing), and every value crossing the boundary is a scalar or a string —
  the input object goes in as one JSON string per evaluation (`JSON.parse` in the guest),
  the result comes back as one JSON string (`JSON.stringify` in the guest). One encode and
  one decode per batch per direction; **measured on a 100 000-row `row`-mode run before the
  round closes and the number recorded in the handback.** Rich proxy objects across the
  boundary would require dropping to `ISOLATED` and are not planned.
- **If the isolate cannot load**, the app starts with JavaScript **disabled** and says so once
  (`transform.js.unavailable` at boot; the node refuses at save with the same code) — never a
  silent fallback to the non-isolated interpreter, whose sandbox on Community is the weaker
  `CONSTRAINED`. Whether a documented opt-in knob (`datapipelines.transform.js.allow-fallback-runtime`,
  default `false`) ships is a round-two decision (§12).
- The implementer proves each limit and each host-access door with a test that violates it
  (`while (true)`, `new Array(1e9)`, a `Java.type` reference, a `fetch`) and reads the
  refusal back. A sandbox no test has breached is a belief.

---

## 5. Execution

### 5.1 Before the function runs

The executor checks each input against the contract once. Table inputs: the staged table's
column set and logical types (from staging metadata) must cover every contract column with
a compatible type; `nullable: false` columns must be NOT NULL in staging or the check scans
for nulls in the batch (`row`) or the loaded table (`table`/`value`). Value inputs: the
Context value's type must fit. Any mismatch is `pipeline.transform.input_contract_violation`
naming the input and column. Row caps (§4.3) apply here. The test runner and
`templates_evaluate` apply the same check to the JSON they are handed — `rows` and each
table input against the contract's input columns, each value input against its type — so a
wrong-data test case can expect this code (§2.2's third case).

### 5.2 Driving the function

- **`row`**: the executor reads the single table input with `result-batch-size`, builds
  `{ rows: batch, inputs: { <value inputs> }, meta }`, evaluates once per batch, and expects
  an array (or `{ rows, rejects }`) of **any length** — one row may become many or none
  (flat-map is the contract, v0.1's O-3). Output is inserted with `insert-batch-size`. The
  table never exists whole in the JVM, and **no state crosses batches** (§11).
- **`table`**: every table input loaded whole (capped), one evaluation, an array back.
- **`value`**: as `table`; one JSON value or object back, written to `context_key`.

### 5.3 The type gate

Every returned row is checked against the declared output columns: the key set must be
exactly the column set (`row_shape_mismatch`, with batch and row number); each value must
fit its logical type (`value_type_mismatch`, same detail); an integer beyond 2^53 or a
decimal with more scale than declared is `precision_lost`. Rejects pass the same gate
against the input's columns plus a non-empty `reason`. A `value` output is checked against
the declared type (or accepted as-is for `kind: object`) and against the size cap.
Mixing shapes — an array where `{ rows, rejects }` is declared, or the reverse — is
`row_shape_mismatch` at the top level.

### 5.4 After the last batch: invariants, then strict

Invariants evaluate over `{ rows, rejects, inputs }` materialised as JSON: `rows` and
`rejects` are read back from the written tempdb tables (the invariants see the real table,
not the JVM's memory), `inputs` holds the value inputs and, for `table`/`value` mode, the
loaded tables; for `row` mode the single table input is read back from tempdb. **The whole
object is bounded by `max-input-rows`** across rows + rejects + table inputs: a `row`-mode
node whose output exceeds it fails with `invariants_too_large` **if the template declares any
invariant**; with none declared the node streams without bound. The docs say so plainly: an
assertion over an unbounded table is a SQL node or a release check, not an invariant. Any
`expr` that is not exactly `true` fails the node with `invariant_failed` naming the invariant
and its message. Then `strict`: a non-empty rejects table with `strict: true` fails with
`rejects_strict`, carrying the count and the first ten reasons.

### 5.5 Atomicity, canonical form, stats

- **Failure is atomic.** Any refusal above rolls back the output and rejects tables the way a
  failed stage is rolled back (staging §4.3). A Context key is written only after invariants
  and strict pass.
- **Canonical JSON** (used by the test comparison and the gate): object keys sorted; integers
  as integers; doubles in shortest round-trip form; DECIMAL as a string normalised to the
  declared scale (`"12.5"` → `"12.50"`); DATE/TIMESTAMP/TIME as ISO-8601 strings normalised
  to the type system's canonical form; arrays in the order the function returned them.
- **Stats and events**: `rows_in`, `rows_out`, `rows_rejected`, `invariants_checked`, and for
  `value` mode `context_key` and `context_value`, through SSE and the run record like every
  other node. A failure is the standard node failure record.

---

## 6. Types at the boundary (D-T5)

| Logical type | JSON | Note |
|---|---|---|
| INTEGER / BIGINT | number | exact (int/long in JSONata-java; ≤ 2^53 in JS — larger is `precision_lost`, never rounded) |
| DECIMAL / NUMERIC | **string**, declared scale | JSON numbers are doubles in both engines; a decimal must not lose scale crossing twice |
| FLOAT / DOUBLE | number | |
| BOOLEAN | boolean | |
| DATE / TIMESTAMP / TIME | **string**, ISO-8601 | never epoch numbers: timezone and precision would be guessed |
| STRING / TEXT | string | |
| BINARY / BLOB | refused as an input column (`transform_input_type_unsupported`) | no JSON representation worth defining |
| NULL | null | |

The reverse direction is the contract's declared columns, enforced by §5.3. Nothing is inferred.

---

## 7. Codes (pipeline-contract §13; the drift tests move in the same commit)

| Code | HTTP | When |
|---|---|---|
| `pipeline.validation.transform_template_type` | 400 | pinned template is not `jsonata`/`javascript` |
| `pipeline.validation.transform_source_forbidden` | 400 | `source` on a TRANSFORM |
| `pipeline.validation.transform_input_unknown` | 400 | a table no upstream stages, or a `$key` nobody writes |
| `pipeline.validation.transform_input_contract` | 400 | `inputs` key set ≠ contract, or a value input's type does not fit |
| `pipeline.validation.transform_rejects_undeclared` / `_missing` | 400 | `output.rejects` vs the contract's `rejects` |
| `pipeline.validation.transform_strict_without_rejects` | 400 | `strict` on a contract without rejects |
| `pipeline.transform.input_contract_violation` | 500 | §5.1 |
| `pipeline.transform.input_too_large` | 500 | §4.3 |
| `pipeline.transform.input_type_unsupported` | 500 | §6 |
| `pipeline.transform.evaluation_failed` | 500 | the script threw; detail carries the engine message (bounded 2 000 chars, ErrorCodeMapper's rule) and, for JS, the line |
| `pipeline.transform.timeout` | 504 | the engine's own bound fired (`pipeline.node.timeout` is the outer) |
| `pipeline.transform.resource_limit` | 500 | heap / statements / depth; detail names which |
| `pipeline.transform.row_shape_mismatch` / `value_type_mismatch` / `precision_lost` | 500 | §5.3, with batch and row |
| `pipeline.transform.value_too_large` | 500 | §4.3 |
| `pipeline.transform.invariant_failed` | 500 | §5.4, invariant name + message |
| `pipeline.transform.invariants_too_large` | 500 | §5.4 |
| `pipeline.transform.rejects_strict` | 500 | §5.4, count + first ten reasons |
| `template.validation.freemarker_forbidden` | 400 | D-T9 |
| `template.contract_invalid` | 400 | §2.2, detail names the rule |
| `template.invariant_invalid` | 400 | a non-compiling or non-boolean invariant |
| `template.test_failed` | 400 | §8.1, case + assertion + bounded diff |
| `template.blocks_not_allowed` | 400 | contract/invariants/tests/implements on `sql`/`html` |
| `template.render_not_applicable` | 400 | `templates_render` on a transform type; detail points at `templates_evaluate` |
| `template.implements_unresolved` | 400 | §2.3 |
| `transform.js.unavailable` | 503 boot log / 400 at save | §4.4 (round two) |

---

## 8. Lifecycle and the semantic link

### 8.1 Save and release

**Save** of a transform version runs, in order: body parse; contract validation; invariant
compile; the test suite. Every case runs through the real engine under the version's limits;
its output passes the type gate against the contract; the invariants run on it; the result is
compared to `expect` after canonicalisation. The first failure refuses the save with
`template.test_failed` naming the case, the assertion (`output` diff bounded to 2 000 chars,
or the expected vs actual refusal code) and, for an invariant, its name. A draft is
re-validated on every update.

**Release** re-runs the suite on the version being released and refuses on the same code.
A suite that passed at save fails at release only if the engine changed — which is the case
worth catching.

**Pipeline save** checks each TRANSFORM node's `inputs` against the pinned version's contract
(§3.1): the dry-render analogue for transforms, where D3's "prove the two agree" survives.

### 8.2 Drift (D-S6 applied to code)

When a cited fact is retired or superseded, every version citing it is marked `needs_review`
**on read** — served with the marker, never edited, never auto-remapped. Pinning a
`needs_review` version at pipeline release is a **warning** in the release response and the
dialog, not a refusal: a fact edit never blocks a release on its own. Clearing the marker is a
deliberate act — cite the superseding fact, or remove the citation.

### 8.3 Discovery

`semantics_list` rows gain `implemented_by: [ { template_id, version } ]`; `templates_list`
accepts `implements: <fact_id>`. The skill's learn-first step gains its third half: search the
facts, find the definition, find the transform that implements it, reuse before writing.

---

## 9. Surfaces

**MCP.** The `templates_*` family gains no per-type names. `templates_create`/`_update` accept
`contract`, `invariants`, `tests`, `implements` and refuse them on `sql`/`html`
(`template.blocks_not_allowed`); `templates_get` returns them; `templates_list` accepts `type`
and `implements`. **One new tool, `templates_evaluate`** (`author`): `{ name, version?, input }`
runs the version over a caller-supplied input object under `evaluate-timeout-seconds`, applies
the type gate and the invariants, and returns `{ output, rejects, invariants: [ { name, passed, message } ] }`
— no staging, no Context. It is `sql_probe`'s twin and the service the editor preview and the
test runner share. `templates_render` refuses transform types with `template.render_not_applicable`
(400, detail `use: templates_evaluate`). **The render-before-you-run check (139,
`pipeline.execution.template_unrendered`) treats a successful `templates_evaluate` of a draft
transform version as its render** — without this, every draft pipeline pinning a draft
transform would be refused at execute forever; the check reads the same audit rows, keyed
by tool name, so it is one more name in its allow-set, pinned by a test.
`pipelines_execute_node` works for TRANSFORM unchanged. The tool count moves 41 → 42 and
every count (catalog, scope matrix, `tools.md`, the listing test) moves in one commit; the
`mcp.tool.called` audit row covers the new tool as it covers every other.

**UI.** The template explorer gets the three faces as a type filter over one tree. The
transform editor face has four panes — body (JSONata grammar vendored, pinned, no CDN),
contract, invariants, tests — and a **run suite** action that calls evaluate per case and
shows green/red with the diff. Save and release use the existing lifecycle dialogs. The
pipeline editor renders a TRANSFORM card with the language badge and a Details tab
(inputs, mode, output, rejects, strict), read-only as every node is today. A `needs_review`
version shows its marker in both explorers and on the card.

**Skill.** One playbook rule in generic wording: *SQL when SQL can; a transform when the
shape is nested, the logic is per-row, or SQL cannot say it; a transform is a pure function
over staged data with a declared contract and a test suite — write the empty and wrong-data
cases before the right-data one.* A new reference `transforms.md` (node shape, input object,
contract/test format, codes); the error-codes reference gains the three families; `tools.md`
regenerates; the skill artifacts task runs in the same commit; core stays within its line
budget. Zero dataset facts (the skill-is-generic guard).

**Published endpoints.** A `value`/object TRANSFORM with `caller` output returns its JSON as
the endpoint body — the nested-response case. Nothing else in the endpoint contract changes.

---

## 10. Testing requirements (the gate for round one; every guard shown red on purpose)

1. Conformance suite over the JSONata engine, parameterised so a second engine adds cases to
   nothing.
2. Type behaviour: `sql`/`html` refuse the blocks; transform types require them; Freemarker
   in a transform body refused; the empty case enforced; `mode`/`output.kind` consistency.
3. Test runner: a failing case refuses save with case + diff; release re-runs; canonicalisation
   ignores key order and decimal spelling; an `expect.refusal` matches only that code; the
   suite timeout fires.
4. Node validation for every §7 400-row; the three drift tests extended for every new code
   (`SECTION_13_ROW_COUNT` re-derived, not incremented).
5. Executor per mode on the H2 staging: `row` never materialises the input (peak staged rows
   via the existing memory-budget hooks); `table`/`value` caps refuse before loading; rejects
   and strict; rollback of both tables on failure; invariants read the written tables;
   `invariants_too_large` on an over-cap `row` output with invariants and streaming without;
   a Context key written only after everything passes; caller-supplied override skips the
   node.
6. The type table both ways, DECIMAL scale round-trip, the 2^53 refusal.
7. Semantic link: a DATASOURCE fact and an invisible fact refused; a retired fact marks every
   citing version on read; release with a `needs_review` pin warns and proceeds; both
   discovery filters return the link.
8. `templates_evaluate` through the real dispatcher with a key principal, plus its audit
   event; `templates_render` refusal on a transform type.
9. One E2E: stage from Postgres + lake → TRANSFORM `row` with rejects → TRANSFORM `value` to
   a Context key → a SQL node binding `:that_key` → caller output → the endpoint returning the
   nested object.
10. Browser tests for the transform face, the run-suite action, the node card and the
    `needs_review` marker, with screenshots under the existing shot conventions.
11. Behaviour-object parity: the SQL/HTML rules moved behind `TemplateTypeBehaviour` produce
    byte-identical refusals on the existing template suites.

---

## 11. Out of scope, by name (this record; the next design that owns each)

| Deferred | Why not now | Where it goes |
|---|---|---|
| **Ingestion nodes** — file acquisition, parsing, cursors, lake commit, quarantine replay | its own security and durability story; composes WITH TRANSFORM rather than extending it (owner 2026-09-17) | the dp-lake ingestion design (2026-09-16 discussion) |
| **The dashboard runtime** — call the engine with held data, push chart payloads over SSE | not a pipeline; needs only the §4.1 seam | dashboards design |
| **Freemarker on transform bodies** | D-T9 — injection without a bind mechanism | entry condition: the SQL `parameter_interpolated` refusal ported to JS, plus a case an `if` cannot express |
| **Custom JSONata functions** (`$decimal()`, date helpers) | a template's meaning must be deployment-independent; measure the need on round-one usage | round two or later |
| **Contracts / tests on SQL and HTML** | reopens D3 in full; the HTML-KPI case is real but not this round | a later design, render tests first |
| **Templates calling templates** | compose through the DAG | — |
| **Cross-batch state in `row` mode** (running totals) | breaks the streaming guarantee; a `table`-mode node or SQL does it | — |
| **User-installed modules / npm** | supply chain inside the sandbox | — |
| **A tempdb query/emit handle for scripts** | ruled out 2026-09-17 (D-T4) | ingestion nodes, if ever |

---

## 12. Open items

**v0.1's O-1..O-5, resolved 2026-09-17:** O-1 two rounds, JSONata first (D-T14). O-2 objects
allowed in `value` mode, typed by the contract (`kind: object`). O-3 flat-map yes, by
construction (§5.2). O-4 (`console.log` as warnings) and O-5 (the fallback knob) are round-two
decisions. The ledger's T223 listed a different five under the same labels from memory; this
record is the authority.

**New, for round two:** R2-1 `console.log` → node warnings capped at `MaxOutputStreamSize`, or
dropped? (lean: warnings). R2-2 ship the fallback knob, default off, or refuse outright?
(lean: refuse outright unless a real deployment needs it). R2-3 the measured serialisation
cost decides whether `row`-mode batches for JS default to `result-batch-size` or a smaller
`transform.js.batch-size`.

---

## 13. The Tableau map (D-T6, unchanged from v0.1, condensed)

Extracts → scheduler + lake write-back; calculated fields → a `metric` template type;
alerts/subscriptions → scheduled pipeline + a `value` TRANSFORM or calculator + notification;
embedding with RLS → endpoints + dashboards; catalog/lineage → the DAG in the explorer;
blending → the tempdb join; Ask/Explain Data → the agent; Prep flows → pipelines + TRANSFORM.

---

## Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-09 | v0.1 | orchestrator, after the owner's brainstorm | Initial record: six decisions; engine and sandbox facts verified against graalvm.org and Maven Central; decimal-as-string rule. |
| 2026-09-17 | v0.2 | orchestrator, after the owner's second brainstorm | Rewritten in place. Pure function only — no tempdb handle, no `emit` (D-T4); one Context namespace, `params`/`context` removed from the input object (D-T10); `meta` block; mode moves to the template's contract; **contract, invariants, tests on the version inside the body hash** (D-T7), a scoped D3 exception (D-T8); no Freemarker on transform bodies (D-T9); rejects + strict (D-T11); exact canonical equality + invariants on every case (D-T12); invariants always JSONata (D-T13); `implements` + `needs_review` drift + discovery (§8); `templates_evaluate` returns invariants; two rounds, JSONata first (D-T14); the `UNTRUSTED` host-collection rule and its JSON-text consequence recorded for round two (§4.4); `values` in the result payload considered and dropped (dashboards are a separate runtime); v0.1's O-1..O-5 resolved. |
