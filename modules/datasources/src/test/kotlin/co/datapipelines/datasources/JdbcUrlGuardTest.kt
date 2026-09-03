package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [JdbcUrlGuard] — the §5.6 URL-carrier refusal guard, attacked directly.
 *
 * [DialectRefusalSetsTest] pins the refusal SETS' contents and their two-carrier parity;
 * nothing pins the guard's own PARSING: the `?`/`&`/`;` tokenization, the case-insensitive
 * key match (`Socketfactory` must not slip past), the credential-authority detection across
 * the per-driver URL forms, and the suffix predicate applied to the URL carrier. A parsing
 * regression here is a silent RCE/SSRF hole — every dialect's vectors below are the ones
 * §5.6 exists for. The authority table is lifted verbatim from the guard's KDoc so the
 * test and the documented contract cannot drift apart unnoticed.
 */
class JdbcUrlGuardTest {
    private val pgKeys = RefusedPropertyKeys.forDialect(Dialect.POSTGRES)
    private val h2Keys = RefusedPropertyKeys.forDialect(Dialect.H2)
    private val mssqlKeys = RefusedPropertyKeys.forDialect(Dialect.MSSQL)
    private val mysqlKeys = RefusedPropertyKeys.forDialect(Dialect.MYSQL)
    private val duckKeys = RefusedPropertyKeys.forDialect(Dialect.DUCKDB)
    private val sqliteKeys = RefusedPropertyKeys.forDialect(Dialect.SQLITE)
    private val oracleKeys = RefusedPropertyKeys.forDialect(Dialect.ORACLE)

    // ------------------------------------------------------------ scheme and shape

    @Test
    fun `a plain valid URL passes with no errors`() {
        JdbcUrlGuard.validate("jdbc:postgresql://db.internal:5432/prod", "postgresql", pgKeys).valid shouldBe true
    }

    @Test
    fun `legitimate properties ride along untouched`() {
        // sslrootcert and sslmode are §5's supported TLS knobs — deliberately NOT refused.
        JdbcUrlGuard
            .validate("jdbc:postgresql://h/db?sslmode=require&sslrootcert=/ca.pem&connectTimeout=10", "postgresql", pgKeys)
            .valid shouldBe true
    }

    @Test
    fun `the wrong sub-protocol is a scheme error not a refusal`() {
        val result = JdbcUrlGuard.validate("jdbc:mysql://h/db", "postgresql", pgKeys)
        result.valid shouldBe false
        result.errors.single().code shouldBe DatasourceErrorCodes.JDBC_URL_SCHEME_INVALID
    }

    @Test
    fun `scheme match is case-insensitive and surrounding whitespace is trimmed`() {
        JdbcUrlGuard.validate("  JDBC:PostgreSQL://h/db  ", "postgresql", pgKeys).valid shouldBe true
    }

    @Test
    fun `an empty sub-name is malformed`() {
        val result = JdbcUrlGuard.validate("jdbc:postgresql:", "postgresql", pgKeys)
        result.valid shouldBe false
        result.errors.single().code shouldBe DatasourceErrorCodes.JDBC_URL_MALFORMED
    }

    // ------------------------------------------------------------ the refusal scan, per carrier syntax

    @Test
    fun `the canonical postgres vector is refused - socketFactory after a question mark`() {
        val error = JdbcUrlGuard.refusalErrors("jdbc:postgresql://h/db?socketFactory=org.evil.Sf", pgKeys)
        error shouldNotBe null
        error?.code shouldBe DatasourceErrorCodes.JDBC_URL_MALFORMED
        error?.message shouldContain "socketfactory"
    }

    @Test
    fun `case variants do not slip past - the KDoc's Socketfactory case`() {
        JdbcUrlGuard.refusalErrors("jdbc:postgresql://h/db?Socketfactory=org.evil.Sf", pgKeys) shouldNotBe null
        JdbcUrlGuard.refusalErrors("jdbc:postgresql://h/db?SOCKETFACTORY=org.evil.Sf", pgKeys) shouldNotBe null
        JdbcUrlGuard.refusalErrors("jdbc:postgresql://h/db?ssLFactorY=org.evil.Sf", pgKeys) shouldNotBe null
    }

    @Test
    fun `an ampersand-separated later token is still scanned`() {
        JdbcUrlGuard.refusalErrors("jdbc:postgresql://h/db?sslmode=require&socketFactory=org.evil.Sf", pgKeys) shouldNotBe null
    }

    @Test
    fun `the semicolon separator carries h2 INIT - connect-time arbitrary SQL`() {
        JdbcUrlGuard.refusalErrors("jdbc:h2:mem:evil;INIT=RUNSCRIPT FROM '/tmp/x.sql'", h2Keys) shouldNotBe null
    }

    @Test
    fun `the semicolon separator carries mssql trustServerCertificate`() {
        JdbcUrlGuard.refusalErrors("jdbc:sqlserver://h;encrypt=true;trustServerCertificate=true", mssqlKeys) shouldNotBe null
    }

    @Test
    fun `duckdb's session-init SQL file is refused on both separators`() {
        JdbcUrlGuard.refusalErrors("jdbc:duckdb:db?session_init_sql_file=/etc/x.sql", duckKeys) shouldNotBe null
        JdbcUrlGuard.refusalErrors("jdbc:duckdb:db;session_init_sql_file=/etc/x.sql", duckKeys) shouldNotBe null
    }

