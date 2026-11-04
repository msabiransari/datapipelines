package co.datapipelines.datasources

import java.util.Properties

/**
 * The bundled DuckDB driver's version as the build RESOLVED it (#781): `generateDuckDbVersion` in this
 * module's build writes [RESOURCE] from the runtime graph, so a fact the product states about the engine
 * (the site's Iceberg sentence) renders from what the jar ships, never from a typed literal. The catalog's
 * `duckdb-jdbc` is the one hand-edited declaration; this is its resolution.
 *
 * [jdbc] is the driver's four-part version (major.minor.patch.build); [core] is the DuckDB core it bundles — the first
 * three components, the version `PRAGMA version` reports with a `v` prefix and the one the Docker image's
 * extension directory is keyed by.
 */
data class DuckDbVersion(
    val jdbc: String,
) {
    val core: String = jdbc.split('.').take(CORE_COMPONENTS).joinToString(".")

    init {
        require(jdbc.matches(SHAPE)) { "Invalid duckdb_jdbc version in $RESOURCE: '$jdbc'" }
    }

    companion object {
        /** The build-generated resource, beside this class in the datasources jar. */
        const val RESOURCE = "co/datapipelines/datasources/duckdb-version.properties"
        private const val CORE_COMPONENTS = 3
        private val SHAPE = Regex("[0-9]+(?:\\.[0-9]+){2,}")

        /** Read once, on first use; a jar built without the resource fails here, loudly, not as a blank on a page. */
        val CURRENT: DuckDbVersion by lazy { load(DuckDbVersion::class.java.classLoader) }

        /** Reads [RESOURCE] from [loader]; absent, unreadable or malformed fails with the resource named. */
        fun load(loader: ClassLoader): DuckDbVersion {
            val properties =
                checkNotNull(loader.getResourceAsStream(RESOURCE)) {
                    "$RESOURCE is missing from the class path — the datasources jar was built without generateDuckDbVersion"
                }.use { stream -> Properties().apply { load(stream) } }
            val jdbc = checkNotNull(properties.getProperty("jdbc")) { "$RESOURCE carries no 'jdbc' key" }
            return DuckDbVersion(jdbc.trim())
        }
    }
}
