package co.datapipelines.executor

import co.datapipelines.pipeline.LakeAppendColumn
import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.LakeAppendSession
import co.datapipelines.pipeline.LakeStatementExecution
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.staging.DuckDbStaging
import co.datapipelines.staging.DuckDbStagingFactory
import co.datapipelines.staging.DuckDbStagingProperties
import co.datapipelines.staging.StagingScratchLimitException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.StagingEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.util.UUID

/** Real native materialization: duplicate labels and volatile SQL survive the validation/write scans. */
class LakeNodeWritebackTest {
    @TempDir
    lateinit var scratch: Path

    @Test
    fun `author SQL runs once and both publication scans see the same values`() {
        withNative { context ->
            context.staging.execute("CREATE SEQUENCE counter START 1")
            val output = NodeOutput.Datasource("managed", "ns.orders", WriteMode.APPEND)
            val scans = mutableListOf<List<Long>>()
            val session =
                RecordingSession { input ->
                    repeat(2) {
                        input.scan { rows ->
                            val values = mutableListOf<Long>()
                            while (rows.next()) values += rows.getLong(1)
                            scans += values
                        }
                    }
                    3
                }
            var checkedBudget = false
            LakeNodeWriteback.run(
                ExecutableNode.from(Fixtures.node("append", output = output)),
                output,
                SqlBindTranslator.translate("SELECT nextval('counter') AS id FROM range(3)", emptyMap()),
                context,
                ResolvedQueryTimeout(10, QueryTimeoutSource.APPLICATION),
                Instant.now(),
                session,
            ) { checkedBudget = true }
            checkedBudget shouldBe true
            scans shouldBe listOf(listOf(1L, 2L, 3L), listOf(1L, 2L, 3L))
            context.staging.withQuery("SELECT nextval('counter')") { rows ->
                rows.next()
                rows.getLong(1)
            } shouldBe 4L
        }
    }

    @Test
    fun `original case-ambiguous labels reach the checker before CTAS renames them`() {
        withNative { context ->
            val output = NodeOutput.Datasource("managed", "ns.orders", WriteMode.APPEND)
            val session =
                RecordingSession { input ->
                    requireNotNull(input.originalColumns).map { it.name } shouldBe listOf("id", "ID")
                    input.scan { rows ->
                        rows.next() shouldBe true
                        rows.getInt(1) shouldBe 1
                        rows.getInt(2) shouldBe 2
                    }
                    1
                }
            LakeNodeWriteback.run(
                ExecutableNode.from(Fixtures.node("append", output = output)),
                output,
                SqlBindTranslator.translate("SELECT 1 AS id, 2 AS \"ID\"", emptyMap()),
                context,
                ResolvedQueryTimeout(10, QueryTimeoutSource.APPLICATION),
                Instant.now(),
                session,
            ) { }
        }
    }

    @Test
    fun `O10 - a position outside the relation, a repeated position and five positions are refused before any statement`() {
        listOf(listOf(0), listOf(SIX + 1), listOf(2, 2), listOf(1, 2, 3, 4, 5)).forEach { orderBy ->
            val statement = RecordingStatement()
            shouldThrow<IllegalArgumentException> { stableInput(statement).scan(orderBy) { } }
            statement.executed shouldBe emptyList()
        }
    }

    @Test
    fun `O10 - positions reach the text as integers, ascending and nulls first, and no order sends today's text`() {
        val ordered = RecordingStatement()
        stableInput(ordered).scan(listOf(2, 1)) { }
        ordered.executed shouldBe listOf("SELECT * FROM \"dp_append_t\" ORDER BY 2 ASC NULLS FIRST, 1 ASC NULLS FIRST")
        val plain = RecordingStatement()
        stableInput(plain).scan { }
        plain.executed shouldBe listOf("SELECT * FROM \"dp_append_t\"")
    }

    @Test
    fun `O9 - the engine's spill-limit text becomes the typed exception, thrown by the statement or while rows are read`() {
        withNative { context ->
            val classify = (context.staging as DuckDbStaging)::classifyEngineFailure
            val atExecute = RecordingStatement(failOnExecute = SQLException(SPILL_LIMIT))
            shouldThrow<StagingScratchLimitException> { stableInput(atExecute, classify).scan(listOf(1)) { } }
                .code shouldBe "pipeline.staging.scratch_limit_exceeded"
            val whileReading = RecordingStatement(failOnNext = SQLException(SPILL_LIMIT))
            shouldThrow<StagingScratchLimitException> {
                stableInput(whileReading, classify).scan(listOf(1)) { rows -> rows.next() }
            }.cause.shouldBeInstanceOf<SQLException>()
            val other = SQLException("Binder Error: something else")
            val input = stableInput(RecordingStatement(failOnNext = other), classify)
            shouldThrow<SQLException> { input.scan { it.next() } } shouldBeSameInstanceAs other
        }
    }

