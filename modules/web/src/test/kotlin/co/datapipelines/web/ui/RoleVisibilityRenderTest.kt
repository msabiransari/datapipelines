package co.datapipelines.web.ui

import co.datapipelines.web.pipelines.PromotionService
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 114 §E.2 — the role-visibility guard for the screens whose fixtures are small enough to build
 * here, plus the two INVENTORY rules that no per-screen test can state.
 *
 * The per-screen ladders live beside the fixtures they need: `PipelineExplorerRenderTest`,
 * `TemplateExplorerRenderTest`, `DatasourcesTemplateRenderTest`, `ApiConsoleRenderTest`,
 * `ShellRenderTest`. What is here is what is CROSS-CUTTING:
 *
 *  1. **Every `data-verb` control sits inside a role guard.** A new verb added without one
 *     would render for a viewer and 403 on click, which is the exact defect this round
 *     removes — and it would pass every per-screen test, because none of them knows the new
 *     verb exists. This arm is the one that catches the SEVENTH screen.
 *  2. **The inventory has not shrunk.** A guard that hides everything passes arm 1 perfectly.
 */
class RoleVisibilityRenderTest {
    // ------------------------------------------------------------------ the editors

    /**
     * §A — a viewer's editor is READ-ONLY: it loads (reading a pipeline is a viewer's right),
     * the mutating verbs are absent, and one quiet `.app-note` line says why. No toast:
     * nothing failed. Execute STAYS — D-R3 puts execution on the viewer row, and the whole
     * point of a viewer is that they can run what they can read.
     */
    @Test
    fun `a viewer's pipeline editor is read-only, says so once, and keeps Execute`() {
        val viewer =
            render("pipelines/editor") {
                editorModel()
                withRoles(RoleModel.NONE.copy(canRead = true, canExecute = true))
            }

        viewer shouldNotContain "data-verb=\"pipeline-release\""
        viewer shouldNotContain "data-verb=\"pipeline-purge\""
        viewer shouldContain "data-role-note=\"read-only\""
        viewer shouldContain "Read-only — you are a viewer in"
        viewer shouldContain "data-verb=\"pipeline-execute\""
        viewer shouldContain "data-verb=\"execution-cancel\""
        // A quiet line, not a greyed button with a tooltip: nothing in the editor's action
        // area is rendered-but-disabled for a role reason (the layout's own empty #toast stack
        // is chrome and is not what "no toast" means here — nothing is PUSHED into it).
        viewer shouldNotContain "ds-toast-danger"
        viewer shouldNotContain "data-verb=\"pipeline-release\" disabled"
    }

    @Test
    fun `an author's pipeline editor loses Release and keeps Purge draft, with no read-only line`() {
        val author =
            render("pipelines/editor") {
                editorModel()
                withRoles(canPromote = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "author")
            }

        author shouldNotContain "data-verb=\"pipeline-release\""
        author shouldContain "data-verb=\"pipeline-purge\""
        author shouldNotContain "data-role-note=\"read-only\""
    }

    @Test
    fun `a promoter's pipeline editor keeps Release and loses Purge draft`() {
        val promoter =
            render("pipelines/editor") {
                editorModel()
                withRoles(canAuthor = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "promoter")
            }

        promoter shouldContain "data-verb=\"pipeline-release\""
        promoter shouldNotContain "data-verb=\"pipeline-purge\""
        promoter shouldNotContain "data-role-note=\"read-only\""
    }

    // ------------------------------------------------------------------ promotion

    /**
     * §B — `canPromote` in the SOURCE workspace. `PromotionUiController` reads
     * `principal.requireWorkspace()` (the ACTIVE workspace) and `ScopeInterceptor` judges
     * `PROMOTE_VERSION` against that same context; the target is only a base URL and no
     * target-side role is consulted anywhere.
     *
     * The PLAN stays readable for everyone — hiding it would leave an author unable to see
     * what is waiting to go out — and only the button that SENDS it is withheld, with a line
     * naming who to ask. That is the one place this round adds an explanation rather than an
     * absence, because a promotion screen with no promote button and no words reads as broken.
     */
    @Test
    fun `promotion renders the plan for everyone and the Promote button only for a promoter`() {
        val promoter = render("promotion/index") { promotionModel() }
        promoter shouldContain "data-verb=\"promote\""
        promoter shouldNotContain "data-role-note=\"promote\""

        val author =
            render("promotion/index") {
                promotionModel()
                withRoles(canPromote = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "author")
            }
        author shouldNotContain "data-verb=\"promote\""
        author shouldContain "data-role-note=\"promote\""
        // The plan itself is a READ and stays.
        author shouldContain "nyc/mobility/revenue_by_borough"
    }

