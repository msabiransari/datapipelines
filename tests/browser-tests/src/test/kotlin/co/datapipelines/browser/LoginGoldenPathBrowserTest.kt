package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Golden paths 1+2 of the release checklist (TEST-GAP-2026-09.md): the login page
 * renders, a wrong password shows the error without a session, and the FULL local
 * flow works — one-time password in, forced change screen (auth.md §5A.4), chosen
 * password set, dashboard reached with its htmx stats loading, and the CHOSEN password
 * authenticating the next login.
 *
 * Every test seeds its OWN user (unique email) — the suite is order-independent by
 * construction, which 060's shuffle-seed gate demands. This is the mechanical
 * codification of what the ad-hoc browser loop checked by hand at 024–027: if this
 * path breaks, NOTHING after it in the UI is testable.
 */
class LoginGoldenPathBrowserTest : BrowserSuite() {
    @Test
    fun `the login page renders the local form and nothing else`() {
        startTrace()
        page.navigate("$baseUrl/login")

        page.locator("h1").first().innerText() shouldContain "datapipelines.co"
        page.locator("#login-email").isVisible shouldBe true
        page.locator("#login-password").isVisible shouldBe true
        // Local-only deployment: no provider buttons, no divider.
        page.locator("a[href*='/oauth2/authorization/']").count() shouldBe 0
    }

    @Test
    fun `a wrong password shows the credentials error and mints no session`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("wrongpw"), generatedPassword("otp"), mustChange = false)

        login(user.email, "definitely-wrong")

        page.waitForURL("**/login?error=credentials")
        page.content() shouldContain "Invalid email or password"
        page.context().cookies("$baseUrl/login").none { it.name == "dp_session" } shouldBe true
    }

    @Test
    fun `the full golden path - one-time login, forced change, dashboard`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("golden"), generatedPassword("otp"), mustChange = true)

        // Step 1: the one-time password logs in and lands on the forced-change gate.
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/settings/password")

        // Step 2: every other authenticated route bounces back to the gate.
        page.navigate("$baseUrl/dashboard")
        page.waitForURL("**/settings/password")

        // Step 3: set the chosen password. The change is an ASYNC htmx post — the click
        // runs INSIDE the wait; the 200 (and its OOB toast) is the release signal,
        // never a sleep.
        page.fill("#currentPassword", user.oneTimePassword)
        page.fill("#newPassword", user.chosenPassword)
        page.fill("#confirmPassword", user.chosenPassword)
        val change =
            page.waitForResponse("**/partials/account/password") {
                page.click("#password-change-form button[type=submit]")
            }
        change.status() shouldBe 200

        // Step 4: the dashboard now loads, and its htmx stats partials arrive.
        page.navigate("$baseUrl/dashboard")
        page.waitForURL("**/dashboard")
        page.locator("h1").first().innerText() shouldContain "Dashboard"
        page.waitForSelector(".ds-stats .ds-stat, .ds-stats td, .ds-stats div")
    }

    @Test
    fun `the chosen password authenticates a fresh login - no second forced change`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("relogin"), generatedPassword("otp"), mustChange = true)

        // Complete the forced change once (this test's own precondition, own user).
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/settings/password")
        page.fill("#currentPassword", user.oneTimePassword)
        page.fill("#newPassword", user.chosenPassword)
        page.fill("#confirmPassword", user.chosenPassword)
        page.waitForResponse("**/partials/account/password") {
            page.click("#password-change-form button[type=submit]")
        }

        // Fresh context = logged out (the original stays open for @AfterEach to close).
        val fresh = newSession()
        fresh.page.navigate("$baseUrl/login")
        fresh.page.fill("#login-email", user.email)
        fresh.page.fill("#login-password", user.chosenPassword)
        fresh.page.click("form button[type=submit]")
        fresh.page.waitForURL("**/dashboard")
        fresh.close()
    }
}