    @Test
    fun `an ordered scan on the real staging engine returns the rows in order, nulls first, and the check scan in arrival order`() {
        withNative { context ->
            val output = NodeOutput.Datasource("managed", "ns.orders", WriteMode.APPEND)
            val scans = mutableListOf<List<String>>()
            val session =
                RecordingSession { input ->
                    listOf(emptyList(), listOf(2, 1)).forEach { orderBy ->
                        input.scan(orderBy) { rows ->
                            val values = mutableListOf<String>()
                            while (rows.next()) values += "${rows.getInt(1)}/${rows.getString(2)}"
                            scans += values
                        }
                    }
                    4
                }
            LakeNodeWriteback.run(
                ExecutableNode.from(Fixtures.node("append", output = output)),
                output,
                SqlBindTranslator.translate(
                    "SELECT * FROM (VALUES (2, 'b'), (1, 'b'), (3, NULL), (0, 'a')) t(id, label)",
                    emptyMap(),
                ),
                context,
                ResolvedQueryTimeout(10, QueryTimeoutSource.APPLICATION),
                Instant.now(),
                session,
            ) { }
            scans shouldBe listOf(listOf("2/b", "1/b", "3/null", "0/a"), listOf("3/null", "0/a", "1/b", "2/b"))
        }
    }

    private fun stableInput(
        statement: Statement,
        classify: (SQLException) -> Exception = { it },
    ) = LakeNodeWriteback.StableInput(
        statement,
        "\"dp_append_t\"",
        (1..SIX).map { LakeAppendColumn("c$it", "INTEGER", 0, 0) },
        {},
        classify,
    ) { action -> action() }

    /** A statement that records every query text and returns an empty cursor, or fails where it is told to. */
    private class RecordingStatement(
        private val failOnExecute: SQLException? = null,
        private val failOnNext: SQLException? = null,
    ) : Statement by proxy(Statement::class.java, { _, _ -> null }) {
        val executed = mutableListOf<String>()

        override fun executeQuery(sql: String): ResultSet {
            executed += sql
            failOnExecute?.let { throw it }
            return proxy(ResultSet::class.java) { name, _ ->
                when (name) {
                    "next" -> failOnNext?.let { throw it } ?: false
                    else -> null
                }
            }
        }
    }

    private fun withNative(block: suspend (NodeExecutionContext) -> Unit) {
        val id = UUID.randomUUID()
        val factory = DuckDbStagingFactory(DuckDbStagingProperties(scratch, MIB * 64, 2, MIB * 256))
        factory.create(id, StagingEngine.DUCKDB).use { staging ->
            val context =
                NodeExecutionContext(
                    id,
                    staging,
                    InMemoryCancellationRegistry().register(id),
                    RunContext.of(emptyMap()),
                    WarningSink(),
                    resultTtlSeconds = 300,
                    renderBudgetChars = 100_000,
                    stagingMaxMemoryMb = 64,
                    tempdbDialect = Dialect.DUCKDB,
                    userId = UUID.randomUUID(),
                    rootExecutionId = id,
                    workspaceId = UUID.randomUUID(),
                )
            runBlocking { block(context) }
        }
        factory.budget.reservedBytes shouldBe 0L
    }

    private class RecordingSession(
        private val publish: (LakeAppendInput) -> Long,
    ) : LakeAppendSession {
        override val datasourceName = "managed"
        override val deadline: Instant = Instant.now().plusSeconds(60)

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
        const val SIX = 6
        const val SPILL_LIMIT =
            "Out of Memory Error: failed to offload data block of size 256.0 KiB (1.0 MiB/1.0 MiB used). " +
                "This limit was set by the 'max_temp_directory_size' setting."

        /** A JDK proxy of [type] answering by method name; `close` and unknown methods do nothing. */
        @Suppress("UNCHECKED_CAST")
        fun <T> proxy(
            type: Class<T>,
            answer: (String, Array<out Any?>?) -> Any?,
        ): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> answer(method.name, args) } as T

        const val MIB = 1_048_576L
    }
}
