package co.datapipelines.application.endpoints

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
        val match = matcher("/nyc/revenue").match("/nyc/revenue")
        assertAll(
            { match?.endpoint?.pathPattern shouldBe "/nyc/revenue" },
            { match?.pathVariables shouldBe emptyMap() },
        )
    }

    @Test
    fun `a variable segment is extracted by name`() {
        val match = matcher("/nyc/revenue/{borough}").match("/nyc/revenue/Manhattan")
        assertAll(
            { match?.endpoint?.pathPattern shouldBe "/nyc/revenue/{borough}" },
            { match?.pathVariables shouldBe mapOf("borough" to "Manhattan") },
        )
    }

    @Test
    fun `several variables bind in one path`() {
        matcher("/{a}/x/{b}").match("/one/x/two")?.pathVariables shouldBe mapOf("a" to "one", "b" to "two")
    }

    @Test
    fun `a variable binds exactly one segment — it never spans a slash`() {
        assertAll(
            { matcher("/nyc/{borough}").match("/nyc/a/b") shouldBe null },
            { matcher("/nyc/{borough}").match("/nyc") shouldBe null },
        )
    }

    @Test
    fun `an unmatched path is null, not an exception`() {
        matcher("/nyc/revenue").match("/nope") shouldBe null
    }

    @Test
    fun `an empty registry matches nothing and is not an error`() {
        val empty = EndpointMatcher(emptyList())
        assertAll(
            { empty.size shouldBe 0 },
            { empty.match("/anything") shouldBe null },
        )
    }

    @Test
    fun `no request path ever matches two patterns of a conflict-free registry`() {
        val endpoints = CONFLICT_FREE.map { endpoint(it) }

        // The premise, asserted rather than assumed: this fixture really is conflict-free by the
        // same rule the repository enforces. Without this, a fixture that accidentally contained
        // a conflict would make the fuzz below fail for the wrong reason — or, worse, a fixture
        // where nothing overlapped anything trivially could hide a matcher that over-matched.
        val conflicts =
            endpoints.flatMap { a ->
                endpoints.filter { it !== a && EndpointPath.overlaps(a.parsed, it.parsed) }.map { a.pathPattern to it.pathPattern }
            }
        withClue("the fixture itself must satisfy §4.1") { conflicts.shouldBeEmpty() }

        val matcher = EndpointMatcher(endpoints)
        val random = Random(SEED)
        var matched = 0
        val doubles = mutableListOf<Pair<String, List<String>>>()
        repeat(FUZZ_PATHS) {
            val path = randomPath(random)
            val hits = matcher.matchAll(path)
            if (hits.size > 1) doubles += path to hits.map { it.pathPattern }
            if (hits.size == 1) matched++
        }

        assertAll(
            { withClue("paths matching more than one pattern") { doubles.shouldBeEmpty() } },
            // Non-vacuity: a generator that produced 200 paths matching nothing would prove
            // nothing at all, and would pass the assertion above trivially.
            { withClue("the fuzz must actually hit the registry") { matched shouldBeGreaterThan 0 } },
        )
    }

    @Test
    fun `a row whose stored pattern the parser rejects is dropped, not fatal`() {
        // Only reachable if a row was written around EndpointPath (§4.1 is strictly narrower than
        // what PathPatternParser accepts). The other endpoints must keep serving.
        val good = endpoint("/nyc/revenue")
        val bad = good.copy(pathPattern = "/nyc/{", parsed = good.parsed)
        val matcher = EndpointMatcher(listOf(good, bad))
        assertAll(
            { matcher.size shouldBe 1 },
            { matcher.match("/nyc/revenue")?.endpoint?.pathPattern shouldBe "/nyc/revenue" },
        )
    }

    private fun matcher(vararg patterns: String) = EndpointMatcher(patterns.map { endpoint(it) })

    private fun endpoint(pattern: String) =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = WORKSPACE,
            pathPattern = pattern,
            pipelineId = UUID.randomUUID(),
            timeoutSeconds = 30,
            description = "",
            isEnabled = true,
            createdBy = ACTOR,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    /**
     * A request path drawn from the same vocabulary the fixture uses, so a meaningful share of
     * them hit. Depth 1–4; each segment either a fixture word or a value a variable would bind.
     */
    private fun randomPath(random: Random): String = "/" + (1..random.nextInt(1, 5)).joinToString("/") { WORDS[random.nextInt(WORDS.size)] }

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        /** Fixed so a failure is reproducible; the point is coverage of shapes, not randomness. */
        const val SEED = 74L
        const val FUZZ_PATHS = 200

        /**
         * Conflict-free by construction and deliberately adversarial: a literal and a variable at
         * the same depth but in DIFFERENT positions, nested prefixes, and a three-deep pair that
         * differs only in its last literal.
         */
        val CONFLICT_FREE =
            listOf(
                "/nyc",
                "/nyc/revenue",
                "/nyc/revenue/{borough}",
                "/nyc/ridership/{borough}",
                "/lending/{product}/home",
                // NOT "/lending/summary/home" — that overlaps the line above (both match
                // '/lending/summary/home'), which the premise assertion caught when this fixture
                // first claimed to be conflict-free. Two segments instead of three is the fix.
                "/lending/summary",
                "/trade/{partner}/{flow}",
                "/health/live/deep/{probe}",
            )

        val WORDS =
            listOf("nyc", "revenue", "ridership", "lending", "summary", "home", "trade", "health", "live", "deep", "manhattan", "queens")
    }
}
