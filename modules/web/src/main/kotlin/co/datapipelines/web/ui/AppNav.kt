package co.datapipelines.web.ui

/**
 * 079 §A/§B — the sidebar's section table, and the ONE place the top bar's breadcrumb is
 * derived from.
 *
 * The rail's MARKUP stays in `layouts/default.html`: every item carries an inline SVG, and
 * moving icon paths into Kotlin would only trade a readable template for an unreadable
 * string table. What lives here is the single derived fact the markup cannot compute — the
 * breadcrumb's `<group> / <page>` pair for the CURRENT path — plus the grouping and matching
 * rule the template renders against.
 *
 * The two halves are kept honest by `ShellRenderTest`: every `data-nav-section` the rendered
 * rail carries must resolve here to that link's own `data-nav-group`/`data-nav-label`. A
 * link added to the template without a row here (or with the wrong group) fails the build
 * rather than silently rendering a breadcrumb that disagrees with the highlighted item.
 *
 * ## Matching
 *
 * Exactly the rule `shell.js` mirrors client-side after a boosted swap and the rule the
 * layout consumes: **a section owns its root and slash-delimited descendants** (#659).
 * `/pipelines/abc` is still "Pipelines", and so is its overview `/overview/pipelines` (#556).
 * [crumbFor] resolves ties by the LONGEST matching section, so a future `/settings/api-keys` row would win
 * over `/settings`.
 */
object AppNav {
    /** The four group headings, in menu order. Home sits above them all, ungrouped. */
    const val BUILD = "Build"

    /**
     * #554 (shell record §3): the families a person reads and composes from — Dashboards,
     * Visualizations and Parameter Sets — leave Build for their own group.
     */
    const val EXPLORE = "Explore"
    const val OPERATE = "Operate"
    const val ORGANISATION = "Organisation"

    /** #556 — the root every family overview sits under (`OverviewController`). */
    const val OVERVIEW_PREFIX = "/overview"

    /**
     * One rail item. [section] is the `data-nav-section` the template renders and `shell.js`
     * matches on; it is a path prefix bounded by a slash or the end of the path.
     */
    data class Item(
        val section: String,
        val label: String,
        val group: String?,
    )

    /** Rail order, top to bottom. The template renders the same order by hand. */
    val ITEMS: List<Item> =
        listOf(
            // D58 (#10 L3b): the landing item is Home — the route (/dashboard) is unchanged,
            // only the label renamed; its section follows the same segment boundary as every item.
            Item("/dashboard", "Home", null),
            Item("/pipelines", "Pipelines", BUILD),
            Item("/templates", "Templates", BUILD),
            Item("/datasources", "Datasources", BUILD),
            // #10 L3b (D58): the Dashboards item — an every-role read (the promoter through
            // the lens), so unlike Executions/Promotion it rides no advice boolean. The item
            // links the catalog; its tree lives in the resource browser beside the menu (#554).
            Item("/dashboards", "Dashboards", EXPLORE),
            // #399 (#396): the Visualizations item — an every-role read (`visualization.read`, the
            // promoter through the lens), no advice boolean. The item links the flat catalog page;
            // its lazy, searchable tree lives in the resource browser (#554).
            Item("/visualizations", "Visualizations", EXPLORE),
            // #374 (#357 S1): the Parameter Sets item — an every-role read (`parameter_set.read`, the
            // promoter through the lens), so it rides no advice boolean either. The item links the
            // flat catalog page; its lazy tree lives in the resource browser (#554).
            Item("/parameter-sets", "Parameter Sets", EXPLORE),
            Item("/executions", "Executions", OPERATE),
            // #9 slice 2: every member reads schedules (`schedule.read`), so unlike Executions
            // the item is drawn for every role — a promoter reads through the lens.
            Item("/schedules", "Schedules", OPERATE),
            Item("/api-console", "API", OPERATE),
            Item("/promotion", "Promotion", OPERATE),
            Item("/workspaces", "Workspaces", ORGANISATION),
            Item("/admin", "Admin", ORGANISATION),
            Item("/docs", "Docs", ORGANISATION),
        )

    private val BY_SECTION: Map<String, Item> = ITEMS.associateBy { it.section }

    /** The item a rendered link's `data-nav-section` refers to, or null if the table has no row. */
    fun bySection(section: String): Item? = BY_SECTION[section]

    /**
     * The active item for a request path, or null when the path is not under any section
     * (`/login`, `/settings`, an error page) — the breadcrumb then falls back to the page's
     * own title and no rail item is highlighted, which is exactly what those screens want.
     */
    fun activeFor(path: String?): Item? {
        if (path.isNullOrBlank()) return null
        return ITEMS
            .filter { matches(it.section, path) }
            .maxByOrNull { it.section.length }
    }

    /**
     * The segment-boundary rule shared by the layout and mirrored in `shell.js`. #556:
     * a section's overview (`/overview/pipelines`) belongs to that section, so the family item
     * stays active and the crumb names the family on its home page.
     */
    fun matches(
        section: String,
        path: String,
    ): Boolean = owns(section, path) || owns(OVERVIEW_PREFIX + section, path)

    /** A prefix owns only its root and slash-delimited descendants, never a bare lookalike. */
    fun owns(
        prefix: String,
        path: String,
    ): Boolean = path == prefix || path.startsWith("$prefix/")

    /**
     * The top bar's breadcrumb for a path: the group (muted, may be null) and the page name
     * (bold). Screens outside the rail get their group as null and their label from
     * [OFF_RAIL] — Settings is the one that matters, since the avatar menu links to it.
     */
    fun crumbFor(path: String?): Pair<String?, String>? {
        val item = activeFor(path)
        if (item != null) return item.group to item.label
        val off = OFF_RAIL.entries.filter { path != null && owns(it.key, path) }.maxByOrNull { it.key.length }
        return off?.let { null to it.value }
    }

    /**
     * Screens reachable from the avatar menu or a link, deliberately NOT in the rail: the rail
     * is for the workspace's content, and a personal-settings row in it would be the eleventh
     * item competing with the ten that matter (the mock puts Settings in the avatar menu).
     */
    private val OFF_RAIL: Map<String, String> =
        mapOf(
            // 179 (D17): the workspace's API keys page — reached from the avatar menu and the
            // console's Manage links, deliberately not a rail item (an admins' screen).
            "/api-keys" to "API keys",
            "/settings/api-keys" to "API keys",
            "/settings/password" to "Password",
            "/settings" to "Settings",
        )
}
