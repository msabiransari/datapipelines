package co.datapipelines.persistence

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * #266 D.7 — **the crash semantics, as a guarantee**: kill the process mid-commit and check that no
 * caller ever proceeded past an item that is not durable.
 *
 * A forked JVM ([CrashHarness]) runs 16 producers recording items through a real [BatchingWriter]
 * whose sink appends each batch to a file and forces it to disk. A producer appends an item's id to
 * a second file — also forced — only AFTER `record` returned `Committed`: that file is "what the
 * business did because the write was acknowledged". On its 20th batch the sink HALTS the JVM
 * (`Runtime.halt` — no shutdown hooks, no drain, the same as `SIGKILL` for this question) BEFORE
 * writing that batch.
 *
 * The guarantee: every proceeded id is in the committed file. The items that were in flight at the
 * crash — queued or inside the halted batch — were never acknowledged, so their callers never
 * proceeded: the outcome for them is "not completed", never "completed without a record". The run
 * is non-vacuous only if awaited items really were in flight when the JVM died (asserted).
 *
 * The non-awaited `submit` path has a loss window by design; the harness submits too, and the test
 * reports how many submitted items the crash took — the window observability §7 documents.
 */
@Timeout(120)
class BatchingWriterCrashTest {
    @Test
    fun `a crash mid-commit never leaves a caller proceeded past an item that is not durable`(
        @TempDir dir: Path,
    ) {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), CrashHarness::class.java.name, dir.toString())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("child.log").toFile())
                .start()
        process.waitFor(90, TimeUnit.SECONDS) shouldBe true
        withClue("the child must have been HALTED by its sink, not have finished: ${dir.resolve("child.log").toFile().readText()}") {
            process.exitValue() shouldBe CrashHarness.HALT_STATUS
        }

        val committed = lines(dir, CrashHarness.COMMITTED)
        val proceeded = lines(dir, CrashHarness.PROCEEDED)
        val attempted = lines(dir, CrashHarness.ATTEMPTED)
        val submitted = lines(dir, CrashHarness.SUBMITTED)

        withClue("every id a caller proceeded past is durable") { (proceeded - committed).size shouldBe 0 }
        withClue("non-vacuity: callers had proceeded before the crash") { proceeded.size shouldBeGreaterThan 0 }
        val inFlight = attempted - proceeded
        withClue("non-vacuity: awaited items were in flight when the JVM died") { inFlight.size shouldBeGreaterThan 0 }
        val submittedLost = submitted - committed
        println(
            "crash-harness attempted=${attempted.size} committed=${committed.size} proceeded=${proceeded.size} " +
                "in_flight_awaited=${inFlight.size} submitted=${submitted.size} submitted_lost=${submittedLost.size}",
        )
    }

    private fun lines(
        dir: Path,
        name: String,
    ): Set<String> =
        dir
            .resolve(name)
            .toFile()
            .takeIf { it.exists() }
            ?.readLines()
            ?.filter { it.isNotBlank() }
            ?.toSet() ?: emptySet()
}

/**
 * The forked process [BatchingWriterCrashTest] kills. Every file write is forced to disk before it
 * counts, so the files are the durable truth the parent reads after the halt.
 */
object CrashHarness {
    const val COMMITTED = "committed.log"
    const val PROCEEDED = "proceeded.log"
    const val ATTEMPTED = "attempted.log"
    const val SUBMITTED = "submitted.log"
    const val HALT_STATUS = 137
    private const val HALT_ON_BATCH = 20
    private const val PRODUCERS = 16
    private const val PER_PRODUCER = 200

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        val committed = ForcedLog(dir.resolve(COMMITTED))
        val proceeded = ForcedLog(dir.resolve(PROCEEDED))
        val attempted = ForcedLog(dir.resolve(ATTEMPTED))
        val submitted = ForcedLog(dir.resolve(SUBMITTED))
        val batches = AtomicInteger()
        val sink =
            object : BatchSink<String> {
                override fun write(items: List<String>) {
                    if (batches.incrementAndGet() == HALT_ON_BATCH) Runtime.getRuntime().halt(HALT_STATUS)
                    // A commit that takes a little time, so items pile up behind it.
                    Thread.sleep(2)
                    committed.append(items)
                }

                override fun partitionKey(item: String): Any = item.substringBefore('-')

                override fun sizeOf(item: String): Int = item.length

                override fun describe(item: String): String = item
            }
        val writer = BatchingWriter("crash", BatchingConfig(writers = 2), sink)
        val pool = Executors.newFixedThreadPool(PRODUCERS)
        val done = CountDownLatch(PRODUCERS)
        repeat(PRODUCERS) { p ->
            pool.execute {
                repeat(PER_PRODUCER) { n ->
                    val id = "p$p-$n"
                    attempted.append(listOf(id))
                    if (writer.record(id) == Outcome.Committed) proceeded.append(listOf(id))
                    val fire = "p$p-submit-$n"
                    if (writer.submit(fire)) submitted.append(listOf(fire))
                }
                done.countDown()
            }
        }
        done.await(60, TimeUnit.SECONDS)
        // Reaching here means the sink never halted — the parent reads exit status 0 as a broken fixture.
        exitProcess(0)
    }

    /** An append-only file whose every append is forced to disk before it returns. */
    private class ForcedLog(
        path: Path,
    ) {
        private val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)

        @Synchronized
        fun append(lines: List<String>) {
            channel.write(java.nio.ByteBuffer.wrap(lines.joinToString("\n", postfix = "\n").toByteArray()))
            channel.force(false)
        }
    }
}
