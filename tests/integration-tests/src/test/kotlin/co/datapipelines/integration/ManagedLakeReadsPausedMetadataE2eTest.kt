package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.B_MAX_AGE_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.CLEANUP_JOIN_MS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.H_LONG_QUERY
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.LIFECYCLE_TICK_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.PRODUCT_LOGGERS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.awaitBurning
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.generationBuiltAt
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.leaseManaged
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.linesOf
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.readerGeneration
import co.datapipelines.integration.ManagedLakeReadsFixture.LongRead
import co.datapipelines.logcapture.SnapshotListAppender
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory

/**
 * E3b (#982 split it out of [ManagedLakeReadsE2eTest], so another test worker can run it beside it): a paused metadata
 * server is bounded and lost reader protection is reported — a 40 s pause keeps the reader pinned, a 90 s pause (a full
 * TTL) loses the protection.
 *
 * A plain JUnit class (no Spring test context): its [ManagedLakeReadsFixture] owns its whole life — its scratch
 * database `managed_lake_reads_paused`, its MinIO and OIDC stub, a setup instance with context A's properties, and the
 * reader fixture (`sales.trips` at CURRENT, status active) before the first test.
 *
 * [pausedReader] pauses the shared Postgres container ([SharedE2e.postgres], one per test JVM), not only this class's
 * database. Tests in one fork run one after another, so no other TEST runs during a pause; the application contexts alive
 * in that JVM do see it — this class's setup instance and whatever context Spring's test-context cache still holds from an
 * earlier class. Their background work waits or fails for the length of the pause and recovers after it, as it did beside
 * context A and the instances B and F before the split. [pausedReader]'s own `finally` unpauses; [closeFixture] first
 * unpauses the container if it is still paused, a net under that `finally`.
 */
class ManagedLakeReadsPausedMetadataE2eTest {
    /** E.3b: short and long metadata outages differ in pin survival, while both retire new admission. */
    @Test
    @Order(22)
    fun `E3b - a paused metadata server is bounded and lost reader protection is reported`() {
        pausedReader(40, true)
        pausedReader(90, false)
    }

    private fun pausedReader(
        pauseSeconds: Long,
        remainsPinned: Boolean,
    ) {
        fixture.ensureReaderFixture()
        val reader =
            fixture.boot(
                maxAgeSeconds = B_MAX_AGE_SECONDS,
                tickSeconds = LIFECYCLE_TICK_SECONDS,
                application = "reader-paused-$pauseSeconds",
                extra =
                    listOf(
                        "datapipelines.datasources.retire-ceiling-seconds" to "180",
                        "datapipelines.datasources.managed-retire-ceiling-seconds" to "180",
                    ),
            )
        val lease = leaseManaged(reader)
        val generation = readerGeneration(reader)
        val run = LongRead(lease.prepareStatement(H_LONG_QUERY))
        val logger = LoggerFactory.getLogger(PRODUCT_LOGGERS) as Logger
        val capture = SnapshotListAppender()
        logger.addAppender(capture)
        val docker = DockerClientFactory.instance().client()
        var paused = false
        try {
            run.start()
            awaitBurning(run)
            val before = fixture.databaseNow()
            docker.pauseContainerCmd(SharedE2e.postgres.containerId).exec()
            paused = true
            Thread.sleep(pauseSeconds * 1000)
            val lost = linesOf(capture, "event=lake.reader_lease_lost").filter { "generation=$generation" in it.text }
            withClue("E3b bounded heartbeat must report loss while Postgres is still paused") { lost.size shouldBe 1 }
            generationBuiltAt(reader) shouldBe null
            run.thread.isAlive shouldBe true
            docker.unpauseContainerCmd(SharedE2e.postgres.containerId).exec()
            paused = false
            val pinned = fixture.readerRows()[generation] == true
            println(
                "READER_PAUSED before=$before after=${fixture.databaseNow()} pause_s=$pauseSeconds pinned=$pinned " +
                    "reader_alive=${run.thread.isAlive} lost=${lost.map { it.text }}",
            )
            withClue(
                "E3b one failed round cannot expire a live reader; a full TTL outage loses protection",
            ) { pinned shouldBe remainsPinned }
            val meters = reader.getBean(Class.forName("io.micrometer.core.instrument.MeterRegistry"))
            val search = meters.javaClass.getMethod("find", String::class.java).invoke(meters, "datapipelines.lake.reader_lease.lost")
            val counter =
                search.javaClass
                    .getMethod("counter")
                    .invoke(search)
                    .shouldNotBeNull()
            (Class.forName("io.micrometer.core.instrument.Counter").getMethod("count").invoke(counter) as Double) shouldBe 1.0
        } finally {
            if (paused) docker.unpauseContainerCmd(SharedE2e.postgres.containerId).exec()
            logger.detachAppender(capture)
            capture.stop()
            reader.close()
            run.thread.join(CLEANUP_JOIN_MS)
            runCatching { lease.close() }
        }
    }

    companion object {
        private val fixture = ManagedLakeReadsFixture("managed_lake_reads_paused", ManagedLakeReadsPausedMetadataE2eTest::class)

        @BeforeAll
        @JvmStatic
        fun startFixture() {
            fixture.start()
        }

        @AfterAll
        @JvmStatic
        fun closeFixture() {
            try {
                val docker = DockerClientFactory.instance().client()
                val postgres = SharedE2e.postgres.containerId
                val state = docker.inspectContainerCmd(postgres).exec().state
                if (state.paused == true) docker.unpauseContainerCmd(postgres).exec()
            } finally {
                fixture.close()
            }
        }
    }
}
