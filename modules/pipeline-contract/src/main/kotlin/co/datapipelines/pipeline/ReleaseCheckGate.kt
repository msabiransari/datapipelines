package co.datapipelines.pipeline

import java.util.UUID

/*
 * The run-time half of release checks (140): the model of ONE check run's outcome and the
 * port the release gate consumes.
 *
 * The runner itself lives in `modules/application` (`PipelineCheckRunner`) — it needs the
 * datasource registry and the bounded probe, which sit in modules this one may not depend
 * on (module-structure §5.2). So the dependency is inverted, exactly as [DatasourceRegistry]
 * and [TemplateVersionStatuses] already are: this module declares the facts and the
 * aggregation layer (`web`) supplies the implementation.
 */

/** The verdict of one check run — wire values `pass` / `fail` / `error` (metadata-db §4.20). */
enum class CheckRunVerdict(
    val wire: String,
) {
    /** The server's observed value satisfied the expectation. */
    PASS("pass"),

    /** The run produced a value and it did NOT satisfy the expectation. */
    FAIL("fail"),

    /**
     * No verdict could be formed: the datasource was unreachable, the statement was refused
     * or returned a shape the expectation cannot compare (two columns for a `value` check),
     * or the parameters did not bind. The truth, recorded — never silently a `fail`.
     */
    ERROR("error"),
    ;

    companion object {
        fun fromWire(value: String): CheckRunVerdict =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("Unknown check run verdict: '$value'")
    }
}

/** The surface a check run was commissioned from — wire values (metadata-db §4.20). */
enum class CheckRunVia(
    val wire: String,
) {
    MCP("mcp"),
    REST("rest"),
    UI("ui"),

    /** The release gate's own fresh run, commissioned by `PipelineReleaseService.release`. */
    RELEASE("release"),
    ;

    companion object {
        fun fromWire(value: String): CheckRunVia =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("Unknown check run via: '$value'")
    }
}

/**
 * One check's outcome from ONE server run — the unit the gate refuses on, the UI lists, and
 * `pipeline_check_runs` persists. [observed] is the server's own reading of the single cell
 * (or the row count), rendered as its wire string; null when [verdict] is
 * [CheckRunVerdict.ERROR] before a value could be read. There is deliberately no constructor
 * path that takes an observed value from a caller: only a run produces one.
 */
data class CheckRunOutcome(
    val checkId: String,
    val name: String,
    val expected: CheckExpectation,
    val observed: String?,
    val verdict: CheckRunVerdict,
    val message: String?,
    /**
     * The persisted row's database-stamped `ran_at` — null only for an unpersisted run (the
     * promotion receiver's pre-import gate, where no row can key to a pipeline that does not
     * exist yet).
     */
    val ranAt: java.time.Instant? = null,
)

/**
 * The release gate's port (versioning §5.3, 140): run [Pipeline.checks] fresh — `via` =
 * `release` — and return every outcome.
 *
 * Called by `PipelineReleaseService.release` AFTER the draft body has been read and
 * re-validated and BEFORE the status flip. The implementation (wired in `web`, riding
 * `modules/application`'s `PipelineCheckRunner`) also persists one `pipeline_check_runs` row
 * per check, so the refusal's details and the UI's latest-run list read the same runs.
 *
 * [NONE] is the default for constructions that predate the wiring (tests of the pre-140
 * behaviour): a version with no checks releases exactly as before — checks are opt-in.
 */
fun interface ReleaseCheckGate {
    fun runForRelease(
        workspaceId: UUID,
        pipelineId: UUID,
        version: Int,
        pipeline: Pipeline,
        actor: UUID,
    ): List<CheckRunOutcome>

    companion object {
        /** No gate at all — every version releases as today. */
        val NONE = ReleaseCheckGate { _, _, _, _, _ -> emptyList() }
    }
}
