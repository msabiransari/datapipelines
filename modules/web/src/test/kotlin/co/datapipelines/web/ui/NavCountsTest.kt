package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.ReadLens
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

/**
 * #554, #574 — the resource browser header's admitted counts: one catalog-size read per family on
 * every call (no cache since #574), the lensed arms answered from the view, an unreadable count
 * omitted (null) and never remembered. The sources are recording lambdas, not mocks: what is asserted
 * is the reads that HAPPENED (a missing read would show as a count, an extra one as a tally).
 */
class NavCountsTest {
    private val workspace = UUID.randomUUID()
    private val reads = mutableListOf<Pair<NavigationFamily, UUID>>()
    private var failing = false

    private val values =
        mapOf(
            NavigationFamily.PIPELINES to 9,
            NavigationFamily.TEMPLATES to 27,
            NavigationFamily.DASHBOARDS to 4,
            NavigationFamily.VISUALIZATIONS to 12,
            NavigationFamily.PARAMETER_SETS to 3,
        )

    private val counts =
        NavCounts(
            sources =
                NavigationFamily.entries.associateWith { family ->
                    { ws: UUID ->
                        reads += family to ws
                        if (failing) error("count read failed")
                        values.getValue(family)
                    }
                },
        )

    @Test
    fun `every family's live catalog size lands on its own property`() {
        counts.forWorkspace(workspace) shouldBe NavCounts.Counts(9, 27, dashboards = 4, visualizations = 12, parameterSets = 3)
        reads.map { it.first }.toSet() shouldBe NavigationFamily.entries.toSet()
        reads.map { it.second }.toSet() shouldBe setOf(workspace)
    }

    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `Counts of a family answers that family's property`(family: NavigationFamily) {
        NavCounts.Counts.of(values).of(family) shouldBe values.getValue(family)
        NavCounts.Counts.NONE.of(family) shouldBe null
    }

    @Test
    fun `every render reads, so a count changed between two renders is the second render's answer`() {
        val pipelines = intArrayOf(9)
        val live = NavCounts(mapOf(NavigationFamily.PIPELINES to { _: UUID -> pipelines[0] }))
        live.forWorkspace(workspace).pipelines shouldBe 9

        // #574: a create between two renders, no clock involved — the next render already counts it.
        pipelines[0] = 10
        live.forWorkspace(workspace).pipelines shouldBe 10
    }

    @Test
    fun `each render reads every family once for the workspace it names`() {
        val other = UUID.randomUUID()
        counts.forWorkspace(workspace).pipelines
        counts.forWorkspace(workspace).templates
        counts.forWorkspace(other).dashboards
        reads.count { it.second == workspace } shouldBe 2 * NavigationFamily.entries.size
        reads.count { it.second == other } shouldBe NavigationFamily.entries.size
    }

    @Test
    fun `nothing is read until a value is, and one render's every value costs one read per family`() {
        val rendered = counts.forWorkspace(workspace)
        // #574: the shell advice builds this on JSON requests too, which never read a value.
        reads shouldBe emptyList()

        rendered.pipelines shouldBe 9
        rendered.templates shouldBe 27
        rendered.of(NavigationFamily.PARAMETER_SETS) shouldBe 3
        rendered.pipelines shouldBe 9
        reads.size shouldBe NavigationFamily.entries.size
    }

    @Test
    fun `a failed read omits every live count and is not remembered`() {
        failing = true
        counts.forWorkspace(workspace) shouldBe NavCounts.Counts.NONE

        failing = false
        reads.clear()
        counts.forWorkspace(workspace).templates shouldBe 27
        reads.isEmpty() shouldBe false
    }

    @Test
    fun `no workspace and an unavailable lens both answer no count without a read`() {
        counts.forWorkspace(null) shouldBe NavCounts.Counts.NONE
        val unavailable =
            LensedView(
                ReadLens.NOTHING,
                ReadLens.NOTHING,
                unavailable = LensedView.Unavailable("https://prod.example", "connect_refused"),
            )
        counts.forWorkspace(workspace, unavailable) shouldBe NavCounts.Counts.NONE
        reads shouldBe emptyList()
    }

    @Test
    fun `a narrowed arm answers the size of its admitted set over the live count`() {
        val view = LensedView(ReadLens.Only(setOf("a/x", "a/y")), ReadLens.Everything, dashboards = ReadLens.Only(emptySet()))

        counts.forWorkspace(workspace, view) shouldBe
            NavCounts.Counts(2, 27, dashboards = 0, visualizations = 12, parameterSets = 3)
    }

    @Test
    fun `a view narrowing all five families answers from the view alone`() {
        val view =
            LensedView(
                ReadLens.Only(setOf("p")),
                ReadLens.Only(setOf("t1", "t2")),
                parameterSets = ReadLens.Only(emptySet()),
                visualizations = ReadLens.Only(setOf("v")),
                dashboards = ReadLens.Only(setOf("d1", "d2", "d3")),
            )

        counts.forWorkspace(workspace, view) shouldBe NavCounts.Counts(1, 2, dashboards = 3, visualizations = 1, parameterSets = 0)
        reads shouldBe emptyList()
    }

    @Test
    fun `a narrowed arm's count survives a failed live read`() {
        failing = true
        val view = LensedView(ReadLens.Only(setOf("a/x")), ReadLens.Everything)

        counts.forWorkspace(workspace, view) shouldBe NavCounts.Counts(1, null)
    }

    @Test
    fun `a family without a source has no count`() {
        val partial = NavCounts(mapOf(NavigationFamily.PIPELINES to { _: UUID -> 5 }))

        partial.forWorkspace(workspace) shouldBe NavCounts.Counts(5, null)
    }
}
