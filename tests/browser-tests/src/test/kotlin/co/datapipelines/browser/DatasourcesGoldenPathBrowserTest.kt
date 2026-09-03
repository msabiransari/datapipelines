package co.datapipelines.browser

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
}
