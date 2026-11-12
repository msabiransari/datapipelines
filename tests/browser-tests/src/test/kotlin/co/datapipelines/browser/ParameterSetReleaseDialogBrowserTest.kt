package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #876 §F.2–F.3 — the SHARED release dialog in a real browser: the parameter set's new release shell (the family's first
 * human release surface), the brief's worked example through the dashboard's dialog, and the unavailable prerequisite
 * through the pipeline's. Every object is authored through the real REST routes ([DependencyPaneBrowserSuite]); every
 * refusal is asserted at the TABLES, never at the page.
 *
 * What only a browser shows: the dialog's "Show graph" region draws the plan's OWN rows with the landed renderer and
 * sends ZERO `GET /api/v1/dependencies/…` requests (Gates "Plan, not tree", counted off the network for the whole
 * test); its Graph/List choice is the page's stored one (no second store); and the graph is torn down — Cytoscape
 * destroyed, the pane back to idle — when the dialog's body is swapped and when the dialog closes (the
 * `DependenciesMount.unmount` witness: a pane held across the swap reads `idle` with nothing drawn, where a leaked one
 * would still read `ready` with its canvas).
 */
class ParameterSetReleaseDialogBrowserTest : DependencyPaneBrowserSuite() {
    private val psDialog = "#ps-dialog"

