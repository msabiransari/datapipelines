package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.Dialect
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One **virtual folder** of the template tree (template-hierarchy-design §3.1, §9.2).
 *
 * A folder is a name prefix and nothing else: there is no table, no column, no id, and no
 * row anywhere that corresponds to one. It is derived, per request, from the names of the
 * live templates beneath it — which is why [templateCount] is always ≥ 1 and an *empty*
 * folder is unrepresentable rather than merely unrendered.
 *
 * [path] is the full prefix (`acme/finance`) — the value the next level's prefix query
 * takes; [segment] is its last element (`finance`), which is what the tree labels.
 */
data class TemplateFolder(
    val path: String,
    val segment: String,
    val templateCount: Int,
)

/** Version metadata, without the body — the `GET /templates/{id}/versions` projection (§9). */
data class TemplateVersionSummary(
    val id: String,
    val version: Int,
    val createdAt: java.time.Instant,
    val createdBy: UUID,
)

/**
 * Persistence for `templates` and `template_versions` (metadata-db §4.8/§4.9, §6,
 * versioning §6).
 *
 * `NamedParameterJdbcTemplate` exclusively — no JPA (module-structure §8.1). The repository
 * lives in the module that owns the entity (§3.1 rule 1); the `DataSource` bean is app-level
 * and schema creation belongs to `app`'s Flyway alone (rule 2), so nothing here creates or
 * alters a table.
 *
 * ## The version lifecycle (versioning §3.1/§6, since V6)
 *
 * Same lifecycle as pipelines: [create] lands v1 RELEASED; [createDraft] copies the current
 * released version to a DRAFT (the partial unique index `uq_template_versions_one_draft`
 * makes concurrent first-writers race-safe, the loser surfacing as
 * `template.version.conflict`); [writeDraft] overwrites the draft in place; [releaseDraft]
 * flips it to RELEASED and bumps `templates.current_version`; [discardDraft] deletes the
 * draft — always a hard delete here, because unlike `pipeline_versions` nothing references
 * a `template_versions` row by FK (pipeline pins are numbers in JSON, not constraints), so
 * §3.4's executed-draft branch cannot fire for templates.
 *
 * ## Draft content vs. index metadata — a deliberate asymmetry
 *
 * A template draft versions the **content fields** (`engine`, `dialect`, `is_library`,
 * `imports`, `body`); `display_name` / `description` live on the index row `templates`
 * only and are NOT part of the versioned artifact, so they keep updating at save time —
 * there is no draft row that could stage them (versioning v1.3 documents the asymmetry;
 * §3.5's metadata-rides-the-release is a pipeline rule, where the metadata is part of the
 * portable body). Templates have no rename: `name` is the identity, so §3.5's
 * draft-write-time name-uniqueness check is a pipeline-only concern.
 *
 * ## `body_hash` — one expression everywhere
 *
 * The canonical template body is the version-owned field object
 * `{engine, dialect, is_library, imports, body}` projected through
 * `jsonb_build_object(...)` (which normalizes key order deterministically) and hashed
 * `encode(sha256(convert_to(<jsonb>::text, 'UTF8')), 'hex')` BY THE DATABASE — the same expression V6's
 * backfill used, so writer and reader cannot disagree on a body's hash.
 *
 * `TooManyFunctions` is suppressed because the version-lifecycle round made this class the
 * single owner of every `template_versions` statement — lifecycle reads, the four write
 * paths, and both import modes — mirroring the `PipelineRepository` precedent.
 *
 * ## Surrogate key and workspace scoping (slice 2)
 *
 * Since V4 `templates` has a surrogate `id UUID` PK and the human id is the `name` column,
 * unique per workspace (metadata-db §4.8); `template_versions` references the surrogate.
 * Every method below keeps taking the human id as a `String` — pipeline-JSON and
 * `imports_json` `{id, version}` refs mean `name`, never the surrogate — and takes the
 * active workspace explicitly as `workspaceId` (design §5: resolution happens in the
 * request pipeline). **No default anywhere**: a missed caller is a compile error, never a
 * silent resolution in some default world.
 */
