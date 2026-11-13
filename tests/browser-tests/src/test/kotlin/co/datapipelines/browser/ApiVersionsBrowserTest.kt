package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #786 B.3/C.3 — the API versions page in a real browser (ui-screens §4.18b; one class for the family, owner ruling
 * 2026-11-08; its fixtures live in [ApiVersionsFixtures]):
 *
 *  1. the console's path opens the page; v1 (created as a DRAFT pinned to pipeline v1) is current and listed;
 *  2. the release dialog releases it (serving itself is PublishedEndpointE2eTest's);
 *  3. the pipeline's current moves to v2 and the API STILL pins v1 (A06 in the UI): a new draft keeps the pin until the
 *     picker changes it, and viewing v1 or v2 never changes what is current;
 *  4. the switch dialog moves current back to v1 explicitly; the API card (enabled, keys) is the same on every version.
 */
class ApiVersionsBrowserTest : BrowserSuite() {
    @Test
    fun `an API's versions - release, a pinned draft that survives a pipeline move, an explicit switch`() {
        startTrace()
        val fx = ApiVersionsFixtures(page)
        val email = uniqueEmail("av786-" + generatedPassword("u").take(8))
        val user = seedLocalUser(email, generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("av786-" + generatedPassword("w").take(8).lowercase())
        val pipelineName = "test/av786_probe"
        val (pipelineId, p1Hash) = fx.seedReleasedPipeline(pipelineName)
        val endpointId = fx.createEndpoint("/av786/v1/probe", pipelineName, pipelineVersion = 1)

        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/api-console")
        page.locator("a[data-api-versions-link]").first().click()
        page.waitForURL("**/api-console/endpoints/$endpointId")
        withClue("v1 is the created DRAFT, pinned to the exact pipeline version, and current (authoring-enabled)") {
            page.locator("[data-api-version='1'] [data-api-status]").innerText() shouldBe "DRAFT"
            page.locator("[data-api-pin]").innerText() shouldBe "$pipelineName@1"
            page.locator("[data-api-current]").innerText() shouldBe "serving v1"
        }
        fx.shot("draft-1440")

        // Release through the dialog: the pinned pipeline version is already RELEASED, so nothing to consent to.
        fx.openDialog("release", "endpoint-release")
        fx.shot("release-dialog-1440")
        fx.submitAndWaitForPage("api_released")
        page.locator("[data-api-version='1'] [data-api-status]").innerText() shouldBe "RELEASED"

        // The pipeline's current moves to v2; the API's pin does not.
        fx.releaseNewPipelineVersion(pipelineId, pipelineName, p1Hash)
        page.reload()
        withClue("A06: the API still pins pipeline v1 after the pipeline's current moved to v2") {
            page.locator("[data-api-pin]").innerText() shouldBe "$pipelineName@1"
        }

        // A new draft starts from the current version: its pin is preselected (v1, now NONCURRENT for the pipeline).
        fx.openDialog("new-draft", "endpoint-draft")
        withClue("the picker preselects the base's noncurrent pin, and lists the pipeline's current v2 beside it") {
            page.locator("[data-api-pipeline-version] option:checked").getAttribute("value") shouldBe "1"
            page.locator("[data-api-pipeline-version]").innerText() shouldContain "v2 · RELEASED · current"
        }
        page.selectOption("[data-api-pipeline-version]", "2")
        fx.submitAndWaitForPage("api_draft_saved")
        page.locator("[data-api-pin]").innerText() shouldBe "$pipelineName@2"
        fx.openDialog("release", "endpoint-release")
        fx.submitAndWaitForPage("api_released")
        page.locator("[data-api-current]").innerText() shouldBe "serving v2"

        // Viewing v1 is a link: it shows v1's pin and leaves v2 current.
        val entityOnV2 = page.locator("[data-api-entity]").innerText()
        page.locator("[data-api-version='1'] a").click()
        page.waitForURL("**version=1")
        page.locator("[data-api-pin]").innerText() shouldBe "$pipelineName@1"
        page.locator("[data-api-current]").innerText() shouldBe "serving v2"
        withClue("the API card is the entity's - the same on every version") {
            page.locator("[data-api-entity]").innerText() shouldBe entityOnV2
        }

        // The explicit switch moves current back to v1.
        fx.openDialog("switch", "endpoint-switch")
        fx.submitAndWaitForPage("api_switched")
        page.locator("[data-api-current]").innerText() shouldBe "serving v1"
        page.locator("[data-api-version='1'] [data-api-is-current]").count() shouldBe 1
        fx.shot("switched-1440")
        ensureTheme("dark")
        fx.shot("switched-1440-dark")
    }
}
