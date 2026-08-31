# Table Component Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every data table in the UI renders on the design system (`.ds-table` / `.ds-badge` / `.ds-empty`), every list screen's search + pager actually works and shares one pager fragment on the 028 swap contract, and every on-page search covers every rendered column.

**Architecture:** The contract is proven on exactly one screen — datasources (028, merged `09c726e`; handback `datapipelines-orchestration/handbacks/028-datasources-spa-toasts.md`; spec `docs/ui-screens.md` §4.5/§5.1). Its four rules: (1) the list partial's ROOT element carries the stable swap-target id, so `outerHTML` swaps land on a target that survives; (2) filter controls live OUTSIDE the fragment and address each other by ELEMENT ID via `hx-include`, never by input name; (3) `.ds-spinner` + `hx-indicator` on every refresh trigger; (4) action results are toasts (`hx-target="#toast" hx-swap="beforeend"`), never row swaps. This plan rolls that contract onto the two list screens where it is currently **inert**, extracts the now-thrice-repeated pager into one fragment, and replaces per-screen inline-style table markup with the design-system primitives that already ship.

**Tech Stack:** Thymeleaf 3.1 partials (standard dialect only — **no htmx dialect is registered**, so every dynamic `hx-*` must go through `th:attr`), htmx (webjar 2.0.10), vendored design-system CSS. Tests: JUnit 5 + Kotest matchers + MockK, standalone Thymeleaf render tests in the `ListPartialsRenderTest` shape. NO new dependencies. No JS changes planned.

**Spec:** `docs/ui-screens.md` — §4.3 Pipeline List, §4.5 Datasource List, §4.6 Template List, §4.8 Execution History, §4.10 API Keys, §4.12 Admin Users, §4.13 Workspaces, §5 htmx pattern, §5.1 Standard States. Updated in the same commits as their screens (Task 8 sweeps what the per-screen commits could not).

---

## Verified state — mechanical inventory (read this before Task 1; do not re-derive)

Every claim below was checked against the working tree on 2026-08-31. Line numbers are from that tree.

### Table inventory (`grep -rn '<table' modules/web/src/main/resources/templates/`)

| Template | Line | Today | Task |
|---|---|---|---|
| `partials/executions.html` | 10 | **already `.ds-table` + `.ds-badge`** | none (Task 5 touches its pager only) |
| `partials/recent-executions.html` | 14 | **already `.ds-table`** | none |
| `partials/execution-result.html` | 9 | **already `.ds-table`** | none |
| `partials/execution-node-stats.html` | 2 | **already `.ds-table`** | none |
| `executions/detail.html` | 62 | **already `.ds-table`** | none |
| `partials/datasources.html` | 20 | inline styles | Task 6 |
| `partials/pipelines.html` | 12 | inline styles | Task 6 |
| `partials/templates.html` | 12 | inline styles | Task 6 |
| `settings/api-keys.html` | 13 | inline styles | Task 7 |
| `admin/users.html` | 32 | inline styles (+ rows built as Kotlin strings) | Task 7 |
| `workspaces/index.html` | 57, 96 | inline styles, TWO tables (the second repeats per managed workspace) | Task 7 |
| `templates/editor.html` | 67 | inline styles | Task 7 |
| `pipelines/editor.html` | 184 | `.pe-result-table` | **out of scope** (execute-page plan) |

Five tables are already migrated. The previous revision of this plan claimed "the templates simply never adopted them" and listed four already-migrated files as work — that was wrong.

### Primitives that already exist

- `static/vendor/design-system/primitives.css:178-214` — `.ds-badge` + `-default/-primary/-success/-warning/-danger`.
- `static/vendor/design-system/primitives.css:218-258` — `.ds-table` (thead/th/td/hover/last-row/`.num`).
- `static/vendor/design-system/primitives.css:1401-1438` — `.ds-empty`, `.ds-empty-icon`, `.ds-empty-title`, `.ds-empty-description`, `.ds-empty-actions`.
- `static/vendor/design-system/primitives.css:1196-1216` — `.ds-spinner`.
- `static/css/app.css:132-170` — the house layer over `.ds-table`: **it already supplies the outer 1px border, `--radius-md`, the header fill and the clipped corners.** Consequence for every migration: the `<div style="border: 1px solid …; border-radius: …; overflow: hidden">` wrapper around a migrated table must be **deleted**, or the screen gets a double border. Likewise the `th:style` zebra striping (`${xStat.odd} ? 'background: var(--surface-default);'`) must be deleted — the design system uses hover, not zebra, and the table already paints `--surface-default`.

### Live defects this plan owns (each verified, none previously named in this plan)

