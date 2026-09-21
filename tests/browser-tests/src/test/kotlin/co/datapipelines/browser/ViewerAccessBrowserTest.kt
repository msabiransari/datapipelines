package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager

/**
 * 143 (T315) — what a READER can reach, through the doors the product actually has.
 *
 * The owner's ruling: a viewer's Open / Open in editor opens the template genuinely
 * read-only, the way the pipeline editor opens for a viewer (122); Admin is absent unless it
 * leads somewhere its reader may go; Workspaces and Promotion stay readable without
 * management or submission controls. The render tests in `modules/web` pin the markup
 * against a hand-built model; this is the arm that walks the LINKS, watches every response,
 * reads the DOM the browser built, and reads the database before and after — because a
 * render test cannot see a route floored above its reader (the 403 122 found), a rail
 * painted by an advice the controller never called, or a draft a "read" quietly created.
 *
 * Every walk rides the app's own links — explorer search, the result row, Open in editor,
 * the versions tab's Open, the keyboard's Enter, the rail — never a direct editor URL (the
 * owner's 2026-09-05 rule). A response listener records every ≥ 400 from the first paint
 * through partial swaps and boosted navigation; the list must stay empty.
 *
 * Falsifications recorded in the handback: the editor route restored to MUTATE makes the
 * viewer walk red at the recorded document status; the layout's Admin guard removed makes
 * the reader arm of the Admin test red.
 */
class ViewerAccessBrowserTest : BrowserSuite() {
    // ------------------------------------------------------------------ A: the viewer's editor

    @Test
    fun `a viewer opens a template from the explorer, reads every version, and changes nothing`() {
        startTrace()
        val fixture = seedTemplate()
        val before = versionRows(fixture.name)
        val auditBefore = templateAuditCount(fixture.name)

        val viewer = signIn("va-viewer", role = "viewer")
        val watch = viewer.watchResponses()
        openInEditorAsViewer(viewer, fixture, watch)
        readEveryVersion(viewer, fixture, watch)
        readerNavigation(viewer, watch)

        // Reads wrote nothing: every version row identical, no draft created, no template
        // audit event for this template beyond the fixture's own.
        versionRows(fixture.name) shouldBe before
        templateAuditCount(fixture.name) shouldBe auditBefore
        viewer.close()
    }

    /** 1 — explorer → result row → Open in editor: the app's own door, and the read-only page it opens. */
    private fun openInEditorAsViewer(
        viewer: RoleSession,
        fixture: TemplateFixture,
        watch: Watch,
    ) {
        viewer.page.navigate("$baseUrl/templates?q=${fixture.leaf}")
        viewer.page.waitForSelector("[data-role]")
        viewer.roleBadge() shouldBe "viewer"
        viewer.page
            .locator("button.tpl-result")
            .first()
            .click()
        val open = viewer.page.locator("a:has-text('Open in editor')").first()
        open.waitFor()
        // The detail's verbs are already role-hidden (114); the LINK is what 143 opens.
        viewer.verbs().shouldBeEmpty()
        // The editor document's own response is awaited and judged BEFORE the URL: at base the
        // route answered 403 and htmx swapped nothing, so the red must name the refused
        // request (the listener's list), not a URL that never changed.
        val editor = viewer.page.waitForResponse(::isEditorDocument) { open.click() }
        watch.bad shouldBe emptyList()
        editor.status() shouldBe 200
        watch.editorDocumentStatus shouldBe 200
        viewer.page.waitForURL("**/templates/editor**")
        viewer.page.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD)

