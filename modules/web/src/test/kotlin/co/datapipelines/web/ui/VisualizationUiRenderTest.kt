package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.util.UUID

/**
 * #399 — the Visualizations pages pinned at the RENDER (DashboardUiRenderTest's mould): the tree level
 * (folders first, the root holds no leaves, a leaf navigates un-boosted with the chart-line glyph), the
 * flat catalog and the sidebar search, the workspace page's ONE-bundle contract and its tabs, the
 * choose-a-version state, and every pane's stated contract — the Preview's fixture label and its exact
 * empty-state sentence, the Evidence cap and its screenshot-or-"no screenshot" rule, the Used-by empty
 * state, the Versions verbs per role, and the lifecycle dialogs (refusals before the button, the ONE
 * consent, the typed confirm, `from` on every workspace-returning form).
 *
 * What the lens hides is not in the MODEL (VisualizationBrowseModel/VisualizationTabModel apply it), so
 * the render guarantee is: what the model carries is what renders, and nothing else.
 */
class VisualizationUiRenderTest {
    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private val vizId = UUID.randomUUID()
    private val folder = VisualizationBrowseModel.FolderView("acme/charts", "charts", 2, "viz-level-aaaa")
    private val leaf =
        VisualizationBrowseModel.LeafView(vizId, "acme/charts/revenue", "revenue", "Revenue", 3, released = true)
    private val draftLeaf =
        VisualizationBrowseModel.LeafView(UUID.randomUUID(), "acme/charts/costs", "costs", "Costs", 1, released = false)

    // ------------------------------------------------------------------ the sidebar's tree level

    @Test
    fun `the root level lists folders only, each a lazy one-level request in the nav scope`() {
        val html = level(prefix = "", folders = listOf(folder), leaves = emptyList())

        Regex("hx-target=\"next .tpl-level\"").findAll(html).toList().size shouldBe 1
        html shouldContain "/partials/visualizations/tree?prefix=acme/charts&amp;scope=nav"
        html shouldContain "role=\"tree\""
        html shouldNotContain "tpl-leaf"
    }

    @Test
    fun `a level's leaves are un-boosted links to the workspace with the chart-line glyph and the draft badge`() {
        val html = level(prefix = "acme/charts", folders = emptyList(), leaves = listOf(leaf, draftLeaf))

        html shouldContain "href=\"/visualizations/$vizId\""
        html shouldContain "data-leaf-id=\"$vizId\""
        Regex("class=\"tpl-leaf\"[^>]*hx-boost=\"true\"").findAll(html).toList().size shouldBe 2
        html shouldContain "lucide-sprite.svg#chart-line"
        // Exactly one draft badge: the unreleased leaf's.
        Regex("tpl-leaf-draft").findAll(html).toList().size shouldBe 1
        html shouldContain "role=\"group\""
    }

    @Test
    fun `an empty root says how visualizations arrive, and a lens-unavailable root says the lens sentence instead`() {
        val empty = level(prefix = "", folders = emptyList(), leaves = emptyList())
        empty shouldContain "No visualizations yet"
        empty shouldContain "authored through the MCP server"

        val unavailable =
            engine.process(
                VisualizationBrowseModel.LEVEL_VIEW,
                webContext("/visualizations").apply {
                    levelVariables("", emptyList(), emptyList())
                    setVariable("lensUnavailable", LensedView.Unavailable("https://uat.example.com", "timeout"))
                },
            )
        unavailable shouldNotContain "No visualizations yet"
        unavailable shouldContain "Could not read the target"
    }

    // ------------------------------------------------------------------ the flat list (catalog + nav search)

    @Test
    fun `the catalog instance is a flat list of un-boosted rows under its stable root, a draft row saying so`() {
        val html = list(scope = "page", rootId = VisualizationBrowseModel.PAGE_ROOT_ID, q = "", rows = listOf(leaf, draftLeaf))

        html shouldContain "id=\"viz-list-wrapper\""
        html shouldContain "data-visualization-list=\"page\""
        Regex("class=\"tpl-result\"[^>]*hx-boost=\"true\"").findAll(html).toList().size shouldBe 2
        html shouldContain "draft v1 pending release"
        html shouldNotContain "role=\"listbox\""
    }

