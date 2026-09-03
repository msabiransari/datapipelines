package co.datapipelines.datasources

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.datasources.crypto.CredentialEncryptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText

/**
 * Bootstrap registration against the real metadata schema (datasources.md §8A; the sample-data
 * design §9 "App" matrix, at the DB level — the rows are the deliverable, not the return value).
 *
 * The proofs, one per way this feature fails:
 * - a fresh database gets every entry, with the flags and the actor the file and caller asked for;
 * - a **restart** mutates nothing — including `updated_at`, which is what an accidental
 *   `save()`-on-existing would move even if every visible field came out the same;
 * - an **operator-edited** row survives byte-identical, which is the whole promise of never-update;
 * - a **soft-deleted** name counts as present (it is the primary key — recreating it would be a
 *   PK violation, not a create);
 * - an entry that fails §9 validation aborts, and the entries applied before it stay applied;
 * - nothing the registrar logs contains the credential.
 *
 * H2 in-memory is the "external" datasource: the §9 test pool build runs with
 * `initializationFailTimeout = -1`, so the pool is constructed and closed without connecting —
 * the validation being proved here is the pool BUILD, and the driver must be loadable for it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BootstrapDatasourceRegistrarIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: DatasourceRepository
    private lateinit var registrar: BootstrapDatasourceRegistrar
    private lateinit var actor: UUID

    @TempDir
    lateinit var tempDir: Path

    /** Binds the JDBC template to the module's shared, already-migrated container. */
    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
    }

    @BeforeEach
    fun setUp() {
        jdbc.jdbcTemplate.execute("TRUNCATE datasources, users CASCADE")
        actor = insertActor()
        repository = DatasourceRepository(jdbc)
        registrar =
            BootstrapDatasourceRegistrar(
                registry = DefaultDatasourceRegistry(repository, CredentialEncryptor.fromBase64Key(test32ByteKeyBase64())),
                repository = repository,
                reader = BootstrapDatasourceFileReader(environment = ENV::get),
            )
    }

    // ------------------------------------------------------------------- fresh database

    @Test
    fun `a fresh database gets every entry with its flags, global scope and created_by`() {
        val summary = registrar.register(file(TWO_ENTRIES), actor)

        summary.registered shouldContainExactly listOf("bootstrap-readonly", "bootstrap-writable")
        summary.skipped shouldContainExactly emptyList()

        val readonly = row("bootstrap-readonly")
        readonly.isReadonly shouldBe true
        readonly.createdBy shouldBe actor
        readonly.displayName shouldBe "Read-only sample"
        // `global: true` is `workspace_id NULL` (metadata-db §4.10). The column is not in the
        // repository's projection, so it is read straight out of the table.
        workspaceIdOf("bootstrap-readonly").shouldBeNull()

        val writable = row("bootstrap-writable")
        writable.isReadonly shouldBe false
        writable.createdBy shouldBe actor
        workspaceIdOf("bootstrap-writable").shouldBeNull()

        // The credential was encrypted on the way in — the file's plaintext is not on disk.
        passwordHexOf("bootstrap-readonly").contains(PASSWORD.toByteArray().joinToString("") { "%02x".format(it) }) shouldBe false
    }

    // ------------------------------------------------------------------- the restart

    @Test
    fun `a second run over the same file mutates nothing and reports every entry skipped`() {
        registrar.register(file(TWO_ENTRIES), actor)
        val before = snapshot()

        val summary = registrar.register(file(TWO_ENTRIES), actor)

        summary.registered shouldContainExactly emptyList()
        summary.skipped shouldContainExactly listOf("bootstrap-readonly", "bootstrap-writable")
        // updated_at is in the snapshot deliberately: a save() on an existing row would move it
        // even when every other column came out identical.
        snapshot() shouldBe before
    }

    @Test
    fun `an operator-edited row survives the next startup byte-identical`() {
        registrar.register(file(TWO_ENTRIES), actor)
        // The operator changes the three things a "helpful" re-registration would put back:
        // the display name, the readonly flag, and the credential.
        jdbc.jdbcTemplate.update(
            "UPDATE datasources SET display_name = ?, is_readonly = FALSE, password_encrypted = ?, updated_at = NOW()" +
                " WHERE name = 'bootstrap-readonly'",
            "Operator renamed this",
            byteArrayOf(1, 2, 3),
        )
        val edited = snapshot()

        registrar.register(file(TWO_ENTRIES), actor)

        snapshot() shouldBe edited
        row("bootstrap-readonly").displayName shouldBe "Operator renamed this"
        row("bootstrap-readonly").isReadonly shouldBe false
    }

    @Test
    fun `a soft-deleted name counts as present and is skipped, not recreated`() {
        registrar.register(file(TWO_ENTRIES), actor)
        repository.softDelete("bootstrap-readonly") shouldBe true
        // It is invisible to every read path...
        repository.exists("bootstrap-readonly") shouldBe false

        val summary = registrar.register(file(TWO_ENTRIES), actor)

        // ...but the name is the primary key, so it is still taken. Treating it as absent would
        // take the create branch straight into a duplicate_name failure.
        summary.registered shouldContainExactly emptyList()
        summary.skipped shouldContainExactly listOf("bootstrap-readonly", "bootstrap-writable")
        isDeletedOf("bootstrap-readonly") shouldBe true
    }

    // ------------------------------------------------------------------- fail-fast

    @Test
    fun `an entry failing section 9 validation aborts startup, leaving the entries before it applied`() {
        // Entry 2 carries a refused Hikari key: server-managed `jdbcUrl` under `properties.hikari`
        // (§5.6). It fails the test pool build the same way it would fail a REST create.
        val yaml =
            """
            datasources:
              - name: bootstrap-first
                dialect: H2
                jdbc_url: jdbc:h2:mem:bootstrap_first;DB_CLOSE_DELAY=-1
                username: sa
                password: sa
                readonly: true
                global: true
              - name: bootstrap-broken
                dialect: H2
                jdbc_url: jdbc:h2:mem:bootstrap_broken;DB_CLOSE_DELAY=-1
                username: sa
                password: sa
                properties:
                  hikari:
                    jdbcUrl: jdbc:h2:mem:elsewhere
                readonly: true
                global: true
              - name: bootstrap-never-reached
                dialect: H2
                jdbc_url: jdbc:h2:mem:bootstrap_never;DB_CLOSE_DELAY=-1
                username: sa
                password: sa
                readonly: true
                global: true
            """.trimIndent()

        val error = shouldThrow<DatasourceValidationException> { registrar.register(file(yaml), actor) }

        error.result.errors.map { it.code } shouldContainExactly listOf(DatasourceErrorCodes.PROPERTIES_INVALID)
        // Entries are applied ONE AT A TIME, each fully validated before the next is attempted:
        // the first is committed, the third was never reached.
        repository.exists("bootstrap-first") shouldBe true
        repository.existsIncludingDeleted("bootstrap-broken") shouldBe false
        repository.existsIncludingDeleted("bootstrap-never-reached") shouldBe false
    }

    // ------------------------------------------------------------------- secrets

    @Test
    fun `no line the registration path logs contains the credential`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            registrar.register(file(TWO_ENTRIES), actor)
            registrar.register(file(TWO_ENTRIES), actor) // the skip path logs too
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }

        val lines = appender.list.map { it.formattedMessage + " " + it.argumentArray?.joinToString(" ") }
        lines.none { it.contains(PASSWORD) } shouldBe true
        // The assertion above can only fail meaningfully if the appender saw anything at all.
        lines.any { it.contains("datasource.bootstrap_registered") } shouldBe true
        lines.any { it.contains("datasource.bootstrap_skipped") } shouldBe true
    }

    // ------------------------------------------------------------------- helpers

    private fun file(yaml: String): Path = tempDir.resolve("bootstrap-${UUID.randomUUID()}.yml").also { it.writeText(yaml) }

    private fun row(name: String): DatasourceRow = checkNotNull(repository.findByName(name)) { "no live row named '$name'" }

    private fun workspaceIdOf(name: String): UUID? =
        jdbc.queryForObject("SELECT workspace_id FROM datasources WHERE name = :n", mapOf("n" to name), UUID::class.java)

    private fun isDeletedOf(name: String): Boolean =
        checkNotNull(
            jdbc.queryForObject("SELECT is_deleted FROM datasources WHERE name = :n", mapOf("n" to name), Boolean::class.java),
        )

    private fun passwordHexOf(name: String): String =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT encode(password_encrypted, 'hex') FROM datasources WHERE name = :n",
                mapOf("n" to name),
                String::class.java,
            ),
        )

    /** Every column that a re-registration could possibly disturb, for the never-update proofs. */
    private fun snapshot(): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT name, display_name, description, dialect, jdbc_url, username," +
                " encode(password_encrypted, 'hex') AS pw, properties_json::text AS props," +
                " query_timeout_seconds, is_readonly, is_deleted, workspace_id, created_by," +
                " created_at, updated_at" +
                " FROM datasources ORDER BY name",
            emptyMap<String, Any>(),
        )

    private fun insertActor(): UUID =
        checkNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO users (email, display_name, provider, provider_subject, is_admin)
                VALUES ('bootstrap-admin@example.com', 'bootstrap-admin', 'bootstrap', 'bootstrap-admin@example.com', TRUE)
                RETURNING id
                """.trimIndent(),
                emptyMap<String, Any>(),
                UUID::class.java,
            ),
        )

    private fun dataSource(): DriverManagerDataSource = SharedPostgres.dataSource()

    private companion object {
        const val PASSWORD = "bootstrap-file-secret"

        /** The process environment the file's placeholders resolve against, injected. */
        val ENV = mapOf("BOOTSTRAP_TEST_PASSWORD" to PASSWORD)

        val TWO_ENTRIES =
            """
            datasources:
              - name: bootstrap-readonly
                display_name: Read-only sample
                dialect: H2
                jdbc_url: jdbc:h2:mem:bootstrap_ro;DB_CLOSE_DELAY=-1
                username: sa
                password: ${'$'}{BOOTSTRAP_TEST_PASSWORD}
                readonly: true
                global: true
              - name: bootstrap-writable
                dialect: H2
                jdbc_url: jdbc:h2:mem:bootstrap_rw;DB_CLOSE_DELAY=-1
                username: sa
                password: ${'$'}{BOOTSTRAP_TEST_PASSWORD}
                readonly: false
                global: true
            """.trimIndent()

    }
}
