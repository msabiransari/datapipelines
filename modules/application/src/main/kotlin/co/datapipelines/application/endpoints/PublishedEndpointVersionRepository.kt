package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistence for `published_endpoint_versions` and the entity's `current_version` pointer (V58, metadata-db §4.13a,
 * dependency-graph record §24). Every statement is parameterised; no table or column name comes from input.
 *
 * Every write runs inside the caller's metadata transaction, AFTER the caller acquired the G0 writer protocol's
 * workspace guard (and the registry key when path absence matters) — this class takes only the class-4 entity row
 * lock ([lockEntity]) and never an advisory key of its own (#841 ledger premise 3: keys only through G0's entry).
 *
 * The body hash is computed by the DATABASE with [API_BODY_HASH_EXPR] on every write — a native save, the in-place
 * conversion and the receiver's import alike — so two writers can never hash one body two ways (record §5.3, A44).
 */
class PublishedEndpointVersionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** One version, or null. */
    fun find(
        endpointId: UUID,
        version: Int,
    ): PublishedEndpointVersion? =
        jdbc
            .query(
                "$SELECT_VERSION WHERE endpoint_id = :id AND version = :version",
                mapOf("id" to endpointId, "version" to version),
                MAPPER,
            ).singleOrNull()

    /** Every version of one API, newest first. */
    fun list(endpointId: UUID): List<PublishedEndpointVersion> =
        jdbc.query("$SELECT_VERSION WHERE endpoint_id = :id ORDER BY version DESC", mapOf("id" to endpointId), MAPPER)

    /** The working DRAFT, or null. */
    fun findDraft(endpointId: UUID): PublishedEndpointVersion? =
        jdbc.query("$SELECT_VERSION WHERE endpoint_id = :id AND status = 'DRAFT'", mapOf("id" to endpointId), MAPPER).singleOrNull()

    /**
     * The version the entity's `current_version` names — the ONE statement the serve path reads its pin with. It reads
     * the entity's new pointer column and the version row only, never the legacy `pipeline_id` / `timeout_seconds` /
     * `description` columns (record §24: serving never reads pipeline CURRENT, and nothing serves from the legacy
     * columns).
     */
    fun findCurrent(endpointId: UUID): PublishedEndpointVersion? =
        jdbc
            .query(
                """
                SELECT v.endpoint_id, v.version, v.status, v.pipeline_name, v.pipeline_version, v.timeout_seconds,
                       v.description, v.body_hash, v.created_at, v.created_by, v.released_at, v.released_by
                  FROM published_endpoints e
                  JOIN published_endpoint_versions v ON v.endpoint_id = e.id AND v.version = e.current_version
                 WHERE e.id = :id
                """.trimIndent(),
                mapOf("id" to endpointId),
                MAPPER,
            ).singleOrNull()

    /** The version addressed by the API's immutable path (the release family's lookup key) — workspace-scoped. */
    fun findByPath(
        workspaceId: UUID,
        pathPattern: String,
        version: Int,
    ): Pair<EntityPointer, PublishedEndpointVersion>? =
        jdbc
            .query(
                """
                SELECT e.id AS entity_id, e.current_version AS entity_current, v.endpoint_id, v.version, v.status,
                       v.pipeline_name, v.pipeline_version, v.timeout_seconds, v.description, v.body_hash, v.created_at,
                       v.created_by, v.released_at, v.released_by
                  FROM published_endpoints e
                  JOIN published_endpoint_versions v ON v.endpoint_id = e.id
                 WHERE e.workspace_id = :ws AND e.path_pattern = :path AND v.version = :version
                """.trimIndent(),
                mapOf("ws" to workspaceId, "path" to pathPattern, "version" to version),
            ) { rs, n ->
                val pointer =
                    EntityPointer(rs.getObject("entity_id", UUID::class.java), rs.getInt("entity_current").takeUnless { rs.wasNull() })
                pointer to requireNotNull(MAPPER.mapRow(rs, n)) { "the version mapper answers a row" }
            }.singleOrNull()

    /** An entity's id and its current pointer, as read for a release node. */
    data class EntityPointer(
        val endpointId: UUID,
        val currentVersion: Int?,
    )

    /**
     * Class-4 lock (record §15.1): the entity row `FOR UPDATE`, after the workspace guard; answers its current pointer,
     * or null when the entity does not exist (the caller's not-found).
     */
    fun lockEntity(endpointId: UUID): EntityPointer? =
        jdbc
            .query(
                "SELECT id, current_version FROM published_endpoints WHERE id = :id FOR UPDATE",
                mapOf("id" to endpointId),
            ) { rs, _ -> EntityPointer(rs.getObject("id", UUID::class.java), rs.getInt("current_version").takeUnless { rs.wasNull() }) }
            .singleOrNull()

    /**
     * A new working DRAFT at `max(existing) + 1` (DISCARDED and purged numbers stay consumed only while their rows
     * exist; a purged draft's number is reused, the pipeline rule). The one-draft index turns a second draft into
     * `endpoint.version.draft_exists`.
     */
    fun insertDraft(
        endpointId: UUID,
        body: EndpointVersionBody,
        actor: UUID,
    ): PublishedEndpointVersion {
        val next =
            jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM published_endpoint_versions WHERE endpoint_id = :id",
                mapOf("id" to endpointId),
                Int::class.java,
            ) ?: 1
        insert(endpointId, next, PipelineVersionStatus.DRAFT, body, actor)
        return requireNotNull(find(endpointId, next)) { "the draft just inserted is not readable" }
    }

    /**
     * Inserts an exact [version] with [status] DRAFT or RELEASED — the in-place conversion's and the receiver import's
     * write (record §24/§E.1: v1 keeps the number reserved for the entity). A RELEASED row is stamped released now, by
     * [actor].
     */
    fun insert(
        endpointId: UUID,
        version: Int,
        status: PipelineVersionStatus,
        body: EndpointVersionBody,
        actor: UUID,
    ) {
        require(status != PipelineVersionStatus.DISCARDED) { "a version is never born DISCARDED" }
        try {
            jdbc.update(
                """
                INSERT INTO published_endpoint_versions
                    (endpoint_id, version, status, pipeline_name, pipeline_version, timeout_seconds, description, body_hash,
                     released_at, released_by, created_by)
                VALUES (:id, :version, :status, :pipelineName, :pipelineVersion, :timeoutSeconds, :description, $API_BODY_HASH_EXPR,
                        CASE WHEN :status = 'RELEASED' THEN NOW() END, CASE WHEN :status = 'RELEASED' THEN CAST(:actor AS UUID) END,
                        :actor)
                """.trimIndent(),
                bodyParams(body) + mapOf("id" to endpointId, "version" to version, "status" to status.name, "actor" to actor),
            )
        } catch (e: DuplicateKeyException) {
            throw draftExists(endpointId, e)
        }
    }

    /** The working draft's new body, at the same number, iff its stored hash is [expectedHash]; null on a CAS miss. */
    fun updateDraft(
        endpointId: UUID,
        version: Int,
        body: EndpointVersionBody,
        expectedHash: String,
        actor: UUID,
    ): PublishedEndpointVersion? {
        val updated =
            jdbc.update(
                """
                UPDATE published_endpoint_versions
                   SET pipeline_name = :pipelineName, pipeline_version = :pipelineVersion, timeout_seconds = :timeoutSeconds,
                       description = :description, body_hash = $API_BODY_HASH_EXPR, updated_at = NOW(), updated_by = :actor
                 WHERE endpoint_id = :id AND version = :version AND status = 'DRAFT' AND body_hash = :expected
                """.trimIndent(),
                bodyParams(body) + mapOf("id" to endpointId, "version" to version, "expected" to expectedHash, "actor" to actor),
            )
        return if (updated == 1) find(endpointId, version) else null
    }

    /**
     * Flips DRAFT [version] to RELEASED iff its stored hash is [expectedHash] and moves the entity's pointer to it (the
     * pipeline rule: a release becomes current). Null on a CAS miss — the coordinator's `plan_conflict`.
     */
    fun release(
        endpointId: UUID,
        version: Int,
        expectedHash: String,
        actor: UUID,
    ): PublishedEndpointVersion? {
        val flipped =
            jdbc.update(
                """
                UPDATE published_endpoint_versions
                   SET status = 'RELEASED', released_at = NOW(), released_by = :actor
                 WHERE endpoint_id = :id AND version = :version AND status = 'DRAFT' AND body_hash = :expected
                """.trimIndent(),
                mapOf("id" to endpointId, "version" to version, "expected" to expectedHash, "actor" to actor),
            )
        if (flipped != 1) return null
        setCurrent(endpointId, version)
        return find(endpointId, version)
    }

    /** RELEASED → DISCARDED; when it was current the pointer recomputes ([recomputePointer]). Whether it flipped. */
    fun discard(
        endpointId: UUID,
        version: Int,
        actor: UUID,
        draftEligible: Boolean,
    ): Boolean {
        val flipped =
            jdbc.update(
                """
                UPDATE published_endpoint_versions SET status = 'DISCARDED', discarded_at = NOW(), discarded_by = :actor
                 WHERE endpoint_id = :id AND version = :version AND status = 'RELEASED'
                """.trimIndent(),
                mapOf("id" to endpointId, "version" to version, "actor" to actor),
            )
        if (flipped == 1) recomputePointer(endpointId, version, draftEligible)
        return flipped == 1
    }

    /**
     * DISCARDED → RELEASED, the original release stamps untouched; the pointer moves to it only when it is above the
     * current one or there is none (the pipeline's D60 rule). Whether it flipped.
     */
    fun restore(
        endpointId: UUID,
        version: Int,
    ): Boolean {
        val flipped =
            jdbc.update(
                """
                UPDATE published_endpoint_versions SET status = 'RELEASED', discarded_at = NULL, discarded_by = NULL
                 WHERE endpoint_id = :id AND version = :version AND status = 'DISCARDED'
                """.trimIndent(),
                mapOf("id" to endpointId, "version" to version),
            )
        if (flipped != 1) return false
        jdbc.update(
            """
            UPDATE published_endpoints SET current_version = :version, updated_at = NOW()
             WHERE id = :id AND (current_version IS NULL OR current_version < :version)
            """.trimIndent(),
            mapOf("id" to endpointId, "version" to version),
        )
        return true
    }

    /** Deletes DRAFT [version]; when it was current the pointer recomputes. Whether a row was deleted. */
    fun purgeDraft(
        endpointId: UUID,
        version: Int,
        draftEligible: Boolean,
    ): Boolean {
        val deleted =
            jdbc.update(
                "DELETE FROM published_endpoint_versions WHERE endpoint_id = :id AND version = :version AND status = 'DRAFT'",
                mapOf("id" to endpointId, "version" to version),
            )
        if (deleted == 1) recomputePointer(endpointId, version, draftEligible)
        return deleted == 1
    }

    /** Points the entity at [version] (the explicit current switch, a release, the conversion and creation). */
    fun setCurrent(
        endpointId: UUID,
        version: Int,
    ) {
        jdbc.update(
            "UPDATE published_endpoints SET current_version = :version, updated_at = NOW() WHERE id = :id",
            mapOf("id" to endpointId, "version" to version),
        )
    }

    /**
     * After [removed] stopped being eligible: when it WAS current, the pointer becomes the latest RELEASED version, else
     * (only where drafts are eligible) the working DRAFT, else NULL — never an older body silently chosen by anything
     * but this documented rule, the pipeline's §3.4 recompute.
     */
    private fun recomputePointer(
        endpointId: UUID,
        removed: Int,
        draftEligible: Boolean,
    ) {
        jdbc.update(
            """
            UPDATE published_endpoints e
               SET current_version = COALESCE(
                       (SELECT MAX(v.version) FROM published_endpoint_versions v WHERE v.endpoint_id = e.id AND v.status = 'RELEASED'),
                       CASE WHEN :draftEligible THEN
                           (SELECT v.version FROM published_endpoint_versions v WHERE v.endpoint_id = e.id AND v.status = 'DRAFT')
                       END),
                   updated_at = NOW()
             WHERE e.id = :id AND e.current_version = :removed
            """.trimIndent(),
            mapOf("id" to endpointId, "removed" to removed, "draftEligible" to draftEligible),
        )
    }

    /**
     * The LIVE (DRAFT or RELEASED) API versions of [workspaceId] pinning exactly `pipelineName@version` — noncurrent
     * versions and disabled entities included; DISCARDED protects nothing (record §14, A39).
     */
    fun livePins(
        workspaceId: UUID,
        pipelineName: String,
        version: Int,
    ): List<Pin> =
        jdbc.query(
            "$SELECT_PIN AND v.pipeline_version = :version AND v.status IN ('DRAFT', 'RELEASED') ORDER BY e.path_pattern, v.version",
            mapOf("ws" to workspaceId, "name" to pipelineName, "version" to version),
            PIN_MAPPER,
        )

    /**
     * The LIVE API versions pinning ANY version of [pipelineName]: the pipeline entity purge's blockers. DISCARDED
     * protects nothing here either (record §14): unlike a dashboard's history (R12), a DISCARDED API version's restore
     * revalidates its outbound pin first, so a purged pipeline makes that restore refuse rather than resurrect a dangling pin.
     */
    fun livePinsAnyVersion(
        workspaceId: UUID,
        pipelineName: String,
    ): List<Pin> =
        jdbc.query(
            "$SELECT_PIN AND v.status IN ('DRAFT', 'RELEASED') ORDER BY e.path_pattern, v.version",
            mapOf("ws" to workspaceId, "name" to pipelineName),
            PIN_MAPPER,
        )

    /** One API version pinning a pipeline version: what a refusal names (path, version, status) within visibility. */
    data class Pin(
        val endpointId: UUID,
        val pathPattern: String,
        val version: Int,
        val status: PipelineVersionStatus,
        val pipelineVersion: Int,
    )

    /**
     * The entities with NO version row — the in-place conversion's selector, which is legacy-only by construction:
     * every creation path writes v1 in the creating transaction (record §24, brief E.2).
     */
    fun countVersionless(): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM published_endpoints e
             WHERE NOT EXISTS (SELECT 1 FROM published_endpoint_versions v WHERE v.endpoint_id = e.id)
            """.trimIndent(),
            emptyMap<String, Any>(),
            Int::class.java,
        ) ?: 0

    private fun draftExists(
        endpointId: UUID,
        cause: Throwable,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Endpoint.VERSION_DRAFT_EXISTS,
        message = "This API already has a working draft; edit that draft instead of starting another.",
        details = mapOf("endpoint_id" to endpointId.toString()),
        cause = cause,
    )

    companion object {
        /**
         * THE body hash (record §5.3, A44) — byte-equal to the expression quoted in V58's header (a drift test reads
         * both). The four casts are what lets PostgreSQL type the `jsonb_build_object` arguments from JDBC parameters.
         */
        const val API_BODY_HASH_EXPR =
            "encode(sha256(convert_to((jsonb_build_object('pipeline', CAST(:pipelineName AS TEXT), " +
                "'pipeline_version', CAST(:pipelineVersion AS INTEGER), 'timeout_seconds', CAST(:timeoutSeconds AS INTEGER), " +
                "'description', CAST(:description AS TEXT)))::text, 'UTF8')), 'hex')"

        /** The named parameters [API_BODY_HASH_EXPR] and the body columns read. */
        fun bodyParams(body: EndpointVersionBody): Map<String, Any?> =
            mapOf(
                "pipelineName" to body.pipelineName,
                "pipelineVersion" to body.pipelineVersion,
                "timeoutSeconds" to body.timeoutSeconds,
                "description" to body.description,
            )

        private val SELECT_VERSION =
            """
            SELECT endpoint_id, version, status, pipeline_name, pipeline_version, timeout_seconds, description, body_hash,
                   created_at, created_by, released_at, released_by
              FROM published_endpoint_versions
            """.trimIndent()

        private val SELECT_PIN =
            """
            SELECT e.id, e.path_pattern, v.version, v.status, v.pipeline_version
              FROM published_endpoint_versions v
              JOIN published_endpoints e ON e.id = v.endpoint_id
             WHERE e.workspace_id = :ws AND v.pipeline_name = :name
            """.trimIndent()

        private val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                PublishedEndpointVersion(
                    endpointId = rs.getObject("endpoint_id", UUID::class.java),
                    version = rs.getInt("version"),
                    status = PipelineVersionStatus.valueOf(rs.getString("status")),
                    body =
                        EndpointVersionBody(
                            pipelineName = rs.getString("pipeline_name"),
                            pipelineVersion = rs.getInt("pipeline_version"),
                            timeoutSeconds = rs.getInt("timeout_seconds"),
                            description = rs.getString("description"),
                        ),
                    bodyHash = rs.getString("body_hash"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    releasedAt = rs.getTimestamp("released_at")?.toInstant(),
                    releasedBy = rs.getObject("released_by", UUID::class.java),
                )
            }

        private val PIN_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Pin(
                    endpointId = rs.getObject("id", UUID::class.java),
                    pathPattern = rs.getString("path_pattern"),
                    version = rs.getInt("version"),
                    status = PipelineVersionStatus.valueOf(rs.getString("status")),
                    pipelineVersion = rs.getInt("pipeline_version"),
                )
            }
    }
}
