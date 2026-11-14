package co.datapipelines.web.config

import co.datapipelines.lake.delivery.LakeStagingStorage
import co.datapipelines.lake.maintenance.compaction.CompactionDeadlinePassed
import co.datapipelines.lake.maintenance.compaction.CompactionScratchExhausted
import co.datapipelines.lake.maintenance.compaction.CompactionScratchPort
import co.datapipelines.lake.maintenance.compaction.CompactionScratchSession
import co.datapipelines.lake.maintenance.compaction.CompactionStatement
import co.datapipelines.persistence.FailureShape
import co.datapipelines.pipeline.LakeStatementExecution
import co.datapipelines.staging.StagingPreparation
import co.datapipelines.staging.StagingScratchLimitException
import co.datapipelines.staging.TrustedScratchDatabase
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * #969 D5 step 5: the compaction part's scratch port over a trusted scratch database (staging.md §8.5). The run's
 * reservation must be the staging factory's [StagingPreparation] (the worker's reserve bean, D5 step 1); anything else
 * is a wiring defect and the part fails. The database is opened trusted with an initializer that configures the lake's
 * object store through [LakeStagingStorage] (extensions and the credential, nothing else) and sets
 * `s3_url_compatibility_mode` as `DeliveryMaterializer` does, so a bound key is read literally. Each statement and the
 * reading of its rows run inside one body, with a `queryTimeout` from the run's deadline.
 *
 * Failures, in this order:
 * 1. **The deadline is decided by [clock], before anything else, never by a message:** when any failure leaves the open,
 *    the execute or the reading of rows and the run's deadline has passed, the answer is [CompactionDeadlinePassed]; the
 *    original goes to the log as its class and SQL state only (`lake.maintenance.compaction_deadline`).
 * 2. Before the deadline, staging's [StagingScratchLimitException], ONLY that one, becomes [CompactionScratchExhausted]
 *    with the grant and the engine's reading.
 * 3. Everything else passes as it is (an interruption of the worker included: 957 D7's).
 */
class TrustedCompactionScratch(
    private val storage: LakeStagingStorage,
    private val clock: Clock,
) : CompactionScratchPort {
    override fun open(
        reservation: AutoCloseable,
        deadline: Instant,
    ): CompactionScratchSession {
        val preparation =
            requireNotNull(reservation as? StagingPreparation) {
                "the maintenance run's reservation is a ${reservation.javaClass.name}, not the staging factory's preparation"
            }
        val database =
            decided(deadline, preparation.reservedBytes) {
                preparation.openTrusted { root -> initialize(root, deadline) }
            }
        return Session(database, deadline)
    }

    private fun initialize(
        root: Connection,
        deadline: Instant,
    ) {
        storage.configure(root, deadline, LakeStatementExecution.DIRECT)
        root.createStatement().use { statement ->
            statement.queryTimeout = remainingSeconds(deadline)
            // Bound strings still glob by default; this makes every listed key literal (DeliveryMaterializer's setting).
            statement.execute("SET s3_url_compatibility_mode = true")
        }
    }

    private inner class Session(
        private val database: TrustedScratchDatabase,
        private val deadline: Instant,
    ) : CompactionScratchSession {
        override fun read(
            statement: CompactionStatement,
            files: List<String>,
            consume: (ResultSet) -> Unit,
        ) = decided(deadline, database.reservedBytes) {
            database.withRoot { connection ->
                val keys = connection.createArrayOf("VARCHAR", files.toTypedArray())
                try {
                    connection.prepareStatement(statement.sql).use { prepared ->
                        prepared.queryTimeout = remainingSeconds(deadline)
                        prepared.setArray(1, keys)
                        prepared.executeQuery().use(consume)
                    }
                } finally {
                    keys.free()
                }
            }
        }

        override fun diskBytes(): Long = database.diskBytes()

        override fun close() = database.close()
    }

    @Suppress("TooGenericExceptionCaught") // D5 step 5: every failure is judged by the clock first, then passed or converted.
    private fun <T> decided(
        deadline: Instant,
        grantBytes: Long,
        step: () -> T,
    ): T =
        try {
            step()
        } catch (failure: StagingScratchLimitException) {
            throw if (passed(deadline)) deadlinePassed(deadline, failure) else CompactionScratchExhausted(grantBytes, failure.engineReading)
        } catch (failure: Exception) {
            throw if (passed(deadline)) deadlinePassed(deadline, failure) else failure
        }

    private fun passed(deadline: Instant): Boolean = !clock.instant().isBefore(deadline)

    /**
     * The clock said the deadline passed: the original failure is logged by its class and SQL state only, never its text.
     * A [CompactionDeadlinePassed] from the consumer is already the answer and passes as it is.
     */
    private fun deadlinePassed(
        deadline: Instant,
        failure: Exception,
    ): CompactionDeadlinePassed {
        if (failure is CompactionDeadlinePassed) return failure
        LOG.warn(
            "event=lake.maintenance.compaction_deadline deadline={} cause={} sql_state={}",
            deadline,
            FailureShape.cause(failure),
            FailureShape.sqlState(failure),
        )
        return CompactionDeadlinePassed()
    }

    /** The statement's timeout: the time left, rounded up to whole seconds; none left → a failure the clock then judges. */
    private fun remainingSeconds(deadline: Instant): Int {
        val remaining = Duration.between(clock.instant(), deadline)
        if (remaining.isNegative || remaining.isZero) throw SQLTimeoutException("The maintenance run's deadline passed.")
        return (remaining.seconds + if (remaining.nano == 0) 0 else 1).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(TrustedCompactionScratch::class.java)
    }
}
