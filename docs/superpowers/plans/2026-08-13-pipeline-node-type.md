# PIPELINE Node Type (Pipeline Composition) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fourth `NodeType`, `PIPELINE`, whose node executes another (version-pinned) pipeline as a real child execution through the internal execution service and consumes its caller-node result directly.

**Architecture:** Per the approved design (`docs/superpowers/specs/2026-08-13-pipeline-node-type-design.md` — READ IT FIRST; decisions D1–D9 govern this plan). Child = own execution record/tempdb/stats/SSE; `direct` delivery streams the child's caller ResultSet to the parent executor (nothing in Redis); lineage columns link the family; cancellation propagates by `root_execution_id`; save-time validation proves references, parameters, depth against pinned (immutable) child bodies.

**Tech Stack:** Kotlin coroutines, Spring, Flyway (Postgres), existing NamedParameterJdbcTemplate repositories. Tests: JUnit 5 runner + Kotest matchers + MockK. NO new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-13-pipeline-node-type-design.md`

**One deliberate deviation from the design doc (§4.4 fan-out):** children do NOT take their own concurrency slot — only root executions do. The design said "children count toward the cap and wait"; that deadlocks (a parent holds a slot while its children wait for slots; cap < family size never resolves). Composition volume is instead bounded by depth (≤5) and the per-pipeline 1000-node cap. The design doc gets a correction note in Task 1.

## Global Constraints

- **Execute AFTER the schema-introspection plan merges** (it bumps MCP counts this plan's drift-test edits would otherwise collide with).
- Branch: `feat/pipeline-node-type` via superpowers:using-git-worktrees; merge to main only after Task 6.
- Drift-guarded docs — amendments land in the SAME commit as their code: `docs/pipeline-contract.md` §12/§13 (parsed by `PipelineErrorCodesSpecDriftTest`), `docs/enums.md` §2 (add a NEW drift test in Task 4 — none exists today), `docs/mcp-server.md` §6.2 (deep-equality on inputSchemas). `docs/metadata-db.md` and `docs/dag-executor.md` are audit-checked only, but still travel with their change.
- Gradle: `./gradlew :modules:<name>:test > /tmp/build-out.txt 2>&1` — never pipe; check exit code, then read. ONE build actor. `allWarningsAsErrors = true`: an exhaustive `when` missing a new enum entry is a BUILD ERROR — that is the designed guard, not an obstacle; extend the `when`, never add `else`.
- Every new error code: constant in `PipelineErrorCodes` + doc row in the SAME commit. Codes are additive, `{domain}.{entity}.{failure}`, lowercase.
- No AI attribution in commits. `./scripts/docs-audit.sh` exits 0 after every docs-touching commit.

---

### Task 1: Lineage columns (migration + repository)

**Files:**
- Create: `modules/app/src/main/resources/db/migration/V2__execution_lineage.sql`
- Modify: `modules/dag/src/main/kotlin/co/datapipelines/executor/ExecutionRepository.kt` (`ExecutionRecord` + SQL), `docs/metadata-db.md` §4.6, `docs/enums.md` (the `triggered_via` catalog section — grep `triggered_via` in enums.md), design doc correction note
- Test: `tests/integration-tests/src/test/kotlin/co/datapipelines/integration/FlywayMigrationIntegrationTest.kt` (extend), `modules/dag` repository tests (locate the existing `ExecutionRepository` test class and extend it)

**Interfaces:**
- Produces: `ExecutionRecord` gains `val parentExecutionId: UUID? = null`, `val parentNodeId: String? = null`, `val rootExecutionId: UUID? = null` (null on the record ⇒ repository persists `execution_id` itself — see migration comment); `ExecutionTrigger` gains `PIPELINE`; `ExecutionRepository.findByRoot(rootExecutionId: UUID): List<ExecutionRecord>`.

- [ ] **Step 1: Migration** (mirror V1's header comment style):

```sql
-- V2__execution_lineage.sql
--
-- Composition lineage (design doc 2026-08-13-pipeline-node-type, §5): a PIPELINE node
-- spawns a real child execution; these columns link the family.
--   root_execution_id: top ancestor; equals execution_id for roots. Backfilled = own id,
--   NOT NULL from here on — family queries and cancellation never special-case NULL.
--   parent_execution_id / parent_node_id: NULL for roots.
-- Also extends chk_triggered_via: child executions record 'PIPELINE'.

