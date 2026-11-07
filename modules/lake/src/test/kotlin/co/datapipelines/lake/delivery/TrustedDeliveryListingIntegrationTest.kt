package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LakeS3Clients
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Instant
import java.util.UUID

/** Real S3 validators: the bytes, marker and overwrite come from MinIO through the production client. */
class TrustedDeliveryListingIntegrationTest {
    @Test
    fun `done completes the folder and overwrite changes the fingerprint without changing its identity`() {
        val storage = LakeMinio.storage(bucket = "lake799-list-${UUID.randomUUID()}")
        val key = DeliveryKey("feed", "batch")
        val prefix = key.prefix(storage.landingPrefix)
        LakeS3Clients.build(storage).use { client ->
            val listing = TrustedDeliveryListing(client, storage, 100)
            val dataKey = prefix + "rows.csv"

            fun put(
                keyName: String,
                body: String,
            ) {
                client.putObject(
                    PutObjectRequest
                        .builder()
                        .bucket(storage.bucket)
                        .key(keyName)
                        .build(),
                    RequestBody.fromString(body),
                )
            }
            put(dataKey, "x\n1\n")
            shouldThrow<DatapipelinesException> { listing.list(key, deadline()) }.code shouldBe Lake.DELIVERY_INCOMPLETE
            put(prefix + "_done", "")
            val before = listing.list(key, deadline())
            before.totalBytes shouldBe 4L
            before.files.single().key shouldBe dataKey
            before.format shouldBe DeliveryFormat.Csv
            shouldThrow<DatapipelinesException> {
                TrustedDeliveryListing(client, storage, 3).list(key, deadline())
            }.code shouldBe Lake.DELIVERY_TOO_LARGE
            put(dataKey, "x\n2\n")
            val after = listing.list(key, deadline())
            after.key shouldBe before.key
            after.totalBytes shouldBe 4L
            after.files.single().etag shouldNotBe before.files.single().etag
            after.fingerprint shouldNotBe before.fingerprint
            client.listObjectsV2 { it.bucket(storage.bucket).prefix(prefix) }.contents().size shouldBe 2
        }
    }

    @Test
    fun `real credential refusal exposes neither credentials nor SDK cause`() {
        val storage = LakeMinio.storage(bucket = "lake799-denied-${UUID.randomUUID()}", secretKey = "invalid-writer-secret")
        LakeS3Clients.build(storage).use { client ->
            val failure =
                shouldThrow<DatapipelinesException> {
                    TrustedDeliveryListing(client, storage, 100).list(DeliveryKey("feed", "batch"), deadline())
                }
            failure.code shouldBe Lake.DELIVERY_LISTING_FAILED
            failure.cause shouldBe null
            failure.message.orEmpty() shouldNotContain "invalid-writer-secret"
            failure.message.orEmpty() shouldNotContain LakeMinio.secret
        }
    }

    private fun deadline(): Instant = Instant.now().plusSeconds(30)
}
