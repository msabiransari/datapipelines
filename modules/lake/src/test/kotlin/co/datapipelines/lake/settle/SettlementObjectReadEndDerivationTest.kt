package co.datapipelines.lake.settle

import co.datapipelines.lake.CatalogDatabase
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * docs/lake.md §8's stated end of the settlement tick's metadata read on a store that accepts and never answers (#866),
 * recomputed term by term from [SettlementObjectReadEnd]'s inputs, so neither a moved constant nor an edited document
 * can leave the other behind. The observed library behavior behind the terms is pinned by
 * `SettlementObjectReadEndCompatTest`.
 */
class SettlementObjectReadEndDerivationTest {
    private val properties =
        LakeCatalogs(
            LakeProperties(enabled = true, storage = LakeProperties.Storage(bucket = "lake", region = "us-east-1")),
            CatalogDatabase("jdbc:postgresql://localhost/none", "none", "none"),
        ).catalogProperties()

    @Test
    fun `a request ends within six attempts' connects and header waits plus the backoff caps`() {
        SettlementObjectReadEnd.requestEndMillis(SHIPPED_CONNECT_MILLIS, SHIPPED_SOCKET_MILLIS, SHIPPED_SDK_RETRIES) shouldBe
            75_100
        SettlementObjectReadEnd.requestEndMillis(SHIPPED_CONNECT_MILLIS, SHIPPED_SOCKET_MILLIS, 0) shouldBe
            SHIPPED_CONNECT_MILLIS + SHIPPED_SOCKET_MILLIS
    }

    @Test
    fun `the whole read is four requests and each answered stream's blocked read and closure`() {
        SettlementObjectReadEnd.stalledReadEndByMillis(SHIPPED_CONNECT_MILLIS, SHIPPED_SOCKET_MILLIS, SHIPPED_SDK_RETRIES) shouldBe
            420_400
        SettlementObjectReadEnd.stalledReadEndByMillis(SHIPPED_CONNECT_MILLIS, SHIPPED_SOCKET_MILLIS, 0) shouldBe
            4 * (SHIPPED_CONNECT_MILLIS + SHIPPED_SOCKET_MILLIS + 3 * SHIPPED_SOCKET_MILLIS)
    }

    @Test
    fun `the effective catalog properties produce the same total`() {
        SettlementObjectReadEnd.stalledReadEndByMillis(properties) shouldBe 420_400
    }

    @Test
    fun `a retry count the properties carry is the one derived from`() {
        SettlementObjectReadEnd.stalledReadEndByMillis(properties + ("s3.retry.num-retries" to "1")) shouldBe
            4 * (2 * (SHIPPED_CONNECT_MILLIS + SHIPPED_SOCKET_MILLIS) + 100 + 3 * SHIPPED_SOCKET_MILLIS)
    }

    @Test
    fun `inputs the model does not admit are refused`() {
        shouldThrow<IllegalArgumentException> {
            SettlementObjectReadEnd.stalledReadEndByMillis(0, SHIPPED_SOCKET_MILLIS, SHIPPED_SDK_RETRIES)
        }
        shouldThrow<IllegalArgumentException> {
            SettlementObjectReadEnd.stalledReadEndByMillis(SHIPPED_CONNECT_MILLIS, 0, SHIPPED_SDK_RETRIES)
        }
        shouldThrow<IllegalArgumentException> {
            SettlementObjectReadEnd.stalledReadEndByMillis(SHIPPED_CONNECT_MILLIS, SHIPPED_SOCKET_MILLIS, -1)
        }
        shouldThrow<IllegalArgumentException> {
            SettlementObjectReadEnd.stalledReadEndByMillis(SHIPPED_CONNECT_MILLIS, SHIPPED_SOCKET_MILLIS, Int.MAX_VALUE)
        }
        shouldThrow<IllegalArgumentException> {
            SettlementObjectReadEnd.stalledReadEndByMillis(properties + ("s3.retry.num-retries" to "many"))
        }
        shouldThrow<IllegalArgumentException> {
            SettlementObjectReadEnd.stalledReadEndByMillis(properties - LakeCatalogs.HTTP_CONNECTION_TIMEOUT_MS)
        }
    }

    @Test
    fun `a bound past the clock overflows instead of wrapping`() {
        shouldThrow<ArithmeticException> {
            SettlementObjectReadEnd.stalledReadEndByMillis(Long.MAX_VALUE, Long.MAX_VALUE, SHIPPED_SDK_RETRIES)
        }
    }

    @Test
    fun `the lake document names the derivation and states the total it derives`() {
        val lake = LakeTestDb.repoFile("docs/lake.md").readText().replace(Regex("\\s+"), " ")

        lake shouldContain "SettlementObjectReadEnd.stalledReadEndByMillis"
        lake shouldContain "at most ${grouped(420_400)} ms"
        lake shouldContain "about 420 seconds"
    }

    /** The shipped numbers, spelled here so the tests read as the derivation's arithmetic. */
    private companion object {
        const val SHIPPED_CONNECT_MILLIS = LakeCatalogs.STORE_CONNECTION_TIMEOUT_MILLIS
        const val SHIPPED_SOCKET_MILLIS = LakeCatalogs.STORE_SOCKET_TIMEOUT_MILLIS
        const val SHIPPED_SDK_RETRIES = 5
    }
}

/** `420 400` — the documents group thousands with spaces (house style), as this test tree's expectations do. */
internal fun grouped(value: Long): String =
    value
        .toString()
        .reversed()
        .chunked(3)
        .joinToString(" ")
        .reversed()
