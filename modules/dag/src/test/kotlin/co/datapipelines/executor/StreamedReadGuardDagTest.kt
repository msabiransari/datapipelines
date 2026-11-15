package co.datapipelines.executor

import co.datapipelines.datasources.ResultRowReader
import co.datapipelines.logcapture.capturingLogEvents
import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.LakeAppendSession
import co.datapipelines.pipeline.LakeStatementExecution
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.staging.DuckDbStaging
import co.datapipelines.staging.DuckDbStagingFactory
import co.datapipelines.staging.DuckDbStagingProperties
import co.datapipelines.staging.StagingErrorCodes
import co.datapipelines.staging.StagingResultIncompleteException
import co.datapipelines.staging.StreamedReadGuard
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * #994 rule G2 on the executor's DuckDB tempdb reads (TEMPORARY, [StreamedReadGuard]): an author's query read through
 * `tempdbCursor` (class C5, the caller and the non-lake write-back outputs, with and without bound values) and the
 * managed append's scans (class C1). The failing statement is a REAL engine failure raised after the first streamed
 * chunk — a view whose expression calls `error()` from row [FAIL_AT] on — which the pinned driver ends silently when it
 * is streamed (StreamedResultTripwireTest in staging pins that). Every database here is a real DuckDB staging.
 */
class StreamedReadGuardDagTest {
    @TempDir
    lateinit var scratch: Path

    // ---------------------------------------------------------------- C5: the author's query, written then read

    @Test
    fun `a tempdb caller over a statement that fails mid-stream fails at the writing statement, and the caller got no row`() {
        withDuck { duck ->
            duck.staging.execute(FAILING_VIEW)
            val sink = RecordingSink()
            val error = duck.failureOf("SELECT v FROM boom", Fixtures.node("caller"), directSink = sink)
            error.code shouldBe duck.executeTimeFailureCode()
            withClue("the caller got no row") { sink.rows.shouldBeEmpty() }
            duck.guardRelations() shouldBe 0L
        }
    }

    @Test
    fun `a tempdb caller with a bound value over a statement that fails mid-stream fails the same way`() {
        withDuck { duck ->
            duck.staging.execute(FAILING_VIEW)
            val sink = RecordingSink()
            val error =
                duck.failureOf("SELECT v FROM boom WHERE v >= :lo", Fixtures.node("caller"), directSink = sink, values = mapOf("lo" to 0))
            error.code shouldBe duck.executeTimeFailureCode()
            sink.rows.shouldBeEmpty()
            duck.guardRelations() shouldBe 0L
        }
    }

    @Test
    fun `a tempdb write-back over a statement that fails mid-stream writes no row to the target, with and without a bound value`() {
        withDuck { duck ->
            duck.staging.execute(FAILING_VIEW)
            listOf("SELECT v FROM boom" to emptyMap(), "SELECT v FROM boom WHERE v >= :lo" to mapOf("lo" to 0)).forEach { (sql, values) ->
                val target = h2Datasource("tgt", listOf("CREATE TABLE tgt (v BIGINT)"))
                val node = Fixtures.node("wb", output = NodeOutput.Datasource("tgt", "tgt", WriteMode.APPEND))
                val registry = FakeDatasourceRegistry(mapOf("tgt" to target))
                val error = duck.failureOf(sql, node, registry = registry, values = values)
                withClue(sql) { error.code shouldBe duck.executeTimeFailureCode(node, registry) }
                withClue("$sql: the target got no row") { countIn(target) shouldBe 0 }
            }
            duck.guardRelations() shouldBe 0L
        }
    }

    @Test
    fun `a clean tempdb read keeps the author's ORDER BY over many chunks at four threads, and leaves no relation behind`() {
        withDuck(threads = 4) { duck ->
            duck.staging.execute("CREATE TABLE src AS SELECT range AS v, (range * 7919) % 300007 AS k FROM range($ORDERED_ROWS)")
            val sink = RecordingSink()
            val result = duck.run("SELECT v FROM src ORDER BY k DESC, v", Fixtures.node("caller"), directSink = sink)
            val expected = (0 until ORDERED_ROWS).sortedWith(compareByDescending<Long> { (it * 7919) % 300007 }.thenBy { it })
            result.rowsOut shouldBe ORDERED_ROWS
            withClue("non-vacuity: the expected order is not the insertion order") {
                (expected != (0 until ORDERED_ROWS).toList()) shouldBe
                    true
            }
            sink.rows.map { it.single() } shouldBe expected
            duck.guardRelations() shouldBe 0L
        }
    }

    @Test
    fun `a clean tempdb read keeps duplicate labels, an expression label, a decimal and a timestamp as the original reports them`() {
        withDuck { duck ->
            duck.staging.execute("CREATE TABLE src AS SELECT range AS v, range * 10 AS k FROM range(3)")
            val sql =
                "SELECT v AS a, k AS a, CAST(v AS DECIMAL(9,2)) AS d, TIMESTAMP '2026-01-02 03:04:05' AS ts, v + 1 " +
                    "FROM src ORDER BY v"
            val oracle = duck.preparedSchema(sql)
            val sink = RecordingSink()
            duck.run(sql, Fixtures.node("caller"), directSink = sink)
            val schema = sink.schema.shouldNotBeNull()
            schema shouldBe oracle
            schema.map { it.name } shouldBe listOf("a", "a", "d", "ts", "(v + 1)")
            schema[2].precision shouldBe 9
            schema[2].scale shouldBe 2
            sink.rows.map { it[0] } shouldBe listOf(0L, 1L, 2L)
            sink.rows.map { it[1] } shouldBe listOf(0L, 10L, 20L)
            sink.rows.map { it[4] } shouldBe listOf(1L, 2L, 3L)
        }
    }

