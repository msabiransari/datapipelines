package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager

/**
 * dag-executor.md §14 bullet 5 — the end-to-end shape with a **non-H2** source (B6/C4).
 *
 * ## Why an H2-only E2E suite left a hole
 *
 * Every other end-to-end test here uses H2 as the "external datasource" *and* as tempdb, so
 * `dispatchOutput`'s central rule — stage "through the **SOURCE** dialect, never H2's"
 * (staging §3.2) — was only ever exercised with `Dialect.H2` on both sides, where getting it wrong
 * is indistinguishable from getting it right. That rule exists precisely because a source dialect's
 * JDBC codes do not mean what H2's mean, and mapping them through the wrong table picks a wrong
 * storage type and loses data *before* egress re-derivation can see it.
 *
 * `uuid` and `jsonb` are the discriminating columns: Postgres reports both as `Types.OTHER`, which
 * `PostgresTypeMapper` maps to canonical `STRING`, and which `H2IngressMapper` does not recognize at
 * all — so an H2-mapped run produces a `type_mapping.unknown_source_type` warning per column. The
 * warning list is therefore a direct read-out of which mapper actually ran.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresSourceIntegrationTest {
    @BeforeAll
    fun seed() {
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE orders (
                        id        bigint PRIMARY KEY,
                        ref       uuid        NOT NULL,
                        payload   jsonb       NOT NULL,
                        total     numeric(12,4) NOT NULL,
                        placed_at timestamptz NOT NULL,
                        note      text
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO orders VALUES
                      (1, '11111111-1111-1111-1111-111111111111', '{"a":1}', 1234.5600, TIMESTAMPTZ '2026-01-15 10:30:00+00', 'first'),
                      (2, '22222222-2222-2222-2222-222222222222', '{"b":2}', 0.0001,    TIMESTAMPTZ '2026-02-15 10:30:00+00', NULL)
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `a Postgres source stages through the Postgres mapper and reaches the caller intact`() =
        runBlocking<Unit> {
            val nodes =
                listOf(
                    Fixtures.node("fetch", source = DATASOURCE, output = NodeOutput.Tempdb("stg_orders")),
                    Fixtures.node("report", output = NodeOutput.Caller, dependsOn = listOf("fetch")),
                )
            val sql =
                mapOf(
                    "fetch" to "SELECT id, ref, payload, total, placed_at, note FROM orders ORDER BY id",
                    // Unquoted, i.e. how a pipeline author actually writes it (staging v1.10).
                    "report" to "SELECT id, ref, payload, total, placed_at, note FROM stg_orders ORDER BY id",
                )

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(sql),
                registry = FakeDatasourceRegistry(mapOf(DATASOURCE to datasource())),
            ).use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))

                result.status shouldBe ExecutionStatus.SUCCESS
                result.nodeStats.single { it.nodeId == "fetch" }.rowsOut shouldBe 2

                // The read-out that proves the SOURCE mapper ran: Postgres maps uuid/jsonb
                // (Types.OTHER) to STRING, H2 does not recognize them at all.
                result.warnings.filter { it.code == PipelineErrorCodes.TypeMapping.UNKNOWN_SOURCE_TYPE }.shouldBeEmpty()

                val view = h.resultStore.describe(result.resultRef.shouldNotBeNull()).shouldNotBeNull()
                val byName = view.schema.associateBy { it.name }
                byName.getValue("id").type shouldBe LogicalType.BIGINTEGER
                byName.getValue("ref").type shouldBe LogicalType.STRING
                byName.getValue("payload").type shouldBe LogicalType.STRING
                byName.getValue("placed_at").type shouldBe LogicalType.TIMESTAMP
                byName.getValue("note").type shouldBe LogicalType.STRING

                view.totalRows shouldBe 2
                val first = view.firstPage.first()
                first[byName.keys.indexOf("ref")] shouldBe "11111111-1111-1111-1111-111111111111"
                // NULL survives the round trip as a null, not as the string "null".
                view.firstPage[1][byName.keys.indexOf("note")] shouldBe null
            }
        }

    @Test
    fun `a Postgres numeric keeps its declared scale through staging and egress`() =
        runBlocking<Unit> {
            // The value that catches a wrong storage type: 0.0001 at scale 4 is 0 under an integer
            // or a scale-0 decimal, and 1E-4 under a mapper that lets exponent notation through.
            val nodes =
                listOf(
                    Fixtures.node("fetch", source = DATASOURCE, output = NodeOutput.Tempdb("stg_totals")),
                    Fixtures.node("report", output = NodeOutput.Caller, dependsOn = listOf("fetch")),
                )
            val sql =
                mapOf(
                    "fetch" to "SELECT id, total FROM orders ORDER BY id",
                    "report" to "SELECT id, total FROM stg_totals ORDER BY id",
                )

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(sql),
                registry = FakeDatasourceRegistry(mapOf(DATASOURCE to datasource())),
            ).use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                val view = h.resultStore.describe(result.resultRef.shouldNotBeNull()).shouldNotBeNull()

                val totalIndex = view.schema.indexOfFirst { it.name == "total" }
                view.schema[totalIndex].scale shouldBe 4
                view.firstPage[0][totalIndex].toString() shouldBe "1234.5600"
                view.firstPage[1][totalIndex].toString() shouldBe "0.0001"
            }
        }

    @Test
    fun `write-back into a Postgres table that rejects the row is writeback_failed`() =
        runBlocking<Unit> {
            // F13: `writeback_failed` end to end, triggered by a real NOT NULL violation rather than
            // a simulated one — and the transaction must roll back, leaving the target untouched.
            DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE IF EXISTS wb_target")
                    statement.execute("CREATE TABLE wb_target (id bigint, note text NOT NULL)")
                }
            }
            val nodes =
                listOf(
                    Fixtures.node(
                        "wb",
                        source = DATASOURCE,
                        output = NodeOutput.Datasource(DATASOURCE, "wb_target", co.datapipelines.pipeline.WriteMode.APPEND),
                    ),
                )

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("wb" to "SELECT id, note FROM orders ORDER BY id")),
                registry = FakeDatasourceRegistry(mapOf(DATASOURCE to datasource())),
            ).use { h ->
                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }.errorCode shouldBe PipelineErrorCodes.Node.WRITEBACK_FAILED
            }

            DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM wb_target").use { rs ->
                        rs.next()
                        // Rolled back: the row that would have succeeded is not left behind either.
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        }

    private fun datasource() =
        Datasource(
            name = DATASOURCE,
            displayName = DATASOURCE,
            dialect = Dialect.POSTGRES,
            jdbcUrl = db.jdbcUrl,
            username = db.username,
            password = db.password,
        )

    private companion object {
        const val DATASOURCE = "pg_orders"

        /**
         * A scratch database on the module's shared container: the pipeline's SOURCE —
         * the `orders` table is this suite's own fixture, not shipped schema, so it must
         * not see the migrated database the other suites use.
         */
        val db = SharedPostgres.scratchDatabase("source_orders")
    }
}
