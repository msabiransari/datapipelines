package co.datapipelines.lake.append

import ch.qos.logback.classic.Logger
import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.body
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.field
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.partition
import co.datapipelines.lake.delivery.LakeDeliveryState
import co.datapipelines.logcapture.capturingLogEvents
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.apache.iceberg.DataFile
import org.apache.iceberg.Table
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/** #967 V3–V5 through the runner (the fenced path): real cursors, Postgres catalog, MinIO files. */
class TruncatePartitionRunnerIntegrationTest {
    private val h = AppendPublicationHarness()

    @ParameterizedTest(name = "{0}")
    @MethodSource("edgeDeliveries")
    fun `an edge delivery fails before a data file, recipe or catalog revision`(
        name: String,
        definition: String,
        sql: String,
        key: String,
    ) {
        h.reset(definition)
        LakeMinio.client().use { client ->
            h.withRequest(sql = sql) { request, input ->
                val failure = shouldThrow<DatapipelinesException> { h.runner(client).publish(request) {} }
                withClue(name) {
                    failure.code shouldBe Lake.APPEND_PARTITION_VALUE_OUT_OF_RANGE
                    failure.details shouldBe mapOf("out_of_range_counts" to mapOf(key to 1L))
                    input.scans shouldBe 1
                    h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.FAILED
                    h.dataKeys() shouldBe emptyList()
                    h.keys(h.recipePrefix()) shouldBe emptyList()
                    h.h.revisions(AppendPublicationHarness.NAMESPACE, AppendPublicationHarness.TABLE) shouldBe 1
                    leaseCount(request) shouldBe 0
                    h.watchesClosed shouldBe 1
                }
            }
        }
    }