    @Test
    fun `a tempdb caller with a bound value delivers only its row through the result store`() {
        withDuck { duck ->
            duck.staging.execute("CREATE TABLE seed AS SELECT range AS n FROM range(1, 4)")
            val store = InMemoryResultStore()
            val result = duck.run("SELECT n FROM seed WHERE n = :n", Fixtures.node("caller"), resultStore = store, values = mapOf("n" to 2))
            store.describe(result.callerResultRef.shouldNotBeNull()).shouldNotBeNull().firstPage shouldBe listOf(listOf(2L))
            duck.guardRelations() shouldBe 0L
        }
    }

    @Test
    fun `a written result over the scratch reservation is refused before the first row`() {
        withDuck(reservationBytes = SMALL_RESERVATION) { duck ->
            val sink = RecordingSink()
            val error = duck.failureOf("SELECT random() AS v FROM range($BIG_ROWS)", Fixtures.node("caller"), directSink = sink)
            error.code shouldBe StagingErrorCodes.SCRATCH_LIMIT_EXCEEDED
            sink.rows.shouldBeEmpty()
        }
        withDuck { duck ->
            val sink = RecordingSink()
            withClue("non-vacuity: the same read fits the default reservation") {
                duck.run("SELECT random() AS v FROM range($BIG_ROWS)", Fixtures.node("caller"), directSink = sink).rowsOut shouldBe BIG_ROWS
            }
        }
    }

    @Test
    fun `a cancel during the writing statement aborts the node and leaves no relation behind`() {
        withDuck { duck ->
            val sink = RecordingSink()
            val failure =
                runBlocking(Dispatchers.IO) {
                    val running =
                        async {
                            runCatching { duck.run(SLOW_SQL, Fixtures.node("caller"), directSink = sink) }.exceptionOrNull()
                        }
                    delay(CANCEL_AFTER_MS)
                    duck.registry.cancel(duck.executionId, AbortReason.CANCELLED)
                    running.await()
                }
            failure.shouldNotBeNull().shouldBeInstanceOf<ExecutionAbortedException>()
            sink.rows.shouldBeEmpty()
            runBlocking { duck.guardRelations() } shouldBe 0L
        }
    }

    // ---------------------------------------------------------------- C5: the plain read, logged (#994 round 4, D1–D4)

    /**
     * The lander's correction 3 (round 2's refusal R1), real engine: the lander's statement, a clean nested PIVOT
     * without an IN list over `random()`, read [VOLATILE_RUNS] times per caller and per binding (20 reads). Round 2's
     * counted form evaluated it twice and refused a read whenever the two evaluations kept different rows (the lander
     * measured 3 of 3); one read in 20 counting equal by chance still leaves 19 refusals, so this is red on that code
     * every run. Now the statement reads plain, once, logged with kind PIVOT and no SQL text.
     */
    @Test
    fun `a clean nested PIVOT over random() reads to the end on both callers, with and without a bound value, logged as PIVOT`() {
        withDuck { duck ->
            listOf(VOLATILE_PIVOT to emptyMap(), VOLATILE_PIVOT_BOUND to mapOf("p" to HALF)).forEach { (sql, values) ->
                repeat(VOLATILE_RUNS) { run ->
                    readsPlainOnBothCallers(duck, sql, values, "run $run") { rows ->
                        withClue(
                            "non-vacuity: random() kept some rows and dropped some",
                        ) { (rows in 1 until VOLATILE_SOURCE) shouldBe true }
                    }
                }
            }
            duck.guardRelations() shouldBe 0L
        }
    }

    /**
     * The deterministic stand-in for the case above: the source keeps the rows whose `nextval` is at most
     * [SEQUENCE_KEPT], so its first evaluation keeps exactly that many and any second evaluation keeps none. A guard
     * that evaluates the statement twice disagrees with itself on every read (round 2's counted form: count
     * [SEQUENCE_KEPT], read 0, refused); one evaluation reads exactly [SEQUENCE_KEPT] rows.
     */
    @Test
    fun `a nested PIVOT over a source that changes per evaluation reads one evaluation on both callers, bound or not`() {
        withDuck { duck ->
            listOf(SEQUENCE_PIVOT to emptyMap(), SEQUENCE_PIVOT_BOUND to mapOf("lim" to SEQUENCE_KEPT)).forEach { (sql, values) ->
                readsPlainOnBothCallers(duck, sql, values, "stand-in", before = { duck.staging.execute(RESET_SEQUENCE) }) { rows ->
                    rows shouldBe SEQUENCE_KEPT
                }
            }
            duck.guardRelations() shouldBe 0L
        }
    }

