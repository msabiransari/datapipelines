package co.datapipelines.lake.profiles

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** Profile documents for tests: the built-in `production` values (V60) and variants of them. */
internal object LakeProfileFixtures {
    private val mapper = JsonMapper.builder().build()

    /** V60's `production` document as written by the migration (P1 asserts the stored row parses to the same values). */
    const val BUILT_IN =
        """{"maintenance": {"window": "02:00-05:00", "max_bytes_rewritten_per_run": 53687091200,
            "target_file_bytes": 67108864, "compact_min_small_files": 5, "compact_small_file_ratio": 0.75,
            "rewrite_manifests_min_count": 100, "cleanup_interval_hours": 24},
          "write": {"row_group_bytes": 1048576},
          "retention": {"keep": [], "keep_time_zone": "UTC"},
          "reporting": {"lateness_sample": 20, "lateness_overdue_factor": 2, "lateness_min_deliveries": 5}}"""

    fun builtIn(): LakeProfile = LakeProfileDocument.parse(BUILT_IN)

    /** [BUILT_IN] with each `"group.key"` of [changes] replaced by its JSON text (an absent value removes the key). */
    fun document(vararg changes: Pair<String, String?>): String {
        val root = mapper.readTree(BUILT_IN) as ObjectNode
        changes.forEach { (path, json) ->
            val (group, key) = path.split('.', limit = 2).let { it[0] to it.getOrNull(1) }
            if (key == null) {
                if (json == null) root.remove(group) else root.set(group, mapper.readTree(json))
            } else {
                val node = root.get(group) as? ObjectNode ?: root.putObject(group)
                if (json == null) node.remove(key) else node.set(key, mapper.readTree(json))
            }
        }
        return mapper.writeValueAsString(root)
    }

    /** A document with the two writer sizes and a cap of twice the target (the smallest D2 admits). */
    fun sizes(
        targetFileBytes: Long,
        rowGroupBytes: Long,
    ): String =
        document(
            "maintenance.target_file_bytes" to "$targetFileBytes",
            "maintenance.max_bytes_rewritten_per_run" to "${2 * targetFileBytes}",
            "write.row_group_bytes" to "$rowGroupBytes",
        )
}
