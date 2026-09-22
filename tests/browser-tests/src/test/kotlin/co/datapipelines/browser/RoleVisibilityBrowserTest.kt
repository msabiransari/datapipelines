package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * RBAC design §8 test 8, in the browser: **a viewer's explorer shows no Release/Purge/Register/
 * New key; an author's shows all but Release; a promoter's shows Release only and no Edit; a
 * workspace admin sees Members with checkboxes; the last-admin rule renders its toast; the role
 * badge in the shell reads correctly for each.**
 *
 * The render tests in `modules/web` pin the same rules against a model built by hand. This one
 * is the end-to-end half and it earns its cost twice over:
 *
 *  - it proves the CONTROLLERS stamp what the templates read. A render test cannot: it sets the
 *    attributes itself, so a controller that forgot `RoleModel.stamp` leaves every screen a
 *    viewer's and every render test green (the "could this check have failed" question — the
 *    answer for a render test alone is no);
 *  - it asserts the member and grant verbs at the DATABASE, not at a fake. The 016/F1 lesson is
 *    that a non-suspending, in-memory double removes exactly the hop the defect lives in; here
 *    the hop is a form POST through the real service into `workspace_members`, and a controller
 *    test's `verify {}` would prove the call and not the row.
 */
class RoleVisibilityBrowserTest : BrowserSuite() {
    /**
     * The ladder, in one visit each. Three sessions, three roles, one screen — and each role is
     * asserted against the one below it, because the roles are not a chain: only a side-by-side
     * reading shows that a promoter LOSES execute and keys while an author gains release (D5,
     * D8 — 2026-09-20: "the author releases, the promoter promotes").
     */
    @Test
    fun `the explorer, the datasources screen and the API console render exactly each role's verbs`() {
        startTrace()

        // --- viewer: reads everything, changes nothing, and still executes (D-R3).
        val viewer = signIn("rbv-viewer", role = "viewer")
        viewer.page.navigate("$baseUrl/pipelines")
        viewer.page.waitForSelector("[data-role]")
        viewer.roleBadge() shouldBe "viewer"
        viewer.verbs() shouldContainExactly emptyList()

        viewer.page.navigate("$baseUrl/datasources")
        viewer.page.waitForSelector(".app-page-h")
        // §7.6 puts register/edit/delete on ws_admin; Test follows execute since 2026-09-20 and
        // renders for a viewer — the one verb this screen offers them.
        viewer.verbs().filterNot { it == "datasource-test" } shouldContainExactly emptyList()

        viewer.page.navigate("$baseUrl/api-console")
        viewer.page.waitForSelector(".app-page-h")
        // 179: the console is read-only (the keys card moved to /api-keys, D17) — no key verb
        // for anyone here. The viewer's OWN MCP key is the top bar's chip (`VIEW_OWN_MCP_KEY`).
        viewer.verbs().contains("key-create") shouldBe false
        viewer.close()

        // --- author: the authoring verbs, and no Release anywhere.
        val author = signIn("rbv-author", role = "author")
        author.page.navigate("$baseUrl/pipelines")
        author.page.waitForSelector("[data-role]")
        author.roleBadge() shouldBe "author"
        author.page.navigate("$baseUrl/api-console")
        author.page.waitForSelector(".app-page-h")
        // 179 (D17): authors PUBLISH endpoints but do not manage API keys — create/delete/
        // associate are the workspace admin's `MANAGE_API_KEYS`, on /api-keys.
        author.verbs().contains("key-create") shouldBe false

        author.page.navigate("$baseUrl/datasources")
        author.page.waitForSelector(".app-page-h")
        // Still not an author's: a datasource is a live credential (§7.6, ws_admin).
        author.verbs().contains("datasource-register") shouldBe false
        author.close()

        // --- promoter: promotes, and none of the authoring, executing or key verbs (D5).
        val promoter = signIn("rbv-promoter", role = "promoter")
        promoter.page.navigate("$baseUrl/pipelines")
        promoter.page.waitForSelector("[data-role]")
        promoter.roleBadge() shouldBe "promoter"
        promoter.page.navigate("$baseUrl/api-console")
        promoter.page.waitForSelector(".app-page-h")
        // A promoter is not an author, and keys are an author's verb.
        promoter.verbs().contains("key-create") shouldBe false
        promoter.close()

        // --- workspace admin: the datasource verbs, and Members with the role dropdown.
        val admin = signIn("rbv-admin", role = "workspace_admin")
        admin.page.navigate("$baseUrl/pipelines")
        admin.page.waitForSelector("[data-role]")
        admin.roleBadge() shouldBe "workspace admin"

        admin.page.navigate("$baseUrl/datasources")
        admin.page.waitForSelector(".app-page-h")
        admin.verbs().contains("datasource-register") shouldBe true
        // Grants is a SUPER admin's verb, one rung up (§7.6 MANAGE_DATASOURCE_GRANTS).
        admin.verbs().contains("datasource-grants") shouldBe false

        // 179 (D17): the workspace admin owns /api-keys — create and the per-row verbs.
        admin.page.navigate("$baseUrl/api-keys")
        admin.page.waitForSelector(".app-page-h")
        admin.verbs().contains("key-create") shouldBe true
        admin.close()
    }

