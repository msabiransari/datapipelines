package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Table creation, quoting, and the defensive duplicate-table guard (§4.2, §4.5).
 */
class H2StagingTableTest {
    private val staging = H2StagingFactory(H2StagingProperties()).create(UUID.randomUUID())

    @AfterEach
    fun tearDown() = staging.close()

    @Test
    fun `stage reports the table name, row count, and canonical columns`() {
        val result =
            SourceDb().use { src ->
                src.exec("CREATE TABLE t (id INTEGER, label VARCHAR(20))")
                src.exec("INSERT INTO t VALUES (1, 'a'), (2, 'b')")
                runBlocking { staging.stage(src.query("SELECT id, label FROM t"), "stg_orders", Dialect.H2) }
            }

        result.tableName shouldBe "stg_orders"
        result.rowsStaged shouldBe 2L
        // H2 folds UNQUOTED source aliases to upper case, so the labels arrive as ID / LABEL —
        // valid identifiers, staged verbatim under quotes (§4.5, §11.3 case-sensitivity note).
        result.columns.map { it.name } shouldBe listOf("ID", "LABEL")
        result.columns.map { it.type } shouldBe listOf(LogicalType.INTEGER, LogicalType.STRING)
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_orders\"") } shouldBe 2L
    }

    @Test
    fun `a mixed-case source alias keeps its exact case in the staged table`() {
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INTEGER)")
            src.exec("INSERT INTO t VALUES (7)")
            runBlocking { staging.stage(src.query("SELECT id AS \"OrderId\" FROM t"), "stg_case", Dialect.H2) }
        }

        // The quoted identifier round-trips case-preserved; the quoted reference resolves.
        staging.readFromStaging { scalarLong(it, "SELECT \"OrderId\" FROM \"stg_case\"") } shouldBe 7L
    }

    @Test
    fun `staging the same table name twice fails and leaves the first table intact`() {
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INTEGER)")
            src.exec("INSERT INTO t VALUES (1), (2), (3)")
            runBlocking { staging.stage(src.query("SELECT id FROM t"), "stg_dup", Dialect.H2) }

            val thrown =
                shouldThrow<StagingTableAlreadyExistsException> {
                    runBlocking { staging.stage(src.query("SELECT id FROM t"), "stg_dup", Dialect.H2) }
                }
            thrown.code shouldBe StagingErrorCodes.TABLE_ALREADY_EXISTS
            thrown.tableName shouldBe "stg_dup"
        }

        // First table's three rows survive the rejected second attempt.
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_dup\"") } shouldBe 3L
        runBlocking { staging.stats() }.tableCount shouldBe 1
    }
}
