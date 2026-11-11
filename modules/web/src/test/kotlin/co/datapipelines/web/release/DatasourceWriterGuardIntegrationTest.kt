package co.datapipelines.web.release

import co.datapipelines.datasources.CredentialKind
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.datasources.DatasourceGrantRepository
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.datasources.DatasourceWriteGuarding
import co.datapipelines.executor.BoundedJdbc
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.guard.PrerequisiteKey
import co.datapipelines.web.SharedPostgres
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * #841 (G0) — the datasource writers under the production guard on a REAL PostgreSQL (brief §B datasource adoption,
 * §C layer (b)): definition writes and grant writes take the datasource's prerequisite key, then every workspace the
 * change is visible in, inside one metadata transaction. Each case holds one lock on a raw connection and observes
 * the production writer WAIT on it (the guard's absence makes the writer finish first — the falsification's red).
 */
class DatasourceWriterGuardIntegrationTest {
    private val rows = GuardRows()
    private val dataSource = SharedPostgres.dataSource()
    private val named = NamedParameterJdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    private val pool = Executors.newCachedThreadPool()
    private val holder = LockHolder()

    private fun writes(boundSeconds: Int = BOUND_SECONDS) =
        DatasourceWriteGuarding(named, transactions, JdbcReleaseMutationGuard(JdbcTemplate(dataSource), boundSeconds))

    private fun repository(writes: DatasourceWriteGuarding = writes()) = DatasourceRepository(named, writes = writes)

    private fun grants(writes: DatasourceWriteGuarding = writes()) = DatasourceGrantRepository(named, writes)

    private fun <T> async(block: () -> T): Future<T> = pool.submit<T> { block() }

    @AfterEach
    fun tearDown() {
        holder.close()
        pool.shutdownNow()
        pool.awaitTermination(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
        rows.close()
    }

    @Test
    fun `a grant to a non-owning workspace waits on the datasource's held prerequisite key`() {
        val owner = rows.workspace()
        val other = rows.workspace()
        val name = rows.datasource("shared", owner = owner)
        holder.prerequisite(PrerequisiteKey.datasource(name))
        val grant = async { grants().grant(name, other, rows.user) }

        awaitBlockedBy(grant)
        holder.commit()
        grant.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS) shouldBe true
        rows.grantees(name) shouldContain other
    }

