package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.RetiredFactCitation
import co.datapipelines.pipeline.TemplateRef
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * "Of these fact ids, which may [workspaceId] cite?" — the transform-nodes design's §2.3 rule
 * (a fact visible from the workspace that is a `WORKSPACE` fact of kind `definition`,
 * `exclusion` or `preference`), as the port the template write paths ask.
 *
 * A port because the facts live in `datasources` and this module may not depend on it
 * (module-structure §4.2); the application layer implements it over `LearnedFactRepository`'s
 * one visibility predicate, so the rule is stated once, where the facts are. The answer is the
 * citable SUBSET — the caller decides whether a missing id is a refusal (a write:
 * `template.implements_unresolved`) or a drop (an import, the owner's ruling of 2026-09-25).
 */
fun interface CitableFacts {
    fun citable(
        workspaceId: UUID,
        factIds: Set<UUID>,
    ): Set<UUID>

    companion object {
        /** Nothing is citable — fail closed for a construction that wired no fact store. */
        val NONE = CitableFacts { _, _ -> emptySet() }
    }
}

/**
 * The §2.3 id list as the wire carries it: strings, because a malformed id must be the
 * catalogued `template.implements_unresolved` and not a binding failure.
 */
object ImplementsIds {
    /**
     * At most this many citations per version — a bound on the request, not a product rule: a
     * transform implementing fifty workspace rules is one that should be split, and every read of
     * the version carries the list.
     */
    const val MAX_CITATIONS = 50

    /** [raw] as UUIDs, deduplicated in first-seen order; a malformed entry is dropped. */
    fun parseLenient(raw: List<String>): List<UUID> = raw.mapNotNull { parseOrNull(it) }.distinct()

    /** The entries of [raw] that [facts] admits for [workspaceId], in first-seen order — the import ruling (drop, never refuse). */
    fun keepCitable(
        workspaceId: UUID,
        raw: List<String>,
        facts: CitableFacts,
    ): List<String> {
        val parsed = parseLenient(raw).take(MAX_CITATIONS)
        if (parsed.isEmpty()) return emptyList()
        val citable = facts.citable(workspaceId, parsed.toSet())
        return parsed.filter { it in citable }.map { it.toString() }
    }

    /**
     * The §2.3 verdict on a WRITE: null when every entry is a citable fact, else the one failure
     * naming the first offender (`details.fact_id`) and why (`details.reason`: `malformed` — not
     * a fact id at all; `unknown` — no fact the workspace may cite, deliberately one answer for
     * "absent", "another workspace's", "a DATASOURCE fact" and "the wrong kind"; `too_many`).
     */
    fun unresolved(
        workspaceId: UUID,
        raw: List<String>,
        facts: CitableFacts,
    ): TemplateValidationFailure? {
        val distinct = raw.distinct()
        return tooMany(distinct) ?: malformed(distinct) ?: uncitable(workspaceId, distinct, facts)
    }

    private fun tooMany(distinct: List<String>): TemplateValidationFailure? =
        if (distinct.size <= MAX_CITATIONS) {
            null
        } else {
            failure(
                "A template version may cite at most $MAX_CITATIONS facts; this one names ${distinct.size}.",
                mapOf("reason" to "too_many", "max" to MAX_CITATIONS, "count" to distinct.size),
            )
        }

    private fun malformed(distinct: List<String>): TemplateValidationFailure? =
        distinct.firstOrNull { parseOrNull(it) == null }?.let { bad ->
            failure(
                "'${bad.truncateForError()}' is not a learned-fact id.",
                mapOf("fact_id" to bad.truncateForError(), "reason" to "malformed"),
            )
        }

    /** Every entry well-formed: the first one [facts] does not admit for [workspaceId], as the one not-found answer. */
    private fun uncitable(
        workspaceId: UUID,
        distinct: List<String>,
        facts: CitableFacts,
    ): TemplateValidationFailure? {
        val ids = distinct.mapNotNull { parseOrNull(it) }.distinct()
        if (ids.isEmpty()) return null
        val citable = facts.citable(workspaceId, ids.toSet())
        val missing = ids.firstOrNull { it !in citable } ?: return null
        return failure(
            "Fact $missing is not a fact this workspace can cite: implements names WORKSPACE facts of kind " +
                "definition, exclusion or preference recorded in this workspace (semantics_list / the " +
                "definitions on datasources_list show their ids).",
            mapOf("fact_id" to missing.toString(), "reason" to "unknown"),
        )
    }

    private fun failure(
        message: String,
        details: Map<String, Any?>,
    ) = TemplateValidationFailure(
        code = PipelineErrorCodes.Template.IMPLEMENTS_UNRESOLVED,
        message = message,
        details = details,
    )

    private fun parseOrNull(raw: String): UUID? =
        try {
            UUID.fromString(raw.trim()).takeIf { it.toString().equals(raw.trim(), ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        }
}

/** One template version citing a fact — an `implemented_by` entry (transform-nodes design §8.3). */
data class ImplementingVersion(
    val templateId: String,
    val version: Int,
)

/**
 * `template_implements` persistence (metadata-db §4.21, V36) — the citations a transform
 * version carries, and the two reverse reads the semantic link needs.
 *
 * `NamedParameterJdbcTemplate` exclusively, the module's rule. Every statement names its
 * template by the human id within ONE workspace (the [TemplateRepository] addressing rule), so a
 * citation of another workspace's template is unreachable by construction.
 *
 * ## One expression, every reader
 *
 * [RETIRED_FACTS_JSON_SQL] is the whole §8.2 rule — a cited fact with `trust = 'retired'`, its
 * reason, and its successor (the visible row whose `supersedes` names it). [TemplateRepository]'s
 * projection selects it on every template read (the `needs_review` flag and detail), and
 * [retiredCitations] — the release warning's and the release dialog's source — selects the SAME
 * text, so the marker in the explorer and the warning at release cannot disagree.
 *
 * ## Not content
 *
 * Nothing here touches `template_versions`: a citation is outside `body_hash` (R9), so a write
 * of it never opens a draft and is legal on a RELEASED version — the one post-release write
 * templates.md §5.1 allows.
 */
class TemplateImplementsRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * Makes [factIds] the citations of `name@version` — one statement: the rows not in the new
     * set are deleted and the new ones inserted (`ON CONFLICT DO NOTHING` keeps the unchanged
     * ones in place, so the delete and the insert never touch the same key). An empty list
     * clears. A version that does not exist in [workspaceId] is a no-op; callers write the
     * version first.
     */
    fun replace(
        workspaceId: UUID,
        name: String,
        version: Int,
        factIds: List<UUID>,
    ) {
        val params = mapOf("name" to name, "workspaceId" to workspaceId, "version" to version, "factIds" to factIds.distinct())
        jdbc.update(if (factIds.isEmpty()) CLEAR_SQL else REPLACE_SQL, params)
    }

    /**
     * Copies `name@fromVersion`'s citations onto `name@toVersion` — the INHERIT half of the write
     * rule (owner ruling 2026-09-25): the first write after a release opens a new draft, and a
     * write that did not state `implements` carries the released version's citations forward.
     */
    fun copy(
        workspaceId: UUID,
        name: String,
        fromVersion: Int,
        toVersion: Int,
    ): Int = jdbc.update(COPY_SQL, mapOf("name" to name, "workspaceId" to workspaceId, "from" to fromVersion, "to" to toVersion))

    /**
     * §8.2 for a set of pins — each pin that cites at least one retired fact, with those facts;
     * a pin absent from the answer reads clean. The release warning's source (the
     * `TemplateReviewMarks` port) and the release dialog's.
     */
    fun retiredCitations(
        workspaceId: UUID,
        pins: Collection<TemplateRef>,
    ): Map<TemplateRef, List<RetiredFactCitation>> {
        val distinct = pins.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return jdbc
            .query(
                """
                SELECT t.name, v.version, $RETIRED_FACTS_JSON_SQL AS retired_facts_json
                  FROM template_versions v
                  JOIN templates t ON t.id = v.template_id
                 WHERE t.workspace_id = :workspaceId AND (t.name, v.version) IN (:pins)
                """.trimIndent(),
                mapOf("workspaceId" to workspaceId, "pins" to distinct.map { arrayOf<Any>(it.id, it.version) }),
            ) { rs, _ -> TemplateRef(rs.getString("name"), rs.getInt("version")) to readRetired(rs.getString("retired_facts_json")) }
            .filter { (_, retired) -> retired.isNotEmpty() }
            .toMap()
    }

    /**
     * §8.3 — for each of [factIds], the LIVE template versions of [workspaceId] citing it that
     * [lens] admits, name then version order. The lens is applied in the SQL: under
     * [ReadLens.Everything] every DRAFT and RELEASED version; under a narrowing lens only the
     * RELEASED versions of the admitted names (a promoter never sees a draft, 178). A DISCARDED
     * version is never listed — it is not something to reuse. Facts nobody implements are absent.
     */
    fun implementedBy(
        workspaceId: UUID,
        lens: ReadLens,
        factIds: Collection<UUID>,
    ): Map<UUID, List<ImplementingVersion>> {
        val ids = factIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val admitted = (lens as? ReadLens.Only)?.names
        if (admitted != null && admitted.isEmpty()) return emptyMap()
        val params =
            buildMap<String, Any> {
                put("workspaceId", workspaceId)
                put("factIds", ids)
                admitted?.let { put("admitted", it) }
            }
        return jdbc
            .query(if (admitted == null) IMPLEMENTED_BY_SQL else IMPLEMENTED_BY_ADMITTED_SQL, params) { rs, _ ->
                rs.getObject("fact_id", UUID::class.java) to ImplementingVersion(rs.getString("name"), rs.getInt("version"))
            }.groupBy({ it.first }, { it.second })
    }

    companion object {
        /**
         * A version's cited fact ids as a JSON text array, sorted — correlated on the enclosing
         * query's `v` (`template_versions`). [TemplateRepository]'s projection selects it.
         */
        internal const val IMPLEMENTS_JSON_SQL =
            "(SELECT COALESCE(json_agg(ti.fact_id::text ORDER BY ti.fact_id::text), '[]'::json)::text" +
                " FROM template_implements ti WHERE ti.template_id = v.template_id AND ti.version = v.version)"

        /**
         * §8.2 — the version's RETIRED cited facts as a JSON text array of
         * `{fact_id, retired_reason, superseded_by}`, correlated on the enclosing query's `v`
         * and `t` (`templates`). THE needs_review rule: a cited fact is retired (`trust =
         * 'retired'`, a superseded fact included — it is retired with reason `superseded`).
         * The successor is the newest row whose `supersedes` names the fact AND that the
         * template's workspace may see (the `learned_facts` visibility predicate) — there is no
         * `superseded_by` column, and `idx_learned_facts_supersedes` keeps this an index read.
         */
        internal const val RETIRED_FACTS_JSON_SQL =
            "(SELECT COALESCE(json_agg(json_build_object(" +
                "'fact_id', f.id::text, 'retired_reason', f.retired_reason, 'superseded_by', " +
                "(SELECT s.id::text FROM learned_facts s WHERE s.supersedes = f.id" +
                " AND (s.scope = 'DATASOURCE' OR s.workspace_id = t.workspace_id)" +
                " ORDER BY s.recorded_at DESC, s.id LIMIT 1)" +
                ") ORDER BY f.id::text), '[]'::json)::text" +
                " FROM template_implements ti JOIN learned_facts f ON f.id = ti.fact_id" +
                " WHERE ti.template_id = v.template_id AND ti.version = v.version AND f.trust = 'retired')"

        /** For a projection that has no citations yet (a row a CTE just inserted) — the columns [TemplateRepository]'s mapper reads. */
        internal const val NO_CITATIONS_COLUMNS = "'[]' AS implements_json, '[]' AS retired_facts_json"

        /** Reads [IMPLEMENTS_JSON_SQL]'s array. */
        internal fun readIds(json: String?): List<String> = if (json == null) emptyList() else MAPPER.readValue(json)

        /** Reads [RETIRED_FACTS_JSON_SQL]'s array. */
        internal fun readRetired(json: String?): List<RetiredFactCitation> = if (json == null) emptyList() else MAPPER.readValue(json)

        private val MAPPER = TemplateJson.objectMapper()

        private val REPLACE_SQL =
            """
            WITH target AS (
                SELECT v.template_id, v.version
                  FROM template_versions v JOIN templates t ON t.id = v.template_id
                 WHERE t.name = :name AND t.workspace_id = :workspaceId AND v.version = :version
            ), dropped AS (
                DELETE FROM template_implements ti USING target
                 WHERE ti.template_id = target.template_id AND ti.version = target.version
                   AND ti.fact_id <> ALL (CAST(ARRAY[:factIds] AS uuid[]))
            )
            INSERT INTO template_implements (template_id, version, fact_id)
            SELECT target.template_id, target.version, f.fact_id
              FROM target, unnest(CAST(ARRAY[:factIds] AS uuid[])) AS f(fact_id)
            ON CONFLICT DO NOTHING
            """.trimIndent()

        private val CLEAR_SQL =
            """
            DELETE FROM template_implements ti
             USING templates t
             WHERE ti.template_id = t.id AND t.name = :name AND t.workspace_id = :workspaceId AND ti.version = :version
            """.trimIndent()

        private val COPY_SQL =
            """
            INSERT INTO template_implements (template_id, version, fact_id)
            SELECT ti.template_id, CAST(:to AS INTEGER), ti.fact_id
              FROM template_implements ti JOIN templates t ON t.id = ti.template_id
             WHERE t.name = :name AND t.workspace_id = :workspaceId AND ti.version = :from
               AND EXISTS (SELECT 1 FROM template_versions v WHERE v.template_id = ti.template_id AND v.version = :to)
            ON CONFLICT DO NOTHING
            """.trimIndent()

        /** The live versions citing any of `:factIds` in one workspace — the lens-free half of [implementedBy]. */
        private val IMPLEMENTED_BY_WHERE =
            """
            SELECT ti.fact_id, t.name, v.version
              FROM template_implements ti
              JOIN template_versions v ON v.template_id = ti.template_id AND v.version = ti.version
              JOIN templates t ON t.id = ti.template_id
             WHERE t.workspace_id = :workspaceId AND ti.fact_id IN (:factIds)
               AND v.status IN ('DRAFT', 'RELEASED')
            """.trimIndent()

        private val IMPLEMENTED_BY_SQL = "$IMPLEMENTED_BY_WHERE\n ORDER BY t.name, v.version"

        /** The narrowing lens (178): RELEASED versions of the admitted names only. */
        private val IMPLEMENTED_BY_ADMITTED_SQL =
            "$IMPLEMENTED_BY_WHERE\n   AND v.status = 'RELEASED' AND t.name IN (:admitted)\n ORDER BY t.name, v.version"
    }
}
