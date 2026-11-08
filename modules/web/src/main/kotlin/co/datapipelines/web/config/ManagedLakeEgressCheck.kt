package co.datapipelines.web.config

import co.datapipelines.datasources.LakeHttpBounds
import co.datapipelines.lake.LakeBootCheck
import co.datapipelines.lake.LakeProperties
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * #845 (owner ruling 2026-11-06 option B, decision D4) — the managed reads' egress proxy, verified once at boot by
 * [ManagedLakeDatasourceRegistrar.register] before it writes the managed row (the `datasource_name_taken` precedent:
 * a web-side refusal with a `lake.boot.*` id). Never on the request path.
 *
 * With `datapipelines.lake.reads.egress-proxy` empty there is nothing to verify: the boot logs
 * `lake.egress_proxy_absent` (WARN) and continues, because a compose-less install ships without a proxy. With it set:
 *
 * 1. **The cap's self-declaration.** `GET http://<proxy>/dp-egress` with the `x-dp-egress-check` header, sent to the
 *    proxy itself (no proxying), must answer `200` with the body `max-age=<reads.egress-max-age-seconds>`. The shipped
 *    proxy config renders that body from the same value the app is given, so a proxy configured with another max-age,
 *    or a proxy that is not the shipped one, refuses the boot. The route is header-gated so a path-style request for
 *    a bucket literally named `dp-egress` is still forwarded to the store.
 * 2. **The store through the proxy.** `HEAD` of the bucket, addressed the way DuckDB addresses it —
 *    [EgressStoreTarget]'s one derivation (probe A, lane 862: a blank endpoint resolves to the REGIONAL
 *    `s3.<region>.amazonaws.com`, default region us-east-1; the endpoint's scheme decides TLS; path or virtual-host
 *    style) — sent through the proxy: an `https` store is a `CONNECT` tunnel, a plain-http one an absolute-form
 *    request.
 *    Unsigned on purpose — nothing but the HEAD leaves, and any answer FROM the store (`403` included) proves the
 *    path. Refused when the exchange fails (connect, tunnel, timeout) or when the proxy answers a gateway failure
 *    (502–504) that carries no `x-amz-request-id` (the proxy's own reply, not the store's).
 *
 * Each exchange is bounded: [CONNECT_TIMEOUT_SECONDS] to connect, [RESPONSE_TIMEOUT_SECONDS] for the whole answer.
 * The green line `event=lake.egress_proxy_verified` carries `reader_end_by_ms`, the bound a reader against a store
 * that trickles on every attempt ends within, from [LakeHttpBounds.egressReaderEndByMillis] (never restated here).
 * A refusal is an [IllegalStateException] whose message leads with `lake.boot.egress_proxy` (configuration.md §7).
 */
object ManagedLakeEgressCheck {
    /** The proxy's self-declaration route and the header that selects it. */
    const val DECLARATION_PATH = "/dp-egress"
    const val DECLARATION_HEADER = "x-dp-egress-check"

    /** The connect bound of each exchange, seconds. */
    const val CONNECT_TIMEOUT_SECONDS = 2L

    /** The whole-answer bound of each exchange, seconds. */
    const val RESPONSE_TIMEOUT_SECONDS = 5L

    /** The header an S3-compatible store stamps on every answer; a gateway failure without it is the proxy's own. */
    private const val STORE_REQUEST_ID_HEADER = "x-amz-request-id"
    private const val HTTP_OK = 200
    private val GATEWAY_FAILURES = 502..504
    private const val NANOS_PER_MILLI = 1_000_000L

    private val REFUSAL = LakeBootCheck.Refusal.EGRESS_PROXY
    private val log = LoggerFactory.getLogger(ManagedLakeEgressCheck::class.java)

    /** The `reads.egress-proxy` grammar (`host:port`), the adapter's own: `LakeRules` refuses a malformed value. */
    fun isWellFormed(value: String): Boolean = LakeHttpBounds.isEgressProxy(value.trim())

