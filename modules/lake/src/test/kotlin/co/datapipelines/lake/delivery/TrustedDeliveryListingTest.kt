package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeProperties
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.S3Object
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class TrustedDeliveryListingTest {
    private val now = Instant.parse("2026-11-04T00:00:00Z")
    private val deadline = now.plusSeconds(60)
    private val key = DeliveryKey("feed", "batch")
    private val prefix = "landing/feed/batch/"
    private val client = mockk<S3Client>()
    private val storage = LakeProperties.Storage(bucket = "bucket", landingPrefix = "/landing/")

    private fun listing(cap: Long = 100) = TrustedDeliveryListing(client, storage, cap, Clock.fixed(now, ZoneOffset.UTC))

    private fun obj(
        name: String,
        size: Long = 1,
        etag: String = "etag-$name",
    ) = S3Object
        .builder()
        .key(prefix + name)
        .size(size)
        .eTag(etag)
        .build()

    private fun response(vararg objects: S3Object) =
        ListObjectsV2Response
            .builder()
            .contents(*objects)
            .isTruncated(false)
            .build()

    private fun answer(vararg objects: S3Object) {
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } returns response(*objects)
    }

    private fun code(block: () -> Unit) = shouldThrow<DatapipelinesException>(block).code

    @Test
    fun `sender keys are validated before any storage access`() {
        answer()
        listOf("", "..", "a/b", "a\nb", "a%2fb", "Feed", "a".repeat(65)).forEach { invalid ->
            code { listing().list(DeliveryKey(invalid, "batch"), deadline) } shouldBe Lake.DELIVERY_INVALID_KEY
            code { listing().list(DeliveryKey("feed", invalid), deadline) } shouldBe Lake.DELIVERY_INVALID_KEY
        }
        DeliveryKey("a".repeat(64), "_-01").prefix("/") shouldBe "${"a".repeat(64)}/_-01/"
        verify(exactly = 0) { client.listObjectsV2(any<ListObjectsV2Request>()) }
    }

    @Test
    fun `pagination uses the exact prefix token and admission deadline`() {
        val requests = mutableListOf<ListObjectsV2Request>()
        every { client.listObjectsV2(capture(requests)) } answers {
            if (requests.size == 1) {
                ListObjectsV2Response
                    .builder()
                    .contents(obj("b.parquet", 2))
                    .isTruncated(true)
                    .nextContinuationToken("next")
                    .build()
            } else {
                response(obj("_done", 0), obj("a.parquet", 3))
            }
        }
        val manifest = listing().list(key, deadline)
        manifest.totalBytes shouldBe 5L
        manifest.files.map { it.key } shouldBe listOf(prefix + "a.parquet", prefix + "b.parquet")
        manifest.objects.size shouldBe 3
        manifest.format shouldBe DeliveryFormat.Parquet
        requests.map { it.prefix() }.distinct() shouldBe listOf(prefix)
        requests.map { it.bucket() }.distinct() shouldBe listOf("bucket")
        requests.map { it.continuationToken() } shouldBe listOf(null, "next")
        requests.forEach {
            it
                .overrideConfiguration()
                .get()
                .apiCallTimeout()
                .get() shouldBe Duration.ofSeconds(60)
        }
    }

    @Test
    fun `fingerprint is order independent and covers keys sizes and etags`() {
        val a = obj("a.csv", 2)
        val done = obj("_done", 0)
        answer(a, done)
        val initial = listing().list(key, deadline)
        answer(done, a)
        listing().list(key, deadline).fingerprint shouldBe initial.fingerprint
        listOf(obj("b.csv", 2), obj("a.csv", 3), obj("a.csv", 2, "new-etag")).forEach { changed ->
            answer(done, changed)
            listing().list(key, deadline).fingerprint shouldNotBe initial.fingerprint
        }
        initial.format shouldBe DeliveryFormat.Csv
        initial.fingerprint.length shouldBe 64
        deliveryFingerprint(listOf(DeliveryObject("a1", 2, "3"))) shouldNotBe
            deliveryFingerprint(listOf(DeliveryObject("a", 12, "3")))
    }

    @Test
    fun `only the exact final marker completes a delivery`() {
        answer(obj("a.csv"), obj("nested/_done", 0))
        code { listing().list(key, deadline) } shouldBe Lake.DELIVERY_INCOMPLETE
        answer(obj("a.csv"), obj("_done", 1))
        code { listing().list(key, deadline) } shouldBe Lake.DELIVERY_INVALID_MANIFEST
        answer(obj("_done", 0))
        code { listing().list(key, deadline) } shouldBe Lake.DELIVERY_INVALID_MANIFEST
    }

    @Test
    fun `byte cap includes every object and cannot overflow`() {
        answer(obj("a.csv", 3), obj("_done", 0))
        listing(3).list(key, deadline).totalBytes shouldBe 3L
        code { listing(2).list(key, deadline) } shouldBe Lake.DELIVERY_TOO_LARGE
        answer(obj("a.csv", Long.MAX_VALUE), obj("b.csv", 1), obj("_done", 0))
        code { listing(Long.MAX_VALUE).list(key, deadline) } shouldBe Lake.DELIVERY_TOO_LARGE
    }

    @Test
    fun `malformed listing entries cannot enter the manifest`() {
        val invalid =
            listOf(
                obj("a.csv", -1),
                obj("a.csv", 1, ""),
                S3Object
                    .builder()
                    .key("landing/feed/other/a.csv")
                    .size(1L)
                    .eTag("x")
                    .build(),
                S3Object
                    .builder()
                    .key(prefix + "a.csv")
                    .eTag("x")
                    .build(),
                obj("folder/", 1),
            )
        invalid.forEach { entry ->
            answer(entry, obj("_done", 0))
            code { listing().list(key, deadline) } shouldBe Lake.DELIVERY_INVALID_MANIFEST
        }
        answer(obj("a.csv"), obj("a.csv"), obj("_done", 0))
        code { listing().list(key, deadline) } shouldBe Lake.DELIVERY_INVALID_MANIFEST
    }

    @Test
    fun `unknown or mixed formats are refused and zero sized directory markers are harmless`() {
        answer(obj("a.csv"), obj("b.parquet"), obj("_done", 0))
        code { listing().list(key, deadline) } shouldBe Lake.DELIVERY_UNSUPPORTED_FORMAT
        answer(obj("a.json"), obj("_done", 0))
        code { listing().list(key, deadline) } shouldBe Lake.DELIVERY_UNSUPPORTED_FORMAT
        answer(obj("a.CSV"), obj("folder/", 0), obj("_done", 0))
        listing().list(key, deadline).files.size shouldBe 1
    }

    @Test
    fun `expired deadline makes no call and SDK failures disclose no cause or secret`() {
        code { listing().list(key, now) } shouldBe Lake.DELIVERY_LISTING_FAILED
        verify(exactly = 0) { client.listObjectsV2(any<ListObjectsV2Request>()) }
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } throws SdkClientException.builder().message("DO_NOT_LEAK").build()
        val failure = shouldThrow<DatapipelinesException> { listing().list(key, deadline) }
        failure.code shouldBe Lake.DELIVERY_LISTING_FAILED
        failure.message.orEmpty() shouldNotContain "DO_NOT_LEAK"
        failure.cause shouldBe null
        failure.details["retryable"] shouldBe true
    }

    @Test
    fun `truncated listings require forward progress`() {
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } returns
            ListObjectsV2Response
                .builder()
                .isTruncated(true)
                .nextContinuationToken("same")
                .build()
        code { listing().list(key, deadline) } shouldBe Lake.DELIVERY_INVALID_MANIFEST
        verify(exactly = 2) { client.listObjectsV2(any<ListObjectsV2Request>()) }
    }
}
