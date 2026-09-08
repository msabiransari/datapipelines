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

        // Step 4: the FORCED change redirects the browser itself (HX-Redirect → /dashboard).
        // The earlier version of this step navigated by hand — which is exactly how the
        // "the dialog did not go away" defect (2026-09-05) stayed invisible to the suite.
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

    /**
     * 090 §C — the owner's walk of 2026-09-07: signed in, `/login` opened in a new tab,
     * and the sign-in form appeared with the sidebar around it and the menu working. Two
     * defects in one screen — `/login` had no session check, and `login.html` decorated
     * with `layouts/default`, which renders the shell whenever `authenticated` is true.
     *
     * This pins the outcome end to end. The unit half lives in `UiControllerTest` (the
     * 302 itself) and `AuthLayoutRenderTest` (the shell-free markup); what only a browser
     * can prove is that the redirect survives the real filter chain — the session cookie
     * is authenticated by the JWT filter on a `permitAll` route, which is the exact
     * mechanism that made the form render in the first place.
     */
    @Test
    fun `a signed-in visitor who opens the login page lands on the dashboard`() {
        startTrace()
        // A slug of its own: `seedLocalUser` is ON CONFLICT DO NOTHING, so two tests sharing
        // an email share a USER — and this one wants `mustChange = false` while the
        // forced-change path wants true. Whichever ran first would decide, which is exactly
        // the order dependency the suite forbids. (Caught by that test timing out, 090.)
        val user = seedLocalUser(uniqueEmail("loginbounce"), generatedPassword("otp"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")

        page.navigate("$baseUrl/login")

        page.waitForURL("**/dashboard")
        page.locator("#login-email").count() shouldBe 0
    }

    /**
     * The other door into the same dead end: the OIDC authorization entry, reachable by
     * bookmark or a stale tab. This deployment registers no providers, so the route 404s
     * rather than redirecting — which still proves the guard is INERT here (a filter that
     * bounced unconditionally would answer 302 on a route that has no provider at all),
     * and `OidcSignedInBounceFilterTest` owns the redirect itself with the chain mocked.
     */
    @Test
    fun `the oidc authorization entry is not a login form for a signed-in visitor`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("oidcentry"), generatedPassword("otp"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")

        val response = page.navigate("$baseUrl/oauth2/authorization/google")

        (response!!.status() != 200) shouldBe true
        page.locator("#login-email").count() shouldBe 0
    }

    /**
     * The ceremony layout itself, live: no rail, no top bar, no boosted-swap target — and
     * the page does not scroll, which is what `.app-auth`'s single grid column buys over
     * `.app-center`'s 70vh floor inside the shell's `<main>`.
     */
    @Test
    fun `the login page renders the ceremony layout with no app shell`() {
        startTrace()
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/login")

        page.locator("nav.app-nav").count() shouldBe 0
        page.locator("#app-main").count() shouldBe 0
        page.locator(".app-rail").count() shouldBe 0
        page.locator(".app-auth-brand").isVisible shouldBe true
        page.locator(".app-card-auth").isVisible shouldBe true
        val scrolls =
            page.evaluate("() => document.documentElement.scrollHeight > window.innerHeight + 1") as Boolean
        scrolls shouldBe false
    }

    /**
     * The forced-change gate is the OTHER ceremony screen, and the one where a shell does
     * real harm: the interceptor refuses every route but this one, so a rail of ten links
     * that all bounce straight back is a menu of dead ends. `settings/password-forced`
     * decorates with the auth layout; the voluntary change from Settings keeps the shell,
     * which `AuthLayoutRenderTest` pins at the render.
     */
    @Test
    fun `the forced password change screen renders without the app shell`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("forcedceremony"), generatedPassword("otp"), mustChange = true)
        login(user.email, user.oneTimePassword)

        page.waitForURL("**/settings/password")
        page.locator("#password-change-form").isVisible shouldBe true
        page.locator("nav.app-nav").count() shouldBe 0
        page.locator("#app-main").count() shouldBe 0
    }
}
