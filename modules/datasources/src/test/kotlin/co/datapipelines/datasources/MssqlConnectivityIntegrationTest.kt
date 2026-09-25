package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path

/**
 * The MSSQL half of [DialectConnectivityIntegrationTest], separated **only** because its
 * container cannot start on every developer host.
 *
 * ## Why the architecture gate
 *
 * Microsoft publishes `mcr.microsoft.com/mssql/server` for **linux/amd64 only** — there is no
 * arm64 image. On Apple Silicon Docker runs it under Rosetta, where `sqlservr` starts, burns CPU,
 * and never reaches "ready for client connections"; every JDBC attempt gets a connection reset
 * and Testcontainers eventually throws `ContainerLaunchException`. Verified on this machine
 * (2026-08-08, darwin/arm64): the container reports `State.Running = true` while `ps` inside it
 * shows `/run/rosetta/rosetta /opt/mssql/bin/sqlservr` spinning, and the container log stops after
 * the three-line startup banner. Microsoft's documented arm64 alternative, Azure SQL Edge, has
 * been retired and is a different T-SQL surface, so substituting it would mean this test no longer
 * tests the product the `MSSQL` dialect targets.
 *
 * The gate is therefore on the **host architecture**, not on Docker availability: on amd64 (CI,
 * and any Intel/Linux dev box) the test runs for real and a genuine MSSQL regression fails the
 * build. On arm64 it reports **skipped, with the reason in the JUnit XML** — deliberately not a
 * silent pass and deliberately not a red build for an environment limitation. Escalated to the
 * orchestrator as a spec-coverage caveat: datasources.md §13.2 asks for an MSSQL container test,
 * and this is that test, with its one unavoidable host precondition stated out loud.
 *
 * ## Why the container's log is captured, and the AIO relaunch (#229)
 *
 * The gate reds of 2026-09-25 were NOT "the listener answered before the engine was ready" —
 * the captured container log proves `sqlservr` **died**, and the JDBC probe's
 * `unexpected pre-login response` was only the outermost layer: `sqlservr` aborts with the
 * fatal error `Unable to create a new asynchronous I/O context. Please increase sysctl
 * fs.aio-max-nr` (errno 11, EAGAIN) when the HOST's AIO context pool is exhausted — a
 * moving `2022-latest` tag, a slow engine, or an unready listener are none of them the
 * cause, and no readiness wait can fix a process that has exited. The gate shape (six test
 * forks, each booting containers, over a busy box) transiently exhausts the pool: this box
 * runs the 65536 default with ~27k contexts in use at idle.
 *
 * So this suite (a) streams the container's stdout/stderr into the test's stderr — the JUnit
 * XML keeps it and every future red carries the death reason, (b) on THAT fatal signature
 * waits (bounded) for the host's AIO pool to drain and relaunches ONCE — a fresh container,
 * since the dead one is gone — and (c) fails loudly with the log if the pool does not drain.
 * The durable fix is the host's: raise `fs.aio-max-nr` (e.g. `sysctl -w fs.aio-max-nr=1048576`
 * plus the same in `/etc/sysctl.d/`, root action — DEVELOPMENT.md §9.2a), which removes the
 * exhaustion instead of surviving it.
 */
class MssqlConnectivityIntegrationTest {
    @Test
    fun `mssql connects, queries, and maps an integer column`() {
        assumeTrue(isAmd64Host()) {
            "SKIPPED on ${System.getProperty("os.arch")}: mcr.microsoft.com/mssql/server ships linux/amd64 only " +
                "and does not reach 'ready for client connections' under Rosetta emulation. " +
                "This test runs for real on amd64 (CI)."
        }
        // Started manually rather than via @Container: a @Container field starts in beforeAll,
        // which runs BEFORE the assumption and would fail the whole class on arm64 instead of
        // skipping it — exactly the failure this class exists to avoid.
        startMssql().use { mssql ->
            DialectProbe.verifyIntegerColumn(
                datasource =
                    Datasource(
                        name = "mssql_it",
                        displayName = "MSSQL",
                        dialect = Dialect.MSSQL,
                        jdbcUrl = mssql.jdbcUrl,
                        username = mssql.username,
                        secret = mssql.password,
                        properties = DatasourceProperties(jdbc = mapOf("trustServerCertificate" to "true")),
                    ),
                integerQuery = "SELECT CAST(1 AS INT) AS n",
            )
        }
    }

