# Design: SCRIPT nodes — JSONata and JavaScript as template types, one node type, one contract, tested like code

**Status:** v0.7.1 (2026-10-21, #8 errata before lane 8a — the owner's rulings D1–D5 of 2026-10-20 and the lander's review, store `notes/2026-10-20-javascript-review-response.md`; the plan's six errata, store `notes/2026-10-20-javascript-round-two-plan.md` §10): §4.4 gains the Engine boundary (D1), `sandbox.MaxStackFrames`, CPU ≠ elapsed (the engine's own `close(true)` watchdog is the wall-clock bound, wired through the pool), parse-only compile (D2), the number rule by logical type (D3), the return-shape contract, the input/result byte budgets, the read-only deployment (D5); `Math.random` is refused (no seed exists) and the time zone comes from the builder, not an unverified option name; §4.1 shows the seam as it is in the tree plus the three defaulted fields 8a adds; §4.6 names `loader.path` as the primary mechanism and gains failure modes 12–13 and three measurements; §12 R2-3/R2-4 corrected, R2-8..R2-12 record the five rulings; §14 row 8b's E2E is `ScriptNodeE2eTest` (there is no §10.9), the module fences are corrected, 8a's deliverables grown. The body contract (the statements of `main(input)`, D4) was written into §2.2 by the rename lane and stands. v0.7 (2026-10-20, #8 round-two readiness): §4.4 re-verified against the current GraalVM guides and Maven Central — the pin is 25.4.4.1.1, the untrusted policy needs six options §4.4 did not list, the isolate is spawned explicitly, a Context that trips a limit is dead; new §4.6 (the spike that opens round two, eleven ways the round can fail with where each is found, the measurements it must record); D-T16 packaging (the jar carries no isolate — the image fetches its platform's, SHA-256 verified, into a directory a setting names) and D-T17 console output to the application log (owner, 2026-10-20); §12's round-two decisions re-listed; §14 gains lanes 8a/8b. v0.6 (2026-09-28, #272): §4.5 gains the owner's ruling — the evaluation pool's abandonment is the script engine's sandbox bound, the between-steps hooks a runaway guard — and the Reserved names row (a body may not bind a `__` name; `$eval` checked the same way); the 7a note's "`$eval` cannot parse bind expressions" corrected. v0.5 (2026-09-26, #260): §4.5's Depth row corrected — the engine counts depth with its own evaluate entry/exit hooks (the library `Timebox` skipped `isParallelCall` frames, leaking one unit per item on set-level shapes); tail-recursive lambdas are trampolined and stay a wall-clock case. v0.4 (2026-09-24) restated every authorization row in the vocabulary of the permissions-and-keys record (`2026-09-23-permissions-and-keys-design.md`, RATIFIED 2026-09-23, #215), which lands BEFORE the remaining script lanes (owner, 2026-09-24); v0.3 RATIFIED for dispatch (2026-09-23) on the owner's nine rulings R1–R9 (§0.1).
**RENAMED.** TRANSFORM was renamed SCRIPT by the script-node-rename lane (owner ruling 2026-10-20):
the wire value, the codes, the config keys and the docs area changed; nothing the node does changed.
Supersedes v0.2 (2026-09-17) in place; every v0.2 decision stands, and the pre-dispatch review
of 2026-09-23 (store: `notes/2026-09-23-script-spec-review.md`) corrected eight statements
that disagreed with the tree at `be0305b3` (§0.2) and closed nine holes with the owner. GitHub
#7 (JSONata) and #8 (JavaScript) point here. Round one ships as five lanes (§14).

**Not packaged into the product.** `modules/web` copies an explicit allowlist of root
`docs/*.md` into the jar; the `docs/superpowers/` tree is outside it (`DocsCatalog` names it
as excluded). The product spec lands in `pipeline-contract.md` (node type, §13 codes),
`templates.md` (the two types, the contract blocks), `dag-executor.md` (the engine seam,
limits), `metadata-db.md` (the columns), `configuration.md` (the knobs), `rest-api.md` (the
evaluate route), `auth.md` §7.6 (the roles rows) and the skill as the lanes ship — each lane's
prompt cites this record.

**Provenance of external facts.** The GraalVM sandboxing guide (`graalvm.org/latest`) was
read on 2026-09-09 and again on 2026-09-17 (§4.4); Maven Central and the
`dashjoin/jsonata-java` README on 2026-09-09, and the library's `Jsonata.java`, `Timebox.java`
and `Functions.java` sources on 2026-09-23 (§4.5). Re-read 2026-10-20 for round two (§4.4, §4.6): the sandboxing
guide (`security-guide/sandboxing/`), the embedding guide (`reference-manual/embed-languages/`, "Polyglot Isolates"),
and Maven Central's repository metadata and jar headers (`repo1.maven.org` — never the search index, which lagged two
release lines that day). Version numbers are recorded as the floor
the implementer verifies against, never as the pin.

---

## 0. Decisions

