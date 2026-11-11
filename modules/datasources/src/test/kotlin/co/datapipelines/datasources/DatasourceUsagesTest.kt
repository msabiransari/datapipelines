package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #873 §F.3 e — [DatasourceUsages]: the lock bit counts every workspace, the lists keep only what the caller's
 * [ReferenceVisibility] admits, in scan order.
 */
class DatasourceUsagesTest {
    private val a = UUID(0xA, 1)
    private val b = UUID(0xB, 1)

    private val pipelines =
        listOf(
            DatasourceReference("acme/report", 1, "RELEASED", "extract", a),
            DatasourceReference("globex/report", 3, "DRAFT", "load", b),
            DatasourceReference("acme/report", 2, "DISCARDED", "load", a),
        )
    private val sets =
        listOf(
            ParameterSetDatasourceReference("globex/filters", 1, "RELEASED", "parameters[0].source.datasource", b),
            ParameterSetDatasourceReference("acme/filters", 2, "DRAFT", "parameters[1].source.datasource", a),
        )

    @Test
    fun `a workspace scope keeps its own references in scan order and still reports the lock`() {
        val usages = DatasourceUsages.of(pipelines, sets, ReferenceVisibility.Workspace(a))

        usages.inUse shouldBe true
        usages.named shouldBe true
        usages.references shouldBe listOf(pipelines[0], pipelines[2])
        usages.parameterSetReferences shouldBe listOf(sets[1])
        usages.referencingPipelines shouldBe listOf("acme/report")
        usages.referencingParameterSets shouldBe listOf("acme/filters")
    }

    @Test
    fun `references held only elsewhere lock the name and name nothing`() {
        val usages = DatasourceUsages.of(listOf(pipelines[1]), listOf(sets[0]), ReferenceVisibility.Workspace(a))

        usages shouldBe DatasourceUsages(inUse = true, references = emptyList(), parameterSetReferences = emptyList())
        usages.named shouldBe false
        // A different outside population is the same value: nothing outside the scope reaches the instance.
        val more = pipelines.filter { it.workspaceId == b } + pipelines[1]
        DatasourceUsages.of(more, sets, ReferenceVisibility.Workspace(UUID(0xC, 1))) shouldBe usages
    }

    @Test
    fun `everything keeps every reference, and no reference at all is not in use`() {
        DatasourceUsages.of(pipelines, sets, ReferenceVisibility.Everything).references shouldBe pipelines
        DatasourceUsages.of(pipelines, sets, ReferenceVisibility.Everything).parameterSetReferences shouldBe sets
        DatasourceUsages.of(emptyList(), emptyList(), ReferenceVisibility.Workspace(a)).inUse shouldBe false
    }

    @Test
    fun `the delete result scopes through the same rule`() {
        val result =
            DeleteResult(
                deleted = false,
                name = "x",
                errorCode = "datasource.in_use",
                references = pipelines,
                parameterSetReferences = sets,
            )

        result.usagesVisibleTo(ReferenceVisibility.Workspace(b)) shouldBe
            DatasourceUsages(inUse = true, references = listOf(pipelines[1]), parameterSetReferences = listOf(sets[0]))
    }
}
