package co.datapipelines.integration

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.response.Response

/**
 * **The query table of the dependency tree routes** (#875 G4 §C). `GET /api/v1/dependencies/<family>` names its root in
 * the QUERY, never in the path, so the route walks' path substitution (`EntryDiscovery.concretePath`, the isolation
 * sweep's variable table, the lens sweep's `{id}`/name pairs) gives them nothing to stand on: without this table they
 * answer 400 to every caller, and a walk that judges only 403 would score that "reached". The table is one entry per
 * family route — [FAMILIES] — and [unfixtured] names any route under [PREFIX] that has none, so a route added without
 * its entry (or an entry deleted) is a named failure ("query-only route without fixtures"), never a green-by-400.
 *
 * [seed] authors ONE real root per family through the product's own REST routes in the caller's workspace — a dashboard
 * closure (its set, source pipeline, chart and their templates), an API on the source pipeline, a schedule on it, and
 * an H2 datasource — so each walked query resolves a seeded root.
 */
internal object DependencyTreeQueries {
    const val PREFIX = "/api/v1/dependencies/"

    /** The family routes, by wire key — one table entry per route. */
    val FAMILIES: List<String> =
        listOf("pipeline", "template", "parameter_set", "visualization", "dashboard", "api_endpoint", "schedule", "datasource")

    /** The seven families whose ROOT a promoter's lens decides (record erratum (8)(b)); a datasource has no lens. */
    val LENSED: List<String> = FAMILIES - "datasource"

    fun route(family: String): String = PREFIX + family

    /** The family [pattern] is the route of, or null when it is not a table entry. */
    fun familyOf(pattern: String): String? = pattern.removePrefix(PREFIX).takeIf { pattern.startsWith(PREFIX) && it in FAMILIES }

    /** Every pattern under [PREFIX] the table has no entry for — each a query-only route no walk can address. */
    fun unfixtured(patterns: Collection<String>): List<String> =
        patterns.filter { it.startsWith(PREFIX) && familyOf(it) == null }.distinct().map { "$it -> query-only route without fixtures" }

    /**
     * One family's raw query naming [key] (at [version] for an artifact family). The key is written UNENCODED: every
     * seeded key is plain URI query text (RFC 3986 admits `/` there), and RestAssured would encode a `%` a second time.
     */
    fun query(
        family: String,
        key: String,
        version: Int = 1,
    ): String {
        require(PLAIN_KEY.matches(key)) { "a table key is plain query text, got $key" }
        return when (family) {
            "schedule" -> "id=$key"
            "datasource" -> "name=$key"
            else -> "name=$key&version=$version"
        }
    }

    /** The route and query of [family]'s root [key]. */
    fun path(
        family: String,
        key: String,
        version: Int = 1,
    ): String = "${route(family)}?${query(family, key, version)}"

    /** One seeded root per family: the lookup key each route's query names (an API's is its `/api` spelling). */
    data class Roots(
        val keys: Map<String, String>,
    ) {
        init {
            // Every table entry has a root; a seeded key with no entry is harmless here, because the walk's own rule
            // ([unfixtured]) names a route whose entry was deleted — the failure belongs there, not in the seed.
            require(keys.keys.containsAll(FAMILIES)) { "one root per family route, got ${keys.keys}" }
        }

        fun path(family: String): String = path(family, keys.getValue(family))
    }

    /**
     * Authors one root per family under [prefix] through REST as [session] (a member who may author, release, publish
     * and schedule in [workspaceId]). The H2 [datasource] is registered first through REST by [registrar] — a super
     * admin's session active in the same workspace, which the registration is granted to — because the parameter set's
     * selector is PROVED against it at save, so a row seeded by SQL with a stand-in credential cannot back the closure.
     * The source pipeline is RELEASED with its pinned template — a schedule refuses an unreleased target.
     */
    @Suppress("LongParameterList") // the world one seed is authored in
    fun seed(
        port: Int,
        session: String,
        workspaceId: String,
        prefix: String,
        datasource: String,
        registrar: String,
    ): Roots {
        val body =
            """{"name": "$datasource", "display_name": "Dependency trees", "dialect": "H2",
               "jdbc_url": "jdbc:h2:mem:$datasource;DB_CLOSE_DELAY=-1", "username": "sa", "password": "sa"}"""
        FamilyRootsFixture(port, registrar, workspaceId, datasource).send("POST", "/api/v1/datasources", body).created("datasource")
        val fx = FamilyRootsFixture(port, session, workspaceId, datasource)
        val (board, edges) = fx.dashboardClosure(prefix)
        val set = edges[0].to
        val source = edges[2].to
        val query = edges[3].to
        val chart = edges[4].to
        val pipeline = fx.row(source)
        fx
            .send("POST", "/api/v1/pipelines/${pipeline.uuid}/release?release_pinned_templates=true", "", pipeline.hash)
            .ok("release ${source.name}")
        val endpoint = "/${prefix.replace('/', '_')}/v1/tree"
        fx
            .send("POST", "/api/v1/endpoints", """{"path": "$endpoint", "pipeline": "${source.name}", "pipeline_version": 1}""")
            .created("endpoint")
        val schedule =
            fx
                .send(
                    "POST",
                    "/api/v1/schedules",
                    """{"name": "$prefix/tree_schedule", "payload": {"pipeline": "${source.name}", "version": 1},
                       "cron": "0 3 * * *", "timezone": "UTC"}""",
                ).created("schedule")
                .jsonPath()
                .getString("data.id")
        return Roots(
            mapOf(
                "pipeline" to source.name,
                "template" to query.name,
                "parameter_set" to set.name,
                "visualization" to chart.name,
                "dashboard" to board.name,
                "api_endpoint" to "/api$endpoint",
                "schedule" to schedule,
                "datasource" to datasource,
            ),
        )
    }

    private fun Response.created(what: String): Response = expect(what, CREATED)

    private fun Response.ok(what: String): Response = expect(what, OK)

    private fun Response.expect(
        what: String,
        status: Int,
    ): Response = also { withClue("$what: ${asString().take(EXCERPT)}") { statusCode shouldBe status } }

    private val PLAIN_KEY = Regex("[a-z0-9_./-]+")
    private const val CREATED = 201
    private const val OK = 200
    private const val EXCERPT = 600
}