    /**
     * 122 — D-R3's execute right, through the DOOR the screen actually has: the explorer's
     * "Open in editor" link. The record said a viewer's editor loads and executes (D-R3,
     * ui-screens §4.3e's pipeline-editor row, RoleVisibilityRenderTest §A) while the route
     * floored the GET at MUTATE_PIPELINES_TEMPLATES, so the click landed on a 403 and the
     * verbs 114 rendered were unreachable — a render test cannot see that, because it never
     * walks the route (this test is the arm that can go red on it).
     *
     * The whole walk rides the app's own links — explorer search, leaf click, Open — never a
     * direct editor URL (the owner's 2026-09-05 rule; 076 missed a P0 by entering by URL).
     * A response listener records EVERY response from the first page load through the walk
     * and the run: any ≥ 400 is a failure, which is what proves the viewer's page carries no
     * hidden 403 from a load-time call an author floor would refuse (122 §A.2).
     *
     * The pipeline is seeded by an AUTHOR through REST because a viewer cannot create one —
     * the subject here is run-and-read, not creation. Execute runs the DRAFT the editor pins
     * (draft.js sends the shown version; D55's execute-default is the same last-version rule
     * rest-wide), so the viewer runs exactly what the screen showed them.
     */
    @Test
    fun `a viewer opens a pipeline from the explorer and executes it in the editor`() {
        startTrace()
        val name = "test/browser_122_" + generatedPassword("p").take(6).lowercase()
        val author = signIn("rbv-exec-author", role = "author")
        createDraftPipeline(author.page, name)
        author.close()

        val viewer = signIn("rbv-exec-viewer", role = "viewer")
        val badResponses = mutableListOf<String>()
        var editorDocumentStatus = 0
        viewer.page.onResponse { response ->
            if (response.status() >= 400) {
                badResponses += "${response.status()} ${response.request().method()} ${response.url()}"
            }
            if (response.request().isNavigationRequest() && response.url().contains("/editor")) {
                editorDocumentStatus = response.status()
            }
        }

        // Explorer → leaf → detail → editor, the app's own links the whole way.
        viewer.page.navigate("$baseUrl/pipelines?q=$name")
        viewer.page.waitForSelector("[data-role]")
        viewer.roleBadge() shouldBe "viewer"
        viewer.page
            .locator("button.tpl-result, button.tpl-leaf")
            .first()
            .click()
        val open = viewer.page.locator("a:has-text('Open in editor')").first()
        open.waitFor()
        badResponses shouldBe emptyList()

        open.click()
        viewer.page.waitForURL("**/pipelines/*/editor")
        // LOAD, not the cards: at base the route answers 403 and no card ever comes, so the
        // red must be the listener's, naming the refused request — not a 30 s card timeout.
        viewer.page.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD)

