package co.datapipelines.lake.maintenance.plan

import org.apache.iceberg.io.FileIO
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The planner's database over the module's Postgres, with one scripted fault: the next `timeout` error stamp of the
 * armed table throws [failure] (a transient database failure at the stamp, the verdict's R1). Every other statement
 * reaches Postgres unchanged.
 */
internal class StampFailingJdbc(
    delegate: NamedParameterJdbcTemplate,
) : NamedParameterJdbcTemplate(delegate.jdbcTemplate) {
    @Volatile
    private var armed: UUID? = null
    val failure = TransientDataAccessResourceException("scripted error-stamp failure")

    fun failNextTimeoutStamp(table: UUID) {
        armed = table
    }

    override fun update(
        sql: String,
        paramMap: Map<String, *>,
    ): Int {
        // by value: the planner's table id is read back from the database, never the armed instance
        val stamp = sql.startsWith(ERROR_STAMP) && paramMap["code"] == PlanErrors.TIMEOUT
        if (stamp && armed != null && paramMap["table"] == armed) {
            armed = null
            throw failure
        }
        return super.update(sql, paramMap)
    }

    private companion object {
        const val ERROR_STAMP = "UPDATE lake_table_measurements SET error_code"
    }
}

/**
 * A reader client factory with two scripted construction faults (the verdict's R2); otherwise it builds [build]'s
 * client.
 *
 * - [failNow]: the next construction throws [ClientBuildFailure] at once;
 * - [failLate]: the next construction blocks until its [LateBuild.release], IGNORING interrupts (a client whose build
 *   never answers), then throws.
 */
internal class ScriptedClientFactory(
    private val build: () -> FileIO,
) : () -> FileIO {
    @Volatile
    private var immediate = false
    private val late = AtomicReference<LateBuild?>()

    class ClientBuildFailure : IllegalStateException("scripted client construction failure")

    class LateBuild {
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        /** The reader thread blocked in this construction, set before [entered] opens. */
        @Volatile
        var thread: Thread? = null

        fun release() = released.countDown()

        internal fun block() {
            thread = Thread.currentThread()
            entered.countDown()
            while (true) {
                try {
                    if (released.await(1, TimeUnit.HOURS)) return
                } catch (_: InterruptedException) {
                    // ignored on purpose: a stuck construction does not answer an interrupt
                }
            }
        }
    }

    fun failNow() {
        immediate = true
    }

    fun failLate(): LateBuild = LateBuild().also { late.set(it) }

    override fun invoke(): FileIO {
        late.getAndSet(null)?.let { construction ->
            construction.block()
            throw ClientBuildFailure()
        }
        if (immediate) {
            immediate = false
            throw ClientBuildFailure()
        }
        return build()
    }
}