    /**
     * Round 1's written form takes a PIVOT WITH an IN list (the engine's parse admits it), so a mid-stream engine failure
     * lands at the writing statement, classified by the staging's `classifyEngineFailure` exactly as today's read of a
     * failure at execute ([Duck.executeTimeFailureCode]); nothing reaches the caller or the target. With the guard taken
     * out the same statement hands back a short result with no failure (round 2 measured 266,240 of 400,000 rows).
     */
    @Test
    fun `a PIVOT with an IN list that fails mid-stream fails at the writing statement on both callers, with and without a bound value`() {
        withDuck { duck ->
            duck.staging.execute(FAILING_VIEW)
            duck.staging.execute(PIVOT_SMALL)
            val callerCode = duck.executeTimeFailureCode()
            listOf(IN_PIVOT to emptyMap(), "$IN_PIVOT WHERE b.v >= :lo" to mapOf("lo" to 0)).forEach { (sql, values) ->
                val sink = RecordingSink()
                var outcome: Result<NodeResult>? = null
                val lines =
                    unguardedLines {
                        outcome = runCatching { runBlocking { duck.run(sql, Fixtures.node("caller"), directSink = sink, values = values) } }
                    }
                val failure = outcome.shouldNotBeNull().exceptionOrNull()
                val error =
                    withClue("$sql: the caller was handed ${sink.rows.size} of $ROWS rows and ${failure ?: "no failure"}") {
                        failure.shouldBeInstanceOf<NodeFailedSignal>().error
                    }
                withClue("$sql: the failure is the writing statement's engine error, as classifyEngineFailure maps it") {
                    error.code shouldBe callerCode
                }
                withClue("$sql: the caller got no row") { sink.rows.shouldBeEmpty() }
                withClue("$sql: the written form took it, so no unguarded-read line") { lines.shouldBeEmpty() }

                val target = h2Datasource("tgt", listOf("CREATE TABLE tgt (v BIGINT, g0 BIGINT, g1 BIGINT, g2 BIGINT)"))
                val node = Fixtures.node("wb", output = NodeOutput.Datasource("tgt", "tgt", WriteMode.APPEND))
                val registry = FakeDatasourceRegistry(mapOf("tgt" to target))
                val writeBackCode = duck.executeTimeFailureCode(node, registry)
                val wbError = duck.failureOf(sql, node, registry = registry, values = values)
                withClue("$sql: write-back ${wbError.message} ${wbError.details}") { wbError.code shouldBe writeBackCode }
                withClue("$sql: the target got no row") { countIn(target) shouldBe 0 }
            }
            duck.guardRelations() shouldBe 0L
        }
    }

    /**
     * #994 D2 (owner ruling 2026-11-15): the SHOW kinds read plain, logged; and so does a PIVOT without an IN list (D1).
     * Each case gives the plain read's labels, type names, rows and order, and one unguarded-read line with its kind and
     * no SQL text. SUMMARIZE's [APPROXIMATE_SUMMARIZE_COLUMNS] are approximate quantiles that differ between two
     * evaluations of the same text (round 3's probe), so those columns are not compared; every other column is.
     */
    @Test
    fun `SHOW TABLES, SHOW, DESCRIBE, SUMMARIZE and a PIVOT without IN read as the plain read, each with its unguarded-read line`() {
        withDuck { duck ->
            duck.staging.execute("CREATE TABLE src AS SELECT range AS v, range % 3 AS g, 'x' || range AS s FROM range(50)")
            val cases =
                listOf(
                    Triple("SHOW TABLES", emptyMap(), "SHOW"),
                    Triple("SHOW src", emptyMap(), "SHOW"),
                    Triple("DESCRIBE src", emptyMap(), "DESCRIBE"),
                    Triple("SUMMARIZE src", emptyMap(), "SUMMARIZE"),
                    Triple("SUMMARIZE (SELECT * FROM src WHERE v >= :lo)", mapOf("lo" to 10), "SUMMARIZE"),
                    Triple("PIVOT src ON g USING count(*) GROUP BY v ORDER BY v", emptyMap<String, Any?>(), "PIVOT"),
                    Triple("SELECT * FROM (PIVOT src ON g USING sum(v) GROUP BY s) ORDER BY s;", emptyMap(), "PIVOT"),
                )
            cases.forEach { (sql, values, kind) ->
                val oracle = runBlocking { duck.plainRead(sql, values) }
                val sink = RecordingSink()
                val lines = unguardedLines { runBlocking { duck.run(sql, Fixtures.node("caller"), directSink = sink, values = values) } }
                withClue("$sql: labels and type names") { sink.schema shouldBe oracle.first }
                val stable = oracle.first.indices.filter { oracle.first[it].name !in APPROXIMATE_SUMMARIZE_COLUMNS }
                withClue("$sql: rows in order") { sink.rows.map { it.slice(stable) } shouldBe oracle.second.map { it.slice(stable) } }
                withClue("$sql: non-vacuity") { oracle.second.isNotEmpty() shouldBe true }
                withClue("$sql: one line, its kind, no SQL text") { lines shouldBe listOf(duck.unguardedLine("caller", kind)) }
            }
            val sink = RecordingSink()
            runBlocking { duck.run("SHOW TABLES", Fixtures.node("caller"), directSink = sink) }
            withClue("SHOW TABLES lists no guard relation") { sink.rows shouldBe listOf(listOf("src")) }

            val target = h2Datasource("tgt", listOf("CREATE TABLE tgt (name VARCHAR(64))"))
            val node = Fixtures.node("wb", output = NodeOutput.Datasource("tgt", "tgt", WriteMode.APPEND))
            val wbLines =
                unguardedLines { runBlocking { duck.run("SHOW TABLES", node, registry = FakeDatasourceRegistry(mapOf("tgt" to target))) } }
            withClue("SHOW TABLES through the write-back: one row, no guard relation") { namesIn(target) shouldBe listOf("src") }
            wbLines shouldBe listOf(duck.unguardedLine("wb", "SHOW"))
            duck.guardRelations() shouldBe 0L
        }
    }

