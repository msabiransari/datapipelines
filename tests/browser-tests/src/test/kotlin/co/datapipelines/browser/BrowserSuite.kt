package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Frame
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Request
import com.microsoft.playwright.Response
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.Tracing
import de.mkammerer.argon2.Argon2Factory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.function.Consumer

/**
 * The browser suite's boot harness (module-structure §5.12, TEST-GAP-2026-09.md).
 *
 * One full application per spec class: SpringBootTest on a random port against the
 * module's [SharedBrowserE2e] containers (round 060's one-container-per-JVM convention —
 * never a per-class `@Container`), LOCAL accounts enabled, no OIDC providers — one
 * command, zero manual setup. The seeded users' stored hashes are real Argon2id of the
 * known passwords with auth's exact parameters (2 / 19 456 / 1), same declared-exception
 * rationale as the integration suite's seeding.
 *
 * **Order independence is a gate, not a hope** (060's shuffle seeds run the suite in any
 * order): every test seeds its OWN user with a unique email, and no test's assertions
 * depend on another test having run. [seedLocalUser] is idempotent per email.
 *
 * Playwright runs chromium-only, headless, with tracing captured to build/reports.
 * No timing sleeps anywhere: Playwright's auto-wait and explicit response waits are the
 * only synchronization primitives.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
abstract class BrowserSuite {
    @LocalServerPort
    protected var port: Int = 0

    protected val baseUrl: String get() = "http://localhost:$port"

    protected lateinit var page: Page
    private lateinit var context: BrowserContext

    @BeforeEach
    fun freshPage() {
        context = newBrowserContext()
        page = context.newPage().patient()
    }

    @AfterEach
    fun closePage() {
        // The trace covers exactly one test; stopped here (and saved) if one was started.
        if (tracingThisTest) {
            context.tracing().stop(Tracing.StopOptions().setPath(tracePath()))
            tracingThisTest = false
        }
        context.close()
        // 188 (#188): ZERO Content-Security-Policy violations across every page every test
        // visited — the live counterpart of InlineScriptAuditTest. Chromium reports a refused
        // inline script, handler or style as a console error naming the policy; every page
        // any context of this suite opens is watched (newBrowserContext), and the test that
        // caused one fails here by name, with the page URL and the browser's own sentence.
        val violations = drainCspViolations()
        if (violations.isNotEmpty()) {
            throw AssertionError("Content-Security-Policy violations during this test:\n" + violations.joinToString("\n"))
        }
    }

    /** Subclass tests call this FIRST so a failing run leaves a diagnoseable trace. */
    protected fun startTrace() {
        context.tracing().start(Tracing.StartOptions().setScreenshots(true).setSnapshots(true))
        tracingThisTest = true
    }

    private fun tracePath() =
        Paths.get("build", "reports", "browser-traces", "trace-${System.currentTimeMillis()}.zip").also {
            it.parent.toFile().mkdirs()
        }

    private var tracingThisTest = false

    /**
     * Drives the local login form (auth.md §5A). Asserts nothing — the caller decides
     * what the outcome should be; this helper only types and clicks.
     *
     * #825: each step that can time out is [named]. The fields' step names the FIELDS, never
     * what was typed into them.
     */
    protected fun login(
        email: String,
        password: String,
    ) = login(page, email, password)

    /**
     * [login] on an explicit page (#831) — a fixture signing in a SECOND session (a promoter or
     * a member beside the admin) drives the identical form, with the identical named steps,
     * instead of a hand-rolled copy whose waits time out bare.
     */
    protected fun login(
        on: Page,
        email: String,
        password: String,
    ) {
        named(on, "the login page navigation ($baseUrl/login)") {
            on.navigate("$baseUrl/login")
        }
        named(on, "the login form's fields (#login-email, #login-password on $baseUrl/login)", ::shellInitFacts) {
            on.fill("#login-email", email)
            on.fill("#login-password", password)
        }
        named(on, "the login form's submit button ('form button[type=submit]' on $baseUrl/login)") {
            on.click("form button[type=submit]")
        }
    }

    /**
     * #831: the redirect a successful [login] ends in, as the derived fixtures wait for it — [named],
     * so a login that never lands reads as the landing step and the page it stayed on (the login
     * form, typically), not as a bare timeout. The test classes' own copies of this wait are a
     * separate sweep.
     */
    protected fun landOnDashboard(on: Page = page) {
        named(on, "the post-login landing (**/dashboard)") { on.waitForURL("**/dashboard") }
    }

    /**
     * Runs [block]; a timeout becomes an AssertionError naming [what] and where the page was —
     * Playwright's bare `TimeoutError` named neither the element nor the document, and four of
     * these reds spent a gate each on setup waits nobody could attribute (#355).
     *
     * #825: [facts], when given, appends what the page itself held when the wait gave up (a
     * render or init wait passes [shellInitFacts]). It is read defensively, so a probe that
     * throws can never replace the named red. It sits BEFORE [block] on purpose: callers pass
     * the block as a trailing lambda, which Kotlin binds to the LAST function-typed parameter.
     *
     * #831: the body is [namedWait], which the suite's support objects call directly.
     */
    protected fun named(
        on: Page,
        what: String,
        facts: ((Page) -> String)? = null,
        block: () -> Unit,
    ) = namedWait(on, what, facts, block)

    /**
     * #554: shows [family]'s panel of the resource browser on [on] and waits for its root level.
     * The browser shows the family of the CURRENT page, so off a page of that family this follows
     * the menu's link (a boosted swap: the same document) or, with the menu off screen (a phone),
     * navigates to the catalog; on a phone it then opens the browser drawer.
     *
     * #825: the navigation, the drawer click and the root-level wait are each [named]; the
     * root-level red also prints the tree as the wait left it ([treeFacts]).
     */
    protected fun openBrowserTree(
        family: String,
        on: Page = page,
    ) {
        if (!(on.evaluate("() => location.pathname") as String).startsWith("/$family")) {
            val link = on.locator(".app-nav-link[data-nav-section='/$family']")
            val viaLink = link.isVisible
            val via = if (viaLink) "the menu's /$family section link" else "$baseUrl/$family"
            named(on, "the $family page navigation ($via, waiting for **/$family)") {
                if (viaLink) link.click() else on.navigate("$baseUrl/$family")
                on.waitForURL("**/$family")
            }
        }
        val closedDrawer =
            "() => matchMedia('(max-width: 760.98px)').matches && !document.documentElement.classList.contains('browser-open')"
        if (on.evaluate(closedDrawer) == true) {
            named(on, "the phone browser drawer's open button (#browser-open, family $family)") { on.click("#browser-open") }
        }
        named(on, "the $family browser tree's root level (DatapipelinesSidebarTrees '$family', parent null)", { treeFacts(it, family) }) {
            on.waitForFunction("family => window.DatapipelinesSidebarTrees.get(family)?.state.levels.get(null)?.complete", family)
        }
    }

    /**
     * #825: [family]'s browser tree as its root-level wait left it — whether it is mounted (and
     * which families are), its level count, and the root level's status, completion, row count
     * and the widget's own error sentence — then the document's facts. No DOM and no row content;
     * read defensively like [shellInitFacts], because a page mid-navigation refuses evaluate.
     */
    private fun treeFacts(
        on: Page,
        family: String,
    ): String {
        val tree =
            runCatching {
                on.evaluate(
                    """
                    family => {
                      const trees = window.DatapipelinesSidebarTrees;
                      if (!trees) return 'tree=absent (no window.DatapipelinesSidebarTrees)';
                      const tree = trees.get(family);
                      if (!tree) return 'tree=absent (mounted: ' + (Array.from(trees.keys()).join(',') || 'none') + ')';
                      const levels = tree.state.levels;
                      const root = levels.get(null);
                      if (!root) return 'tree=mounted levels=' + levels.size + ' rootLevel=absent';
                      return 'tree=mounted levels=' + levels.size + ' rootLevel=' + root.status + ' complete=' + root.complete +
                        ' rows=' + root.nodes.size + (root.error ? ' error=' + root.error.message : '');
                    }
                    """.trimIndent(),
                    family,
                ) as String
            }.getOrElse { "the tree probe itself failed: $it" }
        return "$tree, document: ${shellInitFacts(on)}"
    }

    /**
     * Drives the SELF-SERVE workspace creation flow (the default provisioning mode,
     * configuration.md §3.17): a first-login user has NO workspace until they create one
     * at /workspaces. Plain form POST + redirect — no htmx to wait out.
     */
    protected fun createWorkspace(name: String) = createWorkspaceOn(page, name)

    /**
     * [createWorkspace] on an explicit page — a test driving a SECOND session (a member beside
     * an admin) needs the identical flow, and a hand-rolled copy is how one of them quietly
     * stops waiting for the row and races the next navigation.
     *
     * [renderTimeoutMs] bounds the create form's first render (#825); it exists for
     * BrowserSuiteSetupWaitsBrowserTest's planted render failure alone, which would otherwise
     * wait the whole first-render bound. Every other caller keeps the default.
     */
    protected fun createWorkspaceOn(
        page: Page,
        name: String,
        renderTimeoutMs: Double = FIRST_RENDER_TIMEOUT_MS,
    ) {
        submitWorkspaceCreate(page, name, renderTimeoutMs)
        enterWorkspace(page, name)
    }

    /**
     * [createWorkspaceOn] WITHOUT entering the new workspace — the raw product behaviour, for
     * the one suite whose subject is the switcher itself. [renderTimeoutMs] as there.
     */
    protected fun createWorkspaceWithoutEntering(
        name: String,
        renderTimeoutMs: Double = FIRST_RENDER_TIMEOUT_MS,
    ) = submitWorkspaceCreate(page, name, renderTimeoutMs)

    /**
     * The create flow both entry points share, up to the created row (#825 lifted it, so the two
     * cannot drift): each step that can time out is [named].
     */
    private fun submitWorkspaceCreate(
        page: Page,
        name: String,
        renderTimeoutMs: Double,
    ) {
        named(page, "the workspaces page navigation ($baseUrl/workspaces)") {
            page.navigate("$baseUrl/workspaces")
            page.waitForURL("**/workspaces")
        }
        // The form arrives with the page's first render, and its first appearance is the one
        // wait the whole flow hangs on: under a loaded gate it has outrun the action default
        // three gates running (#327, and #283/#303 before it). Wait for THAT event with the
        // loaded-box bound, then fill — the input is present, so the fill keeps the default.
        val render = "the workspace create form's first render ($WORKSPACE_NAME_INPUT, bound ${renderTimeoutMs.toLong()}ms)"
        named(page, render, ::shellInitFacts) {
            page.locator(WORKSPACE_NAME_INPUT).waitFor(Locator.WaitForOptions().setTimeout(renderTimeoutMs))
        }
        named(page, "the workspace create form's name field ($WORKSPACE_NAME_INPUT)") {
            page.fill(WORKSPACE_NAME_INPUT, name)
        }
        // Wait for the form-submit navigation to settle, THEN the row — a selector wait
        // started before the submit can bind to the outgoing document. `?` is a glob
        // metacharacter, so the query is matched with plain wildcards.
        named(page, "the workspace create form's submit button ($WORKSPACE_CREATE_SUBMIT)") {
            page.click(WORKSPACE_CREATE_SUBMIT)
        }
        // The name appears in THREE places (the table cell, the switcher's hidden
        // <option>, the members heading) — a bare text= wait resolves to the first,
        // the hidden option, and waits for visibility forever. Wait for the visible
        // table cell.
        named(page, "the created workspace's row (the visible table cell naming '$name')") {
            page.locator("td", Page.LocatorOptions().setHasText(name)).first().waitFor()
        }
    }

    /**
     * Switches the session INTO [name] through the product's own switcher.
     *
     * Creating a workspace does not enter it: since D-R11 the creator is a super admin acting
     * instance-wide, not a member who was just provisioned, so the session's active workspace
     * is still whatever it was. Nearly every fixture means "and work in it", and the switch is
     * also the only thing that re-stamps the session cookie's `active_workspace` claim.
     *
     * #761: a red out of here names the ONE [SwitchPhase] that did not finish and carries only
     * the facts that phase can have, so a dead shell never reads as a refused switch. [initTimeoutMs]
     * bounds the shell-init wait; it exists for WorkspaceSwitchOutcomeBrowserTest's planted init
     * failure alone, which would otherwise wait the whole first-render bound. Every other caller
     * keeps the default.
     */
    protected fun enterWorkspace(
        page: Page,
        name: String,
        initTimeoutMs: Double = FIRST_RENDER_TIMEOUT_MS,
    ) {
        val outcome = WorkspaceSwitchOutcome("$baseUrl/workspace/switch", initTimeoutMs)
        // Watched from BEFORE the reload: the render and init phases name the loads that failed.
        val loadFailed = Consumer<Request> { outcome.recordFailedLoad(it) }
        val requested = Consumer<Request> { if (outcome.matches(it)) outcome.request = it }
        val responded = Consumer<Response> { if (outcome.matches(it.request())) outcome.response = it }
        val failed = Consumer<Request> { if (outcome.matches(it)) outcome.failure = it.failure() }
        val navigated = Consumer<Frame> { if (it == page.mainFrame()) outcome.navigationUrl = it.url() }
        page.onRequestFailed(loadFailed)
        try {
            // A full reload first: the create submit is hx-boosted, so only the main content was
            // swapped and the rail still holds the switcher rendered BEFORE this workspace existed.
            // The switcher's first render after the reload is a first-render wait too (#327): the
            // loaded-box bound, then the select acts on a present element at the default patience.
            page.reload()
            page.locator("#workspace-switcher").waitFor(Locator.WaitForOptions().setTimeout(FIRST_RENDER_TIMEOUT_MS))
            // The switch's own transport is watched from here on, as before #761: the reload's
            // navigation must never stand in for the switch's.
            page.onRequest(requested)
            page.onResponse(responded)
            page.onRequestFailed(failed)
            page.onFrameNavigated(navigated)
            outcome.phase = SwitchPhase.SHELL_INIT
            // #629: the switch is submitted by shell.js's delegated change listener; select only
            // once its init ran, so a change can never fire into a page with no listener.
            page.waitForFunction(
                "() => window.__dpShellInit === true",
                null,
                Page.WaitForFunctionOptions().setTimeout(initTimeoutMs),
            )
            outcome.phase = SwitchPhase.SELECT
            // `force`: the select is an invisible overlay on the switcher card (the label is the
            // hit target), so Playwright's visibility precondition never clears on it.
            page.selectOption("#workspace-switcher", arrayOf(name), Page.SelectOptionOptions().setForce(true))
            outcome.phase = SwitchPhase.SWITCH_REQUEST
            awaitSwitchRequest(page, outcome)
            outcome.phase = SwitchPhase.SWITCH_RESULT
            // Observe the POST itself: an abort has no response and must fail immediately;
            // an absent request must be distinguishable from a response redirecting elsewhere.
            page.waitForCondition { outcome.failure != null || (outcome.response != null && outcome.navigationUrl != null) }
            val location = outcome.response?.headerValue("location")
            if (outcome.failure != null || location !in setOf("/dashboard", "$baseUrl/dashboard")) {
                outcome.fail(page, name)
            }
            outcome.phase = SwitchPhase.DASHBOARD_LANDING
            page.waitForURL("$baseUrl/dashboard")
            outcome.phase = SwitchPhase.SELECTED_VALUE
            val selected = page.locator("#workspace-switcher").inputValue()
            if (selected != name) {
                outcome.selected = selected
                outcome.fail(page, name)
            }
        } catch (e: PlaywrightException) {
            outcome.fail(page, name, e)
        } finally {
            page.offRequestFailed(loadFailed)
            page.offRequest(requested)
            page.offResponse(responded)
            page.offRequestFailed(failed)
            page.offFrameNavigated(navigated)
        }
    }

    /**
     * #629: on CI the change once produced no POST for the whole 90 s bound (request=none, the
     * page still on /workspaces). Wait a short bound for the request; without one, print what
     * the page held and re-dispatch the change ONCE through the product's own listener, so the
     * next red names its state instead of a bare timeout. The switch is idempotent, so a slow
     * first submit followed by the re-dispatch posts the same workspace twice.
     */
    private fun awaitSwitchRequest(
        page: Page,
        outcome: WorkspaceSwitchOutcome,
    ) {
        val bound = "${SWITCH_REQUEST_TIMEOUT_MS.toLong()}ms"
        try {
            page.waitForCondition(
                { outcome.request != null || outcome.failure != null },
                Page.WaitForConditionOptions().setTimeout(SWITCH_REQUEST_TIMEOUT_MS),
            )
            outcome.requestWait = "observed within $bound"
        } catch (_: TimeoutError) {
            outcome.pageState = switcherState(page)
            println("WARN #629 no switch POST after ${SWITCH_REQUEST_TIMEOUT_MS}ms; re-dispatching change once: ${outcome.pageState}")
            outcome.requestWait = "none within $bound, re-dispatching the change once"
            page.evaluate(
                "() => document.getElementById('workspace-switcher').dispatchEvent(new Event('change', { bubbles: true }))",
            )
            outcome.requestWait = "none within $bound, the change re-dispatched once"
        }
    }

    /** The switcher's own state, read for a diagnostic: no cookie, token or body. */
    private fun switcherState(page: Page): String =
        page
            .evaluate(
                """
                () => {
                  const select = document.getElementById('workspace-switcher');
                  return JSON.stringify({
                    readyState: document.readyState,
                    shellInit: window.__dpShellInit === true,
                    value: select ? select.value : null,
                    options: select ? select.options.length : null,
                    formAction: select && select.form ? select.form.getAttribute('action') : null,
                  });
                }
                """.trimIndent(),
            ).toString()

    /**
     * The in-document facts of a shell-init wait — url, document readiness, the flag — read
     * defensively, because a page mid-navigation refuses evaluate. ONE probe for the suite:
     * [enterWorkspace]'s render and init phases (#761) and AppShellBrowserTest's own waits (#717).
     *
     * #831: the body is [documentFacts], so the suite's support objects print the same facts.
     */
    protected fun shellInitFacts(on: Page): String = documentFacts(on)

    /**
     * #761: [enterWorkspace]'s steps, in order. A red names the phase that did not finish — [label]
     * leads its message, [step] says what did not happen — and prints only what that phase can
     * have observed.
     */
    private enum class SwitchPhase(
        val label: String,
        val step: String,
    ) {
        SWITCHER_RENDER("switcher-render", "the reload and the switcher's first render did not finish"),
        SHELL_INIT("shell-init", "the window.__dpShellInit wait did not finish"),
        SELECT("select", "the forced selectOption did not finish"),
        SWITCH_REQUEST("switch-request", "the wait for the switch request and its one re-dispatch did not finish"),
        SWITCH_RESULT("switch-result", "the switch request's outcome was not a redirect to /dashboard"),
        DASHBOARD_LANDING("dashboard-landing", "the navigation to /dashboard did not finish"),
        SELECTED_VALUE("selected-value", "the switcher on /dashboard did not show the requested workspace"),
    }

    /**
     * The switch's transport metadata and the document's own state only: never cookies, header
     * values or bodies, and a failed load's URL without its query or fragment (#761).
     */
    private inner class WorkspaceSwitchOutcome(
        private val url: String,
        private val initTimeoutMs: Double,
    ) {
        var phase = SwitchPhase.SWITCHER_RENDER
        var request: Request? = null
        var response: Response? = null
        var failure: String? = null
        var navigationUrl: String? = null
        var pageState: String? = null
        var requestWait: String? = null
        var selected: String? = null
        private val failedLoads = mutableListOf<String>()
        private var failedLoadCount = 0

        fun matches(request: Request): Boolean = request.url() == url && request.method() == "POST"

        /** Every failed load since the reload is counted; the first [FAILED_LOADS_KEPT] are kept. */
        fun recordFailedLoad(request: Request) {
            failedLoadCount++
            if (failedLoads.size < FAILED_LOADS_KEPT) {
                failedLoads += "${request.resourceType()} ${originAndPath(request.url())} ${request.failure()}"
            }
        }

        fun fail(
            page: Page,
            name: String,
            cause: Throwable? = null,
        ): Nothing = throw AssertionError(message(page, name), cause)

        private fun message(
            page: Page,
            name: String,
        ): String =
            "Workspace switch to '$name' did not reach /dashboard with the requested workspace: " +
                "phase=${phase.label} (${phase.step}), " +
                (facts(page) + "finalUrl=${page.url()}").joinToString(", ")

        /** What [phase] can have observed — never a fact only a later phase would learn. */
        private fun facts(page: Page): List<String> =
            when (phase) {
                SwitchPhase.SWITCHER_RENDER -> renderPhaseFacts(page)
                SwitchPhase.SHELL_INIT -> documentPhaseFacts(page, initTimeoutMs, requestFact())
                SwitchPhase.SELECT -> listOf(requestFact(), "switcher=${switcherFacts(page)}")
                SwitchPhase.SWITCH_REQUEST -> listOf(requestFact(), requestWaitFact()) + pageStateFact()
                SwitchPhase.SWITCH_RESULT -> listOf(requestFact(), requestWaitFact()) + pageStateFact() + transportFacts()
                SwitchPhase.DASHBOARD_LANDING -> listOf(requestFact()) + transportFacts()
                SwitchPhase.SELECTED_VALUE -> listOf("selected=${selected ?: "not read"}")
            }

        /** Reload uses the supplied page's navigation timeout; only the switcher's render has our explicit bound. */
        private fun renderPhaseFacts(page: Page) =
            listOf(
                "reloadBound=page navigation timeout",
                "switcherRenderBound=${FIRST_RENDER_TIMEOUT_MS.toLong()}ms",
                "select=not run",
                documentFact(page),
                failedLoadsFact(),
            )

        /** The init phase: its bound, what the switch had done, the document, the failed loads. */
        private fun documentPhaseFacts(
            page: Page,
            bound: Double,
            switchFact: String,
        ) = listOf("bound=${bound.toLong()}ms", switchFact, documentFact(page), failedLoadsFact())

        private fun requestFact() = "request=${request?.let { "${it.method()} ${it.url()}" } ?: "none"}"

        private fun requestWaitFact() = "requestWait=${requestWait ?: "did not finish"}"

        /** Printed only when the request wait timed out and read it — never a stand-in claim. */
        private fun pageStateFact() = listOfNotNull(pageState?.let { "pageState=$it" })

        private fun transportFacts() =
            listOf(
                "status=${response?.status() ?: "none"}",
                "Location=${response?.headerValue("location") ?: "none"}",
                "failure=${failure ?: "none"}",
            )

        private fun documentFact(page: Page) = "document=${shellInitFacts(page)}"

        private fun switcherFacts(page: Page) = runCatching { switcherState(page) }.getOrElse { "the switcher probe itself failed: $it" }

        private fun failedLoadsFact(): String {
            if (failedLoadCount == 0) return "failedLoads=none since the reload"
            val shown = if (failedLoadCount > failedLoads.size) " (the first ${failedLoads.size} shown)" else ""
            return "failedLoads=$failedLoadCount since the reload$shown [${failedLoads.joinToString("; ")}]"
        }

        /** Origin and path only: a query or a fragment can carry a capability. */
        private fun originAndPath(raw: String): String =
            runCatching {
                val uri = java.net.URI(raw)
                val port = if (uri.port == -1) "" else ":${uri.port}"
                if (uri.isOpaque || uri.host == null) "${uri.scheme}:" else "${uri.scheme}://${uri.host}$port${uri.rawPath.orEmpty()}"
            }.getOrElse { "(an unparseable URL)" }
    }

    /** A logged-out session — a fresh context+page pair the test must [Session.close]. */
    protected class Session(
        val page: Page,
        private val ctx: BrowserContext,
    ) {
        fun close() = ctx.close()
    }

    protected fun newSession(): Session {
        val ctx = newBrowserContext()
        return Session(ctx.newPage().patient(), ctx)
    }

    /**
     * Playwright's per-action default is 30 s. On a 2-vCPU CI runner that is ALSO running the
     * unit suite in parallel, the screenshot-set tests (`the handback screenshots`, `the seven
     * widths…`, `the dialog screenshot set`) — dozens of navigations each — exceeded it on a
     * single page load (2026-09-11, every GitHub run) while every assertion they make held.
     * The same assertions get 90 s of patience per action under `CI=true` (GitHub sets it) OR
     * under `dp.browser.ciPatience` (scripts/pregate.sh passes `-Pdp.browser.ciPatience=true`
     * for the browser classes it selects, #438; scripts/gate.sh no longer runs this suite,
     * owner ruling 2026-10-09): a loaded local pre-gate and a 2-vCPU runner are the
     * same condition, and the setting is dedicated rather than an exported `CI=true` because a
     * third-party reader of `CI` would flip too. A plain local run keeps the 30 s, because on a
     * laptop a 30 s wait IS the defect.
     *
     * One class of action is exempt from that principle and always gets
     * [FIRST_RENDER_TIMEOUT_MS]: the FIRST appearance of a page's chrome after a navigation or
     * reload — the create form's input after `navigate("/workspaces")`, the switcher after the
     * create's reload. That wait competes with everything else the box is doing (a gate's
     * clean build, four lanes compiling), so its overrun is the BOX's load, not a product
     * defect — and a shots test runs dozens of navigations, so one unlucky render reds a
     * whole gate while every assertion it would have made held (#327's red was exactly this,
     * on a local gate's clean build under load ~11; the incremental run passed minutes
     * later). A genuinely broken render still fails the same assertion, 90 s later, once.
     */
    private fun Page.patient(): Page =
        apply {
            actionTimeoutMillis(System.getenv("CI"), System.getProperty(CI_PATIENCE_PROPERTY))
                ?.let { setDefaultTimeout(it) }
        }

    /** A per-test local user: its one-time password, its chosen password, its unique email. */
    protected class LocalUser(
        val email: String,
        val oneTimePassword: String,
        val chosenPassword: String,
    )

    /**
     * Seeds ONE local user with a unique email — the suite's isolation unit. No two tests
     * share a user, so execution order cannot matter. [isAdmin] defaults TRUE because almost
     * every golden path needs an admin; a suite asserting a D8 refusal passes `false` and gets
     * a plain workspace MEMBER. Called from inside a test (Flyway
     * has migrated by then — the TracerBullet lesson; a static @BeforeAll races the
     * context boot).
     */
    protected fun seedLocalUser(
        email: String,
        password: String,
        mustChange: Boolean,
        isAdmin: Boolean = true,
        // 114 / D22: the ONE role the seeded `default` membership carries (V29). Defaulted to
        // the workspace ADMIN every golden path needs; a suite whose subject is what a VIEWER,
        // an AUTHOR or a PROMOTER can see passes its own. The value set is the database's
        // (`chk_workspace_member_role`), so a typo here is a refused insert, not a fixture.
        role: String = "workspace_admin",
    ): LocalUser {
        val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
        val hash = argon2.hash(2, 19_456, 1, password.toCharArray())
        DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (email, display_name, provider, provider_subject,
                                           is_active, is_admin, password_hash, must_change_password)
                        VALUES ('$email', 'Browser User', 'local', '$email',
                                TRUE, $isAdmin, '$hash', $mustChange)
                        ON CONFLICT (email) DO NOTHING
                        """.trimIndent(),
                    )
                    // D-R11: a user with NO membership joins `demo` as a VIEWER on first
                    // login, and `demo` then becomes their first membership — the workspace
                    // every suite would land in, holding the shipped example content. That is
                    // the product's behaviour for a brand-new person and exactly wrong for
                    // these fixtures, which mean "somebody who already works here". So the
                    // membership is seeded, as a real deployment's super admin would have
                    // granted it: workspace ADMIN of `default` unless the suite says otherwise.
                    statement.execute(
                        """
                        INSERT INTO workspace_members (workspace_id, user_id, role)
                        SELECT 'defa0000-0000-0000-0000-000000000001', id, '$role'
                          FROM users WHERE email = '$email'
                        ON CONFLICT (workspace_id, user_id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
        return LocalUser(email, password, generatedPassword("chosen"))
    }

    /**
     * Puts the signed-in user's theme into [mode] (`light` / `dark`) through the top bar's own
     * toggle, and waits for the PATCH's out-of-band `#theme-link` swap — the href changing is
     * the completion signal. A no-op when the page already shows that mode. The deployment
     * default is `dark` (owner, 2026-09-11), so a test that photographs "light then dark" starts
     * by asking for light; it must not assume where the toggle begins.
     *
     * #825: the href read and the three waits are each [named] with the document's facts; the
     * read is a wait too (the locator waits for `#theme-link` to exist).
     */
    protected fun ensureTheme(mode: String) {
        val wanted = "/themes/$mode.css"
        var current = ""
        named(page, "the #theme-link href read (wanted $wanted)", ::shellInitFacts) {
            current = page.locator("#theme-link").first().getAttribute("href") ?: ""
        }
        if (current.contains(wanted)) return
        val swap = "wanted $wanted, current href $current"
        named(page, "the theme PATCH response ($swap)", ::shellInitFacts) {
            page.waitForResponse("**/partials/profile/theme") { page.locator("#mode-toggle").click() }
        }
        named(page, "the #theme-link swap ($swap)", ::shellInitFacts) {
            page.waitForFunction("() => document.getElementById('theme-link').getAttribute('href').includes('$wanted')")
        }
        named(page, "the html[data-theme='$mode'] update ($swap)", ::shellInitFacts) {
            page.locator("html[data-theme='$mode']").waitFor()
        }
    }

    /** Generates per-run secrets — no literal secret in any fixture (the HIGH-2 rule). */
    protected fun generatedPassword(prefix: String): String =
        "$prefix-" + (1..24).map { BASE32[SECURE_RANDOM.nextInt(BASE32.length)] }.joinToString("")

    /**
     * Suite-unique email. Lowercased because [co.datapipelines.auth.LocalAuthService]
     * normalizes the submitted email (`trim().lowercase()`) — a mixed-case stored email
     * can never log in, and the suite must honor the app's contract, not fight it.
     */
    protected fun uniqueEmail(slug: String): String = "$slug@browser.datapipelines.test".lowercase()

    companion object {
        const val CI_ACTION_TIMEOUT_MS = 90_000.0

        /**
         * The setting that gives a plain local run GitHub's per-action patience (#438). ONE
         * name for both the Gradle project property the gates pass (`-Pdp.browser.ciPatience=true`)
         * and the system property the browser module's `test` task forwards from it.
         */
        internal const val CI_PATIENCE_PROPERTY = "dp.browser.ciPatience"

        /**
         * The per-action default for a page: [CI_ACTION_TIMEOUT_MS] under `CI=true` (GitHub
         * sets it) or under the gates' [CI_PATIENCE_PROPERTY], `null` — Playwright's 30 s
         * stays — when both are absent or `"false"`. CI wins over `"false"` so a stray flag
         * never makes GitHub's runner impatient. Any OTHER value of the setting is a typo and
         * REFUSES here (loud at the entry point) rather than silently running at 30 s.
         */
        internal fun actionTimeoutMillis(
            ciEnv: String?,
            gatePatience: String?,
        ): Double? {
            // check() throws IllegalStateException (detekt's UseCheckOrError), which is the
            // contract this entry point wants: a mistyped setting refuses loudly.
            check(gatePatience == null || gatePatience == "true" || gatePatience == "false") {
                "$CI_PATIENCE_PROPERTY must be 'true' or 'false', not '$gatePatience'"
            }
            return if (ciEnv == "true" || gatePatience == "true") CI_ACTION_TIMEOUT_MS else null
        }

        /**
         * The bound for FIRST-RENDER waits (see [patient]'s KDoc for which waits and why):
         * the loaded-box bound that survived #283/#303/#327 — one number on CI and off it,
         * because a loaded local gate and a 2-vCPU runner are the same condition for these.
         */
        const val FIRST_RENDER_TIMEOUT_MS = 90_000.0

        /** #629: how long a selected workspace may go without its switch POST before one re-dispatch. */
        private const val SWITCH_REQUEST_TIMEOUT_MS = 15_000.0

        private const val SECRET_BYTES = 32
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private val SECURE_RANDOM = SecureRandom()

        /** #761: how many failed loads since the reload a render/init red prints (all are counted). */
        private const val FAILED_LOADS_KEPT = 10

        /** #825: the create form's name input and submit button, ONE spelling for the shared create flow. */
        private const val WORKSPACE_NAME_INPUT = "form[action*='/workspaces/create'] input[name=name]"
        private const val WORKSPACE_CREATE_SUBMIT = "form[action*='/workspaces/create'] button[type=submit]"

        private fun randomSecret(): String =
            Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { SECURE_RANDOM.nextBytes(it) })

        @JvmStatic
        private var playwright: Playwright? = null

        @JvmStatic
        private var browserHolder: Browser? = null

        @JvmStatic
        protected fun newBrowserContext(): BrowserContext = browser.newContext().watchingCsp()

        /** A context with options — a public-site arm's reduced motion, dark scheme or no-JS posture (145). */
        @JvmStatic
        protected fun newBrowserContext(options: Browser.NewContextOptions): BrowserContext = browser.newContext(options).watchingCsp()

        /** The CSP violations seen since the last drain — see [closePage]. Static: one per test JVM. */
        private val cspViolations = java.util.concurrent.CopyOnWriteArrayList<String>()

        /**
         * SHA-256 of the EMPTY string, as CSP prints it. Playwright's own screenshot path
         * appends an empty `<style>` element to the page and fills it through `insertRule`
         * (CSSOM, which no policy governs) to hide the caret, and Chromium reports the empty
         * element as a refused inline style with this hash. #456: Plotly produces the SAME
         * hash, so only harness provenance may be set aside; app scripts must still fail.
         *
         * The same screenshot path (Playwright 1.62) also writes `caret-color` inline on every
         * input and textarea from its isolated world and clears it afterwards, leaving `style=""`.
         * htmx's default settle step copied that attribute across the next swap with setAttribute,
         * so the refusal carried htmx.min.js as its source; the layouts' htmx-config no longer
         * settles "style" (#456 merge follow-up, guarded by HtmxConfigAuditTest).
         */
        private const val EMPTY_INLINE_STYLE_HASH = "'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='"

        private val harnessCspMessages = java.util.concurrent.CopyOnWriteArrayList<String>()

        /** Every page this context opens reports a CSP refusal into [cspViolations]. */
        private fun BrowserContext.watchingCsp(): BrowserContext =
            apply {
                val consolePath = Paths.get("build", "reports", "browser-console", "console-${System.nanoTime()}.log")
                Files.createDirectories(consolePath.parent)
                onPage { page ->
                    page.onConsoleMessage { message ->
                        val text = message.text()
                        val location = message.location()
                        // Java Playwright formats location as url:line:column (the URL may contain colons).
                        val sourceUrl = location.substringBeforeLast(':').substringBeforeLast(':')
                        val appOrigin = java.net.URI(page.url()).let { "${it.scheme}://${it.rawAuthority}/" }
                        // Preview URLs carry capabilities: preserve the console, never the page's query.
                        val safeLocation = sourceUrl.substringBefore('?') + location.removePrefix(sourceUrl)
                        val event = "${page.url().substringBefore('?')} — ${message.type()} location=$safeLocation — $text"
                        Files.writeString(
                            consolePath,
                            "$event\n",
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND,
                        )
                        if (!text.contains("Content Security Policy")) return@onConsoleMessage
                        // #456: screenshot insertion has no app URL. Never exempt vendor/js emissions,
                        // even with the empty hash; an app-origin document is also an application source.
                        val harnessSource =
                            !sourceUrl.startsWith(appOrigin) && !sourceUrl.contains("/vendor/") && !sourceUrl.contains("/js/")
                        val emptyInlineStyle = text.startsWith("Applying inline style") && text.contains(EMPTY_INLINE_STYLE_HASH)
                        if (emptyInlineStyle && harnessSource) {
                            harnessCspMessages += event
                            println("CSP harness-only: $event")
                            return@onConsoleMessage
                        }
                        cspViolations += event
                    }
                }
            }

        /** Recorded exemptions, including their source locations, for browser evidence. */
        @JvmStatic
        protected fun drainHarnessCspMessages(): List<String> = harnessCspMessages.toList().also { harnessCspMessages.clear() }

        /** Returns what was collected and clears it — the per-test read in [closePage]. */
        @JvmStatic
        protected fun drainCspViolations(): List<String> =
            cspViolations.toList().also {
                cspViolations.clear()
                println("CSP harness-only set aside: ${drainHarnessCspMessages().size}")
            }

        private val browser: Browser
            get() {
                if (browserHolder == null) {
                    playwright = Playwright.create()
                    browserHolder =
                        playwright!!.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
                }
                return browserHolder!!
            }

        @JvmStatic
        @AfterAll
        fun closeBrowser() {
            browserHolder?.close()
            browserHolder = null
            playwright?.close()
            playwright = null
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedBrowserE2e.jdbcUrl }
            registry.add("spring.datasource.username") { SharedBrowserE2e.username }
            registry.add("spring.datasource.password") { SharedBrowserE2e.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedBrowserE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedBrowserE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }
            // LOCAL accounts only — no OIDC providers registered, so the login page is
            // exactly the local form and the suite never touches an identity provider.
            registry.add("datapipelines.auth.local.enabled") { true }
            registry.add("datapipelines.auth.rate-limit.login-per-minute") { 100 }
            // The suites were written against the member gate ON (the pre-186 default, now
            // false): the ws_admin surfaces they assert — the Register verb among them — are
            // gated by it.
            registry.add("datapipelines.workspaces.member-datasources-enabled") { "true" }
            // 186: browser suites register file-backed datasources (SQLite fixture files) from
            // temp directories — declare the temp family as the file root for this context.
            registry.add("datapipelines.datasources.file-roots") { System.getProperty("java.io.tmpdir") }
        }
    }
}

