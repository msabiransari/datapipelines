package co.datapipelines.datasources.semantics

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `learned_facts` persistence (metadata-db §4.18).
 *
 * Reads are per datasource and carry the VISIBILITY predicate in SQL, the `DatasourceRepository`
 * discipline: a DATASOURCE fact is visible wherever the datasource is (the caller gated that
 * before asking); a WORKSPACE fact is visible to its workspace only — `scope = 'DATASOURCE' OR
 * workspace_id = :reader`. Nothing re-derives that rule in Kotlin, so the 112 sweep can find
 * the one place it could break.
 *
 * Writes are the design's three verbs and nothing else: insert (record), a one-way trust
 * demotion (the §6 drift check's mark), and retire (D-S11: never a DELETE). `refs_json` is
 * stored NORMALISED — refs sorted by their canonical key — so the O-4 duplicate check is a
 * plain `jsonb` equality rather than a set comparison in code.
 */
class LearnedFactRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper = DEFAULT_MAPPER,
) {
    /** The insert's inputs — everything the row carries that the caller decides. */
    data class NewFact(
        val scope: LearnedFactScope,
        val workspaceId: UUID?,
        val datasourceName: String,
        val kind: LearnedFactKind,
        val fact: String,
        val refs: List<FactRef>,
        val evidenceSql: String?,
        val evidenceSummary: String?,
        val trust: LearnedFactTrust,
        val schemaFingerprint: String,
        val recordedBy: UUID,
        val recordedVia: String,
        val recordedIn: UUID,
        val sourcePipelineId: UUID?,
        val sourceVersion: Int?,
        val supersedes: UUID?,
    )

    /** Stores one fact and returns the row as written (timestamps are the database's). */
    fun insert(fact: NewFact): LearnedFact =
        jdbc
            .query(
                INSERT_SQL,
                MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("scope", fact.scope.name)
                    .addValue("workspaceId", fact.workspaceId)
                    .addValue("datasourceName", fact.datasourceName)
                    .addValue("kind", fact.kind.wire)
                    .addValue("fact", fact.fact)
                    .addValue("refsJson", refsJson(fact.refs))
                    .addValue("evidenceSql", fact.evidenceSql)
                    .addValue("evidenceSummary", fact.evidenceSummary)
                    .addValue("trust", fact.trust.wire)
                    .addValue("schemaFingerprint", fact.schemaFingerprint)
                    .addValue("recordedBy", fact.recordedBy)
                    .addValue("recordedVia", fact.recordedVia)
                    .addValue("recordedIn", fact.recordedIn)
                    .addValue("sourcePipelineId", fact.sourcePipelineId)
                    .addValue("sourceVersion", fact.sourceVersion)
                    .addValue("supersedes", fact.supersedes),
                rowMapper,
            ).single()

    /** The row under [id] when [readerWorkspaceId] may see it (the visibility predicate), else null. */
    fun findVisible(
        id: UUID,
        readerWorkspaceId: UUID,
    ): LearnedFact? =
        jdbc
            .query(
                "$SELECT WHERE id = :id AND $VISIBLE",
                MapSqlParameterSource().addValue("id", id).addValue("reader", readerWorkspaceId),
                rowMapper,
            ).singleOrNull()

    /**
     * 7e (transform-nodes design §2.3) — of [ids], the facts [readerWorkspaceId] may CITE from a
     * script: visible to it (the ONE predicate) AND a `WORKSPACE` fact of a workspace-scoped
     * kind (`definition`, `exclusion`, `preference` — derived from [LearnedFactKind], never
     * typed). A DATASOURCE fact is excluded by rule (a script never sees a datasource), and an
     * id another workspace owns is simply absent — the caller answers both as not-found. Trust is
     * NOT a condition: citing a retired fact is legal and reads `needs_review` at once.
     */
    fun findCitable(
        ids: Collection<UUID>,
        readerWorkspaceId: UUID,
    ): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return jdbc
            .query(
                "SELECT id FROM learned_facts WHERE id IN (:ids) AND $VISIBLE" +
                    " AND scope = 'WORKSPACE' AND kind IN (:kinds)",
                MapSqlParameterSource()
                    .addValue("ids", ids.distinct())
                    .addValue("reader", readerWorkspaceId)
                    .addValue("kinds", WORKSPACE_KINDS),
            ) { rs, _ -> rs.getObject("id", UUID::class.java) }
            .toSet()
    }

    /**
     * Every fact on [datasourceName] visible to [readerWorkspaceId], oldest first — the set an
     * introspection response is enriched from, and the listing's base. Retired facts are
     * excluded unless [includeRetired]; [since] narrows to facts recorded at or after it (the
     * §9 "what was recorded since" question).
     */
    fun findVisibleByDatasource(
        datasourceName: String,
        readerWorkspaceId: UUID,
        includeRetired: Boolean = false,
        since: Instant? = null,
    ): List<LearnedFact> =
        jdbc.query(
            "$SELECT WHERE datasource_name = :datasource AND $VISIBLE" +
                (if (includeRetired) "" else " AND trust <> 'retired'") +
                (if (since != null) " AND recorded_at >= :since" else "") +
                " ORDER BY recorded_at, id",
            MapSqlParameterSource()
                .addValue("datasource", datasourceName)
                .addValue("reader", readerWorkspaceId)
                .addValue("since", since?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) }),
            rowMapper,
        )

    /**
     * O-4 — the live fact that is an EXACT duplicate of the candidate, or null. The key is
     * `(scope, workspace, datasource, kind, refs, fact)`; retired facts do not count (a retired
     * fact may be re-recorded — that is how a retirement is corrected).
     */
    fun findDuplicate(
        scope: LearnedFactScope,
        workspaceId: UUID?,
        datasourceName: String,
        kind: LearnedFactKind,
        refs: List<FactRef>,
        fact: String,
    ): LearnedFact? =
        jdbc
            .query(
                "$SELECT WHERE scope = :scope AND workspace_id IS NOT DISTINCT FROM :workspaceId" +
                    " AND datasource_name = :datasource AND kind = :kind AND refs_json = CAST(:refsJson AS jsonb)" +
                    " AND fact = :fact AND trust <> 'retired' ORDER BY recorded_at LIMIT 1",
                MapSqlParameterSource()
                    .addValue("scope", scope.name)
                    .addValue("workspaceId", workspaceId)
                    .addValue("datasource", datasourceName)
                    .addValue("kind", kind.wire)
                    .addValue("refsJson", refsJson(refs))
                    .addValue("fact", fact),
                rowMapper,
            ).singleOrNull()

    /**
     * The §6 mark: sets [trust] on a LIVE row. One-way by construction — the predicate refuses
     * to touch a retired row, and the drift check only ever asks for a demotion. Returns whether
     * a row changed, so a read path can log the mark it made.
     */
    fun markTrust(
        id: UUID,
        trust: LearnedFactTrust,
    ): Boolean {
        require(trust != LearnedFactTrust.RETIRED) { "retire() is the only way to a retired fact" }
        return jdbc.update(
            "UPDATE learned_facts SET trust = :trust WHERE id = :id AND trust <> 'retired' AND trust <> :trust",
            MapSqlParameterSource().addValue("id", id).addValue("trust", trust.wire),
        ) > 0
    }

    /** D-S11 — retire, never delete. False when the row is already retired (or absent). */
    fun retire(
        id: UUID,
        reason: String,
    ): Boolean =
        jdbc.update(
            "UPDATE learned_facts SET trust = 'retired', retired_at = NOW(), retired_reason = :reason" +
                " WHERE id = :id AND trust <> 'retired'",
            MapSqlParameterSource().addValue("id", id).addValue("reason", reason),
        ) > 0

    /** Normalised: sorted by `schema.table.column` so equal ref sets store equal JSON. */
    private fun refsJson(refs: List<FactRef>): String =
        mapper.writeValueAsString(
            refs
                .sortedBy { "${it.schema.orEmpty()} ${it.table} ${it.column.orEmpty()}" }
                .map { mapOf("schema" to it.schema, "table" to it.table, "column" to it.column) },
        )

    private val rowMapper =
        RowMapper { rs: ResultSet, _: Int ->
            LearnedFact(
                id = rs.getObject("id", UUID::class.java),
                scope = LearnedFactScope.valueOf(rs.getString("scope")),
                workspaceId = rs.getObject("workspace_id", UUID::class.java),
                datasourceName = rs.getString("datasource_name"),
                kind = requireNotNull(LearnedFactKind.fromWire(rs.getString("kind"))) { "unknown kind in learned_facts" },
                fact = rs.getString("fact"),
                refs = readRefs(rs.getString("refs_json")),
                evidenceSql = rs.getString("evidence_sql"),
                evidenceSummary = rs.getString("evidence_summary"),
                trust = LearnedFactTrust.fromWire(rs.getString("trust")),
                schemaFingerprint = rs.getString("schema_fingerprint"),
                recordedBy = rs.getObject("recorded_by", UUID::class.java),
                recordedVia = rs.getString("recorded_via"),
                recordedIn = rs.getObject("recorded_in", UUID::class.java),
                sourcePipelineId = rs.getObject("source_pipeline_id", UUID::class.java),
                sourceVersion = rs.getInt("source_version").takeUnless { rs.wasNull() },
                recordedAt = rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                verifiedBy = rs.getObject("verified_by", UUID::class.java),
                verifiedAt = rs.getObject("verified_at", OffsetDateTime::class.java)?.toInstant(),
                supersedes = rs.getObject("supersedes", UUID::class.java),
                retiredAt = rs.getObject("retired_at", OffsetDateTime::class.java)?.toInstant(),
                retiredReason = rs.getString("retired_reason"),
            )
        }

    private fun readRefs(json: String): List<FactRef> =
        mapper.readValue<List<Map<String, String?>>>(json).map {
            FactRef(schema = it["schema"], table = requireNotNull(it["table"]) { "ref without a table" }, column = it["column"])
        }

    private companion object {
        val DEFAULT_MAPPER: ObjectMapper = jacksonObjectMapper()

        const val COLUMNS =
            "id, scope, workspace_id, datasource_name, kind, fact, refs_json, evidence_sql, evidence_summary, trust, " +
                "schema_fingerprint, recorded_by, recorded_via, recorded_in, source_pipeline_id, source_version, " +
                "recorded_at, verified_by, verified_at, supersedes, retired_at, retired_reason"

        const val SELECT = "SELECT $COLUMNS FROM learned_facts"

        /** D-S1/D-S9 — the ONE visibility predicate. */
        const val VISIBLE = "(scope = 'DATASOURCE' OR workspace_id = :reader)"

        /** The workspace-scoped kinds a script may implement (§2.3), from the enum's own scope column. */
        val WORKSPACE_KINDS: List<String> = LearnedFactKind.entries.filter { it.scope == LearnedFactScope.WORKSPACE }.map { it.wire }

        val INSERT_SQL =
            """
            INSERT INTO learned_facts
                (id, scope, workspace_id, datasource_name, kind, fact, refs_json, evidence_sql, evidence_summary,
                 trust, schema_fingerprint, recorded_by, recorded_via, recorded_in, source_pipeline_id, source_version,
                 supersedes)
            VALUES
                (:id, :scope, :workspaceId, :datasourceName, :kind, :fact, CAST(:refsJson AS jsonb), :evidenceSql,
                 :evidenceSummary, :trust, :schemaFingerprint, :recordedBy, :recordedVia, :recordedIn, :sourcePipelineId,
                 :sourceVersion, :supersedes)
            RETURNING $COLUMNS
            """.trimIndent()
    }
}
