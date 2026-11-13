package co.datapipelines.staging

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The leftover sweep against a real second PROCESS (#803; probe record A0): a crashed process's
 * files are removed by the next factory, at every point of the open sequence, and a live process's
 * database survives another process's sweep — still writable, its lock still refusing a third
 * process. Each child is [DuckDbScratchChild] on this JVM's own `java` and class path.
 */
class DuckDbScratchCrossProcessTest {
    @TempDir
    lateinit var scratch: Path

    private val children = mutableListOf<Process>()

    @AfterEach
    fun killChildren() {
        children.forEach { it.destroyForcibly().waitFor(30, TimeUnit.SECONDS) }
    }

    @Test
    fun `a live database in another process survives this process's sweep, writable and still locked`() {
        val holder = Child("hold", scratch.toString())
        val executionId = UUID.fromString(holder.expect("READY ").removePrefix("READY "))
        val live = DuckDbStagingFiles(scratch, executionId)
        live.database.exists() shouldBe true
        live.marker.exists() shouldBe true
        val before = namesIn(scratch)
        val sweeper = duckFactory(scratch)

        sweeper.prepareDelivery(UUID.randomUUID(), MIB).close()

        // Rules out: a liveness test answering "dead" for a live owner (the guard that matters most).
        namesIn(scratch) shouldBe before
        sweeper.budget.leftoverBytes shouldBe 0L
        Child("open-db", live.database.toString()).expect("OPENED", "REFUSED ") shouldContain "Conflicting lock"
        holder.send("write")
        holder.expect("WROTE ") shouldBe "WROTE 2"
        holder.send("quit")
        holder.expect("CLOSED")
        pathsOf(scratch, executionId).shouldBeEmpty()
    }

    @Test
    fun `a database whose process was killed with SIGKILL mid-spill is removed by the next factory's sweep`() {
        val child = Child("crash", scratch.toString(), "mid-spill")
        val executionId = UUID.fromString(child.expect("AT mid-spill ").substringAfterLast(' '))
        child.process.destroyForcibly()
        child.process.waitFor(30, TimeUnit.SECONDS) shouldBe true
        val dead = DuckDbStagingFiles(scratch, executionId)
        // The WAL may already be checkpointed away; the file, the spill and the marker are certain.
        listOf(dead.database, dead.tempDir, dead.marker).forEach { it.exists() shouldBe true }
        val spilled = Files.list(dead.tempDir).use { it.count() }
        println("crash evidence: mid-spill left ${namesIn(scratch)} (spill files: $spilled)")
        val factory = duckFactory(scratch)

        factory.prepareDelivery(UUID.randomUUID(), MIB).close()

        // Rules out: a crash's files surviving the next start; nothing is counted for a provably dead owner.
        namesIn(scratch).shouldBeEmpty()
        factory.budget.reservedBytes shouldBe 0L
        factory.budget.leftoverBytes shouldBe 0L
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "after-admission", "owner-file-only", "before-driver-open",
            "after-driver-open", "in-trusted-initializer", "handed-out-with-table",
        ],
    )
    fun `a crash at each point of the open sequence leaves nothing after the next sweep`(point: String) {
        val child = Child("crash", scratch.toString(), point)
        child.expect("AT $point ")
        child.process.destroyForcibly()
        child.process.waitFor(30, TimeUnit.SECONDS) shouldBe true
        val left = Files.list(scratch).use { s -> s.map { "${it.fileName}=${sizeOf(it)}" }.sorted().toList() }
        val factory = duckFactory(scratch)

        factory.prepareDelivery(UUID.randomUUID(), MIB).close()

        val after = namesIn(scratch)
        println("REPLAY| $point | left by the crash: $left | after the sweep: $after | counted: ${factory.budget.leftoverBytes}")
        // Non-vacuity: the crash really left what that point of the sequence had written.
        EXPECTED_LEFT.getValue(point).forEach { prefix -> left.any { it.startsWith(prefix) } shouldBe true }
        after.shouldBeEmpty()
        factory.budget.leftoverBytes shouldBe 0L
    }

    private fun sizeOf(path: Path): Long =
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(path).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        } else {
            Files.size(path)
        }

    private fun Path.exists(): Boolean = Files.exists(this, LinkOption.NOFOLLOW_LINKS)

    /** One child JVM; its stdout lines queue up for [expect]. */
    private inner class Child(
        vararg args: String,
    ) {
        val process: Process =
            ProcessBuilder(
                listOf(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m",
                    "-cp",
                    System.getProperty("java.class.path"),
                    DuckDbScratchChild::class.java.name,
                ) + args,
            ).redirectErrorStream(true).start().also { children += it }
        private val lines = LinkedBlockingQueue<String>()
        private val seen = mutableListOf<String>()

        init {
            thread(isDaemon = true) { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
        }

        fun send(line: String) {
            process.outputStream.write("$line\n".toByteArray())
            process.outputStream.flush()
        }

        /** The first line starting with one of [prefixes]; fails with every line seen after two minutes. */
        fun expect(vararg prefixes: String): String {
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
            while (System.nanoTime() < deadline) {
                val line = lines.poll(1, TimeUnit.SECONDS) ?: continue
                seen += line
                if (prefixes.any { line.startsWith(it) }) return line
            }
            error("no line starting with ${prefixes.toList()} from the child; it printed: $seen")
        }
    }

    private companion object {
        /** What each crash point must have left before the sweep (name prefixes). */
        val EXPECTED_LEFT =
            mapOf(
                "after-admission" to emptyList(),
                "owner-file-only" to listOf("dp-owner_"),
                "before-driver-open" to listOf("dp-owner_", "dp-marker_"),
                "after-driver-open" to listOf("dp-owner_", "dp-marker_", "exec_"),
                "in-trusted-initializer" to listOf("dp-owner_", "dp-marker_", "exec_"),
                "handed-out-with-table" to listOf("dp-owner_", "dp-marker_", "exec_"),
            )
    }
}
