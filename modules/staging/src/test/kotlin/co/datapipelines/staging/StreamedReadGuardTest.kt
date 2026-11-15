package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.sql.SQLException

/**
 * #994 rule G2 at staging's own reads (TEMPORARY, [StreamedReadGuard]): every cursor `withQuery` hands out is judged
 * against the engine's `count(*)` of the same SQL in the same snapshot, and the one-row reads fail on a missing row.
 * The failing read is a REAL engine failure raised after the first streamed chunk — a view whose expression calls
 * `error()` from row [FAIL_AT] on — which the pinned driver ends silently (StreamedResultTripwireTest pins that).
 */
class StreamedReadGuardTest {
    @TempDir
    lateinit var scratch: Path

    @Test
    fun `withQuery fails as result_incomplete when the engine fails mid-stream, and the block saw a short read`() {
        duckFactory(scratch).open().use { staging ->
            runBlocking { staging.execute(FAILING_VIEW) }
            var taken = 0L
            val failure =
                shouldThrow<StagingResultIncompleteException> {
                    runBlocking {
                        staging.withQuery("SELECT v FROM boom") { rs ->
                            while (rs.next()) taken++
                        }
                    }
                }
            withClue("the rows the block saw before the end (non-vacuity: the failure was mid-stream, not at execute)") {
                (taken > 0L) shouldBe true
                taken shouldBeLessThan FAIL_AT + 1
            }
            failure.code shouldBe StagingErrorCodes.RESULT_INCOMPLETE
            failure.rowsRead shouldBe taken
            failure.rowsExpected shouldBe ROWS
            failure.details shouldBe mapOf("rows_read" to taken, "rows_expected" to ROWS, "reader" to "withQuery")
            withClue("no SQL text and no row value in the message") {
                (failure.message!!.contains("boom") || failure.message!!.contains("SELECT")) shouldBe false
            }
        }
    }

    @ParameterizedTest(name = "{0} rows")
    @ValueSource(longs = [0L, 1L, 2_048L, 2_049L, 300_000L])
    fun `a clean read of every size class passes unchanged through withQuery`(rows: Long) {
        duckFactory(scratch).open().use { staging ->
            runBlocking { staging.execute("""CREATE TABLE "t" AS SELECT range AS v FROM range($rows)""") }
            val (count, sum) =
                runBlocking {
                    staging.withQuery("""SELECT v FROM "t"""") { rs ->
                        var n = 0L
                        var s = 0L
                        while (rs.next()) {
                            n++
                            s += rs.getLong(1)
                        }
                        n to s
                    }
                }
            count shouldBe rows
            sum shouldBe (0 until rows).sum()
        }
    }

