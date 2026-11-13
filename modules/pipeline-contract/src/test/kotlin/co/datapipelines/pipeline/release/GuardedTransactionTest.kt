package co.datapipelines.pipeline.release

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

/**
 * The guarded-writer shape (record §4.3): the guard is acquired INSIDE the transaction and BEFORE the write, a refusal
 * runs no write, and the block's own answer — null included — comes back unchanged.
 */
class GuardedTransactionTest {
    private val workspace = UUID.fromString("00000000-0000-0000-0000-000000000517")
    private val events = mutableListOf<String>()

    private val transactions =
        object : TransactionOperations {
            override fun <T : Any?> execute(action: TransactionCallback<T>): T? {
                events += "begin"
                val result = action.doInTransaction(SimpleTransactionStatus())
                events += "commit"
                return result
            }
        }

    private val guard = ReleaseMutationGuard { id, family -> events += "guard:$id:${family.wire}" }

    @Test
    fun `the guard is the transaction's first statement and the write follows it`() {
        val answer =
            GuardedTransaction.run(transactions, guard, workspace, ReleaseFamily.DASHBOARD) {
                events += "written"
                "written"
            }

        answer shouldBe "written"
        events shouldContainExactly listOf("begin", "guard:$workspace:dashboard", "written", "commit")
    }

    @Test
    fun `a nullable answer comes back as null, not as a missing callback`() {
        GuardedTransaction.run<String?>(transactions, guard, workspace, ReleaseFamily.PIPELINE) { null } shouldBe null
    }

    @Test
    fun `a busy guard runs no write and its refusal reaches the caller`() {
        val busy = ReleaseRefusals.busy(ReleaseFamily.TEMPLATE, lockWaitSeconds = 5)
        val refusing = ReleaseMutationGuard { _, _ -> throw busy }

        val thrown =
            shouldThrow<RuntimeException> {
                GuardedTransaction.run(transactions, refusing, workspace, ReleaseFamily.TEMPLATE) { events += "written" }
            }

        thrown shouldBe busy
        events shouldContainExactly listOf("begin")
    }

    @Test
    fun `the direct operations run the block with no transaction manager, for directly constructed services`() {
        GuardedTransaction.run(GuardedTransaction.DIRECT, guard, workspace, ReleaseFamily.VISUALIZATION) { 7 } shouldBe 7
        events shouldContainExactly listOf("guard:$workspace:visualization")
    }
}
