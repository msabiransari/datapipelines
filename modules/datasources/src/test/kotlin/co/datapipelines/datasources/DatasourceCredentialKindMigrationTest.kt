package co.datapipelines.datasources

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * `V13__datasource_credential_kind.sql` against REAL pre-migration rows (datasources.md §3.4,
 * metadata-db §4.10, round 087).
 *
 * [TemplateFolderGateMigrationTest][co.datapipelines.datasources]'s shape, one migration on: the
 * schema is built V1–V12 through [ShippedMigrations] (never a hand-copied list), rows are
 * inserted the way V12-era rows exist — `username NOT NULL`, `password_encrypted NOT NULL` — and
 * the V13 script is executed by plain JDBC exactly as Flyway would apply it.
 *
 * The migration's whole claim is that the backfill is TRUE rather than a guess: every pre-087 row
 * went through a save path that required a username and a password, so every one of them IS a
 * password. What that leaves to prove is (1) the rename carries the bytes across untouched,
 * (2) the DEFAULT reaches every existing row, and (3) the three CHECKs actually refuse the shapes
 * §3.4 forbids — because a constraint that cannot fail is not a constraint.
 *
 * The credential blob is deliberately NOT decrypted here: the encryptor has its own round-trip
 * suite, and what this test is about is the COLUMN — that `substring`-identical bytes arrive on
 * the other side of a rename under a new name.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasourceCredentialKindMigrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun createPreV13SchemaAndMigrate() {
        jdbc = NamedParameterJdbcTemplate(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        val dir = TestFiles.repoDirectory("modules/app/src/main/resources/db/migration")
        ShippedMigrations.migrations(dir).filter { it.first < V13 }.forEach { pair ->
            jdbc.jdbcTemplate.execute(pair.second.readText())
        }
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject)" +
                " VALUES (:id, 'credential-kind@example.com', 'Credential Kind', 'google', 'sub-credential-kind')",
            mapOf("id" to ACTOR_ID),
        )
        // Two V12-era rows, written the only way V12 allowed: a username and a NOT NULL blob.
        insertPreV13Row("pg_legacy", "app", byteArrayOf(1, 2, 3, 4))
        insertPreV13Row("sqlite_legacy", "sqlite", byteArrayOf(9, 9))

        jdbc.jdbcTemplate.execute(v13())
    }

    @Test
    fun `the rename carries every credential across, byte for byte`() {
        // The two rows' blobs, read back under the NEW column name. `encode(...,'hex')` rather
        // than a byte[] comparison so a mismatch reports what actually arrived.
        hexOf("pg_legacy") shouldBe "01020304"
        hexOf("sqlite_legacy") shouldBe "0909"
    }

    @Test
    fun `every pre-087 row is backfilled to kind password, and none of them is none`() {
        // Scoped to the two rows this suite seeded PRE-migration: the other tests insert
        // post-V13 rows into the same scratch database, and a bare GROUP BY would make this
        // assertion depend on execution order rather than on the backfill.
        val histogram =
            jdbc
                .queryForList(
                    "SELECT credential_kind, count(*) AS rows FROM datasources" +
                        " WHERE name IN ('pg_legacy', 'sqlite_legacy') GROUP BY 1",
                    emptyMap<String, Any>(),
                ).associate { row -> row["credential_kind"] as String to (row["rows"] as Number).toLong() }

        histogram shouldBe mapOf("password" to 2L)
    }

    @Test
    fun `a kind none row stores no username and no credential - the shape the demo files now use`() {
        insertPostV13Row(name = "file_db", kind = "none", username = null, credential = null)

        jdbc.queryForObject(
            "SELECT credential_encrypted IS NULL AND username IS NULL FROM datasources WHERE name = 'file_db'",
            emptyMap<String, Any>(),
            Boolean::class.java,
        ) shouldBe true
    }

    @Test
    fun `the present-check refuses kind none WITH a credential`() {
        val thrown =
            shouldThrow<DataIntegrityViolationException> {
                insertPostV13Row(name = "none_with_secret", kind = "none", username = null, credential = byteArrayOf(7))
            }
        thrown.message.orEmpty() shouldContain "chk_datasource_credential_present"
    }

    @Test
    fun `the present-check refuses a credential-bearing kind WITHOUT a credential`() {
        val thrown =
            shouldThrow<DataIntegrityViolationException> {
                insertPostV13Row(name = "token_no_secret", kind = "token", username = null, credential = null)
            }
        thrown.message.orEmpty() shouldContain "chk_datasource_credential_present"
    }

    @Test
    fun `the username-check refuses a password row with no username, and a private_key row WITH one`() {
        shouldThrow<DataIntegrityViolationException> {
            insertPostV13Row(name = "password_no_user", kind = "password", username = null, credential = byteArrayOf(7))
        }.message.orEmpty() shouldContain "chk_datasource_credential_username"

        shouldThrow<DataIntegrityViolationException> {
            insertPostV13Row(name = "pk_with_user", kind = "private_key", username = "app", credential = byteArrayOf(7))
        }.message.orEmpty() shouldContain "chk_datasource_credential_username"
    }

    @Test
    fun `the kind-check refuses a value outside the enums §5A set`() {
        shouldThrow<DataIntegrityViolationException> {
            insertPostV13Row(name = "bad_kind", kind = "kerberos", username = null, credential = byteArrayOf(7))
        }.message.orEmpty() shouldContain "chk_datasource_credential_kind"
    }

    @Test
    fun `a token row may carry a username or omit one - the OPTIONAL rule of §3-4`() {
        insertPostV13Row(name = "token_named", kind = "token", username = "svc", credential = byteArrayOf(7))
        insertPostV13Row(name = "token_bare", kind = "token", username = null, credential = byteArrayOf(7))

        jdbc.queryForObject(
            "SELECT count(*) FROM datasources WHERE credential_kind = 'token'",
            emptyMap<String, Any>(),
            Long::class.java,
        ) shouldBe 2L
    }

    private fun hexOf(name: String): String? =
        jdbc.queryForObject(
            "SELECT encode(credential_encrypted, 'hex') FROM datasources WHERE name = :name",
            mapOf("name" to name),
            String::class.java,
        )

    /** A row as V12 stored one: `username` and `password_encrypted` both NOT NULL, no kind column. */
    private fun insertPreV13Row(
        name: String,
        username: String,
        credential: ByteArray,
    ) = jdbc.update(
        """
        INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, password_encrypted, created_by)
        VALUES (:name, :name, 'H2', 'jdbc:h2:mem:' || :name, :username, :credential, :actor)
        """.trimIndent(),
        mapOf("name" to name, "username" to username, "credential" to credential, "actor" to ACTOR_ID),
    )

    private fun insertPostV13Row(
        name: String,
        kind: String,
        username: String?,
        credential: ByteArray?,
    ) = jdbc.update(
        """
        INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, credential_kind, credential_encrypted, created_by)
        VALUES (:name, :name, 'H2', 'jdbc:h2:mem:' || :name, :username, :kind, :credential, :actor)
        """.trimIndent(),
        mapOf(
            "name" to name,
            "username" to username,
            "kind" to kind,
            "credential" to credential,
            "actor" to ACTOR_ID,
        ),
    )

    /** The SHIPPED V13 script, located by its PURPOSE — the orchestrator renumbers parallel lanes. */
    private fun v13(): String =
        TestFiles
            .repoFile(
                ShippedMigrations.paths().singleOrNull { it.endsWith(MIGRATION_SUFFIX) }
                    ?: error("no shipped migration ends with '$MIGRATION_SUFFIX' — it was renamed, not renumbered"),
            ).readText()

    private companion object {
        const val MIGRATION_SUFFIX = "__datasource_credential_kind.sql"

        /**
         * The version this suite builds UP TO but not including. Derived from the shipped file
         * rather than typed, so a renumber moves the boundary with the script instead of
         * silently applying V13 twice (once in the loop, once as the subject).
         */
        val V13: Int =
            ShippedMigrations
                .paths()
                .single { it.endsWith(MIGRATION_SUFFIX) }
                .substringAfterLast("/V")
                .substringBefore("__")
                .toInt()

        /** datasources.created_by is NOT NULL REFERENCES users — the migrations seed no users. */
        val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000087")

        /** A scratch database: this suite builds the schema PART-WAY on purpose (pre-V13). */
        val db = SharedPostgres.scratchDatabase("pre_v13_credential_kind")
    }
}
