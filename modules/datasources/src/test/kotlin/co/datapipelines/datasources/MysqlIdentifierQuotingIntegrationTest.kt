package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.SQLException

/**
 * `IdentifierQuoteStyle.BACKTICK` against a **real MySQL**, no `ANSI_QUOTES` — the evidence
 * behind the 2026-09-07 contract audit's §c defect (round 087 §D).
 *
 * ## Why this test exists in this module
 *
 * The defect is in the executor: `SqlIdentifiers.quote` produced `"…"` for every dialect, so a
 * write-back to MySQL emitted `INSERT INTO "orders" ("order") VALUES (?)`. The FIX routes through
 * `DialectAdapter.quoteIdentifier`, and `ExecutorPrimitivesTest` pins that routing per dialect as
 * a string comparison — which is the guard that goes red on a revert.
 *
 * What a string comparison cannot show is that the two spellings are not interchangeable on the
 * engine. This does, in both directions, against the pinned Connector/J and a stock MySQL image
 * whose `sql_mode` nobody touched:
 *
 *  - the backtick form the adapter produces **succeeds**;
 *  - the double-quote form the executor produced before **fails**, because without `ANSI_QUOTES`
 *    MySQL reads `"orders"` as a string LITERAL, not an identifier.
 *
 * The second half is the point. An adapter's quote style is a claim about a server the adapter
 * cannot inspect, and a claim about `sql_mode` is exactly the kind that is right in the author's
 * head and wrong in production. The reserved word `order` is not a contrived case: quoting exists
 * for it, so it is the first name a customer hits.
 *
 * It lives here rather than in `dag` because this module already has the MySQL container AND the
 * driver on its test runtime classpath (`testRuntimeOnly(libs.mysql.connector.j)` — the product
 * driver needs `-Pmysql`, the test one does not), and round 087's fence permits `modules/dag`
 * only for `SqlIdentifiers` and its call sites.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MysqlIdentifierQuotingIntegrationTest {
    @Test
    fun `the adapter's backticks are accepted and the pre-087 double quotes are REFUSED`() {
        val adapter = DialectAdapters.forDialect(Dialect.MYSQL)
        // The vocabulary itself, first: the fix is only meaningful if these differ.
        adapter.quoteIdentifier("order") shouldBe "`order`"
        adapter.quoteIdentifier("orders") shouldBe "`orders`"

        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { connection ->
            // The server's own mode, untouched — the fact the whole defect turns on.
            val sqlMode =
                connection.createStatement().use { st ->
                    st.executeQuery("SELECT @@sql_mode").use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            withClue("this MySQL runs with ANSI_QUOTES, so it cannot falsify anything") {
                sqlMode.contains("ANSI_QUOTES") shouldBe false
            }

            connection.createStatement().use {
                it.execute("CREATE TABLE ${adapter.quoteIdentifier("orders")} (${adapter.quoteIdentifier("order")} INT, id INT)")
            }

            // GREEN: what the executor emits after 087.
            val insert =
                "INSERT INTO ${adapter.quoteIdentifier("orders")} " +
                    "(${adapter.quoteIdentifier("order")}, ${adapter.quoteIdentifier("id")}) VALUES (?, ?)"
            connection.prepareStatement(insert).use { ps ->
                ps.setInt(1, 1)
                ps.setInt(2, 2)
                ps.executeUpdate() shouldBe 1
            }

            // RED: what it emitted before. `"orders"` is a string literal here, not a table.
            val thrown =
                shouldThrow<SQLException> {
                    connection.prepareStatement("""INSERT INTO "orders" ("order", "id") VALUES (?, ?)""").use { ps ->
                        ps.setInt(1, 1)
                        ps.setInt(2, 2)
                        ps.executeUpdate()
                    }
                }
            thrown.message.orEmpty().lowercase() shouldContain "syntax"
        }
    }

    private companion object {
        /**
         * The same image [DialectConnectivityIntegrationTest] uses. `sql_mode` is deliberately
         * NOT configured: the defect is about what a stock server does.
         */
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.4").apply { start() }
    }
}
