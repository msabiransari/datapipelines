package co.datapipelines.web.endpoints

import org.springframework.web.util.UriUtils
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The polling URLs of a published mutation's execution (published-mutation-apis §2; #1039): under the CONCRETE business
 * path that admitted it — `/api<path>/executions/{id}` and `…/result` — never the framework's session-only
 * `/api/v1/executions/…`, which an endpoint key cannot follow.
 *
 * The host comes from `datapipelines.auth.base-url` and from nowhere else, exactly like the framework's
 * `ResultUrlFactory` (EngineConfiguration): a URL built from a request's `Host` header would hand a client an attacker's
 * host. With no base URL the URLs are relative. Each path segment is percent-encoded again, because the stored path is
 * the container's DECODED form.
 */
class PublishedMutationUrls(
    baseUrl: String?,
) {
    private val base: String =
        baseUrl
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            ?.also { origin ->
                val parsed = URI.create(origin)
                require(parsed.scheme != null && parsed.host != null) {
                    "datapipelines.auth.base-url must be an absolute origin (scheme + host), was '$origin'"
                }
            }.orEmpty()

    /** `…/executions/{id}` under [businessPath] (the path beneath `/api`, with its leading `/`). */
    fun statusUrl(
        businessPath: String,
        executionId: UUID,
    ): String = "$base${PublishedEndpointController.ROOT}${encoded(businessPath)}/executions/$executionId"

    /** `…/executions/{id}/result` under [businessPath]. */
    fun resultUrl(
        businessPath: String,
        executionId: UUID,
    ): String = statusUrl(businessPath, executionId) + "/result"

    private fun encoded(businessPath: String): String =
        businessPath
            .split('/')
            .joinToString("/") { segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8) }
}
