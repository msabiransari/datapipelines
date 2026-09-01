package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** What a draft discard did (versioning §5.4) — both are a success to the caller. */
sealed interface DiscardOutcome {
    /** A never-executed draft: the row is gone and the version number returns to the pool. */
    data object Deleted : DiscardOutcome

    /** An executed draft: the FK blocks the delete, so the row flipped to DISCARDED. */
    data class FlippedToDiscarded(
        val detail: PipelineVersionDetail,
    ) : DiscardOutcome
}

/**
 * Persistence for `pipelines` and `pipeline_versions` (metadata-db §4.4/§4.5/§6,
 * versioning §5/§9).
 *
 * `NamedParameterJdbcTemplate` exclusively — no JPA, no Hibernate, no Exposed
 * (module-structure §8.1): every query is explicit SQL, so there is no generated SQL to
 * reverse-engineer and no lazy-loading trap. The repository lives in the module that owns
 * the entity (§3.1 persistence-ownership rule 1); the `DataSource` bean is app-level and
 * schema creation belongs to `app`'s Flyway alone (rule 2) — nothing here creates or alters
 * a table.
 *
 * ## The version lifecycle (versioning §3.1, since V6)
 *
 * The immutability discipline of metadata-db §4.5, amended: **RELEASED and DISCARDED rows
 * are never UPDATEd; DRAFT rows may be; only the DB predicate `status = 'DRAFT'` permits
 * mutation.** One bounded, checkable exception replaces "append-only forever":
 *
 *  - [create] lands version 1 directly as RELEASED (§3.2: creation is not modification —
 *    an agent's first create is executable the moment it exists).
 *  - [createDraft] copies the current released version to a DRAFT (copy-on-write, §5.1) —
 *    the partial unique index `uq_pipeline_versions_one_draft` makes two simultaneous
 *    first-writers race-safe: the loser violates the index and surfaces as
 *    `pipeline.version.conflict` carrying the winner's hash.
 *  - [writeDraft] overwrites the DRAFT in place (§5.2). There is no third write branch.
 *  - [releaseDraft] flips the DRAFT to RELEASED and moves `pipelines.current_version`
 *    (§5.3) — the only writer of that column besides create/import. `current_version`
 *    keeps its meaning, **the latest RELEASED version**; it does not move while a draft
 *    exists (§3.4), so every existing reader (execute-default, editor load, MCP get, the
 *    datasource joins) keeps its semantics.
 *  - [discardDraft] deletes a never-executed draft, or flips an executed one to DISCARDED
 *    (§5.4) — the `pipeline_executions` composite FK is what decides which.
 *
 * Every mutation carries its caller's content-hash precondition in the statement's own
 * `WHERE` clause (§4.2): zero rows affected ⇒ stale base ⇒ the caller maps the 409. Each
 * mutating method is a single statement (data-modifying CTE where two tables move
 * together), atomic without an enclosing transaction — `@Transactional` belongs on the
 * service layer (metadata-db §6.3), and a repository that quietly depends on a caller
 * remembering to open one is a repository with a corruption path.
 *
 * ## `body_hash` — one expression everywhere
 *
 * The canonical body is the database's JSONB text projection, hashed
 * `encode(sha256(convert_to(<jsonb>::text, 'UTF8')), 'hex')` BY THE DATABASE. The backfill (V6) and
 * every write statement below share that one expression, so a body's hash cannot differ
 * between the writer and the reader (versioning §4.1's canonicalization rule, spelled
 * mechanically; a JSONB column does not preserve the writer's key order, so the
 * serializer string itself would be a drifting anchor).
 *
 * ## Workspace scoping (slice 2)
 *
 * Since V4 every pipeline belongs to a workspace and `name` is unique per workspace
 * (metadata-db §4.4). Every method below takes the active workspace explicitly as
 * `workspaceId` — **no default anywhere** (design §5: all authored-content operations are
 * scoped to the request's resolved workspace). A missing caller is a compile error, never
 * a silent resolution in some default world.
 *
 * ## `updated_at`
 *
 * metadata-db §2: there are no triggers in this schema; every `UPDATE` on `pipelines`
 * sets `updated_at = NOW()` in its own `SET` clause. `pipeline_versions.updated_at` (V6)
 * belongs to the DRAFT write path only — a release or discard flip does not restamp it
 * (versioning §11: the column is draft-write metadata, not a row-modification clock).
 *
 * `TooManyFunctions` is suppressed because the version-lifecycle round made this class the
 * single owner of every `pipeline_versions` statement — lifecycle reads, the four write
 * paths, and both import modes — and splitting it would scatter one table's invariants
 * across files (the `DatasourceRepository` precedent for the same shape).
 */
