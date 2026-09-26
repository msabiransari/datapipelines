package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
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
 * **The deactivation sweep — gate 7 of the roles design (D15, §3.5), as lane 180 resolved it.**
 *
 * A deactivated USER and a deactivated WORKSPACE are served nothing. One predicate
 * (`PrincipalLiveness`) is judged where each credential becomes a principal, so the refusal
 * arrives before any handler, tool, endpoint or role is consulted; this suite proves it on the
 * LIVE server, over EVERY REST route the application registers (read off Spring's own
 * `RequestMappingHandlerMapping`, as `RoleWalkE2eTest` does) and EVERY MCP tool the server
 * lists, for each principal shape the design names — and asserts exactly the code the boundary
 * table in `docs/auth.md` §11A.3 promises:
 *
 * | principal                                               | REST / MCP                        |
 * |---------------------------------------------------------|-----------------------------------|
 * | session of a deactivated user                           | 401 `auth.principal_deactivated`  |
 * | any key whose IDENTITY is deactivated (keys v2: an      | 401 `auth.principal_deactivated`  |
 * | `mcp` key's own identity, an `endpoint`/`server` key's) |                                   |
 * | an `mcp` key whose CREATOR is deactivated (A20)         | 401 `auth.principal_deactivated`  |
 * | `mcp` / `endpoint` / `server` key, pin deactivated      | 404 `auth.key_workspace_inactive` |
 * | server key on the promotion peer, either                | 401 `auth.promotion.key_invalid`  |
 * | any key, a deactivated workspace's published endpoint   | 404 — the unknown-path body       |
 *
 * Since keys v2 (A13) EVERY key acts as its own `service` identity, and the identity's liveness
 * is what the ACTS-AS read judges (record §3.3, A2). A20 (owner ruling 2026-09-25) adds the ONE
 * creator read an `mcp` key owes: its CREATOR's user liveness — a deactivated person's `mcp`
 * keys are refused with the same code, until reactivation restores them (a read, never a
 * write). The `endpoint` and `server` control keys here were created by the DEACTIVATED user
 * and still serve — the independence half of A20 (PK2), proven on the live chain every run.
 * (The pre-v2 row "a `user` key whose MEMBER is deactivated" is gone with the login mint: a
 * member's deactivation cascades to nothing — A2 — so the mcp arm proves the identity's
 * liveness instead, and the A20 arm the creator's.) A dead credential is refused
 * before its KIND is judged, so a dead MCP key gets the liveness code on REST too, not B2's
 * kind refusal.
 *
 * ## What is proven beyond the codes
 * - A deactivated session navigating a PAGE is sent to `/login?error=inactive` with its cookie
 *   cleared, so the person is told why rather than seeing an ordinary expiry.
 * - The 404 rule holds as a DIFFERENTIAL: a session naming a deactivated workspace and one
 *   naming a workspace that does not exist get byte-identical bodies on every route; a
 *   deactivated workspace's endpoint and an unmatched path get byte-identical bodies.
 * - The promotion peer's ONE answer: a deactivated owner, a deactivated pin and a wrong key are
 *   byte-identical, and neither liveness code appears.
 * - Zero 5xx from any deactivated principal; a LIVE control session (REST is a session's
 *   surface since #215 B2) is refused by none of these codes on any route and reaches
 *   ≥ [CONTROL_REACHED_FLOOR] of them, so a fixture that
 *   broke authentication for everyone could not pass as "everything refused". (The control's
 *   own answers are the handlers' — a 500 there is printed and belongs to that route's issue.)
 *
 * ## Non-vacuity
 * The route count and per-principal counts are printed (`event=deactivation.inventory …`) and
 * floored; every deactivated principal must be refused on EVERY route and EVERY tool — a single
 * reached handler is a leak and fails by name.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class DeactivationSweepTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    // ------------------------------------------------------------------ REST

    @Test
    fun `every REST route refuses each deactivated principal with exactly the boundary table's code`() {
        ensureSeeded(context)
        val routes = walkableRoutes()
        val results = ARMS.associateWith { arm -> walk(routes, arm) }
        val control = walk(routes, CONTROL)

        println(
            "event=deactivation.inventory routes=${routes.size} " +
                results.entries.joinToString(" ") { "${it.key.name}=refused:${it.value.refused}/leaked:${it.value.leaks.size}" } +
                " control=reached:${control.reached}",
        )
        withClue("routes a deactivated principal REACHED (the boundary let it through)") {
            results.values.flatMap { it.leaks }.joinToString("\n") shouldBe ""
        }
        withClue("5xx answers from a deactivated principal (a refusal must never be a crash)") {
            results.values.flatMap { it.crashes }.joinToString("\n") shouldBe ""
        }
        // The control's answers belong to the HANDLERS, not to this boundary: a 500 there is a
        // route defect (its own issue), printed so the run names it, never scored here.
        if (control.crashes.isNotEmpty()) println("event=deactivation.control_crashes ${control.crashes.joinToString("; ")}")
        withClue("the live control principal was refused with a liveness code — the fixture, not the boundary, is broken") {
            control.leaks.joinToString("\n") shouldBe ""
        }

        routes.size shouldBeGreaterThanOrEqual MINIMUM_ROUTES
        results.values.forEach { it.refused shouldBe routes.size }
        control.reached shouldBeGreaterThanOrEqual CONTROL_REACHED_FLOOR
    }

    @Test
    fun `a deactivated session navigating a page lands on the login screen with the reason, cookie cleared`() {
        ensureSeeded(context)
        val response =
            given()
                .port(port)
                .cookie(SESSION_COOKIE, sessionJwt(DEAD_USER, "dead@dsweep.test", WS_LIVE_NAME))
                .accept("text/html")
                .redirects()
                .follow(false)
                .`when`()
                .get("/pipelines")
                .then()
                .extract()

        response.statusCode() shouldBe HTTP_FOUND
        response.header("Location") shouldBe "/login?error=inactive"
        withClue("the dead session cookie is cleared on the way out") {
            response.headers().getValues("Set-Cookie").any { it.startsWith("$SESSION_COOKIE=") && it.contains("Max-Age=0") } shouldBe true
        }
    }

    /**
     * The 404 rule as a differential (auth.md §11A.1): on EVERY route, a live session that
     * names the deactivated workspace and one that names a workspace which does not exist
     * get the same body, names and correlation ids stripped. Nothing about "deactivated"
     * leaks to a member who can no longer reach it.
     */
    @Test
    fun `a deactivated workspace is indistinguishable from an unknown one on every route`() {
        ensureSeeded(context)
        val routes = walkableRoutes()
        val live = sessionJwt(LIVE_USER, "live@dsweep.test", WS_LIVE_NAME)
        val differences =
            routes.mapNotNull { route ->
                val deactivated = call(route) { it.cookie(SESSION_COOKIE, live).header(WORKSPACE_HEADER, WS_DEAD_NAME) }
                val unknown = call(route) { it.cookie(SESSION_COOKIE, live).header(WORKSPACE_HEADER, UNKNOWN_WORKSPACE) }
                val deadPrint = fingerprint(deactivated, route.path, WS_DEAD_NAME)
                val unknownPrint = fingerprint(unknown, route.path, UNKNOWN_WORKSPACE)
                when {
                    deactivated.statusCode() != HTTP_NOT_FOUND || deactivated.code() != WORKSPACE_NOT_FOUND -> {
                        "${route.method} ${route.pattern} named the deactivated workspace -> " +
                            "${deactivated.statusCode()} ${deactivated.code()} (expected 404 $WORKSPACE_NOT_FOUND)"
                    }

                    deadPrint != unknownPrint -> {
                        "${route.method} ${route.pattern} differs: deactivated=${deadPrint.take(LEAK_EXCERPT)} " +
                            "unknown=${unknownPrint.take(LEAK_EXCERPT)}"
                    }

                    else -> {
                        null
                    }
                }
            }
        println("event=deactivation.differential routes=${routes.size} differences=${differences.size}")
        differences.joinToString("\n") shouldBe ""
        routes.size shouldBeGreaterThanOrEqual MINIMUM_ROUTES
    }

    // ------------------------------------------------------------------ MCP

    @Test
    fun `every MCP tool refuses each deactivated key at the transport with the boundary table's code`() {
        ensureSeeded(context)
        val schemas = toolSchemas(CONTROL_KEY.plaintext)
        schemas.size shouldBeGreaterThanOrEqual MINIMUM_TOOLS
        val leaks = mutableListOf<String>()
        var refused = 0
        MCP_ARMS.forEach { arm ->
            // The catalogue itself is refused — a deactivated key may not even list the surface.
            val listing = mcp(arm.key(), """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            if (!arm.matches(listing)) leaks += "${arm.name} tools/list -> ${listing.statusCode()} ${listing.asString().take(LEAK_EXCERPT)}"
            schemas.forEach { (tool, schema) ->
                val answer = mcp(arm.key(), toolCall(tool, synthesise(schema).toString()))
                if (arm.matches(answer)) {
                    refused++
                } else {
                    leaks +=
                        "${arm.name} $tool -> ${answer.statusCode()} ${answer.asString().take(LEAK_EXCERPT)}"
                }
            }
        }
        println("event=deactivation.mcp tools=${schemas.size} arms=${MCP_ARMS.size} refused=$refused leaked=${leaks.size}")
        withClue("tools a deactivated key reached, or answered with the wrong code") { leaks.joinToString("\n") shouldBe "" }
        refused shouldBe schemas.size * MCP_ARMS.size
    }

    // ------------------------------------------------------------------ promotion peer

    @Test
    fun `the promotion peer answers one code for a deactivated owner or pin, byte-identical to a wrong key`() {
        ensureSeeded(context)
        val presented =
            mapOf(
                "server-key:deactivated-workspace" to DEAD_WS_SERVER_KEY.plaintext,
                "server-key:deactivated-identity" to DEAD_IDENTITY_SERVER_KEY.plaintext,
                "wrong-key" to "dpk_ZZZZZZZZZZZZ." + "A".repeat(SECRET_CHARS),
            )
        val prints =
            PROMOTION_ROUTES.associateWith { (method, path) ->
                presented.mapValues { (name, key) ->
                    val response = promotion(method, path, key)
                    withClue("$name $method $path") {
                        response.statusCode() shouldBe HTTP_UNAUTHORIZED
                        response.code() shouldBe PROMOTION_KEY_INVALID
                        response.asString().contains(PRINCIPAL_DEACTIVATED) shouldBe false
                        response.asString().contains(KEY_WORKSPACE_INACTIVE) shouldBe false
                        response.asString().contains(WS_DEAD_NAME) shouldBe false
                    }
                    fingerprint(response, path, "")
                }
            }
        prints.forEach { (route, byName) ->
            withClue("${route.first} ${route.second}: the three refusals must be one body") { byName.values.toSet().size shouldBe 1 }
        }
        // Non-vacuity: a LIVE server key opens the same route (the handler answers, not the filter).
        val control = promotion("GET", PROMOTION_ROUTES.first().second, CONTROL_SERVER_KEY.plaintext)
        withClue("the live server key must reach the handler: ${control.asString().take(LEAK_EXCERPT)}") {
            control.code() shouldNotBe PROMOTION_KEY_INVALID
        }
    }

    // ------------------------------------------------------------------ the serve path

    /**
     * The RESOURCE half (`PublishedEndpointServeService`): a deactivated workspace's endpoint is
     * unknown to a key of ANOTHER workspace — the caller the pre-180 gap answered
     * `endpoint.key_not_bound` (403) to, confirming the endpoint existed — and the body is the
     * unmatched path's, byte for byte. The live twin proves the registry serves the fixture.
     */
    @Test
    fun `a deactivated workspace's published endpoint is an unknown path to every caller`() {
        ensureSeeded(context)
        val deactivated = serve(DEAD_ENDPOINT_PATH, CONTROL_ENDPOINT_KEY.plaintext)
        val unknown = serve("/api/dsweep/v1/nothing-here", CONTROL_ENDPOINT_KEY.plaintext)
        deactivated.statusCode() shouldBe HTTP_NOT_FOUND
        deactivated.code() shouldBe ENDPOINT_NOT_FOUND
        fingerprint(deactivated, DEAD_ENDPOINT_PATH, "") shouldBe fingerprint(unknown, "/api/dsweep/v1/nothing-here", "")

        // The endpoint key bound INSIDE the deactivated workspace: refused at validation, the
        // HTTP row's 404 (`auth.key_workspace_inactive`) — the holder is a member and knows the pin.
        val pinned = serve(DEAD_ENDPOINT_PATH, DEAD_WS_ENDPOINT_KEY.plaintext)
        pinned.statusCode() shouldBe HTTP_NOT_FOUND
        pinned.code() shouldBe KEY_WORKSPACE_INACTIVE

        // Non-vacuity: the live twin is served past the liveness gate (it has no release, so
        // the NEXT stage answers — proof the registry holds the fixture and the gate is what
        // hid the deactivated one).
        val live = serve(LIVE_ENDPOINT_PATH, CONTROL_ENDPOINT_KEY.plaintext)
        withClue("the live twin must be resolved: ${live.asString().take(LEAK_EXCERPT)}") {
            live.code() shouldBe ENDPOINT_PIPELINE_NOT_RELEASED
        }
    }

    // ------------------------------------------------------------------ the walk

    /**
     * One principal shape of the sweep: the key it presents (or the session it carries when
     * [key] is null), and the exact refusal every route owes it.
     */
    private class Arm(
        val name: String,
        val status: Int,
        val code: String,
        val key: E2eAuth.SeededKey?,
        val session: String? = null,
    ) {
        val authenticate: (RequestSpecification) -> RequestSpecification =
            { spec -> if (key != null) spec.header(API_KEY_HEADER, key.plaintext) else spec.cookie(SESSION_COOKIE, checkNotNull(session)) }

        fun matches(response: ExtractableResponse<Response>): Boolean = response.statusCode() == status && response.code() == code

        fun key(): String = checkNotNull(key) { "$name carries no key" }.plaintext
    }

    private class Tally {
        var refused = 0
        var reached = 0
        val leaks = mutableListOf<String>()
        val crashes = mutableListOf<String>()
    }

    private data class Route(
        val method: String,
        val pattern: String,
        val path: String,
        val handler: String,
    )

    private fun walk(
        routes: List<Route>,
        arm: Arm,
    ): Tally {
        val tally = Tally()
        routes.forEach { route ->
            val response = call(route, arm.authenticate)
            val status = response.statusCode()
            if (status >= HTTP_SERVER_ERROR) tally.crashes += "${arm.name} ${route.method} ${route.pattern} -> $status"
            when {
                arm === CONTROL -> {
                    if (response.code() in LIVENESS_CODES) {
                        tally.leaks += "${arm.name} ${route.method} ${route.pattern} -> $status ${response.code()}"
                    } else {
                        tally.reached++
                    }
                }

                arm.matches(response) -> {
                    tally.refused++
                }

                else -> {
                    tally.leaks +=
                        "${arm.name} ${route.method} ${route.pattern} (${route.handler}) -> $status ${response.code() ?: ""} " +
                        "but the boundary says ${arm.status} ${arm.code}"
                }
            }
        }
        return tally
    }

    private fun walkableRoutes(): List<Route> =
        handlerMappings()
            .filter { !it.public }
            .filterNot { it.pattern.startsWith(PROMOTION_RECEIVER_PREFIX) }
            .map { Route(it.method, it.pattern, substitute(it.pattern), it.handler) }
            .distinct()
            // Delete-to-rotate REVOKES the caller's own user key (179) — walked LAST so nothing
            // after it depends on the key it rotated.
            .sortedWith(compareBy({ it.pattern.startsWith(MCP_KEY_ROTATE_PREFIX) }, { it.pattern }, { it.method }))
            // The published-endpoint catch-all, walked at a REAL endpoint of the deactivated
            // workspace as well as at the synthesised path.
            .plus(Route("GET", "/api/{category}/** (dsweep)", DEAD_ENDPOINT_PATH, "PublishedEndpointController#serve"))

    private data class Handler(
        val method: String,
        val pattern: String,
        val handler: String,
        val public: Boolean,
    )

    /** `RequestMappingHandlerMapping.getHandlerMethods()`, by reflection — spring-webmvc is `:modules:app`'s alone. */
    private fun handlerMappings(): List<Handler> {
        val mapping = context.getBean("requestMappingHandlerMapping")
        val methods = mapping.javaClass.getMethod("getHandlerMethods").invoke(mapping) as Map<*, *>
        return methods.entries.flatMap { (info, handlerMethod) ->
            val patterns = patternsOf(info!!)
            val verbs = verbsOf(info)
            val method = handlerMethod!!.javaClass.getMethod("getMethod").invoke(handlerMethod) as java.lang.reflect.Method
            val beanType = handlerMethod.javaClass.getMethod("getBeanType").invoke(handlerMethod) as Class<*>
            patterns.flatMap { pattern ->
                verbs.map { verb -> Handler(verb, pattern, "${beanType.simpleName}#${method.name}", isPublic(pattern)) }
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
    ): ExtractableResponse<Response> {
        val request =
            authenticate(given().port(port))
                .redirects()
                .follow(false)
                .cookie(CSRF_COOKIE, CSRF)
                .header(CSRF_HEADER, CSRF)
                .accept("application/json")
                .contentType(ContentType.JSON)
                .body("{}")
                .`when`()
        return when (route.method) {
            "GET" -> request.get(route.path)
            "POST" -> request.post(route.path)
            "PUT" -> request.put(route.path)
            "PATCH" -> request.patch(route.path)
            "DELETE" -> request.delete(route.path)
            else -> request.get(route.path)
        }.then().extract()
    }

    private fun mcp(
        key: String,
        body: String,
    ): ExtractableResponse<Response> =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body(body)
            .`when`()
            .post("/mcp")
            .then()
            .extract()

    private fun promotion(
        method: String,
        path: String,
        key: String,
    ): ExtractableResponse<Response> {
        val request =
            given()
                .port(port)
                .header(PROMOTION_HEADER, key)
                .contentType(ContentType.JSON)
                .accept("application/json")
                .body("{}")
                .`when`()
        return (if (method == "POST") request.post(path) else request.get(path)).then().extract()
    }

    private fun serve(
        path: String,
        key: String,
    ): ExtractableResponse<Response> =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .accept("application/json")
            .`when`()
            .get(path)
            .then()
            .extract()

    private fun toolSchemas(key: String): Map<String, JsonNode> {
        val body = mcp(key, """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""").asString()
        val json = MAPPER.readTree(body.substringAfter("data:").trim().ifEmpty { body })
        return json.path("result").path("tools").associate { it.path("name").asText() to it.path("inputSchema") }
    }

    private fun toolCall(
        tool: String,
        arguments: String,
    ): String = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""

    /** Arguments satisfying the schema's `required` list — the same synthesis `RoleWalkE2eTest` uses. */
    private fun synthesise(schema: JsonNode): ObjectNode {
        val node = MAPPER.createObjectNode()
        val properties = schema.path("properties")
        schema.path("required").forEach { requiredName ->
            val name = requiredName.asText()
            node.set<JsonNode>(name, valueFor(properties.path(name)))
        }
        return node
    }

    private fun valueFor(property: JsonNode): JsonNode {
        val type = property.path("type").let { if (it.isArray) it.first().asText() else it.asText() }
        property.path("enum").takeIf { it.isArray && it.size() > 0 }?.let { return it.first() }
        return when (type) {
            "object" -> synthesise(property)
            "array" -> MAPPER.createArrayNode()
            "integer", "number" -> MAPPER.nodeFactory.numberNode(1)
            "boolean" -> MAPPER.nodeFactory.booleanNode(false)
            else -> MAPPER.nodeFactory.textNode(if (property.has("pattern")) "nobody/owns_this.sql" else ABSENT_UUID)
        }
    }

    /** The body with the request's own identifiers and the correlation id removed — what survives is content. */
    private fun fingerprint(
        response: ExtractableResponse<Response>,
        path: String,
        named: String,
    ): String {
        var text = "${response.statusCode()} " + response.asString()
        (path.split("/") + named).filter { it.length > MIN_TOKEN }.forEach { text = text.replace(it, "") }
        return text.replace(CORRELATION_ID, "").trim()
    }

    companion object {
        private const val SESSION_COOKIE = "dp_session"
        private const val CSRF_COOKIE = "dp_csrf"
        private const val CSRF_HEADER = "DP-CSRF-Token"
        private const val CSRF = "dsweep-csrf"
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val PROMOTION_HEADER = "DP-Promotion-Key"
        private const val WORKSPACE_HEADER = "DP-Workspace"
        private const val SECRET_BYTES = 32
        private const val SECRET_CHARS = 48
        private const val HTTP_FOUND = 302
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_SERVER_ERROR = 500
        private const val LEAK_EXCERPT = 240
        private const val MIN_TOKEN = 6
        private const val PROMOTION_RECEIVER_PREFIX = "/api/v1/promotion/"
        private const val MCP_KEY_ROTATE_PREFIX = "/partials/mcp-key"
        private const val ABSENT_UUID = "0d0e0000-0000-0000-0000-0000000000dd"
        private const val UNKNOWN_WORKSPACE = "nobody-has-this-one"
        private val MAPPER = ObjectMapper()

        private const val PRINCIPAL_DEACTIVATED = "auth.principal_deactivated"
        private const val KEY_WORKSPACE_INACTIVE = "auth.key_workspace_inactive"
        private const val PROMOTION_KEY_INVALID = "auth.promotion.key_invalid"
        private const val WORKSPACE_NOT_FOUND = "workspace.not_found"
        private const val ENDPOINT_NOT_FOUND = "endpoint.not_found"
        private const val ENDPOINT_PIPELINE_NOT_RELEASED = "endpoint.pipeline_not_released"
        private val LIVENESS_CODES = setOf(PRINCIPAL_DEACTIVATED, KEY_WORKSPACE_INACTIVE)

        /**
         * Floors, not targets. The 2026-09-21 sweep: 176 routes + the real endpoint, every arm
         * refused on all of them; 41 tools × 5 key arms; the control reached 150+.
         */
        private const val MINIMUM_ROUTES = 60
        private const val MINIMUM_TOOLS = 41
        private const val CONTROL_REACHED_FLOOR = 60

        private val VARIABLE_PATTERN = Regex("\\{([^}]+)\\}")
        private val CODE = Regex("\"code\"\\s*:\\s*\"([a-z_.]+)\"")
        private val CORRELATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        private fun ExtractableResponse<Response>.code(): String? = CODE.find(asString())?.groupValues?.get(1)

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

        private val PROMOTION_ROUTES =
            listOf(
                "GET" to "/api/v1/promotion/inventory?workspace=dsweep-live",
                "POST" to "/api/v1/promotion/push",
            )

        // ---------------------------------------------------------------- the fixture

        private const val WS_LIVE = "abc00000-0000-0000-0000-000000000180"
        private const val WS_LIVE_NAME = "dsweep-live"
        private const val WS_DEAD = "abc00000-0000-0000-0000-000000000181"
        private const val WS_DEAD_NAME = "dsweep-dead"
        private const val DEAD_USER = "0a000000-0000-0000-0000-000000000180"
        private const val LIVE_USER = "0b000000-0000-0000-0000-000000000180"
        private const val ADMIN_USER = "0e000000-0000-0000-0000-000000000180"

        /** The REST control's person: its live SESSION walks every route (#215 B2 — REST is a session's surface). */
        private const val REST_CONTROL_USER = "0c000000-0000-0000-0000-000000000180"

        /** The `service` identities (keys v2 A13: every key's own): three deactivated, seven live. */
        private const val DEAD_OWNER_IDENTITY = "5e000000-0000-0000-0000-000000000180"
        private const val DEAD_WS_IDENTITY = "5e000000-0000-0000-0000-000000000187"
        private const val CONTROL_IDENTITY = "5e000000-0000-0000-0000-000000000188"
        private const val DEAD_ENDPOINT_IDENTITY = "5e000000-0000-0000-0000-000000000181"
        private const val DEAD_SERVER_IDENTITY = "5e000000-0000-0000-0000-000000000182"
        private const val DEAD_WS_ENDPOINT_IDENTITY = "5e000000-0000-0000-0000-000000000183"
        private const val DEAD_WS_SERVER_IDENTITY = "5e000000-0000-0000-0000-000000000184"
        private const val CONTROL_SERVER_IDENTITY = "5e000000-0000-0000-0000-000000000185"
        private const val CONTROL_ENDPOINT_IDENTITY = "5e000000-0000-0000-0000-000000000186"
        private const val DEAD_CREATOR_KEY_IDENTITY = "5e000000-0000-0000-0000-000000000189"
        private const val DEAD_PIPELINE = "abc00000-0000-0000-0000-000000000182"
        private const val LIVE_PIPELINE = "abc00000-0000-0000-0000-000000000183"
        private const val DEAD_ENDPOINT = "abc00000-0000-0000-0000-000000000184"
        private const val LIVE_ENDPOINT = "abc00000-0000-0000-0000-000000000185"
        private const val DEAD_ENDPOINT_PATH = "/api/dsweep/v1/report"
        private const val LIVE_ENDPOINT_PATH = "/api/dsweep-live/v1/report"

        /**
         * (key, who it acts as, who created it, workspace, kind) — every key the sweep presents,
         * seeded by SQL. Keys v2 (A13): every key — `mcp` included — acts as its own `service`
         * identity ([identityActive] says whether it is live). A20 adds the creator read for
         * `mcp` keys only: the `mcp-key:deactivated-owner` arm pairs a LIVE identity with a DEAD
         * creator, while the pre-v2 "deactivated member" shape is a deactivated-identity arm.
         */
        private class Seed(
            val key: E2eAuth.SeededKey,
            val actsAs: String,
            val createdBy: String,
            val workspace: String,
            val kind: String,
            val identityActive: Boolean = true,
        ) {
            val role: String? =
                when (kind) {
                    "mcp" -> "workspace_admin"
                    "endpoint" -> "api_caller"
                    "server" -> "promotion_receiver"
                    else -> null
                }
        }

        private val DEAD_OWNER_USER_KEY = E2eAuth.generateKey("dead-owner-mcp")
        private val DEAD_WS_USER_KEY = E2eAuth.generateKey("dead-ws-mcp")
        private val DEAD_WS_ENDPOINT_KEY = E2eAuth.generateKey("dead-ws-endpoint")
        private val DEAD_IDENTITY_ENDPOINT_KEY = E2eAuth.generateKey("dead-identity-endpoint")
        private val DEAD_WS_SERVER_KEY = E2eAuth.generateKey("dead-ws-server")
        private val DEAD_IDENTITY_SERVER_KEY = E2eAuth.generateKey("dead-identity-server")

        /** The A20 arm's key: a LIVE identity, a LIVE workspace — and a DEACTIVATED creator. */
        private val DEAD_CREATOR_MCP_KEY = E2eAuth.generateKey("dead-creator-mcp")

        /** The MCP test's live key: lists the tool catalogue the dead arms are walked over. */
        private val CONTROL_KEY = E2eAuth.generateKey("control-mcp")

        /**
         * Live keys CREATED BY THE DEACTIVATED USER — `endpoint` and `server` kinds, which A20
         * leaves creator-independent (PK2): these are the independence half of the ruling,
         * walked live on the serve and promotion paths every run.
         */
        private val CONTROL_SERVER_KEY = E2eAuth.generateKey("control-server")
        private val CONTROL_ENDPOINT_KEY = E2eAuth.generateKey("control-endpoint")

        private val SEEDS =
            listOf(
                Seed(DEAD_OWNER_USER_KEY, DEAD_OWNER_IDENTITY, DEAD_USER, WS_LIVE, "mcp", identityActive = false),
                Seed(DEAD_CREATOR_MCP_KEY, DEAD_CREATOR_KEY_IDENTITY, DEAD_USER, WS_LIVE, "mcp"),
                Seed(DEAD_WS_USER_KEY, DEAD_WS_IDENTITY, LIVE_USER, WS_DEAD, "mcp"),
                Seed(DEAD_WS_ENDPOINT_KEY, DEAD_WS_ENDPOINT_IDENTITY, LIVE_USER, WS_DEAD, "endpoint"),
                Seed(DEAD_IDENTITY_ENDPOINT_KEY, DEAD_ENDPOINT_IDENTITY, LIVE_USER, WS_LIVE, "endpoint", identityActive = false),
                Seed(DEAD_WS_SERVER_KEY, DEAD_WS_SERVER_IDENTITY, LIVE_USER, WS_DEAD, "server"),
                Seed(DEAD_IDENTITY_SERVER_KEY, DEAD_SERVER_IDENTITY, LIVE_USER, WS_LIVE, "server", identityActive = false),
                Seed(CONTROL_KEY, CONTROL_IDENTITY, LIVE_USER, WS_LIVE, "mcp"),
                Seed(CONTROL_SERVER_KEY, CONTROL_SERVER_IDENTITY, DEAD_USER, WS_LIVE, "server"),
                Seed(CONTROL_ENDPOINT_KEY, CONTROL_ENDPOINT_IDENTITY, DEAD_USER, WS_LIVE, "endpoint"),
            )

        private val ARMS: List<Arm> by lazy {
            listOf(
                Arm(
                    "session:deactivated-user",
                    HTTP_UNAUTHORIZED,
                    PRINCIPAL_DEACTIVATED,
                    key = null,
                    session = sessionJwt(DEAD_USER, "dead@dsweep.test", WS_LIVE_NAME),
                ),
                Arm("mcp-key:deactivated-identity", HTTP_UNAUTHORIZED, PRINCIPAL_DEACTIVATED, DEAD_OWNER_USER_KEY),
                Arm("mcp-key:deactivated-owner", HTTP_UNAUTHORIZED, PRINCIPAL_DEACTIVATED, DEAD_CREATOR_MCP_KEY),
                Arm("mcp-key:deactivated-workspace", HTTP_NOT_FOUND, KEY_WORKSPACE_INACTIVE, DEAD_WS_USER_KEY),
                Arm("endpoint-key:deactivated-identity", HTTP_UNAUTHORIZED, PRINCIPAL_DEACTIVATED, DEAD_IDENTITY_ENDPOINT_KEY),
                Arm("endpoint-key:deactivated-workspace", HTTP_NOT_FOUND, KEY_WORKSPACE_INACTIVE, DEAD_WS_ENDPOINT_KEY),
                Arm("server-key:deactivated-identity", HTTP_UNAUTHORIZED, PRINCIPAL_DEACTIVATED, DEAD_IDENTITY_SERVER_KEY),
                Arm("server-key:deactivated-workspace", HTTP_NOT_FOUND, KEY_WORKSPACE_INACTIVE, DEAD_WS_SERVER_KEY),
            )
        }

        /** Every key arm — the session cannot reach `/mcp` at all (auth.md §8.5), so it is not an MCP arm. */
        private val MCP_ARMS: List<Arm> by lazy { ARMS.filter { it.key != null } }

        /** A live SESSION — REST is a session's surface since #215 B2 (a live MCP key is refused there by kind). */
        private val CONTROL: Arm by lazy {
            Arm("control:live-session", 0, "", key = null, session = sessionJwt(REST_CONTROL_USER, "control@dsweep.test", WS_LIVE_NAME))
        }

        private val random = SecureRandom()
        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private fun sessionJwt(
            userId: String,
            email: String,
            activeWorkspace: String,
        ): String {
            val now = Instant.now()
            val header = b64("""{"alg":"HS256","typ":"JWT"}""")
            val payload =
                b64(
                    """{"sub":"$userId","email":"$email","name":"Deactivation Sweep",""" +
                        """"iss":"datapipelines","iat":${now.epochSecond},"exp":${now.plusSeconds(3600).epochSecond},""" +
                        """"active_workspace":"$activeWorkspace"}""",
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
         * The REST walk drives `DELETE /partials/mcp-key` (delete-to-rotate, every role), which
         * revokes the caller's own MCP key — and the tests run in any order. Un-revoke every
         * seeded key before each test, as `RoleWalkE2eTest` does.
         */
        private fun ensureKeysLive() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "UPDATE api_keys SET is_revoked = FALSE WHERE id IN " + SEEDS.joinToString(", ", "(", ")") { "'${it.key.id}'" },
                    )
                }
            }
        }

        @Suppress("LongMethod") // one fixture, spelled out in the order its foreign keys demand
        fun ensureSeeded(context: ApplicationContext) {
            if (seeded) {
                ensureKeysLive()
                return
            }
            seeded = true
            E2eClean.beforeSeeding()
            // ONE transaction, committed at the end. Three seeds are INACTIVE `service` identities,
            // and until their key rows land they are exactly what `KeyRetentionPurge` deletes: an
            // inactive service identity nothing references. The purge's first tick fires when the
            // context boots — the moment this runs — and under a loaded gate it deleted
            // DEAD_OWNER_IDENTITY between the identity insert and the key batch (FK 23503 on
            // 8f0a23a7's gate, both cycles). Production never shows that state: issuance writes
            // the identity and its key together, and an identity turns inactive only afterwards.
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                            "('$ADMIN_USER', 'admin@dsweep.test', 'Admin', 'test', 'dsweep-admin', TRUE, TRUE), " +
                            "('$LIVE_USER', 'live@dsweep.test', 'Live', 'test', 'dsweep-live', TRUE, FALSE), " +
                            "('$DEAD_USER', 'dead@dsweep.test', 'Dead', 'test', 'dsweep-dead', FALSE, FALSE), " +
                            "('$REST_CONTROL_USER', 'control@dsweep.test', 'Control', 'test', 'dsweep-control', TRUE, FALSE)",
                    )
                    // Every key's own identity, built as V34/ApiKeyService build one (keys v2 A13).
                    SEEDS.forEach { seed ->
                        statement.execute(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) " +
                                "VALUES ('${seed.actsAs}', '${seed.key.id.lowercase()}@keys.invalid', '${seed.key.name}', 'key', " +
                                "'${seed.key.id}', ${seed.identityActive}, FALSE, 'service')",
                        )
                    }
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name, created_by, deactivated_at, deactivated_by) VALUES " +
                            "('$WS_LIVE', '$WS_LIVE_NAME', 'Sweep live', '$ADMIN_USER', NULL, NULL), " +
                            "('$WS_DEAD', '$WS_DEAD_NAME', 'Sweep dead', '$ADMIN_USER', NOW(), '$ADMIN_USER')",
                    )
                    // Both users are workspace admins everywhere — the sweep is about liveness,
                    // and the highest workspace role means "refused" can never be a role refusal.
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES " +
                            "('$WS_LIVE', '$LIVE_USER', 'workspace_admin'), ('$WS_DEAD', '$LIVE_USER', 'workspace_admin'), " +
                            "('$WS_LIVE', '$DEAD_USER', 'workspace_admin'), ('$WS_DEAD', '$DEAD_USER', 'workspace_admin'), " +
                            "('$WS_LIVE', '$REST_CONTROL_USER', 'workspace_admin')",
                    )
                    statement.execute(
                        "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES " +
                            "('$DEAD_PIPELINE', 'dsweep/report', 'Dead report', '', '$LIVE_USER', '$WS_DEAD', 0), " +
                            "('$LIVE_PIPELINE', 'dsweep/report', 'Live report', '', '$LIVE_USER', '$WS_LIVE', 0)",
                    )
                    statement.execute(
                        "INSERT INTO published_endpoints " +
                            "(id, workspace_id, path_pattern, pipeline_id, timeout_seconds, created_by) VALUES " +
                            "('$DEAD_ENDPOINT', '$WS_DEAD', '/dsweep/v1/report', '$DEAD_PIPELINE', 30, '$LIVE_USER'), " +
                            "('$LIVE_ENDPOINT', '$WS_LIVE', '/dsweep-live/v1/report', '$LIVE_PIPELINE', 30, '$LIVE_USER')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?, ?, ?, ?, ?::uuid, ?, ?)",
                    ).use { ps ->
                        SEEDS.forEach { seed ->
                            ps.setString(1, seed.key.id)
                            ps.setObject(2, UUID.fromString(seed.actsAs))
                            ps.setObject(3, UUID.fromString(seed.createdBy))
                            ps.setString(4, seed.key.name)
                            ps.setString(5, seed.key.hash)
                            ps.setString(6, seed.workspace)
                            ps.setString(7, seed.kind)
                            ps.setString(8, seed.role)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO endpoint_key_bindings (path_prefix, api_key_id, workspace_id, created_by) VALUES " +
                            "('/dsweep', '${DEAD_WS_ENDPOINT_KEY.id}', '$WS_DEAD', '$LIVE_USER'), " +
                            // The live twin's caller (#215 B3: an unbound path serves no key).
                            "('/dsweep-live', '${CONTROL_ENDPOINT_KEY.id}', '$WS_LIVE', '$LIVE_USER')",
                    )
                }
                connection.commit()
            }
            // The registry snapshot may predate the fixture (contexts are cached across suites).
            val registry = context.getBean("endpointRegistry")
            registry.javaClass.getMethod("invalidateLocally").invoke(registry)
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
