package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.TimeoutError
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.function.Predicate

/**
 * #825: every [BrowserSuite] setup wait that can time out raises an AssertionError naming its step
 * and where the page was — and, for a render or init wait, what the document held — instead of
 * Playwright's bare `TimeoutError`, which named neither.
 *
 * Each case plants ONE step's failure (a held route, a planted page, a removed control) and runs
 * the helper through [planted], which gives up at [PLANTED_BOUND_MS] instead of the suite's
 * patience. Then, with the plant gone and the patience back, the same helper's real path runs.
 */
class BrowserSuiteSetupWaitsBrowserTest : BrowserSuite() {
    /** Requests a plant holds open; aborted in `finally`, so none outlives its case. */
    private val held = mutableListOf<Route>()

    /** The tree's own fetch only; an unparseable URL is simply not it. */
    private val treeApi = Predicate<String> { url -> runCatching { URI(url).path }.getOrNull() == "/api/v1/$FAMILY/tree" }

    @Test
    fun `a held login page navigation names the navigation and the released route logs in`() {
        startTrace()
        val user = newUser()
        page.route("$baseUrl/login") { held += it }
        try {
            val failure = planted { login(user.email, user.oneTimePassword) }
            val message = failure.shouldNameTheStep("the login page navigation ($baseUrl/login)", document = false)
            println("825 held login navigation: $message")
        } finally {
            releaseHeld()
            page.unroute("$baseUrl/login")
        }
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        println("825 released login: finalUrl=${page.url()}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["fields", "submit"])
    fun `a login form that never takes the input names its step and never the typed values`(step: String) {
        startTrace()
        val user = newUser()
        val expected =
            when (step) {
                "fields" -> "the login form's fields (#login-email, #login-password on $baseUrl/login)"
                else -> "the login form's submit button ('form button[type=submit]' on $baseUrl/login)"
            }
        page.route("$baseUrl/login") { it.fulfill(html(if (step == "fields") LOGIN_EMAIL_ONLY else LOGIN_SUBMIT_DISABLED)) }
        try {
            val failure = planted { login(user.email, user.oneTimePassword) }
            val message = failure.shouldNameTheStep(expected, document = step == "fields")
            message shouldContain "url=$baseUrl/login"
            // The fields' step names the fields: no typed value in the message or the cause's call log.
            failure.stackTraceToString() shouldNotContain user.oneTimePassword
            failure.stackTraceToString() shouldNotContain user.email
            println("825 planted login $step: $message")
        } finally {
            page.unroute("$baseUrl/login")
        }
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
    }

    @ParameterizedTest
    @CsvSource("render, without-entering", "field, without-entering", "submit, without-entering", "render, on")
    fun `a create form that never completes names its step and the released route creates`(
        step: String,
        entry: String,
    ) {
        startTrace()
        signIn()
        val name = "waits-" + generatedPassword("w").take(8).lowercase()
        val (body, expected) =
            when (step) {
                "render" -> NO_CREATE_FORM to "the workspace create form's first render ($NAME_INPUT, bound ${PLANTED_BOUND_MS.toLong()}ms)"
                "field" -> CREATE_FIELD_DISABLED to "the workspace create form's name field ($NAME_INPUT)"
                else -> CREATE_SUBMIT_DISABLED to "the workspace create form's submit button ($CREATE_SUBMIT)"
            }
        page.route("$baseUrl/workspaces") { it.fulfill(html(body)) }
        try {
            val failure =
                planted {
                    if (entry == "on") {
                        createWorkspaceOn(page, name, renderTimeoutMs = PLANTED_BOUND_MS)
                    } else {
                        createWorkspaceWithoutEntering(name, renderTimeoutMs = PLANTED_BOUND_MS)
                    }
                }
            val message = failure.shouldNameTheStep(expected, document = step == "render")
            message shouldContain "url=$baseUrl/workspaces"
            println("825 planted create $step ($entry): $message")
        } finally {
            page.unroute("$baseUrl/workspaces")
        }
        if (entry == "on") {
            createWorkspaceOn(page, name)
            page.url() shouldBe "$baseUrl/dashboard"
            page.locator(".app-ws b").innerText().trim() shouldBe name
        } else {
            createWorkspaceWithoutEntering(name)
            page.locator("td", Page.LocatorOptions().setHasText(name)).first().isVisible shouldBe true
        }
    }

    @Test
    fun `a held family page navigation names the navigation and the released route opens the tree`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        signIn()
        // The menu's family item links the family's overview (#556), which the wait's glob admits.
        val familyOverview = "$baseUrl/overview/$FAMILY"
        page.route(familyOverview) { held += it }
        try {
            val failure = planted { openBrowserTree(FAMILY) }
            val step = "the $FAMILY page navigation (the menu's /$FAMILY section link, waiting for **/$FAMILY)"
            val message = failure.shouldNameTheStep(step, document = false)
            message shouldContain "url=$baseUrl/dashboard"
            println("825 held tree navigation: $message")
        } finally {
            releaseHeld()
            page.unroute(familyOverview)
        }
        page.navigate("$baseUrl/dashboard")
        openBrowserTree(FAMILY)
        rootLevelComplete() shouldBe true
    }

    @Test
    fun `a root level that never completes names the tree and prints its state`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        signIn()
        page.route(treeApi) { held += it }
        try {
            // At the suite's patience: only the tree's own fetch is held, never the page.
            page.navigate("$baseUrl/$FAMILY")
            val failure = planted { openBrowserTree(FAMILY) }
            val step = "the $FAMILY browser tree's root level (DatapipelinesSidebarTrees '$FAMILY', parent null)"
            val message = failure.shouldNameTheStep(step, document = true)
            message shouldContain "url=$baseUrl/$FAMILY"
            message shouldContain "tree=mounted"
            message shouldContain "rootLevel=loading complete=false rows=0"
            println("825 held tree root level: $message")
        } finally {
            releaseHeld()
            page.unroute(treeApi)
        }
        page.reload()
        openBrowserTree(FAMILY)
        rootLevelComplete() shouldBe true
    }

