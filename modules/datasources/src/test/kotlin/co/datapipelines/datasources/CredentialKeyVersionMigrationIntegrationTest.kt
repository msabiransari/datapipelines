package co.datapipelines.datasources

import co.datapipelines.datasources.crypto.CredentialDecryptionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * `V10__datasource_credential_key_version.sql` against a real Postgres — the migration that gave
 * every pre-round credential its key-version byte (datasources.md §7.1, round 068).
 *
 * ## Why this suite builds a PARTIAL schema (changed in 087)
 *
 * It used to replay V10 against the FULLY migrated database, reproducing the pre-round layout by
 * stripping the version byte in SQL. That worked only while V10's column still existed under
 * V10's name: `V13__datasource_credential_kind.sql` renames `password_encrypted` to
 * `credential_encrypted`, and an applied migration's text is frozen by its Flyway checksum, so
 * V10 can never be updated to follow. Replaying a migration against a schema LATER than its own
 * is the defect, not the rename.
 *
 * So it now takes the shape the other migration suites use (`TemplateFolderGateMigrationTest`,
 * [DatasourceCredentialKindMigrationTest]): a scratch database built V1–V9 through
 * [ShippedMigrations], rows inserted the way V9-era rows existed — raw SQL, `password_encrypted`
 * NOT NULL, no `credential_kind` column in sight — and V10 executed by plain JDBC exactly as
 * Flyway applies it. Nothing here goes through [DatasourceRepository]: the repository speaks the
 * CURRENT schema, and a suite whose subject is an older one must not.
 *
 * What it proves, in the order an operator would ask:
 *  1. the migration reaches every credential-bearing row (`get_byte(..., 0) = 1`);
 *  2. the encryptor then decrypts those rows under key version 1 — the backfill's whole claim;
 *  3. before the migration, that same row is REFUSED, so the test could actually have failed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CredentialKeyVersionMigrationIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var owner: UUID

    private val encryptor = testEncryptor()

    @BeforeAll
    fun createPreV10Schema() {
        jdbc = NamedParameterJdbcTemplate(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        val dir = TestFiles.repoDirectory("modules/app/src/main/resources/db/migration")
        ShippedMigrations.migrations(dir).filter { it.first < V10 }.forEach { pair ->
            jdbc.jdbcTemplate.execute(pair.second.readText())
        }
        owner = insertUser()
    }

    @Test
    fun `the migration prefixes version 1 onto every legacy credential, and the encryptor reads it`() {
        insertLegacyRow("pg_legacy", "legacy-secret")
        insertLegacyRow("h2_legacy", "other-secret")

        // Falsification: in the pre-round layout the blob is not readable — so a migration that
        // did nothing could not pass the assertions below.
        shouldThrow<CredentialDecryptionException> { encryptor.decrypt(storedBlob("pg_legacy"), "pg_legacy") }

        applyShippedMigration()

        versionHistogram() shouldBe mapOf(1 to 2L)
        encryptor.decrypt(storedBlob("pg_legacy"), "pg_legacy") shouldBe "legacy-secret"
        encryptor.decrypt(storedBlob("h2_legacy"), "h2_legacy") shouldBe "other-secret"

        cleanRows()
    }

    @Test
    fun `the histogram query datasources §7-3 hands the operator is the one this migration satisfies`() {
        insertLegacyRow("pg_legacy2", "legacy-secret")
        applyShippedMigration()

        // A row written AFTER the migration carries its key version from the encryptor and is
        // NOT prefixed a second time — the migration backfills history, it does not sit on the
        // write path. Both rows therefore report version 1 on this single-key deployment.
        insertRow("pg_fresh", encryptor.encrypt("fresh", "pg_fresh"))
        encryptor.decrypt(storedBlob("pg_fresh"), "pg_fresh") shouldBe "fresh"

        versionHistogram() shouldBe mapOf(1 to 2L)

        cleanRows()
    }

    /**
     * A row as a V9-era deployment stored one: the encryptor's blob with its leading version byte
     * REMOVED, which is exactly `nonce ‖ ciphertext ‖ tag`. Written by raw SQL, not through the
     * repository — the repository speaks the post-V13 schema this database does not have.
     */
    private fun insertLegacyRow(
        name: String,
        secret: String,
    ) = insertRow(name, encryptor.encrypt(secret, name).drop(1).toByteArray())

    private fun insertRow(
        name: String,
        credential: ByteArray,
    ) = jdbc.update(
        """
        INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, password_encrypted, created_by)
        VALUES (:name, :name, 'H2', 'jdbc:h2:mem:' || :name, 'sa', :credential, :actor)
        """.trimIndent(),
        mapOf("name" to name, "credential" to credential, "actor" to owner),
    )

    private fun storedBlob(name: String): ByteArray =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT password_encrypted FROM datasources WHERE name = :name",
                mapOf("name" to name),
                ByteArray::class.java,
            ),
        )

    /** Each test owns its rows: both run in one class against one partial schema. */
    private fun cleanRows() = jdbc.jdbcTemplate.execute("DELETE FROM datasources")

    /**
     * Runs the SHIPPED migration script, read from the repository — never a copy of its text,
     * and located by its PURPOSE rather than its version number: 068 and 066 were in flight
     * together and the orchestrator renumbers whichever merges second.
     */
    private fun applyShippedMigration() {
        val path =
            ShippedMigrations.paths().singleOrNull { it.endsWith(MIGRATION_SUFFIX) }
                ?: error("no shipped migration ends with '$MIGRATION_SUFFIX' — it was renamed, not renumbered")
        jdbc.jdbcTemplate.execute(TestFiles.repoFile(path).readText())
    }

    /** The §7.3 operator query: how many stored credentials carry each key version. */
    private fun versionHistogram(): Map<Int, Long> =
        jdbc
            .queryForList(
                """
                SELECT get_byte(password_encrypted, 0) AS key_version, count(*) AS rows
                FROM datasources
                WHERE password_encrypted IS NOT NULL
                GROUP BY 1
                """.trimIndent(),
                emptyMap<String, Any>(),
            ).associate { row -> (row["key_version"] as Number).toInt() to (row["rows"] as Number).toLong() }

    private fun insertUser(): UUID =
        checkNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO users (email, display_name, provider, provider_subject)
                VALUES ('owner@example.com', 'Owner', 'google', 'sub-1')
                RETURNING id
                """.trimIndent(),
                emptyMap<String, Any>(),
                UUID::class.java,
            ),
        )

    private companion object {
        const val MIGRATION_SUFFIX = "__datasource_credential_key_version.sql"

        /** The version this suite builds UP TO but not including — derived, never typed. */
        val V10: Int =
            ShippedMigrations
                .paths()
                .single { it.endsWith(MIGRATION_SUFFIX) }
                .substringAfterLast("/V")
                .substringBefore("__")
                .toInt()

        /** A scratch database: the subject is a schema OLDER than the module's own. */
        val db = SharedPostgres.scratchDatabase("pre_v10_key_version")
    }
}
