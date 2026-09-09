# Design: TRANSFORM nodes — JSONata and JavaScript as template types, one node type, two sandboxed engines, one contract

**Status:** DRAFT for owner ratification (2026-09-09). Six framing decisions were agreed in
conversation the same day (D-T1–D-T6 below, "yes" to all six); the rest of this record turns
them into a buildable contract and lists what still needs a ruling (§9).

**Not packaged into the product** (`docs/superpowers/` is excluded from the jar). The product
spec lands in `pipeline-contract.md` (node type, §13 codes), `templates.md` (the two types),
`dag-executor.md` (the engine seam, limits), `configuration.md` (the knobs) and the skill
when the implementation round ships — that round's prompt cites this record.

**Every external fact below was read from its source on 2026-09-09, not recalled:** the
GraalVM sandboxing guide and embedding guide (`graalvm.org/latest`), Maven Central via
`central.sonatype.com`, and the `dashjoin/jsonata-java` README. Version numbers are pinned
here so the implementer verifies drift, not existence.

---

## 0. Decisions

| # | Decision | Rationale |
|---|---|---|
| **D-T1** | **No new entities.** JSONata and JavaScript are two new values of the existing `TemplateType` enum (`jsonata`, `javascript`; today `sql`, `html` — `TemplateType.kt:21`, round 046). "Script" / "Transformer" are labels on a type filter, never tables. | The whole lifecycle comes for free — D55–D63, V19's columns, the eleven audited verbs, the §3.5 table, 102's dialogs, the explorer, `templates_*` MCP tools, the pin rule, the drift tests. Two new entities = 101 rebuilt twice and kept in sync forever. |
| **D-T2** | **One node type, `TRANSFORM`.** The language is the pinned template's type; the node declares `inputs`, `mode` (`row` \| `table` \| `value`) and `output`. No `JAVASCRIPT`/`JSONATA` node types. | The contract (inputs, staging, typing, deadline, refusals) is identical across languages; only the evaluator differs. One node type keeps the executor, the validator, the editor and the skill to one story. |
| **D-T3** | **Engines: `dashjoin/jsonata-java` and GraalJS via polyglot isolates, both embedded; out-of-process is the fallback, not the default.** | Both run in the JVM the executor already owns, cancel through the executor's deadline, and need no second process to deploy. The isolate gives JavaScript a real sandbox on the Community build (§4). |
| **D-T4** | **Pure functions in v1: no network, no filesystem, no process, no host classes, no clock/random dependence guaranteed reproducible.** `f(inputs, params, context) → output`. An `HTTP`/side-effect node is a separate later design with its own security story. | Keeps the sandbox provable and the executor's "every node is bounded and cancellable" promise intact. |
| **D-T5** | **Output types are declared or inferred-then-enforced; never silently coerced.** JSON has no DECIMAL/DATE; a value that does not fit the column's logical type is a refusal (`value_type_mismatch`), not a cast. Decimals cross the boundary as strings with declared scale. | T186's lesson at a new boundary: the wire has fewer types than the tables. |
| **D-T6** | **Order after this feature:** 092 scheduler → lake materialization (write-back to dp-lake as the "extract" store) → data-driven alerts/subscriptions → metrics (a `metric` template type, not an entity) + dashboards → embedding with row-level security. | The Tableau map (§8): each is one design record and one or two rounds; none adds an entity. |

---

## 1. Scope of the first round

