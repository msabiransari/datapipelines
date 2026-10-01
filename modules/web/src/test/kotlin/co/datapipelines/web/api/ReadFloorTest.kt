package co.datapipelines.web.api

import co.datapipelines.auth.Permission
import co.datapipelines.auth.PublicPaths
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Gate 2 of the roles design (§4, 177), re-keyed to the permission catalog (#215): **every GET
 * declares the LOWEST permission whose §7.6 row admits it** — a read of admin-only data under an
 * every-role read permission fails by name.
 *
 * The rule is stated per RESOURCE FAMILY, keyed on the path the handler is mapped to, because
 * that is what a reader can see and what the record's §2 rows are about: the executions family
 * is `execution.read` / `execution.result.read` (own unless `execution.read_all`, promoter none —
 * NOT an every-role read, whose row admits the promoter); the workspaces page and the reads OF a
 * workspace are `workspace.read` (a workspace admin's, D13) while the caller's own list is the
 * switcher's `workspace.switch`; the promotion page is `promotion.read` (rule 13); user
 * administration is `user.manage`; a dialog fetched by a verb's route floors at that verb's
 * permission; the self reads are the self rows; everything else a signed-in person may read is
 * one of the every-role reads (`pipeline.read`, `template.read`, `datasource.read`,
 * `endpoint.read`, `semantic.read`, and since #9 `schedule.read` — the same five cells, the lens
 * aside).
 *
 * A family refuses every permission outside its set: `pipeline.read` on an executions GET would
 * admit the promoter to runs the record says it may not see; `execution.read` on a pipelines GET
 * would refuse the promoter reads it holds. Both directions are drift, both fail here by handler
 * name.
 *
 * Non-vacuous: each family must classify at least the handlers it exists for, so a path rule
 * that stopped matching cannot pass by matching nothing. Falsified at birth: re-declare
 * `ExecutionHistoryController#list` as `pipeline.read` and the first test names it.
 */
class ReadFloorTest {
    private val parser = PathPatternParser()
    private val publicPatterns = PublicPaths.PATTERNS.map { parser.parse(it) }

    @Test
    fun `every non-public GET declares the lowest permission its family admits`() {
        val wrong = mutableListOf<String>()
        readers().forEach { handler ->
            val family = familyOf(handler.path)
            val declared = handler.permission
            if (declared == null) {
                wrong += "${handler.name} GET ${handler.path}: no permission at all"
            } else if (declared !in family.permissions) {
                val admitted = family.permissions.map { it.wire }
                wrong += "${handler.name} GET ${handler.path}: declares ${declared.wire}, but the ${family.name} family is $admitted"
            }
        }
        wrong.joinToString("\n").also { withClue(it) { wrong.shouldBeEmpty() } }
    }

    /** Non-vacuity: every family classifies the handlers it exists for. */
    @Test
    fun `every family actually classifies handlers`() {
        val byFamily = readers().groupBy { familyOf(it.path).name }
        Family.entries.forEach { family ->
            withClue("family ${family.name} classified no GET handler — its path rule has stopped matching") {
                (byFamily[family.name]?.size ?: 0) shouldBeGreaterThanOrEqual family.floor
            }
        }
        // …and the sharp ones by name, so the classifier has to get the DIRECTION right.
        familyOf("/api/v1/parameter-sets") shouldBeFamily Family.PARAMETER_SETS
        familyOf("/api/v1/parameter-sets/{id}/versions/{version}") shouldBeFamily Family.PARAMETER_SETS
        familyOf("/api/v1/visualizations") shouldBeFamily Family.VISUALIZATIONS
        familyOf("/api/v1/visualizations/{id}/versions/{version}") shouldBeFamily Family.VISUALIZATIONS
        familyOf("/api/v1/dashboards") shouldBeFamily Family.DASHBOARDS
        familyOf("/api/v1/dashboards/{id}/versions") shouldBeFamily Family.DASHBOARDS
        familyOf("/api/v1/dashboards/{id}") shouldBeFamily Family.DASHBOARDS
        familyOf("/api/v1/dashboards/{id}/runtime/config") shouldBeFamily Family.DASHBOARD_RUNTIME
        familyOf("/api/v1/dashboards/{id}/refreshes") shouldBeFamily Family.DASHBOARD_RUNTIME
        familyOf("/api/v1/dashboards/{id}/refreshes/{refresh_id}") shouldBeFamily Family.DASHBOARD_RUNTIME
        familyOf("/executions") shouldBeFamily Family.EXECUTIONS
        familyOf("/api/v1/executions/{id}/result") shouldBeFamily Family.EXECUTIONS
        familyOf("/partials/recent-executions") shouldBeFamily Family.EXECUTIONS
        // #10 L3b: the dashboards PAGES are the family's reads (DASHBOARD_READ — D50 makes
        // every reader an executor, and the page must not "simplify" into execute), while the
        // events-pane partial is the refresh routes' data and floors with them.
        familyOf("/dashboards") shouldBeFamily Family.DASHBOARDS
        familyOf("/dashboards/{id}") shouldBeFamily Family.DASHBOARDS
        familyOf("/partials/dashboards/tree") shouldBeFamily Family.DASHBOARDS
        familyOf("/partials/dashboards/{id}/refreshes") shouldBeFamily Family.DASHBOARD_RUNTIME
        familyOf("/workspaces") shouldBeFamily Family.WORKSPACES
        familyOf("/api/v1/workspaces") shouldBeFamily Family.WORKSPACES_LIST_OWN
        familyOf("/api/v1/workspaces/{name}/members") shouldBeFamily Family.WORKSPACES
        familyOf("/promotion") shouldBeFamily Family.PROMOTION
        familyOf("/admin/users") shouldBeFamily Family.USER_ADMINISTRATION
        familyOf("/api/v1/pipelines") shouldBeFamily Family.RESOURCES
        familyOf("/pipelines/{id}") shouldBeFamily Family.RESOURCES
        familyOf("/pipelines/{id}/editor") shouldBeFamily Family.PIPELINE_EDITOR
        familyOf("/api-keys") shouldBeFamily Family.API_KEYS
        familyOf("/partials/mcp-key/secret") shouldBeFamily Family.SELF
        familyOf("/settings/password") shouldBeFamily Family.SELF
        familyOf("/avatar") shouldBeFamily Family.SELF
    }

    private infix fun Family.shouldBeFamily(expected: Family) {
        withClue("classified as $name, expected ${expected.name}") { (this == expected).also { require(it) } }
    }

    /** Every non-public GET handler on the classpath. */
    private fun readers(): List<HandlerInventory.Handler> = HandlerInventory.handlers().filter { it.verb == "GET" && !isPublic(it.path) }

    private fun isPublic(path: String): Boolean = publicPatterns.any { it.matches(PathContainer.parsePath(path)) }

    /**
     * The resource families, each the set of permissions a GET in it may declare. Order matters:
     * the first match wins, and the specific families come before the general reads.
     */
    private enum class Family(
        val floor: Int,
        val permissions: Set<Permission>,
        val matches: (String) -> Boolean,
    ) {
        /** D11: own unless workspace admin, promoter none — never READ_RESOURCES. */
        EXECUTIONS(
            floor = 6,
            permissions = setOf(Permission.EXECUTION_READ, Permission.EXECUTION_RESULT_READ),
            matches = { path -> path.contains("executions") },
        ),

        /** The caller's own memberships — the list the switcher draws from, every member's (D13 narrowed the PAGE). */
        WORKSPACES_LIST_OWN(
            floor = 1,
            permissions = setOf(Permission.WORKSPACE_SWITCH),
            matches = { path -> path == "/api/v1/workspaces" },
        ),

        /** D13: the page and the reads OF a workspace (one workspace, its members) are a workspace admin's. */
        WORKSPACES(
            floor = 3,
            permissions = setOf(Permission.WORKSPACE_READ),
            matches = { path -> path == "/workspaces" || path.startsWith("/api/v1/workspaces/") },
        ),

        /** Owner rule 13: author, promoter, admins. */
        PROMOTION(floor = 1, permissions = setOf(Permission.PROMOTION_READ), matches = { path -> path == "/promotion" }),

        /** The instance verbs' reads (the users screen and its partials). */
        USER_ADMINISTRATION(
            floor = 2,
            permissions = setOf(Permission.USER_MANAGE),
            matches = { path ->
                path.startsWith("/admin/users") || path.startsWith("/api/v1/auth/users") ||
                    path.startsWith("/partials/admin/users")
            },
        ),

        /** A super admin's dialog: which workspaces may see a datasource (D-R7). */
        DATASOURCE_GRANTS(
            floor = 1,
            permissions = setOf(Permission.DATASOURCE_GRANT),
            matches = { path -> path.contains("/grants") },
        ),

        /** The workspace admin's datasource dialogs (the pool-fields fragment they embed is a plain read). */
        DATASOURCE_DIALOGS(
            floor = 2,
            permissions = setOf(Permission.DATASOURCE_MANAGE),
            matches = { path -> path.startsWith("/partials/datasources/{") && (path.endsWith("/edit") || path.endsWith("/delete")) },
        ),

        /** Introspection is reading (ratified) — its own row for the credential axis (`author` scope). */
        INTROSPECTION(
            floor = 3,
            permissions = setOf(Permission.DATASOURCE_INTROSPECT, Permission.DATASOURCE_READ),
            matches = { path -> path.startsWith("/api/v1/datasources/") && (path.contains("/schemas") || path.contains("/tables")) },
        ),

        /** The lifecycle dialogs: a GET that returns the FORM of a verb floors at that verb's permission. */
        LIFECYCLE_DIALOGS(
            floor = 10,
            permissions =
                setOf(
                    Permission.PIPELINE_VERSION_MANAGE,
                    Permission.PIPELINE_DELETE,
                    Permission.PIPELINE_RELEASE,
                    Permission.PIPELINE_SWITCH_VERSION,
                    Permission.TEMPLATE_VERSION_MANAGE,
                    Permission.TEMPLATE_DELETE,
                    Permission.TEMPLATE_RELEASE,
                ),
            matches = { path -> path.contains("/lifecycle/") },
        ),

        /**
         * #348 — the OLD editor route's floor is the read it is now: a compatibility REDIRECT
         * into the canonical `/pipelines/{id}` read page (workspace spec §3.2), so the family
         * admits [Permission.PIPELINE_READ] and nothing higher — the promoter walks through it
         * to the released content exactly as the canonical route admits. 122's execute floor
         * described the page the route USED to serve; that page is gone, and auth.md §7.6's
         * rows moved with this family in the same commit.
         */
        PIPELINE_EDITOR(
            floor = 1,
            permissions = setOf(Permission.PIPELINE_READ),
            matches = { path -> path == "/pipelines/{id}/editor" },
        ),

        /** The self verbs' reads — and, since #215, the settings pages that serve them (every role's rows). */
        SELF(
            floor = 5,
            permissions = setOf(Permission.PROFILE_READ, Permission.PROFILE_PASSWORD, Permission.MCP_KEY_OWN),
            matches = { path ->
                path == "/api/v1/auth/me" || path.startsWith("/api/v1/auth/api-keys") ||
                    path.startsWith("/partials/mcp-key") || path == "/settings" || path.startsWith("/settings/") ||
                    path == "/avatar" // #197 — the signed-in principal's own picture
            },
        ),

        /** Keys v2 (A15): the Keys page is EVERY signed-in person's — the mcp_key.own row. */
        API_KEYS(
            floor = 1,
            permissions = setOf(Permission.MCP_KEY_OWN),
            matches = { path -> path == "/api-keys" },
        ),

        /** The promotion RECEIVER's inventory read — the server-key route family (§7.7); its row is the second gate. */
        PROMOTION_RECEIVER(
            floor = 1,
            permissions = setOf(Permission.PROMOTION_INVENTORY_READ),
            matches = { path -> path.startsWith("/api/v1/promotion/") },
        ),

        /** The published-endpoint serve: `read` is the floor, the binding is the gate (§7.7). */
        PUBLISHED_ENDPOINT(
            floor = 1,
            permissions = setOf(Permission.ENDPOINT_SERVE),
            matches = { path -> path.startsWith("/api/{") },
        ),

        /** #194 lane D: the parameter sets — an every-role read (the lens aside, one row shape). */
        PARAMETER_SETS(
            floor = 4,
            permissions = setOf(Permission.PARAMETER_SET_READ),
            matches = { path -> path.startsWith("/api/v1/parameter-sets") },
        ),

        /** #10 L1b: the visualizations — an every-role read (the lens aside); floor = the family's six GET handlers
         * (L1c's export joined). */
        VISUALIZATIONS(
            floor = 6,
            permissions = setOf(Permission.VISUALIZATION_READ),
            matches = { path -> path.startsWith("/api/v1/visualizations") },
        ),

        /**
         * #10 L2: the dashboard RUNTIME's three GETs — the configuration and the caller's refresh list and read — floor
         * `dashboard.execute` (D50), not `dashboard.read`: reading a runtime configuration is the act of running the
         * dashboard. L3b's events-pane partial (/partials/dashboards/{id}/refreshes) is the SAME rows through the page,
         * so it floors here too — the page that embeds it stays on [DASHBOARDS]. AHEAD of [DASHBOARDS] because the first
         * match wins and both start with the same prefix.
         */
        DASHBOARD_RUNTIME(
            floor = 4,
            permissions = setOf(Permission.DASHBOARD_EXECUTE),
            matches = { path ->
                (
                    path.startsWith("/api/v1/dashboards/") &&
                        (path.contains("/runtime/") || path.endsWith("/refreshes") || path.contains("/refreshes/"))
                ) ||
                    (path.startsWith("/partials/dashboards/") && path.contains("/refreshes"))
            },
        ),

        /** #10 L1b the API family (six GET handlers, L1c's export joined), L3b the three pages: the dashboards —
         * the same row shape; the family's reads. */
        DASHBOARDS(
            floor = 9,
            permissions = setOf(Permission.DASHBOARD_READ),
            matches = { path ->
                path.startsWith("/api/v1/dashboards") ||
                    path == "/dashboards" ||
                    path.startsWith("/dashboards/") ||
                    path == "/partials/dashboards/tree"
            },
        ),

        /** Everything else a signed-in person reads: the every-role reads (the lens aside, one row shape). */
        RESOURCES(
            floor = 40,
            permissions =
                setOf(
                    Permission.PIPELINE_READ,
                    Permission.TEMPLATE_READ,
                    Permission.DATASOURCE_READ,
                    Permission.ENDPOINT_READ,
                    Permission.SEMANTIC_READ,
                    // #9 (R8): every member reads schedules, the promoter through the lens — the same row shape.
                    Permission.SCHEDULE_READ,
                ),
            matches = { true },
        ),
    }

    private fun familyOf(path: String): Family = Family.entries.first { it.matches(path) }
}
