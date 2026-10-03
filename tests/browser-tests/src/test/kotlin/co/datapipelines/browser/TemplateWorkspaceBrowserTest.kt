package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * #398 — the TEMPLATE WORKSPACE in a real browser (the pipelines mould of
 * `PipelineWorkspaceVersionBrowserTest`): the resolution states a URL can ask for, R5's
 * read-only rule on a selected RELEASED version, the author's kept affordances (Q1(b)),
 * the from-aware lifecycle redirect, and the workspace's own width behaviour.
 *
 * Every walk rides the app's own doors (the catalog's search, its rows, the version
 * selector's links), the session's cookie and the dp_csrf pair carry the REST seeding, and
 * the console/CSP collectors are the suite's.
 */
class TemplateWorkspaceBrowserTest : BrowserSuite() {
    private fun loginReadyUser(slug: String): String {
        val user =
            seedLocalUser(
                uniqueEmail(slug + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        val workspace = "tlw-" + generatedPassword("w").take(8).lowercase()
        createWorkspace(workspace)
        watchConsole()
        return workspace
    }

    /** One REST call in-page, with the session's CSRF pair (every non-GET carries it). */
    private fun api(
        method: String,
        path: String,
        body: String?,
        ifMatch: String? = null,
    ): Pair<Int, String> {
        val answer =
            page.evaluate(
                """async ([method, path, body, ifMatch]) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json' };
                  if (ifMatch) headers['If-Match'] = ifMatch;
                  if (method !== 'GET') headers['DP-CSRF-Token'] = csrf ? decodeURIComponent(csrf[1]) : '';
                  const res = await fetch(path, { method, credentials: 'same-origin', headers, body });
                  let text = '';
                  try { text = await res.text(); } catch (e) {}
                  return [res.status, text];
                }""",
                arrayOf(method, path, body, ifMatch),
            ) as List<*>
        return (answer[0] as Number).toInt() to (answer[1] as String)
    }

    private fun must(
        method: String,
        path: String,
        body: String?,
        ifMatch: String? = null,
    ): String {
        val (status, text) = api(method, path, body, ifMatch)
        check(status == 200 || status == 201) { "$method $path -> $status: ${text.take(300)}" }
        return text
    }

    private fun hashOf(text: String): String = Regex(""""body_hash"\s*:\s*"([^"]+)"""").find(text)!!.groupValues[1]

    private fun sqlBody(marker: String): String = "SELECT '$marker' AS body"

    /** A released v1 + a newer release v2 + a DRAFT v3, each with its OWN body. */
    private fun seedThreeVersions(name: String) {
        val created = must("POST", "/api/v1/templates", templateJson(name, sqlBody("v1")))
        // Release v1.
        must("POST", "/api/v1/templates/release", """{"name":"$name"}""", ifMatch = hashOf(created))
        // Draft v2 over the v1 release, then release it: v2 is the newer release.
        val v1 = must("GET", "/api/v1/templates?name=$name", null)
        val v2Draft = must("PUT", "/api/v1/templates", templateJson(name, sqlBody("v2")), ifMatch = hashOf(v1))
        must("POST", "/api/v1/templates/release", """{"name":"$name"}""", ifMatch = hashOf(v2Draft))
        // Draft v3 over the v2 release.
        val v2 = must("GET", "/api/v1/templates?name=$name", null)
        must("PUT", "/api/v1/templates", templateJson(name, sqlBody("v3")), ifMatch = hashOf(v2))
    }

    private fun templateJson(
        name: String,
        body: String,
    ): String =
        """{"id":"$name","type":"sql","dialect":"POSTGRES","display_name":"${name.substringAfterLast('/')}",""" +
            """"description":"398 workspace fixture","body":"$body"}"""

    private fun openCatalog(query: String) {
        page.navigate("$baseUrl/templates?q=$query")
        page.waitForSelector("#template-list-wrapper a.tpl-result")
    }

    private fun openWorkspace(
        name: String,
        query: String? = null,
    ) {
        openCatalog(name.substringAfterLast('/'))
        page
            .locator(
                "a.tpl-result",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText(name),
            ).first()
            .click()
        page.waitForURL("**/templates/$name**")
        page.waitForSelector(".tw-root")
        query?.let { q ->
            page.waitForFunction("(m) => document.querySelector('.tw-root')?.textContent?.includes(m)", q)
        }
    }

    private fun viewedVersion(): String = page.locator(".tw-root").getAttribute("data-viewed-version")

    private fun sourceBody(): String =
        page.evaluate(
            "() => document.getElementById('versionBody')?.textContent ?? document.getElementById('templateBody')?.value ?? ''",
        ) as String

    private val consoleErrors = mutableListOf<String>()

    private fun watchConsole() {
        page.onConsoleMessage { message -> if (message.type() == "error") consoleErrors += message.text() }
    }

    // ------------------------------------------------------------------ the resolution states

    @Test
    fun `the unqualified view is the ACTUAL current release - not the draft, not an older version`() {
        startTrace()
        loginReadyUser("tlwres")
        val name = "test/ws_res_${generatedPassword("n").take(6).lowercase()}.sql"
        seedThreeVersions(name)

        openWorkspace(name, query = "v2")
        viewedVersion() shouldBe "2"
        sourceBody() shouldContain "v2"
        // The draft exists and is one selector click away — never the default view.
        page.locator(".tw-version-link[data-version='3']").waitFor()
        consoleErrors shouldBe emptyList()
    }

    @Test
    fun `an explicit version shows that version's body - the release and the draft, visibly labelled`() {
        startTrace()
        loginReadyUser("tlwexp")
        val name = "test/ws_exp_${generatedPassword("n").take(6).lowercase()}.sql"
        seedThreeVersions(name)

        // v1: the older release, read-only.
        page.navigate("$baseUrl/templates/$name?version=1")
        page.waitForSelector(".tw-root")
        viewedVersion() shouldBe "1"
        sourceBody() shouldContain "v1"
        page.locator("#versionBody").waitFor()

        // v3: the DRAFT, explicit, visibly labelled — and for an author the editable surface.
        page.navigate("$baseUrl/templates/$name?version=3")
        page.waitForSelector(".tw-root")
        viewedVersion() shouldBe "3"
        page.locator(".tw-vchip").innerText() shouldContain "draft"
        page.locator("#templateBody").waitFor()
        consoleErrors shouldBe emptyList()
    }

    @Test
    fun `an absent version is the 404, a malformed one the 400, and the old editor URL redirects preserving the version`() {
        startTrace()
        loginReadyUser("tlwbad")
        val name = "test/ws_bad_${generatedPassword("n").take(6).lowercase()}.sql"
        must("POST", "/api/v1/templates", templateJson(name, sqlBody("v1")))

        // Absent version: the family 404, never a silent fallback to the release. (The
        // browser logs the document's own 404 as a console error by design — asserted on the
        // page shape, not on console silence.)
        consoleErrors.clear()
        page.navigate("$baseUrl/templates/$name?version=9")
        page.locator("#tw-root").count() shouldBe 0
        consoleErrors.clear()

        // Malformed version: the house 400.
        page.navigate("$baseUrl/templates/$name?version=two")
        page.locator("#tw-root").count() shouldBe 0
        consoleErrors.clear()

        // The compatibility redirect: the version (and a supported tab) survives.
        page.navigate("$baseUrl/templates/editor?name=$name&version=1&tab=versions")
        page.waitForURL("**/templates/$name?version=1&tab=versions")
        page.waitForSelector(".tw-root")
        page.waitForFunction("() => !document.getElementById('tw-pane-versions').hidden")
        consoleErrors shouldBe emptyList()
    }

    @Test
    fun `R5 in the browser - a selected RELEASED version never becomes editable and Edit lands on the draft`() {
        startTrace()
        loginReadyUser("tlwr5")
        val name = "test/ws_r5_${generatedPassword("n").take(6).lowercase()}.sql"
        seedThreeVersions(name)

        // The release view: read-only pane, Edit present for an author, no textarea.
        page.navigate("$baseUrl/templates/$name?version=2")
        page.waitForSelector(".tw-root")
        page.locator("#versionBody").waitFor()
        page.locator("#templateBody").count() shouldBe 0
        // The hidden Render tab's context JSON textarea is IN THE DOM for an author; the
        // read-only rule is about the SOURCE column.
        page.locator("#template-source textarea").count() shouldBe 0
        page.locator("[data-verb='template-edit']").waitFor()

        // Edit copies the release into a draft and lands on the workspace WITH the draft
        // explicit — a version-less redirect would land back on the read-only release.
        page.waitForResponse({ it.url().contains("/partials/templates/editor/edit") }) {
            page.locator("[data-verb='template-edit']").click()
        }
        page.waitForURL { url ->
            url.contains("/templates/") && url.contains("version=") && url.contains("tab=source")
        }
        page.waitForSelector("#templateBody")
        // The editable surface is the EXISTING draft (Edit opened it and wrote nothing — the
        // lifecycle rule 035/039): v3, never a second draft.
        viewedVersion() shouldBe "3"
        consoleErrors shouldBe emptyList()
    }

    @Test
    fun `the from-aware redirect lands the verb on the Versions tab with the flash`() {
        startTrace()
        loginReadyUser("tlwfrom")
        val name = "test/ws_from_${generatedPassword("n").take(6).lowercase()}.sql"
        seedThreeVersions(name)

        // The {R,D} shape: the header's one destructive is DISCARD; the draft's purge is its
        // row's ⋯ menu on the Versions tab (102 §B.1). The redirect contract is the same.
        openWorkspace(name, query = "v2")
        page.locator("#tw-tab-versions").click()
        val draftRow =
            page
                .locator("#tw-pane-versions tr[data-version-row]")
                .filter(
                    com.microsoft.playwright.Locator
                        .FilterOptions()
                        .setHasText("v3"),
                ).first()
        draftRow
            .locator("details.tplx-vmenu summary")
            .click()
        page
            .locator(
                "#tw-pane-versions .tplx-vmenu-list button",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText("Purge v3"),
            ).first()
            .click()
        page.waitForSelector("#tx-dialog [data-lifecycle-dialog='template-purge']")
        page.locator("#tx-dialog [data-confirm-input]").fill("v3")
        page.locator("#tx-dialog button[data-typed-confirm]").click()

        // The redirect lands on ?tab=versions and the flash toast names the purge.
        page.waitForURL("**/templates/$name?tab=versions&ok=draft_purged")
        page.waitForFunction("() => !document.getElementById('tw-pane-versions').hidden")
        page.locator("#toast .ds-toast").first().waitFor()
        page.locator("#toast").innerText() shouldContain "Draft purged"
        // The draft is gone from the workspace's own version set.
        page.locator(".tw-version-link[data-version='3']").count() shouldBe 0
        consoleErrors shouldBe emptyList()
    }

    @Test
    fun `the width behaviour the rail's retirement leaves behind - no sideways document, both themes`() {
        // The withdrawal guard (the 349 precedent): the side rail, its splitter and its
        // remembered width have no surface on the workspace — what must hold instead is the
        // simplest contract a page can carry: the document never grows sideways, at any width.
        startTrace()
        loginReadyUser("tlwwidth")
        val name = "test/ws_wide_${generatedPassword("n").take(6).lowercase()}.sql"
        must("POST", "/api/v1/templates", templateJson(name, sqlBody("wide")))

        // The retired editor's chrome is gone — named, so a resurrection is a named red.
        page.navigate("$baseUrl/templates/$name")
        page.waitForSelector(".tw-root")
        page.locator(".te-rail").count() shouldBe 0
        page.locator("[data-splitter='template-editor-side']").count() shouldBe 0

        listOf(390, 768, 1100, 1440, 1920).forEach { width ->
            page.setViewportSize(width, 900)
            page.navigate("$baseUrl/templates/$name")
            page.waitForSelector(".tw-root")
            val overflow =
                page.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number
            withClue("at ${width}px the document overflows by $overflow px") {
                overflow.toLong() shouldBe 0L
            }
        }

        // Both themes photograph the Source tab.
        listOf("light", "dark").forEach { theme ->
            ensureTheme(theme)
            val dir =
                java.nio.file.Paths
                    .get("build", "reports", "398-screenshots")
                    .also { it.toFile().mkdirs() }
            page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("398-workspace-$theme.png")))
        }
        consoleErrors shouldBe emptyList()
    }
}
