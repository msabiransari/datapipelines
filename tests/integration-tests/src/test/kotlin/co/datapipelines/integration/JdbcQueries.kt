package co.datapipelines.integration

import java.sql.Connection

/** The first column of the first row of [sql], or null when it returns no row (the #1035 scenario tests' one scalar read). */
internal fun Connection.queryString(sql: String): String? =
    createStatement().use { statement -> statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null } }

/** Runs [sql] for its effect. */
internal fun Connection.run(sql: String) {
    createStatement().use { it.execute(sql) }
}
