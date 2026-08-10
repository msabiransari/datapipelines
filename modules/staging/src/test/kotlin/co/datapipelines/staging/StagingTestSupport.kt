package co.datapipelines.staging

import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.util.UUID

/*
 * Shared free functions for staging tests. The SourceDb fake lives in its own file; this file
 * deliberately holds only top-level functions so there is no single top-level class to name it
 * after.
 */

/**
 * Runs [read] against the staging connection through the only route there is —
 * [Staging.withConnection], which holds the instance's mutex for the block (§9.2). Tests
 * inspect staged state exactly the way a SQL node would; there is no unguarded property.
 *
 * Blocking, so assertions read naturally outside a coroutine. Never call it from inside a
 * `withConnection` block: the mutex is not reentrant.
 */
internal fun <T> Staging.readFromStaging(read: (Connection) -> T): T = runBlocking { withConnection { read(it) } }

/** The JDBC URL a staging instance for [executionId] uses, for lifecycle assertions. */
internal fun stagingUrl(
    executionId: UUID,
    props: H2StagingProperties,
): String = "jdbc:h2:mem:exec_$executionId;MODE=${props.mode}"

/** Counts base tables in the `PUBLIC` schema of [connection] — the object catalog probe. */
internal fun tableCount(connection: Connection): Int =
    connection.createStatement().use { st ->
        st
            .executeQuery(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'",
            ).use { rs ->
                rs.next()
                rs.getInt(1)
            }
    }

/**
 * A `max_memory_mb` budget anchored to the CURRENT measured footprint, plus [headroomMb].
 *
 * §8.2's reading is the JVM's whole used heap, not this database's allocation — so a fixed small
 * budget (the obvious `maxMemoryMb = 1`) sits *below* the process baseline and fails the very
 * first staging call, before the test has staged anything. Such a test passes for the wrong
 * reason and can never distinguish "the data blew the budget" from "the budget was never
 * reachable". Anchoring to a live reading keeps the assertion about the data the test stages.
 *
 * Derived independently of the production reading (deliberately: a test that called the
 * implementation's own helper could not catch that helper going wrong).
 */
internal fun budgetMbAboveBaseline(headroomMb: Long): Long {
    @Suppress("ExplicitGarbageCollectionCall")
    System.gc()
    val runtime = Runtime.getRuntime()
    return (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L) + headroomMb
}

/** Reads a single-column `COUNT(*)`-style scalar long from [sql] against [connection]. */
internal fun scalarLong(
    connection: Connection,
    sql: String,
): Long =
    connection.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }
