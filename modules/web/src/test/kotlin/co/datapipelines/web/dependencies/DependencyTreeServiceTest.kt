package co.datapipelines.web.dependencies

import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations

/**
 * The tree service over a TEST-BUILT registry (A28): the synthetic `test_widget` family and a test transport row reach a
 * projected tree with no change to the walker, the service or the production table — and the handler's order and
 * refusals (#875 G4 §B) hold for it like for a production family.
 */
class DependencyTreeServiceTest {
    private val widgets = StubFamily(TEST_WIDGET)

    private fun service(
        lens: RootLens = RootLens.None,
        view: () -> LensedView = { LensedView.EVERYTHING },
        transactions: TransactionOperations = TransactionOperations.withoutTransaction(),
    ) = treeService(listOf(widgets), listOf(stubRow(TEST_WIDGET, lens = lens)), view = view, transactions = transactions)

    private fun DependencyTreeService.read(
        query: String,
        principal: AuthenticatedPrincipal = member(),
    ) = tree(principal, TEST_WIDGET, query)

    private fun refusal(block: () -> Unit): DatapipelinesException = shouldThrow<DatapipelinesException>(block)

    @Test
    fun `a synthetic family's tree is projected with no walker, service or table change - A28`() {
        val leaf = widgets.put(exact(TEST_WIDGET, "acme/leaf", 2))
        widgets.put(exact(TEST_WIDGET, "acme/root", 1), targets = arrayOf(leaf, exact(TEST_WIDGET, "acme/gone", 4)))

        val tree = service().read("name=acme/root&version=1")

        tree.nodes.map { it.family }.distinct() shouldBe listOf("test_widget")
        tree.nodes.map { it.name to it.resolution } shouldBe
            listOf("acme/root" to "resolved", "acme/leaf" to "resolved", "acme/gone" to "missing")
        tree.nodes.map { it.entityId != null } shouldBe listOf(true, true, false)
        tree.nodes.last().version shouldBe WireNumbered("artifact", 4)
        tree.edges.map { it.sourcePath } shouldBe listOf("/refs/0", "/refs/1")
        tree.complete shouldBe true
        tree.acyclic shouldBe true
        tree.observedAt shouldBe "2026-11-11T00:00:00Z"
    }

    @Test
    fun `a key principal is refused session_required before the request is read`() {
        refusal { service().read("garbage", member(authMethod = AuthMethod.API_KEY)) }.code shouldBe
            PipelineErrorCodes.Auth.SESSION_REQUIRED
        widgets.calls.shouldBeEmpty()
    }

    @Test
    fun `a refused request is request_invalid naming the field and reason - nothing walked`() {
        val refused = refusal { service().read("name=acme/root&version=0") }

        refused.code shouldBe PipelineErrorCodes.Dependencies.REQUEST_INVALID
        refused.details shouldBe mapOf("field" to "version", "reason" to "version_invalid")
        widgets.calls.shouldBeEmpty()
    }

    @Test
    fun `an absent root is the row's not-found code with the one static message`() {
        val absent = refusal { service().read("name=acme/absent&version=1") }

        absent.code shouldBe "test_widget.not_found"
        absent.message shouldBe DependencyTreeService.NOT_FOUND_MESSAGE
        absent.details shouldBe emptyMap()
    }

    @Test
    fun `a lens-free row never obtains a view - an unreachable target cannot fail it`() {
        widgets.put(exact(TEST_WIDGET, "acme/root", 1))
        var views = 0
        val unavailable = {
            views++
            LensedView(ReadLens.NOTHING, ReadLens.NOTHING, LensedView.Unavailable("t", "down"))
        }

        service(view = unavailable).read("name=acme/root&version=1", member(WorkspaceRole.PROMOTER)).nodes.size shouldBe 1
        views shouldBe 0
    }

