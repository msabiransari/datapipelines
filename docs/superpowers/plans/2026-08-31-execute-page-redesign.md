# Execute Page (Pipeline Editor) Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the pipeline editor's execute surface worth looking at: the node details panel shows the node's actual **rendered SQL** (today it shows type / child pipeline / source / template id+version / description / depends-on / output / status — `pipelines/editor.html:105-167`), the result grid renders on the shared `.ds-table` instead of the bespoke `.pe-result-table` (`pipeline-editor.css:357-397`), and completion/failure reporting follows the house toast contract.

**Architecture — the SQL question, settled.** SQL does not live in pipeline nodes. `pipeline-contract.md` §2 principle 3 (line 30) is explicit: *"SQL/FTL lives in template entities, not inline in the Pipeline. Pipelines reference templates by `{id, version}`."* So "show the SQL for a node" means: resolve the node's pinned `template: {id, version}` and render it against the pipeline's effective parameter context. Everything needed is in reach — the full pipeline body JSON is embedded in the page (`PipelineEditorController.kt:34-37` → `PipelineResponses.full`), and the render API is `WorkspaceTemplateEngines.engineFor(workspaceId).render(TemplateRef(id, version), context)` (`TemplateEngine.kt:142-155`, throws `TemplateRenderException`).

The context must be assembled **server-side**: `ParameterBinder(pipeline.parameters).bind(inputs)` is the authority (`ParameterBinder.kt:28-52`), the same binder the execute path uses. But note the asymmetry that shapes the wire format: **`ParameterCoercion` is strict** — `pipeline-contract.md` §6.3 rejects a JSON string where `INTEGER`/`DECIMAL`/`BOOLEAN` is declared, and rejects a JSON number where `BIGINTEGER`/`BIGDECIMAL` is declared. The editor's parameter inputs are `<input type="text">` (`editor.html:79-81`), so raw form strings cannot be handed to the binder. The client already solves this for execute: `window.coerceValue(val, type)` (`execute.js:32-62`, guarded by `coerce-value.test.mjs`). **The SQL preview reuses that same function** and sends §6.3 wire JSON — one coercion path for both surfaces, which is exactly the divergence 027b B existed to fix.

Therefore: a node-scoped server partial `GET /partials/pipelines/{id}/nodes/{nodeId}/sql?parameters=<url-encoded JSON>`, loaded by the details panel via `htmx.ajax` on selection change (precedent: `settings/api-keys.html:90`, `admin/users.html:54`).

**Tech Stack:** Thymeleaf partial + Alpine.js glue (both existing), zero-dependency SQL highlighting (a single-pass tokenizer, ~60 lines, node-tested). Tests: controller + render tests in Kotlin, `node --test` via `editorJsTest` for the tokenizer. NO new dependencies — no highlight.js, no CodeMirror.

**Spec homes** (the previous revision named only §4.2, which is the page-structure section — these are the authoritative homes for the three surfaces this plan changes):
- `docs/pipeline-editor.md` **§8 Node Details Panel** (§8.1 field table, §8.2 template link) — the SQL section and the panel regrouping.
- `docs/pipeline-editor.md` **§10 Result Preview** (§10.1, §10.2) — the result grid and its paging.
- `docs/pipeline-editor.md` **§9 Error Display** — what stays in the modal.
- `docs/pipeline-editor.md` §4.2 — only the server-rendered structure and the new partial's place in it.
- `docs/ui-screens.md` §4.4 + §5.1.

**Sequencing:** lands AFTER the table-component (`2026-08-31-table-component-rollout.md`) and toast (`2026-08-31-toast-application.md`) plans — this plan consumes `.ds-table` and the toast delivery shapes. **Run strictly after the graph plan (031)**, never in parallel: both touch `pipeline-editor.css` and `pipelines/editor.html`.

---

## Verified state — checked 2026-08-31; do not re-derive

### The scope annotation is not optional

`ScopeInterceptor.kt:99-102` treats `/api/v1/**`, `/partials/**` and the MCP path as **scope-governed**: an unannotated handler on those prefixes is **default-denied** (`denyUnannotated`, `:104`). Page controllers carry no `@RequiredScope` — that is the house pattern, and `PipelineEditorController` follows it — but a new `/partials/**` endpoint without one returns 403 with a `DEFAULT DENY` error in the log. The new endpoint gets `@RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)`, matching every other read partial.

