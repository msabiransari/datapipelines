package co.datapipelines.web.config

import co.datapipelines.datasources.ManagedLakeCatalog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * #867 — [LakeConfiguration.transactionAwareManagedCatalog]'s choice, with stub ports that record which one answered.
 * The selector is the Spring resource binding for THE METADATA DataSource, so a transaction on another DataSource, or an
 * active synchronization with nothing bound for this one, must not move a listing onto the unbounded port; a thread flag
 * (`isActualTransactionActive`, `isSynchronizationActive`) in its place is red here. The wired bean's statements, and a
 * real pool, are pinned by `LakeTableCatalogManagedEnrichmentTest` and `LakeTableCatalogTransactionBoundsIntegrationTest`.
 */
class LakeTableCatalogTransactionDispatchTest {
    private val metadata = RecordingTimeoutDataSource(LAKE)
    private val calls = mutableListOf<String>()
    private val catalog =
        LakeConfiguration.transactionAwareManagedCatalog(
            metadata = metadata,
            inTransaction = recordingPort(IN_TRANSACTION),
            outsideTransaction = recordingPort(OUTSIDE),
        )

    @Test
    fun `no transaction - the outside port`() {
        catalog.managedTables(LAKE)

        calls shouldBe listOf(OUTSIDE)
    }

    @Test
    fun `a metadata transaction and a nested participant - the transaction's port`() {
        val transactions = TransactionTemplate(DataSourceTransactionManager(metadata))

        transactions.executeWithoutResult {
            catalog.managedTables(LAKE)
            transactions.executeWithoutResult { catalog.managedTables(LAKE) }
        }

        calls shouldBe listOf(IN_TRANSACTION, IN_TRANSACTION)
    }

    @Test
    fun `a transaction on ANOTHER DataSource cannot select the transaction's port`() {
        val unrelated = RecordingTimeoutDataSource(LAKE)
        var actualTransaction = false

        TransactionTemplate(DataSourceTransactionManager(unrelated)).executeWithoutResult {
            actualTransaction = TransactionSynchronizationManager.isActualTransactionActive()
            catalog.managedTables(LAKE)
        }

        assertAll(
            // Non-vacuity: a real transaction WAS active on the thread when the port was chosen.
            { actualTransaction shouldBe true },
            { calls shouldBe listOf(OUTSIDE) },
        )
    }

    @Test
    fun `an active synchronization with nothing bound for the metadata DataSource cannot select the transaction's port`() {
        val supports =
            TransactionTemplate(DataSourceTransactionManager(metadata)).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_SUPPORTS
            }
        var synchronizationActive = false

        supports.executeWithoutResult {
            synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive()
            catalog.managedTables(LAKE)
        }

        assertAll(
            { synchronizationActive shouldBe true },
            { calls shouldBe listOf(OUTSIDE) },
        )
    }

    @Test
    fun `a failure of either port propagates unchanged`() {
        val boom = IllegalStateException("catalog read failed")
        val failing =
            LakeConfiguration.transactionAwareManagedCatalog(metadata, { throw boom }, { throw boom })

        val outside = shouldThrow<IllegalStateException> { failing.managedTables(LAKE) }
        val inside =
            shouldThrow<IllegalStateException> {
                TransactionTemplate(DataSourceTransactionManager(metadata)).executeWithoutResult { failing.managedTables(LAKE) }
            }

        assertAll(
            { (outside === boom) shouldBe true },
            { (inside === boom) shouldBe true },
        )
    }

    private fun recordingPort(name: String) =
        ManagedLakeCatalog {
            calls += name
            emptyList()
        }

    private companion object {
        const val LAKE = "lake"
        const val IN_TRANSACTION = "in-transaction"
        const val OUTSIDE = "outside"
    }
}