    /** The image pin, the license, and the log capture (stderr + a local buffer) — per attempt. */
    private fun newMssql(log: StringBuilder): MSSQLServerContainer<*> =
        MSSQLServerContainer(DockerImageName.parse(IMAGE))
            .acceptLicense()
            .withLogConsumer { frame ->
                val text = frame.utf8String
                log.append(text)
                System.err.print(text)
            }

    /**
     * Start the container; on the #229 AIO-exhaustion death (the attempt's captured log names
     * it — read from the BUFFER, never `container.logs`, which is a docker call on an id
     * Testcontainers has already nulled by the time `start()` throws), wait for the host pool
     * to drain and relaunch once. Any other failure, or a second AIO death, throws — with the
     * container's own log already in this test's stderr.
     */
    private fun startMssql(): MSSQLServerContainer<*> {
        var attempt = 1
        while (true) {
            val log = StringBuilder()
            val container = newMssql(log)
            try {
                container.start()
                return container
            } catch (e: Exception) {
                val diedOfAio = log.contains(AIO_FATAL_SIGNATURE)
                if (!diedOfAio || attempt >= AIO_RELAUNCH_ATTEMPTS) throw e
                System.err.println("==== #229 sqlservr died on host AIO exhaustion; awaiting headroom, relaunching ====")
                container.stop()
                awaitAioHeadroom()
                attempt++
            }
        }
    }

    /**
     * Wait (bounded) until the host's AIO pool has headroom again: `fs.aio-nr` below
     * `fs.aio-max-nr` minus a batch of contexts. The pool drains as the concurrent holders
     * (the other forks' containers) release their contexts. On a non-Linux host (no /proc
     * sysctls) this returns immediately — the relaunch is then just a relaunch.
     */
    private fun awaitAioHeadroom() {
        val deadline = System.currentTimeMillis() + AIO_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val headroom = aioHeadroom() ?: return
            if (headroom >= AIO_HEADROOM_NEEDED) return
            Thread.sleep(AIO_POLL_MS)
        }
        error(
            "host AIO pool did not drain within ${AIO_WAIT_MS} ms " +
                "(headroom ${aioHeadroom() ?: "unknown"} < $AIO_HEADROOM_NEEDED) — raise fs.aio-max-nr (DEVELOPMENT.md §9.2a)",
        )
    }

    /** Free AIO contexts right now, or null when the host does not expose the sysctls. */
    private fun aioHeadroom(): Long? =
        try {
            val max = Files.readString(Path.of("/proc/sys/fs/aio-max-nr")).trim().toLong()
            val now = Files.readString(Path.of("/proc/sys/fs/aio-nr")).trim().toLong()
            max - now
        } catch (_: Exception) {
            null
        }

    private companion object {
        /**
         * Pinned by digest (#229): build `16.0.4275.2`, the build this box cached ≈2026-08-20 and
         * every gate has run since — while the moving `2022-latest` tag had already been
         * repointed at a different build by 2026-09-25 (`sha256:4402d880…`, `docker manifest
         * inspect` against `mcr.microsoft.com`, read 2026-09-25). The pin is the PROVEN build:
         * it is what all green evidence ran, and a digest cannot move the way the tag did.
         * To move it: `docker pull mcr.microsoft.com/mssql/server:2022-latest`, read the new
         * RepoDigest, run THIS suite and `DialectConnectivityIntegrationTest` against it, land
         * it as its own gated commit (DEVELOPMENT.md §9.2a).
         */
        const val IMAGE =
            "mcr.microsoft.com/mssql/server@sha256:97b448857967be55e005424a660056fe6d51814435804dc07e8f79f028bab5fb"

        /** sqlservr's own fatal line when the host's AIO pool is exhausted (#229's captured log). */
        const val AIO_FATAL_SIGNATURE = "asynchronous I/O context"

        const val AIO_WAIT_MS = 120_000L
        const val AIO_POLL_MS = 2_000L

        /** sqlservr allocates a batch of contexts at startup; demand more than this much slack. */
        const val AIO_HEADROOM_NEEDED = 8_192L

        /** The launch is attempted at most this many times (the initial one plus one relaunch). */
        const val AIO_RELAUNCH_ATTEMPTS = 2

        /** amd64 under either of the JVM's two spellings; anything else cannot run the image. */
        fun isAmd64Host(): Boolean = System.getProperty("os.arch") in setOf("amd64", "x86_64")
    }
}