    @Test
    fun `the set's release dialog - its plan rows, the graph from those rows with zero tree requests, torn down on swap and close`() {
        startTrace()
        val root = ready("psrel")
        val datasource = registerDatasource()
        val selector = sqlTemplate("$root/selector.sql", SELECTOR_BODY)
        val setId = parameterSet("$root/filters", selector, datasource)
        countTreeRequests()

        page.navigate("$baseUrl/parameter-sets/$setId")
        page.waitForSelector(".ps-root")
        val dialog = openSetDialog()

        // The plan: the root, its DRAFT selector template (its own checked box) and the datasource as a TEXT row.
        dialog.locator("[data-release-row]").count() shouldBe 2
        dialog.locator("[data-release-row='root'][data-name='$root/filters']").count() shouldBe 1
        val box = dialog.locator("[data-release-row='draft'][data-name='$selector'] input[name='include_dependencies']")
        box.isChecked shouldBe true
        val prerequisite = dialog.locator("[data-release-prerequisite][data-name='$datasource']")
        prerequisite.innerText() shouldContain "Datasource $datasource"
        prerequisite.locator("input").count() shouldBe 0
        dialog.locator("input[name='tree_hash']").count() shouldBe 1
        submit(dialog).isDisabled shouldBe false

        // The graph: drawn from the rows (two nodes — the prerequisite is never one), Graph by default on this page.
        openGraph(dialog)
        activeView(psDialog) shouldBe "graph"
        (drawn(psDialog)!!["nodes"] as Number).toInt() shouldBe 2
        showList(psDialog)
        listRows(psDialog).size shouldBe 2

        // A body swap (the same dialog re-requested into its container) tears the old graph down; the new dialog's
        // graph opens in the page's stored choice — List, pressed above — with no second store.
        holdPane()
        page.evaluate("p => htmx.ajax('GET', p, { target: '#ps-dialog', swap: 'innerHTML' })", dialogPath(setId))
        page.waitForFunction("() => window.__rdHeld && !window.__rdHeld.isConnected")
        assertHeldPaneTornDown()
        val swapped = page.locator("$psDialog [data-lifecycle-dialog='parameter-set-release']")
        swapped.locator("button[type=submit]").waitFor()
        openGraph(swapped)
        activeView(psDialog) shouldBe "list"

        // Close: the same teardown.
        holdPane()
        page.keyboard().press("Escape")
        page.locator("$psDialog [data-lifecycle-dialog]").waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED))
        assertHeldPaneTornDown()

        // Release through the dialog's own POST: the plan it rendered, both versions RELEASED at the table.
        val again = openSetDialog()
        submit(again).click()
        page.waitForURL("**/parameter-sets/$setId?version=1&ok=released")
        scalar("SELECT status FROM parameter_set_versions WHERE parameter_set_id = '$setId' AND version = 1") shouldBe "RELEASED"
        templateStatus(selector) shouldBe "RELEASED"
        treeRequests shouldBe emptyList()
    }

    /**
     * The brief's worked example (§F.2) through the DASHBOARD's dialog: board → daily, weekly (both → the SAME clean →
     * the RELEASED query on the datasource), board → the RELEASED filters. Seven rows (root, three checked drafts, two
     * released chips, one prerequisite), six hidden edges, `clean` ONCE; the graph six nodes and six edges. A POST
     * without clean's id (its box unticked, the submit forced open) is refused and nothing flips.
     */
    @Test
    fun `the worked example - seven rows, clean once, six nodes and six edges, and a POST without clean flips nothing`() {
        startTrace()
        val root = ready("psrelwx")
        val world = seedWorkedExample(root)
        countTreeRequests()

        page.navigate("$baseUrl/dashboards/${world.boardId}?version=1&tab=versions")
        page.locator("[data-verb='dashboard-release']").first().click()
        val dialog = page.locator("#dp-dialog [data-lifecycle-dialog='dashboard-release']")
        dialog.locator("button[type=submit]").waitFor()

        dialog.locator("[data-release-row]").count() + dialog.locator("[data-release-prerequisite]").count() shouldBe 7
        rowNames(dialog, "draft") shouldContainExactlyInAnyOrder listOf(world.daily, world.weekly, world.clean)
        rowNames(dialog, "released") shouldContainExactlyInAnyOrder listOf(world.filters, world.query)
        dialog.locator("[data-release-row][data-name='${world.clean}']").count() shouldBe 1
        dialog.locator("input[name='include_dependencies']:checked").count() shouldBe 3
        dialog.locator("[data-release-prerequisite][data-name='${world.datasource}']").count() shouldBe 1
        dialog.locator("[data-release-edges] > li").count() shouldBe 6
        dialog.locator("input[name='tree_hash']").count() shouldBe 1
        submit(dialog).isDisabled shouldBe false

        openGraph(dialog)
        val drawn = drawn("#dp-dialog")!!
        (drawn["nodes"] as Number).toInt() shouldBe 6
        (drawn["edges"] as Number).toInt() shouldBe 6
        // The drawn label breaks a folder path after every "/" with U+200B (renderer.js graphLabel); the name is the same.
        (drawn["labels"] as List<*>).count { (it as String).replace("​", "").contains(world.clean) } shouldBe 1

        // A11 through this dialog: clean unticked, the page forced past its own gates — refused, nothing flipped.
        dialog.locator("[data-release-row='draft'][data-name='${world.clean}'] [data-consent-input]").uncheck()
        val refused = forcedPost("#dp-dialog")
        (refused in 400..499) shouldBe true
        scalar("SELECT status FROM dashboard_versions WHERE dashboard_id = '${world.boardId}' AND version = 1") shouldBe "DRAFT"
        listOf(world.dailyId, world.weeklyId, world.cleanId).forEach { id ->
            scalar("SELECT status FROM pipeline_versions WHERE pipeline_id = '$id' AND version = 1") shouldBe "DRAFT"
        }
        treeRequests shouldBe emptyList()
    }

    /**
     * §F.2 Unavailable prerequisite, run 1 (no datasource of that name exists), through the pipeline's dialog, with the
     * two holders #873 pinned (`DatasourceEdgesE2eTest` case 3). `load` is the brief's fixture: its only artifact
     * dependency is a RELEASED template and its own DQL node names the vanished datasource. `parent` is a DRAFT pipeline
     * over a RELEASED `child` whose DQL node names it. Both dialogs read "Datasource … is not available" with the submit
     * disabled. A forced POST is refused either way and nothing flips, but by different guards. `load` flips, so its
     * native revalidation refuses first, outside the guarded transaction: 400 `pipeline.validation.unknown_datasource`.
     * `child` does not flip, so only the coordinator's apply-time prerequisite check sees it: 409
     * `pipeline.release.dependency_not_released` with the versionless datasource blocker.
     */
    @Test
    fun `an unavailable datasource is a blocking text row, the submit disabled, and a forced POST is refused flipping nothing`() {
        startTrace()
        val root = ready("psrelgone")
        val datasource = registerDatasource()
        val query = sqlTemplate("$root/q.sql")
        releaseTemplate(query)
        val loadId = pipeline("$root/load", query, datasource)
        val childId = pipeline("$root/child", query, datasource)
        PlannedRelease.release(
            page,
            "/partials/pipelines/$childId/lifecycle/release",
            "/api/v1/pipelines/$childId/release",
            requireNotNull(pipelineHash(childId)),
        )
        val parentId = parentPipeline("$root/parent", "$root/child")
        // The datasource vanishes after authoring (its grants cascade): the plans still name it, nothing serves it.
        sql("DELETE FROM datasources WHERE name = '$datasource'")
        countTreeRequests()

        // The native holder: refused 400 by its own revalidation.
        val load = openPipelineDialog(loadId, datasource)
        val loadHash = load.locator("input[name='tree_hash']").inputValue()
        val native = forcedPostResponse("#pe-dialog")
        native.first shouldBe 400
        native.second shouldContain "pipeline.validation.unknown_datasource"
        restRelease("/api/v1/pipelines/$loadId/release", loadHash, pipelineHash(loadId))["code"] shouldBe
            "pipeline.validation.unknown_datasource"

        // The prerequisite holder: refused 409 by the coordinator. The dialog's refusal is the toast (code +
        // correlation); the blocker's shape is the REST envelope's, read with the SAME plan the dialog rendered.
        val parent = openPipelineDialog(parentId, datasource)
        val parentHash = parent.locator("input[name='tree_hash']").inputValue()
        val response = forcedPostResponse("#pe-dialog")
        response.first shouldBe 409
        response.second shouldContain "pipeline.release.dependency_not_released"
        val refusal = restRelease("/api/v1/pipelines/$parentId/release", parentHash, pipelineHash(parentId))
        refusal["status"] shouldBe 409
        refusal["code"] shouldBe "pipeline.release.dependency_not_released"
        refusal["blockers"] shouldBe listOf("datasource|$datasource|unavailable")

        listOf(loadId, parentId).forEach { id ->
            scalar("SELECT status FROM pipeline_versions WHERE pipeline_id = '$id' AND version = 1") shouldBe "DRAFT"
        }
        treeRequests shouldBe emptyList()
    }

    /** Opens [pipelineId]'s release dialog and asserts the unavailable [datasource] row and the disabled submit. */
    private fun openPipelineDialog(
        pipelineId: String,
        datasource: String,
    ): Locator {
        page.navigate("$baseUrl/pipelines/$pipelineId")
        page.waitForSelector(".pe-root")
        page.locator(".pe-topbar [data-verb='pipeline-release']").click()
        val dialog = page.locator("#pe-dialog [data-lifecycle-dialog='pipeline-release']")
        val row = dialog.locator("[data-release-prerequisite][data-name='$datasource']")
        row.waitFor()
        row.innerText() shouldContain "Datasource $datasource is not available"
        dialog.locator("[data-release-plan]").getAttribute("data-release-submittable") shouldBe "false"
        dialog.locator("button[type=submit]").first().isDisabled shouldBe true
        return dialog
    }

    // ------------------------------------------------------------------ the dialog

    private fun dialogPath(setId: String) = "/partials/parameter-sets/$setId/lifecycle/release"

    private fun openSetDialog(): Locator {
        page.locator("[data-verb='parameter-set-release']").click()
        val dialog = page.locator("$psDialog [data-lifecycle-dialog='parameter-set-release']")
        dialog.locator("button[type=submit]").waitFor()
        return dialog
    }

    private fun submit(dialog: Locator): Locator = dialog.locator("button[type=submit]")

    private fun openGraph(dialog: Locator) {
        dialog.locator("[data-release-graph] summary").click()
        dialog.locator("[data-dp-deps][data-dp-deps-state='ready']").waitFor()
    }

    private fun rowNames(
        dialog: Locator,
        kind: String,
    ): List<String> = dialog.locator("[data-release-row='$kind']").all().map { it.getAttribute("data-name") }

    /** The view the pane under [scope] shows: the toggle button the pane marks pressed. */
    private fun activeView(scope: String): String =
        requireNotNull(page.getAttribute("$scope [data-dp-deps-view][aria-pressed='true']", "data-dp-deps-view")) {
            "no pressed view button under $scope"
        }

    /** Cytoscape's own registry on the canvas — the drawn graph, read without a test hook in product code. */
    @Suppress("UNCHECKED_CAST")
    private fun drawn(scope: String): Map<String, Any?>? =
        page.evaluate(
            "s => { const c = document.querySelector(s + ' .dp-deps-canvas'); const cy = c && c._cyreg && c._cyreg.cy;" +
                " return cy ? { nodes: cy.nodes().length, edges: cy.edges().length," +
                " labels: cy.nodes().map(n => n.data('text')) } : null; }",
            scope,
        ) as Map<String, Any?>?

    /** Keeps a reference to the dialog's live pane across its removal (the unmount witness). */
    private fun holdPane() {
        page.evaluate("s => { window.__rdHeld = document.querySelector(s + ' [data-dp-deps]'); }", psDialog)
        page.evaluate("() => window.__rdHeld.getAttribute('data-dp-deps-state')") shouldBe "ready"
    }

    /** The held pane was torn down, not merely detached: idle, nothing drawn, no canvas, no style left behind. */
    @Suppress("UNCHECKED_CAST")
    private fun assertHeldPaneTornDown() {
        val held =
            page.evaluate(
                "() => { const p = window.__rdHeld; return { connected: p.isConnected, state: p.getAttribute('data-dp-deps-state')," +
                    " canvases: p.querySelectorAll('.dp-deps-canvas').length, styled: p.querySelectorAll('[style]').length }; }",
            ) as Map<String, Any?>
        held["connected"] shouldBe false
        held["state"] shouldBe "idle"
        (held["canvases"] as Number).toInt() shouldBe 0
        (held["styled"] as Number).toInt() shouldBe 0
    }

    /** Forces the dialog's submit past the page's own gates and sends its htmx POST; returns the status. */
    private fun forcedPost(container: String): Int = forcedPostResponse(container).first

    private fun forcedPostResponse(container: String): Pair<Int, String> {
        val button = "$container [data-lifecycle-dialog] form button[type=submit]"
        page.evaluate(
            """(sel) => {
              const button = document.querySelector(sel);
              const form = button.closest('form');
              form.noValidate = true;
              form.setAttribute('novalidate', '');
              button.disabled = false;
            }""",
            button,
        )
        val response =
            page.waitForResponse({ it.request().method() == "POST" && it.url().contains("/lifecycle/release") }) {
                page.locator(button).first().click()
            }
        val text = response.text()
        println("#876 forced POST $container: status=${response.status()} body=${text.take(EXCERPT)}")
        return response.status() to text
    }

    /** A REST release carrying [treeHash] and no dependency; answers its status, code and blockers as `kind|name|reason`. */
    @Suppress("UNCHECKED_CAST")
    private fun restRelease(
        path: String,
        treeHash: String,
        ifMatch: String?,
    ): Map<String, Any?> {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const response = await fetch(args.path, { method: 'POST', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'If-Match': args.ifMatch,
                               'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                    body: JSON.stringify({ tree_hash: args.treeHash, include_dependencies: [] }) });
                  const text = await response.text();
                  let error = {};
                  try { const json = JSON.parse(text); error = json.error || json; } catch (e) { error = { code: text.slice(0, 300) }; }
                  const blockers = ((error.details || {}).blockers || []).map(b => [b.kind, b.name, b.reason].join('|'));
                  return { status: response.status, code: error.code ?? null, blockers };
                }""",
                mapOf("path" to path, "treeHash" to treeHash, "ifMatch" to ifMatch),
            ) as Map<String, Any?>
        return mapOf("status" to (result["status"] as Number).toInt(), "code" to result["code"], "blockers" to result["blockers"])
    }

    private fun pipelineHash(id: String): String? =
        scalar("SELECT body_hash FROM pipeline_versions WHERE pipeline_id = '$id' AND version = 1")

    // ------------------------------------------------------------------ the worked example

    private data class WorkedExample(
        val datasource: String,
        val query: String,
        val clean: String,
        val daily: String,
        val weekly: String,
        val filters: String,
        val board: String,
        val cleanId: String,
        val dailyId: String,
        val weeklyId: String,
        val boardId: String,
    )

    private fun seedWorkedExample(root: String): WorkedExample {
        val datasource = registerDatasource()
        val query = sqlTemplate("$root/query.sql")
        releaseTemplate(query)
        val cleanId = pipeline("$root/clean", query, datasource)
        val dailyId = parentPipeline("$root/daily", "$root/clean")
        val weeklyId = parentPipeline("$root/weekly", "$root/clean")
        val filtersId =
            api(
                "POST",
                "/api/v1/parameter-sets",
                """{"name":"$root/filters","display_name":"Filters","description":"#876","parameters":[{"name":"region",""" +
                    """"label":"Region","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                    """"source":{"constants":[{"value":"east","display_value":"East","is_default":true}]}}]}""",
            ).data("id")
        api(
            "POST",
            "/api/v1/parameter-sets/$filtersId/release",
            null,
            ifMatch = scalar("SELECT body_hash FROM parameter_set_versions WHERE parameter_set_id = '$filtersId' AND version = 1"),
        )
        val boardId =
            api(
                "POST",
                "/api/v1/dashboards",
                """{"name":"$root/board","display_name":"Board","description":"#876",""" +
                    """"parameter_set":{"name":"$root/filters","version":1},""" +
                    """"sources":[{"name":"daily","pipeline":{"name":"$root/daily","version":1},"parameters":{}},""" +
                    """{"name":"weekly","pipeline":{"name":"$root/weekly","version":1},"parameters":{}}],""" +
                    """"visualizations":[],"layout":{"grid":[]}}""",
            ).data("id")
        return WorkedExample(
            datasource,
            query,
            "$root/clean",
            "$root/daily",
            "$root/weekly",
            "$root/filters",
            "$root/board",
            cleanId,
            dailyId,
            weeklyId,
            boardId,
        )
    }

    /** A pipeline whose one PIPELINE node pins [child] v1 (record §6 Pipeline → pipeline); returns its id. */
    private fun parentPipeline(
        name: String,
        child: String,
    ): String =
        api(
            "POST",
            "/api/v1/pipelines",
            """{"name":"$name","display_name":"${name.substringAfterLast('/')}","description":"#876","nodes":[""" +
                """{"id":"child","description":"Clean","type":"PIPELINE","pipeline":{"name":"$child","version":1},"depends_on":[]}]}""",
        ).data("id")

    private fun releaseTemplate(name: String) {
        api("POST", "/api/v1/templates/release", """{"name":"$name"}""", ifMatch = templateHash(name))
    }

    private fun templateHash(name: String): String? =
        scalar(
            "SELECT v.body_hash FROM template_versions v JOIN templates t ON t.id = v.template_id WHERE t.name = '$name' AND v.version = 1",
        )

    private fun templateStatus(name: String): String? =
        scalar("SELECT v.status FROM template_versions v JOIN templates t ON t.id = v.template_id WHERE t.name = '$name' AND v.version = 1")
}
