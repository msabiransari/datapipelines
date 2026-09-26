package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.DriverManager
import java.util.UUID

/**
 * **The isolated-permission witness** (security-assurance record §7.1, ratified B4; lane 217a, A.3).
 *
 * Every role holds many permissions, so a walk over roles cannot tell a route that declares the
 * RIGHT permission from one that declares another permission the same roles happen to hold: a
 * delete route declaring `pipeline.create` passes every role walk, because every role holding one
 * holds both. The only oracle that separates them is a principal that holds ONE permission and
 * nothing else — which no role is.
 *
 * This suite builds that principal through the seam, never by mocking a decision: the context's
 * [co.datapipelines.auth.PermissionResolver] is replaced by a synthetic grant (a test-sources proxy
 * over the production interface, registered as the context's `@Primary` resolver) that answers,
 * for each witness workspace, exactly one permission — and for every other workspace delegates to
 * the production resolver. The security chain, the `ScopeInterceptor`, the MCP dispatcher, every
 * service and every filter run exactly as shipped. Then, for each witnessed permission, its
 * principal — a member of its own workspace — calls every governed REST/UI route with a session,
 * and must be admitted exactly where auth.md §7.6 PLACES that permission's surfaces and refused ON
 * THE ROLE AXIS everywhere else. The expectation is the doc's placement, never the annotation being
 * tested (record §5: the implementation's decision is not the oracle).
 *
 * The context is dirtied after the class: closing it closes the installation, which puts the
 * production resolver back for every other context in this JVM.
 *
 * Out of the REST walk, and why: public routes (no principal), the promotion receiver family (a
 * route family fenced upstream by `PromotionServerKeyFilter`), and the two TRANSPORT key-role
 * columns (the matrix reads them directly — see `PermissionResolver`'s KDoc). The MCP TOOL leg is
 * back: at the keys-v2 conversion (233b) it was cut, because the matrix then judged a key role
 * straight from [co.datapipelines.auth.RolePermissions] (the `keyRole` short-circuit), so no
 * seedable key was a permission-isolated principal and 169 of the 406 tool-permission pairs —
 * permissions co-granted by every role that holds any of them, measured 2026-09-25 — had no
 * separator. #239 routes an `mcp` key's member-role admission through the seam
 * (`ScopeMatrix.allowed`), and the second test below walks every tool with such a key: the grant,
 * not the role, decides, exactly as it does for the sessions on the routes. Admission is the
 * subject, exactly as in the role walk: a reached handler or tool may answer 404 or 400 on the
 * deliberately absent ids.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // The walk sends ~180 route requests per witness in a few seconds; the §12 per-user limiter (100/s by
    // default) would answer some of them with a 429, which is neither a refusal nor a reach.
    properties = ["datapipelines.rate-limit.requests-per-second=100000", "datapipelines.rate-limit.requests-per-minute=1000000"],
)
@Import(PermissionSeamE2eTest.SeamConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PermissionSeamE2eTest : EntryAssuranceE2eBase() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val doc = RoleMatrixDocE2e.read()
    private val rows = RoleMatrixDocE2e.permissionRows(doc)

    @Test
    fun `a principal holding one permission reaches exactly the routes that declare it - and is refused on every other one`() {
        ensureSeeded()
        val routes = governedRoutes()
        val matrix = mutableListOf<String>()
        val mismatches = mutableListOf<String>()

        WITNESSED.forEach { permission ->
            val principal = principals.getValue(permission)
            val routeAnswers = routes.associateWith { route -> callRoute(route, principal) }
            routeAnswers.filterValues { it.first in UNSCORABLE_STATUSES }.forEach { (route, answer) ->
                mismatches += "$permission UNSCORABLE ${route.method} ${route.pattern}: ${answer.first} ${answer.second.take(EXCERPT)}"
            }
            val reachedRoutes = routeAnswers.filterValues { !it.roleRefusal() }.keys.toList()
            // The oracle is the REVIEWED placement — the routes §7.6 names on this permission's row —
            // never the annotation under test: a route re-declared with another permission would carry
            // its expectation along with it (measured: delete re-declared as create stayed green that way).
            val placed = rows[permission]?.routes.orEmpty().toSet()
            val expectedRoutes = routes.filter { "${it.method} ${EntryDiscovery.canonical(it.pattern)}" in placed }

            (reachedRoutes.toSet() - expectedRoutes.toSet()).forEach {
                mismatches +=
                    "$permission REACHED ${it.method} ${it.pattern} (declares ${it.permission})"
            }
            (expectedRoutes.toSet() - reachedRoutes.toSet()).forEach {
                mismatches +=
                    "$permission REFUSED ${it.method} ${it.pattern} (§7.6 places it on this row; the code declares ${it.permission})"
            }
            matrix += "$permission routes=${expectedRoutes.size}/${reachedRoutes.size} " +
                "refused=${routes.size - reachedRoutes.size}"
        }

        println(matrix.joinToString("\n") { "event=seam.witness $it" })
        mismatches.joinToString("\n") shouldBe ""
        // Non-vacuity: every witnessed permission has at least one route of its own in §7.6.
        WITNESSED.forEach { permission ->
            rows[permission]?.routes.orEmpty().size shouldBeGreaterThanOrEqual 1
        }
        WITNESSED.size shouldBeGreaterThanOrEqual WITNESS_FLOOR
        routes.size shouldBeGreaterThanOrEqual ROUTE_FLOOR
    }

    /**
     * **The tool leg, regained (#239).** An `mcp` key of the AUTHOR role — the strongest member
     * role, so the pre-#239 short-circuit reached every tool its column holds and the red was
     * maximal — seeded in witness workspace 0, whose synthetic grant is [GRANTED_PERMISSION] and
     * nothing else, calls every tool auth.md §7.6 places. It must reach exactly the tools that
     * DECLARE [GRANTED_PERMISSION] and be refused with `auth.role_required` on every other.
     *
     * Red-first: on the keys-v2 base (0ec90695…fa087408), where the matrix read the key role from
     * the table directly, the author key reached 40 of 42 tools — every mismatch a REACHED the
     * grant never got to veto. Since #239 the matrix asks the resolver with the key's workspace,
     * so the grant decides.
     *
     * The inseparable pairs are guarded BY CONSTRUCTION: the doc gives `template.read` exactly
     * the roles `pipeline.read` has (asserted live below, across all ten §7.6 columns), so no
     * role walk can separate a `templates_*` tool from a `pipelines_*` one — only this grant can.
     */
    @Test
    fun `a key holding one permission reaches exactly the tools that declare it - and is refused on every other one`() {
        ensureSeeded()
        ensureWitnessKeySeeded()
        val schemas = toolSchemas()
        val toolPermission = toolPermissions()
        val expectedTools = toolPermission.filterValues { it == GRANTED_PERMISSION }.keys
        val mismatches = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        var reached = 0
        var refused = 0

        toolPermission.keys.forEach { tool ->
            val schema = schemas[tool]
            if (schema == null) {
                unknown += "$tool -> not in tools/list"
                return@forEach
            }
            val body = callTool(tool, WITNESS_KEY.plaintext, synthesise(schema).toString())
            when {
                MCP_UNKNOWN_TOOL.any { body.contains(it) } -> {
                    unknown += "$tool -> ${body.take(EXCERPT)}"
                }

                body.contains("input validation failed") -> {
                    unknown += "$tool -> the synthesised arguments did not satisfy the schema: ${body.take(EXCERPT)}"
                }

                else -> {
                    val wasRefused = body.contains(ROLE_REQUIRED)
                    if (wasRefused) refused++ else reached++
                    if (wasRefused && tool in expectedTools) {
                        mismatches += "$tool REFUSED but §7.6 places it on the $GRANTED_PERMISSION row: ${body.take(EXCERPT)}"
                    }
                    if (!wasRefused && tool !in expectedTools) {
                        mismatches += "$tool REACHED (declares ${toolPermission.getValue(tool)}): ${body.take(EXCERPT)}"
                    }
                }
            }
        }

        println(
            "event=seam.toolwitness permission=$GRANTED_PERMISSION tools=${toolPermission.size} " +
                "expected=${expectedTools.size} reached=$reached refused=$refused",
        )
        unknown.joinToString("\n") shouldBe ""
        mismatches.joinToString("\n") shouldBe ""
        // Non-vacuity: the granted row places tools of its own, the surface is whole, and most of
        // it is refused to a one-permission key.
        expectedTools.size shouldBeGreaterThanOrEqual 1
        refused shouldBeGreaterThanOrEqual TOOL_REFUSED_FLOOR
        toolPermission.size shouldBeGreaterThanOrEqual MINIMUM_TOOLS
        // The inseparable-pair proof, live from the doc: some REFUSED tool's permission is
        // indistinguishable from the granted one across EVERY §7.6 column (member and key-role) —
        // no role walk could refuse that tool while admitting a key of any role holding the
        // granted row. Only the grant just did.
        val inseparableRefused =
            toolPermission
                .filterValues { it != GRANTED_PERMISSION }
                .values
                .filter { other ->
                    COLUMNS.all { column ->
                        rows.getValue(other).allows(column) == rows.getValue(GRANTED_PERMISSION).allows(column)
                    }
                }.toSet()
        inseparableRefused.isNotEmpty() shouldBe true
    }

    @Test
    fun `the context's resolver is the synthetic grant - and every workspace outside the witness is judged by the production table`() {
        val installed =
            Class
                .forName(RESOLUTION)
                .getField("INSTANCE")
                .get(null)
                .let { it.javaClass.getMethod("getResolver").invoke(it) }
        Proxy.isProxyClass(installed.javaClass) shouldBe true
        // A workspace no witness owns: the synthetic grant delegates, so a viewer reads pipelines and deletes none.
        val holds = Class.forName(RESOLVER).getMethod("holds", UUID::class.java, ROLE, Boolean::class.javaPrimitiveType, PERMISSION)
        val viewer = ROLE.enumConstants.first { (it as Enum<*>).name == "VIEWER" }
        holds.invoke(installed, UUID.randomUUID(), viewer, false, permission("pipeline.read")) shouldBe true
        holds.invoke(installed, UUID.randomUUID(), viewer, false, permission("pipeline.delete")) shouldBe false
    }

    // ------------------------------------------------------------------ the synthetic grant

    /**
     * Registers the synthetic grant as the context's `@Primary` resolver; the production bean stays,
     * un-chosen. Imported explicitly: with `@SpringBootTest(classes = …)` a nested `@TestConfiguration`
     * is not picked up on its own (measured — the first run judged every witness by the production
     * table). A class, not an object (detekt's utility-class rule suppressed for it): Spring
     * instantiates a configuration class.
     */
    @TestConfiguration
    @Suppress("UtilityClassWithPublicConstructor")
    class SeamConfiguration {
        companion object {
            @JvmStatic
            @Bean
            fun syntheticGrantRegistrar(): BeanDefinitionRegistryPostProcessor =
                object : BeanDefinitionRegistryPostProcessor {
                    @Suppress("UNCHECKED_CAST")
                    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
                        val definition = RootBeanDefinition(Class.forName(RESOLVER) as Class<Any>) { SyntheticGrantResolver.proxy() }
                        definition.isPrimary = true
                        registry.registerBeanDefinition("syntheticGrantResolver", definition)
                    }

                    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit
                }
        }
    }

    /**
     * `holds(workspaceId, role, superAdmin, permission)` for the witness workspaces: true exactly for
     * the one permission that workspace was granted, whatever the role. Any other workspace (or none)
     * is asked of the production resolver unchanged — so nothing else in this context is judged
     * differently from production.
     */
    object SyntheticGrantResolver : InvocationHandler {
        private val production: Any by lazy { Class.forName(PRODUCTION).getField("INSTANCE").get(null) }

        fun proxy(): Any = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Class.forName(RESOLVER)), this)

        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? =
            when (method.name) {
                "holds" -> {
                    val granted = GRANTS[args!![0] as UUID?]
                    if (granted == null) {
                        method.invoke(production, *args)
                    } else {
                        wireOf(args[3]!!) == granted
                    }
                }

                "equals" -> {
                    proxy === args?.firstOrNull()
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "toString" -> {
                    "SyntheticGrantResolver(${GRANTS.size} witness workspaces)"
                }

                else -> {
                    error("unexpected call ${method.name} on the synthetic grant")
                }
            }
    }

    // ------------------------------------------------------------------ the walk

    private data class Route(
        val method: String,
        val pattern: String,
        val path: String,
        val permission: String,
    )

    private fun governedRoutes(): List<Route> =
        EntryDiscovery
            .handlerMethods(context)
            .filter { !it.public && !it.pattern.startsWith(PROMOTION_RECEIVER_PREFIX) }
            .map {
                Route(
                    it.method,
                    it.pattern,
                    EntryDiscovery.concretePath(it.pattern),
                    requireNotNull(it.permission) { "${it.handler}" },
                )
            }.distinct()

    /**
     * `(status, body)` of [route] as [principal]. Refused means ON THE ROLE AXIS (403
     * `auth.role_required`); a 401 or a 429 is neither refused nor reached and fails the walk as
     * unscorable; anything else (200, 302, 400, 404) is "reached", as in the role walk.
     */
    private fun callRoute(
        route: Route,
        principal: Witness,
    ): Pair<Int, String> {
        val request =
            given()
                .port(port)
                .asSession(principal.session)
                .redirects()
                .follow(false)
                .accept("*/*")
                .contentType(ContentType.JSON)
                .body("{}")
                .`when`()
        val response =
            when (route.method) {
                "POST" -> request.post(route.path)
                "PUT" -> request.put(route.path)
                "PATCH" -> request.patch(route.path)
                "DELETE" -> request.delete(route.path)
                else -> request.get(route.path)
            }.then().extract()
        return response.statusCode() to response.asString()
    }

    private fun Pair<Int, String>.roleRefusal(): Boolean =
        first == HTTP_FORBIDDEN && CODE.find(second)?.groupValues?.get(1) == ROLE_REQUIRED

    // ---------------------------------------------------------- the tool witness (#239)

    /** `tool -> the permission wire of the §7.6 row that places it` — the doc's placement, the oracle. */
    private fun toolPermissions(): Map<String, String> =
        rows.entries.flatMap { (permission, cells) -> cells.tools.map { tool -> tool to permission } }.toMap()

    /**
     * `tool name -> inputSchema`, from the server's own `tools/list`, read with the witness key
     * itself: listing the surface is no admission to call it.
     */
    private fun toolSchemas(): Map<String, JsonNode> {
        val body =
            given()
                .port(port)
                .header(API_KEY_HEADER, WITNESS_KEY.plaintext)
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
                .`when`()
                .post("/mcp")
                .then()
                .extract()
                .asString()
        val json = MAPPER.readTree(body.substringAfter("data:").trim().ifEmpty { body })
        return json.path("result").path("tools").associate { it.path("name").asText() to it.path("inputSchema") }
    }

    private fun callTool(
        tool: String,
        key: String,
        arguments: String,
    ): String =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""")
            .`when`()
            .post("/mcp")
            .then()
            .extract()
            .asString()

    /** A JSON object satisfying [schema]'s `required` list, recursively — the role walk's synthesiser. */
    private fun synthesise(schema: JsonNode): ObjectNode {
        val node = MAPPER.createObjectNode()
        val properties = schema.path("properties")
        schema.path("required").forEach { requiredName ->
            val name = requiredName.asText()
            node.set<JsonNode>(name, valueFor(name, properties.path(name)))
        }
        return node
    }

    private fun valueFor(
        name: String,
        property: JsonNode,
    ): JsonNode {
        val type = property.path("type").let { if (it.isArray) it.first().asText() else it.asText() }
        property.path("enum").takeIf { it.isArray && it.size() > 0 }?.let { return it.first() }
        return when (type) {
            "object" -> synthesise(property)
            "array" -> MAPPER.createArrayNode()
            "integer", "number" -> MAPPER.nodeFactory.numberNode(1)
            "boolean" -> MAPPER.nodeFactory.booleanNode(false)
            else -> MAPPER.nodeFactory.textNode(textFor(name, property))
        }
    }

    /** A well-formed string that names nothing: a UUID, a folder-path name, or a plain token. */
    private fun textFor(
        name: String,
        property: JsonNode,
    ): String =
        when {
            // A name grammar (`templates_update`'s `id` is a template NAME, not a UUID).
            property.has("pattern") -> "nobody/owns_this.sql"

            property.path("format").asText() == "uuid" || name.endsWith("_id") || name == "id" -> EntryDiscovery.ABSENT_UUID

            name in PATH_NAMED_ARGUMENTS -> "nobody/owns_this"

            else -> "nobody-owns-this"
        }

    /**
     * The tool witness's `mcp` key — the role walk's keys-v2 seeding shape, one workspace over:
     * the key acts as its own `service` identity (A13), role `author`, created by the grant
     * workspace's witness person (active, so the A20 creator-liveness arm never fires).
     */
    private fun ensureWitnessKeySeeded() {
        if (toolKeySeeded) return
        toolKeySeeded = true
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { s ->
                s.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                        "('$WITNESS_KEY_IDENTITY', '${WITNESS_KEY.id.lowercase()}@keys.invalid', '${WITNESS_KEY.name}', 'key', " +
                        "'${WITNESS_KEY.id}', TRUE, FALSE, 'service')",
                )
            }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                        " VALUES (?, ?, ?, ?, ?, ?, 'mcp', 'author')",
                ).use { ps ->
                    ps.setString(1, WITNESS_KEY.id)
                    ps.setObject(2, UUID.fromString(WITNESS_KEY_IDENTITY))
                    ps.setObject(3, UUID.fromString(userId(0)))
                    ps.setString(4, WITNESS_KEY.name)
                    ps.setString(5, WITNESS_KEY.hash)
                    ps.setObject(6, UUID.fromString(workspaceId(0)))
                    ps.execute()
                }
        }
    }

    // ------------------------------------------------------------------ the witnesses

    /** One witnessed permission's principal: a viewer member of its own workspace, with a session. */
    private data class Witness(
        val session: String,
    )

    private val principals: Map<String, Witness> by lazy {
        WITNESSED.associateWith { permission ->
            val index = WITNESSED.indexOf(permission)
            Witness(
                session = E2eSession.jwt(jwtSecret, userId(index), email(index), workspace = workspaceName(index)),
            )
        }
    }

    private fun ensureSeeded() {
        if (seeded) return
        seeded = true
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            WITNESSED.forEachIndexed { index, permission ->
                connection.createStatement().use { s ->
                    s.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('${workspaceId(
                            index,
                        )}', '${workspaceName(index)}', '$permission')",
                    )
                    s.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                            "('${userId(index)}', '${email(index)}', 'Seam $index', 'test', 'seam-$index', TRUE, FALSE)",
                    )
                    // The DB role is irrelevant inside a witness workspace — the synthetic grant ignores it — so the least one.
                    s.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('${workspaceId(
                            index,
                        )}', '${userId(index)}', 'viewer')",
                    )
                }
            }
        }
    }

    private fun permission(wire: String): Any = PERMISSION.enumConstants.first { wireOf(it) == wire }

    companion object {
        private const val RESOLVER = "co.datapipelines.auth.PermissionResolver"
        private const val PRODUCTION = "co.datapipelines.auth.RolePermissionsResolver"
        private const val RESOLUTION = "co.datapipelines.auth.PermissionResolution"
        private val ROLE: Class<*> = Class.forName("co.datapipelines.auth.WorkspaceRole")
        private val PERMISSION: Class<*> = Class.forName("co.datapipelines.auth.Permission")

        /**
         * The witnessed permissions — chosen across families, and in co-granted pairs a role walk cannot
         * separate (create/delete/update, read/execute, release/promote, read/cancel), plus instance-,
         * key-, member- and profile-scoped rows. Fourteen; the record's floor is ten. `api_key.create`
         * was witnessed until keys v2 moved the create routes onto `mcp_key.create`'s row and left
         * `api_key.create` a service-CHECK claim with "No route of its own" (auth.md §7.6) — a
         * permission with no surface proves nothing, so the slot moved with the routes.
         */
        val WITNESSED =
            listOf(
                "pipeline.read",
                "pipeline.create",
                "pipeline.delete",
                "pipeline.execute",
                "template.update",
                "template.release",
                "execution.read",
                "execution.cancel",
                "endpoint.publish",
                "promotion.promote",
                "mcp_key.create",
                "workspace.members.manage",
                "datasource.manage",
                "profile.password",
            )

        /** `workspace id -> the one permission granted there`, read by the synthetic grant. */
        private val GRANTS: Map<UUID, String> =
            WITNESSED.withIndex().associate { (index, wire) ->
                UUID.fromString(workspaceId(index)) to
                    wire
            }

        private fun workspaceId(index: Int) = "5e5e0000-0000-0000-0000-%012d".format(index + WORKSPACE_ID_BASE)

        private fun userId(index: Int) = "5e5e1000-0000-0000-0000-%012d".format(index + WORKSPACE_ID_BASE)

        private fun workspaceName(index: Int) = "seam-witness-$index"

        private fun email(index: Int) = "seam-$index@witness.test"

        private fun wireOf(permission: Any): String = permission.javaClass.getMethod("getWire").invoke(permission) as String

        private const val WORKSPACE_ID_BASE = 217_000
        private const val WITNESS_FLOOR = 10
        private const val ROUTE_FLOOR = 150
        private const val HTTP_FORBIDDEN = 403
        private val UNSCORABLE_STATUSES = setOf(401, 429)

        private const val EXCERPT = 240
        private const val ROLE_REQUIRED = "auth.role_required"
        private const val PROMOTION_RECEIVER_PREFIX = "/api/v1/promotion/"
        private val CODE = Regex("\"code\"\\s*:\\s*\"([a-z_.]+)\"")

        // ---------------------------------------------------------------- the tool witness (#239)

        /**
         * Per-CLASS seeding flags, in the companion like the role walk's: JUnit builds a fresh
         * instance per test method, and two seeding tests sharing one set of fixed-UUID rows must
         * not both INSERT them (measured: the second instance's workspaces_pkey collision).
         */
        private var seeded = false
        private var toolKeySeeded = false

        /** The permission the tool witness's workspace is granted — `WITNESSED[0]`, so its grant is live. */
        private const val GRANTED_PERMISSION = "pipeline.read"

        /** The witness key's own `service` identity — the seam test's own UUID block, disjoint from every suite's. */
        private const val WITNESS_KEY_IDENTITY = "5e5e2000-0000-0000-0000-000000239000"

        private val WITNESS_KEY = E2eAuth.generateKey("seam-witness-mcp", ownerId = WITNESS_KEY_IDENTITY)

        /** All ten §7.6 columns, for the inseparability check: member roles, then the key roles. */
        private val COLUMNS = RoleMatrixDocE2e.ROLE_COLUMNS + RoleMatrixDocE2e.KEY_ROLE_COLUMNS

        private val PATH_NAMED_ARGUMENTS = setOf("name", "datasource", "path", "id", "pipeline")
        private val MCP_UNKNOWN_TOOL = listOf("Unknown tool", "tool_not_found", "Tool not found")
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val MINIMUM_TOOLS = 42
        private const val TOOL_REFUSED_FLOOR = 20
        private val MAPPER = ObjectMapper()
    }
}
