package co.datapipelines.dependencies

import co.datapipelines.dependencies.FamilyKey.Companion.DATASOURCE
import co.datapipelines.dependencies.FamilyKey.Companion.PARAMETER_SET
import co.datapipelines.dependencies.FamilyKey.Companion.PIPELINE
import co.datapipelines.dependencies.FamilyKey.Companion.TEMPLATE
import co.datapipelines.dependencies.FamilyKey.Companion.VISUALIZATION
import co.datapipelines.dependencies.conformance.IncomingConformance
import co.datapipelines.dependencies.conformance.IncomingConformance.Case
import co.datapipelines.dependencies.conformance.IncomingConformance.Occurrence
import co.datapipelines.dependencies.conformance.ProviderConformance
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #877 §A: the inverse contract and the conformance harness's inverse arm, over an in-memory provider. Each check of
 * the harness is shown green on a conforming provider and red on a provider that breaks exactly that rule.
 */
class IncomingContractTest {
    private val ws = OwnerScope.Workspace(WS)

    @Test
    fun `the default provider answers Unsupported and declares no dependent family`() {
        val provider = FakeFamily(TEST_WIDGET)
        provider.incomingFrom.shouldBeEmpty()
        provider.findIncoming(query(entity(TEMPLATE, "lib"), PIPELINE), context()) shouldBe IncomingResult.Unsupported
        shouldThrow<IllegalStateException> { provider.findAllIncoming(query(entity(TEMPLATE, "lib"), PIPELINE), context()) }
            .message!! shouldContain "does not answer"
    }

    @Test
    fun `a conforming versioned provider passes every inverse check`() {
        ProviderConformance.checkIncoming(templateFixture(InMemoryIncoming(TEMPLATE, templateRows()))).shouldBeEmpty()
    }

    @Test
    fun `a conforming unversioned provider answers by name and refuses an exact target`() {
        val provider = InMemoryIncoming(DATASOURCE, warehouseRows, versioned = false)
        val fixture =
            IncomingConformance.Fixture(
                descriptor(provider, DATASOURCE),
                context(),
                listOf(
                    Case(entity(DATASOURCE, "warehouse"), PIPELINE, IncomingScope.ANY, warehouseOccurrence),
                    Case(entity(DATASOURCE, "warehouse"), PARAMETER_SET, IncomingScope.ANY, emptyList()),
                ),
                entity(DATASOURCE, "nobody"),
            )
        // The parameter-set arm has no nonempty case, and the fixture says so.
        ProviderConformance.checkIncoming(fixture) shouldContainExactly
            listOf("datasource: no nonempty case for dependent family parameter_set (vacuous)")
    }

    @Test
    fun `a provider that ignores the stored version breaks isolation`() {
        val violations =
            ProviderConformance.checkIncoming(
                templateFixture(InMemoryIncoming(TEMPLATE, templateRows(), ignoreVersion = true)),
            )
        violations.any { "unexpected" in it } shouldBe true
        violations.any { "stores another version" in it } shouldBe true
    }

    @Test
    fun `a provider that answers an empty page for an undeclared family, or skips the cursor, is refused`() {
        val lenient = InMemoryIncoming(TEMPLATE, templateRows(), emptyForUndeclared = true)
        ProviderConformance.checkIncoming(templateFixture(lenient)) shouldContainExactly
            listOf("template: an undeclared dependent family answered Page(edges=[], next=null), not Unsupported")
        val noCursor = InMemoryIncoming(TEMPLATE, templateRows(), dropCursor = true)
        val cut = ProviderConformance.checkIncoming(templateFixture(noCursor))
        cut.any { "page size 256 answers differently" in it } shouldBe true
        cut.filter { "missing" in it }.size shouldBe 2 // lib@2's three pipeline occurrences, cut to the first page
    }

    @Test
    fun `an unversioned provider that admits an exact target is refused`() {
        val provider = InMemoryIncoming(DATASOURCE, warehouseRows, versioned = false, refuseExact = false)
        val fixture =
            IncomingConformance.Fixture(
                descriptor(provider, DATASOURCE),
                context(),
                listOf(Case(entity(DATASOURCE, "warehouse"), PIPELINE, IncomingScope.ANY, warehouseOccurrence)),
                entity(DATASOURCE, "nobody"),
            )
        ProviderConformance.checkIncoming(fixture).filter { "was not refused" in it }.size shouldBe 2 // both inbound arms
    }

