package co.datapipelines.visualization

import java.util.BitSet

/**
 * A dashboard's groups as a graph — built ONCE per validation, every walk linear in groups + memberships. A group
 * list is bounded only by the request body, so a walk per group (a breadth-first search from each one) is quadratic:
 * a 2 MiB document holds some 38,000 nested groups, and 38,000² steps is minutes of CPU per save. L1a's security
 * pass found the cycle check and the scope-consumer walk both shaped that way; this is their one replacement.
 */
internal class GroupGraph(
    body: DashboardBody,
) {
    private val groups = body.groups

    /** A group name → the index of its FIRST declaration (a duplicate name is the namespace rule's refusal). */
    private val indexByName: Map<String, Int> =
        HashMap<String, Int>().also { index -> groups.forEachIndexed { i, group -> index.putIfAbsent(group.name, i) } }

    /** Each group's child groups, by index — one entry per membership, duplicates kept. */
    private val children: List<List<Int>> = groups.map { group -> group.members.mapNotNull { indexByName[it] } }

    /** The groups whose memberships close a cycle (the source of a back edge), in declaration order — one iterative colour DFS. */
    fun cycleClosers(): List<Int> {
        val colour = IntArray(groups.size)
        val closers = sortedSetOf<Int>()
        groups.indices.forEach { root -> if (colour[root] == WHITE) depthFirst(root, colour, closers) }
        return closers.toList()
    }

    /** One iterative DFS from [root]: a GREY child is a back edge, so its parent closes a cycle. */
    private fun depthFirst(
        root: Int,
        colour: IntArray,
        closers: MutableSet<Int>,
    ) {
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(root, 0))
        colour[root] = GREY
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            val kids = children[frame[NODE]]
            if (frame[NEXT] >= kids.size) {
                colour[frame[NODE]] = BLACK
                stack.removeLast()
                continue
            }
            val child = kids[frame[NEXT]++]
            if (colour[child] == GREY) closers += frame[NODE]
            if (colour[child] == WHITE) {
                colour[child] = GREY
                stack.addLast(intArrayOf(child, 0))
            }
        }
    }

    /**
     * [seed] for every group, then OR-ed into every ancestor, children before parents (Kahn's order over the child
     * edges). A group on a cycle never becomes ready: it keeps its own seed and what its finished children gave it —
     * the cycle itself is the layout rule's refusal.
     */
    fun bottomUp(seed: (DashboardGroup) -> BitSet): List<BitSet> {
        val masks = groups.map(seed)
        val waiting = IntArray(groups.size) { children[it].size }
        val parents = List(groups.size) { mutableListOf<Int>() }
        children.forEachIndexed { parent, kids -> kids.forEach { parents[it] += parent } }
        val ready = ArrayDeque(groups.indices.filter { waiting[it] == 0 })
        while (ready.isNotEmpty()) {
            val node = ready.removeFirst()
            parents[node].forEach { parent ->
                masks[parent].or(masks[node])
                if (--waiting[parent] == 0) ready.addLast(parent)
            }
        }
        return masks
    }

    private companion object {
        const val WHITE = 0
        const val GREY = 1
        const val BLACK = 2
        const val NODE = 0
        const val NEXT = 1
    }
}