**In:** the two template types (create/update/get/render-preview through the existing
tools and UI, with a language-aware editor pane); the `TRANSFORM` node type with all three
modes; the `ScriptEngine` seam with the two engines; the conformance suite; the limits and
codes; `templates_evaluate` (the agent's probe — §6); the skill rules; the editor's node
card and Details tab for the new type.

**Out (deliberate):** HTTP/side-effect nodes; user-installed npm modules or JSONata
extensions; scripts calling other scripts (a pin of a `javascript` template from another —
compose through the DAG instead); a script marketplace; running scripts against a
datasource directly (a TRANSFORM reads staged tables and context only — §2.3).

---

## 2. Model

### 2.1 The template types

`TemplateType.JSONATA("jsonata")` and `TemplateType.JAVASCRIPT("javascript")`. A body is
validated at write (D59-style early refusal): JSONata is parsed (`jsonata(expr)` throws on a
syntax error → `template.validation.syntax_error`, the existing code); JavaScript is parsed
by the engine without executing (`Source` + `Context.parse`) → the same code. Every rule that
applies to `sql`/`html` templates applies unchanged: draft-first, hash preconditions, the
pin rule at release, `template.in_use` on purge.

The **JavaScript contract**: the body must define `function transform(input) { … }` and
return the mode's shape (§2.2). Anything else the body defines is private. No `export`, no
modules, no `require`. The **JSONata contract**: the body is one expression evaluated
against the input object.

### 2.2 The node

```json
{
  "id": "shape_quarterly",
  "type": "TRANSFORM",
  "template": { "id": "nyc/shape/quarterly.jsonata", "version": 3 },
  "inputs": { "trips": "stg_trips", "zones": "stg_zones", "threshold": "$rain_threshold_mm" },
  "mode": "table",
  "output": { "target": "tempdb", "table": "quarterly", "columns": [ … ] },
  "depends_on": ["stage_trips", "stage_zones"]
}
```

| Field | Rule |
|---|---|
| `template` | Pinned like every other pin; its type must be `jsonata` or `javascript` (`pipeline.validation.transform_template_type` otherwise). |
| `inputs` | Name → staged table (a tempdb table name this execution produces upstream — validated against the DAG the way `depends_on` is) or `"$name"` (a Context key / declared parameter, CALCULATOR's convention). At least one input. |
| `mode` | `row`: the script sees ONE row of the single table input at a time (exactly one table input required; context inputs allowed) and returns one object (or `null` to drop the row). `table`: the script sees every input table as an array of objects, bounded (§4.3). `value`: like `table`, but the result is a scalar/object written to a Context key. |
| `output` | `row`/`table`: `{ target: tempdb, table, columns? }` or `{ target: caller }` (the last node only, as today). `value`: `{ context_key }` (§6.1's `[a-z_][a-z0-9_]*`, the CALCULATOR rule). |
| `output.columns` | Optional list of `{ name, type, precision?, scale? }` in the typesystem's logical types. Absent → inferred from the first batch (§3.2) and then enforced. |

The **input object** every engine receives is the same shape:

```json
{ "rows": {...}|[...], "inputs": { "<name>": [...] }, "params": { "<declared parameter>": v }, "context": { "<key>": v } }
```

`row` mode: `rows` is the one row (an object); `table`/`value`: `inputs.<name>` are arrays;
`params` and `context` are always present. Column names are the staged names; values are
the JSON projection of the logical types (§3.1).

### 2.3 Where it sits in the DAG

A TRANSFORM is a **tempdb-only** node: `source` is implied and refused if present
(`pipeline.validation.transform_source_forbidden`). It reads what upstream nodes staged and
writes to tempdb, the Context or the caller. It never touches a datasource. That keeps the
"which datasource, which dialect" questions out of scripts entirely, keeps the readonly rule
trivially true, and keeps every script reproducible from the staged inputs.

`row` mode streams: the executor reads the input table with `result-batch-size`, evaluates
per row, inserts with `insert-batch-size` — the table never exists whole in the JVM.
`table`/`value` load the inputs into the engine heap and are bounded (§4.3).

---

## 3. Types at the boundary (D-T5)

### 3.1 Out: logical type → JSON

| Logical type | JSON | Note |
|---|---|---|
| INTEGER / BIGINT | number | Exact in JSONata-java (int/long) and JS (up to 2^53; larger → string — `value_precision_lost` refused, not silently) |
| DECIMAL / NUMERIC | **string** (`"12.50"`) | JSON numbers are doubles in both engines (`jsonata-java` "fits the result into an int, long, or double"); a decimal must not lose scale crossing twice. The engine input carries the scale in a sibling `columns` map so a script that wants arithmetic knows to parse. |
| FLOAT / DOUBLE | number | |
| BOOLEAN | boolean | |
| DATE / TIMESTAMP / TIME | **string**, ISO-8601 | Never epoch numbers: timezone and precision would be guessed. |
| STRING / TEXT | string | |
| BINARY / BLOB | refused as an input column (`transform_input_type_unsupported`) in v1 | No JSON representation worth defining yet. |
| NULL | null | |

### 3.2 In: JSON → logical type

Declared `output.columns` win. Otherwise the first batch (or the whole table in `table`
mode) infers: JSON number with no fraction → BIGINT; with fraction → DOUBLE; string → TEXT;
boolean → BOOLEAN; null-only → TEXT with a warning; object/array → refused in a table
output (`transform_output_not_scalar`) — nest only in `value` mode or a `caller` output.
**After inference the types are enforced**: a later row that does not fit is
`transform_value_type_mismatch` with the row number and column — the node fails, the table
is rolled back like a failed stage (staging §4.3's rule). A ragged row (extra/missing keys)
is `transform_row_shape_mismatch`. No widening on the fly; an author who wants DECIMAL
declares it and returns strings.

`value` mode writes the JSON as-is to the Context key (objects allowed) — the Context is
already JSON-shaped; consumers (`:name` binds, CALCULATOR inputs, a `caller` output) take
what they can and refuse what they cannot, as today.

---

## 4. The engines and the sandbox (D-T3)

### 4.1 The seam

```kotlin
interface ScriptEngine {
    val language: TemplateType
    fun compile(body: String): CompiledScript          // parse only; syntax errors here
    fun evaluate(script: CompiledScript, input: Any, limits: EvaluationLimits): Any?
}
data class EvaluationLimits(val wallClock: Duration, val maxStatements: Long?, val maxHeapBytes: Long?, val maxDepth: Int)
```

One implementation per language; **one conformance suite** parameterised over both
(the "one suite covers all implementations" rule): input shape, each mode, the type table of
§3, the limits firing, cancellation from outside, a syntax error at compile, determinism
(same input → same output twice). Engine-specific tests only for engine-specific facts.

### 4.2 JSONata — `com.dashjoin:jsonata` 0.9.10 (Apache-2.0; Maven Central, latest as of 2026-09-09)

Pure Java, no I/O of its own, thread-safe, ~4× faster than JSONata4Java per its README.
Bounds: `Jsonata.Frame.setRuntimeBounds(timeoutMs, maxRecursionDepth)` — the README's own
limits, used for `wallClock` and `maxDepth`; the executor's node deadline (108) is the outer
bound and cancels the thread. No `$eval` of arbitrary strings beyond JSONata's own; the
function set is JSONata's standard library only (no custom Java functions registered in
v1). Numbers: int/long/double — the reason for §3.1's decimal rule.

### 4.3 JavaScript — GraalJS **polyglot isolate**, Community, `org.graalvm.polyglot:js-isolate-community` 25.3.4.1 + `polyglot` (MIT/UPL; Maven Central, latest as of 2026-09-09)

Facts from the GraalVM guides (2026-09-09):

- Stock OpenJDK 21 runs GraalJS either in **fallback (interpreter-only) mode** — "Execution
  without runtime compilation will negatively impact the guest application performance" —
  or through a **polyglot isolate**, which the runtime table lists as the only optimising
  path on "JDK 21 runtimes". The isolate is a Native-Image-compiled JS runtime loaded as a
  library, "with a dedicated garbage collector and JIT compiler", per-platform artifacts
  (`linux-amd64`, `linux-aarch64`, `darwin-aarch64`, `windows-amd64`). The app image is
  glibc (`eclipse-temurin:21-jre`, chosen for argon2's native lib) — the same reason this
  loads.
- **Sandboxing on Community requires the isolate**: "Sandboxing is available on Oracle
  GraalVM and, starting with GraalVM 25.1, on GraalVM Community Edition when using the
  corresponding isolate artifacts (for example, `js-isolate-community`)." The policies
  `CONSTRAINED → ISOLATED → UNTRUSTED` build on each other; `ISOLATED`/`UNTRUSTED` need the
  isolate. `CONSTRAINED` alone already: "Disallows native access / process creation / system
  exit / host file or socket access / environment access / host class loading / access to
  all public host classes and methods by default" and "Requires redirection of the standard
  output and error streams".
- Resource limits available with the isolate: `sandbox.MaxCPUTime`, `sandbox.MaxStatements`,
  `sandbox.MaxHeapMemory`, `sandbox.MaxThreads`, `sandbox.MaxASTDepth`,
  `sandbox.MaxOutputStreamSize`, `sandbox.MaxErrorStreamSize`.

The decision: **`SandboxPolicy.UNTRUSTED` in an isolate**, one `Engine` per instance
(shared, so the isolate is created once), one `Context` per evaluation (cheap; no state
leaks between nodes or executions), `MaxCPUTime` = the node's wall clock, `MaxHeapMemory` =
`datapipelines.transform.js.max-heap-mb` (default 256), `MaxStatements` as a runaway-loop
backstop, `MaxThreads` = 1, stdout/stderr to bounded buffers that surface in the node's
warnings (a `console.log` is a diagnostic, capped, never a channel). Host access: none.
`allowIO`: none. The implementer proves each limit with a test that violates it (a `while
(true)`, a `new Array(1e9)`, a `Java.type` reference) and reads the refusal code back — a
sandbox no test has breached is a belief.

**If the isolate cannot be loaded** on the deployment (unsupported platform), the app starts
with JavaScript **disabled** and says so once (`transform.js.unavailable` at boot, and the
node refuses at save with the same code) — never a silent fallback to the interpreter-only
non-isolated engine, whose sandbox on Community is the weaker `CONSTRAINED`. A config knob
`datapipelines.transform.js.allow-fallback-runtime` (default `false`) lets an operator
opt into that weaker mode knowingly (documented in one paragraph: what it gives up).

### 4.4 Bounds that are the executor's, not the engine's

- **Wall clock** — the node deadline 108 introduces (`node-timeout-seconds`, per-node
  override) is the outer bound for every mode; the engine's own timeout is set slightly
  lower so the engine reports it first and the executor's cancel is the backstop.
- **Rows** — `table`/`value` refuse inputs above `datapipelines.transform.max-input-rows`
  (default 100 000) with `transform_input_too_large` BEFORE loading them; `row` mode has no
  row cap (it streams) but keeps the staging memory budget.
- **Output size** — `value` results above `datapipelines.transform.max-value-bytes` (default
  1 MB) are refused: a Context value is not a table.
- **Concurrency** — one evaluation per node at a time (a node is one unit of work); the
  isolate's `MaxThreads=1` enforces it inside the engine too.

---

## 5. Codes (pipeline-contract §13, new family `pipeline.transform.*` + the two validation rows)

| Code | HTTP | When |
|---|---|---|
| `pipeline.validation.transform_template_type` | 400 | Pinned template is not `jsonata`/`javascript` |
| `pipeline.validation.transform_source_forbidden` | 400 | A `source` on a TRANSFORM node |
| `pipeline.validation.transform_input_unknown` | 400 | An input names a table no upstream node stages, or a `$key` nobody declares |
| `pipeline.validation.transform_mode_inputs` | 400 | `row` mode without exactly one table input |
| `pipeline.transform.input_too_large` | 500 | §4.4 row cap |
| `pipeline.transform.input_type_unsupported` | 500 | §3.1 BINARY etc. |
| `pipeline.transform.evaluation_failed` | 500 | The script threw; detail carries the engine message (bounded 2 000 chars, ErrorCodeMapper's rule) and, for JS, the line |
| `pipeline.transform.timeout` | 504 | The engine's own bound fired (the node deadline's `pipeline.node.timeout` is the outer one) |
| `pipeline.transform.resource_limit` | 500 | Heap/statements/AST-depth limit; detail names which |
| `pipeline.transform.output_not_scalar` / `row_shape_mismatch` / `value_type_mismatch` / `precision_lost` | 500 | §3.2 |
| `pipeline.transform.value_too_large` | 500 | §4.4 |
| `transform.js.unavailable` | 503 at boot log / 400 at save | §4.3 |

The three drift tests couple these to the constants and the status map — same commit.

---

## 6. Agents and the UI

- **`templates_evaluate`** (MCP, `author`): `{ name, version?, input }` → the script's
  output over a caller-supplied input object, boxed at ~10 s and the §4 limits, no staging.
  The `sql_probe` twin (107): an agent iterates a script the way it iterates SQL. The
  editor's Preview pane for these types calls the same service.
- **Skill**: the playbook gains "SQL when SQL can; JSONata to shape (API responses, parameter
  derivation, pivots); JavaScript when neither can; a transform is a pure function over staged
  data — it never reaches a datasource"; the error-codes table gains the family; `tools.md`
  regenerates for the new tool. Core stays ≤ 400 lines.
- **Editor**: the node card shows the language badge; Details shows inputs/mode/output; the
  template editor's body pane switches syntax highlighting by type (the vendored highlighter
  already does SQL; JS/JSON grammars are two vendored files, pinned, no CDN).
- **Published endpoints**: a `value`-mode TRANSFORM whose output is `caller` returns the
  JSON object as the endpoint's body — the "nested API response" case. The endpoint's
  read-only guard is unaffected (a TRANSFORM never writes anywhere but tempdb/Context).

---

## 7. Testing requirements (the gate)

1. Conformance suite over both engines (§4.1), including the limits going red on purpose.
2. Node tests per mode with the H2 staging: row streaming never materialises the table
   (assert peak staged rows via the existing memory-budget hooks), table/value caps refuse
   before loading.
3. The type table of §3, both directions, per engine, with DECIMAL scale round-trip and the
   2^53 refusal.
4. Validation tests for every §5 400-row; the three drift tests.
5. One E2E: stage → TRANSFORM(row) → TRANSFORM(value → context) → `:name` bind downstream →
   caller output, on Postgres + lake inputs.
6. Sandbox proofs (§4.3): each limit and each host-access door, red on purpose, plus the
   boot-time "isolate unavailable" path with the artifact removed from the classpath.
7. `templates_evaluate` over both languages through the real dispatcher with a key principal.

---

## 8. The Tableau map (what to take after this, D-T6)

| Tableau does | dp answer | Lands as |
|---|---|---|
| **Extracts** (Hyper; scheduled, incremental) | The scheduler (092) + a **lake write-back** target: a pipeline materialises to Parquet in dp-lake on a schedule; DuckDB reads it back at interactive speed. | 092, then "lake materialization" design |
| **Calculated fields / LOD / metrics** | A `metric` **template type** (name, expression over a pipeline output, grain), versioned like everything else; dashboards and endpoints read the same definition. | dashboards design |
| **Data-driven alerts, subscriptions** | Scheduled pipeline + condition (a `value` TRANSFORM or CALCULATOR) + notification target (email/Slack/webhook). Agents author these naturally. | after 092 |
| **Embedding with row-level security** | Endpoints + dashboards hosted by the client app, filtered by a user attribute bound at the key/session. | dashboards + endpoints |
| **Catalog / lineage** | The DAG is lineage; surface per column and per endpoint in the explorer. | explorer round |
| **Blending across databases** | The tempdb join — already the core; say so on the site. | marketing |
| **Ask Data / Explain Data** | The MCP agent. Already better. | — |
| Prep flows | Pipelines + TRANSFORM. | this design |
| Stories, forecasting, native mobile | Not now. | — |

---

## 9. Open items for the owner

| # | Question | My recommendation |
|---|---|---|
| O-1 | Ship JavaScript and JSONata in the same round, or JSONata first? | Same round — the seam and the conformance suite are the work; the second engine is small once the first is proven. But JS carries the isolate artifact (~tens of MB per platform) into the image: acceptable? |
| O-2 | `value` mode writing objects into the Context: allow nesting, or scalars only? | Allow objects (needed for nested API responses); the consumers already refuse what they cannot bind. |
| O-3 | Should a `row`-mode script be allowed to return MULTIPLE rows (flat-map)? | Yes, v1 — it is the pivot/unnest case and costs nothing in the streaming path. |
| O-4 | Should JavaScript `console.log` reach the UI? | As node warnings, capped at `MaxOutputStreamSize` — diagnostics, not a channel. |
| O-5 | The interpreter-only fallback knob (§4.3) — ship it, or refuse JS outright when the isolate is missing? | Ship it, default off, documented. Refusing outright is cleaner; an operator on an unsupported platform may still want JSONata + fallback JS for dev. |

---

## Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-09 | v0.1 | orchestrator, after the owner's brainstorm | Initial record: six decisions agreed in conversation; engines and sandbox facts verified against graalvm.org and Maven Central the same day; JSONata-java's int/long/double numbers drive the decimal-as-string rule. |
