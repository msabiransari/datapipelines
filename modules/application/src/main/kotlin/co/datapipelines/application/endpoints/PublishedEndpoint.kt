package co.datapipelines.application.endpoints

import java.time.Instant
import java.util.UUID

/**
 * One row of `published_endpoints` (published-endpoints design §4) — a released pipeline served
 * as `GET /api/x{pathPattern}`.
 *
 * The row pins a **pipeline**, never a version: §5.1 resolves the latest RELEASED version at
 * request time, so re-releasing a pipeline changes what the endpoint serves without touching the
 * registry. That is deliberate (an endpoint is a stable URL over a moving pipeline) and it is why
 * the §4.2 read-only rule is re-checked on every serve rather than only at publish.
 *
 * [parsed] is the §4.1 parse of [pathPattern], carried on the row so the matcher, the ambiguity
 * check and the tree screen share one parse. A row whose stored pattern no longer parses cannot
 * be constructed — [PublishedEndpoint.of] is the only way in, and the repository refuses to map a
 * row it cannot parse rather than serving something the grammar does not describe.
 */
data class PublishedEndpoint(
    val id: UUID,
    val workspaceId: UUID,
    val pathPattern: String,
    val pipelineId: UUID,
    val timeoutSeconds: Int,
    val description: String,
    val isEnabled: Boolean,
    val createdBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val parsed: EndpointPath.Parsed,
) {
    /** The declared parameter names this endpoint's path binds, in order (§4.2). */
    val pathVariables: List<String> get() = parsed.variableNames

    companion object {
        /**
         * Builds a row, parsing [pathPattern] against §4.1.
         *
         * @throws EndpointPathException when the pattern is not a legal path.
         */
        @Suppress("LongParameterList") // one factory for one wide row; the alternative is a builder nobody wants
        fun of(
            id: UUID,
            workspaceId: UUID,
            pathPattern: String,
            pipelineId: UUID,
            timeoutSeconds: Int,
            description: String,
            isEnabled: Boolean,
            createdBy: UUID,
            createdAt: Instant,
            updatedAt: Instant,
        ): PublishedEndpoint =
            PublishedEndpoint(
                id = id,
                workspaceId = workspaceId,
                pathPattern = pathPattern,
                pipelineId = pipelineId,
                timeoutSeconds = timeoutSeconds,
                description = description,
                isEnabled = isEnabled,
                createdBy = createdBy,
                createdAt = createdAt,
                updatedAt = updatedAt,
                parsed = EndpointPath.parse(pathPattern).getOrThrow(),
            )
    }
}

/**
 * One row of `endpoint_key_bindings` (§4) — an API key bound at a NODE of the endpoint tree.
 *
 * [pathPrefix] is a tree node, not a pattern: `/lending` authorises every endpoint beneath it
 * until a deeper node carries a binding of its own, which REPLACES the inherited one for that
 * subtree (ruling R-EP2). It is stored normalised — a leading `/`, no trailing slash — so the
 * §5.2 ancestor walk can compare strings rather than re-parse.
 */
data class EndpointKeyBinding(
    val pathPrefix: String,
    val apiKeyId: String,
    val workspaceId: UUID,
    val createdBy: UUID,
    val createdAt: Instant,
)
