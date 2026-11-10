package co.datapipelines.web.release

import co.datapipelines.datasources.CredentialKind
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.datasources.DatasourceGrantRepository
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.datasources.DatasourceWriteGuarding
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.guard.PrerequisiteKey
import co.datapipelines.web.SharedPostgres
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
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
    }
}