    @Test
    fun `a phone drawer without its open button names the drawer step`() {
        startTrace()
        signIn()
        page.setViewportSize(PHONE_W, PHONE_H)
        page.navigate("$baseUrl/$FAMILY")
        page.evaluate("() => document.getElementById('browser-open').remove()")
        val failure = planted { openBrowserTree(FAMILY) }
        val message = failure.shouldNameTheStep("the phone browser drawer's open button (#browser-open, family $FAMILY)", document = false)
        message shouldContain "url=$baseUrl/$FAMILY"
        println("825 planted drawer: $message")
        page.reload()
        openBrowserTree(FAMILY)
        rootLevelComplete() shouldBe true
    }

    @ParameterizedTest
    @ValueSource(strings = ["response", "swap", "data-theme"])
    fun `a theme switch that never lands names its step and the released route switches`(step: String) {
        startTrace()
        signIn()
        val current = page.locator("#theme-link").first().getAttribute("href") ?: ""
        val mode = if (current.contains("/themes/dark.css")) "light" else "dark"
        val swap = "wanted /themes/$mode.css, current href $current"
        val expected =
            when (step) {
                "response" -> "the theme PATCH response ($swap)"
                "swap" -> "the #theme-link swap ($swap)"
                else -> "the html[data-theme='$mode'] update ($swap)"
            }
        // `data-theme`: the swapped href includes the wanted path, but the shell reads the theme
        // from its FIRST `/themes/<name>.css`, which is still the current one.
        val plantedHref = current + (if ('?' in current) "&" else "?") + "planted=/themes/$mode.css"
        page.route(THEME_PATCH) { route ->
            when (step) {
                "response" -> held += route
                "swap" -> route.fulfill(html(""))
                else -> route.fulfill(html("<link id=\"theme-link\" rel=\"stylesheet\" hx-swap-oob=\"outerHTML\" href=\"$plantedHref\">"))
            }
        }
        try {
            val failure = planted { ensureTheme(mode) }
            val message = failure.shouldNameTheStep(expected, document = true)
            message shouldContain "url=$baseUrl/dashboard"
            println("825 planted theme $step: $message")
        } finally {
            releaseHeld()
            page.unroute(THEME_PATCH)
        }
        // No plant reached the server, so the stored preference is still the current one.
        page.reload()
        ensureTheme(mode)
        page.locator("html").getAttribute("data-theme") shouldBe mode
    }

    @Test
    fun `a page without the theme link names the href read`() {
        startTrace()
        signIn()
        val plantedPage = "$baseUrl/825-planted-page"
        page.route(plantedPage) { it.fulfill(html(NO_THEME_LINK)) }
        try {
            page.navigate(plantedPage)
            val failure = planted { ensureTheme("light") }
            val message = failure.shouldNameTheStep("the #theme-link href read (wanted /themes/light.css)", document = true)
            message shouldContain "url=$plantedPage"
            println("825 planted theme href read: $message")
        } finally {
            page.unroute(plantedPage)
        }
        page.navigate("$baseUrl/dashboard")
        ensureTheme("light")
        page.locator("html").getAttribute("data-theme") shouldBe "light"
    }

