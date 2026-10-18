package co.datapipelines.web.ui

import co.datapipelines.pipeline.SearchNeedle
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

    /**
     * #554 (shell record §3): four groups under Home, each a `role=group` labelled by its
     * caption, and no count beside any item — "resource counts belong in the resource browser,
     * not repeated beside every primary item". The menu holds no tree either.
     */
    @Test
    fun `the menu renders the four groups in order, the collapse control, and no counts or trees`() {
        val html =
            engine.process(
                "pipelines/list",
                webContext().apply {
                    fillList()
                    setVariable("navCounts", NavCounts.Counts(9, 27))
                },
            )
        val menu = html.substringAfter("<aside class=\"app-rail\"").substringBefore("</aside>")

        html shouldContain "class=\"app-shell\""
        val captions = CAPTION.findAll(menu).toList()
        captions.map { it.groupValues[2] } shouldBe listOf(AppNav.BUILD, AppNav.EXPLORE, AppNav.OPERATE, AppNav.ORGANISATION)
        captions.forEach { caption ->
            menu shouldContain "<div class=\"app-nav-group\" role=\"group\" aria-labelledby=\"app-nav-group-${caption.groupValues[1]}\">"
        }
        menu shouldContain "id=\"rail-collapse\""
        menu shouldNotContain "app-nav-badge"
        menu shouldNotContain ">9<"
        menu shouldNotContain "data-nav-tree"
        html shouldNotContain "data-nav-branch"
        html shouldNotContain "data-nav-tree-toggle"
    }

    /**
     * #554: every menu link sits inside the group whose caption its `data-nav-group` names — the
     * markup's grouping and [AppNav]'s table cannot drift apart (the breadcrumb agreement test
     * below holds the table to the link's attributes; this holds the attributes to the box the
     * reader sees them in).
     */
    @Test
    fun `every menu link sits inside the group its data-nav-group names`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })
        val menu = html.substringAfter("<nav class=\"app-nav\"").substringBefore("</nav>")
        val groups = menu.split("<div class=\"app-nav-group\"").drop(1)
        groups.size shouldBe 4
        var grouped = 0
        groups.forEach { group ->
            val caption = CAPTION.find(group).shouldNotBeNull()
            val names = Regex("""data-nav-group="([^"]*)"""").findAll(group).map { it.groupValues[1] }.toList()
            names.forEach { it shouldBe caption.groupValues[2] }
            grouped += names.size
        }
        // Non-vacuous: every grouped AppNav row was found in a box (Home is the one ungrouped row).
        grouped shouldBe AppNav.ITEMS.count { it.group != null }
    }

    /**
     * #554 (record §3 rule 4–5, §7): the family title and its admitted count head the browser;
     * only the ACTIVE family's parts are visible, the others ship `hidden` for the in-document
     * family switch. The count's number is followed by a visually-hidden noun, so a screen
     * reader hears "9 pipelines", not a bare number.
     */
    @Test
    fun `the browser header carries the active family's title and admitted count`() {
        val html =
            engine.process(
                "pipelines/list",
                webContext().apply {
                    fillList()
                    setVariable("navCounts", NavCounts.Counts(9, 27, dashboards = 4))
                },
            )
        val head = html.substringAfter("class=\"app-browser-head\"").substringBefore("</div>")

        head shouldContain "<span data-browser-family=\"pipelines\">Pipelines</span>"
        head shouldContain "<span data-browser-family=\"templates\" hidden=\"hidden\">Templates</span>"
        head shouldContain
            "<span class=\"app-browser-count\" data-browser-family=\"pipelines\"><span>9</span>" +
            "<span class=\"app-visually-hidden\"> pipelines</span></span>"
        head shouldContain "data-browser-family=\"templates\" hidden=\"hidden\"><span>27</span>"
        head shouldContain "data-browser-family=\"dashboards\" hidden=\"hidden\"><span>4</span>"
        // Unknown counts are omitted, never a zero (record §7).
        head shouldNotContain "app-browser-count\" data-browser-family=\"visualizations\""
        head shouldContain "id=\"browser-hide\""
        head shouldContain "aria-label=\"Hide resource browser\""
    }

    @Test
    fun `a workspace with unreadable counts renders the browser header with no count rather than a zero`() {
        val html =
            engine.process(
                "pipelines/list",
                webContext().apply {
                    fillList()
                    setVariable("navCounts", NavCounts.Counts.NONE)
                },
            )

        html shouldContain "id=\"app-browser-title\""
        html shouldNotContain "app-browser-count"
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
        setVariable("rootId", PipelineBrowseModel.CATALOG_ROOT_ID)
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
        // #513 — UiWorkspaceAdvice's attribute at runtime; the render tests set it by hand.
        setVariable("searchMaxLength", SearchNeedle.MAX_LENGTH)
        // 127 — SiteOriginAdvice's attribute at runtime; the render tests set it by hand.
        setVariable("reportProblemUrl", REPORT_PROBLEM_URL)
    }

    /**
     * D58 (#10 L3b): the landing item is RENAMED Home — the route (/dashboard) and its
     * exact-match section are unchanged. #554 (record §3): Dashboards, Visualizations and
     * Parameter Sets leave Build for Explore, in that order, between Build and Operate.
     */
    @Test
    fun `the landing item is Home and the three read-and-compose families sit in Explore`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })
        val explore =
            html
                .substringAfter("id=\"app-nav-group-explore\">Explore<", missingDelimiterValue = "")
                .substringBefore("id=\"app-nav-group-operate\">Operate<")

        html shouldContain "data-nav-group=\"\" data-nav-label=\"Home\""
        html shouldNotContain "data-nav-label=\"Dashboard\""
        val families = listOf("Dashboards" to "/dashboards", "Visualizations" to "/visualizations", "Parameter Sets" to "/parameter-sets")
        families.forEach { (label, section) ->
            explore shouldContain "data-nav-section=\"$section\""
            explore shouldContain "data-nav-group=\"Explore\" data-nav-label=\"$label\""
        }
        explore.indexOf("/dashboards\"") shouldBeLessThan explore.indexOf("/visualizations\"")
        explore.indexOf("/visualizations\"") shouldBeLessThan explore.indexOf("/parameter-sets\"")
        // The lucide glyphs, from the vendored sprite (icons-from-lucide-only).
        explore shouldContain "/vendor/icons/lucide-sprite.svg#chart-line"
        explore shouldContain "/vendor/icons/lucide-sprite.svg#sliders-horizontal"
        html shouldNotContain "data-nav-group=\"Build\" data-nav-label=\"Dashboards\""
    }

    /**
     * #554 (record §3, §7): the resource browser is the menu's SIBLING, outside the boosted
     * #app-main, hosting the five REST-tree panels with their #350 ids and `data-nav-tree` — the
     * active family's visible, the rest `hidden` — then the footer's View all for that family,
     * the width reset, and the separator on the browser/content boundary.
     */
    @Test
    fun `the browser column hosts the five tree panels outside main, the active family visible`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })
        val shell = html.substringAfter("<div class=\"app-shell\"")
        val browser =
            shell
                .substringAfter("<aside class=\"app-browser\" id=\"app-browser\"", missingDelimiterValue = "")
                .substringBefore("</aside>")

        // Order of the siblings: menu, browser, backdrop, top bar, main.
        shell.indexOf("id=\"app-rail\"") shouldBeLessThan shell.indexOf("id=\"app-browser\"")
        shell.indexOf("id=\"app-browser\"") shouldBeLessThan shell.indexOf("<main id=\"app-main\"")
        html.substringAfter("<main id=\"app-main\"") shouldNotContain "data-nav-tree=\""
        browser shouldContain "hx-boost=\"true\""
        browser shouldContain "aria-labelledby=\"app-browser-title\""
        browser.substringBefore(">") shouldNotContain "hidden"

        val panels = PANEL.findAll(browser).toList()
        panels.map { it.groupValues[1] } shouldBe listOf("pipelines", "templates", "dashboards", "visualizations", "parameter-sets")
        panels.forEach { panel ->
            panel.groupValues[2] shouldBe panel.groupValues[1]
            panel.value shouldContain "data-nav-workspace=\"acme\""
            (panel.value.contains("hidden=\"hidden\"")) shouldBe (panel.groupValues[1] != "pipelines")
        }
        browser shouldContain "<a href=\"/pipelines\" class=\"app-browser-all\" data-browser-family=\"pipelines\">"
        browser shouldContain "View all pipelines"
        browser shouldContain "id=\"browser-width-reset\""
        browser shouldContain
            "<div id=\"browser-boundary\" class=\"app-browser-boundary\" role=\"separator\" tabindex=\"0\""
        browser shouldContain "aria-valuemin=\"240\" aria-valuemax=\"480\""

        // The engine, the browser lifecycle and the width control load once, from the footer.
        html shouldContain "<script src=\"/js/template-explorer.js\"></script>"
        html shouldContain "<script type=\"module\" src=\"/js/tree/sidebar.mjs\"></script>"
        html shouldContain "<script type=\"module\" src=\"/js/browser-resize.mjs\"></script>"
        html shouldNotContain "rail-resize"
        html shouldNotContain "rail-boundary"
        html shouldNotContain "rail-width-reset"
        html shouldNotContain "src=\"/js/nav-tree.js\""
        html shouldNotContain "data-nav-root-url"
        // rail.js reads the active workspace's pre-paint browser keys off <html>.
        html shouldContain "data-dp-workspace=\"acme\""
        // #513 — the browser search's client-side bound rides the same <html> attribute belt.
        html shouldContain "data-dp-search-max-length=\"${SearchNeedle.MAX_LENGTH}\""
    }

    /** #554 (record §3 rule 7): the folder button is named for the family it reveals. */
    @Test
    fun `the top bar's browser opener has desktop and phone glyphs and the active family's name`() {
        listOf(
            "/pipelines" to "Pipelines",
            "/templates/x" to "Templates",
            "/dashboards/x" to "Dashboards",
            "/visualizations" to "Visualizations",
            "/parameter-sets/x" to "Parameter Sets",
        ).forEach { (path, label) ->
            val html =
                engine.process(
                    "pipelines/list",
                    webContext().apply {
                        fillList()
                        setVariable("currentPath", path)
                    },
                )
            val button = html.substringAfter("<button type=\"button\" id=\"browser-open\"").substringBefore(">")
            button shouldContain "aria-controls=\"app-browser\""
            button shouldContain "aria-label=\"Browse $label\" title=\"Browse $label\""
            button shouldNotContain "hidden"
            val content = html.substringAfter("id=\"browser-open\"").substringBefore("</button>")
            content shouldContain "lucide-sprite.svg#chevron-right"
            content shouldContain "lucide-sprite.svg#folder"
            Regex("app-browser-open-desktop").findAll(content).count() shouldBe 1
            Regex("app-browser-open-phone").findAll(content).count() shouldBe 1
        }
    }

    /**
     * #556 (record §3 rule 1): a family's overview is that family's page — its menu item is the
     * active one, the breadcrumb names it, and the resource browser opens on its tree. The
     * Pipelines item links its overview; the other four keep their catalogs until their own
     * overview lands (#579–#582).
     */
    @Test
    fun `a family overview lights its item, names its crumb and opens its browser tree`() {
        listOf(
            "pipelines" to "Pipelines",
            "templates" to "Templates",
            "dashboards" to "Dashboards",
            "visualizations" to "Visualizations",
            "parameter-sets" to "Parameter Sets",
        ).forEach { (family, label) ->
            val path = "/overview/$family"
            val html =
                engine.process(
                    "pipelines/list",
                    webContext().apply {
                        fillList()
                        setVariable("currentPath", path)
                        setVariable("crumbGroup", AppNav.crumbFor(path)?.first)
                        setVariable("crumbPage", AppNav.crumbFor(path)?.second)
                    },
                )
            val active = Regex("""<a [^>]*class="app-nav-link active"[^>]*>""").findAll(html).map { it.value }.toList()
            active.size shouldBe 1
            active.single() shouldContain "data-nav-label=\"$label\""
            active.single() shouldContain "aria-current=\"page\""
            AppNav.crumbFor(path)?.second shouldBe label
            html.substringAfter("class=\"app-crumb-page\"").substringAfter(">").substringBefore("<") shouldBe label

            html.substringAfter("<aside class=\"app-browser\"").substringBefore(">") shouldNotContain "hidden"
            html.substringAfter("<button type=\"button\" id=\"browser-open\"").substringBefore(">") shouldNotContain "hidden"
            PANEL.findAll(html).forEach { panel ->
                if (panel.groupValues[1] == family) panel.value shouldNotContain "hidden" else panel.value shouldContain "hidden=\"hidden\""
            }
        }
    }

    @Test
    fun `the Pipelines item links its overview and the other families keep their catalogs`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })
        val href = { label: String ->
            Regex(
                """<a href="([^"]+)" class="app-nav-link[^"]*" data-nav-section="[^"]+"\s+data-nav-group="[^"]*" data-nav-label="$label"""",
            ).find(html)
                ?.groupValues
                ?.get(1)
        }

        href("Pipelines") shouldBe "/overview/pipelines"
        href("Templates") shouldBe "/templates"
        href("Dashboards") shouldBe "/dashboards"
        href("Visualizations") shouldBe "/visualizations"
        href("Parameter Sets") shouldBe "/overview/parameter-sets"
    }

    /** #554 (record §3 rule 5): off the five families the browser and its folder button render hidden. */
    @Test
    fun `a non-family page renders the browser and its folder button hidden`() {
        listOf("/executions", "/datasources", "/dashboard", "/schedules").forEach { path ->
            val html =
                engine.process(
                    "pipelines/list",
                    webContext().apply {
                        fillList()
                        setVariable("currentPath", path)
                    },
                )
            html.substringAfter("<aside class=\"app-browser\"").substringBefore(">") shouldContain "hidden=\"hidden\""
            html.substringAfter("<button type=\"button\" id=\"browser-open\"").substringBefore(">") shouldContain "hidden=\"hidden\""
            Regex("""id="nav-tree-[a-z-]+"[^>]*>""").findAll(html).forEach { it.value shouldContain "hidden=\"hidden\"" }
        }
    }

    private companion object {
        /** A menu group's caption: its id suffix and its visible word. */
        val CAPTION = Regex("""<div class="app-nav-section" id="app-nav-group-([a-z]+)">([A-Za-z]+)</div>""")

        /** One resource-browser tree panel: its #350 id suffix and its `data-nav-tree`. */
        val PANEL = Regex("""<div class="app-nav-tree app-browser-tree" id="nav-tree-([a-z-]+)" data-nav-tree="([a-z-]+)"[^>]*>""")
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).withRoles()
}
