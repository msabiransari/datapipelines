package co.datapipelines.lake

import org.apache.iceberg.io.FileIO
import org.apache.iceberg.jdbc.JdbcCatalog
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * #878's OWN metadata Postgres, migrated with the shipped migrations, that a case may `docker pause`: the shared
 * [LakeTestDb] is never paused (a paused shared server stalls every later suite). Every pause is undone in the caller's
 * `finally` through [unpause].
 */
internal class PausedCatalogServer : AutoCloseable {
    private val password = HexFormat.of().formatHex(ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes))
    private val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16-alpine").withDatabaseName(DATABASE).withUsername("dp").withPassword(password)
    private val docker = DockerClientFactory.instance().client()

    @Volatile var paused = false
        private set

    val jdbc: JdbcTemplate
    val leases: LakeLeases

    init {
        postgres.start()
        jdbc = JdbcTemplate(DriverManagerDataSource(url("sslmode=disable"), postgres.username, password))
        LakeTestDb.migrations().forEach { jdbc.execute(it.readText()) }
        leases = LakeLeases(jdbc, holderInstance = "lake-878")
    }

    /** The catalog URL on this server; [query] is the test's choice of driver parameters, never a secret. */
    fun url(query: String = ""): String =
        "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$DATABASE" +
            if (query.isEmpty()) "" else "?$query"

    fun database(query: String = "sslmode=disable"): CatalogDatabase = CatalogDatabase(url(query), postgres.username, password)

    /** An autocommit connection of its own, without the catalog's settings — the lock holder and the watcher. */
    fun connect(): Connection = DriverManager.getConnection(url("sslmode=disable"), postgres.username, password)

    fun lease(
        kind: LeaseKind,
        name: String,
        operationId: UUID = UUID.randomUUID(),
    ): LeaseHandle {
        val ttl = Duration.ofMinutes(5)
        val outcome = leases.acquire(kind, name, operationId, ttl, leases.catalogNowPlus(ttl.multipliedBy(2)), "test-878")
        return (outcome as? LeaseOutcome.Acquired)?.lease ?: error("lease $kind $name not acquired: $outcome")
    }

    fun pause() {
        docker.pauseContainerCmd(postgres.containerId).exec()
        paused = true
    }

    fun unpause() {
        if (paused) docker.unpauseContainerCmd(postgres.containerId).exec()
        paused = false
    }

    override fun close() {
        runCatching { unpause() }
        postgres.stop()
    }

    private companion object {
        const val DATABASE = "datapipelines_878"
        const val SECRET_BYTES = 16
    }
}

/**
 * A session opened exactly as `LakeCatalogs.open` opens one — the same [FencedJdbcClientPool] arguments, the same
 * initialize-and-classify — except that every connection goes through a counting connector, so a case can count the
 * pool's `newClient` calls (opened = connect calls; returned = connections that came back from the driver).
 */
internal class CountedCatalogs(
    private val properties: LakeProperties,
    private val database: CatalogDatabase,
    private val fileIo: (Map<String, String>) -> FileIO,
) {
    val connectCalls = AtomicInteger()
    val connectionsReturned = AtomicInteger()

    private val connector =
        FencedJdbcClientPool.CatalogConnector { url, props ->
            connectCalls.incrementAndGet()
            FencedJdbcClientPool.CatalogConnector.DRIVER_MANAGER
                .connect(url, props)
                .also { connectionsReturned.incrementAndGet() }
        }

    fun open(lease: LeaseHandle): LakeCatalogSession {
        val catalog =
            JdbcCatalog(
                { props -> DeferringFileIO(fileIo(props)) },
                { _ ->
                    FencedJdbcClientPool(
                        database.url,
                        database.connectionProperties(),
                        lease,
                        properties.catalog.lockTimeoutSeconds,
                        properties.catalog.statementTimeoutSeconds,
                        FencedJdbcClientPool.DEFAULT_POOL_SIZE,
                        connector,
                    )
                },
                false,
            )
        try {
            catalog.initialize(properties.name, LakeCatalogs(properties, database, fileIo).catalogProperties())
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            runCatching { catalog.close() }
            throw LakeCatalogException.classify(e) ?: e
        }
        return LakeCatalogSession(lease, catalog, properties.storage.root)
    }
}

/** How an operation run on its own thread ended: inside the outer deadline (with its failure), or still blocked. */
internal class Ended(
    val elapsedMillis: Long,
    val failure: Throwable?,
    val stillBlocked: Boolean,
)

/** One operation on its own daemon thread; [startedAt] is read on that thread just before the operation runs. */
internal class Running<T>(
    operation: () -> T,
) {
    private val startedNanos = AtomicLong(0)
    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "lake-878-operation").apply { isDaemon = true } }
    private val future: Future<T> =
        executor.submit(
            Callable {
                startedNanos.set(System.nanoTime())
                operation()
            },
        )

    /** Waits until [outerMillis] after the operation began; a red run reports `stillBlocked` instead of hanging. */
    fun await(outerMillis: Long): Ended {
        while (startedNanos.get() == 0L) Thread.sleep(1)
        val left = outerMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos.get())
        return try {
            future.get(left.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            Ended(elapsed(), null, stillBlocked = false)
        } catch (e: ExecutionException) {
            Ended(elapsed(), e.cause, stillBlocked = false)
        } catch (_: TimeoutException) {
            Ended(elapsed(), null, stillBlocked = true)
        }
    }

    /** After the environment is restored: lets a blocked operation finish (or fail) so no thread outlives its case. */
    fun drain(): Any? {
        val result = runCatching { future.get(DRAIN_SECONDS, TimeUnit.SECONDS) }
        executor.shutdownNow()
        return result.getOrNull()
    }

    private fun elapsed(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos.get())

    private companion object {
        const val DRAIN_SECONDS = 60L
    }
}
