package co.datapipelines.dependencies

import co.datapipelines.dependencies.FamilyKey.Companion.PIPELINE
import co.datapipelines.dependencies.FamilyKey.Companion.TEMPLATE
import co.datapipelines.dependencies.conformance.ProviderConformance
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** A28's registry half: the synthetic `test_widget` family joins a test-built registry with no walker change. */
class RegistryAndSyntheticFamilyTest {
    @Test
    fun `a synthetic family registered by the test is traversed by the unchanged walker`() {
        val g = TestGraph()
        val template = g.node(exact(TEMPLATE, "t", 1), Lifecycle.RELEASED)
        val gadget = g.node(exact(TEST_WIDGET, "gadget", 2), Lifecycle.DRAFT, pin(TEST_WIDGET, template, "parts[0]"))
        val root = g.node(exact(PIPELINE, "p", 1), Lifecycle.DRAFT, pin(PIPELINE, gadget, "nodes[0].widget"))

        val snapshot = g.walk(root)

        snapshot.nodes.map { it.family } shouldContainExactly listOf(PIPELINE, TEST_WIDGET, TEMPLATE)
        snapshot.nodes.map { it.resolution }.toSet() shouldBe setOf(Resolution.RESOLVED)
        snapshot.complete shouldBe true
    }

    @Test
    fun `removing the synthetic family from the registry turns its request into UNSUPPORTED, never an empty set`() {
        val g = TestGraph()
        val gadget = exact(TEST_WIDGET, "gadget", 2)
        val root = g.node(exact(PIPELINE, "p", 1), Lifecycle.DRAFT, pin(PIPELINE, gadget, "nodes[0].widget"))
        val snapshot = g.walk(root) // TEST_WIDGET was never registered in this graph
        snapshot.node(snapshot.idOf("gadget")).resolution shouldBe Resolution.UNSUPPORTED
        snapshot.diagnostics.single().kind shouldBe DiagnosticKind.UNSUPPORTED_FAMILY
        snapshot.complete shouldBe false
    }

    @Test
    fun `a duplicate family registration throws at construction`() {
        val widget = FakeFamily(TEST_WIDGET)
        shouldThrow<IllegalArgumentException> { FamilyRegistry(listOf(widget.descriptor(), FakeFamily(TEST_WIDGET).descriptor())) }
            .message shouldContain "duplicate family registration: test_widget"
        FamilyRegistry(listOf(widget.descriptor())).keys shouldContainExactly listOf(TEST_WIDGET)
    }

    @Test
    fun `the production assembly refuses a family outside PRODUCTION and a misdeclared capability pair`() {
        shouldThrow<IllegalArgumentException> { FamilyRegistry.production(listOf(FakeFamily(TEST_WIDGET).descriptor())) }
            .message shouldContain "test_widget is not a production family"
        val misdeclared = FakeFamily(FamilyKey.SCHEDULE, capabilities = FamilyKey.PRODUCTION.getValue(PIPELINE))
        shouldThrow<IllegalArgumentException> { FamilyRegistry.production(listOf(misdeclared.descriptor())) }
            .message shouldContain "schedule declares"
        shouldThrow<IllegalArgumentException> {
            FamilyRegistry.production(listOf(FakeFamily(PIPELINE).descriptor(), FakeFamily(PIPELINE).descriptor()))
        }
        FamilyRegistry.production(listOf(FakeFamily(PIPELINE).descriptor(), FakeFamily(TEMPLATE).descriptor())).keys shouldBe
            setOf(PIPELINE, TEMPLATE)
    }

    @Test
    fun `the synthetic family passes the provider conformance harness`() {
        val widget = FakeFamily(TEST_WIDGET)
        val present = exact(TEST_WIDGET, "gadget", 1)
        widget.put(
            present,
            TestGraph.facts(present, Lifecycle.DRAFT),
            listOf(pin(TEST_WIDGET, exact(TEMPLATE, "t", 1), "parts[0]"), pin(TEST_WIDGET, exact(TEMPLATE, "u", 1), "parts[1]")),
        )
        val fixture =
            ProviderConformance.Fixture(
                descriptor = widget.descriptor(),
                context = context(),
                present = listOf(present),
                absent = listOf(exact(TEST_WIDGET, "nothing", 1)),
                references = ProviderConformance.References.Fields(mapOf(present to setOf("parts[0]", "parts[1]"))),
            )
        ProviderConformance.check(fixture).shouldBeEmpty()
    }

    @Test
    fun `the harness reports each way a provider can break the contract`() {
        val broken = FakeFamily(PIPELINE)
        val present = exact(PIPELINE, "p", 1)
        val absent = exact(PIPELINE, "missing", 1)
        broken.put(present, TestGraph.facts(present, Lifecycle.DRAFT), listOf(Reference(exact(TEMPLATE, "t", 1), Relationship.IMPORT, "x")))
        broken.unsupported = setOf(absent)
        val violations =
            ProviderConformance.check(
                ProviderConformance.Fixture(
                    broken.descriptor(),
                    context(),
                    listOf(present),
                    listOf(absent),
                    ProviderConformance.References.Fields(mapOf(present to setOf("y"))),
                ),
            )
        violations.any { "not MISSING" in it } shouldBe true
        violations.any { "is not a §6 row" in it } shouldBe true
        violations.any { "field y" in it } shouldBe true
        val nothing = ProviderConformance.References.Fields(emptyMap())
        val vacuous = ProviderConformance.Fixture(broken.descriptor(), context(), emptyList(), emptyList(), nothing)
        ProviderConformance.check(vacuous).size shouldBe 3 // vacuous fixtures are refused, never green
    }
}
