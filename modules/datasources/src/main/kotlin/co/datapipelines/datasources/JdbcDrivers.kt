package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect

/**
 * JDBC driver-class lookup and classpath-availability check (datasources.md §10.3).
 *
 * Nothing in this module compiles against a driver: the bundled drivers are `runtimeOnly`
 * and the optional ones (`ORACLE`, `MYSQL`) are present only when built with `-Poracle` /
 * `-Pmysql` (module-structure §5.4.1). So a driver is referenced by **class name** and
 * resolved reflectively — [isAvailable] answers "is the JAR on the classpath?" without a
 * compile-time dependency, and a save is rejected with
 * [DatasourceErrorCodes.DRIVER_NOT_LOADED] when it is not.
 */
object JdbcDrivers {
    private val DRIVERS: Map<Dialect, String> =
        mapOf(
            Dialect.POSTGRES to "org.postgresql.Driver",
            Dialect.ORACLE to "oracle.jdbc.OracleDriver",
            Dialect.MSSQL to "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            Dialect.MYSQL to "com.mysql.cj.jdbc.Driver",
            Dialect.H2 to "org.h2.Driver",
            Dialect.DUCKDB to "org.duckdb.DuckDBDriver",
            Dialect.SQLITE to "org.sqlite.JDBC",
        )

    /** The fully-qualified driver class name for [dialect]. Total over the enum. */
    fun classNameFor(dialect: Dialect): String = DRIVERS[dialect] ?: error("No JDBC driver mapped for dialect $dialect")

    /**
     * Whether the driver class for [dialect] is loadable from the current classpath.
     *
     * `initialize = false`: existence is all we ask — forcing static initialization here would
     * make availability depend on a driver's own class-init side effects.
     */
    fun isAvailable(dialect: Dialect): Boolean =
        try {
            Class.forName(classNameFor(dialect), false, JdbcDrivers::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
}
