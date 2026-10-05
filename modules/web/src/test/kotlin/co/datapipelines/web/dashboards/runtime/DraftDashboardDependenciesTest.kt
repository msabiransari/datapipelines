package co.datapipelines.web.dashboards.runtime

import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateImport
import co.datapipelines.templates.TemplateService
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Exact nested pins, mutable content and lifecycle failures in the draft configuration fingerprint. */
class DraftDashboardDependenciesTest {
    private val workspace = UUID.randomUUID()
    private val repository = mockk<PipelineRepository>()
    private val pipelines = mockk<PipelineService>()
    private val templates = mockk<TemplateService>()
    private val graph =
        DraftDashboardDependencies(repository, pipelines, templates) { kind, name, reason ->
            IllegalStateException("$kind|$name|$reason")
        }
    private val root = source("root", listOf(node(TemplateRef(), PipelineNodeRef("child", 2))))
    private val child = source("child", listOf(node(TemplateRef("sql", 2))))
    private val sql = template("sql", listOf(TemplateImport("library", 2, "lib")))
    private val library = template("library", emptyList())

    private fun node(
        templateRef: TemplateRef,
        ref: PipelineNodeRef? = null,
    ): Node =
        mockk {
            every { template } returns templateRef
            every { pipeline } returns ref
        }

    private fun source(
        name: String,
        nodes: List<Node>,
    ): ResolvedSource {
        val id = UUID.randomUUID()
        val source = mockk<ResolvedSource>()
        every { source.record.id } returns id
        every { source.record.name } returns name
        every { source.version } returns 2
        every { source.executable.bodyJson } returns name
        every { source.executable.pipeline.nodes } returns nodes
        every { repository.findVersionDetail(workspace, id, 2) } returns
            mockk {
                every { status } returns PipelineVersionStatus.DRAFT
                every { bodyHash } returns "$name-first"
            }
        return source
    }

    private fun template(
        name: String,
        imports: List<TemplateImport>,
    ): Template {
        val body = mockk<Template>()
        every { body.status } returns PipelineVersionStatus.DRAFT
        every { body.bodyHash } returns "$name-first"
        every { body.imports } returns imports
        every { templates.findVersion(workspace, ReadLens.Everything, name, 2) } returns body
        return body
    }

    private fun hashes(): List<String> {
        every { repository.findByNameAnyStatus(workspace, "child") } returns child.record
        every { pipelines.findExecutable(workspace, ReadLens.Everything, child.record, 2) } returns child.executable
        return graph.hashes(workspace, emptyMap(), null, mapOf("source" to root))
    }

    @Test
    fun `nested draft pipeline and library edits change the fingerprint at the exact version`() {
        hashes() shouldContain "template|library|2|library-first"
        every { library.bodyHash } returns "library-edited"
        hashes() shouldContain "template|library|2|library-edited"
        every { repository.findVersionDetail(workspace, child.record.id, 2) } returns
            mockk {
                every { status } returns PipelineVersionStatus.DRAFT
                every { bodyHash } returns "child-edited"
            }
        hashes() shouldContain "pipeline|child|2|child-edited"
    }

    @Test
    fun `discarded nested pipelines fail closed instead of contributing an empty hash`() {
        every { repository.findVersionDetail(workspace, child.record.id, 2) } returns
            mockk {
                every { status } returns PipelineVersionStatus.DISCARDED
            }
        assertThrows<IllegalStateException> { hashes() }.message shouldBe "source|child|not_released"
    }

    @Test
    fun `missing and discarded nested imports fail closed`() {
        every { templates.findVersion(workspace, ReadLens.Everything, "library", 2) } returns null
        assertThrows<IllegalStateException> { hashes() }.message shouldBe "template|library|not_found"
        every { templates.findVersion(workspace, ReadLens.Everything, "library", 2) } returns library
        every { library.status } returns PipelineVersionStatus.DISCARDED
        assertThrows<IllegalStateException> { hashes() }.message shouldBe "template|library|not_released"
    }

    @Test
    fun `cycles terminate and each exact pin contributes once`() {
        every { library.imports } returns listOf(TemplateImport("sql", 2, "cycle"))
        hashes().size shouldBe 6
        every { sql.imports } returns emptyList()
        hashes().size shouldBe 5
    }
}
