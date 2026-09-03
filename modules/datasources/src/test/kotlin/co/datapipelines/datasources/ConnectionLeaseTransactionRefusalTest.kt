package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection

/**
 * **One transaction, one database** (056 §E.2, owner-ratified 2026-09-02; the model is
 * dag-executor.md §16).
 *
 * The application has exactly ONE Spring transaction manager and it is the metadata database's.
 * Customer datasources are Hikari pools that are deliberately not Spring transaction resources.
 * Leasing one inside a metadata transaction would hold that transaction — and its row locks —
 * open for the duration of arbitrary customer SQL, and rolling the metadata transaction back
 * could not undo the customer-side effect in any case.
 *
 * Until 056 that was prose. This is its mechanical form: the refusal sits at the ONE boundary
 * every customer connection passes through, so a future service method that grows a lease inside
 * `@Transactional` fails loudly with a catalogued code instead of quietly becoming a lock holder.
 *
 * The refusal is asserted BEFORE the pool is touched — `verify(exactly = 0)` on `poolFor` — because
 * "it threw" would also be satisfied by a lease that opened a connection and then failed, which is
 * precisely the state this exists to prevent.
 */
class ConnectionLeaseTransactionRefusalTest {
    private val datasource = Fixtures.h2(name = "tx_guard_ds")
    private val registry = mockk<DatasourceRegistry>()

    @AfterEach
    fun clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false)
        TransactionSynchronizationManager.clear()
    }

    @Test
    fun `a lease taken inside a metadata transaction is refused with the catalogued code`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)

        val error =
            shouldThrow<DatapipelinesException> {
                ConnectionLease.lease(registry, datasource) { error("the block must never run") }
            }

        withClue("the refusal is catalogued (pipeline-contract §13.8), not a bare IllegalStateException") {
            error.code shouldBe DatasourceErrorCodes.LEASE_IN_TRANSACTION
        }
        error.details["datasource"] shouldBe "tx_guard_ds"
        withClue("no pool may be touched: a connection opened and then thrown away is the failure itself") {
            verify(exactly = 0) { registry.poolFor(any()) }
        }
    }

    @Test
    fun `outside a transaction the lease proceeds normally`() {
        // The control. Without it "the lease was refused" could be satisfied by a boundary that
        // refuses everything, which would take the whole product down rather than guard it.
        val connection = mockk<Connection>(relaxed = true)
        val pool = mockk<ConnectionPool>()
        every { pool.leaseConnection() } returns connection
        every { registry.poolFor(datasource) } returns pool

        val leased = ConnectionLease.lease(registry, datasource) { LEASED }

        leased shouldBe LEASED
        verify(exactly = 1) { registry.poolFor(datasource) }
    }

    private companion object {
        const val LEASED = "leased"
    }
}