    /**
     * Round 3's security finding: a text that closes a wrapper's parenthesis early smuggles statements into the wrapper,
     * and the driver runs all but the last at prepare (round 2's count wrap dropped `victim`). No wrapper exists now: the
     * written form's gate parses the text alone and refuses it, so it reaches the plain read, which refuses it at parse
     * exactly as before this lane. On both callers, with and without a bound value; `victim` survives every read.
     */
    @Test
    fun `a text that closes a wrapper early is refused as the plain read refuses it, and its smuggled DROP never runs, on both callers`() {
        withDuck { duck ->
            duck.staging.execute("CREATE TABLE victim AS SELECT 1 AS v")
            listOf(INJECTION to emptyMap(), INJECTION_BOUND to mapOf("x" to 1)).forEach { (sql, values) ->
                val plain = runCatching { runBlocking { duck.plainRead(sql, values) } }.exceptionOrNull()
                withClue("$sql: the oracle, the plain read, refuses it at parse") {
                    plain.shouldBeInstanceOf<java.sql.SQLException>().message.shouldNotBeNull() shouldContain PARSER_REFUSAL
                }
                withClue("non-vacuity: the oracle's refusal ran nothing") { duck.victims() shouldBe 1L }

                val callerError = duck.failureOf(sql, Fixtures.node("caller"), directSink = RecordingSink(), values = values)
                withClue("$sql: caller - victim still exists") { duck.victims() shouldBe 1L }
                withClue("$sql: caller - ${callerError.code} ${callerError.message}") {
                    callerError.exception.toString() shouldContain PARSER_REFUSAL
                }

                val target = h2Datasource("tgt", listOf("CREATE TABLE tgt (a BIGINT)"))
                val node = Fixtures.node("wb", output = NodeOutput.Datasource("tgt", "tgt", WriteMode.APPEND))
                val wbError = duck.failureOf(sql, node, registry = FakeDatasourceRegistry(mapOf("tgt" to target)), values = values)
                withClue("$sql: write-back - victim still exists") { duck.victims() shouldBe 1L }
                withClue("$sql: write-back - ${wbError.code} ${wbError.message}") {
                    wbError.exception.toString() shouldContain
                        PARSER_REFUSAL
                }
                withClue("$sql: both callers refuse it with the same code") { wbError.code shouldBe callerError.code }
            }
        }
    }

    /**
     * PINS today's behaviour, owner ruling 2026-11-15 (#994 D2), follow-up [CATALOG_FOLLOW_UP]: a catalog SELECT that
     * round 1's written form takes can see the relation that form is writing. It is a race inside the engine, measured
     * on the pin over 100 raw-driver runs each (store probe `r4-catalog-count-probe2-run.txt`): the guard's `dp_guard_`
     * relation was one extra row of a `duckdb_tables()` listing 100 times, of an `information_schema.tables` listing
     * 99 times, and a `count(*)` over `information_schema.tables` was one higher 5 times (over `duckdb_tables()` 0).
     * So each read here is the plain read's rows plus at most one guard row (a count: the plain count or one higher),
     * and the guard row appears in at least one of [CATALOG_RUNS] `duckdb_tables()` listings. Goes red when that
     * changes; then correct staging.md §8.5 and the follow-up issue.
     */
    @Test
    fun `PIN - a catalog SELECT through the written form may list the guard's own relation as one extra row`() {
        withDuck { duck ->
            duck.staging.execute("CREATE TABLE src AS SELECT 1 AS v")
            val pin = "pinned behaviour, follow-up $CATALOG_FOLLOW_UP"
            val listed = mutableMapOf<String, Int>()
            listOf(SCHEMA_LISTING, DUCKDB_TABLES_LISTING).forEach { sql ->
                val plain = runBlocking { duck.plainRead(sql, emptyMap()) }.second
                withClue("non-vacuity: $sql lists the table") { plain shouldBe listOf(listOf("src")) }
                repeat(CATALOG_RUNS) { run ->
                    val sink = RecordingSink()
                    val lines = unguardedLines { runBlocking { duck.run(sql, Fixtures.node("caller"), directSink = sink) } }
                    withClue("$sql: the written form took it") { lines.shouldBeEmpty() }
                    val (guard, rest) = sink.rows.partition { (it[0] as String).startsWith(StreamedReadGuard.RELATION_PREFIX) }
                    withClue("$sql, run $run ($pin): at most one extra row, the guard's") { (guard.size <= 1) shouldBe true }
                    withClue("$sql, run $run ($pin): every other row is the plain read's") { rest shouldBe plain }
                    listed.merge(sql, guard.size, Int::plus)
                }
            }
            withClue(
                "$DUCKDB_TABLES_LISTING ($pin): the guard's relation was listed in ${listed[DUCKDB_TABLES_LISTING]} of $CATALOG_RUNS reads",
            ) {
                ((listed[DUCKDB_TABLES_LISTING] ?: 0) >= 1) shouldBe true
            }

            listOf("SELECT count(*) AS n FROM information_schema.tables", "SELECT count(*) AS n FROM duckdb_tables()").forEach { count ->
                val plainCount = runBlocking { duck.plainRead(count, emptyMap()) }.second.single().single() as Long
                val sink = RecordingSink()
                runBlocking { duck.run(count, Fixtures.node("caller"), directSink = sink) }
                val caller = sink.rows.single().single() as Long
                withClue("$count on the caller ($pin): $caller against the plain read's $plainCount") {
                    (caller in plainCount..plainCount + 1) shouldBe true
                }
                val target = h2Datasource("tgt", listOf("CREATE TABLE tgt (n BIGINT)"))
                val node = Fixtures.node("wb", output = NodeOutput.Datasource("tgt", "tgt", WriteMode.APPEND))
                runBlocking { duck.run(count, node, registry = FakeDatasourceRegistry(mapOf("tgt" to target))) }
                val written = DriverManager.getConnection(target.jdbcUrl, "sa", "").use { c -> oneInt(c, "SELECT n FROM tgt").toLong() }
                withClue("$count on the write-back ($pin): $written against the plain read's $plainCount") {
                    (written in plainCount..plainCount + 1) shouldBe true
                }
            }
            duck.guardRelations() shouldBe 0L
        }
    }

