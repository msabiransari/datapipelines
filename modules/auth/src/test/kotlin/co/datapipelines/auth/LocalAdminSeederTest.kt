package co.datapipelines.auth

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The first-admin seed (auth.md §5A.2) against a real Postgres: create-if-absent,
 * idempotent across restarts, hash form stored verbatim, plaintext form hashed and
 * never logged, and the still-pending one-time credential visible as a startup WARN.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalAdminSeederTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var users: UserRepository
    private lateinit var audit: AuditLogger
    private lateinit var userService: UserService
    private lateinit var hasher: RecordingHasher

    @BeforeAll
    fun createSchema() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
        RepoFiles.MIGRATION_PATHS.forEach { jdbc.jdbcTemplate.execute(RepoFiles.read(it)) }
    }

    @BeforeEach
    fun setUp() {
        users = UserRepository(jdbc)
        audit = AuditLogger(jdbc, ObjectMapper())
        hasher = RecordingHasher()
        userService = UserService(users, AuthCache(AuthProperties()), AuthProperties(bootstrapAdminEmail = ADMIN_EMAIL), audit)
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute("TRUNCATE audit_log")
    }

    private fun seeder(
        enabled: Boolean = true,
        hash: String? = null,
        plaintext: String? = null,
        adminEmail: String? = ADMIN_EMAIL,
    ) = LocalAdminSeeder(
        users,
        userService,
        hasher,
        AuthProperties(
            bootstrapAdminEmail = adminEmail,
            local = AuthProperties.Local(enabled = enabled, bootstrapPasswordHash = hash, bootstrapPassword = plaintext),
        ),
        audit,
    )

    @Test
    fun `local disabled or no seed keys seeds nothing`() {
        seeder(enabled = false, plaintext = SEED_PASSWORD).seedIfConfigured()
        seeder(enabled = true).seedIfConfigured()

        users.findByEmail(ADMIN_EMAIL).shouldBeNull()
        hasher.hashCalls shouldBe 0
    }

    @Test
    fun `a plaintext seed creates the admin with a hashed one-time credential and both audit rows`() {
        seeder(plaintext = SEED_PASSWORD).seedIfConfigured()

        val user = users.findByEmail(ADMIN_EMAIL).shouldNotBeNull()
        user.isAdmin.shouldBeTrue()
        user.provider shouldBe UserService.BOOTSTRAP_PROVIDER
        user.mustChangePassword.shouldBeTrue()
        val credential = users.findLocalCredential(ADMIN_EMAIL).shouldNotBeNull()
        // Hashed with the SecretHasher — never the plaintext itself, and it verifies.
        credential.passwordHash shouldBe "hash:$SEED_PASSWORD"
        hasher.hashCalls shouldBe 1
        auditEvents("auth.user.admin_granted") shouldBe 1
        auditEvents("auth.password.seeded") shouldBe 1
    }

    @Test
    fun `a pre-computed hash is stored verbatim - the server never sees the plaintext at all`() {
        seeder(hash = PRECOMPUTED_HASH).seedIfConfigured()

        users.findLocalCredential(ADMIN_EMAIL).shouldNotBeNull().passwordHash shouldBe PRECOMPUTED_HASH
        hasher.hashCalls shouldBe 0
        users
            .findByEmail(ADMIN_EMAIL)
            .shouldNotBeNull()
            .mustChangePassword
            .shouldBeTrue()
    }

    @Test
    fun `a restart never resets a changed password - seeding is create-if-absent`() {
        val seed = seeder(plaintext = SEED_PASSWORD)
        seed.seedIfConfigured()
        val userId = users.findByEmail(ADMIN_EMAIL).shouldNotBeNull().id
        // The admin changed the seeded password at first login (§5A.4 cleared the flag).
        users.setPassword(userId, "hash:a-brand-new-password", mustChange = false)

        seed.seedIfConfigured()

        users.findLocalCredential(ADMIN_EMAIL).shouldNotBeNull().passwordHash shouldBe "hash:a-brand-new-password"
        hasher.hashCalls shouldBe 1 // only the original seed
        auditEvents("auth.password.seeded") shouldBe 1
    }

    @Test
    fun `a deployment still running the one-time credential is visible to the operator`() {
        val seed = seeder(plaintext = SEED_PASSWORD)
        seed.seedIfConfigured()

        val lines = capturingLogs { seed.seedIfConfigured() }

        lines.any { it.contains("event=auth.local.one_time_credential_pending") } shouldBe true
    }

    @Test
    fun `seeding without a bootstrap admin email refuses, naming the key`() {
        shouldThrow<IllegalStateException> {
            seeder(plaintext = SEED_PASSWORD, adminEmail = null).seedIfConfigured()
        }.message shouldBe "datapipelines.auth.bootstrap-admin-email is required to seed the local admin credential (§5A.2)"
    }

    @Test
    fun `no line the seed path logs contains the plaintext seed password`() {
        val lines =
            capturingLogs {
                seeder(plaintext = SEED_PASSWORD).seedIfConfigured()
                seeder(plaintext = SEED_PASSWORD).seedIfConfigured()
            }

        // Positive control (the 021 pattern): the expected event lines must be there,
        // or the negative below passes vacuously on an empty capture.
        lines.any { it.contains("event=auth.local.seeded") } shouldBe true
        lines.any { it.contains("event=auth.local.one_time_credential_pending") } shouldBe true
        lines.none { it.contains(SEED_PASSWORD) } shouldBe true
    }

    private fun capturingLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(LocalAdminSeeder::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
        }
    }

    private fun auditEvents(event: String): Int =
        checkNotNull(
            jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE event = '$event'", emptyMap<String, Any>(), Int::class.java),
        ) { "COUNT(*) returned no row" }

    private fun dataSource(): DriverManagerDataSource =
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName(postgres.driverClassName)
        }

    /** Records hash calls so the hash-form seed can prove the server never hashes (or sees) anything. */
    private class RecordingHasher : SecretHasher {
        var hashCalls = 0

        override fun hash(raw: String): String {
            hashCalls++
            return "hash:$raw"
        }

        override fun verify(
            encodedHash: String,
            raw: String,
        ): Boolean = encodedHash == "hash:$raw"
    }

    private companion object {
        const val ADMIN_EMAIL = "admin@datapipelines.test"
        const val SEED_PASSWORD = "one-time-seed-password-123"
        const val PRECOMPUTED_HASH = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c2VlZA\$precomputed"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("dp")
                .withPassword("dp")
    }
}
