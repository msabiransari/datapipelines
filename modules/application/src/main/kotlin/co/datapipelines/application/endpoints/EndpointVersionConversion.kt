package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.PipelineVersionStatus
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * The in-place conversion of existing APIs (#786 §E; record §24; owner ruling 2026-11-08: one server, convert in place,
 * no floor). An API saved before V58 is an entity row with its legacy `pipeline_id`/`timeout_seconds`/`description`
 * and NO version row; this family gives each one its version 1 — the exact pin of what it serves today.
 *
 * - **E.0 precondition, before any write** ([precondition], [logPrecondition]). Every version-less entity's legacy
 *   pipeline must exist, have a version and have a CURRENT pointer. Any failure is one structured ERROR line per row
 *   plus a summary naming the operator's fix; the startup runner refuses the server after EVERY family's precondition
 *   was read, so nothing is written: no pin is invented, no row is disabled. A CURRENT the deployment's posture makes
 *   ineligible (a DRAFT pointer where drafts do not serve) is NOT a failure — it converts to an exact DRAFT pin and
 *   keeps refusing at serve exactly as before.
 * - **E.1, inside the runner's ONE transaction** (#895: every API, schedule and queued run of one startup converts in
 *   one transaction, so a failure anywhere rolls every family back). This family owns no transaction and takes no
 *   workspace or registry lock: [plan] reads the candidates and reports their workspaces; the runner acquires the
 *   workspace guard over the union of every family's workspaces, then the endpoint registry key (G0 classes 2, 3);
 *   [Attempt.lockEntities] then locks each planned entity row in id order (class 4); [Attempt.convert] re-reads the
 *   candidates under those locks — a concurrent conversion or a native create already wrote v1 → no longer selected; a
 *   concurrent discard moved or cleared CURRENT → the pin is the CURRENT read under the lock, or the start refuses as
 *   E.0 would; a candidate that appeared since the plan, or sits in a workspace the union does not cover → an
 *   [EndpointConversionConflict] (the runner rolls back and re-plans). v1 = the portable pipeline name, that exact
 *   version, the legacy timeout and description, the shared hash; RELEASED iff that pipeline version is RELEASED,
 *   else DRAFT; `current_version = 1`. `is_enabled` and the key bindings are untouched; no closure is evaluated
 *   (ruling 2).
 * - **Commit-time effects.** The converted lines, the registry invalidation (required after COMMIT) and the summary are
 *   [EndpointConversionOutcome.publish], which the runner calls once, after the joint commit — never for an attempt
 *   that rolled back.
 * - **The selector is legacy-only by construction** (E.2): every creation path writes v1 in its own transaction, so a
 *   rerun selects nothing and is a no-op.
 */
