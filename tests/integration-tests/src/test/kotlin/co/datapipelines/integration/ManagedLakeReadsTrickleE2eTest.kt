package co.datapipelines.integration

import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.B_MAX_AGE_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.CLEANUP_JOIN_MS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.LAKE
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.LIFECYCLE_TICK_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.MINIO_PORT
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.awaitUntil
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.leaseManaged
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.readerGeneration
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.readerPool
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.retireManagedPool
import co.datapipelines.integration.ManagedLakeReadsFixture.LongRead
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.context.ApplicationContext
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * E1 and E1shutdown (#982 split them out of [ManagedLakeReadsE2eTest], so another test worker can run them beside it): a
 * trickling Parquet GET keeps the generation's reader pins live by the database clock while the real closer cannot finish,
 * once under the registry's close and once under the context's shutdown. Both wait out [ReaderTrickleProxy.WAIT_SECONDS]
 * inside [verifyTrickle], which they share; [Order] keeps E1 before E1shutdown, as before the split.
 *
 * A plain JUnit class (no Spring test context): its [ManagedLakeReadsFixture] owns its whole life — its scratch
 * database `managed_lake_reads_trickle`, its MinIO and OIDC stub, a setup instance with context A's properties, and the
 * reader fixture (`sales.trips` at CURRENT, status active) before the first test.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ManagedLakeReadsTrickleE2eTest {
    /** E.1: the physical query outlives close; database-clock reader rows must outlive it too. */
    @Test
    @Order(19)
    fun `E1 - trickling Parquet GET remains pinned while the real closer cannot finish`() {
        verifyTrickle(shutdown = false)
    }

    @Test
    @Order(23)
    fun `E1shutdown - context close keeps the heartbeat alive until physical readers close`() {
        verifyTrickle(shutdown = true)
    }

    @Suppress("LongMethod", "NestedBlockDepth") // one physical-reader timeline, including context shutdown and the final release oracle
    private fun verifyTrickle(shutdown: Boolean) {
        fixture.ensureReaderFixture()
        ReaderTrickleProxy(fixture.minio.host, fixture.minio.getMappedPort(MINIO_PORT)).use { proxy ->
            val reader =
                fixture.boot(
                    maxAgeSeconds = B_MAX_AGE_SECONDS,
                    tickSeconds = LIFECYCLE_TICK_SECONDS,
                    application = "reader-trickle",
                    extra =
                        listOf(
                            "datapipelines.lake.storage.endpoint" to proxy.endpoint,
                            "datapipelines.datasources.retire-ceiling-seconds" to "1",
                            "datapipelines.datasources.managed-retire-ceiling-seconds" to "1",
                        ),
                )
            val lease = leaseManaged(reader)
            recordReaderInputs(reader, lease)
            val run = LongRead(lease.prepareStatement("SELECT sum(fare) FROM $LAKE.sales.trips"))
            var contextClose: Thread? = null
            try {
                val registry = reader.getBean("datasourceRegistry")
                val generation = readerGeneration(reader)
                val ids = fixture.readerRows().filterKeys { it == generation }
                ids.isNotEmpty() shouldBe true
                val expectedPins = readerViewLocations(reader)
                expectedPins.isNotEmpty() shouldBe true
                withClue("E1 pins match the generation view metadata locations") {
                    readerPinLocations(generation) shouldBe expectedPins
                }
                installHeartbeatWitness()
                proxy.arm()
                run.start()
                proxy.arrived.await(ReaderTrickleProxy.START_SECONDS, TimeUnit.SECONDS) shouldBe true
                retireManagedPool(reader) shouldBe true
                Thread.sleep(2000)
                startRetiredClose(registry, generation)
                if (shutdown) {
                    contextClose =
                        Thread({ reader.close() }, "reader-context-close").apply {
                            isDaemon = true
                            start()
                        }
                }
                val closeStarted = fixture.databaseNow()
                Thread.sleep(ReaderTrickleProxy.WAIT_SECONDS * 1000)
                val stillLive = fixture.readerRows().filterKeys { it in ids }
                println(
                    "READER_TRICKLE shutdown=$shutdown close_started=$closeStarted observed=${fixture.databaseNow()} " +
                        "bytes=${proxy.bytes.get()} " +
                        "rows=$stillLive reader_alive=${run.thread.isAlive}",
                )
                withClue("E1 reader still executing above curl low-speed threshold") { run.thread.isAlive shouldBe true }
                if (shutdown) contextClose?.isAlive shouldBe true
                withClue("E1 every snapshot pin stays live by database clock during blocked close") { stillLive.keys shouldBe ids.keys }
                stillLive.values.all { it } shouldBe true
                readerPinLocations(generation) shouldBe expectedPins
                val physicalCloses = Thread.getAllStackTraces().keys.filter { it.name.startsWith("dp-pool-interrupt-$generation-") }
                withClue("E1 real physical close remains blocked") { physicalCloses.isNotEmpty() shouldBe true }
                val beats = heartbeatWitness(generation, closeStarted)
                println("READER_HEARTBEATS generation=$generation rounds=$beats close_threads=${physicalCloses.map { it.name }}")
                beats shouldBeGreaterThanOrEqual 19
                finishTrickle(proxy, run, physicalCloses, generation)
                contextClose?.join(CLEANUP_JOIN_MS)
                contextClose?.isAlive shouldBe if (shutdown) false else null
            } finally {
                proxy.close()
                run.thread.join(CLEANUP_JOIN_MS)
                runCatching { lease.close() }
                contextClose?.join(CLEANUP_JOIN_MS)
                reader.close()
            }
        }
    }

    private fun finishTrickle(
        proxy: ReaderTrickleProxy,
        run: LongRead,
        closes: List<Thread>,
        generation: UUID,
    ) {
        proxy.close()
        run.thread.join(CLEANUP_JOIN_MS)
        run.thread.isAlive shouldBe false
        closes.forEach { it.join(CLEANUP_JOIN_MS) }
        closes.any { it.isAlive } shouldBe false
        val closedAt = fixture.databaseNow()
        awaitUntil(Instant.now().plusSeconds(10), "reader pins released after physical close") { generation !in fixture.readerRows() }
        val releasedAt = fixture.databaseNow()
        val elapsed = Duration.between(closedAt, releasedAt).toMillis()
        println("READER_RELEASE closed=$closedAt released=$releasedAt elapsed_ms=$elapsed")
        elapsed shouldBeLessThanOrEqual 5000L
    }

    private fun startRetiredClose(
        registry: Any,
        generation: UUID,
    ) {
        registry.javaClass.getMethod("reapRetiredPools").invoke(registry)
        awaitUntil(Instant.now().plusSeconds(ReaderTrickleProxy.START_SECONDS), "physical DuckDB close has begun") {
            Thread.getAllStackTraces().any { (thread, frames) ->
                thread.name.startsWith("dp-pool-interrupt-$generation-") &&
                    frames.any { it.className == "org.duckdb.DuckDBConnection" && it.methodName == "close" }
            }
        }
    }

    private fun recordReaderInputs(
        reader: ApplicationContext,
        lease: Connection,
    ) {
        println("READER_ENGINE database=${lease.metaData.databaseProductVersion} driver=${lease.metaData.driverVersion}")
        val keeper = reader.getBean("managedGenerationPins")
        val metadataAcquire = (reader.getBean("dataSource") as HikariDataSource).connectionTimeout
        val ttl = keeper.javaClass.getMethod("getTtl").invoke(keeper)
        println("READER_LEASE_INPUTS acquire_ms=$metadataAcquire ttl=$ttl")
    }

    private fun readerViewLocations(reader: ApplicationContext): Map<String, String> {
        val generation =
            Class
                .forName(
                    "co.datapipelines.datasources.pooling.ConnectionPool",
                ).getMethod("getManagedGeneration")
                .invoke(readerPool(reader))
        val locations =
            Class
                .forName("co.datapipelines.datasources.pooling.ManagedLakeGeneration")
                .getMethod("getLocations")
                .invoke(requireNotNull(generation)) as Map<*, *>
        return locations.entries
            .mapNotNull { (name, location) ->
                if (location == null) return@mapNotNull null
                require(name is String && location is String)
                name to location
            }.toMap()
    }

    private fun readerPinLocations(generation: UUID): Map<String, String> =
        fixture.metadata { connection ->
            connection
                .prepareStatement(
                    "SELECT split_part(name,'@',1),pinned_metadata_location FROM lake_leases WHERE kind='reader' AND operation_id=?",
                ).use { statement ->
                    statement.setObject(1, generation)
                    statement.executeQuery().use { rows ->
                        buildMap { while (rows.next()) put("$LAKE.${rows.getString(1)}", rows.getString(2)) }
                    }
                }
        }

    /** Independent database witness: only committed UPDATEs count, one transaction per keeper round. */
    private fun installHeartbeatWitness() {
        fixture.metadata { connection ->
            connection.createStatement().use { sql ->
                sql.execute("CREATE TABLE IF NOT EXISTS test_reader_beats (generation uuid, at timestamptz, tx bigint)")
                sql.execute("TRUNCATE test_reader_beats")
                sql.execute(
                    """
                    CREATE OR REPLACE FUNCTION test_reader_beat() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      INSERT INTO test_reader_beats VALUES (NEW.operation_id,clock_timestamp(),txid_current());
                      RETURN NEW;
                    END; $$
                    """.trimIndent(),
                )
                sql.execute("DROP TRIGGER IF EXISTS test_reader_beat ON lake_leases")
                sql.execute(
                    "CREATE TRIGGER test_reader_beat AFTER UPDATE ON lake_leases FOR EACH ROW " +
                        "WHEN (NEW.kind='reader') EXECUTE FUNCTION test_reader_beat()",
                )
            }
        }
    }

    private fun heartbeatWitness(
        generation: UUID,
        since: Instant,
    ): Int =
        fixture.metadata { connection ->
            connection.prepareStatement("SELECT count(DISTINCT tx) FROM test_reader_beats WHERE generation=? AND at>=?").use {
                it.setObject(1, generation)
                it.setObject(2, java.time.OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC))
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    companion object {
        private val fixture = ManagedLakeReadsFixture("managed_lake_reads_trickle", ManagedLakeReadsTrickleE2eTest::class)

        @BeforeAll
        @JvmStatic
        fun startFixture() {
            fixture.start()
        }

        @AfterAll
        @JvmStatic
        fun closeFixture() {
            fixture.close()
        }
    }
}
