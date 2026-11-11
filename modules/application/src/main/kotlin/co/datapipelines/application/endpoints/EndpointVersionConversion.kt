package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.guard.MetadataWriterGuard
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.util.UUID

/**
 * The in-place conversion of existing APIs (#786 §E; record §24; owner ruling 2026-11-08: one server, convert in place,
 * no floor). An API saved before V58 is an entity row with its legacy `pipeline_id`/`timeout_seconds`/`description`
 * and NO version row; this step gives each one its version 1 — the exact pin of what it serves today.
 *
 * - **E.0 precondition, before any write.** Every version-less entity's legacy pipeline must exist, have a version and
 *   have a CURRENT pointer. Any failure refuses the whole run ([ConversionRefusedException], one structured ERROR line
 *   per row plus a summary naming the operator's fix) and nothing is written: no pin is invented, no row is disabled.
 *   A CURRENT the deployment's posture makes ineligible (a DRAFT pointer where drafts do not serve) is NOT a failure —
 *   it converts to an exact DRAFT pin and keeps refusing at serve exactly as before.
 * - **E.1, per entity, under the G0 protocol** (workspace guard → registry key → entity row → write): the candidate is
 *   re-read under those locks — a concurrent conversion or a native create already wrote v1 → skipped; a concurrent
 *   discard moved or cleared CURRENT → the pin is the CURRENT read under the lock, or the run refuses as E.0 would.
 *   v1 = the portable pipeline name, that exact version, the legacy timeout and description, the shared hash;
 *   RELEASED iff that pipeline version is RELEASED, else DRAFT; `current_version = 1`. `is_enabled` and the key
 *   bindings are untouched; no closure is evaluated (ruling 2).
 * - **The selector is legacy-only by construction** (E.2): every creation path writes v1 in its own transaction, so a
 *   rerun selects nothing and is a no-op.
 *
 * The one advisory key that serialises the run against every API writer is the endpoint registry key (G0 class 3).
 */
class EndpointVersionConversion(
    private val jdbc: NamedParameterJdbcTemplate,
    private val versions: PublishedEndpointVersionRepository,
    private val registry: EndpointRegistry,
    private val transactions: TransactionOperations,
    private val writeGuard: MetadataWriterGuard,
) {
    private val log = LoggerFactory.getLogger(EndpointVersionConversion::class.java)

    /** E.0 — every version-less entity whose legacy pipeline cannot be pinned, in path order; empty = convertible. */
    fun precondition(): List<ConversionRefusal> = candidates(null).mapNotNull { it.refusal() }

    /** [precondition], each refusal logged as its structured ERROR line plus the summary; writes nothing (#786 D.5). */
    fun logPrecondition(): Int {
        val refusals = precondition()
        logRefusals(refusals)
        return refusals.size
    }

    /**
     * E.0 then E.1. Throws [ConversionRefusedException] (after logging the list) when E.0 fails — before any write — or
     * when a candidate's pipeline lost its CURRENT between the precondition and its locked re-read (that entity's
     * transaction rolls back; earlier entities stay converted, each exactly as this run would convert it again).
     */
    fun run(): ConversionReport {
        refuseIfAny(precondition())
        val converted = candidates(null).mapNotNull { convertOne(it.endpointId) }
        if (converted.isNotEmpty()) registry.invalidate()
        val report = ConversionReport(converted)
        log.info(
            "event=endpoint.conversion.summary converted={} released={} draft={}",
            report.converted.size,
            report.converted.count { it.status == PipelineVersionStatus.RELEASED },
            report.converted.count { it.status == PipelineVersionStatus.DRAFT },
        )
        return report
    }

    private fun convertOne(endpointId: UUID): ConvertedEndpoint? {
        var outcome: ConvertedEndpoint? = null
        transactions.executeWithoutResult {
            val first = candidates(endpointId).singleOrNull() ?: return@executeWithoutResult
            writeGuard.workspaces(listOf(first.workspaceId), PipelineErrorCodes.Endpoint.WRITE_BUSY)
            writeGuard.endpointRegistry(PipelineErrorCodes.Endpoint.WRITE_BUSY)
            versions.lockEntity(endpointId) ?: return@executeWithoutResult
            // Re-read UNDER the locks: what a concurrent writer committed while this transaction waited is what counts.
            val locked = candidates(endpointId).singleOrNull() ?: return@executeWithoutResult
            locked.refusal()?.let { refuseIfAny(listOf(it)) }
            val pin = locked.pin()
            versions.insert(endpointId, EndpointWrites.FIRST_VERSION, pin.status, pin.body, locked.createdBy)
            versions.setCurrent(endpointId, EndpointWrites.FIRST_VERSION)
            outcome = ConvertedEndpoint(locked.path, pin.body.pipelineName, pin.body.pipelineVersion, pin.status)
        }
        outcome?.let {
            log.info(
                "event=endpoint.conversion.converted path={} pipeline={} pipeline_version={} status={}",
                it.path,
                it.pipeline,
                it.pipelineVersion,
                it.status,
            )
        }
        return outcome
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

    private fun candidates(only: UUID?): List<Candidate> =
        jdbc.query(
            CANDIDATES_SQL + (if (only == null) "" else " AND e.id = :id") + " ORDER BY e.path_pattern, e.id",
            if (only == null) emptyMap() else mapOf("id" to only),
        ) { rs, _ -> candidate(rs) }

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
