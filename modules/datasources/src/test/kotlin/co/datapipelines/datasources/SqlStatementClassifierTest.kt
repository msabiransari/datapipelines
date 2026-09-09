package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * The §7D gate, exercised as a pure function over every shape the KDoc promises — accepts,
 * refusals, and the conservative false positive (a column named `merge`) that is pinned as
 * accepted behavior rather than fixed.
 */
class SqlStatementClassifierTest {
    private fun classify(
        sql: String,
        dialect: Dialect = Dialect.POSTGRES,
    ) = SqlStatementClassifier.classify(sql, dialect)

    @Test
    fun `a plain SELECT passes, and WITH-SELECT passes`() {
        classify("SELECT * FROM trips")
        classify("WITH x AS (SELECT 1 AS one) SELECT * FROM x")
    }

    @Test
    fun `a single trailing semicolon is accepted and stripped from the running text`() {
        classify("SELECT * FROM trips;") shouldBe "SELECT * FROM trips"
        classify("  SELECT 1 ;  ") shouldBe "SELECT 1"
    }

    @Test
    fun `denylist words inside strings and comments never trip the gate`() {
        classify("SELECT * FROM t WHERE note = 'delete this immediately'")
        classify("SELECT 1 -- DROP TABLE t happens here\n")
        classify("/* update the merger; truncate it */ SELECT 1")
        classify("SELECT * FROM t WHERE note = 'a; b' AND x = \"quoted;ident\"")
    }

    @Test
    fun `every write-shaped first keyword is refused`() {
        listOf(
            "INSERT INTO t VALUES (1)",
            "UPDATE t SET x = 1",
            "DELETE FROM t",
            "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN DELETE",
            "DROP TABLE t",
            "CREATE TABLE t (id INT)",
            "ALTER TABLE t ADD COLUMN x INT",
            "TRUNCATE TABLE t",
            "GRANT SELECT ON t TO r",
            "COPY t TO '/tmp/x'",
            "VACUUM t",
            "SET search_path = 'x'",
            "EXPLAIN SELECT 1",
            "ANALYZE t",
        ).forEach { sql ->
            shouldThrow<SqlProbeRefusalException> { classify(sql) }
        }
    }

    @Test
    fun `a second statement is refused, with or without a denylisted verb`() {
        shouldThrow<SqlProbeRefusalException> { classify("SELECT 1; SELECT 2") }
        shouldThrow<SqlProbeRefusalException> { classify("SELECT 1; DROP TABLE t;") }
    }

    @Test
    fun `a semicolon inside a string or comment is not a statement separator`() {
        classify("SELECT ';' AS semi")
        classify("SELECT 1 -- trailing; comment\n")
        classify("SELECT 1 /* a; b */")
    }

    @Test
    fun `WITH wrapping a write is refused by the denylist, not the first keyword`() {
        val thrown = shouldThrow<SqlProbeRefusalException> { classify("WITH x AS (DELETE FROM t RETURNING *) SELECT 1") }

        thrown.keyword shouldBe "DELETE"
    }

    @Test
    fun `SELECT-INTO is refused - the one write that starts with SELECT`() {
        shouldThrow<SqlProbeRefusalException> { classify("SELECT * INTO archive FROM trips") }
    }

    @Test
    fun `SELECT FOR UPDATE is refused by the denylist`() {
        shouldThrow<SqlProbeRefusalException> { classify("SELECT * FROM trips FOR UPDATE") }
    }

    @Test
    fun `an empty statement is refused`() {
        shouldThrow<SqlProbeRefusalException> { classify("   -- nothing here\n") }
    }

    @Test
    fun `a column named like a denylist keyword trips the gate - the documented false positive`() {
        val thrown = shouldThrow<SqlProbeRefusalException> { classify("SELECT merge FROM t") }

        thrown.keyword shouldBe "MERGE"
    }

    @Test
    fun `the denial is a whole-word match - identifiers containing a keyword pass`() {
        classify("SELECT updated_at, inserts, deleted FROM t")
    }

    @Test
    fun `dollar-quoted strings are stripped on the dollar-quote dialects`() {
        // Without the strip, the denylist words inside the body would refuse a legal probe.
        classify("SELECT \$\$delete from x\$\$ AS body", Dialect.POSTGRES)
        classify("SELECT \$tag\$update t\$tag\$ AS body", Dialect.DUCKDB)
        classify("SELECT \$tag\$update t\$tag\$ AS body", Dialect.LAKE)
    }

    @Test
    fun `hash is a comment only on MySQL - elsewhere it hides nothing`() {
        // MySQL: the denylist word rides a comment and the gate passes.
        classify("SELECT 1 # drop everything\n", Dialect.MYSQL)
        // Postgres has no # comments: after the operator, `drop` is a real token and refuses —
        // the conservative direction (nothing the comment would hide can pass).
        shouldThrow<SqlProbeRefusalException> { classify("SELECT 1 # drop\n", Dialect.POSTGRES) }
    }

    @Test
    fun `MySQL backslash escapes keep a quote from closing the string early`() {
        classify("""SELECT * FROM t WHERE note = 'it\'s a delete'""", Dialect.MYSQL)
    }

    @Test
    fun `nested block comments are stripped whole`() {
        classify("SELECT 1 /* outer /* inner drop */ still comment */")
        // The distinguishing case: without nesting the comment would end at the FIRST `*/`,
        // exposing DELETE as a real token and refusing a legal probe.
        classify("SELECT 1 /* x /* y */ DELETE */")
    }

    @Test
    fun `the refusal message is static and never carries the SQL`() {
        val sql = "SELECT secret_col FROM hidden_table; DELETE FROM audit_log"
        val thrown = shouldThrow<SqlProbeRefusalException> { classify(sql) }

        assertAll(
            { thrown.message shouldBe "Only a single statement is probeable." },
            { thrown.message.orEmpty() shouldContain "single statement" },
        )
    }
}
