package co.datapipelines.lake.append

import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.delivery.DeliveryKey
import co.datapipelines.persistence.FailureShape
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.typesystem.DatapipelinesException
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Immutable recipe identity and the checksum the append must place in its snapshot summary. */
data class WrittenRecipe(
    val key: String,
    val sha256: String,
    val exactReplayPossible: Boolean,
)

/** Conditional PUT through the configured writer client. Never overwrites, retries a PUT, or deletes an object. */
class RecipeWriter(
    private val client: S3Client,
    private val storage: LakeProperties.Storage,
    private val document: RecipeDocument,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mapper = PipelineJson.objectMapper()

    fun write(
        tableLocation: String,
        publication: RecipePublication,
        deadline: Instant,
    ): WrittenRecipe {
        val key = recipeKey(tableLocation, storage.bucket, publication.delivery.key, publication.operationId)
        val safe = document.build(publication)
        val bytes = mapper.writeValueAsBytes(safe)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val remaining = Duration.between(clock.instant(), deadline)
        if (remaining.isNegative || remaining.isZero) throw writeFailed()
        try {
            client.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(storage.bucket)
                    .key(key)
                    .contentType("application/json")
                    .ifNoneMatch("*")
                    .overrideConfiguration { config ->
                        // A lost response may have created the object. Leave it; a new attempt has a new key.
                        config.apiCallTimeout(remaining).apiCallAttemptTimeout(remaining).addPlugin { sdk ->
                            sdk.overrideConfiguration { overrides ->
                                overrides.retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                            }
                        }
                    }.build(),
                RequestBody.fromBytes(bytes),
            )
        } catch (failure: S3Exception) {
            if (failure.statusCode() == PRECONDITION_FAILED) {
                throw DatapipelinesException(
                    Lake.APPEND_RECIPE_EXISTS,
                    "The immutable recipe key already exists; publication was not started.",
                )
            }
            refuse(failure)
        } catch (failure: SdkException) {
            refuse(failure)
        }
        return WrittenRecipe(key, hash, safe.path("exact_replay_possible").asBoolean())
    }

    private fun refuse(failure: SdkException): Nothing {
        log.warn("event=lake.append.recipe_write_failed cause={} sql_state={}", FailureShape.cause(failure), FailureShape.sqlState(failure))
        // Never attach the endpoint, credentials, request, SQL or SDK cause chain.
        throw writeFailed()
    }

    private fun writeFailed(): DatapipelinesException =
        DatapipelinesException(
            Lake.APPEND_RECIPE_WRITE_FAILED,
            "Could not write the immutable recipe before the publication deadline; publication was not started.",
        )

    companion object {
        private const val PRECONDITION_FAILED = 412
        private val log = LoggerFactory.getLogger(RecipeWriter::class.java)

        /** The table location comes from its applied Iceberg metadata, never from sender input. */
        fun recipeKey(
            tableLocation: String,
            bucket: String,
            delivery: DeliveryKey,
            operationId: UUID,
        ): String {
            val bucketRoot = "s3://$bucket/"
            require(tableLocation.startsWith(bucketRoot)) { "A managed recipe must use its configured bucket." }
            val tableKey = tableLocation.removePrefix(bucketRoot).trimEnd('/')
            require(tableKey.isNotBlank()) { "A recipe needs a managed table location." }
            return "$tableKey/dp/recipes/${delivery.feed}/${delivery.id}/$operationId.json"
        }
    }
}