    @Test
    fun `a cursor answers only its own query`() {
        val target = entity(TEMPLATE, "lib")
        val cursor = IncomingCursor(target, PIPELINE, IncomingScope.ANY, "p1", 1, Relationship.TEMPLATE, "nodes[0].template")
        shouldThrow<IllegalArgumentException> { IncomingQuery(target, PIPELINE, IncomingScope.LIVE, cursor) }
        shouldThrow<IllegalArgumentException> { IncomingQuery(entity(TEMPLATE, "other"), PIPELINE, IncomingScope.ANY, cursor) }
        shouldThrow<IllegalArgumentException> { IncomingQuery(target, VISUALIZATION, IncomingScope.ANY, cursor) }
        shouldThrow<IllegalArgumentException> { IncomingQuery(target, PIPELINE, IncomingScope.ANY, null, 0) }
        shouldThrow<IllegalArgumentException> {
            IncomingQuery(target, PIPELINE, IncomingScope.ANY, null, GraphLimits.MAX_RESOLVE_BATCH_SIZE + 1)
        }
        IncomingQuery(target, PIPELINE, IncomingScope.ANY, cursor).cursor shouldBe cursor
    }

    @Test
    fun `the total order is byte order, versions numeric, and the page builder cuts at the limit`() {
        val a = edge(PIPELINE, "B", 2, "nodes[0].template", 1)
        val b = edge(PIPELINE, "a", 1, "nodes[1].template", 1)
        val c = edge(PIPELINE, "a", 1, "nodes[10].template", 1)
        val d = edge(PIPELINE, "a", 10, "nodes[0].template", 1)
        // Byte order: 'B' (0x42) < 'a' (0x61); "nodes[10" < "nodes[1]" because '0' (0x30) < ']' (0x5D); version 1 < 10.
        listOf(d, c, b, a).sortedWith(IncomingOrder.EDGES) shouldContainExactly listOf(a, c, b, d)
        val q = query(entity(TEMPLATE, "lib"), PIPELINE, limit = 2)
        val page = incomingPage(q, listOf(a, c, b))
        page.edges shouldContainExactly listOf(a, c)
        val next = requireNotNull(page.next) { "a third row means a further page" }
        next shouldBe IncomingCursor(q.target, PIPELINE, IncomingScope.ANY, "a", 1, Relationship.TEMPLATE, "nodes[10].template")
        incomingPage(q, listOf(a, c)).next shouldBe null
        IncomingOrder.isAfter(b, next) shouldBe true
        IncomingOrder.isAfter(c, next) shouldBe false
    }

    private val warehouseRows get() = listOf(row(PIPELINE, "p1", 1, "nodes[0].source", "warehouse", null, DATASOURCE))

    private val warehouseOccurrence get() = listOf(occ("p1", 1, Relationship.DATASOURCE, "nodes[0].source", null))

    // --- fixtures -----------------------------------------------------------------------------------------------------

    /** Pipelines p1/p2 pin lib@2 (p1 twice), lib@3 once; p3 pins only the vanished lib@9; a visualization pins lib@2. */
    private fun templateRows() =
        listOf(
            row(PIPELINE, "p1", 1, "nodes[0].template", "lib", 2),
            row(PIPELINE, "p1", 1, "nodes[1].template", "lib", 2),
            row(PIPELINE, "p2", 4, "nodes[0].template", "lib", 3),
            row(PIPELINE, "p2", 5, "nodes[0].template", "lib", 2),
            row(PIPELINE, "p3", 1, "nodes[0].template", "gone", 9),
            row(VISUALIZATION, "v1", 1, "transform.template", "lib", 2),
            row(TEMPLATE, "imp", 1, "imports[0]", "lib", 3),
            row(PARAMETER_SET, "s1", 1, "parameters[0].source.template", "lib", 3),
        )

    private fun templateFixture(provider: InMemoryIncoming): IncomingConformance.Fixture<FakeBody> {
        val t = Relationship.TEMPLATE
        return IncomingConformance.Fixture(
            descriptor(provider, TEMPLATE),
            context(),
            listOf(
                Case(
                    exactTarget("lib", 2),
                    PIPELINE,
                    IncomingScope.ANY,
                    listOf(
                        occ("p1", 1, t, "nodes[0].template", 2),
                        occ("p1", 1, t, "nodes[1].template", 2),
                        occ("p2", 5, t, "nodes[0].template", 2),
                    ),
                ),
                Case(exactTarget("lib", 3), PIPELINE, IncomingScope.ANY, listOf(occ("p2", 4, t, "nodes[0].template", 3))),
                Case(
                    entity(TEMPLATE, "gone"),
                    PIPELINE,
                    IncomingScope.ANY,
                    listOf(occ("p3", 1, t, "nodes[0].template", 9)),
                    vanishedVersion = 9,
                ),
                Case(
                    exactTarget("lib", 2),
                    VISUALIZATION,
                    IncomingScope.ANY,
                    listOf(occ("v1", 1, Relationship.TRANSFORM_TEMPLATE, "transform.template", 2)),
                ),
                Case(exactTarget("lib", 3), TEMPLATE, IncomingScope.ANY, listOf(occ("imp", 1, Relationship.IMPORT, "imports[0]", 3))),
                Case(
                    exactTarget("lib", 3),
                    PARAMETER_SET,
                    IncomingScope.ANY,
                    listOf(occ("s1", 1, Relationship.PARAMETER_SOURCE_TEMPLATE, "parameters[0].source.template", 3)),
                ),
            ),
            entity(TEMPLATE, "nobody"),
        )
    }

