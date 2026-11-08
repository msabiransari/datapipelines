package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.UUID
import java.util.function.Predicate

/**
 * #831: the setup waits of the fixtures DERIVED from [BrowserSuite] — DashboardBrowserSuite,
 * SchedulesBrowserSuite, RestTreeBrowserFixture, the post-login landing every fixture shares — and
 * of the suite's support objects (OverviewHomeBrowserSupport, EditorRunFixtures) raise the named
 * AssertionError #825 gave BrowserSuite's own helpers, never Playwright's bare `TimeoutError`.
 * ParameterSetBrowserSuite and VisualizationBrowserSuite keep no wait of their own: their second
 * sessions call [login], [landOnDashboard] and [enterWorkspace].
 *
 * Each case plants ONE step's failure (a held route, a planted page, a removed control) and runs the
 * fixture's own helper through [planted], which gives up at [PLANTED_BOUND_MS]; the shape is
 * asserted by BrowserSuiteSetupWaitsBrowserTest's [shouldNameTheStep]. The schedules and REST-tree
 * fixtures are siblings of this class, so their helpers run on a bound instance ([SchedulesOn],
 * [RestTreeOn]) driving THIS test's page and application — the fixture's code, never a copy.
 */
class FixtureSetupWaitsBrowserTest : DashboardBrowserSuite() {
    /** Requests a plant holds open; aborted in `finally`, so none outlives its case. */
    private val held = mutableListOf<Route>()

    /** The pipelines tree's own fetches only (a level, and `/search`); an unparseable URL is simply not one. */
    private val treeApi = Predicate<String> { url -> pathOf(url)?.startsWith("/api/v1/pipelines/tree") == true }

