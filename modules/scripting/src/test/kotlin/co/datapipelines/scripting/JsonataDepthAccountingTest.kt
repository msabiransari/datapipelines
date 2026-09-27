package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.engine
import com.dashjoin.jsonata.Jsonata
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The depth bound counts correctly for set-level expressions (#260).
 *
 * On 2026-09-26 the library's `Timebox` was proven to leak one depth unit per item for
 * the shape `( $n := 1; rows.( { "a": x, "b": y } ) )`: the evaluator flips
 * `isParallelCall` on the frame for the second object pair (`Jsonata.java:1130`), and
 * `Timebox` returns early from BOTH callbacks on such a frame (`Timebox.java:21–29`) —
 * the frame's own exit then skips its `depth--`. The leak made depth a function of INPUT
 * SIZE: a 240-row reshape refused with `pipeline.transform.resource_limit` at the default
 * `max-depth` of 100. The engine now counts entry/exit itself (no `isParallelCall` skip),
 * so these are the regression gates:
 *
 *  - (a) the probe's minimal shape evaluates at 250 rows under a depth of 100 (the base
 *    refuses at 99);
 *  - (b) the reporting agent's tercile body — the exact expression from #260's transcript,
 *    preserved in the store's 260-probe evidence — evaluates over its 238 rows;
 *  - (c) the library property the engine's counting is built on: entry and exit fire in
 *    balance at ANY row count and the true nesting stays tiny (measured with the same
 *    hooks the engine installs, the probe's Probe6, committed);
 *  - (d) `$sort` with a comparator and the `^()` order-by stay depth-clean at 4 000 rows.
 */
class JsonataDepthAccountingTest {
    /** The probe's minimal leaking shape: outer assignment + per-item block + 2-field object. */
    private val minimalShape =
        """( ${'$'}n := 1; inputs.ranked.( { "a": trip_rank, "b": trip_count } ) )"""

    /** The reporting agent's transform, verbatim from #260's first failed attempt. */
    private val tercileShape =
        """( ${'$'}n := ${'$'}count(inputs.ranked); ${'$'}classes := ["top","middle","bottom"]; """ +
            """${'$'}append([], inputs.ranked.( ${'$'}tercile := ${'$'}floor((trip_rank - 1) * 3 / ${'$'}n); """ +
            """{ "trip_rank": trip_rank, "volume_class": ${'$'}classes[${'$'}tercile], """ +
            """"avg": ${'$'}round(trip_count / days_in_month, 1) } )) )"""

    private val sortWithComparator =
        """${'$'}sort(inputs.ranked, function(${'$'}a, ${'$'}b) { """ +
            """${'$'}a.trip_rank > ${'$'}b.trip_rank or """ +
            """(${'$'}a.trip_rank = ${'$'}b.trip_rank and ${'$'}a.trip_count > ${'$'}b.trip_count) })"""

    private val orderBy = "inputs.ranked^(trip_rank).trip_count"

    private fun input(n: Int): Map<String, Any?> =
        mapOf(
            "inputs" to
                mapOf(
                    "ranked" to
                        (1..n).map { i ->
                            linkedMapOf<String, Any?>(
                                "trip_rank" to i,
                                "trip_count" to 20_000 - i * 7,
                                "days_in_month" to 31,
                            )
                        },
                ),
        )

    /** Production-shaped budgets: the default depth of 100, a generous clock. */
    private fun limits() = EvaluationLimits(Duration.ofSeconds(30), 100, now = ScriptingTestSupport.FIXED_NOW)

    @Test
    fun `the minimal set-level shape evaluates at rows far past max-depth`() {
        val result = engine.evaluate(engine.compile(minimalShape), input(250), limits()).shouldNotBeNull()
        withClue("every row reshaped exactly once") {
            (result as List<*>).shouldHaveSize(250)
        }
        val first = (result as List<*>).first() as Map<*, *>
        first["a"] shouldBe 1
        first["b"] shouldBe 19_993
    }

    @Test
    fun `the agent's tercile body evaluates over its 238 rows at the default depth`() {
        val result = engine.evaluate(engine.compile(tercileShape), input(238), limits())
        val rows = result.shouldNotBeNull() as List<*>
        withClue("every input row carries a volume_class") { rows shouldHaveSize 238 }

        fun volumeClass(tripRank: Int): Any? = (rows[tripRank - 1] as Map<*, *>)["volume_class"]
        volumeClass(1) shouldBe "top"
        volumeClass(80) shouldBe "top"
        volumeClass(81) shouldBe "middle"
        volumeClass(159) shouldBe "middle"
        volumeClass(160) shouldBe "bottom"
        volumeClass(238) shouldBe "bottom"
    }

    @Test
    fun `entry and exit stay balanced at every row count - the property the engine's counting relies on`() {
        val expr = Jsonata.jsonata(minimalShape)
        for (n in listOf(1, 6, 25, 50, 100, 238, 300, 500, 1000, 2000, 4000)) {
            val frame = Jsonata.Frame(null)
            var depth = 0
            var maxNesting = 0
            var entries = 0
            var exits = 0
            frame.setEvaluateEntryCallback { _, _, _ ->
                entries++
                depth++
                if (depth > maxNesting) maxNesting = depth
            }
            frame.setEvaluateExitCallback { _, _, _, _ ->
                exits++
                depth--
            }
            withClue("rows=$n") {
                expr.evaluate(input(n), frame)
                entries - exits shouldBe 0
                maxNesting.shouldBeLessThanOrEqual(10)
            }
        }
    }

    @Test
    fun `dollar-sort with a comparator and the order-by operator stay depth-clean over 4000 rows`() {
        val sorted = engine.evaluate(engine.compile(sortWithComparator), input(4000), limits()).shouldNotBeNull() as List<*>
        sorted.shouldHaveSize(4000)
        (sorted.first() as Map<*, *>)["trip_rank"].shouldNotBeNull()
        val ordered = engine.evaluate(engine.compile(orderBy), input(4000), limits()).shouldNotBeNull() as List<*>
        ordered.shouldHaveSize(4000)
        ordered.first() shouldBe 19_993
    }
}
