package co.datapipelines.staging

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Scheduling-only pauses expose replacement of an owner pathname between open and lock. */
class DuckDbOwnerPathRaceTest {
    @TempDir
    lateinit var scratch: Path

    private val children = mutableListOf<Process>()

    @AfterEach
    fun stopChildren() {
        children.forEach { it.destroyForcibly().waitFor(30, TimeUnit.SECONDS) }
    }

    @Test
    fun `a probe of an unlinked owner pathname is unprovable`() {
        val owner = UUID.randomUUID()
        val path = scratch.resolve(DuckDbScratchNames.owner(owner))
        Files.createFile(path)
        val state = DuckDbScratchOwnership.probe(scratch, owner) { Files.delete(path) }
        try {
            state shouldBe DuckDbScratchOwnership.OwnerState.Unprovable
        } finally {
            if (state is DuckDbScratchOwnership.OwnerState.Dead) state.proof.close()
        }
    }

    @Test
    fun `a probe of a replaced owner pathname is unprovable`() {
        val owner = UUID.randomUUID()
        val path = scratch.resolve(DuckDbScratchNames.owner(owner))
        Files.createFile(path)
        val state =
            DuckDbScratchOwnership.probe(scratch, owner) {
                Files.delete(path)
                Files.writeString(path, "replacement")
            }
        try {
            state shouldBe DuckDbScratchOwnership.OwnerState.Unprovable
        } finally {
            if (state is DuckDbScratchOwnership.OwnerState.Dead) state.proof.close()
        }
    }

    @Test
    fun `a proof never deletes a replacement pathname`() {
        val owner = UUID.randomUUID()
        val path = scratch.resolve(DuckDbScratchNames.owner(owner))
        Files.createFile(path)
        val state = DuckDbScratchOwnership.probe(scratch, owner) as DuckDbScratchOwnership.OwnerState.Dead
        state.proof.use {
            Files.delete(path)
            Files.writeString(path, "replacement")
            it.deleteOwnerFile()
            Files.exists(path) shouldBe true
            Files.readString(path) shouldBe "replacement"
        }
    }

    @Test
    fun `an old open descriptor cannot remove the replacement live owner's lock file`() {
        val holder = Child("owner", scratch.toString())
        val ownerId = UUID.fromString(holder.expect("OWNER_OPEN ").substringAfter(' '))
        val ownerPath = scratch.resolve(DuckDbScratchNames.owner(ownerId))
        val firstKey = Files.readAttributes(ownerPath, BasicFileAttributes::class.java).fileKey()
        val delayed = Child("probe", scratch.toString(), ownerId.toString())
        delayed.expect("PROBE_OPEN")

        // Another process proves the first, not-yet-locked file abandoned and removes it.
        val first = DuckDbScratchOwnership.probe(scratch, ownerId) as DuckDbScratchOwnership.OwnerState.Dead
        first.proof.use { it.deleteOwnerFile() }
        Files.exists(ownerPath) shouldBe false

        // The real factory lock routine retries onto a new inode under the same owner UUID.
        holder.send("continue")
        holder.expect("OWNER_HELD ")
        val secondKey = Files.readAttributes(ownerPath, BasicFileAttributes::class.java).fileKey()
        (firstKey != null && secondKey != null && firstKey != secondKey) shouldBe true
        delayed.send("continue")
        delayed.expect("PROBE_DONE ")
        holder.send("write")
        holder.expect("WROTE ") shouldBe "WROTE 2"

        val sweep = DuckDbScratchHousekeeper(scratch, UUID.randomUUID(), LoggerFactory.getLogger(javaClass)).sweep()
        println("OWNER_PATH_RACE liveOwnerPath=${Files.exists(ownerPath)} counted=${sweep.countedBytes}")
        assertSoftly {
            Files.exists(ownerPath) shouldBe true
            sweep.counted.shouldBeEmpty()
            DuckDbScratchOwnership.probe(scratch, ownerId) shouldBe DuckDbScratchOwnership.OwnerState.Live
        }
    }

    private inner class Child(
        vararg args: String,
    ) {
        private val process =
            ProcessBuilder(
                listOf(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m",
                    "-cp",
                    System.getProperty("java.class.path"),
                    DuckDbOwnerPathRaceChild::class.java.name,
                ) + args,
            ).redirectErrorStream(true).start().also { children += it }
        private val lines = LinkedBlockingQueue<String>()
        private val seen = mutableListOf<String>()

        init {
            thread(isDaemon = true) { process.inputStream.bufferedReader().forEachLine(lines::put) }
        }

        fun send(line: String) {
            process.outputStream.write("$line\n".toByteArray())
            process.outputStream.flush()
        }

        fun expect(prefix: String): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                val line = lines.poll(1, TimeUnit.SECONDS) ?: continue
                seen += line
                if (line.startsWith(prefix)) return line
            }
            error("child did not print $prefix; output=$seen")
        }
    }
}

/** Two real JVMs keep the JVM-local held-owner registry out of the cross-process schedule. */
object DuckDbOwnerPathRaceChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[1])
        when (args[0]) {
            "owner" -> hold(dir)
            "probe" -> probe(dir, UUID.fromString(args[2]))
            else -> error("unknown child mode")
        }
    }

    private fun say(line: String) {
        println(line)
        System.out.flush()
    }

    private fun hold(dir: Path) {
        val owner = DuckDbScratchOwnership.newOwner()
        var first = true
        DuckDbScratchOwnership
            .lockOwnerFile(dir, owner) {
                if (first) {
                    first = false
                    say("OWNER_OPEN $owner")
                    check(readln() == "continue")
                }
            }.use {
                val files = DuckDbStagingFiles(dir, UUID.randomUUID())
                DuckDbScratchOwnership.writeMarker(files, owner)
                serveDatabase(files)
            }
    }

    private fun serveDatabase(files: DuckDbStagingFiles) {
        DriverManager.getConnection("jdbc:duckdb:${files.database}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t AS SELECT 1 AS id")
                say("OWNER_HELD ${files.executionId}")
                while (readlnOrNull() == "write") {
                    writeAndReport(statement)
                }
            }
        }
    }

    private fun writeAndReport(statement: Statement) {
        statement.execute("INSERT INTO t VALUES (2)")
        statement.executeQuery("SELECT count(*) FROM t").use { rows ->
            rows.next()
            say("WROTE ${rows.getLong(1)}")
        }
    }

    private fun probe(
        dir: Path,
        owner: UUID,
    ) {
        val state =
            DuckDbScratchOwnership.probe(dir, owner) {
                say("PROBE_OPEN")
                check(readln() == "continue")
            }
        if (state is DuckDbScratchOwnership.OwnerState.Dead) state.proof.use { it.deleteOwnerFile() }
        say("PROBE_DONE ${state.javaClass.simpleName}")
    }
}
