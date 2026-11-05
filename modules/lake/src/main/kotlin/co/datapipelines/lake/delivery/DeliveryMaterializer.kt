package co.datapipelines.lake.delivery

import java.sql.Connection
import java.sql.SQLTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Materializes the trusted manifest on DP's root connection before its configuration is locked.
 * The caller loads the configured storage extensions and writer secret first. Author SQL receives
 * only the resulting tables, never this unrestricted connection or a path-bearing query.
 * Exceptions are sanitized by the staging preparation's trusted-initializer boundary.
 */
class DeliveryMaterializer(
    private val bucket: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Every object path and manifest value is bound, including quotes and other sender-chosen characters. */
    fun materialize(
        connection: Connection,
        manifest: DeliveryManifest,
        deadline: Instant,
    ) {
        connection.createStatement().use { statement ->
            statement.queryTimeout = remainingSeconds(deadline)
            // Bound strings still glob by default. This setting makes listed keys literal, including '?' and '['.
            statement.execute("SET s3_url_compatibility_mode = true")
        }
        val function =
            when (manifest.format) {
                DeliveryFormat.Parquet -> "read_parquet"
                DeliveryFormat.Csv -> "read_csv"
            }
        val paths = connection.createArrayOf("VARCHAR", manifest.files.map { "s3://$bucket/${it.key}" }.toTypedArray())
        try {
            connection.prepareStatement("CREATE TABLE delivery AS SELECT * FROM $function(?)").use { statement ->
                statement.queryTimeout = remainingSeconds(deadline)
                statement.setArray(1, paths)
                statement.execute()
            }
        } finally {
            paths.free()
        }
        connection.createStatement().use { statement ->
            statement.queryTimeout = remainingSeconds(deadline)
            statement.execute("CREATE TABLE delivery_files (key VARCHAR, size BIGINT, etag VARCHAR)")
        }
        connection.prepareStatement("INSERT INTO delivery_files VALUES (?, ?, ?)").use { statement ->
            manifest.files.forEach { file ->
                statement.queryTimeout = remainingSeconds(deadline)
                statement.setString(1, file.key)
                statement.setLong(2, file.size)
                statement.setString(ETAG_COLUMN, file.etag)
                statement.executeUpdate()
            }
        }
    }

    private fun remainingSeconds(deadline: Instant): Int {
        val remaining = Duration.between(clock.instant(), deadline)
        if (remaining.isNegative || remaining.isZero) throw SQLTimeoutException("Delivery staging deadline passed.")
        return (remaining.seconds + if (remaining.nano == 0) 0 else 1).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        const val ETAG_COLUMN = 3
    }
}
