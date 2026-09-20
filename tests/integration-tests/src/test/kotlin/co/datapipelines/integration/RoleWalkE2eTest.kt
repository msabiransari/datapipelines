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
 * anybody remembering to add it) and EVERY MCP tool in auth.md §7.6 is called with a principal
 * of that role, and the answer is asserted allowed or refused **exactly as the §7.6 role table
 * says** — the table is parsed ([RoleMatrixDocE2e]), so the doc IS the expectation and a cell
 * flipped in the doc fails here against the live server.
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
 * - Every `RestOperation` the doc names is claimed by ≥ 1 walked handler, and every handler's
 *   operation has a doc row (gate 3, the REST half); every doc MCP tool answers as a known tool
 *   (the MCP half — an unknown tool is a row nothing implements).
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
    private val restRows = RoleMatrixDocE2e.restRows(doc)
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
     */
    @Test
    fun `every MCP tool answers each role's key exactly as auth-md §7-6 says`() {
        ensureSeeded()
        val schemas = toolSchemas()
        val tallies = ROLES.associateWith { role -> walkMcp(role, schemas) }
        val counts = tallies.mapValues { (_, tally) -> tally.allowed to tally.refused }

        println("event=rolewalk.mcp tools=${mcpRows.size} ${counts.summary()}")
        tallies.values.flatMap { it.unknown }.joinToString("\n") shouldBe ""
        tallies.values.flatMap { it.mismatches }.joinToString("\n") shouldBe ""

        mcpRows.size shouldBeGreaterThanOrEqual MINIMUM_TOOLS
        counts.getValue("viewer").second shouldBeGreaterThanOrEqual MCP_VIEWER_REFUSED_FLOOR
        counts.getValue("promoter").second shouldBeGreaterThanOrEqual MCP_PROMOTER_REFUSED_FLOOR
        counts.getValue("super_admin").second shouldBe 0
        counts.getValue("workspace_admin").second shouldBe 0
    }

    /** One role's walk: how many answers were allowed / refused, and every answer the doc did not predict. */
    private class Tally {
        var allowed = 0
        var refused = 0
        val mismatches = mutableListOf<String>()
        val unknown = mutableListOf<String>()

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
            val expectAllowed = restRows.getValue(route.operation).allows(role)
            val answer = call(route, sessionFor(role))
            tally.record(answer.roleRefused)
            if (answer.roleRefused == expectAllowed) {
                tally.mismatches +=
                    "$role ${route.method} ${route.pattern} (${route.handler}, ${route.operation}) -> " +
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
            tally.record(wasRefused)
            if (wasRefused == cells.allows(role)) {
                tally.mismatches +=
                    "$role $tool -> ${body.take(LEAK_EXCERPT)} but §7.6 says ${if (cells.allows(role)) "allowed" else "refused"}"
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
     * unannotated non-public handler and an operation with no doc row fail BY NAME; every
     * doc constant is claimed by at least one handler.
     */
    @Test
    fun `every non-public handler declares an operation the doc has a row for, and every row is claimed`() {
        val handlers = handlerMappings()
        val unannotated = handlers.filter { !it.public && it.operation == null }.map { "${it.handler} ${it.method} ${it.pattern}" }
        val undocumented =
            handlers.mapNotNull { it.operation }.distinct().filter { it !in restRows.keys }
        val unclaimed = restRows.keys - handlers.mapNotNull { it.operation }.toSet()

        withClue("non-public handlers without @RequiredScope (the default-deny would refuse them at runtime)") {
            unannotated.shouldBeEmpty()
        }
        withClue("operations declared by a handler but absent from auth.md §7.6") { undocumented.shouldBeEmpty() }
        withClue("auth.md §7.6 rows no handler claims — a row nothing uses is a lie in the doc") { unclaimed.shouldBeEmpty() }

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
        val operation: String,
    )

    private data class Handler(
        val method: String,
        val pattern: String,
        val handler: String,
        val operation: String?,
        val public: Boolean,
    )

    private data class Answer(
        val status: Int,
        val code: String?,
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
                    requireNotNull(h.operation) { "${h.handler} has no operation" },
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
            val operation = requiredScopeOf(method) ?: requiredScopeOf(beanType)
            patterns.flatMap { pattern ->
                verbs.map { verb ->
                    Handler(verb, pattern, "${beanType.simpleName}#${method.name}", operation, isPublic(pattern))
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

    private fun requiredScopeOf(element: java.lang.reflect.AnnotatedElement): String? =
        element.annotations
            .firstOrNull { it.annotationClass.simpleName == "RequiredScope" }
            ?.let { annotation -> (annotation.javaClass.getMethod("value").invoke(annotation) as Enum<*>).name }

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
        authenticate: (RequestSpecification) -> RequestSpecification,
    ): Answer {
        val spec =
            authenticate(given().port(port))
                .redirects()
                .follow(false)
                .cookie(CSRF_COOKIE, CSRF)
                .header(CSRF_HEADER, CSRF)
                .accept("application/json")
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
        return Answer(response.statusCode(), code)
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

    /** `tool name -> inputSchema`, from the server's own `tools/list` (the super admin's key, which every tool admits). */
    private fun toolSchemas(): Map<String, JsonNode> {
        val body =
            given()
                .port(port)
                .header(API_KEY_HEADER, keyFor("super_admin"))
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

    private fun keyFor(role: String): String = KEYS.getValue(role).plaintext

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

        /** A key's refusal on the role axis is the issuer-demotion code (D-R12); a session's is `auth.role_required`. */
        private val MCP_ROLE_REFUSALS = listOf("auth.key_issuer_role_lost", ROLE_REQUIRED)
        private val MCP_UNKNOWN_TOOL = listOf("Unknown tool", "tool_not_found", "Tool not found")
        private val PATH_NAMED_ARGUMENTS = setOf("name", "datasource", "path", "id", "pipeline")

        /**
         * Floors, not targets. The 2026-09-20 walk: 175 routes — viewer allowed 73 / refused 102,
         * author 127 / 48, promoter 58 / 117, workspace admin 150 / 25, super admin 175 / 0; 41
         * tools — viewer 28 / 13, author 41 / 0, promoter 20 / 21, admins 41 / 0. A walk well under
         * these numbers is a broken scan, not a leaner app.
         */
        private const val MINIMUM_ROUTES = 60
        private const val MINIMUM_HANDLERS = 100
        private const val MINIMUM_TOOLS = 41
        private const val ALLOWED_FLOOR = 20
        private const val VIEWER_REFUSED_FLOOR = 40
        private const val AUTHOR_REFUSED_FLOOR = 15
        private const val PROMOTER_REFUSED_FLOOR = 60
        private const val WS_ADMIN_REFUSED_FLOOR = 8
        private const val MCP_VIEWER_REFUSED_FLOOR = 12
        private const val MCP_PROMOTER_REFUSED_FLOOR = 20

        private val VARIABLE_PATTERN = Regex("\\{([^}]+)\\}")
        private val CODE = Regex("\"code\"\\s*:\\s*\"([a-z_.]+)\"")

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
        private val KEYS: Map<String, E2eAuth.SeededKey> =
            USERS.mapValues { (role, id) -> E2eAuth.generateKey("$role-key", arrayOf("read", "execute", "author"), ownerId = id) }

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
                    """{"sub":"$userId","email":"$email","name":"Role Walk","scopes":[],""" +
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
                }
                connection
                    .prepareStatement("INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id) VALUES (?, ?, ?, ?, ?, ?)")
                    .use { ps ->
                        KEYS.values.forEach { key ->
                            ps.setString(1, key.id)
                            ps.setObject(2, UUID.fromString(key.ownerId))
                            ps.setString(3, key.name)
                            ps.setString(4, key.hash)
                            ps.setArray(5, connection.createArrayOf("text", key.scopes))
                            ps.setObject(6, UUID.fromString(WS_ID))
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