**Do not try to reuse the existing render endpoint.** `POST /partials/templates/{id}/versions/{version}/render` (`TemplateEditorController.kt:44-45`) requires `MUTATE_PIPELINES_TEMPLATES` — an author scope. A read-only viewer opening the editor would be refused. That endpoint also takes a free-form `context` JSON string, which would bypass the pipeline's own parameter declarations. A separate, read-scoped, pipeline-aware endpoint is the right call.

### The domain model, exactly

- Parse the body with `PipelineJson.objectMapper().readValue(bodyJson, Pipeline::class.java)`; `Pipeline.node(id): Node?` (`Pipeline.kt:51`) resolves the node.
- **`Node.template` is NOT nullable.** `Node.fromJson` (`Node.kt:86`) binds `template = template ?: TemplateRef()`, and `TemplateRef` defaults to `id = ""`, `version = 0` (`TemplateRef.kt:15-20`). The "no template" branch must test `node.type == NodeType.PIPELINE` or `node.template.id.isBlank()` — **a null check will never fire** and the code will try to render `""@0`.
- **DML and DDL nodes DO have templates.** `pipeline-contract.md` §4.6: `template` is required *except* on PIPELINE nodes. The previous revision's "no-template (side-effect node without template)" state was wrong about which nodes it covers — it is PIPELINE nodes only, and their panel should link to the child pipeline (which `editor.html:118-127` already renders) rather than show SQL.
- `Node.isCallerNode` (`Node.kt:53`) exists server-side; `output` omitted on a DQL node already binds to `NodeOutput.Caller` (`Node.kt:94`).

### Preview context — the required-parameter problem

`ParameterBinder.bind` **rejects** when a required parameter has neither an override nor a default (`ParameterBinder.kt:39`, `PARAMETER_REQUIRED`). A preview that fails whenever a pipeline declares a required parameter is not a preview. The binder already ships the answer: `sampleContext()` (`ParameterBinder.kt:78-85`) is *"defaults where present, type-appropriate sample values otherwise"* — the context the save-time dry render uses (§7.4 / §12.6).

So the partial has three context outcomes, not one: **bound** (everything supplied or defaulted — render with `ExecutionContext.asMap()`), **sampled** (binding rejected only for unsupplied-required — render with `sampleContext()` and label the panel "preview uses sample values for: x, y"), and **rejected** (a supplied override failed coercion — show which parameter and why, do not render).

### Where the markup actually lives

- Details panel: `editor.html:105-167` (inside `<template x-if="selectedNode">`).
- Result panel: `editor.html:170-213`; the result `<table class="pe-result-table">` is at `:180`, rendered by Alpine `x-for` over `resultPanel.columns` / `.rows`.
- **`result.js` contains no markup.** It owns data and paging only — `normalizePage`, `showData`, `loadPage`, `totalPagesFrom`, TTL (`result.js:27-140`). The previous revision listed it for "class/markup alignment"; there is nothing there to align. It stays untouched, and `result-paging.test.mjs` is the fence proving so.
- `.pe-result-table*` CSS: `pipeline-editor.css:357-397` (`-container`, table, thead, th, td, `-actions`, `-page-info`).

### The aria-live parity claim is an addition, not a preservation

`announceStatus` is called for node-level events only — `sse.js:172` (started), `:182` (completed), `:197` (failed). The terminal events do **not** announce: `pipeline_completed` (`sse.js:200-204`) only calls `setBanner`, and `execution_aborted` (`:219-231`) only calls `setBanner`. So "announceStatus keeps parity for every toast" describes work this plan ADDS at the terminal events. Say so, and add it.

### The copy-button toast conflicts with §5.1

`ui-screens.md` §5.1 Notifications: *"Markup is never built client-side — the JS schedules removal only."* `toast.js` arms server-rendered `.ds-toast` nodes; it has no builder. A "Copied" confirmation is a purely client-side event with no server round-trip, so a toast for it would require the client to build toast markup — which the rule forbids, and a round-trip purely to render a confirmation is absurd.

**Resolution: the copy confirmation is not a toast.** Use the live region (`editor.announceStatus("SQL copied to clipboard")`, which already exists and is the a11y-correct channel) plus a transient label swap on the button itself ("Copy" → "Copied" for ~1.5s). Task 2 does that. If the operator wants a real toast there, that is a §5.1 amendment to make deliberately, not a side effect of this plan.

### Spec vs. code — §8 is already ahead of the implementation