@Suppress("TooManyFunctions")
class TemplateRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** The current-version projection of a live template in [workspaceId], or null when absent/soft-deleted. */
    fun findLatest(
        workspaceId: UUID,
        id: String,
    ): Template? =
        jdbc
            .query(
                "$SELECT_JOINED WHERE t.name = :name AND t.workspace_id = :workspaceId" +
                    " AND t.is_deleted = FALSE AND v.version = t.current_version",
                mapOf("name" to id, "workspaceId" to workspaceId),
                MAPPER,
            ).singleOrNull()

    /** A specific stored version's full record, including of a soft-deleted template (§5.1). */
    fun findVersion(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): Template? =
        jdbc
            .query(
                "$SELECT_JOINED WHERE t.name = :name AND t.workspace_id = :workspaceId AND v.version = :version",
                mapOf("name" to id, "workspaceId" to workspaceId, "version" to version),
                MAPPER,
            ).singleOrNull()

    /**
     * The version-table record the engine and validator resolve imports against.
     *
     * Not filtered by `is_deleted`: pipelines referencing a deleted template's version continue
     * to work (templates.md §5.1), so the registry must still resolve it.
     */
    fun lookupVersion(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): TemplateVersion? =
        jdbc
            .query(
                "$SELECT_VERSION WHERE t.name = :name AND t.workspace_id = :workspaceId AND v.version = :version",
                mapOf("name" to id, "workspaceId" to workspaceId, "version" to version),
                VERSION_MAPPER,
            ).singleOrNull()

    /** True when any version of [id] exists in [workspaceId] — the `template_not_found` vs `version_not_found` split. */
    fun existsId(
        workspaceId: UUID,
        id: String,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                " WHERE t.name = :name AND t.workspace_id = :workspaceId)",
            mapOf("name" to id, "workspaceId" to workspaceId),
            Boolean::class.java,
        ) == true

    /**
     * The `GET /templates?dialect=&q=&offset=&limit=` page (templates.md §9, rest-api §8.5).
     *
     * Returns each live template at its **current** version, `name`-ordered so paging is stable.
     * Both filters are optional and independent: [dialect] is an exact match on the version's
     * dialect, [q] a case-insensitive substring of `name`, `display_name`, `description` or the
     * version's `dialect` wire value (the list screen renders a dialect badge, and the 029 search
     * rule is that a screen's search covers every column it renders).
     *
     * `q` is bound as a parameter and its own LIKE metacharacters are escaped ([escapeLike]), so
     * a search for `100%_off` searches for that literal string instead of turning into a wildcard
     * that scans everything.
     *
     * The `t.is_deleted = FALSE` predicate matches `idx_templates_active` (metadata-db §4.8), the
     * partial index that exists for exactly this listing.
     */
    fun list(
        workspaceId: UUID,
        dialect: Dialect? = null,
        type: TemplateType? = null,
        q: String? = null,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): List<Template> =
        jdbc.query(
            """
            $SELECT_JOINED
            $LIST_WHERE
             ORDER BY t.name
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
            mapOf(
                // Every optional filter is CAST in the SQL: a bare `? IS NULL` gives Postgres no
                // type to infer and the statement will not even prepare.
                "dialect" to dialect?.wire,
                "type" to type?.wire,
                "pattern" to q?.let { "%${escapeLike(it)}%" },
                "workspaceId" to workspaceId,
                "limit" to limit.coerceIn(1, MAX_PAGE_LIMIT),
                "offset" to maxOf(0, offset),
            ),
            MAPPER,
        )

    /**
     * The truthful total of the [list] page — the same predicate, no paging (034 E3: the
     * list screen's pager used to report an estimate, "Showing 25 of 26" on a 100-row
     * workspace). The WHERE is the shared [LIST_WHERE], so the page and its total cannot
     * drift apart.
     */
    fun count(
        workspaceId: UUID,
        dialect: Dialect? = null,
        type: TemplateType? = null,
        q: String? = null,
    ): Int =
        checkNotNull(
            jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM templates t
                  JOIN template_versions v ON v.template_id = t.id
                $LIST_WHERE
                """.trimIndent(),
                mapOf(
                    "dialect" to dialect?.wire,
                    "type" to type?.wire,
                    "pattern" to q?.let { "%${escapeLike(it)}%" },
                    "workspaceId" to workspaceId,
                ),
                Int::class.java,
            ),
        )

    /**
     * The **direct sub-folders** of [prefix] — one tree level, never a subtree
     * (template-hierarchy-design §8, §9.2).
     *
     * A folder is a name prefix (§3.1), so this is a `GROUP BY` over the first path segment
     * that follows `prefix/`, restricted to names that have *something* after it. A name with
     * nothing after that segment is a template, not a folder, and comes back from
     * [listChildTemplates] instead; `a/b` and `a/b/c` coexisting therefore yield both a leaf
     * `b` and a folder `b` at the same level, which is exactly what §4.3 describes.
     *
     * [prefix] `null` (or empty) is the tree's **root** level: the first segment of every
     * multi-segment name. Flat legacy names have no first-segment-plus-remainder, so they are
     * absent here and present as root leaves — §4.5 forbids renaming them into folders.
     *
     * The count is over LIVE templates matching the same [dialect]/[type] filters as the
     * level's leaves, so a folder whose entire subtree is filtered out does not come back at
     * all: an empty folder is unrepresentable, not merely unrendered (§9.1).
     *
     * **Index use.** `t.workspace_id = :workspaceId` is the leading column of
     * `uq_templates_workspace_name (workspace_id, name)`, so this is a bounded index range
     * scan over one workspace, not a full table scan — the whole point of §8's prefix form
     * over a `LIKE '%…%'` search. (The `name LIKE 'prefix/%'` predicate narrows further; on a
     * non-`C` database collation Postgres applies it as a filter rather than as a second
     * index bound, which is why the workspace equality carries the bound.)
     */
    fun listChildFolders(
        workspaceId: UUID,
        prefix: String? = null,
        dialect: Dialect? = null,
        type: TemplateType? = null,
        limit: Int = MAX_PAGE_LIMIT,
    ): List<TemplateFolder> =
        jdbc.query(
            """
            SELECT split_part(substring(t.name FROM CAST(:cutFrom AS INT)), '/', 1) AS segment,
                   COUNT(*) AS template_count
              FROM templates t
              JOIN template_versions v ON v.template_id = t.id
            $TREE_WHERE
              AND position('/' IN substring(t.name FROM CAST(:cutFrom AS INT))) > 0
             GROUP BY 1
             ORDER BY 1
             LIMIT :limit
            """.trimIndent(),
            treeParams(workspaceId, prefix, dialect, type) + mapOf("limit" to limit.coerceIn(1, MAX_PAGE_LIMIT + 1)),
        ) { rs, _ ->
            val segment = rs.getString("segment")
            TemplateFolder(
                path = if (prefix.isNullOrEmpty()) segment else "$prefix/$segment",
                segment = segment,
                templateCount = rs.getInt("template_count"),
            )
        }

    /**
     * The **direct template children** of [prefix] — the leaves of one tree level, at their
     * current version, `name`-ordered so paging is stable (§8, §9.2).
     *
     * "Direct" is the whole contract: a name whose remainder after `prefix/` still contains a
     * `/` belongs to a sub-folder and is NOT returned here. With [prefix] `null` the level is
     * the root and the leaves are the flat, single-segment names — every template that exists
     * today (§4.1: single-segment names are valid paths that sit at the root).
     *
     * [q] is deliberately absent: browsing and searching are different presentations (§9.2),
     * and search is a flat list of full paths served by [list], not a pruned tree.
     */
    fun listChildTemplates(
        workspaceId: UUID,
        prefix: String? = null,
        dialect: Dialect? = null,
        type: TemplateType? = null,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): List<Template> =
        jdbc.query(
            """
            $SELECT_JOINED
            $TREE_WHERE
              AND position('/' IN substring(t.name FROM CAST(:cutFrom AS INT))) = 0
             ORDER BY t.name
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
            treeParams(workspaceId, prefix, dialect, type) +
                mapOf(
                    "limit" to limit.coerceIn(1, MAX_PAGE_LIMIT + 1),
                    "offset" to maxOf(0, offset),
                ),
            MAPPER,
        )

    /**
     * The truthful total of one level's [listChildTemplates] page — the same predicate, no
     * paging, so the level and its pager cannot drift apart (the 034 E3 discipline [count]
     * follows for the flat list).
     */
    fun countChildTemplates(
        workspaceId: UUID,
        prefix: String? = null,
        dialect: Dialect? = null,
        type: TemplateType? = null,
    ): Int =
        checkNotNull(
            jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM templates t
                  JOIN template_versions v ON v.template_id = t.id
                $TREE_WHERE
                  AND position('/' IN substring(t.name FROM CAST(:cutFrom AS INT))) = 0
                """.trimIndent(),
                treeParams(workspaceId, prefix, dialect, type),
                Int::class.java,
            ),
        )

    /**
     * The bind values every tree query shares.
     *
     * `namePattern` is the prefix scope — `acme/finance/%` for a folder, `%` for the root
     * (where every name is in scope by definition). The prefix's own LIKE metacharacters are
     * escaped ([escapeLike]) exactly as [list] escapes `q`, so a folder literally named
     * `100%_off` scopes to itself instead of to everything.
     *
     * `cutFrom` is the 1-based offset at which a name's remainder *below* the prefix begins:
     * `prefix.length + 2` skips the prefix and its `/`, and `1` at the root means the whole
     * name. Both tree queries then classify a row with one expression — a remainder that
     * still contains `/` is a folder, one that does not is a leaf.
     */
    private fun treeParams(
        workspaceId: UUID,
        prefix: String?,
        dialect: Dialect?,
        type: TemplateType?,
    ): Map<String, Any?> =
        mapOf(
            "workspaceId" to workspaceId,
            "namePattern" to if (prefix.isNullOrEmpty()) "%" else "${escapeLike(prefix)}/%",
            "cutFrom" to if (prefix.isNullOrEmpty()) 1 else prefix.length + 2,
            "dialect" to dialect?.wire,
            "type" to type?.wire,
        )

    /** Version metadata, newest first (§9 list-versions). */
    fun listVersions(
        workspaceId: UUID,
        id: String,
    ): List<TemplateVersionSummary> =
        jdbc.query(
            """
            SELECT t.name AS template_id, v.version, v.created_at, v.created_by
              FROM template_versions v
              JOIN templates t ON t.id = v.template_id
             WHERE t.name = :name AND t.workspace_id = :workspaceId
             ORDER BY v.version DESC
            """.trimIndent(),
            mapOf("name" to id, "workspaceId" to workspaceId),
        ) { rs, _ ->
            TemplateVersionSummary(
                id = rs.getString("template_id"),
                version = rs.getInt("version"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java),
            )
        }

    /**
     * Inserts the template and its version 1 — RELEASED on creation (§3.2) — together in
     * [workspaceId], returning what the database stored.
     *
     * [draft] `id` is auto-generated when omitted (templates.md §3.2). The final `SELECT` reads
     * back server-assigned `created_at` / `updated_at`, never a hand-built value (metadata-db
     * §6.1).
     */
    fun create(
        workspaceId: UUID,
        draft: TemplateDraft,
        createdBy: UUID,
    ): Template {
        val id = draft.id ?: generateId()
        // §5.3 (046): creation is where a null payload type becomes the explicit `sql` default,
        // so every caller of the create path stores a resolved type without knowing the rule.
        val resolved = TemplateTypeRule.forCreate(draft)
        return mappingDuplicateName(id) {
            jdbc
                .query(
                    INSERT_SQL,
                    params(workspaceId, id, resolved, createdBy),
                    MAPPER,
                ).single()
        }
    }

    /**
     * Translates a `templates` name-UNIQUE violation into §13.9's
     * `template.validation.duplicate_name` (HTTP 409) — the exact `PipelineRepository`
     * precedent (same reasoning: the constraint is the only atomic authority, and the
     * constraint name is matched so a surrogate-PK collision on import cannot masquerade
     * as a name conflict). Before this mapping the violation surfaced as a raw
     * `DuplicateKeyException` 500.
     */
    private fun <T> mappingDuplicateName(
        name: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            if (e.mostSpecificCause.message?.contains(NAME_CONSTRAINT) != true) throw e
            throw co.datapipelines.typesystem.DatapipelinesException(
                code = PipelineErrorCodes.Template.DUPLICATE_NAME,
                message = "A template named '$name' already exists in this workspace.",
                details = mapOf("name" to name),
                cause = e,
            )
        }

    /**
     * Translates the one-draft partial index violation into §13.9's
     * `template.version.conflict` carrying the WINNER's draft state (versioning §3.3/§6):
     * the loser of two simultaneous first-writes must re-read and rebase.
     */
    private fun <T> mappingDraftRace(
        templateId: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            if (e.mostSpecificCause.message?.contains(DRAFT_INDEX) != true) throw e
            val winner = findDraftDetailUnchecked(templateId)
            throw co.datapipelines.typesystem.DatapipelinesException(
                code = PipelineErrorCodes.Template.VERSION_CONFLICT,
                message = "Template was modified by someone else after you loaded it.",
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

    // ---------------------------------------------------------------------------------------------
    // Lifecycle reads (versioning §4/§7)
    // ---------------------------------------------------------------------------------------------

    /** One version's lifecycle detail, or null when the template/version does not exist in the workspace. */
    fun findVersionDetail(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): TemplateVersionDetail? =
        jdbc
            .query(
                DETAIL_WHERE + " AND t.name = :name AND v.version = :version",
                mapOf("name" to id, "version" to version, "workspaceId" to workspaceId),
                DETAIL_MAPPER,
            ).singleOrNull()

    /** The template's DRAFT, or null when none exists — the draft pointer of §7's read shape. */
    fun findDraftDetail(
        workspaceId: UUID,
        id: String,
    ): TemplateVersionDetail? =
        jdbc
            .query(
                DETAIL_WHERE + " AND t.name = :name AND v.status = 'DRAFT'",
                mapOf("name" to id, "workspaceId" to workspaceId),
                DETAIL_MAPPER,
            ).singleOrNull()

    /** The DRAFT detail of each of [ids] that has one — the list screens' pending-release badge (§7). */
    fun findDrafts(
        workspaceId: UUID,
        ids: Collection<String>,
    ): Map<String, TemplateVersionDetail> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc
            .query(
                DETAIL_WHERE + " AND v.status = 'DRAFT' AND t.name IN (:names)",
                mapOf("names" to ids, "workspaceId" to workspaceId),
                DETAIL_MAPPER,
            ).associateBy { it.templateId }
    }

    /**
     * Every template name holding a DRAFT, across ALL workspaces (soft-deleted parents
     * included). The authoring-disabled boot check's evidence (versioning §5.5).
     */
    fun findAllDraftTemplateNames(): List<String> =
        jdbc
            .query(
                """
                SELECT t.name
                  FROM template_versions v
                  JOIN templates t ON t.id = v.template_id
                 WHERE v.status = 'DRAFT'
                 ORDER BY t.name
                """.trimIndent(),
                emptyMap<String, Any>(),
            ) { rs, _ -> rs.getString("name") }

    /**
     * One template version's lifecycle status, or null when it does not exist — what the
     * pipeline-release pin check reads (versioning §6: a pipeline may be released only when
     * every template version its body pins is RELEASED).
     */
    fun findVersionStatus(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): co.datapipelines.pipeline.PipelineVersionStatus? =
        jdbc
            .query(
                """
                SELECT v.status
                  FROM template_versions v JOIN templates t ON t.id = v.template_id
                 WHERE t.name = :name AND t.workspace_id = :workspaceId AND v.version = :version
                """.trimIndent(),
                mapOf("name" to id, "version" to version, "workspaceId" to workspaceId),
            ) { rs, _ -> PipelineVersionStatus.fromWire(rs.getString("status")) }
            .singleOrNull()

    /**
     * The content hash of a candidate template version, computed by the SAME database
     * expression every write and the V6 backfill use — what §9.2's import hash-recompute
     * guard reads. Never recomputed in Kotlin: a second implementation of the canonical
     * form is where the writer's and the reader's hashes would silently diverge.
     *
     * [dialect] is the wire value or null (an `html` template, since 046) — the expression
     * hashes it as JSON `null`, deterministically, exactly as the write path does.
     */
    fun computeBodyHash(
        engine: String,
        dialect: String?,
        isLibrary: Boolean,
        importsJson: String,
        body: String,
    ): String =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT encode(sha256(convert_to(jsonb_build_object('engine', :engine, 'dialect', :dialect," +
                    " 'is_library', :isLibrary, 'imports', CAST(:importsJson AS jsonb), 'body', :body)" +
                    "::text, 'UTF8')), 'hex')",
                mapOf(
                    "engine" to engine,
                    "dialect" to dialect,
                    "isLibrary" to isLibrary,
                    "importsJson" to importsJson,
                    "body" to body,
                ),
                String::class.java,
            ),
        )

    /** The race-loser's read of the winner — workspace unchecked because the INSERT already established the caller's scope. */
    private fun findDraftDetailUnchecked(templateId: String): TemplateVersionDetail? =
        jdbc
            .query(
                """
                SELECT t.name AS template_id, v.version, v.status, v.body_hash, v.created_at, v.created_by,
                       v.released_at, v.released_by, v.updated_by, v.updated_at
                  FROM template_versions v JOIN templates t ON t.id = v.template_id
                 WHERE t.name = :name AND v.status = 'DRAFT'
                """.trimIndent(),
                mapOf("name" to templateId),
                DETAIL_MAPPER,
            ).singleOrNull()

    // ---------------------------------------------------------------------------------------------
    // Lifecycle writes (versioning §5/§6)
    // ---------------------------------------------------------------------------------------------

    /**
     * Draft create — copy-on-write from the current released version (versioning §5.1).
     * The draft pre-allocates `current_version + 1` (§3.4); index metadata
     * (`display_name`/`description`) moves at save time — the documented template asymmetry.
     *
     * ## A no-op write returns the RELEASED state, not a draft (versioning §5.1)
     *
     * The draft insert is suppressed when the incoming CONTENT hash equals the released
     * content's — compared in the statement by the same [TEMPLATE_HASH_EXPR] the INSERT
     * would store. A returned detail with `status = RELEASED` is the no-op signal: no
     * draft, no burned version number, and the row returned is the current released
     * version. Index metadata still moves in that case: `display_name`/`description` are
     * NOT part of the hashed artifact (the §6 asymmetry), so a save that changes only them
     * is a real save of the index row, not a no-op.
     *
     * Null when the guard failed (stale hash, unknown template, no released version); the
     * race loser gets `template.version.conflict` carrying the winner's hash.
     */
    fun createDraft(
        workspaceId: UUID,
        id: String,
        draft: TemplateDraft,
        expectedHash: String,
        actor: UUID,
    ): TemplateVersionDetail? =
        mappingDraftRace(id) {
            jdbc
                .query(
                    CREATE_DRAFT_SQL,
                    params(workspaceId, id, draft, actor) + mapOf("expectedHash" to expectedHash),
                    DETAIL_MAPPER,
                ).singleOrNull()
        }

    /**
     * Draft write — in-place overwrite of the DRAFT (versioning §5.2), metadata moving at save
     * time. A draft edited back to content identical to its RELEASED parent is **left alone**
     * (written in place, never auto-discarded) — discard stays explicit (§5.4). Do not "fix" this.
     */
    fun writeDraft(
        workspaceId: UUID,
        id: String,
        draft: TemplateDraft,
        expectedHash: String,
        actor: UUID,
    ): TemplateVersionDetail? =
        jdbc
            .query(
                WRITE_DRAFT_SQL,
                params(workspaceId, id, draft, actor) + mapOf("expectedHash" to expectedHash),
                DETAIL_MAPPER,
            ).singleOrNull()

    /**
     * Release (versioning §5.3): the DRAFT flips to RELEASED with a database-generated
     * `released_at`, and `templates.current_version` moves to it. Null when no DRAFT
     * matched [expectedHash].
     */
    fun releaseDraft(
        workspaceId: UUID,
        id: String,
        expectedHash: String,
        actor: UUID,
    ): TemplateVersionDetail? =
        jdbc
            .query(
                RELEASE_DRAFT_SQL,
                mapOf("name" to id, "workspaceId" to workspaceId, "expectedHash" to expectedHash, "actor" to actor),
                DETAIL_MAPPER,
            ).singleOrNull()

    /**
     * Discard (versioning §5.4) — a hard delete: nothing references a `template_versions`
     * row by FK, so §3.4's executed-draft DISCARDED branch cannot fire for templates and
     * the version number always returns to the pool. False when no DRAFT matched
     * [expectedHash].
     */
    fun discardDraft(
        workspaceId: UUID,
        id: String,
        expectedHash: String,
    ): Boolean =
        jdbc.update(
            """
            DELETE FROM template_versions v
             USING templates t
             WHERE t.name = :name AND t.workspace_id = :workspaceId AND t.is_deleted = FALSE
               AND v.template_id = t.id AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
            """.trimIndent(),
            mapOf("name" to id, "workspaceId" to workspaceId, "expectedHash" to expectedHash),
        ) > 0

    /**
     * Appends the next version directly as RELEASED and bumps `current_version` — the
     * version-LESS import path (§9.2: allocate-next-local when the payload carries no
     * version). Not the PUT path: HTTP writes go through [createDraft]/[writeDraft].
     *
     * Returns null when no live template has this id in [workspaceId]; the caller decides
     * whether that is a 404.
     */
    fun appendReleasedVersion(
        workspaceId: UUID,
        id: String,
        draft: TemplateDraft,
        updatedBy: UUID,
    ): Template? =
        jdbc
            .query(
                APPEND_RELEASED_SQL,
                params(workspaceId, id, draft, updatedBy),
                MAPPER,
            ).singleOrNull()

    /**
     * Preserved-version import onto a NEW template (§9.2): the template row and its version
     * at the payload's EXACT version land together as RELEASED, `released_at` from the
     * source, `body_hash` the source declared (the caller has recomputed it from the
     * payload body). Constraint violations raise for the caller to classify.
     */
    fun importTemplateVersion(
        workspaceId: UUID,
        draft: TemplateDraft,
        version: Int,
        bodyHash: String,
        releasedAt: java.time.Instant?,
        actor: UUID,
    ): Template {
        val id = draft.id ?: error("preserved-version import requires an id")
        val resolved = TemplateTypeRule.forCreate(draft)
        return mappingDuplicateName(id) {
            jdbc
                .query(
                    IMPORT_NEW_TEMPLATE_SQL,
                    params(workspaceId, id, resolved, actor) +
                        mapOf(
                            "version" to version,
                            "bodyHash" to bodyHash,
                            "releasedAt" to releasedAt?.let(java.sql.Timestamp::from),
                        ),
                    MAPPER,
                ).single()
        }
    }

    /**
     * Preserved-version import onto an EXISTING template (§9.2): inserts the version at the
     * payload's EXACT number as RELEASED, bumping `current_version` (and index metadata)
     * only when it is the new latest. Null when the number is already taken (the
     * `NOT EXISTS` guard suppressed the insert) — the caller re-reads via
     * [findVersionDetail] and classifies per §9.2's table.
     */
    fun insertReleasedVersion(
        workspaceId: UUID,
        id: String,
        draft: TemplateDraft,
        version: Int,
        bodyHash: String,
        releasedAt: java.time.Instant?,
        actor: UUID,
    ): TemplateVersionDetail? =
        jdbc
            .query(
                INSERT_RELEASED_VERSION_SQL,
                params(workspaceId, id, draft, actor) +
                    mapOf(
                        "version" to version,
                        "bodyHash" to bodyHash,
                        "releasedAt" to releasedAt?.let(java.sql.Timestamp::from),
                    ),
                DETAIL_MAPPER,
            ).singleOrNull()

    /** Soft-deletes the template (§9). Returns false when nothing live was there to delete in [workspaceId]. */
    fun softDelete(
        workspaceId: UUID,
        id: String,
    ): Boolean =
        jdbc.update(
            "UPDATE templates SET is_deleted = TRUE, updated_at = NOW()" +
                " WHERE name = :name AND workspace_id = :workspaceId AND is_deleted = FALSE",
            mapOf("name" to id, "workspaceId" to workspaceId),
        ) > 0

    private fun params(
        workspaceId: UUID,
        id: String,
        draft: TemplateDraft,
        actor: UUID,
    ): Map<String, Any?> =
        mapOf(
            "name" to id,
            "workspaceId" to workspaceId,
            "displayName" to draft.displayName,
            "description" to draft.description,
            "engine" to draft.engine,
            // The type every write path resolved before calling in (create's `sql` default or
            // the template's established value, [TemplateTypeRule]); the elvis is the belt
            // behind that — it can only fire on a path that skipped the rule, and the
            // chk_type_dialect invariant then refuses the row rather than storing a lie.
            "type" to (draft.type ?: TemplateType.SQL).wire,
            "dialect" to draft.dialect?.wire,
            "isLibrary" to draft.isLibrary,
            "importsJson" to TemplateJson.writeImports(draft.imports),
            "body" to draft.body,
            "actor" to actor,
        )

    companion object {
        /** The per-workspace name constraint behind `UNIQUE (workspace_id, name)` (metadata-db §4.8, V4). */
        private const val NAME_CONSTRAINT = "uq_templates_workspace_name"

        /** Page size when the caller names none (rest-api §8.5 shows `limit=50`). */
        const val DEFAULT_PAGE_LIMIT = 50

        /** Largest page a caller may ask for — an unbounded `limit` is an unbounded response. */
        const val MAX_PAGE_LIMIT = 200

        /** Hex characters of the random suffix on an auto-generated id. */
        private const val GENERATED_ID_HEX_LENGTH = 16

        /**
         * Escapes `\`, `%` and `_` so a search term is matched **literally** by `ILIKE … ESCAPE
         * '\'`. Without it a `q` of `%` matches every row and a `q` of `_` matches every single
         * character — a search box that silently becomes a full scan.
         */
        private fun escapeLike(term: String): String =
            term
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")

        /** The §4.1 name grammar (templates.md §3.2). A hex suffix keeps generated ids inside the rule. */
        private fun generateId(): String =
            "template_" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(GENERATED_ID_HEX_LENGTH)

        /**
         * `created_by` comes from `template_versions`, never from `templates` (TPL-API-2).
         *
         * The two are genuinely different people: `templates.created_by` is whoever first
         * created the template, `template_versions.created_by` is whoever wrote *this* version.
         * Reading the former made a version updated by B report A as its author — and disagree
         * with [listVersions], which reads the version row, about the very same version.
         *
         * `t.name AS id`: since V4 the human id is the `name` column (the PK is a surrogate
         * UUID, metadata-db §4.8); the alias keeps the record types name-based, which is what
         * pipeline-JSON and `imports_json` `{id, version}` refs mean.
         */
        private val SELECT_JOINED =
            """
            SELECT t.name AS id, t.display_name, t.description,
                   v.version, v.engine, v.type, v.dialect, v.is_library, v.imports_json::TEXT AS imports_json,
                   v.body, v.created_at, v.created_by AS version_created_by, v.status, v.body_hash
              FROM templates t
              JOIN template_versions v ON v.template_id = t.id
            """.trimIndent()

        /**
         * The live-at-current-version page predicate of [list], shared with [count] (034 E3)
         * so the page and its total can never disagree. Every optional filter is CAST in the
         * SQL: a bare `? IS NULL` gives Postgres no type to infer and the statement will not
         * even prepare.
         */
        private val LIST_WHERE =
            """
            WHERE t.is_deleted = FALSE
              AND t.workspace_id = :workspaceId
              AND v.version = t.current_version
              AND (CAST(:dialect AS TEXT) IS NULL OR v.dialect = CAST(:dialect AS TEXT))
              AND (CAST(:type AS TEXT) IS NULL OR v.type = CAST(:type AS TEXT))
              AND (
                    CAST(:pattern AS TEXT) IS NULL
                    OR t.name ILIKE CAST(:pattern AS TEXT) ESCAPE '\'
                    OR t.display_name ILIKE CAST(:pattern AS TEXT) ESCAPE '\'
                    OR t.description ILIKE CAST(:pattern AS TEXT) ESCAPE '\'
                    OR v.dialect ILIKE CAST(:pattern AS TEXT) ESCAPE '\'
                  )
            """.trimIndent()

        /**
         * The live-at-current-version predicate of ONE tree level, shared by
         * [listChildFolders], [listChildTemplates] and [countChildTemplates] so a level, its
         * folders and its total can never disagree.
         *
         * It is [LIST_WHERE] minus the `q` clause (browse and search are different
         * presentations, §9.2) plus the prefix scope. Every optional filter is CAST in the
         * SQL for the same reason as [LIST_WHERE]: a bare `? IS NULL` gives Postgres no type
         * to infer and the statement will not even prepare.
         */
        private val TREE_WHERE =
            """
            WHERE t.is_deleted = FALSE
              AND t.workspace_id = :workspaceId
              AND v.version = t.current_version
              AND t.name LIKE CAST(:namePattern AS TEXT) ESCAPE '\'
              AND (CAST(:dialect AS TEXT) IS NULL OR v.dialect = CAST(:dialect AS TEXT))
              AND (CAST(:type AS TEXT) IS NULL OR v.type = CAST(:type AS TEXT))
            """.trimIndent()

        private const val SELECT_VERSION =
            "SELECT t.name AS template_id, v.version, v.engine, v.type, v.dialect, v.is_library, " +
                "v.imports_json::TEXT AS imports_json, v.body, v.created_at, v.created_by " +
                "FROM template_versions v JOIN templates t ON t.id = v.template_id"

        /** The one-draft partial unique index (versioning §3.3, V6). */
        private const val DRAFT_INDEX = "uq_template_versions_one_draft"

        /**
         * The canonical-hash SQL expression over a template's version-owned fields — the SAME
         * one V6's backfill used, built from the write's own parameters so the hash can never
         * disagree with the row being stored. `jsonb_build_object` normalizes key order, so
         * parameter order is irrelevant to the result.
         *
         * `dialect` is CAST for the reason every optional filter in this file is: since 046 it
         * is **nullable** (null exactly when `type = 'html'`, §5.1's `chk_type_dialect`), and a
         * bare parameter in `jsonb_build_object` gives Postgres no type to infer — the whole
         * statement then fails to prepare with `could not determine data type of parameter`,
         * so creating an html template was impossible on every write path this expression
         * serves. The cast does not change the hash: a text parameter and a `CAST(… AS TEXT)`
         * parameter both build a JSON string, and both build JSON null from null.
         */
        private const val TEMPLATE_HASH_EXPR =
            "encode(sha256(convert_to(jsonb_build_object('engine', :engine, 'dialect', CAST(:dialect AS TEXT)," +
                " 'is_library', :isLibrary, 'imports', CAST(:importsJson AS jsonb), 'body', :body)" +
                "::text, 'UTF8')), 'hex')"

        /** The version-detail column list, `t.name AS template_id` for the human id. */
        private const val DETAIL_COLS_PLAIN =
            "template_id, version, status, body_hash, created_at, created_by," +
                " released_at, released_by, updated_by, updated_at"

        private const val DETAIL_WHERE =
            "SELECT t.name AS template_id, v.version, v.status, v.body_hash, v.created_at, v.created_by," +
                " v.released_at, v.released_by, v.updated_by, v.updated_at" +
                " FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                " WHERE t.workspace_id = :workspaceId AND t.is_deleted = FALSE"

        private val INSERT_SQL =
            """
            WITH new_template AS (
                INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)
                VALUES (:name, :displayName, :description, 1, :workspaceId, :actor)
                RETURNING id, name, display_name, description
            ), new_version AS (
                INSERT INTO template_versions
                    (template_id, version, engine, type, dialect, is_library, imports_json, body,
                     status, body_hash, created_by, released_by, released_at)
                SELECT id, 1, :engine, CAST(:type AS TEXT), CAST(:dialect AS TEXT), :isLibrary, CAST(:importsJson AS jsonb), :body,
                       'RELEASED', $TEMPLATE_HASH_EXPR, :actor, :actor, NOW()
                  FROM new_template
                RETURNING template_id, version, engine, type, dialect, is_library, imports_json::TEXT AS imports_json,
                          body, created_at, created_by
            )
            SELECT t.name AS id, t.display_name, t.description,
                   v.version, v.engine, v.type, v.dialect, v.is_library, v.imports_json, v.body, v.created_at,
                   v.created_by AS version_created_by, 'RELEASED' AS status, $TEMPLATE_HASH_EXPR AS body_hash
              FROM new_template t
              JOIN new_version v ON v.template_id = t.id
            """.trimIndent()

        /** versioning §5.1 — copy-on-write draft create; index metadata moves at save time.
         *
         * The pre-allocated number is `max(existing version) + 1` — the pointer-plus-one in
         * §3.4's prose, made safe against any preserved-version import that landed a higher
         * number; the CONTENT still copies from the current released version.
         *
         * The `<> v.body_hash` predicate is the NO-OP guard (versioning §5.1, the pipeline
         * mirror): identical content must not burn a version number. The `noop` arm returns
         * the RELEASED detail in that case (status RELEASED is the no-op signal). `meta`
         * fires when EITHER arm matched — index metadata is not part of the hashed artifact,
         * so a content-identical save that renames `display_name`/`description` still moves
         * it (§6's asymmetry). Both arms join `guard`, so a stale precondition still yields
         * zero rows ⇒ 409. A draft edited back to its released parent is left alone (never
         * auto-discarded) — see [writeDraft]. */
        private val CREATE_DRAFT_SQL =
            """
            WITH guard AS (
                SELECT 1
                  FROM template_versions v JOIN templates t ON t.id = v.template_id
                 WHERE t.name = :name AND t.workspace_id = :workspaceId
                   AND v.version = t.current_version AND v.status = 'RELEASED' AND v.body_hash = :expectedHash
            ), draft AS (
                INSERT INTO template_versions
                    (template_id, version, engine, type, dialect, is_library, imports_json, body,
                     status, body_hash, created_by, updated_by, updated_at)
                SELECT v.template_id,
                       (SELECT COALESCE(MAX(d2.version), 0) + 1 FROM template_versions d2 WHERE d2.template_id = v.template_id),
                       :engine, CAST(:type AS TEXT), CAST(:dialect AS TEXT), :isLibrary,
                       CAST(:importsJson AS jsonb), :body, 'DRAFT', $TEMPLATE_HASH_EXPR, :actor, :actor, NOW()
                  FROM template_versions v JOIN templates t ON t.id = v.template_id
                 WHERE t.name = :name AND t.workspace_id = :workspaceId AND t.is_deleted = FALSE
                   AND v.version = t.current_version AND v.status = 'RELEASED'
                   AND $TEMPLATE_HASH_EXPR <> v.body_hash
                   AND NOT EXISTS (SELECT 1 FROM template_versions d
                                   WHERE d.template_id = v.template_id AND d.status = 'DRAFT')
                RETURNING $DETAIL_COLS_PLAIN
            ), noop AS (
                SELECT v.template_id, v.version, v.status, v.body_hash, v.created_at, v.created_by,
                       v.released_at, v.released_by, v.updated_by, v.updated_at
                  FROM template_versions v JOIN templates t ON t.id = v.template_id
                  JOIN guard ON TRUE
                 WHERE t.name = :name AND t.workspace_id = :workspaceId AND t.is_deleted = FALSE
                   AND v.version = t.current_version AND v.status = 'RELEASED'
                   AND $TEMPLATE_HASH_EXPR = v.body_hash
                   -- A draft that raced in owns the working state: identical content is then
                   -- a stale base (409), never a no-op (the pipeline mirror's reason).
                   AND NOT EXISTS (SELECT 1 FROM template_versions d
                                   WHERE d.template_id = v.template_id AND d.status = 'DRAFT')
            ), meta AS (
                UPDATE templates
                   SET display_name = :displayName, description = :description, updated_at = NOW()
                 WHERE name = :name AND workspace_id = :workspaceId AND is_deleted = FALSE
                   AND (EXISTS (SELECT 1 FROM draft) OR EXISTS (SELECT 1 FROM noop))
                RETURNING 1
            )
            SELECT t.name AS template_id, draft.version, draft.status, draft.body_hash,
                   draft.created_at, draft.created_by, draft.released_at, draft.released_by,
                   draft.updated_by, draft.updated_at
              FROM draft, guard, meta
              JOIN templates t ON t.name = :name
            UNION ALL
            SELECT t.name AS template_id, noop.version, noop.status, noop.body_hash,
                   noop.created_at, noop.created_by, noop.released_at, noop.released_by,
                   noop.updated_by, noop.updated_at
              FROM noop, meta
              JOIN templates t ON t.name = :name
            """.trimIndent()

        /** versioning §5.2 — in-place draft write; index metadata moves at save time. */
        private val WRITE_DRAFT_SQL =
            """
            WITH written AS (
                UPDATE template_versions v
                   SET engine = :engine, type = CAST(:type AS TEXT), dialect = CAST(:dialect AS TEXT),
                       is_library = :isLibrary,
                       imports_json = CAST(:importsJson AS jsonb), body = :body,
                       body_hash = $TEMPLATE_HASH_EXPR,
                       updated_by = :actor, updated_at = NOW()
                  FROM templates t
                 WHERE t.name = :name AND t.workspace_id = :workspaceId AND t.is_deleted = FALSE
                   AND v.template_id = t.id AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
                RETURNING v.template_id, v.version, v.status, v.body_hash, v.created_at, v.created_by,
                          v.released_at, v.released_by, v.updated_by, v.updated_at
            ), meta AS (
                UPDATE templates
                   SET display_name = :displayName, description = :description, updated_at = NOW()
                 WHERE name = :name AND workspace_id = :workspaceId AND is_deleted = FALSE
                   AND EXISTS (SELECT 1 FROM written)
                RETURNING 1
            )
            SELECT t.name AS template_id, w.version, w.status, w.body_hash, w.created_at, w.created_by,
                   w.released_at, w.released_by, w.updated_by, w.updated_at
              FROM written w
              JOIN templates t ON t.id = w.template_id, meta
            """.trimIndent()

        /** versioning §5.3 — release: flip + pointer bump, one statement. */
        private val RELEASE_DRAFT_SQL =
            """
            WITH locked AS (
                UPDATE template_versions v
                   SET status = 'RELEASED', released_at = NOW(), released_by = :actor
                  FROM templates t
                 WHERE t.name = :name AND t.workspace_id = :workspaceId AND t.is_deleted = FALSE
                   AND v.template_id = t.id AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
                RETURNING v.template_id, v.version, v.status, v.body_hash, v.created_at, v.created_by,
                          v.released_at, v.released_by, v.updated_by, v.updated_at
            ), bumped AS (
                UPDATE templates
                   SET current_version = (SELECT version FROM locked), updated_at = NOW()
                 WHERE name = :name AND workspace_id = :workspaceId AND is_deleted = FALSE
                   AND EXISTS (SELECT 1 FROM locked)
                RETURNING id
            )
            SELECT t.name AS template_id, l.version, l.status, l.body_hash, l.created_at, l.created_by,
                   l.released_at, l.released_by, l.updated_by, l.updated_at
              FROM locked l
              JOIN templates t ON t.id = l.template_id, bumped b
            """.trimIndent()

        /** The version-less import path: next version appended directly as RELEASED. */
        private val APPEND_RELEASED_SQL =
            """
            WITH bumped AS (
                UPDATE templates
                   SET current_version = current_version + 1,
                       display_name = :displayName,
                       description = :description,
                       updated_at = NOW()
                  WHERE name = :name AND workspace_id = :workspaceId AND is_deleted = FALSE
                RETURNING id, name, display_name, description, current_version
            ), new_version AS (
                INSERT INTO template_versions
                    (template_id, version, engine, type, dialect, is_library, imports_json, body,
                     status, body_hash, created_by, released_by, released_at)
                SELECT id, current_version, :engine, CAST(:type AS TEXT), CAST(:dialect AS TEXT), :isLibrary, CAST(:importsJson AS jsonb), :body,
                       'RELEASED', $TEMPLATE_HASH_EXPR, :actor, :actor, NOW()
                  FROM bumped
                RETURNING template_id, version, engine, type, dialect, is_library, imports_json::TEXT AS imports_json,
                          body, created_at, created_by
            )
            SELECT t.name AS id, t.display_name, t.description,
                   v.version, v.engine, v.type, v.dialect, v.is_library, v.imports_json, v.body, v.created_at,
                   v.created_by AS version_created_by, 'RELEASED' AS status, $TEMPLATE_HASH_EXPR AS body_hash
              FROM bumped t
              JOIN new_version v ON v.template_id = t.id
            """.trimIndent()

        /** versioning §9.2 — new template at the source's exact version number. */
        private val IMPORT_NEW_TEMPLATE_SQL =
            """
            WITH new_template AS (
                INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)
                VALUES (:name, :displayName, :description, :version, :workspaceId, :actor)
                RETURNING id, name, display_name, description, current_version
            ), new_version AS (
                INSERT INTO template_versions
                    (template_id, version, engine, type, dialect, is_library, imports_json, body,
                     status, body_hash, created_by, released_by, released_at)
                SELECT id, :version, :engine, CAST(:type AS TEXT), CAST(:dialect AS TEXT), :isLibrary, CAST(:importsJson AS jsonb), :body,
                       'RELEASED', :bodyHash, :actor, :actor, COALESCE(:releasedAt, NOW())
                  FROM new_template
                RETURNING template_id, version, engine, type, dialect, is_library, imports_json::TEXT AS imports_json,
                          body, created_at, created_by
            )
            SELECT t.name AS id, t.display_name, t.description,
                   v.version, v.engine, v.type, v.dialect, v.is_library, v.imports_json, v.body, v.created_at,
                   v.created_by AS version_created_by, 'RELEASED' AS status, :bodyHash AS body_hash
              FROM new_template t
              JOIN new_version v ON v.template_id = t.id
            """.trimIndent()

        /** versioning §9.2 — exact-version insert onto an existing template; metadata rides only when it is the new latest. */
        private val INSERT_RELEASED_VERSION_SQL =
            """
            WITH ins AS (
                INSERT INTO template_versions
                    (template_id, version, engine, type, dialect, is_library, imports_json, body,
                     status, body_hash, created_by, released_by, released_at)
                SELECT t.id, :version, :engine, CAST(:type AS TEXT), CAST(:dialect AS TEXT), :isLibrary, CAST(:importsJson AS jsonb), :body,
                       'RELEASED', :bodyHash, :actor, :actor, COALESCE(:releasedAt, NOW())
                  FROM templates t
                 WHERE t.name = :name AND t.workspace_id = :workspaceId AND t.is_deleted = FALSE
                   AND NOT EXISTS (SELECT 1 FROM template_versions v
                                    WHERE v.template_id = t.id AND v.version = :version)
                RETURNING template_id, version, status, body_hash, created_at, created_by,
                          released_at, released_by, updated_by, updated_at
            ), bumped AS (
                UPDATE templates
                   SET current_version = GREATEST(current_version, :version),
                       display_name = CASE WHEN :version > current_version THEN :displayName ELSE display_name END,
                       description = CASE WHEN :version > current_version THEN :description ELSE description END,
                       updated_at = NOW()
                 WHERE name = :name AND workspace_id = :workspaceId AND is_deleted = FALSE
                   AND EXISTS (SELECT 1 FROM ins)
                RETURNING 1
            )
            SELECT t.name AS template_id, i.version, i.status, i.body_hash, i.created_at, i.created_by,
                   i.released_at, i.released_by, i.updated_by, i.updated_at
              FROM ins i
              JOIN templates t ON t.id = i.template_id, bumped
            """.trimIndent()

        private val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Template(
                    id = rs.getString("id"),
                    version = rs.getInt("version"),
                    engine = rs.getString("engine"),
                    type = TemplateType.fromWire(rs.getString("type")) ?: TemplateType.SQL,
                    dialect = rs.getString("dialect")?.let(Dialect::fromWire),
                    displayName = rs.getString("display_name"),
                    description = rs.getString("description"),
                    imports = TemplateJson.readImports(rs.getString("imports_json")),
                    body = rs.getString("body"),
                    isLibrary = rs.getBoolean("is_library"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    createdBy = rs.getObject("version_created_by", UUID::class.java),
                    status = PipelineVersionStatus.fromWire(rs.getString("status")),
                    bodyHash = rs.getString("body_hash"),
                )
            }

        private val DETAIL_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                TemplateVersionDetail(
                    templateId = rs.getString("template_id"),
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

        private val VERSION_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                TemplateVersion(
                    id = rs.getString("template_id"),
                    version = rs.getInt("version"),
                    engine = rs.getString("engine"),
                    type = TemplateType.fromWire(rs.getString("type")) ?: TemplateType.SQL,
                    dialect = rs.getString("dialect")?.let(Dialect::fromWire),
                    isLibrary = rs.getBoolean("is_library"),
                    imports = TemplateJson.readImports(rs.getString("imports_json")),
                    body = rs.getString("body"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                )
            }
    }
}
