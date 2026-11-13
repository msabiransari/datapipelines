package co.datapipelines.web.health

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.Status
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Path

/**
 * #806 section A — the `duckdb_scratch` indicator's ordered status table, one case per row, over a fake
 * [ScratchSpaceReader] that controls every reading. The exact-threshold case and one byte under it are pinned;
 * every built [Health] carries no details (the path is operator configuration, never a health detail).
 */
class DuckDbScratchHealthIndicatorTest {
    private val dir = Path.of("/srv/scratch/dp-staging")
    private val parent = Path.of("/srv/scratch")

    /** A file system of named paths; anything not named is absent below an existing `/` and `/srv`. */
    private class FakeReader : ScratchSpaceReader {
        val kinds =
            mutableMapOf(
                Path.of("/") to ScratchPathKind.DIRECTORY,
                Path.of("/srv") to ScratchPathKind.DIRECTORY,
            )
        val writable = mutableSetOf<Path>()
        val usable = mutableMapOf<Path, Long>()
        val throwOnKind = mutableSetOf<Path>()
        var throwOnWrite = false
        var throwOnUsable = false

        override fun kind(path: Path): ScratchPathKind {
            if (path in throwOnKind) throw AccessDeniedException(path.toString())
            return kinds[path] ?: ScratchPathKind.ABSENT
        }

        override fun canWrite(directory: Path): Boolean {
            if (throwOnWrite) throw SecurityException("no access check")
            return directory in writable
        }

        override fun usableBytes(path: Path): Long {
            if (throwOnUsable) throw IOException("file store unreadable")
            return usable[path] ?: error("no usable reading for $path")
        }
    }

    private val reader = FakeReader()

    private fun indicator() = DuckDbScratchHealthIndicator(ScratchSpace(dir, reader), THRESHOLD)

    /** The directory exists, is writable, and its store has [usable] bytes. */
    private fun healthyDirectory(usable: Long = THRESHOLD) {
        reader.kinds[parent] = ScratchPathKind.DIRECTORY
        reader.kinds[dir] = ScratchPathKind.DIRECTORY
        reader.writable.add(dir)
        reader.usable[dir] = usable
    }

    /** The directory is absent under a writable parent whose store has [usable] bytes. */
    private fun absentUnderWritableParent(usable: Long = THRESHOLD) {
        reader.kinds[parent] = ScratchPathKind.DIRECTORY
        reader.writable.add(parent)
        reader.usable[parent] = usable
    }

    private fun statusOf(health: Health): Status {
        health.details shouldBe emptyMap<String, Any>()
        return health.status
    }

    @Test
    fun `row 1 - a kind reading that throws is DOWN`() {
        healthyDirectory()
        reader.throwOnKind.add(dir)
        statusOf(indicator().health()) shouldBe Status.DOWN
    }

    @Test
    fun `row 1 - a write-permission reading that throws is DOWN`() {
        healthyDirectory()
        reader.throwOnWrite = true
        statusOf(indicator().health()) shouldBe Status.DOWN
    }

    @Test
    fun `row 1 - a usable-bytes reading that throws is DOWN, even on a path that row 2 would refuse`() {
        healthyDirectory()
        reader.kinds[dir] = ScratchPathKind.NOT_DIRECTORY
        reader.throwOnUsable = true
        statusOf(indicator().health()) shouldBe Status.DOWN
    }

    @Test
    fun `row 1 - a parent that can be confirmed neither present nor absent is DOWN`() {
        reader.throwOnKind.add(parent)
        statusOf(indicator().health()) shouldBe Status.DOWN
    }

    @Test
    fun `row 2 - a scratch path that exists and is not a directory is OUT_OF_SERVICE`() {
        healthyDirectory(usable = THRESHOLD * 10)
        reader.kinds[dir] = ScratchPathKind.NOT_DIRECTORY
        statusOf(indicator().health()) shouldBe Status.OUT_OF_SERVICE
    }

    @Test
    fun `row 2 - a scratch directory the process cannot write is OUT_OF_SERVICE`() {
        healthyDirectory(usable = THRESHOLD * 10)
        reader.writable.remove(dir)
        statusOf(indicator().health()) shouldBe Status.OUT_OF_SERVICE
    }

