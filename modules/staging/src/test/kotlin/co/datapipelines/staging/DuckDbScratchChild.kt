package co.datapipelines.staging

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The second PROCESS of [DuckDbScratchCrossProcessTest] (#803): started with this test JVM's own
 * `java` and class path, it holds a live database, crashes at a named point of the open sequence,
 * or tries to open a database file as another process would. It speaks one line per event on
 * stdout; the parent reads them.
 *
 * A crash is `Runtime.halt` (no shutdown hook, no finally — the kernel releases every lock, as for
 * `kill -9`), or the parent's SIGKILL where the point is a running statement.
 */
object DuckDbScratchChild {
    private const val HALTED = 137

    @JvmStatic
    fun main(args: Array<String>) {
        when (args[0]) {
            "hold" -> hold(Path.of(args[1]))
            "crash" -> crash(Path.of(args[1]), args[2])
            "open-db" -> openDb(Path.of(args[1]))
            else -> error("unknown mode ${args[0]}")
        }
    }

    private fun say(line: String) {
        println(line)
        System.out.flush()
    }

    private fun hold(dir: Path) {
        val staging = duckFactory(dir).open()
        staging.authorExec("CREATE TABLE t AS SELECT 1 AS id")
        say("READY ${staging.executionId}")
        generateSequence { readlnOrNull() }.forEach { command ->
            when (command.trim()) {
                "write" -> {
                    staging.authorExec("INSERT INTO t VALUES (2)")
                    say("WROTE ${staging.scalarText("SELECT count(*) FROM t")}")
                }
                "quit" -> {
                    staging.close()
                    say("CLOSED")
                    exitProcess(0)
                }
            }
        }
    }

    private fun halt(
        point: String,
        executionId: UUID,
    ): Nothing {
        say("AT $point $executionId")
        Runtime.getRuntime().halt(HALTED)
        error("unreachable")
    }

    private fun crash(
        dir: Path,
        point: String,
    ) {
        val id = UUID.randomUUID()
        val props = duckProps(dir, memoryLimitBytes = 64 * MIB)
        when (point) {
            "after-admission" -> {
                Files.createDirectories(dir)
                DuckDbStagingFactory(props).prepareDelivery(id, MIB)
                halt(point, id)
            }
            "owner-file-only" -> {
                val factory = DuckDbStagingFactory(props)
                factory.create(UUID.randomUUID(), MIB).close()
                halt(point, id)
            }
            "before-driver-open" -> {
                val factory = DuckDbStagingFactory(props, ScratchBudget(props.scratchCapacityBytes)) { _, _ -> halt(point, id) }
                factory.create(id, MIB)
            }
            "after-driver-open" -> {
                val factory =
                    DuckDbStagingFactory(props, ScratchBudget(props.scratchCapacityBytes)) { url, p ->
                        DriverManager.getConnection(url, p).also { halt(point, id) }
                    }
                factory.create(id, MIB)
            }
            "in-trusted-initializer" -> DuckDbStagingFactory(props).prepareDelivery(id, 256 * MIB).open { halt(point, id) }
            "handed-out-with-table" -> {
                val staging = DuckDbStagingFactory(props).create(id, 256 * MIB) as DuckDbStaging
                staging.authorExec("CREATE TABLE t AS SELECT range AS x FROM range(100000)")
                say("AT $point $id")
                Thread.sleep(Long.MAX_VALUE)
            }
            "mid-spill" -> {
                val staging = DuckDbStagingFactory(props).create(id, 512 * MIB) as DuckDbStaging
                staging.authorExec("CREATE TABLE big AS SELECT md5(range::VARCHAR) || repeat('x', 200) AS s FROM range(2000000)")
                val spill = DuckDbStagingFiles(dir, id).tempDir
                thread(isDaemon = true) {
                    while (!(Files.isDirectory(spill) && Files.list(spill).use { it.findAny().isPresent })) Thread.sleep(20)
                    say("AT $point $id")
                }
                runBlocking { staging.execute("CREATE TABLE sorted AS SELECT s FROM big ORDER BY s") }
                say("SORT FINISHED")
                Thread.sleep(Long.MAX_VALUE)
            }
            else -> error("unknown crash point $point")
        }
    }

    private fun openDb(database: Path) {
        try {
            DriverManager.getConnection("jdbc:duckdb:$database").use { say("OPENED") }
        } catch (e: SQLException) {
            say("REFUSED ${e.message?.lineSequence()?.first()}")
        }
    }
}
