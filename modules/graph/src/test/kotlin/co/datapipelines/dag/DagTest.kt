package co.datapipelines.dag

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `Dag<T>` against the small synthetic graphs dag-executor.md §14 enumerates: diamonds,
 * self-loops, disconnected components, large fan-outs, and nodes with no entry in the dependency
 * map (the `emptySet()` default path).
 */
class DagTest {
    @Test
    fun `topological order places every node after its dependencies`() {
        // The property, not a spot check. The previous `indexOf("b") >= 1` was vacuous — it holds
        // for any order that does not begin with `b`, including several that are plainly wrong —
        // so it could not have failed for a real ordering bug (F14).
        listOf(diamond(), disconnected(), chain(DEEP_CHAIN)).forEach { dag ->
            val order = dag.topologicalOrder()

            order.toSet() shouldBe dag.nodeIds
            order.size shouldBe dag.nodeIds.size
            dag.nodeIds.forEach { node ->
                dag.dependenciesOf(node).forEach { dependency ->
                    order.indexOf(dependency) shouldBeLessThan order.indexOf(node)
                }
            }
        }
    }

    @Test
    fun `a root node has no entry in the dependency map and yields an empty set`() {
        val dag = diamond()

        dag.dependenciesOf("a") shouldBe emptySet()
        dag.dependentsOf("a") shouldContainExactlyInAnyOrder listOf("b", "c")
        dag.dependentsOf("d") shouldBe emptySet()
    }

    @Test
    fun `independent batches group the nodes that could run in one wave`() {
        diamond().independentBatches() shouldContainExactly
            listOf(setOf("a"), setOf("b", "c"), setOf("d"))
    }

    @Test
    fun `a disconnected component is its own chain in the same graph`() {
        val dag = disconnected()

        dag.independentBatches() shouldContainExactly listOf(setOf("a", "x"), setOf("b", "y"))
        dag.detectCycle().shouldBeNull()
    }

    @Test
    fun `a large fan-out schedules every leaf after the root`() {
        val leaves = (1..LARGE_FAN_OUT).map { "leaf$it" }
        val dag =
            Dag.build<String> {
                addNode("root", "root")
                leaves.forEach { addNode(it, it) }
                leaves.forEach { addDependency(it, "root") }
            }

        dag.topologicalOrder().first() shouldBe "root"
        dag.independentBatches().size shouldBe 2
        dag.dependentsOf("root").size shouldBe LARGE_FAN_OUT
    }

    @Test
    fun `detectCycle returns the offending path and null for an acyclic graph`() {
        diamond().detectCycle().shouldBeNull()

        val cyclic = Dag.of(mapOf("a" to "a", "b" to "b"), mapOf("a" to setOf("b"), "b" to setOf("a")))
        val cycle = cyclic.detectCycle().shouldNotBeNull()

        cycle.first() shouldBe cycle.last()
        cycle.toSet() shouldContainExactlyInAnyOrder listOf("a", "b")
    }

    @Test
    fun `a long chain does not overflow the cycle search`() {
        val dag = chain(DEEP_CHAIN)

        dag.detectCycle().shouldBeNull()
        dag.topologicalOrder() shouldContainExactly (1..DEEP_CHAIN).map { "n$it" }
    }

    @Test
    fun `the builder refuses a self-loop, a duplicate id and a dangling dependency`() {
        shouldThrow<IllegalArgumentException> {
            Dag.build<String> {
                addNode("a", "a")
                addDependency("a", "a")
            }
        }
        shouldThrow<IllegalArgumentException> {
            Dag.build<String> {
                addNode("a", "a")
                addNode("a", "again")
            }
        }
        shouldThrow<IllegalArgumentException> {
            Dag.build<String> {
                addNode("a", "a")
                addDependency("a", "ghost")
            }
        }
    }

    @Test
    fun `the builder refuses a cycle`() {
        val failure =
            shouldThrow<IllegalArgumentException> {
                Dag.build<String> {
                    addNode("a", "a")
                    addNode("b", "b")
                    addDependency("a", "b")
                    addDependency("b", "a")
                }
            }

        failure.message.shouldNotBeNull().shouldContainCycle()
    }

    @Test
    fun `topologicalOrder reports a cycle that bypassed the builder`() {
        val cyclic = Dag.of(mapOf("a" to "a", "b" to "b"), mapOf("a" to setOf("b"), "b" to setOf("a")))

        shouldThrow<IllegalStateException> { cyclic.topologicalOrder() }
        shouldThrow<IllegalStateException> { cyclic.independentBatches() }
    }

    @Test
    fun `node lookup fails loudly for an unknown id`() {
        shouldThrow<IllegalStateException> { diamond().node("ghost") }
    }

    private fun String.shouldContainCycle() {
        (contains("Cycle detected")) shouldBe true
    }

    /** Two independent two-node chains in one graph. */
    private fun disconnected(): Dag<String> =
        Dag.build {
            addNode("a", "a")
            addNode("b", "b")
            addNode("x", "x")
            addNode("y", "y")
            addDependency("b", "a")
            addDependency("y", "x")
        }

    /** `n1 → n2 → … → nLength`. */
    private fun chain(length: Int): Dag<String> {
        val ids = (1..length).map { "n$it" }
        return Dag.build {
            ids.forEach { addNode(it, it) }
            ids.zipWithNext().forEach { (from, to) -> addDependency(to, from) }
        }
    }

    /** a → (b, c) → d. */
    private fun diamond(): Dag<String> =
        Dag.build {
            addNode("a", "a")
            addNode("b", "b")
            addNode("c", "c")
            addNode("d", "d")
            addDependency("b", "a")
            addDependency("c", "a")
            addDependency("d", "b")
            addDependency("d", "c")
        }

    private companion object {
        const val LARGE_FAN_OUT = 50
        const val DEEP_CHAIN = 500
    }
}
