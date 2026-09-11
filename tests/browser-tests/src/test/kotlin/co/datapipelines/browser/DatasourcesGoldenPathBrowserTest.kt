package co.datapipelines.browser

import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Golden path 3 of the release checklist: the datasources screen — empty state, register
 * through the modal (the same shared-rules + save boundary as REST, ui-screens §4.5),
 * the row appearing in the refreshed list, and the connection probe's toast.
 *
 * Uses H2 in-memory (`jdbc:h2:mem:`) — the bundled driver, no external database, and a
 * connect probe that genuinely succeeds. Datasource names are generated per test: the
 * module shares one database, so no two tests may claim the same name.
 */
class DatasourcesGoldenPathBrowserTest : BrowserSuite() {
    private fun loginReadyUser(): LocalUser {
        val user =
            seedLocalUser(
                uniqueEmail("ds-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        // Self-serve default: a first-login user owns no workspace until they create one.
        createWorkspace("ws-" + generatedPassword("w").take(8).lowercase())
        return user
    }

    @Test
    fun `a fresh workspace sees the empty state`() {
        startTrace()
        loginReadyUser()

        page.navigate("$baseUrl/datasources")
        page.waitForURL("**/datasources")
        page.locator(".ds-empty-title").first().innerText() shouldContain "No datasources yet"
    }

    @Test
    fun `register through the modal creates the datasource and refreshes the list`() {
        startTrace()
        loginReadyUser()
        val name = "browser-" + generatedPassword("ds").take(10).lowercase()

        page.navigate("$baseUrl/datasources")
        page.click("text=Register Datasource")
        page.locator("#register-modal").isVisible shouldBe true

        page.fill("#register-modal input[name=name]", name)
        page.selectOption("#register-modal select[name=dialect]", "H2")
        page.fill("#register-modal input[name=jdbcUrl]", "jdbc:h2:mem:${name.replace("-", "_")}")
        page.fill("#register-modal input[name=username]", "sa")
        page.fill("#register-modal input[name=password]", "sa")
        // The register is an ASYNC htmx post — the click runs inside the wait; the 200's
        // OOB list swap is the release signal, never a sleep.
        val register =
            page.waitForResponse("**/partials/datasources") {
                page.click("#register-modal button[type=submit]")
            }
        register.status() shouldBe 200

        // The OOB swap refreshes the list: the new row's visible cell is the proof.
        page
            .locator(
                "td",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText(name),
            ).first()
            .waitFor()
        page.content() shouldContain name

        // 118 §7.3 — Facts is every member's verb (a read): the dialog opens on the new row
        // and, on a store nobody has recorded into, shows its empty state.
        val row =
            page
                .locator(
                    "tr",
                    com.microsoft.playwright.Page
                        .LocatorOptions()
                        .setHasText(name),
                ).first()
        row.locator("[data-read=datasource-facts]").click()
        page.waitForSelector("#ds-facts-modal")
        page.locator("#ds-facts").innerText() shouldContain "Nothing learned yet"
        page.locator("#ds-facts-modal h2").innerText() shouldContain name
    }

    @Test
    fun `the connection probe reports success as a toast`() {
        startTrace()
        loginReadyUser()
        val name = "probe-" + generatedPassword("ds").take(10).lowercase()

        // Register first (this test's own datasource; its own precondition).
        page.navigate("$baseUrl/datasources")
        page.click("text=Register Datasource")
        page.fill("#register-modal input[name=name]", name)
        page.selectOption("#register-modal select[name=dialect]", "H2")
        page.fill("#register-modal input[name=jdbcUrl]", "jdbc:h2:mem:${name.replace("-", "_")}")
        page.fill("#register-modal input[name=username]", "sa")
        page.fill("#register-modal input[name=password]", "sa")
        page.waitForResponse("**/partials/datasources") {
            page.click("#register-modal button[type=submit]")
        }
        page
            .locator(
                "td",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText(name),
            ).first()
            .waitFor()

        // The probe button targets the toast stack (ui-screens §5.1).
        val row =
            page
                .locator(
                    "tr",
                    com.microsoft.playwright.Page
                        .LocatorOptions()
                        .setHasText(name),
                ).first()
        val probe =
            page.waitForResponse("**/partials/datasources/*/test") {
                row.locator("button").first().click()
            }
        probe.status() shouldBe 200
        page.waitForSelector("#toast .ds-toast-success")
        page.locator("#toast").innerText() shouldContain "Connection succeeded"
    }

    // ------------------------------------------------------------------ 094 §A: the pool section

    @Test
    fun `the register modal's pool section is collapsed, and expands to prefilled defaults`() {
        startTrace()
        loginReadyUser()

        page.navigate("$baseUrl/datasources")
        page.click("text=Register Datasource")

        // Collapsed: the summary is on screen, the inputs inside are not.
        val section = page.locator("#register-modal details").first()
        section.locator("summary").innerText() shouldContain "Connection pool"
        page.locator("#register-modal input[name='pool.maximumPoolSize']").isVisible shouldBe false

        section.locator("summary").click()

        // Expanded: every catalogued key, prefilled with the effective default, and the help
        // line that says WHICH layer supplied it — the fact a number alone cannot carry.
        page.locator("#register-modal input[name='pool.maximumPoolSize']").inputValue() shouldBe "10"
        page.locator("#register-modal input[name='pool.minimumIdle']").inputValue() shouldBe "2"
        page.locator("#ds-pool-fields").innerText() shouldContain "this server"
        page.locator("#ds-pool-fields").innerText() shouldContain "HikariCP"
        // readOnly is mirrored, never editable: it is a §5.6-refused pool property.
        page.locator("#ds-pool-readonly").isDisabled shouldBe true

        // Changing the dialect re-fetches the section, because the effective default is the
        // DIALECT's to decide. This is also the only test that drives the fragment endpoint,
        // whose view name is a parameterized Thymeleaf fragment — a shape a unit test would
        // assert as a string and never actually render.
        val refetched =
            page.waitForResponse("**/partials/datasources/pool-fields**") {
                page.selectOption("#ds-register-dialect", "POSTGRES")
            }
        refetched.status() shouldBe 200
        page.locator("#ds-pool-fields input[name='pool.maximumPoolSize']").inputValue() shouldBe "10"
        // The swapped-in fields keep the create dialog's id prefix, so their labels still point
        // at real inputs — `innerHTML` into the page's own wrapper, not a root the swap replaces.
        page.locator("#ds-maximumPoolSize").count() shouldBe 1
    }

    @Test
    fun `an out-of-range pool value is refused inline, and the modal stays open`() {
        startTrace()
        loginReadyUser()
        val name = "poolbad-" + generatedPassword("ds").take(10).lowercase()

        page.navigate("$baseUrl/datasources")
        page.click("text=Register Datasource")

        // The dialect is chosen FIRST and its swap awaited: the select re-fetches the pool
        // section on change, so a pool value typed before the swap lands is wiped by it. That
        // is correct behaviour (the defaults are per dialect) and a real race for any caller
        // that types then switches — the reason this fills the pool field last.
        page.waitForResponse("**/partials/datasources/pool-fields**") {
            page.selectOption("#register-modal select[name=dialect]", "H2")
        }
        page.fill("#register-modal input[name=name]", name)
        page.fill("#register-modal input[name=jdbcUrl]", "jdbc:h2:mem:${name.replace("-", "_")}")
        page.fill("#register-modal input[name=username]", "sa")
        page.fill("#register-modal input[name=password]", "sa")
        page.locator("#register-modal details summary").first().click()
        // 5 s is legal to HikariCP's SETTER and would be silently rewritten to 30 minutes.
        page.fill("#register-modal input[name='pool.maxLifetime']", "5000")

        val response =
            page.waitForResponse("**/partials/datasources") {
                page.click("#register-modal button[type=submit]")
            }

        response.status() shouldBe 400
        page.locator("#register-result").innerText() shouldContain "maxLifetime"
        // A refusal must not close the modal over the operator's work (022/F9).
        page.locator("#register-modal").isVisible shouldBe true
    }

    // ------------------------------------------------------------------ 094 §A: edit

    @Test
    fun `edit saves a retuned pool value and leaves the credential alone`() {
        startTrace()
        loginReadyUser()
        val name = registerThrough("edit-" + generatedPassword("ds").take(10).lowercase())

        rowFor(name).locator("button", hasText("Edit")).click()
        page.waitForSelector("#ds-edit-modal")
        page.locator("#ds-edit-modal details summary").first().click()
        page.fill("#ds-edit-modal input[name='pool.maximumPoolSize']", "25")

        val saved =
            page.waitForResponse("**/partials/datasources/$name") {
                page.click("#ds-edit-modal button[type=submit]")
            }
        saved.status() shouldBe 200

        // Shape A: the dialog closes on the success node, the list refreshes, the toast lands.
        page.waitForSelector("#toast .ds-toast-success")
        page.locator("#toast").innerText() shouldContain "updated"
        page.locator("#ds-dialog").innerHTML() shouldBe ""

        // Re-opening shows the stored value, now marked as this datasource's own.
        rowFor(name).locator("button", hasText("Edit")).click()
        page.waitForSelector("#ds-edit-modal")
        page.locator("#ds-edit-modal details summary").first().click()
        page.locator("#ds-edit-modal input[name='pool.maximumPoolSize']").inputValue() shouldBe "25"
        // Scoped to the dialog: only the REGISTER modal's section carries #ds-pool-fields (it is
        // the dialect select's swap target), so an unscoped locator would match two elements the
        // moment both are in the document — which is exactly what this assertion caught.
        page.locator("#ds-edit-modal #ds-edit-maximumPoolSize-help").innerText() shouldContain "Set on this datasource"
    }

    // ------------------------------------------------------------------ 094 §B: delete

    @Test
    fun `an unused datasource is deleted from its confirm dialog`() {
        startTrace()
        loginReadyUser()
        val name = registerThrough("del-" + generatedPassword("ds").take(10).lowercase())

        rowFor(name).locator("button", hasText("Delete")).click()
        page.waitForSelector("#ds-delete-modal")
        page.locator("#ds-delete-confirm-text").innerText() shouldContain name

        val deleted =
            page.waitForResponse("**/partials/datasources/$name/delete") {
                page.click("#ds-delete-modal button[type=submit]")
            }
        deleted.status() shouldBe 200

        page.waitForSelector("#toast .ds-toast-success")
        page.locator("#toast").innerText() shouldContain "deleted"
        // The OOB list refresh is what removes the row; nothing navigates.
        page.waitForSelector(
            "tr:has-text('$name')",
            com.microsoft.playwright.Page
                .WaitForSelectorOptions()
                .setState(WaitForSelectorState.DETACHED),
        )
    }

    @Test
    fun `a datasource a pipeline uses is refused, with the usage list and no confirm button`() {
        startTrace()
        loginReadyUser()
        val name = registerThrough("used-" + generatedPassword("ds").take(10).lowercase())
        val pipeline = "test/uses_" + name.replace("-", "_")
        seedTemplateAndPipeline(name, pipeline) shouldBe 201

        page.navigate("$baseUrl/datasources")
        rowFor(name).locator("button", hasText("Delete")).click()
        page.waitForSelector("#ds-delete-modal")

        val dialog = page.locator("#ds-delete-modal").innerText()
        dialog shouldContain "cannot be deleted"
        dialog shouldContain pipeline
        dialog shouldContain "read_it"
        // D55: the seeded pipeline is freshly created, so its v1 is a DRAFT — and a draft pin is a
        // real reference (the usage list spans "any pipeline version, ever", templates.md §5.4),
        // which is exactly why the delete is refused. The label states which, and it was only ever
        // "released" here because creation used to release.
        dialog shouldContain "(v1 draft)"
        // THE point: the refusal cannot be clicked past — there is no button on this branch.
        page.locator("#ds-delete-modal .ds-button-danger").count() shouldBe 0
    }

    @Test
    fun `a member cannot delete a GLOBAL datasource, and the dialog says so instead of offering one`() {
        // The D8 rule as a PERSON meets it: the dialog answers the permission question before the
        // usage question, so a member is never shown a confirm whose POST would refuse.
        startTrace()
        loginReadyUser()
        // D-R7: an INSTANCE datasource ("global" on the wire) is owned by no workspace and
        // therefore visible in none — not even to the super admin who registered it. It is a
        // GRANT that makes it visible, which is why this registration does not wait for a row.
        val shared = registerThrough("shared-" + generatedPassword("ds").take(10).lowercase(), global = true, expectRow = false)

        val member =
            seedLocalUser(
                uniqueEmail("dsmember-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
            )
        val session = newSession()
        try {
            session.page.navigate("$baseUrl/login")
            session.page.fill("#login-email", member.email)
            session.page.fill("#login-password", member.oneTimePassword)
            session.page.click("form button[type=submit]")
            session.page.waitForURL("**/dashboard")
            // The member works in `default` (the fixture's membership) and cannot create a
            // workspace at all since D-R11 — creating is the super admin's act. So the grant
            // that makes the instance datasource visible to them names `default`, and it is
            // made through the product's own verb by the super admin who registered it.
            grantToDefaultWorkspace(shared)

            session.page.navigate("$baseUrl/datasources")
            session.page
                .locator(
                    "tr",
                    com.microsoft.playwright.Page
                        .LocatorOptions()
                        .setHasText(shared),
                ).first()
                .locator("button", hasText("Delete"))
                .click()
            session.page.waitForSelector("#ds-delete-modal")

            session.page.locator("#ds-delete-forbidden").innerText() shouldContain "requires admin"
            session.page.locator("#ds-delete-modal .ds-button-danger").count() shouldBe 0
            // Not even the usage list: the permission question is answered first, so the
            // reverse scan never runs for a caller who could not act on its answer anyway.
            session.page.locator("#ds-delete-usages").count() shouldBe 0
        } finally {
            session.close()
        }
    }

    /** Registers a datasource through the modal and returns its name, with the row on screen. */
    private fun registerThrough(
        name: String,
        global: Boolean = false,
        expectRow: Boolean = true,
    ): String {
        page.navigate("$baseUrl/datasources")
        page.click("text=Register Datasource")
        page.fill("#register-modal input[name=name]", name)
        page.selectOption("#register-modal select[name=dialect]", "H2")
        page.fill("#register-modal input[name=jdbcUrl]", "jdbc:h2:mem:${name.replace("-", "_")}")
        page.fill("#register-modal input[name=username]", "sa")
        page.fill("#register-modal input[name=password]", "sa")
        if (global) page.check("#register-modal input[name=global]")
        page.waitForResponse("**/partials/datasources") {
            page.click("#register-modal button[type=submit]")
        }
        if (expectRow) rowFor(name).waitFor()
        return name
    }

    /**
     * `POST /api/v1/datasources/{name}/grants/default` as the signed-in super admin — the
     * grants verb (D-R7), driven from the page so the session cookie and the CSRF
     * double-submit pair are the real ones. There is no grants SCREEN yet; that is round 2.
     */
    private fun grantToDefaultWorkspace(name: String) {
        val status =
            page.evaluate(
                """async (name) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch('/api/v1/datasources/' + name + '/grants/default', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                  });
                  return res.status;
                }""",
                name,
            )
        status shouldBe 200
    }

    private fun rowFor(name: String) =
        page
            .locator(
                "tr",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText(name),
            ).first()

    private fun hasText(text: String) =
        com.microsoft.playwright.Locator
            .LocatorOptions()
            .setHasText(text)

    /**
     * A released pipeline whose one node reads [datasource] — the reference the delete guard
     * finds. Created in-page over REST so the session cookie and the dp_csrf double-submit
     * pair apply, the way the editor suites do it.
     */
    private fun seedTemplateAndPipeline(
        datasource: String,
        pipeline: String,
    ): Int =
        page.evaluate(
            """async ([datasource, pipeline]) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const headers = {
                'Content-Type': 'application/json',
                'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '',
              };
              const templateId = 'test/' + pipeline.split('/')[1] + '.sql';
              await fetch('/api/v1/templates', {
                method: 'POST', credentials: 'same-origin', headers,
                body: JSON.stringify({
                  id: templateId, dialect: 'H2', display_name: 'browser usage probe',
                  description: 'Reads one literal so the pipeline has a node.',
                  imports: [], body: 'SELECT 1 AS v',
                }),
              });
              const res = await fetch('/api/v1/pipelines', {
                method: 'POST', credentials: 'same-origin', headers,
                body: JSON.stringify({
                  name: pipeline,
                  display_name: pipeline,
                  nodes: [{
                    id: 'read_it', type: 'DQL', source: datasource,
                    template: { id: templateId, version: 1 },
                    output: { target: 'caller' }, depends_on: [],
                  }],
                }),
              });
              return res.status;
            }""",
            listOf(datasource, pipeline),
        ) as Int
}