ALTER TABLE pipeline_executions ADD COLUMN parent_execution_id UUID REFERENCES pipeline_executions(execution_id);
ALTER TABLE pipeline_executions ADD COLUMN parent_node_id TEXT;
ALTER TABLE pipeline_executions ADD COLUMN root_execution_id UUID;

UPDATE pipeline_executions SET root_execution_id = execution_id;
ALTER TABLE pipeline_executions ALTER COLUMN root_execution_id SET NOT NULL;

CREATE INDEX idx_executions_root ON pipeline_executions(root_execution_id);

ALTER TABLE pipeline_executions DROP CONSTRAINT chk_triggered_via;
ALTER TABLE pipeline_executions ADD CONSTRAINT chk_triggered_via
    CHECK (triggered_via IN ('UI', 'REST', 'MCP', 'PIPELINE'));
```

- [ ] **Step 2: Failing tests.** Extend `FlywayMigrationIntegrationTest` (Testcontainers Postgres — copy its existing assertion style): after migration, insert an execution row without `root_execution_id` → expect NOT NULL violation, and assert `idx_executions_root` exists via `pg_indexes`. Extend the dag repository test: `create` a record with `parentExecutionId`/`parentNodeId`/`rootExecutionId` set, read it back via `findById`, assert all three; assert `findByRoot` returns parent+child when both share a root.
- [ ] **Step 3: Verify failure** (`:tests:integration-tests:test` needs Docker; if unavailable, run it at Task 6 and rely on repository tests now — say so in the commit body).
- [ ] **Step 4: Implement repository changes.** `ExecutionRecord` gains the three fields (defaults null). In `create(...)`'s INSERT, add the columns; bind `root_execution_id` as `record.rootExecutionId ?: record.executionId`. Row mapper reads them back. Add:

```kotlin
    /** The whole execution family (root + descendants), newest first. */
    fun findByRoot(rootExecutionId: UUID): List<ExecutionRecord> =
        jdbc.query(
            "SELECT * FROM pipeline_executions WHERE root_execution_id = :root ORDER BY started_at DESC",
            mapOf("root" to rootExecutionId),
            rowMapper,
        )
```
(Match the class's real SELECT column-list/rowMapper conventions — open the file; if it enumerates columns explicitly, enumerate.) Add `PIPELINE` to `ExecutionTrigger`.
- [ ] **Step 5: Docs.** `docs/metadata-db.md` §4.6: add the three column rows + index row + CHECK change, with the same backfill rationale; `docs/enums.md` `triggered_via` catalog: add `PIPELINE`; design doc: append under §4.4 Guards: *"Correction (2026-08-13, plan): children do not take concurrency slots — root executions only; a waiting parent holding a slot while children queue would deadlock. Bounds: depth ≤ max-composition-depth, 1000-node cap per pipeline."*
- [ ] **Step 6: Run** `:modules:dag:test` + docs-audit; **Commit** `feat(dag,app): execution lineage columns + PIPELINE trigger (V2 migration)`.

---

### Task 2: dag substrate — direct delivery, lineage in ExecuteRequest, family cancellation

**Files:**
- Modify: `modules/dag/src/main/kotlin/co/datapipelines/executor/ExecuteRequest.kt`, `ResultStore.kt`, `RedisResultStore.kt`, `NodeRunner.kt`, `PipelineExecutor.kt`
- Test: existing `NodeRunner`/`PipelineExecutor`/`RedisResultStore` test classes in `modules/dag/src/test` (extend; follow their fixtures)

**Interfaces:**
- Produces (consumed by Tasks 3–5):

```kotlin
/** Receives a caller-node result streamed directly to an in-process consumer (design §4.2). */
fun interface DirectResultSink {
    /** Called at most once, on the execution's caller node. Rows are consumed inside the call. */
    suspend fun accept(schema: List<ColumnSchema>, rows: Sequence<List<Any?>>)
}
```
  - `ExecuteRequest` gains: `val directSink: DirectResultSink? = null`, `val parentExecutionId: UUID? = null`, `val parentNodeId: String? = null`, `val rootExecutionId: UUID? = null`, `val compositionDepth: Int = 0`.
  - `ResultStore` gains `suspend fun materializeRows(executionId: UUID, schema: List<ColumnSchema>, rows: Sequence<List<Any?>>, ttlSeconds: Long): StoredResult` — needed by Task 5's caller-target adapter (parent PIPELINE node with `output: caller`).

- [ ] **Step 1: Failing tests.**
  - NodeRunner: a DQL caller node with `ctx.directSink != null` invokes the sink with the result schema+rows and does NOT touch `resultStore` (`verify(exactly = 0) { resultStore.materialize(any(), any(), any(), any()) }`); `NodeResult.callerResultRef` is null and `rowsOut` equals the row count.
  - RedisResultStore: `materializeRows` stores pages readable via `describe`/`page` identically to `materialize` (use the class's existing embedded-Redis/mock fixture).
  - PipelineExecutor: when `request.rootExecutionId != null`, `executionSlots` is NOT consulted (mock it; `verify(exactly = 0)`); the cancel poller/boundary check also reads the ROOT id's flag — set a flag under the root id, run a child request, assert it aborts.
- [ ] **Step 2: Verify failure.**
- [ ] **Step 3: Implement.**
  - `NodeExecutionContext` (or however NodeRunner reaches per-execution state — it already carries `executionId`/`resultTtlSeconds`) gains `directSink`. In `dispatchOutput`'s `is NodeOutput.Caller ->` branches (both tempdb `:143-147` and datasource `:387-389` paths), branch first:

```kotlin
                is NodeOutput.Caller -> {
                    val sink = ctx.directSink
                    if (sink != null) {
                        phase(NodePhase.MATERIALIZE, node.id) { streamToSink(node, rs, ctx, startedAt, dialect, sink) }
                    } else {
                        phase(NodePhase.MATERIALIZE, node.id) { materialize(node, rs, ctx, startedAt, dialect) }
                    }
                }
