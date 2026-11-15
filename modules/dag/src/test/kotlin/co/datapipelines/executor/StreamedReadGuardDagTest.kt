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

    @Test
    fun `DESCRIBE and SHOW TABLES still read, now through the counted form, with no unguarded-read line`() {
        withDuck { duck ->
            duck.staging.execute("CREATE TABLE src AS SELECT 1 AS v")
            val lines =
                unguardedLines {
                    listOf("DESCRIBE src", "SHOW TABLES").forEach { sql ->
                        val sink = RecordingSink()
                        runBlocking { duck.run(sql, Fixtures.node("caller"), directSink = sink) }
                        withClue(sql) { sink.rows.isNotEmpty() shouldBe true }
                    }
                }
            withClue("neither statement read unguarded") { lines.shouldBeEmpty() }
            duck.guardRelations() shouldBe 0L
        }
    }

    // ---------------------------------------------------------------- C5's counted form (#994 round 2, D1)

    @Test
    fun `a PIVOT statement that fails mid-stream over many chunks fails as result_incomplete through the counted form`() {
        withDuck { duck ->
            duck.staging.execute(FAILING_VIEW)
            duck.staging.execute(PIVOT_SMALL)
            val sink = RecordingSink()
            val error = duck.failureOf(FAILING_PIVOT, Fixtures.node("caller"), directSink = sink)
            withClue("the caller was handed ${sink.rows.size} of $ROWS rows") {
                error.code shouldBe StagingErrorCodes.RESULT_INCOMPLETE
            }
            error.details["rows_expected"] shouldBe ROWS
            error.details["reader"] shouldBe TempdbReadGuard.TEMPDB_COUNTED_READER
            val read = error.details["rows_read"] as Long
            withClue("non-vacuity: the read was cut short mid-stream, past the first chunk") { (read in 1 until FAIL_AT + 1) shouldBe true }
            sink.rows.size.toLong() shouldBe read
        }
    }

    @Test
    fun `a PIVOT write-back that fails mid-stream, with and without a bound value, fails as result_incomplete`() {
        withDuck { duck ->
            duck.staging.execute(FAILING_VIEW)
            duck.staging.execute(PIVOT_SMALL)
            listOf(FAILING_PIVOT to emptyMap(), "$FAILING_PIVOT WHERE p.g0 >= :lo" to mapOf("lo" to 0)).forEach { (sql, values) ->
                val target = h2Datasource("tgt", listOf("CREATE TABLE tgt (v BIGINT, g0 BIGINT, g1 BIGINT, g2 BIGINT)"))
                val node = Fixtures.node("wb", output = NodeOutput.Datasource("tgt", "tgt", WriteMode.APPEND))
                val error = duck.failureOf(sql, node, registry = FakeDatasourceRegistry(mapOf("tgt" to target)), values = values)
                withClue("$sql: ${error.message} ${error.details}") { error.code shouldBe StagingErrorCodes.RESULT_INCOMPLETE }
                withClue(sql) { error.details["reader"] shouldBe TempdbReadGuard.TEMPDB_COUNTED_READER }
            }
        }
    }

    @Test
    fun `SUMMARIZE, DESCRIBE, SHOW and a clean PIVOT read the same rows, labels, types and order as the plain statement`() {
        withDuck { duck ->
            duck.staging.execute("CREATE TABLE src AS SELECT range AS v, range % 3 AS g, 'x' || range AS s FROM range(50)")
            val cases =
                listOf(
                    "SUMMARIZE src" to emptyMap(),
                    "SUMMARIZE (SELECT * FROM src WHERE v >= :lo)" to mapOf("lo" to 10),
                    "DESCRIBE src" to emptyMap(),
                    "SHOW src" to emptyMap(),
                    "SHOW TABLES" to emptyMap(),
                    "PIVOT src ON g USING count(*) GROUP BY v ORDER BY v" to emptyMap(),
                    "SELECT * FROM (PIVOT src ON g USING sum(v) GROUP BY s) ORDER BY s;" to emptyMap(),
                )
            val lines =
                unguardedLines {
                    cases.forEach { (sql, values) ->
                        val oracle = runBlocking { duck.plainRead(sql, values) }
                        val sink = RecordingSink()
                        runBlocking { duck.run(sql, Fixtures.node("caller"), directSink = sink, values = values) }
                        withClue("$sql: columns") { sink.schema shouldBe oracle.first }
                        withClue("$sql: rows in order") { sink.rows shouldBe oracle.second }
                        withClue("$sql: non-vacuity") { oracle.second.isNotEmpty() shouldBe true }
                    }
                }
            withClue("every case was guarded (written or counted), none read unguarded") { lines.shouldBeEmpty() }
        }
    }

    @Test
    fun `no false alarm for a counted read when another connection changes the table between the count and the read`() {
        withDuck { duck ->
            duck.staging.execute("CREATE TABLE src AS SELECT range AS v, range % 3 AS g FROM range(6)")
            val sql = "PIVOT src ON g USING count(*) GROUP BY v"
            val bound = SqlBindTranslator.translate(sql, emptyMap())
            val node = ExecutableNode.from(Fixtures.node("caller"))
            val resolved = ResolvedQueryTimeout(TIMEOUT_SECONDS, QueryTimeoutSource.APPLICATION)
            val ctx = duck.context()
            val read =
                duck.staging.withConnection { c ->
                    val form = TempdbReadGuard.formOf(node, c, bound, ctx, resolved, duck.staging)
                    val counted = form.shouldBeInstanceOf<TempdbReadForm.Counted>()
                    counted.count.use { count ->
                        TempdbReadGuard.readCounted(
                            node,
                            c,
                            bound,
                            ctx,
                            resolved,
                            count,
                            block = { rs -> generateSequence { if (rs.next()) 1 else null }.count() },
                            // The other connection's write commits between the count and the read.
                            afterCount = { duck.staging.execute("INSERT INTO src SELECT range + 100, range % 3 FROM range(4)") },
                        )
                    }
                }
            withClue("the read sees the count's snapshot") { read shouldBe 6 }
            withClue("non-vacuity: the other connection's rows were committed") {
                duck.staging.withQuery("SELECT count(*) FROM src") { rs -> StreamedReadGuard.oneRow(rs, "test") { it.getLong(1) } } shouldBe
                    10L
            }
        }
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
         * The code TODAY's unguarded tempdb read gives an engine failure raised at execution, for [node]'s output: an
         * EXPLAIN ANALYZE (which neither guarded form takes, so it runs on the unguarded path) whose query fails. The
         * classification the guarded writing statement must keep.
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

        /** A writing statement that runs for many seconds and produces one row. */
        const val SLOW_SQL = "SELECT sum(a.range * b.range) AS v FROM range(300000) a, range(300000) b"
    }
}
