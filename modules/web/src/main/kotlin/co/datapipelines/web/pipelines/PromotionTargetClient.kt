package co.datapipelines.web.pipelines

import co.datapipelines.auth.PromotionProperties
import co.datapipelines.auth.PromotionServerKeyFilter
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.JsonNode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The SENDER's HTTP client for its one configured target (versioning §10, §10.6).
 *
 * The JDK's own `java.net.http.HttpClient` — the repo carries no outbound HTTP client and no
 * dependency needs adding for two calls. Both calls present
 * `DP-Promotion-Key`; nothing else does, and the key never reaches a log line here or
 * anywhere else.
 *
 * ## The target's refusal is the caller's refusal
 * A 4xx from the receiver carries a §13 error envelope that already says exactly what went
 * wrong — `pipeline.promotion.target_is_authoring`, `pipeline.import.missing_datasource`,
 * `pipeline.version.conflict`. Re-labelling it here would hide the receiver's own diagnosis
 * behind a generic "promotion failed", so the code, message and details are re-raised
 * verbatim with `details.target` naming which deployment refused. Transport failures — an
 * unreachable target, a timeout, a non-JSON body — are a different thing entirely and get
 * `pipeline.promotion.target_unreachable` (502), the one promotion code that is not a
 * refusal.
 */
