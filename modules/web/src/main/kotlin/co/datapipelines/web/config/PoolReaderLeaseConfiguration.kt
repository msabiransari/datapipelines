package co.datapipelines.web.config

import co.datapipelines.datasources.pooling.ManagedGenerationPins
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LeaseOutcome
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.TaskManagementConfigUtils
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource

/** Independent of dp-reaper: a metadata stall cannot delay statement-free retirement. */
@Configuration
class PoolReaderLeaseConfiguration {
    @Bean
    @DependsOn(ReaderLeaseKeeper.SCHEDULER, TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
    fun managedGenerationPins(
        jdbc: JdbcTemplate,
        properties: ObjectProvider<LakeProperties>,
        meters: MeterRegistry,
    ): ReaderLeaseKeeper {
        val acquireMillis =
            (jdbc.dataSource as? HikariDataSource)?.connectionTimeout
                ?: HikariConfig().connectionTimeout
        val bounded =
            JdbcTemplate(ReaderLeaseDataSource(requireNotNull(jdbc.dataSource))).apply {
                queryTimeout =
                    LakeLeases.READER_STATEMENT_TIMEOUT_SECONDS
            }
        val lockSeconds = properties.ifAvailable?.catalog?.lockTimeoutSeconds ?: LakeProperties.DEFAULT_LOCK_TIMEOUT_SECONDS
        val leases = LakeLeases(bounded, lockTimeout = Duration.ofSeconds(lockSeconds))
        return ReaderLeaseKeeper(
            leases,
            properties.ifAvailable?.retention?.protectionDays ?: LakeProperties.DEFAULT_PROTECTION_DAYS,
            acquireMillis,
            meters,
        )
    }

    @Bean(name = [ReaderLeaseKeeper.SCHEDULER])
    fun readerLeaseTaskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            // Continue heartbeats during registry destruction; the explicit dependency graph closes this last.
            setAcceptTasksAfterContextClose(true)
            setWaitForTasksToCompleteOnShutdown(true)
            phase = ScheduledJobsTaskScheduler.SHUTDOWN_PHASE
            setThreadFactory { runnable -> Thread(runnable, "dp-reader-lease").apply { isDaemon = true } }
            setAwaitTerminationMillis(ScheduledJobsTaskScheduler.SHUTDOWN_AWAIT_MILLIS)
        }
}