/**
 * The body of [BrowserSuite.named] (#355, #825), lifted to the file's top level by #831 so the
 * suite's support OBJECTS — which cannot call a protected member — raise the identical red: one
 * implementation, no copy. Runs [block]; a Playwright timeout becomes an AssertionError reading
 * `<what> timed out — url=<where the page was>`, plus ` — <facts>` when [facts] is given (read
 * defensively, so a probe that throws never replaces the named red); anything else is rethrown.
 * [facts] sits BEFORE [block]: callers pass the block as a trailing lambda.
 */
internal fun namedWait(
    on: Page,
    what: String,
    facts: ((Page) -> String)? = null,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: PlaywrightException) {
        if (e.message?.contains("Timeout") == true) {
            val held = facts?.let { probe -> " — " + runCatching { probe(on) }.getOrElse { "the facts probe itself failed: $it" } }
            throw AssertionError("$what timed out — url=${on.url()}${held.orEmpty()}", e)
        }
        throw e
    }
}

/**
 * The body of [BrowserSuite.shellInitFacts] (#761), lifted by #831 beside [namedWait] so a support
 * object's render or init wait carries the same document facts — url, document readiness, the
 * shell's init flag — read defensively, because a page mid-navigation refuses evaluate.
 */
internal fun documentFacts(on: Page): String =
    runCatching {
        on.evaluate(
            "() => 'url=' + location.href + " +
                "' readyState=' + document.readyState + " +
                "' __dpShellInit=' + String(window.__dpShellInit)",
        ) as String
    }.getOrElse { "the document probe itself failed: $it" }