    /**
     * [sql] through the caller and the non-lake write-back, each read once after [before]: no failure, every row the read
     * reports delivered ([rowsCheck] judges the count), and exactly one unguarded-read line with kind PIVOT and no SQL.
     */
    private fun readsPlainOnBothCallers(
        duck: Duck,
        sql: String,
        values: Map<String, Any?>,
        clue: String,
        before: suspend () -> Unit = {},
        rowsCheck: (Long) -> Unit,
    ) {
        val sink = RecordingSink()
        var outcome: Result<NodeResult>? = null
        val lines =
            unguardedLines {
                outcome =
                    runCatching {
                        runBlocking {
                            before()
                            duck.run(sql, Fixtures.node("caller"), directSink = sink, values = values)
                        }
                    }
            }
        val result = outcome.shouldNotBeNull()
        withClue("$sql, $clue: the caller's read was refused: ${(result.exceptionOrNull() as? NodeFailedSignal)?.error}") {
            result.exceptionOrNull() shouldBe null
        }
        withClue("$sql, $clue: the caller got every row") { sink.rows.size.toLong() shouldBe result.getOrThrow().rowsOut }
        rowsCheck(sink.rows.size.toLong())
        withClue("$sql, $clue: caller's line") { lines shouldBe listOf(duck.unguardedLine("caller", "PIVOT")) }

        val target = h2Datasource("tgt", listOf("CREATE TABLE tgt (i BIGINT, g0 BIGINT, g1 BIGINT)"))
        val node = Fixtures.node("wb", output = NodeOutput.Datasource("tgt", "tgt", WriteMode.APPEND))
        var wbOutcome: Result<NodeResult>? = null
        val wbLines =
            unguardedLines {
                wbOutcome =
                    runCatching {
                        runBlocking {
                            before()
                            duck.run(sql, node, registry = FakeDatasourceRegistry(mapOf("tgt" to target)), values = values)
                        }
                    }
            }
        val wb = wbOutcome.shouldNotBeNull()
        withClue("$sql, $clue: the write-back's read was refused: ${(wb.exceptionOrNull() as? NodeFailedSignal)?.error}") {
            wb.exceptionOrNull() shouldBe null
        }
        withClue("$sql, $clue: the target got every row") { countIn(target).toLong() shouldBe wb.getOrThrow().rowsOut }
        rowsCheck(countIn(target).toLong())
        withClue("$sql, $clue: write-back's line") { wbLines shouldBe listOf(duck.unguardedLine("wb", "PIVOT")) }
    }

    @Test
    fun `PRAGMA and EXPLAIN still read, each with one unguarded-read line naming its kind and no SQL text`() {
        withDuck { duck ->
            duck.staging.execute("CREATE TABLE src_secret_name AS SELECT 1 AS v")
            val cases = listOf("PRAGMA table_info('src_secret_name')" to "PRAGMA", "EXPLAIN SELECT v FROM src_secret_name" to "EXPLAIN")
            cases.forEach { (sql, kind) ->
                val sink = RecordingSink()
                val lines = unguardedLines { runBlocking { duck.run(sql, Fixtures.node("n_$kind"), directSink = sink) } }
                withClue(sql) { sink.rows.isNotEmpty() shouldBe true }
                val line = lines.single()
                withClue(line) {
                    line shouldBe "event=staging.guard.unguarded_read execution_id=${duck.executionId} node=n_$kind kind=$kind"
                    line.contains("src_secret_name") shouldBe false
                }
            }
        }
    }

