package co.datapipelines.parameters

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Persistence for `parameter_sets` and `parameter_set_versions` (V39; metadata-db §4.26/§4.27) — the
 * `TemplateRepository` shape, addressed by the set's UUID (record P24) instead of a name.
 *
 * `NamedParameterJdbcTemplate` exclusively (module-structure §8.1); `app` owns the schema. **Every
 * statement takes the workspace and filters by it** — there is no method without a `workspaceId`, and
 * no default: a set of another workspace is ABSENT to every read and unreachable by every write
 * (the templates precedent — hidden and absent are indistinguishable). Names travel as binds, never as
 * SQL text.
 *
 * ## `body_hash` — one expression, computed by the database
 *
 * The stored body is `ParameterSetJson.writeBody(body)` (the validator's canonical form); its hash is
 * `encode(sha256(convert_to(<jsonb>::text, 'UTF8')), 'hex')` over the JSONB projection, computed IN the
 * writing statement ([HASH_EXPR]) — versioning §4.1's pipeline rule, so key order and whitespace never
 * move a hash and the writer and every reader agree by construction.
 *
 * ## The lifecycle statements (versioning §3.5, §5)
 *
 * Draft create is copy-on-write with the NO-OP arm (identical content returns the RELEASED detail and
 * burns no number); the draft write and the release carry the hash precondition in their `WHERE`
 * (zero rows ⇒ the caller's base is stale); the one-draft partial index makes two simultaneous first
 * writers race-safe ([mappingDraftRace]). The pointer follows D60 (sticky; a discard or purge of the
 * current version falls back to the highest eligible live one), and the index metadata follows the
 * pointer ([REINDEX_SQL]) because it indexes the current body.
 */
@Suppress("TooManyFunctions", "LargeClass") // the single owner of every parameter_set* statement, as TemplateRepository is
class ParameterSetRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    // ---- reads ----------------------------------------------------------------------------------------

    /** The index row, live or discarded, or null when the workspace holds no such set. */
    fun findRecord(
        workspaceId: UUID,
        id: UUID,
    ): ParameterSetRecord? =
        jdbc
            .query(
                "$SELECT_RECORD WHERE s.id = :id AND s.workspace_id = :workspaceId",
                mapOf("id" to id, "workspaceId" to workspaceId),
                RECORD,
            ).singleOrNull()

    /** The index row by name (import, promotion — both by name), live or discarded. */
    fun findRecordByName(
        workspaceId: UUID,
        name: String,
    ): ParameterSetRecord? =
        jdbc
            .query(
                "$SELECT_RECORD WHERE s.name = :name AND s.workspace_id = :workspaceId",
                mapOf(
                    "name" to name,
                    "workspaceId" to workspaceId,
                ),
                RECORD,
            ).singleOrNull()

    /** One stored version with its body, any status. */
    fun findVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): ParameterSetVersion? =
        jdbc
            .query(
                "$SELECT_VERSION WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.version = :version",
                mapOf("id" to id, "workspaceId" to workspaceId, "version" to version),
                VERSION,
            ).singleOrNull()

    /** The version the pointer names, or null (never released, or every release discarded). */
    fun findCurrent(
        workspaceId: UUID,
        id: UUID,
    ): ParameterSetVersion? =
        jdbc
            .query(
                "$SELECT_VERSION WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.version = s.current_version",
                mapOf("id" to id, "workspaceId" to workspaceId),
                VERSION,
            ).singleOrNull()

    /** The DRAFT's detail, or null when none exists. */
    fun findDraft(
        workspaceId: UUID,
        id: UUID,
    ): ParameterSetVersionDetail? =
        jdbc
            .query("$SELECT_DETAIL AND s.id = :id AND v.status = 'DRAFT'", mapOf("id" to id, "workspaceId" to workspaceId), DETAIL)
            .singleOrNull()

    /** One version's detail (no body), any status. */
    fun findVersionDetail(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): ParameterSetVersionDetail? =
        jdbc
            .query(
                "$SELECT_DETAIL AND s.id = :id AND v.version = :version",
                mapOf("id" to id, "workspaceId" to workspaceId, "version" to version),
                DETAIL,
            ).singleOrNull()

    /**
     * The WORKING version (versioning §7.1): the DRAFT when one exists, else the current version. Null
     * when neither exists — unknown, or an entity whose every version is discarded.
     */
    fun findWorking(
        workspaceId: UUID,
        id: UUID,
    ): ParameterSetVersion? = findDraft(workspaceId, id)?.let { findVersion(workspaceId, id, it.version) } ?: findCurrent(workspaceId, id)

    /** Every version's detail, newest first. */
    fun listVersions(
        workspaceId: UUID,
        id: UUID,
    ): List<ParameterSetVersionDetail> =
        jdbc.query("$SELECT_DETAIL AND s.id = :id ORDER BY v.version DESC", mapOf("id" to id, "workspaceId" to workspaceId), DETAIL)

    /** True while the set holds a DRAFT or a RELEASED version — the derived ACTIVE status (versioning §3.2). */
    fun isLive(
        workspaceId: UUID,
        id: UUID,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM parameter_sets s WHERE s.id = :id AND s.workspace_id = :workspaceId AND $LIVE_S)",
            mapOf("id" to id, "workspaceId" to workspaceId),
            Boolean::class.java,
        ) == true

    /**
     * Every parameter-set name holding a DRAFT, across ALL workspaces — the authoring-disabled
     * boot check's evidence (versioning §5.5; record C14, lane D). ADDITIVE to the frozen
     * repository API (#194 lane B, `83e4d7c5`): a read only, mirroring
     * `PipelineRepository.findAllDraftPipelineNames`.
     */
    fun findAllDraftParameterSetNames(): List<String> =
        jdbc.query(
            """
            SELECT s.name
              FROM parameter_set_versions v
              JOIN parameter_sets s ON s.id = v.parameter_set_id
             WHERE v.status = 'DRAFT'
             ORDER BY s.name
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.getString("name") }

    /**
     * One tree level's direct sub-folders (the `TemplateRepository.listChildFolders` shape — the
     * workspace equality leads `uq_parameter_sets_workspace_name`, so this is a bounded range scan).
     * The root level ([prefix] null) is where a new root would appear — lane D's
     * `new_root_requires_confirmation` reads it.
     */
    fun listChildFolders(
        workspaceId: UUID,
        prefix: String?,
        limit: Int = MAX_PAGE_LIMIT,
    ): List<ParameterSetFolder> =
        jdbc.query(
            """
            SELECT split_part(substring(s.name FROM CAST(:cutFrom AS INT)), '/', 1) AS segment, COUNT(*) AS set_count
              FROM parameter_sets s
            $TREE_WHERE
               AND position('/' IN substring(s.name FROM CAST(:cutFrom AS INT))) > 0
             GROUP BY 1
             ORDER BY 1
             LIMIT :limit
            """.trimIndent(),
            treeParams(workspaceId, prefix) + ("limit" to limit.coerceIn(1, MAX_PAGE_LIMIT + 1)),
        ) { rs, _ ->
            val segment = rs.getString("segment")
            ParameterSetFolder(if (prefix.isNullOrEmpty()) segment else "$prefix/$segment", segment, rs.getInt("set_count"))
        }

    /** One tree level's direct sets, at their WORKING-OR-CURRENT listing version (D55: a never-released set lists its draft). */
    fun listChildSets(
        workspaceId: UUID,
        prefix: String?,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): List<ParameterSetVersion> =
        jdbc.query(
            """
            $SELECT_VERSION
            $TREE_WHERE
               AND position('/' IN substring(s.name FROM CAST(:cutFrom AS INT))) = 0
               AND v.version = $LISTED_VERSION
             ORDER BY s.name
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
            treeParams(workspaceId, prefix) + mapOf("limit" to limit.coerceIn(1, MAX_PAGE_LIMIT + 1), "offset" to maxOf(0, offset)),
            VERSION,
        )

    /** The truthful total of [listChildSets] — the same predicate, no paging. */
    fun countChildSets(
        workspaceId: UUID,
        prefix: String?,
    ): Int =
        checkNotNull(
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM parameter_sets s
                $TREE_WHERE
                   AND position('/' IN substring(s.name FROM CAST(:cutFrom AS INT))) = 0
                """.trimIndent(),
                treeParams(workspaceId, prefix),
                Int::class.java,
            ),
        )

    /**
     * The FLAT listing (#312): every live set at its LISTED version (D55), no level clause — the
     * pipelines §5.7 mould, [listChildSets] minus the folder cut. The tree routes keep
     * [listChildSets]; this is what `GET /parameter-sets` without a prefix pages through.
     */
    fun listAll(
        workspaceId: UUID,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): List<ParameterSetVersion> =
        jdbc.query(
            """
            $SELECT_VERSION
            WHERE s.workspace_id = :workspaceId AND $LIVE_S
               AND v.version = $LISTED_VERSION
             ORDER BY s.name
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
            mapOf("workspaceId" to workspaceId, "limit" to limit.coerceIn(1, MAX_PAGE_LIMIT + 1), "offset" to maxOf(0, offset)),
            VERSION,
        )

    /** The truthful total of [listAll] — the same predicate, no paging. */
    fun countAll(workspaceId: UUID): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM parameter_sets s WHERE s.workspace_id = :workspaceId AND $LIVE_S",
                mapOf("workspaceId" to workspaceId),
                Int::class.java,
            ),
        )

    /**
     * Every live set that HAS a current version, with that version's number and hash — the promoter
     * lens's input (versioning §10.2: released and newer than the target's). A never-released set is
     * absent: a draft is never promotable.
     */
    fun findCurrentVersions(workspaceId: UUID): List<CurrentParameterSetVersion> =
        jdbc.query(
            """
            SELECT s.id, s.name, s.display_name, v.version, v.body_hash
              FROM parameter_sets s
              JOIN parameter_set_versions v ON v.parameter_set_id = s.id AND v.version = s.current_version
             WHERE s.workspace_id = :workspaceId AND $LIVE_S AND v.status = 'RELEASED'
             ORDER BY s.name
            """.trimIndent(),
            mapOf("workspaceId" to workspaceId),
        ) { rs, _ ->
            CurrentParameterSetVersion(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                displayName = rs.getString("display_name"),
                version = rs.getInt("version"),
                bodyHash = rs.getString("body_hash"),
            )
        }

    /** The hash the database would store for [body] — the import's recompute guard (versioning §9.2). */
    fun computeBodyHash(body: ParameterSetBody): String =
        checkNotNull(jdbc.queryForObject("SELECT $HASH_EXPR", mapOf("bodyJson" to ParameterSetJson.writeBody(body)), String::class.java))

    // ---- writes ---------------------------------------------------------------------------------------

    /**
     * Creates the set [id] and its version 1 — DRAFT with a NULL pointer for an authoring create (D55),
     * RELEASED with the pointer at 1 for a seed or a version-less import ([lifecycle] is required: a
     * missed create path is a compile error, the versioning §3.6 rule).
     *
     * @throws DatapipelinesException `parameter.validation.duplicate_name` (409) when the workspace
     *   already holds the name; `parameter.version.conflict` (`details.reason = id_taken`) when the id
     *   exists (an import of a set this server already holds in another workspace).
     */
    @Suppress("LongParameterList") // the set's identity, its body and the three stamps of a create
    fun create(
        workspaceId: UUID,
        id: UUID,
        name: String,
        body: ParameterSetBody,
        actor: UUID,
        lifecycle: CreateLifecycle,
        via: WriteSurface,
    ): ParameterSetVersionDetail =
        mappingUniqueViolations(name, id) {
            jdbc
                .query(
                    if (lifecycle == CreateLifecycle.DRAFT) INSERT_DRAFT_SQL else INSERT_RELEASED_SQL,
                    writeParams(workspaceId, id, body, actor) +
                        mapOf("name" to name, "via" to via.wire, "version" to 1, "releasedAt" to null),
                    DETAIL,
                ).single()
        }

    /**
     * Draft create — copy-on-write from the CURRENT RELEASED version (versioning §5.1): the draft takes
     * `max(version) + 1`. Answers the RELEASED detail when the content is identical (the no-op arm —
     * no draft, no number burned); null when the precondition failed (stale hash, no released current,
     * a draft already exists).
     */
    fun createDraft(
        workspaceId: UUID,
        id: UUID,
        body: ParameterSetBody,
        expectedHash: String,
        actor: UUID,
        via: WriteSurface,
    ): ParameterSetVersionDetail? =
        mappingDraftRace(workspaceId, id) {
            jdbc
                .query(
                    CREATE_DRAFT_SQL,
                    writeParams(workspaceId, id, body, actor) + mapOf("expectedHash" to expectedHash, "via" to via.wire),
                    DETAIL,
                ).singleOrNull()
        }

    /** Draft write in place (versioning §5.2), hash-preconditioned; null when no DRAFT matched [expectedHash]. */
    fun writeDraft(
        workspaceId: UUID,
        id: UUID,
        body: ParameterSetBody,
        expectedHash: String,
        actor: UUID,
        via: WriteSurface,
    ): ParameterSetVersionDetail? =
        jdbc
            .query(
                WRITE_DRAFT_SQL,
                writeParams(workspaceId, id, body, actor) + mapOf("expectedHash" to expectedHash, "via" to via.wire),
                DETAIL,
            ).singleOrNull()

    /**
     * Release (versioning §5.3): the DRAFT at [expectedHash] flips to RELEASED, the pointer moves to it
     * and the index row adopts its display name and description — one statement. Null when no DRAFT
     * matched the hash.
     */
    fun releaseDraft(
        workspaceId: UUID,
        id: UUID,
        expectedHash: String,
        actor: UUID,
    ): ParameterSetVersionDetail? =
        jdbc
            .query(RELEASE_SQL, mapOf("id" to id, "workspaceId" to workspaceId, "expectedHash" to expectedHash, "actor" to actor), DETAIL)
            .singleOrNull()

    /**
     * Purge the DRAFT (versioning §5.4): hard-deleted (nothing references a version row; the only row that
     * references a version cascades — test evidence). The sole version takes the entity with it (D57's twin);
     * a draft that had become the pointer (the development fallback) moves it. [expectedHash] null targets
     * the draft without a hash. The answer is the purge's scope (#372): [Purged.Entity] when the version
     * count hit zero, [Purged.Version] otherwise. Null when no DRAFT matched.
     */
    fun purgeDraft(
        workspaceId: UUID,
        id: UUID,
        expectedHash: String?,
        draftEligible: Boolean,
    ): Purged? {
        val hashGuard = if (expectedHash == null) "" else " AND v.body_hash = :expectedHash"
        val params = mapOf("id" to id, "workspaceId" to workspaceId, "expectedHash" to expectedHash, "draftEligible" to draftEligible)
        val purged =
            jdbc
                .query(
                    "DELETE FROM parameter_set_versions v USING parameter_sets s" +
                        " WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.parameter_set_id = s.id" +
                        " AND v.status = 'DRAFT'$hashGuard" +
                        " RETURNING v.version",
                    params,
                ) { rs, _ -> rs.getInt("version") }
                .singleOrNull() ?: return null
        if (versionCount(workspaceId, id) == 0) {
            deleteEntity(workspaceId, id)
            return Purged.Entity
        }
        jdbc.update(POINTER_FALLBACK_SQL, params + ("version" to purged))
        jdbc.update(REINDEX_SQL, params)
        return Purged.Version
    }

    /** Discard a RELEASED version (versioning §3.1): stamps, pointer fallback when it WAS the pointer. Null when it was not RELEASED. */
    fun discardVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        actor: UUID,
        draftEligible: Boolean,
    ): VersionMoved? {
        val params =
            mapOf(
                "id" to id,
                "workspaceId" to workspaceId,
                "version" to version,
                "actor" to actor,
                "draftEligible" to draftEligible,
            )
        return jdbc.query(DISCARD_SQL, params, MOVED).singleOrNull()?.also { jdbc.update(REINDEX_SQL, params) }
    }

    /** Restore a DISCARDED version to RELEASED; the pointer moves only above-current-or-NULL (D60). Null when it was not DISCARDED. */
    fun restoreVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): VersionMoved? {
        val params = mapOf("id" to id, "workspaceId" to workspaceId, "version" to version)
        return jdbc.query(RESTORE_SQL, params, MOVED).singleOrNull()?.also { jdbc.update(REINDEX_SQL, params) }
    }

    /** Manual switch (D60): the pointer names [version] when it is live and posture-eligible; the pair otherwise null. */
    fun switchCurrent(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        draftEligible: Boolean,
    ): PointerMove? {
        val params = mapOf("id" to id, "workspaceId" to workspaceId, "version" to version, "draftEligible" to draftEligible)
        return jdbc
            .query(
                SWITCH_SQL,
                params,
            ) { rs, _ -> pointerOf(rs) }
            .singleOrNull()
            ?.also { jdbc.update(REINDEX_SQL, params) }
    }

    /** Deletes the index row (versions cascade). True when a row went. */
    fun deleteEntity(
        workspaceId: UUID,
        id: UUID,
    ): Boolean =
        jdbc.update(
            "DELETE FROM parameter_sets WHERE id = :id AND workspace_id = :workspaceId",
            mapOf(
                "id" to id,
                "workspaceId" to workspaceId,
            ),
        ) >
            0

    /**
     * Import onto a NEW set (versioning §9.2): the index row keeps the exported [id] (P24) and the
     * version lands RELEASED at the payload's EXACT number with the source's hash (already recomputed
     * by the caller); the pointer is set — nothing exists to break.
     */
    @Suppress("LongParameterList") // the exported identity, the version and the three stamps of an import
    fun importNew(
        workspaceId: UUID,
        id: UUID,
        name: String,
        body: ParameterSetBody,
        version: Int,
        releasedAt: Instant?,
        actor: UUID,
    ): ParameterSetVersionDetail =
        mappingUniqueViolations(name, id) {
            jdbc
                .query(
                    INSERT_RELEASED_SQL,
                    writeParams(workspaceId, id, body, actor) +
                        mapOf(
                            "name" to name,
                            "via" to WriteSurface.SESSION.wire,
                            "version" to version,
                            "releasedAt" to releasedAt?.let(Timestamp::from),
                        ),
                    DETAIL,
                ).single()
        }

    /**
     * Import onto an EXISTING set (versioning §9.2): the version lands RELEASED at [version] (or, with
     * null, at `max + 1`); the pointer moves ONLY when it is NULL (D60's first-import exception), and
     * the index row adopts the body exactly then. Null when the number is taken — the caller classifies.
     */
    fun insertReleased(
        workspaceId: UUID,
        id: UUID,
        body: ParameterSetBody,
        version: Int?,
        releasedAt: Instant?,
        actor: UUID,
    ): ParameterSetVersionDetail? {
        val params =
            writeParams(workspaceId, id, body, actor) +
                mapOf("version" to version, "releasedAt" to releasedAt?.let(Timestamp::from))
        return jdbc.query(INSERT_RELEASED_VERSION_SQL, params, DETAIL).singleOrNull()?.also { jdbc.update(REINDEX_SQL, params) }
    }

    // ---- plumbing -------------------------------------------------------------------------------------

    private fun versionCount(
        workspaceId: UUID,
        id: UUID,
    ): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM parameter_set_versions v JOIN parameter_sets s ON s.id = v.parameter_set_id" +
                    " WHERE s.id = :id AND s.workspace_id = :workspaceId",
                mapOf("id" to id, "workspaceId" to workspaceId),
                Int::class.java,
            ),
        )

    private fun writeParams(
        workspaceId: UUID,
        id: UUID,
        body: ParameterSetBody,
        actor: UUID,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "workspaceId" to workspaceId,
            "bodyJson" to ParameterSetJson.writeBody(body),
            "displayName" to body.displayName,
            "description" to body.description.orEmpty(),
            "actor" to actor,
        )

    private fun treeParams(
        workspaceId: UUID,
        prefix: String?,
    ): Map<String, Any?> =
        mapOf(
            "workspaceId" to workspaceId,
            "namePattern" to if (prefix.isNullOrEmpty()) "%" else "${escapeLike(prefix)}/%",
            "cutFrom" to if (prefix.isNullOrEmpty()) 1 else prefix.length + 2,
        )

    /**
     * The name UNIQUE → `duplicate_name` (409); the id PK → `version.conflict` / `id_taken` — the
     * constraint is the only atomic authority, so its violation is translated, never pre-checked away.
     */
    private fun <T> mappingUniqueViolations(
        name: String,
        id: UUID,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: DuplicateKeyException) {
            val cause = e.mostSpecificCause.message.orEmpty()
            when {
                NAME_CONSTRAINT in cause -> {
                    throw DatapipelinesException(
                        ParameterErrorCodes.DUPLICATE_NAME,
                        "A parameter set named '${name.safeEcho()}' already exists in this workspace.",
                        mapOf("name" to name.safeEcho()),
                        e,
                    )
                }

                PK_CONSTRAINT in cause -> {
                    throw DatapipelinesException(
                        ParameterErrorCodes.VERSION_CONFLICT,
                        "A parameter set with id $id already exists on this server.",
                        mapOf("reason" to "id_taken", "id" to id.toString()),
                        e,
                    )
                }

                else -> {
                    throw e
                }
            }
        }

    /**
     * The first-writer race → `version.conflict` carrying the WINNER's state (versioning §3.3): the loser
     * re-reads and rebases. Two constraints can refuse the loser, and it is usually the FIRST one: both
     * writers allocate `max(version) + 1` from the same committed rows, so the loser collides on the
     * version PRIMARY KEY before it reaches the one-draft index (Postgres checks unique indexes in
     * creation order) — found by the forced race, which the index-only mapping answered with a raw
     * `DuplicateKeyException`. Either constraint is the same race.
     */
    private fun <T> mappingDraftRace(
        workspaceId: UUID,
        id: UUID,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: DuplicateKeyException) {
            val cause = e.mostSpecificCause.message.orEmpty()
            if (DRAFT_INDEX !in cause && VERSION_PK !in cause) throw e
            val winner = findDraft(workspaceId, id)
            throw DatapipelinesException(
                ParameterErrorCodes.VERSION_CONFLICT,
                "The parameter set was modified by someone else after you loaded it.",
                conflictDetails(winner),
                e,
            )
        }

    companion object {
        const val DEFAULT_PAGE_LIMIT = 50
        const val MAX_PAGE_LIMIT = 200

        private const val NAME_CONSTRAINT = "uq_parameter_sets_workspace_name"
        private const val PK_CONSTRAINT = "parameter_sets_pkey"
        private const val DRAFT_INDEX = "uq_parameter_set_versions_one_draft"
        private const val VERSION_PK = "parameter_set_versions_pkey"

        /** The §4.2 conflict `details`: the current hash, status and author — what the caller rebases on. */
        fun conflictDetails(current: ParameterSetVersionDetail?): Map<String, Any?> =
            mapOf(
                "current_body_hash" to (current?.bodyHash ?: ""),
                "current_status" to (current?.status?.name ?: "UNKNOWN"),
                "updated_by" to (current?.updatedBy?.toString() ?: ""),
                "updated_at" to (current?.updatedAt?.toString() ?: ""),
            )

        private fun escapeLike(term: String): String = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /** The canonical hash of the `:bodyJson` bind — one expression in every write (versioning §4.1). */
        private const val HASH_EXPR = "encode(sha256(convert_to(CAST(:bodyJson AS jsonb)::text, 'UTF8')), 'hex')"

        /** The derived ACTIVE status (versioning §3.2), `s`-aliased. */
        private const val LIVE_S =
            "EXISTS (SELECT 1 FROM parameter_set_versions lv WHERE lv.parameter_set_id = s.id" +
                " AND lv.status IN ('DRAFT','RELEASED'))"

        /** The version a listing shows: the pointer, else (never released, D55) the draft. */
        private const val LISTED_VERSION =
            "COALESCE(s.current_version, (SELECT MAX(d.version) FROM parameter_set_versions d" +
                " WHERE d.parameter_set_id = s.id AND d.status = 'DRAFT'))"

        private const val SELECT_RECORD =
            "SELECT s.id, s.workspace_id, s.name, s.display_name, s.description, s.current_version," +
                " s.created_at, s.updated_at, s.created_by" +
                " FROM parameter_sets s"

        private const val DETAIL_COLS =
            "v.parameter_set_id, v.version, v.status, v.body_hash, v.created_at, v.created_by, v.released_at, v.released_by," +
                " v.discarded_at, v.discarded_by, v.updated_by, v.updated_at, v.created_via, v.updated_via"

        private const val DETAIL_COLS_PLAIN =
            "parameter_set_id, version, status, body_hash, created_at, created_by, released_at, released_by," +
                " discarded_at, discarded_by, updated_by, updated_at, created_via, updated_via"

        /** Workspace-scoped, deliberately WITHOUT a live filter: restore and import classification read discarded rows. */
        private const val SELECT_DETAIL =
            "SELECT $DETAIL_COLS FROM parameter_set_versions v JOIN parameter_sets s ON s.id = v.parameter_set_id" +
                " WHERE s.workspace_id = :workspaceId"

        private const val SELECT_VERSION =
            "SELECT s.id, s.workspace_id, s.name, s.display_name, s.description, s.current_version, s.created_at AS set_created_at," +
                " s.updated_at AS set_updated_at, s.created_by AS set_created_by, v.body_json::TEXT AS body_json, $DETAIL_COLS" +
                " FROM parameter_sets s JOIN parameter_set_versions v ON v.parameter_set_id = s.id"

        /** One tree level's scope: the workspace, the live sets, the prefix (its LIKE metacharacters escaped). */
        private const val TREE_WHERE =
            "WHERE s.workspace_id = :workspaceId AND $LIVE_S AND s.name LIKE CAST(:namePattern AS TEXT) ESCAPE '\\'"

        /** D55: an authoring create lands v1 DRAFT with a NULL pointer; `updated_*` stamped as a draft write stamps them. */
        private val INSERT_DRAFT_SQL =
            """
            WITH new_set AS (
                INSERT INTO parameter_sets (id, workspace_id, name, display_name, description, current_version, created_by)
                VALUES (:id, :workspaceId, :name, :displayName, :description, NULL, :actor)
                RETURNING id
            )
            INSERT INTO parameter_set_versions
                (parameter_set_id, version, body_json, status, body_hash, created_by, updated_by, updated_at, created_via, updated_via)
            SELECT id, 1, CAST(:bodyJson AS jsonb), 'DRAFT', $HASH_EXPR, :actor, :actor, NOW(), :via, :via FROM new_set
            RETURNING $DETAIL_COLS_PLAIN
            """.trimIndent()

        /** The RELEASED create: a seed, a version-less import (v1) or an exact-version import (`:version`); the pointer names it. */
        private val INSERT_RELEASED_SQL =
            """
            WITH new_set AS (
                INSERT INTO parameter_sets (id, workspace_id, name, display_name, description, current_version, created_by)
                VALUES (:id, :workspaceId, :name, :displayName, :description, :version, :actor)
                RETURNING id
            )
            INSERT INTO parameter_set_versions
                (parameter_set_id, version, body_json, status, body_hash, created_by, released_by, released_at, created_via, updated_via)
            SELECT id, :version, CAST(:bodyJson AS jsonb), 'RELEASED', $HASH_EXPR, :actor, :actor,
                   COALESCE(CAST(:releasedAt AS TIMESTAMPTZ), NOW()), :via, :via
              FROM new_set
            RETURNING $DETAIL_COLS_PLAIN
            """.trimIndent()

        /**
         * versioning §5.1 — copy-on-write. `draft` inserts at `max + 1` when the content DIFFERS from the
         * current released version; `noop` answers that version when it is identical. Both join `guard`
         * (the precondition: the caller's base is the current RELEASED hash), and both require that no
         * draft exists — a draft that raced in owns the working state (409, never a no-op).
         */
        private val CREATE_DRAFT_SQL =
            """
            WITH guard AS (
                SELECT 1 FROM parameter_set_versions v JOIN parameter_sets s ON s.id = v.parameter_set_id
                 WHERE s.id = :id AND s.workspace_id = :workspaceId
                   AND v.version = s.current_version AND v.status = 'RELEASED' AND v.body_hash = :expectedHash
            ), draft AS (
                INSERT INTO parameter_set_versions
                    (parameter_set_id, version, body_json, status, body_hash, created_by, updated_by, updated_at, created_via, updated_via)
                SELECT v.parameter_set_id,
                       (SELECT MAX(d2.version) + 1 FROM parameter_set_versions d2 WHERE d2.parameter_set_id = v.parameter_set_id),
                       CAST(:bodyJson AS jsonb), 'DRAFT', $HASH_EXPR, :actor, :actor, NOW(), :via, :via
                  FROM parameter_set_versions v JOIN parameter_sets s ON s.id = v.parameter_set_id
                  JOIN guard ON TRUE
                 WHERE s.id = :id AND s.workspace_id = :workspaceId
                   AND v.version = s.current_version AND v.status = 'RELEASED'
                   AND $HASH_EXPR <> v.body_hash
                   AND NOT EXISTS (SELECT 1 FROM parameter_set_versions d WHERE d.parameter_set_id = v.parameter_set_id AND d.status = 'DRAFT')
                RETURNING $DETAIL_COLS_PLAIN
            ), noop AS (
                SELECT $DETAIL_COLS
                  FROM parameter_set_versions v JOIN parameter_sets s ON s.id = v.parameter_set_id
                  JOIN guard ON TRUE
                 WHERE s.id = :id AND s.workspace_id = :workspaceId
                   AND v.version = s.current_version AND v.status = 'RELEASED'
                   AND $HASH_EXPR = v.body_hash
                   AND NOT EXISTS (SELECT 1 FROM parameter_set_versions d WHERE d.parameter_set_id = v.parameter_set_id AND d.status = 'DRAFT')
            )
            SELECT * FROM draft
            UNION ALL
            SELECT * FROM noop
            """.trimIndent()

        /** versioning §5.2 — in place, hash-preconditioned. A draft edited back to its parent is LEFT (discard stays explicit). */
        private val WRITE_DRAFT_SQL =
            """
            UPDATE parameter_set_versions v
               SET body_json = CAST(:bodyJson AS jsonb), body_hash = $HASH_EXPR,
                   updated_by = :actor, updated_at = NOW(), updated_via = :via
              FROM parameter_sets s
             WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.parameter_set_id = s.id
               AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
            RETURNING $DETAIL_COLS
            """.trimIndent()

        /** versioning §5.3 — flip + pointer + the index row's display metadata, one statement. */
        private val RELEASE_SQL =
            """
            WITH locked AS (
                UPDATE parameter_set_versions v
                   SET status = 'RELEASED', released_at = NOW(), released_by = :actor
                  FROM parameter_sets s
                 WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.parameter_set_id = s.id
                   AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
                RETURNING $DETAIL_COLS, v.body_json
            ), bumped AS (
                UPDATE parameter_sets
                   SET current_version = (SELECT version FROM locked),
                       display_name = (SELECT body_json->>'display_name' FROM locked),
                       description = COALESCE((SELECT body_json->>'description' FROM locked), ''),
                       updated_at = NOW()
                 WHERE id = :id AND workspace_id = :workspaceId AND EXISTS (SELECT 1 FROM locked)
                RETURNING id
            )
            SELECT $DETAIL_COLS_PLAIN FROM locked, bumped
            """.trimIndent()

        /** D60 — the pointer fallback after a purge of the current version: the highest eligible live one, else NULL. */
        private val POINTER_FALLBACK_SQL =
            """
            UPDATE parameter_sets s
               SET current_version = (
                       SELECT MAX(lv.version) FROM parameter_set_versions lv
                        WHERE lv.parameter_set_id = s.id
                          AND (lv.status = 'RELEASED' OR (:draftEligible AND lv.status = 'DRAFT'))
                   ),
                   updated_at = NOW()
             WHERE s.id = :id AND s.workspace_id = :workspaceId AND s.current_version = :version
            """.trimIndent()

        /** The index row indexes the CURRENT body (versioning §3.7): after any pointer move, it adopts that body's metadata. */
        private val REINDEX_SQL =
            """
            UPDATE parameter_sets s
               SET display_name = v.body_json->>'display_name', description = COALESCE(v.body_json->>'description', '')
              FROM parameter_set_versions v
             WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.parameter_set_id = s.id AND v.version = s.current_version
            """.trimIndent()

        /**
         * versioning §3.1 — discard a RELEASED version; the pointer falls back only when it named it
         * (excluding it by number: a CTE cannot see the flip made beside it). The row answers the
         * pointer pair (#372): `prev` reads the pointer from the statement's own snapshot, `bumped`
         * returns the moved one.
         */
        private val DISCARD_SQL =
            """
            WITH flipped AS (
                UPDATE parameter_set_versions v
                   SET status = 'DISCARDED', discarded_at = NOW(), discarded_by = :actor
                  FROM parameter_sets s
                 WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.parameter_set_id = s.id
                   AND v.version = :version AND v.status = 'RELEASED'
                RETURNING $DETAIL_COLS
            ), prev AS (
                SELECT s.current_version FROM parameter_sets s
                 WHERE s.id = :id AND s.workspace_id = :workspaceId
            ), bumped AS (
                UPDATE parameter_sets s
                   SET current_version = CASE
                           WHEN s.current_version = :version THEN (
                               SELECT MAX(lv.version) FROM parameter_set_versions lv
                                WHERE lv.parameter_set_id = s.id AND lv.version <> :version
                                  AND (lv.status = 'RELEASED' OR (:draftEligible AND lv.status = 'DRAFT'))
                           )
                           ELSE s.current_version
                       END,
                       updated_at = NOW()
                 WHERE s.id = :id AND s.workspace_id = :workspaceId AND EXISTS (SELECT 1 FROM flipped)
                RETURNING s.current_version
            )
            SELECT flipped.*, prev.current_version AS pointer_before, bumped.current_version AS pointer_after
              FROM flipped, bumped, prev
            """.trimIndent()

        /** versioning §3.1 — restore; D60's rule `GREATEST(COALESCE(current, 0), v)`; the row answers the pointer pair (#372). */
        private val RESTORE_SQL =
            """
            WITH restored AS (
                UPDATE parameter_set_versions v
                   SET status = 'RELEASED', discarded_at = NULL, discarded_by = NULL
                  FROM parameter_sets s
                 WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.parameter_set_id = s.id
                   AND v.version = :version AND v.status = 'DISCARDED'
                RETURNING $DETAIL_COLS
            ), prev AS (
                SELECT s.current_version FROM parameter_sets s
                 WHERE s.id = :id AND s.workspace_id = :workspaceId
            ), bumped AS (
                UPDATE parameter_sets
                   SET current_version = GREATEST(COALESCE(current_version, 0), (SELECT version FROM restored)), updated_at = NOW()
                 WHERE id = :id AND workspace_id = :workspaceId AND EXISTS (SELECT 1 FROM restored)
                RETURNING current_version
            )
            SELECT restored.*, prev.current_version AS pointer_before, bumped.current_version AS pointer_after
              FROM restored, bumped, prev
            """.trimIndent()

        /** D60 — the manual switch; live and posture-eligible or nothing. The row answers the pointer pair (#372). */
        private val SWITCH_SQL =
            """
            WITH prev AS (
                SELECT s.current_version FROM parameter_sets s
                 WHERE s.id = :id AND s.workspace_id = :workspaceId
            ), moved AS (
                UPDATE parameter_sets s
                   SET current_version = :version, updated_at = NOW()
                 WHERE s.id = :id AND s.workspace_id = :workspaceId
                   AND EXISTS (SELECT 1 FROM parameter_set_versions v
                                WHERE v.parameter_set_id = s.id AND v.version = :version
                                  AND (v.status = 'RELEASED' OR (:draftEligible AND v.status = 'DRAFT')))
                RETURNING s.current_version
            )
            SELECT prev.current_version AS pointer_before, moved.current_version AS pointer_after FROM prev, moved
            """.trimIndent()

        /** versioning §9.2 — an exact (or `max + 1`) RELEASED version onto an existing set; the pointer only when NULL. */
        private val INSERT_RELEASED_VERSION_SQL =
            """
            WITH alloc AS (
                SELECT COALESCE(CAST(:version AS INT), (SELECT COALESCE(MAX(v.version), 0) + 1 FROM parameter_set_versions v
                         JOIN parameter_sets s ON s.id = v.parameter_set_id WHERE s.id = :id AND s.workspace_id = :workspaceId)) AS n
            ), ins AS (
                INSERT INTO parameter_set_versions
                    (parameter_set_id, version, body_json, status, body_hash, created_by, released_by, released_at)
                SELECT s.id, alloc.n, CAST(:bodyJson AS jsonb), 'RELEASED', $HASH_EXPR, :actor, :actor,
                       COALESCE(CAST(:releasedAt AS TIMESTAMPTZ), NOW())
                  FROM parameter_sets s, alloc
                 WHERE s.id = :id AND s.workspace_id = :workspaceId
                   AND NOT EXISTS (SELECT 1 FROM parameter_set_versions v WHERE v.parameter_set_id = s.id AND v.version = alloc.n)
                RETURNING $DETAIL_COLS_PLAIN
            ), bumped AS (
                UPDATE parameter_sets
                   SET current_version = COALESCE(current_version, (SELECT version FROM ins)), updated_at = NOW()
                 WHERE id = :id AND workspace_id = :workspaceId AND EXISTS (SELECT 1 FROM ins)
                RETURNING 1
            )
            SELECT ins.* FROM ins, bumped
            """.trimIndent()

        private val RECORD =
            RowMapper { rs: ResultSet, _: Int ->
                ParameterSetRecord(
                    id = rs.getObject("id", UUID::class.java),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    name = rs.getString("name"),
                    displayName = rs.getString("display_name"),
                    description = rs.getString("description"),
                    currentVersion = rs.getObject("current_version") as Int?,
                    createdAt = rs.instant("created_at")!!,
                    updatedAt = rs.instant("updated_at")!!,
                    createdBy = rs.getObject("created_by", UUID::class.java),
                )
            }

        private val DETAIL = RowMapper { rs: ResultSet, _: Int -> detailOf(rs) }

        /** A discard/restore row: the touched version's detail beside the pointer pair its statement answered (#372). */
        private val MOVED = RowMapper { rs: ResultSet, _: Int -> movedOf(rs) }

        private val VERSION =
            RowMapper { rs: ResultSet, _: Int ->
                ParameterSetVersion(
                    record =
                        ParameterSetRecord(
                            id = rs.getObject("id", UUID::class.java),
                            workspaceId = rs.getObject("workspace_id", UUID::class.java),
                            name = rs.getString("name"),
                            displayName = rs.getString("display_name"),
                            description = rs.getString("description"),
                            currentVersion = rs.getObject("current_version") as Int?,
                            createdAt = rs.instant("set_created_at")!!,
                            updatedAt = rs.instant("set_updated_at")!!,
                            createdBy = rs.getObject("set_created_by", UUID::class.java),
                        ),
                    detail = detailOf(rs),
                    body = ParameterSetJson.readBody(rs.getString("body_json")),
                )
            }

        private fun detailOf(rs: ResultSet) =
            ParameterSetVersionDetail(
                parameterSetId = rs.getObject("parameter_set_id", UUID::class.java),
                version = rs.getInt("version"),
                status = PipelineVersionStatus.fromWire(rs.getString("status")),
                bodyHash = rs.getString("body_hash"),
                createdAt = rs.instant("created_at")!!,
                createdBy = rs.getObject("created_by", UUID::class.java),
                releasedAt = rs.instant("released_at"),
                releasedBy = rs.getObject("released_by", UUID::class.java),
                discardedAt = rs.instant("discarded_at"),
                discardedBy = rs.getObject("discarded_by", UUID::class.java),
                updatedBy = rs.getObject("updated_by", UUID::class.java),
                updatedAt = rs.instant("updated_at"),
                createdVia = rs.getString("created_via"),
                updatedVia = rs.getString("updated_via"),
            )

        /** The pointer pair the discard/restore/switch statements return; NULL reads as null (D60's ∅). */
        private fun pointerOf(rs: ResultSet) = PointerMove(rs.getObject("pointer_before") as Int?, rs.getObject("pointer_after") as Int?)

        private fun movedOf(rs: ResultSet) = VersionMoved(detailOf(rs), pointerOf(rs))

        private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
    }
}
