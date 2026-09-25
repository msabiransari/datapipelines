package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.AntPathMatcher
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * **The role walk — gate 4 of the roles design (§4), and gates 1 and 3 at runtime.**
 *
 * For each of the five roles, EVERY REST route the running application registers (read off
 * Spring's own `RequestMappingHandlerMapping`, so a route added tomorrow is walked without
 * anybody remembering to add it) is called with that role's SESSION, and EVERY MCP tool in
 * auth.md §7.6 with that role's MCP KEY, and the answer is asserted allowed or refused **exactly
 * as the §7.6 permission catalog says** for the permission the route or tool declares (#215) —
 * the catalog is parsed ([RoleMatrixDocE2e]), so the doc IS the expectation and a cell flipped in
 * the doc fails here against the live server. An MCP key is judged by its member's role CAPPED
 * AT AUTHOR, and a super admin with no membership as a viewer (record PK4) — the walk scores
 * each key against the column that cap names.
 *
 * ## The keys over REST (#215 B2, record §3.1)
 * Every key kind is walked over every REST route too: the MCP key is refused on ALL of them
 * (`endpoint.key_kind_refused`, `user_key_off_surface`); an `api_caller` key reaches only the
 * published-endpoint surface and the two reads of its own executions; a `promotion_receiver` key
 * presented as `DP-API-Key` reaches none (its surface is the promotion family, walked by
 * `PromotionTwoDeploymentE2eTest`).
 *
 * ## What "allowed" and "refused" mean here
 * The subject is the INTERCEPTOR's decision, not the handler's: a refused role gets 403
 * `auth.role_required` before any handler runs; an admitted role reaches the handler — which,
 * given the walk's deliberately non-existent identifiers and empty bodies, answers 404, 400,
 * 302 or 200, all of which are "reached". So the walk needs no seeded entities and mutates
 * nothing: every write it is admitted to fails binding or finds no row. (A route whose handler
 * would mutate on an EMPTY body is a route worth knowing about, and would show up here as a
 * changed fixture on the next run.)
 *
 * ## Gates 1 and 3, as this suite carries them
 * - A non-public handler with NO `@RequiredScope` fails by name — the runtime half of gate 1
 *   over every module the application registers a controller from (`modules/app`'s health
 *   controller is public and is asserted SEEN, so the walk provably reaches past `web`).
 * - Every catalog row that places a route is claimed by ≥ 1 walked handler, and every handler's
 *   permission has a catalog row (gate 3, the REST half); every doc MCP tool answers as a known
 *   tool (the MCP half — an unknown tool is a row nothing implements).
 *
 * ## Non-vacuity
 * The route count, and per role the allowed AND refused counts, are printed
 * (`event=rolewalk.inventory …`) and floored: a walk that matched nothing, or a classifier that
 * called everything "reached", cannot pass.
 *
 * ## Deliberately skipped, each for a reason about the ROUTE
 * - public routes (no principal, no role to judge);
 * - the promotion RECEIVER family (every route under the `/api/v1/promotion/` prefix) — its authority is a route family
 *   enforced upstream by `PromotionServerKeyFilter` (auth.md §7.7), which refuses a session
 *   before the matrix is asked; the matrix rows those handlers declare are the second gate.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class RoleWalkE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val doc = RoleMatrixDocE2e.read()
    private val permissionRows = RoleMatrixDocE2e.permissionRows(doc)
    private val mcpRows = RoleMatrixDocE2e.mcpRows(doc)

    @Test
    fun `every REST route answers each role exactly as auth-md §7-6 says`() {
        ensureSeeded()
        val routes = walkableRoutes()
        val tallies = ROLES.associateWith { role -> walkRest(role, routes) }
        val counts = tallies.mapValues { (_, tally) -> tally.allowed to tally.refused }

        println("event=rolewalk.inventory routes=${routes.size} ${counts.summary()}")
        // Joined into ONE string: a collection assertion prints its first element and elides the
        // rest, and the point of a walk's failure is the LIST.
        tallies.values.flatMap { it.mismatches }.joinToString("\n") shouldBe ""

        // Non-vacuity floors, per role and both directions.
        routes.size shouldBeGreaterThanOrEqual MINIMUM_ROUTES
        counts.getValue("viewer").second shouldBeGreaterThanOrEqual VIEWER_REFUSED_FLOOR
        counts.getValue("author").second shouldBeGreaterThanOrEqual AUTHOR_REFUSED_FLOOR
        counts.getValue("promoter").second shouldBeGreaterThanOrEqual PROMOTER_REFUSED_FLOOR
        counts.getValue("workspace_admin").second shouldBeGreaterThanOrEqual WS_ADMIN_REFUSED_FLOOR
        counts.getValue("super_admin").second shouldBe 0
        ROLES.forEach { counts.getValue(it).first shouldBeGreaterThanOrEqual ALLOWED_FLOOR }
    }

    /**
     * The MCP SDK validates a call's arguments against the tool's input schema BEFORE our
     * dispatcher — and therefore before the role gate — so `{}` would make every tool with a
     * required argument answer "validation failed" to every role and the walk would learn
     * nothing. The arguments are SYNTHESISED from each tool's own schema (`tools/list`): every
     * required property gets a well-formed value that exists nowhere, so an admitted role
     * reaches the tool and finds no row, and a refused role meets the gate.
     *
     * Keys v2 (A13/A14, #233): the walk seeds an `mcp` key of EACH member role through the
     * service and scores every tool against that role's §7.6 column — no cap, no derivation,
     * a viewer key does not exist, and refusals answer `auth.role_required` (the key is judged
     * by its own role).
     */
    @Test
    fun `every MCP tool answers each role's key exactly as auth-md §7-6 says`() {
        ensureSeeded()
        ensureKeysLive()
        val schemas = toolSchemas()
        val tallies = MCP_KEY_ROLES.associateWith { role -> walkMcp(role, schemas) }
        val counts = tallies.mapValues { (_, tally) -> tally.allowed to tally.refused }

        println("event=rolewalk.mcp tools=${mcpRows.size} ${counts.summary()}")
        tallies.values.flatMap { it.unknown }.joinToString("\n") shouldBe ""
        tallies.values.flatMap { it.mismatches }.joinToString("\n") shouldBe ""

        mcpRows.size shouldBeGreaterThanOrEqual MINIMUM_TOOLS
        // #215 — the five cells the catalog moved (record §2.3: the row-data tools are author and
        // above). Asserted by name, with the CODE: keys v2 judges a key by its OWN role, so the
        // refusal is `auth.role_required` (the issuer-demotion code is gone with the derivation).
        val moved = MOVED_MCP_CELLS.map { (role, tool) -> "$role $tool -> ${tallies.getValue(role).codes[tool]}" }
        moved shouldBe MOVED_MCP_CELLS.map { (role, tool) -> "$role $tool -> $ROLE_REQUIRED" }
        // A13: the author- and workspace-admin-role keys reach EVERY tool (the author column
        // admits all 42); only the promoter's key is refused — its column's ✗ rows.
        counts.getValue("author").second shouldBe 0
        counts.getValue("workspace_admin").second shouldBe 0
        counts.getValue("promoter").second shouldBeGreaterThanOrEqual MCP_PROMOTER_REFUSED_FLOOR
    }

    @Test
    fun `every REST route refuses each key kind off its surface - the MCP key on all of them`() {
        ensureSeeded()
        ensureKeysLive()
        val routes = walkableRoutes()
        val mismatches = mutableListOf<String>()
        val refusedByKind = mutableMapOf<String, Int>()
        val walked =
            MCP_KEY_ROLES.associate { "mcp:$it" to (keyFor(it) to MCP_KEY_OFF_SURFACE) } +
                mapOf(
                    "api_caller" to (API_CALLER_KEY.plaintext to ENDPOINT_KEY_OFF_SURFACE),
                    "promotion_receiver" to (RECEIVER_KEY.plaintext to SERVER_KEY_OFF_SURFACE),
                )
        walked.forEach { (name, keyAndReason) ->
            refusedByKind[name] = walkKeyOverRest(name, keyAndReason.first, keyAndReason.second, routes, mismatches)
        }
        println("event=rolewalk.keys routes=${routes.size} refused=$refusedByKind")
        mismatches.joinToString("\n") shouldBe ""
        // Non-vacuity: every MCP key refused on EVERY route; the api_caller reached its
        // published tree (catch-all GET + its paging reads ride it).
        MCP_KEY_ROLES.forEach { refusedByKind.getValue("mcp:$it") shouldBe routes.size }
        refusedByKind.getValue("promotion_receiver") shouldBe routes.size
        (routes.size - refusedByKind.getValue("api_caller")) shouldBeGreaterThanOrEqual API_CALLER_REACHED_FLOOR
    }

    /** One key over every route: how many refused it by KIND; an answer that disagrees with the key's surface is a mismatch. */
    private fun walkKeyOverRest(
        name: String,
        key: String,
        reason: String,
        routes: List<Route>,
        mismatches: MutableList<String>,
    ): Int =
        routes.count { route ->
            // `*/*`: a route that PRODUCES only text/plain (`/partials/mcp-key/secret`) refuses an
            // `application/json` request during handler MAPPING — before any interceptor runs —
            // which would measure content negotiation, not the key's kind.
            val answer = call(route, accept = "*/*") { spec -> spec.header(API_KEY_HEADER, key) }
            val kindRefused = answer.status == HTTP_FORBIDDEN && answer.code == KEY_KIND_REFUSED && answer.reason == reason
            val onSurface = name == "api_caller" && apiCallerSurface(route)
            if (kindRefused == onSurface) {
                mismatches += "$name ${route.method} ${route.pattern} -> ${answer.status} ${answer.code ?: ""} " +
                    "but the key is ${if (onSurface) "ON" else "OFF"} its surface"
            }
            kindRefused
        }

    /**
     * auth.md §7.7's `endpoint` row, restated (keys v2 A16): the published tree ONLY — its
     * result-paging routes ride the same catch-all, and the framework's
     * `/api/v1/executions/…` reads are off the surface entirely.
     */
    private fun apiCallerSurface(route: Route): Boolean = route.handler.startsWith("PublishedEndpointController#")

    /** One role's walk: how many answers were allowed / refused, and every answer the doc did not predict. */
    private class Tally {
        var allowed = 0
        var refused = 0
        val mismatches = mutableListOf<String>()
        val unknown = mutableListOf<String>()

        /** MCP: the refusal code each tool answered with (null = admitted). */
        val codes = mutableMapOf<String, String?>()

        fun record(wasRefused: Boolean) = if (wasRefused) refused++ else allowed++
    }

    private fun Map<String, Pair<Int, Int>>.summary(): String =
        entries.joinToString(" ") { "${it.key}=allowed:${it.value.first}/refused:${it.value.second}" }

    private fun walkRest(
        role: String,
        routes: List<Route>,
    ): Tally {
        val tally = Tally()
        routes.forEach { route ->
            val expectAllowed = permissionRows.getValue(route.permission).allows(role)
            val answer = call(route, authenticate = sessionFor(role))
            tally.record(answer.roleRefused)
            if (answer.roleRefused == expectAllowed) {
                tally.mismatches +=
                    "$role ${route.method} ${route.pattern} (${route.handler}, ${route.permission}) -> " +
                    "${answer.status} ${answer.code ?: ""} but §7.6 says ${if (expectAllowed) "allowed" else "refused"}"
            }
        }
        return tally
    }

    private fun walkMcp(
        role: String,
        schemas: Map<String, JsonNode>,
    ): Tally {
        val tally = Tally()
        mcpRows.forEach { (tool, cells) ->
            val schema = schemas[tool]
            if (schema == null) {
                tally.unknown += "$tool -> not in tools/list"
                return@forEach
            }
            val body = callTool(tool, keyFor(role), synthesise(schema).toString())
            val problem = unwalkable(body)
            if (problem != null) {
                tally.unknown += "$tool -> $problem"
                return@forEach
            }
            val wasRefused = MCP_ROLE_REFUSALS.any { body.contains(it) }
            tally.codes[tool] = MCP_ROLE_REFUSALS.firstOrNull { body.contains(it) }
            tally.record(wasRefused)
            val column = role // keys v2: the key IS its role — the member column of the same name
            if (wasRefused == cells.allows(column)) {
                tally.mismatches +=
                    "$role $tool -> ${body.take(LEAK_EXCERPT)} but §7.6 says ${if (cells.allows(column)) "allowed" else "refused"} " +
                    "for the $column key-role column (A13)"
            }
        }
        return tally
    }

    /** An answer the walk cannot score: the tool does not exist, or the synthesised arguments never reached it. */
    private fun unwalkable(body: String): String? =
        when {
            MCP_UNKNOWN_TOOL.any { body.contains(it) } -> {
                body.take(LEAK_EXCERPT)
            }

            body.contains("input validation failed") -> {
                "the synthesised arguments did not satisfy the schema: ${body.take(LEAK_EXCERPT)}"
            }

            else -> {
                null
            }
        }

    /**
     * Gate 1 (runtime) and gate 3: the handlers the application registers, classified. An
     * unannotated non-public handler and a permission with no catalog row fail BY NAME; every
     * catalog row that places a route is claimed by at least one handler (a row that places only
     * tools, or nothing — the service-checked `_all` and server-key rows — is claimed elsewhere,
     * and `MatrixRowReachabilityTest` counts those claims).
     */
    @Test
    fun `every non-public handler declares a permission the doc has a row for, and every routed row is claimed`() {
        val handlers = handlerMappings()
        val unannotated = handlers.filter { !it.public && it.permission == null }.map { "${it.handler} ${it.method} ${it.pattern}" }
        val undocumented =
            handlers.mapNotNull { it.permission }.distinct().filter { it !in permissionRows.keys }
        val routedRows = permissionRows.filterValues { it.routes.isNotEmpty() }.keys
        val unclaimed = routedRows - handlers.mapNotNull { it.permission }.toSet()

        withClue("non-public handlers without @RequiredScope (the default-deny would refuse them at runtime)") {
            unannotated.shouldBeEmpty()
        }
        withClue("permissions declared by a handler but absent from the auth.md §7.6 catalog") { undocumented.shouldBeEmpty() }
        withClue("auth.md §7.6 rows that place routes no handler declares — a row nothing uses is a lie in the doc") {
            unclaimed.shouldBeEmpty()
        }

        // The walk reaches past `web`: the health controller lives in `modules/app`.
        handlers.map { it.handler } shouldContainAll listOf("HealthController#health", "HealthController#ready")
        handlers.size shouldBeGreaterThanOrEqual MINIMUM_HANDLERS
    }

    // ------------------------------------------------------------------ the walk

    private data class Route(
        val method: String,
        val pattern: String,
        val path: String,
        val handler: String,
        val permission: String,
    )

    private data class Handler(
        val method: String,
        val pattern: String,
        val handler: String,
        val permission: String?,
        val public: Boolean,
    )

    private data class Answer(
        val status: Int,
        val code: String?,
        val reason: String? = null,
    ) {
        val roleRefused: Boolean get() = status == HTTP_FORBIDDEN && code == ROLE_REQUIRED
    }

    private fun walkableRoutes(): List<Route> =
        handlerMappings()
            .filter { !it.public }
            .filterNot { it.pattern.startsWith(PROMOTION_RECEIVER_PREFIX) }
            .map { h ->
                Route(
                    h.method,
                    h.pattern,
                    substitute(h.pattern),
                    h.handler,
                    requireNotNull(h.permission) { "${h.handler} has no permission" },
                )
            }.distinct()
            .sortedWith(compareBy({ it.pattern }, { it.method }))

    /**
     * `RequestMappingHandlerMapping.getHandlerMethods()`, read by reflection: spring-webmvc is
     * not on this module's compile classpath (module-structure §4.2 gives it `:modules:app`
     * alone), and the information is the same either way.
     */
    private fun handlerMappings(): List<Handler> {
        val mapping = context.getBean("requestMappingHandlerMapping")
        val methods = mapping.javaClass.getMethod("getHandlerMethods").invoke(mapping) as Map<*, *>
        return methods.entries.flatMap { (info, handlerMethod) ->
            val patterns = patternsOf(info!!)
            val verbs = verbsOf(info)
            val method = handlerMethod!!.javaClass.getMethod("getMethod").invoke(handlerMethod) as java.lang.reflect.Method
            val beanType = handlerMethod.javaClass.getMethod("getBeanType").invoke(handlerMethod) as Class<*>
            val permission = requiredScopeOf(method) ?: requiredScopeOf(beanType)
            patterns.flatMap { pattern ->
                verbs.map { verb ->
                    Handler(verb, pattern, "${beanType.simpleName}#${method.name}", permission, isPublic(pattern))
                }
            }
        }
    }

    private fun patternsOf(info: Any): Set<String> {
        val condition = info.javaClass.getMethod("getPathPatternsCondition").invoke(info)
        @Suppress("UNCHECKED_CAST")
        return condition.javaClass.getMethod("getPatternValues").invoke(condition) as Set<String>
    }

    private fun verbsOf(info: Any): Set<String> {
        val condition = info.javaClass.getMethod("getMethodsCondition").invoke(info)
        val methods = condition.javaClass.getMethod("getMethods").invoke(condition) as Set<*>
        return methods.map { (it as Enum<*>).name }.toSet().ifEmpty { setOf("GET") }
    }

    /** The declared permission's WIRE name (`pipeline.read`) — the catalog's first column — read by reflection. */
    private fun requiredScopeOf(element: java.lang.reflect.AnnotatedElement): String? =
        element.annotations
            .firstOrNull { it.annotationClass.simpleName == "RequiredScope" }
            ?.let { annotation ->
                val permission = annotation.javaClass.getMethod("value").invoke(annotation)
                permission.javaClass.getMethod("getWire").invoke(permission) as String
            }

    /** The runtime's own allowlist (`PublicPaths.PATTERNS`), read by reflection, matched Ant-style as the interceptor matches it. */
    private fun isPublic(pattern: String): Boolean {
        val concrete = substitute(pattern)
        return publicPatterns.any { antMatcher.match(it, concrete) } || pattern == "/"
    }

    private val publicPatterns: List<String> by lazy {
        val type = Class.forName("co.datapipelines.auth.PublicPaths")
        val instance = type.getField("INSTANCE").get(null)
        @Suppress("UNCHECKED_CAST")
        type.getMethod("getPATTERNS").invoke(instance) as List<String>
    }

    private val antMatcher = AntPathMatcher()

    /** Well-formed identifiers that exist NOWHERE — an admitted role reaches the handler and finds no row. */
    private fun substitute(pattern: String): String =
        VARIABLE_PATTERN
            .replace(pattern) { match ->
                val variable = match.groupValues[1].substringBefore(':')
                ABSENT_VALUES[variable] ?: if (variable.lowercase().endsWith("id")) ABSENT_UUID else "nobody-owns-this"
            }.replace("/**", "/x")
            .replace("*", "x")

    private fun call(
        route: Route,
        accept: String = "application/json",
        authenticate: (RequestSpecification) -> RequestSpecification,
    ): Answer {
        val spec =
            authenticate(given().port(port))
                .redirects()
                .follow(false)
                .cookie(CSRF_COOKIE, CSRF)
                .header(CSRF_HEADER, CSRF)
                .accept(accept)
                .contentType(ContentType.JSON)
                .body("{}")
        val request = spec.`when`()
        val response =
            when (route.method) {
                "GET" -> request.get(route.path)
                "POST" -> request.post(route.path)
                "PUT" -> request.put(route.path)
                "PATCH" -> request.patch(route.path)
                "DELETE" -> request.delete(route.path)
                else -> request.get(route.path)
            }.then().extract()
        val body = response.asString()
        val code = CODE.find(body)?.groupValues?.get(1)
        return Answer(response.statusCode(), code, REASON.find(body)?.groupValues?.get(1))
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

    /**
     * `tool name -> inputSchema`, from the server's own `tools/list` — read with the workspace
     * admin's key, an author's (PK4), which every tool admits.
     */
    private fun toolSchemas(): Map<String, JsonNode> {
        val body =
            given()
                .port(port)
                .header(API_KEY_HEADER, keyFor("workspace_admin"))
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

    /**
     * A JSON object satisfying [schema]'s `required` list, recursively: a UUID where the format
     * says so, an enum's first value, a legal-looking name otherwise, 1 for numbers, `{}` / `[]`
     * for objects and arrays. Nothing here exists in the fixture's workspace, by construction.
     */
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

    /** A well-formed string that names nothing in the fixture: a UUID, a folder-path name, or a plain token. */
    private fun textFor(
        name: String,
        property: JsonNode,
    ): String =
        when {
            // A name grammar (`templates_update`'s `id` is a template NAME, not a UUID).
            property.has("pattern") -> "nobody/owns_this.sql"

            property.path("format").asText() == "uuid" || name.endsWith("_id") || name == "id" -> ABSENT_UUID

            name in PATH_NAMED_ARGUMENTS -> "nobody/owns_this"

            else -> "nobody-owns-this"
        }

    private fun sessionFor(role: String): (RequestSpecification) -> RequestSpecification =
        { spec -> spec.cookie(SESSION_COOKIE, sessionJwt(USERS.getValue(role), "$role@rolewalk.test")) }

    private fun keyFor(role: String): String = MCP_KEYS.getValue(role).plaintext

    companion object {
        private const val SESSION_COOKIE = "dp_session"
        private const val CSRF_COOKIE = "dp_csrf"
        private const val CSRF_HEADER = "DP-CSRF-Token"
        private const val CSRF = "rolewalk-csrf"
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val SECRET_BYTES = 32
        private const val HTTP_FORBIDDEN = 403
        private const val LEAK_EXCERPT = 240
        private const val ROLE_REQUIRED = "auth.role_required"
        private const val PROMOTION_RECEIVER_PREFIX = "/api/v1/promotion/"
        private const val ABSENT_UUID = "0d0e0000-0000-0000-0000-0000000000ff"
        private val MAPPER = ObjectMapper()

        private const val KEY_KIND_REFUSED = "endpoint.key_kind_refused"
        private const val MCP_KEY_OFF_SURFACE = "mcp_key_off_surface"
        private const val ENDPOINT_KEY_OFF_SURFACE = "endpoint_key_off_surface"
        private const val SERVER_KEY_OFF_SURFACE = "server_key_off_surface"

        /** Keys v2 A13/A14: the MEMBER roles an `mcp` key may carry — the walk seeds one of each. */
        private val MCP_KEY_ROLES = listOf("author", "promoter", "workspace_admin")

        /** A key's refusal on the role axis: `auth.role_required` — the key is judged by its own role (A13). */
        private val MCP_ROLE_REFUSALS = listOf(ROLE_REQUIRED)

        /**
         * #215 — the MCP cells the catalog moves to author-and-above (record §2.3): the row-data
         * tools. Keys v2: the walk's keys start at author (A15 — no viewer key exists), so the
         * named cells are the PROMOTER key's refusals, answered `auth.role_required` (A13).
         */
        private val MOVED_MCP_CELLS =
            listOf(
                "promoter" to "datasources_preview_rows",
                "promoter" to "sql_probe",
            )
        private val MCP_UNKNOWN_TOOL = listOf("Unknown tool", "tool_not_found", "Tool not found")
        private val PATH_NAMED_ARGUMENTS = setOf("name", "datasource", "path", "id", "pipeline")

        /**
         * Floors, not targets. The 2026-09-20 walk: 175 routes — viewer allowed 73 / refused 102,
         * author 127 / 48, promoter 58 / 117, workspace admin 150 / 25, super admin 175 / 0; 42
         * tools — viewer 28 / 13, author 42 / 0, promoter 20 / 21, admins 42 / 0. Measured on the
         * #215 lane (2026-09-24), before the catalog (091df07b) and after it: 185 routes — viewer
         * 75 / 110, author 128 / 57, promoter 60 / 125, workspace admin 159 / 26, super admin
         * 185 / 0, IDENTICAL before and after; 42 tools — viewer 28 / 14 → 25 / 17, promoter
         * 20 / 22 → 18 / 24 (the five cells named in [MOVED_MCP_CELLS]), author and admins 42 / 0.
         * Slice (b) (2026-09-24): REST identical (185 routes, the same five pairs); MCP — the keys are
         * scored against the PK4-capped column, so the workspace admin stays 42 / 0 (an author) and
         * the membership-less super admin moves 42 / 0 → 25 / 17 (a viewer, B1); keys over REST —
         * every member's MCP key refused on 185 / 185, `promotion_receiver` 185 / 185, `api_caller`
         * 181 (it reaches the published catch-all and its runs' three execution routes).
         * A walk well under these numbers is a broken scan, not a leaner app.
         */
        private const val MINIMUM_ROUTES = 60
        private const val MINIMUM_HANDLERS = 100
        private const val MINIMUM_TOOLS = 42
        private const val ALLOWED_FLOOR = 20
        private const val VIEWER_REFUSED_FLOOR = 40
        private const val AUTHOR_REFUSED_FLOOR = 15
        private const val PROMOTER_REFUSED_FLOOR = 60
        private const val WS_ADMIN_REFUSED_FLOOR = 8
        private const val MCP_VIEWER_REFUSED_FLOOR = 15
        private const val MCP_PROMOTER_REFUSED_FLOOR = 22

        /**
         * The api_caller's surface among the walked routes (keys v2 A16): the published
         * catch-all alone — serve AND its result-paging rides the one mapping, so one walked
         * route carries all of it.
         */
        private const val API_CALLER_REACHED_FLOOR = 1

        private val VARIABLE_PATTERN = Regex("\\{([^}]+)\\}")
        private val CODE = Regex("\"code\"\\s*:\\s*\"([a-z_.]+)\"")
        private val REASON = Regex("\"reason\"\\s*:\\s*\"([a-z_]+)\"")

        /** Identifiers by the variable NAME a route uses — the same table the isolation sweep keys on. */
        private val ABSENT_VALUES: Map<String, String> =
            mapOf(
                "id" to ABSENT_UUID,
                "pipelineId" to ABSENT_UUID,
                "executionId" to ABSENT_UUID,
                "userId" to ABSENT_UUID,
                "name" to "nobody_owns_this",
                "templateName" to "nobody_owns_this",
                "version" to "1",
                "workspace" to "no-such-workspace",
                "email" to "nobody@nowhere.test",
                "kind" to "welcome",
                "action" to "activate",
                "ns" to "nobody",
                "t" to "nobody",
                "slug" to "nobody",
                "engine" to "postgres",
            )

        val ROLES = RoleMatrixDocE2e.ROLE_COLUMNS

        private const val WS_ID = "abc00000-0000-0000-0000-000000000177"
        private const val WS_NAME = "rolewalk"
        private val USERS: Map<String, String> =
            mapOf(
                "viewer" to "0a000000-0000-0000-0000-000000000177",
                "author" to "0b000000-0000-0000-0000-000000000177",
                "promoter" to "0c000000-0000-0000-0000-000000000177",
                "workspace_admin" to "0d000000-0000-0000-0000-000000000177",
                // D7: NO membership row — the implicit super admin, resolving the workspace through
                // `superAdminOver`, audited `acting_via=super_admin`.
                "super_admin" to "0e000000-0000-0000-0000-000000000177",
            )
        /**
         * Keys v2 (A13/A14): the walk's MCP keys — one per MEMBER role, each acting as its own
         * `service` identity, created by the workspace admin through the service's creation
         * contract. No viewer key, no super-admin key, no login mint (A15).
         */
        private const val MCP_AUTHOR_IDENTITY = "5e000000-0000-0000-0000-000000000181"
        private const val MCP_PROMOTER_IDENTITY = "5e000000-0000-0000-0000-000000000182"
        private const val MCP_WSADMIN_IDENTITY = "5e000000-0000-0000-0000-000000000183"
        private val MCP_KEYS: Map<String, E2eAuth.SeededKey> =
            mapOf(
                "author" to E2eAuth.generateKey("rolewalk-mcp-author", ownerId = MCP_AUTHOR_IDENTITY),
                "promoter" to E2eAuth.generateKey("rolewalk-mcp-promoter", ownerId = MCP_PROMOTER_IDENTITY),
                "workspace_admin" to E2eAuth.generateKey("rolewalk-mcp-admin", ownerId = MCP_WSADMIN_IDENTITY),
            )

        /** The two transport key roles (record §3.2), each acting as its own `service` identity. */
        private const val API_CALLER_IDENTITY = "5e000000-0000-0000-0000-000000000177"
        private const val RECEIVER_IDENTITY = "5e000000-0000-0000-0000-000000000178"
        private val API_CALLER_KEY = E2eAuth.generateKey("rolewalk-caller", ownerId = API_CALLER_IDENTITY)
        private val RECEIVER_KEY = E2eAuth.generateKey("rolewalk-receiver", ownerId = RECEIVER_IDENTITY)

        private val random = SecureRandom()
        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private fun sessionJwt(
            userId: String,
            email: String,
        ): String {
            val now = Instant.now()
            val header = b64("""{"alg":"HS256","typ":"JWT"}""")
            val payload =
                b64(
                    """{"sub":"$userId","email":"$email","name":"Role Walk",""" +
                        """"iss":"datapipelines","iat":${now.epochSecond},"exp":${now.plusSeconds(3600).epochSecond},""" +
                        """"active_workspace":"$WS_NAME"}""",
                )
            val signature =
                Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(Base64.getDecoder().decode(jwtSecret), "HmacSHA256"))
                    b64(doFinal("$header.$payload".toByteArray(Charsets.UTF_8)))
                }
            return "$header.$payload.$signature"
        }

        private fun b64(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun b64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

        private var seeded = false

        /**
         * 179: the REST walk drives `DELETE /partials/mcp-key` (the top bar's
         * delete-to-rotate, `VIEW_OWN_MCP_KEY` — every role), which REVOKES the walked
         * user's seeded key. When the REST walk runs before the MCP walk, those keys must
         * be un-revoked first; V31's unique index is untouched (no second key was ever
         * minted — minting happens at login, and the walk never logs in).
         */
        fun ensureKeysLive() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "UPDATE api_keys SET is_revoked = FALSE WHERE id IN " +
                            (MCP_KEYS.values + API_CALLER_KEY + RECEIVER_KEY).joinToString(", ", "(", ")") { "'${it.id}'" },
                    )
                }
            }
        }

        fun ensureSeeded() {
            if (seeded) return
            seeded = true
            E2eClean.beforeSeeding()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$WS_ID', '$WS_NAME', 'Role Walk')")
                    USERS.forEach { (role, id) ->
                        statement.execute(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                                "('$id', '$role@rolewalk.test', '$role', 'test', '$role-sub', TRUE, ${role == "super_admin"})",
                        )
                    }
                    // One membership per workspace role — the super admin holds none (D7).
                    USERS.filterKeys { it != "super_admin" }.forEach { (role, id) ->
                        statement.execute("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WS_ID', '$id', '$role')")
                    }
                    // The key identities, built as V34 and ApiKeyService build one (record §3.3):
                    // every key acts as its own `service` row (keys v2 A13).
                    (MCP_KEYS.values + listOf(API_CALLER_KEY, RECEIVER_KEY)).forEach { key ->
                        statement.execute(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                                "('${key.ownerId}', '${key.id.lowercase()}@keys.invalid', '${key.name}', 'key', '${key.id}', TRUE, FALSE, 'service')",
                        )
                    }
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    ).use { ps ->
                        val creator = USERS.getValue("workspace_admin")
                        // Keys v2 (A13): EVERY key acts as its own identity (seeded above, one per
                        // key), holds its own role — the member role chosen for the mcp keys, the
                        // transport role for the other two — and names its creator.
                        data class KeyRow(
                            val key: E2eAuth.SeededKey,
                            val kind: String,
                            val role: String,
                        )
                        val rows =
                            MCP_KEYS.map { (roleName, key) -> KeyRow(key, "mcp", roleName) } +
                                listOf(
                                    KeyRow(API_CALLER_KEY, "endpoint", "api_caller"),
                                    KeyRow(RECEIVER_KEY, "server", "promotion_receiver"),
                                )
                        rows.forEach { row ->
                            val key = row.key
                            val kind = row.kind
                            val role = row.role
                            ps.setString(1, key.id)
                            ps.setObject(2, UUID.fromString(key.ownerId))
                            ps.setObject(3, UUID.fromString(creator))
                            ps.setString(4, key.name)
                            ps.setString(5, key.hash)
                            ps.setObject(6, UUID.fromString(WS_ID))
                            ps.setString(7, kind)
                            ps.setString(8, role)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
            }
        }

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { jwtSecret }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }
            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        private val oidc = OidcDiscoveryStub()

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
