package co.datapipelines.datasources

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.datasources.crypto.CredentialEncryptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText

/**
 * **The owner's 2026-09-02 incident, reproduced and then fixed** — bootstrap rule 3
 * (datasources.md §8A.3, 061/T84).
 *
 * ## Why this suite needs a REAL login
 *
 * Every other bootstrap test uses H2, where the §9 test pool build never connects
 * (`initializationFailTimeout = -1`) and any password is accepted. The defect being closed here
 * is precisely an AUTHENTICATION failure, so the datasource under test is a real Postgres role
 * in the same container that holds the metadata schema, and "the password changed out of band"
 * is a real `ALTER ROLE`. A fake that cannot reject a credential cannot reproduce a credential
 * defect.
 *
 * ## What went wrong, in the incident's own shape
 *
 * `sample-trips` was registered on 2026-08-30 with the then-current `SAMPLE_PG_PASSWORD`.
 * The variable later changed; the sample database's role got the NEW password at the next
 * load, the stored row kept the OLD one, and nothing compared them. Every multi-node demo
 * pipeline failed at CONNECT with `password authentication failed for user "dp_demo_ro"`
 * while the datasources screen showed the datasource as fine — listing does not connect.
 *
 * ## The "before" state is not a hypothetical
 *
 * [rulesOneAndTwoOnly] is the registrar wired to a registry whose `resyncBootstrapCredential`
 * answers `NOT_APPLICABLE` — the interface's own documented default, and byte-for-byte the
 * behaviour of the pre-rule-3 registrar: see the row, skip it, do nothing. Running it first is
 * what makes "the fix fixes something" a measurement rather than a claim.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BootstrapCredentialResyncIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: DatasourceRepository
    private lateinit var actor: UUID

    private val encryptor = CredentialEncryptor.fromBase64Key(test32ByteKeyBase64())

    @TempDir
    lateinit var tempDir: Path

    @BeforeAll
    fun createSchema() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
        ShippedMigrations.paths().forEach { path -> jdbc.jdbcTemplate.execute(TestFiles.repoFile(path).readText()) }
    }

    @BeforeEach
    fun setUp() {
        jdbc.jdbcTemplate.execute("TRUNCATE datasources, users CASCADE")
        actor = insertActor()
        repository = DatasourceRepository(jdbc)
        // The sample database's login, reset per test to the ORIGINAL password. Created once
        // and ALTERed thereafter: a DROP would fail on the database-level GRANT that depends
        // on it, and the role's identity is not what any of these tests is varying.
        jdbc.jdbcTemplate.execute(
            """
            DO ${'$'}${'$'} BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$ROLE') THEN
                    CREATE ROLE $ROLE LOGIN;
                END IF;
            END ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.jdbcTemplate.execute("ALTER ROLE $ROLE LOGIN PASSWORD '$ORIGINAL_PASSWORD'")
        jdbc.jdbcTemplate.execute("GRANT CONNECT ON DATABASE ${postgres.databaseName} TO $ROLE")
    }

    // ------------------------------------------------------------- the incident, reproduced

    /**
     * Gate 3, first half: with rules 1 and 2 alone, the row keeps the stale credential across
     * the boot and an execution-shaped connect fails at authentication — exactly what the
     * owner hit, and exactly what the datasource LIST could not show.
     */
    @Test
    fun `WITHOUT rule 3 a rotated password leaves the row stale and the next execution fails at connect`() {
        register(ORIGINAL_PASSWORD)
        leaseSucceeds() shouldBe true

        rotateRolePasswordOutOfBand()
        val beforeBoot = snapshot()

        rulesOneAndTwoOnly().register(bootstrapFile(), actor)

        // Rule 1 did its job and that is the whole problem: the row is untouched...
        snapshot() shouldBe beforeBoot
        // ...and it no longer works. This is the failure the demo pipelines hit at CONNECT.
        // HikariCP wraps the driver's refusal in a PoolInitializationException — the type is
        // the pool's, the sentence is the database's, and the sentence is what matters.
        val failure = shouldThrow<Exception> { leaseOrThrow() }
        causeMessages(failure).any { it.contains("password authentication failed") } shouldBe true
    }

    // ------------------------------------------------------------- the incident, fixed

    /**
     * Gate 3, second half: rule 3 connection-tests the STORED credential, finds it broken,
     * finds the FILE's works, and replaces the credential — the credential ALONE. The WARN
     * line names the datasource; the execution that failed above now succeeds.
     */
    @Test
    fun `WITH rule 3 the credential is resynced, the WARN names the datasource, and the execution succeeds`() {
        register(ORIGINAL_PASSWORD)
        rotateRolePasswordOutOfBand()
        val beforeBoot = definitionSnapshot()
        val staleCiphertext = passwordHex()

        val (summary, lines) = capturingLogs { registrar().register(bootstrapFile(), actor) }

        summary.resynced shouldContainExactly listOf(NAME)
        summary.broken.shouldBeEmpty()
        // The WARN names the datasource and the env key — never the value (see the secrecy test).
        val warn = lines.single { it.contains("datasource.bootstrap_credential_resynced") }
        warn shouldContain NAME
        warn shouldContain PASSWORD_ENV_KEY

        // The credential — and ONLY the credential — moved. Everything an operator owns is
        // byte-identical: name, display name, description, URL, username, properties, timeout,
        // readonly, workspace, created_at, created_by.
        definitionSnapshot() shouldBe beforeBoot
        passwordHex() shouldNotBe staleCiphertext
        leaseSucceeds() shouldBe true

        // §8.1B: the outcome is on the row, so the screen can say so without an execution.
        val lastTest =
            repository
                .findByName(NAME)
                .shouldNotBeNull()
                .lastTest
                .shouldNotBeNull()
        lastTest.ok shouldBe true
    }

    /**
     * Rule 1 still holds, which is the other half of the promise: an operator whose edited
     * credential WORKS keeps it, even though the file now says something different. The row —
     * every column that existed before V9, `updated_at` included — is byte-identical across
     * the boot.
     *
     * The three V9 `last_test_*` columns are the single deliberate exception, and they are not
     * part of the datasource's definition: they record that a probe happened, which is the
     * whole point of running one. `updated_at` staying put is what makes that distinction
     * checkable rather than asserted.
     */
    @Test
    fun `an OPERATOR-edited WORKING credential is left byte-untouched even when the file differs`() {
        register(ORIGINAL_PASSWORD)
        // The operator rotates the role AND the stored credential — through the UI, not the file.
        jdbc.jdbcTemplate.execute("ALTER ROLE $ROLE PASSWORD '$OPERATOR_PASSWORD'")
        DefaultDatasourceRegistry(repository, encryptor).save(entity(OPERATOR_PASSWORD), actor)
        val beforeBoot = preV9Snapshot()

        // The file still carries the ORIGINAL password: a difference, so rule 3 runs — and
        // decides by connecting, which is what keeps it from reverting the operator.
        val summary = registrar().register(bootstrapFile(ORIGINAL_PASSWORD), actor)

        summary.resynced.shouldBeEmpty()
        summary.broken.shouldBeEmpty()
        preV9Snapshot() shouldBe beforeBoot
        leaseSucceeds() shouldBe true
        // The probe DID happen and its outcome was recorded — the observation columns move,
        // the definition does not.
        repository
            .findByName(NAME)
            .shouldNotBeNull()
            .lastTest
            .shouldNotBeNull()
            .ok shouldBe true
    }

    /**
     * Neither credential authenticates: the row is left alone (there is nothing to resync TO)
     * and the ERROR line names the datasource AND the environment variable an operator would
     * change — which on 2026-09-02 was `SAMPLE_PG_PASSWORD` in `deploy/.env.demo`.
     */
    @Test
    fun `when NEITHER credential authenticates the row is untouched and the ERROR names the env key`() {
        register(ORIGINAL_PASSWORD)
        jdbc.jdbcTemplate.execute("ALTER ROLE $ROLE PASSWORD 'something-nobody-has'")
        val beforeBoot = preV9Snapshot()

        val (summary, lines) = capturingLogs { registrar().register(bootstrapFile(ROTATED_PASSWORD), actor) }

        summary.broken shouldContainExactly listOf(NAME)
        summary.resynced.shouldBeEmpty()
        preV9Snapshot() shouldBe beforeBoot
        val error = lines.single { it.contains("datasource.bootstrap_credential_broken") }
        error shouldContain NAME
        error shouldContain PASSWORD_ENV_KEY

        // §8.1B again, in the case that matters most: the screen shows FAILED with the
        // driver's own message, without any execution having run.
        val lastTest =
            repository
                .findByName(NAME)
                .shouldNotBeNull()
                .lastTest
                .shouldNotBeNull()
        lastTest.ok shouldBe false
        lastTest.message.shouldNotBeNull() shouldContain "password authentication failed"
    }

    /**
     * A row whose ciphertext cannot be DECRYPTED is a key problem, not a login problem: the
     * row is left alone and the ERROR names the config key. Overwriting here would silently
     * paper over a wrong `datapipelines.db.encryption-key` that every other datasource in the
     * deployment also has.
     */
    @Test
    fun `an UNREADABLE stored credential is left alone and named as a key problem, not resynced`() {
        register(ORIGINAL_PASSWORD)
        jdbc.jdbcTemplate.update("UPDATE datasources SET password_encrypted = ? WHERE name = '$NAME'", byteArrayOf(1, 2, 3))
        val beforeBoot = preV9Snapshot()

        val (summary, lines) = capturingLogs { registrar().register(bootstrapFile(), actor) }

        summary.broken shouldContainExactly listOf(NAME)
        preV9Snapshot() shouldBe beforeBoot
        lines.single { it.contains("datasource.bootstrap_credential_unreadable") } shouldContain NAME
    }

    /**
     * A SOFT-DELETED bootstrap datasource is never resynced: rule 1 promises a datasource an
     * operator deleted never resurrects, and writing its credential back would be a
     * resurrection in everything but the flag.
     */
    @Test
    fun `a soft-deleted row is never resynced, however stale its credential`() {
        register(ORIGINAL_PASSWORD)
        rotateRolePasswordOutOfBand()
        repository.softDelete(NAME) shouldBe true
        val beforeBoot = preV9Snapshot()

        val summary = registrar().register(bootstrapFile(), actor)

        summary.resynced.shouldBeEmpty()
        summary.broken.shouldBeEmpty()
        summary.skipped shouldContainExactly listOf(NAME)
        preV9Snapshot() shouldBe beforeBoot
    }

    /**
     * The ordinary boot: the file's credential equals the stored one, so **nothing is
     * probed**. The `last_test_*` columns staying NULL is the assertion — a rule that fired on
     * every restart would open one connection per bootstrap datasource per boot to rediscover
     * what the equality already said.
     */
    @Test
    fun `a healthy restart probes nothing at all`() {
        register(ORIGINAL_PASSWORD)
        val beforeBoot = preV9Snapshot()

        registrar().register(bootstrapFile(ORIGINAL_PASSWORD), actor)

        preV9Snapshot() shouldBe beforeBoot
        repository
            .findByName(NAME)
            .shouldNotBeNull()
            .lastTest
            .shouldBeNull()
    }

    /** No rule-3 line carries a credential — not the stored one, not the file's. */
    @Test
    fun `no rule 3 log line contains either credential`() {
        register(ORIGINAL_PASSWORD)
        rotateRolePasswordOutOfBand()

        val (_, lines) = capturingLogs { registrar().register(bootstrapFile(), actor) }

        lines.none { it.contains(ORIGINAL_PASSWORD) } shouldBe true
        lines.none { it.contains(ROTATED_PASSWORD) } shouldBe true
        // Vacuity floor: the assertions above mean nothing unless the rule actually ran.
        lines.any { it.contains("datasource.bootstrap_credential_resynced") } shouldBe true
    }

    // ------------------------------------------------------------- helpers

    /** The production registrar — rules 1, 2 and 3. */
    private fun registrar() =
        BootstrapDatasourceRegistrar(
            registry = DefaultDatasourceRegistry(repository, encryptor),
            repository = repository,
            reader = BootstrapDatasourceFileReader(environment = ::envValue),
        )

    /**
     * The registrar as it behaved BEFORE rule 3: the registry's `resyncBootstrapCredential`
     * default (`NOT_APPLICABLE`) is exactly "see the row, skip it, do nothing".
     */
    private fun rulesOneAndTwoOnly() =
        BootstrapDatasourceRegistrar(
            registry =
                object : DatasourceRegistry by DefaultDatasourceRegistry(repository, encryptor) {
                    override fun resyncBootstrapCredential(
                        name: String,
                        fileCredential: String,
                    ) = CredentialResync.NOT_APPLICABLE
                },
            repository = repository,
            reader = BootstrapDatasourceFileReader(environment = ::envValue),
        )

    private fun register(password: String) {
        DefaultDatasourceRegistry(repository, encryptor).save(entity(password), actor)
    }

    private fun entity(password: String) =
        Datasource(
            name = NAME,
            displayName = "Sample trips",
            description = "The demo's sample database, via a real login.",
            dialect = co.datapipelines.typesystem.Dialect.POSTGRES,
            jdbcUrl = sampleUrl(),
            username = ROLE,
            password = password,
            isReadonly = true,
        )

    /**
     * The datasource's URL, built rather than taken from `postgres.jdbcUrl`: Testcontainers
     * appends `?loggerLevel=OFF`, and §5.6's fail-closed URL guard refuses it — correctly, and
     * for a reason that has nothing to do with what these tests measure.
     */
    private fun sampleUrl(): String = "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}"

    private fun bootstrapFile(filePassword: String = ROTATED_PASSWORD): Path {
        currentFilePassword = filePassword
        val yaml =
            """
            datasources:
              - name: $NAME
                display_name: Sample trips
                description: The demo's sample database, via a real login.
                dialect: POSTGRES
                jdbc_url: ${sampleUrl()}
                username: $ROLE
                password: ${'$'}{$PASSWORD_ENV_KEY}
                readonly: true
                global: true
            """.trimIndent()
        return tempDir.resolve("bootstrap-${UUID.randomUUID()}.yml").also { it.writeText(yaml) }
    }

    private var currentFilePassword: String = ROTATED_PASSWORD

    /**
     * The process environment the file's placeholders resolve against — read LAZILY, at
     * resolution time. A map captured when the registrar was built would freeze whatever
     * [bootstrapFile] had not yet set, which is a way for a test to silently measure the
     * wrong scenario.
     */
    private fun envValue(key: String): String? = if (key == PASSWORD_ENV_KEY) currentFilePassword else null

    /** The out-of-band change: the sample loader gives the role the NEW password; the row is not told. */
    private fun rotateRolePasswordOutOfBand() {
        jdbc.jdbcTemplate.execute("ALTER ROLE $ROLE PASSWORD '$ROTATED_PASSWORD'")
    }

    /** An execution-shaped connect: build the pool from the stored row and lease a connection. */
    private fun leaseOrThrow() {
        val registry = DefaultDatasourceRegistry(repository, encryptor)
        try {
            val datasource = registry.get(NAME).shouldNotBeNull()
            registry.poolFor(datasource).leaseConnection().use { connection ->
                connection.createStatement().use { it.executeQuery("SELECT 1").close() }
            }
        } finally {
            registry.shutdown()
        }
    }

    /** Every message in the cause chain — a pool exception's own text names no database. */
    private fun causeMessages(e: Throwable): List<String> = generateSequence(e) { it.cause }.mapNotNull { it.message }.toList()

    private fun leaseSucceeds(): Boolean =
        try {
            leaseOrThrow()
            true
        } catch (e: Exception) {
            LoggerFactory.getLogger(javaClass).debug("lease failed", e)
            false
        }

    private fun <T> capturingLogs(block: () -> T): Pair<T, List<String>> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            val result = block()
            return result to appender.list.map { it.formattedMessage + " " + it.argumentArray?.joinToString(" ") }
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }

    private fun passwordHex(): String? =
        jdbc.queryForObject(
            "SELECT encode(password_encrypted, 'hex') FROM datasources WHERE name = :n",
            mapOf("n" to NAME),
            String::class.java,
        )

    /** Everything, credential and observation columns included — for "nothing moved at all". */
    private fun snapshot(): List<Map<String, Any?>> = rows(PRE_V9_COLUMNS + ", encode(password_encrypted, 'hex') AS pw" + V9_COLUMNS)

    /**
     * Every column that existed BEFORE V9 — the definition of the datasource, `updated_at` and
     * the credential included. This is the byte-identity the rule-1 guarantee is about.
     */
    private fun preV9Snapshot(): List<Map<String, Any?>> = rows(PRE_V9_COLUMNS + ", encode(password_encrypted, 'hex') AS pw")

    /**
     * The definition WITHOUT the credential and WITHOUT `updated_at` — for the resync, where
     * both are meant to move (a credential replacement is a real change to the datasource).
     */
    private fun definitionSnapshot(): List<Map<String, Any?>> = rows(DEFINITION_COLUMNS)

    private fun rows(columns: String): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT $columns FROM datasources ORDER BY name", emptyMap<String, Any>())

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

    private fun dataSource(): DriverManagerDataSource =
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName(postgres.driverClassName)
        }

    private companion object {
        const val NAME = "sample-trips"
        const val ROLE = "dp_demo_ro"
        const val PASSWORD_ENV_KEY = "SAMPLE_PG_PASSWORD"
        const val ORIGINAL_PASSWORD = "pw-2026-08-30"
        const val ROTATED_PASSWORD = "pw-2026-09-02"
        const val OPERATOR_PASSWORD = "pw-set-by-the-operator"

        /** The datasource AS DEFINED, minus the credential and minus `updated_at`. */
        const val DEFINITION_COLUMNS =
            "name, display_name, description, dialect, jdbc_url, username, properties_json::text AS props, " +
                "query_timeout_seconds, introspection_include_schemas_json::text AS incl, is_readonly, workspace_id, " +
                "is_deleted, created_at, created_by"

        /** Everything that existed before V9 — the definition plus `updated_at`. */
        const val PRE_V9_COLUMNS = "$DEFINITION_COLUMNS, updated_at"

        const val V9_COLUMNS = ", last_test_at, last_test_ok, last_test_message"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("dp")
                .withPassword("dp")
    }
}
