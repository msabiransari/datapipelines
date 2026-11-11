package co.datapipelines.browser

import java.sql.DriverManager
import java.util.UUID

/**
 * Plants, in SQL, the state a pre-#786 discard left under a live schedule: the pipeline's only release
 * DISCARDED and its CURRENT pointer NULL. Since #786 B.4 a live schedule protects the version it runs, so
 * the discard verb refuses this (`pipeline.version.pinned`) and a blocked `pointer_null` schedule is
 * legacy data only — which is what the suites that walk the blocked state now seed. Restore then works
 * through the native verb exactly as it did after a real discard.
 */
internal fun plantLegacyDiscardedRelease(pipelineId: String) {
    val id = UUID.fromString(pipelineId)
    DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
        connection.autoCommit = false
        connection
            .prepareStatement(
                "UPDATE pipeline_versions SET status = 'DISCARDED', discarded_at = NOW(), discarded_by = created_by " +
                    "WHERE pipeline_id = ? AND version = 1 AND status = 'RELEASED'",
            ).use { statement ->
                statement.setObject(1, id)
                check(statement.executeUpdate() == 1) { "pipeline $pipelineId has no RELEASED v1 to plant as discarded" }
            }
        connection.prepareStatement("UPDATE pipelines SET current_version = NULL, updated_at = NOW() WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            check(statement.executeUpdate() == 1) { "no pipeline $pipelineId" }
        }
        connection.commit()
    }
}