    @Test
    fun `a lensed row for a promoter with an unreachable target is view unavailable before any read`() {
        val unavailable = { LensedView(ReadLens.NOTHING, ReadLens.NOTHING, LensedView.Unavailable("t", "down")) }

        refusal { service(Lens(), unavailable).read("name=acme/root&version=1", member(WorkspaceRole.PROMOTER)) }.code shouldBe
            PipelineErrorCodes.Navigation.VIEW_UNAVAILABLE
        widgets.calls.shouldBeEmpty()
    }

    @Test
    fun `a root either lens half hides answers exactly as an absent root does`() {
        widgets.put(exact(TEST_WIDGET, "acme/root", 1))
        val absent = refusal { service().read("name=acme/absent&version=1") }
        val promoter = member(WorkspaceRole.PROMOTER)
        val readsBefore = widgets.calls.size

        val hiddenBefore = refusal { service(Lens(request = false)).read("name=acme/root&version=1", promoter) }
        val hiddenAfter = refusal { service(Lens(resolved = false)).read("name=acme/root&version=1", promoter) }

        listOf(hiddenBefore, hiddenAfter).forEach {
            (it.code to it.message) shouldBe (absent.code to absent.message)
            it.details shouldBe absent.details
        }
        // the request half refused before the walk; the resolved half after it
        widgets.calls.size - readsBefore shouldBe 1
    }

    @Test
    fun `a lensed row is not consulted for a principal the lens does not narrow`() {
        widgets.put(exact(TEST_WIDGET, "acme/root", 1))

        service(Lens(request = false, resolved = false)).read("name=acme/root&version=1").nodes.size shouldBe 1
    }

    @Test
    fun `a failed metadata read and a failed transaction are metadata_unavailable, with the cause kept`() {
        widgets.beforeBatch = { _, _ -> throw DataAccessResourceFailureException("connection refused") }
        val failedRead = refusal { service().read("name=acme/root&version=1") }
        val failingTransactions =
            object : TransactionOperations {
                override fun <T : Any?> execute(action: TransactionCallback<T>): T = throw CannotCreateTransactionException("pool closed")
            }
        val failedTransaction = refusal { service(transactions = failingTransactions).read("name=acme/root&version=1") }

        listOf(failedRead, failedTransaction).forEach { it.code shouldBe PipelineErrorCodes.Dependencies.METADATA_UNAVAILABLE }
        failedRead.cause.shouldBeInstanceOf<DataAccessResourceFailureException>()
        failedTransaction.cause.shouldBeInstanceOf<CannotCreateTransactionException>()
    }

    @Test
    fun `a defect is not dressed as an outage - it propagates as itself`() {
        widgets.beforeBatch = { _, _ -> error("provider bug") }

        shouldThrow<IllegalStateException> { service().read("name=acme/root&version=1") }.message shouldBe "provider bug"
    }

    @Test
    fun `a projected tree over max-wire-bytes is response_too_large, never a truncated tree`() {
        widgets.put(exact(TEST_WIDGET, "acme/root", 1))
        val tight = TREE_LIMITS.copy(wireBytes = 64)
        val small = treeService(listOf(widgets), listOf(stubRow(TEST_WIDGET)), limits = tight)

        val tooLarge = refusal { small.read("name=acme/root&version=1") }

        tooLarge.code shouldBe PipelineErrorCodes.Dependencies.RESPONSE_TOO_LARGE
        tooLarge.cause.shouldBeInstanceOf<IllegalStateException>()
    }

    @Test
    fun `a reducible over-budget tree is a 200 with the wire_bytes LIMIT, the root kept - record 11_1`() {
        val tree = wideTree(TREE_LIMITS.copy(wireBytes = REDUCIBLE_BUDGET))

        tree.roots shouldBe listOf("n0")
        tree.nodes.first().name shouldBe "acme/root"
        tree.complete shouldBe false
        tree.acyclic shouldBe null
        tree.diagnostics.last() shouldBe
            WireDiagnostic("LIMIT", null, null, "The wire_bytes limit (4096) was reached; the graph is incomplete.")
        (bytesOf(tree) <= REDUCIBLE_BUDGET) shouldBe true
    }

