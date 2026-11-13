package co.datapipelines.dependencies

import co.datapipelines.dependencies.FamilyKey.Companion.PIPELINE
import co.datapipelines.dependencies.FamilyKey.Companion.TEMPLATE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * #868 R1, round 3, at the walker: a speculative provider call runs inside the context's [ReadIsolation], so a failure
 * the walk forgives cannot leave the providers' shared state broken for the reads after it.
 *
 * The providers here read through a [Transaction] that models what a failed PostgreSQL statement does — every later read
 * in the transaction fails ("current transaction is aborted") — and a [Savepoints] fence that models a savepoint. The
 * unfenced walk ([ReadIsolation.NONE]) reproduces the round-2 defect in this model; the fenced walk gives the outcome of
 * a walk that reads one needed request at a time. The same cases run on a real PostgreSQL in the application module
 * (`ReleaseSpeculativeReadPostgresTest`).
 */
class ReadIsolationTest {
    private val root = exact(TEST_WIDGET, "w", 1)
    private val a = exact(TEMPLATE, "a", 1)
    private val b = exact(TEMPLATE, "b", 1)
    private val d = exact(TEMPLATE, "d", 1)
    private val p = exact(PIPELINE, "p", 1)

    @Test
    fun `the model - an unfenced speculative failure aborts the transaction and overtakes the stop the walk reaches`() {
        val tx = Transaction()
        val g = sameFamilyRetry(tx)

        shouldThrow<IllegalStateException> { g.walk(root, limits = TestGraph.LIMITS.copy(edges = 1)) }.message shouldBe ABORTED
        g.family(TEMPLATE).calls shouldContainExactly listOf(listOf(a, b), listOf(a))
    }

    @Test
    fun `a failing call that also held the needed request rolls back to its savepoint, and the needed read alone decides`() {
        val tx = Transaction()
        val g = sameFamilyRetry(tx)

        g.walk(root, limits = TestGraph.LIMITS.copy(edges = 1), isolation = Savepoints(tx)).limitStop() shouldBe (LimitKind.EDGES to 1)
        g.family(TEMPLATE).calls shouldContainExactly listOf(listOf(a, b), listOf(a))
        // The fenced call failed and was rolled back; the needed request's re-read ran unfenced, in a usable transaction.
        tx.log shouldContainExactly listOf("read [w]", "savepoint", "read [a, b]", "rollback", "read [a]")
        tx.aborted shouldBe false
    }

    @Test
    fun `a speculative-only failure rolls back, and the needed read after it runs in a usable transaction`() {
        val tx = Transaction()
        val g = speculativeOnly(tx)

        // root→a, a→d, then root→p is the third edge: the walk stops before it needs p.
        g.walk(root, limits = TestGraph.LIMITS.copy(edges = 2), isolation = Savepoints(tx)).limitStop() shouldBe (LimitKind.EDGES to 2)
        tx.log shouldContainExactly listOf("read [w]", "read [a]", "savepoint", "read [p]", "rollback", "read [d]")
        tx.aborted shouldBe false

        val unfenced = Transaction()
        shouldThrow<IllegalStateException> {
            speculativeOnly(unfenced).walk(root, limits = TestGraph.LIMITS.copy(edges = 2))
        }.message shouldBe ABORTED
    }

    @Test
    fun `control - a failure on a request the walk reaches propagates, fenced or not, and aborts the transaction as it would`() {
        val tx = Transaction()
        val g = TestGraph()
        g.node(root, Lifecycle.DRAFT, pin(TEST_WIDGET, b, "parts[0]"))
        transactional(g, tx)
        g.family(TEMPLATE).failing = setOf(b)

        shouldThrow<IllegalStateException> { g.walk(root, isolation = Savepoints(tx)) }.message shouldBe "metadata source unavailable: b"
        tx.log shouldContainExactly listOf("read [w]", "read [b]") // a needed read alone is never fenced
        tx.aborted shouldBe true
    }

    @Test
    fun `a failure of the fence itself is never forgiven - it propagates`() {
        val tx = Transaction()
        val g = speculativeOnly(tx)
        val broken = IllegalStateException("no savepoint: the connection is gone")
        val refusing =
            object : ReadIsolation {
                override fun <T> isolate(read: () -> T): T = throw broken
            }

        shouldThrow<IllegalStateException> {
            g.walk(root, limits = TestGraph.LIMITS.copy(edges = 2), isolation = refusing)
        } shouldBeSameInstanceAs broken

        // A fence that cannot restore the state throws its own failure instead of the read's: propagated too.
        val unrestorable = IllegalStateException("rollback to savepoint failed")
        val failingRollback =
            object : ReadIsolation {
                override fun <T> isolate(read: () -> T): T =
                    try {
                        read()
                    } catch (failure: RuntimeException) {
                        unrestorable.addSuppressed(failure)
                        throw unrestorable
                    }
            }
        shouldThrow<IllegalStateException> {
            speculativeOnly(Transaction()).walk(root, limits = TestGraph.LIMITS.copy(edges = 2), isolation = failingRollback)
        } shouldBeSameInstanceAs unrestorable
    }

    /** root → a, b (both TEMPLATE; b fails): a's read carries b as speculation in the SAME call. */
    private fun sameFamilyRetry(tx: Transaction): TestGraph {
        val g = TestGraph()
        g.node(a, Lifecycle.RELEASED)
        g.node(root, Lifecycle.DRAFT, pin(TEST_WIDGET, a, "parts[0]"), pin(TEST_WIDGET, b, "parts[1]"))
        transactional(g, tx)
        g.family(TEMPLATE).failing = setOf(b)
        return g
    }

    /** root → a (TEMPLATE), p (PIPELINE, fails); a → d: a's read carries p as a speculative call of its own. */
    private fun speculativeOnly(tx: Transaction): TestGraph {
        val g = TestGraph()
        g.node(d, Lifecycle.RELEASED)
        g.node(a, Lifecycle.DRAFT, pin(TEMPLATE, d, "imports[0]"))
        g.node(root, Lifecycle.DRAFT, pin(TEST_WIDGET, a, "parts[0]"), pin(TEST_WIDGET, p, "parts[1]"))
        transactional(g, tx)
        g.family(PIPELINE).failing = setOf(p)
        return g
    }

    /** Every family of [g] reads through [tx]: a read in an aborted transaction fails, a failing read aborts it. */
    private fun transactional(
        g: TestGraph,
        tx: Transaction,
    ) {
        listOf(TEST_WIDGET, TEMPLATE, PIPELINE).forEach { key ->
            val family = g.family(key)
            family.beforeBatch = { requests ->
                check(!tx.aborted) { ABORTED }
                tx.log += "read ${requests.map { it.address.lookupKey }}"
            }
            family.onFailure = { tx.aborted = true }
        }
    }

    /** The shared state providers read through, as PostgreSQL treats a transaction after a failed statement. */
    private class Transaction {
        var aborted = false
        val log = ArrayList<String>()
    }

    /** A savepoint fence over [tx]: a failure restores the state the read started in and rethrows the same exception. */
    private class Savepoints(
        private val tx: Transaction,
    ) : ReadIsolation {
        override fun <T> isolate(read: () -> T): T {
            val before = tx.aborted
            tx.log += "savepoint"
            return try {
                read()
            } catch (failure: RuntimeException) {
                tx.aborted = before
                tx.log += "rollback"
                throw failure
            }
        }
    }

    private companion object {
        const val ABORTED = "current transaction is aborted"
    }
}