        // The working version (the DRAFT, v2) in the read-only pane; nothing editable.
        assertReadOnlyEditor(viewer.page, expectedVersion = 2, expectedBody = fixture.draftBody)
        viewer.page.locator("[data-role-note='read-only']").waitFor()
        viewer.verbs().shouldBeEmpty()
        ensureThemeOn(viewer.page, "light")
        shot(viewer.page, "viewer-editor", "light")
    }

    /** 2 — the version select, the versions tab's Open with its version, and the keyboard's Enter. */
    private fun readEveryVersion(
        viewer: RoleSession,
        fixture: TemplateFixture,
        watch: Watch,
    ) {
        // The version select swaps the source partial: v1, still read-only.
        viewer.page.selectOption("#versionSelect", "1")
        viewer.page.waitForFunction(
            "(body) => { const p = document.getElementById('versionBody'); return p !== null && p.textContent === body; }",
            fixture.releasedBody,
        )
        assertReadOnlyEditor(viewer.page, expectedVersion = 1, expectedBody = fixture.releasedBody)
        watch.bad shouldBe emptyList()

        // The versions tab's Open carries THAT row's version (the audit's finding 5).
        viewer.page.navigate("$baseUrl/templates?q=${fixture.leaf}")
        viewer.page
            .locator("button.tpl-result")
            .first()
            .click()
        val v1Open = viewer.page.locator(".tplx-vrow:has(.app-chip-mono:text-is('v1')) a:has-text('Open')")
        v1Open.waitFor()
        v1Open.click()
        viewer.page.waitForURL("**/templates/editor**version=1**")
        viewer.page.locator("#versionBody").waitFor()
        assertReadOnlyEditor(viewer.page, expectedVersion = 1, expectedBody = fixture.releasedBody)

        // Keyboard Open: Enter on the focused result row lands on the same route.
        viewer.page.navigate("$baseUrl/templates?q=${fixture.leaf}")
        val row = viewer.page.locator("button.tpl-result").first()
        row.waitFor()
        row.focus()
        viewer.page.keyboard().press("Enter")
        viewer.page.waitForURL("**/templates/editor**")
        viewer.page.locator("#versionBody").waitFor()
        assertReadOnlyEditor(viewer.page, expectedVersion = 2, expectedBody = fixture.draftBody)
        ensureThemeOn(viewer.page, "dark")
        shot(viewer.page, "viewer-editor", "dark")
        ensureThemeOn(viewer.page, "light")
    }

    /** 3 — the rest of a reader's navigation: no Admin item; Promotion and Workspaces readable, no controls. */
    private fun readerNavigation(
        viewer: RoleSession,
        watch: Watch,
    ) {
        viewer.adminAnchors() shouldBe emptyList()
        // 177: the promotion page is `PROMOTION_READ` (author, promoter, admins — rule 13) and
        // the workspaces page is a workspace admin's (D13). A viewer is refused both by ROLE —
        // the catalogued 403, not a screen with the verbs hidden — and the rail draws neither
        // item, so there is no dead link to click. The switcher in the chrome stays.
        viewer.page.locator("nav.app-nav a[data-nav-section='/promotion']").count() shouldBe 0
        viewer.page.locator("nav.app-nav a[data-nav-label='Workspaces']").count() shouldBe 0
        viewer.page.locator("#workspace-switcher").count() shouldBe 1
        // Asked through the session's own request context (the cookies ride along) rather than
        // by navigating: the response watcher above records every ≥400 the PAGE sees, and these
        // two refusals are the point, not a defect.
        listOf("/promotion", "/workspaces").forEach { path ->
            val refused = viewer.page.request().get("$baseUrl$path")
            refused.status() shouldBe 403
            refused.text() shouldContain "auth.role_required"
        }
        shot(viewer.page, "viewer-dashboard", "light")
        ensureThemeOn(viewer.page, "dark")
        shot(viewer.page, "viewer-dashboard", "dark")
        // Boosted navigation keeps the rail as painted: still no Admin after rail clicks.
        viewer.page.locator("nav.app-nav a[data-nav-section='/pipelines']").click()
        viewer.page.waitForURL("**/pipelines**")
        viewer.adminAnchors() shouldBe emptyList()
        watch.bad shouldBe emptyList()
    }

    /** The positive halves, so the reader arm cannot pass by hiding everything. */
    @Test
    fun `an author keeps the editing surface and Preview, a promoter gets the read-only source and no verb`() {
        startTrace()
        val fixture = seedTemplate()

        val author = signIn("va-author", role = "author")
        val watch = author.watchResponses()
        author.page.navigate("$baseUrl/templates?q=${fixture.leaf}")
        author.page
            .locator("button.tpl-result")
            .first()
            .click()
        author.page
            .locator("a:has-text('Open in editor')")
            .first()
            .click()
        author.page.waitForURL("**/templates/editor**")
        author.page.locator("#templateBody").waitFor()
        author.page.locator("#previewBtn").waitFor()
        author.page.locator("#context-rows").waitFor()
        author.page.locator("#versionBody").count() shouldBe 0
        // Preview renders the stored draft: the pane fills, the request is not refused.
        author.page.locator("#previewBtn").click()
        author.page.waitForFunction("() => document.getElementById('previewPane').textContent.trim().length > 0")
        author.page.locator("#previewPane").innerText() shouldContain "SELECT"
        watch.bad shouldBe emptyList()
        author.close()

        // D5 (2026-09-20): the promoter authors nothing and RELEASES nothing — the editor is a
        // read-only source with the read-only note and no verb at all.
        val promoter = signIn("va-promoter", role = "promoter")
        val promoterWatch = promoter.watchResponses()
        promoter.page.navigate("$baseUrl/templates?q=${fixture.leaf}")
        promoter.page
            .locator("button.tpl-result")
            .first()
            .click()
        val promoterOpen = promoter.page.locator("a:has-text('Open in editor')").first()
        promoter.page.waitForResponse(::isEditorDocument) { promoterOpen.click() }
        promoterWatch.bad shouldBe emptyList()
        promoter.page.waitForURL("**/templates/editor**")
        assertReadOnlyEditor(promoter.page, expectedVersion = 2, expectedBody = fixture.draftBody)
        promoter.page.locator("[data-role-note='read-only']").waitFor()
        promoter.verbs() shouldBe emptyList()
        promoter.page.locator("[data-verb='template-release']").count() shouldBe 0
        promoterWatch.bad shouldBe emptyList()
        promoter.close()
    }

    // ------------------------------------------------------------------ B: the Admin item

    @Test
    fun `the Admin item follows the reader's authority and a role-changing switch re-derives it`() {
        startTrace()
        readersHaveNoAdminItem()
        workspaceAdminArm()
        superAdminArm()
        implicitSuperAdminArm()
    }

    /**
     * Viewer, author, pure promoter: no Admin item at all, and since D13 (2026-09-20) no
     * Workspaces item either — the page is a workspace admin's; the SWITCHER in the chrome is
     * what every member keeps. The promoter also has no Executions item (D11); the other two do.
     */
    private fun readersHaveNoAdminItem() {
        listOf("va-nav-viewer" to "viewer", "va-nav-author" to "author", "va-nav-promoter" to "promoter").forEach { (slug, role) ->
            val session = signIn(slug, role = role)
            session.page.waitForSelector("nav.app-nav")
            session.adminAnchors() shouldBe emptyList()
            session.page.locator("nav.app-nav a[data-nav-label='Workspaces']").count() shouldBe 0
            session.page.locator("#workspace-switcher").count() shouldBe 1
            session.page.locator("nav.app-nav a[data-nav-section='/executions']").count() shouldBe if (role == "promoter") 0 else 1
            session.close()
        }
    }

    /**
     * A workspace admin of `default` who is a VIEWER elsewhere: Admin leads to the current
     * workspace's members; a boosted navigation keeps it; switching to the viewer workspace
     * removes it at the next full paint.
     */
    private fun workspaceAdminArm() {
        val other = "va-ws-" + suffix()
        val adminUser = seedRoleUser("va-nav-admin", role = "workspace_admin")
        seedViewerMembership(other, adminUser.email)
        val admin = openSession(adminUser)
        val watch = admin.watchResponses()
        admin.page.waitForSelector("nav.app-nav")
        admin.roleBadge() shouldBe "workspace admin"
        val members = admin.adminAnchors().single()
        members.getAttribute("data-nav-admin") shouldBe "members"
        members.getAttribute("href") shouldEndWith "/workspaces#workspace-members"
        members.click()
        admin.page.waitForURL("**/workspaces#workspace-members")
        admin.page.locator("#workspace-members").waitFor()
        admin.page.locator("#workspace-members [data-verb='member-add']").waitFor()
        // Active state is the path rule: Workspaces lights, the members shortcut does not.
        admin.page.locator("nav.app-nav a.active[data-nav-label='Workspaces']").count() shouldBe 1
        admin.page.locator("nav.app-nav a.active[data-nav-section='/admin']").count() shouldBe 0
        ensureThemeOn(admin.page, "light")
        shot(admin.page, "ws-admin-members", "light")
        admin.page.locator("nav.app-nav a[data-nav-section='/executions']").click()
        admin.page.waitForURL("**/executions**")
        admin.adminAnchors().single().getAttribute("data-nav-admin") shouldBe "members"

        // D13/D14 (177): the switcher in the chrome stays every member's and re-issues the
        // token; the workspaces PAGE is the admin's and refuses the viewer this person now is.
        val tokenBefore = admin.sessionToken()
        admin.switchTo(other)
        admin.roleBadge() shouldBe "viewer"
        admin.sessionToken().let {
            it shouldNotBe null
            it shouldNotBe tokenBefore
        }
        admin.adminAnchors() shouldBe emptyList()
        admin.page.locator("nav.app-nav a[data-nav-label='Workspaces']").count() shouldBe 0
        admin.page.locator("#workspace-switcher").count() shouldBe 1
        shot(admin.page, "ws-admin-switched-to-viewer", "light")
        watch.bad shouldBe emptyList()
        val refused = admin.page.request().get("$baseUrl/workspaces")
        refused.status() shouldBe 403
        refused.text() shouldContain "auth.role_required"
        admin.close()
    }

    /** An instance super admin: Admin is the users screen, and it answers. */
    private fun superAdminArm() {
        val root = openSession(superAdminUser("va-nav-root"))
        val watch = root.watchResponses()
        root.page.waitForSelector("nav.app-nav")
        root.roleBadge() shouldBe "super admin"
        val users = root.adminAnchors().single()
        users.getAttribute("data-nav-admin") shouldBe "users"
        users.click()
        root.page.waitForURL("**/admin/users**")
        root.page.waitForSelector(".app-page-h")
        root.page.locator("nav.app-nav a.active[data-nav-section='/admin']").count() shouldBe 1
        watch.bad shouldBe emptyList()
        ensureThemeOn(root.page, "light")
        shot(root.page, "super-admin-users", "light")
        root.close()
    }

    /**
     * A super admin whose ONLY explicit membership is in a deactivated workspace. Measured
     * (2026-09-15): the session filter's D-R8 fallback (`WorkspaceService.resolveForSession`)
     * gives such a principal the instance's FIRST ACTIVE workspace as an implicit context, so
     * they are never workspace-less while the instance has one — a truly no-workspace super
     * admin exists only on an instance with zero active workspaces, which a shared test
     * instance cannot be. Proven here: the entry renders from the principal's instance
     * authority (no explicit active membership), the badge reads super admin, the users
     * screen answers. The workspace-less derivation itself is RoleModelTest's
     * (`shell(session(flags = null, superAdmin = true))`).
     */
    private fun implicitSuperAdminArm() {
        val orphan = superAdminUser("va-nav-orphan")
        moveSoleMembershipToDeactivated(orphan.email, "va-off-" + suffix())
        val session = openSession(orphan)
        val watch = session.watchResponses()
        session.page.waitForSelector("nav.app-nav")
        session.roleBadge() shouldBe "super admin"
        session.adminAnchors().single().getAttribute("data-nav-admin") shouldBe "users"
        session.page.locator("nav.app-nav a[data-nav-admin='users']").click()
        session.page.waitForURL("**/admin/users**")
        session.page.waitForSelector(".app-page-h")
        watch.bad shouldBe emptyList()
        session.close()
    }

    private fun superAdminUser(slug: String): LocalUser =
        seedLocalUser(uniqueEmail("$slug-" + suffix()), generatedPassword("pw"), mustChange = false, isAdmin = true)

    // ------------------------------------------------------------------ shared assertions

    /** The editor PAGE's response (boosted fetch or full load) — never one of its partials. */
    private fun isEditorDocument(response: com.microsoft.playwright.Response): Boolean =
        response.url().contains("/templates/editor") && !response.url().contains("/partials/")

    private fun assertReadOnlyEditor(
        page: Page,
        expectedVersion: Int,
        expectedBody: String,
    ) {
        page.locator("#versionBody").waitFor()
        page.locator("#versionBody").textContent() shouldBe expectedBody
        page.locator("#templateBody").count() shouldBe 0
        page.locator("textarea").count() shouldBe 0
        page.locator("#previewBtn").count() shouldBe 0
        page.locator("#previewPane").count() shouldBe 0
        page.locator("#context-rows").count() shouldBe 0
        page.locator("#tpl-edit-version").count() shouldBe 0
        page.locator("#versionSelect").inputValue() shouldBe expectedVersion.toString()
        page.locator(".te-source .ds-badge", Page.LocatorOptions().setHasText("v$expectedVersion")).first().waitFor()
    }

    // ------------------------------------------------------------------ fixtures

    private class TemplateFixture(
        val name: String,
        val leaf: String,
        val releasedBody: String,
        val draftBody: String,
    )

    /** v1 RELEASED and v2 DRAFT, made by an AUTHOR through REST (release is the author's since D8; a viewer cannot). */
    private fun seedTemplate(): TemplateFixture {
        val leaf = "va143_" + suffix()
        val name = "test/$leaf.sql"
        val releasedBody = "SELECT 1 AS released_$leaf"
        val draftBody = "SELECT 2 AS draft_$leaf"
        val maker = signIn("va-maker", role = "author")
        val created =
            send(
                maker.page,
                "POST",
                "/api/v1/templates",
                templateJson(name, leaf, releasedBody),
            )
        created.first shouldBe 201
        val v1Hash = hashOf(created.second!!)
        val released = send(maker.page, "POST", "/api/v1/templates/release", """{"name":"$name"}""", ifMatch = v1Hash)
        released.first shouldBe 200
        val releasedHash = hashOf(released.second!!)
        val drafted =
            send(
                maker.page,
                "PUT",
                "/api/v1/templates",
                templateJson(name, leaf, draftBody),
                ifMatch = releasedHash,
            )
        drafted.first shouldBe 200
        maker.close()
        versionRowsFull(name).map { it.version to it.status } shouldBe listOf(1 to "RELEASED", 2 to "DRAFT")
        return TemplateFixture(name, leaf, releasedBody, draftBody)
    }

    private fun templateJson(
        name: String,
        leaf: String,
        body: String,
    ): String =
        """{"id":"$name","type":"sql","dialect":"H2","display_name":"$leaf",""" +
            """"description":"143 browser fixture","body":"$body"}"""

    private fun hashOf(body: String): String = Regex(""""body_hash"\s*:\s*"([0-9a-f]+)"""").find(body)!!.groupValues[1]

    /** The in-page REST call of the sibling suites: cookie session, the CSRF pair, optional If-Match. */
    @Suppress("UNCHECKED_CAST")
    private fun send(
        page: Page,
        method: String,
        url: String,
        body: String? = null,
        ifMatch: String? = null,
    ): Pair<Int, String?> {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = {'Content-Type': 'application/json',
                                   'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''};
                  if (args.ifMatch) headers['If-Match'] = args.ifMatch;
                  const res = await fetch(args.url, {
                    method: args.method, credentials: 'same-origin', headers,
                    body: args.body ?? undefined,
                  });
                  const text = await res.text();
                  return {status: res.status, body: text.length < 16384 ? text : null};
               }""",
                mapOf("method" to method, "url" to url, "body" to body, "ifMatch" to ifMatch),
            ) as Map<String, Any?>
        return (result["status"] as Number).toInt() to (result["body"] as String?)
    }

    /** Every version row of [name] as `version → "status|body_hash|updated_at"`, in version order. */
    private fun versionRows(name: String): List<Pair<Int, String>> =
        versionRowsFull(name).map { r -> r.version to "${r.status}|${r.bodyHash}|${r.updatedAt}" }

    private data class Row(
        val version: Int,
        val status: String,
        val bodyHash: String,
        val updatedAt: String,
    )

    private fun versionRowsFull(name: String): List<Row> =
        query(
            "SELECT v.version, v.status, v.body_hash, COALESCE(v.updated_at::text, '') FROM template_versions v" +
                " JOIN templates t ON t.id = v.template_id WHERE t.name = ? ORDER BY v.version",
            name,
        ) { rows -> Row(rows.getInt(1), rows.getString(2), rows.getString(3), rows.getString(4)) }

    private fun templateAuditCount(name: String): Int =
        query(
            "SELECT COUNT(*) FROM audit_log WHERE event LIKE 'template.%'" +
                " AND (details_json->>'template_id' = ? OR details_json->>'name' = ?)",
            name,
            name,
        ) { rows -> rows.getInt(1) }.single()

    /** One parameterised query against the suite's database, one [map] call per row. */
    private fun <T> query(
        sql: String,
        vararg params: String,
        map: (java.sql.ResultSet) -> T,
    ): List<T> =
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { i, p -> statement.setString(i + 1, p) }
                statement.executeQuery().use { rows -> generateSequence { if (rows.next()) map(rows) else null }.toList() }
            }
        }

    /** A fresh ACTIVE workspace in which [email] is a plain VIEWER (seeded before login — AuthCache). */
    private fun seedViewerMembership(
        workspace: String,
        email: String,
    ) = sql(
        """
        INSERT INTO workspaces (name, display_name, is_personal, created_by)
        VALUES ('$workspace', '$workspace', FALSE, NULL)
        """.trimIndent(),
        """
        INSERT INTO workspace_members (workspace_id, user_id, role)
        SELECT w.id, u.id, 'viewer' FROM workspaces w, users u
         WHERE w.name = '$workspace' AND u.email = '$email'
        """.trimIndent(),
    )

    /** Makes [email]'s ONLY membership one in a DEACTIVATED workspace: no active workspace resolves at login. */
    private fun moveSoleMembershipToDeactivated(
        email: String,
        workspace: String,
    ) = sql(
        """
        INSERT INTO workspaces (name, display_name, is_personal, created_by, deactivated_at)
        VALUES ('$workspace', '$workspace', FALSE, NULL, NOW())
        """.trimIndent(),
        "DELETE FROM workspace_members WHERE user_id = (SELECT id FROM users WHERE email = '$email')",
        """
        INSERT INTO workspace_members (workspace_id, user_id, role)
        SELECT w.id, u.id, 'workspace_admin' FROM workspaces w, users u
         WHERE w.name = '$workspace' AND u.email = '$email'
        """.trimIndent(),
    )

    private fun sql(vararg statements: String) =
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
        }

    // ------------------------------------------------------------------ sessions (RoleVisibilityBrowserTest's shape)

    private class Watch(
        val bad: MutableList<String> = mutableListOf(),
    ) {
        var editorDocumentStatus: Int = 0
    }

    private inner class RoleSession(
        val page: Page,
        val email: String,
        private val session: Session,
    ) {
        // 179: the SCREEN's verbs, never the shell's — the top bar's MCP-key chip carries
        // copy/rotate for every role, and a page-wide read mixes chrome into the assertion.
        fun verbs(): List<String> = page.locator("#app-main [data-verb]").all().map { it.getAttribute("data-verb") }

        fun roleBadge(): String = page.locator("[data-role]").first().getAttribute("data-role")

        /** The rail's Admin anchors — zero or one, by the design. */
        fun adminAnchors(): List<Locator> = page.locator("nav.app-nav a[data-nav-section='/admin']").all()

        /** Records every ≥ 400 response and the editor document's own status. */
        fun watchResponses(): Watch {
            val watch = Watch()
            page.onResponse { response ->
                if (response.status() >= 400) {
                    watch.bad += "${response.status()} ${response.request().method()} ${response.url()}"
                }
                // The explorer's Open in editor is a BOOSTED link (unlike the pipeline editor's,
                // which is a full load for its Alpine root), so the editor page arrives as
                // htmx's fetch of the same document: record its status whichever way it came.
                val url = response.url()
                if (response.request().method() == "GET" && url.contains("/templates/editor") && !url.contains("/partials/")) {
                    watch.editorDocumentStatus = response.status()
                }
            }
            return watch
        }

        /**
         * Switches through the CHROME's switcher (the `<select>` every member keeps, D13/D14),
         * landing on the dashboard with the switch applied — not through the workspaces page,
         * which since 177 is a workspace admin's and would refuse the viewer this session is
         * about to become.
         */
        fun switchTo(workspace: String) {
            page.navigate("$baseUrl/dashboard")
            page.waitForSelector("#workspace-switcher")
            page.selectOption("#workspace-switcher", arrayOf(workspace), Page.SelectOptionOptions().setForce(true))
            page.waitForFunction(
                "() => document.querySelector('[data-role]') && document.querySelector('.app-ws b')?.textContent?.trim() === '$workspace'",
            )
            page.waitForSelector("nav.app-nav")
        }

        /** The `dp_session` cookie's value — a switch re-issues the token (D14), so it must change. */
        fun sessionToken(): String? =
            page
                .context()
                .cookies()
                .firstOrNull { it.name == "dp_session" }
                ?.value

        fun close() = session.close()
    }

    private fun signIn(
        slug: String,
        role: String,
    ): RoleSession = openSession(seedRoleUser(slug, role))

    private fun seedRoleUser(
        slug: String,
        role: String,
    ): LocalUser =
        seedLocalUser(
            uniqueEmail("$slug-" + suffix()),
            generatedPassword("pw"),
            mustChange = false,
            isAdmin = false,
            role = role,
        )

    private fun openSession(user: LocalUser): RoleSession {
        val session = newSession()
        session.page.navigate("$baseUrl/login")
        session.page.fill("#login-email", user.email)
        session.page.fill("#login-password", user.oneTimePassword)
        session.page.click("form button[type=submit]")
        session.page.waitForURL("**/dashboard")
        return RoleSession(session.page, user.email, session)
    }

    /** [BrowserSuite.ensureTheme], for a role session's own page (the toggle flips light ↔ dark). */
    private fun ensureThemeOn(
        page: Page,
        mode: String,
    ) {
        val wanted = "/themes/$mode.css"
        val current = page.locator("#theme-link").first().getAttribute("href") ?: ""
        if (current.contains(wanted)) return
        page.waitForResponse("**/partials/profile/theme") { page.locator("#mode-toggle").click() }
        page.waitForFunction("() => document.getElementById('theme-link').getAttribute('href').includes('$wanted')")
        page.locator("html[data-theme='$mode']").waitFor()
    }

    private fun shot(
        page: Page,
        name: String,
        theme: String,
    ) {
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("143-$name-$theme.png")))
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "143-screenshots").also { it.toFile().mkdirs() }

    private fun suffix(): String = generatedPassword("s").take(8).lowercase()
}