    @Test
    fun `the nav search is a listbox, and its no-match state offers the tree's clear`() {
        val hits = list(scope = "nav", rootId = VisualizationBrowseModel.NAV_ROOT_ID, q = "rev", rows = listOf(leaf))
        hits shouldContain "role=\"listbox\""
        hits shouldContain "role=\"option\""

        val none = list(scope = "nav", rootId = VisualizationBrowseModel.NAV_ROOT_ID, q = "zzz", rows = emptyList())
        none shouldContain "No visualizations match your search"
        none shouldNotContain "data-nav-tree-clear"
    }

    @Test
    fun `the catalog page reveals the sidebar tree and searches through the wrapper partial`() {
        val html =
            engine.process(
                VisualizationUiController.LIST_VIEW,
                webContext("/visualizations").apply {
                    setVariable("q", "")
                    setVariable("searching", true)
                    setVariable("scope", "page")
                    setVariable("rootId", VisualizationBrowseModel.PAGE_ROOT_ID)
                    setVariable("lensUnavailable", null)
                    setVariable("offset", 0)
                    setVariable("visualizations", listOf(leaf))
                    setVariable("hasMore", false)
                    setVariable("total", 1)
                },
            )

        html shouldContain "data-nav-tree-reveal=\"visualizations\""
        html shouldContain "hx-get=\"/partials/visualizations\""
        html shouldContain "id=\"viz-list-wrapper\""
        html shouldContain ">acme/charts/revenue<"
    }

    // ------------------------------------------------------------------ the workspace page

    @Test
    fun `the workspace declares ONE Plotly bundle, the viewed chip, five tabs and lazy panes for the viewed version`() {
        for (bundle in listOf("2d", "3d")) {
            val html = workspace(bundle = bundle, activeTab = "preview")
            val body = html.replace(Regex("<!--[\\s\\S]*?-->"), "")

            Regex("<script[^>]*plotly-[23]d\\.min\\.js").findAll(body).toList().size shouldBe 1
            body shouldContain "data-chart-assets"
            body shouldContain "/js/visualization-preview.js"
            body shouldContain ">acme/charts/revenue<"
            body shouldContain "v3 · released · current"
            Regex("data-dp-tab=\"").findAll(body).toList().size shouldBe 5
            body shouldContain "Preview (test fixtures)"
            body shouldContain "data-lazy-url=\"/partials/visualizations/$vizId/preview?version=3\""
            body shouldContain "data-lazy-url=\"/partials/visualizations/$vizId/overview?version=3\""
            body shouldContain "data-lazy-url=\"/partials/visualizations/$vizId/versions?version=3\""
            body shouldContain "data-lazy-url=\"/partials/visualizations/$vizId/evidence\""
            body shouldContain "data-lazy-url=\"/partials/visualizations/$vizId/used-by\""
            body shouldContain "data-nav-current=\"visualizations\""
            body shouldContain "id=\"dp-dialog\""
            // Every version switch is a full navigation that keeps the tab.
            body shouldContain "href=\"/visualizations/$vizId?version=2&amp;tab=preview\""
        }
    }

    @Test
    fun `the active tab is the only pane without the hidden attribute`() {
        val html = workspace(bundle = "2d", activeTab = "evidence")

        Regex("id=\"viz-pane-evidence\"[^>]*hidden").containsMatchIn(html) shouldBe false
        for (other in listOf("preview", "overview", "used-by", "versions")) {
            Regex("id=\"viz-pane-$other\"[^>]*hidden=\"hidden\"").containsMatchIn(html) shouldBe true
        }
        html shouldContain "data-active-tab=\"evidence\""
    }