    @Test
    fun `row 3 - an absent directory whose nearest existing parent is not writable is OUT_OF_SERVICE`() {
        absentUnderWritableParent(usable = THRESHOLD * 10)
        reader.writable.remove(parent)
        statusOf(indicator().health()) shouldBe Status.OUT_OF_SERVICE
    }

    @Test
    fun `row 3 - an absent directory whose nearest existing parent is a file is OUT_OF_SERVICE`() {
        absentUnderWritableParent(usable = THRESHOLD * 10)
        reader.kinds[parent] = ScratchPathKind.NOT_DIRECTORY
        // Nothing is read beneath a file: a stat there would be an error, not an absence.
        reader.throwOnKind.add(dir)
        statusOf(indicator().health()) shouldBe Status.OUT_OF_SERVICE
    }

    @Test
    fun `row 4 - one byte under one default reservation is OUT_OF_SERVICE, in the directory and under an absent one's parent`() {
        healthyDirectory(usable = THRESHOLD - 1)
        statusOf(indicator().health()) shouldBe Status.OUT_OF_SERVICE

        val absent = FakeReader().also { it.kinds[parent] = ScratchPathKind.DIRECTORY }
        absent.writable.add(parent)
        absent.usable[parent] = THRESHOLD - 1
        statusOf(DuckDbScratchHealthIndicator(ScratchSpace(dir, absent), THRESHOLD).health()) shouldBe Status.OUT_OF_SERVICE
    }

    @Test
    fun `row 5 - exactly one default reservation is UP`() {
        healthyDirectory(usable = THRESHOLD)
        statusOf(indicator().health()) shouldBe Status.UP
    }

    @Test
    fun `row 5 - an absent directory under a writable parent with room is UP, reading the parent's store`() {
        absentUnderWritableParent(usable = THRESHOLD)
        statusOf(indicator().health()) shouldBe Status.UP
    }

    @Test
    fun `the status follows the readings at every probe`() {
        healthyDirectory(usable = THRESHOLD)
        val indicator = indicator()
        statusOf(indicator.health()) shouldBe Status.UP
        reader.usable[dir] = THRESHOLD - 1
        statusOf(indicator.health()) shouldBe Status.OUT_OF_SERVICE
        reader.usable[dir] = THRESHOLD
        statusOf(indicator.health()) shouldBe Status.UP
    }

    @Test
    fun `one WARN on the transition into a non-UP status, naming the path and the numbers - not one per probe`() {
        val logger = LoggerFactory.getLogger(DuckDbScratchHealthIndicator::class.java) as Logger
        // raw-list-appender: synchronous — the indicator is probed on the test thread only
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            healthyDirectory(usable = THRESHOLD)
            val indicator = indicator()
            repeat(3) { indicator.health() }
            appender.list.shouldBeEmpty()

            reader.usable[dir] = THRESHOLD - 1
            repeat(3) { indicator.health() }
            appender.list.size shouldBe 1
            val warn = appender.list.single()
            warn.level shouldBe Level.WARN
            warn.formattedMessage shouldContain "status=OUT_OF_SERVICE"
            warn.formattedMessage shouldContain "path=$dir"
            warn.formattedMessage shouldContain "${THRESHOLD - 1} usable bytes"
            warn.formattedMessage shouldContain "threshold_bytes=$THRESHOLD"

            reader.throwOnUsable = true
            repeat(2) { indicator.health() }
            appender.list.size shouldBe 2
            appender.list[1].level shouldBe Level.WARN
            appender.list[1].formattedMessage shouldContain "status=DOWN"

            reader.throwOnUsable = false
            reader.usable[dir] = THRESHOLD
            repeat(2) { indicator.health() }
            appender.list.size shouldBe 3
            appender.list[2].level shouldBe Level.INFO
            appender.list[2].formattedMessage shouldContain "status=UP"
        } finally {
            logger.detachAppender(appender)
        }
    }

    private companion object {
        /** One default reservation at the shipped defaults: 1 GiB × 3. */
        const val THRESHOLD = 3L shl 30
    }
}