    private fun exactTarget(
        key: String,
        version: Int,
    ) = IncomingTarget.ExactVersion(TEMPLATE, ws, key, version)

    private fun entity(
        family: FamilyKey,
        key: String,
    ) = IncomingTarget.Entity(family, ws, key)

    private fun query(
        target: IncomingTarget,
        dependents: FamilyKey,
        limit: Int = GraphLimits.DEFAULT_RESOLVE_BATCH_SIZE,
    ) = IncomingQuery(target, dependents, IncomingScope.ANY, null, limit)

    private fun occ(
        id: String,
        version: Int,
        relationship: Relationship,
        path: String,
        stored: Int?,
    ) = Occurrence(id, version, relationship, path, stored)

    private fun descriptor(
        provider: InMemoryIncoming,
        key: FamilyKey,
    ) = FamilyDescriptor(
        key,
        SelectorKind.entries.toSet(),
        FamilyKey.PRODUCTION.getValue(key),
        1,
        provider,
        FamilyDescriptor.NO_SAFE_SUMMARY,
    )

    private data class StoredRow(
        val edge: IncomingEdge,
        val targetKey: String,
    )

    private fun edge(
        from: FamilyKey,
        id: String,
        version: Int,
        path: String,
        stored: Int?,
        to: FamilyKey = TEMPLATE,
    ) = IncomingEdge(
        dependent = ResolvedIdentity(from, ws, id, version),
        dependentName = "acme/$id",
        dependentLifecycle = Lifecycle.RELEASED,
        relationship = Relationship.between(from, to)!!,
        sourcePath = path,
        storedVersion = stored,
    )

    private fun row(
        from: FamilyKey,
        id: String,
        version: Int,
        path: String,
        targetKey: String,
        stored: Int?,
        to: FamilyKey = TEMPLATE,
    ) = StoredRow(edge(from, id, version, path, stored, to), targetKey)

    /** An in-memory inverse with switchable defects — each one breaks exactly one harness check. */
    private class InMemoryIncoming(
        private val family: FamilyKey,
        private val rows: List<StoredRow>,
        private val versioned: Boolean = true,
        private val ignoreVersion: Boolean = false,
        private val emptyForUndeclared: Boolean = false,
        private val dropCursor: Boolean = false,
        private val refuseExact: Boolean = true,
    ) : DependencyProvider<FakeBody> {
        override val incomingFrom: Set<FamilyKey> =
            Relationship.entries
                .filter { it.to == family }
                .map { it.from }
                .toSet()

        override fun resolveMany(
            requests: List<ObjectRequest>,
            context: ResolveContext,
        ): Map<ObjectRequest, Outcome<FakeBody>> = requests.associateWith { Outcome.Missing }

        override fun outgoing(resolved: Outcome.Resolved<FakeBody>): List<Reference> = emptyList()

        override fun findIncoming(
            query: IncomingQuery,
            context: ResolveContext,
        ): IncomingResult {
            if (query.dependents !in incomingFrom) {
                return if (emptyForUndeclared) IncomingResult.Page(emptyList(), null) else IncomingResult.Unsupported
            }
            val target = query.target
            if (!versioned && refuseExact) require(target is IncomingTarget.Entity) { "a datasource is referenced by name" }
            val matching =
                rows
                    .filter { it.edge.dependent.family == query.dependents && it.targetKey == target.key }
                    .filter { ignoreVersion || target !is IncomingTarget.ExactVersion || it.edge.storedVersion == target.version }
                    .map { it.edge }
                    .sortedWith(IncomingOrder.EDGES)
                    .filter { edge -> query.cursor?.let { IncomingOrder.isAfter(edge, it) } ?: true }
                    .take(query.limit + 1)
            val page = incomingPage(query, matching)
            return if (dropCursor) page.copy(next = null) else page
        }
    }
}
