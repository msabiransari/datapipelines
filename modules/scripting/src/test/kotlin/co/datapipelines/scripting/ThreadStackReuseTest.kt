package co.datapipelines.scripting

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * #685 — **a Java thread that starts after the GraalJS isolate's threads have exited still ends a
 * runaway recursion in `StackOverflowError`, never in a SIGSEGV that kills the JVM.**
 *
 * On the measured Linux runtime, isolate threads placed their guard zones one page higher than
 * VM-started Java threads. The syscall captures showed a protected page left inside a reused Java
 * thread's usable stack, followed by SIGSEGV and exit 139 without a HotSpot report. This reproduced
 * the worker loss in `JsonataEngineNestingLimitTest` without JSONata. The explanation involving an
 * attached thread's glibc guard and failed kernel signal-frame delivery remains an inference.
 * The test conventions turn glibc's stack cache off in every test JVM (`CommonConventionsPlugin`,
 * DEVELOPMENT.md §9.4), so a fresh mapping replaces every reused block.
 *
 * A forked JVM ([StackReuseProbe]) runs JavaScript evaluations, waits for their isolate threads to
 * exit, then starts [StackReuseProbe.THREADS] Java threads at once (so each holds its own cached
 * block) and lets each recurse to its stack's end. Forked because the defect kills the process.
 * The child receives this test JVM's own environment, so it is red when the conventions stop
 * setting the tunable. Unmitigated crash controls remain diagnostic evidence, rather than requiring
 * every Linux runtime to retain this defect. Measured 2026-11-01 on Temurin 21.0.12 with
 * GraalJS 25.4.4.1.1: 10/10 children died without it, 6/6 pinned to two CPUs; 0/16 died with it.
 */
@Timeout(180)
class ThreadStackReuseTest {
    @Test
    fun `threads reusing an exited isolate thread's stack overflow into StackOverflowError under the test conventions`(
        @TempDir dir: Path,
    ) {
        withClue("the test conventions must turn glibc's thread-stack cache off for this JVM (CommonConventionsPlugin)") {
            System.getenv(TUNABLES).orEmpty().split(':') shouldContainEntry DISABLED
        }
        val (exit, log) = runProbe(dir, environment = System.getenv())
        withClue("the child must finish with every thread's StackOverflowError caught:\n$log") {
            exit shouldBe 0
            log shouldContain "stack-reuse-probe overflows=${StackReuseProbe.THREADS}/${StackReuseProbe.THREADS}"
        }
    }

    private fun runProbe(
        dir: Path,
        environment: Map<String, String>,
    ): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val logFile = dir.resolve("child.log").toFile()
        val builder =
            ProcessBuilder(
                java,
                "-Xmx256m",
                "-XX:ErrorFile=" + dir.resolve("hs_err_pid%p.log"),
                "-cp",
                System.getProperty("java.class.path"),
                StackReuseProbe::class.java.name,
            ).redirectErrorStream(true).redirectOutput(logFile)
        builder.environment().apply {
            clear()
            putAll(environment)
        }
        val process = builder.start()
        try {
            withClue("the probe child did not finish in time") { process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true }
            return process.exitValue() to logFile.readText()
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                withClue("the probe child did not exit after termination") {
                    process.waitFor(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
                }
            }
        }
    }

    private infix fun List<String>.shouldContainEntry(entry: String) {
        withClue("GLIBC_TUNABLES entries: $this") { (entry in this) shouldBe true }
    }

    private companion object {
        const val TUNABLES = "GLIBC_TUNABLES"
        const val STACK_CACHE = "glibc.pthread.stack_cache_size="
        const val DISABLED = STACK_CACHE + "0"

        const val CHILD_TIMEOUT_SECONDS = 75L
        const val CLEANUP_TIMEOUT_SECONDS = 5L
    }
}

/**
 * The forked process [ThreadStackReuseTest] runs: isolate threads come and go, then Java threads
 * overflow their stacks concurrently. Prints `stack-reuse-probe overflows=<caught>/<started>`.
 */
object StackReuseProbe {
    const val THREADS = 64
    private const val EVALUATIONS = 20
    private const val ISOLATE_THREADS_EXIT_MILLIS = 3_000L

    /** Half the threads at a reduced stack (small enough to reuse either isolate block size), half at the default. */
    private const val REDUCED_STACK_BYTES = 300L * 1024

    /** The overflow IS the expected outcome: each thread counts its StackOverflowError instead of rethrowing it. */
    @JvmStatic
    @Suppress("SwallowedException")
    fun main(args: Array<String>) {
        repeat(EVALUATIONS) {
            ScriptingTestSupport.js("let s = 0; for (let i = 0; i < 200000; i++) { s += i % 7; } return s;")
        }
        Thread.sleep(ISOLATE_THREADS_EXIT_MILLIS)
        val go = CountDownLatch(1)
        val caught = AtomicInteger()
        val threads =
            (0 until THREADS).map { i ->
                val overflow =
                    Runnable {
                        go.await()
                        try {
                            recurse(0)
                        } catch (overflowed: StackOverflowError) {
                            caught.incrementAndGet()
                        }
                    }
                val stackBytes = if (i % 2 == 0) REDUCED_STACK_BYTES else 0L
                Thread(null, overflow, "stack-reuse-$i", stackBytes).also { it.start() }
            }
        go.countDown()
        threads.forEach { it.join() }
        println("stack-reuse-probe overflows=${caught.get()}/$THREADS")
    }

    private fun recurse(depth: Int): Int = recurse(depth + 1) + 1
}
