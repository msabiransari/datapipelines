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
     * asserted against the one below it, because the capability axis is not a chain: only a
     * side-by-side reading shows that an author LOSES Release while a promoter loses everything
     * else (D-R2, "the DevOps guys who can only release").
     */
    @Test
    fun `the explorer, the datasources screen and the API console render exactly each role's verbs`() {
        startTrace()

        // --- viewer: reads everything, changes nothing, and still executes (D-R3).
        val viewer = signIn("rbv-viewer", author = false, promoter = false, admin = false)
        viewer.page.navigate("$baseUrl/pipelines")
        viewer.page.waitForSelector("[data-role]")
        viewer.roleBadge() shouldBe "viewer"
        viewer.verbs() shouldContainExactly emptyList()

        viewer.page.navigate("$baseUrl/datasources")
        viewer.page.waitForSelector(".app-page-h")
        // §7.6 puts register/edit/delete/test on ws_admin — a viewer sees the list and no verb.
        viewer.verbs() shouldContainExactly emptyList()

        viewer.page.navigate("$baseUrl/api-console")
        viewer.page.waitForSelector(".app-page-h")
        // O-2: viewers never mint keys. Revoke is a different rung (§7.6's MANAGE_OWN_API_KEYS
        // is `view`), so it is present whenever there is a live key to revoke — there is none
        // in a fresh session, and its absence here is data, not policy.
        viewer.verbs().contains("key-create") shouldBe false
        viewer.close()

        // --- author: the authoring verbs, and no Release anywhere.
        val author = signIn("rbv-author", author = true, promoter = false, admin = false)
        author.page.navigate("$baseUrl/pipelines")
        author.page.waitForSelector("[data-role]")
        author.roleBadge() shouldBe "author"
        author.page.navigate("$baseUrl/api-console")
        author.page.waitForSelector(".app-page-h")
        author.verbs().contains("key-create") shouldBe true

        author.page.navigate("$baseUrl/datasources")
        author.page.waitForSelector(".app-page-h")
        // Still not an author's: a datasource is a live credential (§7.6, ws_admin).
        author.verbs().contains("datasource-register") shouldBe false
        author.close()

        // --- promoter: Release and Switch, and none of the authoring verbs.
        val promoter = signIn("rbv-promoter", author = false, promoter = true, admin = false)
        promoter.page.navigate("$baseUrl/pipelines")
        promoter.page.waitForSelector("[data-role]")
        promoter.roleBadge() shouldBe "promoter"
        promoter.page.navigate("$baseUrl/api-console")
        promoter.page.waitForSelector(".app-page-h")
        // A promoter is not an author, and keys are an author's verb.
        promoter.verbs().contains("key-create") shouldBe false
        promoter.close()

        // --- workspace admin: the datasource verbs, and Members with the three checkboxes.
        val admin = signIn("rbv-admin", author = true, promoter = false, admin = true)
        admin.page.navigate("$baseUrl/pipelines")
        admin.page.waitForSelector("[data-role]")
        admin.roleBadge() shouldBe "admin"

        admin.page.navigate("$baseUrl/datasources")
        admin.page.waitForSelector(".app-page-h")
        admin.verbs().contains("datasource-register") shouldBe true
        // Grants is a SUPER admin's verb, one rung up (§7.6 MANAGE_DATASOURCE_GRANTS).
        admin.verbs().contains("datasource-grants") shouldBe false
        admin.close()
    }

    /**
     * §C.1 — the members screen a workspace admin gets: three checkboxes per row, three on the
     * add form, and the flags landing in `workspace_members`. Asserted at the ROW, because "did
     * the checkbox reach the database" is the only question that matters and a rendered form
     * proves nothing about it.
     *
     * The fixture builds its OWN workspace rather than using the shared `default`: every suite
     * on this box seeds members into `default`, so a rule about "the members of a workspace"
     * asserted there would be reading other tests' rows.
     */
    @Test
    fun `a workspace admin adds a member with flags and then replaces them`() {
        startTrace()
        val workspace = "rbv-ws-" + suffix()
        val adminUser = seedRoleUser("rbv-mem-admin", author = false, promoter = false, admin = false)
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

        // Add-with-flags: the pre-114 form posted `email` only, so every member added from the
        // UI arrived a viewer whatever the operator meant.
        admin.page.fill("$section form[action$='/members'] input[name=email]", member)
        admin.page.check("$section form[action$='/members'] input[name=promoter]")
        admin.page.click("$section [data-verb='member-add']")
        admin.page.waitForSelector("$section tr:has-text('$member')")

        flagsOf(workspace, member) shouldBe Triple(false, true, false)

        // …and a REPLACE through the row's own form: ticking admin, with the SERVER's
        // normalisation making it an author too (V23's chk_workspace_member_admin_authors —
        // stated once, by the constraint, and never re-spelled in the binder).
        admin.page.navigate("$baseUrl/workspaces")
        val row = "$section tr:has-text('$member')"
        admin.page.waitForSelector("$row input[data-flag=admin]")
        admin.page.check("$row input[data-flag=admin]")
        admin.page.click("$row [data-verb='member-flags']")
        admin.page.waitForSelector("$row input[data-flag=admin]:checked")

        flagsOf(workspace, member) shouldBe Triple(true, true, true)
        admin.close()
    }

    /**
     * The last-admin rule as the USER meets it: a 409 from the service arrives as the §5.1
     * toast that names the remedy ("Give another member admin first"), not as an error page and
     * not as a silent no-op. The refusal is the SERVER's — what this asserts is that the screen
     * tells the truth about it, and that a refused write does not half-apply.
     */
    @Test
    fun `demoting the only admin renders the last-admin toast and leaves the row alone`() {
        startTrace()
        val workspace = "rbv-last-" + suffix()
        val adminUser = seedRoleUser("rbv-last-admin", author = false, promoter = false, admin = false)
        seedWorkspaceAdministeredBy(workspace, adminUser.email)
        val admin = openSession(adminUser)
        admin.switchTo(workspace)

        val row = "section[data-workspace='$workspace'] tr:has-text('${admin.email}')"
        admin.page.waitForSelector("$row input[data-flag=admin]")
        admin.page.uncheck("$row input[data-flag=admin]")
        admin.page.click("$row [data-verb='member-flags']")

        admin.page.waitForSelector(".ds-toast-danger")
        admin.page.locator(".ds-toast-danger").first().innerText().lowercase()
            .contains("at least one admin") shouldBe true

        // The row is untouched: a refused write must not half-apply.
        flagsOf(workspace, admin.email).third shouldBe true
        admin.close()
    }

    // ------------------------------------------------------------------ helpers

    private inner class RoleSession(
        val page: Page,
        val email: String,
        private val session: Session,
    ) {
        /** Every `data-verb` the current page renders, in document order. */
        fun verbs(): List<String> =
            page
                .locator("[data-verb]")
                .all()
                .map { it.getAttribute("data-verb") }

        /** The shell badge beside the switcher's workspace name (§C.4). */
        fun roleBadge(): String = page.locator("[data-role]").first().getAttribute("data-role")

        /**
         * Enters [workspace] through the screen's own Switch verb, then lands back on
         * `/workspaces` with the switch APPLIED.
         *
         * Not `BrowserSuite.enterWorkspace`: that one waits for `**\/dashboard` after driving
         * the rail's switcher, and a session already sitting on `/dashboard` satisfies that
         * wait instantly — the assertion passes before the POST has been answered, and the
         * next navigation races the re-minted cookie. Switching FROM `/workspaces` makes the
         * redirect a real URL change, so the wait has something to wait for.
         */
        fun switchTo(workspace: String) {
            page.navigate("$baseUrl/workspaces")
            val form = "form[action*='/workspace/switch']:has(input[value='$workspace'])"
            page.waitForSelector("$form [data-verb='workspace-switch']")
            page.click("$form [data-verb='workspace-switch']")
            page.waitForURL("**/dashboard")
            page.navigate("$baseUrl/workspaces")
        }

        fun close() = session.close()
    }

    private fun signIn(
        slug: String,
        author: Boolean,
        promoter: Boolean,
        admin: Boolean,
    ): RoleSession = openSession(seedRoleUser(slug, author, promoter, admin))

    private fun seedRoleUser(
        slug: String,
        author: Boolean,
        promoter: Boolean,
        admin: Boolean,
    ): LocalUser =
        seedLocalUser(
            uniqueEmail("$slug-" + suffix()),
            generatedPassword("pw"),
            mustChange = false,
            // NOT an instance super admin: a super admin holds every capability in every
            // workspace (D-R8), which would make each of these four sessions identical.
            isAdmin = false,
            author = author,
            promoter = promoter,
            admin = admin,
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

    /** A member of `default` and nothing else, with no flags — someone to add somewhere. */
    private fun viewerUser(slug: String): String {
        val email = uniqueEmail("$slug-" + suffix())
        seedLocalUser(email, generatedPassword("pw"), mustChange = false, isAdmin = false, author = false, admin = false)
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
                    INSERT INTO workspace_members (workspace_id, user_id, author, promoter, admin)
                    SELECT w.id, u.id, TRUE, FALSE, TRUE
                      FROM workspaces w, users u
                     WHERE w.name = '$name' AND u.email = '$adminEmail'
                    """.trimIndent(),
                )
            }
        }

    /** The stored `(author, promoter, admin)` of [email]'s membership in [workspace]. */
    private fun flagsOf(
        workspace: String,
        email: String,
    ): Triple<Boolean, Boolean, Boolean> =
        DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT m.author, m.promoter, m.admin
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
                            Triple(rows.getBoolean(1), rows.getBoolean(2), rows.getBoolean(3))
                        }
                    }
            }

    private fun suffix(): String = generatedPassword("s").take(8).lowercase()

}
