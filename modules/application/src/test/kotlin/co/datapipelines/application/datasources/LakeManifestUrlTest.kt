package co.datapipelines.application.datasources

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

/**
 * [LakeManifestUrl] — the SSRF boundary of the lake-table import (089 §A): a manifest URL is
 * fetched server-side ONLY from the datasource's own endpoint/bucket. Every refusal below must
 * be able to FAIL — a suite that passes while `https://169.254.169.254/` resolves is an open
 * SSRF hole wearing a green badge.
 */
class LakeManifestUrlTest {
    @TempDir
    lateinit var tempDir: Path

    private fun lake(dialect: Map<String, Any?> = emptyMap()) =
        Datasource(
            name = "sample-lake",
            displayName = "Sample lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb:",
            properties = DatasourceProperties(dialect = dialect),
        )

    // ---------------------------------------------------------- accepted forms

    @Test
    fun `an endpoint-declared datasource accepts URLs under that endpoint, http and https`() {
        val ds = lake(mapOf("catalog.kind" to "s3", "endpoint" to "minio.internal:9000"))
        assertAll(
            {
                LakeManifestUrl.resolveFetchUrl(ds, "http://minio.internal:9000/lake/v1/manifest.json") shouldBe
                    "http://minio.internal:9000/lake/v1/manifest.json"
            },
            {
                LakeManifestUrl.resolveFetchUrl(ds, "https://minio.internal:9000/lake/v1/manifest.json") shouldBe
                    "https://minio.internal:9000/lake/v1/manifest.json"
            },
            // s3:// translates to path-style against the endpoint.
            {
                LakeManifestUrl.resolveFetchUrl(ds, "s3://lake/v1/manifest.json") shouldBe
                    "https://minio.internal:9000/lake/v1/manifest.json"
            },
        )
    }

    @Test
    fun `a plain AWS datasource accepts only AWS S3 hosts over https, both styles`() {
        val ds = lake(mapOf("catalog.kind" to "s3", "region" to "us-east-1"))
        assertAll(
            {
                LakeManifestUrl.resolveFetchUrl(ds, "https://datapipelines-co.s3.amazonaws.com/sample-data/lake/v1/manifest.json") shouldBe
                    "https://datapipelines-co.s3.amazonaws.com/sample-data/lake/v1/manifest.json"
            },
            {
                LakeManifestUrl.resolveFetchUrl(ds, "https://datapipelines-co.s3.us-east-1.amazonaws.com/lake/v1/manifest.json") shouldBe
                    "https://datapipelines-co.s3.us-east-1.amazonaws.com/lake/v1/manifest.json"
            },
            {
                LakeManifestUrl.resolveFetchUrl(ds, "https://s3.us-east-1.amazonaws.com/datapipelines-co/lake/v1/manifest.json") shouldBe
                    "https://s3.us-east-1.amazonaws.com/datapipelines-co/lake/v1/manifest.json"
            },
            // s3:// translates to virtual-hosted style, with the declared region.
            {
                LakeManifestUrl.resolveFetchUrl(ds, "s3://datapipelines-co/sample-data/lake/v1/manifest.json") shouldBe
                    "https://datapipelines-co.s3.us-east-1.amazonaws.com/sample-data/lake/v1/manifest.json"
            },
        )
    }

    @Test
    fun `a REST catalog ref is an allowed root of its own`() {
        val ds = lake(mapOf("catalog.kind" to "rest", "catalog.ref" to "https://iceberg.internal:8181/catalog"))
        LakeManifestUrl.resolveFetchUrl(ds, "https://iceberg.internal:8181/manifest.json") shouldBe
            "https://iceberg.internal:8181/manifest.json"
    }

    @Test
    fun `a file ref declares a local mirror root and only paths under it resolve`() {
        val ds = lake(mapOf("catalog.ref" to "file:///srv/lake-mirror"))
        assertAll(
            {
                LakeManifestUrl.resolveFetchUrl(ds, "file:///srv/lake-mirror/v1/manifest.json") shouldBe
                    "file:///srv/lake-mirror/v1/manifest.json"
            },
            // The root itself is under the root.
            { LakeManifestUrl.resolveFetchUrl(ds, "file:///srv/lake-mirror") shouldBe "file:///srv/lake-mirror" },
        )
    }

    @Test
    fun `a non-URL catalog ref contributes no root and leaves the plain-AWS rule intact`() {
        // The adapter's other ref shapes (an s3:// catalog identifier, an ARN, an account id) are
        // not fetch roots: the datasource stays plain AWS — a regression here would both refuse
        // the real S3 host and admit the ref's "authority" as a nonsense http root.
        val ds = lake(mapOf("catalog.kind" to "s3", "catalog.ref" to "s3://datapipelines-co/sample-data/lake", "region" to "us-east-1"))
        LakeManifestUrl.resolveFetchUrl(ds, "https://datapipelines-co.s3.amazonaws.com/lake/v1/manifest.json") shouldBe
            "https://datapipelines-co.s3.amazonaws.com/lake/v1/manifest.json"
        shouldThrow<DatapipelinesException> { LakeManifestUrl.resolveFetchUrl(ds, "https://datapipelines-co/x") }
            .code shouldBe PipelineErrorCodes.Datasource.LAKE_MANIFEST_URL_FORBIDDEN
    }