        // The 403 the route used to answer IS the red this test is born with: assert the
        // listener first, so the failure names the refused request instead of timing out
        // on a note that never rendered.
        badResponses shouldBe emptyList()
        editorDocumentStatus shouldBe 200

        // The graph's node cards are the signal that the editor finished loading (the same
        // wait every editor walk uses).
        viewer.page
            .locator(".pe-card")
            .first()
            .waitFor()

        // 114 §A's screen, now actually reachable: the read-only note, Execute kept,
        // no authoring verb anywhere on the page.
        viewer.page.locator("[data-role-note='read-only']").waitFor()
        viewer.verbs().contains("pipeline-execute") shouldBe true
        viewer.verbs().contains("pipeline-release") shouldBe false
        viewer.verbs().contains("pipeline-purge") shouldBe false

        // The run itself — D-R3's point. The Execute button re-enables when the stream
        // ends (x-bind:disabled="isExecuting"); the status chip names the terminal phase.
        viewer.page.locator("[data-verb='pipeline-execute']").click()
        viewer.page
            .locator("[data-verb='pipeline-execute']:not([disabled])")
            .waitFor(
                com.microsoft.playwright.Locator
                    .WaitForOptions()
                    .setTimeout(EXECUTION_TIMEOUT_MS),
            )
        viewer.page.locator(".pe-status:has-text('Completed')").waitFor()
        badResponses shouldBe emptyList()
        viewer.close()
    }

    /** A one-node `fiscal_quarter` pipeline (102's fixture), created in-page by [page]'s cookies. */
    @Suppress("UNCHECKED_CAST")
    private fun createDraftPipeline(
        page: Page,
        name: String,
    ): String {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch('/api/v1/pipelines', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {
                      'Content-Type': 'application/json',
                      'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '',
                    },
                    body: JSON.stringify({
                      name: args.name,
                      display_name: args.name,
                      nodes: [{
                        id: 'fq',
                        type: 'CALCULATOR',
                        kind: 'fiscal_quarter',
                        context_key: 'run_fiscal_quarter',
                        inputs: { date: '${'$'}current_date', fiscal_start: '${'$'}org_fiscal_start_date' },
                      }],
                    }),
                  });
                  const body = await res.text();
                  return { status: res.status, body: body };
                }""",
                mapOf("name" to name),
            ) as Map<String, Any?>
        (result["status"] as Number).toInt() shouldBe 201
        return Regex(""""id"\s*:\s*"([0-9a-f-]+)"""").find(result["body"] as String)!!.groupValues[1]
    }

    private companion object {
        /** A calculator run is engine-internal (no datasource); 60 s is already generous. */
        const val EXECUTION_TIMEOUT_MS = 60_000.0
    }

    /**
     * §C.1 / D22 — the members screen a workspace admin gets: ONE role dropdown per row, one on
     * the add form, and the role landing in `workspace_members`. Asserted at the ROW, because
     * "did the dropdown reach the database" is the only question that matters and a rendered
     * form proves nothing about it.
     *
     * The fixture builds its OWN workspace rather than using the shared `default`: every suite
     * on this box seeds members into `default`, so a rule about "the members of a workspace"
     * asserted there would be reading other tests' rows.
     */
    @Test
    fun `a workspace admin adds a member with a role and then changes it through the dropdown`() {
        startTrace()
        val workspace = "rbv-ws-" + suffix()
        val adminUser = seedRoleUser("rbv-mem-admin", role = "viewer")
        val member = viewerUser("rbv-target")
        // BEFORE the login: AuthCache caches memberships per user, so a workspace seeded after
        // the session opened stays invisible for the TTL.
        seedWorkspaceAdministeredBy(workspace, adminUser.email)
        val admin = openSession(adminUser)
        // Member verbs are judged against the ACTIVE workspace, so managing one means being in
        // it — the screen says so, and this is the product's own flow, not a test convenience.
        admin.switchTo(workspace)

        val section = "section[data-workspace='$workspace']"
        admin.page.waitForSelector("$section [data-verb='member-add']")

        // Add-with-role: the pre-114 form posted `email` only, so every member added from the
        // UI arrived a viewer whatever the operator meant.
        admin.page.fill("$section form[action$='/members'] input[name=email]", member)
        admin.page.selectOption("$section form[action$='/members'] select[name=role]", "promoter")
        admin.page.click("$section [data-verb='member-add']")
        admin.page.waitForSelector("$section tr[data-member='$member']")

        roleOf(workspace, member) shouldBe "promoter"

        // …and a CHANGE through the row's own dropdown: the one htmx partial on this screen,
        // which swaps the row in place and toasts. The write's own completion signal is the
        // success toast — the response that carries it is the response that committed.
        val row = "$section tr[data-member='$member']"
        admin.page.waitForSelector("$row select[data-member-role]")
        admin.page.selectOption("$row select[data-member-role]", "workspace_admin")
        admin.page.click("$row [data-verb='member-role']")
        // The response's OWN toast (the `?ok=member_added` flash is already on the page, so a
        // bare `.ds-toast-success` wait would be satisfied before the POST is answered).
        admin.page.waitForSelector(".ds-toast-success:has-text('Role changed')")
        // …and the swapped-in row shows what the database now holds: the SERVER marked the
        // option selected in the re-rendered fragment (read as the select's value — an <option>
        // in a closed select is never "visible", so a selector wait on it would never resolve).
        admin.page.locator("$row select[data-member-role]").inputValue() shouldBe "workspace_admin"

        roleOf(workspace, member) shouldBe "workspace_admin"
        admin.close()
    }

    /**
     * The last-admin rule as the USER meets it: a 409 from the service arrives as the §5.1
     * toast that names the remedy ("give someone else the workspace admin role first"), not as
     * an error page and not as a silent no-op. The refusal is the SERVER's — what this asserts
     * is that the screen tells the truth about it, and that a refused write does not half-apply.
     */
    @Test
    fun `demoting the only admin renders the last-admin toast and leaves the row alone`() {
        startTrace()
        val workspace = "rbv-last-" + suffix()
        val adminUser = seedRoleUser("rbv-last-admin", role = "viewer")
        seedWorkspaceAdministeredBy(workspace, adminUser.email)
        // #208: nobody administers their OWN row, so the only admin cannot be the one who
        // demotes them — a super admin holding a viewer membership here is.
        val rootUser = seedLocalUser(uniqueEmail("rbv-last-root-" + suffix()), generatedPassword("pw"), mustChange = false)
        addMember(workspace, rootUser.email, "viewer")

        val admin = openSession(adminUser)
        admin.switchTo(workspace)
        val ownRow = "section[data-workspace='$workspace'] tr[data-member='${admin.email}']"
        admin.page.waitForSelector("$ownRow select[data-member-role]")
        admin.page.locator("$ownRow [data-verb='member-role']").count() shouldBe 0
        admin.page.locator("$ownRow [data-verb='member-remove']").count() shouldBe 0
        admin.close()

        val root = openSession(rootUser)
        root.switchTo(workspace)
        val row = "section[data-workspace='$workspace'] tr[data-member='${adminUser.email}']"
        root.page.waitForSelector("$row select[data-member-role]")
        root.page.selectOption("$row select[data-member-role]", "author")
        root.page.click("$row [data-verb='member-role']")

        root.page.waitForSelector(".ds-toast-danger")
        root.page
            .locator(".ds-toast-danger")
            .first()
            .innerText()
            .lowercase()
            .contains("last workspace admin") shouldBe true

        // The row is untouched: a refused write must not half-apply.
        roleOf(workspace, adminUser.email) shouldBe "workspace_admin"
        root.close()
    }

    // ------------------------------------------------------------------ helpers

    private inner class RoleSession(
        val page: Page,
        val email: String,
        private val session: Session,
    ) {
        /**
         * Every `data-verb` the current SCREEN renders, in document order — the main region's,
         * never the shell's: since 179 the top bar carries the MCP-key chip's copy/rotate
         * (every role, their own key), and a page-wide read would mix chrome into what is a
         * statement about the SCREEN.
         */
        fun verbs(): List<String> =
            page
                .locator("#app-main [data-verb]")
                .all()
                .map { it.getAttribute("data-verb") }

        /** The shell badge beside the switcher's workspace name (§C.4). */
        fun roleBadge(): String = page.locator("[data-role]").first().getAttribute("data-role")

        /**
         * Enters [workspace] through the chrome's switcher, then lands on `/workspaces` with the
         * switch APPLIED — the wait is on the badge's workspace NAME changing, so the next
         * navigation cannot race the re-minted cookie.
         */
        fun switchTo(workspace: String) {
            // 177/D13: through the CHROME's switcher — the workspaces PAGE is a workspace
            // admin's, and this person is a viewer of `default` until the switch lands.
            page.navigate("$baseUrl/dashboard")
            page.waitForSelector("#workspace-switcher")
            page.selectOption("#workspace-switcher", arrayOf(workspace), Page.SelectOptionOptions().setForce(true))
            page.waitForFunction("() => document.querySelector('.app-ws b')?.textContent?.trim() === '$workspace'")
            page.navigate("$baseUrl/workspaces")
        }

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
            // NOT an instance super admin: a super admin holds every capability in every
            // workspace (D-R8), which would make each of these four sessions identical.
            isAdmin = false,
            role = role,
        )

    /**
     * Signs [user] in. Every membership the session will see must be SEEDED FIRST: `AuthCache`
     * holds a user's memberships for its TTL, so a workspace granted after login is invisible
     * for a minute — which reads exactly like a screen that forgot to render it.
     */
    private fun openSession(user: LocalUser): RoleSession {
        val email = user.email
        val session = newSession()
        session.page.navigate("$baseUrl/login")
        session.page.fill("#login-email", user.email)
        session.page.fill("#login-password", user.oneTimePassword)
        session.page.click("form button[type=submit]")
        session.page.waitForURL("**/dashboard")
        return RoleSession(session.page, email, session)
    }

    /** A member of `default` and nothing else, a viewer — someone to add somewhere. */
    private fun viewerUser(slug: String): String {
        val email = uniqueEmail("$slug-" + suffix())
        seedLocalUser(email, generatedPassword("pw"), mustChange = false, isAdmin = false, role = "viewer")
        return email
    }

    /**
     * A fresh workspace whose ONLY admin is [adminEmail].
     *
     * Seeded in SQL rather than through the create form because creating one is a super
     * admin's verb (D-R11) and these fixtures are deliberately NOT super admins — a super
     * admin holds every capability in every workspace, which would make the four role sessions
     * above indistinguishable.
     */
    private fun seedWorkspaceAdministeredBy(
        name: String,
        adminEmail: String,
    ) = DriverManager
        .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
        .use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO workspaces (name, display_name, is_personal, created_by)
                    VALUES ('$name', '$name', FALSE, NULL)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO workspace_members (workspace_id, user_id, role)
                    SELECT w.id, u.id, 'workspace_admin'
                      FROM workspaces w, users u
                     WHERE w.name = '$name' AND u.email = '$adminEmail'
                    """.trimIndent(),
                )
            }
        }

    /** Adds [email] to [workspace] with [role] — the #208 tests need a second, differently placed actor. */
    private fun addMember(
        workspace: String,
        email: String,
        role: String,
    ) = DriverManager
        .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
        .use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO workspace_members (workspace_id, user_id, role)
                    SELECT w.id, u.id, '$role'
                      FROM workspaces w, users u
                     WHERE w.name = '$workspace' AND u.email = '$email'
                    """.trimIndent(),
                )
            }
        }

    /** The stored role of [email]'s membership in [workspace]. */
    private fun roleOf(
        workspace: String,
        email: String,
    ): String =
        DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT m.role
                          FROM workspace_members m
                          JOIN users u ON u.id = m.user_id
                          JOIN workspaces w ON w.id = m.workspace_id
                         WHERE u.email = ? AND w.name = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, email)
                        statement.setString(2, workspace)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "no membership row for $email" }
                            rows.getString(1)
                        }
                    }
            }

    private fun suffix(): String = generatedPassword("s").take(8).lowercase()
}
