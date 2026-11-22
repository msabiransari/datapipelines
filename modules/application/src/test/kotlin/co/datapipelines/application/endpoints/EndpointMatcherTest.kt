package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.PipelineVersionStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * The matcher, and the invariant that makes it allowed to be this simple.
 *
 * `EndpointMatcher` does no ranking: it returns the FIRST pattern that matches, with no
 * longest-literal precedence and no tie-break. That is only correct because §4.1 refuses to
 * publish an ambiguous pattern, so at most one can ever match. The fuzz test below is what turns
 * that sentence into something that can go red: it publishes a conflict-free set (checked with
 * the same [EndpointPath.overlaps] the repository uses) and fires 200 request paths at it,
 * failing if any path ever matches two patterns.
 *
 * If someone later weakened the publish-time refusal, this suite would keep passing — the fuzz
 * builds its OWN conflict-free set. What it protects is the other direction: a matcher that
 * started matching more broadly than the grammar says (a stray `**`, a trailing-slash tolerance,
 * a variable spanning two segments) shows up here immediately.
 */
class EndpointMatcherTest {
    @Test
    fun `a literal path matches its endpoint and binds no variables`() {
        val match = matcher("/nyc/v1/revenue").match("/nyc/v1/revenue", EndpointMethod.GET)
        assertAll(
            { match?.endpoint?.pathPattern shouldBe "/nyc/v1/revenue" },
            { match?.pathVariables shouldBe emptyMap() },
        )
    }

    @Test
    fun `a variable segment is extracted by name`() {
        val match = matcher("/nyc/v1/revenue/{borough}").match("/nyc/v1/revenue/Manhattan", EndpointMethod.GET)
        assertAll(
            { match?.endpoint?.pathPattern shouldBe "/nyc/v1/revenue/{borough}" },
            { match?.pathVariables shouldBe mapOf("borough" to "Manhattan") },
        )
    }

    @Test
    fun `several variables bind in one path`() {
        matcher("/nyc/v1/{a}/x/{b}").match("/nyc/v1/one/x/two", EndpointMethod.GET)?.pathVariables shouldBe
            mapOf("a" to "one", "b" to "two")
    }

    @Test
    fun `a variable binds exactly one segment — it never spans a slash`() {
        assertAll(
            { matcher("/nyc/v1/{borough}").match("/nyc/v1/a/b", EndpointMethod.GET) shouldBe null },
            { matcher("/nyc/v1/{borough}").match("/nyc/v1", EndpointMethod.GET) shouldBe null },
        )
    }

    @Test
    fun `an unmatched path is null, not an exception`() {
        matcher("/nyc/v1/revenue").match("/nope", EndpointMethod.GET) shouldBe null
    }

    @Test
    fun `an empty registry matches nothing and is not an error`() {
        val empty = EndpointMatcher(emptyList())
        assertAll(
            { empty.size shouldBe 0 },
            { empty.match("/anything", EndpointMethod.GET) shouldBe null },
        )
    }

    @Test
    fun `a GET and a POST at one pattern are two routes, each matched only by its method`() {
        // #1037's core identity: the method is half of the route. The two entities share a
        // pattern and coexist in one registry — the conflict refusal is per method — and a
        // request for a method the path does not publish resolves to NOTHING (the opaque 404),
        // never to the sibling entity.
        val matcher =
            EndpointMatcher(
                listOf(
                    endpoint("/nyc/v1/revenue"),
                    endpoint("/nyc/v1/revenue", EndpointMethod.POST),
                ),
            )
        assertAll(
            { matcher.size shouldBe 2 },
            { matcher.match("/nyc/v1/revenue", EndpointMethod.GET)?.endpoint?.method shouldBe EndpointMethod.GET },
            { matcher.match("/nyc/v1/revenue", EndpointMethod.POST)?.endpoint?.method shouldBe EndpointMethod.POST },
            { matcher.match("/nyc/v1/revenue", EndpointMethod.PUT) shouldBe null },
            { matcher.matchAll("/nyc/v1/revenue", EndpointMethod.GET).map { it.method } shouldBe listOf(EndpointMethod.GET) },
        )
    }

    @Test
    fun `no request path ever matches two patterns of one method of a conflict-free registry`() {
        // #1037: the fuzz registry is published under EVERY method — same-pattern twins across
        // methods are the legal cross-method shape, so this stresses the method filter as part of
        // the same run: a matcher that forgot it would see four patterns per path and go red.
        val endpoints = EndpointMethod.ALL.flatMap { method -> CONFLICT_FREE.map { endpoint(it, method) } }

        // The premise, asserted rather than assumed: this fixture really is conflict-free by the
        // same rule the repository enforces (path-only — the method layering is #1037's, and the
        // twins below share patterns BY DESIGN). Without this, a fixture that accidentally
        // contained a conflict would make the fuzz below fail for the wrong reason — or, worse,
        // a fixture where nothing overlapped anything trivially could hide a matcher that
        // over-matched.
        val perPattern = endpoints.distinctBy { it.pathPattern }
        val conflicts =
            perPattern.flatMap { a ->
                perPattern.filter { it !== a && EndpointPath.overlaps(a.parsed, it.parsed) }.map { a.pathPattern to it.pathPattern }
            }
        withClue("the fixture itself must satisfy §4.1") { conflicts.shouldBeEmpty() }

        val matcher = EndpointMatcher(endpoints)
        val random = Random(SEED)
        val matched = mutableMapOf<EndpointMethod, Int>().withDefault { 0 }
        val doubles = mutableListOf<Triple<EndpointMethod, String, List<String>>>()
        repeat(FUZZ_PATHS) {
            val path = randomPath(random)
            EndpointMethod.ALL.forEach { method ->
                val hits = matcher.matchAll(path, method)
                if (hits.size > 1) doubles += Triple(method, path, hits.map { it.pathPattern })
                if (hits.size == 1) matched[method] = matched.getValue(method) + 1
            }
        }

        // Non-vacuity, per method: a generator that produced 200 paths matching nothing would prove nothing at
        // all, and would pass the assertion above trivially.
        val nonVacuity: List<() -> Unit> =
            EndpointMethod.ALL.map { method ->
                {
                    withClue("the ${method.name} fuzz must actually hit the registry") {
                        matched.getValue(method) shouldBeGreaterThan 0
                    }
                }
            }
        assertAll(
            { withClue("paths matching more than one pattern of one method") { doubles.shouldBeEmpty() } },
            *nonVacuity.toTypedArray(),
        )
    }

