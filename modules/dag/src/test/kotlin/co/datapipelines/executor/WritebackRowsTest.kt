package co.datapipelines.executor

import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * `WritebackRunner.writebackRows` — the composition write-back path (design §4.2): a parent
 * PIPELINE node with `output.target: "datasource"` lands the child's already-decoded rows here.
 * Same contract as the cursor-driven `writeback`: one transaction, identifier guards, and the
 * SQLState-driven target-missing mapping.
 */
class WritebackRowsTest {
    private val schema =
        listOf(
            ColumnSchema("id", LogicalType.INTEGER),
            ColumnSchema("label", LogicalType.STRING),
        )

    @Test
    fun `rows land in the target table and the written count is returned`() {
        val datasource = h2Datasource("wb", listOf("CREATE TABLE tgt (id INT, label VARCHAR(20))"))
        val runner = JdbcWritebackRunner(FakeDatasourceRegistry(mapOf("wb" to datasource)))

        val written =
            runner.writebackRows(
                schema,
                sequenceOf(listOf(1, "a"), listOf(2, "b"), listOf(3, null)),
                NodeOutput.Datasource("wb", "tgt", WriteMode.APPEND),
            )

        written shouldBe 3L
        DriverManager.getConnection(datasource.jdbcUrl, datasource.username, "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM tgt").use { rs ->
                    rs.next()
                    rs.getLong(1) shouldBe 3L
                }
                statement.executeQuery("SELECT COUNT(*) FROM tgt WHERE label IS NULL").use { rs ->
                    rs.next()
                    rs.getLong(1) shouldBe 1L
                }
            }
        }
    }

    @Test
    fun `replace truncates and inserts in one transaction`() {
        val datasource =
            h2Datasource("wb", listOf("CREATE TABLE tgt (id INT)", "INSERT INTO tgt VALUES (100)"))
        val runner = JdbcWritebackRunner(FakeDatasourceRegistry(mapOf("wb" to datasource)))
        val idSchema = listOf(ColumnSchema("id", LogicalType.INTEGER))

        runner.writebackRows(idSchema, sequenceOf(listOf(1)), NodeOutput.Datasource("wb", "tgt", WriteMode.REPLACE)) shouldBe 1L

        DriverManager.getConnection(datasource.jdbcUrl, datasource.username, "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM tgt").use { rs ->
                    rs.next()
                    rs.getLong(1) shouldBe 1L
                }
            }
        }
    }

    @Test
    fun `a missing target reports writeback_target_missing, as the cursor path does`() {
        val datasource = h2Datasource("wb", emptyList())
        val runner = JdbcWritebackRunner(FakeDatasourceRegistry(mapOf("wb" to datasource)))
        val thrown =
            shouldThrow<DatapipelinesException> {
                runner.writebackRows(
                    listOf(ColumnSchema("id", LogicalType.INTEGER)),
                    sequenceOf(listOf(1)),
                    NodeOutput.Datasource("wb", "nope", WriteMode.APPEND),
                )
            }

        thrown.code shouldBe PipelineErrorCodes.Node.WRITEBACK_TARGET_MISSING
    }

    @Test
    fun `a readonly target fails datasource_readonly on the composition path too (workspaces §6 shape 3)`() {
        // The parent PIPELINE node's rows land here via writebackRows — the backstop lives in
        // the shared writeAll shell, so BOTH row sources are behind it by construction.
        val datasource =
            h2Datasource("wb", listOf("CREATE TABLE tgt (id INT)")).copy(isReadonly = true)
        val runner = JdbcWritebackRunner(FakeDatasourceRegistry(mapOf("wb" to datasource)))

        val thrown =
            shouldThrow<DatapipelinesException> {
                runner.writebackRows(
                    listOf(ColumnSchema("id", LogicalType.INTEGER)),
                    sequenceOf(listOf(1)),
                    NodeOutput.Datasource("wb", "tgt", WriteMode.APPEND),
                )
            }

        thrown.code shouldBe PipelineErrorCodes.Node.DATASOURCE_READONLY
        thrown.details["datasource"] shouldBe "wb"
        thrown.details["table"] shouldBe "tgt"
    }

    @Test
    fun `a mid-stream failure commits nothing`() {
        val datasource = h2Datasource("wb", listOf("CREATE TABLE tgt (id INT)"))
        val runner = JdbcWritebackRunner(FakeDatasourceRegistry(mapOf("wb" to datasource)))
        val failing =
            sequence {
                yield(listOf(1))
                throw ChildCursorDied()
            }

        shouldThrow<ChildCursorDied> {
            runner.writebackRows(
                listOf(ColumnSchema("id", LogicalType.INTEGER)),
                failing,
                NodeOutput.Datasource("wb", "tgt", WriteMode.APPEND),
            )
        }

        DriverManager.getConnection(datasource.jdbcUrl, datasource.username, "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM tgt").use { rs ->
                    rs.next()
                    rs.getLong(1) shouldBe 0L
                }
            }
        }
    }

    /** The mid-stream child failure, as a named type (detekt: no generic throws). */
    private class ChildCursorDied : RuntimeException("child cursor died mid-stream")
}
