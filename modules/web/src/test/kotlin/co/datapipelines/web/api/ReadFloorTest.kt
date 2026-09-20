package co.datapipelines.web.api

import co.datapipelines.auth.PublicPaths
import co.datapipelines.auth.ScopeMatrix.RestOperation
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Gate 2 of the roles design (§4, 177): **every GET declares the LOWEST operation whose §7.6
 * row admits it** — a read of admin-only data under `READ_RESOURCES` fails by name.
 *
 * The rule is stated per RESOURCE FAMILY, keyed on the path the handler is mapped to, because
 * that is what a reader can see and what the record's §2 rows are about: the executions family
 * is the `READ_EXECUTIONS` / `RETRIEVE_RESULT` row (own unless admin, promoter none — NOT
 * `READ_RESOURCES`, whose row admits the promoter); the workspaces page and the reads OF a
 * workspace are `WORKSPACES_READ` (a workspace admin's, D13) while the caller's own list is the
 * switcher's `WORKSPACE_SWITCH`; the promotion page is `PROMOTION_READ` (rule
 * 13); user administration is `USER_ADMINISTRATION`; a dialog fetched by a verb's route floors at
 * that verb's operation; everything else a signed-in person may read is `READ_RESOURCES`.
 *
 * A family that names one operation refuses every other: `READ_RESOURCES` on an executions GET
 * would admit the promoter to runs the record says it may not see; `READ_EXECUTIONS` on a
 * pipelines GET would refuse the promoter reads it holds. Both directions are drift, both fail
 * here by handler name.
 *
 * Non-vacuous: each family must classify at least the handlers it exists for, so a path rule
 * that stopped matching cannot pass by matching nothing. Falsified at birth: re-declare
 * `ExecutionHistoryController#screen` as `READ_RESOURCES` and the first test names it.
 */
class ReadFloorTest {
    private val parser = PathPatternParser()
    private val publicPatterns = PublicPaths.PATTERNS.map { parser.parse(it) }

