package co.datapipelines.lake.settle

import co.datapipelines.lake.CatalogDatabase
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    @Test
    fun `the abandoned read's documented bound names this socket timeout, so the number cannot drift from the constant`() {
        val lake = LakeTestDb.repoFile("docs/lake.md").readText().replace(Regex("\\s+"), " ")
        val seconds = LakeCatalogs.STORE_SOCKET_TIMEOUT_MILLIS / MILLIS_PER_SECOND

        lake shouldContain
            "**an abandoned read ends at its next chunk, or after the store's $seconds-second socket timeout if that chunk never comes**"
    }

    @Test
    fun `the metadata read's cap is the documented 64 MiB`() {
        LakeMetadataReads.METADATA_MAX_BYTES shouldBe MIB * CAP_MIB
        LakeTestDb.repoFile("docs/lake.md").readText().replace(Regex("\\s+"), " ") shouldContain
            "capped at $CAP_MIB MiB of JSON after decompression"
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MIB = 1_024L * 1_024L
        const val CAP_MIB = 64L
    }
}
