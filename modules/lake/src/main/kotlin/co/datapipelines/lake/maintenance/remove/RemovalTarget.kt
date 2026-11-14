package co.datapipelines.lake.maintenance.remove

import co.datapipelines.lake.LakeNames
import co.datapipelines.lake.LakeProperties
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * The table a pass works on, resolved from DP's own rows — never from a request: [location] is `<storage root>/<namespace>/<id>`
 * (call 1, V54's comment "Its id is the table's location segment"), and [keyPrefix] is that location's object-key prefix
 * with its trailing `/`, the only prefix a pass lists and the only one it deletes under.
 */
data class RemovalTarget(
    val tableId: UUID,
    val namespace: String,
    val name: String,
    val status: String,
    val bucket: String,
    val location: String,
    val keyPrefix: String,
) {
    /** The Iceberg/lease name `<namespace>.<table>`. */
    val leaseName: String get() = LakeNames.tableLeaseName(namespace, name)

    /** The key of the clock marker a pass of [runId] writes (`dp/clock/<run-id>`, record §8a (iii)). */
    fun markerKey(runId: UUID): String = "$keyPrefix$CLOCK_DIR$runId"

    /** [key] relative to the table location, for the `dp/` rules and for a log line that must not carry the full key. */
    fun relative(key: String): String = key.removePrefix(keyPrefix)

    /** The object key of [location] when it names an object of this table's bucket (`s3://`, `s3a://`, `s3n://`), else null. */
    fun keyOf(location: String): String? {
        val scheme = SCHEMES.firstOrNull { location.startsWith(it) } ?: return null
        val rest = location.removePrefix(scheme)
        val slash = rest.indexOf('/')
        if (slash <= 0 || rest.substring(0, slash) != bucket) return null
        return rest.substring(slash + 1)
    }

    companion object {
        /** Everything DP writes beside Iceberg's files lives under `<location>/dp/` (§9). */
        const val DP_DIR = "dp/"
        const val RECIPES_DIR = "dp/recipes/"
        const val CLOCK_DIR = "dp/clock/"
        private val SCHEMES = listOf("s3://", "s3a://", "s3n://")
    }
}

/** Reads a managed table's identity and status from `lake_tables_managed` and `lake_namespaces`; one parameterised statement. */
class RemovalTables(
    private val jdbc: JdbcTemplate,
    private val storage: LakeProperties.Storage,
) {
    /** The target for [tableId], or null when no managed table has that id. */
    fun resolve(tableId: UUID): RemovalTarget? =
        jdbc
            .query(
                "SELECT n.name AS namespace, t.name AS table_name, t.status FROM lake_tables_managed t" +
                    " JOIN lake_namespaces n ON n.id = t.namespace_id WHERE t.id = ?",
                { rs, _ -> Triple(rs.getString("namespace"), rs.getString("table_name"), rs.getString("status")) },
                tableId,
            ).singleOrNull()
            ?.let { (namespace, name, status) ->
                // Both are storage path segments; the V54 checks enforce the grammar, this keeps a bad row from widening the prefix.
                LakeNames.requireName(namespace)
                LakeNames.requireName(name)
                require(storage.bucket.isNotBlank()) { "the lake bucket is not configured" }
                RemovalTarget(
                    tableId = tableId,
                    namespace = namespace,
                    name = name,
                    status = status,
                    bucket = storage.bucket,
                    location = "${storage.root}/$namespace/$tableId",
                    keyPrefix = "${storage.normalisedPrefix}$namespace/$tableId/",
                )
            }
}