    @Test
    fun `D3 probe - a text of several statements reads the last statement's rows on both callers, unguarded and logged as MULTI`() {
        withDuck { duck ->
            duck.staging.execute("CREATE TABLE src AS SELECT range AS v FROM range(5)")
            val cases =
                listOf(
                    "SELECT 42 AS answer; SELECT v FROM src ORDER BY v" to emptyMap(),
                    "SELECT 42 AS answer; SELECT v FROM src WHERE v >= :lo ORDER BY v" to mapOf("lo" to 2),
                )
            cases.forEach { (sql, values) ->
                val sink = RecordingSink()
                val lines = unguardedLines { runBlocking { duck.run(sql, Fixtures.node("caller"), directSink = sink, values = values) } }
                val lo = values["lo"] ?: 0
                withClue("$sql: the LAST statement's rows came back") { sink.rows shouldBe (lo until 5).map { listOf(it.toLong()) } }
                withClue(sql) { sink.schema.shouldNotBeNull().map { it.name } shouldBe listOf("v") }
                val expectedLine = "event=staging.guard.unguarded_read execution_id=${duck.executionId} node=caller kind=MULTI"
                withClue(sql) { lines.single() shouldBe expectedLine }

                val target = h2Datasource("tgt", listOf("CREATE TABLE tgt (v BIGINT)"))
                val node = Fixtures.node("wb", output = NodeOutput.Datasource("tgt", "tgt", WriteMode.APPEND))
                val wbLines =
                    unguardedLines {
                        runBlocking { duck.run(sql, node, registry = FakeDatasourceRegistry(mapOf("tgt" to target)), values = values) }
                    }
                withClue("$sql: the write-back wrote the last statement's rows") { countIn(target) shouldBe 5 - lo }
                withClue(sql) { wbLines.single().endsWith("node=wb kind=MULTI") shouldBe true }
            }
            duck.guardRelations() shouldBe 0L
        }
    }

    // ---------------------------------------------------------------- C1: the managed append's scans

    @Test
    fun `the managed append's scan fails as result_incomplete when the engine fails mid-stream`() {
        withDuck { duck ->
            val output = NodeOutput.Datasource("managed", "ns.orders", WriteMode.APPEND)
            var scanned = 0L
            val session = PublishingSession { input -> input.scan { rows -> while (rows.next()) scanned++ }.let { scanned } }
            val failure =
                shouldThrow<StagingResultIncompleteException> {
                    LakeNodeWriteback.run(
                        ExecutableNode.from(Fixtures.node("append", output = output)),
                        output,
                        SqlBindTranslator.translate("SELECT range AS v FROM range($ROWS)", emptyMap()),
                        duck.context(),
                        ResolvedQueryTimeout(TIMEOUT_SECONDS, QueryTimeoutSource.APPLICATION),
                        Instant.now(),
                        session,
                    ) { duck.swapAppendRelationForFailingView() }
                }
            withClue("non-vacuity: the scan was cut short mid-stream, not at execute") { (scanned in 1 until FAIL_AT + 1) shouldBe true }
            failure.details shouldBe
                mapOf("rows_read" to scanned, "rows_expected" to ROWS, "reader" to LakeNodeWriteback.StableInput.APPEND_SCAN)
        }
    }

    @Test
    fun `a clean managed append scans every row in arrival order, guarded`() {
        withDuck { duck ->
            val output = NodeOutput.Datasource("managed", "ns.orders", WriteMode.APPEND)
            val values = mutableListOf<Long>()
            val session =
                PublishingSession { input ->
                    input.scan { rows -> while (rows.next()) values += rows.getLong(1) }.let { values.size.toLong() }
                }
            LakeNodeWriteback.run(
                ExecutableNode.from(Fixtures.node("append", output = output)),
                output,
                SqlBindTranslator.translate("SELECT range AS v FROM range($ORDERED_ROWS)", emptyMap()),
                duck.context(),
                ResolvedQueryTimeout(TIMEOUT_SECONDS, QueryTimeoutSource.APPLICATION),
                Instant.now(),
                session,
            ) { }
            values shouldBe (0 until ORDERED_ROWS).toList()
        }
    }

    // ---------------------------------------------------------------- harness

