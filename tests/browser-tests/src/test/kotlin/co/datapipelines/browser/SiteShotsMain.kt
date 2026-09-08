package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.ElementHandle
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.ReducedMotion
import com.microsoft.playwright.options.WaitForSelectorState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * `./gradlew siteShots` — the marketing site's screenshots, produced by a SCRIPT (070 §C).
 *
 * ## Why this exists rather than ten hand-taken PNGs
 *
 * The editor's layout moves (059 reshaped the graph into cards; 065 re-homes the dock and
 * inspector). Every time it moves, every shot on the marketing page becomes a picture of a
 * product that no longer exists — and the page's own rule is that nothing a reader could
 * mistake for a screenshot may be illustrated or retouched. So the deliverable is the
 * command, not the images: re-run this after a UI round and the page catches up.
 *
 * ## What it drives, and why not the golden-path harness's own app
 *
 * [BrowserSuite] boots the application against throwaway containers with NO sample data. A
 * screenshot of an empty workspace is a mockup with extra steps, so this driver points at a
 * REAL demo deployment — the one `./app.sh --start --demo nyc` produces — and photographs
 * exactly what an engineer evaluating the product sees. It shares that suite's browser
 * discipline (chromium, headless, fixed viewport, no timing sleeps: every wait is a
 * selector or a response) and none of its container plumbing.
 *
 * ```bash
 * ./app.sh --start --demo nyc,trade,lake            # the deployment being photographed
 * ./gradlew siteShots -PshotsUrl=http://localhost:8080 \
 *                     -PshotsEmail=you@example.com -PshotsPassword=... \
 *                     -PshotsHeroOut=../datapipelines-orchestration/handbacks/093-shots
 * ```
 *
 * The `lake` family is not optional for the `site` set since 093: the hero photographs the
 * four-engine showcase pipeline, whose rideshare node reads Parquet on S3 through the `LAKE`
 * dialect, and the dp-lake datasource shot photographs that datasource's registry.
 * `-PshotsHeroOut` is optional and produces the two hero copies the poster and the OG image
 * want; without it the hero is captured once, into the page's own image directory.
 *
 * The account must have signed in once already (its personal workspace is what carries the
 * seeded examples) and must not still owe a forced password change.
 *
 * ## Determinism
 *
 * Fixed 1440x900 viewport at scale 1, `prefers-reduced-motion`, an injected stylesheet that
 * zeroes every animation, transition and caret, `document.fonts.ready` awaited before every
 * capture, and clock-bearing regions blanked ([BLANK_CLOCKS]) — because a relative timestamp
 * ("2 minutes ago") re-renders differently on every run and would make each re-run a diff.
 * Run it twice and the PNGs are byte-identical; that property is the point, and the round
 * that added this task proved it rather than asserting it.
 *
 * ## The `app` set (076 §E)
 *
 * `-PshotsSet=app` skips the marketing list and instead photographs every top-level screen
 * (/dashboard through /admin/users) at BOTH 1440x900 and 2560x1440, in light AND dark — the
 * dark pass driven through the settings theme select (the control a user has), never an
 * emulation, and the account put back on light afterwards so a later `site` run's assertion
 * still holds. One Playwright video of a full boosted-navigation click-through is recorded
 * alongside (app/boost-navigation.webm), from its own context so the still-shot contexts
 * stay clean. Everything lands in an `app/` subfolder of the same output directory.
 */
object SiteShotsMain {
    private const val VIEWPORT_W = 1440
    private const val VIEWPORT_H = 900

    /** The pipeline photographed for the graph, inspector and failure shots. */
    private const val SHOWCASE = "nyc/mobility/weather_sensitivity_by_borough"

    /**
     * The HERO pipeline (093 §B). The four-engine 089 showcase is the product's thesis in one
     * frame: yellow-taxi trips from Postgres, rideshare zone-days from Parquet on S3 (dialect
     * `LAKE`, read in place), the borough lookup from SQLite, the day's rainfall from MySQL, a
     * CALCULATOR deriving the window length, and one staging join over all of it. It needs
     * `--demo nyc,lake` (its lake node needs S3 egress); the driver fails loudly rather than
     * photographing a lesser pipeline in its place, because "several nodes, more than one
     * engine, a staging join" is what the shot is FOR.
     */
    private const val HERO_PIPELINE = "nyc/mobility/taxi_vs_rideshare"

    /** The hero's viewport: taller than the shot list's 1440x900, so the dock fits under the graph. */
    private const val HERO_W = 1440
    private const val HERO_H = 1000

    /** The poster copy: 1200 CSS px at deviceScaleFactor 2 — 2400 device px wide, same aspect. */
    private const val POSTER_W = 1200
    private const val POSTER_H = 833
    private const val POSTER_SCALE = 2.0

    /** The OG copy: a 1200x630 VIEWPORT, so the crop is the browser's, never a resample. */
    private const val OG_W = 1200
    private const val OG_H = 630

    /** The LAKE datasource the `lake` demo family registers, and its dp-catalog namespace root. */
    private const val LAKE_DATASOURCE = "sample-lake"
    private const val LAKE_NAMESPACE_ROOT = "nyc"

    /** Which partial URL a lake-tables expansion fetches (datasources/detail.html). */
    private const val LAKE_PARTIAL = "/lake-tables"

    /**
     * The endpoint the driver publishes so the API section's inventory card has a row and the
     * `endpoint`-kind key has something to bind to. Both are demo state, produced by the
     * script — an empty card and an unbound key would photograph a surface nobody has used.
     */
    private const val DEMO_ENDPOINT_PATH = "/nyc/revenue-by-borough"
    private const val DEMO_ENDPOINT_PIPELINE = "nyc/mobility/revenue_by_borough"

    /** A library template lives here; this one is PINNED by four pipelines, which is what used-by shows. */
    private const val SHARED_TEMPLATE = "nyc/reference/sample_zones.sql"

    /** Created by the driver if absent, so the switcher shot has something to switch between. */
    private const val SECOND_WORKSPACE = "analytics-team"

    /** The two shot sets `-PshotsSet` selects between; `site` is the default. */
    private const val SET_SITE = "site"
    private const val SET_APP = "app"

    /** The subfolder of the output dir the `app` set lands in. */
    private const val APP_DIR = "app"

    /** Which partial URL an explorer expansion fetches — the two trees differ only in this. */
    private const val TEMPLATES_PARTIAL = "/partials/templates"
    private const val PIPELINES_PARTIAL = "/partials/pipelines"

    /** Every top-level screen, in nav order — the `app` set's pages and the video's click path. */
    private val APP_PAGES =
        listOf(
            "/dashboard",
            "/pipelines",
            "/datasources",
            "/templates",
            "/executions",
            // 079 §C: the API section joins the shot list — it is a top-level screen now.
            "/api-console",
            "/workspaces",
            "/promotion",
            "/settings",
            "/docs",
            "/admin/users",
        )

    /**
     * The routes the RAIL links to — APP_PAGES minus `/settings`, which 079 §B moved into the
     * avatar menu. The still-shot list keeps /settings (it is a screen); the click-through
     * cannot, because there is no rail link to click.
     */
    private val RAIL_ROUTES = APP_PAGES.filterNot { it == "/settings" }