| # | Decision | Rationale |
|---|---|---|
| **D-T1** | **No new entities.** `jsonata` and `javascript` are two new values of `TemplateType` (today `sql`, `html`). The UI and the skill present three faces — SQL, HTML, Script — over one entity. | The lifecycle is where the safety lives: draft-first, hash preconditions, the pin rule, `in_use` on purge, the audited verbs, the RBAC matrix, export and promotion. A second entity is a second, slightly different copy, and the difference is where the next incident hides. A face costs a type filter, a reference doc and an editor pane. (The export/import FILE FORMAT does change — it carries the three blocks — see §2.2.) |
| **D-T2** | **One node type, `SCRIPT`.** The language is the pinned template's type. The node declares `inputs` and `output`; the mode comes from the template's contract. | The contract (inputs, staging, typing, deadline, refusals) is identical across languages; only the evaluator differs. |
| **D-T3** (sharpened) | **Engines behind one seam, in-process.** `dashjoin/jsonata-java` in round one; GraalJS as a polyglot isolate in round two. Out-of-process is never the default. | Both run in the JVM the executor owns and cancel through its deadline. The seam is a standalone service with three callers (§4.1). |
| **D-T4** (sharpened) | **A script is a pure function driven by the executor.** It receives one JSON object and returns one JSON value. It has no handle to tempdb, no `emit`, no `query`, no network, filesystem, process, host classes or clock. Nothing is exported into a script. | The owner's 2026-09-17 ruling: iteration is the executor's job (`row` mode), and data acquisition belongs to a later family of ingestion nodes. Reproducibility becomes a theorem — the output is a function of the template version and its inputs — and the sandbox has no host entry points to audit. |
| **D-T5** (corrected v0.3) | **Output types are declared and enforced in the type system's own vocabulary; nothing is inferred.** A contract names `LogicalType`s (`INTEGER`, `BIGINTEGER`, `DECIMAL`, `BIGDECIMAL`, `STRING`, `BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP`; `BINARY` refused; `NULL` never declared) and every value crosses in that type's existing wire form (type-system.md §3: `DECIMAL` is a JSON number, `BIGDECIMAL` and `BIGINTEGER` are strings). A value that does not fit is a refusal with a row number; a `DECIMAL` output is ROUNDED to its declared scale (R1). | T186's lesson at a new boundary, without inventing a second type table: the wire has fewer types than the tables, and the type system already decided how each crosses. |
| **D-T6** | **Order after this feature:** scheduler → lake materialization → alerts → metrics + dashboards → embedding with row-level security (the Tableau map, §13). The parameter engine follows the scheduler (R8). | Unchanged. |
| **D-T7** | **A script is code, so it carries a contract, invariants and tests on its version, inside the body hash.** Save runs the suite; release re-runs it. | The owner's reason for extending the semantic layer to scripts was testing: no data, wrong data and right data must all behave, and the assertions must stay in place. A test outside the version can be edited to pass against a body it no longer describes. |
| **D-T8** | **Scoped exception to D3.** SQL and HTML templates keep D3 exactly (no schema of their own; the pipeline's `parameters` is the single declaration point, proven by dry-render). The two script types declare, and the pipeline conforms: a node's `inputs` must satisfy the pinned contract at pipeline save. | A SQL fragment only means something inside a pipeline; a script is reused across pipelines and later dashboards and must be testable alone. Two declaration points drift only when neither is authoritative; here the template is. |
| **D-T9** | **No Freemarker on script bodies.** Both types set `engine` to `none`; `${`, `<#` and `<@` in a body are refused at save; `imports` must be empty and `is_library` false. Deferred with an entry condition (§11). | JavaScript has no bind mechanism: `${param}` in a JS body is a caller-supplied string spliced into executable source — the injection SQL's `:name` rule exists to prevent. Everything a conditional render could do, an `if` inside the function does, with the input object as the value channel. Compile-once-per-version and a test suite that covers the real program both depend on the body never varying. |
| **D-T10** | **One value namespace: the Context.** The input object carries `rows`, `inputs` and `meta` — no `params`, no `context`. Every value a script reads is named in the node's `inputs` map (`$key` = a Context key of any tier; otherwise a staged table) and validated at save, the CALCULATOR convention. | The contract has one namespace with five tiers; v0.1 invented a split it does not have. A misspelt key in an unvalidated channel returns nothing in both languages; in `inputs` it is a save-time refusal. |
| **D-T11** | **Validation partitions or fails, chosen per node.** A contract may declare `rejects`; the function then returns `{ rows, rejects }`, both written to tempdb. `strict: true` on the node fails it on any reject. Default is partition. Rejects are never dropped: a rejects contract needs a rejects table, so it is refused with caller output (R5). | A report of what could not be imported is more useful than a dead run; the chart-payload and business-logic cases get fail-fast with one flag. Nothing is inferred from the data. Silently dropped rows are the debugging pain the owner will not pay for. |
| **D-T12** | **Tests assert exact canonical-JSON equality or a refusal code; invariants run on every case and every real execution.** No tolerances. | A test that needs tolerance is a sign the output should be a declared DECIMAL with a scale (and the gate rounds to it, R1). Invariants-only proves plausibility, not correctness. |
| **D-T13** | **Invariants are JSONata booleans, whatever the template's language.** | Assertions stay data, not code — the instinct behind release checks — so every script's guarantees render in one place and cannot have side effects. |
| **D-T14** | **Two implementation rounds: JSONata first with the whole contract machinery; JavaScript second.** | The seam and the conformance suite are proven on the engine with no isolate before the engine with one arrives; the JS round measures serialisation cost on a working system rather than estimating it. |
| **D-T15** (new v0.3) | **The JSONata engine is fenced like its JavaScript brother and like every other executor resource: its bounds are stated honestly, it runs on its own bounded pool, its inputs and outputs are capped, and a breach suite proves each bound or records its absence (R7, §4.5).** | A sandbox no test has breached is a belief. The library bounds time and depth between steps only; the executor must own what the library cannot. |
| **D-T16** (new v0.7, owner 2026-10-20) | **The jar carries no isolate; the image fetches the one for its platform.** The GraalJS isolate is a per-platform native artifact (§4.4). The product jar bundles only the polyglot API and the engine code; the Dockerfile fetches the isolate for its `TARGETARCH` from Maven Central at image build, verified against the published SHA-256, into the directory `datapipelines.script.js.isolate-dir` names (the DuckDB-extension pattern already in the Dockerfile). A non-Docker deployment drops its platform's isolate jar there. **(v0.7.1)** The directory reaches the class path through the boot launcher's `loader.path` (`PropertiesLauncher` is already the jar's `Main-Class`; the image's `ENTRYPOINT` passes `-Dloader.path=/opt/graaljs`) — the primary mechanism of the round-two plan (M1), not a fallback; `isolate-dir` stays the key the boot check verifies and names. Empty or missing → JavaScript disabled, said once at boot (`script.js.unavailable`); never a silent fallback. Local builds and tests take the host platform's isolate through Gradle; all four platform artifacts are pinned in the verification metadata. | Bundling every platform costs ~123 MB on a 186 MB jar for files no deployment runs; bundling one bakes the platform into the jar and an org building an arm64 image from the published jar gets JavaScript silently off. Docker's own `--platform` is the knob orgs already know. Measured 2026-10-20: linux-amd64 isolate 62 459 905 bytes, linux-aarch64 60 719 533, the jar 186 408 039. |
| **D-T17** (new v0.7, owner 2026-10-20) | **`console.*` output is logged like the application's own logs** — one structured line per call under the logger `script.js.console`, tagged with template id and version, node id and execution id, bounded by `sandbox.MaxOutputStreamSize` / `MaxErrorStreamSize`; `console.error`/`warn` at WARN, the rest at INFO. The same captured text is returned in `templates_evaluate` and the test runner's result (`console: [...]`, capped the same way) so an author — who has no server log — sees it where they debug; a pipeline run logs only. | The owner's ruling (2026-10-20): "those are just like java logs". The author-facing copy is the orchestrator's addition, confirmed at the 8a dispatch; a console line can carry row data, so the log line is bounded, marked and subject to the same retention as every other log line. |

### 0.1 The owner's rulings of 2026-09-23 (R1–R9)

| # | Ruling | Where it lands |
|---|---|---|
| **R1** | A `DECIMAL` output column is rounded (half-even) to its declared scale by the type gate; `precision_lost` is reserved for magnitude overflow. A `BIGDECIMAL` output is a string and must be exact — excess scale is `precision_lost`. | §5.3 |
| **R2** | A `row`-mode test case lists no table under `inputs`; the runner builds `inputs.<table>` from the case's `rows` for the invariants only. Test input mirrors production exactly. | §2.2, §5.4 |
| **R3** | No JSON bodies now: a `value`-mode SCRIPT writes a Context key only; `output.target` is refused on value mode; the endpoint contract does not change in this record. The nested-object body is deferred to the dashboards design. | §3.1, §9, §11 |
| **R4** | An object-valued Context key (`output.kind: object`) is bindable only as another SCRIPT's input; a SQL node or calculator binding it is refused at pipeline save. Persisted as jsonb under the 1 MB cap. | §3.1, §5.5, §7 |
| **R5** | A rejects-declaring contract with `output.target: caller` is refused at pipeline save. Rejects are never silently dropped. | §3.1, §7 |
| **R6** | Roles: `templates_evaluate` (MCP), the REST evaluate route, `implements` on update and the list filters take the `templates_render` row — viewer ✗, author ✓, promoter ✗, workspace admin ✓, super admin ✓. | §9.4 |
| **R7** | JSONata is treated like JavaScript and the rest of the executor: honest bounds, a dedicated bounded pool, input/output caps, a breach suite. | §4.3, §4.5, §10 |
| **R8** | Order: scripts → scheduler → parameter engine. The parameter-engine record rebases its counts (tool count, `Dag.kt` move) on what scripts leave behind. | §14 |
| **R9** | `implements` / `needs_review` / discovery ship in round one, day 1, as their own lane. | §8, §14 |

### 0.2 Corrections against the tree (v0.2 → v0.3, from the 2026-09-23 review)

Type names (`BIGINT`, `NUMERIC`, `FLOAT/DOUBLE`, `TEXT`, `BLOB`) replaced by `LogicalType`s and
the §6 table by a pointer; the body hash is the SQL `jsonb_build_object(engine, dialect,
is_library, imports, body)` expression, not "the body alone", and the import guard recomputes
it; `pipelines_execute_node` refuses a SCRIPT (it refuses every tempdb node by design);
`engine: none` is refused today by `template.validation.engine_unsupported`, whose rule becomes
type-conditional; the template editor is a textarea, so the script face is textarea panes;
`input_type_unsupported` is one save-time rule; `ColumnSchema.nullable` is a `Boolean?` whose
absence means "unknown" and the H2 ingress mapper does not fill it, so the `nullable: false`
check scans unless the staged schema positively says `nullable = false`; the count pins are
named for the lanes.

---

## 1. Scope

**Round one (§14, five lanes):** the two template types in the model; `engine: none`; the
contract, invariants and tests blocks with save/release gating; the `ScriptEngine` seam with
the JSONata engine, the conformance suite and the JSONata breach suite; the `SCRIPT` node
with all three modes, rejects and strict; the type gate and the codes; `templates_evaluate`
(MCP and REST); the skill rule and reference; the explorer faces, the script editor face
(textarea panes), the node card; `implements`, the drift marker and discovery (R9).

**Round two (#8):** the JavaScript engine (§4.4) — the isolate artifact and its platform
matrix, the sandbox proofs, the boot-time unavailable path, the fallback knob, `console.log`
handling, the batch-serialisation measurement, the JavaScript editor pane.

**Out, by name (§11):** ingestion nodes; the dashboard runtime; a nested-object endpoint body
(R3); Freemarker on script bodies; custom JSONata functions; contracts or tests on SQL/HTML
templates; templates calling templates; cross-batch state in `row` mode; user-installed
modules; a code editor component.

---

## 2. Model

### 2.1 The types

`TemplateType.JSONATA("jsonata")`, `TemplateType.JAVASCRIPT("javascript")`. A script
version's `engine` is `none` (the existing field; today always `freemarker`). Every rule that
applies to `sql`/`html` applies unchanged: folder-path names, draft-first, hash
preconditions, the pin rule at release, `template.in_use` on purge, the RBAC matrix, the
audited verbs, export and promotion.

**`engine` becomes type-conditional.** `template.validation.engine_unsupported` (templates.md
§7, enums.md §6) today refuses every value but `freemarker`. Its rule becomes: `freemarker`
iff `sql`/`html`, `none` iff `jsonata`/`javascript`; any other pairing is refused with the same
code, detail naming the type. `dialect` is forbidden on script types (the existing
`template.validation.dialect_not_allowed`; `chk_type_dialect` extended). `imports` must be
`[]` and `is_library` false on a script type (`template.validation.freemarker_forbidden`,
detail `imports` / `is_library`) — there is no Freemarker to import into.

**Body contracts.** JSONata: the body is the STATEMENTS of `function main(input)`: the
engine wraps it as `function main(input) { <body> }`; `input` is in scope, the body
`return`s the result, helpers are nested functions; nothing is defined outside main
(owner's words, 2026-10-20: `main` and the braces are immutable, shown as labels around
the editable statements). JavaScript (round two): the same statements-of-`main` contract. A body is parsed at save
through the engine (`template.validation.syntax_error`, the existing code). A body containing
a Freemarker construct is refused with `template.validation.freemarker_forbidden` (D-T9).

**Compiled once.** A released version's body never varies, so the engine compiles it once
and caches by `(id, version)`; a draft is recompiled on each evaluate.

### 2.2 The three blocks (script types only; D-T7)

Stored on `template_versions` as `contract_json`, `invariants_json`, `tests_json` — nullable
jsonb, with a CHECK (`chk_script_blocks`, the `chk_type_dialect` shape) that they are
non-null exactly when `type IN ('jsonata','javascript')`. **Inside the version's `body_hash`:**
the hash is the SQL expression `jsonb_build_object('engine', 'dialect', 'is_library',
'imports', 'body')` in `TemplateRepository.computeBodyHash` (computed in Postgres, never in
Kotlin, recomputed by the import guard and the V6 backfill; `type` is deliberately NOT in it —
V8 explains why). The three blocks join that object as jsonb **only when present** —
`|| CASE WHEN contract_json IS NOT NULL THEN jsonb_build_object('contract', …, 'invariants',
…, 'tests', …) ELSE '{}'::jsonb END` — so every stored sql/html hash is byte-identical before
and after (the migration test recomputes every existing row and finds zero changes; V8's
"any change touching body_hash" warning is the reason), and every copy of the expression
(`computeBodyHash`, `TEMPLATE_HASH_EXPR` and its eleven uses, the three test copies) moves in
one commit. The wire `Template` DTO, the import DTO `TemplateDraft` (which today drops unknown
keys), the pipeline export bundle and `PromotionService.templatePayloadOf` (built field by
field) all carry the three blocks; an import of a script version whose recomputed hash
disagrees is refused by the existing hash guard. Migration: the next free
`V__` in `modules/app` (V31 is the latest on main; the lane re-checks against every open lane
before choosing).

```json
{
  "contract": {
    "mode": "row",
    "inputs": {
      "orders":    { "kind": "table", "columns": [
                       { "name": "order_id",     "type": "INTEGER" },
                       { "name": "amount_cents", "type": "INTEGER" },
                       { "name": "customer_id",  "type": "STRING", "nullable": true } ] },
      "tz":        { "kind": "value", "type": "STRING" },
      "min_total": { "kind": "value", "type": "DECIMAL", "precision": 12, "scale": 2 }
    },
    "output": { "kind": "table", "columns": [
                  { "name": "order_id",    "type": "INTEGER" },
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
      "input":  { "rows": [], "inputs": { "tz": "UTC", "min_total": 0.00 } },
      "expect": { "output": { "rows": [], "rejects": [] } } },
    { "name": "missing customer is rejected",
      "input":  { "rows": [ { "order_id": 1, "amount_cents": 1250, "customer_id": null } ],
                  "inputs": { "tz": "UTC", "min_total": 0.00 } },
      "expect": { "output": { "rows": [],
                              "rejects": [ { "row": { "order_id": 1, "amount_cents": 1250, "customer_id": null },
                                             "reason": "customer_id missing" } ] } } },
    { "name": "wrong shape is refused",
      "input":  { "rows": [ { "order_id": "x" } ], "inputs": { "tz": "UTC", "min_total": 0.00 } },
      "expect": { "refusal": "pipeline.script.input_contract_violation" } }
  ]
}
```

**`contract`**

| Field | Rule |
|---|---|
| `mode` | `row` \| `table` \| `value`. The template is written for one mode: a `row` body reads `rows`; a `table`/`value` body reads `inputs.<name>`. `row` requires exactly one `table` input (`template.contract_invalid`, detail `row_mode_inputs`). |
| `inputs` | Name → `{ kind: "table", columns[] }` or `{ kind: "value", type, precision?, scale? }`. Names obey §6.1's `[a-z_][a-z0-9_]*`. At least one input. `columns[]` entries are `{ name, type, precision?, scale?, nullable? }`; `type` is a `LogicalType` wire name (§6); `nullable` defaults to false. `BINARY` and `NULL` are refused as declared types (`template.contract_invalid`, detail `type_unsupported`, naming the input and column). `precision`/`scale` follow type-system.md §4 (required for `DECIMAL`/`BIGDECIMAL` with an exact-numeric meaning). |
| `output` | `{ kind: "table", columns[] }` for `row`/`table`; `{ kind: "value", type, … }` or `{ kind: "object" }` for `value` (an object is written as-is to the Context — for nested payloads a later SCRIPT consumes; R4 says who may read it). `mode`/`output.kind` mismatch is `contract_invalid`. |
| `rejects` | Boolean, default false; legal only with a table output. When true the function returns `{ rows: [...], rejects: [ { row, reason } ] }` where `row` fits the single table input's columns (`row` mode) or the output columns (`table` mode) and `reason` is a non-empty string. When false the function returns a plain array (or one value). |

**`invariants`** — a list, possibly empty, of `{ name, expr, message }`. `expr` is a JSONata
expression over `{ rows, rejects, inputs }` (§5.4) that must evaluate to boolean `true`.
Each `expr` is compiled at save (`template.invariant_invalid` on a syntax error) and run on
every test case's output; a non-boolean result on any case is `invariant_invalid` with the
case named.

**`tests`** — a non-empty list of `{ name, input, expect }`. `input` is the input object of
§3.2 with `meta` optional (the runner supplies `{ node_id: "test", context_key: null }`) and
**mirrors production exactly (R2)**: in `row` mode, `rows` is the batch and `inputs` holds the
value inputs only — the table input is NOT listed (`template.contract_invalid`, detail
`row_case_lists_table`); the runner builds `inputs.<table>` = the case's `rows` for the
invariants. In `table`/`value` mode `inputs` holds every input, tables as arrays. `expect` is
exactly one of `{ output }` — the function's return value, compared after canonicalisation
(§5.5) — or `{ refusal: "<code>" }` — the run must refuse with that code (a type-gate refusal or
an engine refusal; an invariant failure is `pipeline.script.invariant_failed`). A case may pin the clock with `"now": "<ISO-8601 TIMESTAMP>"` (v0.3.1): the
engine's `$now()`/`$millis()` return it; without it they refuse (§4.5 — a script is a pure
function of its inputs), and in a run the executor pins them to the execution's
`current_timestamp`. **One case
whose every table input and `rows` are empty is mandatory** (`template.contract_invalid`,
detail `empty_case_missing`): a script with no test for zero rows does not save.

### 2.3 `implements` (outside the body hash; R9 — round one, lane 7e)

A script version may cite learned facts it implements: a list of fact ids, stored in a
join table `template_implements (template_id, version, fact_id)` so the reverse lookup is an
index read. It is **not** part of `body_hash` — citing a fact is a claim about meaning, not a
change of behaviour — and it is editable through `templates_update` / `PUT` on a draft or a
released version alike (the existing precondition applies; `implements` alone never bumps the
version). A cited fact must be visible from the active workspace and be a `WORKSPACE` fact of
kind `definition`, `exclusion` or `preference` — the three `WORKSPACE`-scoped kinds of
`LearnedFactKind` (`template.implements_unresolved` otherwise). `DATASOURCE` facts are
excluded by rule: a script never sees a datasource, so it cannot implement a fact about one.

### 2.4 One behaviour per type

A `TemplateTypeBehaviour` per `TemplateType` owns: body validation, whether `dialect` is
required or forbidden, which engine value is required, which blocks are required or refused,
what `render`/`preview` mean, and the test runner. The template services call the behaviour;
no `when (type)` accumulates in a service. The existing SQL/HTML conditionals move behind the
same interface in the same round (a refactor with parity tests, not a behaviour change — the
lane prompt lists every conditional it moves).

---

## 3. The node

### 3.1 Shape

```json
{
  "id": "shape_orders",
  "description": "Order lines at reporting grain; cents to currency; refunds preserved.",
  "type": "SCRIPT",
  "template": { "id": "acme/shape/order_lines.jsonata", "version": 3 },
  "inputs": { "orders": "stg_orders", "tz": "$org_timezone", "min_total": "$min_total" },
  "output": { "target": "tempdb", "table": "order_lines", "rejects": "order_lines_rejected" },
  "strict": false,
  "depends_on": ["stage_orders"]
}
```

| Field | Rule |
|---|---|
| `template` | Pinned like every pin; its type must be `jsonata` or `javascript` (`pipeline.validation.script_template_type`). |
| `source` | **Forbidden** (`script_source_forbidden`). A SCRIPT is tempdb-only: it reads what upstream nodes staged plus the Context, and writes tempdb, the Context or the caller. It never touches a datasource — no dialect question, the read-only rule trivially true, every run reproducible from staged inputs. |
| `inputs` | The CALCULATOR convention. `"$name"` is a Context key of any tier; anything else is a tempdb table a node this one depends on stages. The key set must equal the contract's input names (`script_input_contract`, detail names the missing/extra input); a table name no upstream stages or a `$key` nobody writes is `script_input_unknown`; a value input whose Context type does not fit the contract's type is `script_input_contract`. An object-valued key (R4) binds only to a `value` input whose contract kind is `object`. |
| `mode` | **Not on the node** — it is the contract's. (v0.1 put it here; a redundant copy is a drift point.) |
| `output` | **Table output** (`row`/`table` mode): `{ target: "tempdb", table, rejects? }` or `{ target: "caller" }` (the caller-node rules of pipeline-contract §9 unchanged: at most one caller node, its rows are the result). `rejects` on a `caller` output, or a rejects-declaring contract pinned by a `caller` node, is `script_rejects_on_caller` (R5). **Value/object output** (`value` mode): `{ context_key }` only, obeying every calculator rule — §6.1 name shape, one writer per key, never shadowing a declared parameter (`calculator_output_collision` reused), topology-ordered; a `target` on a value-mode node is `script_output_shape` (R3: no JSON result bodies in this record). Any other output/mode pairing is `script_output_shape`. `rejects` present without the contract declaring it — or absent when it does — is `script_rejects_undeclared` / `script_rejects_missing`. |
| `strict` | Boolean, default false. Legal only when the contract declares rejects (`script_strict_without_rejects`). |
| `depends_on` | Sequencing is topology, as for calculators: a `$key` written by another node is bindable only from a node that depends on its writer (`calculator_input_unordered` reused). |
| `inputs`, `context_key` on a SCRIPT | Legal — `calculator_fields_on_non_calculator` (`CalculatorRules.checkForeignFields`) exempts `SCRIPT` for these two fields; `kind` and `context_keys` stay refused on it. |

**Every key a `value`-mode SCRIPT writes is an implicit optional execute input**, exactly
like a calculator's (§4.10): supplied by the caller, the node is skipped and its stats carry
`provided_by: "caller"`; the supplied value is checked against the contract's output type (an
`object` output accepts any JSON object under the size cap).

