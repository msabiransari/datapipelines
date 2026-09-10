package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * **The IDOR sweep — the guard the whole RBAC design rests on** (design §3/§8.2, D-R5).
 *
 * Two workspaces are seeded with one of every entity by the sibling
 * [WorkspaceIsolationIntegrationTest]'s fixture (`acme` and `globex`, same content in each).
 * This suite then walks **every REST route the application registers** and **every MCP tool**,
 * substituting `globex`'s identifiers into every slot, as a member of `acme` — first on a
 * session, then on an API key — and asserts the answer is always the not-found one.
 *
 ## What it asserts — a DIFFERENTIAL, not a fixed status
 *
 * The first cut demanded "never 2xx, never 403, and 404 for bodyless routes", and that was
 * wrong in both directions. A handler can legitimately refuse before it reaches a repository
 * (a malformed body, an unparseable id), so a fixed status asserts request binding rather
 * than isolation. And a blanket "never 403" flagged every SUPER-ADMIN route — where a 403 is
 * returned for EVERY name, existing or not, and therefore leaks nothing at all.
 *
 * What D-R5 actually promises is narrower and testable: **the answer must not depend on
 * whether the foreign row exists.** So every route is called TWICE — once with `globex`'s
 * identifier, once with a well-formed identifier that exists nowhere — and the two answers
 * must be identical. That is the property, stated as the property:
 *
 * - **statuses differ** → an oracle. The caller can tell "someone else's" from "nobody's",
 *   which is the whole thing the rule removes;
 * - **the foreign call is 2xx** → a leak outright, whatever the control did.
 *
 * This is also what makes the assertion survive a "fix" that adds a permission check instead
 * of a workspace predicate: a 403-for-existing against a 404-for-missing is a differing pair,
 * and it fails here.
 *
 * ## Falsifying it
 * Remove the workspace predicate from ONE repository read and this suite names that route.
 * The 112 handback pastes the run: deleting `AND p.workspace_id = :workspaceId` from
 * `PipelineRepository.findById` turns `GET /api/v1/pipelines/{id}` from 404 into 200 and the
 * failure prints the path.
 *
 * ## The route list is REFLECTIVE, on purpose
 * It is read off the controllers' own `@*Mapping` annotations, so **a route added tomorrow is
 * swept without anybody remembering to add it here**. A hand-written list would guard the
 * routes somebody thought of, which is never the one that leaks.
 *
 * The reflection is deliberately PLAIN — `Class.forName` and `Method#getAnnotations`, reading
 * the annotation types by NAME. Spring's own `RequestMappingHandlerMapping` would be more
 * faithful, but it is not on this module's test compile classpath: module-structure §4.2 gives
 * `tests/integration-tests` `:modules:app` alone, and adding the MVC library would have meant
 * regenerating a STRICT dependency lock (run 004) to buy a nicety. Reading the annotations is
 * the same information from the same source; what it gives up is Spring's pattern
 * normalisation, which these routes do not use.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class WorkspaceIsolationSweepTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `no REST route hands an ACME session a GLOBEX row`() {
        WorkspaceIsolationIntegrationTest.ensureSeeded()

        val leaks = sweep { spec -> spec.cookie(SESSION_COOKIE, WorkspaceIsolationIntegrationTest.acmeSession()) }

        // Joined into ONE string rather than asserted empty as a list: a collection assertion
        // prints its first element and elides the rest, and the whole point of a sweep's
        // failure is the LIST — one route tells you almost nothing about which rule broke.
        leaks.joinToString("\n") shouldBe ""
    }

    @Test
    fun `no REST route hands an ACME key a GLOBEX row`() {
        WorkspaceIsolationIntegrationTest.ensureSeeded()

        val leaks = sweep { spec -> spec.header(API_KEY_HEADER, WorkspaceIsolationIntegrationTest.acmeKey()) }

        leaks.joinToString("\n") shouldBe ""
    }

    @Test
    fun `no MCP tool hands an ACME key a GLOBEX row`() {
        WorkspaceIsolationIntegrationTest.ensureSeeded()

        val leaks =
            MCP_TOOL_ARGUMENTS.mapNotNull { (tool, args) ->
                val body =
                    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$args}}"""
                val response =
                    given()
                        .port(port)
                        .header(API_KEY_HEADER, WorkspaceIsolationIntegrationTest.acmeKey())
                        .contentType(ContentType.JSON)
                        .accept("application/json, text/event-stream")
                        .body(body)
                        .`when`()
                        .post("/mcp")
                        .then()
                        .extract()
                        .asString()
                // The MCP envelope is 200-with-an-error-payload by protocol, so the leak shape
                // is different: a tool that ANSWERED with globex content is the failure, and a
                // not-found envelope is the pass. `isError` or a not_found code is what a
                // refusal looks like; a payload naming the foreign row is what a leak does.
                val refused = response.contains("not_found") || response.contains("\"isError\":true")
                if (refused) null else "$tool -> $response".take(LEAK_EXCERPT)
            }

        leaks.joinToString("\n") shouldBe ""
    }

    @Test
    fun `the sweep actually walks the surface - a vacuous walk cannot pass`() {
        // Non-vacuity. Without this, a scan that stopped matching would sweep zero routes and
        // report zero leaks — the strongest possible false green, and exactly the shape
        // MISTAKES.md's "a guard that cannot go red" entry describes.
        val swept = sweepableRoutes()

        swept.size shouldBeGreaterThanOrEqual MINIMUM_SWEPT_ROUTES
        withClue("the sweep reaches the three URL families a foreign id can travel in") {
            swept.any { it.path.startsWith("/api/v1/pipelines") } shouldBe true
            swept.any { it.path.startsWith("/api/v1/datasources") } shouldBe true
            swept.any { it.path.startsWith("/partials/") } shouldBe true
        }
        withClue("every swept route actually carries a foreign identifier") {
            swept.none { route -> FOREIGN_VALUES.values.none { it in route.path } } shouldBe true
        }
        MCP_TOOL_ARGUMENTS.size shouldBeGreaterThanOrEqual MINIMUM_SWEPT_TOOLS
    }

    // ------------------------------------------------------------------ the walk

    /** One route, with `globex`'s identifiers already substituted into its pattern. */
    private data class Route(
        val method: String,
        val path: String,
        val handler: String,
        /** The same route with identifiers that exist NOWHERE — the differential's control. */
        val controlPath: String = path,
    )

    /**
     * Every route this sweep can drive, with foreign identifiers substituted.
     *
     * Skipped, each for a reason that is about the ROUTE rather than about convenience:
     * - **public routes**: they carry no principal and no workspace, so there is nothing for a
     *   foreign id to reach;
     * - **patterns whose variables the substitution table does not name**: substituting a
     *   made-up value would assert nothing about isolation. An unnameable variable is a route
     *   worth adding to the table rather than hiding;
     * - **patterns with NO path variable at all.** This one is the important exclusion, and
     *   the first run of this suite is why it exists: without it the walk swept
     *   `GET /api/v1/auth/api-keys` — a listing of the caller's OWN keys — and called its 200
     *   a leak. A route with no caller-supplied id cannot carry a foreign one, so it is not
     *   what this suite is about; "no listing of A's contains a B row" is asserted directly by
     *   [WorkspaceIsolationIntegrationTest]. Sweeping them also made the suite MUTATING
     *   against a shared world (it fired every bodyless POST and DELETE), which is a second
     *   reason the exclusion is structural rather than cosmetic.
     */
    private fun sweepableRoutes(): List<Route> =
        walkMappings()
            .flatMap { (patterns, methods, handler) ->
                patterns.flatMap { pattern ->
                    methods.map { method -> Triple(method, pattern, handler) }
                }
            }.filterNot { (_, pattern, _) -> isPublic(pattern) }
            .mapNotNull { (method, pattern, handler) ->
                val foreign = substitute(pattern, FOREIGN_VALUES) ?: return@mapNotNull null
                val control = substitute(pattern, ABSENT_VALUES) ?: return@mapNotNull null
                Route(method, foreign, handler, control)
            }.distinct()

    /** The leaking routes: `method path handler -> foreign vs control`, empty when D-R5 holds. */
    private fun sweep(authenticate: (RequestSpecification) -> RequestSpecification): List<String> =
        sweepableRoutes().mapNotNull { route ->
            // The differential: the SAME route with identifiers that exist nowhere. Equal
            // answers mean the caller cannot tell "someone else's" from "nobody's", which is
            // exactly what D-R5 promises; different answers are the oracle.
            val foreign = call(route, authenticate)
            val control = call(route.copy(path = route.controlPath), authenticate)
            when {
                foreign.status != control.status -> {
                    "${route.method} ${route.path} (${route.handler}) -> ${foreign.status}, " +
                        "but a NONEXISTENT id gives ${control.status}"
                }

                // A 2xx with a DIFFERENT body is the leak itself: the page rendered something
                // the foreign row supplied. A 2xx with the SAME body is a screen that draws its
                // own empty state, which tells the caller nothing — and demanding 404 there
                // would be asserting a UI convention, not the isolation rule.
                foreign.status in SUCCESS_RANGE && foreign.fingerprint != control.fingerprint -> {
                    "${route.method} ${route.path} (${route.handler}) -> ${foreign.status} " +
                        "RENDERED FOREIGN CONTENT (its body differs from the nonexistent-id control)"
                }

                else -> {
                    null
                }
            }
        }

    /** One answer: its status, and a fingerprint of its body with the request's own ids removed. */
    private data class Answer(
        val status: Int,
        val fingerprint: String,
    )

    private fun call(
        route: Route,
        authenticate: (RequestSpecification) -> RequestSpecification,
    ): Answer {
        val csrf = "sweep-csrf-token"
        val spec =
            authenticate(given().port(port))
                .cookie(CSRF_COOKIE, csrf)
                .header(CSRF_HEADER, csrf)
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
        return Answer(response.statusCode(), fingerprint(response.asString(), route.path))
    }

    /**
     * The body, with everything that legitimately differs between the two calls removed: the
     * identifiers the request itself carried (they are echoed back on error pages and in
     * links) and correlation ids. What survives is the CONTENT — so two empty states
     * fingerprint alike, and a page that rendered a foreign row does not.
     */
    private fun fingerprint(
        body: String,
        path: String,
    ): String {
        var text = body
        (FOREIGN_VALUES.values + ABSENT_VALUES.values + path.split("/")).forEach { token ->
            if (token.length > MIN_TOKEN) text = text.replace(token, "")
        }
        return text.replace(CORRELATION_ID, "").trim()
    }

    /**
     * Substitutes `globex`'s identifiers into [pattern]'s variables, or null when a variable
     * has no foreign value to stand for.
     *
     * The table is keyed by VARIABLE NAME, not by position: `{id}` on a pipeline route and
     * `{name}` on a template route mean different things, and a positional substitution would
     * put a UUID where a name grammar is expected and prove only that the grammar rejects it.
     */
    private fun substitute(
        pattern: String,
        values: Map<String, String>,
    ): String? {
        val variables = VARIABLE_PATTERN.findAll(pattern).toList()
        // No variable = no caller-supplied id = nothing this suite can be about (see the KDoc).
        if (variables.isEmpty()) return null
        var path = pattern
        variables.forEach { match ->
            val variable = match.groupValues[1].substringBefore(':')
            val value = values[variable] ?: return null
            path = path.replace(match.value, value)
        }
        return path.takeUnless { "{" in it }
    }

    private fun isPublic(pattern: String): Boolean = pattern == "/" || PUBLIC_PREFIXES.any { pattern.startsWith(it) }

    /** `(path patterns, methods, "Class#method")` for every handler, read off its annotations. */
    private fun walkMappings(): List<Triple<Set<String>, Set<String>, String>> =
        controllerClasses().flatMap { type ->
            val classPrefixes = mappingOf(type.annotations)?.first ?: setOf("")
            type.declaredMethods.mapNotNull { method ->
                val (methodPaths, methodVerbs) = mappingOf(method.annotations) ?: return@mapNotNull null
                val patterns =
                    classPrefixes
                        .flatMap { prefix -> methodPaths.map { path -> join(prefix, path) } }
                        .toSet()
                Triple(patterns, methodVerbs, "${type.simpleName}#${method.name}")
            }
        }

    /**
     * The paths and HTTP verbs one `@*Mapping` annotation declares, or null when there is none.
     *
     * Matched by the annotation's SIMPLE NAME rather than by type, because the types are not on
     * this module's compile classpath (see the class KDoc). `value` and `path` are aliases in
     * Spring's own model, so both are read; a mapping that names neither is the empty path,
     * which is what `@GetMapping` on a class-prefixed handler means.
     */
    private fun mappingOf(annotations: Array<Annotation>): Pair<Set<String>, Set<String>>? {
        annotations.forEach { annotation ->
            val verbs = VERB_BY_ANNOTATION[annotation.annotationClass.simpleName] ?: return@forEach
            val paths =
                (readStrings(annotation, "value") + readStrings(annotation, "path"))
                    .ifEmpty { listOf("") }
                    .toSet()
            val declaredVerbs =
                verbs.ifEmpty {
                    // `@RequestMapping(method = [POST])` — the verb is a field, not the name.
                    readEnumNames(annotation, "method").ifEmpty { ALL_VERBS }
                }
            return paths to declaredVerbs
        }
        return null
    }

    private fun readStrings(
        annotation: Annotation,
        member: String,
    ): List<String> =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            (
                annotation.annotationClass.java
                    .getMethod(member)
                    .invoke(annotation) as Array<String>
            ).toList()
        }.getOrDefault(emptyList())

    private fun readEnumNames(
        annotation: Annotation,
        member: String,
    ): Set<String> =
        runCatching {
            (
                annotation.annotationClass.java
                    .getMethod(member)
                    .invoke(annotation) as Array<*>
            ).mapNotNull { (it as? Enum<*>)?.name }
                .toSet()
        }.getOrDefault(emptySet())

    private fun join(
        prefix: String,
        path: String,
    ): String {
        val joined = (prefix.trimEnd('/') + "/" + path.trimStart('/')).trimEnd('/')
        return joined.ifEmpty { "/" }
    }

    /**
     * The application's controller classes, found by walking the packaged classes under
     * `co.datapipelines.web` on the runtime classpath. `:modules:app` puts the web jar there,
     * so this sees exactly what the running application registers.
     */
    private fun controllerClasses(): List<Class<*>> {
        val marker = Class.forName("co.datapipelines.web.api.ApiResponse")
        val jar =
            java.io.File(
                marker.protectionDomain.codeSource.location
                    .toURI(),
            )
        val names =
            if (jar.isDirectory) {
                jar
                    .walkTopDown()
                    .filter { it.extension == "class" }
                    .map {
                        it
                            .relativeTo(jar)
                            .path
                            .removeSuffix(".class")
                            .replace(java.io.File.separatorChar, '.')
                    }.toList()
            } else {
                java.util.jar.JarFile(jar).use { archive ->
                    archive
                        .entries()
                        .toList()
                        .map { it.name }
                        .filter { it.endsWith(".class") }
                        .map { it.removeSuffix(".class").replace('/', '.') }
                }
            }
        return names
            .filter { it.startsWith(BASE_PACKAGE) && "$" !in it }
            .mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
            .filter { type -> type.annotations.any { it.annotationClass.simpleName in CONTROLLER_ANNOTATIONS } }
            .sortedBy { it.name }
    }

    companion object {
        /**
         * This suite's OWN discovery stub. It cannot borrow the sibling's: that one is closed
         * by the sibling's `@AfterAll`, and whichever suite runs second then boots against a
         * dead issuer — which is exactly how this suite failed its first two runs, with a
         * context that never started and four "failures" that had executed no assertion at all.
         */
        private val oidc = OidcDiscoveryStub()

        /**
         * The same containers and secrets the sibling suite configures — the WORLD is shared
         * deliberately, because this suite walks what that suite seeds, and an isolation proof
         * over a world nobody seeded proves nothing. Only the stub is this suite's own.
         */
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            WorkspaceIsolationIntegrationTest.properties(registry)
            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
            }
        }

        @JvmStatic
        @AfterAll
        fun closeStub() {
            oidc.close()
        }

        const val BASE_PACKAGE = "co.datapipelines.web"

        /**
         * What marks a class as a request handler. `@RestController` is meta-annotated
         * `@Controller`, but reflection over the CLASS sees only what is written on it, so
         * both are named.
         */
        val CONTROLLER_ANNOTATIONS = setOf("Controller", "RestController")

        val ALL_VERBS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")

        /** Spring's shorthand mappings, and the verb each one fixes. `RequestMapping` fixes none. */
        val VERB_BY_ANNOTATION: Map<String, Set<String>> =
            mapOf(
                "GetMapping" to setOf("GET"),
                "PostMapping" to setOf("POST"),
                "PutMapping" to setOf("PUT"),
                "PatchMapping" to setOf("PATCH"),
                "DeleteMapping" to setOf("DELETE"),
                "RequestMapping" to emptySet(),
            )
        const val API_KEY_HEADER = "DP-API-Key"
        const val SESSION_COOKIE = "dp_session"
        const val CSRF_COOKIE = "dp_csrf"
        const val CSRF_HEADER = "DP-CSRF-Token"

        val SUCCESS_RANGE = 200..299
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val LEAK_EXCERPT = 300

        /** Shorter tokens ("1", "GET") would blank out half the page and hide a real difference. */
        const val MIN_TOKEN = 6

        val CORRELATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /**
         * Floors, not targets. The endpoint inventory taken for the 2026-09-08 security review
         * counted 133 routes; a floor well under it and well over "the scan broke" is what makes
         * a silently-empty walk impossible.
         */
        const val MINIMUM_SWEPT_ROUTES = 25
        const val MINIMUM_SWEPT_TOOLS = 12

        val VARIABLE_PATTERN = Regex("\\{([^}]+)\\}")

        /**
         * `globex`'s identifiers, by the variable NAME a route uses for them. Every value here
         * exists in `globex` and in no other workspace, so a 200 can only mean a leak.
         */
        val FOREIGN_VALUES: Map<String, String> =
            mapOf(
                "id" to WorkspaceIsolationIntegrationTest.PIPE_GLOBEX,
                "pipelineId" to WorkspaceIsolationIntegrationTest.PIPE_GLOBEX,
                "executionId" to WorkspaceIsolationIntegrationTest.EXEC_GLOBEX,
                "name" to "globex_tpl",
                "templateName" to "globex_tpl",
                "version" to "1",
                "workspace" to "globex",
                // `userId` is deliberately ABSENT. Users are a GLOBAL entity managed by super
                // admins (D-R8), not a workspace-scoped one, so substituting another user's id
                // into `/api/v1/auth/users/{userId}/…` is not a cross-workspace probe — it is
                // an instance verb, and a non-super-admin's 403 there is the same 403 every
                // caller gets for every id. The 404 rule is about what a WORKSPACE contains.
            )

        /**
         * Every MCP tool that takes a workspace-scoped identifier, with `globex`'s. The tools
         * that take none (`calculators_*`, the bare listings) cannot carry a foreign id and are
         * covered by the listing assertions in [WorkspaceIsolationIntegrationTest] instead.
         */
        val MCP_TOOL_ARGUMENTS: Map<String, String> =
            mapOf(
                "pipelines_get" to """{"id":"${WorkspaceIsolationIntegrationTest.PIPE_GLOBEX}"}""",
                "pipelines_execute" to """{"id":"${WorkspaceIsolationIntegrationTest.PIPE_GLOBEX}"}""",
                "pipelines_update" to """{"id":"${WorkspaceIsolationIntegrationTest.PIPE_GLOBEX}","pipeline":{}}""",
                "templates_get" to """{"name":"globex_tpl"}""",
                "templates_used_by" to """{"name":"globex_tpl"}""",
                "templates_render" to """{"name":"globex_tpl","version":1,"parameters":{}}""",
                "templates_purge_draft" to """{"name":"globex_tpl"}""",
                "executions_get" to """{"execution_id":"${WorkspaceIsolationIntegrationTest.EXEC_GLOBEX}"}""",
                "executions_get_result" to """{"execution_id":"${WorkspaceIsolationIntegrationTest.EXEC_GLOBEX}"}""",
                "executions_cancel" to """{"execution_id":"${WorkspaceIsolationIntegrationTest.EXEC_GLOBEX}"}""",
                "datasources_get" to """{"name":"globex-only-db"}""",
                "datasources_get_schemas" to """{"name":"globex-only-db"}""",
                "datasources_get_tables" to """{"name":"globex-only-db"}""",
                "sql_probe" to """{"datasource":"globex-only-db","sql":"SELECT 1"}""",
                "endpoints_get" to """{"path_pattern":"/globex/report"}""",
            )

        /**
         * Well-formed identifiers that exist NOWHERE — the differential's control. Same SHAPE
         * as the foreign ones (a UUID where a UUID goes, a legal name where a name goes), so
         * what differs between the two calls is existence and nothing else: a control that
         * failed request binding would make every route look like an oracle.
         */
        val ABSENT_VALUES: Map<String, String> =
            mapOf(
                "id" to "0d0e0000-0000-0000-0000-0000000000ff",
                "pipelineId" to "0d0e0000-0000-0000-0000-0000000000ff",
                "executionId" to "0d0e0000-0000-0000-0000-0000000000fe",
                "name" to "nobody_owns_this",
                "templateName" to "nobody_owns_this",
                "version" to "1",
                "workspace" to "no-such-workspace",
            )

        /**
         * The route families that carry no principal and no workspace, so a foreign id has
         * nothing to reach through them: the marketing site, the packaged docs, the login
         * surface, static assets and the probes.
         *
         * Written here as PREFIXES rather than read from `auth`'s `PublicPaths`, because
         * module-structure §4.2 gives this suite `:modules:app` and nothing else. The
         * approximation is deliberately BROAD — over-excluding shrinks the sweep, which the
         * non-vacuity floor and the three-family assertion above are what catch.
         */
        val PUBLIC_PREFIXES =
            listOf("/login", "/oauth2", "/docs", "/skill", "/site", "/compare", "/assets", "/actuator", "/health", "/error", "/sitemap")
    }
}
