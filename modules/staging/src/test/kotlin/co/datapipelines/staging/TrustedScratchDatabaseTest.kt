package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * C16 (brief 969): the trusted scratch database ([StagingPreparation.openTrusted], staging.md §8.5) on real DuckDB.
 * (a)'s object-store read is `ManagedLakeCompactionE2eTest`'s; here external access is proven by a local Parquet
 * read the author database of the same factory refuses.
 */
class TrustedScratchDatabaseTest {
    @TempDir
    lateinit var scratch: Path

    @TempDir
    lateinit var outside: Path

    @Test
    fun `C16 a opened from a preparation - spill beside the file, the reservation, the memory limit, locked, external access on`() {
        val factory = duckFactory(scratch)
        val parquet = outside.resolve("probe.parquet")
        val id = UUID.randomUUID()
        factory.prepareDelivery(id, RESERVATION).openTrusted(TrustedStagingInitializer.NONE).use { db ->
            db.withRoot { c ->
                // External access on: the engine writes and reads a file outside its scratch directory.
                c.exec("COPY (SELECT 7 AS v) TO '$parquet' (FORMAT parquet)")
                c.text("SELECT v FROM read_parquet('$parquet')") shouldBe "7"
                c.text("SELECT current_setting('temp_directory')") shouldBe DuckDbStagingFiles(scratch, id).tempDir.toString()
                c.text("SELECT current_setting('max_temp_directory_size')") shouldBe "64.0 MiB"
                c.text("SELECT current_setting('memory_limit')") shouldBe "256.0 MiB"
                c.text("SELECT current_setting('lock_configuration')") shouldBe "true"
                c.text("SELECT current_setting('enable_external_access')") shouldBe "true"
                shouldThrow<SQLException> { c.exec("SET threads = 1") }.message shouldContain "locked"
            }
        }
        // The author database of the same factory is unchanged: external access off, read back, the read refused.
        val author = factory.open()
        try {
            author.scalarText("SELECT current_setting('enable_external_access')") shouldBe "false"
            author.scalarText("SELECT current_setting('lock_configuration')") shouldBe "true"
            shouldThrow<SQLException> { author.scalarText("SELECT v FROM read_parquet('$parquet')") }
        } finally {
            author.close()
        }
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `C16 b a preparation opens once - open after openTrusted and the reverse are refused`() {
        val factory = duckFactory(scratch)
        val first = factory.prepareDelivery(UUID.randomUUID(), RESERVATION)
        first.openTrusted(TrustedStagingInitializer.NONE).use {
            shouldThrow<IllegalStateException> { first.open() }
            shouldThrow<IllegalStateException> { first.openTrusted(TrustedStagingInitializer.NONE) }
        }
        val second = factory.prepareDelivery(UUID.randomUUID(), RESERVATION)
        second.open().close()
        shouldThrow<IllegalStateException> { second.openTrusted(TrustedStagingInitializer.NONE) }
        val closed = factory.prepareDelivery(UUID.randomUUID(), RESERVATION)
        closed.close()
        shouldThrow<IllegalStateException> { closed.openTrusted(TrustedStagingInitializer.NONE) }
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `C16 c an initializer that fails leaves as creation_failed with the files gone and the reservation released`() {
        val factory = duckFactory(scratch)
        val id = UUID.randomUUID()
        val preparation = factory.prepareDelivery(id, RESERVATION)
        val released = AtomicInteger()
        preparation.afterRelease { released.incrementAndGet() }
        val failure =
            shouldThrow<DatapipelinesException> {
                preparation.openTrusted { throw SQLException("TRUSTED_SECRET_SENTINEL_969") }
            }
        failure.code shouldBe StagingErrorCodes.CREATION_FAILED
        failure.message.orEmpty().contains("TRUSTED_SECRET_SENTINEL_969") shouldBe false
        factory.budget.reservedBytes shouldBe 0L
        released.get() shouldBe 1
        pathsOf(scratch, id).shouldBeEmpty()
        Files.exists(DuckDbStagingFiles(scratch, id).marker) shouldBe false
    }

    @Test
    fun `C16 d close after a body that returned - files and marker gone, budget back, release once, second close nothing`() {
        closeCase { db -> db.withRoot { c -> c.exec("CREATE TABLE t AS SELECT range AS v FROM range(1000)") } }
    }

    @Test
    fun `C16 d close after a body that threw`() {
        closeCase { db -> shouldThrow<IllegalStateException> { db.withRoot<Unit> { error("the body failed") } } }
    }

    @Test
    fun `C16 d close with a cursor still open`() {
        closeCase { db ->
            val open = db.withRoot { c -> c.createStatement().executeQuery("SELECT range FROM range(100000)").also { it.next() } }
            open.isClosed shouldBe false
        }
    }

    @Test
    fun `C16 d a closed database runs no body`() {
        val db = duckFactory(scratch).prepareDelivery(UUID.randomUUID(), RESERVATION).openTrusted(TrustedStagingInitializer.NONE)
        db.close()
        shouldThrow<IllegalStateException> { db.withRoot { } }
    }

    @Test
    fun `C16 e a real spill past the reservation inside a body leaves as the scratch-limit exception`() {
        val factory = duckFactory(scratch, memoryLimitBytes = SPILL_MEMORY)
        factory.prepareDelivery(UUID.randomUUID(), SPILL_RESERVATION).openTrusted(TrustedStagingInitializer.NONE).use { db ->
            val e = shouldThrow<StagingScratchLimitException> { db.withRoot { c -> c.exec("CREATE TABLE s AS $SPILLING_SORT") } }
            e.reservationBytes shouldBe SPILL_RESERVATION
            e.engineReading.shouldNotBeNull()
        }
        factory.budget.reservedBytes shouldBe 0L
    }

    /**
     * The pinned driver does not RAISE an engine error that happens after the first streamed chunks: `next()` answers
     * false and the cursor ends short (measured 2026-11-14, store `notes/evidence/969-lake-compaction/stream-error-probe/`).
     * So the read-time case is a real spill raised while the body holds a cursor and is reading it: the classification
     * is where the body leaves, not around one execute.
     */
    @Test
    fun `C16 e a spill raised while rows are read leaves as the scratch-limit exception`() {
        val factory = duckFactory(scratch, memoryLimitBytes = SPILL_MEMORY)
        val read = AtomicInteger()
        factory.prepareDelivery(UUID.randomUUID(), SPILL_RESERVATION).openTrusted(TrustedStagingInitializer.NONE).use { db ->
            shouldThrow<StagingScratchLimitException> {
                db.withRoot { c ->
                    c.createStatement().use { cursor ->
                        cursor.executeQuery("SELECT range AS v FROM range(10)").use { rs ->
                            while (rs.next()) {
                                read.incrementAndGet()
                                if (read.get() == SPILL_AT_ROW) c.exec("CREATE TABLE s AS $SPILLING_SORT")
                            }
                        }
                    }
                }
            }
        }
        read.get() shouldBe SPILL_AT_ROW
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `C16 e a SQLException that is not an engine limit leaves unchanged`() {
        duckFactory(scratch).prepareDelivery(UUID.randomUUID(), RESERVATION).openTrusted(TrustedStagingInitializer.NONE).use { db ->
            val thrown = SQLException("plain")
            shouldThrow<SQLException> { db.withRoot<Unit> { throw thrown } } shouldBeSameInstanceAs thrown
            val engine = shouldThrow<SQLException> { db.withRoot { c -> c.exec("SELECT * FROM no_such_table") } }
            engine.message.orEmpty() shouldContain "no_such_table"
        }
    }

    @Test
    fun `the files are measured after a body - past the reservation is the scratch-limit exception`() {
        val factory = duckFactory(scratch)
        factory.prepareDelivery(UUID.randomUUID(), MIB).openTrusted(TrustedStagingInitializer.NONE).use { db ->
            val e =
                shouldThrow<StagingScratchLimitException> {
                    db.withRoot { c ->
                        c.exec("CREATE TABLE big AS SELECT range AS v, md5(range::VARCHAR) AS h FROM range(200000)")
                        c.exec("CHECKPOINT")
                    }
                }
            requireNotNull(e.measuredBytes) shouldBeGreaterThan MIB
        }
    }

    private fun closeCase(use: (TrustedScratchDatabase) -> Unit) {
        val factory = duckFactory(scratch)
        val id = UUID.randomUUID()
        val preparation = factory.prepareDelivery(id, RESERVATION)
        val released = AtomicInteger()
        preparation.afterRelease { released.incrementAndGet() }
        val db = preparation.openTrusted(TrustedStagingInitializer.NONE)
        factory.budget.reservedBytes shouldBe RESERVATION
        use(db)
        db.close()
        pathsOf(scratch, id).shouldBeEmpty()
        Files.exists(DuckDbStagingFiles(scratch, id).marker) shouldBe false
        factory.budget.reservedBytes shouldBe 0L
        released.get() shouldBe 1
        db.close()
        preparation.close()
        released.get() shouldBe 1
        factory.budget.reservedBytes shouldBe 0L
    }

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.text(sql: String): String? = createStatement().use { st -> st.executeQuery(sql).use(::first) }

    private fun first(rs: ResultSet): String? = if (rs.next()) rs.getString(1) else null

    private companion object {
        const val RESERVATION = 64 * MIB
        const val SPILL_MEMORY = 64 * MIB
        const val SPILL_RESERVATION = 8 * MIB
        const val SPILL_AT_ROW = 3
        const val SPILLING_SORT = "SELECT range AS v, hash(range) AS k FROM range(10000000) ORDER BY k"
    }
}
