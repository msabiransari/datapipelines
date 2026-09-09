package co.datapipelines.templates

import co.datapipelines.pipeline.CreateLifecycle
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
    /** The lifecycle status — 101's version verbs made the version list a lifecycle surface. */
    val status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
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
                    " AND $TEMPLATE_LIVE_T AND v.version = t.current_version",
                mapOf("name" to id, "workspaceId" to workspaceId),
                MAPPER,
            ).singleOrNull()

    /**
     * The **working version's** projection (versioning §7.1): the DRAFT when one exists, else the
     * current RELEASED version. Null only when the template is unknown or soft-deleted.
     *
     * [findLatest] answers "what is released"; this answers "what is the template right now", and
     * since D55 the two differ for every template between its creation and its first release —
     * where [findLatest] is null, so an authoring read built on it would 404 on a template the
     * caller had just created. Composed from two existing reads rather than a third SQL predicate:
     * the draft pointer is a single-row lookup and the exact-version read is already the honest one.
     */
    fun findWorking(
        workspaceId: UUID,
        id: String,
    ): Template? =
        findDraftDetail(workspaceId, id)
            ?.let { findVersion(workspaceId, id, it.version) }
            ?: findLatest(workspaceId, id)

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
     * The `$TEMPLATE_LIVE_T` predicate is the derived entity status (versioning §3.2, 101) —
     * a DISCARDED template (every version discarded) leaves the listing exactly as the old
     * soft-delete predicate removed deleted rows.
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
     * the root and, since 077, it has no leaves at all: §4.1 requires a folder, so every
     * template name has a prefix and the root level is a list of folders.
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

    /**
     * `current_version` for each of [ids] live in [workspaceId] — the used-by service's
     * latest-RELEASED lookup (040 D5). By the version lifecycle's invariant `current_version`
     * IS the latest released version (a draft never moves it), so this read needs no status
     * filter; soft-deleted templates are absent, which the upgrade signal reads as "nothing to
     * upgrade to". Since D55 a NEVER-RELEASED template is absent too (its pointer is NULL) —
     * there is no latest-released version to report, and reporting `getInt`'s 0 would name a
     * version that cannot exist. Empty [ids] short-circuits — an `IN ()` list would not even prepare.
     */
    fun findCurrentVersions(
        workspaceId: UUID,
        ids: Collection<String>,
    ): Map<String, Int> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc
            .query(
                """
                SELECT t.name, t.current_version
                  FROM templates t
                 WHERE t.workspace_id = :workspaceId AND $TEMPLATE_LIVE_T AND t.name IN (:names)
                   -- D55/V18: NULL means never released. A never-released template has no
                   -- latest-RELEASED version to report, and reporting 0 (getInt's answer for
                   -- NULL) would name a version that cannot exist.
                   AND t.current_version IS NOT NULL
                """.trimIndent(),
                mapOf("workspaceId" to workspaceId, "names" to ids),
            ) { rs, _ -> rs.getString("name") to rs.getInt("current_version") }
            .toMap()
    }

    /** Version metadata, newest first (§9 list-versions). */
    fun listVersions(
        workspaceId: UUID,
        id: String,
    ): List<TemplateVersionSummary> =
        jdbc.query(
            """
            SELECT t.name AS template_id, v.version, v.status, v.created_at, v.created_by
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
                status = PipelineVersionStatus.fromWire(rs.getString("status")),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java),
            )
        }

    /**
     * Inserts the template and its version 1 together in [workspaceId], returning what the
     * database stored.
     *
     * [lifecycle] says which of §3.2's two create paths this is, and it is required: authoring
     * (`POST /templates`, `templates_create`, the editor) lands version 1 **DRAFT** with
     * `current_version` NULL (D55 — a human releases), while a promotion or seed import lands it
     * RELEASED. A pipeline may pin a DRAFT template version while iterating; that pin only
     * becomes an error when the PIPELINE is released (versioning §6, templates lock first).
     *
     * [draft] `id` is auto-generated when omitted (templates.md §3.2). The final `SELECT` reads
     * back server-assigned `created_at` / `updated_at`, never a hand-built value (metadata-db
     * §6.1).
     */
    fun create(
        workspaceId: UUID,
        draft: TemplateDraft,
        createdBy: UUID,
        lifecycle: CreateLifecycle,
    ): Template {
        val id = draft.id ?: generateId()
        // §5.3 (046): creation is where a null payload type becomes the explicit `sql` default,
        // so every caller of the create path stores a resolved type without knowing the rule.
        val resolved = TemplateTypeRule.forCreate(draft)
        return mappingDuplicateName(id) {
            jdbc
                .query(
                    if (lifecycle == CreateLifecycle.DRAFT) INSERT_DRAFT_SQL else INSERT_SQL,
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
                       v.released_at, v.released_by, v.discarded_at, v.discarded_by, v.updated_by, v.updated_at
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
     * Purge the draft (versioning §5.4, 101): the version row is hard-deleted — nothing
     * references a `template_versions` row by FK, so there are no executions to take with
     * it. When the draft was the sole version the ENTITY row goes too (D57's twin: an
     * entity holds >= 1 version or does not exist); when the draft had become
     * `current_version` (the development fallback) the pointer recomputes.
     *
     * [expectedHash] is the §4.2 precondition; null targets the draft without a hash (the
     * 101 admin verb). Returns null when no DRAFT matched; the caller re-reads.
     */
    fun purgeDraft(
        workspaceId: UUID,
        id: String,
        expectedHash: String?,
        draftEligible: Boolean,
    ): Boolean {
        val hashGuard = if (expectedHash == null) "" else " AND v.body_hash = :expectedHash"
        val params =
            mutableMapOf<String, Any?>(
                "name" to id,
                "workspaceId" to workspaceId,
                "draftEligible" to draftEligible,
            )
        if (expectedHash != null) params["expectedHash"] = expectedHash

        val purged =
            jdbc.query(
                "DELETE FROM template_versions v" +
                    " USING templates t" +
                    " WHERE t.name = :name AND t.workspace_id = :workspaceId" +
                    " AND v.template_id = t.id AND v.status = 'DRAFT'$hashGuard" +
                    " RETURNING v.version",
                params,
            ) { rs, _ -> rs.getInt("version") }.singleOrNull() ?: return false

        val remaining =
            checkNotNull(
                jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM template_versions v JOIN templates t ON t.id = v.template_id
                     WHERE t.name = :name AND t.workspace_id = :workspaceId
                    """.trimIndent(),
                    mapOf("name" to id, "workspaceId" to workspaceId),
                    Int::class.java,
                ),
            )
        if (remaining == 0) {
            // Sole version ⇒ entity purge (D57's twin): versions cascade on the entity delete.
            jdbc.update(
                "DELETE FROM templates WHERE name = :name AND workspace_id = :workspaceId",
                mapOf("name" to id, "workspaceId" to workspaceId),
            )
            return true
        }
        jdbc.update(T_POINTER_FALLBACK_SQL, params + ("version" to purged) + ("name" to id))
        return true
    }

    /**
     * Discard a RELEASED template version (§3.1, 101): status flips to DISCARDED with the
     * stamps, pointer recomputes only when this version WAS the pointer. Graph rule 1's
     * guard rides the statement: a LIVE pipeline version pinning `name@version` (the same
     * lateral [PipelineRepository.findLiveVersionsPinningTemplateVersion] runs — kept in
     * step by the model test's pin invariant) refuses the flip by returning zero rows.
     * Returns null when the target was not RELEASED or is pinned.
     */
    fun discardVersion(
        workspaceId: UUID,
        id: String,
        version: Int,
        actor: UUID,
        draftEligible: Boolean,
    ): TemplateVersionDetail? =
        jdbc
            .query(
                T_DISCARD_VERSION_SQL,
                mapOf(
                    "name" to id,
                    "workspaceId" to workspaceId,
                    "version" to version,
                    "actor" to actor,
                    "draftEligible" to draftEligible,
                ),
                DETAIL_MAPPER,
            ).singleOrNull()

    /**
     * Restore a DISCARDED template version (§3.1, 101) — stamps back to RELEASED, discard
     * stamps cleared, `released_at`/`released_by` untouched; pointer moves only
     * above-current-or-NULL. No live predicate: restoring the first version of a DISCARDED
     * template is the one way back. Returns null when the target was not DISCARDED.
     */
    fun restoreVersion(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): TemplateVersionDetail? =
        jdbc
            .query(
                T_RESTORE_VERSION_SQL,
                mapOf("name" to id, "workspaceId" to workspaceId, "version" to version),
                DETAIL_MAPPER,
            ).singleOrNull()

    /**
     * Manual switch (§3.4, 101): `current = version`, which must be LIVE and
     * posture-eligible — eligibility rides the statement's EXISTS. Returns the new current
     * version, or null when not eligible.
     */
    fun switchCurrent(
        workspaceId: UUID,
        id: String,
        version: Int,
        draftEligible: Boolean,
    ): Int? =
        jdbc
            .query(
                T_SWITCH_CURRENT_SQL,
                mapOf("name" to id, "workspaceId" to workspaceId, "version" to version, "draftEligible" to draftEligible),
            ) { rs, _ -> rs.getInt("current_version") }
            .singleOrNull()

    /**
     * The entity purge's offer (versioning §3.5, 101): the DRAFT-ONLY templates whose only
     * pinner among LIVE pipeline versions is [pipelineId] — this pipeline's private
     * work-in-progress, orphaned by its purge. Name-addressed by the pipeline's id.
     */
    fun exclusiveDraftTemplateIds(
        workspaceId: UUID,
        pipelineId: java.util.UUID,
    ): List<String> =
        jdbc
            .query(
                EXCLUSIVE_DRAFT_TEMPLATES_SQL,
                mapOf("workspaceId" to workspaceId, "pipelineId" to pipelineId),
            ) { rs, _ -> rs.getString("name") }

    /** Deletes the template entity row; versions cascade (V1's `ON DELETE CASCADE`). True when the row went. */
    fun deleteTemplateRow(
        workspaceId: UUID,
        id: String,
    ): Boolean =
        jdbc.update(
            "DELETE FROM templates WHERE name = :name AND workspace_id = :workspaceId",
            mapOf("name" to id, "workspaceId" to workspaceId),
        ) > 0

    /** True when the template entity is LIVE (ACTIVE) — the §3.2 derivation read directly. */
    fun hasLiveVersion(
        workspaceId: UUID,
        id: String,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM template_versions lv JOIN templates t ON t.id = lv.template_id
                 WHERE t.name = :name AND t.workspace_id = :workspaceId
                   AND lv.status IN ('DRAFT','RELEASED')
            )
            """.trimIndent(),
            mapOf("name" to id, "workspaceId" to workspaceId),
            Boolean::class.java,
        ) == true

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

        /**
         * A generated name for a create that omitted one (templates.md §3.2). A hex suffix
         * keeps it inside the §4.1 grammar and collision-free.
         *
         * The `test/` prefix is not decoration: §4.1 has required a folder since 077, and this
         * path RUNS AFTER validation — the validator only sees an id the caller supplied — so
         * a flat `template_1a2b…` here would write a name the next save, the tree UI and the
         * `V12` deploy gate all refuse. `test/` is the sanctioned scratch folder (§15.2), and
         * a template whose author did not name it is scratch by definition. Pinned by
         * `TemplateRepositoryIntegrationTest`, which asserts the generated id against the
         * grammar object rather than against a literal shape.
         */
        private fun generateId(): String =
            "test/template_" +
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
         * The working-version page predicate of [list], shared with [count] (034 E3)
         *
         * **The COALESCE is D55.** `current_version` is NULL until a human releases, so a plain
         * `v.version = t.current_version` made every freshly created template INVISIBLE — absent
         * from the explorer, from `templates_list` and from its own count. The pointer still wins
         * whenever there IS a release (a template with a draft over a release lists its RELEASED
         * projection, and the `drafts` badge is what says a draft exists); the draft fills the gap
         * only where nothing has been released at all, because there it is the only version the
         * template has.
         * so the page and its total can never disagree. Every optional filter is CAST in the
         * SQL: a bare `? IS NULL` gives Postgres no type to infer and the statement will not
         * even prepare.
         */
        private val LIST_WHERE =
            """
            WHERE $TEMPLATE_LIVE_T
              AND t.workspace_id = :workspaceId
              AND v.version = COALESCE(
                    t.current_version,
                    (SELECT MAX(d.version) FROM template_versions d
                      WHERE d.template_id = t.id AND d.status = 'DRAFT')
                  )
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
         * The working-version predicate of ONE tree level, shared by [listChildFolders],
         * [listChildTemplates] and [countChildTemplates] so a level, its folders and its total can
         * never disagree. It carries [LIST_WHERE]'s D55 COALESCE for the same reason: a
         * never-released template must still have a row in the tree.
         *
         * It is [LIST_WHERE] minus the `q` clause (browse and search are different
         * presentations, §9.2) plus the prefix scope. Every optional filter is CAST in the
         * SQL for the same reason as [LIST_WHERE]: a bare `? IS NULL` gives Postgres no type
         * to infer and the statement will not even prepare.
         */
        private val TREE_WHERE =
            """
            WHERE $TEMPLATE_LIVE_T
              AND t.workspace_id = :workspaceId
              AND v.version = COALESCE(
                    t.current_version,
                    (SELECT MAX(d.version) FROM template_versions d
                      WHERE d.template_id = t.id AND d.status = 'DRAFT')
                  )
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
                " released_at, released_by, discarded_at, discarded_by, updated_by, updated_at"

        // Workspace-scoped only, deliberately WITHOUT an entity-live filter (101): a
        // DISCARDED template entity's version details are exactly what restore and §9.2's
        // import classification must read.
        private const val DETAIL_WHERE =
            "SELECT t.name AS template_id, v.version, v.status, v.body_hash, v.created_at, v.created_by," +
                " v.released_at, v.released_by, v.discarded_at, v.discarded_by, v.updated_by, v.updated_at" +
                " FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                " WHERE t.workspace_id = :workspaceId"

        /**
         * The derived entity status (versioning §3.2, since V19 retired `is_deleted`): a
         * template is LIVE while it holds >= 1 DRAFT or RELEASED version. `t`-aliased probe.
         */
        private const val TEMPLATE_LIVE_T =
            "EXISTS (SELECT 1 FROM template_versions lv" +
                " WHERE lv.template_id = t.id AND lv.status IN ('DRAFT','RELEASED'))"

        /** [TEMPLATE_LIVE_T] for unaliased `UPDATE templates` contexts. */
        private const val TEMPLATE_LIVE =
            "EXISTS (SELECT 1 FROM template_versions lv" +
                " WHERE lv.template_id = templates.id AND lv.status IN ('DRAFT','RELEASED'))"

        /**
         * §3.2 as ruled by D55 — the authoring create: version 1 lands DRAFT,
         * `templates.current_version` stays NULL, and `updated_by`/`updated_at` are stamped the
         * way a draft WRITE stamps them (V6) so the 409's `details` are honest from the start.
         *
         * [INSERT_SQL] is its RELEASED twin and is NOT dead code: `TemplateImportService`'s
         * version-less path (promotion, the example/lake seeders) lands released content.
         */
        private val INSERT_DRAFT_SQL =
            """
            WITH new_template AS (
                INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)
                VALUES (:name, :displayName, :description, NULL, :workspaceId, :actor)
                RETURNING id, name, display_name, description
            ), new_version AS (
                INSERT INTO template_versions
                    (template_id, version, engine, type, dialect, is_library, imports_json, body,
                     status, body_hash, created_by, updated_by, updated_at)
                SELECT id, 1, :engine, CAST(:type AS TEXT), CAST(:dialect AS TEXT), :isLibrary, CAST(:importsJson AS jsonb), :body,
                       'DRAFT', $TEMPLATE_HASH_EXPR, :actor, :actor, NOW()
                  FROM new_template
                RETURNING template_id, version, engine, type, dialect, is_library, imports_json::TEXT AS imports_json,
                          body, created_at, created_by
            )
            SELECT t.name AS id, t.display_name, t.description,
                   v.version, v.engine, v.type, v.dialect, v.is_library, v.imports_json, v.body, v.created_at,
                   v.created_by AS version_created_by, 'DRAFT' AS status, $TEMPLATE_HASH_EXPR AS body_hash
              FROM new_template t
              JOIN new_version v ON v.template_id = t.id
            """.trimIndent()

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
                 WHERE t.name = :name AND t.workspace_id = :workspaceId AND $TEMPLATE_LIVE_T
                   AND v.version = t.current_version AND v.status = 'RELEASED'
                   AND $TEMPLATE_HASH_EXPR <> v.body_hash
                   AND NOT EXISTS (SELECT 1 FROM template_versions d
                                   WHERE d.template_id = v.template_id AND d.status = 'DRAFT')
                RETURNING $DETAIL_COLS_PLAIN
            ), noop AS (
                SELECT v.template_id, v.version, v.status, v.body_hash, v.created_at, v.created_by,
                       v.released_at, v.released_by, v.discarded_at, v.discarded_by, v.updated_by, v.updated_at
                  FROM template_versions v JOIN templates t ON t.id = v.template_id
                  JOIN guard ON TRUE
                 WHERE t.name = :name AND t.workspace_id = :workspaceId AND $TEMPLATE_LIVE_T
                   AND v.version = t.current_version AND v.status = 'RELEASED'
                   AND $TEMPLATE_HASH_EXPR = v.body_hash
                   -- A draft that raced in owns the working state: identical content is then
                   -- a stale base (409), never a no-op (the pipeline mirror's reason).
                   AND NOT EXISTS (SELECT 1 FROM template_versions d
                                   WHERE d.template_id = v.template_id AND d.status = 'DRAFT')
            ), meta AS (
                UPDATE templates
                   SET display_name = :displayName, description = :description, updated_at = NOW()
                 WHERE name = :name AND workspace_id = :workspaceId AND $TEMPLATE_LIVE
                   AND (EXISTS (SELECT 1 FROM draft) OR EXISTS (SELECT 1 FROM noop))
                RETURNING 1
            )
            SELECT t.name AS template_id, draft.version, draft.status, draft.body_hash,
                   draft.created_at, draft.created_by, draft.released_at, draft.released_by, draft.discarded_at, draft.discarded_by,
                   draft.updated_by, draft.updated_at
              FROM draft, guard, meta
              JOIN templates t ON t.name = :name
            UNION ALL
            SELECT t.name AS template_id, noop.version, noop.status, noop.body_hash,
                   noop.created_at, noop.created_by, noop.released_at, noop.released_by, noop.discarded_at, noop.discarded_by,
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
                 WHERE t.name = :name AND t.workspace_id = :workspaceId AND $TEMPLATE_LIVE_T
                   AND v.template_id = t.id AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
                RETURNING v.template_id, v.version, v.status, v.body_hash, v.created_at, v.created_by,
                          v.released_at, v.released_by, v.discarded_at, v.discarded_by, v.updated_by, v.updated_at
            ), meta AS (
                UPDATE templates
                   SET display_name = :displayName, description = :description, updated_at = NOW()
                 WHERE name = :name AND workspace_id = :workspaceId AND $TEMPLATE_LIVE
                   AND EXISTS (SELECT 1 FROM written)
                RETURNING 1
            )
            SELECT t.name AS template_id, w.version, w.status, w.body_hash, w.created_at, w.created_by,
                   w.released_at, w.released_by, w.discarded_at, w.discarded_by, w.updated_by, w.updated_at
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
                 WHERE t.name = :name AND t.workspace_id = :workspaceId AND $TEMPLATE_LIVE_T
                   AND v.template_id = t.id AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
                RETURNING v.template_id, v.version, v.status, v.body_hash, v.created_at, v.created_by,
                          v.released_at, v.released_by, v.discarded_at, v.discarded_by, v.updated_by, v.updated_at
            ), bumped AS (
                UPDATE templates
                   SET current_version = (SELECT version FROM locked), updated_at = NOW()
                 WHERE name = :name AND workspace_id = :workspaceId AND $TEMPLATE_LIVE
                   AND EXISTS (SELECT 1 FROM locked)
                RETURNING id
            )
            SELECT t.name AS template_id, l.version, l.status, l.body_hash, l.created_at, l.created_by,
                   l.released_at, l.released_by, l.discarded_at, l.discarded_by, l.updated_by, l.updated_at
              FROM locked l
              JOIN templates t ON t.id = l.template_id, bumped b
            """.trimIndent()

        /**
         * versioning §9.2 + D60 (101) — the version-less import: allocation is
         * `max(version) + 1` (this rewrite also closes the latent V18 defect where
         * `current_version + 1` computed NULL on a never-released template — the pipeline
         * twin had the COALESCE, this side did not), and the pointer moves ONLY when the
         * entity has no current at all. Index metadata rides that move, and nothing else.
         */
        private val APPEND_RELEASED_SQL =
            """
            WITH alloc AS (
                SELECT COALESCE(MAX(v.version), 0) + 1 AS next
                  FROM template_versions v JOIN templates t ON t.id = v.template_id
                 WHERE t.name = :name AND t.workspace_id = :workspaceId
            ), new_version AS (
                INSERT INTO template_versions
                    (template_id, version, engine, type, dialect, is_library, imports_json, body,
                     status, body_hash, created_by, released_by, released_at)
                SELECT t.id, alloc.next, :engine, CAST(:type AS TEXT), CAST(:dialect AS TEXT), :isLibrary, CAST(:importsJson AS jsonb), :body,
                       'RELEASED', $TEMPLATE_HASH_EXPR, :actor, :actor, NOW()
                  FROM templates t, alloc
                 WHERE t.name = :name AND t.workspace_id = :workspaceId
                RETURNING template_id, version, engine, type, dialect, is_library, imports_json::TEXT AS imports_json,
                          body, created_at, created_by
            ), bumped AS (
                UPDATE templates
                   SET current_version = COALESCE(current_version, (SELECT version FROM new_version)),
                       display_name = CASE WHEN current_version IS NULL THEN :displayName ELSE display_name END,
                       description = CASE WHEN current_version IS NULL THEN :description ELSE description END,
                       updated_at = NOW()
                  WHERE name = :name AND workspace_id = :workspaceId
                    AND EXISTS (SELECT 1 FROM new_version)
                RETURNING id, name, display_name, description, current_version
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

        /**
         * versioning §9.2 + D60 (101) — exact-version insert onto an existing template.
         * The pointer moves ONLY when it is NULL (first import onto a current-less entity);
         * index metadata rides that same move. No live predicate: the import classification
         * must reach DISCARDED entities too (§9.2's present-DISCARDED row).
         */
        private val INSERT_RELEASED_VERSION_SQL =
            """
            WITH ins AS (
                INSERT INTO template_versions
                    (template_id, version, engine, type, dialect, is_library, imports_json, body,
                     status, body_hash, created_by, released_by, released_at)
                SELECT t.id, :version, :engine, CAST(:type AS TEXT), CAST(:dialect AS TEXT), :isLibrary, CAST(:importsJson AS jsonb), :body,
                       'RELEASED', :bodyHash, :actor, :actor, COALESCE(:releasedAt, NOW())
                  FROM templates t
                 WHERE t.name = :name AND t.workspace_id = :workspaceId
                   AND NOT EXISTS (SELECT 1 FROM template_versions v
                                    WHERE v.template_id = t.id AND v.version = :version)
                RETURNING template_id, version, status, body_hash, created_at, created_by,
                          released_at, released_by, discarded_at, discarded_by, updated_by, updated_at
            ), bumped AS (
                UPDATE templates
                   SET current_version = COALESCE(current_version, :version),
                       display_name = CASE WHEN current_version IS NULL THEN :displayName ELSE display_name END,
                       description = CASE WHEN current_version IS NULL THEN :description ELSE description END,
                       updated_at = NOW()
                 WHERE name = :name AND workspace_id = :workspaceId
                   AND EXISTS (SELECT 1 FROM ins)
                RETURNING 1
            )
            SELECT t.name AS template_id, i.version, i.status, i.body_hash, i.created_at, i.created_by,
                   i.released_at, i.released_by, i.discarded_at, i.discarded_by, i.updated_by, i.updated_at
              FROM ins i
              JOIN templates t ON t.id = i.template_id, bumped
            """.trimIndent()

        /** §3.4 (101) — the template pointer fallback; the twin of the pipeline statement. */
        private val T_POINTER_FALLBACK_SQL =
            """
            UPDATE templates
               SET current_version = (
                       SELECT MAX(lv.version) FROM template_versions lv JOIN templates t ON t.id = lv.template_id
                        WHERE t.name = :name AND t.workspace_id = :workspaceId
                          AND (lv.status = 'RELEASED' OR (:draftEligible AND lv.status = 'DRAFT'))
                   ),
                   updated_at = NOW()
             WHERE name = :name AND workspace_id = :workspaceId
               AND current_version = :version
            """.trimIndent()

        /**
         * §3.1 (101) — discard a RELEASED template version: flip + stamps + pointer
         * recompute in one statement, with graph rule 1's pin guard riding the flip (the
         * same lateral [PipelineRepository.findLiveVersionsPinningTemplateVersion] runs —
         * one expression in two files, tied together by the model test's pin invariant).
         */
        private val T_DISCARD_VERSION_SQL =
            """
            WITH flipped AS (
                UPDATE template_versions v
                   SET status = 'DISCARDED', discarded_at = NOW(), discarded_by = :actor
                  FROM templates t
                 WHERE t.name = :name AND t.workspace_id = :workspaceId
                   AND v.template_id = t.id AND v.version = :version AND v.status = 'RELEASED'
                   AND NOT EXISTS (
                       SELECT 1 FROM pipelines pp
                       JOIN pipeline_versions pv ON pv.pipeline_id = pp.id
                       CROSS JOIN LATERAL jsonb_array_elements(pv.body_json->'nodes') AS pnode
                        WHERE pp.workspace_id = :workspaceId
                          AND pv.status IN ('DRAFT','RELEASED')
                          AND pnode->'template'->>'id' = :name
                          AND (pnode->'template'->>'version')::int = :version
                   )
                RETURNING v.template_id, v.version, v.status, v.body_hash, v.created_at, v.created_by,
                          v.released_at, v.released_by, v.discarded_at, v.discarded_by, v.updated_by, v.updated_at
            ), bumped AS (
                UPDATE templates t
                   SET current_version = CASE
                           WHEN t.current_version = :version THEN (
                               SELECT MAX(lv.version) FROM template_versions lv
                                WHERE lv.template_id = t.id
                                  AND (lv.status = 'RELEASED' OR (:draftEligible AND lv.status = 'DRAFT'))
                           )
                           ELSE t.current_version
                       END,
                       updated_at = NOW()
                 WHERE t.name = :name AND t.workspace_id = :workspaceId
                   AND EXISTS (SELECT 1 FROM flipped)
                RETURNING 1
            )
            SELECT t.name AS template_id, f.version, f.status, f.body_hash, f.created_at, f.created_by,
                   f.released_at, f.released_by, f.discarded_at, f.discarded_by, f.updated_by, f.updated_at
              FROM flipped f
              JOIN templates t ON t.id = f.template_id, bumped
            """.trimIndent()

        /** §3.1 (101) — restore a DISCARDED template version; `GREATEST(COALESCE(cur,0), v)` is D60's restore rule. */
        private val T_RESTORE_VERSION_SQL =
            """
            WITH restored AS (
                UPDATE template_versions v
                   SET status = 'RELEASED', discarded_at = NULL, discarded_by = NULL
                  FROM templates t
                 WHERE t.name = :name AND t.workspace_id = :workspaceId
                   AND v.template_id = t.id AND v.version = :version AND v.status = 'DISCARDED'
                RETURNING v.template_id, v.version, v.status, v.body_hash, v.created_at, v.created_by,
                          v.released_at, v.released_by, v.discarded_at, v.discarded_by, v.updated_by, v.updated_at
            ), bumped AS (
                UPDATE templates
                   SET current_version = GREATEST(COALESCE(current_version, 0), (SELECT version FROM restored)),
                       updated_at = NOW()
                 WHERE name = :name AND workspace_id = :workspaceId
                   AND EXISTS (SELECT 1 FROM restored)
                RETURNING 1
            )
            SELECT t.name AS template_id, r.version, r.status, r.body_hash, r.created_at, r.created_by,
                   r.released_at, r.released_by, r.discarded_at, r.discarded_by, r.updated_by, r.updated_at
              FROM restored r
              JOIN templates t ON t.id = r.template_id, bumped
            """.trimIndent()

        /** §3.4 (101) — the template manual switch; eligibility rides the EXISTS. */
        private val T_SWITCH_CURRENT_SQL =
            """
            UPDATE templates t
               SET current_version = :version, updated_at = NOW()
             WHERE t.name = :name AND t.workspace_id = :workspaceId
               AND EXISTS (
                   SELECT 1 FROM template_versions v
                    WHERE v.template_id = t.id AND v.version = :version
                      AND (v.status = 'RELEASED' OR (:draftEligible AND v.status = 'DRAFT'))
               )
            RETURNING t.current_version
            """.trimIndent()

        /**
         * §3.5 (101) — the entity purge's offer: DRAFT-ONLY templates whose only pinner
         * among LIVE pipeline versions is the pipeline being purged. The pin probes walk the
         * nodes laterally and match the template pin exactly — a text LIKE over the body
         * would false-positive on a name that merely appears in prose.
         */
        private val EXCLUSIVE_DRAFT_TEMPLATES_SQL =
            """
            SELECT t.name
              FROM templates t
             WHERE t.workspace_id = :workspaceId
               AND EXISTS (SELECT 1 FROM template_versions v WHERE v.template_id = t.id AND v.status = 'DRAFT')
               AND NOT EXISTS (SELECT 1 FROM template_versions v WHERE v.template_id = t.id AND v.status <> 'DRAFT')
               AND EXISTS (
                   SELECT 1 FROM pipeline_versions pv
                   CROSS JOIN LATERAL jsonb_array_elements(pv.body_json->'nodes') AS pnode
                    WHERE pv.pipeline_id = :pipelineId AND pv.status IN ('DRAFT','RELEASED')
                      AND pnode->'template'->>'id' = t.name
               )
               AND NOT EXISTS (
                   SELECT 1 FROM pipeline_versions pv JOIN pipelines pp ON pp.id = pv.pipeline_id
                   CROSS JOIN LATERAL jsonb_array_elements(pv.body_json->'nodes') AS pnode
                    WHERE pv.pipeline_id <> :pipelineId AND pv.status IN ('DRAFT','RELEASED')
                      AND pp.workspace_id = :workspaceId
                      AND pnode->'template'->>'id' = t.name
               )
             ORDER BY t.name
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
                    discardedAt = rs.getObject("discarded_at", OffsetDateTime::class.java)?.toInstant(),
                    discardedBy = rs.getObject("discarded_by", UUID::class.java),
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
