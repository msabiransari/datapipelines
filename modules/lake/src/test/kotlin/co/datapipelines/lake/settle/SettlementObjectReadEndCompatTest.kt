package co.datapipelines.lake.settle

import co.datapipelines.lake.CatalogDatabase
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.iceberg.aws.s3.S3FileIOProperties
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.retry.RetryMode
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy
import software.amazon.awssdk.core.retry.backoff.FullJitterBackoffStrategy
import software.amazon.awssdk.http.Abortable
import software.amazon.awssdk.services.s3.S3Client
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pinned-library premises `SettlementObjectReadEnd` stands on, observed against the jars this build ships with:
 * the Iceberg and SDK versions, the SDK retry defaults DP does not override, the LEGACY backoff caps, and the
 * observed reopen behavior of a read blocked on a scripted store client. Any of these moving turns this red — and
 * docs/lake.md §8's stated total wrong with it, until the derivation is reviewed.
 */
class SettlementObjectReadEndCompatTest {
    private val properties =
        LakeCatalogs(
            LakeProperties(enabled = true, storage = LakeProperties.Storage(bucket = "lake", region = "us-east-1")),
            CatalogDatabase("jdbc:postgresql://localhost/none", "none", "none"),
        ).catalogProperties()

    @Test
    fun `the iceberg pin is the version whose stream policy the derivation cites`() {
        LakeTestDb.repoFile("gradle/libs.versions.toml").readText() shouldContain """iceberg = "1.12.0""""
    }

    @Test
    fun `the sdk core on the classpath is the version whose retry defaults the derivation cites`() {
        val pom =
            requireNotNull(S3Client::class.java.getResourceAsStream(POM_PROPERTIES)) {
                "sdk-core's pom.properties is not on the classpath"
            }
        pom.readBytes().decodeToString() shouldContain "version=2.46.7"
    }

    @Test
    fun `the sdk retries DP does not configure default to five, the derivation's six attempts`() {
        S3FileIOProperties.S3_RETRY_NUM_RETRIES shouldBe "s3.retry.num-retries"
        S3FileIOProperties.S3_RETRY_NUM_RETRIES_DEFAULT shouldBe 5
        listOf(
            S3FileIOProperties.S3_RETRY_NUM_RETRIES,
            S3FileIOProperties.S3_RETRY_MIN_WAIT_MS,
            S3FileIOProperties.S3_RETRY_MAX_WAIT_MS,
        ).forEach { key -> properties.containsKey(key) shouldBe false }
    }

    /**
     * The SDK deprecates its LEGACY strategy classes, and Iceberg's retry builder pins exactly that mode
     * (`RetryPolicy.builder(RetryMode.LEGACY)`): the deprecated API is the pinned premise, not a choice.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `the live LEGACY backoff caps are the derivation's base and ceiling`() {
        val strategy = BackoffStrategy.defaultStrategy(RetryMode.LEGACY)
        (strategy is FullJitterBackoffStrategy) shouldBe true
        val builder = (strategy as FullJitterBackoffStrategy).toBuilder()
        builder.baseDelay() shouldBe Duration.ofMillis(SettlementObjectReadEnd.SDK_BACKOFF_BASE_MILLIS)
        builder.maxBackoffTime() shouldBe Duration.ofMillis(SettlementObjectReadEnd.SDK_BACKOFF_MAX_MILLIS)
    }

    @Test
    fun `a blocked read is four GetObject calls, each answered stream probed before close`() {
        val client = ScriptedStoreClient()
        val stream = client.newInputStream("s3://lake/metadata/00000.metadata.json")

        shouldThrow<SocketTimeoutException> { stream.read(ByteArray(16), 0, 16) }
        runCatching { stream.close() }
        client.objects.get() shouldBe SettlementObjectReadEnd.STREAM_REOPENS + 1
        client.socketReads.get() shouldBe 2 * (SettlementObjectReadEnd.STREAM_REOPENS + 1)
    }

    /**
     * An S3Client that hands back a stream whose socket never answers, counting every request and every socket read
     * the read performs against it. Any other call is refused loudly: this client must only ever be asked for the
     * metadata object.
     */
    private class ScriptedStoreClient {
        val objects = AtomicInteger()
        val socketReads = AtomicInteger()

        private val proxy =
            Proxy.newProxyInstance(
                S3Client::class.java.classLoader,
                arrayOf(S3Client::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getObject", "executeGetObject" -> {
                        objects.incrementAndGet()
                        BlockedStream(socketReads)
                    }

                    "toString" -> {
                        "scripted-store-client"
                    }

                    else -> {
                        error("the scripted client must only getObject, not ${method.name}")
                    }
                }
            } as S3Client

        /** A `S3InputStream` on the scripted client, built the way S3FileIO builds it (the class is package-private). */
        fun newInputStream(location: String): InputStream {
            val streamClass = Class.forName("org.apache.iceberg.aws.s3.S3InputStream")
            val uriClass = Class.forName("org.apache.iceberg.aws.s3.S3URI")
            val uri =
                uriClass
                    .getDeclaredConstructor(String::class.java)
                    .apply { isAccessible = true }
                    .newInstance(location)
            return streamClass
                .getDeclaredConstructor(S3Client::class.java, uriClass)
                .apply { isAccessible = true }
                .newInstance(proxy, uri) as InputStream
        }
    }

    /** A stream whose reads block until the socket timeout: `read` always throws, `abort` never gets reached. */
    private class BlockedStream(
        private val socketReads: AtomicInteger,
    ) : InputStream(),
        Abortable {
        override fun read(): Int {
            socketReads.incrementAndGet()
            throw SocketTimeoutException("the store accepted the request and never answered")
        }

        override fun abort() {
            error("abort must not be reached while the stream's socket never answers")
        }
    }

    private companion object {
        const val POM_PROPERTIES = "/META-INF/maven/software.amazon.awssdk/sdk-core/pom.properties"
    }
}
