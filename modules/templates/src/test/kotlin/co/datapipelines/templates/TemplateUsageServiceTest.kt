package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplatePin
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [TemplateUsageService] — the two questions stay two questions, the used-by answer counts
 * pipelines honestly, and the upgrade signal fires only for pins a NEWER RELEASED version
 * outdates. The SQL half of the two-scans discipline is pinned by
 * `PipelineRepositoryIntegrationTest`; this suite owns the service's questions, its
 * not-found splits and its signal edge cases.
 */
class TemplateUsageServiceTest {
    private val templates = mockk<TemplateRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val service = TemplateUsageService(templates, pipelines)

    private val workspaceId = UUID.randomUUID()

    private fun pin(
        pipeline: String,
        node: String,
        pipelineVersion: Int = 1,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
        pinned: Int = 1,
    ) = TemplatePin(UUID.nameUUIDFromBytes(pipeline.toByteArray()), pipeline, pipelineVersion, status, node, pinned)

    @Test
    fun `usedBy answers with per-node references and an honest distinct-pipeline count`() {
        every { templates.existsId(workspaceId, "t.sql") } returns true
        every { templates.findVersionStatus(workspaceId, "t.sql", 1) } returns PipelineVersionStatus.RELEASED
        every { pipelines.findWorkingVersionTemplatePins(workspaceId, "t.sql", 1) } returns
            listOf(pin("p1", "fetch"), pin("p1", "again"), pin("p2", "fetch"))

        val used = service.usedBy(workspaceId, "t.sql", 1)

        used.references.map { it.pipelineName to it.nodeId } shouldBe
            listOf("p1" to "fetch", "p1" to "again", "p2" to "fetch")
        used.pipelineCount shouldBe 2
    }

    @Test
    fun `usedBy splits the miss - unknown id versus known id unknown version`() {
        every { templates.existsId(workspaceId, "nope.sql") } returns false
        val idMiss = shouldThrow<DatapipelinesException> { service.usedBy(workspaceId, "nope.sql", 1) }
        idMiss.code shouldBe PipelineErrorCodes.Template.NOT_FOUND
        idMiss.details.containsKey("version") shouldBe false

        every { templates.existsId(workspaceId, "t.sql") } returns true
        every { templates.findVersionStatus(workspaceId, "t.sql", 9) } returns null
        val versionMiss = shouldThrow<DatapipelinesException> { service.usedBy(workspaceId, "t.sql", 9) }
        versionMiss.code shouldBe PipelineErrorCodes.Template.NOT_FOUND
        versionMiss.details["version"] shouldBe 9
    }

    @Test
    fun `referencedAnywhere is the any-version scan - the delete guard's evidence`() {
        every { pipelines.findAnyVersionTemplatePins(workspaceId, "t.sql") } returns
            listOf(
                pin("p1", "fetch", pipelineVersion = 7, pinned = 1),
                pin("p3", "fetch", pipelineVersion = 2, status = PipelineVersionStatus.DRAFT, pinned = 2),
            )

        service.referencedAnywhere(workspaceId, "t.sql").map { it.pipelineName to it.pinnedVersion } shouldBe
            listOf("p1" to 1, "p3" to 2)
    }

    @Test
    fun `upgradeAvailable fires only when a newer released version exists`() {
        val body =
            """
            {"nodes": [
              {"id": "stale",   "template": {"id": "t.sql", "version": 1}},
              {"id": "current", "template": {"id": "t.sql", "version": 3}},
              {"id": "ahead",   "template": {"id": "t.sql", "version": 5}},
              {"id": "gone",    "template": {"id": "deleted.sql", "version": 1}},
              {"id": "child"}
            ]}
            """.trimIndent()
        every { templates.findCurrentVersions(workspaceId, setOf("t.sql", "deleted.sql")) } returns mapOf("t.sql" to 3)

        service.upgradeAvailable(workspaceId, body) shouldBe
            listOf(TemplateUsageService.UpgradeAvailable("stale", "t.sql", pinned = 1, latestReleased = 3))
    }

    @Test
    fun `upgradeAvailable is empty for bodies with no template pins at all`() {
        every { templates.findCurrentVersions(workspaceId, emptySet()) } returns emptyMap()

        service.upgradeAvailable(workspaceId, """{"nodes": [{"id": "child"}]}""") shouldBe emptyList()
        service.upgradeAvailable(workspaceId, """{"settings": {}}""") shouldBe emptyList()
    }
}