    // ---------------------------------------------------------- refusals — each one must throw

    @Test
    fun `anything outside the datasource's own roots is refused`() {
        val withEndpoint = lake(mapOf("endpoint" to "minio.internal:9000"))
        val plainAws = lake(mapOf("region" to "us-east-1"))
        val noConfig = lake()

        val cases =
            listOf(
                withEndpoint to "http://minio.internal:9001/x/manifest.json", // wrong port
                withEndpoint to "https://other-host:9000/x/manifest.json", // wrong host
                withEndpoint to "https://minio.internal.evil.test/x", // suffix lookalike
                plainAws to "http://datapipelines-co.s3.amazonaws.com/x", // http downgrade
                plainAws to "https://example.com/manifest.json", // arbitrary host
                plainAws to "https://s3.amazonaws.com.evil.test/x", // suffix lookalike
                plainAws to "https://169.254.169.254/latest/meta-data", // cloud metadata
                plainAws to "http://localhost:8080/actuator/env", // loopback
                plainAws to "http://10.0.0.4/internal", // private network
                noConfig to "https://example.com/manifest.json", // no declared roots at all
                noConfig to "file:///etc/passwd", // not a fetch scheme
                plainAws to "ftp://datapipelines-co/x",
                plainAws to "javascript:alert(1)",
                plainAws to "not a url",
                plainAws to "s3://bucket-only", // no object key
                plainAws to "s3://bucket/x y", // whitespace
                plainAws to "https://datapipelines-co.s3.amazonaws.com/x'OR'1'='1", // injection characters
            )
        cases.forEach { (ds, url) ->
            withClue(url) {
                shouldThrow<DatapipelinesException> { LakeManifestUrl.resolveFetchUrl(ds, url) }
                    .code shouldBe PipelineErrorCodes.Datasource.LAKE_MANIFEST_URL_FORBIDDEN
            }
        }
    }

    @Test
    fun `file URLs outside the declared root are refused - the root is the whole boundary`() {
        val ds = lake(mapOf("catalog.ref" to "file:///srv/lake-mirror"))
        val noRoot = lake()
        val cases =
            listOf(
                ds to "file:///srv/other/v1/manifest.json", // a different tree
                ds to "file:///srv/lake-mirror/../etc/passwd", // normalizes OUT of the root
                ds to "file:///srv/lake-mirror-echo/v1/manifest.json", // prefix lookalike
                ds to "file://remote-host/srv/lake-mirror/v1/manifest.json", // a host is not the local filesystem
                noRoot to "file:///srv/lake-mirror/v1/manifest.json", // no declared root at all
            )
        cases.forEach { (datasource, url) ->
            withClue(url) {
                shouldThrow<DatapipelinesException> { LakeManifestUrl.resolveFetchUrl(datasource, url) }
                    .code shouldBe PipelineErrorCodes.Datasource.LAKE_MANIFEST_URL_FORBIDDEN
            }
        }
    }

    @Test
    fun `the production fetcher reads a vetted file URL off the local filesystem`() {
        val manifest = Files.writeString(tempDir.resolve("manifest.json"), """{"tables": [], "schema_version": 1}""")
        val tree = LakeManifestFetcher.HTTP.fetch(manifest.toUri().toString())
        tree.get("schema_version").asInt() shouldBe 1
    }

    @Test
    fun `the production fetcher maps a missing local manifest to the unreachable code`() {
        shouldThrow<DatapipelinesException> {
            LakeManifestFetcher.HTTP.fetch(tempDir.resolve("absent.json").toUri().toString())
        }.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE
    }

    @Test
    fun `the production fetcher GETs a vetted http URL and parses the body`() {
        withServer("""{"tables": [], "schema_version": 7}""") { url ->
            LakeManifestFetcher.HTTP
                .fetch(url)
                .get("schema_version")
                .asInt() shouldBe 7
        }
    }

    @Test
    fun `the production fetcher maps a non-2xx answer to the unreachable code`() {
        withServer("""{"error": "broken"}""", status = 500) { url ->
            shouldThrow<DatapipelinesException> { LakeManifestFetcher.HTTP.fetch(url) }
                .code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE
        }
    }

    @Test
    fun `the production fetcher maps an oversized local manifest to the unreachable code`() {
        val oversized = Files.write(tempDir.resolve("big.json"), ByteArray(4 * 1024 * 1024 + 1) { ' '.code.toByte() })
        shouldThrow<DatapipelinesException> {
            LakeManifestFetcher.HTTP.fetch(oversized.toUri().toString())
        }.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE
    }

    @Test
    fun `the production fetcher maps a body that is not JSON to the unreachable code`() {
        val garbage = Files.writeString(tempDir.resolve("garbage.json"), "this is not json")
        shouldThrow<DatapipelinesException> {
            LakeManifestFetcher.HTTP.fetch(garbage.toUri().toString())
        }.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE
    }

    /** A loopback [HttpServer] answering every GET with [body] and [status]; the fetcher is under test, not the SSRF vetting. */
    private fun withServer(
        body: String,
        status: Int = 200,
        block: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        try {
            server.start()
            block("http://${server.address.hostString}:${server.address.port}/manifest.json")
        } finally {
            server.stop(0)
        }
    }
}
