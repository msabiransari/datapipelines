package co.datapipelines.datasources

import co.datapipelines.datasources.crypto.CredentialEncryptor
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.UUID

/**
 * [DatasourceRepository] against a real Postgres running the **shipped** `V1` schema — the same
 * pattern (and rationale) as `PipelineRepositoryIntegrationTest`: the migration is executed
 * through plain JDBC because Flyway and the migration scripts live in `app` alone
 * (module-structure §3.1 rule 2). Proves the metadata round-trip metadata-db §4.10 defines,
 * including the `BYTEA` credential and the `JSONB` `properties_json`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasourceRepositoryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: DatasourceRepository
    private lateinit var owner: UUID

    private val encryptor = CredentialEncryptor.fromBase64Key(test32ByteKeyBase64())

    /** Binds the JDBC template to the module's shared, already-migrated container. */
    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
    }

    @BeforeEach
    fun setUp() {
        repository = DatasourceRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE datasources, users CASCADE")
        owner = insertUser()
    }

    @Test
    fun `create stores the row and returns what the database persisted`() {
        val datasource =
            Fixtures
                .postgres(
                    name = "pg_prod",
                    properties = DatasourceProperties(hikari = mapOf("maximumPoolSize" to 8), jdbc = mapOf("ssl" to "true")),
                ).copy(queryTimeoutSeconds = 45)

        val row = repository.create(datasource, encryptor.encrypt("s3cret", datasource.name), owner)

        row.name shouldBe "pg_prod"
        row.dialect shouldBe Dialect.POSTGRES
        row.queryTimeoutSeconds shouldBe 45
        row.isDeleted shouldBe false
        row.createdAt shouldNotBe null
        // properties_json round-tripped through JSONB, both namespaces intact.
        row.properties.hikari["maximumPoolSize"] shouldBe 8
        row.properties.jdbc["ssl"] shouldBe "true"
        // The stored credential is ciphertext and decrypts back to the original.
        encryptor.decrypt(row.passwordEncrypted, row.name) shouldBe "s3cret"
    }

    @Test
    fun `findByName and list skip soft-deleted rows`() {
        repository.create(Fixtures.postgres(name = "a"), encryptor.encrypt("p", "a"), owner)
        repository.create(Fixtures.h2(name = "b"), encryptor.encrypt("p", "b"), owner)

        repository.findByName("a") shouldNotBe null
        repository.findAll().map { it.name }.toSet() shouldBe setOf("a", "b")
        repository.findAll(Dialect.H2).map { it.name } shouldBe listOf("b")

        repository.softDelete("a") shouldBe true
        repository.findByName("a").shouldBeNull()
        repository.exists("a") shouldBe false
        repository.findAll().map { it.name } shouldBe listOf("b")
        // Second delete finds nothing live.
        repository.softDelete("a") shouldBe false
    }

    @Test
    fun `update changes fields, keeps the password when none is supplied, and bumps updated_at`() {
        val created = repository.create(Fixtures.postgres(name = "pg"), encryptor.encrypt("original", "pg"), owner)

        val updated =
            checkNotNull(
                repository.update(
                    Fixtures.postgres(name = "pg").copy(displayName = "Renamed", queryTimeoutSeconds = 99),
                    passwordEncrypted = null,
                ),
            )

        updated.displayName shouldBe "Renamed"
        updated.queryTimeoutSeconds shouldBe 99
        (updated.updatedAt >= created.updatedAt) shouldBe true
        // Password kept: still decrypts to the original.
        encryptor.decrypt(checkNotNull(repository.findByName("pg")).passwordEncrypted, "pg") shouldBe "original"
    }

    @Test
    fun `update replaces the password when one is supplied`() {
        repository.create(Fixtures.postgres(name = "pg"), encryptor.encrypt("original", "pg"), owner)

        repository.update(Fixtures.postgres(name = "pg"), encryptor.encrypt("rotated", "pg"))

        encryptor.decrypt(checkNotNull(repository.findByName("pg")).passwordEncrypted, "pg") shouldBe "rotated"
    }

    @Test
    fun `creating a datasource whose name is taken raises duplicate_name`() {
        repository.create(Fixtures.postgres(name = "pg"), encryptor.encrypt("p", "pg"), owner)

        val thrown =
            shouldThrow<DatapipelinesException> {
                repository.create(Fixtures.postgres(name = "pg"), encryptor.encrypt("p", "pg"), owner)
            }

        thrown.code shouldBe DatasourceErrorCodes.DUPLICATE_NAME
        thrown.details["name"] shouldBe "pg"
    }

    @Test
    fun `a soft-deleted name is never released - re-creating it raises duplicate_name`() {
        // §9: uniqueness is GLOBAL, not "among live rows". `name` is the primary key and a soft
        // delete keeps the row, so the name stays taken — deliberately, because pipelines
        // reference datasources by name and reusing a retired name would silently repoint every
        // pipeline that still names it at a different database.
        repository.create(Fixtures.postgres(name = "retired"), encryptor.encrypt("p", "retired"), owner)
        repository.softDelete("retired") shouldBe true
        repository.exists("retired") shouldBe false

        val thrown =
            shouldThrow<DatapipelinesException> {
                repository.create(Fixtures.postgres(name = "retired"), encryptor.encrypt("p", "retired"), owner)
            }

        thrown.code shouldBe DatasourceErrorCodes.DUPLICATE_NAME
    }

    @Test
    fun `a datasource with no properties stores {} - not two empty namespaces`() {
        repository.create(Fixtures.postgres(name = "bare"), encryptor.encrypt("p", "bare"), owner)

        val stored =
            jdbc.queryForObject(
                "SELECT properties_json::text FROM datasources WHERE name = 'bare'",
                emptyMap<String, Any>(),
                String::class.java,
            )

        stored shouldBe "{}"
        // …and it round-trips back as two empty namespaces, so nothing downstream changes.
        val row = checkNotNull(repository.findByName("bare"))
        row.properties.hikari shouldBe emptyMap()
        row.properties.jdbc shouldBe emptyMap()
        row.properties.unknownNamespaces shouldBe emptySet()
    }

    @Test
    fun `properties parsed from a raw map round-trip through JSONB unchanged`() {
        // The production path is: request JSON -> DatasourceProperties.fromRaw -> save. Every
        // other case here builds DatasourceProperties by hand, which skips the parse entirely.
        val raw = mapOf("hikari" to mapOf("maximumPoolSize" to 4), "jdbc" to mapOf("sslmode" to "verify-full"))
        val datasource = Fixtures.postgres(name = "from_raw", properties = DatasourceProperties.fromRaw(raw))

        repository.create(datasource, encryptor.encrypt("p", "from_raw"), owner)

        val row = checkNotNull(repository.findByName("from_raw"))
        row.properties.hikari shouldBe mapOf("maximumPoolSize" to 4)
        row.properties.jdbc shouldBe mapOf("sslmode" to "verify-full")
    }

    @Test
    fun `the introspection include-schemas allowlist round-trips - and the column default reads as empty`() {
        // §3.3/§7A (V2): the allowlist persists as a JSONB array and comes back verbatim; a
        // datasource saved without one reads the '[]' column default as the empty list —
        // absent and empty are the same behavior.
        repository.create(
            Fixtures.postgres(name = "with_allowlist", introspectionIncludeSchemas = listOf("apex_reporting")),
            encryptor.encrypt("p", "with_allowlist"),
            owner,
        )
        repository.create(Fixtures.postgres(name = "without_allowlist"), encryptor.encrypt("p", "without_allowlist"), owner)

        checkNotNull(repository.findByName("with_allowlist")).introspectionIncludeSchemas shouldBe listOf("apex_reporting")
        checkNotNull(repository.findByName("without_allowlist")).introspectionIncludeSchemas shouldBe emptyList()

        // And an update that drops the allowlist persists the drop.
        repository.update(Fixtures.postgres(name = "with_allowlist"), passwordEncrypted = null)
        checkNotNull(repository.findByName("with_allowlist")).introspectionIncludeSchemas shouldBe emptyList()
    }

    @Test
    fun `a row whose allowlist landed mixed-case by a non-programmatic path reads back normalized`() {
        // R4 F3: a restore or a manual JSONB edit writes rows without crossing
        // registry.save — the read boundary normalizes too, so such an entry can never sit
        // inert (an unnormalized entry would silently match nothing, because matching
        // compares lowercase reported schemas against stored strings verbatim).
        jdbc.update(
            """
            INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, password_encrypted,
                                     introspection_include_schemas_json, created_by)
            VALUES ('restored', 'Restored', 'H2', 'jdbc:h2:mem:restored', 'sa', :pw,
                    CAST('["APEX_REPORTING"]' AS jsonb), :owner)
            """.trimIndent(),
            mapOf("pw" to encryptor.encrypt("p", "restored"), "owner" to owner),
        )

        checkNotNull(repository.findByName("restored")).introspectionIncludeSchemas shouldBe listOf("apex_reporting")
    }

    @Test
    fun `a row whose allowlist holds blanks and duplicates reads back as the clean list`() {
        // R5 F2: the read-boundary normalization must drop blank-after-trim entries and
        // duplicates, not just trim+lowercase — otherwise a restored `[" ", "apex", "APEX"]`
        // row surfaces `["", "apex", "apex"]` to REST and MCP and 400s an unmodified PUT
        // round-trip (the validator rejects blanks) while exempting nothing.
        jdbc.update(
            """
            INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, password_encrypted,
                                     introspection_include_schemas_json, created_by)
            VALUES ('dirty', 'Dirty', 'H2', 'jdbc:h2:mem:dirty', 'sa', :pw,
                    CAST('[" ", "apex", "APEX", " sales "]' AS jsonb), :owner)
            """.trimIndent(),
            mapOf("pw" to encryptor.encrypt("p", "dirty"), "owner" to owner),
        )

        checkNotNull(repository.findByName("dirty")).introspectionIncludeSchemas shouldBe listOf("apex", "sales")
    }

    // ------------------------------------------------- V9: the last connection test's outcome

    /**
     * §8.1B (061/T84): the outcome round-trips, and NULL across all three columns reads back
     * as "never tested" — which is what every pre-V9 row is. `last_test_message` cannot be the
     * discriminator (a success with no server version leaves it NULL), so `last_test_at` is.
     */
    @Test
    fun `a recorded test outcome round-trips, and an unprobed row reads back as never-tested`() {
        repository.create(Fixtures.h2(name = "probed"), encryptor.encrypt("p", "probed"), owner)

        checkNotNull(repository.findByName("probed")).lastTest.shouldBeNull()

        val at = Instant.parse("2026-08-30T09:15:00Z")
        repository.recordTestOutcome("probed", DatasourceTestOutcome(at, ok = false, message = "password authentication failed")) shouldBe
            true

        val stored = checkNotNull(checkNotNull(repository.findByName("probed")).lastTest)
        stored.testedAt shouldBe at
        stored.ok shouldBe false
        stored.message shouldBe "password authentication failed"

        // A success with no server version: the message is legitimately NULL and the outcome
        // is still an outcome.
        repository.recordTestOutcome("probed", DatasourceTestOutcome(at, ok = true)) shouldBe true
        checkNotNull(checkNotNull(repository.findByName("probed")).lastTest).let {
            it.ok shouldBe true
            it.message.shouldBeNull()
        }
    }

    /**
     * The one documented exception to metadata-db §2's "every UPDATE sets `updated_at`": a
     * test outcome is an observation ABOUT the datasource, not a change TO it. §8A.3 rule 1
     * promises an operator's row survives a boot byte-untouched, and that promise stays
     * mechanically checkable only while a probe leaves the definition columns alone.
     */
    @Test
    fun `recording an outcome moves NOTHING else - updated_at included`() {
        repository.create(Fixtures.h2(name = "still"), encryptor.encrypt("p", "still"), owner)
        val before = definitionRow("still")

        repository.recordTestOutcome("still", DatasourceTestOutcome(Instant.parse("2026-09-03T00:00:00Z"), ok = true))

        definitionRow("still") shouldBe before
    }

    /**
     * §8A.3 rule 3's write is the credential and nothing else — and `updated_at` DOES move,
     * because unlike an outcome this is a real change to the datasource.
     */
    @Test
    fun `updateCredential replaces the credential alone and moves updated_at`() {
        repository.create(Fixtures.h2(name = "resync"), encryptor.encrypt("old", "resync"), owner)
        val before = definitionRow("resync")
        val oldCipher = passwordHexOf("resync")

        repository.updateCredential("resync", encryptor.encrypt("new", "resync")) shouldBe true

        val after = definitionRow("resync")
        // Everything the operator owns is untouched — the two columns a resync is ALLOWED to
        // move are excluded and asserted separately below.
        val moved = setOf("pw", "updated_at")
        after.filterKeys { it !in moved } shouldBe before.filterKeys { it !in moved }
        // ...updated_at moved, and the credential is the new one.
        after["updated_at"] shouldNotBe before["updated_at"]
        passwordHexOf("resync") shouldNotBe oldCipher
        encryptor.decrypt(checkNotNull(repository.findByName("resync")).passwordEncrypted, "resync") shouldBe "new"

        repository.updateCredential("absent", ByteArray(1)) shouldBe false
    }

    /** A soft-deleted row accepts neither write — rule 1's "a deleted datasource never resurrects". */
    @Test
    fun `neither V9 write touches a soft-deleted row`() {
        repository.create(Fixtures.h2(name = "gone"), encryptor.encrypt("p", "gone"), owner)
        repository.softDelete("gone") shouldBe true

        repository.recordTestOutcome("gone", DatasourceTestOutcome(Instant.EPOCH, ok = true)) shouldBe false
        repository.updateCredential("gone", encryptor.encrypt("other", "gone")) shouldBe false
    }

    /** The definition columns of one row, credential included — the "nothing moved" unit. */
    private fun definitionRow(name: String): Map<String, Any?> =
        jdbc
            .queryForList(
                "SELECT name, display_name, description, dialect, jdbc_url, username," +
                    " encode(password_encrypted, 'hex') AS pw, properties_json::text AS props, query_timeout_seconds," +
                    " introspection_include_schemas_json::text AS incl, is_readonly, workspace_id, is_deleted," +
                    " created_at, updated_at, created_by" +
                    " FROM datasources WHERE name = :n",
                mapOf("n" to name),
            ).single()

    private fun passwordHexOf(name: String): String =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT encode(password_encrypted, 'hex') FROM datasources WHERE name = :n",
                mapOf("n" to name),
                String::class.java,
            ),
        )

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

    private fun dataSource(): DriverManagerDataSource = SharedPostgres.dataSource()
}
