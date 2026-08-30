package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * The password mutations (auth.md §5A.4/§5A.1): self-service change with its
 * current-password check and the §5A.5 policy floor, and the admin operations —
 * create (through the single §4.4 creation path), reset (one-time, clears
 * lockout), disable, unlock — each audited with the actor.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalPasswordServiceTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var users: UserRepository
    private lateinit var audit: AuditLogger
    private lateinit var userService: UserService
    private lateinit var service: LocalPasswordService
    private val hasher = Argon2SecretHasher()

    @BeforeAll
    fun createSchema() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
        RepoFiles.MIGRATION_PATHS.forEach { jdbc.jdbcTemplate.execute(RepoFiles.read(it)) }
    }

    @BeforeEach
    fun setUp() {
        users = UserRepository(jdbc)
        audit = AuditLogger(jdbc, ObjectMapper())
        val cache = AuthCache(AuthProperties())
        userService = UserService(users, cache, AuthProperties(), audit)
        service = LocalPasswordService(users, userService, hasher, cache, audit, AuthProperties())
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute("TRUNCATE audit_log")
    }

    private fun localUser(
        email: String,
        password: String,
        mustChange: Boolean = false,
    ): User {
        val user = users.insert(email, "Local", null, UserService.LOCAL_PROVIDER, email, isAdmin = false)
        users.setPassword(user.id, hasher.hash(password), mustChange)
        return user
    }

    // ---------------------------------------------------------- self-service change

    @Test
    fun `a correct current password rotates the credential, clears must-change, and audits`() {
        val user = localUser("a@company.com", OLD_PASSWORD, mustChange = true)

        service.changeOwn(user.id, OLD_PASSWORD, NEW_PASSWORD) shouldBe LocalPasswordService.ChangeResult.Success

        val reloaded = users.findById(user.id).shouldNotBeNull()
        reloaded.mustChangePassword.shouldBeFalse()
        hasher.verify(users.findLocalCredential("a@company.com").shouldNotBeNull().passwordHash, NEW_PASSWORD).shouldBeTrue()
        auditEvents("auth.password.changed") shouldBe 1
    }

    @Test
    fun `a wrong current password changes nothing - a hijacked session cannot rotate the credential`() {
        val user = localUser("a@company.com", OLD_PASSWORD, mustChange = true)

        service.changeOwn(user.id, "not-the-password", NEW_PASSWORD) shouldBe
            LocalPasswordService.ChangeResult.WrongCurrentPassword

        hasher.verify(users.findLocalCredential("a@company.com").shouldNotBeNull().passwordHash, OLD_PASSWORD).shouldBeTrue()
        users
            .findById(user.id)
            .shouldNotBeNull()
            .mustChangePassword
            .shouldBeTrue()
        auditEvents("auth.password.changed") shouldBe 0
    }

    /**
     * The oracle guard. Before this, `changeOwn` verified the current password with NO
     * lockout check, NO failure counting and NO audit on failure — while `POST /login`
     * had all three. That made the change endpoint an unmetered, silent brute-force
     * oracle sitting beside a fully-defended login path, and (until the session gate on
     * the controller) it was reachable with a leaked read-scoped API key.
     *
     * Asserted at the PERSISTED level, not on a spy: the counter is read back out of the
     * database, because the defect was precisely that nothing was written.
     */
    @Test
    fun `a wrong current password is counted and audited on the same lockout counter as login`() {
        val user = localUser("a@company.com", OLD_PASSWORD)

        service.changeOwn(user.id, "not-the-password", NEW_PASSWORD) shouldBe
            LocalPasswordService.ChangeResult.WrongCurrentPassword

        users.findLocalCredential("a@company.com").shouldNotBeNull().failedLoginCount shouldBe 1
        auditEvents("auth.password.change_failed") shouldBe 1
    }

    @Test
    fun `repeated wrong current passwords lock the account, and the lock then short-circuits the check`() {
        val user = localUser("a@company.com", OLD_PASSWORD)

        // The AuthProperties default is 5 consecutive failures.
        repeat(5) {
            service.changeOwn(user.id, "not-the-password", NEW_PASSWORD) shouldBe
                LocalPasswordService.ChangeResult.WrongCurrentPassword
        }

        users
            .findLocalCredential("a@company.com")
            .shouldNotBeNull()
            .lockedUntil
            .shouldNotBeNull()

        // Once locked, even the CORRECT password is refused without an Argon2 verify — the
        // lock must be a brake, not a CPU amplifier, exactly as the login path treats it.
        service.changeOwn(user.id, OLD_PASSWORD, NEW_PASSWORD) shouldBe LocalPasswordService.ChangeResult.AccountLocked
        hasher.verify(users.findLocalCredential("a@company.com").shouldNotBeNull().passwordHash, OLD_PASSWORD).shouldBeTrue()
    }

    @Test
    fun `a successful change clears the failure counter`() {
        val user = localUser("a@company.com", OLD_PASSWORD)
        service.changeOwn(user.id, "not-the-password", NEW_PASSWORD)
        users.findLocalCredential("a@company.com").shouldNotBeNull().failedLoginCount shouldBe 1

        service.changeOwn(user.id, OLD_PASSWORD, NEW_PASSWORD) shouldBe LocalPasswordService.ChangeResult.Success

        users.findLocalCredential("a@company.com").shouldNotBeNull().failedLoginCount shouldBe 0
    }

    @Test
    fun `the policy floor rejects short and overlong passwords before any hashing`() {
        val user = localUser("a@company.com", OLD_PASSWORD)

        (service.changeOwn(user.id, OLD_PASSWORD, "x".repeat(11)) is LocalPasswordService.ChangeResult.PolicyViolation) shouldBe true
        (service.changeOwn(user.id, OLD_PASSWORD, "x".repeat(129)) is LocalPasswordService.ChangeResult.PolicyViolation) shouldBe true
        service.changeOwn(user.id, OLD_PASSWORD, "x".repeat(12)) shouldBe LocalPasswordService.ChangeResult.Success
    }

    @Test
    fun `an OIDC-only account has no password to change`() {
        val user = users.insert("oidc@company.com", "Oidc", null, "google", "sub-1", isAdmin = false)

        service.changeOwn(user.id, OLD_PASSWORD, NEW_PASSWORD) shouldBe LocalPasswordService.ChangeResult.NoLocalAccount
    }

    // ------------------------------------------------------------- admin operations

    @Test
    fun `create mints a local account whose one-time password works once and must be changed`() {
        val result = service.createLocalUser("New@Company.com", "New User", ACTOR)

        (result is LocalPasswordService.CreateResult.Success) shouldBe true
        val created = (result as LocalPasswordService.CreateResult.Success)
        created.oneTimePassword.shouldHaveLength(14) // xxxx-xxxx-xxxx
        val user = created.user
        user.email shouldBe "new@company.com"
        user.provider shouldBe UserService.LOCAL_PROVIDER
        // The returned User is the insert snapshot; setPassword ran after it.
        users
            .findById(user.id)
            .shouldNotBeNull()
            .mustChangePassword
            .shouldBeTrue()
        hasher
            .verify(users.findLocalCredential("new@company.com").shouldNotBeNull().passwordHash, created.oneTimePassword)
            .shouldBeTrue()
        auditEvents("auth.user.created") shouldBe 1
    }

    @Test
    fun `create through the one creation path still applies the bootstrap grant`() {
        // §4.4 fires at row creation on EVERY path — an admin who creates the
        // bootstrap address creates an admin, exactly as a first OIDC login would.
        val grantingService =
            LocalPasswordService(
                users,
                UserService(users, AuthCache(AuthProperties()), AuthProperties(bootstrapAdminEmail = "root@company.com"), audit),
                hasher,
                AuthCache(AuthProperties()),
                audit,
                AuthProperties(),
            )

        val result = grantingService.createLocalUser("root@company.com", "Root", ACTOR)

        (result as LocalPasswordService.CreateResult.Success).user.isAdmin.shouldBeTrue()
        auditEvents("auth.user.admin_granted") shouldBe 1
    }

    @Test
    fun `create refuses a taken email without touching the existing account`() {
        localUser("a@company.com", OLD_PASSWORD)

        service.createLocalUser("a@company.com", "Clone", ACTOR) shouldBe LocalPasswordService.CreateResult.EmailTaken

        hasher.verify(users.findLocalCredential("a@company.com").shouldNotBeNull().passwordHash, OLD_PASSWORD).shouldBeTrue()
    }

    @Test
    fun `reset issues a one-time password, forces the change, and clears the lockout`() {
        val user = localUser("a@company.com", OLD_PASSWORD)
        users.recordLocalLoginFailure(user.id, 1, 15).lockedUntil.shouldNotBeNull()

        val oneTime = service.resetPassword(user.id, ACTOR).shouldNotBeNull()

        val reloaded = users.findById(user.id).shouldNotBeNull()
        reloaded.mustChangePassword.shouldBeTrue()
        reloaded.lockedUntil.shouldBeNull()
        hasher.verify(users.findLocalCredential("a@company.com").shouldNotBeNull().passwordHash, oneTime).shouldBeTrue()
        hasher.verify(users.findLocalCredential("a@company.com").shouldNotBeNull().passwordHash, OLD_PASSWORD).shouldBeFalse()
        auditEvents("auth.password.reset") shouldBe 1
    }

    @Test
    fun `disable local access makes the account OIDC-only and reports the transition once`() {
        val user = localUser("a@company.com", OLD_PASSWORD, mustChange = true)

        service.disableLocalAccess(user.id, ACTOR).shouldBeTrue()
        service.disableLocalAccess(user.id, ACTOR).shouldBeFalse()

        users.findLocalCredential("a@company.com").shouldBeNull()
        users
            .findById(user.id)
            .shouldNotBeNull()
            .mustChangePassword
            .shouldBeFalse()
        auditEvents("auth.password.disabled") shouldBe 1
    }

    @Test
    fun `unlock clears the lockout without touching the credential`() {
        val user = localUser("a@company.com", OLD_PASSWORD)
        users.recordLocalLoginFailure(user.id, 1, 15)

        service.unlock(user.id, ACTOR).shouldBeTrue()
        service.unlock(user.id, ACTOR).shouldBeFalse()

        users
            .findLocalCredential("a@company.com")
            .shouldNotBeNull()
            .lockedUntil
            .shouldBeNull()
        hasher.verify(users.findLocalCredential("a@company.com").shouldNotBeNull().passwordHash, OLD_PASSWORD).shouldBeTrue()
        auditEvents("auth.user.unlocked") shouldBe 1
    }

    private fun auditEvents(event: String): Int =
        checkNotNull(
            jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE event = '$event'", emptyMap<String, Any>(), Int::class.java),
        ) { "COUNT(*) returned no row" }

    private fun dataSource(): DriverManagerDataSource =
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName(postgres.driverClassName)
        }

    private companion object {
        val ACTOR: UUID = UUID.randomUUID()
        const val OLD_PASSWORD = "the-old-password-1"
        const val NEW_PASSWORD = "the-new-password-1"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("dp")
                .withPassword("dp")
    }
}
