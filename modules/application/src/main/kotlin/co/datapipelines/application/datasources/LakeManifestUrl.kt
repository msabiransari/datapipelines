package co.datapipelines.application.datasources

import co.datapipelines.datasources.Datasource
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The SSRF boundary of `POST /api/v1/datasources/{name}/tables/import` (round 089 §A): a
 * manifest URL is fetched **server-side, and only from the datasource's own bucket/endpoint** —
 * never an arbitrary URL.
 *
 * ## The allowed roots, derived from the datasource's typed `properties.dialect.*` (087 §12.1)
 *
 * - `dialect.endpoint` declared (an S3-compatible endpoint — MinIO, on-prem): the URL's
 *   authority must EQUAL it (host case-insensitive, port exact). Both `http` and `https` are
 *   accepted, because the endpoint field carries no scheme and plain-HTTP MinIO is the
 *   ordinary case (the adapter's own `USE_SSL false` precedent).
 * - `dialect.catalog.ref` holding an http(s) URL (an Iceberg REST catalog): the URL's
 *   authority must equal the ref's.
 * - Neither declared (plain AWS S3 through the credential chain): `https` only, and the host
 *   must be an AWS S3 host — `s3.amazonaws.com`, `s3.<region>.amazonaws.com`, or
 *   `<bucket>.s3[.<region>].amazonaws.com` (virtual-hosted and path style). `http` is refused:
 *   a downgrade has no legitimate reason here.
 *
 * The `s3://` form of a manifest URL is accepted by TRANSLATION, never fetched as-is: with an
 * endpoint it becomes path-style `https://<endpoint>/<bucket>/<key>`, without one it becomes
 * virtual-hosted `https://<bucket>.s3[.<region>].amazonaws.com/<key>` (the region from
 * `dialect.region` when declared) — so the fetched URL always lands inside one of the allowed
 * roots above by construction.
 *
 * Every refusal is the catalogued `datasource.validation.lake_manifest_url_forbidden`, and the
 * same total character refusal the location grammar applies ([LakeTableValidator.containsRefusedChar])
 * runs FIRST: the resolved URL is logged and may be echoed, and a value carrying those
 * characters is an attack string, not an address.
 */
object LakeManifestUrl {
    /**
     * AWS S3 hosts, both styles: `s3.amazonaws.com`, `s3.us-east-1.amazonaws.com`,
     * `bucket.s3.amazonaws.com`, `bucket.s3.us-east-1.amazonaws.com` (and the `s3-<region>`
     * spelling). The trailing label is always `amazonaws.com` — an attacker-registered
     * `s3.amazonaws.com.evil.test` does not match.
     */
    private val AWS_S3_HOST = Regex("([a-z0-9][a-z0-9.-]*\\.)?s3([.-][a-z0-9-]+)?\\.amazonaws\\.com")

    /**
     * Resolves [url] against [datasource]'s own roots and returns the URL to fetch.
     *
     * @throws DatapipelinesException `datasource.validation.lake_manifest_url_forbidden` for
     *   anything outside those roots — this is the SSRF refusal, and it is total.
     */
    fun resolveFetchUrl(
        datasource: Datasource,
        url: String,
    ): String {
        val trimmed = url.trim()
        if (LakeTableValidator.containsRefusedChar(trimmed)) {
            throw forbidden("a manifest URL may not contain quotes, backslashes, whitespace or control characters")
        }
        val roots = AllowedRoots.of(datasource)
        return if (trimmed.startsWith("s3://")) {
            translateS3Url(roots, trimmed)
        } else {
            vetHttpUrl(roots, trimmed)
        }
    }

    /** The `s3://bucket/key` form, translated into an allowed root — never fetched as-is. */
    private fun translateS3Url(
        roots: AllowedRoots,
        url: String,
    ): String {
        val remainder = url.removePrefix("s3://")
        val bucket = remainder.substringBefore('/')
        val key = remainder.substringAfter('/', "")
        if (bucket.isEmpty() || key.isEmpty()) {
            throw forbidden("an s3:// manifest URL must name a bucket AND an object key")
        }
        return when {
            roots.endpoint != null -> "https://${roots.endpoint}/$bucket/$key"
            roots.region != null -> "https://$bucket.s3.${roots.region}.amazonaws.com/$key"
            else -> "https://$bucket.s3.amazonaws.com/$key"
        }
    }

    /** An http(s) URL, admitted only when it sits inside one of the datasource's own roots. */
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued 4xx
    private fun vetHttpUrl(
        roots: AllowedRoots,
        url: String,
    ): String {
        val uri = parseUri(url)
        if (uri.scheme != "https" && uri.scheme != "http") {
            throw forbidden("a manifest URL is https:// (or http:// against the datasource's own endpoint), nothing else")
        }
        val authority = uri.authority ?: throw forbidden("the URL has no host")
        if (roots.endpoint != null && authority.equals(roots.endpoint, ignoreCase = true)) return url
        if (roots.refAuthority != null && authority.equals(roots.refAuthority, ignoreCase = true)) return url
        if (roots.isPlainAws && uri.scheme == "https" && AWS_S3_HOST.matches(uri.host.orEmpty().lowercase())) {
            // Plain AWS S3: https only, and only a real S3 host — the whole internet is not
            // fetchable through this endpoint, and neither is anything on the local network.
            return url
        }
        throw forbidden(
            "'${url.take(MAX_ECHOED_VALUE_CHARS)}' is not under the datasource's own endpoint/bucket — " +
                "a manifest is fetched from the datasource's declared dialect.endpoint / catalog.ref / AWS S3 only",
        )
    }

    private fun parseUri(url: String): URI =
        try {
            URI(url)
        } catch (e: IllegalArgumentException) {
            throw forbidden("'${url.take(MAX_ECHOED_VALUE_CHARS)}' is not a parseable URL", e)
        }

    private fun forbidden(
        why: String,
        cause: Throwable? = null,
    ): DatapipelinesException =
        DatapipelinesException(
            PipelineErrorCodes.Datasource.LAKE_MANIFEST_URL_FORBIDDEN,
            "Refused lake-table import: $why.",
            mapOf("field" to "manifest_url"),
            cause,
        )

    private const val MAX_ECHOED_VALUE_CHARS = 64

    /** The roots a datasource's own typed `properties.dialect.*` (087 §12.1) allow a fetch from. */
    private class AllowedRoots(
        val endpoint: String?,
        val refAuthority: String?,
        val region: String?,
    ) {
        /** No endpoint and no catalog ref — plain AWS S3 through the credential chain. */
        val isPlainAws: Boolean get() = endpoint == null && refAuthority == null

        companion object {
            fun of(datasource: Datasource): AllowedRoots {
                val dialect = datasource.properties.dialect
                return AllowedRoots(
                    endpoint = dialect["endpoint"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    refAuthority =
                        dialect["catalog.ref"]
                            ?.toString()
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { ref -> runCatching { URI(ref).authority }.getOrNull() },
                    region = dialect["region"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }
}

/**
 * The fetch half of the import-by-URL flow, as a port: the service depends on this interface
 * (one method, so tests substitute a fake that never touches a socket), and production wires
 * [HTTP]. The URL it receives has ALWAYS crossed [LakeManifestUrl.resolveFetchUrl] first —
 * resolving and fetching are separate methods precisely so no caller can fetch an unvetted URL
 * by accident.
 */
fun interface LakeManifestFetcher {
    /** GETs [url] and parses the body as JSON. Failures arrive as [DatapipelinesException]. */
    fun fetch(url: String): JsonNode

    companion object {
        /**
         * The production fetcher: bounded timeouts, a bounded body, 2xx required, no redirects
         * followed (a redirect could lead off the allowed root — NEVER_FOLLOW is the SSRF
         * boundary's second line).
         *
         * The RuntimeException catch is deliberate (the DS-SEC-6 precedent in
         * `DefaultDatasourceRegistry.probe`): java.net.http reports connect/read failures as
         * both [java.io.IOException] and RuntimeExceptions, and catching only the first would
         * let the second escape a fetcher whose contract is failure-as-data.
         */
        @Suppress("TooGenericExceptionCaught")
        val HTTP =
            LakeManifestFetcher { url ->
                val client =
                    HttpClient
                        .newBuilder()
                        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()
                val request =
                    HttpRequest
                        .newBuilder(URI(url))
                        .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                        .GET()
                        .build()
                val body =
                    try {
                        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                        if (response.statusCode() !in SUCCESS_RANGE) {
                            throw unreachable("the manifest endpoint answered HTTP ${response.statusCode()}")
                        }
                        if (response.body().size > MAX_MANIFEST_BYTES) {
                            throw unreachable("the manifest is larger than $MAX_MANIFEST_BYTES bytes")
                        }
                        response.body()
                    } catch (e: DatapipelinesException) {
                        throw e
                    } catch (e: IOException) {
                        throw unreachable("the manifest could not be fetched", e)
                    } catch (e: RuntimeException) {
                        throw unreachable("the manifest could not be fetched", e)
                    }
                try {
                    MAPPER.readTree(body)
                } catch (e: IOException) {
                    throw unreachable("the fetched manifest is not valid JSON", e)
                }
            }

        private val MAPPER = JsonMapper.builder().build()

        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val REQUEST_TIMEOUT_SECONDS = 20L
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        private val SUCCESS_RANGE = 200..299

        /**
         * A fetch failure is the catalogued `pipeline.execution.datasource_unreachable` (502) —
         * the same code the introspection surfaces reuse for "the datasource's own storage could
         * not be reached", which is exactly what a failed fetch from the datasource's bucket is.
         * The HTTP layer's message stays off the wire; the reason is a static string.
         */
        private fun unreachable(
            why: String,
            cause: Throwable? = null,
        ): DatapipelinesException =
            DatapipelinesException(
                PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
                "The lake-table import manifest could not be read: $why.",
                mapOf("field" to "manifest_url"),
                cause,
            )
    }
}
