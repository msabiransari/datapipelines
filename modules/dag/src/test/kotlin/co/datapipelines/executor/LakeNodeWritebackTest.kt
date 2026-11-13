package co.datapipelines.executor

import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.LakeAppendSession
import co.datapipelines.pipeline.LakeStatementExecution
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.staging.DuckDbStagingFactory
import co.datapipelines.staging.DuckDbStagingProperties
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.StagingEngine
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
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
        const val MIB = 1_048_576L
    }
}
