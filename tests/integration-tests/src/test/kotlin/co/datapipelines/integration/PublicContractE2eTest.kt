package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.util.AntPathMatcher
import java.io.File

/**
 * **What a `PublicPaths` glob makes public is a reviewed contract, row by row** (security-assurance
 * record §4 and §10.3 — "an intentional public route is a tested contract, not an exemption"; lane
 * 217a, A.2).
 *
 * `PublicPathsTest` freezes the globs and their reasons; `PublicRouteWalkerTest` freezes, at build
 * time, the public handler set `web` registers. What neither says is what each glob is FOR and what
 * an anonymous caller actually gets there. auth.md §8.6.2 carries one row per glob — its reason as
 * `PublicPaths` states it, every handler it covers, a probe path and the answer an anonymous GET must
 * get, what the route touches, and what happens on the other verbs — and this suite holds the
 * running application to it:
 *
 * - every handler under a public glob is named in the row of the FIRST glob (declaration order) that
 *   covers it, and every handler a row names is registered — so a controller that lands under an
 *   existing glob fails by name, and a row whose glob no longer covers anything fails by name;
 * - the rows and `PublicPaths` are the same set of globs, reason for reason;
 * - an anonymous GET of each probe answers the row's status, never with a stack trace and never
 *   setting a session cookie;
 * - an anonymous POST, PUT and DELETE on each probe — with a valid CSRF double-submit pair, so the
 *   refusal measured is the route's and not the CSRF filter's — is refused (403/404/405), unless the
 *   row names the suites that own those verbs (the login and OIDC flows have their own E2Es; this
 *   suite checks that those suites exist and does not repeat them) or states that every verb gets
 *   the GET's answer (`/error`, Boot's error page, answers every verb alike).
 *
 * The "principal · write · content" cell is the reviewed claim; this suite does not measure datastore
 * writes (the record's slice B effect witnesses do). Said here so the cell is not read as tested.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PublicContractE2eTest : EntryAssuranceE2eBase() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val rows = EntryContract.publicContract(RoleMatrixDocE2e.read())

    @Test
    fun `every public handler is named in its glob's row - and every row names only what is registered`() {
        val handlers = EntryDiscovery.handlerMethods(context).filter { it.public }
        val problems = unnamedHandlers(handlers) + staleRowEntries(handlers) + globDrift()

        println("event=public.contract globs=${rows.size} handlers=${handlers.size}")
        problems.joinToString("\n") shouldBe ""
        rows.size shouldBeGreaterThanOrEqual GLOB_FLOOR
        handlers.size shouldBeGreaterThanOrEqual PUBLIC_HANDLER_FLOOR
    }

    @Test
    fun `an anonymous GET of every probe answers as its row says - never a stack, never a session cookie`() {
        val problems = rows.flatMap { row -> answerProblems(row, "GET", row.answer) }
        println("event=public.contract.get probes=${rows.size}")
        problems.joinToString("\n") shouldBe ""
    }

    @Test
    fun `an anonymous POST, PUT and DELETE on every probe are refused - unless the row names the suites that own them`() {
        val problems = mutableListOf<String>()
        var refused = 0
        rows.forEach { row ->
            when {
                row.otherVerbs == REFUSED -> {
                    val answers = WRITE_VERBS.associateWith { verb -> anonymous(verb, row.probe).statusCode() }
                    refused += answers.values.count { it in REFUSAL_STATUSES }
                    answers
                        .filterValues { it !in REFUSAL_STATUSES }
                        .forEach { (verb, status) ->
                            problems +=
                                "${row.glob}: anonymous $verb ${row.probe} answered $status — the row says refused"
                        }
                }

                row.otherVerbs.startsWith(SAME_ANSWER) -> {
                    val expected =
                        row.otherVerbs
                            .removePrefix(SAME_ANSWER)
                            .trim()
                            .toInt()
                    WRITE_VERBS.forEach { verb -> problems += answerProblems(row, verb, expected) }
                }

                row.otherVerbs.startsWith(OWN_E2E) -> {
                    problems += unknownSuites(row)
                }

                else -> {
                    problems += "${row.glob}: 'other verbs' is `$REFUSED`, `$SAME_ANSWER<status>` or `$OWN_E2E<Suite>, <Suite>`"
                }
            }
        }
        println("event=public.contract.writes refused=$refused")
        problems.joinToString("\n") shouldBe ""
        refused shouldBeGreaterThanOrEqual REFUSED_FLOOR
    }

    // ------------------------------------------------------------------ the checks

    /** Every public handler must be named in the row of the FIRST glob that covers it. */
    private fun unnamedHandlers(handlers: List<EntryDiscovery.HandlerEntry>): List<String> =
        handlers.mapNotNull { h ->
            val entry = entryOf(h)
            val owner = EntryDiscovery.publicGlobsOf(h.pattern).firstOrNull() ?: "/"
            val named = rows.firstOrNull { it.glob == owner }?.handlers.orEmpty()
            if (entry in named) null else "$entry is under $owner but its §8.6.2 row does not name it"
        }

    /** Every entry a row names must exist: a public handler, the resource handler serving its probe, or a chain filter. */
    private fun staleRowEntries(handlers: List<EntryDiscovery.HandlerEntry>): List<String> {
        val registered = handlers.map(::entryOf).toSet()
        val chainFilters = securityChainFilters()
        val resourcePatterns = resourcePatterns()
        return rows.flatMap { row ->
            val empty =
                listOfNotNull(
                    "${row.glob}: the row names nothing — a glob that covers nothing is a stale permit".takeIf { row.handlers.isEmpty() },
                )
            empty +
                row.handlers.mapNotNull { named ->
                    val exists =
                        when {
                            named == RESOURCES -> resourcePatterns.any { antMatcher.match(it, row.probe) }
                            named.startsWith(FILTER_PREFIX) -> named.removePrefix(FILTER_PREFIX) in chainFilters
                            else -> named in registered
                        }
                    if (exists) null else "${row.glob}: names $named, which nothing registered serves"
                }
        }
    }

    /** The rows and `PublicPaths` are one set of globs, reason for reason. */
    private fun globDrift(): List<String> {
        val globs = EntryDiscovery.publicEntries
        return (rows.map { it.glob } - globs.keys).map { "§8.6.2 row $it is not a PublicPaths glob" } +
            (globs.keys - rows.map { it.glob }.toSet()).map { "PublicPaths glob $it has no §8.6.2 row" } +
            rows
                .filter { globs[it.glob] != null && globs[it.glob] != it.reason }
                .map { "${it.glob}: the row's reason is not PublicPaths' (\"${globs[it.glob]}\")" }
    }

    /** An anonymous [verb] on the row's probe: the [expected] status, no stack trace, no session cookie. */
    private fun answerProblems(
        row: EntryContract.PublicRow,
        verb: String,
        expected: Int,
    ): List<String> {
        val answer = anonymous(verb, row.probe)
        val cookies = sessionCookies(answer)
        return listOfNotNull(
            "${row.glob}: $verb ${row.probe} answered ${answer.statusCode()}, the row says $expected".takeIf {
                answer.statusCode() !=
                    expected
            },
            "${row.glob}: $verb ${row.probe} carries a stack trace".takeIf { STACK_FRAME.containsMatchIn(answer.asString()) },
            "${row.glob}: $verb ${row.probe} sets a session cookie: $cookies".takeIf { cookies.isNotEmpty() },
        )
    }

    /** An `own E2E:` cell is a reference — every suite it names must exist. */
    private fun unknownSuites(row: EntryContract.PublicRow): List<String> =
        row.otherVerbs
            .removePrefix(OWN_E2E)
            .split(",")
            .map { it.trim() }
            .filterNot(::suiteExists)
            .map { "${row.glob}: names $it as the owner of its other verbs, and no such suite exists" }

    private fun entryOf(h: EntryDiscovery.HandlerEntry): String = "${h.method} ${EntryDiscovery.canonical(h.pattern)} ${h.handler}"

    // ------------------------------------------------------------------ the wire

    /**
     * An anonymous request as a browser sends it; a write verb carries a matching CSRF pair (the
     * double-submit accepts any equal value), so the answer measured is the route's.
     */
    private fun anonymous(
        verb: String,
        path: String,
    ): ExtractableResponse<Response> {
        val spec =
            given()
                .port(port)
                .redirects()
                .follow(false)
                .urlEncodingEnabled(false)
                .accept(BROWSER_ACCEPT)
        if (verb != "GET") spec.cookie(E2eSession.CSRF_COOKIE, E2eSession.CSRF_TOKEN).header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
        val request = spec.`when`()
        return when (verb) {
            "GET" -> request.get(path)
            "POST" -> request.post(path)
            "PUT" -> request.put(path)
            else -> request.delete(path)
        }.then().extract()
    }

    /** A session cookie with a value: `dp_session` minted, or a servlet session (the app has none, by design). */
    private fun sessionCookies(answer: ExtractableResponse<Response>): List<String> =
        answer
            .headers()
            .getValues("Set-Cookie")
            .filter { cookie -> SESSION_COOKIES.any { cookie.startsWith("$it=") } && !cookie.startsWith("${E2eSession.COOKIE}=;") }

    private fun securityChainFilters(): Set<String> {
        val proxy = context.getBean("springSecurityFilterChain")
        val chains =
            Class
                .forName(
                    "org.springframework.security.web.FilterChainProxy",
                ).getMethod("getFilterChains")
                .invoke(proxy) as List<*>
        val chainType = Class.forName("org.springframework.security.web.SecurityFilterChain")
        return chains
            .flatMap { chain ->
                (chainType.getMethod("getFilters").invoke(chain) as List<*>).map { it!!.javaClass.simpleName }
            }.toSet()
    }

    private fun resourcePatterns(): List<String> {
        val mapping = context.getBean("resourceHandlerMapping")
        val type = Class.forName("org.springframework.web.servlet.handler.AbstractUrlHandlerMapping")
        return (type.getMethod("getHandlerMap").invoke(mapping) as Map<*, *>).keys.map { it.toString() }
    }

    /** A suite named in an "own E2E" cell must exist in this module or in a module's tests — a reference, never a repeat. */
    private fun suiteExists(name: String): Boolean {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val root = requireNotNull(dir) { "repository root not found" }
        return listOf(File(root, "modules"), File(root, "tests")).any { base ->
            base.walkTopDown().any { it.isFile && it.name == "$name.kt" && it.path.contains("/src/test/") }
        }
    }

    private companion object {
        const val RESOURCES = "resources"
        const val FILTER_PREFIX = "filter:"
        const val REFUSED = "refused"
        const val OWN_E2E = "own E2E: "
        const val SAME_ANSWER = "same answer: "
        const val BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        val WRITE_VERBS = listOf("POST", "PUT", "DELETE")
        val REFUSAL_STATUSES = setOf(403, 404, 405)
        val SESSION_COOKIES = listOf(E2eSession.COOKIE, "JSESSIONID")

        /** A JVM stack frame as a response body would carry one: `at pkg.Type.method(File.kt:12)`. */
        val STACK_FRAME = Regex("\\bat [a-z][\\w.$]*\\.[\\w$<>]+\\([\\w$]+\\.(java|kt):\\d+\\)")
        val antMatcher = AntPathMatcher()

        /** Floors, not targets (measured on this lane's base, 2026-09-24): 43 globs, 47 public handler routes. */
        const val GLOB_FLOOR = 43
        const val PUBLIC_HANDLER_FLOOR = 45
        const val REFUSED_FLOOR = 100
    }
}
