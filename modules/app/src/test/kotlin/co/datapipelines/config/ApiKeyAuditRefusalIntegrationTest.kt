package co.datapipelines.config

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.DatapipelinesApplication
import co.datapipelines.OidcDiscoveryStub
import co.datapipelines.SharedPostgres
import co.datapipelines.config.ApiKeyAuditFixture.Fixture
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

    private val fixtures by lazy { ApiKeyAuditFixture(context, jdbc) }

    @Test
    fun `an issuance whose audit row is refused is refused itself, with nothing committed (T1, T2)`() {
        val fx = fixtures.fixture()
        fixtures.plant("auth.api_key.created", "NEW.details_json ->> 'name' = '${fx.keyName}'")
        try {
            val outcome = runCatching { fixtures.issue(fx) }
            // T2 — the swallow's shape: issue RETURNED a key. Then its row must have committed, and
            // with the swallow it did not (the aborted transaction's COMMIT was a silent rollback).
            // Unreachable once the refusal propagates.
            if (outcome.isSuccess) {
                withClue("issue returned a key, so the returned key must exist in api_keys") { fixtures.keyCount(fx) shouldBe 1 }
            }
            val refusal = outcome.exceptionOrNull()
            withClue("the refusal must be a DataAccessException: $refusal") { (refusal is DataAccessException) shouldBe true }
            assertPlanted(refusal as DataAccessException)
            assertNothingCommitted(fx)
            assertBoundaryAnswer(refusal, fx)
            fixtures.assertProxied()
        } finally {
            fixtures.dropPlant()
        }
    }

    @Test
    fun `positive control - with no plant the issuance commits a key, its identity and one audit row (T3)`() {
        val fx = fixtures.fixture()
        fixtures.assertProxied()
        fixtures.issue(fx)
        assertSoftly {
            withClue("(control) the key row committed") { fixtures.keyCount(fx) shouldBe 1 }
            withClue("(control) the key's identity committed") { fixtures.identityCount(fx) shouldBe 1 }
            withClue("(control) exactly one auth.api_key.created row committed") { fixtures.createdAuditCount(fx) shouldBe 1 }
        }
    }

    @Test
    fun `a revocation whose audit row is refused is refused itself - the key stays live (T4)`() {
        val fx = fixtures.fixture()
        fixtures.issue(fx)
        val (keyId, identityId) = fixtures.liveKeyOf(fx)
        fixtures.plant("auth.api_key.revoked", "NEW.key_id = '$keyId'")
        try {
            val refusal = shouldThrow<DataAccessException> { fixtures.revokeOwn(keyId, fx.memberId) }
            assertPlanted(refusal)
            assertSoftly {
                withClue("the key stays live") { fixtures.keyRevoked(keyId) shouldBe false }
                withClue("its identity stays active") { fixtures.identityActive(identityId) shouldBe true }
                withClue("no revocation row committed") { fixtures.revokedAuditCount(keyId) shouldBe 0 }
            }
            fixtures.assertProxied()
        } finally {
            fixtures.dropPlant()
        }
    }

    @AfterAll
    fun afterAll() {
        fixtures.cleanup()
    }

    /** The oracle is the plant: the refusal must carry the trigger's own message. */
    private fun assertPlanted(refusal: DataAccessException) {
        withClue("the exception must be the planted trigger, not an incidental failure") { refusal.message shouldContain "planted by #704" }
    }

    /** "A refusal with nothing committed": no key row, no identity, no audit row — read as committed state from SQL. */
    private fun assertNothingCommitted(fx: Fixture) {
        assertSoftly {
            withClue("(a) no api_keys row for the refused issuance") { fixtures.keyCount(fx) shouldBe 0 }
            withClue("(b) no SERVICE identity for the would-be key") { fixtures.identityCount(fx) shouldBe 0 }
            withClue("(c) no auth.api_key.created row") { fixtures.createdAuditCount(fx) shouldBe 0 }
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

    private fun IThrowableProxy?.render(): String = if (this == null) "" else "$className: $message | " + cause.render()

    companion object {
        private const val SECRET_BYTES = 32

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
