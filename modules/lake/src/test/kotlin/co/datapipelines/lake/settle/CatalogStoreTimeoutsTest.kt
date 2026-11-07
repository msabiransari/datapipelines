package co.datapipelines.lake.settle

import co.datapipelines.lake.CatalogDatabase
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeProperties
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The object store's two socket bounds on every FileIO the catalog builds (record §8b, D4): so a STALLED store ends the
 * read a settlement tick abandoned. The keys are spelled out here, because Iceberg reads them by these strings: a typo in
 * the source would still compile and leave the SDK's defaults in place.
 */
class CatalogStoreTimeoutsTest {
    private val properties =
        LakeCatalogs(
            LakeProperties(enabled = true, storage = LakeProperties.Storage(bucket = "lake", region = "us-east-1")),
            CatalogDatabase("jdbc:postgresql://localhost/none", "none", "none"),
        ).catalogProperties()

    @Test
    fun `the connection bound stays at the SDK's two seconds`() {
        properties["http-client.urlconnection.connection-timeout-ms"] shouldBe "2000"
    }

    @Test
    fun `the socket bound is the sweep's ten-second budget, not the SDK's thirty`() {
        properties["http-client.urlconnection.socket-timeout-ms"] shouldBe LakeSettlementSweep.SWEEP_BUDGET.toMillis().toString()
    }
}
