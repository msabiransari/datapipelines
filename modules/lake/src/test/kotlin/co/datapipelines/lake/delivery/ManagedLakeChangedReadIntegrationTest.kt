package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.append.AppendPublicationHarness
import co.datapipelines.lake.append.ManagedLakeAppendPort
import co.datapipelines.pipeline.DatasourceFacts
import co.datapipelines.pipeline.DeliveryRef
import co.datapipelines.pipeline.LakeExecutionInput
import co.datapipelines.pipeline.LakeExecutionLineage
import co.datapipelines.pipeline.LakeStatementExecution
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TempdbSettings
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.StagingEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.util.unit.DataSize
import software.amazon.awssdk.core.sync.RequestBody
import java.net.URI
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/** E9 across the actual port, trusted listing, native S3 materialization, re-list and PostgreSQL cleanup. */
class ManagedLakeChangedReadIntegrationTest {
    @Test
    @Suppress("LongMethod", "NestedBlockDepth", "SwallowedException") // Keep the E9 sequence visible; SQL errors may contain credentials.
    fun `an overwrite after listing is actually read then refused before publication without deleting its source`() {
        val h = AppendPublicationHarness()
        h.reset()
        val properties =
            LakeProperties(
                enabled = true,
                storage = h.properties.storage,
                admission = LakeProperties.Admission(maxDeliveryBytes = DataSize.ofMegabytes(16), leaseWaitSeconds = 0),
            )
        val key = DeliveryKey("changed799", UUID.randomUUID().toString())
        val path = key.prefix(properties.storage.landingPrefix)
        LakeMinio.client().use { client ->
            client.putObject(
                { it.bucket(properties.storage.bucket).key(path + "input.csv") },
                RequestBody.fromString("id\n11\n22\n"),
            )
            client.putObject({ it.bucket(properties.storage.bucket).key(path + "_done") }, RequestBody.empty())
            val storage =
                LakeStagingStorage { connection, _, statements ->
                    // This callback can only run after admission's trusted listing, on the unrestricted staging root.
                    client.putObject(
                        { it.bucket(properties.storage.bucket).key(path + "input.csv") },
                        RequestBody.fromString("id\n99\n99\n"),
                    )
                    try {
                        connection.createStatement().use { statement ->
                            statement.queryTimeout = 30
                            statements.execute(statement) { statement.execute("INSTALL httpfs FROM core") }
                            statements.execute(statement) { statement.execute("LOAD httpfs") }
                            statements.execute(statement) {
                                statement.execute(
                                    "CREATE SECRET changed799 (TYPE s3, KEY_ID '${properties.storage.accessKey}', " +
                                        "SECRET '${properties.storage.secretKey}', REGION 'us-east-1', " +
                                        "ENDPOINT '${URI.create(properties.storage.endpoint).authority}', URL_STYLE 'path', USE_SSL false)",
                                )
                            }
                        }
                    } catch (failure: SQLException) {
                        throw AssertionError("Test storage setup failed; SQLSTATE=${failure.sqlState}")
                    }
                }
            val landing =
                DeliveryLanding(
                    DeliveryAdmission(properties.admission, h.leases::catalogNowPlus),
                    TrustedDeliveryListing(client, properties.storage, properties.admission.maxDeliveryBytes.toBytes()),
                    h.h.definitions,
                    h.ledger,
                    h.settler,
                )
            val port =
                ManagedLakeAppendPort(properties, landing, storage, h.runner(client)) { _, _ ->
                    DatasourceFacts(Dialect.LAKE, readonly = true, managedLake = true)
                }
            val pipeline =
                Pipeline(
                    Pipeline.SUPPORTED_SCHEMA_VERSION,
                    "test/changed_read",
                    "Changed read",
                    "E9 fixture",
                    settings = PipelineSettings(tempdb = TempdbSettings(engine = StagingEngine.DUCKDB)),
                    parameters = emptyMap(),
                    nodes =
                        listOf(
                            Node(
                                "land",
                                "Read",
                                NodeType.DQL,
                                "tempdb",
                                TemplateRef("test/read.sql", 1),
                                NodeOutput.Tempdb("staged"),
                                emptyList(),
                                delivery = DeliveryRef(key.feed, key.id),
                            ),
                            Node(
                                "append",
                                "Append",
                                NodeType.DQL,
                                "tempdb",
                                TemplateRef("test/append.sql", 1),
                                NodeOutput.Datasource(
                                    properties.name,
                                    "${AppendPublicationHarness.NAMESPACE}.${AppendPublicationHarness.TABLE}",
                                    WriteMode.APPEND,
                                ),
                                listOf("land"),
                            ),
                        ),
                )
            val execution =
                LakeExecutionInput(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    UUID.randomUUID(),
                    pipeline,
                    null,
                    emptyMap(),
                    null,
                    LakeExecutionLineage(),
                )
            val prepared = port.prepare(execution, DeliveryRef(key.feed, key.id), emptyMap()) { AutoCloseable {} }
            try {
                DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("SET memory_limit = '256MB'")
                        statement.execute("SET threads = 1")
                    }
                    prepared.session.initialize(connection, LakeStatementExecution.DIRECT)
                    connection.createStatement().use { statement ->
                        statement.execute("SET enable_external_access = false")
                        statement.execute("SET lock_configuration = true")
                        statement.executeQuery("SELECT sum(id) FROM delivery").use { rows ->
                            rows.next()
                            rows.getLong(1) shouldBe 198L
                        }
                    }
                    val refusal = shouldThrow<DatapipelinesException> { prepared.session.readCompleted() }
                    refusal.code shouldBe Lake.DELIVERY_CHANGED_DURING_READ
                }
            } finally {
                prepared.session.close()
            }
            h.jdbc.queryForObject(
                "SELECT state || ':' || failure_phase || ':' || failure_code FROM lake_deliveries WHERE feed = ? AND delivery_id = ?",
                String::class.java,
                key.feed,
                key.id,
            ) shouldBe "FAILED:staging:lake.delivery.changed_during_read"
            h.h.revisions(AppendPublicationHarness.NAMESPACE, AppendPublicationHarness.TABLE) shouldBe 1
            h.dataKeys().size shouldBe 0
            client.listObjectsV2 { it.bucket(properties.storage.bucket).prefix(path) }.contents().size shouldBe 2
            println("case=changed_read observed_sum=198 ledger=FAILED phase=staging revisions=1 retained_sources=2")
        }
    }
}
