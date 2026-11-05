package co.datapipelines.datasources

import java.util.Properties

/**
 * The pinned JDBC drivers' versions as the build RESOLVED them (#826), packaged beside the code that
 * loads them. `generateDuckDbVersion` in this module's build (the name predates the other two rows)
 * writes [RESOURCE] — the same file as [DuckDbVersion] — from the runtime graph, so the version drift
 * guard (`DriverVersionSpecDriftTest`) holds every pinned-driver literal in the tracked tree to what
 * the jars actually carry. The catalog declares `duckdb-jdbc` by hand; the BOM-managed H2 and SQLite
 * JDBC drivers have no catalog row, so this resolution is the guard's authority for them, and
 * `DuckDbVersionTest` proves the DuckDB pair equal.
 *
 * Despite the resource's name it now carries three keys: `jdbc` (DuckDB JDBC), [h2] and
 * [sqliteJdbc] — each required, each shape-checked on load.
 */
data class DriverVersions(
    val jdbc: String,
    val h2: String,
    val sqliteJdbc: String,
) {
    init {
        require(jdbc.matches(SHAPE)) { "Invalid duckdb_jdbc version in $RESOURCE: '$jdbc'" }
        require(h2.matches(SHAPE)) { "Invalid $H2_KEY version in $RESOURCE: '$h2'" }
        require(sqliteJdbc.matches(SHAPE)) { "Invalid sqlite-jdbc version in $RESOURCE: '$sqliteJdbc'" }
    }

    companion object {
        /** The build-generated resource — named for its first key; all three drivers ride in it. */
        const val RESOURCE = DuckDbVersion.RESOURCE

        /** The H2 driver's property key; the BOM resolves `com.h2database:h2`. */
        const val H2_KEY = "h2"

        /** The SQLite JDBC driver's property key; the BOM resolves `org.xerial:sqlite-jdbc`. */
        const val SQLITE_JDBC_KEY = "sqlite_jdbc"

        private val SHAPE = Regex("[0-9]+(?:\\.[0-9]+){2,}")

        /** Read once, on first use; a jar built without the resource fails here, loudly, at the first reader. */
        val CURRENT: DriverVersions by lazy { load(DriverVersions::class.java.classLoader) }

        /** Reads [RESOURCE] from [loader]; absent, unreadable, missing a key or malformed fails naming both. */
        fun load(loader: ClassLoader): DriverVersions {
            val properties =
                checkNotNull(loader.getResourceAsStream(RESOURCE)) {
                    "$RESOURCE is missing from the class path — the datasources jar was built without generateDuckDbVersion"
                }.use { stream -> Properties().apply { load(stream) } }

            fun key(name: String): String = checkNotNull(properties.getProperty(name)) { "$RESOURCE carries no '$name' key" }.trim()
            return DriverVersions(key("jdbc"), key(H2_KEY), key(SQLITE_JDBC_KEY))
        }
    }
}