    /** One real DuckDB staging database and the executor's collaborators over it. */
    private class Duck(
        val staging: DuckDbStaging,
        val executionId: UUID,
    ) {
        val registry = InMemoryCancellationRegistry()
        val handle = registry.register(executionId)

        fun context(
            directSink: DirectResultSink? = null,
            values: Map<String, Any?> = emptyMap(),
        ) = NodeExecutionContext(
            executionId = executionId,
            staging = staging,
            handle = handle,
            values = RunContext.of(values),
            warnings = WarningSink(),
            resultTtlSeconds = 300,
            renderBudgetChars = 100_000,
            stagingMaxMemoryMb = 64,
            tempdbDialect = Dialect.DUCKDB,
            userId = UUID.randomUUID(),
            rootExecutionId = executionId,
            directSink = directSink,
            workspaceId = UUID.randomUUID(),
        )

        suspend fun run(
            sql: String,
            node: co.datapipelines.pipeline.Node,
            registry: FakeDatasourceRegistry = FakeDatasourceRegistry(emptyMap()),
            resultStore: ResultStore = InMemoryResultStore(),
            directSink: DirectResultSink? = null,
            values: Map<String, Any?> = emptyMap(),
        ): NodeResult {
            val (engine, _) = Fixtures.templateEngine(sql)
            val runner = NodeRunner(engine, registry, JdbcWritebackRunner(registry), resultStore, ExecutorConfig())
            return runner.run(ExecutableNode.from(node), context(directSink, values))
        }

        suspend fun failureOf(
            sql: String,
            node: co.datapipelines.pipeline.Node,
            registry: FakeDatasourceRegistry = FakeDatasourceRegistry(emptyMap()),
            directSink: DirectResultSink? = null,
            values: Map<String, Any?> = emptyMap(),
        ): MappedError = shouldThrow<NodeFailedSignal> { run(sql, node, registry, directSink = directSink, values = values) }.error

        /**
         * The code TODAY's plain tempdb read gives an engine failure raised at execution, for [node]'s output: an
         * EXPLAIN ANALYZE (which the written form does not take, so it reads plain) whose query fails. The
         * classification the written form's writing statement must keep.
         */
        suspend fun executeTimeFailureCode(
            node: co.datapipelines.pipeline.Node = Fixtures.node("caller"),
            registry: FakeDatasourceRegistry = FakeDatasourceRegistry(emptyMap()),
        ): String =
            failureOf("EXPLAIN ANALYZE SELECT error('at-execute') AS v", node, registry, directSink = RecordingSink())
                .code
                .also { it shouldNotBe StagingErrorCodes.RESULT_INCOMPLETE }

        /** [sql] read as a plain statement, as the unguarded path reads it: the executor's schema and decoded rows. */
        suspend fun plainRead(
            sql: String,
            values: Map<String, Any?>,
        ): Pair<List<co.datapipelines.typesystem.ColumnSchema>, List<List<Any?>>> {
            val bound = SqlBindTranslator.translate(sql, values)
            return staging.withConnection { c: Connection ->
                BoundStatements.statementFor(c, bound).use { st ->
                    BoundStatements.query(st, bound).use { rs ->
                        val columns = ResultRowReader.schemaOf(rs.metaData, Dialect.DUCKDB).columns
                        val rows = mutableListOf<List<Any?>>()
                        while (rs.next()) rows += columns.indices.map { ResultRowReader.readValue(rs, it + 1, columns[it]) }
                        columns to rows
                    }
                }
            }
        }

        /** The one `staging.guard.unguarded_read` line a read of [node] with statement [kind] writes (#994 D4). */
        fun unguardedLine(
            node: String,
            kind: String,
        ) = "event=staging.guard.unguarded_read execution_id=$executionId node=$node kind=$kind"

        /** How many tables named `victim` the database holds. */
        suspend fun victims(): Long =
            staging.withQuery("SELECT count(*) FROM duckdb_tables() WHERE table_name = 'victim'") { rs ->
                StreamedReadGuard.oneRow(rs, "test") { it.getLong(1) }
            }

        /** Relations with the guard's prefix still in the database. */
        suspend fun guardRelations(): Long =
            staging.withQuery(
                "SELECT count(*) FROM duckdb_tables() WHERE starts_with(table_name, '${StreamedReadGuard.RELATION_PREFIX}')",
            ) { rs -> StreamedReadGuard.oneRow(rs, "test") { it.getLong(1) } }

        /** The prepared original's schema, read as the executor reads a result's — the C5 metadata oracle. */
        suspend fun preparedSchema(sql: String) =
            staging.withConnection { c: Connection ->
                c.prepareStatement(sql).use { ResultRowReader.schemaOf(it.metaData, Dialect.DUCKDB).columns }
            }

        /** The append's afterMaterialized seam: its relation becomes a view that fails from row [FAIL_AT] on. */
        suspend fun swapAppendRelationForFailingView() {
            val name =
                staging.withQuery("SELECT table_name FROM duckdb_tables() WHERE starts_with(table_name, 'dp_append_')") { rs ->
                    StreamedReadGuard.oneRow(rs, "test") { it.getString(1) }
                }
            staging.execute("DROP TABLE \"$name\"")
            staging.execute("CREATE VIEW \"$name\" AS $FAILING_SELECT")
        }
    }

    private fun withDuck(
        threads: Int = 2,
        reservationBytes: Long? = null,
        block: suspend (Duck) -> Unit,
    ) {
        val id = UUID.randomUUID()
        val factory = DuckDbStagingFactory(DuckDbStagingProperties(scratch, MIB * 64, 4, MIB * 1024, threads = threads))
        val staging = (reservationBytes?.let { factory.create(id, it) } ?: factory.create(id, factory.properties.defaultReservationBytes))
        (staging as DuckDbStaging).use { runBlocking { block(Duck(it, id)) } }
        factory.budget.reservedBytes shouldBe 0L
    }

    /** The `staging.guard.unguarded_read` lines NodeRunner's logger wrote during [block]. */
    private fun unguardedLines(block: () -> Unit): List<String> =
        capturingLogEvents(org.slf4j.LoggerFactory.getLogger(NodeRunner::class.java) as ch.qos.logback.classic.Logger) { block() }
            .map { it.formattedMessage }
            .filter { it.startsWith("event=staging.guard.unguarded_read") }

    private fun countIn(target: co.datapipelines.datasources.Datasource): Int =
        DriverManager.getConnection(target.jdbcUrl, "sa", "").use { c -> oneInt(c, "SELECT COUNT(*) FROM tgt") }

