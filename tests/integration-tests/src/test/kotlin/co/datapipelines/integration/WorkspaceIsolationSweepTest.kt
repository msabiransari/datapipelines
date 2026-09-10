package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

/**
 * **The IDOR sweep — the guard the whole RBAC design rests on** (design §3/§8.2, D-R5).
 *
 * Two workspaces are seeded with one of every entity by the sibling
 * [WorkspaceIsolationIntegrationTest]'s fixture (`acme` and `globex`, same content in each).
 * This suite then walks **every REST route the application registers** and **every MCP tool**,
 * substituting `globex`'s identifiers into every slot, as a member of `acme` — first on a
 * session, then on an API key — and asserts the answer is always the not-found one.
 *
 * ## What it asserts, and why not simply "404"
 * A handler can refuse a request before it ever reaches a repository — a malformed body, a
 * missing parameter, an unparseable UUID. Demanding 404 everywhere would therefore assert
 * things about request binding rather than about isolation, and would go green or red for
 * reasons that have nothing to do with the rule.
 *
 * So the invariant is stated as what must NEVER happen, which is exactly the leak:
 * - never `2xx` — a foreign row must not be read, executed or mutated;
 * - never `403` — "forbidden" tells the caller the thing EXISTS, which is the oracle D-R5
 *   removes. This is the assertion that fails when somebody "fixes" an isolation bug by
 *   adding a permission check instead of a workspace predicate.
 *
 * On top of that, every route that needs no request body — GET and DELETE — is held to the
 * stronger, exact `404`, because nothing else can legitimately refuse those first.
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

        withClue("routes that leaked globex to an acme SESSION") { leaks.shouldBeEmpty() }
    }

    @Test
    fun `no REST route hands an ACME key a GLOBEX row`() {
        WorkspaceIsolationIntegrationTest.ensureSeeded()

        val leaks = sweep { spec -> spec.header(API_KEY_HEADER, WorkspaceIsolationIntegrationTest.acmeKey()) }

        withClue("routes that leaked globex to an acme API KEY") { leaks.shouldBeEmpty() }
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

        withClue("MCP tools that answered for globex to an acme key") { leaks.shouldBeEmpty() }
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
            swept.any { it.path.startsWith("/api/v1/templates") } shouldBe true
            swept.any { it.path.startsWith("/partials/") } shouldBe true
        }
        MCP_TOOL_ARGUMENTS.size shouldBeGreaterThanOrEqual MINIMUM_SWEPT_TOOLS
    }

    // ------------------------------------------------------------------ the walk

    /** One route, with `globex`'s identifiers already substituted into its pattern. */
    private data class Route(
        val method: String,
        val path: String,
        val handler: String,
    )

    /**
     * Every route this sweep can drive, with foreign identifiers substituted.
     *
     * Skipped, each for a reason that is about the ROUTE rather than about convenience:
     * - **public routes** ([PublicPaths]-matched): they carry no principal and no workspace,
     *   so there is nothing for a foreign id to reach;
     * - **patterns whose variables the substitution table does not name**: substituting a
     *   made-up value would assert nothing about isolation. The table is asserted non-empty
     *   above, and an unnameable variable is a route worth adding to it rather than hiding.
     */
    private fun sweepableRoutes(): List<Route> =
        walkMappings()
            .flatMap { (patterns, methods, handler) ->
                patterns.flatMap { pattern ->
                    methods.map { method -> Triple(method, pattern, handler) }
                }
            }.filterNot { (_, pattern, _) -> isPublic(pattern) }
            .mapNotNull { (method, pattern, handler) ->
                substitute(pattern)?.let { Route(method, it, handler) }
            }.distinct()

    /** The leaked routes: `method path handler -> status`, empty when isolation holds. */
    private fun sweep(authenticate: (RequestSpecification) -> RequestSpecification): List<String> =
        sweepableRoutes().mapNotNull { route ->
            val status = call(route, authenticate)
            val bodyless = route.method == "GET" || route.method == "DELETE"
            val leaked =
                when {
                    // A foreign row was read, run or written. The leak this suite exists for.
                    status in SUCCESS_RANGE -> true

                    // "Forbidden" tells the caller the row EXISTS — the oracle D-R5 removes,
                    // and the shape a permission-check "fix" leaves behind.
                    status == HTTP_FORBIDDEN -> true

                    // A route with no body cannot legitimately refuse before resolving its id.
                    bodyless && status != HTTP_NOT_FOUND -> true

                    else -> false
                }
            if (leaked) "${route.method} ${route.path} (${route.handler}) -> $status" else null
        }

    private fun call(
        route: Route,
        authenticate: (RequestSpecification) -> RequestSpecification,
    ): Int {
        val csrf = "sweep-csrf-token"
        val spec =
            authenticate(given().port(port))
                .cookie(CSRF_COOKIE, csrf)
                .header(CSRF_HEADER, csrf)
                .contentType(ContentType.JSON)
                .body("{}")
        val request = spec.`when`()
        return when (route.method) {
            "GET" -> request.get(route.path)
            "POST" -> request.post(route.path)
            "PUT" -> request.put(route.path)
            "PATCH" -> request.patch(route.path)
            "DELETE" -> request.delete(route.path)
            else -> request.get(route.path)
        }.then().extract().statusCode()
    }

    /**
     * Substitutes `globex`'s identifiers into [pattern]'s variables, or null when a variable
     * has no foreign value to stand for.
     *
     * The table is keyed by VARIABLE NAME, not by position: `{id}` on a pipeline route and
     * `{name}` on a template route mean different things, and a positional substitution would
     * put a UUID where a name grammar is expected and prove only that the grammar rejects it.
     */
    private fun substitute(pattern: String): String? {
        var path = pattern
        VARIABLE_PATTERN.findAll(pattern).forEach { match ->
            val variable = match.groupValues[1].substringBefore(':')
            val value = FOREIGN_VALUES[variable] ?: return null
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

    private companion object {
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

        /**
         * Floors, not targets. The endpoint inventory taken for the 2026-09-08 security review
         * counted 133 routes; a floor well under it and well over "the scan broke" is what makes
         * a silently-empty walk impossible.
         */
        const val MINIMUM_SWEPT_ROUTES = 40
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
                "userId" to WorkspaceIsolationIntegrationTest.BOB,
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
