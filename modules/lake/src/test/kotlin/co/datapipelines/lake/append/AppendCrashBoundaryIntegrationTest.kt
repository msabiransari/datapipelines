package co.datapipelines.lake.append

import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.delivery.LakeDeliveryState
import co.datapipelines.lake.delivery.ReceiveDelivery
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/** Replays the durable state left at two pre-commit crash boundaries, using actual attempts and immutable recipes. */
class AppendCrashBoundaryIntegrationTest {
    @Test
    fun `crash after attempt admission leaves received then sweep fails it and a fresh attempt publishes once`() {
        val h = AppendPublicationHarness()
        h.reset()
        LakeMinio.client().use { client ->
            h.withRequest { request, _ ->
                h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.RECEIVED
                h.settler.settle(request.delivery.tableId, request.deadline) shouldBe 1
                h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.FAILED
                val next = retry(h, request)
                h.runner(client).publish(next) {} shouldBe 3L
                assertPublishedOnce(h, next)
                println("case=crash_after_attempt attempts=2 ledger=PUBLISHED revisions=2 data_files=1")
            }
        }
    }

    @Test
    fun `crash after recipe attachment leaves an immutable orphan and retry references only its own recipe`() {
        val h = AppendPublicationHarness()
        h.reset()
        LakeMinio.client().use { client ->
            h.withRequest { request, _ ->
                val lease =
                    h.leases
                        .acquire(
                            LeaseKind.TABLE,
                            "${request.target.namespace}.${request.target.table}",
                            request.delivery.operationId,
                            Duration.ofMinutes(2),
                            request.deadline,
                            "append",
                        ).shouldBeInstanceOf<LeaseOutcome.Acquired>()
                        .lease
                h.ledger.beginPublishing(request.delivery) shouldBe true
                val publishing = requireNotNull(h.deliveries.find(request.delivery.id))
                val orphan =
                    h.h.catalogs.open(lease).use { session ->
                        val table = session.loadTable(request.target.namespace, request.target.table)
                        val provenance = AppendRecipeAssembler(h.h.definitions, h.deliveries, h.leases).build(request, table)
                        RecipeWriter(client, h.properties.storage, RecipeDocument(RecipeRedactor { emptyMap() }))
                            .write(table.location(), provenance, request.deadline)
                    }
                h.deliveries.attachRecipe(publishing, orphan.key, orphan.sha256) shouldBe 1
                val before = client.getObjectAsBytes { it.bucket(h.properties.storage.bucket).key(orphan.key) }.asByteArray()
                // No commit or terminal stamp ran. The disappeared worker's lease has now expired by PostgreSQL's clock.
                h.jdbc.update(
                    "UPDATE lake_leases SET expires_at = now() - interval '1 second' WHERE operation_id = ?",
                    request.delivery.operationId,
                )
                h.settler.settle(request.delivery.tableId, request.deadline) shouldBe 1
                h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.FAILED
                h.deliveries.committedEvidence(request.delivery.id, request.delivery.operationId) shouldBe null
                val next = retry(h, request)
                h.runner(client).publish(next) {} shouldBe 3L
                assertPublishedOnce(h, next)
                client
                    .getObjectAsBytes {
                        it
                            .bucket(
                                h.properties.storage.bucket,
                            ).key(orphan.key)
                    }.asByteArray()
                    .contentEquals(before) shouldBe
                    true
                h.withTable { table ->
                    (table.currentSnapshot().summary()["dp.recipe"] == orphan.key) shouldBe false
                    table.currentSnapshot().summary()["dp.operation.id"] shouldBe next.delivery.operationId.toString()
                }
                h.keys(h.recipePrefix()).size shouldBe 2
                println("case=crash_after_recipe attempts=2 ledger=PUBLISHED revisions=2 retained_recipes=2 data_files=1")
            }
        }
    }

    private fun retry(
        h: AppendPublicationHarness,
        request: AppendPublicationInput,
    ): AppendPublicationInput {
        val next =
            h.ledger
                .receive(
                    ReceiveDelivery(
                        request.delivery.tableId,
                        request.manifest,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        request.execution.pipelineId,
                    ),
                ).delivery
        return request.copy(delivery = next, execution = request.execution.copy(executionId = next.executionId))
    }

    private fun assertPublishedOnce(
        h: AppendPublicationHarness,
        request: AppendPublicationInput,
    ) {
        h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.PUBLISHED
        h.jdbc.queryForObject(
            "SELECT count(*) FROM lake_delivery_attempts WHERE delivery_id = ?",
            Int::class.java,
            request.delivery.id,
        ) shouldBe
            2
        h.h.revisions(AppendPublicationHarness.NAMESPACE, AppendPublicationHarness.TABLE) shouldBe 2
        h.dataKeys().size shouldBe 1
    }
}
