package co.datapipelines.config

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.DatapipelinesApplication
import co.datapipelines.OidcDiscoveryStub
import co.datapipelines.SharedPostgres
import co.datapipelines.web.api.ApiExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.reflect.full.primaryConstructor

/**
 * #704 — a refused `audit_log` INSERT inside the caller's transaction refuses the request and
 * commits nothing (auth.md §10.1A). The witness was #517's f2a: `AuditLogger` caught the refusal,
 * the service went on, and on Postgres its COMMIT became a silent ROLLBACK behind a success — an
 * issuance that answered a plaintext key whose key row, identity and `auth.api_key.created` row
 * never committed.
 *
 * The mould is `RemoveMemberRollbackIntegrationTest`'s: the PROXIED `apiKeyService` of the real
 * application context on the shared test Postgres, auth beans reached by name and reflection (`app`
 * does not compile against `auth`, module-structure §4.2), and a plpgsql plant `dp704_refuse()`
 * raising `planted by #704`, wired by ONE `BEFORE INSERT ON audit_log` trigger per case whose `WHEN`
 * clause names this run's own row — the event AND a per-run marker — so nothing else sharing the
 * database is touched. Created inside the test, dropped in `finally`; [afterAll] proves the drop.
 *
 * Cases: T1/T2 — the issuance under the plant: refused with the plant's message and nothing
 * committed, and with the #266b swallow restored the SAME case goes red on "the returned key's row
 * committed" (the issue's half the falsification flips); T3 — the control: no plant, a key, an
 * identity and exactly one `auth.api_key.created` row; T4 — `revokeOwn` under a plant on
 * `auth.api_key.revoked`: refused, the key stays live and its identity active.
 *
 * `IssuedApiKey.plaintext` is never read: every key is identified by SQL, so no credential reaches a
 * log, an assertion message or the evidence.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyAuditRefusalIntegrationTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var exceptionHandler: ApiExceptionHandler

    private val workspaceService: Any by lazy { context.getBean(WORKSPACE_SERVICE_BEAN) }
    private val apiKeyService: Any by lazy { context.getBean(API_KEY_SERVICE_BEAN) }
    private val userRepository: Any by lazy { context.getBean(USER_REPOSITORY_BEAN) }

    // Every created row is tracked the moment it exists; afterAll sweeps them. Never TRUNCATE.
    private val userIds = mutableListOf<UUID>()
    private val workspaceIds = mutableListOf<UUID>()
    private val workspaceNames = mutableListOf<String>()
    private val keyNames = mutableListOf<String>()

    /** A workspace, an AUTHOR member who mints keys, and the name the next key will carry. */
    private data class Fixture(
        val workspaceId: UUID,
        val workspaceName: String,
        val member: Any,
        val memberId: UUID,
        val keyName: String,
    )

    @Test
    fun `an issuance whose audit row is refused is refused itself, with nothing committed (T1, T2)`() {
        val fx = fixture()
        plant("auth.api_key.created", "NEW.details_json ->> 'name' = '${fx.keyName}'")
        try {
            val outcome = runCatching { issue(fx) }
            // T2 — the swallow's shape: issue RETURNED a key. Then its row must have committed, and
            // with the swallow it did not (the aborted transaction's COMMIT was a silent rollback).
            // Unreachable once the refusal propagates.
            if (outcome.isSuccess) {
                withClue("issue returned a key, so the returned key must exist in api_keys") { keyCount(fx) shouldBe 1 }
            }
            val refusal = outcome.exceptionOrNull()
            withClue("the refusal must be a DataAccessException: $refusal") { (refusal is DataAccessException) shouldBe true }
            assertPlanted(refusal as DataAccessException)
            assertNothingCommitted(fx)
            assertBoundaryAnswer(refusal, fx)
            assertProxied()
        } finally {
            dropPlant()
        }
    }

    @Test
    fun `positive control - with no plant the issuance commits a key, its identity and one audit row (T3)`() {
        val fx = fixture()
        assertProxied()
        issue(fx)
        assertSoftly {
            withClue("(control) the key row committed") { keyCount(fx) shouldBe 1 }
            withClue("(control) the key's identity committed") { identityCount(fx) shouldBe 1 }
            withClue("(control) exactly one auth.api_key.created row committed") { createdAuditCount(fx) shouldBe 1 }
        }
    }

    @Test
    fun `a revocation whose audit row is refused is refused itself - the key stays live (T4)`() {
        val fx = fixture()
        issue(fx)
        val (keyId, identityId) = liveKeyOf(fx)
        plant("auth.api_key.revoked", "NEW.key_id = '$keyId'")
        try {
            val refusal = shouldThrow<DataAccessException> { invoke(apiKeyService, "revokeOwn", keyId, fx.memberId) }
            assertPlanted(refusal)
            assertSoftly {
                withClue("the key stays live") { keyRevoked(keyId) shouldBe false }
                withClue("its identity stays active") { identityActive(identityId) shouldBe true }
                withClue("no revocation row committed") { revokedAuditCount(keyId) shouldBe 0 }
            }
            assertProxied()
        } finally {
            dropPlant()
        }
    }

    @AfterAll
    fun afterAll() {
        dropPlant()
        workspaceNames.forEach { name -> jdbc.jdbcTemplate.update("DELETE FROM audit_log WHERE details_json ->> 'workspace' = ?", name) }
        workspaceIds.forEach { wsId ->
            jdbc.jdbcTemplate.update("DELETE FROM audit_log WHERE key_id IN (SELECT id FROM api_keys WHERE workspace_id = ?)", wsId)
        }
        userIds.forEach { id -> jdbc.jdbcTemplate.update("DELETE FROM audit_log WHERE user_id = ?", id) }
        workspaceIds.forEach { wsId -> jdbc.jdbcTemplate.update("DELETE FROM api_keys WHERE workspace_id = ?", wsId) }
        keyNames.forEach { name ->
            jdbc.jdbcTemplate.update("DELETE FROM audit_log WHERE user_id IN (SELECT id FROM users WHERE display_name = ?)", name)
            jdbc.jdbcTemplate.update("DELETE FROM users WHERE display_name = ? AND provider_subject LIKE 'dpk_%'", name)
        }
        workspaceIds.forEach { wsId ->
            jdbc.jdbcTemplate.update("DELETE FROM workspace_members WHERE workspace_id = ?", wsId)
            jdbc.jdbcTemplate.update("DELETE FROM workspaces WHERE id = ?", wsId)
        }
        userIds.forEach { id -> jdbc.jdbcTemplate.update("DELETE FROM users WHERE id = ?", id) }
        withClue("every #704 trigger must be dropped - the plant may never outlive the class") {
            count("SELECT COUNT(*) FROM pg_trigger WHERE tgname LIKE 'dp704%'", emptyMap()) shouldBe 0
        }
    }

    // ------------------------------------------------------------------ the fixture

    private fun fixture(): Fixture {
        val sfx = UUID.randomUUID().toString().substring(0, SUFFIX_CHARS)
        val actorEmail = "root-704-$sfx@example.com"
        val memberEmail = "alice-704-$sfx@example.com"
        insertUser(actorEmail, "Root 704", "sub-704-root-$sfx", isAdmin = true)
        insertUser(memberEmail, "Alice 704", "sub-704-alice-$sfx", isAdmin = false)
        val actorId = userIdByEmail(actorEmail).also { userIds.add(it) }
        val memberId = userIdByEmail(memberEmail).also { userIds.add(it) }
        val actor = principalFor(actorId, actorEmail, "Root 704", superAdmin = true)
        val member = principalFor(memberId, memberEmail, "Alice 704", superAdmin = false)
        val wsName = "acme-704-$sfx"
        invoke(workspaceService, "create", actor, wsName, "Acme 704")
        val wsId =
            workspaceIdByName(wsName).also {
                workspaceIds.add(it)
                workspaceNames.add(wsName)
            }
        invoke(workspaceService, "addMember", actor, wsName, memberEmail, enumConstant(WORKSPACE_ROLE_CLASS, "AUTHOR"))
        return Fixture(wsId, wsName, member, memberId, "mcp/704-$sfx".also { keyNames.add(it) })
    }

    /** The member mints an MCP key named [Fixture.keyName] through the PROXIED service (a SERVICE identity is provisioned). */
    private fun issue(fx: Fixture) {
        invoke(
            apiKeyService,
            "issue",
            fx.member,
            fx.keyName,
            fx.workspaceId,
            null,
            enumConstant(API_KEY_KIND_CLASS, "MCP"),
            enumConstant(KEY_ROLE_CLASS, "AUTHOR"),
        )
    }

    private fun insertUser(
        email: String,
        displayName: String,
        providerSubject: String,
        isAdmin: Boolean,
    ) {
        invoke(
            userRepository,
            "insert",
            email,
            displayName,
            null,
            "google",
            providerSubject,
            isAdmin,
            enumConstant(USER_KIND_CLASS, "HUMAN"),
        )
    }

    // ------------------------------------------------------------------ the assertions

    private fun assertProxied() {
        withClue("the service under test must be the transaction-proxied bean") { AopUtils.isAopProxy(apiKeyService) shouldBe true }
    }

    /** The oracle is the plant: the refusal must carry the trigger's own message. */
    private fun assertPlanted(refusal: DataAccessException) {
        withClue("the exception must be the planted trigger, not an incidental failure") { refusal.message shouldContain "planted by #704" }
    }

    /** "A refusal with nothing committed": no key row, no identity, no audit row — read as committed state from SQL. */
    private fun assertNothingCommitted(fx: Fixture) {
        assertSoftly {
            withClue("(a) no api_keys row for the refused issuance") { keyCount(fx) shouldBe 0 }
            withClue("(b) no SERVICE identity for the would-be key") { identityCount(fx) shouldBe 0 }
            withClue("(c) no auth.api_key.created row") { createdAuditCount(fx) shouldBe 0 }
        }
    }

    /**
     * The boundary's answer to this refusal (#704: no code of its own — the catch-all): the REST
     * handler's 500 `internal_error` envelope, fixed text, nothing of the refused row. The ERROR line
     * the boundary writes is captured and printed — the handback quotes it — and must not carry the
     * row's `details` either (here the key name is the only caller-supplied detail).
     */
    private fun assertBoundaryAnswer(
        refusal: DataAccessException,
        fx: Fixture,
    ) {
        val lines = ListAppender<ILoggingEvent>().apply { start() }
        val handlerLog = LoggerFactory.getLogger(ApiExceptionHandler::class.java) as Logger
        handlerLog.addAppender(lines)
        val answer =
            try {
                exceptionHandler.onUnexpected(refusal, MockHttpServletRequest("POST", "/api/v1/auth/api-keys"))
            } finally {
                handlerLog.detachAppender(lines)
            }
        val body = ObjectMapper().findAndRegisterModules().writeValueAsString(answer?.body)
        val logged = lines.list.joinToString("\n") { it.formattedMessage + " | " + it.throwableProxy.render() }
        println("#704 boundary answer: ${answer?.statusCode} $body")
        println("#704 boundary log: $logged")
        assertSoftly {
            answer?.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            body shouldContain "\"internal_error\""
            body shouldNotContain fx.keyName
            body shouldNotContain "planted by #704"
            logged shouldContain "500 POST /api/v1/auth/api-keys: unhandled"
            logged shouldNotContain fx.keyName
        }
    }

    private fun keyCount(fx: Fixture): Int =
        count(
            "SELECT COUNT(*) FROM api_keys WHERE workspace_id = :ws AND name = :name",
            mapOf("ws" to fx.workspaceId, "name" to fx.keyName),
        )

    private fun identityCount(fx: Fixture): Int =
        count("SELECT COUNT(*) FROM users WHERE display_name = :name AND provider_subject LIKE 'dpk_%'", mapOf("name" to fx.keyName))

    private fun createdAuditCount(fx: Fixture): Int =
        count(
            "SELECT COUNT(*) FROM audit_log WHERE event = 'auth.api_key.created' AND details_json ->> 'name' = :name",
            mapOf("name" to fx.keyName),
        )

    private fun revokedAuditCount(keyId: String): Int =
        count("SELECT COUNT(*) FROM audit_log WHERE event = 'auth.api_key.revoked' AND key_id = :id", mapOf("id" to keyId))

    private fun keyRevoked(keyId: String): Boolean =
        requireNotNull(jdbc.queryForObject("SELECT is_revoked FROM api_keys WHERE id = :id", mapOf("id" to keyId), Boolean::class.java)) {
            "the fixture key must still exist when the refusal is asserted"
        }

    private fun identityActive(identityId: UUID): Boolean =
        requireNotNull(jdbc.queryForObject("SELECT is_active FROM users WHERE id = :id", mapOf("id" to identityId), Boolean::class.java)) {
            "the fixture identity must still exist when the refusal is asserted"
        }

    /** The fixture key's id and the SERVICE identity that acts as it. */
    private fun liveKeyOf(fx: Fixture): Pair<String, UUID> =
        jdbc
            .query(
                "SELECT id, user_id FROM api_keys WHERE workspace_id = :ws AND name = :name AND is_revoked = FALSE",
                mapOf("ws" to fx.workspaceId, "name" to fx.keyName),
            ) { rs, _ -> rs.getString("id") to rs.getObject("user_id", UUID::class.java) }
            .single()

    private fun count(
        sql: String,
        params: Map<String, Any?>,
    ): Int = jdbc.queryForObject(sql, params, Int::class.java) ?: 0

    private fun userIdByEmail(email: String): UUID =
        requireNotNull(jdbc.queryForObject("SELECT id FROM users WHERE email = :email", mapOf("email" to email), UUID::class.java)) {
            "the fixture user $email must exist"
        }

    private fun workspaceIdByName(name: String): UUID =
        requireNotNull(jdbc.queryForObject("SELECT id FROM workspaces WHERE name = :name", mapOf("name" to name), UUID::class.java)) {
            "the fixture workspace $name must exist"
        }

    private fun IThrowableProxy?.render(): String = if (this == null) "" else "$className: $message | " + cause.render()

    // ------------------------------------------------------------------ the plant

    /**
     * The refusal and ONE trigger narrowed to [event] and [narrowing] — this run's own row. The
     * narrowing's literals are this class's generated names and ids, never external input.
     */
    private fun plant(
        event: String,
        narrowing: String,
    ) {
        jdbc.jdbcTemplate.execute(REFUSE_FUNCTION_SQL)
        jdbc.jdbcTemplate.execute(
            "CREATE TRIGGER dp704_audit_refuse BEFORE INSERT ON audit_log FOR EACH ROW " +
                "WHEN (NEW.event = '$event' AND $narrowing) EXECUTE FUNCTION dp704_refuse()",
        )
    }

    /** CASCADE on the lane-namespaced function: it can only ever drop THIS lane's trigger. */
    private fun dropPlant() {
        jdbc.jdbcTemplate.execute("DROP TRIGGER IF EXISTS dp704_audit_refuse ON audit_log")
        jdbc.jdbcTemplate.execute("DROP FUNCTION IF EXISTS dp704_refuse() CASCADE")
    }

    // ------------------------------------------------------------------ off-classpath access (RemoveMemberRollbackIntegrationTest's)

    /** Invokes [method] on [bean] reflectively, rethrowing the REAL cause (not the ITE wrapper). */
    private fun invoke(
        bean: Any,
        method: String,
        vararg args: Any?,
    ): Any? =
        runCatching {
            bean.javaClass.methods
                .filter { it.name == method && it.parameterCount == args.size }
                .single()
                .invoke(bean, *args)
        }.getOrElse { throw it.cause ?: it }

    private fun principalFor(
        userId: UUID,
        email: String,
        displayName: String,
        superAdmin: Boolean,
    ): Any {
        val constructor =
            Class.forName(PRINCIPAL_CLASS).kotlin.primaryConstructor
                ?: error("AuthenticatedPrincipal must have a primary constructor")
        val byName = constructor.parameters.associateBy { it.name }
        return constructor.callBy(
            buildMap {
                put(byName.getValue("userId"), userId)
                put(byName.getValue("email"), email)
                put(byName.getValue("displayName"), displayName)
                put(byName.getValue("authMethod"), enumConstant(AUTH_METHOD_CLASS, "OIDC"))
                put(byName.getValue("superAdmin"), superAdmin)
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumConstant(
        className: String,
        constant: String,
    ): Any = java.lang.Enum.valueOf(Class.forName(className) as Class<out Enum<*>>, constant)

    companion object {
        private const val SUFFIX_CHARS = 8
        private const val SECRET_BYTES = 32

        private const val WORKSPACE_SERVICE_BEAN = "workspaceService"
        private const val API_KEY_SERVICE_BEAN = "apiKeyService"
        private const val USER_REPOSITORY_BEAN = "userRepository"

        private const val PRINCIPAL_CLASS = "co.datapipelines.auth.AuthenticatedPrincipal"
        private const val AUTH_METHOD_CLASS = "co.datapipelines.auth.AuthMethod"
        private const val WORKSPACE_ROLE_CLASS = "co.datapipelines.auth.WorkspaceRole"
        private const val USER_KIND_CLASS = "co.datapipelines.auth.UserKind"
        private const val API_KEY_KIND_CLASS = "co.datapipelines.auth.ApiKeyKind"
        private const val KEY_ROLE_CLASS = "co.datapipelines.auth.KeyRole"

        private const val REFUSE_FUNCTION_SQL =
            "CREATE OR REPLACE FUNCTION dp704_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ " +
                "BEGIN RAISE EXCEPTION 'planted by #704: %', TG_TABLE_NAME; END; \$\$"

        private val postgres get() = SharedPostgres.postgres
        private val redis get() = SharedRedis.redis

        private val oidc = OidcDiscoveryStub()

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedRedis.port }
            registry.add("datapipelines.redis.password") { SharedRedis.PASSWORD }

            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }
    }
}
