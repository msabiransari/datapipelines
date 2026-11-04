package co.datapipelines.lake.definitions

import co.datapipelines.lake.LakeProperties
import com.fasterxml.jackson.databind.json.JsonMapper

/**
 * A new managed table's `policy_json` (metadata-db §4.49): the environment's retention and maintenance defaults from
 * `datapipelines.lake.retention.*` / `datapipelines.lake.maintenance.*`, copied at creation. P3 stores it; P6 reads
 * it; a super admin edits it (P8). Never promoted.
 */
object LakeTablePolicy {
    private val mapper = JsonMapper.builder().build()

    fun defaultsJson(properties: LakeProperties): String =
        mapper.writeValueAsString(
            mapOf(
                "retention" to
                    mapOf(
                        "protection_days" to properties.retention.protectionDays,
                        "orphan_grace_hours" to properties.retention.orphanGraceHours,
                    ),
                "maintenance" to
                    mapOf(
                        "window" to properties.maintenance.window,
                        "max_bytes_rewritten_per_run" to properties.maintenance.maxBytesRewrittenPerRun.toBytes(),
                    ),
            ),
        )
}