    @Test
    fun `the choose-a-version state keeps the NAME, offers the versions, and loads no bundle and no panes`() {
        val html =
            engine.process(
                VisualizationUiController.WORKSPACE_VIEW,
                webContext("/visualizations/$vizId").apply {
                    workspaceVariables(activeTab = "preview")
                    setVariable("hasSelected", false)
                    setVariable("viewedVersion", null)
                    setVariable("viewedLabel", VisualizationUiController.NO_VERSION_SELECTED)
                    setVariable("viewedIsDraft", false)
                    setVariable(
                        "versions",
                        listOf(VisualizationUiController.VersionRow(1, "DRAFT", isCurrent = false, isSelected = false)),
                    )
                },
            )
        val body = html.replace(Regex("<!--[\\s\\S]*?-->"), "")

        body shouldContain ">acme/charts/revenue<"
        body shouldContain "data-viz-choose-version"
        body shouldContain "Choose a version"
        body shouldContain "href=\"/visualizations/$vizId?version=1&amp;tab=preview\""
        body shouldNotContain "plotly-2d.min.js"
        body shouldNotContain "/js/visualization-preview.js"
        body shouldNotContain "data-dp-pane"
    }

    // ------------------------------------------------------------------ the panes

    @Test
    fun `the preview with no cases says EXACTLY the required sentence and carries no data block`() {
        val html = preview(emptyList())

        html shouldContain
            "no test cases — the preview renders a visualization's test-case fixtures; live data runs inside a dashboard"
        html shouldContain "data-viz-preview-empty"
        html shouldContain "Fixtures only"
        html shouldNotContain "id=\"viz-preview-data\""
        html shouldNotContain "data-viz-case-select"
    }

    @Test
    fun `the preview shows one case at a time behind a selector, and its names are text`() {
        val cases =
            listOf(
                VisualizationTabModel.CaseView("twelve months", listOf("rendered")),
                VisualizationTabModel.CaseView("<b>empty</b>", listOf("trace count = 0")),
            )
        val html = preview(cases)

        html shouldContain "data-viz-case-select"
        Regex("<section[^>]*data-viz-case-index=\"1\"[^>]*hidden=\"hidden\"").containsMatchIn(html) shouldBe true
        Regex("<section[^>]*data-viz-case-index=\"0\"[^>]*hidden").containsMatchIn(html) shouldBe false
        html shouldContain "id=\"viz-preview-data\""
        // A case name is TEXT: the markup arrives escaped, never as an element.
        html shouldContain "&lt;b&gt;empty&lt;/b&gt;"
        html shouldNotContain "<b>empty</b>"
    }

    @Test
    fun `a single case renders no selector`() {
        val html = preview(listOf(VisualizationTabModel.CaseView("only", emptyList())))

        html shouldNotContain "data-viz-case-select"
        html shouldContain "data-viz-case-index=\"0\""
    }

    @Test
    fun `the evidence pane states the 100-run cap, and its empty state says how runs arrive`() {
        val html =
            engine.process(
                VisualizationTabModel.EVIDENCE_VIEW,
                webContext("/partials").apply {
                    setVariable("visualizationId", vizId)
                    setVariable("runsCap", VisualizationTabModel.RUNS_CAP)
                    setVariable("runs", emptyList<VisualizationTabModel.RunRow>())
                },
            )

        html shouldContain "the newest 100 runs at most"
        html shouldContain "data-viz-evidence-empty"
    }

    @Test
    fun `the evidence rows open the run detail into its own region`() {
        val run = runRow()
        val html =
            engine.process(
                VisualizationTabModel.EVIDENCE_VIEW,
                webContext("/partials").apply {
                    setVariable("visualizationId", vizId)
                    setVariable("runsCap", VisualizationTabModel.RUNS_CAP)
                    setVariable("runs", listOf(run))
                },
            )

        html shouldContain "data-run-row=\"${run.runId}\""
        html shouldContain "hx-get=\"/partials/visualizations/$vizId/evidence/${run.runId}\""
        html shouldContain "hx-target=\"#viz-evidence-run\""
        html shouldContain ">passed<"
    }