    /**
     * Runs ONE planted failure with every default-patience wait giving up at [PLANTED_BOUND_MS]
     * (not 30 s / 90 s), then puts the suite's patience back before anything else runs.
     */
    private fun planted(block: () -> Unit): Throwable {
        page.setDefaultTimeout(PLANTED_BOUND_MS)
        try {
            return shouldThrowAny(block)
        } finally {
            page.setDefaultTimeout(suitePatienceMs())
        }
    }

    /** What the suite's `patient()` gave this page: CI patience, or Playwright's own default. */
    private fun suitePatienceMs(): Double =
        BrowserSuite.actionTimeoutMillis(System.getenv("CI"), System.getProperty(BrowserSuite.CI_PATIENCE_PROPERTY))
            ?: PLAYWRIGHT_DEFAULT_TIMEOUT_MS

    private fun releaseHeld() {
        held.forEach { route -> runCatching { route.abort() }.onFailure { println("825 held route already settled: $it") } }
        held.clear()
    }

    private fun newUser(): LocalUser =
        seedLocalUser(uniqueEmail("waits-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)

    private fun signIn() {
        val user = newUser()
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
    }

    private fun rootLevelComplete(): Boolean =
        page.evaluate(
            "family => window.DatapipelinesSidebarTrees.get(family)?.state.levels.get(null)?.complete === true",
            FAMILY,
        ) as Boolean

    private fun html(body: String): Route.FulfillOptions =
        Route
            .FulfillOptions()
            .setStatus(200)
            .setContentType("text/html; charset=utf-8")
            .setBody(body)

    private companion object {
        /** A planted failure's bound only: its wait can never succeed (#825 A.6, the #761 bound). */
        const val PLANTED_BOUND_MS = 5_000.0

        /** Playwright's documented per-action default, which a page the suite left unpatient keeps. */
        const val PLAYWRIGHT_DEFAULT_TIMEOUT_MS = 30_000.0

        const val FAMILY = "pipelines"
        const val THEME_PATCH = "**/partials/profile/theme"
        const val NAME_INPUT = "form[action*='/workspaces/create'] input[name=name]"
        const val CREATE_SUBMIT = "form[action*='/workspaces/create'] button[type=submit]"
        const val DESKTOP_W = 1440
        const val DESKTOP_H = 900
        const val PHONE_W = 390
        const val PHONE_H = 844

        const val LOGIN_EMAIL_ONLY =
            "<!doctype html><title>825 planted login</title><form method=\"post\"><input id=\"login-email\"></form>"
        const val LOGIN_SUBMIT_DISABLED =
            "<!doctype html><title>825 planted login</title><form method=\"post\"><input id=\"login-email\">" +
                "<input id=\"login-password\" type=\"password\"><button type=\"submit\" disabled>Log in</button></form>"
        const val NO_CREATE_FORM = "<!doctype html><title>825 planted workspaces</title><main>the create form never renders</main>"
        const val CREATE_FIELD_DISABLED =
            "<!doctype html><title>825 planted workspaces</title><form action=\"/workspaces/create\" method=\"post\">" +
                "<input name=\"name\" disabled><button type=\"submit\">Create</button></form>"
        const val CREATE_SUBMIT_DISABLED =
            "<!doctype html><title>825 planted workspaces</title><form action=\"/workspaces/create\" method=\"post\">" +
                "<input name=\"name\"><button type=\"submit\" disabled>Create</button></form>"
        const val NO_THEME_LINK = "<!doctype html><title>825 planted page</title><main>no theme link</main>"
    }
}

/**
 * The named shape (#355, #825), asserted step-first so a missing wrapper reds HERE with
 * Playwright's bare text: the step and `url=` lead; a render or init wait carries the document's
 * readiness; the cause is Playwright's timeout, kept for the trace, never the message; no cookie.
 *
 * #831: top-level and internal so FixtureSetupWaitsBrowserTest asserts the identical shape.
 */
internal fun Throwable.shouldNameTheStep(
    step: String,
    document: Boolean,
): String {
    val text = requireNotNull(message)
    text shouldContain "$step timed out — url="
    if (document) text shouldContain "readyState="
    text shouldNotContain "TimeoutError"
    text shouldNotContain "dp_session"
    text shouldNotContain "dp_csrf"
    this.shouldBeInstanceOf<AssertionError>()
    cause.shouldBeInstanceOf<TimeoutError>()
    return text
}
