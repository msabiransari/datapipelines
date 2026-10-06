package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Golden path 4 of the release checklist: templates — the catalog's empty state, creation
 * through the modal (the SAME validator + repository as the REST surface), the leaf
 * appearing in the refreshed catalog, and the leaf's NAVIGATION into its workspace.
 *
 * 077 changed the shape of this path (a name carries a FOLDER; the create posts
 * `test/<leaf>`), and #398 changed its surface: the refreshed list is the CATALOG's flat
 * rows (the leaf appears there at once — the catalog lists every template), and the leaf a
 * reader reaches through the SIDEBAR's tree is a full navigation into the template
 * workspace. Both halves would have gone red silently otherwise: the create would 400 on
 * the flat name, and the catalog row's href is the one place the workspace URL is pinned
 * end to end.
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
    fun `create through the modal puts the template in the catalog and opens it in the workspace`() {
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

        // The OOB swap refreshes the CATALOG's list (#398): the new leaf is a row at once —
        // the catalog lists every template — a link to its workspace with the full path on
        // `title` (§9.4).
        val row =
            page.locator(
                "#template-list-wrapper a.tpl-result",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText(name),
            )
        row.waitFor()
        row.getAttribute("href") shouldBe "/templates/$name"

        // The SIDEBAR's tree carries the folder-and-leaf shape: the branch's root level holds
        // FOLDERS ONLY (077), the `test` folder expands with ONE prefix request (§9.1), and
        // its leaf is a link with the same label rule.
        page.click("[data-nav-tree-reveal='templates']")
        page.waitForSelector("#nav-tree-templates [aria-expanded]")
        val folder =
            page.locator(
                "#nav-tree-templates [data-tree-key='folder:test'] > .dp-tree-line button",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText("test"),
            )
        page.waitForResponse(
            { response -> response.url().contains("/api/v1/templates/tree") && response.url().contains("parent=test") },
            { folder.click() },
        )
        val leafLink =
            page.locator(
                "#nav-tree-templates a.dp-tree-activate",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText(leaf),
            )
        leafLink.waitFor()
        leafLink.locator("span[title]").getAttribute("title") shouldBe name

        // A leaf NAVIGATES — a full document into the template workspace.
        leafLink.click()
        page.waitForURL("**/templates/$name")
        page.waitForSelector(".tw-root")
        page.locator(".tw-crumb-name").innerText() shouldContain leaf
    }
}
