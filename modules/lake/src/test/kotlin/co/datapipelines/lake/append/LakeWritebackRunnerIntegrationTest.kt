package co.datapipelines.lake.append

import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.delivery.LakeDeliveryState
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException

/** Actual fenced catalog transactions, files, immutable recipes and permanent delivery evidence. */
class LakeWritebackRunnerIntegrationTest {
    private val h = AppendPublicationHarness()

    @BeforeEach
    fun reset() = h.reset()

    @Test
    fun `publication commits data recipe summaries and durable evidence before repairing the projection`() {
        LakeMinio.client().use { client ->
            h.withRequest { request, _ ->
                h.runner(client).publish(request) {} shouldBe 3
                h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.PUBLISHED
                val evidence = h.deliveries.committedEvidence(request.delivery.id, request.delivery.operationId).shouldNotBeNull()
                h.withTable { table ->
                    val snapshot = table.currentSnapshot().shouldNotBeNull()
                    snapshot.summary()["dp.operation.id"] shouldBe request.delivery.operationId.toString()
                    snapshot.summary()["dp.delivery.key"] shouldBe "feed/batch"
                    snapshot.summary()["total-records"] shouldBe "3"
                    val recipe =
                        client
                            .getObjectAsBytes {
                                it
                                    .bucket(
                                        h.properties.storage.bucket,
                                    ).key(snapshot.summary()["dp.recipe"])
                            }.asByteArray()
                    snapshot.summary()["dp.recipe.sha256"] shouldBe
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(recipe))
                    val document = PipelineJson.objectMapper().readTree(recipe)
                    document.path("pipeline").path("body_hash").asText() shouldBe request.execution.metadata?.bodyHash
                    document.path("exact_replay_possible").asBoolean() shouldBe false
                    recipe.toString(Charsets.UTF_8).contains("resolved-secret-799") shouldBe false
                    recipe.toString(Charsets.UTF_8).contains("default-secret-799") shouldBe false
                    val files = table.newScan().planFiles().use { tasks -> tasks.map { it.file() }.toList() }
                    h.native
                        .read(table, files)
                        .map { it.getField("id") as Long }
                        .sorted() shouldBe listOf(0L, 1L, 2L)
                }
                h.deliveries.find(request.delivery.id)?.metadataLocationAfter shouldBe evidence.metadataLocation
                h.h.revisions(AppendPublicationHarness.NAMESPACE, AppendPublicationHarness.TABLE) shouldBe 2
                h.repaired shouldBe listOf(h.properties.name)
                h.watchesStarted shouldBe 1
                h.watchesClosed shouldBe 1
                leaseCount(request) shouldBe 0
            }
        }
    }

    @Test
    fun `empty filtered delivery still commits durable duplicate evidence without a data file`() {
        LakeMinio.client().use { client ->
            h.withRequest(sql = "SELECT 1::BIGINT AS id WHERE false") { request, _ ->
                h.runner(client).publish(request) {} shouldBe 0
                h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.PUBLISHED
                h.deliveries.committedEvidence(request.delivery.id, request.delivery.operationId).shouldNotBeNull()
                h.dataKeys() shouldBe emptyList()
                h.withTable { table ->
                    table.currentSnapshot().shouldNotBeNull().summary()["dp.operation.id"] shouldBe
                        request.delivery.operationId.toString()
                }
            }
        }
    }

    @Test
    fun `a missing grant refuses before a table lease writer or recipe`() {
        LakeMinio.client().use { client ->
            h.withRequest(grant = false) { request, _ ->
                shouldThrow<DatapipelinesException> { h.runner(client).publish(request) {} }.code shouldBe Lake.APPEND_GRANT_MISSING
                h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.RECEIVED
                h.watchesStarted shouldBe 0
                leaseCount(request) shouldBe 0
                h.dataKeys() shouldBe emptyList()
                h.keys(h.recipePrefix()) shouldBe emptyList()
            }
        }
    }

    @Test
    fun `an occupied table lease refuses without changing its holder or starting publication`() {
        LakeMinio.client().use { client ->
            h.withRequest { request, _ ->
                val other = UUID.randomUUID()
                h.leases
                    .acquire(
                        LeaseKind.TABLE,
                        "${request.target.namespace}.${request.target.table}",
                        other,
                        Duration.ofMinutes(1),
                        request.deadline,
                        "apply",
                    ).shouldBeInstanceOf<LeaseOutcome.Acquired>()
                try {
                    shouldThrow<DatapipelinesException> { h.runner(client).publish(request) {} }.code shouldBe Lake.TABLE_BUSY
                    h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.RECEIVED
                    h.watchesStarted shouldBe 0
                    h.jdbc.queryForObject("SELECT operation_id FROM lake_leases WHERE kind = 'table'", UUID::class.java) shouldBe other
                } finally {
                    h.leases.release(other)
                }
            }
        }
    }

    @Test
    fun `required null validation fails before a data file recipe or catalog revision`() {
        LakeMinio.client().use { client ->
            h.withRequest(sql = "SELECT * FROM (VALUES (1::BIGINT), (NULL::BIGINT)) t(id)") { request, _ ->
                shouldThrow<DatapipelinesException> { h.runner(client).publish(request) {} }.code shouldBe Lake.APPEND_NULL_IN_REQUIRED
                h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.FAILED
                h.dataKeys() shouldBe emptyList()
                h.keys(h.recipePrefix()) shouldBe emptyList()
                h.h.revisions(AppendPublicationHarness.NAMESPACE, AppendPublicationHarness.TABLE) shouldBe 1
                leaseCount(request) shouldBe 0
                h.watchesClosed shouldBe 1
            }
        }
    }

    @Test
    fun `cancellation while streaming marks failure releases authority and retains its uncommitted file`() {
        LakeMinio.client().use { client ->
            h.withRequest { request, input ->
                var writtenChecks = 0
                shouldThrow<CancellationException> {
                    h.runner(client).publish(request) {
                        if (input.scans == 2 && ++writtenChecks == 2) throw CancellationException("Stopped by caller.")
                    }
                }
                h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.FAILED
                h.dataKeys().size shouldBe 1
                h.deliveries.committedEvidence(request.delivery.id) shouldBe null
                h.h.revisions(AppendPublicationHarness.NAMESPACE, AppendPublicationHarness.TABLE) shouldBe 1
                h.watchesClosed shouldBe 1
                leaseCount(request) shouldBe 0
            }
        }
    }

    @Test
    fun `a conflicting immutable recipe keeps the original object and never enters the catalog commit`() {
        LakeMinio.client().use { client ->
            h.withRequest { request, _ ->
                val key =
                    h.withTable { table ->
                        RecipeWriter.recipeKey(
                            table.location(),
                            h.properties.storage.bucket,
                            request.manifest.key,
                            request.delivery.operationId,
                        )
                    }
                client.putObject({ it.bucket(h.properties.storage.bucket).key(key) }, RequestBody.fromString("original-recipe"))
                shouldThrow<DatapipelinesException> { h.runner(client).publish(request) {} }.code shouldBe Lake.APPEND_RECIPE_EXISTS
                h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.FAILED
                client.getObjectAsBytes { it.bucket(h.properties.storage.bucket).key(key) }.asUtf8String() shouldBe "original-recipe"
                h.dataKeys().size shouldBe 1
                h.h.revisions(AppendPublicationHarness.NAMESPACE, AppendPublicationHarness.TABLE) shouldBe 1
                leaseCount(request) shouldBe 0
            }
        }
    }

    private fun leaseCount(request: AppendPublicationInput): Int? =
        h.jdbc.queryForObject("SELECT count(*) FROM lake_leases WHERE operation_id = ?", Int::class.java, request.delivery.operationId)
}
