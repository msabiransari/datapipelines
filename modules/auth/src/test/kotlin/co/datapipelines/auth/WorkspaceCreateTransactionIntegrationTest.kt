package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.aop.support.AopUtils
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.function.Supplier

/**
 * #823 (owner ruling 2026-11-07, option 1): the managed lake's grant is made INSIDE workspace creation's metadata
 * transaction, so a workspace never exists without it and a grant that fails leaves no workspace.
 *
 * Proven against a real Postgres and a real Spring transaction proxy, over the shipped [WorkspaceService] and the
 * shipped [WorkspaceRepository], with the same transaction manager the production wiring names. The port is a double
 * (the real implementation lives in `web`, over `datasources`, which `auth` may not see — module-structure §4.2), but a
 * double that does what the real one does: it WRITES a row, through the same data source, so a write that is not on the
 * creation's transaction cannot be rolled back with it. The real grant row is asserted by
 * `ManagedLakeReadsE2eTest`'s `#823` cases.
 *
 * The control is the same service WITHOUT its proxy — the same calls, the same failure — which leaves the workspace
 * behind. So the suite goes red for the right reason if the annotation, the manager's name or the proxying is lost.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceCreateTransactionIntegrationTest {
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private val suffix = UUID.randomUUID().toString().substring(0, SUFFIX_CHARS)
    private val creatorEmail = "root-823-$suffix@example.com"
    private lateinit var creator: AuthenticatedPrincipal

    /** What the double saw: the workspace it was handed, and whether a transaction was active on its thread. */
    private val handed = mutableListOf<UUID>()
    private var transactionActiveInPort: Boolean? = null
    private var portFailure: RuntimeException? = null
    private var raceTheNameCheck = false

    private val createdNames = mutableListOf<String>()

    @BeforeAll
    fun start() {
        jdbc = NamedParameterJdbcTemplate(dataSource)
        jdbc.jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS dp823_grant_marker (workspace_id UUID PRIMARY KEY)")
        val user = UserRepository(jdbc).insert(creatorEmail, "Root 823", null, "test", "sub-823-$suffix", isAdmin = true)
        creator =
            AuthenticatedPrincipal(
                userId = user.id,
                email = creatorEmail,
                displayName = "Root 823",
                authMethod = AuthMethod.OIDC,
                superAdmin = true,
            )
        context =
            AnnotationConfigApplicationContext().apply {
                register(TransactionConfig::class.java)
                registerBean("workspaceService", WorkspaceService::class.java, Supplier { workspaceService() })
                refresh()
            }
    }

    @AfterAll
    fun stop() {
        context.close()
        createdNames.forEach { name ->
            jdbc.update("DELETE FROM audit_log WHERE details_json ->> 'workspace' = :name", MapSqlParameterSource("name", name))
            jdbc.update(
                "DELETE FROM dp823_grant_marker WHERE workspace_id IN (SELECT id FROM workspaces WHERE name = :name)",
                MapSqlParameterSource("name", name),
            )
            jdbc.update(
                "DELETE FROM workspace_members WHERE workspace_id IN (SELECT id FROM workspaces WHERE name = :name)",
                MapSqlParameterSource("name", name),
            )
            jdbc.update("DELETE FROM workspaces WHERE name = :name", MapSqlParameterSource("name", name))
        }
        jdbc.update("DELETE FROM audit_log WHERE user_id = :id", MapSqlParameterSource("id", creator.userId))
        jdbc.update("DELETE FROM users WHERE id = :id", MapSqlParameterSource("id", creator.userId))
        jdbc.jdbcTemplate.execute("DROP TABLE IF EXISTS dp823_grant_marker")
    }

    /** The shipped service over the real repository and audit trail; only the port (and the ctor's unused collaborators) are doubles. */
    private fun workspaceService(): WorkspaceService {
        val properties = AuthProperties()
        val real = WorkspaceRepository(jdbc)
        // A racing create: the name check says "free" while the unique index says otherwise, so the REAL insert raises
        // the DuplicateKeyException the service maps. Everything else is the real repository.
        val repository =
            spyk(real) {
                every { nameExists(any()) } answers { if (raceTheNameCheck) false else real.nameExists(firstArg()) }
            }
        return WorkspaceService(
            repository,
            mockk(relaxed = true),
            UserRepository(jdbc),
            AuthCache(properties),
            null,
            AuditLogger(jdbc, ObjectMapper()),
            mockk(relaxed = true),
            properties,
            managedDatasourceGrants =
                ManagedDatasourceGrants { workspaceId ->
                    handed += workspaceId
                    transactionActiveInPort = TransactionSynchronizationManager.isActualTransactionActive()
                    // The grant's own write, through the shared data source: it commits or rolls back with the creation.
                    jdbc.update("INSERT INTO dp823_grant_marker (workspace_id) VALUES (:id)", MapSqlParameterSource("id", workspaceId))
                    portFailure?.let { throw it }
                    true
                },
        )
    }

    private fun name(label: String) = "$label-823-$suffix".also { createdNames += it }

    private fun reset() {
        handed.clear()
        transactionActiveInPort = null
        portFailure = null
        raceTheNameCheck = false
    }

    private fun workspaceRows(name: String) = count("SELECT COUNT(*) FROM workspaces WHERE name = :name", name)

    private fun memberRows(name: String) =
        count(
            "SELECT COUNT(*) FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id WHERE w.name = :name",
            name,
        )

    private fun markerRows(name: String) =
        count(
            "SELECT COUNT(*) FROM dp823_grant_marker g JOIN workspaces w ON w.id = g.workspace_id WHERE w.name = :name",
            name,
        )

    private fun auditRows(name: String) =
        count("SELECT COUNT(*) FROM audit_log WHERE event = 'auth.workspace.created' AND details_json ->> 'workspace' = :name", name)

    private fun count(
        sql: String,
        name: String,
    ): Int = jdbc.queryForObject(sql, MapSqlParameterSource("name", name), Int::class.java) ?: 0

    @Test
    fun `the grant runs on the creation's transaction and commits with the workspace, its membership and its audit row`() {
        reset()
        val name = name("ok")
        val proxied = context.getBean("workspaceService", WorkspaceService::class.java)
        withClue("the bean the application calls is a transaction proxy") { AopUtils.isAopProxy(proxied) shouldBe true }

        val created = proxied.create(creator, name, "Ok")

        assertSoftly {
            withClue("the port was handed exactly the created workspace") { handed shouldBe listOf(created.id) }
            withClue("the port ran inside a transaction") { transactionActiveInPort shouldBe true }
            withClue("the workspace row") { workspaceRows(name) shouldBe 1 }
            withClue("the creator's membership row") { memberRows(name) shouldBe 1 }
            withClue("the grant's write") { markerRows(name) shouldBe 1 }
            withClue("the creation's audit row") { auditRows(name) shouldBe 1 }
        }
    }

    @Test
    fun `a grant that fails rolls the workspace, its membership and the grant's write back - no workspace without the grant`() {
        reset()
        val name = name("fail")
        val proxied = context.getBean("workspaceService", WorkspaceService::class.java)
        portFailure = IllegalStateException("planted by #823")

        val refusal = shouldThrow<IllegalStateException> { proxied.create(creator, name, "Fail") }

        assertSoftly {
            withClue("the exception is the port's own, not a transaction wrapper") { refusal.message shouldBe "planted by #823" }
            withClue("the port was reached (the failure is the grant's)") { handed.size shouldBe 1 }
            withClue("the workspace row rolled back") { workspaceRows(name) shouldBe 0 }
            withClue("the creator's membership rolled back") { memberRows(name) shouldBe 0 }
            withClue("the grant's write rolled back") { markerRows(name) shouldBe 0 }
            withClue("no creation audit for a creation that did not complete") { auditRows(name) shouldBe 0 }
        }
    }

    @Test
    fun `the control - the same failure without the transaction leaves the workspace behind`() {
        reset()
        val name = name("control")
        portFailure = IllegalStateException("planted by #823")

        shouldThrow<IllegalStateException> { workspaceService().create(creator, name, "Control") }

        withClue("non-vacuity: without the proxy the workspace row survives the failed grant") {
            workspaceRows(name) shouldBe 1
        }
    }

    /**
     * The repository's `DuplicateKeyException` is caught INSIDE the now-transactional method and rethrown as the
     * catalogued exception. The caller must get that one — not an `UnexpectedRollbackException`, not the raw
     * constraint violation — and the port must not have run for a workspace that was never written.
     */
    @Test
    fun `a racing duplicate name still reaches the caller as the catalogued exception, and grants nothing`() {
        reset()
        val name = name("dup")
        val proxied = context.getBean("workspaceService", WorkspaceService::class.java)
        proxied.create(creator, name, "First")
        reset()
        raceTheNameCheck = true

        val refusal = shouldThrow<WorkspaceDuplicateNameException> { proxied.create(creator, name, "Second") }

        assertSoftly {
            withClue("the catalogued code") { refusal.code shouldBe WorkspaceErrorCodes.DUPLICATE_NAME }
            withClue("the port never ran for the refused creation") { handed.size shouldBe 0 }
            withClue("the first workspace is untouched") { workspaceRows(name) shouldBe 1 }
            withClue("and still has its one grant write") { markerRows(name) shouldBe 1 }
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
        private const val SUFFIX_CHARS = 8

        /** ONE data source for the manager and the repositories, so the writes join the transaction. */
        private val dataSource: DriverManagerDataSource by lazy { SharedPostgres.dataSource() }
    }
}