    @Test
    fun `a run with a stored screenshot shows the existing route's image, one without says no screenshot`() {
        val run = runRow()
        val with = evidenceRun(run, hasScreenshot = true)
        with shouldContain "src=\"/api/v1/visualizations/$vizId/tests/runs/${run.runId}/screenshot\""
        with shouldContain "data-viz-screenshot"
        with shouldNotContain "data-viz-no-screenshot"

        val without = evidenceRun(run, hasScreenshot = false)
        without shouldContain ">no screenshot<"
        without shouldNotContain "<img"
    }

    @Test
    fun `the run's stored JSON is text, never markup`() {
        val html = evidenceRun(runRow(), hasScreenshot = false, casesJson = "[{\"name\":\"</pre><script>x()</script>\"}]")

        html shouldContain "&lt;/pre&gt;&lt;script&gt;"
        html shouldNotContain "<script>x()"
    }

    @Test
    fun `used-by with no pin says exactly so, and a pin links the dashboard's workspace un-boosted`() {
        val empty = usedBy(emptyList())
        empty shouldContain ">no dashboard pins this visualization<"

        val dashboardId = UUID.randomUUID()
        val html =
            usedBy(
                listOf(
                    VisualizationTabModel.UsedByRow("acme/dashboards/revenue", 4, dashboardId, listOf(2, 3)),
                    VisualizationTabModel.UsedByRow("acme/dashboards/gone", 1, null, listOf(3)),
                ),
            )
        html shouldContain "href=\"/dashboards/$dashboardId?version=4\""
        html shouldContain ">acme/dashboards/revenue@4<"
        html shouldContain "pins v2, v3"
        // The unresolved pinner renders unlinked.
        html shouldContain "<span class=\"u-mono\">acme/dashboards/gone@1</span>"
        html shouldNotContain "data-viz-used-by-empty"
    }

