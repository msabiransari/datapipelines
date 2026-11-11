package co.datapipelines.web.dependencies

import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
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
        init {
            require(TEST_WIDGET !in FamilyKey.PRODUCTION) { "test_widget must stay outside production" }
        }
    }
}
