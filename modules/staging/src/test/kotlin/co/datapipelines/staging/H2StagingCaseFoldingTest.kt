package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * The identifier-folding contract of §11.3: **unquoted author SQL resolves against the staged,
 * quoted-lowercase tables**.
 *
 * Staging creates every object double-quoted (§4.5) and table names are lowercase by contract
 * (§4.1), so a staged table is stored as `stg_orders`. `MODE=PostgreSQL` alone does *not* make
 * H2 fold unquoted identifiers to lower case — H2 keeps upper-folding regardless of mode — so
 * `SELECT n FROM stg_orders` resolved as `STG_ORDERS` and failed with SQLState 42S03 against
 * every staged table. That is the canonical author style in dag-executor.md §6.5,
 * pipeline-contract.md §10.1 and templates.md §11: the multi-node pipeline was broken end to
 * end, and §11.3's "table names are lowercase by contract, so unquoted references to them work"
 * was false for the shipped URL. `DATABASE_TO_LOWER=TRUE` in [H2StagingFactory]'s URL is the fix.
 *
 * **This test can fail.** [the same unquoted SQL fails without the folding parameter] runs the
 * identical statements against a database opened with the *old* URL and shows them failing
 * 42S03 — so a revert of the factory parameter turns the assertions below into that control's
 * behavior rather than passing for some unrelated reason.
 */
class H2StagingCaseFoldingTest {
    private val props = H2StagingProperties()
    private val executionId = UUID.randomUUID()
    private val staging = H2StagingFactory(props).create(executionId)

    @AfterEach
    fun tearDown() = staging.close()

    /**
     * Stages `stg_orders(n, region)` with **lowercase** labels — what a Postgres source yields,
     * and what §11.3 tells template authors to alias to.
     */
    private fun stageOrders() {
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INTEGER, region VARCHAR(10))")
            src.exec("INSERT INTO t VALUES (1, 'emea'), (2, 'apac'), (3, 'emea')")
            runBlocking {
                staging.stage(src.query("SELECT id AS \"n\", region AS \"region\" FROM t"), "stg_orders", Dialect.H2)
            }
        }
    }

    @Test
    fun `unquoted author SQL reads a staged quoted-lowercase table`() {
        stageOrders()

        // The exact defect shape, through the author's own read entry point (§3.3).
        val total =
            runBlocking {
                staging.withQuery("SELECT SUM(n) FROM stg_orders") { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }

        total shouldBe 6L
    }

    @Test
    fun `unquoted author SQL selects, filters and orders by an unquoted staged column`() {
        stageOrders()

        val emea =
            runBlocking {
                staging.withQuery("SELECT n FROM stg_orders WHERE region = 'emea' ORDER BY n") { rows ->
                    buildList { while (rows.next()) add(rows.getInt(1)) }
                }
            }

        emea shouldBe listOf(1, 3)
    }

    @Test
    fun `an unquoted multi-node shape — CREATE TABLE AS over a staged table, then read back`() {
        stageOrders()

        // What a `tempdb`→`tempdb` SQL node really issues: unquoted everywhere, both sides.
        runBlocking {
            staging.execute("CREATE TABLE int_revenue AS SELECT region, SUM(n) AS total FROM stg_orders GROUP BY region")
        }

        val emeaTotal =
            runBlocking {
                staging.withQuery("SELECT total FROM int_revenue WHERE region = 'emea'") { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }

        emeaTotal shouldBe 4L
        // Both tables are visible to §10's catalog projection — and the catalog's own schemas are
        // still excluded from it now that they are lower-cased too (UPPER-guarded in H2Staging;
        // without that guard this reads 36 on the pinned driver, not 2).
        runBlocking { staging.stats() }.tableCount shouldBe 2
    }

    @Test
    fun `an unquoted INSERT and DELETE through execute hit the staged table`() {
        stageOrders()

        runBlocking { staging.execute("INSERT INTO stg_orders (n, region) VALUES (4, 'amer')") } shouldBe 1L
        runBlocking { staging.execute("DELETE FROM stg_orders WHERE n > 3") } shouldBe 1L
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM stg_orders") } shouldBe 3L
    }

    @Test
    fun `a mixed-case staged column still needs quoting, exactly as section 4-2 says`() {
        // Folding does not make staging case-insensitive: §4.2/§4.5 keep a mixed-case source
        // alias exact under quotes, and §11.3 tells the author to quote it downstream. Pinned so
        // the fix is not mistaken for "case no longer matters anywhere".
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INTEGER)")
            src.exec("INSERT INTO t VALUES (7)")
            runBlocking { staging.stage(src.query("SELECT id AS \"OrderId\" FROM t"), "stg_case", Dialect.H2) }
        }

        staging.readFromStaging { scalarLong(it, "SELECT \"OrderId\" FROM stg_case") } shouldBe 7L

        val thrown =
            shouldThrow<SQLException> {
                runBlocking { staging.withQuery("SELECT OrderId FROM stg_case") { it.next() } }
            }
        thrown.sqlState shouldBe COLUMN_NOT_FOUND
    }

    @Test
    fun `the same unquoted SQL fails without the folding parameter, which is what makes this guard bite`() {
        // Guard the guard. Every assertion above would also pass if H2 had simply become
        // case-insensitive, or if the staged table were not really lowercase. This builds the
        // identical staged shape on the URL the factory used BEFORE the fix — MODE only — and
        // shows the author's unquoted SELECT failing 42S03. That failure is the production defect;
        // reverting the factory's DATABASE_TO_LOWER=TRUE reproduces it in every test above.
        val legacyUrl = "jdbc:h2:mem:cf_control_${UUID.randomUUID().toString().replace('-', '_')};MODE=${props.mode}"
        DriverManager.getConnection(legacyUrl, "sa", "").use { legacy ->
            legacy.createStatement().use { st ->
                st.execute("CREATE TABLE \"stg_orders\" (\"n\" INTEGER)")
                st.execute("INSERT INTO \"stg_orders\" (\"n\") VALUES (1)")
            }

            val thrown =
                shouldThrow<SQLException> {
                    legacy.createStatement().use { it.executeQuery("SELECT n FROM stg_orders") }
                }
            thrown.sqlState shouldBe TABLE_NOT_FOUND
        }

        // …and the very same statement succeeds on the staging instance under test.
        stageOrders()
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM stg_orders") } shouldBe 3L
    }

    private companion object {
        /** H2 2.3.232's SQLState for "table not found" — the shape the shipped URL produced. */
        const val TABLE_NOT_FOUND = "42S03"

        /** H2 2.3.232's SQLState for "column not found". */
        const val COLUMN_NOT_FOUND = "42S22"
    }
}