**Object keys have one reader (R4).** A key whose writer declares `output.kind: object` may be
bound only by a SCRIPT `inputs` entry; a SQL node's `:key`, a calculator input or a
`PIPELINE` node's `parameters` reference to it is refused at pipeline save with
`pipeline.validation.script_object_key_bound`, naming the key, its writer and the offending
node. A caller may supply it at execute time as any JSON object (the implicit optional input
above).

### 3.2 The input object

```json
{ "rows": [ … ],                       // row mode only: one batch of the single table input
  "inputs": { "<name>": [ … ] | value }, // row mode: value inputs only; table/value mode: every input, tables as arrays
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
// As in the tree (modules/scripting, v0.7.1 re-read 2026-10-21); the three commented fields are 8a's additions.
interface ScriptEngine {
    val type: ScriptLanguage
    val capabilities: EngineCapabilities
    fun compile(body: String): CompiledScript                      // parse only — NO guest statement runs (D2)
    fun evaluate(script: CompiledScript, input: Any?, limits: EvaluationLimits): Any?
}
data class EvaluationLimits(
    val wallClock: Duration, val maxDepth: Int,
    val maxHeapBytes: Long? = null, val maxStatements: Long? = null,
    val now: Instant? = null,                                        // the pinned clock (R2-4; null → clock reads refuse)
    // 8a adds, defaulted so no caller changes:
    val maxInputBytes: Long? = null, val maxResultBytes: Long? = null, // the byte budgets (§4.4); null → the runtime's options
    val console: ConsoleSink? = null,                                // NOT a bound: where console.* lines go; null discards them
)
```

Its own module (`modules/scripting`), depending on `typesystem` only. Input and output are
JSON-shaped values (maps, lists, scalars). The seam knows nothing of modes, contracts,
invariants, Context or tempdb. An engine reports which limits it can enforce
(`EngineCapabilities`: `boundsWallClockBetweenSteps`, `boundsDepth`, `boundsHeap`,
`boundsStatements`, `interruptible`) so the caller can document, not guess, what a limit
means for that engine (§4.5). Three callers:

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