@Suppress("TooManyFunctions") // see the KDoc above
class PipelineRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** The pipeline's metadata row, or null when it does not exist, is soft-deleted, or lives in another workspace. */
    fun findById(
        workspaceId: UUID,
        id: UUID,
    ): PipelineRecord? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE id = :id AND workspace_id = :workspaceId AND is_deleted = FALSE",
                mapOf("id" to id, "workspaceId" to workspaceId),
                MAPPER,
            ).singleOrNull()

    /** As [findById], by machine name — the identifier MCP and cross-pipeline references use (§3.2). */
    fun findByName(
        workspaceId: UUID,
        name: String,
    ): PipelineRecord? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE name = :name AND workspace_id = :workspaceId AND is_deleted = FALSE",
                mapOf("name" to name, "workspaceId" to workspaceId),
                MAPPER,
            ).singleOrNull()

    /**
     * As [findByName], but **including soft-deleted rows** — the read composition needs.
     *
     * Soft-delete does not affect existing pinned references (design
     * 2026-08-13-pipeline-node-type D7, mirroring templates): a saved PIPELINE node keeps
     * resolving its pinned child after the child is deleted. The save-time resolver reads the
     * row's `isDeleted` flag to block only NEW references, and the runtime runner reads the
     * pinned body either way. Callers that list or look up live pipelines use [findByName].
     */
    fun findByNameIncludingDeleted(
        workspaceId: UUID,
        name: String,
    ): PipelineRecord? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE name = :name AND workspace_id = :workspaceId",
                mapOf("name" to name, "workspaceId" to workspaceId),
                MAPPER,
            ).singleOrNull()

    /** Every live pipeline in the workspace, newest first; optionally narrowed to one owner (§14 `GET /pipelines`). */
    fun findAll(
        workspaceId: UUID,
        ownerId: UUID? = null,
        limit: Int? = null,
        offset: Int = 0,
    ): List<PipelineRecord> {
        val ownerFilter = if (ownerId == null) "" else " AND owner_id = :ownerId"
        val limitClause = if (limit != null) " LIMIT :limit OFFSET :offset" else ""
        val params = mutableMapOf<String, Any?>("workspaceId" to workspaceId)
        if (ownerId != null) params["ownerId"] = ownerId
        if (limit != null) {
            params["limit"] = limit
            params["offset"] = offset
        }
        return jdbc.query(
            "$SELECT_COLUMNS WHERE is_deleted = FALSE AND workspace_id = :workspaceId$ownerFilter" +
                " ORDER BY created_at DESC$limitClause",
            params,
            MAPPER,
        )
    }

    /** Live pipelines in the workspace whose current body references [datasourceName] in any node; newest first. */
    fun findAllByDatasource(
        workspaceId: UUID,
        datasourceName: String,
        ownerId: UUID? = null,
        limit: Int? = null,
        offset: Int = 0,
    ): List<PipelineRecord> {
        val ownerFilter = if (ownerId == null) "" else " AND p.owner_id = :ownerId"
        val limitClause = if (limit == null) "" else " LIMIT :limit OFFSET :offset"
        val params = mutableMapOf<String, Any?>()
        params["datasourceName"] = datasourceName
        params["workspaceId"] = workspaceId
        if (ownerId != null) params["ownerId"] = ownerId
        if (limit != null) {
            params["limit"] = limit
            params["offset"] = offset
        }
        return jdbc.query(
            """
            SELECT p.*
              FROM pipelines p
              JOIN pipeline_versions v ON v.pipeline_id = p.id AND v.version = p.current_version
             WHERE p.is_deleted = FALSE AND p.workspace_id = :workspaceId$ownerFilter
               AND (v.body_json @> jsonb_build_object('nodes', jsonb_build_array(jsonb_build_object('source', :datasourceName)))
                    OR v.body_json @> jsonb_build_object('nodes', jsonb_build_array(jsonb_build_object('output', jsonb_build_object('datasource', :datasourceName)))))
             ORDER BY p.created_at DESC
             $limitClause
            """.trimIndent(),
            params,
            MAPPER,
        )
    }

    /** Count of live pipelines in the workspace. */
    fun countAll(workspaceId: UUID): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM pipelines WHERE is_deleted = FALSE AND workspace_id = :workspaceId",
                mapOf("workspaceId" to workspaceId),
                Int::class.java,
            ),
        )

    /**
     * The stored body JSON of one version, or null when that version does not exist or the
     * pipeline lives in another workspace.
     *
     * Read as `body_json::TEXT` and deserialized by Jackson (metadata-db §6.2): binding a
     * `JsonNode` or a `PGobject` directly would move serialization into the driver and make
     * the code Postgres-specific for no gain.
     */
    fun findVersionBody(
        workspaceId: UUID,
        pipelineId: UUID,
        version: Int,
    ): String? =
        jdbc
            .query(
                """
                SELECT v.body_json::TEXT AS body_json
                  FROM pipeline_versions v
                  JOIN pipelines p ON p.id = v.pipeline_id
                 WHERE v.pipeline_id = :pipelineId AND v.version = :version AND p.workspace_id = :workspaceId
                """.trimIndent(),
                mapOf("pipelineId" to pipelineId, "version" to version, "workspaceId" to workspaceId),
            ) { rs, _ -> rs.getString("body_json") }
            .singleOrNull()

    /** The body of the pipeline's current version — `GET /pipelines/{id}` (§14). */
    fun findLatestBody(
        workspaceId: UUID,
        pipelineId: UUID,
    ): String? = findById(workspaceId, pipelineId)?.let { findVersionBody(workspaceId, it.id, it.currentVersion) }

    /** Version metadata, newest first — `GET /pipelines/{id}/versions` (§14; lifecycle fields since V6). */
    fun listVersions(
        workspaceId: UUID,
        pipelineId: UUID,
    ): List<PipelineVersionRecord> =
        jdbc.query(
            """
            SELECT v.pipeline_id, v.version, v.status, v.body_hash, v.created_at, v.created_by, v.released_at
              FROM pipeline_versions v
              JOIN pipelines p ON p.id = v.pipeline_id
             WHERE v.pipeline_id = :pipelineId AND p.workspace_id = :workspaceId
             ORDER BY v.version DESC
            """.trimIndent(),
            mapOf("pipelineId" to pipelineId, "workspaceId" to workspaceId),
        ) { rs, _ ->
            PipelineVersionRecord(
                pipelineId = rs.getObject("pipeline_id", UUID::class.java),
                version = rs.getInt("version"),
                status = PipelineVersionStatus.fromWire(rs.getString("status")),
                bodyHash = rs.getString("body_hash"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java),
                releasedAt = rs.getObject("released_at", OffsetDateTime::class.java)?.toInstant(),
            )
        }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle reads (versioning §4/§7)
    // ---------------------------------------------------------------------------------------------

    /** One version's lifecycle detail, or null when it does not exist or lives in another workspace. */
    fun findVersionDetail(
        workspaceId: UUID,
        pipelineId: UUID,
        version: Int,
    ): PipelineVersionDetail? =
        jdbc
            .query(
                DETAIL_WHERE + " AND v.pipeline_id = :pipelineId AND v.version = :version",
                mapOf("pipelineId" to pipelineId, "version" to version, "workspaceId" to workspaceId),
                DETAIL_MAPPER,
            ).singleOrNull()

    /** The version `pipelines.current_version` names — by invariant the latest RELEASED version. */
    fun findCurrentVersionDetail(
        workspaceId: UUID,
        pipelineId: UUID,
    ): PipelineVersionDetail? =
        jdbc
            .query(
                DETAIL_WHERE + " AND v.pipeline_id = :pipelineId AND v.version = p.current_version",
                mapOf("pipelineId" to pipelineId, "workspaceId" to workspaceId),
                DETAIL_MAPPER,
            ).singleOrNull()

    /** The pipeline's DRAFT, or null when none exists — the draft pointer of §7's read shape. */
    fun findDraftDetail(
        workspaceId: UUID,
        pipelineId: UUID,
    ): PipelineVersionDetail? =
        jdbc
            .query(
                DETAIL_WHERE + " AND v.pipeline_id = :pipelineId AND v.status = 'DRAFT'",
                mapOf("pipelineId" to pipelineId, "workspaceId" to workspaceId),
                DETAIL_MAPPER,
            ).singleOrNull()

    /** The DRAFT detail of each of [pipelineIds] that has one — the list screens' pending-release badge (§7). */
    fun findDrafts(
        workspaceId: UUID,
        pipelineIds: Collection<UUID>,
    ): Map<UUID, PipelineVersionDetail> {
        if (pipelineIds.isEmpty()) return emptyMap()
        return jdbc
            .query(
                DETAIL_WHERE +
                    " AND v.status = 'DRAFT' AND v.pipeline_id IN (:pipelineIds)",
                mapOf("pipelineIds" to pipelineIds, "workspaceId" to workspaceId),
                DETAIL_MAPPER,
            ).associateBy { it.pipelineId }
    }

    /**
     * Every pipeline name holding a DRAFT, across ALL workspaces (soft-deleted parents
     * included — a draft under a deleted pipeline still blocks a same-number promotion
     * import, §9.2). The authoring-disabled boot check's evidence (versioning §5.5).
     */
    fun findAllDraftPipelineNames(): List<String> =
        jdbc
            .query(
                """
                SELECT p.name
                  FROM pipeline_versions v
                  JOIN pipelines p ON p.id = v.pipeline_id
                 WHERE v.status = 'DRAFT'
                 ORDER BY p.name
                """.trimIndent(),
                emptyMap<String, Any>(),
            ) { rs, _ -> rs.getString("name") }

    /**
     * `released_at` for each `(pipelineId, version)` pair — §8's draft-run derivation, read
     * for the execution history's informational draft markers. Null values are kept: a
     * version with no `released_at` (still DRAFT, or DISCARDED) makes every execution of it
     * a draft run.
     */
    fun releasedAtFor(
        workspaceId: UUID,
        versions: Collection<Pair<UUID, Int>>,
    ): Map<Pair<UUID, Int>, Instant?> {
        if (versions.isEmpty()) return emptyMap()
        val params = mutableMapOf<String, Any>("workspaceId" to workspaceId)
        val disjunction =
            versions
                .mapIndexed { index, (pipelineId, version) ->
                    params["p$index"] = pipelineId
                    params["v$index"] = version
                    "(v.pipeline_id = :p$index AND v.version = :v$index)"
                }.joinToString(" OR ")
        return jdbc
            .query(
                """
                SELECT v.pipeline_id, v.version, v.released_at
                  FROM pipeline_versions v
                  JOIN pipelines p ON p.id = v.pipeline_id
                 WHERE p.workspace_id = :workspaceId AND ($disjunction)
                """.trimIndent(),
                params,
            ) { rs, _ ->
                rs.getObject("pipeline_id", UUID::class.java) to rs.getInt("version") to
                    rs.getObject("released_at", OffsetDateTime::class.java)?.toInstant()
            }.toMap()
    }

    /** §3.5's draft-write-time name check: is [name] taken by a DIFFERENT pipeline in the workspace? */
    fun nameTakenByAnother(
        workspaceId: UUID,
        name: String,
        excludePipelineId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM pipelines
                 WHERE name = :name AND workspace_id = :workspaceId AND id <> :excludePipelineId
            )
            """.trimIndent(),
            mapOf("name" to name, "workspaceId" to workspaceId, "excludePipelineId" to excludePipelineId),
            Boolean::class.java,
        ) == true

    /**
     * The content hash of a candidate body, computed by the SAME database expression every
     * write and the V6 backfill use — what §9.2's import hash-recompute guard reads. Never
     * recomputed in Kotlin: a second implementation of the canonical form is where the
     * writer's and the reader's hashes would silently diverge.
     */
    fun computeBodyHash(bodyJson: String): String =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT $BODY_HASH_EXPR",
                mapOf("bodyJson" to bodyJson),
                String::class.java,
            ),
        )

    // ---------------------------------------------------------------------------------------------
    // Writes
    // ---------------------------------------------------------------------------------------------

    /**
     * Inserts the pipeline and its version 1 — RELEASED on creation (§3.2) — returning the
     * row the database actually stored.
     *
     * The final `SELECT` reads back server-generated `created_at` / `updated_at` rather than
     * asserting what they should be — a hand-built return value is how a default, trigger or
     * CHECK becomes invisible to the caller (metadata-db §6.1's "why this shape").
     */
    fun create(
        workspaceId: UUID,
        pipeline: NewPipeline,
        bodyJson: String,
        createdBy: UUID,
    ): PipelineRecord =
        mappingDuplicateName(pipeline.name) {
            jdbc
                .query(
                    INSERT_PIPELINE_SQL,
                    mapOf(
                        "id" to pipeline.id,
                        "name" to pipeline.name,
                        "displayName" to pipeline.displayName,
                        "description" to pipeline.description,
                        "ownerId" to pipeline.ownerId,
                        "workspaceId" to workspaceId,
                        "bodyJson" to bodyJson,
                        "createdBy" to createdBy,
                    ),
                    MAPPER,
                ).single()
        }

    /**
     * Draft create — the copy-on-write first write after a release (versioning §5.1).
     *
     * The guard CTE requires the caller's [expectedHash] to equal the current RELEASED
     * version's stored hash; the `NOT EXISTS` and the partial unique index keep the insert
     * single even under a concurrent first-write. Returns null when the guard found nothing
     * (stale base, no released version, unknown pipeline) — the caller re-reads to build the
     * 409. A loser of the index race gets `pipeline.version.conflict` carrying the WINNER's
     * draft hash, thrown here because the constraint violation already names the outcome.
     *
     * ## A no-op write returns the RELEASED state, not a draft (versioning §5.1)
     *
     * The draft insert is suppressed when the incoming body's hash **equals** the released
     * body's — the comparison is hash-to-hash, IN the statement, by the same
     * [BODY_HASH_EXPR] the INSERT would store. A returned detail with
     * `status = RELEASED` is therefore the no-op signal: nothing was written, no version
     * number was consumed, and the row returned is the current released version — the
     * caller shows it as the current state rather than inferring the no-op from an absence.
     * Only a body that genuinely differs opens a draft, which is what makes draft-existence
     * a truthful "unreleased changes exist" signal (versioning §7).
     */
    fun createDraft(
        workspaceId: UUID,
        pipelineId: UUID,
        bodyJson: String,
        expectedHash: String,
        actor: UUID,
    ): PipelineVersionDetail? =
        mappingDraftRace(pipelineId) {
            jdbc
                .query(
                    CREATE_DRAFT_SQL,
                    mapOf(
                        "pipelineId" to pipelineId,
                        "workspaceId" to workspaceId,
                        "bodyJson" to bodyJson,
                        "expectedHash" to expectedHash,
                        "actor" to actor,
                    ),
                    DETAIL_MAPPER,
                ).singleOrNull()
        }

    /**
     * Draft write — in-place overwrite of the DRAFT (versioning §5.2). There is no third
     * write branch: a PUT never appends a released version and never touches a RELEASED or
     * DISCARDED row.
     *
     * A draft edited back to content identical to its RELEASED parent is **left alone** —
     * this method writes it in place (same content, restamped) and nothing auto-discards
     * it. Silently deleting a draft row, its version number and its `updated_by` history
     * because someone reverted would be surprising; discard stays explicit (§5.4). Do not
     * "fix" this.
     *
     * Returns null when no DRAFT matched the [expectedHash] — stale base or no draft; the
     * caller re-reads and maps the 409 (or takes the create branch when no draft exists).
     */
    fun writeDraft(
        workspaceId: UUID,
        pipelineId: UUID,
        bodyJson: String,
        expectedHash: String,
        actor: UUID,
    ): PipelineVersionDetail? =
        jdbc
            .query(
                WRITE_DRAFT_SQL,
                mapOf(
                    "pipelineId" to pipelineId,
                    "workspaceId" to workspaceId,
                    "bodyJson" to bodyJson,
                    "expectedHash" to expectedHash,
                    "actor" to actor,
                ),
                DETAIL_MAPPER,
            ).singleOrNull()

    /** The pipeline record and released detail [releaseDraft] produced. */
    data class Released(
        val record: PipelineRecord,
        val version: PipelineVersionDetail,
    )

    /**
     * Release — one statement, three effects (versioning §5.3): the DRAFT flips to RELEASED
     * with a database-generated `released_at` (§8's precondition: at most one of the two
     * timestamps in the draft-run comparison may span a clock), `pipelines.current_version`
     * moves to it, and the index row's metadata (name/display_name/description) adopts the
     * released body's values — §3.5: metadata rides the release.
     *
     * Returns null when no DRAFT matched [expectedHash]. The `pipelines` name constraint
     * stays the authority on uniqueness and maps to `pipeline.validation.duplicate_name` —
     * the draft-write-time check (§3.5) is the courtesy, this is the backstop.
     */
    fun releaseDraft(
        workspaceId: UUID,
        pipelineId: UUID,
        name: String,
        displayName: String,
        description: String,
        expectedHash: String,
        actor: UUID,
    ): Released? =
        mappingDuplicateName(name) {
            jdbc
                .query(
                    RELEASE_DRAFT_SQL,
                    mapOf(
                        "pipelineId" to pipelineId,
                        "workspaceId" to workspaceId,
                        "name" to name,
                        "displayName" to displayName,
                        "description" to description,
                        "expectedHash" to expectedHash,
                        "actor" to actor,
                    ),
                    RELEASE_MAPPER,
                ).singleOrNull()
        }

    /**
     * Discard (versioning §5.4): delete a never-executed draft, or — when the
     * `pipeline_executions` composite FK blocks the delete — flip it to DISCARDED. Both
     * outcomes are transparent to the caller. Null when no DRAFT matched [expectedHash].
     */
    fun discardDraft(
        workspaceId: UUID,
        pipelineId: UUID,
        expectedHash: String,
    ): DiscardOutcome? {
        val params =
            mapOf(
                "pipelineId" to pipelineId,
                "workspaceId" to workspaceId,
                "expectedHash" to expectedHash,
            )
        val deleted =
            try {
                jdbc.update(DELETE_DRAFT_SQL, params) > 0
            } catch (
                @Suppress("SwallowedException") e: DataIntegrityViolationException,
            ) {
                // Swallowed DELIBERATELY: the violation IS the answer — the draft was
                // executed, the FK blocks the delete, and §5.4's second branch takes over
                // (the StagingFactory precedent: the catch is control flow, not loss).
                false
            }
        if (deleted) return DiscardOutcome.Deleted
        return jdbc
            .query(FLIP_DRAFT_SQL, params, DETAIL_MAPPER)
            .singleOrNull()
            ?.let(DiscardOutcome::FlippedToDiscarded)
    }

    /**
     * Appends the next version directly as RELEASED and bumps `current_version`, with the
     * index row adopting the body's metadata — the version-LESS import path (§9.2: "when
     * absent, today's allocate-next-local behavior applies") and the seeder. Not the PUT
     * path: HTTP and MCP writes go through [createDraft]/[writeDraft].
     *
     * Returns null when no live pipeline has this id in [workspaceId]; the caller decides
     * whether that is a 404.
     */
    fun appendReleasedVersion(
        workspaceId: UUID,
        id: UUID,
        pipeline: Pipeline,
        bodyJson: String,
        actor: UUID,
    ): PipelineRecord? =
        mappingDuplicateName(pipeline.name) {
            jdbc
                .query(
                    APPEND_RELEASED_SQL,
                    mapOf(
                        "id" to id,
                        "workspaceId" to workspaceId,
                        "name" to pipeline.name,
                        "displayName" to pipeline.displayName,
                        "description" to pipeline.description,
                        "bodyJson" to bodyJson,
                        "actor" to actor,
                    ),
                    MAPPER,
                ).singleOrNull()
        }

    /**
     * Preserved-version import onto a NEW pipeline (§9.2, first row): the pipeline row and
     * its version at the payload's EXACT version land together as RELEASED, `released_at`
     * from the source, `body_hash` the source declared (the caller has already recomputed
     * it from the payload body). Gaps below [version] are expected and harmless.
     *
     * A name or id collision raises the constraint violation for the caller to classify.
     */
    fun importPipelineVersion(
        workspaceId: UUID,
        pipeline: NewPipeline,
        version: Int,
        bodyJson: String,
        bodyHash: String,
        releasedAt: Instant?,
        actor: UUID,
    ): PipelineRecord =
        mappingDuplicateName(pipeline.name) {
            jdbc
                .query(
                    IMPORT_NEW_PIPELINE_SQL,
                    mapOf(
                        "id" to pipeline.id,
                        "name" to pipeline.name,
                        "displayName" to pipeline.displayName,
                        "description" to pipeline.description,
                        "ownerId" to pipeline.ownerId,
                        "workspaceId" to workspaceId,
                        "version" to version,
                        "bodyJson" to bodyJson,
                        "bodyHash" to bodyHash,
                        "releasedAt" to releasedAt?.let(Timestamp::from),
                        "actor" to actor,
                    ),
                    MAPPER,
                ).single()
        }

    /**
     * Preserved-version import onto an EXISTING pipeline (§9.2): inserts the version at the
     * payload's EXACT number as RELEASED and bumps `current_version` (and the index row's
     * metadata, from [name]/[displayName]/[description]) only when the imported version is
     * the new latest.
     *
     * Null when that number is already taken (the sequential case: the `NOT EXISTS` guard
     * suppresses the insert) — the caller re-reads via [findVersionDetail] and classifies
     * per §9.2's table. A TRUE concurrent same-version insert still loses on the PK and
     * raises the DuplicateKeyException for the same classification.
     */
    @Suppress("LongParameterList") // the row's whole shape; a parameter object would just rename these ten
    fun insertReleasedVersion(
        workspaceId: UUID,
        pipelineId: UUID,
        version: Int,
        name: String,
        displayName: String,
        description: String,
        bodyJson: String,
        bodyHash: String,
        releasedAt: Instant?,
        actor: UUID,
    ): PipelineVersionDetail? =
        jdbc
            .query(
                INSERT_RELEASED_VERSION_SQL,
                mapOf(
                    "pipelineId" to pipelineId,
                    "workspaceId" to workspaceId,
                    "version" to version,
                    "name" to name,
                    "displayName" to displayName,
                    "description" to description,
                    "bodyJson" to bodyJson,
                    "bodyHash" to bodyHash,
                    "releasedAt" to releasedAt?.let(Timestamp::from),
                    "actor" to actor,
                ),
                DETAIL_MAPPER,
            ).singleOrNull()

    /**
     * Soft-deletes the pipeline (§14: "Soft delete"). Returns false when nothing was live to
     * delete in [workspaceId].
     *
     * The row stays, so its name stays taken — metadata-db §4.4 makes that explicit and
     * deliberate: execution history references the name, so reusing it would re-point old
     * records at a different pipeline.
     */
    fun softDelete(
        workspaceId: UUID,
        id: UUID,
    ): Boolean =
        jdbc.update(
            "UPDATE pipelines SET is_deleted = TRUE, updated_at = NOW()" +
                " WHERE id = :id AND workspace_id = :workspaceId AND is_deleted = FALSE",
            mapOf("id" to id, "workspaceId" to workspaceId),
        ) > 0

    // ---------------------------------------------------------------------------------------------
    // Constraint translation
    // ---------------------------------------------------------------------------------------------

    /**
     * Translates a `pipelines.name` UNIQUE violation into §12.1's
     * `pipeline.validation.duplicate_name` (HTTP 409).
     *
     * ## Why the constraint, and not a read-then-write pre-check
     *
     * A `SELECT … WHERE name = ?` before the insert is a check two concurrent creates both
     * pass, after which one of them still violates the constraint and surfaces a raw
     * `DataIntegrityViolationException` — a 500 for what is a 409. The database is the only
     * thing that can answer this question atomically, so the constraint stays the authority and
     * this is its translation into the error catalog.
     *
     * ## Why the constraint name is matched
     *
     * These statements can violate more than one unique constraint: `pipelines_pkey` when an
     * import re-uses an id (§11.3), and `pipeline_versions_pkey` on a concurrent update of the
     * same pipeline. Mapping every `DuplicateKeyException` to `duplicate_name` would report
     * "that name is taken" for both, sending the caller to rename something that is not the
     * problem. `uq_pipelines_workspace_name` is the per-workspace constraint metadata-db §4.4
     * names explicitly; anything else is rethrown untouched.
     */
    private fun <T> mappingDuplicateName(
        name: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: DuplicateKeyException) {
            if (e.mostSpecificCause.message?.contains(NAME_CONSTRAINT) != true) throw e
            throw DatapipelinesException(
                code = PipelineErrorCodes.Validation.DUPLICATE_NAME,
                message = "A pipeline named '${name.truncateForError()}' already exists.",
                details = mapOf("name" to name.truncateForError()),
                cause = e,
            )
        }

    /**
     * Translates the two constraints a concurrent draft-create can violate into §13.13's
     * `pipeline.version.conflict` carrying the WINNER's draft state (versioning §3.3):
     * `uq_pipeline_versions_one_draft` when the loser inserted a different version number,
     * and `pipeline_versions_pkey` when both computed the same next number — the loser of
     * two simultaneous first-writes must re-read and rebase either way, and the response's
     * job is to point it at what it lost to.
     */
    private fun <T> mappingDraftRace(
        pipelineId: UUID,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: DuplicateKeyException) {
            val message = e.mostSpecificCause.message
            if (message?.contains(DRAFT_INDEX) != true && message?.contains(DRAFT_PK) != true) throw e
            val winner = findDraftDetailUnchecked(pipelineId)
            throw DatapipelinesException(
                code = PipelineErrorCodes.Versioning.VERSION_CONFLICT,
                message = "Pipeline was modified by someone else after you loaded it.",
                details =
                    mapOf(
                        "current_body_hash" to (winner?.bodyHash ?: ""),
                        "current_status" to (winner?.status?.name ?: "DRAFT"),
                        "updated_by" to (winner?.updatedBy?.toString() ?: ""),
                        "updated_at" to (winner?.updatedAt?.toString() ?: ""),
                    ),
                cause = e,
            )
        }

    /** The race-loser's read of the winner — workspace unchecked because the INSERT already established the caller's scope. */
    private fun findDraftDetailUnchecked(pipelineId: UUID): PipelineVersionDetail? =
        jdbc
            .query(
                """
                SELECT v.pipeline_id, v.version, v.status, v.body_hash, v.created_at, v.created_by,
                       v.released_at, v.released_by, v.updated_by, v.updated_at
                  FROM pipeline_versions v
                 WHERE v.pipeline_id = :pipelineId AND v.status = 'DRAFT'
                """.trimIndent(),
                mapOf("pipelineId" to pipelineId),
                DETAIL_MAPPER,
            ).singleOrNull()

    private companion object {
        /**
         * The per-workspace name constraint behind `UNIQUE (workspace_id, name)` (metadata-db
         * §4.4, V4).
         *
         * Note it is a plain UNIQUE constraint, **not** a partial index on `is_deleted = FALSE`:
         * a soft-deleted pipeline's name stays taken within its workspace until the row is
         * hard-deleted, which §4.4 states is deliberate because execution history references
         * the name.
         */
        const val NAME_CONSTRAINT = "uq_pipelines_workspace_name"

        /** The one-draft partial unique index (versioning §3.3, V6). */
        const val DRAFT_INDEX = "uq_pipeline_versions_one_draft"

        /** The version-table PK — a concurrent draft-create computing the same next number. */
        const val DRAFT_PK = "pipeline_versions_pkey"

        const val COLUMNS =
            "id, name, display_name, description, owner_id, current_version, is_deleted, created_at, updated_at"

        const val SELECT_COLUMNS = "SELECT $COLUMNS FROM pipelines"

        /**
         * The canonical-hash SQL expression — the SAME one V6's backfill used, so a
         * pre-migration row's stored hash is by construction the hash this repository would
         * compute (the class KDoc's "one expression everywhere" rule).
         */
        const val BODY_HASH_EXPR = "encode(sha256(convert_to(CAST(:bodyJson AS jsonb)::text, 'UTF8')), 'hex')"

        /**
         * The version-detail column list. Plain (unqualified) — `INSERT … RETURNING` cannot
         * reference the target through an alias, so every context that needs `v.`-qualified
         * columns derives it from this list.
         */
        const val DETAIL_COLS_PLAIN =
            "pipeline_id, version, status, body_hash, created_at, created_by," +
                " released_at, released_by, updated_by, updated_at"

        /** The `v.`-qualified detail list for SELECT/UPDATE-RETURNING contexts. */
        val DETAIL_COLUMNS =
            DETAIL_COLS_PLAIN.split(", ").joinToString(", ") { "v.$it" }

        val DETAIL_WHERE =
            "SELECT $DETAIL_COLUMNS FROM pipeline_versions v JOIN pipelines p ON p.id = v.pipeline_id" +
                " WHERE p.workspace_id = :workspaceId AND p.is_deleted = FALSE"

        val INSERT_PIPELINE_SQL =
            """
            WITH new_pipeline AS (
                INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version)
                VALUES (:id, :name, :displayName, :description, :ownerId, :workspaceId, 1)
                RETURNING $COLUMNS
            ), new_version AS (
                INSERT INTO pipeline_versions
                    (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)
                SELECT id, 1, CAST(:bodyJson AS jsonb), $BODY_HASH_EXPR, 'RELEASED', :createdBy, :createdBy, NOW()
                  FROM new_pipeline
                RETURNING pipeline_id
            )
            SELECT p.id, p.name, p.display_name, p.description, p.owner_id,
                   p.current_version, p.is_deleted, p.created_at, p.updated_at
              FROM new_pipeline p
              JOIN new_version v ON v.pipeline_id = p.id
            """.trimIndent()

        /** versioning §5.1 — copy-on-write draft create, guard and insert in one statement.
         *
         * The pre-allocated number is `max(existing version) + 1` — normally
         * `current_version + 1`, but a DISCARDED row keeps its number consumed (§3.4:
         * "never reused once any execution has referenced them"), so allocation reads the
         * MAX, not the pointer. The BODY still copies from the current released version:
         * a discard does not change what the released content is.
         *
         * The `<> v.body_hash` predicate is the NO-OP guard (versioning §5.1): a PUT whose
         * body already equals the released one must not burn a version number or light the
         * pending-release badge. Hash-to-hash in the database — comparing in Kotlin would
         * re-derive the canonical form a second time, exactly the defect 035 found live.
         * The `noop` arm returns the RELEASED detail in that case (status RELEASED is the
         * no-op signal); `draft` and `noop` are mutually exclusive on the same parameters,
         * and both join `guard`, so a stale precondition still yields zero rows ⇒ 409. */
        val CREATE_DRAFT_SQL =
            """
            WITH guard AS (
                SELECT 1
                  FROM pipeline_versions v
                  JOIN pipelines p ON p.id = v.pipeline_id
                 WHERE v.pipeline_id = :pipelineId AND p.workspace_id = :workspaceId
                   AND p.is_deleted = FALSE AND v.version = p.current_version
                   AND v.status = 'RELEASED' AND v.body_hash = :expectedHash
            ), draft AS (
                INSERT INTO pipeline_versions
                    (pipeline_id, version, body_json, body_hash, status, created_by, updated_by, updated_at)
                SELECT v.pipeline_id,
                       (SELECT COALESCE(MAX(d2.version), 0) + 1 FROM pipeline_versions d2 WHERE d2.pipeline_id = v.pipeline_id),
                       CAST(:bodyJson AS jsonb), $BODY_HASH_EXPR,
                       'DRAFT', :actor, :actor, NOW()
                  FROM pipeline_versions v
                  JOIN pipelines p ON p.id = v.pipeline_id
                 WHERE v.pipeline_id = :pipelineId AND p.workspace_id = :workspaceId
                   AND p.is_deleted = FALSE AND v.version = p.current_version AND v.status = 'RELEASED'
                   AND $BODY_HASH_EXPR <> v.body_hash
                   AND NOT EXISTS (SELECT 1 FROM pipeline_versions d
                                   WHERE d.pipeline_id = :pipelineId AND d.status = 'DRAFT')
                RETURNING $DETAIL_COLS_PLAIN
            ), noop AS (
                SELECT v.pipeline_id, v.version, v.status, v.body_hash, v.created_at, v.created_by,
                       v.released_at, v.released_by, v.updated_by, v.updated_at
                  FROM pipeline_versions v
                  JOIN pipelines p ON p.id = v.pipeline_id
                  JOIN guard ON TRUE
                 WHERE v.pipeline_id = :pipelineId AND p.workspace_id = :workspaceId
                   AND p.is_deleted = FALSE AND v.version = p.current_version AND v.status = 'RELEASED'
                   AND $BODY_HASH_EXPR = v.body_hash
                   -- A draft that raced in owns the pipeline's working state: the caller's
                   -- "identical to released" write is then a stale base (409), not a no-op —
                   -- answering RELEASED/no-draft while a draft exists would be a lie.
                   AND NOT EXISTS (SELECT 1 FROM pipeline_versions d
                                   WHERE d.pipeline_id = :pipelineId AND d.status = 'DRAFT')
            )
            SELECT $DETAIL_COLS_PLAIN FROM draft, guard
            UNION ALL
            SELECT $DETAIL_COLS_PLAIN FROM noop
            """.trimIndent()

        /** versioning §5.2 — in-place draft write. */
        val WRITE_DRAFT_SQL =
            """
            UPDATE pipeline_versions v
               SET body_json = CAST(:bodyJson AS jsonb),
                   body_hash = $BODY_HASH_EXPR,
                   updated_by = :actor, updated_at = NOW()
              FROM pipelines p
             WHERE v.pipeline_id = :pipelineId AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
               AND p.id = v.pipeline_id AND p.workspace_id = :workspaceId AND p.is_deleted = FALSE
            RETURNING $DETAIL_COLUMNS
            """.trimIndent()

        /** versioning §5.3 — release: flip, pointer bump, metadata ride, one statement. */
        val RELEASE_DRAFT_SQL =
            """
            WITH locked AS (
                UPDATE pipeline_versions v
                   SET status = 'RELEASED', released_at = NOW(), released_by = :actor
                  FROM pipelines p
                 WHERE v.pipeline_id = :pipelineId AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
                   AND p.id = v.pipeline_id AND p.workspace_id = :workspaceId AND p.is_deleted = FALSE
                RETURNING $DETAIL_COLUMNS
            ), bumped AS (
                UPDATE pipelines
                   SET current_version = (SELECT version FROM locked),
                       name = :name, display_name = :displayName, description = :description,
                       updated_at = NOW()
                 WHERE id = :pipelineId AND workspace_id = :workspaceId AND is_deleted = FALSE
                   AND EXISTS (SELECT 1 FROM locked)
                RETURNING $COLUMNS
            )
            SELECT ${COLUMNS.split(", ").joinToString(", ") { "b.$it AS b_$it" }},
                   ${DETAIL_COLS_PLAIN.split(", ").joinToString(", ") { "l.$it AS l_$it" }}
              FROM bumped b, locked l
            """.trimIndent()

        /** versioning §5.4 — delete a never-executed draft; the number returns to the pool. */
        val DELETE_DRAFT_SQL =
            """
            DELETE FROM pipeline_versions v
             USING pipelines p
             WHERE v.pipeline_id = :pipelineId AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
               AND p.id = v.pipeline_id AND p.workspace_id = :workspaceId AND p.is_deleted = FALSE
            """.trimIndent()

        /** versioning §5.4/§3.4 — the FK blocks the delete of an executed draft; flip instead. */
        val FLIP_DRAFT_SQL =
            """
            UPDATE pipeline_versions v
               SET status = 'DISCARDED'
              FROM pipelines p
             WHERE v.pipeline_id = :pipelineId AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
               AND p.id = v.pipeline_id AND p.workspace_id = :workspaceId AND p.is_deleted = FALSE
            RETURNING $DETAIL_COLUMNS
            """.trimIndent()

        /** The version-less import path: next version appended directly as RELEASED. */
        val APPEND_RELEASED_SQL =
            """
            WITH bumped AS (
                UPDATE pipelines
                   SET current_version = current_version + 1,
                       name = :name,
                       display_name = :displayName,
                       description = :description,
                       updated_at = NOW()
                  WHERE id = :id AND workspace_id = :workspaceId AND is_deleted = FALSE
                RETURNING $COLUMNS
            ), new_version AS (
                INSERT INTO pipeline_versions
                    (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)
                SELECT id, current_version, CAST(:bodyJson AS jsonb), $BODY_HASH_EXPR,
                       'RELEASED', :actor, :actor, NOW()
                  FROM bumped
                RETURNING pipeline_id
            )
            SELECT p.id, p.name, p.display_name, p.description, p.owner_id,
                   p.current_version, p.is_deleted, p.created_at, p.updated_at
              FROM bumped p
              JOIN new_version v ON v.pipeline_id = p.id
            """.trimIndent()

        /** versioning §9.2 — new pipeline at the source's exact version number. */
        val IMPORT_NEW_PIPELINE_SQL =
            """
            WITH new_pipeline AS (
                INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version)
                VALUES (:id, :name, :displayName, :description, :ownerId, :workspaceId, :version)
                RETURNING $COLUMNS
            ), new_version AS (
                INSERT INTO pipeline_versions
                    (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)
                SELECT id, :version, CAST(:bodyJson AS jsonb), :bodyHash, 'RELEASED', :actor, :actor,
                       COALESCE(:releasedAt, NOW())
                  FROM new_pipeline
                RETURNING pipeline_id
            )
            SELECT p.id, p.name, p.display_name, p.description, p.owner_id,
                   p.current_version, p.is_deleted, p.created_at, p.updated_at
              FROM new_pipeline p
              JOIN new_version v ON v.pipeline_id = p.id
            """.trimIndent()

        /** versioning §9.2 — exact-version insert onto an existing pipeline; index metadata rides only when it is the new latest. */
        val INSERT_RELEASED_VERSION_SQL =
            """
            WITH ins AS (
                INSERT INTO pipeline_versions
                    (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)
                SELECT p.id, :version, CAST(:bodyJson AS jsonb), :bodyHash, 'RELEASED', :actor, :actor,
                       COALESCE(:releasedAt, NOW())
                  FROM pipelines p
                 WHERE p.id = :pipelineId AND p.workspace_id = :workspaceId AND p.is_deleted = FALSE
                   AND NOT EXISTS (SELECT 1 FROM pipeline_versions v
                                    WHERE v.pipeline_id = p.id AND v.version = :version)
                RETURNING $DETAIL_COLS_PLAIN
            ), bumped AS (
                UPDATE pipelines
                   SET current_version = GREATEST(current_version, :version),
                       name = CASE WHEN :version > current_version THEN :name ELSE name END,
                       display_name = CASE WHEN :version > current_version THEN :displayName ELSE display_name END,
                       description = CASE WHEN :version > current_version THEN :description ELSE description END,
                       updated_at = NOW()
                 WHERE id = :pipelineId AND workspace_id = :workspaceId AND is_deleted = FALSE
                   AND EXISTS (SELECT 1 FROM ins)
                RETURNING 1
            )
            SELECT $DETAIL_COLS_PLAIN FROM ins, bumped
            """.trimIndent()

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                PipelineRecord(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    displayName = rs.getString("display_name"),
                    description = rs.getString("description"),
                    ownerId = rs.getObject("owner_id", UUID::class.java),
                    currentVersion = rs.getInt("current_version"),
                    isDeleted = rs.getBoolean("is_deleted"),
                    // TIMESTAMPTZ → OffsetDateTime is exact regardless of the JVM zone;
                    // getTimestamp() without a Calendar reads it in the default zone
                    // (metadata-db §6.1 "timestamp reads").
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                )
            }

        val DETAIL_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                PipelineVersionDetail(
                    pipelineId = rs.getObject("pipeline_id", UUID::class.java),
                    version = rs.getInt("version"),
                    status = PipelineVersionStatus.fromWire(rs.getString("status")),
                    bodyHash = rs.getString("body_hash"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    releasedAt = rs.getObject("released_at", OffsetDateTime::class.java)?.toInstant(),
                    releasedBy = rs.getObject("released_by", UUID::class.java),
                    updatedBy = rs.getObject("updated_by", UUID::class.java),
                    updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }

        /** [RELEASE_DRAFT_SQL]'s joined projection: the bumped record (b_) beside the released version (l_). */
        val RELEASE_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Released(
                    record =
                        PipelineRecord(
                            id = rs.getObject("b_id", UUID::class.java),
                            name = rs.getString("b_name"),
                            displayName = rs.getString("b_display_name"),
                            description = rs.getString("b_description"),
                            ownerId = rs.getObject("b_owner_id", UUID::class.java),
                            currentVersion = rs.getInt("b_current_version"),
                            isDeleted = rs.getBoolean("b_is_deleted"),
                            createdAt = rs.getObject("b_created_at", OffsetDateTime::class.java).toInstant(),
                            updatedAt = rs.getObject("b_updated_at", OffsetDateTime::class.java).toInstant(),
                        ),
                    version =
                        PipelineVersionDetail(
                            pipelineId = rs.getObject("l_pipeline_id", UUID::class.java),
                            version = rs.getInt("l_version"),
                            status = PipelineVersionStatus.fromWire(rs.getString("l_status")),
                            bodyHash = rs.getString("l_body_hash"),
                            createdAt = rs.getObject("l_created_at", OffsetDateTime::class.java).toInstant(),
                            createdBy = rs.getObject("l_created_by", UUID::class.java),
                            releasedAt = rs.getObject("l_released_at", OffsetDateTime::class.java)?.toInstant(),
                            releasedBy = rs.getObject("l_released_by", UUID::class.java),
                            updatedBy = rs.getObject("l_updated_by", UUID::class.java),
                            updatedAt = rs.getObject("l_updated_at", OffsetDateTime::class.java)?.toInstant(),
                        ),
                )
            }
    }
}