class EndpointVersionConversion(
    private val jdbc: NamedParameterJdbcTemplate,
    private val versions: PublishedEndpointVersionRepository,
    private val registry: EndpointRegistry,
) {
    private val log = LoggerFactory.getLogger(EndpointVersionConversion::class.java)

    /** E.0 — every version-less entity whose legacy pipeline cannot be pinned, in path order; empty = convertible. */
    fun precondition(): List<ConversionRefusal> = candidates().mapNotNull { it.refusal() }

    /** [precondition], each refusal logged as its structured ERROR line plus the summary; writes nothing (#786 D.5). */
    fun logPrecondition(): Int {
        val refusals = precondition()
        logRefusals(refusals)
        return refusals.size
    }

    /** One attempt's plan, read inside the runner's transaction before any lock: the candidates and their workspaces. */
    fun plan(): Attempt =
        candidates().let { planned ->
            Attempt(planned.map { it.endpointId }, planned.mapTo(sortedSetOf()) { it.workspaceId })
        }

    /** This family's part of one attempt of the runner's transaction. */
    inner class Attempt internal constructor(
        private val planned: List<UUID>,
        /** Every workspace the planned candidates sit in. */
        val workspaces: Set<UUID>,
    ) {
        private val locked = sortedSetOf<UUID>()

        /** G0 class 4, after the runner's workspace guard and registry key: each planned entity row, in id order. */
        fun lockEntities() {
            planned.sorted().forEach { id -> versions.lockEntity(id)?.let { locked += it.endpointId } }
        }

        /**
         * Re-reads the candidates under the locks and writes each one's v1, in path order. Throws
         * [EndpointConversionConflict] for a candidate the locks do not cover, and [ConversionRefusedException] (after
         * logging it) when a candidate's pipeline lost its CURRENT since the precondition; either way the runner's
         * transaction rolls back every write of this startup.
         */
        fun convert(guarded: Set<UUID>): EndpointConversionOutcome {
            val current = candidates()
            current.firstOrNull { it.workspaceId !in guarded || it.endpointId !in locked }?.let {
                throw EndpointConversionConflict("API ${it.path} appeared outside the planned locks; re-planning from fresh rows")
            }
            return EndpointConversionOutcome(ConversionReport(current.map { convertLocked(it) }), this@EndpointVersionConversion::publish)
        }
    }

    /** Converts [locked], a candidate re-read under the runner's locks, inside the runner's transaction. */
    private fun convertLocked(locked: Candidate): ConvertedEndpoint {
        locked.refusal()?.let { refuseIfAny(listOf(it)) }
        val pin = locked.pin()
        versions.insert(locked.endpointId, EndpointWrites.FIRST_VERSION, pin.status, pin.body, locked.createdBy)
        versions.setCurrent(locked.endpointId, EndpointWrites.FIRST_VERSION)
        return ConvertedEndpoint(locked.path, pin.body.pipelineName, pin.body.pipelineVersion, pin.status)
    }

    /** The commit-time effects of [report]: one line per converted API, the invalidation (after COMMIT), the summary. */
    private fun publish(report: ConversionReport) {
        report.converted.forEach {
            log.info(
                "event=endpoint.conversion.converted path={} pipeline={} pipeline_version={} status={}",
                it.path,
                it.pipeline,
                it.pipelineVersion,
                it.status,
            )
        }
        if (report.converted.isNotEmpty()) registry.invalidate()
        log.info(
            "event=endpoint.conversion.summary converted={} released={} draft={}",
            report.converted.size,
            report.converted.count { it.status == PipelineVersionStatus.RELEASED },
            report.converted.count { it.status == PipelineVersionStatus.DRAFT },
        )
    }

    private fun refuseIfAny(refusals: List<ConversionRefusal>) {
        if (refusals.isEmpty()) return
        logRefusals(refusals)
        throw ConversionRefusedException(refusals)
    }

    private fun logRefusals(refusals: List<ConversionRefusal>) {
        if (refusals.isEmpty()) return
        refusals.forEach {
            log.error(
                "event=endpoint.conversion.refused kind=api id={} path={} pipeline={} reason={}",
                it.endpointId,
                it.path,
                it.pipeline,
                it.reason.wire,
            )
        }
        log.error(
            "event=endpoint.conversion.refused_summary refused={} fix=\"{}\"",
            refusals.size,
            ConversionRefusedException.OPERATOR_FIX,
        )
    }

    private fun candidates(): List<Candidate> =
        jdbc.query("$CANDIDATES_SQL ORDER BY e.path_pattern, e.id", emptyMap<String, Any>()) { rs, _ -> candidate(rs) }

    private fun candidate(rs: ResultSet) =
        Candidate(
            endpointId = rs.getObject("id", UUID::class.java),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            path = rs.getString("path_pattern"),
            createdBy = rs.getObject("created_by", UUID::class.java),
            legacyPipelineId = rs.getObject("pipeline_id", UUID::class.java),
            timeoutSeconds = rs.getInt("timeout_seconds"),
            description = rs.getString("description").orEmpty(),
            pipelineName = rs.getString("pipeline_name"),
            versionCount = rs.getInt("version_count"),
            currentVersion = rs.getInt("current_version").takeUnless { rs.wasNull() },
            currentStatus = rs.getString("current_status")?.let(PipelineVersionStatus::valueOf),
        )

    /** One version-less entity and what its legacy pipeline holds right now. */
    private data class Candidate(
        val endpointId: UUID,
        val workspaceId: UUID,
        val path: String,
        val createdBy: UUID,
        val legacyPipelineId: UUID,
        val timeoutSeconds: Int,
        val description: String,
        val pipelineName: String?,
        val versionCount: Int,
        val currentVersion: Int?,
        val currentStatus: PipelineVersionStatus?,
    ) {
        fun refusal(): ConversionRefusal? {
            val reason =
                when {
                    pipelineName == null -> {
                        ConversionRefusal.Reason.MISSING_PIPELINE
                    }

                    versionCount == 0 -> {
                        ConversionRefusal.Reason.NO_VERSIONS
                    }

                    // A pointer at a DISCARDED row serves nothing today; pinning it would invent a version.
                    currentVersion == null || currentStatus == null || currentStatus == PipelineVersionStatus.DISCARDED -> {
                        ConversionRefusal.Reason.NO_CURRENT
                    }

                    else -> {
                        return null
                    }
                }
            return ConversionRefusal(endpointId, path, pipelineName ?: legacyPipelineId.toString(), reason)
        }

        fun pin(): Pin {
            val name = requireNotNull(pipelineName) { "refusal() answered null, so the pipeline exists" }
            val version = requireNotNull(currentVersion) { "refusal() answered null, so CURRENT exists" }
            val status =
                if (currentStatus ==
                    PipelineVersionStatus.RELEASED
                ) {
                    PipelineVersionStatus.RELEASED
                } else {
                    PipelineVersionStatus.DRAFT
                }
            return Pin(EndpointVersionBody(name, version, timeoutSeconds, description), status)
        }
    }

    private data class Pin(
        val body: EndpointVersionBody,
        val status: PipelineVersionStatus,
    )

    private companion object {
        /**
         * The legacy-only selector (E.2): entities with NO version row, joined to the legacy pipeline (same workspace)
         * and its CURRENT version. No closure predicate, no datasource check (ruling 2).
         */
        const val CANDIDATES_SQL =
            "SELECT e.id, e.workspace_id, e.path_pattern, e.created_by, e.pipeline_id, e.timeout_seconds, e.description," +
                " p.name AS pipeline_name, p.current_version, cv.status AS current_status," +
                " (SELECT COUNT(*) FROM pipeline_versions pv WHERE pv.pipeline_id = p.id) AS version_count" +
                " FROM published_endpoints e" +
                " LEFT JOIN pipelines p ON p.id = e.pipeline_id AND p.workspace_id = e.workspace_id" +
                " LEFT JOIN pipeline_versions cv ON cv.pipeline_id = p.id AND cv.version = p.current_version" +
                " WHERE NOT EXISTS (SELECT 1 FROM published_endpoint_versions v WHERE v.endpoint_id = e.id)"
    }
}

