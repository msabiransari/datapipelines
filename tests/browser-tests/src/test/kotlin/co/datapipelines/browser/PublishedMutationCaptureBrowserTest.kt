package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #1043 (ui-screens §4.18c/§4.18d; published-mutation-apis §6, §7) — runtime capture walked by a workspace
 * administrator in a real browser, the route called by an external client in between:
 *
 *  1. OPEN — the API page's capture card reads "disabled" with the personal-data warning; "Capture for 15 minutes"
 *     turns it active with the remaining time and "every request";
 *  2. REPRODUCE — the external client posts a body carrying a SELECTED marker (kept by the value-mode transformer) and
 *     a RAW marker (no node selects it); the business row is written once and the reservation stores the decision;
 *  3. STOP — "Stop capture" returns the card to disabled, the history row is ended and links the captured run; a
 *     second post after the stop is served and stores NO decision;
 *  4. INSPECT — the link opens the diagnostic page: status complete, the run SUCCESS, the `pick` output's JSON text is
 *     the SELECTED marker, and the RAW marker appears nowhere on the page (it never left the request).
 */
class PublishedMutationCaptureBrowserTest : BrowserSuite() {
    @Test
    @Suppress("LongMethod") // one flow: the window opened, the call reproduced, the stop and the inspection are one story
    fun `an administrator opens a capture, reproduces a call, stops it and inspects the captured run`() {
        startTrace()
        val suffix = generatedPassword("s").take(8).lowercase().filter { it.isLetterOrDigit() }
        val user = seedLocalUser(uniqueEmail("cap1043-$suffix"), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("cap1043-$suffix")
        page.navigate("$baseUrl/api-console")
        val fx = PublishedMutationCaptureFixtures(page, baseUrl, suffix)
        val path = "/cap1043$suffix/v1/orders"
        val endpointId = fx.seedRoute(path)
        val writer = fx.mintWriter(path)

        // 1 — open.
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/api-console/endpoints/$endpointId")
        val card = page.locator("[data-api-capture-card]")
        card.waitFor()
        withClue("a new route's capture is off, and the card says what capture keeps before anyone opens one") {
            card.getAttribute("data-capture-state") shouldBe "disabled"
            page.locator("[data-api-capture-warning]").innerText() shouldContain "can itself contain personal data"
            page.locator("[data-api-capture-start]").innerText() shouldBe "Capture for 15 minutes"
        }
        shot("card-disabled")
        page
            .waitForResponse({ it.url().endsWith("/partials/endpoints/$endpointId/capture") && it.request().method() == "POST" }) {
                page.locator("[data-api-capture-start]").click()
            }.status() shouldBe 200
        page.locator("[data-api-capture-card][data-capture-state='active']").waitFor()
        withClue("the active card: remaining time, no filter") {
            page.locator("[data-api-capture-remaining]").innerText() shouldContain "min remaining"
            page.locator("[data-api-capture-filter]").innerText() shouldBe "Every request to this route and method."
            page.locator("[data-api-capture-error]").count() shouldBe 0
        }
        shot("card-active")

        // 2 — reproduce, from an external client.
        val selected = "SEL-$suffix-1043"
        val raw = "RAW-$suffix-1043"
        val answered = fx.post(writer, path, "cap-$suffix-1", """{"selected":"$selected","raw":"$raw"}""")
        withClue(answered.body()) { answered.statusCode() shouldBe 200 }
        val executionId = answered.headers().firstValue("DP-Execution-Id").orElseThrow()
        fx.count("SELECT count(*) FROM cap_rows WHERE picked = '$selected'") shouldBe 1
        withClue("the decision was stored with the admitting reservation") {
            fx.metadata(
                "SELECT (capture_session_id IS NOT NULL)::text FROM published_mutation_reservations WHERE execution_id = '$executionId'",
            ) shouldBe
                "true"
        }

        // 3 — stop.
        page
            .waitForResponse({ it.url().endsWith("/stop") && it.request().method() == "POST" }) {
                page.locator("[data-api-capture-stop]").click()
            }.status() shouldBe 200
        page.locator("[data-api-capture-card][data-capture-state='disabled']").waitFor()
        val run = page.locator("[data-api-capture-history] [data-api-capture-run]")
        withClue("the ended window links exactly the run it captured") {
            run.count() shouldBe 1
            run.innerText() shouldBe executionId
        }
        shot("card-stopped")
        val after = fx.post(writer, path, "cap-$suffix-2", """{"selected":"after-$suffix","raw":"after"}""")
        withClue(after.body()) { after.statusCode() shouldBe 200 }
        val afterId = after.headers().firstValue("DP-Execution-Id").orElseThrow()
        withClue("a call after the stop is served uncaptured") {
            fx.metadata(
                "SELECT coalesce(capture_session_id::text, 'none') FROM published_mutation_reservations WHERE execution_id = '$afterId'",
            ) shouldBe
                "none"
        }

        // 4 — inspect.
        run.click()
        page.waitForURL("**/executions/$executionId/capture")
        page.locator("[data-capture-status]").waitFor()
        withClue("the diagnostic page shows the kept declared output as its JSON text, and never the raw input") {
            page.locator("[data-capture-execution]").innerText() shouldBe executionId
            page.locator("[data-capture-status]").innerText() shouldBe "complete"
            page.locator("[data-capture-execution-status]").innerText() shouldBe "SUCCESS"
            page.locator("[data-capture-node='pick'] [data-capture-value]").innerText() shouldBe "\"$selected\""
            page.locator("[data-capture-summary]").innerText() shouldStartWith "1 kept, 0 omitted, 0 failed writes"
            page.content() shouldNotContain raw
        }
        shot("detail")
        ensureTheme("dark")
        shot("detail-dark")
    }

    private fun shot(name: String) {
        val dir = Paths.get("build", "reports", "1043-screenshots")
        Files.createDirectories(dir)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(dir.resolve("1043-$name.png"))
                .setFullPage(false),
        )
    }

    private companion object {
        /** Serving mutation routes is off by default (configuration.md §3.22); this class's context turns it on. */
        @DynamicPropertySource
        @JvmStatic
        fun mutations(registry: DynamicPropertyRegistry) {
            registry.add("datapipelines.endpoints.mutations-enabled") { "true" }
        }
    }
}
