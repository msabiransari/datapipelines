package co.datapipelines.datasources

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * [DatasourceGrantRepository] against the shipped schema — `datasource_workspaces` as V23
 * creates it (metadata-db §4.16).
 *
 * The grant IS the visibility rule (D-R7), so these are not bookkeeping assertions: every
 * `findVisibleByName` / `listVisible` answer in the product is decided by a row this
 * repository wrote, and [DatasourceRepositoryIntegrationTest] proves the read side against
 * rows written here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasourceGrantRepositoryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: DatasourceGrantRepository
    private lateinit var datasources: DatasourceRepository
    private lateinit var actor: UUID
    private lateinit var alpha: UUID
    private lateinit var beta: UUID

    private val encryptor = testEncryptor()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    }

    @BeforeEach
    fun setUp() {
        repository = DatasourceGrantRepository(jdbc)
        datasources = DatasourceRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE datasource_workspaces, datasources, workspaces, users CASCADE")
        actor = insertUser("granter@example.com", "sub-granter")
        alpha = insertWorkspace("alpha")
        beta = insertWorkspace("beta")
    }

    @Test
    fun `a grant makes one datasource visible to one workspace, and nothing else`() {
        register("shared", owner = null)
        register("alpha-only", owner = alpha)

        repository.grant("shared", alpha, actor) shouldBe true

        repository.isGranted("shared", alpha) shouldBe true
        repository.isGranted("shared", beta) shouldBe false
        // Ownership alone is NOT visibility — the read predicate consults this table only.
        repository.isGranted("alpha-only", alpha) shouldBe false
        datasources.findVisibleByName("shared", alpha)?.name shouldBe "shared"
        datasources.findVisibleByName("shared", beta) shouldBe null
    }

    @Test
    fun `re-granting is success and keeps the ORIGINAL actor - the first grant is the decision`() {
        register("shared", owner = null)
        val second = insertUser("later@example.com", "sub-later")

        repository.grant("shared", alpha, actor) shouldBe true
        val first = repository.grantsOf("shared").single()

        // Second call: no new row, so `false`, and the recorded decision is untouched.
        repository.grant("shared", alpha, second) shouldBe false
        val after = repository.grantsOf("shared").single()
        after.grantedBy shouldBe actor
        after.grantedAt shouldBe first.grantedAt
    }

    @Test
    fun `grantOnRegistration is the idempotent form - registering twice is not an error`() {
        register("owned", owner = alpha)

        repository.grantOnRegistration("owned", alpha, actor)
        repository.grantOnRegistration("owned", alpha, actor)

        repository.grantsOf("owned").map { it.workspaceName } shouldContainExactly listOf("alpha")
    }

    @Test
    fun `grantsOf lists every workspace in name order, with who granted it`() {
        register("shared", owner = null)
        repository.grant("shared", beta, actor) shouldBe true
        repository.grant("shared", alpha, actor) shouldBe true

        val grants = repository.grantsOf("shared")
        grants.map { it.workspaceName } shouldContainExactly listOf("alpha", "beta")
        grants.map { it.grantedBy }.toSet() shouldBe setOf(actor)
        grants.map { it.datasourceName }.toSet() shouldBe setOf("shared")
    }

    @Test
    fun `grantAllInstanceDatasourcesTo takes the unowned rows only, skips the deleted, and counts the NEW grants`() {
        register("instance-a", owner = null)
        register("instance-b", owner = null)
        register("owned-by-alpha", owner = alpha)
        register("instance-gone", owner = null)
        datasources.softDelete("instance-gone") shouldBe true
        // Already granted: kept with its original actor, and NOT counted again.
        repository.grant("instance-a", beta, actor) shouldBe true

        repository.grantAllInstanceDatasourcesTo(beta, actor) shouldBe 1

        jdbc
            .queryForList(
                "SELECT datasource_name FROM datasource_workspaces WHERE workspace_id = :ws ORDER BY datasource_name",
                mapOf("ws" to beta),
                String::class.java,
            ).shouldContainExactly(listOf("instance-a", "instance-b"))
    }

    @Test
    fun `revoke removes the grant and says whether there was one - the datasource itself is untouched`() {
        register("shared", owner = null)
        repository.grant("shared", alpha, actor) shouldBe true

        repository.revoke("shared", alpha) shouldBe true
        repository.revoke("shared", alpha) shouldBe false

        repository.isGranted("shared", alpha) shouldBe false
        datasources.findByName("shared")?.isDeleted shouldBe false
    }

    private fun register(
        name: String,
        owner: UUID?,
    ) {
        val datasource = Fixtures.postgres(name = name).copy(ownerWorkspaceId = owner)
        datasources.create(datasource, encryptor.encrypt("p", name), actor)
    }

    private fun insertUser(
        email: String,
        subject: String,
    ): UUID =
        checkNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO users (email, display_name, provider, provider_subject)
                VALUES (:email, 'Granter', 'google', :subject)
                RETURNING id
                """.trimIndent(),
                mapOf("email" to email, "subject" to subject),
                UUID::class.java,
            ),
        )

    private fun insertWorkspace(name: String): UUID =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO workspaces (name, display_name) VALUES (:name, :name) RETURNING id",
                mapOf("name" to name),
                UUID::class.java,
            ),
        )
}
