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
 * REAL demo deployment — the one `./app.sh --start --demo-nyc` produces — and photographs
 * exactly what an engineer evaluating the product sees. It shares that suite's browser
 * discipline (chromium, headless, fixed viewport, no timing sleeps: every wait is a
 * selector or a response) and none of its container plumbing.
 *
 * ```bash
 * ./app.sh --start --demo-nyc                       # the deployment being photographed
 * ./gradlew siteShots -PshotsUrl=http://localhost:8080 \
 *                     -PshotsEmail=you@example.com -PshotsPassword=...
 * ```
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
 */
object SiteShots {
    private const val VIEWPORT_W = 1440
    private const val VIEWPORT_H = 900

    /** The pipeline photographed for the graph, inspector and failure shots. */
    private const val SHOWCASE = "weather_sensitivity_by_borough"

    /** A library template lives here; this one is PINNED by four pipelines, which is what used-by shows. */
    private const val SHARED_TEMPLATE = "sample_zones.sql"

    /** Created by the driver if absent, so the switcher shot has something to switch between. */
    private const val SECOND_WORKSPACE = "analytics-team"

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

        Playwright.create().use { pw ->
            pw.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                val context =
                    browser.newContext(
                        Browser.NewContextOptions()
                            .setViewportSize(VIEWPORT_W, VIEWPORT_H)
                            .setDeviceScaleFactor(1.0)
                            .setColorScheme(ColorScheme.LIGHT)
                            .setReducedMotion(ReducedMotion.REDUCE),
                    )
                context.use {
                    val page = context.newPage()
                    signIn(page, email, password)
                    assertLightTheme(page)
                    val taken = Shots(page).captureAll()
                    println("siteShots: wrote ${taken.size} PNGs to ${outDir.toAbsolutePath()}")
                    taken.forEach { println("  $it") }
                }
            }
        }
    }

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

    /** Every capture goes through here, so no shot can skip the determinism steps. */
    internal class Shots(private val page: Page) {
        private val written = mutableListOf<String>()

        fun captureAll(): List<String> {
            datasources()
            templateExplorer()
            templateUsedBy()
            apiKeys()
            workspaces()
            editorRunAndGraph()
            nodeInspector()
            failureDetail()
            executions()
            promotion()
            sitePageReview()
            return written
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

        private fun apiKeys() {
            page.navigate("$baseUrl/settings/api-keys")
            // The table arrives with the page but the EMPTY state is a different element —
            // wait for whichever landed. Counting rows before either exists reports zero, and
            // this method would mint a fresh key on every run (it minted five before this
            // wait was here) until the shot was a wall of duplicates.
            waitFor(".ds-table, .ds-empty")
            // A fixed name, not a timestamped one: two runs of this command must produce the
            // same pixels, and "agent-2026-09-04" in a table cell would differ tomorrow.
            val name = "analytics-agent"
            // The name lives in a SPAN inside the cell (a revoked key adds a badge beside it),
            // so an exact-text match on the `td` misses and this method mints a duplicate on
            // every run — which it did, four times, before this selector was corrected.
            if (page.locator("#keys-table-body span:text-is('$name')").count() == 0) {
                page.click("button:has-text('Generate Key')")
                page.locator("#create-modal").waitFor()
                page.fill("#create-modal input[name=name]", name)
                page.waitForResponse({ it.url().contains("/partials/api-keys") }) {
                    page.click("#create-modal button[type=submit]")
                }
            }
            // The once-only reveal must be OFF SCREEN before the shutter: a live secret in a
            // published PNG is a leak that no later edit can undo (§B).
            dismissSecretReveal()
            page.navigate("$baseUrl/settings/api-keys")
            waitFor(".ds-table")
            // The listing shows each key's PREFIX (`dpk_` + 12 chars) — an identifier, and the
            // only way to tell two keys apart when revoking one. The SECRET is far longer and
            // is shown exactly once, at creation. Refuse to photograph anything longer than a
            // prefix: a live secret in a published PNG is a leak no later edit can undo.
            val secrets = SECRET_SHAPED.findAll(page.content()).map { it.value }.filter { it.length > KEY_PREFIX_MAX }
            check(secrets.none()) {
                "a full-length API key is still on the page (${secrets.first().take(8)}…) — refusing to photograph it"
            }
            shoot("api-keys.png")
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
            check(page.locator("table").first().locator("tbody tr").count() >= 2) {
                "the workspaces shot needs two workspaces; the screen shows " +
                    page.locator("table").first().locator("tbody tr").count()
            }
            shoot("workspaces.png")
        }

        private fun editorRunAndGraph() {
            openEditor(SHOWCASE)
            execute()
            waitFor(".pe-result-panel")
            // The results dock takes the lower half; without a fit only the first cards are in
            // frame, and the shot is supposed to show the run state of EVERY node.
            fitGraph()
            shoot("graph-cards.png")
        }

        private fun nodeInspector() {
            openEditor(SHOWCASE)
            selectNodeOnCanvas(0)
            // The resolved SQL is fetched server-side; the spinner leaving is the release signal.
            page.locator("#pe-node-sql-spinner").waitFor(
                com.microsoft.playwright.Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN),
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
            waitFor(".pe-node-failure, .pe-error-exception")
            // §9.1's modal restates the user-facing message over the graph. The shot is about
            // the CARD and the detail beneath it, so dismiss the modal — it is a real element
            // of the flow, not of this state.
            page.locator("button:has-text('Dismiss')").first().click()
            page.locator("button:has-text('Dismiss')").first().waitFor(
                com.microsoft.playwright.Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN),
            )
            // The chain is the point of the shot (dag-executor §8.4) — open every collapsed level.
            page.locator(".pe-error-exception details").all().forEach { it.evaluate("e => e.open = true") }
            settle()
            shoot("failure-detail.png")
        }

        private fun executions() {
            page.navigate("$baseUrl/executions")
            waitFor(".ds-table")
            shoot("executions.png")
        }

        private fun promotion() {
            page.navigate("$baseUrl/promotion")
            waitFor("body")
            // No configured target renders the "names the two config keys" state (ui-screens
            // §4.17) — a truthful screen, but not the one the shot list asks for.
            if (page.locator("text=/promotion.target|not configured|No target/i").count() > 0) {
                println("  SKIP promotion.png — this deployment has no promotion target configured")
                return
            }
            waitFor(".ds-table, .ds-empty")
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
            val dir = java.nio.file.Paths.get(prop("shots.review", "build/site-review"))
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
                    Page.ScreenshotOptions()
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
            waitFor(".ds-table")
            // Rows navigate on click (no anchor). Match the NAME CELL exactly: several rows
            // mention another pipeline's name inside their description column.
            page.locator("tr:has(td span:text-is('$name'))").first().click()
            page.waitForURL("**/editor")
            waitFor("#cy-container")
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
                com.microsoft.playwright.Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS),
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
            page.mouse().click(box.x + box.width / 2, box.y + box.height / 2)
            page.locator(".pe-details-panel.pe-details-visible").waitFor()
            // `.pe-node-list` is clipped for sighted users but un-clips on :focus-within (031),
            // and the canvas click leaves focus inside it — so the screen-reader affordance
            // would appear in the frame. Drop focus to the body and it re-clips.
            page.evaluate("() => document.activeElement && document.activeElement.blur()")
        }

        /**
         * A folder is a `<summary>` whose label carries the FULL prefix on `title` — the rule
         * every surface showing a template name follows (template-hierarchy-design §9.4), and
         * the only attribute here that is unambiguous: the visible text is the last segment
         * only, so two `mobility` folders under different parents would both match it.
         */
        private fun expandFolder(prefix: String) {
            val summary = page.locator("summary.tpl-summary:has(.tpl-label[title='$prefix'])").first()
            summary.waitFor()
            page.waitForResponse({ it.url().contains("/partials/templates") }) { summary.click() }
        }

        private fun selectLeaf(name: String) {
            val leaf = page.locator("button.tpl-leaf:has(.tpl-label[title='$name'])").first()
            leaf.waitFor()
            page.waitForResponse({ it.url().contains("/partials/templates/versions") }) { leaf.click() }
            waitFor("#template-detail .ds-table")
        }

        private fun dismissSecretReveal() {
            page.locator(".ds-modal-close, button:has-text('Done'), button:has-text('Close')")
                .all()
                .filter { it.isVisible }
                .forEach { it.click() }
        }

        private fun waitFor(selector: String): ElementHandle? = page.waitForSelector(selector, Page.WaitForSelectorOptions().setTimeout(WAIT_MS))

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
            page.evaluate(BLANK_CLOCKS)
            page.waitForLoadState()
        }

        private fun shoot(file: String) {
            settle()
            val target = outDir.resolve(file)
            page.screenshot(
                Page.ScreenshotOptions()
                    .setPath(target)
                    .setClip(0.0, 0.0, VIEWPORT_W.toDouble(), VIEWPORT_H.toDouble())
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