- **D1 — the pipelines and templates pagers are dead, and always have been.** `pipelines/list.html:23-25` and `templates/list.html:24-26` write `<div id="pipeline-list-wrapper" th:replace="~{partials/pipelines :: fragment}">`. `th:replace` **removes the host element**, so the id never reaches the DOM; the partial's root is a `<th:block>`, which contributes no element either. The pagers target `#pipeline-list-wrapper` / `#template-list-wrapper` (`partials/pipelines.html:40,46`, `partials/templates.html:44,50`) — a selector that matches nothing on first paint. This is the identical bug 028 fixed for datasources (`partials/datasources.html:9` carries the id on the fragment root; `datasources/list.html:42-44` leaves only a placeholder `<div th:replace>`).
- **D2 — the pipelines and templates search boxes are inert.** `pipelines/list.html:14-20` and `templates/list.html:14-21` carry `name="q"` and a `<select>` with **no `hx-*` attribute of any kind**. Typing does nothing. The `q` value only ever arrives on a full page load.
- **D3 — the pipelines "All Datasources" select is unimplementable as written.** `pipelines/list.html:17-20` is labelled datasources but populated from `${dialects}`, and `PipelinePartialController.list` (`:21-25`) has no such `@RequestParam`. `PipelineRecord` carries no datasource field, so the filter cannot be served without joining the pipeline definition. Decision in Task 3: delete the control.
- **D4 — `.ds-empty-state` is not a class.** `grep -rl '\.ds-empty-state' modules/web/src/main/resources/static/` returns **nothing**. `partials/{datasources,pipelines,templates}.html` and `workspaces/index.html` all use it; every pixel comes from the inline styles beside it. The real primitive is `.ds-empty` / `.ds-empty-title` / `.ds-empty-description`, already used correctly by `partials/executions.html:3-7`.
- **D5 — the datasource search misses five of its six rendered columns.** `DatasourcePartialController.kt:165-176` matches `name`, `displayName`, `description`. The table renders name, dialect, workspace, URL, username (`partials/datasources.html:23-28`). This is the user-reported bug.
- **D6 — the template search misses the rendered dialect column.** `TemplateRepository.list` (`modules/templates/.../TemplateRepository.kt:118-150`) ILIKEs `t.name`, `t.display_name`, `t.description` only; the table renders a dialect badge (`partials/templates.html:31`). Fixing this is a **repository SQL change with an integration test**, not a controller predicate.
- **D7 — the executions pager's `hx-vals` is very likely unprocessed.** `partials/executions.html:50,61` write `hx-vals='{"offset": "[[${offset - pageSize}]]"}'` as a PLAIN attribute. Thymeleaf 3.1 processes `[[…]]` inlining in text nodes, not in attribute values, so the literal is expected to reach the browser and `offset: Int` binding to 400. The spec already prescribes the correct form at `docs/ui-screens.md:381` (`th:attr="hx-vals=|{&quot;offset&quot;: ${nextOffset}}|"`). **Labelled hypothesis, not a verified fact** — Task 5 Step 1 falsifies it with a render assertion before any fix.
- **D8 — the guards cannot see D1 or the bug the operator just hotfixed.** `TemplateHtmxRenderAuditTest` sweeps for plain `hx-post|put|patch|delete="${…}"` and for `th:hx-*`. It does not catch an unquoted literal inside a `th:attr` assignation sequence (which 500'd both list screens on 2026-08-31), and it cannot catch an `hx-target` whose id no rendered page produces. Task 1 closes both.

### Cosmetic findings — record, do not fix here

- `partials/templates.html:35` renders `t.createdAt` under an **"Updated"** header; `Template` has no `updatedAt` field. Rename the header to "Created" during the Task 6 migration (one-word change, same commit).
- `PipelinePartialController.kt:36` computes the unfiltered `total` as `page + items.size + (if (more) 1 else 0)` — an "at least" figure, so a 100-row workspace renders "Showing 25 of 26". `TemplatePartialController` supplies no `total` at all, which is why `partials/templates.html:47` says "Showing N templates". See **Deferred decisions**.

### Landing zone

**Clear — the hotfix landed on main in `a01c712`** (*fix(web): /pipelines and /templates 500 once a row exists — quote th:attr hx literals*), together with `ListPartialsRenderTest.kt` and an unrelated `settings/index.html` change. Branch from a main that contains it.

Verify before branching, because this plan's Task 1 builds directly on that fix:

```bash
git merge-base --is-ancestor a01c712 origin/main && echo "hotfix present"
```

If that fails, you are on a main that predates it — stop and ask the operator rather than re-fixing the quoting yourself.

---

## Global Constraints

- Branch: `feat/table-component` via worktree (`.worktrees/`, `superpowers:using-git-worktrees`); merge to main only after Task 9.
- **htmx attributes**: dynamic values go through `th:attr`, and every literal inside a `th:attr` assignation sequence is QUOTED (`hx-target='#id', hx-swap='outerHTML'`). An unquoted literal makes Thymeleaf reject the whole sequence — `Could not parse as assignation sequence` — and 500s the page the moment a row renders. `th:hx-*` is never a processor in this build. `TemplateHtmxRenderAuditTest` must stay green; it sweeps every template.
- **Design tokens only** — no hard-coded colors/fonts/spacing. New shared CSS goes in `static/css/app.css`; the vendored design-system files under `static/vendor/design-system/` are never edited (`ui-screens.md` §3).
- **Empty states** use `.ds-empty` / `.ds-empty-title` / `.ds-empty-description` (§5.1), and distinguish *nothing exists* (offer the create action) from *nothing matched the filter* (offer "Clear filters").
- `./scripts/docs-audit.sh` exits 0 after every docs-touching commit. **No AI attribution trailers** in commits.
- Focused runs: `./gradlew :modules:web:test --tests '<Class>' -x verifyTestsExecuted`. The `-x` is mandatory — the guard (`buildSrc/src/main/kotlin/CommonConventionsPlugin.kt:305-345`) is `finalizedBy` the `test` task and fails any filtered run because it counts result XML files against `*Test.kt` sources.
- Full gate before merge: `./gradlew build ktlintCheck detekt --rerun-tasks`. `--rerun-tasks` is not optional: an `UP-TO-DATE` verification task is not evidence about code you just changed.

---

### Task 1: Close the guard gap, then fix the dead swap targets

The two defects D1 and D8 are one task: write the guards, watch them go RED on today's tree (that is the proof the defects are real), then fix.

**Files:**
- Modify: `modules/web/src/test/kotlin/co/datapipelines/web/ui/TemplateHtmxRenderAuditTest.kt`
- Modify: `modules/web/src/main/resources/templates/partials/pipelines.html:1` (fragment root), `modules/web/src/main/resources/templates/partials/templates.html:1` (fragment root)
- Modify: `modules/web/src/main/resources/templates/pipelines/list.html:23-25`, `modules/web/src/main/resources/templates/templates/list.html:24-26` (host div becomes a bare placeholder)

**Interfaces:**
- Produces: the stable swap roots `#pipeline-list-wrapper` and `#template-list-wrapper` as attributes of the fragment root element itself. Tasks 2, 4 and 6 target these ids.

- [ ] **Step 1: Add the unquoted-assignation guard.** Append to `TemplateHtmxRenderAuditTest`:

```kotlin
    /**
     * An unquoted literal inside a th:attr assignation sequence (`hx-target=#id`)
     * makes Thymeleaf reject the WHOLE sequence at render time — "Could not parse
     * as assignation sequence" — but only once the block containing it renders,
     * which is why the empty-state pages looked fine while both list screens 500'd
     * on their first row (2026-08-31 hotfix). Source-level, because the render that
     * would catch it needs a non-empty model on every screen.
     */
    @Test
    fun `no th-attr sequence carries an unquoted literal value`() {
        val violations =
            templateSources().flatMap { (name, source) ->
                TH_ATTR_VALUE.findAll(source)
                    .flatMap { m -> splitAssignations(m.groupValues[1]) }
                    .filter { it.isNotBlank() && !isProcessableValue(it.substringAfter('=', "")) }
                    .map { "$name: `$it` — the literal must be quoted: `${it.substringBefore('=')}='…'`" }
            }
        violations shouldBe emptyList()
    }

    /** Non-vacuity: the guard must be ABLE to see the shape it exists to catch. */
    @Test
    fun `the unquoted-assignation guard can go red`() {
        val bad = """th:attr="hx-get=@{/partials/pipelines}, hx-target=#pipeline-list-wrapper""""
        val good = """th:attr="hx-get=@{/partials/pipelines}, hx-target='#pipeline-list-wrapper'""""
        offenders(bad).shouldNotBeEmpty()
        offenders(good) shouldBe emptyList()
    }

    private fun offenders(source: String): List<String> =
        TH_ATTR_VALUE.findAll(source)
            .flatMap { m -> splitAssignations(m.groupValues[1]) }
            .filter { it.isNotBlank() && !isProcessableValue(it.substringAfter('=', "")) }
            .toList()
```

and in the companion object:

```kotlin
        /** The raw value of every th:attr, captured for assignation-level checking. */
        val TH_ATTR_VALUE = Regex("""th:attr="([^"]*)"""")

        /** Split on commas that are not inside @{...}, ${...} or '...'. */
        fun splitAssignations(value: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var quoted = false
            for (c in value) {
                when {
                    c == '\'' -> { quoted = !quoted; current.append(c) }
                    quoted -> current.append(c)
                    c == '{' -> { depth++; current.append(c) }
                    c == '}' -> { depth--; current.append(c) }
                    c == ',' && depth == 0 -> { parts.add(current.toString().trim()); current.clear() }
                    else -> current.append(c)
                }
            }
            parts.add(current.toString().trim())
            return parts
        }

        /** A value Thymeleaf can evaluate: an expression, a quoted literal, or a boolean. */
        fun isProcessableValue(raw: String): Boolean {
            val v = raw.trim()
            return v.startsWith("'") ||
                v.startsWith("@{") || v.startsWith("$" + "{") || v.startsWith("#{") ||
                v.startsWith("|") || v == "true" || v == "false"
        }
```

- [ ] **Step 2: Add the render-level orphan-target guard.** A source-level id check is NOT sufficient — `pipelines/list.html` *does* carry `id="pipeline-list-wrapper"` in source, and `th:replace` deletes it. The guard must read the RENDERED page:

```kotlin
    /**
     * D1: `th:replace` REMOVES the host element, so an id written on the host div
     * never reaches the DOM. Both list pages targeted an id that no rendered page
     * produced, and their pagers matched nothing from first paint. Source-level
     * checks cannot see this — only the rendered output can.
     */
    @Test
    fun `every hx-target id a rendered list page references exists in that page`() {
        listOf(
            "pipelines/list" to { c: WebContext -> c.fillPipelineList() },
            "templates/list" to { c: WebContext -> c.fillTemplateList() },
            "datasources/list" to { c: WebContext -> c.fillDatasourceList() },
        ).forEach { (view, fill) ->
            val html = templateEngine().process(view, webContext().apply { fill(this) })
            val referenced = Regex("""hx-target="#([A-Za-z0-9_-]+)"""").findAll(html).map { it.groupValues[1] }.toSet()
            referenced.shouldNotBeEmpty()
            val produced = Regex("""id="([A-Za-z0-9_-]+)"""").findAll(html).map { it.groupValues[1] }.toSet()
            (referenced - produced - LAYOUT_PROVIDED_IDS) shouldBe emptySet()
        }
    }
```

`TemplateHtmxRenderAuditTest` builds its `WebContext` inline today (inside `execution detail renders …`); extract that into a `private fun webContext(): WebContext` first — identical to `ListPartialsRenderTest.kt:110-115` — so both tests can call it. Then add `LAYOUT_PROVIDED_IDS = setOf("toast")` to the companion object (the toast stack lives in `layouts/default`, outside a standalone fragment render), and three `fillXList()` helpers modelled on the existing `fillDetailModel` — each sets the layout variables (`_csrf`, `workspaceHeaderFragment`, `workspaceOptions`, `activeWorkspace`, `activeTheme`) plus that screen's model: `pipelines`/`templates`/`datasources` with **one row** (empty lists render no pager and make the test vacuous), `q=""`, `offset=0`, `hasMore=true`, `total=30`, `selectedDialect=""`, `dialects=emptyList<String>()`, `scopes=setOf("READ")`, `canRegister=false`.

- [ ] **Step 3: Run the guards and record the RED.**

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.TemplateHtmxRenderAuditTest' -x verifyTestsExecuted`
Expected: `every hx-target id a rendered list page references exists in that page` FAILS with `pipeline-list-wrapper` and `template-list-wrapper` in the diff; `datasources/list` passes. The unquoted-assignation test passes if the operator's hotfix landed (that is the regression lock, not a new find). **Paste the failure into the handback — it is the evidence D1 is real.**

- [ ] **Step 4: Move the ids onto the fragment roots.** In `partials/pipelines.html` replace line 1:

```html
<!-- The ROOT carries id="pipeline-list-wrapper": the single stable swap target for
     search, filters and the pager. The old <th:block> root contributed no element,
     and the page's host div was consumed by th:replace, so the target never existed
     in the DOM at all (ui-screens.md §4.3, the 028 §4.5 contract). -->
<div xmlns:th="http://www.thymeleaf.org" th:fragment="fragment" id="pipeline-list-wrapper">
```

and close with `</div>` instead of `</th:block>` on the last line. Do the same in `partials/templates.html` with `id="template-list-wrapper"`.

- [ ] **Step 5: Reduce the host divs to placeholders.** `pipelines/list.html:23-25` becomes:

```html
  <!-- The fragment root carries id="pipeline-list-wrapper", so the swap target
       survives every refresh — this div is only a placeholder (§4.3). -->
  <div th:replace="~{partials/pipelines :: fragment}"></div>
```

and the same for `templates/list.html` with `partials/templates`.

- [ ] **Step 6: Run the guards and the existing render tests.**

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.TemplateHtmxRenderAuditTest' --tests 'co.datapipelines.web.ui.ListPartialsRenderTest' --tests 'co.datapipelines.web.ui.DatasourcesTemplateRenderTest' -x verifyTestsExecuted`
Expected: PASS.

- [ ] **Step 7: Commit.**

```bash
git add modules/web/src/test/kotlin/co/datapipelines/web/ui/TemplateHtmxRenderAuditTest.kt \
        modules/web/src/main/resources/templates/partials/pipelines.html \
        modules/web/src/main/resources/templates/partials/templates.html \
        modules/web/src/main/resources/templates/pipelines/list.html \
        modules/web/src/main/resources/templates/templates/list.html
git commit -m "fix(web): pipelines/templates list pagers targeted an id no page rendered (029)"
```

---

### Task 2: Live filter controls on the pipelines and templates lists (D2)

**Files:**
- Modify: `modules/web/src/main/resources/templates/pipelines/list.html:13-21`, `modules/web/src/main/resources/templates/templates/list.html:13-22`
- Test: `modules/web/src/test/kotlin/co/datapipelines/web/ui/ListPartialsRenderTest.kt` (extend)

**Interfaces:**
- Consumes: `#pipeline-list-wrapper` / `#template-list-wrapper` from Task 1.
- Produces: control ids `pipeline-filter-q`, `pipeline-filter-spinner`, `template-filter-q`, `template-filter-dialect`, `template-filter-spinner`. Task 8's spec rows name these.

- [ ] **Step 1: Write the failing page-render tests.** Add to `ListPartialsRenderTest` (it currently renders partials only — these render the PAGES, so add the layout variables listed in Task 1 Step 2):

```kotlin
    @Test
    fun `pipelines page wires a live search control into the stable swap root`() {
        val html = engine.process("pipelines/list", webContext().apply { fillPipelineList() })

        html shouldContain "id=\"pipeline-filter-q\""
        html shouldContain "hx-get=\"/partials/pipelines\""
        html shouldContain "hx-target=\"#pipeline-list-wrapper\""
        html shouldContain "hx-swap=\"outerHTML\""
        html shouldContain "hx-trigger=\"input changed delay:300ms, search\""
        html shouldContain "id=\"pipeline-filter-spinner\""
        html shouldContain "hx-indicator=\"#pipeline-filter-spinner\""
        // D3: the inert, unimplementable datasource select is gone.
        html shouldNotContain "All Datasources"
    }

    @Test
    fun `templates page wires search and dialect controls that include each other by id`() {
        val html = engine.process("templates/list", webContext().apply { fillTemplateList() })

        html shouldContain "id=\"template-filter-q\""
        html shouldContain "id=\"template-filter-dialect\""
        // By ID, never by name — other name="dialect" inputs exist on these screens.
        html shouldContain "hx-include=\"#template-filter-dialect\""
        html shouldContain "hx-include=\"#template-filter-q\""
        html shouldContain "hx-target=\"#template-list-wrapper\""
        html shouldContain "id=\"template-filter-spinner\""
    }
```

- [ ] **Step 2: Run and verify RED.**

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.ListPartialsRenderTest' -x verifyTestsExecuted`
Expected: FAIL — no `pipeline-filter-q` in the output.

- [ ] **Step 3: Implement the pipelines controls.** Replace `pipelines/list.html:13-21` (note: the datasource select is deleted per D3, not rewired):

```html
  <!-- SPA filter controls (§4.3, the 028 §4.5 contract): they re-fetch ONLY the list
       fragment into the stable #pipeline-list-wrapper root, so the controls are never
       re-rendered and the search keeps focus and value. -->
  <div style="margin-bottom: var(--gap-md); display: flex; gap: var(--gap-md); align-items: center;">
    <input type="search" id="pipeline-filter-q" name="q" th:value="${q}"
           placeholder="Search pipelines..."
           th:attr="hx-get=@{/partials/pipelines}, hx-target='#pipeline-list-wrapper', hx-swap='outerHTML'"
           hx-trigger="input changed delay:300ms, search"
           hx-indicator="#pipeline-filter-spinner"
           class="ds-input" style="flex: 1; max-width: 360px;">
    <span id="pipeline-filter-spinner" class="htmx-indicator ds-spinner" aria-hidden="true"></span>
  </div>
```

- [ ] **Step 4: Implement the templates controls.** Replace `templates/list.html:13-22`:

```html
  <!-- Search and dialect address each other by ID (hx-include): a name-based include
       would also sweep up any other name="dialect" input on the page (§4.6). -->
  <div style="margin-bottom: var(--gap-md); display: flex; gap: var(--gap-md); align-items: center;">
    <input type="search" id="template-filter-q" name="q" th:value="${q}"
           placeholder="Search templates..."
           th:attr="hx-get=@{/partials/templates}, hx-target='#template-list-wrapper', hx-swap='outerHTML'"
           hx-trigger="input changed delay:300ms, search"
           hx-include="#template-filter-dialect"
           hx-indicator="#template-filter-spinner"
           class="ds-input" style="flex: 1; max-width: 360px;">
    <select id="template-filter-dialect" name="dialect" class="ds-input"
            th:attr="hx-get=@{/partials/templates}, hx-target='#template-list-wrapper', hx-swap='outerHTML'"
            hx-trigger="change"
            hx-include="#template-filter-q"
            hx-indicator="#template-filter-spinner">
      <option value="" th:selected="${#strings.isEmpty(selectedDialect)}">All Dialects</option>
      <option th:each="d : ${dialects}" th:value="${d}" th:text="${d}"
              th:selected="${d == selectedDialect}"></option>
    </select>
    <span id="template-filter-spinner" class="htmx-indicator ds-spinner" aria-hidden="true"></span>
  </div>
```

- [ ] **Step 5: Run and verify GREEN.**

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.ListPartialsRenderTest' --tests 'co.datapipelines.web.ui.TemplateHtmxRenderAuditTest' -x verifyTestsExecuted`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add modules/web/src/main/resources/templates/pipelines/list.html \
        modules/web/src/main/resources/templates/templates/list.html \
        modules/web/src/test/kotlin/co/datapipelines/web/ui/ListPartialsRenderTest.kt
git commit -m "fix(web): pipelines/templates search controls were inert (029)"
```

---

### Task 3: The all-columns search rule (D5, D6)

**The rule this plan establishes: a screen's server-side search covers every column that screen renders.** Where the column is derived (a dialect enum's wire value), the search matches the rendered text.

**Files:**
- Modify: `modules/web/src/main/kotlin/co/datapipelines/web/ui/DatasourcePartialController.kt:165-176`
- Modify: `modules/templates/src/main/kotlin/co/datapipelines/templates/TemplateRepository.kt:128-140`
- Test: `modules/web/src/test/kotlin/co/datapipelines/web/ui/DatasourceUiControllerTest.kt` (extend, MockK shape already there); the TemplateRepository integration test alongside its existing repository specs in `modules/templates/src/test/kotlin/`

- [ ] **Step 1: Write the failing datasource filter tests.** The predicate is private, so drive it through `DatasourcePartialController.list` and read the model, matching the existing `every { registry.listVisible(...) } returns …` shape:

First widen the file's existing fixture — it currently takes `name` only (`DatasourceUiControllerTest.kt:32-40`). Keep every current default so no existing test changes behaviour:

```kotlin
    private fun datasource(
        name: String = "pg-prod",
        dialect: Dialect = Dialect.POSTGRES,
        jdbcUrl: String = "jdbc:postgresql://db:5432/app",
        username: String = "readonly",
    ) = Datasource(
        name = name,
        displayName = "Production Postgres",
        description = "Main production database",
        dialect = dialect,
        jdbcUrl = jdbcUrl,
        username = username,
    )
```

Then the cases. Note the house shape in this file: `authenticate()` first (the partial reads the workspace off the principal and returns an empty list without it), the `partialController()` factory, and `ExtendedModelMap` — not `ConcurrentModel`:

```kotlin
    @Test
    fun `partial search matches every rendered column`() {
        authenticate()
        val partialController = partialController()
        val rows = listOf(
            datasource(name = "alpha", jdbcUrl = "jdbc:postgresql://reports.internal:5432/db", username = "svc_reports"),
            datasource(name = "beta", dialect = Dialect.SQLITE, jdbcUrl = "jdbc:sqlite:/tmp/other.db", username = "svc_other"),
        )
        every { registry.listVisible(null, workspaceId) } returns rows

        // jdbcUrl substring, username, and the dialect's wire value each select alpha only.
        listOf("reports.internal", "svc_reports", "postgres").forEach { q ->
            val model: ExtendedModelMap = ExtendedModelMap()
            partialController.list(model, q, null, null)
            @Suppress("UNCHECKED_CAST")
            val shown = model.getAttribute("datasources") as List<Datasource>
            shown.map { it.name } shouldBe listOf("alpha")
        }
    }

    @Test
    fun `partial search still excludes non-matches`() {
        authenticate()
        every { registry.listVisible(null, workspaceId) } returns listOf(datasource(name = "alpha"))
        val model: ExtendedModelMap = ExtendedModelMap()
        partialController().list(model, "nothing-matches-this", null, null)
        (model.getAttribute("datasources") as List<*>) shouldBe emptyList<Datasource>()
    }
```

- [ ] **Step 2: Run and verify RED.**

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.DatasourceUiControllerTest' -x verifyTestsExecuted`
Expected: FAIL — the URL/username/dialect queries return both rows.

- [ ] **Step 3: Implement.** Replace `DatasourcePartialController.kt:165-176`:

```kotlin
    /**
     * The screen's search covers EVERY column the table renders (§4.5): name +
     * readonly, dialect, workspace, URL, username — plus description, which is
     * searchable though only the modal shows it. A search that silently ignores a
     * visible column reads as "no results" to the user (029).
     */
    private fun filter(
        list: List<Datasource>,
        query: String?,
    ): List<Datasource> {
        if (query == null) return list
        val lower = query.lowercase()
        return list.filter { d ->
            d.name.lowercase().contains(lower) ||
                d.displayName.lowercase().contains(lower) ||
                d.dialect.wire.lowercase().contains(lower) ||
                d.jdbcUrl.lowercase().contains(lower) ||
                d.username.lowercase().contains(lower) ||
                (d.description?.lowercase()?.contains(lower) == true)
        }
    }
```

The workspace column renders `workspaceName` when bound and the literal `global` otherwise; `Datasource` carries `workspaceId`, not the name. Cover it only if the registry exposes the name on the record — if it does not, **say so in the handback and in the §4.5 spec row** rather than silently leaving the column unsearchable.

- [ ] **Step 4: Run and verify GREEN.** Same command as Step 2.

- [ ] **Step 5: Write the failing template-dialect repository test**, in the existing `TemplateRepository` integration spec (same Testcontainer/fixture shape as its neighbours — clean up only the tables you touch):

```kotlin
    @Test
    fun `list matches the rendered dialect column`() {
        // Two templates whose names and descriptions share no substring with "sqlite".
        val hits = repository.list(workspaceId, q = "sqlite")
        hits.map { it.id } shouldBe listOf("inventory.sql")
    }
```

- [ ] **Step 6: Run and verify RED**, then add `OR v.dialect ILIKE CAST(:pattern AS TEXT) ESCAPE '\'` to the `q` disjunction at `TemplateRepository.kt:133-137`, keeping the existing `escapeLike` binding untouched. Re-run and verify GREEN.

Run: `./gradlew :modules:templates:test -x verifyTestsExecuted`

- [ ] **Step 7: Audit the pipelines predicate and record the verdict.** `PipelinePartialController.kt:53-64` matches name, displayName, description; the table renders exactly those three plus `v{currentVersion}` and a formatted `updatedAt`. Free-text search over a version number or a timestamp is not the rule's intent — **state in the handback that pipelines already satisfies the rule and no change was made.** Do not add version/date matching.

- [ ] **Step 8: Commit.**

```bash
git add modules/web/src/main/kotlin/co/datapipelines/web/ui/DatasourcePartialController.kt \
        modules/web/src/test/kotlin/co/datapipelines/web/ui/DatasourceUiControllerTest.kt \
        modules/templates/src/main/kotlin/co/datapipelines/templates/TemplateRepository.kt \
        modules/templates/src/test/kotlin/...
git commit -m "fix(web): list search covers every rendered column (029)"
```

---

### Task 4: The shared pager fragment

Extract only now — after Tasks 1-2 there are three call sites with identical structure, so the abstraction is earned rather than speculative.

**Design constraint that shapes the signature:** Thymeleaf link expressions take **literal parameter names** — `@{${endpoint}(q=${q})}` cannot splat a map of per-screen filters. So the fragment does **not** build URLs. Each caller builds its own `prevUrl` / `nextUrl` with `th:with` (exactly as `partials/datasources.html:68,76` already does) and passes the finished strings.

**Files:**
- Create: `modules/web/src/main/resources/templates/partials/pager.html`
- Modify: `partials/datasources.html:65-81`, `partials/pipelines.html:37-49`, `partials/templates.html:41-53`
- Test: `modules/web/src/test/kotlin/co/datapipelines/web/ui/PagerFragmentRenderTest.kt` (new)

**Interfaces:**
- Produces: `partials/pager :: pager(targetId, prevUrl, nextUrl, offset, hasMore, shown, total)`
  - `targetId: String` — the swap-root selector including the `#`, e.g. `'#pipeline-list-wrapper'`
  - `prevUrl`, `nextUrl: String` — fully-built URLs from the caller's own `@{...}`
  - `offset: Int` — Previous is disabled when `offset == 0`
  - `hasMore: Boolean` — Next is disabled when false
  - `shown: Int` — rows in this page
  - `total: Int?` — **nullable**: `TemplatePartialController` supplies no total, so the fragment renders `Showing {shown}` when it is null and `Showing {shown} of {total}` otherwise. See Deferred decisions.

- [ ] **Step 1: Write the failing fragment test.**

```kotlin
class PagerFragmentRenderTest {
    private val engine = SpringTemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/"; suffix = ".html"; characterEncoding = "UTF-8"
        })
    }

    @Test
    fun `first page disables previous and resolves the next url verbatim`() {
        val html = render(offset = 0, hasMore = true, shown = 25, total = 30)

        html shouldContain "disabled"
        html shouldContain "hx-get=\"/partials/pipelines?q=trip&amp;offset=25\""
        html shouldContain "hx-target=\"#pipeline-list-wrapper\""
        html shouldContain "hx-swap=\"outerHTML\""
        html shouldContain "Showing 25 of 30"
    }

    @Test
    fun `a middle page enables both buttons`() {
        val html = render(offset = 25, hasMore = true, shown = 25, total = 100)
        Regex("""<button[^>]*\sdisabled""").containsMatchIn(html) shouldBe false
    }

    @Test
    fun `the last page disables next`() {
        render(offset = 25, hasMore = false, shown = 5, total = 30) shouldContain "disabled"
    }

    @Test
    fun `a null total renders the count alone`() {
        render(offset = 0, hasMore = true, shown = 25, total = null) shouldContain "Showing 25"
    }

    private fun render(offset: Int, hasMore: Boolean, shown: Int, total: Int?): String =
        engine.process(
            "partials/pager",
            webContext().apply {
                setVariable("targetId", "#pipeline-list-wrapper")
                setVariable("prevUrl", "/partials/pipelines?q=trip&offset=" + (offset - 25))
                setVariable("nextUrl", "/partials/pipelines?q=trip&offset=" + (offset + 25))
                setVariable("offset", offset)
                setVariable("hasMore", hasMore)
                setVariable("shown", shown)
                setVariable("total", total)
            },
        )
    // webContext() as in ListPartialsRenderTest
}
```

Rendering a parameterised fragment standalone needs a host: process a one-line inline template, or add a test-only wrapper under `src/test/resources/templates/`. Prefer processing `"partials/pager"` with the variables set as above and `th:fragment="pager(...)"` reading them as context variables — Thymeleaf resolves fragment parameters from the context when the fragment is processed directly.

- [ ] **Step 2: Run and verify RED** (template not found).

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.PagerFragmentRenderTest' -x verifyTestsExecuted`

- [ ] **Step 3: Implement `partials/pager.html`.**

```html
<!-- The one pager for every list screen (§5). It never builds URLs: Thymeleaf link
     expressions take literal parameter names, so a per-screen filter set cannot be
     splatted into @{...}. Each caller builds prevUrl/nextUrl with its own th:with and
     passes the finished strings, together with the swap root it owns. -->
<div xmlns:th="http://www.thymeleaf.org"
     th:fragment="pager(targetId, prevUrl, nextUrl, offset, hasMore, shown, total)"
     style="display: flex; align-items: center; justify-content: center; gap: var(--gap-md); margin-top: var(--gap-md); font-size: var(--text-sm);">
  <button class="ds-button ds-button-ghost ds-button-sm"
          th:disabled="${offset == 0}"
          th:attr="hx-get=${prevUrl}, hx-target=${targetId}, hx-swap='outerHTML'">
    Previous
  </button>
  <span style="color: var(--text-secondary);"
        th:text="${total == null} ? 'Showing ' + ${shown} : 'Showing ' + ${shown} + ' of ' + ${total}">Showing 0</span>
  <button class="ds-button ds-button-ghost ds-button-sm"
          th:disabled="${!hasMore}"
          th:attr="hx-get=${nextUrl}, hx-target=${targetId}, hx-swap='outerHTML'">
    Next
  </button>
</div>
```

- [ ] **Step 4: Run and verify GREEN.** Same command as Step 2.

- [ ] **Step 5: Adopt on all three screens.** Replace each pager block with a `th:with` + `th:replace` pair. Pipelines (`partials/pipelines.html:37-49`):

```html
    <th:block th:with="prevUrl=@{/partials/pipelines(q=${q}, offset=${offset - 25})},
                       nextUrl=@{/partials/pipelines(q=${q}, offset=${offset + 25})}">
      <div th:replace="~{partials/pager :: pager('#pipeline-list-wrapper', ${prevUrl}, ${nextUrl}, ${offset}, ${hasMore}, ${pipelines.size()}, ${total})}"></div>
    </th:block>
```

Templates (`partials/templates.html:41-53`) — same shape with `/partials/templates(q=${q}, dialect=${selectedDialect}, …)`, `'#template-list-wrapper'`, `${templates.size()}` and `null` for total. Datasources (`partials/datasources.html:65-81`) — `/partials/datasources(q=${q}, dialect=${selectedDialect}, …)`, `'#datasource-list-wrapper'`, `${datasources.size()}`, `${total}`.

The page size `25` stays literal in each caller's URL arithmetic, matching each controller's `PAGE_SIZE` (all three are 25: `DatasourcePartialController.kt:184`, `PipelinePartialController.kt:72`, `TemplatePartialController.kt:60`). **Do not** invent a shared constant — the coupling is already loose and this plan does not touch controller paging.

- [ ] **Step 6: Run the full web render suite.**

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.*RenderTest' --tests 'co.datapipelines.web.ui.TemplateHtmxRenderAuditTest' -x verifyTestsExecuted`
Expected: PASS. `ListPartialsRenderTest`'s existing URL assertions (`hx-get="/partials/pipelines?q=&amp;offset=25"`) must still hold — the fragment changes where the markup lives, not what it renders.

- [ ] **Step 7: Commit.**

```bash
git add modules/web/src/main/resources/templates/partials/pager.html \
        modules/web/src/main/resources/templates/partials/{datasources,pipelines,templates}.html \
        modules/web/src/test/kotlin/co/datapipelines/web/ui/PagerFragmentRenderTest.kt
git commit -m "feat(web): one shared pager fragment for every list screen (029)"
```

---

### Task 5: The executions pager's `hx-vals` (D7 — falsify first)

**Files:**
- Modify (only if Step 1 goes red): `modules/web/src/main/resources/templates/partials/executions.html:50,61`
- Test: `modules/web/src/test/kotlin/co/datapipelines/web/ui/ExecutionsPartialRenderTest.kt` (new)

The executions list keeps its OWN contract — `hx-target="#execution-table"` with `innerHTML` into a stable outer div (`executions/list.html:40`) and `hx-include="#execution-filters"` (`executions/list.html:11`). That contract is sound and already satisfies every §5.1 guarantee; converting it to the outerHTML shape would be churn with regression risk. **It does not adopt the Task 4 fragment.**

- [ ] **Step 1: Write the falsification test.**

```kotlin
    @Test
    fun `the executions pager renders a resolved hx-vals offset`() {
        val html = engine.process("partials/executions", webContext().apply {
            setVariable("executions", listOf(executionRecord()))
            setVariable("offset", 25)
            setVariable("pageSize", 25)
            setVariable("nextOffset", 50)
            setVariable("hasMore", true)
        })

        // Thymeleaf processes [[...]] inlining in TEXT nodes, not in plain attributes.
        html shouldNotContain "[[$" + "{"
        html shouldContain "\"offset\": \"0\""
        html shouldContain "\"offset\": \"50\""
    }
```

- [ ] **Step 2: Run it. This step decides the task.**

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.ExecutionsPartialRenderTest' -x verifyTestsExecuted`
- **RED** → D7 is confirmed; go to Step 3.
- **GREEN** → D7 was wrong; **say so in the handback**, keep the test as the regression lock, commit it alone, and skip Step 3.

- [ ] **Step 3: Fix using the form the spec already prescribes** (`docs/ui-screens.md:381`), for both buttons:

```html
                th:attr="hx-vals=|{&quot;offset&quot;: ${offset - pageSize}}|"
```
```html
                th:attr="hx-vals=|{&quot;offset&quot;: ${nextOffset}}|"
```

Re-run Step 2 and verify GREEN. Adjust the test's expected strings to the unquoted-number form the spec uses (`"offset": 0`) if that is what renders.

- [ ] **Step 4: Commit.**

```bash
git add modules/web/src/main/resources/templates/partials/executions.html \
        modules/web/src/test/kotlin/co/datapipelines/web/ui/ExecutionsPartialRenderTest.kt
git commit -m "fix(web): executions pager offsets were never interpolated (029)"
```

---

### Task 6: `.ds-table` / `.ds-badge` / `.ds-empty` on the four list partials

**Files:**
- Modify: `modules/web/src/main/resources/templates/partials/{datasources,pipelines,templates}.html`
- Test: `DatasourcesTemplateRenderTest`, `ListPartialsRenderTest` (class assertions replace style assertions)

Apply the same five edits to each partial:

1. Delete the `<div style="border: 1px solid …; border-radius: …; overflow: hidden;">` wrapper — `app.css:140-146` already gives `.ds-table` its border, radius and background. Leaving both draws two.
2. `<table style="…">` → `<table class="ds-table">`; delete every `<th style="…">` and `<td style="…">` padding/weight/color declaration.
3. Delete the `th:style` zebra striping and the per-row `border-bottom` — `.ds-table` owns row borders and hover.
4. Chips → badges: dialect `<span style="…">` → `<span class="ds-badge ds-badge-default">`; the datasources `readonly` chip → `<span class="ds-badge ds-badge-warning">` (it is a restriction, not a neutral fact); the workspaces `active` chip → `<span class="ds-badge ds-badge-primary">`.
5. Empty states → the real primitive (D4).

- [ ] **Step 1: Write the failing class assertions.** In `DatasourcesTemplateRenderTest` and `ListPartialsRenderTest`, per partial:

```kotlin
        html shouldContain "<table class=\"ds-table\">"
        html shouldContain "ds-badge"
        // The migration is only done when the inline table styles are GONE.
        html shouldNotContain "border-collapse: collapse"
        html shouldNotContain "padding: var(--gap-sm) var(--gap-md); text-align: left"
```

and for the empty-state render (empty list in the model):

```kotlin
        html shouldContain "class=\"ds-empty\""
        html shouldContain "class=\"ds-empty-title\""
        html shouldNotContain "ds-empty-state"   // a class with no CSS anywhere (D4)
```

- [ ] **Step 2: Run and verify RED.**

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.ListPartialsRenderTest' --tests 'co.datapipelines.web.ui.DatasourcesTemplateRenderTest' -x verifyTestsExecuted`

- [ ] **Step 3: Migrate the empty states.** Each partial's empty block becomes (pipelines shown; the other two swap the noun and the create action):

```html
  <th:block th:if="${#lists.isEmpty(pipelines)}">
    <div class="ds-empty">
      <p class="ds-empty-title" th:if="${#strings.isEmpty(q)}">No pipelines yet</p>
      <p class="ds-empty-title" th:unless="${#strings.isEmpty(q)}">No pipelines match your search</p>
      <p class="ds-empty-description" th:if="${#strings.isEmpty(q)}">Create your first pipeline to get started.</p>
      <div class="ds-empty-actions" th:unless="${#strings.isEmpty(q)}">
        <button class="ds-button ds-button-secondary ds-button-sm"
                th:attr="hx-get=@{/partials/pipelines}, hx-target='#pipeline-list-wrapper', hx-swap='outerHTML'">Clear filters</button>
      </div>
    </div>
  </th:block>
```

This satisfies §5.1's requirement that the two empties be distinguished and that the filtered one offer "Clear filters".

- [ ] **Step 4: Migrate the tables** per edits 1-4 above.

- [ ] **Step 5: Rename the templates "Updated" header to "Created"** (`partials/templates.html:20`) — the cell renders `t.createdAt` and `Template` has no `updatedAt`.

- [ ] **Step 6: Run and verify GREEN.** Same command as Step 2, plus `TemplateHtmxRenderAuditTest`.

- [ ] **Step 7: Browser check** on the rebuilt demo stack (`./app.sh --start --demo`): datasources, pipelines and templates each — search by a URL/username/dialect substring, a pager round trip, and (datasources) Test → toast, which is the 028 regression check. Screenshot each for the handback.

- [ ] **Step 8: Commit.**

```bash
git add modules/web/src/main/resources/templates/partials/{datasources,pipelines,templates}.html \
        modules/web/src/test/kotlin/co/datapipelines/web/ui/{DatasourcesTemplateRenderTest,ListPartialsRenderTest}.kt
git commit -m "refactor(web): list partials on ds-table/ds-badge/ds-empty (029)"
```

---

### Task 7: The remaining tables — classes only, no behaviour change

Every table here is either static or swapped by a contract that already works (027/027b). Change markup and classes only.

**Files:**
- Modify: `templates/settings/api-keys.html:13-47`, `templates/admin/users.html:31-50`, `modules/web/src/main/kotlin/co/datapipelines/web/ui/AdminUsersPartialController.kt:140-170`, `templates/workspaces/index.html:55-58,95-98`, `templates/templates/editor.html:67`
- Test: `ApiKeysControllerTest`, `AdminUsersControllerTest`, `WorkspacesUiControllerTest` (class assertions)

- [ ] **Step 1: api-keys.** `<table style="…">` → `<table class="ds-table">`; drop the `<div class="ds-card" style="padding:var(--gap-md)">` wrapper — `.ds-table` now supplies the box, and a card around it double-frames. Scope chips → `<span class="ds-badge ds-badge-default">`; the `(revoked)` text → `<span class="ds-badge ds-badge-danger">revoked</span>`. The colspan empty row becomes a `.ds-empty` block outside the table.

- [ ] **Step 2: admin users — template AND Kotlin.** `admin/users.html:32` → `<table class="ds-table">` (drop the `.ds-card` wrapper). Rows are built as Kotlin strings in `AdminUsersPartialController.buildUserRow` (`:140-170`): replace each `<td style="padding:var(--gap-xs)…">` with a bare `<td>`, and the two status/role `<span style="…background:$roleBg…">` chips with `<span class="ds-badge ds-badge-success|-danger">` (active/inactive) and `<span class="ds-badge ds-badge-primary|-default">` (admin/user). Delete the now-unused `COLOR_SUCCESS` / `COLOR_DANGER` / `COLOR_PRIMARY_BG` / `COLOR_TERTIARY` constants **only if** `actionButton` no longer references them — it does today (`style="color:$color"`), so keep the ones it uses and delete only the orphans. `ktlintCheck` will flag unused private constants.

- [ ] **Step 3: workspaces.** Both tables (`:57` and the per-workspace members table at `:96`) → `.ds-table`, wrapper divs deleted, the `active` chip → `.ds-badge .ds-badge-primary`, and the `ds-empty-state` block at `:52` → `.ds-empty` (D4).

- [ ] **Step 4: templates editor.** `templates/editor.html:67` → `.ds-table`, inline cell styles deleted.

- [ ] **Step 5: Add the class assertions** to each screen's existing controller test — presence of `class="ds-table"` and, where chips exist, `ds-badge`. Visual correctness is out of scope for render tests; class presence is the assertion.

- [ ] **Step 6: Run.**

Run: `./gradlew :modules:web:test -x verifyTestsExecuted`
Expected: PASS, including `TemplateHtmxRenderAuditTest`.

- [ ] **Step 7: Commit.**

```bash
git commit -am "refactor(web): remaining tables on ds-table/ds-badge (029)"
```

---

### Task 8: Spec sweep

The spec drifted from the code in ways this plan's work now settles. Fix the drift as well as recording the new contract.

- [ ] **Step 1: Per-screen rows in `docs/ui-screens.md`.**
  - §4.3 Pipeline List — the htmx cell currently names `hx-target="#pipeline-table"`, an id that has never existed; replace with `/partials/pipelines` into the fragment-root `#pipeline-list-wrapper`, `outerHTML`, plus the `#pipeline-filter-q` control. The Content line lists an "owner" column the table does not render and a datasource filter that D3 removed — correct both, and note the datasource filter as deferred with its reason (`PipelineRecord` carries no datasource).
  - §4.6 Template List — name the `#template-list-wrapper` root, the id-addressed `hx-include` pair, and the shared pager. The Content line's `is_library` badge does not exist in the table; remove it. Record that dialect is searchable (Task 3).
  - §4.5 Datasource List — add the all-columns search rule and the shared pager; keep the toast contract wording as v1.11 left it.
  - §4.8 Execution History — record that this screen keeps the `#execution-table` / `innerHTML` / `hx-include="#execution-filters"` contract deliberately, and (if Task 5 went red) that `hx-vals` is server-rendered via `th:attr`.
  - §4.10 / §4.12 / §4.13 — design-primitive rows name `.ds-table` / `.ds-badge`.

- [ ] **Step 2: §5 and §5.1.**
  - §5 gains the shared pager fragment: its signature, and the reason it takes URLs rather than a filter map (link expressions need literal parameter names).
  - §5.1 gains one sentence under a new **Search** paragraph: "A screen's server-side search covers every column that screen renders; where a column is derived, the search matches the rendered text."
  - §5.1's Empty-state paragraph gains the class names `.ds-empty` / `.ds-empty-title` / `.ds-empty-description` / `.ds-empty-actions`, so the next screen cannot reinvent `.ds-empty-state`.

- [ ] **Step 3: Changelog.** Add the `v1.12` row (the file is at v1.11, `docs/ui-screens.md:468`) naming: the dead pipelines/templates swap roots and inert search controls; the shared pager; the all-columns search rule; `.ds-table`/`.ds-badge`/`.ds-empty` adoption; the two new mechanical guards. Bump **Status:** at line 3 to v1.12.

- [ ] **Step 4: Run** `./scripts/docs-audit.sh` — exit 0. **Commit** `docs(ui-screens): shared table contract, live list controls, search rule (029)`.

---

### Task 9: Gate, evidence, merge

- [ ] **Step 1: Full gate.**

Run: `./gradlew build ktlintCheck detekt --rerun-tasks`
Expected: BUILD SUCCESSFUL. Read the log's last line, not the wrapper's exit code, and confirm no verification task reported `UP-TO-DATE`.

- [ ] **Step 2: Browser evidence** on the rebuilt demo stack (`./app.sh --start --demo`): every migrated screen visited; search-per-column on datasources; the pipelines and templates search and pager working **for the first time** (before/after screenshots — this is the headline evidence); a pager round trip on each list; the datasources Test → toast regression.

- [ ] **Step 3: Falsification runs, one per guard.** For each of the two new audits in Task 1 and the pager fragment test: revert the fix in a scratch copy, run the guard, confirm RED, restore. A guard that cannot go red is not a guard. Record each run in the handback.

- [ ] **Step 4: Handback** at `datapipelines-orchestration/handbacks/029-table-component.md`: evidence, deviations, the falsification runs, the Task 3 Step 7 pipelines verdict, the Task 5 Step 2 verdict on D7, and every deferred item below.

- [ ] **Step 5: Merge** from the MAIN checkout (operator reviews first, per house flow). Before committing anything there, check `git symbolic-ref HEAD` — not the SHA — and `git status` for foreign modified files. After pushing, verify containment: `git merge-base --is-ancestor <your-sha> origin/main`. Then a full build on main.

---

## Deferred decisions — surface in the handback, do not decide silently

- **Approximate totals.** `PipelinePartialController.kt:36` reports `total` as "rows so far + 1 if more", so a 100-row workspace renders "Showing 25 of 26", and `TemplatePartialController` reports no total at all (hence the nullable `total` in the pager fragment). A truthful count needs a `COUNT(*)` on both repositories. **Recommendation:** add it as a follow-up; the pager's nullable-total contract already accommodates either outcome without another template change.
- **Pipelines datasource filter (D3).** Deleted rather than implemented. Serving it needs a join through the pipeline definition to its datasource references — a repository change out of proportion to this plan.
- **Datasource workspace column searchability.** Covered only if the registry exposes `workspaceName` on the record (Task 3 Step 3). If it does not, the column stays unsearchable and the §4.5 row must say so.
- **`hx-push-url` for filter state.** Still deferred from 028 — no screen puts search/filter state in the URL, so a filtered list cannot be shared or restored on reload.

## Explicitly NOT in this plan

- The editor result grid and execute-page tables — `2026-08-31-execute-page-redesign.md` (`pipelines/editor.html:184`, `.pe-result-table`).
- The pipeline graph — `2026-08-31-pipeline-graph-design.md`.
- Converting mutations to toasts beyond what 028 already shipped — `2026-08-31-toast-application.md`.
- New endpoints, new columns, controller paging changes, and any edit to `static/vendor/design-system/`.
