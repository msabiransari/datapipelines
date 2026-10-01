package co.datapipelines.visualization

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Persistence for one artifact family's two tables (V42; metadata-db §4.28–§4.31) — `ParameterSetRepository`'s
 * API, ONE implementation over both families ([VisualizationRepository], [DashboardRepository]).
 *
 * `NamedParameterJdbcTemplate` exclusively (module-structure §8.1); `app` owns the schema. **Every statement
 * takes the workspace and filters by it** — no method without a `workspaceId`, no default: an artifact of another
 * workspace is ABSENT to every read and unreachable by every write. Names travel as binds, never as SQL text.
 *
 * ## `body_hash` — one expression, computed by the database
 * The stored body is the codec's canonical form; its hash is `ArtifactSql.HASH_EXPR` over the JSONB projection,
 * computed IN the writing statement (versioning §4.1's pipeline rule), so key order and whitespace never move a
 * hash and the writer and every reader agree by construction.
 *
 * ## The lifecycle statements (versioning §3.5, §5)
 * Draft create is copy-on-write with the NO-OP arm (identical content answers the RELEASED detail and burns no
 * number); the draft write and the release carry the hash precondition in their `WHERE` (zero rows ⇒ the caller's
 * base is stale); the one-draft partial index makes two simultaneous first writers race-safe ([mappingDraftRace]).
 * The pointer follows D60 and the index metadata follows the pointer ([ArtifactSql.reindex]).
 */
@Suppress("TooManyFunctions") // the single owner of every statement of the family, as ParameterSetRepository is
open class ArtifactRepository<B : Any>(
    protected val jdbc: NamedParameterJdbcTemplate,
    val kind: ArtifactKind,
    private val codec: BodyCodec<B>,
) {
    private val sql = ArtifactSql(kind)
    private val codes = kind.codes

    // ---- reads ----------------------------------------------------------------------------------------

    /** The index row, live or discarded, or null when the workspace holds no such artifact. */
    fun findRecord(
        workspaceId: UUID,
        id: UUID,
    ): ArtifactRecord? =
        jdbc.query("${sql.selectRecord} WHERE s.id = :id AND s.workspace_id = :workspaceId", ids(workspaceId, id), record).singleOrNull()

    /** The index row by name (import, promotion, a pin — all by name), live or discarded. */
    fun findRecordByName(
        workspaceId: UUID,
        name: String,
    ): ArtifactRecord? =
        jdbc
            .query(
                "${sql.selectRecord} WHERE s.name = :name AND s.workspace_id = :workspaceId",
                mapOf(
                    "name" to name,
                    "workspaceId" to workspaceId,
                ),
                record,
            ).singleOrNull()

    /** One stored version with its body, any status. */
    fun findVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): ArtifactVersion<B>? =
        jdbc
            .query(
                "${sql.selectVersion} WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.version = :version",
                ids(workspaceId, id) + ("version" to version),
                versionMapper,
            ).singleOrNull()

    /** The version the pointer names, or null (never released, or every release discarded). */
    fun findCurrent(
        workspaceId: UUID,
        id: UUID,
    ): ArtifactVersion<B>? =
        jdbc
            .query(
                "${sql.selectVersion} WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.version = s.current_version",
                ids(workspaceId, id),
                versionMapper,
            ).singleOrNull()

    /** The DRAFT's detail, or null when none exists. */
    fun findDraft(
        workspaceId: UUID,
        id: UUID,
    ): ArtifactVersionDetail? =
        jdbc.query("${sql.selectDetail} AND s.id = :id AND v.status = 'DRAFT'", ids(workspaceId, id), detail).singleOrNull()

    /** One version's detail (no body), any status. */
    fun findVersionDetail(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): ArtifactVersionDetail? =
        jdbc
            .query(
                "${sql.selectDetail} AND s.id = :id AND v.version = :version",
                ids(workspaceId, id) + ("version" to version),
                detail,
            ).singleOrNull()

    /** The WORKING version (versioning §7.1): the DRAFT when one exists, else the current version. */
    fun findWorking(
        workspaceId: UUID,
        id: UUID,
    ): ArtifactVersion<B>? = findDraft(workspaceId, id)?.let { findVersion(workspaceId, id, it.version) } ?: findCurrent(workspaceId, id)

    /** Every version's detail, newest first. */
    fun listVersions(
        workspaceId: UUID,
        id: UUID,
    ): List<ArtifactVersionDetail> = jdbc.query("${sql.selectDetail} AND s.id = :id ORDER BY v.version DESC", ids(workspaceId, id), detail)

    /** True while the artifact holds a DRAFT or a RELEASED version — the derived ACTIVE status (versioning §3.2). */
    fun isLive(
        workspaceId: UUID,
        id: UUID,
    ): Boolean = jdbc.queryForObject(sql.isLive, ids(workspaceId, id), Boolean::class.java) == true

    /**
     * Every name holding a DRAFT, across ALL workspaces — the authoring-disabled boot check's evidence (versioning
     * §5.5; `AuthoringStartupCheck` gains the two families in L1b), the parameter-set twin.
     */
    fun findAllDraftNames(): List<String> = jdbc.query(sql.allDraftNames, emptyMap<String, Any>()) { rs, _ -> rs.getString("name") }

    /** One tree level's direct sub-folders — a bounded range scan led by the per-workspace name uniqueness. */
    fun listChildFolders(
        workspaceId: UUID,
        prefix: String?,
        limit: Int = MAX_PAGE_LIMIT,
    ): List<ArtifactFolder> =
        jdbc.query(sql.childFolders, treeParams(workspaceId, prefix) + ("limit" to limit.coerceIn(1, MAX_PAGE_LIMIT + 1))) { rs, _ ->
            val segment = rs.getString("segment")
            ArtifactFolder(if (prefix.isNullOrEmpty()) segment else "$prefix/$segment", segment, rs.getInt("artifact_count"))
        }

    /** One tree level's direct artifacts, at their WORKING-OR-CURRENT listing version (D55). */
    fun listChildren(
        workspaceId: UUID,
        prefix: String?,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): List<ArtifactVersion<B>> = jdbc.query(sql.childArtifacts, treeParams(workspaceId, prefix) + paging(offset, limit), versionMapper)

    /** The truthful total of [listChildren] — the same predicate, no paging. */
    fun countChildren(
        workspaceId: UUID,
        prefix: String?,
    ): Int = checkNotNull(jdbc.queryForObject(sql.countChildren, treeParams(workspaceId, prefix), Int::class.java))

    /** The FLAT listing (#312's shape): every live artifact at its LISTED version, no level clause. */
    fun listAll(
        workspaceId: UUID,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): List<ArtifactVersion<B>> = jdbc.query(sql.listAll, mapOf("workspaceId" to workspaceId) + paging(offset, limit), versionMapper)

    /** The truthful total of [listAll]. */
    fun countAll(workspaceId: UUID): Int =
        checkNotNull(jdbc.queryForObject(sql.countAll, mapOf("workspaceId" to workspaceId), Int::class.java))

    /** Every live artifact that HAS a current RELEASED version — the promoter lens's input (versioning §10.2). */
    fun findCurrentVersions(workspaceId: UUID): List<CurrentArtifactVersion> =
        jdbc.query(sql.currentVersions, mapOf("workspaceId" to workspaceId)) { rs, _ ->
            CurrentArtifactVersion(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                displayName = rs.getString("display_name"),
                version = rs.getInt("version"),
                bodyHash = rs.getString("body_hash"),
            )
        }

    /** The hash the database would store for [body] — the import's recompute guard (versioning §9.2). */
    fun computeBodyHash(body: B): String =
        checkNotNull(jdbc.queryForObject("SELECT ${ArtifactSql.HASH_EXPR}", mapOf("bodyJson" to codec.write(body)), String::class.java))

    // ---- writes ---------------------------------------------------------------------------------------

    /**
     * Creates [id] and its version 1 — DRAFT with a NULL pointer for an authoring create (D55), RELEASED with the
     * pointer at 1 for a seed ([lifecycle] is required: a missed create path is a compile error).
     *
     * @throws DatapipelinesException `*.validation.name_taken` (409) for a name the workspace holds; `*.import.id_taken`
     *   for an id the server holds (C29).
     */
    @Suppress("LongParameterList") // the artifact's identity, its body and the three stamps of a create
    fun create(
        workspaceId: UUID,
        id: UUID,
        name: String,
        body: B,
        actor: UUID,
        lifecycle: CreateLifecycle,
        via: WriteSurface,
    ): ArtifactVersionDetail =
        mappingUniqueViolations(name, id) {
            jdbc
                .query(
                    if (lifecycle == CreateLifecycle.DRAFT) sql.insertDraft else sql.insertReleased,
                    writeParams(workspaceId, id, body, actor) +
                        mapOf("name" to name, "via" to via.wire, "version" to 1, "releasedAt" to null),
                    detail,
                ).single()
        }

    /**
     * Draft create — copy-on-write from the CURRENT RELEASED version (versioning §5.1). Answers the RELEASED detail
     * when the content is identical (the no-op arm); null when the precondition failed.
     */
    @Suppress("LongParameterList") // the draft's identity, body, precondition and two stamps
    fun createDraft(
        workspaceId: UUID,
        id: UUID,
        body: B,
        expectedHash: String,
        actor: UUID,
        via: WriteSurface,
    ): ArtifactVersionDetail? =
        mappingDraftRace(workspaceId, id) {
            jdbc
                .query(
                    sql.createDraft,
                    writeParams(workspaceId, id, body, actor) + mapOf("expectedHash" to expectedHash, "via" to via.wire),
                    detail,
                ).singleOrNull()
        }

    /** Draft write in place (versioning §5.2), hash-preconditioned; null when no DRAFT matched [expectedHash]. */
    @Suppress("LongParameterList") // the draft's identity, body, precondition and two stamps
    fun writeDraft(
        workspaceId: UUID,
        id: UUID,
        body: B,
        expectedHash: String,
        actor: UUID,
        via: WriteSurface,
    ): ArtifactVersionDetail? =
        jdbc
            .query(
                sql.writeDraft,
                writeParams(workspaceId, id, body, actor) + mapOf("expectedHash" to expectedHash, "via" to via.wire),
                detail,
            ).singleOrNull()

    /** Release (versioning §5.3): the DRAFT at [expectedHash] flips, the pointer moves, the index adopts its metadata. */
    fun releaseDraft(
        workspaceId: UUID,
        id: UUID,
        expectedHash: String,
        actor: UUID,
    ): ArtifactVersionDetail? =
        jdbc.query(sql.release, ids(workspaceId, id) + mapOf("expectedHash" to expectedHash, "actor" to actor), detail).singleOrNull()

    /**
     * Purge the DRAFT (versioning §5.4): hard-deleted (the only row that references a version — test evidence —
     * cascades). The sole version takes the entity; a draft that had become the pointer moves it. False when no
     * DRAFT matched.
     */
    fun purgeDraft(
        workspaceId: UUID,
        id: UUID,
        expectedHash: String?,
        draftEligible: Boolean,
    ): Boolean {
        val params = ids(workspaceId, id) + mapOf("expectedHash" to expectedHash, "draftEligible" to draftEligible)
        val purged =
            jdbc.query(sql.purgeDraft(expectedHash != null), params) { rs, _ -> rs.getInt("version") }.singleOrNull() ?: return false
        if (checkNotNull(jdbc.queryForObject(sql.versionCount, ids(workspaceId, id), Int::class.java)) == 0) {
            deleteEntity(workspaceId, id)
            return true
        }
        jdbc.update(sql.pointerFallback, params + ("version" to purged))
        jdbc.update(sql.reindex, params)
        return true
    }

    /** Discard a RELEASED version (versioning §3.1): stamps, pointer fallback when it WAS the pointer. Null when it was not RELEASED. */
    fun discardVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        actor: UUID,
        draftEligible: Boolean,
    ): ArtifactVersionDetail? {
        val params = ids(workspaceId, id) + mapOf("version" to version, "actor" to actor, "draftEligible" to draftEligible)
        return jdbc.query(sql.discard, params, detail).singleOrNull()?.also { jdbc.update(sql.reindex, params) }
    }

    /** Restore a DISCARDED version to RELEASED; the pointer moves only above-current-or-NULL (D60). */
    fun restoreVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): ArtifactVersionDetail? {
        val params = ids(workspaceId, id) + ("version" to version)
        return jdbc.query(sql.restore, params, detail).singleOrNull()?.also { jdbc.update(sql.reindex, params) }
    }

    /** Manual switch (D60): the pointer names [version] when it is live and posture-eligible; null otherwise. */
    fun switchCurrent(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        draftEligible: Boolean,
    ): Int? {
        val params = ids(workspaceId, id) + mapOf("version" to version, "draftEligible" to draftEligible)
        return jdbc
            .query(
                sql.switch,
                params,
            ) { rs, _ -> rs.getInt("current_version") }
            .singleOrNull()
            ?.also { jdbc.update(sql.reindex, params) }
    }

    /** Deletes the index row (versions cascade). True when a row went. */
    fun deleteEntity(
        workspaceId: UUID,
        id: UUID,
    ): Boolean = jdbc.update(sql.deleteEntity, ids(workspaceId, id)) > 0

    /** Import onto a NEW artifact (versioning §9.2): the exported [id] kept, the version RELEASED at its EXACT number. */
    @Suppress("LongParameterList") // the exported identity, the version and the three stamps of an import
    fun importNew(
        workspaceId: UUID,
        id: UUID,
        name: String,
        body: B,
        version: Int,
        releasedAt: Instant?,
        actor: UUID,
    ): ArtifactVersionDetail =
        mappingUniqueViolations(name, id) {
            jdbc
                .query(
                    sql.insertReleased,
                    writeParams(workspaceId, id, body, actor) +
                        mapOf(
                            "name" to name,
                            "via" to WriteSurface.SESSION.wire,
                            "version" to version,
                            "releasedAt" to releasedAt?.let(Timestamp::from),
                        ),
                    detail,
                ).single()
        }

    /** Import onto an EXISTING artifact: RELEASED at [version] (or `max + 1`); the pointer moves ONLY when NULL. Null when taken. */
    @Suppress("LongParameterList") // the target, the version and the three stamps of an import
    fun insertReleased(
        workspaceId: UUID,
        id: UUID,
        body: B,
        version: Int?,
        releasedAt: Instant?,
        actor: UUID,
    ): ArtifactVersionDetail? {
        val params =
            writeParams(workspaceId, id, body, actor) + mapOf("version" to version, "releasedAt" to releasedAt?.let(Timestamp::from))
        return jdbc.query(sql.insertReleasedVersion, params, detail).singleOrNull()?.also { jdbc.update(sql.reindex, params) }
    }

    /** The §4.2 conflict `details`: the current hash, status and author — what the caller rebases on. */
    fun conflictDetails(current: ArtifactVersionDetail?): Map<String, Any?> =
        mapOf(
            "current_body_hash" to (current?.bodyHash ?: ""),
            "current_status" to (current?.status?.name ?: "UNKNOWN"),
            "updated_by" to (current?.updatedBy?.toString() ?: ""),
            "updated_at" to (current?.updatedAt?.toString() ?: ""),
        )

    // ---- plumbing -------------------------------------------------------------------------------------

    private fun ids(
        workspaceId: UUID,
        id: UUID,
    ): Map<String, Any?> = mapOf("id" to id, "workspaceId" to workspaceId)

    private fun paging(
        offset: Int,
        limit: Int,
    ): Map<String, Any?> = mapOf("limit" to limit.coerceIn(1, MAX_PAGE_LIMIT + 1), "offset" to maxOf(0, offset))

    private fun writeParams(
        workspaceId: UUID,
        id: UUID,
        body: B,
        actor: UUID,
    ): Map<String, Any?> =
        ids(workspaceId, id) +
            mapOf(
                "bodyJson" to codec.write(body),
                "displayName" to codec.displayName(body),
                "description" to codec.description(body).orEmpty(),
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

    /** The name UNIQUE → `name_taken` (409); the id PK → `import.id_taken` (C29) — the constraint is the only atomic authority. */
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
                kind.nameConstraint in cause -> {
                    throw DatapipelinesException(
                        codes.nameTaken,
                        "A ${kind.noun} named '${name.safeEcho()}' already exists in this workspace.",
                        mapOf("name" to name.safeEcho()),
                        e,
                    )
                }

                kind.idConstraint in cause -> {
                    throw DatapipelinesException(
                        codes.idTaken,
                        "A ${kind.noun} with id $id already exists on this server — ids are never re-issued (C29).",
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
     * The first-writer race → `version.conflict` carrying the WINNER's state (versioning §3.3). Both writers allocate
     * `max(version) + 1`, so the loser usually collides on the version PRIMARY KEY before the one-draft index — either
     * constraint is the same race (the parameter-set forced race's finding).
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
            if (kind.draftIndex !in cause && kind.versionKey !in cause) throw e
            throw DatapipelinesException(
                codes.versionConflict,
                "The ${kind.noun} was modified by someone else after you loaded it.",
                conflictDetails(findDraft(workspaceId, id)),
                e,
            )
        }

    private val record =
        RowMapper { rs: ResultSet, _: Int ->
            ArtifactRecord(
                id = rs.getObject("id", UUID::class.java),
                workspaceId = rs.getObject("workspace_id", UUID::class.java),
                name = rs.getString("name"),
                displayName = rs.getString("display_name"),
                description = rs.getString("description"),
                currentVersion = rs.getObject("current_version") as Int?,
                createdAt = checkNotNull(rs.instant("created_at")),
                updatedAt = checkNotNull(rs.instant("updated_at")),
                createdBy = rs.getObject("created_by", UUID::class.java),
            )
        }

    private val detail = RowMapper { rs: ResultSet, _: Int -> detailOf(rs) }

    private val versionMapper =
        RowMapper { rs: ResultSet, _: Int ->
            ArtifactVersion(
                record =
                    ArtifactRecord(
                        id = rs.getObject("id", UUID::class.java),
                        workspaceId = rs.getObject("workspace_id", UUID::class.java),
                        name = rs.getString("name"),
                        displayName = rs.getString("display_name"),
                        description = rs.getString("description"),
                        currentVersion = rs.getObject("current_version") as Int?,
                        createdAt = checkNotNull(rs.instant("set_created_at")),
                        updatedAt = checkNotNull(rs.instant("set_updated_at")),
                        createdBy = rs.getObject("set_created_by", UUID::class.java),
                    ),
                detail = detailOf(rs),
                body = codec.read(rs.getString("body_json")),
            )
        }

    private fun detailOf(rs: ResultSet) =
        ArtifactVersionDetail(
            artifactId = rs.getObject("artifact_id", UUID::class.java),
            version = rs.getInt("version"),
            status = PipelineVersionStatus.fromWire(rs.getString("status")),
            bodyHash = rs.getString("body_hash"),
            createdAt = checkNotNull(rs.instant("created_at")),
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

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    companion object {
        const val DEFAULT_PAGE_LIMIT = 50
        const val MAX_PAGE_LIMIT = 200

        private fun escapeLike(term: String): String = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

/** `visualizations` / `visualization_versions` (V42) — and the [VisualizationPins] the dashboard validator reads. */
class VisualizationRepository(
    jdbc: NamedParameterJdbcTemplate,
) : ArtifactRepository<VisualizationBody>(jdbc, ArtifactKind.VISUALIZATION, BodyCodec.Visualization) {
    /** The pinned visualization version a dashboard names, by name within the workspace; null when absent. */
    val pins: VisualizationPins =
        VisualizationPins { workspaceId, ref ->
            findRecordByName(workspaceId, ref.name)?.let { record ->
                findVersion(workspaceId, record.id, ref.version)?.let { PinnedVisualization(it.detail.status, it.body) }
            }
        }
}

/** `dashboards` / `dashboard_versions` (V42) — and the pin scan the visualization lifecycle's purge and discard guards read. */
class DashboardRepository(
    jdbc: NamedParameterJdbcTemplate,
) : ArtifactRepository<DashboardBody>(jdbc, ArtifactKind.DASHBOARD, BodyCodec.Dashboard) {
    /**
     * The LIVE (DRAFT or RELEASED) dashboard versions that pin visualization [name] — at [version], or at any
     * version when null — as `name@version` (the design record §4.2: purge and discard guards keep referenced
     * releases). A JSONB containment probe over `body_json -> 'visualizations'`, built as a bound JSON value.
     */
    fun livePinsOf(
        workspaceId: UUID,
        name: String,
        version: Int?,
    ): List<String> {
        val pin = ArtifactJson.mapper.createObjectNode().put("name", name)
        version?.let { pin.put("version", it) }
        val probe = ArtifactJson.mapper.createArrayNode().add(ArtifactJson.mapper.createObjectNode().set<JsonNode>("visualization", pin))
        return jdbc.query(
            """
            SELECT s.name || '@' || v.version AS pinned_by
              FROM dashboard_versions v JOIN dashboards s ON s.id = v.dashboard_id
             WHERE s.workspace_id = :workspaceId AND v.status IN ('DRAFT', 'RELEASED')
               AND v.body_json -> 'visualizations' @> CAST(:probe AS jsonb)
             ORDER BY 1
            """.trimIndent(),
            mapOf("workspaceId" to workspaceId, "probe" to ArtifactJson.mapper.writeValueAsString(probe)),
        ) { rs, _ -> rs.getString("pinned_by") }
    }

    /**
     * [livePinsOf] for MANY visualization names in ONE statement (#331): every LIVE version's pin
     * occurrences naming one of [names], as `pinned visualization name -> pinning `name@version``.
     * The names are a bind list (`IN`), never concatenated; the DISTINCT collapses a dashboard that
     * places the same pin twice — the containment probe answered it once, so does this.
     */
    fun livePinsOfAll(
        workspaceId: UUID,
        names: Collection<String>,
    ): Map<String, List<String>> {
        if (names.isEmpty()) return emptyMap()
        val rows =
            jdbc.query(
                """
                SELECT DISTINCT pin -> 'visualization' ->> 'name' AS pinned_name,
                       s.name || '@' || v.version AS pinned_by
                  FROM dashboard_versions v JOIN dashboards s ON s.id = v.dashboard_id
                 CROSS JOIN LATERAL jsonb_array_elements(v.body_json -> 'visualizations') pin
                 WHERE s.workspace_id = :workspaceId AND v.status IN ('DRAFT', 'RELEASED')
                   AND pin -> 'visualization' ->> 'name' IN (:names)
                 ORDER BY 2
                """.trimIndent(),
                mapOf("workspaceId" to workspaceId, "names" to names),
            ) { rs, _ -> rs.getString("pinned_name") to rs.getString("pinned_by") }
        return rows.groupBy({ it.first }, { it.second })
    }

    /**
     * The page's pins under a NARROWING lens' source query (#331): the current RELEASED dashboards'
     * pin occurrences naming one of [names], as plain rows — the LENS filters the dashboard names
     * afterwards (in memory, the house `through` shape), so the statement count stays ONE whatever
     * the lens admits.
     */
    fun currentPinsOfAll(
        workspaceId: UUID,
        names: Collection<String>,
    ): List<CurrentPinRow> {
        if (names.isEmpty()) return emptyList()
        return jdbc.query(
            """
            SELECT DISTINCT s.name AS dashboard_name, v.version AS dashboard_version,
                   pin -> 'visualization' ->> 'name' AS pinned_name
              FROM dashboards s
              JOIN dashboard_versions v ON v.dashboard_id = s.id AND v.version = s.current_version
              CROSS JOIN LATERAL jsonb_array_elements(v.body_json -> 'visualizations') pin
             WHERE s.workspace_id = :workspaceId AND v.status = 'RELEASED'
               AND pin -> 'visualization' ->> 'name' IN (:names)
            """.trimIndent(),
            mapOf("workspaceId" to workspaceId, "names" to names),
        ) { rs, _ ->
            CurrentPinRow(rs.getString("dashboard_name"), rs.getInt("dashboard_version"), rs.getString("pinned_name"))
        }
    }

    /**
     * The current RELEASED dashboards' promotion projection (#330), ONE statement: the identity
     * [CurrentArtifactVersion] carries plus the two NAME lists the lens derivation reads out of the
     * body — the pinned visualization names and the source pipeline names. The body itself is never
     * loaded; the JSON paths are read inside the statement and parsed off the returned jsonb text.
     */
    fun findCurrentPinsAndSources(workspaceId: UUID): List<DashboardCurrentPins> =
        jdbc.query(
            """
            SELECT s.id, s.name, s.display_name, v.version, v.body_hash,
                   COALESCE(v.body_json -> 'sources', '[]'::jsonb)::TEXT AS sources,
                   COALESCE(v.body_json -> 'visualizations', '[]'::jsonb)::TEXT AS pins
              FROM dashboards s
              JOIN dashboard_versions v ON v.dashboard_id = s.id AND v.version = s.current_version
             WHERE s.workspace_id = :workspaceId AND v.status = 'RELEASED'
             ORDER BY s.name
            """.trimIndent(),
            mapOf("workspaceId" to workspaceId),
        ) { rs, _ ->
            DashboardCurrentPins(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                displayName = rs.getString("display_name"),
                version = rs.getInt("version"),
                bodyHash = rs.getString("body_hash"),
                sourcePipelineNames = namesOf(rs.getString("sources")) { it.path("pipeline").path("name") },
                pinnedVisualizationNames = namesOf(rs.getString("pins")) { it.path("visualization").path("name") },
            )
        }

    /** The `name` strings of [json]'s array elements, read at [at]; a malformed stored array reads as none. */
    private fun namesOf(
        json: String,
        at: (JsonNode) -> JsonNode,
    ): List<String> =
        runCatching { ArtifactJson.mapper.readTree(json) }
            .getOrNull()
            ?.takeIf(JsonNode::isArray)
            ?.mapNotNull { element -> at(element).asText(null) }
            ?: emptyList()

    /** One [currentPinsOfAll] row: the pinning dashboard by name and CURRENT version, and the pin it holds. */
    data class CurrentPinRow(
        val dashboardName: String,
        val dashboardVersion: Int,
        val pinnedName: String,
    )
}

/**
 * One current RELEASED dashboard's promotion projection (#330) — the identity fields of
 * [CurrentArtifactVersion] plus the two name lists the promoter-lens derivation reads out of the
 * body: which pipelines it sources and which visualizations it pins. The body is NOT loaded.
 */
data class DashboardCurrentPins(
    val id: UUID,
    val name: String,
    val displayName: String,
    val version: Int,
    val bodyHash: String,
    val sourcePipelineNames: List<String>,
    val pinnedVisualizationNames: List<String>,
) {
    /** The identity half, for the promotion page's dashboard rows (the shape `currentVersions` answered). */
    fun toCurrentVersion(): CurrentArtifactVersion =
        CurrentArtifactVersion(id = id, name = name, displayName = displayName, version = version, bodyHash = bodyHash)
}
