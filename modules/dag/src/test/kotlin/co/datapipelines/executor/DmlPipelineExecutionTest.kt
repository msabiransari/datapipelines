package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.pipeline.DmlInput
import co.datapipelines.pipeline.DmlKey
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * #1034 through the whole [PipelineExecutor]: run admission's re-application of the DML shape
 * rules, and the record's §9 pipeline — an order insert staged to tempdb, its `id` consumed by a
 * downstream line insert, a separate discarded result, and no caller node at all (stats only).
 * Durable rows are read on a second connection the executor never touched.
 */
class DmlPipelineExecutionTest {
    @Test
    fun `an operation-less DML node is refused at run admission before any business write`() =
        runBlocking<Unit> {
            val shop = shop()
            ExecutorHarness(
                templateEngine =
                    Fixtures.templateEngine(
                        mapOf("first" to "UPDATE orders SET customer = 'touched'", "legacy" to "DELETE FROM orders"),
                    ),
                registry = FakeDatasourceRegistry(mapOf("shop" to shop)),
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures.node("first", type = NodeType.DML, source = "shop"),
                        Fixtures.node("legacy", type = NodeType.DML, source = "shop", dependsOn = listOf("first")).copy(operation = null),
                    )

                val thrown = shouldThrow<DatapipelinesException> { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) }

                // Refused before ANY node ran — the valid first writer included — so nothing was
                // written; asserted first, since the node-level refusal alone would also throw the code.
                h.emitter.events
                    .filterIsInstance<co.datapipelines.events.NodeStarted>()
                    .shouldBeEmpty()
                customers(shop) shouldBe listOf("seed")
                thrown.code shouldBe PipelineErrorCodes.Validation.DML_OPERATION_REQUIRED
                thrown.details["path"] shouldBe "nodes[1].operation"
            }
        }

    @Test
    fun `a staged order id feeds a line insert, a separate result is discarded, and a zero-caller run returns stats only`() =
        runBlocking<Unit> {
            val shop = shop()
            ExecutorHarness(
                templateEngine =
                    Fixtures.templateEngine(
                        mapOf(
                            "order" to "INSERT INTO orders (customer) VALUES (:customer)",
                            "line_input" to """SELECT "id" AS "order_id", 'SKU-1' AS "sku" FROM "new_order"""",
                            "lines" to "INSERT INTO order_lines (order_id, sku) VALUES (:order_id, :sku)",
                            "audit" to "UPDATE orders SET customer = customer WHERE customer = 'seed'",
                        ),
                    ),
                registry = FakeDatasourceRegistry(mapOf("shop" to shop)),
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures
                            .node(
                                "order",
                                type = NodeType.DML,
                                source = "shop",
                                operation = "insert",
                                dmlOutput = NodeOutput.Tempdb("new_order"),
                            ).copy(key = DmlKey("order_id", "BIGINTEGER"), input = DmlInput(parameters = listOf("customer"))),
                        Fixtures.node("line_input", output = NodeOutput.Tempdb("lines_in"), dependsOn = listOf("order")),
                        Fixtures
                            .node("lines", type = NodeType.DML, source = "shop", operation = "insert", dependsOn = listOf("line_input"))
                            .copy(key = DmlKey("line_id", "INTEGER"), input = DmlInput(table = "lines_in")),
                        Fixtures.node("audit", type = NodeType.DML, source = "shop", dependsOn = listOf("lines")),
                    )
                val pipeline = Fixtures.pipeline(nodes, parameters = mapOf("customer" to Parameter(LogicalType.STRING, required = true)))
                val request = Fixtures.request(pipeline).copy(parameters = mapOf("customer" to TextNode("ada")))

                val result = h.executor.execute(request)

                result.status shouldBe ExecutionStatus.SUCCESS
                result.resultRef.shouldBeNull()
                result.nodeStats.associate { it.nodeId to it.rowsOut } shouldBe
                    mapOf("order" to 1L, "line_input" to 1L, "lines" to 1L, "audit" to 1L)
            }
            val orderId = orderIds(shop).single { it != SEED_ID }
            lines(shop) shouldBe listOf(orderId to "SKU-1")
        }

    private fun shop(): Datasource =
        h2Datasource(
            "shop",
            listOf(
                "CREATE TABLE orders (order_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, customer VARCHAR(40) NOT NULL)",
                "CREATE TABLE order_lines (line_id INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                    "order_id BIGINT NOT NULL, sku VARCHAR(20))",
                "INSERT INTO orders (order_id, customer) VALUES ($SEED_ID, 'seed')",
            ),
        )

    private fun <T> query(
        shop: Datasource,
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        DriverManager.getConnection(shop.jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
            }
        }

    private fun customers(shop: Datasource) = query(shop, "SELECT customer FROM orders ORDER BY order_id") { it.getString(1) }

    private fun orderIds(shop: Datasource) = query(shop, "SELECT order_id FROM orders ORDER BY order_id") { it.getLong(1) }

    private fun lines(shop: Datasource) = query(shop, "SELECT order_id, sku FROM order_lines") { it.getLong(1) to it.getString(2) }

    private companion object {
        const val SEED_ID = 100L
    }
}