/** One E.0 failure: an API whose legacy pipeline cannot be pinned exactly. */
data class ConversionRefusal(
    val endpointId: UUID,
    val path: String,
    val pipeline: String,
    val reason: Reason,
) {
    /** The `reason=` words of the structured ERROR line (brief E.0). */
    enum class Reason(
        val wire: String,
    ) {
        MISSING_PIPELINE("missing_pipeline"),
        NO_VERSIONS("no_versions"),
        NO_CURRENT("no_current"),
    }
}

/** One converted entity — a line of the conversion report. */
data class ConvertedEndpoint(
    val path: String,
    val pipeline: String,
    val pipelineVersion: Int,
    val status: PipelineVersionStatus,
)

/** What one run converted, in path order; empty on a rerun. */
data class ConversionReport(
    val converted: List<ConvertedEndpoint>,
)

/** One committed-or-discarded attempt's [report] and its commit-time effects, which only the runner [publish]es. */
class EndpointConversionOutcome internal constructor(
    val report: ConversionReport,
    private val effects: (ConversionReport) -> Unit,
) {
    /** Logs the converted lines and the summary and invalidates the registry — call only after the joint COMMIT. */
    fun publish() = effects(report)
}

/** A candidate the runner's locks do not cover: the runner rolls the whole transaction back and re-plans. */
class EndpointConversionConflict(
    message: String,
) : RuntimeException(message)

/** E.0 refused: the server must not start. [refusals] names every row; nothing was written for them. */
class ConversionRefusedException(
    val refusals: List<ConversionRefusal>,
) : IllegalStateException(
        "The API version conversion refused to start: ${refusals.size} API(s) reference a pipeline that has no " +
            "current version to pin (${refusals.joinToString { "${it.path} -> ${it.pipeline}: ${it.reason.wire}" }}). $OPERATOR_FIX",
    ) {
    companion object {
        /** The operator's fix, named in the summary line and in docs/deployment.md §8. */
        const val OPERATOR_FIX =
            "On the previous version, release the pipeline or delete the API, then upgrade again."
    }
}
