package co.datapipelines.lake.maintenance.plan

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** The state of a table's profile-dependent figures (brief 959 D3a, D8). */
enum class FiguresState(
    val wire: String,
) {
    CURRENT("current"),
    STALE("stale"),
    NEVER_MEASURED("never_measured"),
}

/**
 * One table's planner figures (record §11b call 46, "The planner's figures"; brief 959 D8), with their times. A figure
 * whose source was not read is null, never zero.
 */
data class PlanFigures(
    val tableId: UUID,
    /** The last attempt's time and error (null after a success). */
    val attemptedAt: Instant?,
    val errorCode: String?,
    /** The last successful measurement's time; null when the table was never measured. */
    val measuredAt: Instant?,
    val currentBytes: Long?,
    val currentRows: Long?,
    val currentFiles: Long?,
    val smallFiles: Long?,
    val candidateBytes: Long?,
    val figuresState: FiguresState,
    /** Current bytes now minus those of the newest usage row measured at least 7 days ago; null without one. */
    val growthPerWeek: Long?,
    /** That baseline row's real measurement time (it can be days older than seven: cut L8). */
    val growthBaselineAt: Instant?,
)

/** The read method for P8 (D8): one statement per table. */
class PlanFiguresReader(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun figures(tableId: UUID): PlanFigures? =
        jdbc
            .query(
                "SELECT t.id, t.profile_id, p.updated_at AS profile_updated_at, m.attempted_at, m.error_code, m.measured_at, " +
                    "m.current_bytes, m.current_rows, m.data_files, m.delete_files, m.small_files, m.candidate_bytes, " +
                    "m.figures_profile_id, m.figures_profile_updated_at, b.measured_at AS baseline_at, b.current_bytes AS baseline_bytes " +
                    "FROM lake_tables_managed t JOIN lake_profiles p ON p.id = t.profile_id " +
                    "LEFT JOIN lake_table_measurements m ON m.table_id = t.id " +
                    "LEFT JOIN LATERAL (SELECT u.measured_at, u.current_bytes FROM lake_storage_usage u WHERE u.table_id = t.id " +
                    "AND u.measured_at <= now() - make_interval(days => :days) ORDER BY u.measured_at DESC LIMIT 1) b ON true " +
                    "WHERE t.id = :table",
                mapOf("table" to tableId, "days" to GROWTH_BASELINE_DAYS),
            ) { rs, _ -> figures(rs) }
            .singleOrNull()

    private fun figures(rs: ResultSet): PlanFigures {
        fun long(column: String): Long? = (rs.getObject(column) as Number?)?.toLong()

        fun instant(column: String): Instant? = rs.getTimestamp(column)?.toInstant()
        val measuredAt = instant("measured_at")
        val state =
            when {
                measuredAt == null -> FiguresState.NEVER_MEASURED
                rs.getObject("figures_profile_id", UUID::class.java) == rs.getObject("profile_id", UUID::class.java) &&
                    instant("figures_profile_updated_at") == instant("profile_updated_at") -> FiguresState.CURRENT
                else -> FiguresState.STALE
            }
        val bytes = long("current_bytes")
        val dataFiles = long("data_files")
        val baselineBytes = long("baseline_bytes")
        return PlanFigures(
            tableId = rs.getObject("id", UUID::class.java),
            attemptedAt = instant("attempted_at"),
            errorCode = rs.getString("error_code"),
            measuredAt = measuredAt,
            currentBytes = bytes,
            currentRows = long("current_rows"),
            currentFiles = dataFiles?.let { it + (long("delete_files") ?: 0) },
            smallFiles = long("small_files"),
            candidateBytes = long("candidate_bytes"),
            figuresState = state,
            growthPerWeek = if (bytes != null && baselineBytes != null) bytes - baselineBytes else null,
            growthBaselineAt = if (bytes != null && baselineBytes != null) instant("baseline_at") else null,
        )
    }

    companion object {
        /** Call 46: growth per WEEK reads the newest usage row at least seven days old. */
        const val GROWTH_BASELINE_DAYS = 7
    }
}