    /** The two review widths the `app` set captures every screen at. */
    private val APP_VIEWPORTS = listOf(1440 to 900, 2560 to 1440)

    /** The recorded click-through's frame size. */
    private const val VIDEO_W = 1920
    private const val VIDEO_H = 1080

    private lateinit var baseUrl: String
    private lateinit var outDir: Path

    @JvmStatic
    fun main(args: Array<String>) {
        baseUrl = prop("shots.url", "http://localhost:8080").trimEnd('/')
        val email = prop("shots.email", "")
        val password = prop("shots.password", "")
        outDir = Paths.get(prop("shots.out", "modules/web/src/main/resources/static/site/img"))
        require(email.isNotBlank() && password.isNotBlank()) {
            "siteShots needs an account: -PshotsEmail=… -PshotsPassword=… (see the KDoc)"
        }
        Files.createDirectories(outDir)

        val taken =
            when (prop("shots.set", SET_SITE)) {
                SET_APP -> {
                    val stills = withSignedInPage(email, password) { page -> Shots(page).captureApp() }
                    stills + recordBoostNavigation(email, password)
                }

                else -> {
                    withSignedInPage(email, password) { page ->
                        assertLightTheme(page)
                        Shots(page).captureAll()
                    } + heroExtras(email, password)
                }
            }
        println("siteShots: wrote ${taken.size} artefacts to ${outDir.toAbsolutePath()}")
        taken.forEach { println("  $it") }
    }

    /**
     * Owns the whole browser session — Playwright, the browser, the context — so [main] reads as
     * the six steps it is and each resource still closes in reverse order on any exit path.
     * The theme assertion is the CALLER's business: the `site` list specifies light and asserts
     * it; the `app` list drives both themes on purpose.
     */
    private fun <T> withSignedInPage(
        email: String,
        password: String,
        block: (Page) -> T,
    ): T =
        withBrowser(contextOptions()) { page ->
            signIn(page, email, password)
            block(page)
        }

