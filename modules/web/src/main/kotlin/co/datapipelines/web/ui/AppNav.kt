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
 * layout's `th:classappend` has used since 076: **Dashboard by equality, everything else by
 * prefix** — `/pipelines/abc` is still "Pipelines". [crumbFor] resolves ties by the LONGEST
 * matching section, so a future `/settings/api-keys` row would win over `/settings`.
 */
object AppNav {
    /** The three group headings, in rail order. Dashboard sits above them all, ungrouped. */
    const val BUILD = "Build"
    const val OPERATE = "Operate"
    const val ORGANISATION = "Organisation"

    /**
     * One rail item. [section] is the `data-nav-section` the template renders and `shell.js`
     * matches on; it is a path PREFIX except for the Dashboard, which matches exactly (a
     * prefix rule would light Dashboard up on every path, since `/` prefixes everything —
     * hence the special case rather than a cleverer general rule).
     */
    data class Item(
        val section: String,
        val label: String,
        val group: String?,
    )

    /** Rail order, top to bottom. The template renders the same order by hand. */
    val ITEMS: List<Item> =
        listOf(
            Item("/dashboard", "Dashboard", null),
            Item("/pipelines", "Pipelines", BUILD),
            Item("/templates", "Templates", BUILD),
            Item("/datasources", "Datasources", BUILD),
            Item("/executions", "Executions", OPERATE),
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

    /** The 076 rule, restated once so the server, `shell.js` and this table cannot drift. */
    fun matches(
        section: String,
        path: String,
    ): Boolean =
        when (section) {
            "/dashboard" -> path == "/dashboard"
            else -> path.startsWith(section)
        }

    /**
     * The top bar's breadcrumb for a path: the group (muted, may be null) and the page name
     * (bold). Screens outside the rail get their group as null and their label from
     * [OFF_RAIL] — Settings is the one that matters, since the avatar menu links to it.
     */
    fun crumbFor(path: String?): Pair<String?, String>? {
        val item = activeFor(path)
        if (item != null) return item.group to item.label
        val off = OFF_RAIL.entries.filter { path != null && path.startsWith(it.key) }.maxByOrNull { it.key.length }
        return off?.let { null to it.value }
    }

    /**
     * Screens reachable from the avatar menu or a link, deliberately NOT in the rail: the rail
     * is for the workspace's content, and a personal-settings row in it would be the eleventh
     * item competing with the ten that matter (the mock puts Settings in the avatar menu).
     */
    private val OFF_RAIL: Map<String, String> =
        mapOf(
            "/settings/api-keys" to "API keys",
            "/settings/password" to "Password",
            "/settings" to "Settings",
        )
}