    private fun namesIn(target: co.datapipelines.datasources.Datasource): List<String> =
        DriverManager.getConnection(target.jdbcUrl, "sa", "").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT name FROM tgt ORDER BY name").use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        }

    private fun oneInt(
        connection: Connection,
        sql: String,
    ): Int =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> if (rs.next()) rs.getInt(1) else error("no row: $sql") }
        }

    /** An append session that hands the input to [publish]; nothing is written anywhere. */
    private class PublishingSession(
        private val publish: (LakeAppendInput) -> Long,
    ) : LakeAppendSession {
        override val datasourceName = "managed"
        override val deadline: Instant = Instant.now().plusSeconds(TIMEOUT_SECONDS.toLong())

        override fun initialize(
            connection: Connection,
            statements: LakeStatementExecution,
        ) = Unit

        override fun readCompleted() = Unit

        override fun append(
            target: NodeOutput.Datasource,
            input: LakeAppendInput,
            checkActive: () -> Unit,
        ): Long {
            checkActive()
            return publish(input)
        }

        override fun failed(code: String?) = Unit

        override fun close() = Unit
    }

    private companion object {
        const val MIB = 1_048_576L
        const val ROWS = 400_000L
        const val FAIL_AT = 300_000L
        const val ORDERED_ROWS = 300_000L
        const val BIG_ROWS = 2_000_000L
        const val SMALL_RESERVATION = 4 * MIB
        const val TIMEOUT_SECONDS = 60
        const val CANCEL_AFTER_MS = 700L
        const val FAILING_SELECT = "SELECT CASE WHEN range < $FAIL_AT THEN range ELSE error('boom') END AS v FROM range($ROWS)"
        const val FAILING_VIEW = "CREATE VIEW boom AS $FAILING_SELECT"
        const val PIVOT_SMALL = "CREATE TABLE small AS SELECT 'g' || (range % 3) AS g FROM range(9)"

        /**
         * A statement with a PIVOT (the written form refuses PIVOT anywhere) whose result streams [ROWS] rows from the
         * failing view, one per row of the one-row pivot: it fails mid-stream, past the first chunk, at 2 and 4 threads.
         */
        const val FAILING_PIVOT = "SELECT b.v, p.* FROM boom b CROSS JOIN (PIVOT small ON g USING count(*)) p"

        /** A PIVOT WITH an IN list (round 1's written form takes it) streaming [ROWS] rows from the failing view. */
        const val IN_PIVOT = "SELECT b.v, p.* FROM boom b CROSS JOIN (PIVOT small ON g IN ('g0', 'g1', 'g2') USING count(*)) p"

        const val VOLATILE_SOURCE = 10_000L
        const val VOLATILE_RUNS = 5
        const val HALF = 0.5
        const val PIVOT_NO_IN = "CROSS JOIN (PIVOT (VALUES ('g0'), ('g1')) t(g) ON g USING count(*)) p"

        /** The lander's statement (store landings/994-r2.md): a nested PIVOT without IN over random(). */
        const val RANDOM_SOURCE = "SELECT i FROM range($VOLATILE_SOURCE) t(i) WHERE random()"
        const val VOLATILE_PIVOT = "SELECT b.i, p.* FROM ($RANDOM_SOURCE < 0.5) b $PIVOT_NO_IN"
        const val VOLATILE_PIVOT_BOUND = "SELECT b.i, p.* FROM ($RANDOM_SOURCE < :p) b $PIVOT_NO_IN"

        const val SEQUENCE_KEPT = 5_000L
        const val RESET_SEQUENCE = "CREATE OR REPLACE SEQUENCE volatile_seq"
        const val SEQUENCE_SOURCE = "SELECT i FROM range($VOLATILE_SOURCE) t(i) WHERE nextval('volatile_seq')"
        const val SEQUENCE_PIVOT = "SELECT b.i, p.* FROM ($SEQUENCE_SOURCE <= $SEQUENCE_KEPT) b $PIVOT_NO_IN"
        const val SEQUENCE_PIVOT_BOUND = "SELECT b.i, p.* FROM ($SEQUENCE_SOURCE <= :lim) b $PIVOT_NO_IN"

        /** Round 3's security probe: the text closes a wrapper's parenthesis early and smuggles a DROP in. */
        const val INJECTION = "SELECT 1) AS a; DROP TABLE victim; SELECT * FROM (SELECT 2"
        const val INJECTION_BOUND = "SELECT :x) AS a; DROP TABLE victim; SELECT * FROM (SELECT 2"
        const val PARSER_REFUSAL = "syntax error at or near \")\""

        /** SUMMARIZE's approximate quantiles: they differ between two evaluations of the same text. */
        val APPROXIMATE_SUMMARIZE_COLUMNS = setOf("q25", "q50", "q75")

        const val SCHEMA_LISTING = "SELECT table_name FROM information_schema.tables ORDER BY table_name"
        const val DUCKDB_TABLES_LISTING = "SELECT table_name FROM duckdb_tables() ORDER BY table_name"
        const val CATALOG_RUNS = 5

        /** The follow-up issue the catalog-listing pin names (#994 D2). */
        const val CATALOG_FOLLOW_UP = "#1002"

        /** A writing statement that runs for many seconds and produces one row. */
        const val SLOW_SQL = "SELECT sum(a.range * b.range) AS v FROM range(300000) a, range(300000) b"
    }
}