class PromotionTargetClient(
    private val promotionProperties: PromotionProperties,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    /** The lens's inventory counter (§4.1) — a real registry in production and in tests, never a strict mock. */
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    /** Injected so the cache's TTL can be tested without sleeping — `AuthCache`'s shape. */
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val log = LoggerFactory.getLogger(PromotionTargetClient::class.java)

    /** True when this deployment has a target at all — the screen's "promotes nowhere" state. */
    val hasTarget: Boolean get() = promotionProperties.target.isConfigured

    /** The configured target's base URL, for display. Never the key. */
    val targetBaseUrl: String get() = promotionProperties.target.baseUrl.orEmpty()

    /** §18.1 — the target's inventory for [workspace], FRESH: the push path's read (§10.3 guards against a fresh inventory). */
    fun inventory(workspace: String): PromotionWire.Inventory {
        val uri = uri("/api/v1/promotion/inventory?workspace=" + java.net.URLEncoder.encode(workspace, StandardCharsets.UTF_8))
        val request = authorized(HttpRequest.newBuilder(uri).GET()).build()
        return read(send(request), PromotionWire.Inventory::class.java)
    }

    /**
     * The inventory as the promoter LENS reads it (178, roles design §3.1): one target call
     * per workspace per `inventory-cache-ttl-seconds` window, and never a throw.
     *
     * A failed probe — unreachable, malformed, a refusal, no target configured — is
     * [CachedInventory.Unreachable], remembered for the SAME window: the lens then shows
     * nothing (fail closed), and a dead target costs one probe and one WARN per window rather
     * than one per read a promoter makes. `event=pipeline.promotion.lens_unavailable` is that
     * WARN, logged on the probe and not on the cached answers, so it is once per window by
     * construction. The counter's `outcome` is a closed set: `hit` (served from the window),
     * `miss` (probed, answered), `unreachable` (probed, failed).
     *
     * `0` disables the cache: every call probes. [invalidate] drops a workspace's entry after
     * a successful push so the next read sees the target as it now is.
     */
    fun cachedInventory(workspace: String): CachedInventory {
        val ttl = promotionProperties.inventoryCacheTtlSeconds * NANOS_PER_SECOND
        val now = nowNanos()
        val cached = inventories[workspace]
        if (ttl > 0 && cached != null && now - cached.readAt < ttl) {
            count(OUTCOME_HIT)
            return cached.value
        }
        val fresh =
            try {
                CachedInventory.Present(inventory(workspace)).also { count(OUTCOME_MISS) }
            } catch (e: ApiException) {
                // Every failure the fresh read can raise is an ApiException carrying the §13
                // code and a `reason` — the transport ones from unreachable()/malformed(),
                // the target's own refusal from targetRefused(), the unconfigured target from
                // uri(). The reason is what the operator needs; the key is never in it.
                val reason = (e.details["reason"] as? String) ?: e.code
                log.warn(
                    "event=pipeline.promotion.lens_unavailable target={} reason={} workspace={} code={}",
                    targetBaseUrl,
                    reason,
                    workspace,
                    e.code,
                )
                count(OUTCOME_UNREACHABLE)
                CachedInventory.Unreachable(reason, e.code)
            }
        if (ttl > 0) inventories[workspace] = Entry(fresh, now)
        return fresh
    }

    /** Drops [workspace]'s cached inventory — after a successful push, so the next lens read is current. */
    fun invalidate(workspace: String) {
        inventories.remove(workspace)
    }

    /** What [cachedInventory] remembers: the target's answer, or why there is none. */
    sealed interface CachedInventory {
        data class Present(
            val inventory: PromotionWire.Inventory,
        ) : CachedInventory

        /** [reason] is the transport/config reason (`ConnectException`, `no_target_configured`, …); [code] the §13 code. */
        data class Unreachable(
            val reason: String,
            val code: String,
        ) : CachedInventory
    }

    private class Entry(
        val value: CachedInventory,
        val readAt: Long,
    )

    private val inventories = ConcurrentHashMap<String, Entry>()

    private fun count(outcome: String) {
        meterRegistry.counter(LENS_INVENTORY_METRIC, "outcome", outcome).increment()
    }

    /** §18.2 — push one batch. All of it lands on the target, or none of it does. */
    fun push(batch: PromotionWire.Batch): PromotionWire.Applied {
        val body = MAPPER.writeValueAsString(batch)
        val request =
            authorized(HttpRequest.newBuilder(uri("/api/v1/promotion/push")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
        return read(send(request), PromotionWire.Applied::class.java)
    }

    private fun authorized(builder: HttpRequest.Builder): HttpRequest.Builder =
        builder
            .timeout(REQUEST_TIMEOUT)
            .header(PromotionServerKeyFilter.HEADER, requireKey())
            .header("Accept", "application/json")

    private fun requireKey(): String =
        promotionProperties.target.serverKey?.takeIf { it.isNotBlank() }
            ?: throw ApiException(
                PipelineErrorCodes.Auth.PROMOTION_KEY_INVALID,
                "This deployment has a promotion target configured with no server key; startup should have refused it " +
                    "(configuration.md §7). Set datapipelines.deployment.promotion.target.server-key.",
                mapOf("target" to targetBaseUrl),
            )

    private fun uri(path: String): URI {
        val base = promotionProperties.target.baseUrl?.trimEnd('/')
        if (base.isNullOrBlank()) {
            throw ApiException(
                PipelineErrorCodes.Versioning.PROMOTION_TARGET_UNREACHABLE,
                "This deployment has no promotion target configured " +
                    "(datapipelines.deployment.promotion.target.base-url); there is nowhere to promote to.",
                mapOf("reason" to "no_target_configured"),
            )
        }
        return URI.create(base + path)
    }

    private fun send(request: HttpRequest): HttpResponse<String> =
        try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (e: IOException) {
            throw unreachable(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw unreachable(e)
        }

    /**
     * The envelope's `data`, or the target's own error re-raised.
     *
     * A `2xx` body is `{schema_version, correlation_id, data}` (rest-api §4.1); anything else
     * is `{..., error: {code, message, details}}` (§4.2) and the code is re-raised as-is.
     */
    @Suppress("ThrowsCount") // a boundary: each distinct way the target's answer can be wrong is its own catalogued refusal
    private fun <T> read(
        response: HttpResponse<String>,
        type: Class<T>,
    ): T {
        val tree =
            runCatching { MAPPER.readTree(response.body()) }.getOrElse {
                throw malformed(response, "the response body is not JSON")
            }
        if (response.statusCode() in SUCCESS) {
            val data = tree.get("data") ?: throw malformed(response, "the success envelope carries no 'data'")
            return runCatching { MAPPER.treeToValue(data, type) }.getOrElse {
                throw malformed(response, "the response 'data' does not match ${type.simpleName}")
            }
        }
        throw targetRefused(response, tree)
    }

    /** Re-raises the receiver's §13 code verbatim, with the target named in `details`. */
    private fun targetRefused(
        response: HttpResponse<String>,
        tree: JsonNode,
    ): ApiException {
        val error = tree.get("error")
        val code = error?.get("code")?.asText()?.takeIf { it.isNotBlank() }
        val message = error?.get("message")?.asText().orEmpty()
        log.info(
            "event=pipeline.promotion.target_refused target={} status={} code={}",
            targetBaseUrl,
            response.statusCode(),
            code ?: "(none)",
        )
        if (code == null) {
            return malformed(response, "the target refused with HTTP ${response.statusCode()} and no error code")
        }
        val details = linkedMapOf<String, Any?>("target" to targetBaseUrl, "target_status" to response.statusCode())
        (error.get("details") as? com.fasterxml.jackson.databind.node.ObjectNode)?.properties()?.forEach { (key, value) ->
            details[key] = MAPPER.convertValue(value, Any::class.java)
        }
        return ApiException(code, "The promotion target refused: $message", details)
    }

    private fun unreachable(cause: Throwable): ApiException =
        ApiException(
            PipelineErrorCodes.Versioning.PROMOTION_TARGET_UNREACHABLE,
            "The promotion target at $targetBaseUrl could not be reached: ${cause.javaClass.simpleName}.",
            mapOf("target" to targetBaseUrl, "reason" to cause.javaClass.simpleName),
            cause,
        )

    private fun malformed(
        response: HttpResponse<String>,
        why: String,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Versioning.PROMOTION_TARGET_UNREACHABLE,
            "The promotion target at $targetBaseUrl answered HTTP ${response.statusCode()} and $why.",
            mapOf("target" to targetBaseUrl, "target_status" to response.statusCode(), "reason" to "malformed_response"),
        )

    companion object {
        /** `datapipelines.promotion.lens.inventory{outcome=hit|miss|unreachable}` (observability §4.1). */
        const val LENS_INVENTORY_METRIC = "datapipelines.promotion.lens.inventory"
        const val OUTCOME_HIT = "hit"
        const val OUTCOME_MISS = "miss"
        const val OUTCOME_UNREACHABLE = "unreachable"

        private const val NANOS_PER_SECOND = 1_000_000_000L

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** A batch is one request; a large closure over a slow link needs more than a default. */
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(120)

        private val SUCCESS = 200..299
        private val MAPPER = PipelineJson.objectMapper()
    }
}
