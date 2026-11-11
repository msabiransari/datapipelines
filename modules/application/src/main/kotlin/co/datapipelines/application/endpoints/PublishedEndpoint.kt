package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.PipelineVersionStatus
import java.time.Instant
import java.util.UUID

/**
 * One row of `published_endpoints` (published-endpoints design §4, dependency-graph record §24) — an API ENTITY served
 * as `GET /api{pathPattern}` (R-EP5: the stored pattern is the part after `/api`, and its first segment is the
 * engineer's category).
 *
 * Since #786 the entity pins NO pipeline: it owns versions (`published_endpoint_versions`, [PublishedEndpointVersion])
 * and a runtime pointer, [current]. Each version pins an EXACT pipeline version by name and number, so moving a
 * pipeline's current version never changes what an API serves — releasing a new API version, or switching the API's
 * own pointer, does. (This corrects the pre-#786 comment that the row pins a pipeline and serves its latest RELEASED
 * version, record §2 F5.) The §4.2 read-only rule is still re-checked on every serve, against the pinned version.
 *
 * The identity ([id], [workspaceId], the immutable [pathPattern]) and the operational [isEnabled] are entity-level;
 * none of them is a body-hash input. [current] is the version the pointer names, as read with the row — a listing's
 * view; the serve path re-reads the pointer and its row per request.
 *
 * [parsed] is the §4.1 parse of [pathPattern], carried on the row so the matcher, the ambiguity check and the tree
 * screen share one parse. A row whose stored pattern no longer parses cannot be constructed — [PublishedEndpoint.of]
 * is the only way in. Since #274 the repository never feeds it such a row: a stored row that fails today's grammar is
 * mapped as an [EndpointRow.Legacy] value (retired, flagged with the reason, never served) instead of thrown, so one
 * old row cannot take the registry read — or the boot — down with it.
 */
data class PublishedEndpoint(
    val id: UUID,
    val workspaceId: UUID,
    val pathPattern: String,
    val isEnabled: Boolean,
    val createdBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val parsed: EndpointPath.Parsed,
    val current: CurrentVersion? = null,
) {
    /** The declared parameter names this endpoint's path binds, in order (§4.2). */
    val pathVariables: List<String> get() = parsed.variableNames

    /** The current version's pinned pipeline name, or null when the API has no current version. */
    val pipelineName: String? get() = current?.body?.pipelineName

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
            isEnabled: Boolean,
            createdBy: UUID,
            createdAt: Instant,
            updatedAt: Instant,
            current: CurrentVersion? = null,
        ): PublishedEndpoint =
            PublishedEndpoint(
                id = id,
                workspaceId = workspaceId,
                pathPattern = pathPattern,
                isEnabled = isEnabled,
                createdBy = createdBy,
                createdAt = createdAt,
                updatedAt = updatedAt,
                parsed = EndpointPath.parse(pathPattern).getOrThrow(),
                current = current,
            )
    }
}

/** The version an entity's `current_version` names, as read with the entity row (a listing's view, never the serve read). */
data class CurrentVersion(
    val version: Int,
    val status: PipelineVersionStatus,
    val body: EndpointVersionBody,
)

/**
 * The creation snapshot the legacy `published_endpoints` columns keep (V58 header): written once when an entity is
 * created, read only by the in-place conversion of an entity that has no version row. Nothing serves from it.
 */
data class LegacyEndpointColumns(
    val pipelineId: UUID,
    val timeoutSeconds: Int,
    val description: String,
)

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