    @Test
    fun `a row whose stored pattern the parser rejects is dropped, not fatal`() {
        // Only reachable if a row was written around EndpointPath (§4.1 is strictly narrower than
        // what PathPatternParser accepts). The other endpoints must keep serving.
        val good = endpoint("/nyc/v1/revenue")
        val bad = good.copy(pathPattern = "/nyc/v1/{", parsed = good.parsed)
        val matcher = EndpointMatcher(listOf(good, bad))
        assertAll(
            { matcher.size shouldBe 1 },
            { matcher.match("/nyc/v1/revenue", EndpointMethod.GET)?.endpoint?.pathPattern shouldBe "/nyc/v1/revenue" },
        )
    }

    @Test
    fun `a row whose category is reserved is dropped, not served — the publish rule re-checked at serve`() {
        // R-EP5's serve-time half: publish refuses a v<n> or 'api' category, and a row that
        // somehow bypassed that refusal (a direct write) is never served either. The row LOADS —
        // the grammar is legal — so every other endpoint keeps working.
        val bypassed = endpoint("/v1/v1/revenue")
        val good = endpoint("/nyc/v1/revenue")
        val matcher = EndpointMatcher(listOf(bypassed, good))
        assertAll(
            { matcher.size shouldBe 1 },
            { matcher.match("/v1/v1/revenue", EndpointMethod.GET) shouldBe null },
            { matcher.match("/nyc/v1/revenue", EndpointMethod.GET)?.endpoint?.pathPattern shouldBe "/nyc/v1/revenue" },
        )
    }

    private fun matcher(vararg patterns: String) = EndpointMatcher(patterns.map { endpoint(it) })

    private fun endpoint(
        pattern: String,
        method: EndpointMethod = EndpointMethod.GET,
    ) = PublishedEndpoint.of(
        id = UUID.randomUUID(),
        workspaceId = WORKSPACE,
        pathPattern = pattern,
        isEnabled = true,
        createdBy = ACTOR,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        current = CurrentVersion(1, PipelineVersionStatus.RELEASED, EndpointVersionBody("test/pipeline", 1, 30, "")),
        method = method,
    )

    /**
     * A request path drawn so a meaningful share of them hit: half the draws instantiate a
     * fixture pattern (its variables filled from the vocabulary), half are free draws over the
     * same words. A purely random draw over 13 words almost never assembles a 4-segment
     * published path — the non-vacuity floor below exists because that generator proved it.
     */
    private fun randomPath(random: Random): String {
        if (random.nextBoolean()) {
            val pattern = CONFLICT_FREE[random.nextInt(CONFLICT_FREE.size)]
            return VARIABLE_IN_PATTERN.replace(pattern) { WORDS[random.nextInt(WORDS.size)] }
        }
        return "/" + (1..random.nextInt(1, 6)).joinToString("/") { WORDS[random.nextInt(WORDS.size)] }
    }

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        /** Fixed so a failure is reproducible; the point is coverage of shapes, not randomness. */
        const val SEED = 74L
        const val FUZZ_PATHS = 200

        /**
         * Conflict-free by construction and deliberately adversarial: a literal and a variable at
         * the same depth but in DIFFERENT positions, nested prefixes, and a pair that differs only
         * in its last literal. Every pattern carries the R-EP5 shape — a literal category, a
         * free-form literal version, then the path.
         */
        val CONFLICT_FREE =
            listOf(
                "/nyc/v1/revenue",
                "/nyc/v1/revenue/{borough}",
                "/nyc/v1/ridership/{borough}",
                "/lending/v1/{product}/home",
                // NOT "/lending/v1/summary/home" — that overlaps the line above (both match
                // '/lending/v1/summary/home'), which the premise assertion caught when this fixture
                // first claimed to be conflict-free. One segment fewer is the fix.
                "/lending/v1/summary",
                "/trade/v1/{partner}/{flow}",
                "/health/v1/live/deep/{probe}",
            )

        val WORDS =
            listOf(
                "nyc",
                "v1",
                "revenue",
                "ridership",
                "lending",
                "summary",
                "home",
                "trade",
                "health",
                "live",
                "deep",
                "manhattan",
                "queens",
            )

        val VARIABLE_IN_PATTERN = Regex("\\{[a-z_][a-z0-9_]*}")
    }
}
