package co.datapipelines.pipeline.inverse

import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineVersionStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * The native bounded inverse queries' shared vocabulary (#877 §B, record §14): every dependent family's repository
 * answers "which stored references of mine point at this target?" with ONE keyset-bounded statement per page, through
 * [InverseSql.page]. Plain SQL and plain rows — this package knows nothing of the dependency contract; the provider arms
 * in `application` map [InverseRow]s to the contract's edges, and the domain classes page these same queries to
 * exhaustion in place (one definition shared by both, no upward call).
 *
 * Lives in `pipeline-contract` because every domain module that owns a dependent family already depends on it.
 */
enum class InverseScope {
    /** Each live dependent's working version: its DRAFT when one exists, else its current pointer. */
    WORKING,

    /** Every DRAFT or RELEASED dependent version. */
    LIVE,

    /** Every stored version, DISCARDED included, of a dependent entity that still has a live version. */
    ANY,
}

/** The position after a page's last occurrence: the order key without the per-arm constant relationship. */
data class InverseAfter(
    val dependentId: String,
    val dependentVersion: Int,
    val sourcePath: String,
)

/**
 * One bounded inverse question. [targetVersion] null is the entity-wide form (every reference naming [targetKey],
 * whatever version it stores); [limit] is the page size — the statement fetches `limit + 1` rows so the caller knows
 * whether another page exists.
 */
data class InverseRequest(
    val workspaceId: UUID,
    val targetKey: String,
    val targetVersion: Int?,
    val scope: InverseScope,
    val after: InverseAfter?,
    val limit: Int,
) {
    init {
        require(limit >= 1) { "an inverse page holds at least one occurrence, got $limit" }
        require(targetVersion == null || targetVersion >= 1) { "an exact target version is at least 1, got $targetVersion" }
    }
}

/**
 * One stored reference occurrence (the page unit): the dependent's stable id ([dependentId], the entity UUID's text),
 * name, version (0 for an unversioned dependent) and lifecycle status (null when unversioned), the field path inside
 * its body, the version the reference stores (null when it stores none), and the body element's own id where the
 * family has one (a pipeline node's `id`, a parameter's `name`).
 */
data class InverseRow(
    val dependentId: String,
    val dependentName: String,
    val dependentVersion: Int,
    val status: PipelineVersionStatus?,
    val sourcePath: String,
    val storedVersion: Int?,
    val elementId: String?,
) {
    val after: InverseAfter get() = InverseAfter(dependentId, dependentVersion, sourcePath)

    /** The dependent entity's UUID — every versioned family's stable id is one. */
    val dependentUuid: UUID get() = UUID.fromString(dependentId)
}

/**
 * The node-type rule of a pipeline body — ONE definition, read by the pipeline inverse SQL and asserted against the
 * outgoing extractors (`PipelineDependencySource.pinsOf`, `DatasourceEdges.ofPipeline`) by `PinNodeRulesTest`: which
 * node types carry which reference (approved #873 §F.1 for datasources).
 */
object PinNodeRules {
    /** Node types whose `template` is a pin: every type but CALCULATOR (no template) and PIPELINE (a child pin). */
    val TEMPLATE: Set<NodeType> = NodeType.entries.toSet() - setOf(NodeType.CALCULATOR, NodeType.PIPELINE)

    /** Node types whose `pipeline` is a child pin. */
    val CHILD: Set<NodeType> = setOf(NodeType.PIPELINE)

    /** Node types whose `source` reads or writes a datasource (`tempdb` is never one). */
    val DATASOURCE_SOURCE: Set<NodeType> = setOf(NodeType.DQL, NodeType.DML, NodeType.DDL)

    /** Node types whose `output.datasource` writes a datasource (when `output.target` is `datasource`). */
    val DATASOURCE_OUTPUT: Set<NodeType> = setOf(NodeType.DQL, NodeType.DML, NodeType.DDL, NodeType.PIPELINE)

    /** The SQL predicate "[node]'s type is one of [types]" — closed wire constants only, never input. */
    fun typeIn(
        node: String,
        types: Set<NodeType>,
    ): String = types.sortedBy { it.wire }.joinToString(", ", "$node->>'type' IN (", ")") { "'${it.wire}'" }
}

/** The SQL builders every native inverse query shares. Only closed constants are interpolated; values are binds. */
object InverseSql {
    /** The integer a JSON value stores, or NULL when it is not an integral number inside Int's range (never a cast error). */
    fun jsonInt(value: String): String =
        "(CASE WHEN jsonb_typeof($value) = 'number' AND ($value)::numeric = trunc(($value)::numeric)" +
            " AND ($value)::numeric BETWEEN -2147483648 AND 2147483647 THEN (($value)::numeric)::int END)"

