package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.Dialect
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Version metadata, without the body — the `GET /templates/{id}/versions` projection (§9). */
data class TemplateVersionSummary(
    val id: String,
    val version: Int,
    val createdAt: java.time.Instant,
    val createdBy: UUID,
)

/**
 * Persistence for `templates` and `template_versions` (metadata-db §4.8/§4.9, §6).
 *
 * `NamedParameterJdbcTemplate` exclusively — no JPA (module-structure §8.1). The repository
 * lives in the module that owns the entity (§3.1 rule 1); the `DataSource` bean is app-level
 * and schema creation belongs to `app`'s Flyway alone (rule 2), so nothing here creates or
 * alters a table.
 *
 * ## Immutable per version
 *
 * A template is immutable per version (templates.md §5.1): [update] does not rewrite a body,
 * it appends a `template_versions` row and bumps `templates.current_version`. Both writes land
 * in **one** data-modifying-CTE statement so the pair is atomic without an enclosing
 * transaction — a `templates` row whose `current_version` names a version row that was never
 * inserted is unrepresentable. There is deliberately **no `params_schema` column** (D3).
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
     * dialect, [q] a case-insensitive substring of `name`, `display_name` or `description`.
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
        q: String? = null,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): List<Template> =
        jdbc.query(
            """
            $SELECT_JOINED
             WHERE t.is_deleted = FALSE
               AND t.workspace_id = :workspaceId
               AND v.version = t.current_version
               AND (CAST(:dialect AS TEXT) IS NULL OR v.dialect = CAST(:dialect AS TEXT))
               AND (
                     CAST(:pattern AS TEXT) IS NULL
                     OR t.name ILIKE CAST(:pattern AS TEXT) ESCAPE '\'
                     OR t.display_name ILIKE CAST(:pattern AS TEXT) ESCAPE '\'
                     OR t.description ILIKE CAST(:pattern AS TEXT) ESCAPE '\'
                   )
             ORDER BY t.name
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
            mapOf(
                // Every optional filter is CAST in the SQL: a bare `? IS NULL` gives Postgres no
                // type to infer and the statement will not even prepare.
                "dialect" to dialect?.wire,
                "pattern" to q?.let { "%${escapeLike(it)}%" },
                "workspaceId" to workspaceId,
                "limit" to limit.coerceIn(1, MAX_PAGE_LIMIT),
                "offset" to maxOf(0, offset),
            ),
            MAPPER,
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
     * Inserts the template and its version 1 together in [workspaceId], returning what the
     * database stored.
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
        return mappingDuplicateName(id) {
            jdbc
                .query(
                    INSERT_SQL,
                    params(workspaceId, id, draft, createdBy),
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
     * Appends a new version and bumps `current_version`, in one statement.
     *
     * Returns null when no live template has this id in [workspaceId] — the caller decides
     * whether that is a 404; the repository does not raise catalog errors for control flow.
     */
    fun update(
        workspaceId: UUID,
        id: String,
        draft: TemplateDraft,
        updatedBy: UUID,
    ): Template? =
        jdbc
            .query(
                UPDATE_SQL,
                params(workspaceId, id, draft, updatedBy),
                MAPPER,
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
            "dialect" to draft.dialect.wire,
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

        /** `[a-z0-9_.\-]+` (templates.md §3.2). A hex suffix keeps generated ids inside the rule. */
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
                   v.version, v.engine, v.dialect, v.is_library, v.imports_json::TEXT AS imports_json,
                   v.body, v.created_at, v.created_by AS version_created_by
              FROM templates t
              JOIN template_versions v ON v.template_id = t.id
            """.trimIndent()

        private const val SELECT_VERSION =
            "SELECT t.name AS template_id, v.version, v.engine, v.dialect, v.is_library, " +
                "v.imports_json::TEXT AS imports_json, v.body, v.created_at, v.created_by " +
                "FROM template_versions v JOIN templates t ON t.id = v.template_id"

        private val INSERT_SQL =
            """
            WITH new_template AS (
                INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)
                VALUES (:name, :displayName, :description, 1, :workspaceId, :actor)
                RETURNING id, name, display_name, description
            ), new_version AS (
                INSERT INTO template_versions
                    (template_id, version, engine, dialect, is_library, imports_json, body, created_by)
                SELECT id, 1, :engine, :dialect, :isLibrary, CAST(:importsJson AS jsonb), :body, :actor
                  FROM new_template
                RETURNING template_id, version, engine, dialect, is_library, imports_json::TEXT AS imports_json,
                          body, created_at, created_by
            )
            SELECT t.name AS id, t.display_name, t.description,
                   v.version, v.engine, v.dialect, v.is_library, v.imports_json, v.body, v.created_at,
                   v.created_by AS version_created_by
              FROM new_template t
              JOIN new_version v ON v.template_id = t.id
            """.trimIndent()

        private val UPDATE_SQL =
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
                    (template_id, version, engine, dialect, is_library, imports_json, body, created_by)
                SELECT id, current_version, :engine, :dialect, :isLibrary, CAST(:importsJson AS jsonb), :body, :actor
                  FROM bumped
                RETURNING template_id, version, engine, dialect, is_library, imports_json::TEXT AS imports_json,
                          body, created_at, created_by
            )
            SELECT t.name AS id, t.display_name, t.description,
                   v.version, v.engine, v.dialect, v.is_library, v.imports_json, v.body, v.created_at,
                   v.created_by AS version_created_by
              FROM bumped t
              JOIN new_version v ON v.template_id = t.id
            """.trimIndent()

        private val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Template(
                    id = rs.getString("id"),
                    version = rs.getInt("version"),
                    engine = rs.getString("engine"),
                    dialect = Dialect.fromWire(rs.getString("dialect")),
                    displayName = rs.getString("display_name"),
                    description = rs.getString("description"),
                    imports = TemplateJson.readImports(rs.getString("imports_json")),
                    body = rs.getString("body"),
                    isLibrary = rs.getBoolean("is_library"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    createdBy = rs.getObject("version_created_by", UUID::class.java),
                )
            }

        private val VERSION_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                TemplateVersion(
                    id = rs.getString("template_id"),
                    version = rs.getInt("version"),
                    engine = rs.getString("engine"),
                    dialect = Dialect.fromWire(rs.getString("dialect")),
                    isLibrary = rs.getBoolean("is_library"),
                    imports = TemplateJson.readImports(rs.getString("imports_json")),
                    body = rs.getString("body"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                )
            }
    }
}