```
  with `streamToSink` reading schema via the same path `ResultStore.materialize` uses (`ResultRowReader.schemaOf(rs.metaData, dialect)`) and iterating rows through `ResultRowReader`'s row-decode (reuse its reader; do not hand-roll JDBC value decoding), then `NodeResult.of(nodeId, rowsOut = n, startedAt, callerResultRef = null)`.
  - `RedisResultStore.materializeRows`: refactor the existing `materialize` so both entry points share the page-encoding/write internals (extract a private `writePages(executionId, schema, rowIterator, ttl)`); `materialize` drains the ResultSet into that, `materializeRows` feeds the sequence.
  - `PipelineExecutor.execute`: `if (request.rootExecutionId != null) runExecution(executionId, request) else executionSlots.withSlot(request.userId) { runExecution(executionId, request) }`. `checkCancelFlag`: also read `request.rootExecutionId`'s flag (both ids — a child can be cancelled individually or via the family).
- [ ] **Step 4: Run** `:modules:dag:test` — green. **Step 5: Docs:** `docs/dag-executor.md`: document `direct` delivery + root-slot rule + family cancellation (one subsection). **Step 6: Commit** `feat(dag): direct result delivery, lineage-aware execution, family cancellation`.

---

### Task 3: Config key for composition depth

**Files:**
- Modify: the executor/app config class holding execution settings (grep `cancelPollIntervalSeconds` — add the new property beside it), `docs/configuration.md` (the authority table — this doc is parsed by `WebPropertiesSpecDriftTest`/`TemplatesPropertiesSpecDriftTest`-style guards; run `:modules:web:test` after and fix expectations if a config-key drift test covers the section you touch)
- Test: wherever the config class's defaults are asserted (grep its test)

- [ ] **Step 1:** Add `datapipelines.pipelines.max-composition-depth` (Int, default 5, validated ≥ 1 wherever sibling keys validate) + doc row in configuration.md's matching section (copy an adjacent row's format exactly) + default-value test. Same commit: `feat(config): max-composition-depth key`.

---

### Task 4: Contract — enum, node shape, validation, error codes (+ dag branch stub in the same commit)

This is the drift-heavy commit. Everything below lands in ONE commit.

**Files:**
- Modify: `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/NodeType.kt`, `Node`/model file (grep `data class Node(`), `PipelineDeserializer.kt`, `PipelineSerializer.kt`, `NodeTypeRules.kt`, `PipelineValidator.kt`, `PipelineErrorCodes.kt`
- Create: `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/CompositionRules.kt`, `.../PipelineResolver.kt`, `modules/pipeline-contract/src/test/kotlin/co/datapipelines/pipeline/NodeTypeSpecDriftTest.kt` (NEW guard), `CompositionRulesTest.kt`
- Modify: `modules/dag/.../NodeRunner.kt` (+`SubPipelineRunner` port file in dag), `Dag`/`ExecutablePipeline` admission (grep `ExecutablePipeline.from`)
- Docs (same commit): `docs/pipeline-contract.md` §4.6/§4.7 (PIPELINE rows), new `### 4.9 JSON structure (PIPELINE node)` example, `### 8.5 PIPELINE nodes` execution behavior, `### 12.9 Composition validations` table, §13.4 two new rows, §15 stability note; `docs/enums.md` §2 (PIPELINE row)

**Interfaces:**
- Produces:

```kotlin
/** pipeline-contract: resolves pinned pipeline references at save time (design §3, D5). */
fun interface PipelineResolver {
    /** The pinned version's parsed body, or null when name/version is unknown or soft-deleted-for-new-references. */
    fun resolve(name: String, version: Int): ResolvedPipeline?
}
data class ResolvedPipeline(val pipeline: Pipeline, val deleted: Boolean)

/** dag: executes a child pipeline for a PIPELINE node (design §4.1). Implemented in app/web (Task 5). */
fun interface SubPipelineRunner {
    suspend fun run(node: ExecutableNode, ctx: NodeExecutionContext): NodeResult
}
```
  - `Node` gains `val pipeline: PipelineNodeRef?` (`data class PipelineNodeRef(val name: String, val version: Int)`) and `val parameters: Map<String, JsonNode>?` (PIPELINE nodes only).
  - New `PipelineErrorCodes.Validation` constants (strings exactly as design §6): `PIPELINE_NOT_FOUND`, `PIPELINE_VERSION_NOT_FOUND`, `PIPELINE_SELF_REFERENCE`, `PIPELINE_REFERENCE_DELETED`, `PIPELINE_NODE_HAS_SOURCE`, `PIPELINE_NODE_HAS_TEMPLATE`, `PIPELINE_PARAMETER_UNMAPPED`, `PIPELINE_PARAMETER_UNKNOWN`, `PIPELINE_PARAMETER_TYPE_MISMATCH`, `PIPELINE_OUTPUT_ON_SIDEEFFECT_CHILD`, `COMPOSITION_TOO_DEEP`; new `PipelineErrorCodes.Node` constants: `CHILD_EXECUTION_FAILED` (`pipeline.node.child_execution_failed`), `COMPOSITION_DEPTH_EXCEEDED` (`pipeline.node.composition_depth_exceeded`).

- [ ] **Step 1: Failing tests.** `CompositionRulesTest` — one test per §12.9 row (mock `PipelineResolver`; a helper builds a parent with one PIPELINE node and a resolvable child with declared parameters; each test perturbs one thing and asserts exactly its code in the collector, mirroring `NodeTypeRulesTest`'s style). `NodeTypeSpecDriftTest` — copy `DatasourceAuditEventsSpecDriftTest`'s shape with `SPEC_PATH = "docs/enums.md"`, `SECTION_START = "## 2."`, `SECTION_END = "## 3."`, backticked-value regex for BARE uppercase values `Regex("^\\|\\s*\`([A-Z]+)\`\\s*\\|", MULTILINE)`, asserting parsed == `NodeType.entries.map { it.wire }` (both directions + non-empty-parse guard). Serializer/deserializer round-trip test for a PIPELINE node. `pipeline.validation.type_invalid` message test updated (WIRE_VALUES now has 4).
- [ ] **Step 2: Verify failure** (`:modules:pipeline-contract:compileTestKotlin`).
- [ ] **Step 3: Implement contract side.**
  - `NodeType`: add `/** Executes another pipeline as a child execution (§4.9, §8.5); carries a \`pipeline\` ref, never \`source\`/\`template\`. */ PIPELINE("PIPELINE"),`.
  - Model/deserializer: PIPELINE nodes require `pipeline`, forbid `source`/`template` (deserializer-level structural failures use the new `PIPELINE_NODE_HAS_SOURCE`/`_HAS_TEMPLATE`/`PIPELINE_NOT_FOUND`-adjacent codes ONLY where §12.9 says; keep deserializer vs validator split exactly as existing fields do — copy how `template` requiredness is enforced today and mirror it).
  - `NodeTypeRules.check`: `NodeType.PIPELINE -> Unit` (its output legality is CompositionRules' job — comment says so).
  - `CompositionRules.check(pipeline, resolver, maxDepth, into)`: per PIPELINE node — resolve ref (`PIPELINE_NOT_FOUND` / `PIPELINE_VERSION_NOT_FOUND` on null, `PIPELINE_REFERENCE_DELETED` when `deleted`), self-reference by name, parameter mapping against the child's declared `parameters` (`UNMAPPED` = required-without-default not supplied; `UNKNOWN` = supplied key not declared; `TYPE_MISMATCH` = literal fails the child param's §6.3 wire-encoding check — reuse the existing parameter-coercion helper the contract module already has for `default_type_mismatch` (grep `DEFAULT_TYPE_MISMATCH` for its checker) — and `${ref}` (regex `^\$\{([a-z_][a-z0-9_]*)\}$`) must name a parent parameter of the identical declared type), `PIPELINE_OUTPUT_ON_SIDEEFFECT_CHILD` when the child has no caller node (child caller detection: zero nodes resolving to caller-target per §9.1 — reuse the existing caller-resolution helper) yet the node declares `output`, and `COMPOSITION_TOO_DEEP` via ITERATIVE depth walk over resolver-loaded children (explicit stack, never recursion — §12.2 crash-safety).
  - `PipelineValidator`: constructor gains `private val pipelines: PipelineResolver` and `private val maxCompositionDepth: Int`; add `CompositionRules.check(pipeline, pipelines, maxCompositionDepth, collector)`. Update every `PipelineValidator(` construction site (grep across modules; test sites pass `PipelineResolver { _, _ -> null }` and depth 5 unless composition is under test).
- [ ] **Step 4: dag side (same commit — the enum breaks its exhaustive `when`s).**
  - `NodeRunner.run`: BEFORE render/source dispatch: `if (node.type == NodeType.PIPELINE) return subPipelineRunner?.run(node, ctx) ?: throw DatapipelinesException(PipelineErrorCodes.Node.CHILD_EXECUTION_FAILED, "Pipeline composition is not wired in this runtime.", details = mapOf("node" to node.id))`. Constructor gains `private val subPipelineRunner: SubPipelineRunner? = null` (last, defaulted — existing call sites compile unchanged).
  - The two `when (node.type)` switches (`NodeRunner.kt:124-129`, `:333-340`): add `NodeType.PIPELINE -> error("unreachable: PIPELINE dispatched before source resolution")` — the compiler demands the branch; the guard documents why it cannot fire.
  - Admission (`ExecutablePipeline`/`Dag.build`): PIPELINE nodes admit without template/source resolution (grep where nodes resolve templates at admission and skip that path for PIPELINE).
- [ ] **Step 5: Docs (same commit).** All sections listed under Files, formats copied from the neighboring sections; §12.9 table rows exactly the design §6 table; §13.4 two rows with HTTP 500 and the design's descriptions.
- [ ] **Step 6: Run** `:modules:pipeline-contract:test` and `:modules:dag:test`; then — because §13/catalog changed — the FULL build `./gradlew build > /tmp/full-build.txt 2>&1` (standing rule). Fix every count/list the drift tests surface. docs-audit green.
- [ ] **Step 7: Commit** `feat(contract,dag): PIPELINE node type — model, composition validation, error codes, docs (§4.9/§8.5/§12.9/§13.4, enums §2)`.

---

### Task 5: Wiring — SubPipelineRunner, sink adapters, validator resolver, surfaces

**Files:**
- Create: `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/SubPipelineExecutionRunner.kt` (mirror `McpRecordingExecutionRunner`'s placement/shape — web implements dag's port, wired in app)
- Modify: the `@Configuration`/factory in `modules/app` (grep `pipelineExecutor(`/`NodeRunner(` construction to find the assembly point), `PipelineValidator` wiring sites (`PipelineResolver` impl over `PipelineRepository.findByName` + `findVersionBody` + `PipelineDeserializer.readOrThrow`, `deleted` from the record's soft-delete flag), UI execution-history template (spawned-by marker), editor node rendering (PIPELINE node kind + link), `docs/mcp-server.md` §6.2 `pipelines_create`/`pipelines_update` inputSchemas IF they enumerate node types (grep `"DQL"` in mcp-server.md — if the enum appears, extend doc + Kotlin string identically; if not, no change)
- Test: `SubPipelineExecutionRunnerTest` (web), wiring tests in app

**Interfaces:**
- Consumes: everything Tasks 2–4 produced.
- Produces: the runtime behavior — a PIPELINE node executes its child via `PipelineExecutor.execute` with: pinned child body loaded by name+version (repository sequence identical to `PipelineExecuteTool.kt:107-111`), `parameters` = node literals + resolved `${parent_param}` values from the parent's runtime parameters, `directSink` = an adapter for the parent node's output target, lineage fields from the parent context (`rootExecutionId` = parent's root, `parentNodeId` = node id, `triggeredVia = ExecutionTrigger.PIPELINE`, `compositionDepth = parent + 1` — refuse with `COMPOSITION_DEPTH_EXCEEDED` beyond the config), a recording `EventEmitter` (child rows appear in execution history — D6; reuse whatever emitter wiring `McpRecordingExecutionRunner` uses so `WebEventEmitter.createExecutionRow` fires with the lineage fields threaded through its context).

- [ ] **Step 1: Failing tests** — runner test with mocked repository/executor: child request carries lineage+depth+PIPELINE trigger; depth-exceeded refuses with the catalogued code; child failure surfaces `CHILD_EXECUTION_FAILED` wrapping the child's code + execution id in details; zero-caller child (no sink invocation) completes as side-effect-only.
- [ ] **Step 2: Verify failure. Step 3: Implement.** Sink adapters per parent `output`:
  - `tempdb` target: write rows into the parent's staging table — reuse the exact write path NodeRunner's `NodeOutput.Tempdb` branch uses (open `dispatchOutput`, find the staging write call, and route the sink's schema+rows through the same staging writer against the PARENT's tempdb handle from `NodeExecutionContext`).
  - `caller` target: `resultStore.materializeRows(parentExecutionId, schema, rows, ctx.resultTtlSeconds)` → NodeResult with `callerResultRef`.
  - `datasource` target: reuse the writeback runner the `NodeOutput.Datasource` branch uses, fed from the sink rows.
  - Node stats: the PIPELINE node's `NodeResult` detail carries `child_execution_id` (extend `NodeResult`/stats JSON where node extras already serialize — grep `nodeStatsJson` construction).
- [ ] **Step 4: Surfaces.** The parent's SSE node events for a PIPELINE node include `child_execution_id` (extend the node-event payload where node ids/stats already serialize — same place as the stats extension in Step 3). Execution-history row template: when `parent_execution_id != null` render a "spawned by ⟨parent⟩" link; execution-detail: family list via `findByRoot`. Editor: PIPELINE nodes get a distinct Cytoscape node class + link (NO subgraph rendering — design §7). MCP: apply the §6.2 grep result from Files.
- [ ] **Step 5: Run** `:modules:web:test`, `:modules:app:test`, docs-audit. **Step 6: Commit** `feat(web,app): PIPELINE node runtime wiring — child invocation, sink adapters, lineage surfaces`.

---

### Task 6: E2E, full build, merge

- [ ] **Step 1: E2E** in `tests/integration-tests` (copy `TracerBulletE2e`'s harness): pipeline A (child) = one DQL caller node over H2 datasource; pipeline B (parent) = PIPELINE node → `output: {target: caller}`. Execute B; assert: B's result rows equal A's data; TWO execution rows exist; child row has `parent_execution_id` = B's id, `root_execution_id` = B's id, `triggered_via = 'PIPELINE'`; B's node stats carry the child execution id. Second scenario: grandchild depth-3 chain succeeds; a chain built to depth 6 fails validation at save with `composition_too_deep`.
- [ ] **Step 2:** `./gradlew build > /tmp/full-build.txt 2>&1` (exit code; a crashed daemon is neither green nor red — re-run) + `./scripts/docs-audit.sh`.
- [ ] **Step 3:** ROADMAP: note composition shipped (and remove any superseded future-work bullet mentioning cross-pipeline calls). Commit `docs: pipeline composition shipped — bookkeeping`.
- [ ] **Step 4: Merge** per superpowers:finishing-a-development-branch from the MAIN checkout; verify with `git merge-base --is-ancestor <branch-sha> main`; full build on main.

**Explicitly NOT in this plan (design §9):** editor inline sub-graph rendering, child-result caching/memoization, `direct` delivery for external callers, history "hide children" filter, the learned semantic layer.