| §8.1 requires | `editor.html` today |
|---|---|
| Template as *"clickable link to template editor"* | plain text (`:132`) — only the child-pipeline link exists (`:120-125`) |
| Output as *"returns result to caller (default)"* for an omitted block | `JSON.stringify(selectedNode.output)` (`:158`) — renders `undefined` when omitted |
| Depends-on entries clickable | plain `<li>` (`:150`) |
| Last execution stats via `/api/v1/executions?pipeline_id={id}&limit=1` | not rendered |
| Error (code/message/details/doc_url) on failure | not rendered — failures go to the modal only |

Also, **§8.2 names a route that does not exist**: `/templates/{id}/versions/{version}/editor`. The real route is `/templates/{id}/editor` (`TemplateEditorController.kt:30`). Task 4 fixes §8.2 and implements the link against the real route. The stats and error rows stay unimplemented — Task 5 records them as still-open spec items rather than deleting them.

### No demo pipeline exists

`./app.sh --start --demo` seeds **datasources only** (`deploy/sample-data/bootstrap-datasources.yml`: `sample-trips` POSTGRES, `sample-weather` MYSQL, `sample-reference` SQLITE). There are no seeded pipelines or templates. `big_result`, named as the evidence fixture in the previous revision, does not exist anywhere in the repo. **Use Task 0 of `2026-08-31-pipeline-graph-design.md`** — it creates a four-node `graph_fixture` pipeline with one parameter that has a default, which is exactly what this plan's evidence needs. Do not invent a second fixture.

---

## Global Constraints

- Branch: `feat/execute-page` via worktree (`superpowers:using-git-worktrees`); merge after Task 7.
- **The editor is a VIEWER/EXECUTOR.** `pipeline-editor.md` §11.1 makes it read-only in v1; no editing capability arrives with this redesign — not inline SQL editing, not "run this SQL", not parameter persistence.
- Dynamic htmx attributes use `th:attr` with quoted literals; `TemplateHtmxRenderAuditTest` stays green.
- **027b's execute-path contracts are load-bearing and frozen**: SSE frame chunking, BIGDECIMAL-as-string coercion, `limit`-authoritative paging with `total_rows` as the denominator, and the resolved cancel path. `sse-parser.test.mjs`, `coerce-value.test.mjs`, `result-paging.test.mjs` and `TemplateHtmxRenderAuditTest` must stay green **unchanged** — if a step needs one of them edited, stop and re-read the step.
- Design tokens only; no hardcoded hex in the highlight classes.
- Full gate before merge: `./gradlew build ktlintCheck detekt editorJsTest --rerun-tasks` (`editorJsTest` named explicitly — it SKIPS rather than fails without node; confirm the log shows it ran). Browser evidence is mandatory for this screen: it is the operator's daily surface.

---

### Task 1: The node-SQL partial (server)

**Files:**
- Create: `modules/web/src/main/kotlin/co/datapipelines/web/ui/PipelineNodeSqlPartialController.kt`
- Create: `modules/web/src/main/resources/templates/partials/pipeline-node-sql.html`
- Test: `modules/web/src/test/kotlin/co/datapipelines/web/ui/PipelineNodeSqlPartialControllerTest.kt`, `.../PipelineNodeSqlRenderTest.kt`

**Interface produced — Task 3 consumes it:**

`GET /partials/pipelines/{id}/nodes/{nodeId}/sql`, `@RequiredScope(READ_RESOURCES)`, one optional request parameter `parameters` carrying a **URL-encoded JSON object in §6.3 wire form**. GET, not POST: it is a read, so it needs no CSRF token, and it matches the `/partials/**` GET idiom. A query string carries only strings, which is why the values are a JSON document rather than repeated params — the same shape the existing render endpoint uses for its `context` param (`TemplateEditorController.kt:51`).

Model attributes, one per state:

| `state` | Additional attributes | When |
|---|---|---|
| `rendered` | `sql`, `dialect`, `templateId`, `templateVersion`, `sampledParameters` (list, empty when fully bound) | template resolved and rendered |
| `child-pipeline` | `childName`, `childVersion` | `node.type == PIPELINE` — no template by contract §4.6 |
| `template-missing` | `templateId`, `templateVersion` | the pinned `{id, version}` is not in the workspace registry |
| `parameter-rejected` | `failures` (list of `{parameter, message}`) | a supplied override failed §6.3 coercion |
| `render-failed` | `message` | `TemplateRenderException` |
| `node-missing` | `nodeId` | the pipeline has no such node |