    /** Playwright + browser + context over [options], every resource closed in reverse order. */
    private fun <T> withBrowser(
        options: Browser.NewContextOptions,
        block: (Page) -> T,
    ): T =
        Playwright.create().use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                browser.newContext(options).use { context ->
                    block(context.newPage())
                }
            }
        }

    /** Fixed viewport at scale 1, light, reduced motion — the determinism contract, in one place. */
    private fun contextOptions(): Browser.NewContextOptions =
        Browser
            .NewContextOptions()
            .setViewportSize(VIEWPORT_W, VIEWPORT_H)
            .setDeviceScaleFactor(1.0)
            .setColorScheme(ColorScheme.LIGHT)
            .setReducedMotion(ReducedMotion.REDUCE)

    private fun prop(
        key: String,
        fallback: String,
    ): String = System.getProperty("dp.$key")?.takeIf { it.isNotBlank() } ?: fallback

    private fun signIn(
        page: Page,
        email: String,
        password: String,
    ) {
        page.navigate("$baseUrl/login")
        page.fill("#login-email", email)
        page.fill("#login-password", password)
        page.click("form button[type=submit]")
        page.waitForURL("**/dashboard")
        // A forced password change would silently redirect every later navigate to the change
        // form and every shot would be that form. Fail loudly instead (auth.md §5A.4).
        check(!page.url().contains("/settings/password")) {
            "the account still owes a forced password change — sign in once by hand first"
        }
    }

    /**
     * The shots are specified as LIGHT theme. The theme is a user preference falling back to
     * `datapipelines.ui.theme`, so it is a property of the deployment and the account, not of
     * this driver — assert it rather than fight it.
     */
    private fun assertLightTheme(page: Page) {
        page.navigate("$baseUrl/dashboard")
        val href = page.locator("#theme-link").getAttribute("href") ?: ""
        val dark = href.contains("dark")
        check(!dark) { "the account's theme resolves to $href — the shot list specifies light" }
    }

    /**
     * The two extra copies of the hero (093 §B) — the poster's and the OG image's — written to
     * `-PshotsHeroOut=<dir>` and skipped entirely when that property is absent, because they do
     * not ship with the app and must not land in `static/site/img`.
     *
     * They are the SAME capture of the SAME screen, differing only in how the browser was asked
     * to paint it. `deviceScaleFactor` is a CONTEXT option in Playwright, not a page one, so the
     * poster copy needs its own browser context — which is the whole reason this is a separate
     * pass rather than three `shoot()` calls inside [Shots]. The OG copy is a 1200x630 VIEWPORT
     * captured at CSS scale: the browser lays the page out into that frame and the shutter takes
     * it whole, so the result is a crop, never a resample of a wider image.
     */
    private fun heroExtras(
        email: String,
        password: String,
    ): List<String> {
        val dir = prop("shots.heroOut", "")
        if (dir.isBlank()) {
            println("  SKIP the hero's poster and OG copies — no -PshotsHeroOut=<dir> given")
            return emptyList()
        }
        val target = Paths.get(dir)
        Files.createDirectories(target)
        val written = mutableListOf<String>()

        // Poster: 1200 CSS px at scale 2 = 2400 device px wide, captured at DEVICE scale.
        withBrowser(
            contextOptions()
                .setViewportSize(POSTER_W, POSTER_H)
                .setDeviceScaleFactor(POSTER_SCALE),
        ) { page ->
            signIn(page, email, password)
            val shots = Shots(page)
            if (!shots.heroScreen("editor-hero-2400.png")) return@withBrowser
            page.screenshot(
                Page
                    .ScreenshotOptions()
                    .setPath(target.resolve("editor-hero-2400.png"))
                    .setClip(0.0, 0.0, POSTER_W.toDouble(), POSTER_H.toDouble())
                    .setAnimations(com.microsoft.playwright.options.ScreenshotAnimations.DISABLED)
                    .setScale(com.microsoft.playwright.options.ScreenshotScale.DEVICE),
            )
            written += target.resolve("editor-hero-2400.png").toString()
        }

        // OG: the browser lays the page out into 1200x630 and the shutter takes that frame.
        withBrowser(contextOptions().setViewportSize(OG_W, OG_H)) { page ->
            signIn(page, email, password)
            val shots = Shots(page)
            if (!shots.heroScreen("editor-hero-1200x630.png")) return@withBrowser
            page.screenshot(
                Page
                    .ScreenshotOptions()
                    .setPath(target.resolve("editor-hero-1200x630.png"))
                    .setClip(0.0, 0.0, OG_W.toDouble(), OG_H.toDouble())
                    .setAnimations(com.microsoft.playwright.options.ScreenshotAnimations.DISABLED)
                    .setScale(com.microsoft.playwright.options.ScreenshotScale.CSS),
            )
            written += target.resolve("editor-hero-1200x630.png").toString()
        }
        return written
    }

    /**
     * One boosted-navigation pass, recorded (076 §E): the point of the video is that the nav
     * NEVER re-renders across the click-through — hx-boost swaps only #app-main, so per click
     * the waits are the URL change and network idle, never a fixed sleep. Its own context, so
     * the recording options stay off the still-shot contexts. Playwright finalises the .webm
     * on context.close(); it is then renamed to the stable path the evidence pack expects.
     */
    private fun recordBoostNavigation(
        email: String,
        password: String,
    ): List<String> {
        val videoDir = outDir.resolve(APP_DIR).resolve("video")
        Files.createDirectories(videoDir)
        val options =
            contextOptions()
                .setViewportSize(VIDEO_W, VIDEO_H)
                .setRecordVideoDir(videoDir)
                .setRecordVideoSize(VIDEO_W, VIDEO_H)
        // Playwright finalises the .webm on context.close(); it is renamed below.
        withBrowser(options) { page ->
            signIn(page, email, password)

            // 079: the walk uses RAIL_ROUTES, not APP_PAGES. /settings is still a screen and
            // is still photographed, but it has no rail link any more — it moved into the
            // avatar menu (§B) — so clicking `nav.app-nav a[href='/settings']` waits forever.
            RAIL_ROUTES.forEach { route ->
                page.locator("nav.app-nav a[href='$route']").first().click()
                page.waitForURL({ url -> url.endsWith(route) })
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
            }

            // 079 §A/§B: the four interactions the round asks a reviewer to watch, after the
            // section walk. Each waits on the state it produces, never on a clock.
            page.locator("#rail-collapse").click()
            page.locator("html.rail-collapsed").waitFor()
            page.locator("#rail-collapse").click()
            page.locator("html:not(.rail-collapsed)").waitFor()

            page.locator("#app-avatar").click()
            page.locator("#app-user-menu:not([hidden])").waitFor()

            // The theme PATCH answers with an out-of-band swap of #theme-link, so the href
            // CHANGING is the completion signal — a sleep here would film a half-swapped page.
            page.locator("#mode-toggle").click()
            page.waitForFunction(
                "() => document.getElementById('theme-link').getAttribute('href').includes('/themes/dark.css')",
            )
            page.locator("#mode-toggle").click()
            page.waitForFunction(
                "() => document.getElementById('theme-link').getAttribute('href').includes('/themes/light.css')",
            )
            page.keyboard().press("Escape")

            // Three sections in the restored theme, so the film ends where a reader starts.
            listOf("/pipelines", "/api-console", "/executions").forEach { route ->
                page.locator("nav.app-nav a[href='$route']").first().click()
                page.waitForURL({ url -> url.endsWith(route) })
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
            }
        }
        val produced =
            Files.list(videoDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".webm") }.findFirst()
            }
        check(produced.isPresent) { "the video context closed but $videoDir holds no .webm" }
        val target = outDir.resolve(APP_DIR).resolve("boost-navigation.webm")
        Files.move(produced.get(), target, StandardCopyOption.REPLACE_EXISTING)
        Files.delete(videoDir)
        return listOf("$APP_DIR/boost-navigation.webm")
    }

    /** Every capture goes through here, so no shot can skip the determinism steps. */
    internal class Shots(
        private val page: Page,
    ) {
        private val written = mutableListOf<String>()

        fun captureAll(): List<String> {
            datasources()
            datasourceLake()
            templateExplorer()
            templateUsedBy()
            // 093: publish the demo endpoint FIRST — the keys shot needs a node to bind an
            // `endpoint`-kind key to, and the API-section shot needs the inventory to have a row.
            publishDemoEndpoint()
            keys()
            apiConsole()
            workspaces()
            editorHero()
            editorRunAndGraph()
            nodeInspector()
            failureDetail()
            executions()
            executionResult()
            promotion()
            shellDark()
            sitePageReview()
            return written
        }

        /**
         * The `app` set (076 §E): every top-level screen at BOTH review widths in BOTH
         * themes. The theme is the account's preference, driven through the settings UI —
         * the control a user has, never a stylesheet hack — and put back on light at the
         * end so a later `site` run's light-theme assertion still holds.
         */
        fun captureApp(): List<String> {
            Files.createDirectories(outDir.resolve(APP_DIR))
            setTheme("light")
            captureAppPasses("light")
            setTheme("dark")
            captureAppPasses("dark")
            setTheme("light")
            return written
        }

        private fun captureAppPasses(theme: String) {
            APP_VIEWPORTS.forEach { (width, height) ->
                page.setViewportSize(width, height)
                APP_PAGES.forEach { route ->
                    page.navigate("$baseUrl$route")
                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
                    shoot("$APP_DIR/${route.substring(1).replace('/', '-')}-${width}x$height-$theme.png")
                }
            }
        }

        /**
         * The same select#themeSelect a user drives (settings/index.html): the PATCH answers
         * with an out-of-band swap of #theme-link, so the href CHANGING is the completion
         * signal — anything else (or a sleep) photographs a half-swapped page.
         */
        private fun setTheme(theme: String) {
            page.navigate("$baseUrl/settings")
            waitFor("#themeSelect")
            val current = page.locator("#theme-link").getAttribute("href") ?: ""
            if (current.contains("/themes/$theme.css")) return
            page.selectOption("#themeSelect", theme)
            page.waitForFunction(
                "(expected) => document.querySelector('#theme-link').getAttribute('href').includes(expected)",
                "/themes/$theme.css",
            )
        }

        /**
         * The rail's collapse toggle, driven by the button a user clicks. `shell.js` writes
         * `dp-rail` to localStorage on that click, so the state survives navigation; the class
         * on `<html>` appearing (or leaving) is the completion signal, never a sleep.
         */
        private fun setRailCollapsed(collapsed: Boolean) {
            page.navigate("$baseUrl/dashboard")
            waitFor("#rail-collapse")
            val already = page.locator("html.rail-collapsed").count() > 0
            if (already == collapsed) return
            page.locator("#rail-collapse").click()
            if (collapsed) {
                page.locator("html.rail-collapsed").waitFor()
            } else {
                page.locator("html:not(.rail-collapsed)").waitFor()
            }
        }

        // ---------------------------------------------------------------- shots

        private fun datasources() {
            page.navigate("$baseUrl/datasources")
            waitFor(".ds-table")
            // "last-test outcome visible" means the probe has actually run — click every Test
            // and wait for its response, never a sleep.
            page.locator("button:has-text('Test')").all().forEach { button ->
                page.waitForResponse({ it.url().contains("/test") }) { button.click() }
            }
            settle()
            shoot("datasources.png")
        }

        private fun templateExplorer() {
            page.navigate("$baseUrl/templates")
            waitFor("#template-list-wrapper")
            expandFolder("nyc")
            expandFolder("nyc/mobility")
            selectLeaf("nyc/mobility/od_matrix.sql")
            shoot("template-explorer.png")
        }

        private fun templateUsedBy() {
            page.navigate("$baseUrl/templates")
            waitFor("#template-list-wrapper")
            selectLeaf(SHARED_TEMPLATE)
            // The detail pane's versions table carries the per-version in-use count — the
            // used-by surface on this screen (templates.md §5.4, ui-screens.md §4.6).
            waitFor("#template-detail .ds-table")
            shoot("template-used-by.png")
        }

        /**
         * The dp-lake datasource's own page (089 §A): the dp-catalog registry as a read-only
         * namespace tree. Expanded to the leaves, because "the registry has four tables in it"
         * is the fact the shot exists to state — a collapsed folder says nothing.
         */
        private fun datasourceLake() {
            page.navigate("$baseUrl/datasources/$LAKE_DATASOURCE")
            waitFor("#lake-table-tree")
            // The namespace tree is the SAME markup as the template and pipeline explorers, so
            // the selectors transfer; only the partial URL differs (detail.html).
            expandFolder(LAKE_NAMESPACE_ROOT, LAKE_PARTIAL)
            expandFolder("$LAKE_NAMESPACE_ROOT/mobility", LAKE_PARTIAL)
            val leaves = page.locator("#lake-table-tree .tpl-leaf-static").count()
            check(leaves > 0) {
                "the $LAKE_DATASOURCE registry shows no tables — is the lake family loaded (--demo lake)?"
            }
            shoot("datasource-lake.png")
        }

        /**
         * The endpoint the API section's inventory card lists and the `endpoint` key binds to.
         * Idempotent: a `409` on the second run is the row already being there, which is the
         * state we want. Published through the REST surface the UI calls, not by clicking a
         * screen that does not offer publication (074 step 6: publishing stays on REST/MCP).
         */
        private fun publishDemoEndpoint() {
            page.navigate("$baseUrl/api-console")
            waitFor("body")
            val status =
                page.evaluate(
                    PUBLISH_ENDPOINT,
                    listOf(baseUrl, DEMO_ENDPOINT_PATH, DEMO_ENDPOINT_PIPELINE),
                ) as Int
            check(status == 200 || status == 201 || status == 409) {
                "publishing $DEMO_ENDPOINT_PATH answered $status — the API-section shots need it"
            }
        }

        /**
         * The key-minting ceremony (091): the modal's field ORDER is the claim — Kind → Scope →
         * Name → Expiry → Bindings, scope and bindings conditional on the kind — so the shot is
         * the open form, not the table. The table is [apiConsole]'s business: at 1440x900 the
         * whole API section fits one viewport, so two captures of it would be the same bytes
         * (measured 2026-09-08: byte-identical files).
         *
         * The two keys are minted first because [apiConsole] needs them: a `user` key (an agent's
         * and a program's key are the same credential) and an `endpoint` key bound to a published
         * path, whose "reach" cell is a path and not a scope — the distinction the column exists
         * to make.
         */
        private fun keys() {
            page.navigate("$baseUrl/api-console")
            // The table arrives with the page but the EMPTY state is a different element —
            // wait for whichever landed. Counting rows before either exists reports zero, and
            // this method would mint a fresh key on every run (it minted five before this
            // wait was here) until the shot was a wall of duplicates.
            waitFor(".ds-table, .ds-empty")
            // Fixed names, not timestamped ones: two runs of this command must produce the
            // same pixels, and "agent-2026-09-04" in a table cell would differ tomorrow.
            mintKeyIfAbsent("analytics-agent", kind = "user", binding = null)
            mintKeyIfAbsent("reporting-endpoint", kind = "endpoint", binding = DEMO_ENDPOINT_PATH)
            page.navigate("$baseUrl/api-console")
            waitFor(".ds-table")
            refuseVisibleSecret()
            // The MINT ceremony, which is the 091 claim the page makes: Kind → Scope → Name →
            // Expiry → Bindings, in that order, with scope and bindings conditional on the kind.
            // The default kind is `user`, so this is the form as it opens — nothing is driven
            // into an unusual state for the camera.
            page.click("button:has-text('New key')")
            page.locator("#key-modal").waitFor()
            waitFor("#key-scope-field")
            shoot("keys.png")
            page.keyboard().press("Escape")
        }

        /**
         * The API section itself: the keys table (two kinds, two reaches), the published-endpoint
         * inventory, and the MCP connection card — which renders the live tool count from
         * `McpToolCatalog.NAMES.size`, exactly as the marketing page does. One capture, because at
         * 1440x900 the whole section is one screen.
         */
        private fun apiConsole() {
            page.navigate("$baseUrl/api-console")
            waitFor(".ds-table")
            page.locator("h2.ds-title:text-is('Published endpoints')").first().waitFor()
            page.locator("h2.ds-title:text-is('MCP server')").first().waitFor()
            refuseVisibleSecret()
            shoot("api-console.png")
        }

        /**
         * The create-key modal, driven the way a person drives it: Kind first (it decides what
         * the rest of the form MEANS), then scope or bindings, then the name. `check()` on the
         * radio dispatches the change event `keyKindChanged()` listens for — a `.click()` on the
         * label would too, but the radio is what the handler is bound to.
         *
         * The name lives in a SPAN inside the cell (a revoked key adds a badge beside it), so an
         * exact-text match on the `td` misses and this method mints a duplicate on every run —
         * which it did, four times, before this selector was corrected.
         */
        private fun mintKeyIfAbsent(
            name: String,
            kind: String,
            binding: String?,
        ) {
            if (page.locator("#keys-table-body span:text-is('$name')").count() > 0) return
            page.click("button:has-text('New key')")
            page.locator("#key-modal").waitFor()
            page.locator("#key-modal input[name=kind][value=$kind]").check()
            if (binding != null) {
                // The bindings picker only exists for the `endpoint` kind, and only after the
                // kind's change handler has run — wait for the field, never for a clock.
                page.locator("#key-bindings-field:not(.u-hidden)").waitFor()
                val node = bindingNode(binding)
                page.locator("#key-bindings-field input[name=bindings][value='$node']").check()
            }
            page.fill("#key-name", name)
            page.waitForResponse({ it.url().contains("/partials/api-keys") }) {
                page.click("#createKeyForm button[type=submit]")
            }
            // The once-only reveal must be OFF SCREEN before the shutter: a live secret in a
            // published PNG is a leak that no later edit can undo (093 §2).
            dismissSecretReveal()
        }

        /** The offered binding node closest to [path] — its parent prefix, or the root. */
        private fun bindingNode(path: String): String {
            val parent = path.substringBeforeLast('/', "")
            val offered = page.locator("#key-bindings-field input[name=bindings][value='$parent']")
            return if (parent.isNotBlank() && offered.count() > 0) parent else "/"
        }

        /**
         * The listing shows each key's PREFIX (`dpk_` + 12 chars) — an identifier, and the only
         * way to tell two keys apart when revoking one. The SECRET is far longer and is shown
         * exactly once, at creation. Refuse to photograph anything longer than a prefix.
         */
        private fun refuseVisibleSecret() {
            val secrets = SECRET_SHAPED.findAll(page.content()).map { it.value }.filter { it.length > KEY_PREFIX_MAX }
            check(secrets.none()) {
                "a full-length API key is still on the page (${secrets.first().take(8)}…) — refusing to photograph it"
            }
        }

        private fun workspaces() {
            page.navigate("$baseUrl/workspaces")
            waitFor("body")
            // The screen has TWO tables — "Your workspaces" and the members list — so a bare
            // `table tbody tr` count is answered by the members table alone and the creation
            // below is skipped forever. Count the WORKSPACES table (the first one).
            val workspaceRows = page.locator("table").first().locator("tbody tr")
            if (workspaceRows.count() < 2) {
                page.fill("form[action*='/workspaces/create'] input[name=name]", SECOND_WORKSPACE)
                page.click("form[action*='/workspaces/create'] button[type=submit]")
                page.locator("td:has-text('$SECOND_WORKSPACE')").first().waitFor()
            }
            check(page.locator("#workspace-switcher").count() == 1) { "no workspace switcher on the page" }
            check(
                page
                    .locator("table")
                    .first()
                    .locator("tbody tr")
                    .count() >= 2,
            ) {
                "the workspaces shot needs two workspaces; the screen shows " +
                    page
                        .locator("table")
                        .first()
                        .locator("tbody tr")
                        .count()
            }
            shoot("workspaces.png")
        }

        /**
         * The HERO (093 §B) — the editor on the four-engine 089 showcase after a SUCCESSFUL run,
         * the Results dock open on the run's rows, the sidebar collapsed so the canvas has the
         * width, at 1440x1000.
         *
         * The rail's collapsed state is persisted in `localStorage` under `dp-rail`
         * (layouts/default.html), so it survives the navigation the editor does — which is why
         * it is set BEFORE opening the pipeline and cleared after, leaving the account exactly
         * as the other shots expect to find it.
         */
        private fun editorHero() {
            if (heroScreen()) shoot("editor-hero.png")
            page.setViewportSize(VIEWPORT_W, VIEWPORT_H)
            setRailCollapsed(false)
        }

        /**
         * Rule 2 of the marketing page: a screen that renders broken is not photographed, it is
         * REPORTED. The canvas is the one surface no selector-based wait can check — Cytoscape
         * paints to a bitmap — so this reads the colours the graph actually resolved and refuses
         * a node surface that came back as the brand colour.
         *
         * Measured 2026-09-08 (093): `readDesignTokens` resolves twelve tokens through ONE reused
         * probe span, and every read after the first returns the FIRST value. When the design-token
         * sheet is applied before the editor initialises, every node card, edge, edge label and
         * state accent therefore paints `--brand` — an indigo blob with unreadable node text. When
         * the sheet has NOT applied yet, each token falls back to its hard-coded light hex and the
         * canvas looks right, so the defect comes and goes with a stylesheet race.
         *
         * A skip, not a failure: every non-canvas shot in the list is still worth taking, and the
         * promotion shot already established the pattern.
         */
        private fun canvasPaintsSanely(shot: String): Boolean {
            @Suppress("UNCHECKED_CAST")
            val painted = page.evaluate(CANVAS_TOKENS) as Map<String, Any?>
            val nodeBg = painted["nodeBg"]?.toString().orEmpty()
            val brand = painted["brand"]?.toString().orEmpty()
            if (nodeBg.isNotBlank() && sameColour(nodeBg, brand)) {
                println("  SKIP $shot — the graph resolved its node surface to the BRAND colour")
                println("         nodeBg=$nodeBg brand=$brand surfaceRaised=${painted["surfaceRaised"]}")
                println("         one reused probe span, two reads: ${painted["reusedProbe"]}")
                println("         (graph.js colourResolver — a broken canvas is reported, not photographed)")
                return false
            }
            return true
        }

        /** `rgb(79,70,229)` and `#4f46e5` are the same colour; compare the digits, not the syntax. */
        private fun sameColour(
            a: String,
            b: String,
        ): Boolean {
            val x = colourBytes(a)
            val y = colourBytes(b)
            return x.size == 3 && x == y
        }

        /** The three sRGB bytes of a `#rrggbb` or `rgb(r, g, b)` string; empty for anything else. */
        private fun colourBytes(value: String): List<Int> {
            if (value.startsWith("#") && value.length == 7) {
                return (1..5 step 2).map { value.substring(it, it + 2).toInt(16) }
            }
            return Regex("""\d+""")
                .findAll(value)
                .map { it.value.toInt() }
                .take(3)
                .toList()
        }

        /**
         * Everything the hero shot IS, short of the shutter: the same screen in the same state,
         * so [heroExtras]' poster and OG copies photograph the hero rather than something like
         * it. It leaves the viewport where the caller set it — the three captures differ only
         * in frame, and that difference is the caller's business. Returns whether the canvas is
         * worth photographing at all ([canvasPaintsSanely]).
         */
        internal fun heroScreen(shot: String = "editor-hero.png"): Boolean {
            setRailCollapsed(true)
            if (page.viewportSize().width == VIEWPORT_W && page.viewportSize().height == VIEWPORT_H) {
                page.setViewportSize(HERO_W, HERO_H)
            }
            openEditor(HERO_PIPELINE)
            execute()
            // 080: the dock's four tabs are Details | Results | Errors | Events; a successful
            // run lands on Results, and the table is the signal the rows arrived.
            waitFor(".pe-dock")
            waitFor(".pe-dock .ds-table")
            // The results dock takes the lower half; without a fit only the first cards are in
            // frame, and the shot is supposed to show the run state of EVERY node.
            fitGraph()
            return canvasPaintsSanely(shot)
        }

        /**
         * The theme toggle is a feature; show it once (093 §C). Driven through the settings
         * select — the control a user has, never an emulation or a stylesheet hack — and put
         * back on light immediately, so the `site` set's light-theme assertion still holds on
         * the next run and every other shot in THIS run has already been taken.
         */
        private fun shellDark() {
            setTheme("dark")
            page.navigate("$baseUrl/pipelines")
            waitFor("#pipeline-list-wrapper")
            expandFolder("nyc", PIPELINES_PARTIAL)
            expandFolder("nyc/mobility", PIPELINES_PARTIAL)
            shoot("shell-dark.png")
            setTheme("light")
        }

        private fun editorRunAndGraph() {
            openEditor(SHOWCASE)
            execute()
            // 065: the results live in the bottom dock (Results | Errors), never a closable panel.
            waitFor(".pe-dock")
            waitFor(".pe-dock .ds-table")
            // The results dock takes the lower half; without a fit only the first cards are in
            // frame, and the shot is supposed to show the run state of EVERY node.
            fitGraph()
            if (!canvasPaintsSanely("graph-cards.png")) return
            shoot("graph-cards.png")
        }

        private fun nodeInspector() {
            openEditor(SHOWCASE)
            // FIT FIRST. At the canvas's default zoom a five-node dagre layout is wider than a
            // 1440 viewport, and the right-hand cards' expand buttons land OUTSIDE it — measured
            // 2026-09-08 on `nyc/mobility/borough_od_matrix`: card 2's button sat at x=1656 and
            // `document.elementFromPoint` returned null, which Playwright reports as the page
            // background "intercepting pointer events". `scrollIntoViewIfNeeded` cannot help: a
            // Cytoscape overlay is positioned, not scrolled. The product is fine — the canvas is
            // pannable and the fit control is right there; the driver just has to use it.
            fitGraph()
            if (!canvasPaintsSanely("node-inspector.png")) return
            selectNodeOnCanvas(0)
            // The resolved SQL is fetched server-side; the spinner leaving is the release signal.
            page.locator("#pe-node-sql-spinner").waitFor(
                com.microsoft.playwright.Locator
                    .WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN),
            )
            waitFor("#pe-node-sql")
            shoot("node-inspector.png")
        }

        /**
         * The failure shot. No seeded pipeline fails — a demo whose examples break would be a
         * bad demo — so the state is produced honestly: a pipeline pinned to a datasource
         * whose password is wrong, in this throwaway stack. Nothing is illustrated; the
         * exception chain on screen is the driver's own.
         */
        private fun failureDetail() {
            val pipeline = System.getProperty("dp.shots.failingPipeline")
            if (pipeline.isNullOrBlank()) {
                println("  SKIP failure-detail.png — no -PshotsFailingPipeline=<name> given")
                return
            }
            openEditor(pipeline)
            execute()
            // 065: the first node failure opens the dock on the Errors tab (pipeline-editor §9).
            // §9.1's modal still restates the user-facing message over the graph; the shot is
            // about the CARD and the Errors tab beneath it, so dismiss it — it is a real element
            // of the flow, not of this state. The chain is the point of the shot (dag-executor
            // §8.4) — open every collapsed level.
            waitFor(".pe-dock-error")
            val dismiss = page.locator("button:has-text('Dismiss')").first()
            if (dismiss.isVisible) {
                dismiss.click()
                dismiss.waitFor(
                    com.microsoft.playwright.Locator
                        .WaitForOptions()
                        .setState(WaitForSelectorState.HIDDEN),
                )
            }
            page.locator(".pe-dock-error details").all().forEach { it.evaluate("e => e.open = true") }
            if (!canvasPaintsSanely("failure-detail.png")) return
            settle()
            shoot("failure-detail.png")
        }

        private fun executions() {
            page.navigate("$baseUrl/executions")
            waitFor(".ds-table")
            shoot("executions.png")
        }

        /**
         * The execution DETAIL of a SUCCESS run — the badge, the per-node stats and the rows
         * themselves. This is the "agent checks its own work" story and it is not a canvas
         * screen, so it survives the graph.js token defect the editor shots do not.
         *
         * The row is chosen by its SUCCESS badge, not by position: the history is ordered by
         * time and the newest run is whatever this driver did last, which on the failure pass
         * is a FAILED one.
         */
        private fun executionResult() {
            page.navigate("$baseUrl/executions")
            waitFor(".ds-table")
            // The row is a clickable TR, not a link (partials/executions.html sets an onclick),
            // so there is nothing to click INSIDE it — click the row. Newest first, so the first
            // SUCCESS is the freshest one and its result is still inside the Redis TTL.
            val row = page.locator("tbody tr.app-row-clickable:has-text('SUCCESS')").first()
            row.waitFor()
            row.click()
            page.waitForURL(Regex(".*/executions/[0-9a-f-]+$").toPattern())
            // `#result-content` self-loads (hx-trigger="load"); the ROWS are the signal, not the
            // page frame — a shot taken on the frame photographs the spinner.
            page.locator("#result-content table tbody tr").first().waitFor()
            shoot("execution-result.png")
        }

        private fun promotion() {
            page.navigate("$baseUrl/promotion")
            waitFor("body")
            // Three states are truthful but are NOT the one the shot list asks for: no target
            // configured, target unreachable, and target refusing (ui-screens §4.17). Skip
            // loudly on any of them — photographing an error page and shipping it as "here is
            // promotion" is the mocked visual this whole task exists to avoid. The shot needs a
            // live receiver; DEVELOPMENT.md §9.0 says how to run one.
            // Three states are truthful but are NOT the one the shot list asks for. Match the
            // MARKUP, not prose: `.ds-empty-title` is the element promotion/index.html renders
            // for every one of them ("No promotion target configured" when nothing is set,
            // "Could not read the target" when it is unreachable or refusing). The 070 version
            // matched a phrase list and missed "No promotion target configured" — none of its
            // alternatives is a substring of it — so it photographed the empty state and shipped
            // it as "here is promotion" (measured 2026-09-08).
            val blocked = page.locator(".ds-empty .ds-empty-title")
            if (blocked.count() > 0) {
                val why =
                    blocked
                        .first()
                        .innerText()
                        .take(80)
                        .replace("\n", " ")
                println("  SKIP promotion.png — the promotion target is not usable right now: $why")
                println("         (start a receiver — deployment.md §6.3A — and re-run for this shot)")
                return
            }
            waitFor(".ds-table")
            shoot("promotion.png")
        }

        /**
         * Two FULL-PAGE captures of the marketing page itself — desktop and phone — for the
         * reviewer who has to decide whether the page still lays out. They are review
         * artefacts, not shipped assets, so they land under `build/` and are never referenced
         * by the page: a layout regression is a thing to LOOK at, and 059's lesson was that
         * nobody looks unless the images are produced for them.
         */
        private fun sitePageReview() {
            val dir =
                java.nio.file.Paths
                    .get(prop("shots.review", "build/site-review"))
            Files.createDirectories(dir)
            listOf("site-1440.png" to 1440, "site-390.png" to 390).forEach { (file, width) ->
                page.setViewportSize(width, VIEWPORT_H)
                page.navigate("$baseUrl/")
                waitFor("#hero-title")
                // Every shot on the page is `loading="lazy"`, and a full-page capture does NOT
                // scroll — so without this the review capture shows the layout with empty
                // boxes where the screenshots are, which is precisely the thing being reviewed.
                page.evaluate(SCROLL_THROUGH)
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
                settle()
                page.screenshot(
                    Page
                        .ScreenshotOptions()
                        .setPath(dir.resolve(file))
                        .setFullPage(true)
                        .setAnimations(com.microsoft.playwright.options.ScreenshotAnimations.DISABLED)
                        .setScale(com.microsoft.playwright.options.ScreenshotScale.CSS),
                )
                written += "build/site-review/$file"
            }
            page.setViewportSize(VIEWPORT_W, VIEWPORT_H)
        }

        // ---------------------------------------------------------------- helpers

        private fun openEditor(name: String) {
            page.navigate("$baseUrl/pipelines")
            waitFor("#pipeline-list-wrapper")
            // 067: the explorer is a folder tree, not a table — expand the name's prefix one
            // segment at a time (each expansion fetches ONE more level), then read the editor
            // URL off the LEAF itself: data-editor-url is the same URL the detail pane's Open
            // button uses, and reading it depends on no detail-pane markup.
            name
                .split('/')
                .dropLast(1)
                .runningReduce { prefix, segment -> "$prefix/$segment" }
                .forEach { prefix -> expandFolder(prefix, PIPELINES_PARTIAL) }
            val leaf = page.locator("button.tpl-leaf:has(.tpl-label[title='$name'])").first()
            leaf.waitFor()
            val editorUrl =
                leaf.getAttribute("data-editor-url")
                    ?: error("pipeline leaf '$name' carries no data-editor-url")
            page.navigate("$baseUrl$editorUrl")
            // 080 renamed the canvas element: `#cy-container` (065) is `#cy-canvas` now, and
            // `PipelineGraph` is constructed against that id (init.js). Waiting on the old one
            // times out on a page that rendered perfectly.
            waitFor("#cy-canvas")
            // The graph is drawn by Cytoscape after the body loads. The node CARDS are the
            // DOM signal that it finished — `#pe-node-list` is visually clipped for screen
            // readers (031) and never becomes "visible" to a waiting locator.
            page.locator(".pe-card").first().waitFor()
        }

        private fun execute() {
            page.click("button:has-text('Execute')")
            // The terminal state, not a timeout: the Execute button re-enables when the stream
            // ends (x-bind:disabled="isExecuting").
            page.locator("button:has-text('Execute'):not([disabled])").waitFor(
                com.microsoft.playwright.Locator
                    .WaitForOptions()
                    .setTimeout(EXECUTION_TIMEOUT_MS),
            )
        }

        /**
         * The card overlay becomes VISIBLE a frame before Cytoscape has positioned it, so a
         * bounding box read on the visibility signal alone comes back null or zero-sized — a
         * flake that failed one run in three before this poll existed. Waits for a box with
         * real area instead of trusting visibility.
         */
        private fun laidOutBox(
            card: com.microsoft.playwright.Locator,
            index: Int,
        ): com.microsoft.playwright.options.BoundingBox {
            val deadline = System.nanoTime() + LAYOUT_TIMEOUT_MS.toLong() * 1_000_000
            while (System.nanoTime() < deadline) {
                val box = card.boundingBox()
                if (box != null && box.width > 0 && box.height > 0) return box
                page.waitForTimeout(LAYOUT_POLL_MS)
            }
            error("node card $index still had no laid-out box after ${LAYOUT_TIMEOUT_MS.toInt()} ms")
        }

        private fun fitGraph() {
            page.locator(".pe-graph-controls button[aria-label='Fit graph to view']").click()
            // Cytoscape tweens the fit on the canvas; the injected stylesheet zeroes CSS
            // animation, which a canvas tween is not.
            page.waitForTimeout(FIT_SETTLE_MS)
        }

        /**
         * Selects a node by clicking the CANVAS under its card. The card overlay is
         * `pointer-events: none` (pipeline-editor.md §5.3) so that Cytoscape keeps every
         * interaction — which means a click on the card element itself reaches nothing. The
         * card's own bounding box is the coordinate source, so this follows the layout instead
         * of hard-coding a position dagre is free to change.
         */
        private fun selectNodeOnCanvas(index: Int) {
            val card = page.locator(".pe-card").nth(index)
            card.waitFor()
            val box = laidOutBox(card, index)
            // 080: a click on the card SELECTS and fills the dock's Details tab; the
            // card's own expand button is the explicit route in (it sits in the
            // card's top-right corner).
            page.mouse().click(box.x + box.width / 2, box.y + box.height / 2)
            val open = card.locator(".pe-card-open").first()
            val openBox = open.boundingBox()
            val viewport = page.viewportSize()
            check(
                openBox != null &&
                    openBox.x >= 0 && openBox.y >= 0 &&
                    openBox.x + openBox.width <= viewport.width &&
                    openBox.y + openBox.height <= viewport.height,
            ) {
                "node card $index's expand button is outside the ${viewport.width}x${viewport.height} " +
                    "viewport (${openBox?.x}, ${openBox?.y}) — fit the graph before selecting"
            }
            open.click()
            page.locator("#pe-pane-details .pe-details").waitFor()
            page.evaluate("() => document.activeElement && document.activeElement.blur()")
        }

        /**
         * A folder is a `<summary>` whose label carries the FULL prefix on `title` — the rule
         * every surface showing a template name follows (template-hierarchy-design §9.4), and
         * the only attribute here that is unambiguous: the visible text is the last segment
         * only, so two `mobility` folders under different parents would both match it.
         *
         * The pipeline explorer (067) renders the SAME markup by design — its partial states
         * the selectors transfer unchanged — so [partial] is the only explorer-specific bit:
         * which partial URL the expansion's one-level fetch goes to.
         */
        private fun expandFolder(
            prefix: String,
            partial: String = TEMPLATES_PARTIAL,
        ) {
            val summary = page.locator("summary.tpl-summary:has(.tpl-label[title='$prefix'])").first()
            summary.waitFor()
            // `hx-trigger="click once"` means a second click never fetches again, so waiting for
            // a response on an ALREADY-open folder waits forever. Expanding an open folder is a
            // no-op the caller is allowed to ask for (a leaf's path is expanded blindly).
            if (summary.getAttribute("aria-expanded") == "true") return
            page.waitForResponse({ it.url().contains(partial) }) { summary.click() }
        }

        /**
         * Selects a template leaf, expanding its folder path first. The expansion is NOT
         * optional and is not inherited from a previous shot: each capture navigates, and a
         * navigation collapses the tree back to its roots — a leaf two levels down is simply
         * not in the DOM until the levels above it have been fetched (measured 2026-09-08: the
         * used-by shot timed out on exactly this, waiting 30 s for a leaf nothing had loaded).
         */
        private fun selectLeaf(name: String) {
            name
                .split('/')
                .dropLast(1)
                .runningReduce { prefix, segment -> "$prefix/$segment" }
                .forEach { prefix -> expandFolder(prefix) }
            val leaf = page.locator("button.tpl-leaf:has(.tpl-label[title='$name'])").first()
            leaf.waitFor()
            page.waitForResponse({ it.url().contains("/partials/templates/versions") }) { leaf.click() }
            waitFor("#template-detail .ds-table")
        }

        private fun dismissSecretReveal() {
            page
                .locator(".ds-modal-close, button:has-text('Done'), button:has-text('Close')")
                .all()
                .filter { it.isVisible }
                .forEach { it.click() }
        }

        private fun waitFor(selector: String): ElementHandle? =
            page.waitForSelector(selector, Page.WaitForSelectorOptions().setTimeout(WAIT_MS))

        /**
         * Everything that must be true before the shutter: fonts resolved (a fallback face
         * re-flows every label), animations and carets dead, and every clock blanked.
         */
        private fun settle() {
            // A toast auto-hides on a timer, so its presence in frame is a function of how
            // fast the machine ran — the definition of a non-deterministic pixel.
            page.evaluate("() => document.querySelectorAll('#toast .ds-toast').forEach(t => t.remove())")
            page.addStyleTag(Page.AddStyleTagOptions().setContent(FREEZE_CSS))
            page.evaluate("() => document.fonts.ready")
            awaitVendoredFaces()
            page.evaluate(BLANK_CLOCKS)
            page.waitForLoadState()
        }

        /**
         * `document.fonts.ready` is NOT enough since 090 §B put the vendored faces behind
         * `font-display: optional`: "optional" lets the browser decide, at first paint, that a
         * face did not arrive in time and then NEVER use it for that page load. Two runs of this
         * command then render the same screen in two different typefaces, and every text line in
         * the capture differs by a pixel or two while every border and background matches exactly
         * — which is precisely what the 093 twice-run diff measured (`datasource-lake.png`
         * differed ONLY on its two BOLD folder labels; every regular-weight leaf row was
         * byte-identical).
         *
         * So ask for the faces explicitly and wait until the browser agrees it is using them.
         * A timeout is reported, never thrown: the shot is still a truthful photograph of the
         * product, it just cannot be compared byte-for-byte with the previous run.
         */
        private fun awaitVendoredFaces() {
            page.evaluate(LOAD_FACES)
            try {
                page.waitForFunction(FACES_IN_USE, null, Page.WaitForFunctionOptions().setTimeout(FONT_WAIT_MS))
            } catch (e: com.microsoft.playwright.TimeoutError) {
                println("  NOTE fonts: the vendored faces are not in use on this page (${e.message?.take(60)})")
                println("       — the capture is honest but will not be byte-identical to another run")
            }
        }

        private fun shoot(file: String) {
            settle()
            val target = outDir.resolve(file)
            // Clip to the CURRENT viewport, not a constant: the `app` set resizes the page
            // between passes, and a hard-coded 1440x900 clip would crop the 2560x1440 pass.
            val viewport = page.viewportSize()
            page.screenshot(
                Page
                    .ScreenshotOptions()
                    .setPath(target)
                    .setClip(0.0, 0.0, viewport.width.toDouble(), viewport.height.toDouble())
                    .setAnimations(com.microsoft.playwright.options.ScreenshotAnimations.DISABLED)
                    .setScale(com.microsoft.playwright.options.ScreenshotScale.CSS),
            )
            written += file
        }
    }

    /** `dpk_` + 12 characters is the listed prefix; the minted secret is several times that. */
    private const val KEY_PREFIX_MAX = 16
    private val SECRET_SHAPED = Regex("""dpk_[A-Za-z0-9_.\-]+""")

    private const val FIT_SETTLE_MS = 400.0
    private const val LAYOUT_TIMEOUT_MS = 15_000.0
    private const val LAYOUT_POLL_MS = 50.0

    private const val WAIT_MS = 30_000.0
    private const val EXECUTION_TIMEOUT_MS = 180_000.0

    /**
     * Publishes the demo endpoint from INSIDE the page, so the browser's own session cookie and
     * the CSRF token the shell already holds are the credentials — the driver never has to learn
     * the auth scheme. Returns the HTTP status; a 409 means the row is already there, which on a
     * second run is the state we want.
     */
    private val PUBLISH_ENDPOINT =
        """
        async ([base, path, pipeline]) => {
          const token = document.body.getAttribute('hx-headers');
          const csrf = token ? JSON.parse(token)['DP-CSRF-Token'] : '';
          const res = await fetch(base + '/api/v1/endpoints', {
            method: 'POST',
            credentials: 'same-origin',
            headers: {'Content-Type': 'application/json', 'DP-CSRF-Token': csrf},
            body: JSON.stringify({
              path: path,
              pipeline: pipeline,
              timeout_seconds: 30,
              description: 'Revenue and trips by borough, for the demo window.',
            }),
          });
          return res.status;
        }
        """.trimIndent()

    /**
     * What the graph ACTUALLY painted with, read off the live Cytoscape instance, beside what
     * the tokens say it should be. Two reads of one reused probe span are included because they
     * are the evidence: the second is supposed to be the surface colour and comes back as the
     * brand (see [Shots.canvasPaintsSanely]).
     */
    private val CANVAS_TOKENS =
        """
        () => {
          const root = getComputedStyle(document.documentElement);
          const inst = window.__peInstance;
          const cy = inst && inst.graph && inst.graph.cy;
          const n = cy && cy.nodes().length ? cy.nodes()[0] : null;
          const probe = document.createElement('span');
          probe.style.position = 'absolute';
          probe.style.width = '0';
          probe.style.height = '0';
          probe.style.visibility = 'hidden';
          const reused = [];
          document.body.appendChild(probe);
          ['--brand', '--surface-raised'].forEach(function (name) {
            probe.style.color = '';
            probe.style.color = 'var(' + name + ')';
            reused.push(name + '=' + getComputedStyle(probe).color);
          });
          probe.parentNode.removeChild(probe);
          return {
            nodeBg: n ? n.style('background-color') : '',
            brand: root.getPropertyValue('--brand').trim(),
            surfaceRaised: root.getPropertyValue('--surface-raised').trim(),
            reusedProbe: reused.join(' ; '),
          };
        }
        """.trimIndent()

    private const val FONT_WAIT_MS = 5_000.0

    /** Ask for every weight the app paints with, so `optional` has them before it decides. */
    private val LOAD_FACES =
        """
        () => Promise.all([
          document.fonts.load('400 1em Inter'),
          document.fonts.load('500 1em Inter'),
          document.fonts.load('600 1em Inter'),
          document.fonts.load('700 1em Inter'),
          document.fonts.load('400 1em "JetBrains Mono"'),
        ]).then(() => document.fonts.ready)
        """.trimIndent()

    /** True once the browser reports it is painting with the vendored faces, not the fallbacks. */
    private val FACES_IN_USE =
        """
        () => document.fonts.check('400 1em Inter')
           && document.fonts.check('700 1em Inter')
           && document.fonts.check('400 1em "JetBrains Mono"')
        """.trimIndent()

    /** Walks the document so every `loading="lazy"` image enters the viewport and decodes. */
    private val SCROLL_THROUGH =
        """
        async () => {
          const step = window.innerHeight;
          for (let y = 0; y < document.body.scrollHeight; y += step) {
            window.scrollTo(0, y);
            await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
          }
          window.scrollTo(0, 0);
          await Promise.all(
            [...document.images].filter(i => !i.complete).map(i => i.decode().catch(() => {})),
          );
        }
        """.trimIndent()

    private val FREEZE_CSS =
        """
        *, *::before, *::after {
          animation-duration: 0s !important;
          animation-delay: 0s !important;
          transition-duration: 0s !important;
          transition-delay: 0s !important;
          caret-color: transparent !important;
        }
        """.trimIndent()

    /**
     * Clocks are what changes between two runs of the same command, and a screenshot pipeline
     * whose output differs every time cannot tell a real UI regression from the passage of
     * time. Absolute timestamps, relative ages, correlation ids and a result's remaining TTL
     * are replaced with a fixed placeholder of the same shape.
     *
     * **Node run times are deliberately NOT blanked.** "run time and rows on each card" is
     * content the shot list asks for; a card reading `000 ms` would be a retouched screenshot
     * of a product that never ran. They are therefore the one region that legitimately differs
     * between two runs, and this round reported that rather than hiding it.
     */
    private val BLANK_CLOCKS =
        """
        () => {
          const patterns = [
            [/\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2})?/g, '2026-01-01 00:00:00'],
            [/\b\d+\s*(seconds?|minutes?|hours?|days?)\s+ago\b/gi, 'moments ago'],
            // A result's remaining TTL counts down in real time and is pure noise.
            [/Expires in [^<\n]*/g, 'Expires in 60 minutes'],
            [/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi,
             '00000000-0000-0000-0000-000000000000'],
          ];
          const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
          const nodes = [];
          while (walker.nextNode()) nodes.push(walker.currentNode);
          for (const n of nodes) {
            let v = n.nodeValue;
            for (const [re, to] of patterns) v = v.replace(re, to);
            if (v !== n.nodeValue) n.nodeValue = v;
          }
        }
        """.trimIndent()
}