    @Test
    fun `mysql's deserialization and local-infile vectors are refused`() {
        JdbcUrlGuard.refusalErrors("jdbc:mysql://h/db?autoDeserialize=true", mysqlKeys) shouldNotBe null
        JdbcUrlGuard.refusalErrors("jdbc:mysql://h/db?allowLoadLocalInfile=true", mysqlKeys) shouldNotBe null
    }

    @Test
    fun `sqlite's extension loading is refused`() {
        JdbcUrlGuard.refusalErrors("jdbc:sqlite:/data/x.db?enable_load_extension=1", sqliteKeys) shouldNotBe null
    }

    @Test
    fun `oracle's wallet path is refused`() {
        JdbcUrlGuard.refusalErrors("jdbc:oracle:thin:@//h:1521/svc?oracle.net.wallet_location=/wallet", oracleKeys) shouldNotBe null
    }

    @Test
    fun `mixed separators in one URL are all tokenized`() {
        // H2 accepts `;` properties AND the guard must not stop at the first separator kind.
        JdbcUrlGuard.refusalErrors("jdbc:h2:tcp://h/db;LOCK_TIMEOUT=2000;INIT=CREATE ALIAS x", h2Keys) shouldNotBe null
    }

    // ------------------------------------------------------------ credentials

    @Test
    fun `user and password properties are refused in the URL carrier`() {
        JdbcUrlGuard.refusalErrors("jdbc:postgresql://h/db?user=admin", pgKeys) shouldNotBe null
        JdbcUrlGuard.refusalErrors("jdbc:postgresql://h/db?password=sekrit", pgKeys) shouldNotBe null
    }

    @Test
    fun `the suffix predicate reaches the URL carrier - both carriers identical`() {
        // `myproxyclientkey` is in NO enumerated set; it is refused purely because its value
        // is credential material (DS-SEC-14). If this passed the URL but not properties.jdbc,
        // the two carriers would have drifted — the exact property §5.6 refuses to accept.
        JdbcUrlGuard.refusalErrors("jdbc:postgresql://h/db?myproxyclientkey=abc123", pgKeys) shouldNotBe null
    }

    @Test
    fun `the credential-authority table from the KDoc - refused forms`() {
        withClue("pg //user:pw@") {
            JdbcUrlGuard.refusalErrors("jdbc:postgresql://admin:pw@host/db", pgKeys) shouldNotBe null
        }
        withClue("h2 tcp://user:pw@") {
            JdbcUrlGuard.refusalErrors("jdbc:h2:tcp://user:pw@host/db", h2Keys) shouldNotBe null
        }
        withClue("oracle thin:scott/tiger@") {
            JdbcUrlGuard.refusalErrors("jdbc:oracle:thin:scott/tiger@//host:1521/svc", oracleKeys) shouldNotBe null
        }
    }

    @Test
    fun `the credential-authority table from the KDoc - allowed forms`() {
        withClue("oracle empty userinfo") {
            JdbcUrlGuard.refusalErrors("jdbc:oracle:thin:@//host:1521/svc", oracleKeys) shouldBe null
        }
        withClue("duckdb bare path with at-sign filename") {
            JdbcUrlGuard.refusalErrors("jdbc:duckdb:/var/lib/a@b.db", duckKeys) shouldBe null
        }
        withClue("windows drive-style path with at-sign") {
            JdbcUrlGuard.refusalErrors("jdbc:sqlite:C:/data/a@b.db", sqliteKeys) shouldBe null
        }
        withClue("at-sign inside a property VALUE is not an authority") {
            JdbcUrlGuard.refusalErrors("jdbc:postgresql://h/db?ApplicationName=a@b", pgKeys) shouldBe null
        }
    }

    // ------------------------------------------------------------ tokenization details

    @Test
    fun `propertyKeys reads keys after every separator kind, lowercased and trimmed`() {
        JdbcUrlGuard.propertyKeys("//h/db? One =1&two=2;three=3") shouldBe setOf("one", "two", "three")
    }

    @Test
    fun `tokens without an equals sign or with an empty key are ignored`() {
        JdbcUrlGuard.propertyKeys("//h/db?flag&=value&ok=1") shouldBe setOf("ok")
    }

    @Test
    fun `subNameOf strips the scheme when present and passes a bare sub-name through`() {
        JdbcUrlGuard.subNameOf("jdbc:postgresql://h/db") shouldBe "//h/db"
        JdbcUrlGuard.subNameOf("//h/db") shouldBe "//h/db"
        JdbcUrlGuard.subNameOf("jdbc:nomatch") shouldBe "nomatch"
    }

    @Test
    fun `a token whose key smuggles whitespace is still caught`() {
        // `? socketFactory =x` — trim before lowercase means whitespace smuggling fails too.
        JdbcUrlGuard.refusalErrors("jdbc:postgresql://h/db? socketFactory =org.evil.Sf", pgKeys) shouldNotBe null
    }

    @Test
    fun `validate with a clean URL carries no errors`() {
        JdbcUrlGuard.validate("jdbc:h2:mem:test", "h2", h2Keys).errors.shouldBeEmpty()
    }

    @Test
    fun `every dialect's union is non-empty - the fail-closed floor`() {
        // §5.6 fail-closed: no dialect can yield an empty union, because the lookup never
        // consults the adapter. If any of these goes empty, a whole dialect's URL carrier
        // becomes unguarded.
        listOf(pgKeys, h2Keys, mssqlKeys, mysqlKeys, duckKeys, sqliteKeys, oracleKeys).forEach { keys ->
            keys shouldNotBe emptySet<String>()
        }
    }
}
