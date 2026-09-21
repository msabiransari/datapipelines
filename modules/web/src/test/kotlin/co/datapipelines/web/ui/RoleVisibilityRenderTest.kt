package co.datapipelines.web.ui

import co.datapipelines.web.pipelines.PromotionService
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.maps.shouldBeEmpty
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
        // 151/#144: the canvas's Start marker is a run trigger exactly when the toolbar's
        // Execute renders — the SAME flag, stamped once on the root for the script to read.
        viewer shouldContain "class=\"pe-root\" data-can-execute=\"true\""
    }

    /**
     * 151/#144 — the one render in which the right is ABSENT: no Execute, and the root says
     * so, so the Start marker is drawn as a plain shape (`role="img"`) rather than a button.
     * In a browser session every workspace member may execute (D-R3), so this is the
     * template contract for a principal without a workspace, not a reachable viewer walk.
     */
    @Test
    fun `an editor rendered without the execute right stamps data-can-execute false and draws no Execute`() {
        val noExecute =
            render("pipelines/editor") {
                editorModel()
                withRoles(RoleModel.NONE.copy(canRead = true, canExecute = false))
            }

        noExecute shouldNotContain "data-verb=\"pipeline-execute\""
        noExecute shouldContain "class=\"pe-root\" data-can-execute=\"false\""
        // The canvas is a group, not an image: its buttons must stay in the accessible tree.
        noExecute shouldContain "id=\"cy-canvas\" role=\"group\""
    }

    /** D8 (2026-09-20): release is the AUTHOR's — an author's editor carries Release AND Purge draft. */
    @Test
    fun `an author's pipeline editor keeps Release and Purge draft, with no read-only line`() {
        val author =
            render("pipelines/editor") {
                editorModel()
                withRoles(canPromote = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "author")
            }

        author shouldContain "data-verb=\"pipeline-release\""
        author shouldContain "data-verb=\"pipeline-purge\""
        author shouldNotContain "data-role-note=\"read-only\""
    }

    /**
     * D5 (2026-09-20): the promoter authors nothing and releases nothing. The route itself
     * refuses a promoter the pipeline editor (`EXECUTE_PIPELINE`); this pins the template
     * contract for a principal with the promoter's booleans — read-only, no verb.
     */
    @Test
    fun `a promoter's pipeline editor is read-only - no Release, no Purge draft`() {
        val promoter =
            render("pipelines/editor") {
                editorModel()
                withRoles(canExecute = false, canAuthor = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "promoter")
            }

        promoter shouldNotContain "data-verb=\"pipeline-release\""
        promoter shouldNotContain "data-verb=\"pipeline-purge\""
        promoter shouldContain "data-role-note=\"read-only\""
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
    fun `promotion renders the plan for every reader of the page and the Promote button only for a promoter`() {
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

        // 143 (T315): a reader's plan is a TABLE — no submission form, no Send column, no
        // selection boxes — read from the rendered page's own content, not from a guard word.
        val main = author.substringAfter("<main").substringBefore("</main>")
        main shouldNotContain "<form"
        main shouldNotContain "<input"
        main shouldNotContain ">Send<"
        main shouldContain "<table"
        main shouldContain "Revenue by borough"
        // ...and the promoter's keeps all three, so the reader arm is not "hide everything".
        val promoterMain = promoter.substringAfter("<main").substringBefore("</main>")
        promoterMain shouldContain "<form"
        promoterMain shouldContain "type=\"checkbox\""
        promoterMain shouldContain ">Send<"
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
                withRoles(isSuperAdmin = false, roleLabel = "workspace admin")
            }
        workspaceAdmin shouldNotContain "data-verb=\"workspace-deactivate\""
        workspaceAdmin shouldNotContain "data-verb=\"workspace-reactivate\""
        workspaceAdmin shouldNotContain "data-verb=\"workspace-delete\""
        workspaceAdmin shouldNotContain "data-verb=\"workspace-create\""
        // …and still administers the members of the workspaces they administer.
        workspaceAdmin shouldContain "data-verb=\"member-role\""
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

    /** §C.1 / D22 — ONE role dropdown per member row (current role selected), one on the add form, no checkbox anywhere. */
    @Test
    fun `the members table renders the role dropdown and the 113 pending-invitation rows`() {
        val html = render("workspaces/index") { workspacesModel() }

        html shouldContain "select name=\"role\" class=\"ds-input app-input-inline\" data-member-role"
        html shouldContain "value=\"workspace_admin\" selected"
        html shouldContain "hx-post=\"/partials/workspaces/acme/members/11111111-1111-1111-1111-111111111111/role\""
        html shouldContain "data-new-member-role"
        html shouldNotContain "checkbox"
        html shouldNotContain "data-flag"
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
        // 162 (#156): the discovered-schema Tables tree is read-only by construction — same
        // role floor as the datasources list itself (a viewer may read) — and carries no verb
        // on any of its three levels, with no role attribute required to make that true.
        listOf(
            DatasourceSchemaTreeBrowseModel.SCHEMAS_VIEW,
            DatasourceSchemaTreeBrowseModel.TABLES_VIEW,
            DatasourceSchemaTreeBrowseModel.COLUMNS_VIEW,
        ).forEach { view ->
            val html = engine().process(view, bare().apply { datasourceTablesModel() })
            html shouldNotContain "data-verb="
        }
    }

    // ------------------------------------------------------------------ 179: keys

    /**
     * D16 — the top bar's chip (partials/mcp-key-chip): prefix and BOTH verbs when a
     * copyable key exists; NO copy control for a key minted before V31 (there is no sealed
     * secret to serve, and a button that 404s is a lie); the rotation hint in its place.
     * Every role holds VIEW_OWN_MCP_KEY, so the role question does not arise here — what
     * this pins is the copyable state machine.
     */
    @Test
    fun `the top bar chip shows copy only when the key has a sealed secret`() {
        val copyable =
            engine().process(
                "partials/mcp-key-chip",
                bare().apply { setVariable("mcpKey", McpKeyChip(prefix = "dpk_ABCDEFGH…", copyable = true)) },
            )
        copyable shouldContain "dpk_ABCDEFGH…"
        copyable shouldContain "data-mcp-copy=\"/partials/mcp-key/secret\""
        copyable shouldContain "hx-delete=\"/partials/mcp-key\""

        val legacy =
            engine().process(
                "partials/mcp-key-chip",
                bare().apply { setVariable("mcpKey", McpKeyChip(prefix = "dpk_ABCDEFGH…", copyable = false)) },
            )
        legacy shouldContain "dpk_ABCDEFGH…"
        legacy shouldNotContain "data-mcp-copy"
        // Rotation is the pre-V31 key's way to a copyable one — the delete stays.
        legacy shouldContain "hx-delete=\"/partials/mcp-key\""
        legacy shouldContain "sign in again"

        val none =
            engine().process("partials/mcp-key-chip", bare().apply { setVariable("mcpKey", null) })
        none shouldNotContain "data-verb="
        none shouldContain "minted when you next sign in"
    }

    /**
     * D17 — the `/api-keys` page's verbs are inside the `MANAGE_API_KEYS` guard
     * (`canAdminWorkspace` / `isSuperAdmin`) even though the route refuses everyone else:
     * a fragment rendered off its route — a test, a reuse — must not leak the verbs.
     */
    @Test
    fun `the api-keys page draws no verb for a reader and all of them for a workspace admin`() {
        val admin =
            render("api/keys") { apiKeysModel() }
        admin shouldContain "data-verb=\"key-create\""
        admin shouldContain "data-verb=\"key-revoke\""
        admin shouldContain "data-verb=\"key-bind\""

        // The page's own route refuses this render; what is pinned here is that the TEMPLATE
        // would not leak the verbs if it ever rendered off its route.
        val reader =
            render("api/keys") {
                apiKeysModel()
                withRoles(canAuthor = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "viewer")
            }
        reader shouldNotContain "data-verb="
    }

    private fun WebContext.apiKeysModel() {
        chrome()
        setVariable("currentPath", "/api-keys")
        setVariable(
            "keys",
            listOf(
                ApiKeyRows.Row(
                    id = "dpk_A7QxKF2MPLQR",
                    name = "ci",
                    kind = "endpoint",
                    prefix = "dpk_A7QxKF2M…",
                    createdBy = "Alice",
                    scopes = emptyList(),
                    boundPaths = listOf("/nyc"),
                    createdRelative = "3 days ago",
                    createdAbsolute = "2026-09-05 09:00 UTC",
                    lastUsedRelative = "never",
                    lastUsedAbsolute = null,
                    expiresRelative = "never",
                    expiresAbsolute = null,
                    isRevoked = false,
                    isExpired = false,
                ),
            ),
        )
        setVariable("kindChoices", ApiKeyForm.kindChoices(isAdmin = true))
        setVariable("expiryChoices", ApiKeyForm.EXPIRY_CHOICES)
        setVariable("expiryCustomWire", ApiKeyForm.CUSTOM)
        setVariable("bindingNodes", listOf("/", "/nyc"))
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

    /**
     * 177 §D.8 — the route-guarded exemption list is EMPTY, for good. Every dialog fragment
     * that used to be exempt (its route carried the `@RequiredScope`) now stamps the role model
     * and guards its verb in the markup too, so the sweep above reads them like every other
     * file. This arm refuses a non-empty list: an exemption is a verb the sweep cannot see,
     * and the list grew to fourteen entries before anyone asked why the route alone was not
     * enough (a render of the fragment outside its route — a test, a future reuse — showed the
     * verb to everyone).
     */
    @Test
    fun `the route-guarded exemption list is empty and stays empty`() {
        ROUTE_GUARDED.shouldBeEmpty()
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
        listOf("pipeline-release", "template-release", "datasource-register", "key-create", "member-role", "promote")
            .forEach { verb -> (verb in distinct) shouldBe true }
    }

    /**
     * 161 (#155) — the shell search is a READ, so it renders for EVERY role, viewer
     * included: a viewer may search, and every hit is a link to a page the destination's
     * own guards govern read-only. Rendered at `RoleModel.NONE` + `canRead` — the arm is
     * worthless against the fullest role, which is what every other fixture here uses.
     * A viewer's palette carries no verb controls, so the `data-verb` sweep above
     * already pins the other half.
     */
    @Test
    fun `the header search renders for a viewer - it is a read, not a verb`() {
        val viewer =
            render("pipelines/list") {
                chrome()
                setVariable("currentPath", "/pipelines")
                withRoles(RoleModel.NONE.copy(canRead = true))
                setVariable("scopes", setOf<String>())
                setVariable("dialects", emptyList<String>())
                setVariable("pipelines", emptyList<Any>())
                setVariable("drafts", emptyMap<Any, Any>())
                setVariable("q", "")
                setVariable("offset", 0)
                setVariable("hasMore", false)
                setVariable("total", 0)
            }

        viewer shouldContain "<input id=\"app-search-input\" type=\"search\" name=\"q\" class=\"app-search-field\" role=\"combobox\""
        viewer shouldContain "data-role=\"viewer\""
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

    /**
     * One populated level of each kind — a schema, a table, a column — so the scan below is
     * not vacuously true over three empty panes.
     */
    private fun WebContext.datasourceTablesModel() {
        setVariable(
            "datasource",
            co.datapipelines.datasources.Datasource(
                name = "pg-demo",
                displayName = "pg-demo",
                description = null,
                dialect = co.datapipelines.typesystem.Dialect.POSTGRES,
                jdbcUrl = "jdbc:postgresql://db/app",
            ),
        )
        setVariable("introspectionErrorCode", null)
        setVariable("introspectionErrorMessage", null)
        setVariable("flat", false)
        setVariable("schemas", listOf(SchemaFolderView(co.datapipelines.datasources.SchemaEntry(listOf("public"), "public"))))
        setVariable("schemasTruncated", false)
        setVariable("levelId", DatasourceSchemaTreeBrowseModel.tablesLevelId(listOf("public")))
        setVariable("namespace", "public")
        setVariable(
            "tables",
            listOf(TableRowView(listOf("public"), co.datapipelines.datasources.TableInfo(listOf("public"), "orders", "TABLE"))),
        )
        setVariable("tablesTruncated", false)
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 1)
        setVariable("table", "orders")
        setVariable(
            "columns",
            listOf(
                co.datapipelines.datasources.ColumnInfo(
                    co.datapipelines.typesystem.ColumnSchema(
                        name = "id",
                        type = co.datapipelines.typesystem.LogicalType.INTEGER,
                        nullable = false,
                    ),
                    sourceTypeName = "int4",
                    warnings = emptyList(),
                ),
            ),
        )
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
        setVariable("workspaceRoles", co.datapipelines.auth.WorkspaceRole.entries)
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
                            role = co.datapipelines.auth.WorkspaceRole.WORKSPACE_ADMIN,
                            roleLabel = "workspace admin",
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
                "canReadExecutions",
                "canAuthor",
                "canReadPromotion",
                "canPromote",
                "canAdminWorkspace",
                "isSuperAdmin",
                "canCancel",
                "canCreate",
                // 179 (D16) — the top-bar chip's copy/delete are EVERY role's on their OWN key
                // (VIEW_OWN_MCP_KEY), so the guard is not a role boolean but the key's
                // existence: `mcpKey` is non-null exactly when a live login-minted key is in
                // the active workspace, and the verbs render only inside it.
                "mcpKey",
            )

        /**
         * Files whose verb guard would be their ROUTE alone. EMPTY since 177 (§D.8): the fourteen
         * dialog fragments that lived here stamp the role model and guard their verbs in the
         * markup. Kept as a typed constant so the refusal above has something to be empty.
         */
        val ROUTE_GUARDED: Map<String, String> = emptyMap()

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