    /** #422 — created/released relative in the cell, the absolute UTC stamp on `title` (ui-screens §3.7, the keys rule). */
    @Test
    fun `the versions pane shows created and released relative with the UTC stamp on hover`() {
        val html = versions(canAct = true)
        html shouldNotContain Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d""")
        html shouldContain "<span title=\"2026-10-01 09:00 UTC\">3 days ago</span>"
        // A released row shows its release the same way; an unreleased one the em dash (never a title).
        html shouldContain "<span title=\"2026-10-01 10:00 UTC\">2 days ago</span>"
        html shouldContain "<span>—</span>"
    }

    @Test
    fun `the versions pane offers the export to every role and the verbs only to the permissions that admit them`() {
        val author = versions(canAct = true)
        for (verb in listOf(
            "visualization-release",
            "visualization-purge-draft",
            "visualization-discard",
            "visualization-restore",
            "visualization-purge-version",
            "visualization-switch",
            "visualization-purge-entity",
        )) {
            author shouldContain "data-verb=\"$verb\""
        }
        author shouldContain "href=\"/api/v1/visualizations/$vizId/export\""
        author shouldContain "/partials/visualizations/$vizId/lifecycle/release?from=versions"
        // A discarded version is not a link: it serves nothing.
        author shouldNotContain "href=\"/visualizations/$vizId?version=1&amp;tab=preview\""

        val viewer = versions(canAct = false)
        viewer shouldNotContain "data-verb="
        viewer shouldContain "data-viz-export"
    }

    // ------------------------------------------------------------------ the dialogs

    @Test
    fun `a refused release lists every refusal with its code and draws no form`() {
        val dlg =
            release(
                pin = VisualizationLifecycleDialogModel.PinView("acme/templates/t", 2, PipelineVersionStatus.DISCARDED),
                refusals =
                    listOf(
                        VisualizationLifecycleDialogModel.Refusal("visualization.release.tests_missing", "No test cases."),
                        VisualizationLifecycleDialogModel.Refusal("visualization.release.dependency_not_released", "Pin."),
                    ),
            )
        val html = dialog("visualization-lifecycle-release", dlg)

        html shouldContain "data-refusal-code=\"visualization.release.tests_missing\""
        html shouldContain "data-refusal-code=\"visualization.release.dependency_not_released\""
        html shouldNotContain "<form"
        html shouldNotContain "data-verb=\"visualization-release-confirm\""
    }

    @Test
    fun `a releasable draft with a DRAFT transform carries the ONE consent, checked, and from`() {
        val dlg =
            release(
                pin = VisualizationLifecycleDialogModel.PinView("acme/templates/t", 2, PipelineVersionStatus.DRAFT),
                refusals = emptyList(),
            )
        val html = dialog("visualization-lifecycle-release", dlg)

        html shouldContain "hx-post=\"/partials/visualizations/$vizId/lifecycle/release\""
        html shouldContain "name=\"release_pinned_templates\""
        html shouldContain "data-consent-input"
        Regex("name=\"release_pinned_templates\"[^>]*checked").containsMatchIn(html) shouldBe true
        html shouldContain "name=\"from\" value=\"versions\""
        html shouldContain "data-verb=\"visualization-release-confirm\""
    }

    @Test
    fun `a released transform pin needs no consent`() {
        val dlg =
            release(
                pin = VisualizationLifecycleDialogModel.PinView("acme/templates/t", 2, PipelineVersionStatus.RELEASED),
                refusals = emptyList(),
            )
        val html = dialog("visualization-lifecycle-release", dlg).replace(Regex("<!--[\\s\\S]*?-->"), "")

        html shouldNotContain "release_pinned_templates"
        html shouldContain "data-release-pins"
    }

    @Test
    fun `the confirm buttons render only for a role that may author`() {
        val dlg = release(pin = null, refusals = emptyList())
        val promoter =
            engine.process(
                "partials/visualization-lifecycle-release",
                webContext("/partials").withRoles(canAuthor = false).apply {
                    setVariable("dlg", dlg)
                    setVariable("from", "versions")
                },
            )
        promoter shouldNotContain "data-verb=\"visualization-release-confirm\""
    }

    @Test
    fun `the purge dialogs ask for the typed confirm, and a pinned version refuses with its pinners`() {
        val purge = VisualizationLifecycleDialogModel.PurgeDialog(vizId, "acme/charts/revenue", 4, "v4", emptyList(), false)
        val html = dialog("visualization-lifecycle-purge-version", purge)
        html shouldContain "data-confirm-expect=\"v4\""
        html shouldContain "data-typed-confirm"
        html shouldContain "name=\"from\" value=\"versions\""

        val pinned = purge.copy(pinnedBy = listOf("acme/dashboards/revenue@2"))
        val refused = dialog("visualization-lifecycle-purge-draft", pinned)
        refused shouldContain "acme/dashboards/revenue@2"
        refused shouldNotContain "data-typed-confirm"
    }

    @Test
    fun `the purge-entity dialog types the NAME, and a refusal draws no form`() {
        val ok = VisualizationLifecycleDialogModel.PurgeEntityDialog(vizId, "acme/charts/revenue", "acme/charts/revenue", null, emptyList())
        val html = dialog("visualization-lifecycle-purge-entity", ok)
        html shouldContain "data-confirm-expect=\"acme/charts/revenue\""
        html shouldContain "data-verb=\"visualization-purge-entity-confirm\""

        val refused =
            ok.copy(refusal = VisualizationLifecycleDialogModel.Refusal("visualization.version.last_release", "Discard is per version."))
        val refusedHtml = dialog("visualization-lifecycle-purge-entity", refused)
        refusedHtml shouldContain "Discard is per version."
        refusedHtml shouldNotContain "<form"
    }

    @Test
    fun `the switch dialog checks the current version and disables an ineligible one`() {
        val dlg =
            VisualizationLifecycleDialogModel.SwitchDialog(
                vizId,
                "acme/charts/revenue",
                2,
                listOf(
                    VisualizationLifecycleDialogModel.SwitchOption(3, PipelineVersionStatus.DRAFT, isCurrent = false, eligible = false),
                    VisualizationLifecycleDialogModel.SwitchOption(2, PipelineVersionStatus.RELEASED, isCurrent = true, eligible = true),
                ),
            )
        val html =
            engine.process(
                "partials/visualization-lifecycle-switch",
                webContext("/partials").apply {
                    setVariable("dlg", dlg)
                    setVariable("from", "versions")
                    setVariable("preselect", null)
                },
            )

        Regex("value=\"2\"[^>]*checked").containsMatchIn(html) shouldBe true
        Regex("value=\"3\"[^>]*disabled").containsMatchIn(html) shouldBe true
        html shouldContain "not eligible for the pointer on this deployment"
    }

    @Test
    fun `discard says where the pointer falls back, and restore says whether it moves`() {
        val discard =
            VisualizationLifecycleDialogModel.DiscardDialog(vizId, "acme/charts/revenue", 3, isCurrent = true, fallback = "v2", emptyList())
        val discardHtml = dialog("visualization-lifecycle-discard", discard)
        discardHtml shouldContain "v2"
        discardHtml shouldContain "data-verb=\"visualization-discard-confirm\""
        discardHtml shouldContain "name=\"from\" value=\"versions\""

        val restore = VisualizationLifecycleDialogModel.RestoreDialog(vizId, "acme/charts/revenue", 1, 2, movesPointer = false)
        val restoreHtml = dialog("visualization-lifecycle-restore", restore)
        restoreHtml shouldContain "data-verb=\"visualization-restore-confirm\""
        restoreHtml shouldContain "hx-post=\"/partials/visualizations/$vizId/lifecycle/restore?version=1\""
    }

    // ------------------------------------------------------------------ helpers

    private fun level(
        prefix: String,
        folders: List<VisualizationBrowseModel.FolderView>,
        leaves: List<VisualizationBrowseModel.LeafView>,
    ): String =
        engine.process(
            VisualizationBrowseModel.LEVEL_VIEW,
            webContext("/visualizations").apply { levelVariables(prefix, folders, leaves) },
        )

    private fun WebContext.levelVariables(
        prefix: String,
        folders: List<VisualizationBrowseModel.FolderView>,
        leaves: List<VisualizationBrowseModel.LeafView>,
    ) {
        setVariable("prefix", prefix)
        setVariable("scope", VisualizationBrowseModel.SCOPE_NAV)
        setVariable("levelId", VisualizationBrowseModel.levelId(prefix))
        setVariable("folders", folders)
        setVariable("visualizations", leaves)
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", leaves.size)
        setVariable("lensUnavailable", null)
    }

    private fun list(
        scope: String,
        rootId: String,
        q: String,
        rows: List<VisualizationBrowseModel.LeafView>,
    ): String =
        engine.process(
            VisualizationBrowseModel.SEARCH_VIEW,
            webContext("/visualizations").apply {
                setVariable("scope", scope)
                setVariable("rootId", rootId)
                setVariable("q", q)
                setVariable("lensUnavailable", null)
                setVariable("offset", 0)
                setVariable("visualizations", rows)
                setVariable("hasMore", false)
                setVariable("total", rows.size)
            },
        )

    private fun WebContext.workspaceVariables(activeTab: String) {
        setVariable("visualizationId", vizId.toString())
        setVariable("visualizationName", "acme/charts/revenue")
        setVariable("displayName", "Revenue")
        setVariable("navCurrentPath", "acme/charts/revenue")
        setVariable("activeTab", activeTab)
        setVariable("currentVersion", 3)
        setVariable("draftVersion", null)
    }

    private fun workspace(
        bundle: String,
        activeTab: String,
    ): String =
        engine.process(
            VisualizationUiController.WORKSPACE_VIEW,
            webContext("/visualizations/$vizId").apply {
                workspaceVariables(activeTab)
                setVariable("bundle", bundle)
                setVariable("hasSelected", true)
                setVariable("viewedVersion", 3)
                setVariable("viewedLabel", "v3 · released · current")
                setVariable("viewedIsDraft", false)
                setVariable(
                    "versions",
                    listOf(
                        VisualizationUiController.VersionRow(3, "RELEASED", isCurrent = true, isSelected = true),
                        VisualizationUiController.VersionRow(2, "RELEASED", isCurrent = false, isSelected = false),
                    ),
                )
            },
        )

    private fun preview(cases: List<VisualizationTabModel.CaseView>): String =
        engine.process(
            VisualizationTabModel.PREVIEW_VIEW,
            webContext("/partials").apply {
                setVariable("version", 2)
                setVariable("cases", cases)
                setVariable("previewJson", "{}")
            },
        )

    private fun runRow() = VisualizationTabModel.RunRow(UUID.randomUUID(), 3, "GREEN", "2026-10-01T09:00:00Z", null, "passed")

    private fun evidenceRun(
        run: VisualizationTabModel.RunRow,
        hasScreenshot: Boolean,
        casesJson: String? = "[]",
    ): String =
        engine.process(
            VisualizationTabModel.EVIDENCE_RUN_VIEW,
            webContext("/partials").apply {
                setVariable("visualizationId", vizId)
                setVariable("run", run)
                setVariable("hasScreenshot", hasScreenshot)
                setVariable("casesJson", casesJson)
                setVariable("environmentJson", null)
                setVariable("mechanicalJson", null)
            },
        )

    private fun usedBy(rows: List<VisualizationTabModel.UsedByRow>): String =
        engine.process(
            VisualizationTabModel.USED_BY_VIEW,
            webContext("/partials").apply { setVariable("usedBy", rows) },
        )

    private fun versions(canAct: Boolean): String =
        engine.process(
            VisualizationTabModel.VERSIONS_VIEW,
            webContext("/partials").apply {
                setVariable("visualizationId", vizId)
                setVariable("canRelease", canAct)
                setVariable("canManageVersions", canAct)
                setVariable("canSwitch", canAct)
                setVariable("canDelete", canAct)
                setVariable("soleDraft", false)
                setVariable(
                    "versions",
                    listOf(
                        versionRow(4, "DRAFT"),
                        versionRow(3, "RELEASED", isCurrent = true),
                        versionRow(2, "RELEASED"),
                        versionRow(1, "DISCARDED"),
                    ),
                )
            },
        )

    private fun versionRow(
        version: Int,
        status: String,
        isCurrent: Boolean = false,
    ) = VisualizationTabModel.VersionRow(
        version = version,
        status = status,
        createdAt = VisualizationUiFixtures.AT,
        createdAgo = "3 days ago",
        createdAbsolute = "2026-10-01 09:00 UTC",
        releasedAt = if (status == "RELEASED") VisualizationUiFixtures.AT.plusSeconds(3_600) else null,
        releasedAgo = if (status == "RELEASED") "2 days ago" else null,
        releasedAbsolute = if (status == "RELEASED") "2026-10-01 10:00 UTC" else null,
        isCurrent = isCurrent,
        isDraft = status == "DRAFT",
        isDiscarded = status == "DISCARDED",
        isViewed = isCurrent,
    )

    private fun release(
        pin: VisualizationLifecycleDialogModel.PinView?,
        refusals: List<VisualizationLifecycleDialogModel.Refusal>,
    ) = VisualizationLifecycleDialogModel.ReleaseDialog(
        id = vizId,
        name = "acme/charts/revenue",
        version = 4,
        bodyHash = "h",
        updatedBy = "Ada",
        updatedAgo = "2 hours ago",
        caseCount = 1,
        pin = pin,
        refusals = refusals,
    )

    private fun dialog(
        template: String,
        dlg: Any,
    ): String =
        engine.process(
            "partials/$template",
            webContext("/partials").apply {
                setVariable("dlg", dlg)
                setVariable("from", "versions")
            },
        )

    private fun webContext(path: String): WebContext {
        val request = MockHttpServletRequest()
        request.requestURI = path
        return WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(request, MockHttpServletResponse()),
        ).withRoles().apply {
            setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
            setVariable("workspaceHeaderFragment", "")
            setVariable("workspaceOptions", emptyList<Any>())
            setVariable("activeWorkspace", "acme")
            setVariable("activeTheme", "saas")
            setVariable("authenticated", true)
            setVariable("currentPath", path)
            setVariable("reportProblemUrl", REPORT_PROBLEM_URL)
        }
    }
}
