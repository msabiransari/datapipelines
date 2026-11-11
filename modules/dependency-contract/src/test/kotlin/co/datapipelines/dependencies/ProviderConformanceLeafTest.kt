package co.datapipelines.dependencies

import co.datapipelines.dependencies.FamilyKey.Companion.DATASOURCE
import co.datapipelines.dependencies.FamilyKey.Companion.PIPELINE
import co.datapipelines.dependencies.FamilyKey.Companion.TEMPLATE
import co.datapipelines.dependencies.conformance.ProviderConformance
import co.datapipelines.dependencies.conformance.ProviderConformance.References
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #868 R3: the harness admits a legitimate LEAF family — datasource has no outgoing row in record §6 — without a fake
 * edge, while a family that has reference-bearing fields still states every one of them, exactly, and no suite is vacuous.
 */
class ProviderConformanceLeafTest {
    private val present = exact(DATASOURCE, "warehouse", 1)
    private val absent = exact(DATASOURCE, "nothing", 1)

    @Test
    fun `a datasource-style leaf family with a present and a MISSING fixture and no references conforms`() {
        val datasource = leaf()
        ProviderConformance.check(fixture(datasource.descriptor(), References.None)).shouldBeEmpty()
        datasource.requested() shouldContainExactly listOf(present, absent) // the suite really called the provider
    }

    @Test
    fun `a vacuous leaf suite is refused - no present request, no absent request`() {
        check(leaf().descriptor(), References.None, present = emptyList(), absent = emptyList()) shouldContainExactly
            listOf(
                "datasource: the fixture has no present request (vacuous)",
                "datasource: the fixture has no absent request (MISSING never exercised)",
            )
    }

    @Test
    fun `a leaf family whose body yields a reference is refused - None means none`() {
        val datasource = FakeFamily(DATASOURCE)
        datasource.put(present, TestGraph.facts(present, null), listOf(Reference(exact(TEMPLATE, "t", 1), Relationship.IMPORT, "x")))
        val violations = ProviderConformance.check(fixture(datasource.descriptor(), References.None))
        violations.filter { "undeclared field x" in it }.size shouldBe 1
    }

    @Test
    fun `a family that section 6 gives outgoing rows cannot declare None, and a leaf cannot declare fields`() {
        val pipeline = FakeFamily(PIPELINE)
        val p = exact(PIPELINE, "p", 1)
        pipeline.put(p, TestGraph.facts(p, Lifecycle.DRAFT), emptyList())
        check(pipeline.descriptor(), References.None, listOf(p), listOf(exact(PIPELINE, "gone", 1))) shouldContainExactly
            listOf("pipeline: section 6 gives pipeline outgoing rows, so the fixture declares its reference fields")
        ProviderConformance
            .check(fixture(leaf().descriptor(), References.Fields(mapOf(present to setOf("x")))))
            .filter { "section 6 gives datasource no outgoing row" in it }
            .size shouldBe 1
    }

    @Test
    fun `a family with reference fields - a missing declared reference, an undeclared one and an empty declaration are refused`() {
        val pipeline = FakeFamily(PIPELINE)
        val p = exact(PIPELINE, "p", 1)
        val gone = exact(PIPELINE, "gone", 1)
        pipeline.put(p, TestGraph.facts(p, Lifecycle.DRAFT), listOf(pin(PIPELINE, exact(TEMPLATE, "t", 1), "nodes[0].template")))
        // Exact: the one produced field declared — green.
        val exactly = References.Fields(mapOf(p to setOf("nodes[0].template")))
        check(pipeline.descriptor(), exactly, listOf(p), listOf(gone)).shouldBeEmpty()
        // A declared field the body does not reference.
        val oneTooMany = References.Fields(mapOf(p to setOf("nodes[0].template", "nodes[1].pipeline")))
        check(pipeline.descriptor(), oneTooMany, listOf(p), listOf(gone)) shouldContainExactly
            listOf("pipeline: reference-bearing field nodes[1].pipeline of $p produced no reference")
        // A produced field the fixture did not declare (the request is present but not named).
        val q = exact(PIPELINE, "q", 1)
        pipeline.put(q, TestGraph.facts(q, Lifecycle.DRAFT), listOf(pin(PIPELINE, exact(TEMPLATE, "u", 1), "nodes[0].template")))
        check(pipeline.descriptor(), exactly, listOf(p, q), listOf(gone)) shouldContainExactly
            listOf("pipeline: $q produced a reference at undeclared field nodes[0].template")
        // An empty declaration is a vacuous suite, never a leaf by accident.
        check(pipeline.descriptor(), References.Fields(emptyMap()), listOf(p), listOf(gone)) shouldContainExactly
            listOf(
                "pipeline: the fixture declares no reference-bearing field (an empty suite; a leaf declares None)",
                "pipeline: $p produced a reference at undeclared field nodes[0].template",
            )
    }

    /** A datasource-style provider: one present object with no references; everything else MISSING. */
    private fun leaf(): FakeFamily = FakeFamily(DATASOURCE).apply { put(present, TestGraph.facts(present, null), emptyList()) }

    private fun fixture(
        descriptor: FamilyDescriptor<FakeBody>,
        references: References,
        present: List<ObjectRequest> = listOf(this.present),
        absent: List<ObjectRequest> = listOf(this.absent),
    ) = ProviderConformance.Fixture(descriptor, context(), present, absent, references)

    private fun check(
        descriptor: FamilyDescriptor<FakeBody>,
        references: References,
        present: List<ObjectRequest> = listOf(this.present),
        absent: List<ObjectRequest> = listOf(this.absent),
    ): List<String> = ProviderConformance.check(fixture(descriptor, references, present, absent))
}