    // ------------------------------------------------------------------ workspaces

    /**
     * §C.3(b) — deactivate/reactivate/delete are INSTANCE verbs (`MANAGE_INSTANCE_WORKSPACES`,
     * super admin), never a workspace admin's, and a deactivated workspace is listed ONLY to a
     * super admin: to a member it does not exist at all (§11A.3, so deactivation is not a
     * signal anybody can read).
     */
    @Test
    fun `only a super admin sees deactivate, reactivate and delete`() {
        val superAdmin = render("workspaces/index") { workspacesModel() }
        superAdmin shouldContain "data-verb=\"workspace-deactivate\""
        superAdmin shouldContain "data-verb=\"workspace-delete\""
        superAdmin shouldContain "data-verb=\"workspace-create\""

        val workspaceAdmin =
            render("workspaces/index") {
                workspacesModel()
                setVariable("canCreate", false)
                withRoles(isSuperAdmin = false, roleLabel = "admin")
            }
        workspaceAdmin shouldNotContain "data-verb=\"workspace-deactivate\""
        workspaceAdmin shouldNotContain "data-verb=\"workspace-reactivate\""
        workspaceAdmin shouldNotContain "data-verb=\"workspace-delete\""
        workspaceAdmin shouldNotContain "data-verb=\"workspace-create\""
        // …and still administers the members of the workspaces they administer.
        workspaceAdmin shouldContain "data-verb=\"member-flags\""
        workspaceAdmin shouldContain "data-verb=\"member-add\""
    }

    /** A deactivated row shows the badge and the Reactivate verb, and never Switch. */
    @Test
    fun `a deactivated workspace row offers Reactivate and no Switch`() {
        val html =
            render("workspaces/index") {
                workspacesModel(active = false)
            }

        html shouldContain "inactive"
        html shouldContain "data-verb=\"workspace-reactivate\""
        html shouldNotContain "data-verb=\"workspace-switch\""
        html shouldNotContain "data-verb=\"workspace-deactivate\""
    }

    /** §C.1 — three checkboxes per member row, plus the add form's three, all named for the binder. */
    @Test
    fun `the members table renders the three role checkboxes and the 113 pending-invitation rows`() {
        val html = render("workspaces/index") { workspacesModel() }

        html shouldContain "name=\"author\" value=\"true\" data-flag=\"author\""
        html shouldContain "name=\"promoter\" value=\"true\" data-flag=\"promoter\""
        html shouldContain "name=\"admin\" value=\"true\" data-flag=\"admin\""
        html shouldContain "/workspaces/acme/members/"
        // 113 merged with this round: the anchor became the ghost rows, revoke included.
        html shouldContain "data-invitation=\"pending@acme.test\""
        html shouldContain "data-verb=\"invitation-revoke\""
        html shouldContain "/workspaces/acme/invitations/revoke"
    }

    /**
     * §C.3(a) — the no-workspace page. It is NOT an error page: nothing failed, and `error/403`
     * would tell someone their credential is wrong when their membership is simply absent.
     *
     * Reachable since the 113/114 merge: `ScopeMatrix` lets a session through `WORKSPACES_READ`
     * with no context (`RoleMatrixTest`, auth.md §11A.1). The MARKUP is pinned here.
     */
    @Test
    fun `the no-workspace page explains, points at an admin, and shows create only to a super admin`() {
        val superAdmin =
            render("workspaces/none") {
                chrome()
                setVariable("canCreate", true)
            }
        superAdmin shouldContain "not a member of any active workspace"
        superAdmin shouldContain "data-verb=\"workspace-create\""

        val member =
            render("workspaces/none") {
                chrome()
                setVariable("canCreate", false)
            }
        member shouldNotContain "data-verb=\"workspace-create\""
        member shouldContain "Ask an administrator"
        // It must not read as a REFUSAL — a 403 page names the wrong problem entirely.
        member shouldNotContain "You don&#39;t have permission"
    }

