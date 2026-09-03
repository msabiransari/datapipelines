package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Golden path 10 of the release checklist: the PUBLIC surface renders signed-out (the
 * marketing site, 033's owner decision), everything authenticated redirects to login
 * instead of leaking, and the in-product docs render once signed in. If this breaks,
 * either the front door is dark or a page stopped being gated — both release blockers.
 */
class NavigationGoldenPathBrowserTest : BrowserSuite() {
    @Test
    fun `the marketing site renders signed-out`() {
        startTrace()
        page.navigate("$baseUrl/")

        page.locator("#hero-title").innerText() shouldContain "Agent-native Data Pipelines"
    }

    @Test
    fun `the dashboard redirects to login when signed-out`() {
        startTrace()
        page.navigate("$baseUrl/dashboard")

        page.waitForURL("**/login")
    }

    @Test
    fun `the in-product docs redirect to login when signed-out`() {
        startTrace()
        page.navigate("$baseUrl/docs")

        page.waitForURL("**/login")
    }

    @Test
    fun `the docs render for a signed-in user`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("docs"), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")

        page.navigate("$baseUrl/docs")
        page.waitForURL("**/docs")
        page.locator("h1").first().innerText() shouldContain "Documentation"
        // The spec set renders in-product (README): at least one known doc title present.
        page.content() shouldContain "Pipeline Contract"
    }
}
