package co.datapipelines.web.ui

import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * 076 §B — the boosted-navigation shell, pinned at the RENDER.
 *
 * `layouts/default.html` carries the whole contract: `hx-boost="true"` on the
 * nav and on `<main id="app-main">` (shell.js retargets boosted swaps at the
 * main region — hx-target/hx-select are deliberately NOT on those elements,
 * because htmx would inherit them into every partial swap), the five
 * full-navigation routes marked `hx-boost="false"` (/login, /logout, the OIDC
 * redirects, the forced-change gate, file downloads), the #app-progress bar,
 * and the hidden #toast-flash bin INSIDE the swapped region so server-rendered
 * redirect flashes survive the swap.
 *
 * Every assertion renders the real templates through the real layout — a
 * dropped attribute is invisible to controller tests and to the JS tests.
 */
class ShellRenderTest {
    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    @Test
    fun `every app page renders the boost shell on nav and main`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })

        // 079 §A moved the nav into the rail; the BOOST CONTRACT is unchanged, which is
        // what this pins — same element class, same hx-boost, same swap target, same
        // progress bar and flash bin.
        html shouldContain "<nav class=\"app-nav\" hx-boost=\"true\""
        // #358: the history cache is scoped to the main region — hx-history-elt rides
        // the boost contract (the snapshot carries the workspace region, never the body).
        html shouldContain "<main id=\"app-main\" class=\"app-container app-main\" hx-boost=\"true\" hx-history-elt>"
        html shouldContain "id=\"app-progress\""
        // 079 §F: the bin gained [data-toast-flash] — toast.js now drains EVERY marked bin,
        // so a screen with its own refusal vocabulary (promotion) can render its own.
        html shouldContain "id=\"toast-flash\" data-toast-flash hidden"
        html shouldContain "data-nav-section=\"/pipelines\""
        html shouldContain "/js/shell.js"
    }

    /**
     * 114 §C.4 — the role badge beside the switcher's workspace name.
     *
     * It sits with the workspace name because the role IS a property of the (workspace,
     * person) pair: the same person is a viewer in one and an admin in another, so a badge
     * anywhere else on the shell would be saying something that is not true of the page it is
     * on. `data-role` is the stable hook; the word is [RoleModel]'s label, derived and stored
     * nowhere (D-R2 — no single label names an additive row).
     */
    @Test
    fun `the switcher carries the active workspace's role badge`() {
        val admin =
            engine.process("pipelines/list", webContext().apply { fillList() })
        admin shouldContain "class=\"ds-badge app-ws-role\" data-role=\"super admin\""

        val viewer =
            engine.process(
                "pipelines/list",
                webContext()
                    .withRoles(
                        canAuthor = false,
                        canPromote = false,
                        canAdminWorkspace = false,
                        isSuperAdmin = false,
                        roleLabel = "viewer",
                    ).apply { fillList() },
            )
        viewer shouldContain "data-role=\"viewer\""

        // The badge lives INSIDE the label span the collapsed rail hides, so a 60px rail
        // shows the avatar alone rather than a word with no name beside it (§3.6).
        val label = viewer.substringAfter("app-ws-text app-rail-label")
        label.substringBefore("</span>") shouldContain "app-ws-role"
    }

    @Test
    fun `the rail renders the three groups, the collapse control and the counts`() {
        val html =
            engine.process(
                "pipelines/list",
                webContext().apply {
                    fillList()
                    setVariable("navCounts", NavCounts.Counts(9, 27))
                },
            )

        html shouldContain "class=\"app-shell\""
        html shouldContain "app-nav-section app-rail-label\">Build<"
        html shouldContain "app-nav-section app-rail-label\">Operate<"
        html shouldContain "app-nav-section app-rail-label\">Organisation<"
        html shouldContain "id=\"rail-collapse\""
        // Counts render as badges only when the cache produced a number (§A).
        html shouldContain "app-nav-badge app-rail-label\">9<"
        html shouldContain "app-nav-badge app-rail-label\">27<"
    }

    @Test
    fun `a workspace with unreadable counts renders no badges rather than a zero`() {
        val html =
            engine.process(
                "pipelines/list",
                webContext().apply {
                    fillList()
                    setVariable("navCounts", NavCounts.Counts(null, null))
                },
            )

        html shouldContain "data-nav-label=\"Pipelines\""
        html shouldNotContain "app-nav-badge"
    }

    @Test
    fun `the top bar renders the search palette, the mode toggle and the avatar menu`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })

        html shouldContain "class=\"app-topbar\""
        html shouldContain "app-crumb-page"
        // 161 (#155): the search is a REAL control — an <input role="combobox"> wired to
        // /partials/search, with the palette and its listbox it names via aria-controls.
        // The 079 §B placeholder's shape is banned in BOTH copies: no aria-hidden search
        // div may come back.
        html shouldNotContain "class=\"app-search\" aria-hidden"
        html shouldContain
            "<input id=\"app-search-input\" type=\"search\" name=\"q\" class=\"app-search-field\" role=\"combobox\""
        html shouldContain "aria-controls=\"app-search-palette\""
        html shouldContain "hx-get=\"/partials/search\""
        html shouldContain "hx-target=\"#app-search-results\""
        html shouldContain "id=\"app-search-palette\""
        html shouldContain "role=\"listbox\" aria-label=\"Search results\""
        html shouldContain "id=\"mode-toggle\""
        html shouldContain "hx-patch=\"/partials/profile/theme\""
        html shouldContain "id=\"app-avatar\""
        html shouldContain "aria-haspopup=\"menu\""
        html shouldContain "aria-expanded=\"false\""
        html shouldContain "id=\"app-user-menu\""
        html shouldContain "role=\"menu\""
    }

    /**
     * 161 — the drawer's copy is the SAME control, never a decoration: a real input,
     * its own ids (the combobox's aria-controls must name ITS palette), and no
     * aria-hidden copy left anywhere in the layout. Falsified by reverting either
     * block to the 079 §B `<div aria-hidden>` — the positive assertions go red.
     */
    @Test
    fun `the drawer carries its own real search control and no inert copy survives`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })

        html shouldContain
            "<input id=\"app-search-drawer-input\" type=\"search\" name=\"q\" class=\"app-search-field\" role=\"combobox\""
        html shouldContain "aria-controls=\"app-search-drawer-palette\""
        html shouldContain "hx-target=\"#app-search-drawer-results\""
        html shouldContain "id=\"app-search-drawer-palette\""
        html shouldNotContain "app-search-text"
    }

    /**
     * #197 — the avatar renders through the app's OWN proxy: the `<img>` points at
     * `/avatar`, and the stored provider URL never reaches the page HTML (that URL is what
     * the old `img-src https:` grant existed to load — the proxy is why the grant is gone).
     */
    @Test
    fun `the avatar renders the OIDC picture through the avatar proxy`() {
        val withPicture =
            engine.process(
                "pipelines/list",
                webContext().apply {
                    fillList()
                    setVariable(
                        "currentUser",
                        mapOf(
                            "displayName" to "Muhammad Sabir",
                            "email" to "m@example.com",
                            "profilePictureUrl" to "https://pic.example/x.png",
                        ),
                    )
                },
            )
        // `users.profile_picture_url` IS stored (OidcSuccessHandler writes the `picture`
        // claim through UserRepository on every login) — so the avatar is the real image
        // when there is one, and initials otherwise. The URL itself stays server-side:
        // GET /avatar serves the signed-in principal's own picture (AvatarController).
        withPicture shouldContain "class=\"app-avatar-img\""
        withPicture shouldContain """src="/avatar""""
        withPicture shouldNotContain "https://pic.example/x.png"

        val withoutPicture =
            engine.process(
                "pipelines/list",
                webContext().apply {
                    fillList()
                    setVariable(
                        "currentUser",
                        mapOf("displayName" to "Muhammad Sabir", "email" to "m@example.com", "profilePictureUrl" to null),
                    )
                },
            )
        withoutPicture shouldContain "class=\"app-avatar-initials\""
        withoutPicture shouldContain ">MU<"
        withoutPicture shouldNotContain """/avatar""""
    }

    @Test
    fun `the appearance segment and the palette swatches write the one theme preference`() {
        val html =
            engine.process(
                "pipelines/list",
                webContext().apply {
                    fillList()
                    setVariable("themePalettes", listOf("forest", "ocean", "saas"))
                },
            )

        // Nine vendored themes, ONE users.theme_preference: three of them are modes and
        // the rest are palettes, and both controls PATCH the same endpoint (§B).
        html shouldContain "data-mode=\"light\""
        html shouldContain "data-mode=\"dark\""
        html shouldContain "data-mode=\"auto\""
        html shouldContain "data-swatch=\"forest\""
        html shouldContain "data-swatch=\"ocean\""
        html shouldContain "data-swatch=\"saas\""
        html shouldNotContain "data-swatch=\"light\""
    }

    @Test
    fun `every rail link agrees with the breadcrumb table`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })

        // The drift guard between the rail's MARKUP (here) and AppNav's TABLE (Kotlin):
        // the top bar's breadcrumb is derived from the table, the highlight from the
        // markup, and a link added to one without the other would make them disagree
        // silently. Non-vacuous by construction — the count is asserted too.
        val links = Regex("""data-nav-section="([^"]+)" *\n? *data-nav-group="([^"]*)" data-nav-label="([^"]+)"""").findAll(html).toList()
        links.size shouldBe AppNav.ITEMS.size
        links.forEach { match ->
            val (section, group, label) = match.destructured
            val item = AppNav.bySection(section)
            item.shouldNotBeNull()
            item.label shouldBe label
            (item.group ?: "") shouldBe group
        }
    }

    @Test
    fun `logout is a full navigation`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })

        html shouldContain "class=\"app-logout-form\" hx-boost=\"false\""
    }

    /**
     * 127 §C — the avatar menu's choices, pinned at the render: exactly the four `menuitem`s
     * in arrow-key order (Settings, API keys, Report a problem, Log out), and the new one
     * opens the bug form in a NEW tab (external, never boosted) with the URL the SitePages
     * constant spells. Removing the `menuitem` role from the new link turns the count red —
     * that is the guard's falsification leg.
     */
    @Test
    fun `the avatar menu carries Report a problem between API keys and Log out`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })

        val items = Regex("""role="menuitem"""").findAll(html).toList()
        items.size shouldBe 4

        val report = html.indexOf(">Report a problem</a>")
        (report > 0) shouldBe true
        (html.indexOf(">API keys</a>") < report) shouldBe true
        (report < html.indexOf(">Log out</button>")) shouldBe true
        html shouldContain "href=\"$REPORT_PROBLEM_URL\" role=\"menuitem\" target=\"_blank\" rel=\"noopener\""
        // The new tab is the contract: no hx-boost on an external target.
        Regex("""href="$REPORT_PROBLEM_URL[^"]*"[^>]*hx-boost""").containsMatchIn(html) shouldBe false
    }

    /** 127 §C — the docs index header carries the same link, from the same constant. */
    @Test
    fun `the docs index header links the bug form in a new tab`() {
        val html =
            engine.process(
                "docs/index",
                webContext().apply {
                    fillLayoutChrome()
                    setVariable("groups", emptyList<Any>())
                },
            )

        html shouldContain "href=\"$REPORT_PROBLEM_URL\" target=\"_blank\" rel=\"noopener\" class=\"ds-button ds-button-secondary\""
        html shouldContain ">Report a problem</a>"
    }

    @Test
    fun `login and the OIDC redirect are full navigations`() {
        val html = engine.process("login", webContext().apply { fillLogin() })

        // 079 §D stripped the inline `style="text-align: left;"` this used to name; the
        // ASSERTION's subject was always the hx-boost="false", so it is pinned on the form
        // itself rather than on an attribute string that a styling change can move.
        html shouldContain "action=\"/login\""
        Regex("""<form[^>]*action="/login"[^>]*hx-boost="false"""").containsMatchIn(html) shouldBe true
        html shouldContain "href=\"/oauth2/authorization/sso\""
        html shouldContain "hx-boost=\"false\""
    }

    /**
     * 090 §C: the forced-change gate moved to its own view (`settings/password-forced`,
     * decorated with `layouts/auth`), so this asserts the boost marker on the view a locked
     * user actually gets. The marker is now belt AND braces — the auth layout has no
     * `hx-boost` anywhere to inherit from — and it stays because the SAME card partial is
     * rendered inside the shell for a voluntary change, where the marker is load-bearing.
     */
    @Test
    fun `the forced-change gate is a full navigation`() {
        val forced = engine.process("settings/password-forced", webContext().apply { fillPassword() })
        val voluntary = engine.process("settings/password", webContext().apply { fillPassword() })

        forced shouldContain "id=\"password-change-form\""
        forced shouldContain "hx-boost=\"false\""
        voluntary shouldContain "hx-boost=\"false\""
    }

    @Test
    fun `the editor's result downloads are full navigations`() {
        val html = engine.process("pipelines/editor", webContext().apply { fillEditor() })

        html shouldContain "hx-boost=\"false\" x-bind:href=\"resultPanel.downloadJsonHref\""
        html shouldContain "hx-boost=\"false\" x-bind:href=\"resultPanel.downloadCsvHref\""
        html shouldContain "hx-boost=\"false\" x-bind:href=\"resultPanel.downloadArrowHref\""
    }

    private fun WebContext.fillList() {
        fillLayoutChrome()
        setVariable("scopes", setOf("READ"))
        setVariable("dialects", emptyList<String>())
        // #350: the /pipelines page is the flat catalog under its own root.
        setVariable("searching", true)
        setVariable("scope", PipelineListScope.CATALOG.wire)
        setVariable("rootId", PipelineListScope.CATALOG.rootId)
        setVariable("pipelines", emptyList<Any>())
        setVariable("drafts", emptyMap<Any, Any>())
        setVariable("q", "")
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 0)
    }

    private fun WebContext.fillLogin() {
        fillLayoutChrome()
        setVariable("localEnabled", true)
        setVariable(
            "providers",
            listOf(mapOf("registrationId" to "sso", "displayName" to "Corporate SSO")),
        )
        setVariable("error", null)
    }

    private fun WebContext.fillPassword() {
        fillLayoutChrome()
        setVariable("mustChange", true)
        setVariable("hasLocalPassword", true)
    }

    private fun WebContext.fillEditor() {
        fillLayoutChrome()
        setVariable("pipelineJson", "{\"id\":\"p1\",\"name\":\"demo\",\"nodes\":[]}")
        setVariable("workspaceJson", "{\"viewedVersion\":1,\"hasBody\":true}")
        setVariable("pipelineId", "11111111-1111-1111-1111-111111111111")
        setVariable("pipelineName", "demo")
        setVariable("hasSelectedBody", true)
        setVariable("viewedVersion", 1)
        setVariable("viewedLabel", "v1 · released · current")
        setVariable("viewedIsDraft", false)
        setVariable("viewedIsCurrent", true)
        setVariable("viewedStatusLabel", "released")
        setVariable("currentVersion", 1)
        setVariable("hasDraft", false)
        setVariable("draftVersion", null)
        setVariable("versions", emptyList<Any>())
        setVariable("activeTab", "flow")
        setVariable("canReadExecutions", true)
    }

    private fun WebContext.fillLayoutChrome() {
        setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/pipelines")
        // 127 — SiteOriginAdvice's attribute at runtime; the render tests set it by hand.
        setVariable("reportProblemUrl", REPORT_PROBLEM_URL)
    }

    /**
     * D58 (#10 L3b): the landing item is RENAMED Home — the route (/dashboard) and its
     * exact-match section are unchanged, so the pin is on the label pair, and the new
     * Dashboards item sits in Build with its lazy branch beside it (the branch is not a link:
     * the rail's link count stays the table's count — the agreement test above holds it).
     */
    @Test
    fun `the landing item is Home and the Dashboards item sits in Build with its lazy branch`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })

        html shouldContain "data-nav-section=\"/dashboard\""
        html shouldContain "data-nav-group=\"\" data-nav-label=\"Home\""
        html shouldContain "data-nav-section=\"/dashboards\""
        html shouldContain "data-nav-group=\"Build\" data-nav-label=\"Dashboards\""
        // The lazy branch: since #350 the rail's ONE navigating-tree pattern — a toggle BUTTON
        // beside the link (aria-controls the panel), a panel whose root URL is the nav scope
        // (one level per request), and the NAV instance's root placeholder, never the page's.
        html shouldContain "aria-controls=\"nav-tree-dashboards\" aria-label=\"Dashboard folders\""
        html shouldContain "data-nav-tree=\"dashboards\""
        html shouldContain "data-nav-root-url=\"/partials/dashboards/tree?scope=nav\""
        html shouldContain "id=\"dash-tree-nav\""
        html shouldNotContain "data-nav-label=\"Dashboard\""
        // The second tree engine is gone: no <details> disclosure of its own.
        html shouldNotContain "app-nav-branch-tree"
    }

    @Test
    fun `#350 - Pipelines carries the same branch - link, a separate toggle, and the lazy sidebar tree with its search`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })
        val branch = html.substringAfter("data-nav-branch=\"pipelines\"").substringBefore("data-nav-branch=\"dashboards\"")

        // The item LINK is unchanged: the catalog page, the active section, the crumb pair.
        branch shouldContain "href=\"/pipelines\" class=\"app-nav-link"
        branch shouldContain "data-nav-group=\"Build\" data-nav-label=\"Pipelines\""
        // The TOGGLE is its own control — opening the tree is not navigating.
        branch shouldContain "<button type=\"button\" class=\"app-nav-branch-toggle app-rail-label\" data-nav-tree-toggle"
        branch shouldContain "aria-expanded=\"false\" aria-controls=\"nav-tree-pipelines\""
        // The PANEL: closed on paint, the nav-scope root URL, the document's workspace for the
        // admission guard, the sidebar search addressing the sidebar's root, the bounded region.
        branch shouldContain "id=\"nav-tree-pipelines\" data-nav-tree=\"pipelines\" hidden"
        branch shouldContain "data-nav-root-url=\"/partials/pipelines?scope=nav\""
        branch shouldContain "data-nav-workspace=\"acme\""
        branch shouldContain "hx-get=\"/partials/pipelines?scope=nav\""
        branch shouldContain "hx-target=\"#pipeline-nav-root\""
        branch shouldContain "data-nav-tree-scroll"
        branch shouldContain "id=\"pipeline-nav-root\""
        // The shared engine and the branch controller load once, from the layout's footer.
        html shouldContain "<script src=\"/js/template-explorer.js\"></script>"
        html shouldContain "<script src=\"/js/nav-tree.js\"></script>"
        // rail.js reads the active workspace's pre-paint width key off <html>.
        html shouldContain "data-dp-workspace=\"acme\""
    }

    @Test
    fun `#374 - Parameter Sets carries the same branch in Build - link, a separate toggle, the lazy nav-scope tree and no search`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })
        val branch =
            html
                .substringAfter("data-nav-branch=\"parameter-sets\"")
                .substringBefore("<div class=\"app-nav-section app-rail-label\">Operate")

        branch shouldContain "href=\"/parameter-sets\" class=\"app-nav-link"
        branch shouldContain "data-nav-group=\"Build\" data-nav-label=\"Parameter Sets\""
        // The lucide sliders-horizontal glyph, from the vendored sprite (icons-from-lucide-only).
        branch shouldContain "/vendor/icons/lucide-sprite.svg#sliders-horizontal"
        branch shouldContain "<button type=\"button\" class=\"app-nav-branch-toggle app-rail-label\" data-nav-tree-toggle"
        branch shouldContain "aria-expanded=\"false\" aria-controls=\"nav-tree-parameter-sets\" aria-label=\"Parameter set folders\""
        branch shouldContain "id=\"nav-tree-parameter-sets\" data-nav-tree=\"parameter-sets\" hidden"
        branch shouldContain "data-nav-root-url=\"/partials/parameter-sets/tree?scope=nav\""
        branch shouldContain "data-nav-workspace=\"acme\""
        branch shouldContain "id=\"params-tree-nav\""
        // No search box: the parameter-set service has no name search to back one.
        branch shouldNotContain "hx-get="
        // The branch sits in Build, before Operate, and is one more item - not a second tree engine.
        html.indexOf("data-nav-branch=\"parameter-sets\"") shouldBeLessThan html.indexOf(">Operate<")
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).withRoles()
}