    @ParameterizedTest
    @ValueSource(strings = ["dashboard", "schedules", "rest-tree", "second-page"])
    fun `a sign-in that never lands names the post-login landing`(fixture: String) {
        startTrace()
        val session = if (fixture == "second-page") newSession() else null
        val on = session?.page ?: page
        // The submit's POST answers a page of its own, so the form never redirects to the dashboard.
        on.route("$baseUrl/login") { route ->
            if (route.request().method() == "POST") route.fulfill(html(SIGN_IN_NEVER_LANDS)) else route.resume()
        }
        try {
            val failure =
                planted(on) {
                    when (fixture) {
                        "dashboard" -> ready("waits")
                        "schedules" -> SchedulesOn(page, port).signedInWorkspace("waits")
                        "rest-tree" -> RestTreeOn(page, port).ready()
                        else -> signIn(on)
                    }
                }
            val message = failure.shouldNameTheStep("the post-login landing (**/dashboard)", document = false)
            message shouldContain "url=$baseUrl/login"
            println("831 landing ($fixture): $message")
        } finally {
            on.unroute("$baseUrl/login")
            session?.close()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["host-navigation", "chip"])
    fun `a dashboard host or chip that never arrives names its step`(step: String) {
        startTrace()
        val board = UUID.randomUUID().toString()
        val (expected, document) =
            when (step) {
                "host-navigation" -> {
                    page.route(Predicate<String> { url -> url.startsWith("$baseUrl/test/dashboards/host") }) { held += it }
                    "the dashboard host page navigation ($baseUrl/test/dashboards/host?id=$board)" to false
                }

                else -> {
                    plantPage("<main>no visualization here</main>")
                    "the status chip ([data-dp-viz=\"viz-831\"] .dp-dashboard-status) reaching data-dp-state=ready" to false
                }
            }
        try {
            val failure = planted { if (step == "host-navigation") openHost(board) else chipStateIs("viz-831", "ready") }
            val message = failure.shouldNameTheStep(expected, document)
            if (step == "chip") message shouldContain "chip=absent"
            println("831 dashboard $step: $message")
        } finally {
            releaseHeld()
            page.unrouteAll()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["holder-rows", "refresh-row"])
    fun `a refresh row that never reaches its status names the wait and its last read`(step: String) {
        startTrace()
        signIn()
        val board = UUID.randomUUID().toString()
        val refresh = UUID.randomUUID().toString()
        val failure =
            planted {
                if (step == "holder-rows") awaitHolderRows(board, listOf(refresh), "ready") else awaitRefreshStatus(board, refresh, "ready")
            }
        val expected =
            if (step == "holder-rows") {
                "the refresh rows of board $board reaching status=ready (1 ids)"
            } else {
                "the refresh row $refresh of board $board reaching status=ready"
            }
        val message = failure.shouldNameTheStep(expected, document = false)
        message shouldContain "last read="
        message shouldContain "http=404"
        println("831 dashboard $step: $message")
    }

    @ParameterizedTest
    @ValueSource(strings = ["dashboard-navigation", "dashboard-idle", "editor-run-navigation", "editor-run-idle"])
    fun `a datasources page that never settles names its step`(case: String) {
        startTrace()
        val (fixture, step) = case.substringBeforeLast('-') to case.substringAfterLast('-')
        if (step == "navigation") {
            page.route("$baseUrl/datasources") { held += it }
        } else {
            // The page loads, then keeps one request open for ever: the network never goes idle.
            page.route("$baseUrl/datasources") { it.fulfill(html(NEVER_IDLE)) }
            page.route("$baseUrl/831-busy") { held += it }
        }
        try {
            val failure =
                planted {
                    if (fixture == "dashboard") {
                        registerSourceDatasource()
                    } else {
                        EditorRunFixtures.registerSourceDatasource(page, baseUrl, "x")
                    }
                }
            val expected =
                if (step == "navigation") {
                    "the datasources page navigation ($baseUrl/datasources)"
                } else {
                    "the datasources page's network idle ($baseUrl/datasources)"
                }
            val message = failure.shouldNameTheStep(expected, document = step == "idle")
            println("831 $fixture datasources $step: $message")
        } finally {
            releaseHeld()
            page.unrouteAll()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["navigation", "list"])
    fun `a schedules page that never settles names its step`(step: String) {
        startTrace()
        if (step == "navigation") {
            page.route("$baseUrl/schedules") { held += it }
        } else {
            page.route("$baseUrl/schedules") { it.fulfill(html(SCHEDULES_ALWAYS_BUSY)) }
        }
        try {
            val failure = planted { SchedulesOn(page, port).openList() }
            val expected =
                if (step == "navigation") {
                    "the schedules page navigation ($baseUrl/schedules)"
                } else {
                    "the schedule list's first settle (#schedule-list:not([aria-busy]))"
                }
            val message = failure.shouldNameTheStep(expected, document = step == "list")
            println("831 schedules $step: $message")
        } finally {
            releaseHeld()
            page.unrouteAll()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["registry", "expand-button", "expand-level", "search-field", "search-results"])
    fun `a pipelines tree step that never completes names the step and the tree's state`(step: String) {
        startTrace()
        val tree = RestTreeOn(page, port)
        val absentButton = tree.folderSelector("w831-absent") + " > $TREE_LINE_BUTTON"
        val failure =
            try {
                plantTreeStep(tree, step)
                planted {
                    when (step) {
                        "registry" -> tree.ready()
                        "expand-button" -> tree.expandFolder("w831-absent")
                        "expand-level" -> tree.expandFolder("w831")
                        else -> tree.searchFor("w831")
                    }
                }
            } finally {
                releaseHeld()
                page.unrouteAll()
            }
        val expected =
            when (step) {
                "registry" -> "the resource browser's tree registry (window.DatapipelinesSidebarTrees)"
                "expand-button" -> "the pipelines tree's expand button for folder w831-absent ($absentButton)"
                "expand-level" -> "the pipelines tree's level for folder w831 (folder:w831)"
                "search-field" -> "the pipelines tree's search field ($SEARCH_FIELD)"
                else -> "the pipelines tree's search results (query 'w831')"
            }
        val message = failure.shouldNameTheStep(expected, document = step == "registry")
        when (step) {
            "expand-level" -> message shouldContain "level=loading complete=false rows=0"
            "search-results" -> message shouldContain "query=\"w831\" search=loading complete=false rows=0"
        }
        println("831 rest tree $step: $message")
    }

    /**
     * The tree step's plant: the registry case empties the tree's module (so the shell itself still
     * initializes); every other case first reaches a real, loaded tree, then removes the search field
     * or holds the tree's own fetch.
     */
    private fun plantTreeStep(
        tree: RestTreeOn,
        step: String,
    ) {
        if (step == "registry") {
            page.route(Predicate<String> { url -> pathOf(url) == "/js/tree/sidebar.mjs" }) { it.fulfill(javascript("export {};")) }
            return
        }
        tree.ready()
        if (step == "expand-level") tree.seed("w831/leaf")
        tree.showTree()
        if (step == "search-field") page.evaluate("() => document.querySelector('$SEARCH_FIELD').remove()")
        if (step == "expand-level" || step == "search-results") page.route(treeApi) { held += it }
    }

    @ParameterizedTest
    @ValueSource(strings = ["open-control", "opening", "close"])
    fun `an overview dialog that never opens or closes names its step`(step: String) {
        startTrace()
        plantPage(if (step == "close") DIALOG_STAYS_OPEN else DIALOG_NEVER_OPENS)
        val failure =
            planted {
                when (step) {
                    "open-control" -> OverviewHomeBrowserSupport.openDialog(page, "[data-open='absent']")
                    "opening" -> OverviewHomeBrowserSupport.openDialog(page, "[data-open='definitions']")
                    else -> OverviewHomeBrowserSupport.definitionsRoundTrip(page, DEFINITIONS_TOTAL)
                }
            }
        val expected =
            when (step) {
                "open-control" -> "the overview dialog's open control ([data-open='absent'])"
                "opening" -> "the overview dialog's opening (dialog[data-overview-dialog] open, via [data-open='definitions'])"
                else -> "the overview dialog's close on Escape (dialog[data-overview-dialog] not open)"
            }
        val message = failure.shouldNameTheStep(expected, document = step != "open-control")
        println("831 overview dialog $step: $message")
    }

    @ParameterizedTest
    @ValueSource(strings = ["reload", "summary", "href-read"])
    fun `an overview evidence page that never renders names its step`(step: String) {
        startTrace()
        var served = 0
        val body = if (step == "href-read") "<main>no theme link</main>" else LIGHT_PAGE_WITHOUT_SUMMARY
        page.route(plantedPage) { route ->
            served += 1
            if (step == "reload" && served > 1) held += route else route.fulfill(html(body))
        }
        try {
            page.navigate(plantedPage)
            val failure = planted { OverviewHomeBrowserSupport.screenshots(page, "w831") }
            val shot = "${OverviewHomeBrowserSupport.WIDE} x ${OverviewHomeBrowserSupport.WIDE_H}, light"
            val expected =
                when (step) {
                    "reload" -> "the overview page reload ($plantedPage, $shot)"
                    "summary" -> "the overview summary's render ([data-overview-summary], $shot)"
                    else -> "the #theme-link href read (wanted /themes/light.css)"
                }
            val message = failure.shouldNameTheStep(expected, document = step != "reload")
            println("831 overview evidence $step: $message")
        } finally {
            releaseHeld()
            page.unrouteAll()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["response", "swap", "data-theme"])
    fun `the overview's theme switch names its step like ensureTheme`(step: String) {
        startTrace()
        signIn()
        // The overview's evidence set starts in light: put the stored theme in dark for real first.
        ensureTheme("dark")
        val current = page.locator("#theme-link").first().getAttribute("href") ?: ""
        val swap = "wanted /themes/light.css, current href $current"
        val plantedHref = current + (if ('?' in current) "&" else "?") + "planted=/themes/light.css"
        page.route(THEME_PATCH) { route ->
            when (step) {
                "response" -> held += route
                "swap" -> route.fulfill(html(""))
                else -> route.fulfill(html("<link id=\"theme-link\" rel=\"stylesheet\" hx-swap-oob=\"outerHTML\" href=\"$plantedHref\">"))
            }
        }
        try {
            val failure = planted { OverviewHomeBrowserSupport.screenshots(page, "w831") }
            val expected =
                when (step) {
                    "response" -> "the theme PATCH response ($swap)"
                    "swap" -> "the #theme-link swap ($swap)"
                    else -> "the html[data-theme='light'] update ($swap)"
                }
            val message = failure.shouldNameTheStep(expected, document = true)
            message shouldContain "url=$baseUrl/dashboard"
            println("831 overview theme $step: $message")
        } finally {
            releaseHeld()
            page.unroute(THEME_PATCH)
        }
    }

    /**
     * Runs ONE planted failure with every default-patience wait of [on] giving up at
     * [PLANTED_BOUND_MS], then puts the suite's patience back (BrowserSuiteSetupWaitsBrowserTest's shape).
     */
    private fun planted(
        on: Page = page,
        block: () -> Unit,
    ): Throwable {
        on.setDefaultTimeout(PLANTED_BOUND_MS)
        try {
            return shouldThrowAny(block)
        } finally {
            on.setDefaultTimeout(suitePatienceMs())
        }
    }

    private fun suitePatienceMs(): Double =
        BrowserSuite.actionTimeoutMillis(System.getenv("CI"), System.getProperty(BrowserSuite.CI_PATIENCE_PROPERTY))
            ?: PLAYWRIGHT_DEFAULT_TIMEOUT_MS

    /** Serves [body] at [plantedPage] and opens it; the route stays until the case unroutes. */
    private fun plantPage(body: String) {
        page.route(plantedPage) { it.fulfill(html(body)) }
        page.navigate(plantedPage)
    }

    private fun releaseHeld() {
        held.forEach { route -> runCatching { route.abort() }.onFailure { println("831 held route already settled: $it") } }
        held.clear()
    }

    private fun newUser(): LocalUser =
        seedLocalUser(uniqueEmail("fwaits-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)

    /** A fresh user signed in on [on] through the suite's own login and landing. */
    private fun signIn(on: Page = page) {
        val user = newUser()
        login(on, user.email, user.oneTimePassword)
        landOnDashboard(on)
    }

    private fun pathOf(url: String): String? = runCatching { URI(url).path }.getOrNull()

    private fun html(body: String): Route.FulfillOptions = fulfilled("text/html; charset=utf-8", body)

    private fun javascript(body: String): Route.FulfillOptions = fulfilled("text/javascript; charset=utf-8", body)

    private fun fulfilled(
        type: String,
        body: String,
    ): Route.FulfillOptions =
        Route
            .FulfillOptions()
            .setStatus(200)
            .setContentType(type)
            .setBody(body)

    private val plantedPage: String get() = "$baseUrl/831-planted-page"

    /** SchedulesBrowserSuite's own helpers, bound to this test's page and application. */
    private class SchedulesOn(
        page: Page,
        port: Int,
    ) : SchedulesBrowserSuite() {
        init {
            this.page = page
            this.port = port
        }

        fun signedInWorkspace(slug: String) {
            ready(slug)
        }

        fun openList() = openSchedules()
    }

    /** RestTreeBrowserFixture's own helpers, bound to this test's page and application. */
    private class RestTreeOn(
        page: Page,
        port: Int,
    ) : RestTreeBrowserFixture() {
        init {
            this.page = page
            this.port = port
        }

        fun ready() = readyTree()

        fun seed(name: String) {
            seedPipeline(name)
        }

        fun showTree() = openTree()

        fun folderSelector(path: String) = folder(path)

        fun expandFolder(path: String) = expand(path)

        fun searchFor(value: String) = search(value)
    }

    private companion object {
        /** A planted failure's bound only: its wait can never succeed (the #825/#761 bound). */
        const val PLANTED_BOUND_MS = 5_000.0

        /** Playwright's documented per-action default, which a page the suite left unpatient keeps. */
        const val PLAYWRIGHT_DEFAULT_TIMEOUT_MS = 30_000.0

        const val THEME_PATCH = "**/partials/profile/theme"
        const val SEARCH_FIELD = "#nav-tree-pipelines input[type=search]"
        const val TREE_LINE_BUTTON = ".dp-tree-line button"
        const val SCHEDULES_ALWAYS_BUSY =
            "<!doctype html><title>831 planted schedules</title><div id=\"schedule-list\" aria-busy=\"true\">busy</div>"
        const val DEFINITIONS_TOTAL = 4

        const val SIGN_IN_NEVER_LANDS = "<!doctype html><title>831 planted sign-in</title><main>the sign-in never lands</main>"
        const val NEVER_IDLE =
            "<!doctype html><title>831 planted datasources</title><script>fetch('/831-busy')</script><main>busy for ever</main>"
        const val LIGHT_PAGE_WITHOUT_SUMMARY =
            "<!doctype html><title>831 planted overview</title>" +
                "<link id=\"theme-link\" rel=\"stylesheet\" href=\"/vendor/design-system/themes/light.css\"><main>no summary</main>"
        const val DIALOG_NEVER_OPENS =
            "<!doctype html><title>831 planted overview</title><button data-open=\"definitions\">i</button>" +
                "<dialog data-overview-dialog></dialog>"

        /** A non-modal dialog (the `open` attribute, not showModal): Escape never closes it. */
        const val DIALOG_STAYS_OPEN =
            "<!doctype html><title>831 planted overview</title>" +
                "<footer data-overview-foot><button data-open=\"definitions\">i</button></footer>" +
                "<dialog data-overview-dialog open><h2 data-overview-dialog-title>How the summary is counted</h2>" +
                "<section data-dialog-section=\"definitions\">These four groups total 4.</section>" +
                "<section data-dialog-section=\"prompt\" hidden>prompt</section></dialog>"
    }
}
