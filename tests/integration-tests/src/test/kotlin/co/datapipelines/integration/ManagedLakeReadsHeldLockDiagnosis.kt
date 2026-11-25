package co.datapipelines.integration

import com.sun.management.GarbageCollectionNotificationInfo
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.support.ScheduledTaskObservationContext
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.openmbean.CompositeData

/** Retains bounded, safe observations independently of the waiter predicate; no SQL or exception messages escape. */
internal class ManagedLakeReadsHeldLockDiagnosis(
    private val application: String,
    private val threadId: Long,
    context: ApplicationContext? = null,
    private val ticks: (() -> Int)? = null,
) : AutoCloseable {
    private val retained = ArrayDeque<String>()
    private val pauses = ConcurrentLinkedQueue<String>()
    private val invocations = ConcurrentLinkedQueue<String>()
    private val running = AtomicReference<ScheduledTaskObservationContext?>()
    private val startedAt = AtomicReference<Long?>()
    private val runtime = ManagementFactory.getRuntimeMXBean()
    private val threads = ManagementFactory.getThreadMXBean()
    private val collectors = ManagementFactory.getGarbageCollectorMXBeans()
    private val emitters = collectors.filterIsInstance<NotificationEmitter>()
    private val listener =
        NotificationListener { notification, _ ->
            if (notification.type == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) {
                val gc = GarbageCollectionNotificationInfo.from(notification.userData as CompositeData).gcInfo
                pauses.add("gc_uptime_ms=${gc.startTime}..${gc.endTime}")
                while (pauses.size > MAX_SAMPLES) pauses.poll()
            }
        }
    private var window = clock()
    private var baseline = ticks?.invoke()
    private var pressure = psi()
    private var gcBaseline = gcTotals()
    private var activitySize = "unknown"

    init {
        emitters.forEach { it.addNotificationListener(listener, null, null) }
        context?.getBean(ObservationRegistry::class.java)?.observationConfig()?.observationHandler(
            object : ObservationHandler<ScheduledTaskObservationContext> {
                override fun supportsContext(context: Observation.Context) = context is ScheduledTaskObservationContext

                override fun onStart(context: ScheduledTaskObservationContext) {
                    if (Thread.currentThread().threadId() != threadId) return
                    startedAt.set(System.nanoTime())
                    running.set(context)
                    invocationEvent("start", context)
                }

                override fun onStop(context: ScheduledTaskObservationContext) {
                    if (running.compareAndSet(context, null)) invocationEvent("stop", context)
                }
            },
        )
        retain("before_locks ${clock()} ticks=${ticks?.invoke() ?: "not_applicable"} thread_id=$threadId")
        println(
            "HELD_LOCK_DIAG_BEGIN app=$application pid=${ProcessHandle.current().pid()} " +
                "worker=${System.getProperty("org.gradle.test.worker")} ${clock()}",
        )
    }

    fun beginWindow(connection: Connection) {
        window = clock()
        baseline = ticks?.invoke()
        pressure = psi()
        gcBaseline = gcTotals()
        activitySize =
            safe {
                connection.createStatement().use { statement ->
                    statement.queryTimeout = QUERY_SECONDS
                    statement.executeQuery("SHOW track_activity_query_size").use { rows ->
                        rows.next()
                        rows.getString(1)
                    }
                }
            }
        retain("lock_window_start $window ticks=$baseline psi=$pressure gc=$gcBaseline")
    }

    fun beforeLocks() = retain("before_locks ${clock()} ticks=${ticks?.invoke() ?: "not_applicable"} thread_id=$threadId")

    fun await(
        connection: Connection,
        budget: Duration,
        what: String,
        observerText: String? = null,
        condition: (() -> Boolean)? = null,
    ) {
        val deadline = System.nanoTime() + budget.toNanos()
        do {
            val matched = sample(connection, observerText)
            if (condition?.invoke() ?: matched) return
            if (System.nanoTime() >= deadline) break
            Thread.sleep(POLL_MILLIS)
        } while (true)
        throw AssertionError("$what: not observed within ${budget.toMillis()} ms\n${dump()}")
    }

    /** The classification and the tested LIKE are evaluated over the very same activity rows. */
    fun sample(
        connection: Connection,
        observerText: String? = null,
    ): Boolean {
        val now = clock()
        var observed = false
        val activity =
            query(connection, ACTIVITY, bind = {
                it.setString(1, observerText)
                it.setString(2, application)
            }) { rows ->
                val query = rows.getString("query").orEmpty()
                val kind = classify(query)
                val matched = rows.getBoolean("observed")
                val state = rows.getString("state")
                val wait = "${rows.getString("wait_event_type")}/${rows.getString("wait_event")}"
                observed = observed || matched
                "pid=${rows.getInt("pid")} app=$application kind=$kind state=$state wait=$wait " +
                    "observed=$matched query_chars=${query.length}"
            }
        val locks =
            query(connection, LOCKS, bind = { it.setString(1, application) }) { rows ->
                "pid=${rows.getInt(1)} type=${rows.getString(2)} " +
                    "relation=${rows.getString(3)} mode=${rows.getString(4)} granted=${rows.getBoolean(5)}"
            }
        val stack = threads.getThreadInfo(threadId, Int.MAX_VALUE)
        val invocation = running.get()?.let { System.identityHashCode(it).toString() } ?: "unknown"
        retain(
            "sample $now ticks=${ticks?.invoke() ?: "not_applicable"} ticks_started_since_locks=${startsSinceBaseline()} " +
                "observer_configured=${observerText != null} " +
                "thread_id=$threadId thread_state=${stack?.threadState} invocation=$invocation " +
                "invocation_start_nano=${startedAt.get()}\n" +
                "activity=$activity\nlocks=$locks\nstack=${stack?.stackTrace?.joinToString("\n") ?: "unknown"}",
        )
        if (observed) println("HELD_LOCK_OBSERVED app=$application $now activity=$activity locks=$locks")
        return observed && !activity.startsWith("unknown(")
    }

    private fun classify(query: String): String =
        when {
            "FROM lake_tables t" in query && "t.namespace" in query -> "catalog_read"
            "INSERT INTO datasource_workspaces" in query && "FROM workspaces w" in query -> "repair_insert"
            "SELECT id FROM lake_tables_managed" in query -> "settlement_page"
            "pg_advisory" in query -> "advisory"
            else -> "other_or_truncated"
        }

    private fun query(
        connection: Connection,
        sql: String,
        bind: (PreparedStatement) -> Unit,
        row: (ResultSet) -> String,
    ): String =
        safe {
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = QUERY_SECONDS
                bind(statement)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) row(rows) else null }.joinToString("; ")
                }
            }
        }

    fun dump(): String {
        val end = clock()
        val endPressure = psi()
        val deltas =
            (pressure.keys + endPressure.keys).associateWith { key ->
                val startValue = pressure[key]
                val endValue = endPressure[key]
                if (startValue == null || endValue == null) "unknown" else (endValue - startValue).toString()
            }
        val gcEnd = gcTotals()
        val report =
            "HELD_LOCK_DIAG app=$application track_activity_query_size=$activitySize " +
                "start=$window end=$end mono_delta_ms=${(end.nano - window.nano) / NANOS_PER_MS} " +
                "wall_delta_ms=${end.wall - window.wall} uptime_window_ms=${window.uptime}..${end.uptime} " +
                "psi_delta_us=$deltas gc_start=$gcBaseline gc_end=$gcEnd " +
                "gc_notifications=${if (emitters.isEmpty()) "unknown" else pauses.toList()}\n" +
                "invocation_events=$invocations\n" + retained.joinToString("\n")
        println(report)
        return report
    }

    override fun close() {
        emitters.forEach { it.removeNotificationListener(listener) }
        println("HELD_LOCK_DIAG_END app=$application ${clock()}")
    }

    private fun retain(value: String) {
        if (retained.size == MAX_SAMPLES) retained.removeAt(1) // Preserve the pre-lock identity as well as the latest samples.
        retained.addLast(value)
    }

    private fun invocationEvent(
        event: String,
        context: ScheduledTaskObservationContext,
    ) {
        invocations.add("$event ${clock()} id=${System.identityHashCode(context)} job=${context.targetClass.name}.${context.method.name}")
        while (invocations.size > MAX_SAMPLES) invocations.poll()
    }

    private fun clock() = Clock(System.nanoTime(), System.currentTimeMillis(), runtime.uptime)

    private fun startsSinceBaseline(): String = baseline?.let { start -> ticks?.invoke()?.minus(start)?.toString() } ?: "not_applicable"

    private fun gcTotals(): List<String> {
        fun known(value: Long) = value.takeIf { it >= 0 }?.toString() ?: "unknown"
        return collectors.map { "count=${known(it.collectionCount)},time_ms=${known(it.collectionTime)}" }
    }

    private fun psi(): Map<String, Long?> =
        buildMap {
            listOf("cpu", "memory", "io").forEach { resource ->
                val lines = runCatching { Files.readAllLines(Path.of("/proc/pressure/$resource")) }.getOrNull()
                listOf("some", "full").forEach { category ->
                    put(
                        "$resource.$category",
                        lines
                            ?.firstOrNull { it.startsWith("$category ") }
                            ?.split(' ')
                            ?.firstOrNull { it.startsWith("total=") }
                            ?.substringAfter('=')
                            ?.toLongOrNull(),
                    )
                }
            }
        }

    @Suppress("TooGenericExceptionCaught") // Diagnostic failure is explicitly unknown and cannot replace the original assertion.
    private fun safe(action: () -> String): String =
        try {
            action()
        } catch (failure: Exception) {
            "unknown(${failure.javaClass.simpleName})"
        }

    private data class Clock(
        val nano: Long,
        val wall: Long,
        val uptime: Long,
    )

    companion object {
        private const val MAX_SAMPLES = 128
        private const val QUERY_SECONDS = 2
        private const val POLL_MILLIS = 500L
        private const val NANOS_PER_MS = 1_000_000L
        private val ACTIVITY =
            """
            SELECT pid, state, wait_event_type, wait_event, query,
                   (wait_event_type = 'Lock' AND query LIKE ?) AS observed
            FROM pg_stat_activity WHERE application_name = ?
            """.trimIndent()
        private val LOCKS =
            """
            SELECT l.pid, l.locktype,
                   CASE WHEN c.relname IN ('lake_tables_managed', 'datasource_workspaces') THEN c.relname ELSE 'other' END,
                   l.mode, l.granted
            FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
            LEFT JOIN pg_class c ON c.oid = l.relation
            WHERE l.locktype IN ('relation', 'advisory') AND
                  (a.application_name = ? OR c.relname IN ('lake_tables_managed', 'datasource_workspaces')) AND
                  (l.database IS NULL OR l.database = (SELECT oid FROM pg_database WHERE datname = current_database()))
            """.trimIndent()

        fun identify(context: ApplicationContext): Long =
            context
                .getBean("taskScheduler", ThreadPoolTaskScheduler::class.java)
                .scheduledThreadPoolExecutor
                .submit<Long> { Thread.currentThread().threadId() }
                .get(Duration.ofSeconds(IDENTIFY_SECONDS).toMillis(), TimeUnit.MILLISECONDS)

        private const val IDENTIFY_SECONDS = 28L
    }
}