    // ------------------------------------------------------------------ degrade, never throw

    /**
     * **A fragment rendered with NO role attributes renders as a viewer's — it never throws and
     * never leaks a verb.**
     *
     * Two things make this worth pinning. Thymeleaf's SpEL raises on a null operand of `and`/`or`
     * in some shapes (a bare `${a and b}` with `a` absent did, in this round's first render run),
     * so a forgotten stamp could turn a whole partial into a 500; and a null read as "true" would
     * leak every verb to a viewer. Writing every role operand `== true` makes the answer FALSE in
     * both directions: a missing role fails closed and quiet.
     *
     * The stamp itself now lives in `fillDetail`, where all three callers of the detail fragment
     * get it — this arm is the second line, not the first.
     */
    @Test
    fun `a detail fragment with no role attributes renders as a viewer's, not as an error`() {
        listOf("partials/pipeline-detail", "partials/pipeline-versions").forEach { view ->
            val html = engine().process(view, bare().apply { pipelineDetailModel() })
            html shouldNotContain "data-verb="
        }
        listOf("partials/template-detail", "partials/template-versions").forEach { view ->
            val html = engine().process(view, bare().apply { templateDetailModel() })
            html shouldNotContain "data-verb="
        }
    }

    // ------------------------------------------------------------------ the inventory

    /**
     * **The arm that catches the screen nobody wrote a test for.** Every `data-verb` control
     * must sit inside a role guard — its own `th:if`/`th:unless`, or an enclosing one within
     * the same fragment. A verb added without one renders for a viewer and 403s on click.
     *
     * The check is textual and deliberately so: it reads the TEMPLATE, not a render, because a
     * render can only prove what its fixture happened to set. The role vocabulary is the seven
     * attribute names [RoleModel.stamp] writes, and nothing else counts as a guard.
     */
    @Test
    fun `every data-verb control is inside a role guard`() {
        val unguarded = mutableListOf<String>()
        templates().forEach { file ->
            val text = Files.readString(file)
            if (!text.contains("data-verb=")) return@forEach
            if (file.fileName.toString() in ROUTE_GUARDED) return@forEach
            val guardsInFile = ROLE_ATTRS.any { text.contains(it) }
            text.lines().forEachIndexed { index, line ->
                if (!line.contains("data-verb=")) return@forEachIndexed
                val ownGuard = ROLE_ATTRS.any { line.contains(it) }
                // A control whose own line carries no role attribute is acceptable ONLY when
                // its file establishes one — the enclosing `th:block`/`th:if` idiom this round
                // used for the version menus and the datasource action column.
                if (!ownGuard && !guardsInFile) {
                    unguarded += "${file.fileName}:${index + 1}: ${line.trim().take(80)}"
                }
            }
        }
        unguarded.shouldBeEmpty()
    }

    /** Non-vacuity for the exemptions: a stale entry exempts nothing and must fail here. */
    @Test
    fun `every route-guarded exemption is a real fragment that still carries a verb`() {
        val carrying =
            templates()
                .filter { Files.readString(it).contains("data-verb=") }
                .map { it.fileName.toString() }
                .toSet()

        (ROUTE_GUARDED.keys - carrying).shouldBeEmpty()
    }

    /**
     * Non-vacuity for the arm above, and the number the handback reports: hiding every verb
     * would satisfy "all guarded" perfectly. The floor is the inventory as shipped, so a verb
     * DELETED to make a guard pass fails here instead.
     */
    @Test
    fun `the data-verb inventory has not shrunk`() {
        val controls = templates().sumOf { file -> Files.readString(file).split("data-verb=").size - 1 }
        val distinct =
            templates()
                .flatMap { file -> VERB_NAME.findAll(Files.readString(file)).map { it.groupValues[1] }.toList() }
                .toSet()

        controls shouldBeGreaterThanOrEqual SHIPPED_CONTROLS
        distinct.size shouldBeGreaterThanOrEqual SHIPPED_VERBS
        // The six families the §B table names, each present by at least one control.
        listOf("pipeline-release", "template-release", "datasource-register", "key-create", "member-flags", "promote")
            .forEach { verb -> (verb in distinct) shouldBe true }
    }

