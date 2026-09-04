package co.datapipelines.datasources

import co.datapipelines.datasources.crypto.CredentialDecryptionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * `V11__datasource_credential_key_version.sql` against a real Postgres — the migration that gave
 * every pre-round credential its key-version byte (datasources.md §7.1, round 068).
 *
 * The pre-round layout is reproduced the only honest way available after the code change: a row
 * is written through the production path, then its leading version byte is STRIPPED in SQL, which
 * leaves exactly the `nonce ‖ ciphertext ‖ tag` bytes a v1.3 deployment stored. The migration is
 * then applied from **the shipped file**, read off disk — never a hand-copy of its SQL, which
 * would test a string in this file rather than the script Flyway runs.
 *
 * What it proves, in the order an operator would ask:
 *  1. the migration reaches every credential-bearing row (`get_byte(..., 0) = 1`);
 *  2. the encryptor then decrypts those rows under key version 1 — the backfill's whole claim;
 *  3. before the migration, that same row is REFUSED, so the test could actually have failed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CredentialKeyVersionMigrationIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: DatasourceRepository
    private lateinit var owner: UUID

    private val encryptor = testEncryptor()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    }

    @BeforeEach
    fun setUp() {
        repository = DatasourceRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE datasources, users CASCADE")
        owner = insertUser()
    }

    @Test
    fun `the migration prefixes version 1 onto every legacy credential, and the encryptor reads it`() {
        repository.create(Fixtures.postgres(name = "pg_legacy"), encryptor.encrypt("legacy-secret", "pg_legacy"), owner)
        repository.create(Fixtures.h2(name = "h2_legacy"), encryptor.encrypt("other-secret", "h2_legacy"), owner)
        stripVersionBytes()

        // Falsification: in the pre-round layout the blob is not readable — so a migration that
        // did nothing could not pass the assertions below.
        shouldThrow<CredentialDecryptionException> {
            encryptor.decrypt(checkNotNull(repository.findByName("pg_legacy")).passwordEncrypted, "pg_legacy")
        }

        applyShippedMigration()

        versionHistogram() shouldBe mapOf(1 to 2L)
        encryptor.decrypt(checkNotNull(repository.findByName("pg_legacy")).passwordEncrypted, "pg_legacy") shouldBe "legacy-secret"
        encryptor.decrypt(checkNotNull(repository.findByName("h2_legacy")).passwordEncrypted, "h2_legacy") shouldBe "other-secret"
    }

    @Test
    fun `the histogram query datasources §7-3 hands the operator is the one this migration satisfies`() {
        repository.create(Fixtures.postgres(name = "pg_legacy"), encryptor.encrypt("legacy-secret", "pg_legacy"), owner)
        stripVersionBytes()
        applyShippedMigration()

        // A row written AFTER the migration carries its key version from the encryptor and is
        // NOT prefixed a second time — the migration backfills history, it does not sit on the
        // write path. Both rows therefore report version 1 on this single-key deployment.
        repository.create(Fixtures.postgres(name = "pg_fresh"), encryptor.encrypt("fresh", "pg_fresh"), owner)
        encryptor.decrypt(checkNotNull(repository.findByName("pg_fresh")).passwordEncrypted, "pg_fresh") shouldBe "fresh"

        versionHistogram() shouldBe mapOf(1 to 2L)
    }

    /** Reproduces the pre-round `nonce ‖ ciphertext ‖ tag` layout by removing the version byte. */
    private fun stripVersionBytes() {
        jdbc.jdbcTemplate.execute(
            "UPDATE datasources SET password_encrypted = substring(password_encrypted FROM 2) WHERE password_encrypted IS NOT NULL",
        )
    }

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
    }
}