    @Test
    fun `a LIMIT read is judged against min(n, count), and a reader that stops early is not judged`() {
        duckFactory(scratch).open().use { staging ->
            runBlocking { staging.execute("""CREATE TABLE "t" AS SELECT range AS v FROM range(25)""") }
            val limited =
                runBlocking {
                    staging.withQuery(
                        """SELECT v FROM "t" LIMIT 10""",
                    ) { rs -> generateSequence { if (rs.next()) 1 else null }.count() }
                }
            limited shouldBe 10
            val short =
                runBlocking {
                    staging.withQuery(
                        """SELECT v FROM "t" LIMIT 100""",
                    ) { rs -> generateSequence { if (rs.next()) 1 else null }.count() }
                }
            short shouldBe 25
            val firstOnly = runBlocking { staging.withQuery("""SELECT v FROM "t"""") { rs -> rs.next().also { rs.close() } } }
            firstOnly shouldBe true
        }
    }

    @Test
    fun `a metadata-only read (LIMIT 0) keeps its metadata and returns no row`() {
        duckFactory(scratch).open().use { staging ->
            runBlocking { staging.execute("""CREATE TABLE "t" (a INTEGER, b DECIMAL(12,3), c TIMESTAMP)""") }
            val described =
                runBlocking {
                    staging.withQuery("""SELECT a, b AS "B label", c FROM "t" LIMIT 0""") { rs ->
                        val md = rs.metaData
                        val columns =
                            (1..md.columnCount).map {
                                listOf(
                                    md.getColumnLabel(it),
                                    md.getColumnTypeName(it),
                                    md.getPrecision(it),
                                    md.getScale(it),
                                )
                            }
                        columns to rs.next()
                    }
                }
            described.first.map { it[0] } shouldBe listOf("a", "B label", "c")
            described.first[1][1] shouldBe "DECIMAL(12,3)"
            described.first[1][2] shouldBe 12
            described.first[1][3] shouldBe 3
            described.second shouldBe false
        }
    }

    @Test
    fun `a one-row read with no row fails as result_incomplete 0 of 1 and never reads as zero`() {
        duckFactory(scratch).open().use { staging ->
            val failure =
                shouldThrow<StagingResultIncompleteException> {
                    runBlocking {
                        staging.withConnection { c ->
                            c.createStatement().use { st ->
                                st.executeQuery("SELECT 42 WHERE false").use { rs ->
                                    StreamedReadGuard.oneRow(rs, "probe") { it.getInt(1) }
                                }
                            }
                        }
                    }
                }
            failure.details shouldBe mapOf("rows_read" to 0L, "rows_expected" to 1L, "reader" to "probe")
        }
    }

    @Test
    fun `a one-row read that has its row returns it, and one that fails at execution throws the engine's error`() {
        duckFactory(scratch).open().use { staging ->
            val value =
                runBlocking {
                    staging.withConnection { c ->
                        c.createStatement().use { st ->
                            st.executeQuery("SELECT 42").use { rs -> StreamedReadGuard.oneRow(rs, "probe") { it.getInt(1) } }
                        }
                    }
                }
            value shouldBe 42
            shouldThrow<SQLException> {
                runBlocking {
                    staging.withConnection { c ->
                        c.createStatement().use { st ->
                            st.executeQuery("SELECT error('at-execute')").use { rs ->
                                StreamedReadGuard.oneRow(rs, "probe") { it.getInt(1) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `stats - the one-row reads of countTables and memoryUsed - still read on a clean database`() {
        duckFactory(scratch).open().use { staging ->
            runBlocking { staging.execute("""CREATE TABLE "t" AS SELECT 1 AS v""") }
            runBlocking { staging.stats() }.tableCount shouldBe 1
        }
    }

    @Test
    fun `no false alarm when another connection writes between the count and the read`() {
        duckFactory(scratch).open().use { staging ->
            runBlocking { staging.execute("""CREATE TABLE "t" AS SELECT range AS v FROM range(3)""") }
            val read =
                runBlocking {
                    staging.withConnection { c ->
                        StreamedReadGuard.inOneSnapshot(c) {
                            val expected =
                                c.createStatement().use {
                                    StreamedReadGuard.expectedRows(
                                        it,
                                        """SELECT count(*) FROM "t"""",
                                        "latch",
                                    )
                                }
                            // The other connection's write commits between the count and the read.
                            staging.execute("""INSERT INTO "t" SELECT range FROM range(2)""")
                            c.createStatement().use { st ->
                                StreamedReadGuard.counted(st.executeQuery("""SELECT v FROM "t""""), expected, "latch").use { rs ->
                                    generateSequence { if (rs.next()) 1 else null }.count()
                                }
                            }
                        }
                    }
                }
            withClue("the read sees the count's snapshot") { read shouldBe 3 }
            withClue("non-vacuity: the other connection's rows were committed") {
                staging.scalarText("""SELECT count(*) FROM "t"""") shouldBe "5"
            }
        }
    }

    @Test
    fun `the snapshot restores auto-commit on success and on failure, and leaves a caller's transaction alone`() {
        duckFactory(scratch).open().use { staging ->
            runBlocking { staging.execute("""CREATE TABLE "t" AS SELECT range AS v FROM range(3)""") }
            runBlocking {
                staging.withConnection { c ->
                    StreamedReadGuard.inOneSnapshot(c) { c.autoCommit shouldBe false }
                    c.autoCommit shouldBe true
                    shouldThrow<IllegalStateException> { StreamedReadGuard.inOneSnapshot(c) { error("reader failed") } }
                    c.autoCommit shouldBe true

                    c.autoCommit = false
                    c.createStatement().use { it.execute("""INSERT INTO "t" VALUES (99)""") }
                    StreamedReadGuard.inOneSnapshot(c) {
                        c.createStatement().use { StreamedReadGuard.expectedRows(it, """SELECT count(*) FROM "t"""", "tx") } shouldBe 4
                    }
                    withClue("the guard neither committed nor ended the caller's transaction") { c.autoCommit shouldBe false }
                    c.rollback()
                    c.autoCommit = true
                    c.createStatement().use { StreamedReadGuard.expectedRows(it, """SELECT count(*) FROM "t"""", "tx") } shouldBe 3
                }
            }
        }
    }

    private companion object {
        const val ROWS = 400_000L
        const val FAIL_AT = 300_000L
        const val FAILING_VIEW =
            "CREATE VIEW boom AS SELECT CASE WHEN range < $FAIL_AT THEN range ELSE error('boom') END AS v FROM range($ROWS)"
    }
}
