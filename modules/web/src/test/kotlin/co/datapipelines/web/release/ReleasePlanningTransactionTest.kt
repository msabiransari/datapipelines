package co.datapipelines.web.release

import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.pipeline.release.ReleasePlanRequest
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus

/**
 * The plan path's first closure read (#868 G1 section D.3, record §7.2 step 2): the coordinator built with the
 * production planning template reads the whole closure inside ONE read-only REPEATABLE READ `REQUIRES_NEW` transaction,
 * which ends before preparation and before the release write transaction opens; the guarded re-walk stays inside the
 * release transaction (no second planning transaction). The release transaction itself is unchanged.
 */
class ReleasePlanningTransactionTest {
    private val fx = CoordinatorFixture()
    private val t1 = fx.template("test/t1.sql")
    private val c = fx.pipeline("test/c", listOf(t1))
    private val a = fx.pipeline("test/a", listOf(t1, c))

    /** Logs each planning transaction's definition and the source reads seen at its begin and its commit. */
    private val planningManager =
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
                val d = checkNotNull(definition)
                fx.log += "planning-begin:${d.name}:iso=${d.isolationLevel}:ro=${d.isReadOnly}" +
                    ":prop=${d.propagationBehavior}:reads=${fx.reads.size}"
                return SimpleTransactionStatus()
            }

            override fun commit(status: TransactionStatus) {
                fx.log += "planning-commit:reads=${fx.reads.size}"
            }

            override fun rollback(status: TransactionStatus) {
                fx.log += "planning-rollback"
            }
        }

    @Test
    fun `the plan's closure is read in its own read-only repeatable-read transaction, ended before the release transaction`() {
        val preview = fx.preview(a)
        fx.reads.clear()

        fx
            .coordinator(planningTransactions = ReleasePlanningWalk.planningTransaction(planningManager))
            .release(fx.request(a, ReleasePlanRequest(preview.treeHash, preview.ids(t1, c))))

        fx.writes() shouldContainExactly
            listOf(
                "planning-begin:${ReleasePlanningWalk.PLANNING_TRANSACTION_NAME}:iso=${TransactionDefinition.ISOLATION_REPEATABLE_READ}" +
                    ":ro=true:prop=${TransactionDefinition.PROPAGATION_REQUIRES_NEW}:reads=0",
                // the three versions of the closure, every one read before the planning transaction ends
                "planning-commit:reads=3",
                "prepare:test/c",
                "prepare:test/a",
                "begin",
                "guard:pipeline",
                "flip:test/t1.sql@hash-test/t1.sql",
                "flip:test/c@hash-test/c",
                "flip:test/a@hash-test/a",
                "audit:template.version.released:test/t1.sql",
                "audit:pipeline.version.released:test/c",
                "audit:pipeline.version.released:test/a",
                "commit",
            )
    }
}