    @Test
    fun `every non-public GET declares the lowest operation its family admits`() {
        val wrong = mutableListOf<String>()
        readers().forEach { handler ->
            val family = familyOf(handler.path)
            val declared = handler.operation
            if (declared == null) {
                wrong += "${handler.name} GET ${handler.path}: no operation at all"
            } else if (declared !in family.operations) {
                val admitted = family.operations.map { it.name }
                wrong += "${handler.name} GET ${handler.path}: declares ${declared.name}, but the ${family.name} family is $admitted"
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
    }

    private infix fun Family.shouldBeFamily(expected: Family) {
        withClue("classified as $name, expected ${expected.name}") { (this == expected).also { require(it) } }
    }

    /** Every non-public GET handler on the classpath. */
    private fun readers(): List<HandlerInventory.Handler> = HandlerInventory.handlers().filter { it.verb == "GET" && !isPublic(it.path) }

    private fun isPublic(path: String): Boolean = publicPatterns.any { it.matches(PathContainer.parsePath(path)) }

    /**
     * The resource families, each the set of operations a GET in it may declare. Order matters:
     * the first match wins, and the specific families come before the general reads.
     */
    private enum class Family(
        val floor: Int,
        val operations: Set<RestOperation>,
        val matches: (String) -> Boolean,
    ) {
        /** D11: own unless workspace admin, promoter none — never READ_RESOURCES. */
        EXECUTIONS(
            floor = 6,
            operations = setOf(RestOperation.READ_EXECUTIONS, RestOperation.RETRIEVE_RESULT),
            matches = { path -> path.contains("executions") },
        ),

        /** The caller's own memberships — the list the switcher draws from, every member's (D13 narrowed the PAGE). */
        WORKSPACES_LIST_OWN(
            floor = 1,
            operations = setOf(RestOperation.WORKSPACE_SWITCH),
            matches = { path -> path == "/api/v1/workspaces" },
        ),

        /** D13: the page and the reads OF a workspace (one workspace, its members) are a workspace admin's. */
        WORKSPACES(
            floor = 3,
            operations = setOf(RestOperation.WORKSPACES_READ),
            matches = { path -> path == "/workspaces" || path.startsWith("/api/v1/workspaces/") },
        ),

        /** Owner rule 13: author, promoter, admins. */
        PROMOTION(floor = 1, operations = setOf(RestOperation.PROMOTION_READ), matches = { path -> path == "/promotion" }),

        /** The instance verbs' reads (the users screen and its partials). */
        USER_ADMINISTRATION(
            floor = 2,
            operations = setOf(RestOperation.USER_ADMINISTRATION),
            matches = { path ->
                path.startsWith("/admin/users") || path.startsWith("/api/v1/auth/users") ||
                    path.startsWith("/partials/admin/users")
            },
        ),

        /** A super admin's dialog: which workspaces may see a datasource (D-R7). */
        DATASOURCE_GRANTS(
            floor = 1,
            operations = setOf(RestOperation.MANAGE_DATASOURCE_GRANTS),
            matches = { path -> path.contains("/grants") },
        ),

        /** The workspace admin's datasource dialogs (the pool-fields fragment they embed is a plain read). */
        DATASOURCE_DIALOGS(
            floor = 2,
            operations = setOf(RestOperation.MUTATE_WORKSPACE_DATASOURCES),
            matches = { path -> path.startsWith("/partials/datasources/{") && (path.endsWith("/edit") || path.endsWith("/delete")) },
        ),

        /** Introspection is reading (ratified) — its own row for the credential axis (`author` scope). */
        INTROSPECTION(
            floor = 3,
            operations = setOf(RestOperation.INTROSPECT_DATASOURCE, RestOperation.READ_RESOURCES),
            matches = { path -> path.startsWith("/api/v1/datasources/") && (path.contains("/schemas") || path.contains("/tables")) },
        ),

        /** The lifecycle dialogs: a GET that returns the FORM of a verb floors at that verb's operation. */
        LIFECYCLE_DIALOGS(
            floor = 10,
            operations =
                setOf(
                    RestOperation.MUTATE_PIPELINES_TEMPLATES,
                    RestOperation.RELEASE_VERSION,
                    RestOperation.SWITCH_SERVED_VERSION,
                ),
            matches = { path -> path.contains("/lifecycle/") },
        ),

        /** 122: the pipeline editor floors at the operation the screen exists to perform for its lowest role. */
        PIPELINE_EDITOR(
            floor = 1,
            operations = setOf(RestOperation.EXECUTE_PIPELINE),
            matches = { path -> path == "/pipelines/{id}/editor" },
        ),

        /** The self verbs' reads. */
        SELF(
            floor = 2,
            operations = setOf(RestOperation.CURRENT_PRINCIPAL, RestOperation.MANAGE_OWN_API_KEYS),
            matches = { path -> path == "/api/v1/auth/me" || path.startsWith("/api/v1/auth/api-keys") },
        ),

        /** The promotion RECEIVER's inventory read — the server-key route family (§7.7); its row is the second gate. */
        PROMOTION_RECEIVER(
            floor = 1,
            operations = setOf(RestOperation.READ_RESOURCES),
            matches = { path -> path.startsWith("/api/v1/promotion/") },
        ),

        /** The published-endpoint serve: `read` is the floor, the binding is the gate (§7.7). */
        PUBLISHED_ENDPOINT(
            floor = 1,
            operations = setOf(RestOperation.SERVE_PUBLISHED_ENDPOINT),
            matches = { path -> path.startsWith("/api/{") },
        ),

        /** Everything else a signed-in person reads: the lowest row, every role. */
        RESOURCES(floor = 40, operations = setOf(RestOperation.READ_RESOURCES), matches = { true }),
    }

    private fun familyOf(path: String): Family = Family.entries.first { it.matches(path) }
}