    // ------------------------------------------------------------------ fixtures

    /** A context with NO role attributes at all — the forgotten-stamp case. */
    private fun bare(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )

    /**
     * The lifecycle FLAGS all true, so every verb would render if the role allowed it — the
     * arm is worthless against a fixture whose state already hides them.
     */
    private fun WebContext.pipelineDetailModel() {
        setVariable("pipelineId", java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"))
        setVariable("pipeline", null)
        setVariable("releasableVersion", 2)
        setVariable("draftVersion", 2)
        setVariable("canDelete", true)
        setVariable("canDiscardCurrent", true)
        setVariable("canPurgeDraftInHeader", true)
        setVariable("canSwitchHeader", true)
        setVariable("versions", emptyList<Any>())
    }

    private fun WebContext.templateDetailModel() {
        setVariable("templateId", "demo/top_carrier.sql")
        setVariable("template", null)
        setVariable("releasableVersion", 2)
        setVariable("draftVersion", 2)
        setVariable("currentReleaseVersion", 1)
        setVariable("canDelete", true)
        setVariable("canDiscardCurrent", true)
        setVariable("canPurgeDraftInHeader", true)
        setVariable("versions", emptyList<Any>())
    }

    private fun WebContext.chrome() {
        setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/workspaces")
        setVariable("navCounts", NavCounts.Counts(1, 1))
    }

    private fun WebContext.editorModel() {
        chrome()
        setVariable("currentPath", "/pipelines")
        setVariable("pipelineId", "00000000-0000-0000-0000-000000000001")
        setVariable("pipelineName", "revenue_by_borough")
        setVariable("hasDraft", true)
        setVariable("draftVersion", 2)
        setVariable("releasedVersion", 1)
        setVariable("stagingEngine", "H2")
        setVariable("pipelineJson", "{}")
    }

    private fun WebContext.promotionModel() {
        chrome()
        setVariable("currentPath", "/promotion")
        setVariable("hasTarget", true)
        setVariable("targetBaseUrl", "https://prod.example")
        setVariable("plan", PROMOTION_PLAN)
    }

    private fun WebContext.workspacesModel(active: Boolean = true) {
        chrome()
        setVariable("canCreate", true)
        setVariable(
            "own",
            listOf(
                WorkspaceRowView(
                    name = "acme",
                    roleLabel = "admin",
                    active = active,
                    isCurrent = active,
                    canAdmin = true,
                ),
            ),
        )
        setVariable(
            "managed",
            mapOf(
                "acme" to
                    listOf(
                        MemberRowView(
                            userId = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                            email = "alice@acme.test",
                            displayName = "Alice",
                            flags = co.datapipelines.auth.MembershipFlags(author = true, admin = true),
                            roleLabel = "admin",
                        ),
                    ),
            ),
        )
        setVariable(
            "pending",
            mapOf(
                "acme" to
                    listOf(
                        InvitationRowView(
                            email = "pending@acme.test",
                            roleLabel = "author",
                            invitedAt = java.time.Instant.parse("2026-09-10T00:00:00Z"),
                        ),
                    ),
            ),
        )
    }

    private fun render(
        view: String,
        fill: WebContext.() -> Unit,
    ): String = COMMENT.replace(htmlWithComments(view, fill), "")

    private fun htmlWithComments(
        view: String,
        fill: WebContext.() -> Unit,
    ): String = engine().process(view, context().apply(fill))

    private fun context(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).withRoles()

    private fun engine(): SpringTemplateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private fun templates(): List<Path> =
        Files.walk(Paths.get("src/main/resources/templates")).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".html") }
                // The marketing site is public, anonymous and role-free (fenced out of this
                // round entirely) — it has no memberships to guard against.
                .filter { !it.toString().contains("/site/") }
                .toList()
        }

    private companion object {
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val VERB_NAME = Regex("data-verb=\"([a-z-]+)\"")

        /**
         * What counts as a role guard: the six booleans [RoleModel.stamp] writes, plus the two
         * DERIVED attributes whose definition is a role AND a state — `canCancel`
         * (`RUNNING && canExecute`, ExecutionDetailController) and `canCreate` (`isSuperAdmin`,
         * WorkspacesUiController). Both are computed from the helper's own inputs and are
         * named here so a screen may use the sharper one where it exists, rather than
         * re-deriving the state beside the role in the template.
         */
        val ROLE_ATTRS =
            listOf(
                "canRead",
                "canExecute",
                "canAuthor",
                "canPromote",
                "canAdminWorkspace",
                "isSuperAdmin",
                "canCancel",
                "canCreate",
            )

        /**
         * The files whose guard is their ROUTE, not their markup: a dialog fragment is fetched
         * by a URL whose handler carries the `@RequiredScope` for the very operation its button
         * posts (`RELEASE_VERSION` for the release dialog, `MUTATE_DATASOURCES`… ). A role that
         * may not perform the verb cannot obtain the markup at all, so a `th:if` inside it
         * would be a second copy of a check that has already been made — and the launcher that
         * opens the dialog is itself guarded, in a file this arm does check.
         *
         * Each entry must still exist and still carry a `data-verb`, or the exemption is
         * certifying nothing (the non-vacuity rule the house's other allowlists carry).
         */
        val ROUTE_GUARDED: Map<String, String> =
            mapOf(
                "pipeline-lifecycle-release.html" to "fetched by GET /partials/pipelines/{id}/lifecycle/release — RELEASE_VERSION",
                "pipeline-lifecycle-discard.html" to "GET .../lifecycle/discard — MUTATE_PIPELINES_TEMPLATES",
                "pipeline-lifecycle-purge.html" to "GET .../lifecycle/purge — MUTATE_PIPELINES_TEMPLATES",
                "pipeline-lifecycle-purge-entity.html" to "GET .../lifecycle/purge-entity — MUTATE_PIPELINES_TEMPLATES",
                "pipeline-lifecycle-restore.html" to "GET .../lifecycle/restore — MUTATE_PIPELINES_TEMPLATES",
                "pipeline-lifecycle-switch.html" to "GET .../lifecycle/switch — SWITCH_SERVED_VERSION",
                "template-lifecycle-release.html" to "GET /partials/templates/lifecycle/release — RELEASE_VERSION",
                "template-lifecycle-discard.html" to "GET .../lifecycle/discard — MUTATE_PIPELINES_TEMPLATES",
                "template-lifecycle-purge.html" to "GET .../lifecycle/purge — MUTATE_PIPELINES_TEMPLATES",
                "template-lifecycle-purge-entity.html" to "GET .../lifecycle/purge-entity — MUTATE_PIPELINES_TEMPLATES",
                "template-lifecycle-restore.html" to "GET .../lifecycle/restore — MUTATE_PIPELINES_TEMPLATES",
                "datasource-edit.html" to "GET /partials/datasources/{name}/edit — MUTATE_WORKSPACE_DATASOURCES",
                "datasource-delete.html" to "GET /partials/datasources/{name}/delete — MUTATE_WORKSPACE_DATASOURCES",
                "datasource-grants.html" to "GET /partials/datasources/{name}/grants — MANAGE_DATASOURCE_GRANTS (super admin)",
            )

        /** The inventory as 114 shipped it: 63 controls over 50 distinct verbs. */
        const val SHIPPED_CONTROLS = 63
        const val SHIPPED_VERBS = 50

        /** The promotion plan the screen reads (055, `PromotionService.Plan`) — the real type. */
        val PROMOTION_PLAN =
            PromotionService.Plan(
                targetBaseUrl = "https://prod.example",
                targetDeployment = "prod",
                targetAuthoringEnabled = false,
                workspace = "acme",
                promotable =
                    listOf(
                        PromotionService.Candidate(
                            name = "nyc/mobility/revenue_by_borough",
                            displayName = "Revenue by borough",
                            localVersion = 2,
                            targetVersion = 1,
                        ),
                    ),
                examined = 4,
            )
    }
}