    @Test
    fun `every offending row is counted per partition field and no value reaches the error or a log line`() {
        h.reset(
            body(
                listOf(ID, field(2, "amount", "DECIMAL", "precision" to 15, "scale" to 3), field(3, "n", "INTEGER")),
                partition = listOf(partition(2, "truncate[2]"), partition(3, "truncate[10]")),
            ),
        )
        val sql =
            "SELECT * FROM (VALUES (1::BIGINT, '$D15_LOW'::DECIMAL(15,3), 5), (2::BIGINT, '$D15_LOW'::DECIMAL(15,3), 6), " +
                "(3::BIGINT, '$D15_LOW'::DECIMAL(15,3), 7), (4::BIGINT, 1.5::DECIMAL(15,3), (-2147483648)::INTEGER), " +
                "(5::BIGINT, 2.5::DECIMAL(15,3), 8)) t(id, amount, n)"
        var failure: DatapipelinesException? = null
        val events =
            capturingLogEvents(LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger) {
                LakeMinio.client().use { client ->
                    h.withRequest(sql = sql) { request, _ ->
                        failure = shouldThrow<DatapipelinesException> { h.runner(client).publish(request) {} }
                    }
                }
            }
        val refused = requireNotNull(failure)
        refused.code shouldBe Lake.APPEND_PARTITION_VALUE_OUT_OF_RANGE
        refused.details shouldBe mapOf("out_of_range_counts" to mapOf("truncate[2](amount)" to 3L, "truncate[10](n)" to 1L))
        val serialized =
            PipelineJson.objectMapper().writeValueAsString(
                mapOf("error" to mapOf("code" to refused.code, "message" to refused.message, "details" to refused.details)),
            )
        val logged = events.flatMap { listOfNotNull(it.formattedMessage, it.throwableProxy?.message) }
        logged.shouldNotBeEmpty() // the runner's own failure lines were captured, so their absence of digits means something
        (listOf(serialized) + logged).forEach { text ->
            text shouldNotContain "999999999999"
            text shouldNotContain "2147483648"
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("neighbourDeliveries")
    fun `the neighbours publish and every file's partition value fits and is not above its rows`(
        name: String,
        definition: String,
        sql: String,
    ) {
        h.reset(definition)
        LakeMinio.client().use { client ->
            h.withRequest(sql = sql) { request, _ ->
                withClue(name) {
                    h.runner(client).publish(request) {} shouldBe 5
                    h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.PUBLISHED
                }
            }
        }
        h.withTable { table ->
            val files = files(table)
            files.sumOf { it.recordCount() } shouldBe 5
            files.forEach { file ->
                val partition = file.partition().get(0, Any::class.java) ?: return@forEach
                val values = h.native.read(table, listOf(file)).mapNotNull { it.getField("v") }
                when (partition) {
                    is BigDecimal -> {
                        withClue("partition precision fits decimal(15,3)") { (partition.precision() <= 15) shouldBe true }
                        values.all { partition <= it as BigDecimal } shouldBe true
                    }

                    is Int -> {
                        values.all { partition <= it as Int } shouldBe true
                    }

                    else -> {
                        error("unexpected partition value type ${partition.javaClass}")
                    }
                }
            }
        }
    }

    @Test
    fun `the applied column decides - a promoted value whose partition fits the wider table column publishes`() {
        h.reset(body(listOf(ID, field(2, "v", "BIGDECIMAL", "precision" to 16, "scale" to 3)), partition = listOf(partition(2, "truncate[2]"))))
        LakeMinio.client().use { client ->
            h.withRequest(sql = "SELECT '$D15_LOW'::DECIMAL(15,3) AS v, 1::BIGINT AS id") { request, _ ->
                h.runner(client).publish(request) {} shouldBe 1
            }
        }
        h.withTable { table ->
            files(table).single().partition().get(0, BigDecimal::class.java) shouldBe BigDecimal("-1000000000000.000")
        }
    }

    @Test
    fun `a delivery without the optional partition source column publishes a null partition value`() {
        h.reset(body(listOf(ID, field(2, "v", "INTEGER")), partition = listOf(partition(2, "truncate[10]"))))
        LakeMinio.client().use { client ->
            h.withRequest(sql = "SELECT 1::BIGINT AS id") { request, _ -> h.runner(client).publish(request) {} shouldBe 1 }
        }
        h.withTable { table -> files(table).single().partition().get(0, Any::class.java) shouldBe null }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uncheckedDeliveries")
    fun `what is not checked still publishes`(
        name: String,
        definition: String,
        sql: String,
    ) {
        h.reset(definition)
        LakeMinio.client().use { client ->
            h.withRequest(sql = sql) { request, _ ->
                withClue(name) { h.runner(client).publish(request) {} shouldBe 2 }
            }
        }
    }

    private fun files(table: Table): List<DataFile> = table.newScan().planFiles().use { tasks -> tasks.map { it.file() }.toList() }

    private fun leaseCount(request: AppendPublicationInput): Int? =
        h.jdbc.queryForObject("SELECT count(*) FROM lake_leases WHERE operation_id = ?", Int::class.java, request.delivery.operationId)

    companion object {
        private const val D15_LOW = "-999999999999.999"
        private val ID = field(1, "id", "BIGINTEGER", "required" to true)
        private val DECIMAL_15 = body(listOf(ID, field(2, "v", "DECIMAL", "precision" to 15, "scale" to 3)), partition = listOf(partition(2, "truncate[2]")))
        private val INTEGER_10 = body(listOf(ID, field(2, "v", "INTEGER")), partition = listOf(partition(2, "truncate[10]")))

        private fun rows(vararg values: String): String =
            "SELECT * FROM (VALUES " + values.mapIndexed { index, value -> "(${index + 1}::BIGINT, $value)" }.joinToString() + ") t(id, v)"

        @JvmStatic
        fun edgeDeliveries(): List<Array<Any>> =
            listOf(
                arrayOf("decimal(15,3) truncate[2]", DECIMAL_15, rows("1.5::DECIMAL(15,3)", "'$D15_LOW'::DECIMAL(15,3)"), "truncate[2](v)"),
                arrayOf("INTEGER truncate[10]", INTEGER_10, rows("7::INTEGER", "(-2147483648)::INTEGER"), "truncate[10](v)"),
            )

        /** The nearest value that fits (probes.out), -1, zero, the highest value, a null in the optional column. */
        @JvmStatic
        fun neighbourDeliveries(): List<Array<Any>> =
            listOf(
                arrayOf(
                    "decimal(15,3) truncate[2]",
                    DECIMAL_15,
                    rows(
                        "'-999999999999.998'::DECIMAL(15,3)",
                        "'-1.000'::DECIMAL(15,3)",
                        "'0.000'::DECIMAL(15,3)",
                        "'999999999999.999'::DECIMAL(15,3)",
                        "NULL::DECIMAL(15,3)",
                    ),
                ),
                arrayOf(
                    "INTEGER truncate[10]",
                    INTEGER_10,
                    rows("(-2147483638)::INTEGER", "(-1)::INTEGER", "0::INTEGER", "2147483647::INTEGER", "NULL::INTEGER"),
                ),
            )

        /** `identity` and `bucket[16]` on an INTEGER holding its lowest value, `truncate[4]` on a string. */
        @JvmStatic
        fun uncheckedDeliveries(): List<Array<Any>> =
            listOf(
                arrayOf(
                    "identity on INTEGER",
                    body(listOf(ID, field(2, "v", "INTEGER")), partition = listOf(partition(2, "identity"))),
                    rows("(-2147483648)::INTEGER", "5::INTEGER"),
                ),
                arrayOf(
                    "bucket[16] on INTEGER",
                    body(listOf(ID, field(2, "v", "INTEGER")), partition = listOf(partition(2, "bucket[16]"))),
                    rows("(-2147483648)::INTEGER", "5::INTEGER"),
                ),
                arrayOf(
                    "truncate[4] on STRING",
                    body(listOf(ID, field(2, "v", "STRING")), partition = listOf(partition(2, "truncate[4]"))),
                    rows("'abcdef'", "''"),
                ),
            )
    }
}