    @Test
    fun `the reduction keeps the largest node prefix that fits - one node more is over the budget`() {
        wideWorld()
        val full = readWith(TREE_LIMITS)
        val tree = readWith(TREE_LIMITS.copy(wireBytes = REDUCIBLE_BUDGET))
        val kept = tree.nodes.size

        (kept in 2 until full.nodes.size) shouldBe true
        tree.nodes shouldBe full.nodes.take(kept)
        (bytesOf(tree) <= REDUCIBLE_BUDGET) shouldBe true
        (bytesOf(cut(full, kept + 1, tree.diagnostics.last())) > REDUCIBLE_BUDGET) shouldBe true
    }

    @Test
    fun `a reduced tree names only kept nodes and edges - roots, edge ends, diagnostics`() {
        wideWorld(missingEvery = 3)
        val full = readWith(TREE_LIMITS)
        val budget = bytesOf(full) / 2
        val tree = readWith(TREE_LIMITS.copy(wireBytes = budget))
        val nodeIds = tree.nodes.map { it.nodeId }.toSet()
        val edgeIds = tree.edges.map { it.edgeId }.toSet()

        tree.roots.all { it in nodeIds } shouldBe true
        tree.edges.all { it.from in nodeIds && it.to in nodeIds } shouldBe true
        tree.diagnostics.all { (it.nodeId == null || it.nodeId in nodeIds) && (it.edgeId == null || it.edgeId in edgeIds) } shouldBe true
        // in their order, never renumbered: exactly the full tree's kept rows, then the one LIMIT
        tree.edges shouldBe full.edges.filter { it.from in nodeIds && it.to in nodeIds }
        tree.diagnostics.dropLast(1) shouldBe full.diagnostics.filter { it.nodeId == null || it.nodeId in nodeIds }
        // non-vacuous: the cut kept some MISSING diagnostics and dropped others
        withClue("kept ${tree.diagnostics.size - 1} of ${full.diagnostics.size}") {
            ((tree.diagnostics.size - 1) in 1 until full.diagnostics.size) shouldBe true
        }
        (bytesOf(tree) <= budget) shouldBe true
    }

    @Test
    fun `the same over-budget input twice is reduced to byte-identical output`() {
        wideWorld(missingEvery = 3)
        val limits = TREE_LIMITS.copy(wireBytes = REDUCIBLE_BUDGET)

        MAPPER.writeValueAsBytes(readWith(limits)).toList() shouldBe MAPPER.writeValueAsBytes(readWith(limits)).toList()
    }

    @Test
    fun `a payload exactly at the budget is unchanged with no LIMIT - one byte less and it is reduced`() {
        wideWorld()
        val full = readWith(TREE_LIMITS)
        val exactly = bytesOf(full)

        readWith(TREE_LIMITS.copy(wireBytes = exactly)) shouldBe full
        full.diagnostics.none { it.code == "LIMIT" } shouldBe true
        readWith(TREE_LIMITS.copy(wireBytes = exactly - 1)).complete shouldBe false
    }

    @Test
    fun `the roots alone with the LIMIT at the budget are a 200 - one byte under is response_too_large`() {
        wideWorld()
        val rootOnly = readWith(TREE_LIMITS.copy(wireBytes = REDUCIBLE_BUDGET)).let { cut(it, 1, it.diagnostics.last()) }
        // the LIMIT names the budget itself: the floor is the root-only size with a maximum of the same digit count
        val floor = bytesOf(rootOnly.copy(diagnostics = listOf(limitAt(bytesOf(rootOnly)))))
        bytesOf(rootOnly.copy(diagnostics = listOf(limitAt(floor - 1)))) shouldBe floor

        readWith(TREE_LIMITS.copy(wireBytes = floor)).nodes.map { it.nodeId } shouldBe listOf("n0")
        val refused = refusal { readWith(TREE_LIMITS.copy(wireBytes = floor - 1)) }
        refused.code shouldBe PipelineErrorCodes.Dependencies.RESPONSE_TOO_LARGE
        refused.cause.shouldBeInstanceOf<IllegalStateException>()
    }

