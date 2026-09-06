package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Golden path 4 of the release checklist: templates — the tree's empty state, creation
 * through the modal (the SAME validator + repository as the REST surface), the leaf
 * appearing in the refreshed tree, and the two-pane selection contract (the versions
 * pane swaps into #template-detail while the tree stays untouched).
 *
 * 077 changed the shape of this path, not its meaning. A name carries a FOLDER now (§4.1),
 * so the create posts `test/<leaf>` and the refreshed ROOT shows a **folder**, not a leaf —
 * the leaf lives one level down and the test expands the folder to reach it, which is the
 * journey a real user now takes. Both halves would have gone red silently otherwise: the
 * create would 400 on the flat name, and the root would never grow a `tpl-leaf`.
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
        // 077: a folder is mandatory. `test/` is the sanctioned scratch folder (§15.2), which
        // is exactly what a browser fixture is.
        val leaf = "browser_tpl_" + generatedPassword("t").take(6).lowercase()
        val name = "test/$leaf"

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

        // The OOB swap refreshes the ROOT level, and since 077 that level holds FOLDERS ONLY:
        // what appears is the `test` folder, never the leaf. Asserting the absence too, because
        // "the leaf is not visible yet" is the actual change and a missing assertion here is
        // how the old expectation would creep back.
        page.waitForSelector("details.tpl-folder")
        page.locator("#template-list-wrapper button.tpl-leaf").count() shouldBe 0
        val folder =
            page.locator(
                "summary.tpl-summary",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText("test"),
            )
        folder.waitFor()

        // Expanding the folder issues ONE request for ONE level (§9.1) — and THAT level has
        // the leaf, labelled by its last segment with the full path on `title` (§9.4).
        // A PREDICATE, not a glob: Playwright treats `?` as a wildcard in a URL glob, so
        // "**/partials/templates?prefix=test**" does not reliably match the query string the
        // fragment renders (`?prefix=test&dialect=&type=`).
        page.waitForResponse(
            { response -> response.url().contains("/partials/templates") && response.url().contains("prefix=test") },
            { folder.click() },
        )
        val leafButton =
            page.locator(
                "button.tpl-leaf",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText(leaf),
            )
        leafButton.waitFor()
        leafButton.locator("span.tpl-label").getAttribute("title") shouldBe name

        // Selection swaps the versions pane into #template-detail — the two-pane contract.
        page.waitForResponse(
            { response -> response.url().contains("/partials/templates/versions") },
            { leafButton.click() },
        )
        page.waitForSelector("#template-detail")
        page.locator("#template-detail").innerText() shouldContain leaf
    }
}
