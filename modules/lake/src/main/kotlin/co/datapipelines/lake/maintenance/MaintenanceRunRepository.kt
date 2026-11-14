package co.datapipelines.lake.maintenance

import co.datapipelines.lake.LakeProperties
import co.datapipelines.pipeline.PipelineJson
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What the admitting statement answered (#957 D4). */
sealed interface Admission {
    /** The run's row is written, `running`, with [deadline] on the catalog's clock; [abandoned] are the rows it closed. */
    data class Admitted(
        val runId: UUID,
        val deadline: Instant,
        val abandoned: List<UUID>,
    ) : Admission

    /** An earlier run of the table is still running and not past its deadline: no row; [deadline] is that run's. */
    data class EarlierRunLive(
        val deadline: Instant?,
    ) : Admission
}

/** A run row as stored (the tests' and the table page's read). */
data class MaintenanceRunRow(
    val id: UUID,
    val tableId: UUID,
    val kind: String,
    val state: String,
    val deadline: Instant,
    val scratchReservedBytes: Long,
    val figures: PartFigures,
    val partsJson: String,
    val settlementJson: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val errorCode: String?,
    val errorMessage: String?,
)

/**
 * `lake_maintenance_runs` (V60; #957 D4) and the two metadata rows a run reads before admission: the table's row and the
 * catalog's mode. Every statement is parameterised and autocommits on its own (a bounded template, no transaction around
 * it). Every write after the admitting one is conditional on `state = 'running'`: a row its successor marked `abandoned`
 * is never overwritten by its late worker (the record's §11a call 34, rule 2).
 */
class MaintenanceRunRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper = PipelineJson.objectMapper(),
) : MaintenanceRunStore {
    /** The table's row (D3 step 3's input), or null when there is no managed table by that id. */
    override fun findTable(tableId: UUID): MaintenanceTable? =
        jdbc
            .query(
                "SELECT t.id, n.name AS namespace, t.name, t.status FROM lake_tables_managed t " +
                    "JOIN lake_namespaces n ON n.id = t.namespace_id WHERE t.id = :id",
                mapOf("id" to tableId),
            ) { rs, _ ->
                MaintenanceTable(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("namespace"),
                    rs.getString("name"),
                    rs.getString("status"),
                )
            }.singleOrNull()

    /** True when `lake_control.mode` is `normal` (D3 step 1): a restoring or replaying catalog admits no run. */
    override fun catalogNormal(): Boolean =
        jdbc.queryForObject(
            "SELECT mode = :mode FROM lake_control WHERE id = 1",
            mapOf("mode" to LakeProperties.MODE_NORMAL),
            Boolean::class.java,
        ) == true

    /**
     * The ONE admitting statement (D4): it closes an earlier `running` row of [tableId] whose deadline has passed as
     * `abandoned`, and inserts the new `running` row with `deadline_at = now() + timeout`, both on the catalog's clock and
     * both or neither. An earlier `running` row not yet past its deadline admits nothing and answers its deadline. The
     * partial unique index decides a race between two instances: the loser inserts nothing.
     *
     * The insert reads the `abandoned` CTE, so the abandonment runs first and the old row has left the unique index when
     * the insert checks it.
     */
    override fun admit(
        runId: UUID,
        tableId: UUID,
        kind: MaintenanceRunKind,
        timeout: Duration,
        scratchReservedBytes: Long,
    ): Admission {
        val params =
            mapOf(
                "id" to runId,
                "table" to tableId,
                "kind" to kind.wire,
                "seconds" to timeout.toMillis() / MILLIS_PER_SECOND.toDouble(),
                "scratch" to scratchReservedBytes,
                "abandonedCode" to ABANDONED_CODE,
                "abandonedMessage" to ABANDONED_MESSAGE,
            )
        val answer =
            jdbc.queryForObject(ADMIT_SQL, params) { rs, _ ->
                Triple(
                    rs.getObject("id", UUID::class.java),
                    rs.getTimestamp("deadline_at")?.toInstant(),
                    rs.getTimestamp("live_deadline")?.toInstant() to (rs.getArray("abandoned")?.array as? Array<*>).orEmpty(),
                )
            }!!
        val (inserted, deadline, rest) = answer
        val (liveDeadline, abandoned) = rest
        val abandonedIds = abandoned.map { it as UUID }
        return if (inserted != null && deadline != null) {
            Admission.Admitted(inserted, deadline, abandonedIds)
        } else {
            // A concurrent winner committed after this statement's snapshot: read its deadline with a fresh one.
            Admission.EarlierRunLive(liveDeadline ?: runningDeadline(tableId))
        }
    }

    /** The deadline of [tableId]'s `running` row, or null when none is running. */
    fun runningDeadline(tableId: UUID): Instant? =
        jdbc
            .query(
                "SELECT deadline_at FROM lake_maintenance_runs WHERE table_id = :table AND state = 'running'",
                mapOf("table" to tableId),
            ) { rs, _ -> rs.getTimestamp("deadline_at").toInstant() }
            .singleOrNull()

    /** Records the run's progress (the parts so far, the settlement); answers false when the row is no longer `running`. */
    override fun recordProgress(
        runId: UUID,
        parts: List<PartRecord>,
        settlement: SettlementRecord?,
    ): Boolean =
        jdbc.update(
            "UPDATE lake_maintenance_runs SET parts_json = CAST(:parts AS jsonb), settlement_json = CAST(:settlement AS jsonb), " +
                "files_touched = :files, bytes_touched = :bytes, deferred_deletions = :deferred " +
                "WHERE id = :id AND state = 'running'",
            progressParams(runId, parts, settlement),
        ) == 1

    /**
     * Closes the run `finished` or `failed` (D3 step 8), conditional on the row still being `running`; answers false when
     * a successor had already marked it `abandoned` (the row is left exactly as the successor wrote it).
     */
    override fun close(
        runId: UUID,
        state: MaintenanceRunState,
        parts: List<PartRecord>,
        settlement: SettlementRecord?,
        errorCode: String?,
        errorMessage: String?,
    ): Boolean {
        require(
            state == MaintenanceRunState.FINISHED || state == MaintenanceRunState.FAILED,
        ) { "a worker closes finished or failed: $state" }
        return jdbc.update(
            "UPDATE lake_maintenance_runs SET state = :state, ended_at = now(), parts_json = CAST(:parts AS jsonb), " +
                "settlement_json = CAST(:settlement AS jsonb), files_touched = :files, bytes_touched = :bytes, " +
                "deferred_deletions = :deferred, error_code = :code, error_message = :message " +
                "WHERE id = :id AND state = 'running'",
            progressParams(runId, parts, settlement) +
                mapOf("state" to state.wire, "code" to errorCode, "message" to errorMessage?.take(MAX_MESSAGE)),
        ) == 1
    }

    fun find(runId: UUID): MaintenanceRunRow? =
        jdbc.query("SELECT * FROM lake_maintenance_runs WHERE id = :id", mapOf("id" to runId), ::row).singleOrNull()

    fun forTable(tableId: UUID): List<MaintenanceRunRow> =
        jdbc.query("SELECT * FROM lake_maintenance_runs WHERE table_id = :t ORDER BY started_at, id", mapOf("t" to tableId), ::row)

    /** The `parts_json` value of [parts], in the wire shape the migration documents; a part's details follow its keys. */
    fun partsJson(parts: List<PartRecord>): String =
        mapper.writeValueAsString(
            parts.map { part ->
                linkedMapOf<String, Any?>(
                    "name" to part.name,
                    "outcome" to part.outcome.wire,
                    "attempted" to part.attempted,
                    "files" to part.figures.files,
                    "bytes" to part.figures.bytes,
                    "deferred_deletions" to part.figures.deferredDeletions,
                    "until" to part.until?.toString(),
                    "reason" to part.reason,
                    "error_code" to part.errorCode,
                ).apply { putAll(part.details) }
            },
        )

    private fun settlementJson(settlement: SettlementRecord?): String? =
        settlement?.let {
            mapper.writeValueAsString(
                linkedMapOf(
                    "settled" to it.settled,
                    "visited" to it.visited,
                    "left_unsettled" to it.leftUnsettled,
                    "deferred" to it.deferred,
                    "error_code" to it.errorCode,
                ),
            )
        }

    private fun progressParams(
        runId: UUID,
        parts: List<PartRecord>,
        settlement: SettlementRecord?,
    ): Map<String, Any?> {
        val total = parts.fold(PartFigures.NONE) { sum, part -> sum + part.figures }
        return mapOf(
            "id" to runId,
            "parts" to partsJson(parts),
            "settlement" to settlementJson(settlement),
            "files" to total.files,
            "bytes" to total.bytes,
            "deferred" to total.deferredDeletions,
        )
    }

    private fun row(
        rs: java.sql.ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNum: Int,
    ) = MaintenanceRunRow(
        id = rs.getObject("id", UUID::class.java),
        tableId = rs.getObject("table_id", UUID::class.java),
        kind = rs.getString("kind"),
        state = rs.getString("state"),
        deadline = rs.getTimestamp("deadline_at").toInstant(),
        scratchReservedBytes = rs.getLong("scratch_reserved_bytes"),
        figures = PartFigures(rs.getLong("files_touched"), rs.getLong("bytes_touched"), rs.getLong("deferred_deletions")),
        partsJson = rs.getString("parts_json"),
        settlementJson = rs.getString("settlement_json"),
        startedAt = rs.getTimestamp("started_at").toInstant(),
        endedAt = rs.getTimestamp("ended_at")?.let(Timestamp::toInstant),
        errorCode = rs.getString("error_code"),
        errorMessage = rs.getString("error_message"),
    )

    companion object {
        /** The code an abandoned row carries: its outcome was never confirmed by its worker. */
        const val ABANDONED_CODE = MaintenanceErrors.OUTCOME_NOT_CONFIRMED

        /** The abandoned row's message: nothing is concluded from it (§11a call 34, rule 2). */
        const val ABANDONED_MESSAGE = "outcome not confirmed: the run passed its deadline while running and a successor took the table over"

        private const val MILLIS_PER_SECOND = 1000L
        private const val MAX_MESSAGE = 500

        /**
         * The admitting statement (D4). `now()` is the statement's start on the catalog's clock, the same instant for the
         * abandonment, the liveness test and the new deadline.
         */
        internal val ADMIT_SQL =
            """
            WITH abandoned AS (
                UPDATE lake_maintenance_runs
                   SET state = 'abandoned', ended_at = now(), error_code = :abandonedCode, error_message = :abandonedMessage
                 WHERE table_id = :table AND state = 'running' AND deadline_at <= now()
                RETURNING id
            ), live AS (
                SELECT deadline_at FROM lake_maintenance_runs
                 WHERE table_id = :table AND state = 'running' AND deadline_at > now()
            ), inserted AS (
                INSERT INTO lake_maintenance_runs (id, table_id, kind, state, deadline_at, scratch_reserved_bytes, started_at)
                SELECT :id, :table, :kind, 'running', now() + make_interval(secs => CAST(:seconds AS double precision)), :scratch, now()
                 WHERE NOT EXISTS (SELECT 1 FROM live) AND (SELECT count(*) FROM abandoned) >= 0
                ON CONFLICT (table_id) WHERE state = 'running' DO NOTHING
                RETURNING id, deadline_at
            )
            SELECT (SELECT id FROM inserted) AS id,
                   (SELECT deadline_at FROM inserted) AS deadline_at,
                   (SELECT deadline_at FROM live) AS live_deadline,
                   (SELECT array_agg(id) FROM abandoned) AS abandoned
            """.trimIndent()
    }
}
