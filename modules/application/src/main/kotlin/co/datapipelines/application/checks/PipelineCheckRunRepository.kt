package co.datapipelines.application.checks

import co.datapipelines.pipeline.CheckRunVerdict
import co.datapipelines.pipeline.CheckRunVia
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * One `pipeline_check_runs` row (metadata-db §4.20): the server-side record of ONE check's run.
 *
 * Append-only by contract — nothing here updates or deletes a row. [via] and [verdict] map to
 * the pipeline-contract enums through their wire values; the CHECK constraints make an unknown
 * wire value unreachable, so an unmapped one fails loudly rather than deserializing into a lie
 * (the `LakeTableRepository` format precedent).
 */
data class PipelineCheckRun(
    val id: UUID,
    val pipelineId: UUID,
    val version: Int,
    val checkId: String,
    val ranAt: java.time.Instant,
    val ranBy: UUID?,
    val via: CheckRunVia,
    val parametersJson: String,
    val observedJson: String?,
    val verdict: CheckRunVerdict,
    val message: String?,
    val correlationId: String?,
    val durationMs: Long?,
)

/** What one run writes (140) — everything but the id and the DB-stamped `ran_at`. */
data class NewPipelineCheckRun(
    val pipelineId: UUID,
    val version: Int,
    val checkId: String,
    val ranBy: UUID?,
    val via: CheckRunVia,
    val parametersJson: String,
    val observedJson: String?,
    val verdict: CheckRunVerdict,
    val message: String?,
    val correlationId: String?,
    val durationMs: Long?,
)

/**
 * Persistence for `pipeline_check_runs` (metadata-db §4.20, round 140).
 *
 * A plain JDBC repository in the house shape
 * ([co.datapipelines.application.datasources.LakeTableRepository] is the sibling exemplar):
 * NamedParameterJdbcTemplate, one RowMapper, no JPA. Two verbs and no others, because the table
 * is append-only: [insert] is the run's own write; [latestPerCheck] is the release gate's and
 * the UI's read. The JSONB columns cross as text cast at the statement
 * (`CAST(:x AS jsonb)`) — the `ExecutionRepository` convention — never as a driver `PGobject`,
 * so serialization stays in this class.
 */
class PipelineCheckRunRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** Records one check run. `ran_at` is the database's own `NOW()` — never a client stamp. */
    fun insert(run: NewPipelineCheckRun): PipelineCheckRun =
        jdbc
            .query(
                """
                INSERT INTO pipeline_check_runs
                    (id, pipeline_id, version, check_id, ran_by, via, parameters_json, observed_json,
                     verdict, message, correlation_id, duration_ms)
                VALUES
                    (:id, :pipelineId, :version, :checkId, :ranBy, :via, CAST(:parametersJson AS jsonb),
                     CAST(:observedJson AS jsonb), :verdict, :message, :correlationId, :durationMs)
                $RETURNING
                """.trimIndent(),
                mapOf(
                    "id" to UUID.randomUUID(),
                    "pipelineId" to run.pipelineId,
                    "version" to run.version,
                    "checkId" to run.checkId,
                    "ranBy" to run.ranBy,
                    "via" to run.via.wire,
                    "parametersJson" to run.parametersJson,
                    "observedJson" to run.observedJson,
                    "verdict" to run.verdict.wire,
                    "message" to run.message,
                    "correlationId" to run.correlationId,
                    "durationMs" to run.durationMs,
                ),
                MAPPER,
            ).single()

    /**
     * The latest run of every check of one pipeline version — the read
     * `idx_pipeline_check_runs_latest` exists for. `DISTINCT ON` picks the newest row per
     * `check_id`; `id` breaks `ran_at` ties (two runs in one transaction share its `NOW()`)
     * deterministically, not meaningfully.
     */
    fun latestPerCheck(
        pipelineId: UUID,
        version: Int,
    ): List<PipelineCheckRun> =
        jdbc.query(
            """
            SELECT DISTINCT ON (check_id) id, pipeline_id, version, check_id, ran_at, ran_by, via,
                   parameters_json, observed_json, verdict, message, correlation_id, duration_ms
              FROM pipeline_check_runs
             WHERE pipeline_id = :pipelineId AND version = :version
             ORDER BY check_id, ran_at DESC, id DESC
            """.trimIndent(),
            mapOf("pipelineId" to pipelineId, "version" to version),
            MAPPER,
        )

    private companion object {
        const val RETURNING =
            """
            RETURNING id, pipeline_id, version, check_id, ran_at, ran_by, via, parameters_json,
                      observed_json, verdict, message, correlation_id, duration_ms
            """

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                PipelineCheckRun(
                    id = rs.getObject("id", UUID::class.java),
                    pipelineId = rs.getObject("pipeline_id", UUID::class.java),
                    version = rs.getInt("version"),
                    checkId = rs.getString("check_id"),
                    ranAt = rs.getTimestamp("ran_at").toInstant(),
                    ranBy = rs.getObject("ran_by", UUID::class.java),
                    via = CheckRunVia.fromWire(rs.getString("via")),
                    parametersJson = rs.getString("parameters_json"),
                    observedJson = rs.getString("observed_json"),
                    verdict = CheckRunVerdict.fromWire(rs.getString("verdict")),
                    message = rs.getString("message"),
                    correlationId = rs.getString("correlation_id"),
                    durationMs = rs.getLong("duration_ms").takeUnless { rs.wasNull() },
                )
            }
    }
}
