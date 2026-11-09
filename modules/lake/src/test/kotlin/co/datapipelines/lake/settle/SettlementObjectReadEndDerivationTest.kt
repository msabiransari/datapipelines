package co.datapipelines.lake.settle

import co.datapipelines.lake.CatalogDatabase
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** Arithmetic for the documented model; no stalled-store timing experiment is claimed by these tests. */
class SettlementObjectReadEndDerivationTest {
    private val properties =
        LakeCatalogs(
            LakeProperties(enabled = true, storage = LakeProperties.Storage(bucket = "lake", region = "us-east-1")),
            CatalogDatabase("jdbc:postgresql://localhost/none", "none", "none"),
        ).catalogProperties()
    private val inputs = SettlementObjectReadEnd.effectiveInputs(properties)

    @Test
    fun `default inputs come from the catalog and the retry policy Iceberg actually configures`() {
        inputs shouldBe SettlementObjectReadEnd.Inputs(2_000, 10_000, 5, 100, 20_000)
    }

    @Test
    fun `default total includes every request, backoff gap, body read, retry close and final close`() {
        val attemptWaits = 6L * (2_000L + 10_000L)
        val backoffCaps = 100L + 200L + 400L + 800L + 1_600L
        val bodyReads = 4L * 10_000L
        val retryCloseProbesAndCloses = 3L * (10_000L + 10_000L)
        val finalCloseProbeAndClose = 10_000L + 10_000L
        val expected = 4L * (attemptWaits + backoffCaps) + bodyReads + retryCloseProbesAndCloses + finalCloseProbeAndClose

        expected shouldBe 420_400
        SettlementObjectReadEnd.stalledReadEndByMillis(inputs) shouldBe expected
        SettlementObjectReadEnd.stalledReadEndByMillis(properties) shouldBe expected
    }

    @Test
    fun `altered effective socket and request retries feed the derivation`() {
        val altered =
            properties +
                mapOf(
                    LakeCatalogs.HTTP_CONNECTION_TIMEOUT_MS to "7",
                    LakeCatalogs.HTTP_SOCKET_TIMEOUT_MS to "11",
                    "s3.retry.num-retries" to "1",
                )
        SettlementObjectReadEnd.stalledReadEndByMillis(altered) shouldBe 4L * (2L * (7L + 11L) + 100L + 3L * 11L)
    }

    @Test
    fun `zero SDK retries still counts all body retries and close reads, without any request backoff`() {
        SettlementObjectReadEnd.stalledReadEndByMillis(inputs.copy(requestRetries = 0)) shouldBe
            4L * (2_000L + 10_000L + 3L * 10_000L)
    }

    @Test
    fun `altered backoff base and ceiling saturate independently of the socket and retry terms`() {
        val altered = SettlementObjectReadEnd.Inputs(7, 11, 3, 80, 100)
        SettlementObjectReadEnd.stalledReadEndByMillis(altered) shouldBe
            4L * (4L * (7L + 11L) + 80L + 100L + 100L + 3L * 11L)
    }

    @Test
    fun `throttling wait properties do not change the ordinary socket-failure backoff`() {
        val altered = properties + mapOf("s3.retry.min-wait-ms" to "3000", "s3.retry.max-wait-ms" to "30000")
        SettlementObjectReadEnd.effectiveInputs(altered) shouldBe inputs
    }

    @Test
    fun `unbounded, overflowing and unsupported inputs are rejected`() {
        listOf(
            inputs.copy(connectTimeoutMillis = 0),
            inputs.copy(socketTimeoutMillis = 0),
            inputs.copy(connectTimeoutMillis = Long.MAX_VALUE),
            inputs.copy(socketTimeoutMillis = Long.MAX_VALUE),
            inputs.copy(requestRetries = -1),
            inputs.copy(requestRetries = Int.MAX_VALUE),
            inputs.copy(backoffBaseMillis = 0),
            inputs.copy(backoffMaxMillis = 0),
            inputs.copy(backoffBaseMillis = Long.MAX_VALUE),
            inputs.copy(backoffMaxMillis = Long.MAX_VALUE),
            inputs.copy(backoffMaxMillis = inputs.backoffBaseMillis - 1),
        ).forEach { invalid ->
            shouldThrow<IllegalArgumentException> { SettlementObjectReadEnd.stalledReadEndByMillis(invalid) }
        }
        shouldThrow<IllegalArgumentException> {
            SettlementObjectReadEnd.stalledReadEndByMillis(properties + ("s3.retry.num-retries" to "many"))
        }
        shouldThrow<IllegalArgumentException> {
            SettlementObjectReadEnd.stalledReadEndByMillis(properties - LakeCatalogs.HTTP_CONNECTION_TIMEOUT_MS)
        }
        shouldThrow<IllegalArgumentException> {
            SettlementObjectReadEnd.stalledReadEndByMillis(properties + ("http-client.type" to "apache"))
        }
        shouldThrow<IllegalArgumentException> {
            SettlementObjectReadEnd.stalledReadEndByMillis(properties + ("client.factory" to "custom.Factory"))
        }
    }

    @Test
    fun `the largest admitted inputs saturate backoff before doubling can overflow`() {
        val largest = Int.MAX_VALUE.toLong()
        val altered = SettlementObjectReadEnd.Inputs(largest, largest, 63, largest, largest)
        SettlementObjectReadEnd.stalledReadEndByMillis(altered) shouldBe
            4L * (64L * (largest + largest) + 63L * largest + 3L * largest)
    }

    @Test
    fun `the lake document names the derivation and states the total it derives`() {
        val lake = LakeTestDb.repoFile("docs/lake.md").readText().replace(Regex("\\s+"), " ")

        lake shouldContain "SettlementObjectReadEnd.stalledReadEndByMillis"
        lake shouldContain "at most ${grouped(420_400)} ms"
        lake shouldContain "about 420 seconds"
    }
}

/** `420 400` — the documents group thousands with spaces. */
internal fun grouped(value: Long): String =
    value
        .toString()
        .reversed()
        .chunked(3)
        .joinToString(" ")
        .reversed()
