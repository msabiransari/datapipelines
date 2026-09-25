package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.aop.support.AopUtils
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import java.util.UUID
import java.util.function.Supplier

/**
 * #215 B4: an API key and its `service` identity are created in ONE transaction — "a half-created
 * pair cannot exist". Proven against a real database and a real Spring transaction proxy: the
 * identity is written by the shipped [UserService]/[UserRepository], and the key's insert is made
 * to FAIL after it (a key repository that throws), which is the only moment an orphan identity
 * could be left behind.
 *
 * The control is the same service WITHOUT its proxy — the same calls, the same failure — which
 * leaves the orphan row. So the suite fails for the right reason if the annotation, the manager
 * name or the proxying is lost: the proxied case then looks exactly like the control.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyIssuanceTransactionIntegrationTest {
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private val workspaceId = UUID.randomUUID()
    private val issuerId = UUID.randomUUID()

    @BeforeAll
    fun start() {
        jdbc = NamedParameterJdbcTemplate(dataSource)
        context =
            AnnotationConfigApplicationContext().apply {
                register(TransactionConfig::class.java)
                registerBean("apiKeyService", ApiKeyService::class.java, Supplier { apiKeyService() })
                refresh()
            }
    }

    @AfterAll
    fun stop() {
        context.close()
    }

    @BeforeEach
    fun clean() {
        jdbc.jdbcTemplate.execute("DELETE FROM users WHERE provider = '${UserService.KEY_PROVIDER}'")
    }

    /** The shipped service over the real identity path; only the key store and the issuance guard are doubles. */
    private fun apiKeyService(): ApiKeyService {
        val properties = AuthProperties()
        val cache = AuthCache(properties)
        val userService = UserService(UserRepository(jdbc), cache, properties, AuditLogger(jdbc, ObjectMapper()))
        val failingKeys =
            mockk<ApiKeyRepository> {
                // Keys v2 A18: the name check runs BEFORE the insert; the probe name is free.
                every { liveNameExists(any(), any()) } returns false
                every { insert(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                    DataIntegrityViolationException("the key insert failed after the identity was written")
            }
        val workspaceService =
            mockk<WorkspaceService> {
                every { requireIssuancePermission(any(), any(), any()) } returns
                    WorkspaceContext(workspaceId, "acme", WorkspaceRole.WORKSPACE_ADMIN)
            }
        return ApiKeyService(
            failingKeys,
            userService,
            cache,
            mockk(relaxed = true),
            Argon2SecretHasher(),
            workspaceService,
            PrincipalLiveness(userService) { true },
        )
    }

    private val issuer =
        AuthenticatedPrincipal(
            userId = issuerId,
            email = "admin@company.com",
            displayName = "Admin",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.WORKSPACE_ADMIN),
        )

    private fun identityRows(name: String): Int =
        jdbc.jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE provider = ? AND display_name = ?",
            Int::class.java,
            UserService.KEY_PROVIDER,
            name,
        ) ?: 0

    @Test
    fun `a failed key insert rolls the identity back with it - no orphan identity`() {
        val proxied = context.getBean("apiKeyService", ApiKeyService::class.java)
        withClue("the bean the application calls is a transaction proxy") { AopUtils.isAopProxy(proxied) shouldBe true }

        shouldThrow<DataIntegrityViolationException> {
            proxied.issue(issuer, "orphan-probe", workspaceId, kind = ApiKeyKind.ENDPOINT)
        }

        withClue("the identity was written inside the issuance transaction, so it must be gone") {
            identityRows("orphan-probe") shouldBe 0
        }
    }

    @Test
    fun `the control - the same failure without the transaction leaves the orphan`() {
        shouldThrow<DataIntegrityViolationException> {
            apiKeyService().issue(issuer, "orphan-control", workspaceId, kind = ApiKeyKind.ENDPOINT)
        }

        withClue("non-vacuity: without the proxy the identity row survives the failed key insert") {
            identityRows("orphan-control") shouldBe 1
        }
    }

    /** The production wiring's transaction half: the named manager (`modules/app`'s), CGLIB proxies. */
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TransactionConfig {
        @Bean("metadataTransactionManager")
        open fun metadataTransactionManager(): PlatformTransactionManager = DataSourceTransactionManager(dataSource)
    }

    companion object {
        /** ONE data source for the manager and the repositories, so the writes join the transaction. */
        private val dataSource: DriverManagerDataSource by lazy { SharedPostgres.dataSource() }
    }
}