Floor 0.9.10 (Apache-2.0; Maven Central `<latest>0.9.10</latest>`, `lastUpdated 2026-05-06`,
read 2026-09-23 — the implementer pins the current stable after verifying it; the project is
small — 99 stars, 17 open issues, last push 2026-07-08 — which is a bus-factor note for the
dependency review, not a blocker). Pure Java, in-process, thread-safe ("performance optimized
& thread safe", README); input and output cross as Java maps/lists with no serialisation.
Bounds: `Jsonata.Frame.setRuntimeBounds(long timeoutMs, int maxRecursionDepth)`
(`Jsonata.java:107`) installs a `Timebox` that checks wall clock and depth in the evaluate
entry/exit callbacks — i.e. **between expression steps only** (§4.5). The node deadline of
§4.11 (`settings.timeout_seconds`, lane 108) is the outer bound. No custom Java functions
registered (a template's meaning is identical on every deployment). Numbers are fitted to
int/long/double by `Utils.convertNumber` — the reason §6 keeps the type system's decimal rule.

### 4.3 Bounds that are the executor's

- **Wall clock** — the node deadline is the outer bound for every mode; the engine's own
  timeout is set slightly lower so the engine reports first (`pipeline.script.timeout`)
  and the executor's cancel (`pipeline.node.timeout`) is the backstop. Because the JSONata
  engine is not interruptible inside a builtin (§4.5), the executor's cancel abandons the
  evaluation thread to the pool's own bound rather than joining it, and the node fails on
  time regardless.
- **The pool (R7)** — every evaluation (node, test runner, `templates_evaluate`) runs on one
  dedicated bounded pool: at most `datapipelines.script.pool-size` (default 4) evaluations
  run at once, and `datapipelines.script.pool-queue` (default 64) bounds the evaluations
  ADMITTED in total, running plus waiting (so 4/64 refuses the 65th concurrent submission —
  lane 7a's reading, kept). A submission that cannot be admitted, or that waits its own whole
  wall clock without a running slot, is `pipeline.script.pool_exhausted` (503 on the
  tool/route, a node failure in a run). An evaluation that outlives its wall clock by
  `datapipelines.script.abandon-grace-seconds` (default 30) is logged at ERROR with the
  template id and version, counted (`script.evaluations.abandoned`) and abandoned by its
  caller; its thread is left to finish or die with the JVM, is never handed new work, and
  **keeps its slot until it ends** — the bulkhead: at most `pool-size` evaluation threads are
  ever alive, abandoned ones included, so a runaway degrades script capacity and never the
  rest of the JVM. (Corrected at the 7a merge, 2026-09-23: v0.3 said "the pool replaces it",
  which released the slot at abandonment and let every runaway add one more live thread with
  no ceiling. R7 bounds JSONata like the executor's own fixed pool, which keeps a thread for an
  abandoned statement.) The executor's own threads are never the ones that evaluate.
- **Rows** — `table`/`value` refuse when any table input exceeds
  `datapipelines.script.max-input-rows` (default 100 000) BEFORE loading
  (`input_too_large`); `row` mode streams and has no cap on the input, but its invariants do
  (§5.4).
- **Value size** — a `value`/object output above `datapipelines.script.max-value-bytes`
  (default 1 MB) is refused (`value_too_large`): a Context value is not a table. A single
  string in any returned row above `datapipelines.script.max-string-bytes` (default 1 MB)
  is `value_too_large` with the row number.
- **Probe and tests** — `datapipelines.script.evaluate-timeout-seconds` (default 10)
  bounds one `templates_evaluate` call and one test case; a suite is bounded by
  `datapipelines.script.suite-timeout-seconds` (default 60).
- **Concurrency** — one evaluation per node at a time; a node is one unit of work.

### 4.4 JavaScript — GraalJS polyglot isolate (round two; facts kept here so they are not re-derived)

Read from the GraalVM sandboxing and embedding guides on 2026-09-09, re-read 2026-09-17 and **re-verified
2026-10-20** (the v0.7 corrections are marked):

- Stock OpenJDK 21 runs GraalJS either in interpreter-only mode or through a **polyglot isolate**: a native
  image of the engine loaded into our process with its own heap, garbage collector, JIT and fault domain. The
  embedding guide's runtime table lists JDK 21 as a supported host, optimizing "through the polyglot isolate
  only". **Artifacts (v0.7, read from `repo1.maven.org`):** `org.graalvm.polyglot:polyglot` (the API, a 0.5 MB
  jar) plus the Community isolate. `org.graalvm.polyglot:js-isolate-community` is a `pom` aggregating four
  platform poms (`js-isolate-linux-amd64-community`, `-linux-aarch64-`, `-darwin-aarch64-`, `-windows-amd64-`),
  each of which depends on the binary `org.graalvm.js:js-isolate-<platform>-community` (linux-amd64
  62 459 905 bytes, linux-aarch64 60 719 533; transitive `org.graalvm.sdk:nativebridge`,
  `org.graalvm.truffle:truffle-api`). The isolate carries the language; no `js-community` beside it.
  **Pin 25.4.4.1.1** (released 2026-09-22; 25.3.4.1 is the previous line). glibc — matches the
  `eclipse-temurin:21-jre` image. Packaging is D-T16.
- **Sandboxing on Community requires the isolate** (since GraalVM 25.1). Policies
  `CONSTRAINED → ISOLATED → UNTRUSTED` build on each other; `ISOLATED`/`UNTRUSTED` need the isolate. The
  decision is **`SandboxPolicy.UNTRUSTED`**, one `Context` per evaluation, and the Engine boundary of the next bullet. **The isolate
  is never automatic (v0.7):** the builder calls `spawnIsolate(true)` on a builder with an explicit language
  list (`Context.newBuilder("js")` / `Engine.newBuilder("js")`); `engine.IsolateMode=external` (a separate OS
  process) is experimental and not planned.
- **The Engine boundary (D1, owner 2026-10-20, v0.7.1).** The sandboxing guide ("Sharing Execution Engines") says guest
  code of different trust domains must be separated at the Engine level — Contexts of one Engine share the isolate heap
  and the code cache, and GraalVM promises isolation between Engines, not between Contexts. v0.7's "one shared `Engine`"
  was a documented deviation. Ruled "3 if not then 2": **one Engine per EVALUATION** when lane 8a's spike shows it
  affordable — spawn + first Context + close median ≤ ~100 ms and p95 ≤ ~250 ms over ≥ 200 cycles, process RSS flat over
  1 000 cycles (the orchestrator's gate) — else **one Engine per JVM** with the deviation recorded (author `Source`s
  `cached(false)`, per-Context `sandbox.MaxHeapMemory` well under `engine.MaxIsolateMemory`, the Engine's rebuild path
  after a fatal trip, and the failure mode "a tenant's hard-heap trip can cancel other tenants' evaluations once" in the
  KDoc, the handback and deployment.md). One Engine per WORKSPACE was offered and not chosen. No designed data path crosses
  Contexts in either arm (fresh Context, `HostAccess.UNTRUSTED`, one JSON string in and out, no shared globals); the lander's
  probe of 2026-10-20 saw a sibling Context survive one hard-heap trip — the spike re-measures it (§4.6).
- **What `UNTRUSTED` requires (v0.7 — the guide's current list; a missing one refuses to build the Context):**
  `sandbox.MaxCPUTime` (set to the wall clock as the CPU budget — **v0.7.1: CPU time is not elapsed time**; the guide says it excludes time waiting on synchronisation or IO, is checked every `sandbox.MaxCPUTimeCheckInterval`, default 10 ms, with no accuracy guarantee — so the WALL-CLOCK bound is the engine's own watchdog calling `Context.close(true)` at `limits.wallClock`, see the cancellation bullet; whichever trips first wins),
  `sandbox.MaxHeapMemory` (= `datapipelines.script.js.max-heap-mb`, default 256 — **a soft limit: the guide
  says guest code can still cause `OutOfMemory`**), `engine.MaxIsolateMemory` (the isolate's whole heap; every
  Context sharing the Engine shares it, so it is ≥ `pool-size × max-heap-mb` plus headroom —
  `datapipelines.script.js.isolate-memory-mb`), `sandbox.MaxStatements` (the runaway-loop backstop;
  `MaxStatementsIncludeInternal` identical on every Context of the Engine), `sandbox.MaxThreads = 1`,
  `sandbox.MaxASTDepth` (parse depth), `sandbox.MaxOutputStreamSize` and `sandbox.MaxErrorStreamSize` (D-T17's caps), and — optional under the policy, **v0.7.1**: v0.7 wrongly concluded no stack-depth option exists — `sandbox.MaxStackFrames` set from `EvaluationLimits.maxDepth` (runtime guest frames, a different thing from AST depth), so `EngineCapabilities.boundsDepth` is **true** for this engine;
  `in`, `out` and `err` redirected, `allowHostAccess(HostAccess.UNTRUSTED)`, `engine.UntrustedCodeMitigation`
  on. `sandbox.UseLowMemoryTrigger` is unsupported under the policy. `--sandbox.TraceLimits=true` records peak
  usage for sizing. All of these are config keys under `datapipelines.script.js.*` (configuration.md's
  §3.28 table grows; `ScriptConfigKeysSpecDriftTest` pins the set).
- **Under `UNTRUSTED`, host entry points are explicit only** (`@HostAccess.Export`) and **guest access to host
  arrays, lists, maps, buffers, iterables and iterators is disallowed**. Consequence (2026-09-17, unchanged):
  nothing is exported (D-T4 needs nothing); the input object goes in as one JSON string per evaluation
  (`JSON.parse` in the guest), the result comes back as one JSON string (`JSON.stringify` in the guest). One
  encode and one decode per batch per direction; **measured on a 100 000-row `row`-mode run before the round
  closes and the number recorded in the handback** (§4.6).
- **When a limit trips (v0.7):** a `PolyglotException` with `isResourceExhausted()` true (`isCancelled()` too
  for CPU, statements and output); the Context is cancelled and cannot run guest code again. That matches one
  Context per evaluation: the engine closes it (try-with-resources) and maps the exception to
  `pipeline.script.resource_limit` (detail names the limit) or `pipeline.script.timeout`. Cancellation
  from outside is `Context.close(true)` — **and only that (v0.7.1):** the lander's probe of 2026-10-20 left a `while(true){}`
  Context alive after 1.5 s of `Thread.interrupt()`; `close(true)` from another thread stopped it in ~3 ms. The pool's
  abandonment (`Future.cancel(true)`) therefore reaches nothing by itself: the pool gains a per-slot cancellation hook that
  an engine registers at the start of `evaluate` (its `close(true)`) and the pool calls BEFORE the interrupt; the engine's
  own wall-clock watchdog calls the same hook at `limits.wallClock`. So, unlike JSONata, this engine reports
  `interruptible = true`, meaning exactly "`close(true)` within ~50 ms of the deadline", and the abandonment path is the
  backstop, not the bound — verified in A.0 before the capability is claimed, and `ScriptEvaluationPoolCancellationTest`
  proves the slot is free within 2 s of the wall clock.
- **If the isolate cannot load**, the app starts with JavaScript **disabled** and says so once
  (`script.js.unavailable` at boot; the node and the template refuse at save with the same code) — never a
  silent fallback to the non-isolated interpreter, whose sandbox on Community is the weaker `CONSTRAINED`.
  The fallback knob (`datapipelines.script.js.allow-fallback-runtime`) is **not shipped** (R2-2's lean,
  decided at the 8a dispatch).
- **Determinism (v0.7, corrected v0.7.1):** the engine installs a guest prelude before the body: `Date.now` and `new Date()`
  with no argument read the evaluation's pinned clock (`EvaluationLimits.now`, R2-4 — the JS twin of §4.5's `$now()` rule:
  unpinned → the call refuses); **`Math.random` is refused this round** (no seed exists on the limits; v0.7's "pinned seed" was
  wrong; a seed field is a later decision); the time zone is fixed through the polyglot builder (`Context.Builder.timeZone(ZoneId.of("UTC"))`
  — the lane verifies the method on the installed API and records the mechanism it used; v0.7's `js.timezone=UTC` was an
  unverified option name), locale `en-US`. The prelude also lists every other time or entropy source the policy Context
  exposes (`performance`, `crypto`, timers…) and refuses or records each. The prelude runs in strict mode.
  A guest can in principle undo a prelude, so this is a correctness aid for authors, **not a security
  boundary** — the boundary is the policy above.
- **Compile is parse only (D2, owner 2026-10-20, v0.7.1).** `compile()` runs outside the pool (`TemplateEvaluateService`,
  the test runner, the node runner all compile before `pool.run`), so no guest statement may run in it — the plan's
  "evaluate once at compile to refuse a body that never returns" would have put author code outside the admission
  boundary (pool-size × heap). The engine parses the wrapped Source in a throwaway policy Context (`Context.parse`, or
  the nearest equivalent the API offers, recorded); a syntax error maps to `ScriptSyntaxException(line, column)` with the
  prelude's line offset subtracted; a body that returns nothing or fails at runtime is caught INSIDE the pool by `evaluate`
  ("main returned nothing" / the guest error). Save still refuses the bad body: 8b runs the template's test cases — and a
  `{}` probe when there are none — through the pool at save (owner: "tests written with proper input and assert on
  output, just like the JSONata ones"). A test proves `globalThis.__ran = true; return 1;` leaves no trace after compile.
- **The number rule (D3, owner 2026-10-20 "by logical type", v0.7.1).** Facts (node 24.21.0; ECMAScript-standard, GraalJS
  the same): `JSON.parse("9007199254740993")` is 9007199254740992 — an integer above 2^53 rounds in the guest BEFORE the
  type gate sees it, so "TypeGate catches precision loss" was too strong; `"10.50" + 1` is `"10.501"` — strings give digits,
  not arithmetic, so the plan's "every BigDecimal as a string" would have turned an identity script over a `DECIMAL` column
  into a type-gate refusal. The rule, by logical type: **in** — integers within ±2^53 → Number; `BIGINTEGER` and any integer
  beyond ±2^53 → decimal string; `DECIMAL` → Number when `BigDecimal(d.toString()) == value` (the double round-trips exactly),
  else decimal string; dates/times as today's strings. **out** — an integral Number → Long (BigInteger beyond Long); a
  non-integral Number → BigDecimal from the shortest round-trip string; a BigInt → decimal string; a decimal string stays a
  string. The gate widens: `TypeGate.checkDecimal` accepts a decimal STRING for a `DECIMAL` column (`BigDecimal(s)`; a
  non-decimal string stays refused with the same failure); the `BIGINTEGER` gate already takes a string. The skill says:
  "a DECIMAL may arrive as a string when the double would lose digits — `Number(x)` loses digits beyond 15; for exact
  money use integer minor units". No decimal library in the prelude this round (recorded as the follow-up). §4.6 row 8
  is read with this rule.
- **The return-shape contract (v0.7.1; the lander's point 7).** `JSON.stringify` silently repairs an invalid result:
  `{x:NaN,y:Infinity}` → `{"x":null,"y":null}`; `{a:undefined,f:()=>1,b:1}` → `{"b":1}`; `[1,,undefined,NaN]` →
  `[1,null,null,null]`; a Promise, a `Map` or a `Set` → `{}`. So `main` returns a synchronous JSON-shaped value — null,
  boolean, finite number, string, BigInt, array, plain object, recursively — and the prelude's checker walks the result
  BEFORE `JSON.stringify` and refuses, naming the path: `NaN`/`±Infinity`; `undefined` as an array element, a hole, or the
  top-level value ("main returned nothing"); a function or symbol; a Promise/thenable; `Map`, `Set`, typed arrays,
  `ArrayBuffer`, `Date` (the author calls `toISOString()`); cycles. **Product choice:** an OBJECT property that is
  `undefined` is DROPPED (the JS idiom; the KDoc and the skill say so). The checker is an aid; the host's decode into
  Map/List/String/Boolean/Number/null is the boundary, and a test proves a reassigned `JSON.stringify` or checker cannot
  smuggle a non-JSON value past it. Error mapping: `isResourceExhausted()`/`isCancelled()` by limit name (CPU and the
  watchdog → `pipeline.script.timeout`; heap, statements, stack frames, output streams and the two budgets →
  `pipeline.script.resource_limit` with the limit named); `isGuestException()` → the guest message (≤ 2 000 chars) with
  the author-relative line; `isInternalError()` or a dead Engine → named, plus the rebuild path under the per-JVM arm;
  `isHostException()` cannot happen under the policy (a `Java.type` reference is a guest `ReferenceError`, tested).
- **Byte budgets (v0.7.1; the lander's point 8).** The console caps bound `console.*`, not the data. `EvaluationLimits` gains
  `maxInputBytes` and `maxResultBytes` (nullable, defaulted; null → the runtime's options): the serialised input JSON above
  `maxInputBytes` is refused BEFORE the Context exists (`INPUT_BYTES`); the guest's result string above `maxResultBytes` is
  refused by the prelude (an aid) AND by the host measuring UTF-8 bytes before parsing (`RESULT_BYTES`, the boundary).
  Recommended defaults, bound by 8b: 64 MiB each (a 100 000-row table must fit); the executor's `max-value-bytes` (1 MB)
  stays the cap on a VALUE output, a different thing.
- **Console semantics (D-T17 made precise, v0.7.1).** The prelude's `console` shim writes every level to ONE stream (`out`)
  as `"<level>\u0001" + text + "\n"` so order is preserved across levels; the engine parses it after the guest returns or
  fails, hands at most 200 lines of ≤ 2 000 chars to the `ConsoleSink` with a final "… N more lines dropped" line; a line
  without the tag is LOG; the `err` stream is the engine's own (a non-empty `err` after a guest failure is appended to the
  exception message, bounded). Output past `sandbox.MaxOutputStreamSize` fails the evaluation (`resource_limit`, kind
  `OUTPUT`) — refused at the entry point, not truncated. The JSONata engine ignores the sink.
- **Read-only deployment (D5, v0.7.1; the lander's point 9).** The Helm chart already runs the container with
  `readOnlyRootFilesystem: true` and `/tmp` as an emptyDir (`deploy/helm/datapipelines/templates/deployment.yaml`), so a
  runtime language-resource cache under `/opt` would fail to start the Engine under the chart. Not a choice: the image
  pre-extracts the language resources at build (`Engine.copyResources(Path, "js")` → `/opt/graaljs/resources`, run by a
  `main` nothing in the app calls) and the `ENTRYPOINT` passes `-Dpolyglot.engine.resourcePath=/opt/graaljs/resources`;
  no runtime cache write; `docker run --read-only --tmpfs /tmp` is a PASS gate of the spike (§4.6); deployment.md's
  read-only row stays true.
- The implementer proves each limit and each host-access door with a test that violates it
  (`while (true)`, `new Array(1e9)`, a `Java.type` reference, a `fetch`, `console.log` past the cap, a
  `Date.now()` without a pin, a `Math.random()`, a 2^53 + 1 integer, a `Date` or `NaN` in the result, a result above the
  byte budget) and reads the refusal back. A sandbox no test has breached is a belief.

### 4.5 What the JSONata engine bounds, and what it does not (R7; read 2026-09-23 from the library source)

| Bound | Library | Executor's answer |
|---|---|---|
| Wall clock | `Timebox.checkRunnaway()` runs in the evaluate entry/exit callbacks: a step that overruns is caught at the NEXT step boundary. A single builtin call (`$pad(s, 1e9)`, `$join` over a large array, `$match`/`$replace` with a backtracking regex on `java.util.regex`) runs to completion first. | The pool + abandon rule (§4.3): the node fails on time; the thread is abandoned, counted and replaced. Documented in dag-executor.md as "bounded between steps". |
| Depth | `maxRecursionDepth` — the engine counts every evaluate entry and exit itself (#260, 2026-09-26): the library's `Timebox` returned early on `isParallelCall` frames — the evaluator marks the second and later object pairs/arguments that way on the frame — so a set-level shape leaked one depth unit per item and a 240-row reshape refused at the default depth of 100. Nested expressions and non-tail lambda recursion are refused by depth; a tail-recursive lambda is trampolined by the library (depth stays flat) and the wall clock catches it. | `datapipelines.script.max-depth` (default 100, the library default), counted by the engine's own hooks — every evaluate entry and exit, no `isParallelCall` skip; the wall clock is checked at those same hooks. |
| Memory | None. The range operator caps a sequence at 1e7 elements (`Jsonata.java:1214`; the D2014 message says 1e6 — a library inconsistency, not ours); nothing else is capped, and `maxHeapBytes` cannot be enforced in-process. | Input caps (`max-input-rows`, batch size in `row` mode) and output caps (`max-value-bytes`, `max-string-bytes`) bound what a well-formed evaluation can allocate; a malicious body can still exhaust the heap. The docs say so; `EngineCapabilities.boundsHeap = false`. Round two's isolate has `MaxHeapMemory`; the day JSONata needs the same guarantee it moves behind the same isolate story (§11). |
| Interrupt | The engine never reads `Thread.interrupted()` (grep of `Jsonata.java`, `Functions.java`, `Timebox.java`: none). | `EngineCapabilities.interruptible = false`; the executor does not join the evaluation. |
| Host access | No host entry points unless a Java function is registered; none is. `$eval` evaluates a string as a JSONata expression under the SAME Timebox. | The conformance suite proves no builtin reaches the filesystem, network, clock or environment (`$now()` and `$millis()` ARE clock reads — the executor pins them: `$now`/`$millis` return the execution's `current_timestamp` from the platform tier, so a template is reproducible; the lane says how the library lets a caller supply them, or refuses bodies that use them). |
| Sandbox (owner ruling 2026-09-28, #272) | — | **The evaluation pool's abandonment IS the script engine's sandbox bound; the between-steps hooks are a runaway guard.** A hostile body is bounded by the pool — the caller's timeout fires on time, the abandoned thread is counted and keeps its slot until it ends (the bulkhead) — never by the hooks, which catch a runaway expression between steps and cannot see inside a single builtin. |
| Reserved names (#272) | The hooks ARE the frame variables `__evaluate_entry`/`__evaluate_exit`, looked up through the frame chain at every step (`Jsonata.java:155, 230`; `Frame.lookup` returns a bound null). A body that binds either switched both checks off for every deeper step — measured 2026-09-28: a 200-deep recursion returned 200 at a depth bound of 50, and the breach suite's tail loop outlived the grace. `$eval` parses at evaluate time and binds in the CALLING frame, so a compile-time check alone cannot see it. | `compile` refuses a body that binds any `__` name — `:=`, a lambda parameter, a focus `@$x`, an index `#$i` — read from the library's parsed AST, never the source text (`template.validation.syntax_error`, with the bind's line and column). `$eval` is shadowed per evaluation: its string meets the same check before the library runs it (`pipeline.script.evaluation_failed`). The seam rethrows the refusal that ESCAPED — read from the cause chain (`$sort`'s comparator wraps), else a refusal still pending under a library replacement (`and`/`or`, `$eval`'s D3121); one the library swallowed and went on past never labels a later error. Breach suite: `hook-rebind` and `hook-rebind-eval` REFUSED. |

**Measured at 7a (2026-09-23, `docs/dag-executor.md` §5.3 carries the generated table):** the range bomb is lazy and survives 512 MB alone (tripled, it exhausts the heap: unbounded); the pad bomb and an eval'd builtin overrun are unbounded (the thread outlives the grace; the pool bounds the caller and, since the bulkhead, the thread count); `$join` over the range is refused by the library's own argument cap; the backtracking regex is resistant on `java.util.regex` at these sizes; `$now()` is pinned or refused; ~~`$eval` cannot parse bind expressions (`:=`)~~ — corrected 2026-09-28 (#272): it can (`$eval("( $x := 1; $x )")` is 1), and a top-level eval'd bind lands in the CALLING frame; the Reserved names row above is the consequence.

**The JSONata breach suite** (lane 7a, mirrors §4.4's list): a range bomb (`[1..1e7]`), a pad
bomb (`$pad("x", 1e8)`), a join bomb, a regex bomb (`$match("aaaaaaaaaaaaaaaaaaaaaaaaaaaaa!", /^(a+)+$/)`),
deep recursion (a recursive lambda), `$eval` nesting, `$now()`, and — since #272 — the two hook rebinds
(`$__evaluate_entry := …` in the body, and the same bind through `$eval`). Each case records one of:
**refused** (which code), **bounded loss** (the node failed on time; the thread was abandoned
and replaced; the number of seconds it lived), or **unbounded** (heap) — and the docs table
above is generated from that record, not written by hand.

---

### 4.6 Round two: the spike, the ways it fails, the measurements (v0.7, 2026-10-20)

**The spike opens the round (lane 8a, A.0).** Before any engine code: the real Spring Boot jar, the isolate
loaded from the D-T16 directory, one `Context` under `UNTRUSTED` with the full §4.4 option set, `1 + 1`
evaluated and read back; then `while (true)` refused on time. GraalVM's guide says uber jars are "not
recommended" and that the class path may need `--add-modules=org.graalvm.polyglot`; nobody on this project
has run the isolate from a nested jar, and the whole packaging decision rests on it. **v0.7.1:** the external directory on `loader.path` (Spring's `PropertiesLauncher`, already the jar's `Main-Class`) is the
PRIMARY mechanism (the round-two plan's M1; D-T16), not a fallback — the lander's nested-jar probe needed no
`--add-modules`. If the spike fails on it, the alternative is a module-path launch in the Dockerfile — a change to how the
app starts, decided with the owner (8a's STOP rule).

**The ways this round fails**, with where each is found and what answers it:

| # | Failure | Found by | Answer |
|---|---|---|---|
| 1 | The isolate does not load from our deployment shape (`loader.path` directory beside the nested jar, `--add-modules`) | A.0, day one | D-T16's directory on `loader.path` (M1); else the module-path launch, with the owner |
| 2 | JDK 21 and GraalJS 25.4 disagree at runtime (the guide lists JDK 21; nobody here has run it) | A.0 | pin the previous line, or the JDK moves (its own decision) |
| 3 | A guest failure crashes the JVM (native code in-process; the Dockerfile records a JNA SIGSEGV precedent) | the breach suite's heap and stack cases | the fault-domain claim is measured, not believed; `engine.IsolateMode=external` is the fallback design, not shipped |
| 4 | Start-up and per-evaluation cost (isolate boot, a Context per batch, two JSON passes per batch) | the measurements below | `row`-mode batches come from `result-batch-size` today; a smaller `datapipelines.script.js.batch-size` ships ONLY if the measurement says so (R2-3), Context reuse only as its own decision (R2-7) |
| 5 | Memory on the server: the isolate heap sits beside the JVM heap; `pool-size × max-heap-mb` at defaults is 1 GB | sizing in 8b against the VPS (RAM read from the box, never from memory) | defaults chosen from the measured peak (`--sandbox.TraceLimits`), documented in deployment.md |
| 6 | The heap limit is soft (the guide says so): a guest can exhaust it before the check | the breach suite's three-way record | the input/output caps stay the real bound; the honest-bounds table says UNBOUNDED where it is |
| 7 | Determinism leaks (`Date.now`, `Math.random`, zone, locale) make one template mean two things on two servers | the conformance suite's determinism case + the prelude tests | §4.4's prelude; stated as an aid, not a boundary |
| 8 | Silent number damage: an integer above 2^53 rounds in the guest before the type gate sees it; a plain number returned for `BIGINTEGER`/`BIGDECIMAL` is quietly wrong, not refused | a type-gate case per column type (§6) | `BIGINTEGER`/`BIGDECIMAL` cross as strings both ways (D-T5); the engine refuses a non-string for those columns; one skill sentence |
| 9 | Dependency plumbing: four platform natives, `truffle-api`/`nativebridge`, lock files and verification metadata from a cold Gradle home, the vulnerability scan, the licence of the Community isolate (not yet read) | the dependency review in 8a | DEVELOPMENT.md §6.2's cold command; the licence read recorded in the handback |
| 10 | Egress at image build (Maven Central at `docker build`) | a hardened org without egress | D-T16's directory; Maven Central's artifacts are immutable by policy, so a private mirror is an ops choice, not a correctness need (see #8's comments) |
| 11 | The record itself was thin on the untrusted policy (six required options missing until v0.7) | — | §4.4 as rewritten; the conformance case "the Context builds with exactly the configured set" |
| 12 | The Engine boundary (v0.7.1): a per-evaluation Engine costs a spawn per batch; a per-JVM Engine shares the isolate heap and code cache across workspaces (the vendor's trust-domain rule) | A.0's D1 measurement (200 spawn cycles p50/p95; RSS over 1 000) | D1: per evaluation when the gate passes, else per JVM with the deviation recorded (§4.4) |
| 13 | The read-only root (v0.7.1): a runtime resource cache cannot be written under Helm's `readOnlyRootFilesystem` | A.0's `--read-only` container run | D5: resources pre-extracted at image build, `resourcePath` in the `ENTRYPOINT` (§4.4) |

**The measurements the round records** (handback, then `dag-executor.md` §5.3 beside JSONata's table, generated
not hand-written): isolate load time at boot; Context creation time (p50/p95 over 1 000); **(v0.7.1)** Engine spawn + first Context + close
(p50/p95 over ≥ 200 cycles) and process RSS over 1 000 cycles — the D1 gate; the `--read-only` run's verdict; the
`Object.getOwnPropertyNames(globalThis)` list with every time/entropy source refused or absent; the 100 000-row
`row`-mode run at `result-batch-size` and at two smaller batch sizes (wall, encode/decode share); peak isolate
heap under `--sandbox.TraceLimits`; the JS breach suite's three-way record (REFUSED / BOUNDED / UNBOUNDED)
for every §4.4 case; the jar and image sizes before and after.

---

## 5. Execution

### 5.1 Before the function runs

The executor checks each input against the contract once. Table inputs: the staged table's
column set and logical types (`ColumnSchema`: name, type, precision, scale, `nullable:
Boolean?` — absent means the driver did not say, and `H2IngressMapper` never fills it) must
cover every contract column with a compatible type; a contract column with `nullable: false`
is checked by scanning the batch (`row`) or the loaded table (`table`/`value`) for nulls unless
the staged schema positively says `nullable = false` — an absent value is never read as false
(the `ColumnSchema` KDoc's own rule). O(rows), once per batch. Value inputs: the
Context value's type must fit. Any mismatch is `pipeline.script.input_contract_violation`
naming the input and column. Row caps (§4.3) apply here. The test runner and
`templates_evaluate` apply the same check to the JSON they are handed — `rows` and each
table input against the contract's input columns, each value input against its type — so a
wrong-data test case can expect this code (§2.2's third case).

### 5.2 Driving the function

- **`row`**: the executor reads the single table input with `result-batch-size`
  (`H2StagingProperties.resultBatchSize`), builds `{ rows: batch, inputs: { <value inputs> }, meta }`,
  evaluates once per batch, and expects an array (or `{ rows, rejects }`) of **any length** —
  one row may become many or none (flat-map is the contract, v0.1's O-3). Output is inserted
  with `insert-batch-size`. The table never exists whole in the JVM, and **no state crosses
  batches** (§11).
- **`table`**: every table input loaded whole (capped), one evaluation, an array back.
- **`value`**: as `table`; one JSON value or object back, written to `context_key`.

### 5.3 The type gate (R1)

Every returned row is checked against the declared output columns: the key set must be
exactly the column set (`row_shape_mismatch`, with batch and row number); each value must
fit its logical type's wire form (`value_type_mismatch`, same detail). Numeric rules, in the
type system's vocabulary:

| Declared | Accepted from the engine | Then |
|---|---|---|
| `INTEGER` | a JSON number with no fractional part within int32 | stored; outside int32 is `precision_lost` |
| `BIGINTEGER` | a JSON string parsing as an int64 (the wire form); a JSON number is accepted only if it is an integer ≤ 2^53 (the engine's exact range) | stored; anything else is `precision_lost` |
| `DECIMAL(p, s)` | a JSON number | **rounded half-even to scale `s`**; the rounded value must fit precision `p`, else `precision_lost`. `0.1 * 3` becomes `0.30` under `s = 2`. |
| `BIGDECIMAL(p, s)` | a JSON string parsing as a decimal | exact: scale > `s` or precision > `p` is `precision_lost`; never rounded |
| `DECIMAL` without scale (approximate origin) | a JSON number | stored as-is |

Rejects pass the same gate against the input's columns plus a non-empty `reason`. A `value`
output is checked against the declared type (or accepted as-is for `kind: object`) and
against the size caps. Mixing shapes — an array where `{ rows, rejects }` is declared, or the
reverse — is `row_shape_mismatch` at the top level.

### 5.4 After the last batch: invariants, then strict

Invariants evaluate over `{ rows, rejects, inputs }` materialised as JSON: `rows` and
`rejects` are read back from the written tempdb tables (the invariants see the real table,
not the JVM's memory), `inputs` holds the value inputs and, for `table`/`value` mode, the
loaded tables; for `row` mode the single table input is read back from tempdb under its
contract name (in the test runner, it is the case's `rows` — R2). **The whole object is bounded
by `max-input-rows`** across rows + rejects + table inputs: a `row`-mode node whose output
exceeds it fails with `invariants_too_large` **if the template declares any invariant**; with
none declared the node streams without bound. The docs say so plainly: an assertion over an
unbounded table is a SQL node or a release check, not an invariant. Any `expr` that is not
exactly `true` fails the node with `invariant_failed` naming the invariant and its message.
Then `strict`: a non-empty rejects table with `strict: true` fails with `rejects_strict`,
carrying the count and the first ten reasons.

### 5.5 Atomicity, canonical form, stats

- **Failure is atomic.** Any refusal above rolls back the output and rejects tables the way a
  failed stage is rolled back (staging §4.3). A Context key is written only after invariants
  and strict pass.
- **Canonical JSON** (used by the test comparison and the gate): object keys sorted; integers
  as integers; doubles in shortest round-trip form; `DECIMAL` as a number rounded to the
  declared scale; `BIGDECIMAL` as a string normalised to the declared scale (`"12.5"` →
  `"12.50"`); DATE/TIMESTAMP/TIME as ISO-8601 strings normalised to the type system's
  canonical form; arrays in the order the function returned them. One implementation, in
  `modules/scripting`, used by every caller.
- **Object keys persist as jsonb (R4)** in the resolved Context of the execution, under the
  same 1 MB cap; the run record and SSE show them as JSON.
- **Stats and events**: `rows_in` (new on `NodeStats` — no node reports it today), `rows_out`,
  `rows_rejected`, `invariants_checked`, and for `value` mode `context_key` and
  `context_value`, through SSE and the run record like every other node. A failure is the
  standard node failure record.

---

## 6. Types at the boundary (D-T5, corrected)

There is no script-specific type table. A contract names `LogicalType` wire names, and a
value crosses in the wire form type-system.md §3 already fixes for that type:

- JSON **number**: `INTEGER` (int32), `DECIMAL` (precision ≤ 15; scale-less when the origin
  was approximate).
- JSON **string**: `BIGINTEGER`, `BIGDECIMAL`, `STRING`, `DATE`, `TIME`, `TIMESTAMP` (ISO-8601
  in the type system's canonical form — never epoch numbers).
- JSON **boolean**: `BOOLEAN`. JSON **null**: any nullable column.
- `BINARY`: refused as a declared input or output column (§2.2, `type_unsupported`).

The reverse direction is the contract's declared columns, enforced by §5.3 with R1's rounding
rule. Nothing is inferred. The engine's int/long/double fitting (§4.2) is why `BIGINTEGER`
and `BIGDECIMAL` cross as strings: a body that wants exact arithmetic on them converts
explicitly and the gate refuses what does not come back exact.

---

## 7. Codes (pipeline-contract **§13.18 Script** — §13.16 is the MCP surface and §13.17 release checks today; the parameter-engine record's claim on §13.16 is stale and rebases later, R8. The drift tests move in the same commit: `PipelineErrorCodesSpecDriftTest.DOMAINS`, `ApiErrorCatalogSpecDriftTest`'s `SECTION_13_ROW_COUNT` and `SECTION_12_CODE_COUNT`, `AuthErrors`' prefix→anchor map, `ApiErrorCatalog`'s family maps, enums.md §16's domain row, `TemplateErrorCodesSpecDriftTest` for the `template.*` rows)

| Code | HTTP | When |
|---|---|---|
| `pipeline.validation.script_template_type` | 400 | pinned template is not `jsonata`/`javascript` |
| `pipeline.validation.script_source_forbidden` | 400 | `source` on a SCRIPT |
| `pipeline.validation.script_input_unknown` | 400 | a table no upstream stages, or a `$key` nobody writes |
| `pipeline.validation.script_input_contract` | 400 | `inputs` key set ≠ contract, or a value input's type does not fit |
| `pipeline.validation.script_output_shape` | 400 | output block does not fit the contract's mode (a `target` on value mode — R3; a `context_key` on table mode; a table output without `table` on `tempdb`) |
| `pipeline.validation.script_rejects_undeclared` / `_missing` | 400 | `output.rejects` vs the contract's `rejects` |
| `pipeline.validation.script_rejects_on_caller` | 400 | `rejects` on a `caller` output, or a rejects-declaring contract on a `caller` node (R5) |
| `pipeline.validation.script_strict_without_rejects` | 400 | `strict` on a contract without rejects |
| `pipeline.validation.script_object_key_bound` | 400 | a SQL `:bind`, calculator input or `PIPELINE` parameter reference to an object-valued key (R4) |
| `pipeline.script.input_contract_violation` | 500 | §5.1 |
| `pipeline.script.input_too_large` | 500 | §4.3 |
| `pipeline.script.evaluation_failed` | 500 | the script threw; detail carries the engine message (bounded by `ErrorCodeMapper.MAX_MESSAGE_CHARS` = 2000) and, for JS, the line |
| `pipeline.script.timeout` | 504 | the engine's own bound fired (`pipeline.node.timeout` is the outer) |
| `pipeline.script.resource_limit` | 500 | depth (JSONata); heap / statements (JS); detail names which |
| `pipeline.script.pool_exhausted` | 503 | §4.3: the evaluation pool's queue is full |
| `pipeline.script.row_shape_mismatch` / `value_type_mismatch` / `precision_lost` | 500 | §5.3, with batch and row |
| `pipeline.script.value_too_large` | 500 | §4.3 (value, object or a single string) |
| `pipeline.script.invariant_failed` | 500 | §5.4, invariant name + message |
| `pipeline.script.invariants_too_large` | 500 | §5.4 |
| `pipeline.script.rejects_strict` | 500 | §5.4, count + first ten reasons |
| `template.validation.engine_unsupported` (rule changed) | 400 | §2.1: engine must match the type |
| `template.validation.freemarker_forbidden` | 400 | D-T9: a Freemarker construct in a script body; `imports` non-empty or `is_library` on a script type (detail) |
| `template.contract_invalid` | 400 | §2.2, detail names the rule (`row_mode_inputs`, `type_unsupported`, `empty_case_missing`, `row_case_lists_table`, `mode_output_mismatch`, …) |
| `template.invariant_invalid` | 400 | a non-compiling or non-boolean invariant |
| `template.test_failed` | 400 | §8.1, case + assertion + bounded diff |
| `template.blocks_not_allowed` | 400 | contract/invariants/tests/implements on `sql`/`html` |
| `template.render_not_applicable` | 400 | `templates_render` on a script type; detail points at `templates_evaluate` |
| `template.implements_unresolved` | 400 | §2.3 |
| `script.js.unavailable` | 503 boot log / 400 at save | §4.4 (round two) |

`pipelines_execute_node` on a SCRIPT refuses with the code it already uses for a tempdb
source ("no ancestors run and no tempdb exists"), detail pointing at `templates_evaluate`.

The HTTP column for `pipeline.script.*` is the class the node failure record carries, as
for every execution-time family in §13.4; through `templates_evaluate` and the test runner
these surface inside `template.test_failed` (400) or as the tool's own 4xx/5xx.

---

## 8. Lifecycle and the semantic link

### 8.1 Save and release

**Save** of a script version runs, in order: body parse; contract validation; invariant
compile; the test suite. Every case runs through the real engine under the version's limits on
the evaluation pool; its output passes the type gate against the contract; the invariants run
on it; the result is compared to `expect` after canonicalisation. The first failure refuses
the save with `template.test_failed` naming the case, the assertion (`output` diff bounded to
2 000 chars, or the expected vs actual refusal code) and, for an invariant, its name. A draft
is re-validated on every update.

**Release** re-runs the suite on the version being released and refuses on the same code.
A suite that passed at save fails at release only if the engine changed — which is the case
worth catching.

**Pipeline save** checks each SCRIPT node's `inputs` and `output` against the pinned
version's contract (§3.1) and every binding of an object-valued key (R4): the dry-render
analogue for scripts, where D3's "prove the two agree" survives.

### 8.2 Drift (D-S6 applied to code)

When a cited fact is retired (`learned_facts.trust = 'retired'`; a superseded fact is a retired
one with `retired_reason = 'superseded'` and a successor carrying `supersedes` — there is no
`superseded_by` column), every version citing it is marked `needs_review` **on read** — served
with the marker, never edited, never auto-remapped (the `LearnedFactDrift` precedent; the name
is shared with the fact trust state `needs_review` on purpose: both mean "re-verify before you
lean on it"). Pinning a `needs_review` version at pipeline release is a **warning** in the
release response and the dialog, not a refusal: a fact edit never blocks a release on its own.
No release-warnings channel exists today — the release response (`PipelineReleaseService.Released`,
the REST body) gains `warnings: [ { code, message, template, version } ]` and the dialog's pin
rows a `needsReview` flag (`PinView`); both are lane 7e's. Clearing the marker is a
deliberate act — cite the superseding fact, or remove the citation.

### 8.3 Discovery

`semantics_list` rows gain `implemented_by: [ { template_id, version } ]`; `templates_list`
accepts `implements: <fact_id>`. The skill's learn-first step gains its third half: search the
facts, find the definition, find the script that implements it, reuse before writing.

---

## 9. Surfaces

### 9.1 MCP

The `templates_*` family gains no per-type names. `templates_create`/`_update` accept
`contract`, `invariants`, `tests`, `implements` and refuse them on `sql`/`html`
(`template.blocks_not_allowed`); `templates_get` returns them; `templates_list` accepts `type`
(exists today) and `implements`. **One new tool, `templates_evaluate`**: `{ name, version?, input }`
runs the version over a caller-supplied input object under `evaluate-timeout-seconds` on the
evaluation pool, applies the type gate and the invariants, and returns
`{ output, rejects, invariants: [ { name, passed, message } ] }` — no staging, no Context. It
is `sql_probe`'s twin and the service the editor preview, the REST route and the test runner
share. `templates_render` refuses script types with `template.render_not_applicable`
(400, detail `use: templates_evaluate`). **The render-before-you-run check (139,
`pipeline.execution.template_unrendered`) treats a successful `templates_evaluate` of a draft
script version as its render** — without this, every draft pipeline pinning a draft
script would be refused at execute forever; the check reads the same audit rows, keyed
by tool name, so it is one more name in its allow-set, pinned by a test.
`pipelines_execute_node` **refuses** a SCRIPT (it refuses every tempdb node by design);
its description says so. The tool count moves 41 → 42 and every count (catalog, scope matrix,
`tools.md`, `DatasourcesCreateRemovedTest`, the wiring test, mcp-server.md) moves in one
commit; the `mcp.tool.called` audit row covers the new tool as it covers every other.

### 9.2 REST

The templates routes accept and return the three blocks and `implements` on the existing
create/update/get shapes (`rest-api.md` templates section; refused on `sql`/`html` with
`template.blocks_not_allowed`). **One new route, `POST /api/v1/templates/evaluate`**
(`{ name, version?, input }`, the MCP tool's body and response, same service, same pool,
same timeout) — the UI's run-suite action and any client without MCP call it. The list route
gains `implements=<fact_id>` beside the existing `type` filter. `POST /api/v1/templates/render`
refuses script types as the tool does.

### 9.3 UI

The template explorer gets the three faces as a type filter over one tree. The script
editor face has four panes — body, contract, invariants, tests — each a `<textarea>` like
today's SQL body (no grammar, no editor component: the roadmap's "template editor, per type"
row owns that later), a **Save draft** action (today's editor has no body save — the create
modal is the only body write; the script face's save posts the four panes to the
existing mutate operation and is new UI work, lane 7d) and a **run suite** action that posts
each case to the evaluate route and shows green/red with the diff. Release uses the existing
lifecycle dialog. The
pipeline editor renders a SCRIPT card with the language badge and a Details tab (inputs,
mode, output, rejects, strict), read-only as every node is today. A `needs_review` version
shows its marker in both explorers and on the card. Every verb sits inside the role guard
its row in §9.4 names.

### 9.4 Roles (AGENTS.md rule of 2026-09-20; R6; vocabulary of the permissions record, v0.4)

Authorization is a catalog permission per surface (`<functionality>.<permission>`, PK1); roles hold
permissions; no code compares role names. The cells are the permissions record's §2.1 rows; R6's
choice (the `templates_render` row for evaluation) is unchanged. Lane 7b landed `templates_evaluate`
and `POST /api/v1/templates/evaluate` in the pre-catalog vocabulary (`Scope.AUTHOR` / `Permission.AUTHOR`);
#215's slice (a) migrates them to `template.evaluate`. No script surface gains or loses access.

| Action (route / tool / verb) | viewer | author | promoter | ws_admin | super_admin | Catalog permission | Guard |
|---|---|---|---|---|---|---|---|
| `templates_evaluate` (MCP), `POST /api/v1/templates/evaluate` | ✗ | ✓ | ✗ | ✓ | ✓ | `template.evaluate` (7b landed it on the `templates_render` row; #215(a) renames) | catalog drift (`ScopeMatrixSpecDriftTest` as extended by #215), `RequiredScopeCoverageTest`, `MatrixRowReachabilityTest`, `RoleWalkE2eTest` |
| `contract`/`invariants`/`tests`/`implements` on `templates_create` / `POST /api/v1/templates` | ✗ | ✓ | ✗ | ✓ | ✓ | `template.create` — fields on an existing action | existing |
| the same on `templates_update` / `PUT /api/v1/templates` (`implements` on a released version too) | ✗ | ✓ | ✗ | ✓ | ✓ | `template.update` | existing |
| `templates_list?implements=` / `GET /api/v1/templates?implements=`, `needs_review` on read | ✓ | ✓ | lens | ✓ | ✓ | `template.read` — a filter/field on an existing read | `ReadFloorTest` |
| `semantics_list` `implemented_by` | ✓ | ✓ | ✓ | ✓ | ✓ | `semantic.read` — a field on an existing read | existing |
| the release `warnings` (a `needs_review` pin) | ✗ | ✓ | ✗ | ✓ | ✓ | `pipeline.release` — a field on the existing release | existing |
| UI: Save draft on the script face (`POST /partials/templates/script-face/save`) | ✗ | ✓ | ✗ | ✓ | ✓ | `template.update` | `RequiredScopeCoverageTest`, `MutatingHandlerScopeFloorTest`, `RoleVisibilityRenderTest` |
| UI: Run suite (`POST /partials/templates/script-face/run-suite`) | ✗ | ✓ | ✗ | ✓ | ✓ | `template.evaluate` | the same |
| UI: the face read-only, the `needs_review` marker, the node card | ✓ | ✓ | lens | ✓ | ✓ | `template.read` / `pipeline.read` | `ReadFloorTest`, `RoleVisibilityRenderTest` (no verb) |
| `pipelines_execute_node` on a SCRIPT | ✗ | ✓ | ✗ | ✓ | ✓ | `pipeline.execute_node` — unchanged row; REFUSES the type | existing |

Keys: the MCP key holds the member's role (capped at author, PK4), so a member's agent can
evaluate exactly when the member can. No API-key role holds `template.evaluate`, `template.create`
or `template.update` (§3.2 of the permissions record; the `api_caller` role serves endpoints only).

### 9.5 Security (AGENTS.md rule of 2026-09-21)

- **The body is untrusted code evaluated on the server.** Its blast radius is §4.5's table:
  CPU (bounded between steps + pool + abandon), depth (bounded), heap (NOT bounded by the
  library — input/output caps only), no host access (no registered functions; `$now`/`$millis`
  pinned). A template author must hold `template.evaluate` (author and above, §9.4): the evaluation
  surface is reachable through a member's MCP key only, never through an API key.
- **Injection:** no Freemarker on script bodies (D-T9); a body is never spliced with any
  value; inputs reach the function as a JSON object, never as source.
- **SQL:** every tempdb table name a SCRIPT writes is validated by §6.1's regex and passed
  through the staging API that already quotes identifiers; no statement is built from a
  template's output.
- **Rendering:** contract, invariants, tests and any engine message reach the UI through
  `th:text`; the run-suite diff is text.
- **Secrets:** none; `templates_evaluate` audits its call (`mcp.tool.called`) with the
  template name and version, never the input object.
- **Outbound:** none. The library makes no network call (the conformance suite proves it by
  running with no network in the container).

### 9.6 Published endpoints

Unchanged in this record (R3). A pipeline whose caller node is a `row`/`table`-mode SCRIPT
serves its rows through the existing endpoint contract (`schema`, `rows`, paging), the columns
being the contract's output columns. The nested-object body is the dashboards design's (§11).

### 9.7 Skill

One playbook rule in generic wording: *SQL when SQL can; a script when the shape is
nested, the logic is per-row, or SQL cannot say it; a script is a pure function over
staged data with a declared contract and a test suite — write the empty and wrong-data cases
before the right-data one.* A new reference `scripts.md` (node shape, input object,
contract/test format, the type rule, codes, what the sandbox does and does not bound); the
error-codes reference gains the families; `tools.md` regenerates (`:mcp-server:skillToolsDoc`);
the skill artifacts task runs in the same commit; `SKILL.md` links `references/scripts.md`
from its reference map and stays within `MAX_SKILL_LINES = 400` — it is at 400 today, so the
rule and the map row come in only with lines taken out elsewhere (the orchestrator reads the
skill diff at merge). Zero dataset facts (`SkillHasNoDemoContentTest`).

---

## 10. Testing requirements (the gate for round one; every guard shown red on purpose)

1. Conformance suite over the JSONata engine, parameterised so a second engine adds cases to
   nothing. The JSONata breach suite (§4.5) with its three-way outcome record.
2. Type behaviour: `sql`/`html` refuse the blocks; script types require them; `engine` must
   match the type both ways; `dialect`, `imports`, `is_library` refused on script types;
   Freemarker in a script body refused; the empty case enforced; a `row`-mode case listing
   its table refused (R2); `mode`/`output.kind` consistency; `BINARY` refused.
3. Test runner: a failing case refuses save with case + diff; release re-runs; canonicalisation
   ignores key order and decimal spelling; an `expect.refusal` matches only that code; the
   suite timeout fires; every case runs on the pool.
4. Node validation for every §7 400-row (including R3's `target` on value mode, R4's object-key
   binding by a SQL node, a calculator and a `PIPELINE` parameter, R5's rejects-on-caller both
   ways); the three drift tests extended for every new code (`SECTION_13_ROW_COUNT` re-derived,
   not incremented); `pipelines_execute_node` refuses a SCRIPT.
5. Executor per mode on the H2 staging: `row` never materialises the input (peak staged rows
   via the existing memory-budget hooks); `table`/`value` caps refuse before loading; rejects
   and strict; rollback of both tables on failure; invariants read the written tables;
   `invariants_too_large` on an over-cap `row` output with invariants and streaming without;
   a Context key written only after everything passes; caller-supplied override skips the
   node; an object key persisted as jsonb and readable by a downstream SCRIPT; the pool
   exhausted → `pool_exhausted`; an abandoned evaluation counted and the node failed on time.
6. The type rule both ways: `DECIMAL` rounding (`0.1 * 3` under scale 2 → `0.30`; overflow of
   `p` → `precision_lost`), `BIGDECIMAL` exactness, `INTEGER` int32 edge, `BIGINTEGER` as string
   and as a ≤ 2^53 number, DATE/TIME/TIMESTAMP canonical strings, a string over
   `max-string-bytes`.
7. Semantic link: a DATASOURCE fact and an invisible fact refused; a retired fact marks every
   citing version on read; release with a `needs_review` pin warns and proceeds; both
   discovery filters return the link.
8. `templates_evaluate` through the real dispatcher with a key principal, plus its audit
   event; the REST route through the role walk; `templates_render` refusal on a script
   type; check B accepts an evaluate as the render.
9. One E2E: stage from Postgres + lake → SCRIPT `row` with rejects → SCRIPT `value` to
   a Context key → a SQL node binding `:that_key` → a `table`-mode SCRIPT as the caller node
   → the endpoint returning its rows through the existing contract.
10. Browser tests for the script face, the run-suite action, the node card and the
    `needs_review` marker, with screenshots under the existing shot conventions.
11. Behaviour-object parity: the SQL/HTML rules moved behind `TemplateTypeBehaviour` produce
    byte-identical refusals on the existing template suites.

---

## 11. Out of scope, by name (this record; the next design that owns each)

| Deferred | Why not now | Where it goes |
|---|---|---|
| **A nested-object endpoint body** — a `value`-mode caller node whose JSON is the response | R3: the endpoint contract has one shape; a second one is the dashboards' need | dashboards design |
| **Ingestion nodes** — file acquisition, parsing, cursors, lake commit, quarantine replay | its own security and durability story; composes WITH SCRIPT rather than extending it (owner 2026-09-17) | the dp-lake ingestion design (2026-09-16 discussion) |
| **The dashboard runtime** — call the engine with held data, push chart payloads over SSE | not a pipeline; needs only the §4.1 seam | dashboards design |
| **Freemarker on script bodies** | D-T9 — injection without a bind mechanism | entry condition: the SQL `parameter_interpolated` refusal ported to JS, plus a case an `if` cannot express |
| **Custom JSONata functions** (`$decimal()`, date helpers) | a template's meaning must be deployment-independent; measure the need on round-one usage | round two or later |
| **A heap bound for JSONata** | §4.5: not enforceable in-process; the isolate story exists for JS | if a deployment needs it: JSONata behind the same isolate, its own record |
| **A code editor component** (grammar, highlighting) | the editor is a textarea for SQL too | the roadmap's "template editor, per type" row |
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

**The 2026-09-23 review's B1–B9: all resolved (§0.1).** Nothing blocks dispatch.

**For round two (re-listed 2026-10-20, v0.7):** R2-1 `console.*` → **ruled** (D-T17: the application log, and
the evaluate/test result). R2-2 the fallback knob → lean: refuse outright, not shipped (decided at the 8a
dispatch). R2-3 `row`-mode batches for JS come from `result-batch-size` as for JSONata; a smaller
`datapipelines.script.js.batch-size` ships ONLY if §4.6's measurement says a smaller JS batch is needed (v0.7.1: the key
is not new by default). R2-4 the JS clock pin → the §4.4 prelude (`Date.now()`/`new Date()` under the isolate,
unpinned → refuse), the twin of §4.5's `$now()` rule; `Math.random` REFUSED this round (v0.7.1 — no seed exists; a seed
field is a later decision). R2-5 packaging → **ruled** (D-T16). R2-6 the number trap (§4.6 row 8) →
strings both ways for the big types, refused otherwise. R2-7 Context reuse across batches → not in this round;
only if R2-3's measurement forces it, and then as its own decision (shared state between batches is the
risk §11 names).

**Ruled 2026-10-20 after the lander's review (v0.7.1; store `notes/2026-10-20-javascript-review-response.md` §3):**
R2-8 the Engine boundary → **ruled** D1 ("3 if not then 2": per evaluation when the spike's gate passes, else per JVM with
the deviation recorded; per workspace not chosen) — §4.4. R2-9 compile → **ruled** D2 (parse only; the pooled test cases /
`{}` probe refuse a bad body at save) — §4.4, 8b. R2-10 numbers → **ruled** D3 ("1 by logical type"; no decimal library
this round, a follow-up) — §4.4. R2-11 the UI → **ruled** D4 (the JavaScript template is a template like JSONata: a
skeleton body yes, NO console panel — console lines render inside each case's result; `function main(input)` and the braces
are immutable labels around the editable statements, §2.2) — 8b. R2-12 the read-only root → **ruled** D5 (pre-extracted
resources, no runtime cache write; the owner was to object only if they disagreed, and did not) — §4.4. Still open for a
later round: a `Math.random` seed; a vetted decimal library in the prelude; Context reuse (R2-7); per-workspace Engines if
the per-JVM arm ships and a tenant incident shows the shared heap matters.

---

## 13. The Tableau map (D-T6, unchanged from v0.1, condensed)

Extracts → scheduler + lake write-back; calculated fields → a `metric` template type;
alerts/subscriptions → scheduled pipeline + a `value` SCRIPT or calculator + notification;
embedding with RLS → endpoints + dashboards; catalog/lineage → the DAG in the explorer;
blending → the tempdb join; Ask/Explain Data → the agent; Prep flows → pipelines + SCRIPT.

---

## 14. Lane split and build order (round one; one-then-next — each lane's contract is the next lane's premise)

| Lane | Fence | Delivers | Depends on |
|---|---|---|---|
| **7a — the engine** | new `modules/scripting` (+ the layering table and the root build's allowed-dependency list) | `ScriptEngine`, `EvaluationLimits`, `EngineCapabilities`, the JSONata engine, canonical JSON, the evaluation pool, the conformance suite, the breach suite. No product surface, no config wiring beyond the module's own properties class. | — |
| **7b — the template model** | `templates`, `application`, `mcp-server`, `web` (templates REST + the explorer's type filter only), `app` (the migration), docs `templates.md`/`enums.md`/`metadata-db.md`/`rest-api.md`/`mcp-server.md`/`auth.md` §7.6, the skill | the two types; `engine`/`dialect`/`imports`/`is_library` rules; the blocks in the hash and the export; `chk_script_blocks`; `TemplateTypeBehaviour` with parity; contract/invariant validation; the test runner at save and release; `templates_evaluate` MCP + REST with roles rows; render refusal; check B allow-set; `tools.md`; `scripts.md` (the template half). | 7a merged |
| **7c — the node** | `pipeline-contract`, `dag`, `mcp-server` (validation + execute_node refusal only), docs `pipeline-contract.md` §4/§13, `dag-executor.md`, `configuration.md`, the skill's node half | SCRIPT validation codes (R3–R5), the executor per mode, the type gate (R1), rejects/strict, invariants read back, atomicity, Context keys incl. object keys, stats/SSE, the `datapipelines.script.*` block, the E2E. | 7b merged |
| **7d — the UI** | `web` templates/JS/browser tests for the explorer faces, the script editor face, the node card | §9.3 with screenshots. | 7b merged (may overlap 7c: `web` vs `dag`) |
| **7e — the semantic link** | `datasources/semantics`, `templates` (the join table + `implements` on write/read), `mcp-server` (`semantics_list` field, `templates_list` filter), `web` (the marker, the release warning, the list filter), `app` (its migration), docs, the skill's learn-first step | §2.3, §8.2, §8.3 (R9). | 7b merged; after 7d (both touch `web`) |

**Order (owner, 2026-09-24):** 7a and 7b are on main (`ad183ebc`, `4e3134cd`); the permissions work
(#215, three slices) lands next and BEFORE 7c; then 7c → 7d → 7e in strict sequence, each
prompt naming its base at dispatch. Round two (#8, the JavaScript engine) and the parameter engine
follow the scheduler (R8).

**Round two (#8, v0.7 — one-then-next; L lanes for Claude Code):**

| Lane | Fence | Delivers | Depends on |
|---|---|---|---|
| **8a — the engine** | `modules/scripting` (+ the version catalog, lock files, verification metadata) — including `ScriptEvaluationPool.kt` for the cancellation hook, `Dockerfile` and `deploy/` for D-T16/D5, `DEVELOPMENT.md` | A.0 the spike (§4.6: packaging, the D1 measurement, the `--read-only` run); `JavaScriptRuntime` (the D1 arm, availability, the ONE factory every Context goes through); `JavaScriptEngine` on the §4.1 seam with the §4.4 option set incl. `MaxStackFrames`, the wall-clock watchdog, parse-only compile, the prelude (clock pin, `Math.random` refused, console shim), the number rule, the return-shape contract, the byte budgets (`EvaluationLimits.maxInputBytes`/`maxResultBytes`/`console`, defaulted); the pool's per-slot cancellation hook (`close(true)` before the interrupt); registration in `ScriptEngineConformanceTest`; `JavaScriptBreachTest` in the `breachTest` source set with its own report; the §4.6 measurements; the D-T16 fetch in the image with SHA-256 verification and the D5 pre-extraction (`JavaScriptResourcesMain`, `resourcePath` in the `ENTRYPOINT`); the dependency review (licence read, cold-home locks, the image scan). No product surface: the template still refuses at save. (v0.7.1) | the SCRIPT rename on main (it is, 8b05522f8) + this errata commit |
| **8b — the wiring** | `templates` (`TemplateTypeBehaviour.Javascript`), `application` (`TemplateEvaluateService`, the test runner — the pooled cases / `{}` probe at save, D2), `mcp-server` (`TemplatesEvaluateTool`, `TemplateAuthoringTools`: the console in the result), `web` (`ScriptConfiguration`, `TemplateValidator`'s default map, `ScriptProperties`, `ApiErrorCatalog`, `AuthoringStartupCheck`, the face: the skeleton body and the immutable `main` labels, D4), `dag` (`ScriptNodeRuns.engineFor`), `pipeline-contract` (`PipelineErrorCodes`; `ScriptRules` exists in BOTH `pipeline-contract` and `app` — the brief names which), `app` (`ScriptRules`, the config-keys drift test), `typesystem` (`TypeGate.checkDecimal` widening, D3), docs `configuration.md` §3.28 / `dag-executor.md` §5.3 / `templates.md` / `deployment.md`, the skill (`scripts.md`'s "until round two" line, the number sentence) | the engine map gains `JAVASCRIPT` at every site; the boot-time disable path; D-T17 in `templates_evaluate` and the test runner (console lines inside each case's result); the config keys with their defaults from 8a's measurements (the byte budgets, heap, isolate memory, `batch-size` only if measured); `ScriptNodeE2eTest` (`tests/integration-tests/.../ScriptNodeE2eTest.kt` — v0.7.1: there is no §10.9) run once with a JavaScript body. (module fences corrected v0.7.1: the lander's point 10) | 8a merged |

---

## Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-10-21 | v0.7.1 | orchestrator, after the lander's review and the owner's rulings D1–D5 (2026-10-20) | Errata before lane 8a (plan §10 + review response §4). §4.4: the Engine boundary D1 (per evaluation when the spike's gate passes, else per JVM with the deviation recorded; v0.7's "one shared Engine" was an undocumented deviation from the vendor's trust-domain rule); `sandbox.MaxStackFrames` exists and bounds depth (v0.7 said no stack-depth option); `MaxCPUTime` is CPU not elapsed — the engine's `close(true)` watchdog, wired through a new per-slot pool hook, is the wall-clock bound (the lander's probe: interrupt does nothing, `close(true)` ~3 ms); compile is parse only (D2); the number rule by logical type (D3; "TypeGate catches precision loss" withdrawn; the plan's strings-for-every-BigDecimal withdrawn); the return-shape contract (`JSON.stringify`'s silent repairs refused; `undefined` object properties dropped); the input/result byte budgets; console semantics (one tagged stream, 200 × 2 000, past the cap refuses); the read-only deployment D5 (pre-extracted resources, Helm's `readOnlyRootFilesystem` kept true). Corrections: `Math.random` refused, no seed (v0.7 said a pinned seed); the time zone via the builder (v0.7's `js.timezone=UTC` was unverified); `loader.path` is the PRIMARY packaging mechanism (v0.7 listed it as the first fallback); `batch-size` ships only if measured (v0.7 named it as new); §4.1 shows the seam as it is in the tree (`ScriptLanguage`, `capabilities`, `input: Any?`, `now`) plus 8a's three defaulted fields; §4.6 rows 12–13 and three measurements; §12 R2-8..R2-12 (D1–D5 ruled); §14 row 8b: `ScriptNodeE2eTest` (there was no §10.9), module fences corrected (`TemplateEvaluateService` is `application`, the two tools `mcp-server`, `PipelineErrorCodes` `pipeline-contract`, `ApiErrorCatalog`/`AuthoringStartupCheck` `web`, `ScriptRules` in both `app` and `pipeline-contract`), 8a's deliverables grown. D-T16 notes `loader.path`. The body contract (D4, the statements of `main(input)`) was already written into §2.2 by the rename lane. |
| 2026-10-20 | v0.7 | orchestrator, with the owner (round-two readiness, #8) | §4.4 re-verified against the current GraalVM sandboxing and embedding guides and Maven Central: pin 25.4.4.1.1, the artifact layout (aggregator poms over `org.graalvm.js:js-isolate-<platform>-community`), the untrusted policy's full required option set (`engine.MaxIsolateMemory`, `sandbox.MaxASTDepth`, the stream caps, `in`, `HostAccess.UNTRUSTED`), `spawnIsolate(true)` explicit, a tripped Context is dead, cancellation from outside; the determinism prelude. New §4.6: the spike, eleven failure modes with where each is found, the measurements. D-T16 packaging (owner): the jar carries no isolate, the image fetches its `TARGETARCH`'s with SHA-256, a directory setting for non-Docker deployments. D-T17 (owner): `console.*` to the application log, and to the evaluate/test result. §12 round-two decisions re-listed (R2-1, R2-5 ruled). §14 lanes 8a/8b. |
| 2026-09-09 | v0.1 | orchestrator, after the owner's brainstorm | Initial record: six decisions; engine and sandbox facts verified against graalvm.org and Maven Central; decimal-as-string rule. |
| 2026-09-17 | v0.2 | orchestrator, after the owner's second brainstorm | Rewritten in place. Pure function only — no tempdb handle, no `emit` (D-T4); one Context namespace, `params`/`context` removed from the input object (D-T10); `meta` block; mode moves to the template's contract; **contract, invariants, tests on the version inside the body hash** (D-T7), a scoped D3 exception (D-T8); no Freemarker on transform bodies (D-T9); rejects + strict (D-T11); exact canonical equality + invariants on every case (D-T12); invariants always JSONata (D-T13); `implements` + `needs_review` drift + discovery (§8); `templates_evaluate` returns invariants; two rounds, JSONata first (D-T14); the `UNTRUSTED` host-collection rule and its JSON-text consequence recorded for round two (§4.4); `values` in the result payload considered and dropped (dashboards are a separate runtime); v0.1's O-1..O-5 resolved. |
| 2026-09-23 | v0.3 | orchestrator, after the pre-dispatch review and the owner's rulings R1–R9 | Ratified for dispatch. Corrections against the tree at `be0305b3` (§0.2): `LogicalType` vocabulary replaces the invented type table (D-T5, §6); the body hash is the SQL `jsonb_build_object` expression, extended, with export/import carrying the blocks (§2.2); `execute_node` refuses a TRANSFORM (§9.1); `engine` type-conditional, `imports`/`is_library` refused (§2.1); textarea panes (§9.3); one save-time `type_unsupported`; nullability always scanned (§5.1). Rulings: `DECIMAL` rounding at the gate (R1, §5.3); row-mode cases mirror production (R2, §2.2); no JSON result bodies, value mode writes a key only (R3, §3.1, §9.6, §11); object keys readable by TRANSFORM only (R4, §3.1, §7); rejects-on-caller refused (R5); the roles table (R6, §9.4); D-T15 + §4.5 the honest JSONata bounds, the evaluation pool, the caps, the breach suite (R7); build order transforms → scheduler → parameters (R8); `implements` in round one as lane 7e (R9). New §9.2 REST, §9.5 Security, §14 lane split. |
| 2026-09-26 | v0.5 | lane 260, the depth-accounting fix (#260) | §4.5's Depth row corrected: the library `Timebox` returned early on `isParallelCall` frames (the second and later object pairs/arguments), leaking one depth unit per item on set-level shapes — a 240-row reshape refused at the default depth of 100 — and skipping the clock check in those frames. The engine now counts every evaluate entry/exit with its own hooks; nested expressions and non-tail lambda recursion are depth refusals, a tail-recursive lambda is trampolined (depth flat) and caught by the wall clock, which is checked at every entry and exit. §7 unchanged; no new limit, knob or error code; the refusal's `details` stay `{kind}` (the issue's "enrich the refusal" direction was for a refusal that is no longer false). The issue's "set-level work belongs in SQL" docs direction NOT adopted: set-level JSONata works. |
| 2026-09-24 | v0.4 | orchestrator, after the permissions record | §9.4 rewritten in the catalog vocabulary (`template.evaluate` / `template.create` / `template.update` / `template.read` / `semantic.read` / `pipeline.release` / `pipeline.execute_node`; guards as #215 extends them); §9.5's holder sentence; §14 order: #215 before 7c (owner). No cell changed. |
| 2026-09-23 | v0.3.1 | orchestrator, at the 7a merge | §4.3 pool: the bulkhead — an abandoned evaluation keeps its slot until its thread ends; `pool-queue` bounds total admissions; a caller waits at most its own wall clock. §4.5: depth catches expression nesting, time catches recursive lambdas; the breach suite's measured outcomes recorded. §2.2: a test case may pin the clock with `now` (7a's A.3 made `$now()` refuse when unpinned). |
