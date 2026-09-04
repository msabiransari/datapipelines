package co.datapipelines.web.pipelines

import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceNotFoundException
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [PromotionInventoryService] — the receiver's delta input (versioning §10.2, rest-api §18.1),
 * in the unit layer beside the PromotionTwoDeploymentE2e that exercises it end-to-end.
 *
 * What only this suite pins: the one-read shape (name/version/hash triples from the CURRENT
 * released version — a draft's hash must never appear), sorted deterministically so the
 * sender's diff is stable, template paging beyond the first page, the datasource names as
 * §10.5's pre-validation set, and the trusted-peer 404 for an unknown workspace NAME — the
 * deliberate exception to the no-oracle rule.
 */
class PromotionInventoryServiceTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>()
    private val datasources = mockk<DatasourceRegistry>()
    private val service =
        PromotionInventoryService(
            workspaces = workspaces,
            pipelines = pipelines,
            templates = templates,
            datasources = datasources,
            deploymentName = "prod",
            authoringEnabled = false,
        )

    private val workspaceId = UUID.randomUUID()

    private fun workspace() =
        Workspace(
            id = workspaceId,
            name = "acme",
            displayName = "Acme",
            isPersonal = false,
            createdBy = null,
            isDeleted = false,
            createdAt = Instant.EPOCH,
        )

    private fun pipelineRecord(name: String) =
        PipelineRecord(
            id = UUID.randomUUID(),
            name = name,
            displayName = name,
            description = "",
            ownerId = UUID.randomUUID(),
            currentVersion = 3,
            isDeleted = false,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun detail(hash: String) =
        PipelineVersionDetail(
            pipelineId = UUID.randomUUID(),
            version = 3,
            status = PipelineVersionStatus.RELEASED,
            bodyHash = hash,
            createdAt = Instant.EPOCH,
            createdBy = UUID.randomUUID(),
        )

    private fun template(
        name: String,
        version: Int,
        hash: String,
    ) = Template(
        id = name,
        version = version,
        dialect = Dialect.POSTGRES,
        displayName = name,
        description = "",
        body = "",
        bodyHash = hash,
        createdAt = Instant.EPOCH,
        createdBy = UUID.randomUUID(),
    )

    @Test
    fun `one read carries the delta input - triples, datasource names, posture`() {
        every { workspaces.findByName("acme") } returns workspace()
        every { pipelines.findAll(workspaceId) } returns listOf(pipelineRecord("zeta"), pipelineRecord("alpha"))
        every { pipelines.findCurrentVersionDetail(workspaceId, any()) } returns detail("hash-of-current")
        every { templates.list(workspaceId = workspaceId, offset = 0, limit = 200) } returns
            listOf(template("t_a", 2, "th1"))
        every { datasources.listVisible(dialect = null, workspaceId = workspaceId) } returns
            listOf(ds("pg2"), ds("pg1"))

        val inventory = service.inventoryOf("acme")

        inventory.deployment shouldBe "prod"
        inventory.authoringEnabled shouldBe false
        inventory.workspace shouldBe "acme"
        // Sorted by name — the sender's diff must be stable across calls.
        inventory.pipelines.map { it.name } shouldBe listOf("alpha", "zeta")
        inventory.pipelines.first().currentVersion shouldBe 3
        inventory.pipelines.first().bodyHash shouldBe "hash-of-current"
        inventory.templates.map { it.name } shouldBe listOf("t_a")
        inventory.datasources shouldBe listOf("pg1", "pg2")
    }

    @Test
    fun `a pipeline whose version detail is gone is skipped, not crashed`() {
        every { workspaces.findByName("acme") } returns workspace()
        every { pipelines.findAll(workspaceId) } returns listOf(pipelineRecord("kept"), pipelineRecord("ghost"))
        every { pipelines.findCurrentVersionDetail(workspaceId, any()) } returnsMany listOf(detail("h1"), null)
        every { templates.list(workspaceId = workspaceId, offset = 0, limit = 200) } returns emptyList()
        every { datasources.listVisible(dialect = null, workspaceId = workspaceId) } returns emptyList()

        val inventory = service.inventoryOf("acme")

        inventory.pipelines.map { it.name } shouldBe listOf("kept")
    }

    @Test
    fun `template paging walks past the first page`() {
        every { workspaces.findByName("acme") } returns workspace()
        every { pipelines.findAll(workspaceId) } returns emptyList()
        every { templates.list(workspaceId = workspaceId, offset = 0, limit = 200) } returns
            List(200) { template("t%03d".format(it), 1, "h") }
        every { templates.list(workspaceId = workspaceId, offset = 200, limit = 200) } returns
            listOf(template("t_last", 2, "h2"))
        every { datasources.listVisible(dialect = null, workspaceId = workspaceId) } returns emptyList()

        val inventory = service.inventoryOf("acme")

        inventory.templates.size shouldBe 201
        inventory.templates.last().name shouldBe "t_last"
    }

    @Test
    fun `an unknown workspace name is the trusted-peer 404, not the no-oracle 403`() {
        every { workspaces.findByName("ghost") } returns null

        shouldThrow<WorkspaceNotFoundException> { service.inventoryOf("ghost") }
    }

    @Test
    fun `contextFor resolves the workspace a promotion principal is stamped with`() {
        every { workspaces.findByName("acme") } returns workspace()

        val context = service.contextFor("acme")

        context.id shouldBe workspaceId
        context.name shouldBe "acme"
    }

    private fun ds(name: String) =
        Datasource(
            name = name,
            displayName = name,
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://h/$name",
            username = "u",
            workspaceId = workspaceId,
        )
}