    /**
     * The [scope] predicate over a versioned family: entity alias [entity] (with `id` and `current_version`), versions
     * alias [version] (with `version` and `status`) in table [versions] keyed by [fk]. LIVE = the version is DRAFT or
     * RELEASED; ANY = the entity still has a live version; WORKING = ANY and the version is the draft, else the pointer.
     */
    fun scope(
        scope: InverseScope,
        versions: String,
        fk: String,
        entity: String = "s",
        version: String = "v",
    ): String {
        val live = "EXISTS (SELECT 1 FROM $versions lv WHERE lv.$fk = $entity.id AND lv.status IN ('DRAFT','RELEASED'))"
        return when (scope) {
            InverseScope.LIVE -> {
                "$version.status IN ('DRAFT','RELEASED')"
            }

            InverseScope.ANY -> {
                live
            }

            InverseScope.WORKING -> {
                "$live AND $version.version = COALESCE(" +
                    "(SELECT d.version FROM $versions d WHERE d.$fk = $entity.id AND d.status = 'DRAFT' LIMIT 1), " +
                    "$entity.current_version)"
            }
        }
    }

    /** The 1-based array ordinal [ordinal] as the 0-based path `prefix[i]suffix`. */
    fun path(
        prefix: String,
        ordinal: String,
        suffix: String,
    ): String = "'$prefix[' || ($ordinal - 1) || ']$suffix'"

    /**
     * Pages [occurrences] — a SELECT yielding the columns `dependent_id` (text), `dependent_name`, `dependent_version`
     * (int), `dependent_status` (text or NULL), `source_path`, `stored_version` (int or NULL) and `element_id` — after
     * [request]'s cursor, in the total order (id and path under COLLATE "C"), `limit + 1` rows. ONE statement.
     */
    fun page(
        jdbc: NamedParameterJdbcTemplate,
        occurrences: String,
        params: Map<String, Any?>,
        request: InverseRequest,
    ): List<InverseRow> {
        val after = request.after
        val keyset =
            if (after == null) {
                ""
            } else {
                """
                WHERE (o.dependent_id COLLATE "C" > :inverseAfterId COLLATE "C"
                   OR (o.dependent_id = :inverseAfterId
                       AND (o.dependent_version > :inverseAfterVersion
                            OR (o.dependent_version = :inverseAfterVersion
                                AND o.source_path COLLATE "C" > :inverseAfterPath COLLATE "C"))))
                """.trimIndent()
            }
        val sql =
            """
            SELECT o.* FROM (
            $occurrences
            ) o
            $keyset
             ORDER BY o.dependent_id COLLATE "C", o.dependent_version, o.source_path COLLATE "C"
             LIMIT :inverseLimit
            """.trimIndent()
        val binds =
            params +
                mapOf(
                    "inverseAfterId" to after?.dependentId,
                    "inverseAfterVersion" to after?.dependentVersion,
                    "inverseAfterPath" to after?.sourcePath,
                    "inverseLimit" to request.limit + 1,
                )
        return jdbc.query(sql, binds, ROW_MAPPER)
    }

    /**
     * Reads every page of [read] in order — the domain callers' complete answer, one statement per page. [read] answers
     * up to `limit + 1` rows after its argument.
     */
    fun exhaust(
        limit: Int,
        read: (InverseAfter?) -> List<InverseRow>,
    ): List<InverseRow> {
        val rows = ArrayList<InverseRow>()
        var after: InverseAfter? = null
        do {
            val page = read(after)
            rows += page.take(limit)
            after = if (page.size > limit) page[limit - 1].after else null
        } while (after != null)
        return rows
    }

    /** The page size a domain caller exhausts with — the record's §25.4 `max-resolve-batch-size` default. */
    const val EXHAUST_PAGE = 256

    val ROW_MAPPER: RowMapper<InverseRow> =
        RowMapper { rs: ResultSet, _: Int ->
            InverseRow(
                dependentId = rs.getString("dependent_id"),
                dependentName = rs.getString("dependent_name"),
                dependentVersion = rs.getInt("dependent_version"),
                status = rs.getString("dependent_status")?.let(PipelineVersionStatus::fromWire),
                sourcePath = rs.getString("source_path"),
                storedVersion = rs.getInt("stored_version").takeUnless { rs.wasNull() },
                elementId = rs.getString("element_id"),
            )
        }
}
