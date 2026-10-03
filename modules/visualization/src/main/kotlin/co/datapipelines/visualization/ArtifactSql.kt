package co.datapipelines.visualization

/**
 * Every statement of one family, built ONCE from its [ArtifactKind] — `ParameterSetRepository`'s SQL line for
 * line, with the table and foreign-key names substituted. The substituted names are the enum's closed constants
 * (never input); every value — ids, names, bodies, hashes, prefixes — is a bind. `s` is the index row, `v` a
 * version row.
 */
@Suppress("LargeClass") // one constant per statement of the verb table, as in ParameterSetRepository's companion
internal class ArtifactSql(
    kind: ArtifactKind,
) {
    private val index = kind.index
    private val versions = kind.versions
    private val fk = kind.fk

    /** The canonical hash of the `:bodyJson` bind — one expression in every write (versioning §4.1). */
    val hashExpr = HASH_EXPR

    /** The derived ACTIVE status (versioning §3.2), `s`-aliased. */
    val liveS = "EXISTS (SELECT 1 FROM $versions lv WHERE lv.$fk = s.id AND lv.status IN ('DRAFT','RELEASED'))"

    /** The version a listing shows: the pointer, else (never released, D55) the draft. */
    val listedVersion =
        "COALESCE(s.current_version, (SELECT MAX(d.version) FROM $versions d WHERE d.$fk = s.id AND d.status = 'DRAFT'))"

    val selectRecord =
        "SELECT s.id, s.workspace_id, s.name, s.display_name, s.description, s.current_version, s.created_at, s.updated_at, s.created_by" +
            " FROM $index s"

    private val detailCols =
        "v.$fk AS artifact_id, v.version, v.status, v.body_hash, v.created_at, v.created_by, v.released_at, v.released_by," +
            " v.discarded_at, v.discarded_by, v.updated_by, v.updated_at, v.created_via, v.updated_via"

    private val detailColsPlain =
        "$fk AS artifact_id, version, status, body_hash, created_at, created_by, released_at, released_by," +
            " discarded_at, discarded_by, updated_by, updated_at, created_via, updated_via"

    /** Workspace-scoped, deliberately WITHOUT a live filter: restore and import classification read discarded rows. */
    val selectDetail = "SELECT $detailCols FROM $versions v JOIN $index s ON s.id = v.$fk WHERE s.workspace_id = :workspaceId"

    val selectVersion =
        "SELECT s.id, s.workspace_id, s.name, s.display_name, s.description, s.current_version, s.created_at AS set_created_at," +
            " s.updated_at AS set_updated_at, s.created_by AS set_created_by, v.body_json::TEXT AS body_json, $detailCols" +
            " FROM $index s JOIN $versions v ON v.$fk = s.id"

    /** One tree level's scope: the workspace, the live artifacts, the prefix (its LIKE metacharacters escaped). */
    val treeWhere = "WHERE s.workspace_id = :workspaceId AND $liveS AND s.name LIKE CAST(:namePattern AS TEXT) ESCAPE '\\'"

    val allDraftNames =
        "SELECT s.name FROM $versions v JOIN $index s ON s.id = v.$fk WHERE v.status = 'DRAFT' ORDER BY s.name"

    val childFolders =
        """
        SELECT split_part(substring(s.name FROM CAST(:cutFrom AS INT)), '/', 1) AS segment, COUNT(*) AS artifact_count
          FROM $index s
        $treeWhere
           AND position('/' IN substring(s.name FROM CAST(:cutFrom AS INT))) > 0
         GROUP BY 1
         ORDER BY 1
         LIMIT :limit
        """.trimIndent()

    val childArtifacts =
        """
        $selectVersion
        $treeWhere
           AND position('/' IN substring(s.name FROM CAST(:cutFrom AS INT))) = 0
           AND v.version = $listedVersion
         ORDER BY s.name
         LIMIT :limit OFFSET :offset
        """.trimIndent()

    val countChildren =
        """
        SELECT COUNT(*) FROM $index s
        $treeWhere
           AND position('/' IN substring(s.name FROM CAST(:cutFrom AS INT))) = 0
        """.trimIndent()

    val listAll =
        """
        $selectVersion
        WHERE s.workspace_id = :workspaceId AND $liveS
           AND v.version = $listedVersion
         ORDER BY s.name
         LIMIT :limit OFFSET :offset
        """.trimIndent()

    val countAll = "SELECT COUNT(*) FROM $index s WHERE s.workspace_id = :workspaceId AND $liveS"

    /**
     * The name search's scope (#399; `TemplateRepository`'s shape): the workspace, the live artifacts, and the index row's
     * name, display name or description matching `:pattern` — a bind the repository builds as `%term%` with the term's
     * LIKE metacharacters escaped, so `%`, `_` and `\` match literally and case-insensitively. Never string-built.
     */
    private val searchWhere =
        "WHERE s.workspace_id = :workspaceId AND $liveS AND (" +
            "s.name ILIKE CAST(:pattern AS TEXT) ESCAPE '\\'" +
            " OR s.display_name ILIKE CAST(:pattern AS TEXT) ESCAPE '\\'" +
            " OR s.description ILIKE CAST(:pattern AS TEXT) ESCAPE '\\')"

    /** [listAll] narrowed by [searchWhere] — each match at its listed version, by name. */
    val search =
        """
        $selectVersion
        $searchWhere
           AND v.version = $listedVersion
         ORDER BY s.name
         LIMIT :limit OFFSET :offset
        """.trimIndent()

    /** The truthful total of [search]. */
    val countSearch = "SELECT COUNT(*) FROM $index s $searchWhere"

    /** Every match's id, unpaged — the narrowing lens intersects it with the admitted set and pages in memory. */
    val searchIds = "SELECT s.id FROM $index s $searchWhere"

    val currentVersions =
        """
        SELECT s.id, s.name, s.display_name, v.version, v.body_hash
          FROM $index s
          JOIN $versions v ON v.$fk = s.id AND v.version = s.current_version
         WHERE s.workspace_id = :workspaceId AND $liveS AND v.status = 'RELEASED'
         ORDER BY s.name
        """.trimIndent()

    val isLive = "SELECT EXISTS (SELECT 1 FROM $index s WHERE s.id = :id AND s.workspace_id = :workspaceId AND $liveS)"

    val versionCount =
        "SELECT COUNT(*) FROM $versions v JOIN $index s ON s.id = v.$fk WHERE s.id = :id AND s.workspace_id = :workspaceId"

    /** D55: an authoring create lands v1 DRAFT with a NULL pointer; `updated_*` stamped as a draft write stamps them. */
    val insertDraft =
        """
        WITH new_artifact AS (
            INSERT INTO $index (id, workspace_id, name, display_name, description, current_version, created_by)
            VALUES (:id, :workspaceId, :name, :displayName, :description, NULL, :actor)
            RETURNING id
        )
        INSERT INTO $versions
            ($fk, version, body_json, status, body_hash, created_by, updated_by, updated_at, created_via, updated_via)
        SELECT id, 1, CAST(:bodyJson AS jsonb), 'DRAFT', $HASH_EXPR, :actor, :actor, NOW(), :via, :via FROM new_artifact
        RETURNING $detailColsPlain
        """.trimIndent()

    /** The RELEASED create: a seed, a version-less import (v1) or an exact-version import (`:version`); the pointer names it. */
    val insertReleased =
        """
        WITH new_artifact AS (
            INSERT INTO $index (id, workspace_id, name, display_name, description, current_version, created_by)
            VALUES (:id, :workspaceId, :name, :displayName, :description, :version, :actor)
            RETURNING id
        )
        INSERT INTO $versions
            ($fk, version, body_json, status, body_hash, created_by, released_by, released_at, created_via, updated_via)
        SELECT id, :version, CAST(:bodyJson AS jsonb), 'RELEASED', $HASH_EXPR, :actor, :actor,
               COALESCE(CAST(:releasedAt AS TIMESTAMPTZ), NOW()), :via, :via
          FROM new_artifact
        RETURNING $detailColsPlain
        """.trimIndent()

    /**
     * versioning §5.1 — copy-on-write. `draft` inserts at `max + 1` when the content DIFFERS from the current released
     * version; `noop` answers that version when it is identical. Both join `guard` (the caller's base is the current
     * RELEASED hash), and both require that no draft exists — a draft that raced in owns the working state.
     */
    val createDraft =
        """
        WITH guard AS (
            SELECT 1 FROM $versions v JOIN $index s ON s.id = v.$fk
             WHERE s.id = :id AND s.workspace_id = :workspaceId
               AND v.version = s.current_version AND v.status = 'RELEASED' AND v.body_hash = :expectedHash
        ), draft AS (
            INSERT INTO $versions
                ($fk, version, body_json, status, body_hash, created_by, updated_by, updated_at, created_via, updated_via)
            SELECT v.$fk,
                   (SELECT MAX(d2.version) + 1 FROM $versions d2 WHERE d2.$fk = v.$fk),
                   CAST(:bodyJson AS jsonb), 'DRAFT', $HASH_EXPR, :actor, :actor, NOW(), :via, :via
              FROM $versions v JOIN $index s ON s.id = v.$fk
              JOIN guard ON TRUE
             WHERE s.id = :id AND s.workspace_id = :workspaceId
               AND v.version = s.current_version AND v.status = 'RELEASED'
               AND $HASH_EXPR <> v.body_hash
               AND NOT EXISTS (SELECT 1 FROM $versions d WHERE d.$fk = v.$fk AND d.status = 'DRAFT')
            RETURNING $detailColsPlain
        ), noop AS (
            SELECT $detailCols
              FROM $versions v JOIN $index s ON s.id = v.$fk
              JOIN guard ON TRUE
             WHERE s.id = :id AND s.workspace_id = :workspaceId
               AND v.version = s.current_version AND v.status = 'RELEASED'
               AND $HASH_EXPR = v.body_hash
               AND NOT EXISTS (SELECT 1 FROM $versions d WHERE d.$fk = v.$fk AND d.status = 'DRAFT')
        )
        SELECT * FROM draft
        UNION ALL
        SELECT * FROM noop
        """.trimIndent()

    /** versioning §5.2 — in place, hash-preconditioned. A draft edited back to its parent is LEFT (discard stays explicit). */
    val writeDraft =
        """
        UPDATE $versions v
           SET body_json = CAST(:bodyJson AS jsonb), body_hash = $HASH_EXPR,
               updated_by = :actor, updated_at = NOW(), updated_via = :via
          FROM $index s
         WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.$fk = s.id
           AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
        RETURNING $detailCols
        """.trimIndent()

    /** versioning §5.3 — flip + pointer + the index row's display metadata, one statement. */
    val release =
        """
        WITH locked AS (
            UPDATE $versions v
               SET status = 'RELEASED', released_at = NOW(), released_by = :actor
              FROM $index s
             WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.$fk = s.id
               AND v.status = 'DRAFT' AND v.body_hash = :expectedHash
            RETURNING $detailCols, v.body_json
        ), bumped AS (
            UPDATE $index
               SET current_version = (SELECT version FROM locked),
                   display_name = (SELECT body_json->>'display_name' FROM locked),
                   description = COALESCE((SELECT body_json->>'description' FROM locked), ''),
                   updated_at = NOW()
             WHERE id = :id AND workspace_id = :workspaceId AND EXISTS (SELECT 1 FROM locked)
            RETURNING id
        )
        SELECT artifact_id, version, status, body_hash, created_at, created_by, released_at, released_by,
               discarded_at, discarded_by, updated_by, updated_at, created_via, updated_via
          FROM locked, bumped
        """.trimIndent()

    fun purgeDraft(hashGuarded: Boolean): String =
        "DELETE FROM $versions v USING $index s" +
            " WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.$fk = s.id" +
            " AND v.status = 'DRAFT'" + (if (hashGuarded) " AND v.body_hash = :expectedHash" else "") +
            " RETURNING v.version"

    /** D60 — the pointer fallback after a purge of the current version: the highest eligible live one, else NULL. */
    val pointerFallback =
        """
        UPDATE $index s
           SET current_version = (
                   SELECT MAX(lv.version) FROM $versions lv
                    WHERE lv.$fk = s.id
                      AND (lv.status = 'RELEASED' OR (:draftEligible AND lv.status = 'DRAFT'))
               ),
               updated_at = NOW()
         WHERE s.id = :id AND s.workspace_id = :workspaceId AND s.current_version = :version
        """.trimIndent()

    /** The index row indexes the CURRENT body (versioning §3.7): after any pointer move, it adopts that body's metadata. */
    val reindex =
        """
        UPDATE $index s
           SET display_name = v.body_json->>'display_name', description = COALESCE(v.body_json->>'description', '')
          FROM $versions v
         WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.$fk = s.id AND v.version = s.current_version
        """.trimIndent()

    /**
     * versioning §3.1 — discard a RELEASED version; the pointer falls back only when it named it.
     * The row answers the pointer pair (#372): `prev` reads the pointer from the statement's own
     * snapshot, `bumped` returns the moved one — the controller never re-reads.
     */
    val discard =
        """
        WITH flipped AS (
            UPDATE $versions v
               SET status = 'DISCARDED', discarded_at = NOW(), discarded_by = :actor
              FROM $index s
             WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.$fk = s.id
               AND v.version = :version AND v.status = 'RELEASED'
            RETURNING $detailCols
        ), prev AS (
            SELECT s.current_version FROM $index s
             WHERE s.id = :id AND s.workspace_id = :workspaceId
        ), bumped AS (
            UPDATE $index s
               SET current_version = CASE
                       WHEN s.current_version = :version THEN (
                           SELECT MAX(lv.version) FROM $versions lv
                            WHERE lv.$fk = s.id AND lv.version <> :version
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
    val restore =
        """
        WITH restored AS (
            UPDATE $versions v
               SET status = 'RELEASED', discarded_at = NULL, discarded_by = NULL
              FROM $index s
             WHERE s.id = :id AND s.workspace_id = :workspaceId AND v.$fk = s.id
               AND v.version = :version AND v.status = 'DISCARDED'
            RETURNING $detailCols
        ), prev AS (
            SELECT s.current_version FROM $index s
             WHERE s.id = :id AND s.workspace_id = :workspaceId
        ), bumped AS (
            UPDATE $index
               SET current_version = GREATEST(COALESCE(current_version, 0), (SELECT version FROM restored)), updated_at = NOW()
             WHERE id = :id AND workspace_id = :workspaceId AND EXISTS (SELECT 1 FROM restored)
            RETURNING current_version
        )
        SELECT restored.*, prev.current_version AS pointer_before, bumped.current_version AS pointer_after
          FROM restored, bumped, prev
        """.trimIndent()

    /** D60 — the manual switch; live and posture-eligible or nothing. The row answers the pointer pair (#372). */
    val switch =
        """
        WITH prev AS (
            SELECT s.current_version FROM $index s
             WHERE s.id = :id AND s.workspace_id = :workspaceId
        ), moved AS (
            UPDATE $index s
               SET current_version = :version, updated_at = NOW()
             WHERE s.id = :id AND s.workspace_id = :workspaceId
               AND EXISTS (SELECT 1 FROM $versions v
                            WHERE v.$fk = s.id AND v.version = :version
                              AND (v.status = 'RELEASED' OR (:draftEligible AND v.status = 'DRAFT')))
            RETURNING s.current_version
        )
        SELECT prev.current_version AS pointer_before, moved.current_version AS pointer_after FROM prev, moved
        """.trimIndent()

    val deleteEntity = "DELETE FROM $index WHERE id = :id AND workspace_id = :workspaceId"

    /** versioning §9.2 — an exact (or `max + 1`) RELEASED version onto an existing artifact; the pointer only when NULL. */
    val insertReleasedVersion =
        """
        WITH alloc AS (
            SELECT COALESCE(CAST(:version AS INT), (SELECT COALESCE(MAX(v.version), 0) + 1 FROM $versions v
                     JOIN $index s ON s.id = v.$fk WHERE s.id = :id AND s.workspace_id = :workspaceId)) AS n
        ), ins AS (
            INSERT INTO $versions
                ($fk, version, body_json, status, body_hash, created_by, released_by, released_at)
            SELECT s.id, alloc.n, CAST(:bodyJson AS jsonb), 'RELEASED', $HASH_EXPR, :actor, :actor,
                   COALESCE(CAST(:releasedAt AS TIMESTAMPTZ), NOW())
              FROM $index s, alloc
             WHERE s.id = :id AND s.workspace_id = :workspaceId
               AND NOT EXISTS (SELECT 1 FROM $versions v WHERE v.$fk = s.id AND v.version = alloc.n)
            RETURNING $detailColsPlain
        ), bumped AS (
            UPDATE $index
               SET current_version = COALESCE(current_version, (SELECT version FROM ins)), updated_at = NOW()
             WHERE id = :id AND workspace_id = :workspaceId AND EXISTS (SELECT 1 FROM ins)
            RETURNING 1
        )
        SELECT ins.* FROM ins, bumped
        """.trimIndent()

    companion object {
        /** `encode(sha256(convert_to(<jsonb>::text, 'UTF8')), 'hex')` over the bound body — ParameterSetRepository's expression. */
        const val HASH_EXPR = "encode(sha256(convert_to(CAST(:bodyJson AS jsonb)::text, 'UTF8')), 'hex')"
    }
}
