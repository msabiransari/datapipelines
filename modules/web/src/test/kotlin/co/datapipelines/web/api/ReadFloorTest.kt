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
 * `endpoint.read`, `semantic.read` — the same five cells, the lens aside).
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
        familyOf("/executions") shouldBeFamily Family.EXECUTIONS
        familyOf("/api/v1/executions/{id}/result") shouldBeFamily Family.EXECUTIONS
        familyOf("/partials/recent-executions") shouldBeFamily Family.EXECUTIONS
        familyOf("/workspaces") shouldBeFamily Family.WORKSPACES
        familyOf("/api/v1/workspaces") shouldBeFamily Family.WORKSPACES_LIST_OWN
        familyOf("/api/v1/workspaces/{name}/members") shouldBeFamily Family.WORKSPACES
        familyOf("/promotion") shouldBeFamily Family.PROMOTION
        familyOf("/admin/users") shouldBeFamily Family.USER_ADMINISTRATION
        familyOf("/api/v1/pipelines") shouldBeFamily Family.RESOURCES
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

        /** 122: the pipeline editor floors at the operation the screen exists to perform for its lowest role. */
        PIPELINE_EDITOR(
            floor = 1,
            permissions = setOf(Permission.PIPELINE_EXECUTE),
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
                ),
            matches = { true },
        ),
    }

    private fun familyOf(path: String): Family = Family.entries.first { it.matches(path) }
}
