package co.datapipelines.datasources

import co.datapipelines.typesystem.guard.MetadataWriterGuard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun `a budgeted guarded repair grants every lacking workspace inside its budget - #841 R4`() {
        register("shared", owner = null)
        repository.grant("shared", alpha, actor) shouldBe true

        budgeted(Duration.ofSeconds(REPAIR_BUDGET_SECONDS)).grantToEveryWorkspace("shared", actor) shouldBe 1

        grantees("shared") shouldBe setOf(alpha, beta)
    }

    @Test
    fun `a budgeted repair's INSERT queued behind a conflicting writer is cancelled at the budget, nothing granted - #841 R4`() {
        register("shared", owner = null)
        val executor = Executors.newSingleThreadExecutor()
        // A conflicting writer in flight: its uncommitted grant of alpha makes the repair's INSERT wait on its transaction.
        val holder =
            DriverManager.getConnection(
                SharedPostgres.postgres.jdbcUrl,
                SharedPostgres.postgres.username,
                SharedPostgres.postgres.password,
            )
        try {
            holder.autoCommit = false
            holder
                .prepareStatement("INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) VALUES ('shared', ?, ?)")
                .use { ps ->
                    ps.setObject(1, alpha)
                    ps.setObject(2, actor)
                    ps.executeUpdate()
                }
            val repair = budgeted(Duration.ofMillis(SHORT_BUDGET_MILLIS))
            val started = System.nanoTime()
            val outcome = executor.submit<Throwable?> { runCatching { repair.grantToEveryWorkspace("shared", actor) }.exceptionOrNull() }
            // Generous and named: it bounds only a red run (an uncapped INSERT waits until the holder ends).
            val failure = outcome.get(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS)
            val elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis()

            withClue("the repair failed on its statement bound: $failure") { sqlStatesOf(failure) shouldContain "57014" }
            withClue("cancelled at the budget, not before it") { elapsed shouldBeInRange BUDGET_WINDOW_MS }
            withClue("the conflicting writer is untouched") { holder.isClosed shouldBe false }
        } finally {
            holder.rollback()
            holder.close()
            executor.shutdownNow()
        }
        withClue("the rolled-back repair granted nothing") { grantees("shared") shouldBe emptySet() }
    }

    @Test
    fun `a budget spent before the repair's next statement refuses it with nothing granted - #841 R4`() {
        register("shared", owner = null)
        // The first read fixes the deadline; every later read is past it.
        val reads = AtomicInteger()
        val clock = { if (reads.getAndIncrement() == 0) 0L else TimeUnit.SECONDS.toNanos(REPAIR_BUDGET_SECONDS * 2) }

        shouldThrow<TransactionTimedOutException> {
            budgeted(Duration.ofSeconds(REPAIR_BUDGET_SECONDS), clock).grantToEveryWorkspace("shared", actor)
        }

        grantees("shared") shouldBe emptySet()
    }

    /**
     * The freshness tick's shape (#841 R4): guarded writes in a real metadata transaction on the SAME DataSource as the
     * repository's template (so its statements join it), the no-op guard (the lock protocol is the web module's proof),
     * and a repair budget.
     */
    private fun budgeted(
        budget: Duration,
        clock: () -> Long = System::nanoTime,
    ): DatasourceGrantRepository {
        val dataSource = checkNotNull(jdbc.jdbcTemplate.dataSource) { "the template has a DataSource" }
        val writes = DatasourceWriteGuarding(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), MetadataWriterGuard.NONE)
        return DatasourceGrantRepository(jdbc, writes, repairBudget = budget, nanoTime = clock)
    }

    private fun grantees(datasource: String): Set<UUID> =
        jdbc
            .queryForList(
                "SELECT workspace_id FROM datasource_workspaces WHERE datasource_name = :name",
                mapOf("name" to datasource),
                UUID::class.java,
            ).toSet()

    private fun sqlStatesOf(failure: Throwable?): List<String> =
        generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().mapNotNull { it.sqlState }.toList()

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

    private companion object {
        const val REPAIR_BUDGET_SECONDS = 5L
        const val SHORT_BUDGET_MILLIS = 1_500L
        const val OUTCOME_WAIT_SECONDS = 20L

        /** The short budget, less the start-up of the repair's first statements, up to a slack of 2 s. */
        val BUDGET_WINDOW_MS = 1_000L..3_500L
    }
}
