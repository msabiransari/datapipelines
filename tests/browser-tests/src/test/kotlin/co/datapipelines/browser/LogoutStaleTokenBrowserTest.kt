package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #782, option (a), end to end: a logout POSTED FROM THE APP'S OWN PAGE succeeds on ANY
 * token state (the stale tab whose dp_csrf was re-minted still logs out and lands on
 * /login), while a refused browser navigation renders the 403 page — with the fixed
 * message, a correlation id and a way back — instead of the §13.7 JSON envelope.
 *
 * The integration tests forge `Sec-Fetch-Site` by hand; only a browser can prove the
 * REAL attestation on a REAL form POST carries what [co.datapipelines.auth.LogoutSameOriginMatcher]
 * reads, and that the rendered page's stale pair is exactly the state the exemption
 * forgives. Machine surfaces staying JSON is pinned here too, from the same page.
 */
class LogoutStaleTokenBrowserTest : BrowserSuite() {
    /**
     * The golden path of option (a): sign in, make the page's token pair stale by
     * rewriting the dp_csrf cookie (the rendered hidden field keeps the ORIGINAL token),
     * click Log out — the browser's own Sec-Fetch-Site: same-origin attestation exempts
     * the POST, the session is cleared, and the journey ends on the login form.
     */
    @Test
    fun `a logout with a stale token from the app's own page still lands on login signed out`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("stalelogout"), generatedPassword("otp"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")

        // The exact stale-tab shape: the page's forms carry the old token, the cookie is
        // not what it pairs with any more. dp_csrf is readable by JS by design (#202).
        page.evaluate("() => { document.cookie = 'dp_csrf=stale-token-782; path=/'; }")

        page.locator("#app-avatar").click()
        page.locator("#app-user-menu").waitFor()
        named(page, "the Log out button (#app-user-menu form button[type=submit])") {
            page.click("#app-user-menu form button[type=submit]")
        }

        page.waitForURL("**/login")
        page.context().cookies("$baseUrl/login").none { it.name == "dp_session" } shouldBe true
        page.locator("#login-email").isVisible shouldBe true
    }

    /**
     * A refused BROWSER navigation gets the page: the fixed user message, the correlation
     * id row, and the Log in action that returns to a working form. The login form itself
     * is the navigation under test — clear the cookies after the page rendered, submit,
     * and the posted token pairs with nothing (reason=missing) → refused, as a page.
     */
    @Test
    fun `a refused browser navigation renders the 403 page with a correlation id and a way back`() {
        startTrace()
        page.navigate("$baseUrl/login")
        page.context().clearCookies()
        page.fill("#login-email", "anyone@datapipelines.test")
        page.fill("#login-password", "wrong-on-purpose")
        named(page, "the login form's submit button") {
            page.click("form button[type=submit]")
        }

        page.locator("text=That action could not be taken").waitFor()
        page.content() shouldContain "Your page is out of date. Reload it and try again."
        page.content() shouldContain "Correlation ID"

        // The way back: a plain (non-boosted) link to a working login form.
        page.locator("a", com.microsoft.playwright.Page.LocatorOptions().setHasText("Log in")).first().click()
        named(page, "the login form after the refusal page's Log in link") {
            page.locator("#login-email").waitFor()
        }
        page.locator("#login-email").isVisible shouldBe true
    }

    /**
     * The same page, a MACHINE consumer: a fetch with an Accept the handler reads as JSON
     * against a machine surface keeps the §13.7 envelope — the page shape never leaks to
     * scripts, and the envelope never replaces the page for people.
     */
    @Test
    fun `a machine-shaped fetch from the page still gets the json envelope`() {
        startTrace()
        page.navigate("$baseUrl/login")

        val result =
            page.evaluate(
                """async () => {
                    const response = await fetch('/api/v1/probe', {
                        method: 'POST',
                        headers: { 'Accept': 'application/json', 'DP-CSRF-Token': 'stale-token-782' },
                    });
                    return { status: response.status, type: response.headers.get('content-type'), body: await response.text() };
                }""",
            ) as Map<*, *>

        result["status"] shouldBe 403
        (result["type"] as String).contains("application/json") shouldBe true
        (result["body"] as String) shouldContain "auth.csrf.invalid"
    }
}
