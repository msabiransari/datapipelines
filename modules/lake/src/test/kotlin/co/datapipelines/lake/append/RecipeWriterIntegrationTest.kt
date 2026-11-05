package co.datapipelines.lake.append

import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LakeS3Clients
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Real conditional writes using the same Chainguard MinIO digest pinned by LakeMinio and the boot check. */
class RecipeWriterIntegrationTest {
    @Test
    fun `recipe bytes carry complete provenance and redaction before immutable PUT`() {
        val storage = LakeMinio.storage(bucket = "recipe799-${UUID.randomUUID()}")
        val publication = RecipeFixtures.publication()
        LakeS3Clients.build(storage).use { client ->
            val writer = RecipeWriter(client, storage, RecipeFixtures.document)
            val written = writer.write("${storage.root}/sales/table799", publication, Instant.now().plusSeconds(60))
            val bytes = client.getObjectAsBytes { it.bucket(storage.bucket).key(written.key) }.asByteArray()
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) shouldBe written.sha256
            val body = RecipeFixtures.mapper.readTree(bytes)
            bytes.toString(Charsets.UTF_8) shouldNotContain "resolved-secret-799"
            bytes.toString(Charsets.UTF_8) shouldNotContain "default-secret-799"
            written.exactReplayPossible shouldBe false
            body.path("resolved_parameters").path("token").asText() shouldBe "<redacted>"
            body.path("pipeline").path("body_hash").asText() shouldBe publication.pipeline.bodyHash
            body.path("pipeline").path("draft").asBoolean() shouldBe true
            body.path("pipeline").path("body_redacted").asBoolean() shouldBe true
            body.path("delivery").path("manifest").size() shouldBe 2
            body.path("delivery").path("fingerprint").asText() shouldBe publication.delivery.fingerprint
            body
                .path("templates")
                .path(0)
                .path("body_hash")
                .asText() shouldBe "c".repeat(64)
            body
                .path("parameter_sets")
                .path(0)
                .path("version")
                .asInt() shouldBe 4
            body.path("applied_definition").path("version").asInt() shouldBe 2
            body.path("applied_definition").path("body") shouldBe publication.appliedDefinitionBody
            body.path("operation_id").asText() shouldBe publication.operationId.toString()
            body.path("restore_epoch").asText() shouldBe publication.restoreEpoch.toString()
            body.path("recorded_at").asText() shouldBe publication.recordedAt.toString()
            body.path("worker").asText() shouldBe publication.worker
            publication.pipeline.resolvedParameters
                .getValue("token")
                .asText() shouldBe "resolved-secret-799"
        }
    }

    @Test
    fun `existing recipe is a bug signal and another attempt has a different immutable key`() {
        val storage = LakeMinio.storage(bucket = "recipe799-${UUID.randomUUID()}")
        val publication = RecipeFixtures.publication(redact = false)
        LakeS3Clients.build(storage).use { client ->
            val writer = RecipeWriter(client, storage, RecipeFixtures.document)
            val location = "${storage.root}/sales/table799"
            val first = writer.write(location, publication, Instant.now().plusSeconds(60))
            first.exactReplayPossible shouldBe true
            val original = client.getObjectAsBytes { it.bucket(storage.bucket).key(first.key) }.asUtf8String()
            val conflict =
                shouldThrow<DatapipelinesException> {
                    writer.write(location, publication.copy(worker = "replacement"), Instant.now().plusSeconds(60))
                }
            conflict.code shouldBe PipelineErrorCodes.Lake.APPEND_RECIPE_EXISTS
            conflict.cause shouldBe null
            client.getObjectAsBytes { it.bucket(storage.bucket).key(first.key) }.asUtf8String() shouldBe original
            val second = writer.write(location, publication.copy(operationId = UUID.randomUUID()), Instant.now().plusSeconds(60))
            second.key shouldNotBe first.key
            RecipeFixtures.mapper
                .readTree(original)
                .path("resolved_parameters")
                .path("token")
                .asText() shouldBe "resolved-secret-799"
        }
    }

    @Test
    fun `failed storage authentication exposes neither SDK cause credentials nor endpoint`() {
        val storage = LakeMinio.storage(bucket = "recipe799-${UUID.randomUUID()}", secretKey = "wrong-recipe-key", create = false)
        LakeS3Clients.build(storage).use { client ->
            val refusal =
                shouldThrow<DatapipelinesException> {
                    RecipeWriter(client, storage, RecipeFixtures.document).write(
                        "${storage.root}/sales/table799",
                        RecipeFixtures.publication(),
                        Instant.now().plusSeconds(60),
                    )
                }
            refusal.code shouldBe PipelineErrorCodes.Lake.APPEND_RECIPE_WRITE_FAILED
            refusal.cause shouldBe null
            refusal.message.orEmpty() shouldNotContain storage.secretKey
            refusal.message.orEmpty() shouldNotContain storage.endpoint
            refusal.message.orEmpty() shouldNotContain LakeMinio.secret
        }
    }
}
