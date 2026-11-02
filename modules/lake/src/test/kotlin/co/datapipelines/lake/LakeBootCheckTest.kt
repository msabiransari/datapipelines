package co.datapipelines.lake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * What the boot check SENDS (#785 outbound rule): exactly a HEAD on the bucket, a HEAD on the prefix and two PUTs
 * of the one marker key with `If-None-Match: *` — recorded by an [S3Client] whose every other operation throws
 * (the SDK interface's defaults), so any extra call fails the suite. And how it reads the answers: a store that
 * ignores the condition, or fails the first PUT, refuses boot. The catalog checks read the migrated database.
 */
class LakeBootCheckTest {
    private val storage = LakeProperties.Storage(bucket = "dp-lake", prefix = "/lake/", region = "eu-west-1")

    @Test
    fun `a compliant store sees two HEADs and two conditional PUTs of the marker, nothing else`() {
        val s3 = RecordingS3(putAnswers = listOf(OK, PRECONDITION_FAILED))
        LakeBootCheck(LakeTestDb.jdbc, s3, storage).verify()
        s3.calls shouldContainExactly
            listOf(
                "HEAD bucket dp-lake",
                "HEAD object dp-lake/lake/",
                "PUT dp-lake/lake/dp/conditional-put-probe If-None-Match=*",
                "PUT dp-lake/lake/dp/conditional-put-probe If-None-Match=*",
            )
    }

    @Test
    fun `an empty prefix skips the prefix HEAD and probes at the bucket root`() {
        val root = LakeProperties.Storage(bucket = "dp-lake", prefix = "", region = "eu-west-1")
        val s3 = RecordingS3(putAnswers = listOf(PRECONDITION_FAILED, PRECONDITION_FAILED))
        LakeBootCheck(LakeTestDb.jdbc, s3, root).verify()
        s3.calls shouldContainExactly
            listOf(
                "HEAD bucket dp-lake",
                "PUT dp-lake/dp/conditional-put-probe If-None-Match=*",
                "PUT dp-lake/dp/conditional-put-probe If-None-Match=*",
            )
    }

    @Test
    fun `a store that ignores If-None-Match, or fails the first PUT, refuses boot`() {
        shouldThrow<IllegalStateException> {
            LakeBootCheck(LakeTestDb.jdbc, RecordingS3(putAnswers = listOf(OK, OK)), storage).verify()
        }.message.orEmpty() shouldStartWith "lake.boot.conditional_put"
        shouldThrow<IllegalStateException> {
            LakeBootCheck(LakeTestDb.jdbc, RecordingS3(putAnswers = listOf(SERVER_ERROR, PRECONDITION_FAILED)), storage).verify()
        }.message.orEmpty() shouldStartWith "lake.boot.conditional_put"
    }

    @Test
    fun `a forbidden prefix HEAD refuses boot, a 404 one passes`() {
        shouldThrow<IllegalStateException> {
            LakeBootCheck(LakeTestDb.jdbc, RecordingS3(prefixAnswer = FORBIDDEN, putAnswers = listOf(OK, PRECONDITION_FAILED)), storage)
                .verify()
        }.message.orEmpty() shouldStartWith "lake.boot.storage_unreachable"
        LakeBootCheck(LakeTestDb.jdbc, RecordingS3(prefixAnswer = NOT_FOUND, putAnswers = listOf(OK, PRECONDITION_FAILED)), storage)
            .verify()
    }

    /** Answers with the given HTTP statuses (200 = success, else an [S3Exception] of that status) and records. */
    private class RecordingS3(
        private val prefixAnswer: Int = OK,
        putAnswers: List<Int>,
    ) : S3Client {
        val calls = mutableListOf<String>()
        private val puts = ArrayDeque(putAnswers)

        override fun headBucket(request: HeadBucketRequest): HeadBucketResponse {
            calls += "HEAD bucket ${request.bucket()}"
            return HeadBucketResponse.builder().build()
        }

        override fun headObject(request: HeadObjectRequest): HeadObjectResponse {
            calls += "HEAD object ${request.bucket()}/${request.key()}"
            answer(prefixAnswer)
            return HeadObjectResponse.builder().build()
        }

        override fun putObject(
            request: PutObjectRequest,
            body: RequestBody,
        ): PutObjectResponse {
            calls += "PUT ${request.bucket()}/${request.key()} If-None-Match=${request.ifNoneMatch()}"
            answer(puts.removeFirst())
            return PutObjectResponse.builder().build()
        }

        override fun serviceName(): String = "s3"

        override fun close() = Unit

        private fun answer(status: Int) {
            if (status != OK) throw S3Exception.builder().statusCode(status).message("stub $status").build()
        }
    }

    private companion object {
        const val OK = 200
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val PRECONDITION_FAILED = 412
        const val SERVER_ERROR = 500
    }
}
