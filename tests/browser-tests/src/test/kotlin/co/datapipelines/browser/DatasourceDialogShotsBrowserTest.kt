package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The 094 dialogs, walked in BOTH modes and photographed at each state.
 *
 * A screenshot proves a layout to a reader; an assertion proves it to the build. This walk does
 * both at every stop, so no image here is of a state nobody checked — the register modal's pool
 * section collapsed and expanded, an out-of-range value refused inline, the delete dialog's
 * in-use branch with its usage rows and no button, and its confirm branch naming the datasource.
 *
 * Files land in `build/reports/094-screenshots/` as `094-<state>-<mode>.png`. They are a
 * deliverable of the round, not an input to it: nothing reads them back, and this class fails on
 * the assertions, never on a pixel.
 *
 * The mode switch is the app's own `#mode-toggle` (the same PATCH `AppShellBrowserTest` drives),
 * so the dark shots are of the real dark theme rather than a forced `prefers-color-scheme`.
 */
class DatasourceDialogShotsBrowserTest : BrowserSuite() {
    @Test
    fun `the pool section and both delete branches, photographed light and dark`() {
        startTrace()
        val user =
            seedLocalUser(
                uniqueEmail("dsshot-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("shot-" + generatedPassword("w").take(8).lowercase())

        val used = register("shotused-" + generatedPassword("ds").take(8).lowercase())
        val unused = register("shotfree-" + generatedPassword("ds").take(8).lowercase())
        val pipeline = "test/shot_uses_" + used.replace("-", "_")
        seedPipelineUsing(used, pipeline) shouldBe 201

        page.navigate("$baseUrl/datasources")
        ensureTheme("light")
        walk("light", used, unused, pipeline)

        // The app's own toggle, and the stylesheet href is the completion signal — the PATCH
        // answers with an out-of-band swap and nothing else in the response says it landed.
        page.navigate("$baseUrl/datasources")
        ensureTheme("dark")

        walk("dark", used, unused, pipeline)
    }

    /** The five states, asserted then photographed, in whichever mode is currently active. */
    private fun walk(
        mode: String,
        used: String,
        unused: String,
        pipeline: String,
    ) {
        page.navigate("$baseUrl/datasources")

        // 1. collapsed — the section costs nothing until it is wanted.
        page.click("text=Register Datasource")
        page.locator("#register-modal").isVisible shouldBe true
        page.locator("#register-modal input[name='pool.maximumPoolSize']").isVisible shouldBe false
        shot("pool-collapsed", mode)

        // 2. expanded — every catalogued key, prefilled, each saying which layer it came from.
        page.locator("#register-modal details summary").first().click()
        page.locator("#register-modal input[name='pool.maximumPoolSize']").inputValue() shouldBe "10"
        page.locator("#ds-pool-fields").innerText() shouldContain "this server"
        // The modal scrolls; a shot taken from the top would photograph the credential fields
        // and one edge of the section this image is OF.
        page.locator("#ds-pool-fields").scrollIntoViewIfNeeded()
        shot("pool-expanded", mode)

        // 3. an out-of-range value, refused inline with the modal still open.
        val name = "shotbad-" + generatedPassword("ds").take(8).lowercase()
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
        page.fill("#register-modal input[name='pool.maxLifetime']", "5000")
        page
            .waitForResponse("**/partials/datasources") {
                page.click("#register-modal button[type=submit]")
            }.status() shouldBe 400
        page.locator("#register-result").innerText() shouldContain "maxLifetime"
        page.locator("#register-result").scrollIntoViewIfNeeded()
        shot("pool-validation-error", mode)
        page.click("#register-modal .app-modal-close")

        // 4. delete refused, with the usage list and no button to click past it.
        rowFor(used).locator("button", hasText("Delete")).click()
        page.waitForSelector("#ds-delete-modal")
        // The pipeline name is in the usage LIST, not in the paragraph above it.
        page.locator("#ds-delete-modal").innerText() shouldContain pipeline
        page.locator("#ds-delete-usages").count() shouldBe 1
        page.locator("#ds-delete-modal .ds-button-danger").count() shouldBe 0
        shot("delete-refused", mode)
        page.click("#ds-delete-modal .app-modal-close")

        // 5. delete confirm, naming the datasource.
        rowFor(unused).locator("button", hasText("Delete")).click()
        page.waitForSelector("#ds-delete-modal")
        page.locator("#ds-delete-confirm-text").innerText() shouldContain unused
        shot("delete-confirm", mode)
        page.click("#ds-delete-modal .app-modal-close")
    }

    private fun shot(
        state: String,
        mode: String,
    ) {
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("094-$state-$mode.png")))
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "094-screenshots").also { it.toFile().mkdirs() }

    private fun register(name: String): String {
        page.navigate("$baseUrl/datasources")
        page.click("text=Register Datasource")
        page.waitForResponse("**/partials/datasources/pool-fields**") {
            page.selectOption("#register-modal select[name=dialect]", "H2")
        }
        page.fill("#register-modal input[name=name]", name)
        page.fill("#register-modal input[name=jdbcUrl]", "jdbc:h2:mem:${name.replace("-", "_")}")
        page.fill("#register-modal input[name=username]", "sa")
        page.fill("#register-modal input[name=password]", "sa")
        page.waitForResponse("**/partials/datasources") {
            page.click("#register-modal button[type=submit]")
        }
        rowFor(name).waitFor()
        return name
    }

    private fun rowFor(name: String) = page.locator("tr", Page.LocatorOptions().setHasText(name)).first()

    private fun hasText(text: String) =
        com.microsoft.playwright.Locator
            .LocatorOptions()
            .setHasText(text)

    /** A released pipeline whose one node reads [datasource] — the reference the guard finds. */
    private fun seedPipelineUsing(
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
                  id: templateId, dialect: 'H2', display_name: 'shot usage probe',
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