    /** Verifies the configured proxy, or refuses the boot. A no-op (with a WARN) when no proxy is configured. */
    fun verify(properties: LakeProperties) {
        val proxy = properties.reads.egressProxy.trim()
        if (proxy.isEmpty()) {
            log.warn(
                "event=lake.egress_proxy_absent message=\"datapipelines.lake.reads.egress-proxy is empty: a managed read's " +
                    "object-store connection has no max-age, so a store trickling above curl's low-speed limit can hold " +
                    "a reader (duckdb-httpfs#519)\"",
            )
            return
        }
        if (!isWellFormed(proxy)) refuse("datapipelines.lake.reads.egress-proxy '$proxy' is not host:port")
        val maxAge = properties.reads.egressMaxAgeSeconds
        val started = System.nanoTime()
        val address = InetSocketAddress(proxy.substringBeforeLast(':'), proxy.substringAfterLast(':').toInt())
        if (address.isUnresolved) refuse("the proxy $proxy is unreachable: its host does not resolve")
        val store = storeProbeUri(properties.storage)

        val declared = exchange(HttpClient.Builder.NO_PROXY, declarationRequest(proxy), "the proxy $proxy is unreachable")
        val expected = "max-age=$maxAge"
        if (declared.statusCode() != HTTP_OK || declared.body().trim() != expected) {
            refuse(
                "the proxy $proxy answered ${declared.statusCode()} '${declared.body().trim().take(BODY_ECHO_LIMIT)}' at " +
                    "$DECLARATION_PATH; the configured cap is '$expected' (datapipelines.lake.reads.egress-max-age-seconds)",
            )
        }

        val reached =
            exchange(ProxySelector.of(address), storeRequest(store), "the proxy $proxy cannot reach the store $store")
        if (reached.statusCode() in GATEWAY_FAILURES && reached.headers().firstValue(STORE_REQUEST_ID_HEADER).isEmpty) {
            refuse("the proxy $proxy cannot reach the store $store: it answered ${reached.statusCode()} itself")
        }
        log.info(
            "event=lake.egress_proxy_verified proxy={} max_age_seconds={} reader_end_by_ms={} store={} store_status={} " +
                "duration_ms={}",
            proxy,
            maxAge,
            // The trickled reader's bound, from the one derivation (LakeHttpBounds.egressReaderEndByMillis).
            LakeHttpBounds.egressReaderEndByMillis(maxAge),
            store,
            reached.statusCode(),
            (System.nanoTime() - started) / NANOS_PER_MILLI,
        )
    }

    /**
     * The bucket as DuckDB addresses it for this storage configuration — [EgressStoreTarget]'s ONE derivation
     * (probe A, lane 862: the blank-endpoint case resolves to the REGIONAL `s3.<region>.amazonaws.com` the engine
     * dials, default region us-east-1; the class KDoc's step 2), so the probe cannot succeed while the engine
     * dials a host the proxy refuses.
     */
    internal fun storeProbeUri(storage: LakeProperties.Storage): URI =
        EgressStoreTarget.derive(
            endpoint = storage.endpoint,
            region = storage.region,
            bucket = storage.bucket,
            pathStyle = storage.pathStyle,
        ).probeUri

    private fun declarationRequest(proxy: String): HttpRequest =
        HttpRequest
            .newBuilder(URI("http://$proxy$DECLARATION_PATH"))
            .timeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
            .header(DECLARATION_HEADER, "1")
            .GET()
            .build()

    private fun storeRequest(store: URI): HttpRequest =
        HttpRequest
            .newBuilder(store)
            .timeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()

    /** One bounded exchange on a client of its own, shut down whatever happens; every failure refuses with [what]. */
    private fun exchange(
        proxy: ProxySelector,
        request: HttpRequest,
        what: String,
    ): HttpResponse<String> {
        val client =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(proxy)
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        var answer: CompletableFuture<HttpResponse<String>>? = null
        return try {
            answer = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            answer.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            refuse("$what: no answer within $RESPONSE_TIMEOUT_SECONDS s (${timeout.javaClass.simpleName})")
        } catch (failed: ExecutionException) {
            val cause = failed.cause ?: failed
            if (cause !is IOException) throw failed
            refuse("$what: ${cause.javaClass.simpleName}: ${cause.message.orEmpty()}")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            refuse("$what: interrupted")
        } finally {
            answer?.cancel(true)
            client.shutdownNow()
        }
    }

    private fun refuse(detail: String): Nothing = error("${REFUSAL.id}: ${REFUSAL.summary} — $detail")

    /** How much of an unexpected declaration body a refusal echoes. */
    private const val BODY_ECHO_LIMIT = 80
}
