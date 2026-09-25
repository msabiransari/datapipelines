package co.datapipelines.integration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.AntPathMatcher
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * **The promoter lens sweep — gate 5 of the roles design (§4; 178).**
 *
 * A promoter sees ONLY released pipelines and templates newer than the higher environment's
 * inventory entry (versioning §10.2's rule, everywhere — §3.1). This suite boots the sender
 * against a STUB higher environment (`/api/v1/promotion/inventory` on a loopback port, every
 * request logged), seeds one of each kind the rule distinguishes — draft-only, released and
 * already on the target at the same hash, released but behind the target, released and newer,
 * released and absent there — for pipelines AND templates, plus an endpoint on a hidden and on
 * a visible pipeline, and then walks, as the promoter on a session and on a key:
 *
 * 1. **the differential** ([WorkspaceIsolationSweepTest]'s shape): every GET route the promoter
 *    reaches is called with a HIDDEN identifier and with one that exists nowhere, and the two
 *    answers must be identical — status and fingerprint. A hidden object is an absent object;
 * 2. **no leak**: no 2xx body anywhere in the walk names a hidden pipeline, template, endpoint
 *    or id — the reverse arrows (usage, used-by, endpoints, search, the rail) included;
 * 3. **exactness**: every list, level, count and MCP read tool answers EXACTLY the newer set;
 * 4. **the viewer control**: the same walk as a viewer sees everything, and the stub's request
 *    log did not grow — a non-lensed principal never triggers the target call;
 * 5. **fail closed**: with the target stopped, the promoter's lists are empty, the gets are
 *    not-found, `pipeline.promotion.lens_unavailable` is logged ONCE for the whole walk (once per
 *    cache window, by construction) and the counter's `unreachable` outcome moved.
 *
 * ## The route list is the server's own
 * Read off `RequestMappingHandlerMapping` by reflection ([RoleWalkE2eTest]'s way), GET routes
 * only (this suite reads; the promoter's one verb, `POST /promotion/promote`, has its own
 * suite), so a read route added tomorrow is swept without anybody remembering to add it.
 *
 * ## Falsifying it
 * Remove the lens from ONE seam — `PipelineService.findRecord`'s `takeIf` — and test 1 names
 * `GET /api/v1/pipelines/{id}` (200 for a hidden id against 404 for an absent one) and test 2
 * names the hidden name the body carried. The 178 handback pastes both runs.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PromoterLensSweepTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    /**
     * `web`'s client and the meter registry, reached by NAME and by reflection: this module
     * compiles against `:modules:app` alone (module-structure §4.2), so `PromotionTargetClient`
     * and micrometer are runtime-only here, exactly as `SampleDataBootstrapE2eTest` reaches
     * `userService`.
     */
    private val client: Any by lazy { context.getBean("promotionTargetClient") }

    @Test
    @Order(1)
    fun `as the promoter every reachable GET answers a hidden id exactly as an absent one, and no body leaks a hidden name`() {
        ensureSeeded()
        val walk = reachableGetRoutes()
        println("event=lens.sweep.inventory routes=${walk.size}")
        walk.size shouldBeGreaterThanOrEqual MINIMUM_ROUTES

        // #215 B2, keys v2: REST is the session's surface alone — the promoter's MCP key is refused
        // on every route by KIND before the lens is ever asked (pinned here, so the walk below is
        // not the key's), and the key's lens is walked over /mcp in test 2.
        val keyOnRest = call("/api/v1/pipelines", keyHeaderFor(PROMOTER))
        keyOnRest.status shouldBe HTTP_FORBIDDEN
        keyOnRest.body.contains("mcp_key_off_surface") shouldBe true
        listOf("session" to sessionFor(PROMOTER)).forEach { (credential, auth) ->
            val findings = Findings()
            walk.forEach { route -> sweep(route, credential, auth, findings) }
            println("event=lens.sweep.differential credential=$credential pairs=${findings.pairs}")
            withClue("hidden vs absent must be indistinguishable") { findings.differential.joinToString("\n") shouldBe "" }
            withClue("no promoter-reachable body may name a hidden object") { findings.leaks.joinToString("\n") shouldBe "" }
            findings.pairs shouldBeGreaterThanOrEqual MINIMUM_PAIRS
        }
    }

    /** One credential's walk: the differential mismatches, the leaks, and how many pairs were compared. */
    private class Findings {
        val differential = mutableListOf<String>()
        val leaks = mutableListOf<String>()
        var pairs = 0
    }

    /** One route: each hidden/absent pair compared, then every 2xx body scanned for a hidden token it did not request. */
    private fun sweep(
        route: Route,
        credential: String,
        auth: (RequestSpecification) -> RequestSpecification,
        findings: Findings,
    ) {
        val answers = mutableListOf<Pair<String, Answer>>()
        identifierPairs(route).forEach { (hidden, absent) ->
            findings.pairs++
            val a = call(hidden, auth)
            val b = call(absent, auth)
            answers += hidden to a
            differ(route, credential, hidden, a, b)?.let { findings.differential += it }
        }
        if (identifierPairs(route).isEmpty()) answers += route.path to call(route.path, auth)
        // A body may ECHO the identifier the request itself carried (a detail pane titles itself
        // with the requested name, absent or not — the differential proved the echo identical for
        // an absent one); every OTHER hidden token is a leak.
        answers.filter { (_, answer) -> answer.status in SUCCESS }.forEach { (requested, answer) ->
            HIDDEN_TOKENS
                .filter { it !in requested && it in answer.body }
                .forEach { token -> findings.leaks += "$credential GET $requested (${route.handler}) leaks $token" }
        }
    }

    /** The differential's verdict for one pair, or null when hidden and absent are indistinguishable. */
    private fun differ(
        route: Route,
        credential: String,
        hidden: String,
        a: Answer,
        b: Answer,
    ): String? =
        when {
            a.status != b.status -> {
                "$credential GET $hidden (${route.handler}) -> ${a.status}, but the NONEXISTENT id gives ${b.status}"
            }

            a.status in SUCCESS && a.fingerprint != b.fingerprint -> {
                "$credential GET $hidden (${route.handler}) -> ${a.status} " +
                    "RENDERED HIDDEN CONTENT (body differs from the absent-id control)"
            }

            else -> {
                null
            }
        }

    @Test
    @Order(2)
    fun `as the promoter every list, level, count and read tool is exactly the newer set`() {
        ensureSeeded()
        val auth = sessionFor(PROMOTER)
        restIsTheNewerSet(auth)
        screensAreTheNewerSet(auth)
        mcpIsTheNewerSet(keyFor(PROMOTER))
    }

    private fun restIsTheNewerSet(auth: (RequestSpecification) -> RequestSpecification) {
        withClue("REST pipelines: the flat list, the folder level, the root's folder count") {
            names(getJson("/api/v1/pipelines", auth).path("data").path("items"), "name") shouldContainExactlyInAnyOrder VISIBLE_PIPELINES
            val level = getJson("/api/v1/pipelines?prefix=$P", auth).path("data")
            names(level.path("pipelines"), "name") shouldContainExactlyInAnyOrder VISIBLE_PIPELINES
            level.path("total").asInt() shouldBe VISIBLE_PIPELINES.size
            val root = getJson("/api/v1/pipelines?prefix=", auth).path("data").path("folders")
            root.map { it.path("path").asText() to it.path("pipeline_count").asInt() } shouldContainExactly
                listOf(P to VISIBLE_PIPELINES.size)
        }
        withClue("REST templates: the flat list, the folder level, the root's folder count") {
            names(getJson("/api/v1/templates", auth).path("data").path("items"), "id") shouldContainExactlyInAnyOrder VISIBLE_TEMPLATES
            val level = getJson("/api/v1/templates?prefix=$T", auth).path("data")
            names(level.path("templates"), "id") shouldContainExactlyInAnyOrder VISIBLE_TEMPLATES
            level.path("total").asInt() shouldBe VISIBLE_TEMPLATES.size
            val root = getJson("/api/v1/templates?prefix=", auth).path("data").path("folders")
            root.map { it.path("path").asText() to it.path("template_count").asInt() } shouldContainExactly
                listOf(T to VISIBLE_TEMPLATES.size)
        }
        withClue("REST: a visible object's pending DRAFT is invisible — the working version is the release") {
            getJson("/api/v1/pipelines/$PIPE_NEWER", auth).path("data").path("version").asInt() shouldBe 3
            names(getJson("/api/v1/pipelines/$PIPE_NEWER/versions", auth).path("data"), "status") shouldContainExactly listOf("RELEASED")
            call("/api/v1/pipelines/$PIPE_NEWER/versions/4", auth).status shouldBe HTTP_NOT_FOUND
            getJson("/api/v1/templates?name=$T/newer.sql", auth).path("data").path("version").asInt() shouldBe 3
            call("/api/v1/templates/versions?name=$T/newer.sql&version=4", auth).status shouldBe HTTP_NOT_FOUND
        }
        withClue("REST endpoints: visible iff the pipeline is") {
            names(getJson("/api/v1/endpoints", auth).path("data"), "path") shouldContainExactly listOf(VISIBLE_ENDPOINT)
        }
    }

    private fun screensAreTheNewerSet(auth: (RequestSpecification) -> RequestSpecification) {
        withClue("the explorers, the search palette, the dashboard tile and the rail badges") {
            val pipelinesLevel = call("/partials/pipelines?prefix=$P", auth).body
            VISIBLE_PIPELINES.forEach { pipelinesLevel.contains(it) shouldBe true }
            val templatesLevel = call("/partials/templates?prefix=$T", auth).body
            VISIBLE_TEMPLATES.forEach { templatesLevel.contains(it) shouldBe true }
            val search = call("/partials/search?q=$P/", auth).body
            VISIBLE_PIPELINES.forEach { search.contains(it) shouldBe true }
            statNumber(call("/partials/dashboard-stats", auth).body) shouldBe VISIBLE_PIPELINES.size
            railBadges(call("/pipelines", auth).body) shouldBe listOf(VISIBLE_PIPELINES.size, VISIBLE_TEMPLATES.size)
        }
    }

    private fun mcpIsTheNewerSet(key: String) {
        withClue("MCP: the read tools and the resource catalogue") {
            names(toolResult(tool("pipelines_list", "{}", key)), "name") shouldContainExactlyInAnyOrder VISIBLE_PIPELINES
            names(toolResult(tool("pipelines_list", """{"prefix":"$P"}""", key)).path("pipelines"), "name") shouldContainExactlyInAnyOrder
                VISIBLE_PIPELINES
            names(toolResult(tool("templates_list", "{}", key)), "id") shouldContainExactlyInAnyOrder VISIBLE_TEMPLATES
            names(toolResult(tool("templates_list", """{"prefix":"$T"}""", key)).path("templates"), "id") shouldContainExactlyInAnyOrder
                VISIBLE_TEMPLATES
            names(toolResult(tool("endpoints_list", "{}", key)).path("endpoints"), "path") shouldContainExactly listOf(VISIBLE_ENDPOINT)
            HIDDEN_PIPELINE_IDS.forEach { id -> refused(tool("pipelines_get", """{"id":"$id"}""", key)) shouldBe true }
            HIDDEN_TEMPLATES.forEach { id ->
                refused(tool("templates_get", """{"id":"$id"}""", key)) shouldBe true
                refused(tool("templates_used_by", """{"id":"$id","version":1}""", key)) shouldBe true
            }
            refused(tool("endpoints_get", """{"path":"$HIDDEN_ENDPOINT"}""", key)) shouldBe true
            withClue("the visible ones still answer, so the not-founds above are the lens and not a broken key") {
                refused(tool("pipelines_get", """{"id":"$PIPE_NEWER"}""", key)) shouldBe false
                refused(tool("templates_get", """{"id":"$T/newer.sql"}""", key)) shouldBe false
            }
            withClue("a visible object's pending DRAFT is invisible: the get serves the release, the draft version is not-found") {
                val got = toolResult(tool("pipelines_get", """{"id":"$PIPE_NEWER"}""", key))
                got.path("version").asInt() shouldBe 3
                got.path("status").asText() shouldBe "RELEASED"
                got.path("draft").size() shouldBe 0
                refused(tool("pipelines_get", """{"id":"$PIPE_NEWER","version":4}""", key)) shouldBe true
                toolResult(tool("templates_get", """{"id":"$T/newer.sql"}""", key)).path("version").asInt() shouldBe 3
                refused(tool("templates_get", """{"id":"$T/newer.sql","version":4}""", key)) shouldBe true
            }
            val catalogue = mcp("""{"jsonrpc":"2.0","id":1,"method":"resources/list","params":{}}""", key)
            HIDDEN_TOKENS.filter { it in catalogue } shouldBe emptyList()
            catalogue.contains(PIPE_NEWER.toString()) shouldBe true
            HIDDEN_PIPELINE_IDS.forEach { id ->
                mcp("""{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"datapipelines://pipelines/$id"}}""", key)
                    .contains("not found") shouldBe true
            }
            mcp("""{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"datapipelines://templates/$T/behind.sql"}}""", key)
                .contains("not found") shouldBe true
        }
    }

    /**
     * 178b — the orchestrator's finding: three reads passed the name check and then read draft
     * CONTENT of a visible object. The visible pipeline `lp/newer` carries a DRAFT v4 whose `n1`
     * pins the DRAFT template v4 (its SQL is the marker); the checks partial takes a
     * caller-chosen version; `used_by` lists working-version pins. As the promoter, none of it
     * shows — and the viewer sees all of it, so the promoter's not seeing it is the lens.
     */
    @Test
    @Order(3)
    fun `as the promoter a visible object's DRAFT content is invisible on the node SQL, the checks and the used-by reads`() {
        ensureSeeded()
        val promoter = sessionFor(PROMOTER)
        val viewer = sessionFor(VIEWER)
        val nodeSql = "/partials/pipelines/$PIPE_NEWER/nodes/n1/sql"

        withClue("fix 1: the node-SQL partial renders the RELEASED n1, never the draft's (which pins the DRAFT template)") {
            val asPromoter = call(nodeSql, promoter)
            asPromoter.status shouldBe HTTP_OK
            withClue(asPromoter.body.take(LEAK_EXCERPT * 4)) {
                asPromoter.body.contains(DRAFT_MARKER) shouldBe false
                asPromoter.body.contains("SELECT 1") shouldBe true
            }
            val asViewer = call(nodeSql, viewer)
            withClue("non-vacuity: the viewer's same partial IS the draft's SQL — " + asViewer.body.take(LEAK_EXCERPT * 4)) {
                asViewer.body.contains(DRAFT_MARKER) shouldBe true
            }
        }
        withClue("fix 2: the checks partial and the REST checks read answer the DRAFT version exactly as an absent one") {
            val checksRoutes =
                listOf("/partials/pipelines/$PIPE_NEWER/versions/%d/checks", "/api/v1/pipelines/$PIPE_NEWER/versions/%d/checks")
            checksRoutes.forEach { route ->
                val draft = call(route.format(4), promoter)
                val absent = call(route.format(ABSENT_VERSION), promoter)
                withClue(route) {
                    draft.status shouldBe absent.status
                    draft.fingerprint shouldBe absent.fingerprint
                    draft.status shouldBe HTTP_NOT_FOUND
                    call(route.format(4), viewer).status shouldBe HTTP_OK
                }
            }
        }
        withClue("184: the node-SQL partial answers a hidden pipeline id exactly as an absent one — the house 404, never the 500") {
            nodeSqlPartialIsTheHouse404(promoter)
        }
        withClue("fix 3: used_by shows no DRAFT pin and no draft version, and the DRAFT template version is not-found") {
            val used = toolResult(tool("templates_used_by", """{"id":"$T/newer.sql","version":3}""", keyFor(PROMOTER)))
            val references = used.path("references")
            references.size() shouldBeGreaterThanOrEqual 1
            references.map { it.path("pipeline_version_status").asText() }.toSet() shouldBe setOf("RELEASED")
            references.map { it.path("pipeline_version").asInt() }.contains(4) shouldBe false
            refused(tool("templates_used_by", """{"id":"$T/newer.sql","version":4}""", keyFor(PROMOTER))) shouldBe true
            withClue("non-vacuity: the author-role key's (not lensed) used_by v3 carries the DRAFT pin") {
                toolResult(tool("templates_used_by", """{"id":"$T/newer.sql","version":3}""", keyFor(AUTHOR)))
                    .path("references")
                    .map { it.path("pipeline_version_status").asText() }
                    .contains("DRAFT") shouldBe true
            }
            val usedByPage = call("/partials/templates/versions?name=$T/newer.sql", promoter).body
            usedByPage.contains("(DRAFT)") shouldBe false
            usedByPage.contains("v4") shouldBe false
            call("/partials/templates/versions?name=$T/newer.sql", viewer).body.contains("(DRAFT)") shouldBe true
        }
    }

    /** 184 — every hidden pipeline id on the node-SQL partial: the 404 an absent id gets, byte-identical. */
    private fun nodeSqlPartialIsTheHouse404(promoter: (RequestSpecification) -> RequestSpecification) {
        HIDDEN_PIPELINE_IDS.forEach { hidden ->
            val asPromoter = call("/partials/pipelines/$hidden/nodes/n1/sql", promoter)
            val absent = call("/partials/pipelines/$ABSENT_UUID/nodes/n1/sql", promoter)
            withClue("hidden $hidden") {
                asPromoter.status shouldBe HTTP_NOT_FOUND
                asPromoter.fingerprint shouldBe absent.fingerprint
            }
        }
    }

    @Test
    @Order(4)
    fun `as a viewer the same walk sees everything, and the target is never called`() {
        ensureSeeded()
        val before = stubRequests.size
        val auth = sessionFor(VIEWER)

        names(getJson("/api/v1/pipelines", auth).path("data").path("items"), "name") shouldContainExactlyInAnyOrder
            VISIBLE_PIPELINES + HIDDEN_PIPELINES
        names(getJson("/api/v1/templates", auth).path("data").path("items"), "id") shouldContainExactlyInAnyOrder
            VISIBLE_TEMPLATES + HIDDEN_TEMPLATES
        names(getJson("/api/v1/endpoints", auth).path("data"), "path") shouldContainExactlyInAnyOrder
            listOf(VISIBLE_ENDPOINT, HIDDEN_ENDPOINT)
        HIDDEN_PIPELINE_IDS.forEach { id -> call("/api/v1/pipelines/$id", auth).status shouldBe HTTP_OK }
        withClue("the viewer sees the visible object's DRAFT — so the promoter's not seeing it is the lens, not a missing row") {
            call("/api/v1/pipelines/$PIPE_NEWER", auth).body.contains(DRAFT_MARKER) shouldBe true
            call("/api/v1/templates?name=$T/newer.sql", auth).body.contains(DRAFT_MARKER) shouldBe true
        }
        reachableGetRoutes().forEach { route -> identifierPairs(route).forEach { (hidden, _) -> call(hidden, auth) } }
        names(toolResult(tool("pipelines_list", "{}", keyFor(AUTHOR))), "name") shouldContainExactlyInAnyOrder
            VISIBLE_PIPELINES + HIDDEN_PIPELINES
        railBadges(call("/pipelines", auth).body) shouldBe
            listOf(VISIBLE_PIPELINES.size + HIDDEN_PIPELINES.size, VISIBLE_TEMPLATES.size + HIDDEN_TEMPLATES.size)

        withClue("a non-lensed principal never triggers the inventory call — the stub's request log must not grow") {
            stubRequests.size shouldBe before
        }
        withClue("and the promoter's walk DID reach the stub, so the log can tell the two apart") { before shouldBeGreaterThanOrEqual 1 }
    }

    @Test
    @Order(5)
    fun `with the target unreachable the promoter sees nothing, is told once per window, and the counter moved`() {
        ensureSeeded()
        val warnings = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(CLIENT_CLASS) as Logger
        logger.addAppender(warnings)
        val unreachableBefore = unreachableCount()
        try {
            stub.stop(0)
            client.javaClass.getMethod("invalidate", String::class.java).invoke(client, WS_NAME)
            val auth = sessionFor(PROMOTER)

            getJson("/api/v1/pipelines", auth).path("data").path("items").size() shouldBe 0
            getJson("/api/v1/templates", auth).path("data").path("items").size() shouldBe 0
            getJson("/api/v1/endpoints", auth).path("data").size() shouldBe 0
            call("/api/v1/pipelines/$PIPE_NEWER", auth).status shouldBe HTTP_NOT_FOUND
            call("/api/v1/templates?name=$T/newer.sql", auth).status shouldBe HTTP_NOT_FOUND
            withClue("the explorer says why, in the promotion page's words") {
                call("/partials/pipelines?prefix=", auth).body.contains("Could not read the target") shouldBe true
                call("/partials/templates?prefix=", auth).body.contains("Could not read the target") shouldBe true
            }
            railBadges(call("/pipelines", auth).body) shouldBe listOf(0, 0)
            val key = keyFor(PROMOTER)
            toolResult(tool("pipelines_list", "{}", key)).size() shouldBe 0
            refused(tool("pipelines_get", """{"id":"$PIPE_NEWER"}""", key)) shouldBe true
            withClue("never a 502 to a promoter's read: the promotion page is the one place the target's state is an error") {
                call("/promotion", auth).body.contains("Could not read the target") shouldBe true
            }

            val warns =
                warnings.list.filter {
                    it.level == Level.WARN &&
                        it.formattedMessage.contains("event=pipeline.promotion.lens_unavailable")
                }
            withClue("one WARN for the whole walk — the failure is cached for the window") { warns.size shouldBe 1 }
            warns.single().formattedMessage.contains("workspace=$WS_NAME") shouldBe true
            withClue("outcome=unreachable moved exactly once") { unreachableCount() shouldBe unreachableBefore + 1 }
        } finally {
            logger.detachAppender(warnings)
        }
    }

    // ------------------------------------------------------------------ the walk

    private data class Route(
        val pattern: String,
        val path: String,
        val handler: String,
    )

    private data class Answer(
        val status: Int,
        val body: String,
        val fingerprint: String,
    )

    /**
     * Every GET route the PROMOTER reaches — the non-public handlers, with well-formed
     * identifiers substituted, minus the promotion RECEIVER family (a server-key route) and
     * minus the routes the interceptor refuses the role (a 403 for every id tells the lens
     * nothing). Read once; the refusal probe is the role walk's job, not this suite's.
     */
    private fun reachableGetRoutes(): List<Route> =
        handlerPatterns()
            .filterNot { isPublic(it.first) }
            .filterNot { it.first.startsWith(PROMOTION_RECEIVER_PREFIX) }
            .map { (pattern, handler) -> Route(pattern, substitute(pattern, ABSENT_UUID, ABSENT_NAME), handler) }
            .distinct()
            .filter { call(it.path, sessionFor(PROMOTER)).status != HTTP_FORBIDDEN }
            .sortedBy { it.pattern }

    /**
     * The (hidden, absent) path pairs one route yields: for a pipeline route with an `{id}`,
     * one pair per hidden pipeline; for a template route (names travel as `?name=`), one pair
     * per hidden template; for a route with neither, none — it is walked once for the leak
     * check only.
     */
    private fun identifierPairs(route: Route): List<Pair<String, String>> =
        when {
            "{id}" in route.pattern && "pipelines" in route.pattern -> {
                HIDDEN_PIPELINE_IDS.map { substitute(route.pattern, it.toString(), ABSENT_NAME) to route.path }
            }

            "templates" in route.pattern && "{" !in route.pattern -> {
                HIDDEN_TEMPLATES.map { "${route.path}?name=$it&version=1" to "${route.path}?name=$ABSENT_NAME&version=1" }
            }

            else -> {
                emptyList()
            }
        }

    /** `(pattern, "Class#method")` for every GET handler, off `RequestMappingHandlerMapping` by reflection. */
    private fun handlerPatterns(): List<Pair<String, String>> {
        val mapping = context.getBean("requestMappingHandlerMapping")
        val methods = mapping.javaClass.getMethod("getHandlerMethods").invoke(mapping) as Map<*, *>
        return methods.entries.flatMap { (info, handlerMethod) ->
            val condition = info!!.javaClass.getMethod("getPathPatternsCondition").invoke(info)

            @Suppress("UNCHECKED_CAST")
            val patterns = condition.javaClass.getMethod("getPatternValues").invoke(condition) as Set<String>
            val verbs =
                info.javaClass.getMethod("getMethodsCondition").invoke(info).let { c ->
                    c.javaClass.getMethod("getMethods").invoke(c) as Set<*>
                }
            val isGet = verbs.isEmpty() || verbs.any { (it as Enum<*>).name == "GET" }
            val method = handlerMethod!!.javaClass.getMethod("getMethod").invoke(handlerMethod) as java.lang.reflect.Method
            val beanType = handlerMethod.javaClass.getMethod("getBeanType").invoke(handlerMethod) as Class<*>
            if (isGet) patterns.map { it to "${beanType.simpleName}#${method.name}" } else emptyList()
        }
    }

    private val publicPatterns: List<String> by lazy {
        val type = Class.forName("co.datapipelines.auth.PublicPaths")
        val instance = type.getField("INSTANCE").get(null)

        @Suppress("UNCHECKED_CAST")
        type.getMethod("getPATTERNS").invoke(instance) as List<String>
    }

    private val antMatcher = AntPathMatcher()

    private fun isPublic(pattern: String): Boolean =
        pattern == "/" || publicPatterns.any { antMatcher.match(it, substitute(pattern, ABSENT_UUID, ABSENT_NAME)) }

    private fun substitute(
        pattern: String,
        id: String,
        name: String,
    ): String =
        VARIABLE_PATTERN
            .replace(pattern) { match ->
                when (val variable = match.groupValues[1].substringBefore(':')) {
                    "version" -> "1"
                    "nodeId" -> "n1"
                    "name", "templateName" -> name
                    "workspace" -> "no-such-workspace"
                    else -> if (variable.lowercase().endsWith("id")) id else "nobody-owns-this"
                }
            }.replace("/**", "/x")
            .replace("*", "x")

    private fun call(
        path: String,
        authenticate: (RequestSpecification) -> RequestSpecification,
    ): Answer {
        val response =
            authenticate(given().port(port))
                .redirects()
                .follow(false)
                .cookie(CSRF_COOKIE, CSRF)
                .header(CSRF_HEADER, CSRF)
                .`when`()
                .get(path)
                .then()
                .extract()
        val body = response.asString()
        return Answer(response.statusCode(), body, fingerprint(body, path))
    }

    /**
     * The body with the identifiers the request carried and the correlation id removed — what
     * is left is CONTENT. Only the whole identifiers are stripped, never the path's segments:
     * a page that ignores the identifier (the templates page ignores `?name=`) still renders
     * the visible tree, and stripping `lt` from one side would make two identical pages differ.
     */
    private fun fingerprint(
        body: String,
        @Suppress("UNUSED_PARAMETER") path: String,
    ): String {
        var text = body
        (HIDDEN_TOKENS + ABSENT_UUID + ABSENT_NAME).forEach { token -> text = text.replace(token, "") }
        return text.replace(CORRELATION_ID, "").trim()
    }

    private fun getJson(
        path: String,
        authenticate: (RequestSpecification) -> RequestSpecification,
    ): JsonNode {
        val answer = call(path, authenticate)
        withClue("GET $path -> ${answer.status} ${answer.body.take(LEAK_EXCERPT)}") { answer.status shouldBe HTTP_OK }
        return MAPPER.readTree(answer.body)
    }

    private fun names(
        node: JsonNode,
        field: String,
    ): List<String> = node.map { it.path(field).asText() }

    private fun statNumber(html: String): Int =
        checkNotNull(STAT.find(html)) {
            "no stat tile in: ${html.take(LEAK_EXCERPT)}"
        }.groupValues[1].toInt()

    private fun railBadges(html: String): List<Int> = BADGE.findAll(html).map { it.groupValues[1].toInt() }.toList()

    private fun tool(
        tool: String,
        arguments: String,
        key: String,
    ): String = mcp("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""", key)

    private fun mcp(
        request: String,
        key: String,
    ): String =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body(request)
            .`when`()
            .post("/mcp")
            .then()
            .extract()
            .asString()

    /** The tool's structured result, parsed out of the SSE-or-JSON envelope's first text content. */
    private fun toolResult(body: String): JsonNode {
        val json = MAPPER.readTree(body.substringAfter("data:").trim().ifEmpty { body })
        val text =
            json
                .path("result")
                .path("content")
                .firstOrNull()
                ?.path("text")
                ?.asText()
        checkNotNull(text) { "no tool result in: ${body.take(LEAK_EXCERPT)}" }
        return MAPPER.readTree(text)
    }

    private fun refused(body: String): Boolean = body.contains("not_found") || body.contains("\"isError\":true")

    /** `datapipelines.promotion.lens.inventory{outcome=unreachable}` off the context's registry, by reflection (see [client]). */
    private fun unreachableCount(): Double {
        val registry = context.getBean(Class.forName("io.micrometer.core.instrument.MeterRegistry"))
        val search = registry.javaClass.getMethod("find", String::class.java).invoke(registry, LENS_INVENTORY_METRIC)
        val tagged = search.javaClass.getMethod("tag", String::class.java, String::class.java).invoke(search, "outcome", "unreachable")
        val counter = tagged.javaClass.getMethod("counter").invoke(tagged) ?: return 0.0
        return counter.javaClass.getMethod("count").invoke(counter) as Double
    }

    private fun sessionFor(role: String): (RequestSpecification) -> RequestSpecification =
        { spec -> spec.cookie(SESSION_COOKIE, sessionJwt(USERS.getValue(role), "$role@lenswalk.test")) }

    private fun keyHeaderFor(role: String): (RequestSpecification) -> RequestSpecification =
        { spec -> spec.header(API_KEY_HEADER, keyFor(role)) }

    private fun keyFor(role: String): String = KEYS.getValue(role).plaintext

    companion object {
        private const val SESSION_COOKIE = "dp_session"
        private const val CSRF_COOKIE = "dp_csrf"
        private const val CSRF_HEADER = "DP-CSRF-Token"
        private const val CSRF = "lenswalk-csrf"
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val SECRET_BYTES = 32
        private const val HTTP_OK = 200
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val LEAK_EXCERPT = 240
        private const val PROMOTION_RECEIVER_PREFIX = "/api/v1/promotion/"
        private const val CLIENT_CLASS = "co.datapipelines.web.pipelines.PromotionTargetClient"
        private const val LENS_INVENTORY_METRIC = "datapipelines.promotion.lens.inventory"
        private const val ABSENT_UUID = "0d0e0000-0000-0000-0000-0000000000ff"
        private const val ABSENT_VERSION = 9
        private const val ABSENT_NAME = "nobody/owns_this"
        private val SUCCESS = 200..299
        private val MAPPER = ObjectMapper()
        private val VARIABLE_PATTERN = Regex("\\{([^}]+)\\}")
        private val CORRELATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val STAT = Regex("app-stat-v\">(\\d+)<")
        private val BADGE = Regex("app-nav-badge app-rail-label\">(\\d+)<")

        /**
         * Floors, not targets — the 2026-09-21 walk: 57 promoter-reachable GET routes, 57
         * hidden/absent pairs per credential. A walk well under these is a broken scan.
         */
        private const val MINIMUM_ROUTES = 30
        private const val MINIMUM_PAIRS = 15

        private const val PROMOTER = "promoter"
        private const val AUTHOR = "author"
        private const val VIEWER = "viewer"
        private const val WS_ID = "abc00000-0000-0000-0000-000000000178"
        private const val WS_NAME = "lenswalk"

        /** Pipeline names under one folder, template ids under another, so a leak check on either family cannot match the other. */
        private const val P = "lp"
        private const val T = "lt"
        private val SERVER_KEY = "lens-sweep-" + UUID.randomUUID()

        private val PIPE_DRAFT_ONLY: UUID = UUID.fromString("1a000000-0000-0000-0000-000000000178")
        private val PIPE_ON_TARGET: UUID = UUID.fromString("1b000000-0000-0000-0000-000000000178")
        private val PIPE_BEHIND: UUID = UUID.fromString("1c000000-0000-0000-0000-000000000178")
        private val PIPE_NEWER: UUID = UUID.fromString("1d000000-0000-0000-0000-000000000178")
        private val PIPE_ABSENT: UUID = UUID.fromString("1e000000-0000-0000-0000-000000000178")
        private val HIDDEN_PIPELINE_IDS = listOf(PIPE_DRAFT_ONLY, PIPE_ON_TARGET, PIPE_BEHIND)
        private val HIDDEN_PIPELINES = listOf("$P/draft_only", "$P/on_target_same", "$P/behind")
        private val VISIBLE_PIPELINES = listOf("$P/absent_there", "$P/newer")
        private val HIDDEN_TEMPLATES = listOf("$T/draft_only.sql", "$T/on_target_same.sql", "$T/behind.sql")
        private val VISIBLE_TEMPLATES = listOf("$T/absent_there.sql", "$T/newer.sql")
        private const val HIDDEN_ENDPOINT = "/lens/v1/hidden_behind"
        private const val VISIBLE_ENDPOINT = "/lens/v1/visible_newer"

        /** Everything a promoter's answer must never carry: the hidden names, ids and the hidden endpoint's path. */
        private val HIDDEN_TOKENS: List<String> =
            HIDDEN_PIPELINES + HIDDEN_TEMPLATES + HIDDEN_PIPELINE_IDS.map { it.toString() } + HIDDEN_ENDPOINT + DRAFT_MARKER

        private val USERS: Map<String, String> =
            mapOf(
                VIEWER to "0a000000-0000-0000-0000-000000000178",
                AUTHOR to "0b000000-0000-0000-0000-000000000178",
                PROMOTER to "0c000000-0000-0000-0000-000000000178",
            )

        /** The keys' own `service` identities (keys v2 A13). */
        private const val PROMOTER_KEY_IDENTITY = "5e000000-0000-0000-0000-000000000178"
        private const val AUTHOR_KEY_IDENTITY = "5e000000-0000-0000-0000-000000000179"

        /**
         * Keys v2 (A13/A15): no viewer key exists — the lens CONTRAST key is an author-role key
         * (author is not lensed), and the lens key is a promoter-role key, the one lensed role.
         */
        private val KEYS: Map<String, E2eAuth.SeededKey> =
            mapOf(
                PROMOTER to E2eAuth.generateKey("promoter-lens-key", ownerId = PROMOTER_KEY_IDENTITY),
                AUTHOR to E2eAuth.generateKey("author-lens-key", ownerId = AUTHOR_KEY_IDENTITY),
            )

        private val random = SecureRandom()
        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        /** The stub higher environment: §18.1's inventory for the seeded workspace, every request logged. */
        private val stubRequests = CopyOnWriteArrayList<String>()
        private val stub: HttpServer by lazy {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
                server.createContext("/api/v1/promotion/inventory") { exchange ->
                    stubRequests += exchange.requestURI.toString()
                    val bytes = INVENTORY.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                server.start()
            }
        }

        /**
         * What the target holds: `on_target_same` at the same version and hash (nothing to
         * push), `behind` AHEAD of us, `newer` behind us. `draft_only` and `absent_there` are
         * unknown to it — the first is hidden by having no release, the second visible by
         * being absent there.
         */
        private val INVENTORY =
            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat","authoring_enabled":false,"workspace":"$WS_NAME",""" +
                """"pipelines":[{"name":"$P/on_target_same","current_version":2,"body_hash":"hash-same"},""" +
                """{"name":"$P/behind","current_version":5,"body_hash":"hash-theirs"},""" +
                """{"name":"$P/newer","current_version":2,"body_hash":"hash-old"}],""" +
                """"templates":[{"name":"$T/on_target_same.sql","current_version":2,"body_hash":"thash-same"},""" +
                """{"name":"$T/behind.sql","current_version":5,"body_hash":"thash-theirs"},""" +
                """{"name":"$T/newer.sql","current_version":2,"body_hash":"thash-old"}],"datasources":[]}}"""

        private const val PIPELINE_BODY =
            """{"schema_version":1,"name":"lens","display_name":"Lens","description":"",""" +
                """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"lt/newer.sql","version":3}}]}"""

        /** A visible object's pending DRAFT: its body carries this marker, which no promoter answer may contain. */
        private const val DRAFT_MARKER = "draft_marker_178_never_shown"

        /**
         * The visible pipeline's DRAFT (v4): `n1` pins the DRAFT template version (v4, whose SQL
         * is the marker — the node-SQL partial must render the RELEASE's `n1` instead), and `n2`
         * pins the released template v3, so `templates_used_by v3` carries a DRAFT pipeline pin
         * the lens must drop (178b).
         */
        private const val DRAFT_BODY =
            """{"schema_version":1,"name":"lens","display_name":"$DRAFT_MARKER","description":"$DRAFT_MARKER",""" +
                """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"lt/newer.sql","version":4}},""" +
                """{"id":"n2","type":"DQL","source":"tempdb","template":{"id":"lt/newer.sql","version":3},"depends_on":["n1"]}]}"""

        private fun sessionJwt(
            userId: String,
            email: String,
        ): String {
            val now = Instant.now()
            val header = b64("""{"alg":"HS256","typ":"JWT"}""")
            val payload =
                b64(
                    """{"sub":"$userId","email":"$email","name":"Lens Walk",""" +
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
                    seedPrincipals(statement)
                    val owner = USERS.getValue(VIEWER)
                    seedPipelines(statement, owner)
                    seedTemplates(statement, owner)
                    seedEndpoints(statement, owner)
                }
                seedKeys(connection)
            }
        }

        private fun seedPrincipals(statement: java.sql.Statement) {
            statement.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$WS_ID', '$WS_NAME', 'Lens Walk')")
            USERS.forEach { (role, id) ->
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                        "('$id', '$role@lenswalk.test', '$role', 'test', '$role-lens-sub', TRUE, FALSE)",
                )
                statement.execute("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WS_ID', '$id', '$role')")
            }
        }

        /** Pipelines: one row per clause of §10.2. `current_version` is the pointer; NULL for the draft-only one (D55). */
        private fun seedPipelines(
            statement: java.sql.Statement,
            owner: String,
        ) {
            statement.execute(
                """
                INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES
                    ('$PIPE_DRAFT_ONLY', '$P/draft_only', 'Draft only', '', '$owner', '$WS_ID', NULL),
                    ('$PIPE_ON_TARGET', '$P/on_target_same', 'On target', '', '$owner', '$WS_ID', 2),
                    ('$PIPE_BEHIND', '$P/behind', 'Behind', '', '$owner', '$WS_ID', 1),
                    ('$PIPE_NEWER', '$P/newer', 'Newer', '', '$owner', '$WS_ID', 3),
                    ('$PIPE_ABSENT', '$P/absent_there', 'Absent there', '', '$owner', '$WS_ID', 1)
                """.trimIndent(),
            )
            statement.execute(
                """
                INSERT INTO pipeline_versions
                    (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) VALUES
                    ('$PIPE_DRAFT_ONLY', 1, '$PIPELINE_BODY'::jsonb, 'hash-draft', 'DRAFT', '$owner', NULL, NULL),
                    ('$PIPE_ON_TARGET', 2, '$PIPELINE_BODY'::jsonb, 'hash-same', 'RELEASED', '$owner', '$owner', NOW()),
                    ('$PIPE_BEHIND', 1, '$PIPELINE_BODY'::jsonb, 'hash-ours', 'RELEASED', '$owner', '$owner', NOW()),
                    ('$PIPE_NEWER', 3, '$PIPELINE_BODY'::jsonb, 'hash-new', 'RELEASED', '$owner', '$owner', NOW()),
                    ('$PIPE_ABSENT', 1, '$PIPELINE_BODY'::jsonb, 'hash-absent', 'RELEASED', '$owner', '$owner', NOW()),
                    ('$PIPE_NEWER', 4, '$DRAFT_BODY'::jsonb, 'hash-new-draft', 'DRAFT', '$owner', NULL, NULL)
                """.trimIndent(),
            )
        }

        /** Templates: the same five clauses. `templates.id` is the surrogate, `name` the human id. */
        private fun seedTemplates(
            statement: java.sql.Statement,
            owner: String,
        ) {
            val templateIds = (HIDDEN_TEMPLATES + VISIBLE_TEMPLATES).associateWith { UUID.randomUUID() }
            val currentVersions =
                mapOf(
                    "$T/draft_only.sql" to null,
                    "$T/on_target_same.sql" to 2,
                    "$T/behind.sql" to 1,
                    "$T/newer.sql" to 3,
                    "$T/absent_there.sql" to 1,
                )
            val hashes =
                mapOf(
                    "$T/draft_only.sql" to "thash-draft",
                    "$T/on_target_same.sql" to "thash-same",
                    "$T/behind.sql" to "thash-ours",
                    "$T/newer.sql" to "thash-new",
                    "$T/absent_there.sql" to "thash-absent",
                )
            templateIds.forEach { (name, id) ->
                val current = currentVersions.getValue(name)
                statement.execute(
                    "INSERT INTO templates (id, name, display_name, description, current_version, workspace_id, created_by) " +
                        "VALUES ('$id', '$name', '$name', '', ${current ?: "NULL"}, '$WS_ID', '$owner')",
                )
                val version = current ?: 1
                val status = if (current == null) "DRAFT" else "RELEASED"
                val released = if (current == null) "NULL, NULL" else "'$owner', NOW()"
                val hash = hashes.getValue(name)
                statement.execute(
                    "INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, " +
                        "body_hash, status, created_by, released_by, released_at) VALUES " +
                        "('$id', $version, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT 1', '$hash', " +
                        "'$status', '$owner', $released)",
                )
                // The visible `newer` template also carries a pending DRAFT (v4) — a promoter must
                // never see its body, its pointer or its version row.
                if (name == "$T/newer.sql") {
                    statement.execute(
                        "INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, " +
                            "body_hash, status, created_by, released_by, released_at) VALUES " +
                            "('$id', 4, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT $DRAFT_MARKER', 'thash-new-draft', " +
                            "'DRAFT', '$owner', NULL, NULL)",
                    )
                }
            }
        }

        /** Endpoints: one over a hidden pipeline, one over a visible one. */
        private fun seedEndpoints(
            statement: java.sql.Statement,
            owner: String,
        ) {
            statement.execute(
                "INSERT INTO published_endpoints " +
                    "(id, workspace_id, path_pattern, pipeline_id, timeout_seconds, description, created_by) VALUES " +
                    "('${UUID.randomUUID()}', '$WS_ID', '$HIDDEN_ENDPOINT', '$PIPE_BEHIND', 30, 'hidden', '$owner'), " +
                    "('${UUID.randomUUID()}', '$WS_ID', '$VISIBLE_ENDPOINT', '$PIPE_NEWER', 30, 'visible', '$owner')",
            )
        }

        private fun seedKeys(connection: java.sql.Connection) {
            // The identities FIRST (the key rows FK to them) — keys v2 A13.
            connection.createStatement().use { statement ->
                KEYS.values.forEach { key ->
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject," +
                            " is_active, is_admin, kind) VALUES " +
                            "('${key.ownerId}', '${key.id.lowercase()}@keys.invalid', '${key.name}', 'key', '${key.id}'," +
                                " TRUE, FALSE, 'service')",
                    )
                }
            }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                        " VALUES (?, ?, ?, ?, ?, ?, 'mcp', ?)",
                ).use { ps ->
                    mapOf(PROMOTER to PROMOTER, AUTHOR to AUTHOR).forEach { (roleName, role) ->
                        val key = KEYS.getValue(roleName)
                        ps.setString(1, key.id)
                        ps.setObject(2, UUID.fromString(key.ownerId))
                        ps.setObject(3, UUID.fromString(USERS.getValue(role)))
                        ps.setString(4, key.name)
                        ps.setString(5, key.hash)
                        ps.setObject(6, UUID.fromString(WS_ID))
                        ps.setString(7, role)
                        ps.addBatch()
                    }
                    ps.executeBatch()
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
            // The sender half: the stub is the higher environment. One long window, so the
            // whole walk is one probe and the unreachable test's invalidate is what re-probes.
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${stub.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { SERVER_KEY }
            registry.add("datapipelines.deployment.promotion.inventory-cache-ttl-seconds") { "600" }
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
            runCatching { stub.stop(0) }
        }
    }
}
