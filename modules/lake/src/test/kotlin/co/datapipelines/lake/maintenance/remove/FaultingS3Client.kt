package co.datapipelines.lake.maintenance.remove

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The remover's S3 client in the tests: it wraps the REAL MinIO client (the store stays real, brief 958 V14), records every
 * call the remover makes, and lets a test replace one delete call's answer ([onDelete]) or act after a delete call
 * ([afterDelete], e.g. lose the lease between two batches). Only the four operations the remover uses are forwarded; any
 * other operation is the interface's default, which refuses.
 */
internal class FaultingS3Client(
    private val real: S3Client,
) : S3Client {
    /** Every call in order: `put`, `head`, `list`, `delete`. */
    val calls: MutableList<String> = CopyOnWriteArrayList()

    /** The keys of every delete call, in order (one entry per call). */
    val deleteCalls: MutableList<List<String>> = CopyOnWriteArrayList()

    /** Replaces the answer of delete call [index] (0-based): return a response, or throw; null = forward to the store. */
    @Volatile
    var onDelete: ((index: Int, request: DeleteObjectsRequest, real: S3Client) -> DeleteObjectsResponse?)? = null

    /** Runs after delete call [index] has answered (forwarded or replaced). */
    @Volatile
    var afterDelete: ((index: Int) -> Unit)? = null

    val listCalls: Int get() = calls.count { it == "list" }

    override fun putObject(
        request: PutObjectRequest,
        body: RequestBody,
    ): PutObjectResponse {
        calls += "put"
        return real.putObject(request, body)
    }

    override fun headObject(request: HeadObjectRequest): HeadObjectResponse {
        calls += "head"
        return real.headObject(request)
    }

    override fun listObjectsV2(request: ListObjectsV2Request): ListObjectsV2Response {
        calls += "list"
        return real.listObjectsV2(request)
    }

    override fun deleteObjects(request: DeleteObjectsRequest): DeleteObjectsResponse {
        val index = deleteCalls.size
        calls += "delete"
        deleteCalls += request.delete().objects().map { it.key() }
        val response = onDelete?.invoke(index, request, real) ?: real.deleteObjects(request)
        afterDelete?.invoke(index)
        return response
    }

    override fun serviceName(): String = real.serviceName()

    override fun close() = Unit
}