    @Test
    fun `a grant waits on the held row of the workspace it changes`() {
        val other = rows.workspace()
        val name = rows.datasource("instance")
        holder.workspace(other)
        val grant = async { grants().grant(name, other, rows.user) }

        awaitBlockedBy(grant)
        holder.commit()
        grant.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `a revoke waits on the held row of the workspace it changes`() {
        val grantee = rows.workspace()
        val name = rows.datasource("revoked").also { rows.grant(it, grantee) }
        holder.workspace(grantee)
        val revoke = async { grants().revoke(name, grantee) }

        awaitBlockedBy(revoke)
        holder.commit()
        revoke.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `a delete waits on a held grantee row - the definition change is visible there`() {
        val grantee = rows.workspace()
        val name = rows.datasource("deleted").also { rows.grant(it, grantee) }
        holder.workspace(grantee)
        val delete = async { repository().softDelete(name) }

        awaitBlockedBy(delete)
        holder.commit()
        delete.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `a credential update waits on the datasource's held key`() {
        val name = rows.datasource("rotated")
        // A token-kind row: the credential-present CHECK forbids a ciphertext on a `none` row.
        rows.jdbc.update("UPDATE datasources SET credential_kind = 'token', credential_encrypted = ? WHERE name = ?", byteArrayOf(9), name)
        holder.prerequisite(PrerequisiteKey.datasource(name))
        val rotate = async { repository().updateCredential(name, byteArrayOf(1, 2, 3)) }

        awaitBlockedBy(rotate)
        holder.commit()
        rotate.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `a create of a name that does not exist yet waits on that name's key and on its owner's row`() {
        val owner = rows.workspace()
        val name = rows.datasourceName("absent")
        holder.prerequisite(PrerequisiteKey.datasource(name))
        val create = async { repository().create(definition(name, owner), null, rows.user) }

        awaitBlockedBy(create)
        holder.commit()
        create.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS).shouldNotBeNull()

        val nextName = rows.datasourceName("absent2")
        LockHolder().use { rowHolder ->
            rowHolder.workspace(owner)
            val second = async { repository().create(definition(nextName, owner), null, rows.user) }
            LockObserver.awaitWaiter(rowHolder, second).shouldNotBeNull().blockers shouldContain rowHolder.pid
            rowHolder.commit()
            second.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS).shouldNotBeNull()
        }
    }

    @Test
    fun `an exhausted wait refuses datasource write busy with the 55P03 cause and writes nothing`() {
        val other = rows.workspace()
        val name = rows.datasource("busy")
        holder.prerequisite(PrerequisiteKey.datasource(name))

        val refusal = shouldThrow<DatapipelinesException> { grants(writes(boundSeconds = 1)).grant(name, other, rows.user) }
        refusal.code shouldBe DatasourceErrorCodes.WRITE_BUSY
        refusal.details shouldBe mapOf("lock_wait_seconds" to 1)
        generateSequence(refusal.cause) { it.cause }.filterIsInstance<SQLException>().first().sqlState shouldBe "55P03"
        rows.grantees(name).shouldBeEmpty()
    }

    @Test
    fun `the managed-lake repair fails closed on an exhausted wait - the busy refusal propagates, nothing is granted`() {
        rows.workspace() // a live workspace lacking the grant: the repair has a row to change
        val name = rows.datasource("lake-busy")
        holder.prerequisite(PrerequisiteKey.datasource(name))

        // The repair's caller (the freshness tick) turns this into one WARN and retries on its next tick
        // (`ManagedLakeFreshnessSchedulerTest`); the boot pass lets it fail the boot step.
        val refusal = shouldThrow<DatapipelinesException> { grants(writes(boundSeconds = 1)).grantToEveryWorkspace(name, rows.user) }
        refusal.code shouldBe DatasourceErrorCodes.WRITE_BUSY
        generateSequence(refusal.cause) { it.cause }.filterIsInstance<SQLException>().first().sqlState shouldBe "55P03"
        rows.grantees(name).shouldBeEmpty()
    }

    // ------------------------------------------------------------------ #841 R4: the freshness repair's one budget

    /**
     * #841 R4 — the repair as the freshness tick builds it: its statements on a template bounded at the tick's 5 s, a
     * [REPAIR_BUDGET_SECONDS] budget for the whole guarded write, and the guard configured at [BOUND_SECONDS] (20 s, far
     * above the budget — `lock-wait-seconds` admits up to 60) so that only the budget can end a wait inside it.
     */
    private fun tickRepair() =
        DatasourceGrantRepository(
            BoundedJdbc.bounded(named, REPAIR_BUDGET_SECONDS, "REPAIR_BUDGET_SECONDS"),
            writes(),
            repairBudget = Duration.ofSeconds(REPAIR_BUDGET_SECONDS.toLong()),
        )

    /**
     * A conflicting writer that does not take the key: its uncommitted grant row makes the repair's INSERT wait on the
     * unique key — the test's table lock in `ManagedLakeReadsE2eTest`, narrowed to one row of this test's own datasource
     * so the module's other suites on the shared database never wait on it.
     */
    private fun conflictingRow(
        name: String,
        workspace: UUID,
    ) = LockHolder().execute(
        "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) VALUES (?, ?, ?)",
        name,
        workspace,
        rows.user,
    )

    @Test
    fun `the budgeted repair that owns the key has its INSERT cancelled 57014 at the budget - nothing granted - #841 R4`() {
        val lacking = rows.workspace()
        val name = rows.datasource("budget-insert")
        conflictingRow(name, lacking).use { row ->
            val started = System.nanoTime()
            val repair = async { tickRepair().grantToEveryWorkspace(name, rows.user) }
            val waiter = LockObserver.awaitWaiter(row, repair).shouldNotBeNull()
            withClue("the INSERT-owning repair waits on the conflicting row, holding the datasource's key: $waiter") {
                waiter.query shouldContain "INSERT INTO datasource_workspaces"
                LockObserver.holdersOf(PrerequisiteKey.datasource(name)) shouldBe 1
            }
            val failure = failureOf(repair)
            val elapsed = millisSince(started)
            withClue("the INSERT's own statement bound fired: $failure") { sqlStatesOf(failure) shouldContain "57014" }
            withClue("queued INSERT ended at the budget, elapsed=$elapsed ms") { elapsed shouldBeInRange BUDGET_WINDOW_MS }
            withClue("the conflicting writer's transaction is still open: its row was never released") {
                openTransaction(row.pid) shouldBe
                    true
            }
        }
        withClue("the rolled-back repair granted nothing") { rows.grantees(name).shouldBeEmpty() }
        LockObserver.holdersOf(PrerequisiteKey.datasource(name)) shouldBe 0
    }

    @Test
    fun `a budgeted repair waiting on the held key refuses busy 55P03 at the budget, not at the 20 s lock wait - #841 R4`() {
        rows.workspace()
        val name = rows.datasource("budget-key")
        holder.prerequisite(PrerequisiteKey.datasource(name))
        val started = System.nanoTime()
        val repair = async { tickRepair().grantToEveryWorkspace(name, rows.user) }

        awaitBlockedBy(repair)
        val failure = failureOf(repair)
        val elapsed = millisSince(started)
        withClue("the peer refused at the key, never reaching its INSERT: $failure") {
            (failure as? DatapipelinesException)?.code shouldBe DatasourceErrorCodes.WRITE_BUSY
            sqlStatesOf(failure) shouldContain "55P03"
        }
        withClue("refused at the budget, elapsed=$elapsed ms (the guard's own bound is ${BOUND_SECONDS}s)") {
            elapsed shouldBeInRange BUDGET_WINDOW_MS
        }
        withClue("the holder still holds the key") { LockObserver.holdersOf(PrerequisiteKey.datasource(name)) shouldBe 1 }
        rows.grantees(name).shouldBeEmpty()
    }

    @Test
    fun `a key wait and the INSERT wait after it share ONE budget - the repair never runs two bounds back to back - #841 R4`() {
        val lacking = rows.workspace()
        val name = rows.datasource("budget-both")
        conflictingRow(name, lacking).use { row ->
            holder.prerequisite(PrerequisiteKey.datasource(name))
            val started = System.nanoTime()
            val repair = async { tickRepair().grantToEveryWorkspace(name, rows.user) }
            awaitBlockedBy(repair)
            // The held key spends part of the budget — the gate-red interleaving: a sibling's repair held the key while
            // its own INSERT waited. Before R4 the INSERT then got a full bound of its own (2.5 + 5 s).
            Thread.sleep(KEY_HOLD_MILLIS)
            holder.commit()
            val insertWaiter = LockObserver.awaitWaiter(row, repair)
            withClue("after the key was released the repair reached its INSERT, queued on the conflicting row") {
                insertWaiter.shouldNotBeNull().query shouldContain "INSERT INTO datasource_workspaces"
            }
            val failure = failureOf(repair)
            val elapsed = millisSince(started)
            withClue("the INSERT was cancelled at what was left of the budget: $failure") { sqlStatesOf(failure) shouldContain "57014" }
            withClue("key wait + INSERT wait = one budget, elapsed=$elapsed ms (two bounds would be ~${KEY_HOLD_MILLIS + 5_000} ms)") {
                elapsed shouldBeInRange BUDGET_WINDOW_MS
            }
        }
        rows.grantees(name).shouldBeEmpty()
    }

    @Test
    fun `the budgeted repair with nothing in its way grants and commits - #841 R4`() {
        val lacking = rows.workspace()
        val name = rows.datasource("budget-free")
        tickRepair().grantToEveryWorkspace(name, rows.user) shouldBeGreaterThanOrEqual 1
        rows.grantees(name) shouldContain lacking
    }

    /** Whether backend [pid] is inside an open transaction, read on a fresh connection. */
    private fun openTransaction(pid: Int): Boolean =
        rows.jdbc.queryForObject("SELECT state = 'idle in transaction' FROM pg_stat_activity WHERE pid = ?", Boolean::class.java, pid) ==
            true

    /** What [writer] failed with (it must fail), unwrapped from the executor. */
    private fun failureOf(writer: Future<*>): Throwable {
        val thrown = runCatching { writer.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS) }.exceptionOrNull()
        withClue("the repair was expected to fail at its budget") { thrown.shouldNotBeNull() }
        return (thrown as? ExecutionException)?.cause ?: checkNotNull(thrown)
    }

    private fun sqlStatesOf(e: Throwable): List<String> =
        generateSequence(e) {
            it.cause.takeUnless { cause ->
                cause === it
            }
        }.filterIsInstance<SQLException>().mapNotNull { it.sqlState }.toList()

    private fun millisSince(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    @Test
    fun `the instance-grant replan picks up a datasource registered while it waited - under the original deadline`() {
        val target = rows.workspace()
        val existing = rows.datasource("i1")
        val arriving = rows.datasourceName("i2")
        holder
            .prerequisite(PrerequisiteKey.datasource(existing))
            .execute(
                "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by)" +
                    " VALUES (?, 'G0', 'POSTGRES', 'jdbc:postgresql://g0.invalid/db', 'none', ?)",
                arriving,
                rows.user,
            )
        val seed = async { grants().grantAllInstanceDatasourcesTo(target, rows.user) }

        awaitBlockedBy(seed)
        holder.commit()
        seed.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
        // Without the re-read under the keys, the planned set (read before the holder committed) would be granted and
        // the datasource that arrived in between silently missed.
        rows.grantees(existing) shouldContain target
        rows.grantees(arriving) shouldContain target
    }

    @Test
    fun `the managed-lake repair locks only the workspaces lacking the grant`() {
        val granted = rows.workspace()
        val lacking = rows.workspace()
        val name = rows.datasource("lake").also { rows.grant(it, granted) }
        holder.workspace(granted)

        // The already-granted workspace's held row does not stop the repair...
        grants().grantToEveryWorkspace(name, rows.user)
        rows.grantees(name) shouldContain lacking
        holder.commit()

        // ...but a held row of a workspace that lacks the grant does.
        val late = rows.workspace()
        LockHolder().use { rowHolder ->
            rowHolder.workspace(late)
            val repair = async { grants().grantToEveryWorkspace(name, rows.user) }
            LockObserver.awaitWaiter(rowHolder, repair).shouldNotBeNull().blockers shouldContain rowHolder.pid
            rowHolder.commit()
            repair.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
        }
        rows.grantees(name) shouldContain late
    }

    @Test
    fun `a grant joined to a workspace-creating transaction takes the key inside it - the 823 grant-at-creation shape`() {
        val name = rows.datasource("lake823")
        val created = UUID.randomUUID()
        holder.prerequisite(PrerequisiteKey.datasource(name))
        val writes = writes()
        val creation =
            async {
                transactions.executeWithoutResult {
                    named.jdbcTemplate.update(
                        "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (?, ?, 'G0', ?)",
                        created,
                        "g0-${created.toString().take(8)}-new",
                        rows.user,
                    )
                    grants(writes).grant(name, created, rows.user)
                }
            }

        awaitBlockedBy(creation)
        // Nothing of the creation is visible while its grant waits: workspace and grant commit together.
        rows.jdbc.queryForList("SELECT id FROM workspaces WHERE id = ?", created).shouldBeEmpty()
        holder.commit()
        creation.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
        rows.grantees(name) shouldContain created
        rows.jdbc.update("DELETE FROM datasource_workspaces WHERE workspace_id = ?", created)
        rows.jdbc.update("DELETE FROM workspaces WHERE id = ?", created)
    }

    private fun awaitBlockedBy(writer: Future<*>) {
        val waiter = LockObserver.awaitWaiter(holder, writer)
        if (waiter == null && writer.isDone) {
            val outcome = runCatching { writer.get() }.exceptionOrNull()?.let { (it as? ExecutionException)?.cause ?: it }
            throw AssertionError("the writer finished without waiting on the held lock (guard absent?)", outcome)
        }
        waiter.shouldNotBeNull().blockers shouldContain holder.pid
    }

    private fun definition(
        name: String,
        owner: UUID,
    ) = Datasource(
        name = name,
        displayName = "G0",
        dialect = Dialect.POSTGRES,
        jdbcUrl = "jdbc:postgresql://g0.invalid/db",
        credentialKind = CredentialKind.NONE,
        ownerWorkspaceId = owner,
    )

    private companion object {
        const val BOUND_SECONDS = 20

        /** #841 R4: the freshness tick's bound and budget, a literal (never read back from the class under test). */
        const val REPAIR_BUDGET_SECONDS = 5

        /** How long the key holder keeps the key after the repair queued on it: half the budget. */
        const val KEY_HOLD_MILLIS = 2_500L

        /**
         * Start-to-failure of a budgeted repair: at least the budget less one second (the start is taken before the
         * repair's own clock), at most the budget plus a NAMED 1.5 s allowance for the executor hand-off and the
         * statements' round trips. Two consecutive bounds (the pre-R4 shape) land at 7.5 s or 20 s, outside it.
         */
        val BUDGET_WINDOW_MS = (REPAIR_BUDGET_SECONDS * 1_000L - 1_000L)..(REPAIR_BUDGET_SECONDS * 1_000L + 1_500L)
    }
}
