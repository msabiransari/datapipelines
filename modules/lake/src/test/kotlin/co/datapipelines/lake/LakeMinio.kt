package co.datapipelines.lake

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.S3Client
import java.security.SecureRandom
import java.util.HexFormat

/**
 * ONE MinIO container for this module's test JVM — the object store `S3FileIO` and the boot check talk to.
 *
 * The root user and password are drawn at random for every JVM (#785 security: never `minioadmin`, never a
 * committed value); they live only in this process and in the container's environment, and nothing prints them.
 * [storage] hands a suite the `storage.*` binding production reads, pointed at the container's endpoint.
 */
internal object LakeMinio {
    /**
     * Chainguard's MinIO by DIGEST (DEVELOPMENT.md §9.2a) — the same pin as `datasources`'
     * `LakeConnectivityIntegrationTest` and `tests/integration-tests`' `SharedE2e`; move all three together.
     * `scripts/verify-image-pins.sh` checks it against the registry in CI.
     */
    private const val IMAGE = "cgr.dev/chainguard/minio@sha256:bd014394a80898e68c149f2311fdf8d5a2c2f3bb2c33b9327ae6d02b4b065ae1"
    private const val PORT = 9000
    private const val USER_BYTES = 8
    private const val PASSWORD_BYTES = 24

    private val random = SecureRandom()

    /** Throwaway root credentials, this JVM only. */
    private val user: String = "lake" + HexFormat.of().formatHex(ByteArray(USER_BYTES).also(random::nextBytes))
    private val password: String = HexFormat.of().formatHex(ByteArray(PASSWORD_BYTES).also(random::nextBytes))

    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(IMAGE))
            .withEnv("MINIO_ROOT_USER", user)
            .withEnv("MINIO_ROOT_PASSWORD", password)
            .withCommand("server", "/data")
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(PORT))
            .also { it.start() }
    }

    val endpoint: String get() = "http://${container.host}:${container.getMappedPort(PORT)}"

    /** The `storage.*` binding production reads, against this container; [bucket] is created when [create]. */
    fun storage(
        bucket: String,
        prefix: String = LakeProperties.DEFAULT_PREFIX,
        create: Boolean = true,
        secretKey: String = password,
    ): LakeProperties.Storage {
        val storage =
            LakeProperties.Storage(
                bucket = bucket,
                prefix = prefix,
                region = LakeCatalogs.DEFAULT_SIGNING_REGION,
                endpoint = endpoint,
                pathStyle = true,
                accessKey = user,
                secretKey = secretKey,
            )
        if (create) {
            client().use { s3 ->
                val exists = s3.listBuckets().buckets().any { it.name() == bucket }
                if (!exists) s3.createBucket { it.bucket(bucket) }
            }
        }
        return storage
    }

    /** An S3 client with the root pair (built by production's [LakeS3Clients]) — for a suite's own reads. */
    fun client(): S3Client = LakeS3Clients.build(storage(bucket = "unused", create = false))

    /** The secret a suite asserts never leaks into a message, a log line or a `toString`. */
    val secret: String get() = password
}
