package co.datapipelines.lake.delivery

import co.datapipelines.pipeline.LakeStatementExecution
import java.sql.Connection
import java.time.Instant

/** The assembling layer configures extensions and writer credentials on DP's unrestricted staging root. */
fun interface LakeStagingStorage {
    fun configure(
        connection: Connection,
        deadline: Instant,
        statements: LakeStatementExecution,
    )
}

/** Local pool retirement and cross-instance notification after a committed projection change. */
fun interface LakeProjectionInvalidation {
    fun changed(datasourceName: String)
}