/** A pin follows physical closure, not retirement, a wrapper timeout or the close-started flag. */
class ReaderLeaseKeeper(
    private val leases: LakeLeases,
    protectionDays: Int,
    acquireTimeoutMillis: Long,
    private val meters: MeterRegistry,
) : ManagedGenerationPins {
    private val window = Duration.ofDays(protectionDays.toLong())

    /**
     * The database starts TTL at statement start. Include the previous successful statement's tail,
     * then a failed round and the following successful round, with both fixed-delay waits.
     */
    val ttl: Duration =
        Duration.ofMillis(
            2 * (CADENCE_MILLIS + acquireTimeoutMillis + LakeLeases.READER_STATEMENT_TIMEOUT_SECONDS * MILLIS_PER_SECOND) +
                LakeLeases.READER_STATEMENT_TIMEOUT_SECONDS * MILLIS_PER_SECOND,
        )
    private val entries = ConcurrentHashMap<UUID, Entry>()

    private class Entry(
        val datasource: String,
        val count: Int,
        val builtAt: Instant,
        val holdsHandles: () -> Boolean,
        val retire: () -> Unit,
    ) {
        @Volatile var renewedAtNanos: Long = System.nanoTime()
        val ready = AtomicBoolean(false)
        val valid = AtomicBoolean(true)
        val overlong = AtomicBoolean(false)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun pin(
        generation: UUID,
        datasource: String,
        locations: Map<String, String>,
        builtAt: Instant,
        holdsHandles: () -> Boolean,
        retire: () -> Unit,
    ): Boolean {
        val entry = Entry(datasource, locations.size, builtAt, holdsHandles, retire)
        // Install BEFORE the first insert so a build taking several rounds never leaves early pins unheartbeated.
        check(entries.putIfAbsent(generation, entry) == null) { "generation already pinned" }
        try {
            locations.forEach { (name, location) ->
                check(
                    leases.acquireReader(
                        "$name@${leases.holderInstance}/$generation",
                        generation,
                        ttl,
                        builtAt.plus(window),
                        location,
                    ) is LeaseOutcome.Acquired,
                )
            }
            val renewedAt = System.nanoTime()
            check((leases.heartbeatReaders(setOf(generation), ttl)[generation] ?: 0) == entry.count) { "reader pins lapsed during build" }
            entry.renewedAtNanos = renewedAt
            entry.ready.set(true)
            return entry.valid.get()
        } catch (failure: Exception) {
            entry.valid.set(false)
            throw failure
        }
    }

    override fun live(): Set<UUID> = entries.filterValues { it.holdsHandles() }.keys

    override fun valid(generation: UUID): Boolean {
        val entry = entries[generation] ?: return false
        return entry.ready.get() && entry.valid.get() &&
            System.nanoTime() - entry.renewedAtNanos < ttl.toNanos() && Instant.now() < entry.builtAt.plus(window)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun unpin(generation: UUID) {
        val entry = entries[generation] ?: return
        if (entry.holdsHandles()) return
        try {
            leases.release(generation)
            entries.remove(generation, entry)
        } catch (failure: Exception) {
            lost(generation, entry, failure.javaClass.simpleName)
        }
    }

    /** One round, one statement for all rows; partial loss identifies the exact affected generation. */
    @Scheduled(fixedDelay = CADENCE_MILLIS, scheduler = SCHEDULER)
    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    fun heartbeat() {
        val started = System.nanoTime()
        val snapshot = entries.toMap()
        try {
            val physical = live()
            val released = snapshot.filterKeys { it !in physical }
            val live = snapshot - released.keys
            val renewed = leases.heartbeatReaders(live.keys, ttl, released.keys)
            released.forEach { (id, entry) -> entries.remove(id, entry) }
            live.forEach { (id, entry) ->
                if (!entry.holdsHandles()) return@forEach
                if ((renewed[id] ?: 0) == entry.count) {
                    entry.renewedAtNanos = started
                } else if (entry.ready.get()) {
                    lost(id, entry, "short_heartbeat")
                }
                if (Duration.between(entry.builtAt, Instant.now()) > window.dividedBy(HOUR_SLICES) &&
                    entry.overlong.compareAndSet(false, true)
                ) {
                    LOG.warn("event=lake.reader_lease_overlong datasource={} generation={}", entry.datasource, id)
                    meters.counter("datapipelines.lake.reader_lease.overlong").increment()
                }
            }
        } catch (failure: Exception) {
            snapshot.forEach { (id, entry) -> lost(id, entry, failure.javaClass.simpleName) }
        } finally {
            if (System.nanoTime() - started > Duration.ofMillis(CADENCE_MILLIS).toNanos()) {
                LOG.warn("event=lake.reader_lease_round_slow")
                meters.counter("datapipelines.lake.reader_lease.round_slow").increment()
            }
        }
    }

    /** Called by registry shutdown BEFORE Spring cancels this singleton's scheduled task. */
    override fun close() {
        var interrupted = Thread.interrupted()
        try {
            while (live().isNotEmpty()) {
                LockSupport.parkNanos(PHYSICAL_CLOSE_POLL_NANOS)
                interrupted = Thread.interrupted() || interrupted
            }
            heartbeat()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun lost(
        id: UUID,
        entry: Entry,
        cause: String,
    ) {
        if (!entry.valid.compareAndSet(true, false)) {
            entry.retire()
            return
        }
        LOG.warn("event=lake.reader_lease_lost datasource={} generation={} cause={}", entry.datasource, id, cause)
        meters.counter("datapipelines.lake.reader_lease.lost").increment()
        entry.retire()
    }

    companion object {
        private const val PHYSICAL_CLOSE_POLL_NANOS = 25_000_000L
        private const val MILLIS_PER_SECOND = 1000L
        const val CADENCE_MILLIS = 5_000L
        const val HOUR_SLICES = 24L
        const val SCHEDULER = "readerLeaseTaskScheduler"
        private val LOG = LoggerFactory.getLogger(ReaderLeaseKeeper::class.java)
    }
}

/**
 * A paused metadata server cannot acknowledge a JDBC cancel. Bound the reader keeper's socket read too.
 * Hikari tracks setNetworkTimeout on its proxy and restores the pool's timeout on return; no shared setting changes.
 */
internal class ReaderLeaseDataSource(
    source: DataSource,
) : DelegatingDataSource(source) {
    override fun getConnection(): Connection = bound(super.getConnection())

    override fun getConnection(
        username: String,
        password: String,
    ): Connection = bound(super.getConnection(username, password))

    private fun bound(connection: Connection): Connection {
        try {
            connection.setNetworkTimeout(Executor { it.run() }, LakeLeases.READER_STATEMENT_TIMEOUT_SECONDS * MILLIS_PER_SECOND)
            return connection
        } catch (failure: java.sql.SQLException) {
            connection.close()
            throw failure
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000
    }
}
