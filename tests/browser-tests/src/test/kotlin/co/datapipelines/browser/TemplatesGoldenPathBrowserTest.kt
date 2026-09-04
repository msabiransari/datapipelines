package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Golden path 4 of the release checklist: templates — the tree's empty state, creation
 * through the modal (the SAME validator + repository as the REST surface), the leaf
 * appearing in the refreshed tree, and the two-pane selection contract (the versions
 * pane swaps into #template-detail while the tree stays untouched).
 */
class TemplatesGoldenPathBrowserTest : BrowserSuite() {
    private fun loginReadyUser(): LocalUser {
        val user =
            seedLocalUser(
                uniqueEmail("tpl-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("tplws-" + generatedPassword("w").take(8).lowercase())
        return user
    }

    @Test
    fun `a fresh workspace sees the tree's empty state`() {
        startTrace()
        loginReadyUser()

        page.navigate("$baseUrl/templates")
        page.waitForURL("**/templates")
        page.locator(".ds-empty-title").first().innerText() shouldContain "No templates yet"
    }

    @Test
    fun `create through the modal puts the template in the tree and selects it`() {
        startTrace()
        loginReadyUser()
        val name = "browser_tpl_" + generatedPassword("t").take(6).lowercase()

        page.navigate("$baseUrl/templates")
        page.click("text=Create Template")
        page.locator("#create-template-modal").isVisible shouldBe true

        page.fill("#create-template-modal input[name=name]", name)
        page.selectOption("#create-template-type", "sql")
        page.selectOption("#create-template-dialect", "POSTGRES")
        page.fill("#create-template-modal textarea[name=body]", "SELECT * FROM demo WHERE id = ${'$'}{id}")
        // NOTE: the form marks `description` browser-required while the server treats it
        // optional — filled here so the flow proceeds; the drift is flagged to the round.
        page.fill("#create-template-modal input[name=description]", "browser test template")
        val create =
            page.waitForResponse("**/partials/templates") {
                page.click("#create-template-modal button[type=submit]")
            }
        create.status() shouldBe 200

        // The OOB swap refreshes the tree: the leaf is on the page now.
        page.waitForSelector("button.tpl-leaf")
        page
            .locator(
                "button.tpl-leaf",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText(name),
            ).waitFor()

        // Selection swaps the versions pane into #template-detail — the two-pane contract.
        page.waitForResponse("**/partials/templates/versions**") {
            page
                .locator(
                    "button.tpl-leaf",
                    com.microsoft.playwright.Page
                        .LocatorOptions()
                        .setHasText(name),
                ).click()
        }
        page.waitForSelector("#template-detail")
        page.locator("#template-detail").innerText() shouldContain name
    }
}
