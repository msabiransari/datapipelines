package co.datapipelines.lake

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI

/**
 * The boot check's S3 client, built from the same `storage.*` keys as the catalog's `S3FileIO` (§6): the
 * configured endpoint only (or AWS's endpoint for the region), the writer credentials when both are set, else the
 * SDK's default chain (instance credentials). JDK HTTP client — no Netty stack on the classpath.
 */
object LakeS3Clients {
    fun build(storage: LakeProperties.Storage): S3Client {
        val builder =
            S3Client
                .builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(storage.region.ifBlank { LakeCatalogs.DEFAULT_SIGNING_REGION }))
                .forcePathStyle(storage.pathStyle)
                .credentialsProvider(credentials(storage))
        if (storage.endpoint.isNotBlank()) builder.endpointOverride(URI.create(storage.endpoint))
        return builder.build()
    }

    private fun credentials(storage: LakeProperties.Storage): AwsCredentialsProvider =
        if (storage.hasStaticCredentials) {
            StaticCredentialsProvider.create(AwsBasicCredentials.create(storage.accessKey, storage.secretKey))
        } else {
            DefaultCredentialsProvider.builder().build()
        }
}
