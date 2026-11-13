package co.datapipelines.lake.append

import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.PgResponseDropProxy
import co.datapipelines.lake.delivery.LakeDeliveryAuditEvents
import co.datapipelines.lake.delivery.LakeDeliveryState
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** E1's real protocol proxy, already ported for P2: drop the committed CAS response through the production pool. */
class AppendLostAcknowledgementIntegrationTest {
    @Test
    fun `lost commit acknowledgement records unknown then settlement proves publication without replaying the CAS`() {
        val h = AppendPublicationHarness()
        h.reset()
        PgResponseDropProxy(LakeTestDb.host, LakeTestDb.port).use { proxy ->
            val catalogs = LakeCatalogs(h.properties, LakeTestDb.catalogDatabase(proxy.url(LakeTestDb.DATABASE)))
            LakeMinio.client().use { client ->
                h.withRequest { request, _ ->
                    proxy.arm()
                    shouldThrow<DatapipelinesException> { h.runner(client, catalogs).publish(request) {} }.code shouldBe
                        Lake.APPEND_OUTCOME_UNKNOWN
                    proxy.awaitDropped(5) shouldBe true
                    proxy.withheld shouldContain "C:UPDATE 1"
                    proxy.casSent.get() shouldBe 1
                    h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.PUBLISHED
                    h.deliveries.committedEvidence(request.delivery.id, request.delivery.operationId).shouldNotBeNull()
                    h.h.revisions(AppendPublicationHarness.NAMESPACE, AppendPublicationHarness.TABLE) shouldBe 2
                    h.h.audit.events
                        .filter { it.event.startsWith("lake.delivery.") }
                        .map { it.event } shouldBe
                        listOf(
                            LakeDeliveryAuditEvents.UNKNOWN,
                            LakeDeliveryAuditEvents.PUBLISHED,
                            LakeDeliveryAuditEvents.SETTLED,
                        )
                    h.h.audit.events
                        .filter { it.event.startsWith("lake.delivery.") }
                        .all { it.inTransaction } shouldBe true
                    h.dataKeys().isNotEmpty() shouldBe true
                    println("case=lost_ack revisions=2 ledger=PUBLISHED attempts=1 cas=1 intermediate=UNKNOWN")
                }
            }
        }
    }
}
