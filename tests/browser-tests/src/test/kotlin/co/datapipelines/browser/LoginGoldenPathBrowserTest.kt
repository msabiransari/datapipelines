package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Golden path 1+2 of the release checklist (TEST-GAP-2026-09.md): the login page
 * renders, a wrong password shows the error without a session, and the FULL local
 * flow works — one-time password in, forced change screen (auth.md §5A.4), chosen
 * password set, dashboard reached with its htmx stats loading.
 *
 * This is the mechanical codification of what the ad-hoc browser loop checked by hand
 * at 024–027: if this path breaks, NOTHING after it in the UI is testable.
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
        login(adminEmail, "definitely-wrong")

        page.waitForURL("**/login?error=credentials")
        page.content() shouldContain "Invalid email or password"
        contextCookiesHaveNoSession()
    }

    @Test
    fun `the full golden path - one-time login, forced change, dashboard`() {
        startTrace()
        seedAdminOnce()

        // Step 1: the one-time password logs in and lands on the forced-change gate.
        login(adminEmail, adminOneTimePassword)
        try {
            page.waitForURL("**/settings/password", Page.WaitForURLOptions().setTimeout(8000.0))
        } catch (e: Exception) {
            println("DEBUG final url: " + page.url())
            println("DEBUG body head: " + page.content().take(800))
            throw e
        }

        // Step 2: every other authenticated route bounces back to the gate.
        page.navigate("$baseUrl/dashboard")
        page.waitForURL("**/settings/password")

        // Step 3: set the chosen password. The form POSTs via htmx to the partial;
        // success clears the gate — navigating on proves the session is released.
        page.fill("#currentPassword", adminOneTimePassword)
        page.fill("#newPassword", adminChosenPassword)
        page.fill("#confirmPassword", adminChosenPassword)
        // The change is an ASYNC htmx post — navigating before it lands cancels it.
        // The click runs INSIDE the wait; the 200 (and its OOB toast) is the release
        // signal — never a sleep.
        val change =
            page.waitForResponse("**/partials/account/password") {
                page.click("#password-change-form button[type=submit]")
            }
        change.status() shouldBe 200

        // Step 4: the dashboard now loads, and its htmx stats partials arrive.
        page.navigate("$baseUrl/dashboard")
        page.waitForURL("**/dashboard")
        page.locator("h1").first().innerText() shouldContain "Dashboard"
        // The stats partial replaces its placeholder on load — wait for real content,
        // never a sleep. totalPipelines renders inside .ds-stats.
        page.waitForSelector(".ds-stats .ds-stat, .ds-stats td, .ds-stats div")
    }

    @Test
    fun `the changed password is the one that works on the next login`() {
        startTrace()
        seedAdminOnce()
        // Complete the forced change first (the golden path's precondition).
        login(adminEmail, adminOneTimePassword)
        page.waitForURL("**/settings/password")
        page.fill("#currentPassword", adminOneTimePassword)
        page.fill("#newPassword", adminChosenPassword)
        page.fill("#confirmPassword", adminChosenPassword)
        page.click("button[type=submit]")

        // Fresh context = logged out. The CHOSEN password now authenticates and goes
        // straight to the dashboard — no second forced change.
        page.context().close()
        val fresh = newSession()
        fresh.page.navigate("$baseUrl/login")
        fresh.page.fill("#login-email", adminEmail)
        fresh.page.fill("#login-password", adminChosenPassword)
        fresh.page.click("button[type=submit]")
        fresh.page.waitForURL("**/dashboard")
        fresh.close()
    }

    private fun contextCookiesHaveNoSession() {
        page.context().cookies("$baseUrl/login").none { it.name == "dp_session" } shouldBe true
    }
}