    @Test
    fun `under a budget that would reduce the tree, a hidden root still answers exactly as an absent one`() {
        wideWorld()
        val tight = TREE_LIMITS.copy(wireBytes = REDUCIBLE_BUDGET)
        val lensed = { lens: RootLens -> treeService(listOf(widgets), listOf(stubRow(TEST_WIDGET, lens = lens)), limits = tight) }
        val promoter = member(WorkspaceRole.PROMOTER)

        val absent = refusal { lensed(Lens()).read("name=acme/absent&version=1", promoter) }
        val hidden = refusal { lensed(Lens(resolved = false)).read("name=acme/root&version=1", promoter) }

        (hidden.code to hidden.message) shouldBe (absent.code to absent.message)
        hidden.details shouldBe absent.details
        lensed(Lens()).read("name=acme/root&version=1", promoter).complete shouldBe false
    }

    /** `acme/root` over [LEAVES] leaves — 11,463 bytes unreduced, 625 as its root alone; every [missingEvery]th absent. */
    private fun wideWorld(missingEvery: Int = 0) {
        val leaves = (1..LEAVES).map { exact(TEST_WIDGET, "acme/leaf_$it", 1) }
        leaves.forEachIndexed { index, leaf -> if (missingEvery == 0 || index % missingEvery != 0) widgets.put(leaf) }
        widgets.put(exact(TEST_WIDGET, "acme/root", 1), targets = leaves.toTypedArray())
    }

    private fun wideTree(limits: GraphLimits): DependencyTreeData {
        wideWorld()
        return readWith(limits)
    }

    private fun readWith(limits: GraphLimits) =
        treeService(listOf(widgets), listOf(stubRow(TEST_WIDGET)), limits = limits).read("name=acme/root&version=1")

    /** The oracle, built from the FULL tree by the brief's rule: the first [count] nodes, their edges and diagnostics. */
    private fun cut(
        full: DependencyTreeData,
        count: Int,
        limit: WireDiagnostic,
    ): DependencyTreeData {
        val nodes = full.nodes.take(count)
        val ids = nodes.map { it.nodeId }.toSet()
        val edges = full.edges.filter { it.from in ids && it.to in ids }
        val diagnostics = full.diagnostics.filter { it.nodeId == null || it.nodeId in ids } + limit
        return full.copy(nodes = nodes, edges = edges, diagnostics = diagnostics, complete = false, acyclic = null)
    }

    private fun limitAt(maximum: Int) =
        WireDiagnostic("LIMIT", null, null, "The wire_bytes limit ($maximum) was reached; the graph is incomplete.")

    private fun bytesOf(data: DependencyTreeData) = MAPPER.writeValueAsBytes(data).size

    /** A test root lens answering [request] before the walk and [resolved] after it. */
    private class Lens(
        private val request: Boolean = true,
        private val resolved: Boolean = true,
    ) : RootLens.Lensed {
        override fun admitsRequest(
            principal: AuthenticatedPrincipal,
            view: LensedView,
            request: DependenciesRequest,
        ) = this.request

        override fun admitsResolved(
            principal: AuthenticatedPrincipal,
            view: LensedView,
            request: DependenciesRequest,
            root: ResolvedIdentity,
        ) = resolved
    }

    private companion object {
        const val LEAVES = 20
        const val REDUCIBLE_BUDGET = 4_096
        val MAPPER = jacksonObjectMapper()

        init {
            require(TEST_WIDGET !in FamilyKey.PRODUCTION) { "test_widget must stay outside production" }
        }
    }
}