An invisible pipeline (not in the caller's workspace) is a 404, matching `PipelineEditorController.kt:29-30`'s `NoSuchElementException`.

- [ ] **Step 1: Write the failing controller tests**, one per state above plus these three:

```kotlin
    @Test
    fun `the pinned version is rendered, never the latest`() {
        // node pins orders.sql@1 while the registry also holds @2
        controller.nodeSql(pipelineId, "trips_by_day", parameters = null, model = model)

        verify { engine.render(TemplateRef("trips_by_day.sql", 1), any(), any()) }
        verify(exactly = 0) { engine.render(TemplateRef("trips_by_day.sql", 2), any(), any()) }
    }

    @Test
    fun `a wire-form override reaches the binder and a malformed one is reported, not rendered`() {
        // §6.3: INTEGER is a NUMBER on the wire; a string is rejected, never converted.
        controller.nodeSql(pipelineId, "top_days", """{"limit":"5"}""", model)

        model.getAttribute("state") shouldBe "parameter-rejected"
        (model.getAttribute("failures") as List<*>).toString() shouldContain "limit"
        verify(exactly = 0) { engine.render(any(), any(), any()) }
    }

    @Test
    fun `an unsupplied REQUIRED parameter falls back to sample values and says so`() {
        // ParameterBinder.bind rejects; sampleContext() is the documented dry-render context.
        controller.nodeSql(pipelineId, "trips_by_day", parameters = null, model = model)

        model.getAttribute("state") shouldBe "rendered"
        model.getAttribute("sampledParameters") shouldBe listOf("start_date")
    }

    @Test
    fun `a PIPELINE node has no template by contract and renders the child-pipeline state`() {
        // Node.fromJson gives a PIPELINE node TemplateRef("", 0) — NOT null.
        controller.nodeSql(pipelineId, "run_child", parameters = null, model = model)

        model.getAttribute("state") shouldBe "child-pipeline"
        verify(exactly = 0) { engine.render(any(), any(), any()) }
    }
```

- [ ] **Step 2: Run and verify RED.**

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.PipelineNodeSqlPartialControllerTest' -x verifyTestsExecuted`

- [ ] **Step 3: Implement the controller.** Shape it on `PipelineEditorController` (workspace resolution, body lookup) plus `DatasourcePartialController` (partial + `@RequiredScope`):

```kotlin
@Controller
class PipelineNodeSqlPartialController(
    private val pipelines: PipelineRepository,
    private val templateEngines: WorkspaceTemplateEngines,
    private val templates: TemplateRepository,
    private val mapper: ObjectMapper = PipelineJson.objectMapper(),
) {
    /**
     * The rendered SQL for ONE node (pipeline-editor.md §8). SQL is not stored in the
     * pipeline — contract §2.3 puts it in template entities — so this resolves the node's
     * PINNED {id, version} and renders it against the pipeline's own parameter context.
     *
     * Read-scoped and pipeline-aware on purpose: /partials/templates/.../render requires
     * MUTATE_PIPELINES_TEMPLATES and takes a free-form context, so it would both refuse a
     * viewer and bypass the pipeline's parameter declarations.
     *
     * `parameters` is §6.3 WIRE JSON, produced by the page's own coerceValue — the same
     * function the execute path uses. ParameterCoercion is strict (a string for INTEGER is
     * a rejection, not a conversion), so raw form strings would fail here by design.
     */
    @GetMapping("/partials/pipelines/{id}/nodes/{nodeId}/sql")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun nodeSql(
        @PathVariable id: UUID,
        @PathVariable nodeId: String,
        @RequestParam(required = false) parameters: String?,
        model: Model,
    ): String { /* … */ }
}
```

Context assembly, in order: parse `parameters` into `Map<String, JsonNode>` (a malformed document is `parameter-rejected`, not a 500); `ParameterBinder(pipeline.parameters).bind(inputs)`; on `Bound`, render with `context.asMap()` and an empty `sampledParameters`; on `Rejected`, split the failures — if **every** failure is `PARAMETER_REQUIRED`, fall back to `sampleContext()` and list those names in `sampledParameters`; if any failure is `INVALID_PARAMETER_TYPE`, the state is `parameter-rejected`. Never render on a coercion failure: showing SQL built from a value the executor would refuse is worse than showing nothing.

- [ ] **Step 4: Implement the partial.** `partials/pipeline-node-sql.html`, one `th:switch` over `state`. The `rendered` branch emits `<pre class="pe-sql"><code th:text="${sql}"></code></pre>` — `th:text`, so escaping is Thymeleaf's, not hand-rolled — plus a dialect `.ds-badge`, the template link (`@{/templates/{id}/editor(id=${templateId})}` — the route that exists), a copy button, and a `.ds-empty`-shaped note when `sampledParameters` is non-empty. Every other branch renders a `.ds-empty` block with its own sentence.

- [ ] **Step 5: Write the render test** asserting each state's distinctive markup and that a template body containing `<script>` comes out escaped (`&lt;script&gt;`).

- [ ] **Step 6: Run and verify GREEN**, plus `TemplateHtmxRenderAuditTest`.

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.PipelineNodeSql*' --tests 'co.datapipelines.web.ui.TemplateHtmxRenderAuditTest' -x verifyTestsExecuted`

- [ ] **Step 7: Commit.**

```bash
git add modules/web/src/main/kotlin/co/datapipelines/web/ui/PipelineNodeSqlPartialController.kt \
        modules/web/src/main/resources/templates/partials/pipeline-node-sql.html \
        modules/web/src/test/kotlin/co/datapipelines/web/ui/PipelineNodeSql*.kt
git commit -m "feat(web): node-scoped rendered-SQL partial (032)"
```

---

### Task 2: Zero-dependency SQL highlighting and copy

**Files:**
- Create: `modules/web/src/main/resources/static/js/pipeline-editor/sql-highlight.js`
- Modify: `pipelines/editor.html` (one script tag), `pipeline-editor.css` (token-coloured highlight classes)
- Test: `modules/web/src/test/js/sql-highlight.test.mjs` (new — the `editorJsTest` fileTree picks it up automatically)

**Interface produced:** `window.DpSqlHighlight = { highlight(sql) -> htmlString, tokenize(sql) -> [{kind, text}] }`, plus `module.exports` for `node --test` (the `toast.js:69` export shape).

**Escaping order is the security-critical decision:** tokenize the RAW SQL, then escape each token's text as it is emitted into a `<span>`. Never escape first and tokenize the escaped string — `&lt;` would tokenize as three tokens and `&` inside a string literal would corrupt the output. `highlight()` returns HTML, so it is assigned with `innerHTML`; every code path that puts text in that string escapes `&`, `<`, `>` first.

- [ ] **Step 1: Write the failing tests.**

```js
test("keywords, strings, comments, numbers and parameters are classified", () => {
  const { tokenize } = loadHighlight();
  const kinds = (sql) => tokenize(sql).map((t) => t.kind);

  assert.ok(kinds("SELECT * FROM t").includes("keyword"));
  assert.ok(kinds("WHERE a = 'it''s'").includes("string"));      // doubled quote escape
  assert.ok(kinds("-- a comment\nSELECT 1").includes("comment"));
  assert.ok(kinds("/* block */ SELECT 1").includes("comment"));
  assert.ok(kinds("LIMIT 100").includes("number"));
  assert.ok(kinds("WHERE d >= ${start_date}").includes("parameter"));
  assert.ok(kinds("WHERE d >= :start_date").includes("parameter"));
});

test("output is escaped — markup in the SQL never survives as markup", () => {
  const html = loadHighlight().highlight("SELECT '<script>alert(1)</script>' AS x");
  assert.ok(!html.includes("<script"));
  assert.ok(html.includes("&lt;script&gt;"));
});

test("an unterminated string terminates the token stream instead of hanging", () => {
  const sql = "SELECT '" + "x".repeat(10000);
  const started = Date.now();
  const tokens = loadHighlight().tokenize(sql);
  assert.ok(Date.now() - started < 500, "the tokenizer must be single-pass, not backtracking");
  assert.equal(tokens[tokens.length - 1].kind, "string");   // consumed to EOF, no throw
});

test("every input is reconstructible from its tokens — nothing is dropped", () => {
  const sql = "SELECT a, /* c */ b\nFROM t -- tail\nWHERE x = 'y' AND n = 12";
  const tokens = loadHighlight().tokenize(sql);
  assert.equal(tokens.map((t) => t.text).join(""), sql);
});
```

The last test is the one that catches a tokenizer silently eating whitespace or an unrecognized character — a class of bug that looks fine until a real query loses a newline.

- [ ] **Step 2: Run and verify RED.** `./gradlew :modules:web:editorJsTest`

- [ ] **Step 3: Implement the tokenizer.** Single pass over the string with an index — no regex with alternation over the whole input, no backtracking. Kinds: `keyword`, `string`, `comment`, `number`, `parameter`, `punct`, `plain`. The keyword set is deliberately minimal and written down in a comment as such: `SELECT FROM WHERE GROUP BY ORDER HAVING LIMIT OFFSET JOIN LEFT RIGHT INNER OUTER ON AS AND OR NOT NULL IS IN INSERT UPDATE DELETE SET VALUES CREATE TABLE DROP ALTER WITH UNION ALL DISTINCT CASE WHEN THEN ELSE END COUNT SUM AVG MIN MAX` — dialect-agnostic and matched case-insensitively on word boundaries. Anything not matched is `plain`; an unknown identifier must never be lost.

- [ ] **Step 4: Style the tokens** in `pipeline-editor.css` with `--pe-sql-*` custom properties that resolve to design-system accents (none exist yet — add them alongside the existing `.pe-*` rules), e.g. `--pe-sql-keyword: var(--accent-primary, #2563eb)`. No hex outside the fallback slot. Check contrast in BOTH themes during Task 6.

- [ ] **Step 5: Wire it in.** Add the script tag to `editor.html` beside the other editor modules, and call `highlight()` on the `<code>` element after the partial swaps in (`htmx:afterSwap` on the SQL container, or directly in the Alpine loader's `.then`).

- [ ] **Step 6: Copy button — not a toast.** See the §5.1 conflict above. `navigator.clipboard.writeText(sql)` with a `document.execCommand("copy")` fallback for non-secure contexts, then `editor.announceStatus("SQL copied to clipboard")` and a 1.5s label swap on the button. Read the SQL from a `data-sql` attribute or the `<code>` element's `textContent` — **never from the highlighted innerHTML**, which carries `<span>` markup.

- [ ] **Step 7: Run and verify GREEN**, then **commit.**

```bash
git commit -am "feat(web): dependency-free SQL highlighting and copy (032)"
```

---

### Task 3: Panel wiring — load the SQL on selection

**Files:**
- Modify: `pipelines/editor.html:105-167` (a new SQL section), `static/js/pipeline-editor/init.js` (the loader)
- Test: `modules/web/src/test/kotlin/co/datapipelines/web/ui/PipelineEditorRenderTest.kt` (new or extended)

- [ ] **Step 1: Write the failing render test** — the details panel contains `id="pe-node-sql"`, a `.ds-spinner` indicator, and a "select a node" empty state, and the page still loads `sql-highlight.js`.

- [ ] **Step 2: Add the section** to `editor.html`, between the identity fields and Depends-On (§8.1's field order):

```html
              <div class="pe-field pe-field-sql">
                <label class="pe-label">SQL
                  <span id="pe-node-sql-spinner" class="htmx-indicator ds-spinner" aria-hidden="true"></span>
                </label>
                <!-- Filled by htmx.ajax on selection change; the URL carries §6.3 wire
                     parameters built by the page's own coerceValue (init.js). -->
                <div id="pe-node-sql"></div>
              </div>
```

- [ ] **Step 3: Implement the loader** in `init.js`, called from wherever `selectedNode` is set, and again when a parameter override changes:

```js
      loadNodeSql: function () {
        var self = this;
        if (!self.selectedNode) return;
        if (self.selectedNode.type === "PIPELINE") { /* the partial handles it; still load */ }
        var wire = {};
        Object.keys(self.parameterOverrides || {}).forEach(function (k) {
          var raw = self.parameterOverrides[k];
          if (raw === undefined || raw === "") return;          // unsupplied → server defaults
          var type = (self.parameters[k] && self.parameters[k].type) || "STRING";
          wire[k] = coerceValue(raw, type);                      // ONE coercion path (execute.js)
        });
        var url = "/partials/pipelines/" + encodeURIComponent(self.pipeline.id) +
          "/nodes/" + encodeURIComponent(self.selectedNode.id) + "/sql" +
          "?parameters=" + encodeURIComponent(JSON.stringify(wire));
        htmx.ajax("GET", url, { target: "#pe-node-sql", swap: "innerHTML" })
          .then(function () { window.DpSqlHighlight.apply(document.getElementById("pe-node-sql")); });
      },
```

Debounce the parameter-change trigger (~300ms) so typing in an override box does not fire a render per keystroke — the same delay the list screens use for search.

- [ ] **Step 4: Run** the render test and `TemplateHtmxRenderAuditTest`, then **commit** `feat(web): details panel loads the node's rendered SQL (032)`.

---

### Task 4: Details panel structure and the §8 gaps worth closing

**Files:** `pipelines/editor.html:105-167`, `pipeline-editor.css`

- [ ] **Step 1: Regroup** the panel into headed sections — Identity (id, description, type badge), SQL (Task 3's section), Configuration (source, template, output, depends-on), Runtime (status) — using token spacing, `.ds-badge` for the type and status chips.

- [ ] **Step 2: Close the two cheap §8.1 gaps** while the markup is open:
  - Template becomes a link to `/templates/{id}/editor` (the route that exists — §8.2 currently names one that does not).
  - Output renders "returns result to caller (default)" when `selectedNode.output` is undefined, "side effect" for DML/DDL, and target + table/mode otherwise — instead of today's `JSON.stringify` which prints `undefined` for the commonest case.

  The remaining §8.1 gaps — last-execution stats and the per-node error row — stay unimplemented; Task 5 records them as open, and they are listed under **Explicitly NOT in this plan**.

- [ ] **Step 3: Long-value handling.** `.pe-value` gets `overflow-wrap`/ellipsis with a `title` carrying the full text, so a long JDBC URL or table name cannot widen the 320px panel (`--app-detail-width`, `app.css:5`).

- [ ] **Step 4: Run** render tests and **commit** `refactor(web): details panel sections, template link, output copy (032)`.

---

### Task 5: Result grid on the shared table, and toast completion

**Files:**
- Modify: `pipelines/editor.html:170-213` (the result panel markup), `pipeline-editor.css:357-397` (delete the bespoke table styles), `static/js/pipeline-editor/sse.js` (terminal events → toast + announce)
- **Not modified:** `result.js` — it holds no markup, and `result-paging.test.mjs` is the fence proving the paging contract is untouched.

- [ ] **Step 1: Migrate the table.** `<table class="pe-result-table">` → `<table class="ds-table">`; delete `.pe-result-table`, `.pe-result-table thead/th/td` from the CSS. **Keep `.pe-result-table-container`** only if it supplies scroll (`overflow-x: auto`) — `.ds-table` in `app.css:140-146` now supplies the border, radius and background, so any border/radius left on the container draws a second frame. Add `.num` to numeric cells if the column type is known; otherwise leave alignment alone.

- [ ] **Step 2: Align the pager's look, not its logic.** The Prev/Next/page-info row keeps `resultPanel.prevPage()` / `nextPage()` and the `hasPrev` / `hasNext` bindings **exactly as they are** — this is client-side cursor paging over a stored result, not the server-rendered shared pager fragment, and 027b C's `limit`-authoritative, `total_rows`-denominated arithmetic is frozen. Restyle to match the shared pager (ghost buttons, centred count text, token spacing). The downloads row becomes ghost buttons on tokens.

- [ ] **Step 3: Terminal events become toasts.** In `sse.js`:
  - `pipeline_completed` (`:200-204`): replace `setBanner("Pipeline completed successfully", "success")` with a success toast, and **add** `editor.announceStatus("Pipeline completed successfully")` — the terminal events do not announce today (see the parity finding).
  - `execution_aborted` (`:219-231`): the same, with the event's `reason` in the body, per §6.3's "Execution aborted ({reason})".
  - `pipeline_failed` (`:206-210`): **keeps the error modal** (§9) — a failure detail is not a 6s notification. Add the `announceStatus` call it also lacks.
  - The running-progress banner stays at the toolbar for the `running` state only.

  Delivery shape: these are client-side events with no server response to hang an OOB fragment on, so the toast markup cannot come from `partials/toast-oob`. **This is the same §5.1 "never build markup client-side" conflict as the copy button, and it is bigger.** Two honest options, decide with the operator before implementing:
  - **(a)** Keep the toolbar banner for terminal events (no change beyond the `announceStatus` additions) and let toasts stay a server-response mechanism. Cheapest, and consistent with §5.1 as written.
  - **(b)** Amend §5.1 to allow a single named client-side entry point — `DpToast.show(variant, title, message)` in `toast.js`, which builds the one markup shape the server fragment produces and is covered by `toast.test.mjs`. Then the SSE handlers and the copy button both use it.

  **Recommendation: (b)**, because the SSE surface has no server response to attach an OOB swap to and the alternative is that the operator's most-used screen is the only one that never toasts — but it is a spec amendment, so it goes in the toast plan's §5.1 section and gets named in the handback, not slipped in here.

- [ ] **Step 4: Run every editor JS test and the render tests.**

Run: `./gradlew :modules:web:editorJsTest :modules:web:test --tests 'co.datapipelines.web.ui.*RenderTest' --tests 'co.datapipelines.web.ui.TemplateHtmxRenderAuditTest' -x verifyTestsExecuted`
Expected: PASS, with `result-paging.test.mjs`, `sse-parser.test.mjs` and `coerce-value.test.mjs` green and **unedited** — confirm with `git status` that none of the three appears.

- [ ] **Step 5: Commit** `feat(web): result grid on the shared table, terminal events report via toast (032)`.

---

### Task 6: Spec sweep

- [ ] **Step 1: `docs/pipeline-editor.md` §8** — add the SQL section to the §8.1 field table (source: the new partial; note the pinned version and the sampled-parameter state), fix §8.2's route to `/templates/{id}/editor`, and mark the last-execution-stats and error rows explicitly as **not implemented in v1** rather than leaving them reading as shipped.
- [ ] **Step 2: §10 Result Preview** — the grid is `.ds-table`; §10.2's paging contract is unchanged and says so.
- [ ] **Step 3: §9 Error Display** — the modal keeps failure detail; terminal success/abort report as notifications.
- [ ] **Step 4: §4.2** — the new partial's route, scope and place in the page structure.
- [ ] **Step 5: `docs/ui-screens.md`** §4.4 editor row (SQL section, shared table, notification behaviour) and, if Task 5 Step 3 option (b) was taken, the §5.1 amendment for the client-side entry point.
- [ ] **Step 6: Changelog row** — renumber jointly with the other 2026-08-31 plans if they land in the same window; never duplicate a version number.
- [ ] **Step 7: Run `./scripts/docs-audit.sh`** (exit 0) and **commit** `docs(pipeline-editor): execute-page redesign spec (032)`.

---

### Task 7: Gate, browser evidence, merge

- [ ] **Step 1: Full gate.**

Run: `./gradlew build ktlintCheck detekt editorJsTest --rerun-tasks`
Expected: BUILD SUCCESSFUL with `editorJsTest` shown as RUN. Read the log's last line, not the wrapper's exit code.

- [ ] **Step 2: Browser evidence** on the `graph_fixture` pipeline from the graph plan's Task 0 (`./app.sh --start --demo`):
  - select `trips_by_day` → the SQL section loads, rendered with the parameter's default;
  - override `start_date` → the SQL re-renders after the debounce with the new value visible in the SQL;
  - type a deliberately wrong override for a typed parameter → the `parameter-rejected` state names the parameter and no SQL is shown;
  - select the PIPELINE node (add one to the fixture if absent) → the `child-pipeline` state, not an empty SQL block;
  - copy → the button says "Copied" and the live region announces it;
  - highlight legible in BOTH themes (swap the theme without reloading);
  - execute → the result grid on `.ds-table`, and the 027b pager round trip intact — **specifically page to the short LAST page and back, confirming Prev/Next stay correct** (that is the exact 027b C regression);
  - completion and cancel report as decided in Task 5 Step 3.
  Screenshot each for the handback.

- [ ] **Step 3: Falsification, one per guard**: the escaping test (return raw HTML from `highlight` and watch it go red), the pinned-version test, the strict-coercion test, and the reconstructibility test. Record each run.

- [ ] **Step 4: Handback** at `datapipelines-orchestration/handbacks/032-execute-page.md`: evidence, falsification runs, the Task 5 Step 3 decision as taken, and the §8.1 rows left unimplemented.

- [ ] **Step 5: Merge** from the MAIN checkout after operator review. Check `git symbolic-ref HEAD` — the ref, not the SHA — and `git status` for foreign modified files before committing there. After pushing, verify `git merge-base --is-ancestor <your-sha> origin/main`, then a full build on main.

---

## Explicitly NOT in this plan

- Pipeline or graph editing of any kind (`pipeline-editor.md` §11.1 — the editor is read-only in v1), including inline SQL editing and re-running ad-hoc SQL from the panel.
- The §8.1 last-execution-stats row and the per-node error row — both need an executions lookup this plan does not build. Task 6 marks them not-implemented rather than deleting the requirement.
- FTL SOURCE display — the template screen owns that; this panel shows the RENDERED SQL only.
- Result formatting beyond the shared table: charts, pivots, column type formatting.
- Any change to the 027b execute-path contracts (SSE chunking, wire coercion, `limit`-authoritative paging, cancel resolution) or to their three JS test files.
- `hx-push-url` state for the selected node or parameter overrides.
- The graph canvas itself — `2026-08-31-pipeline-graph-design.md`.
