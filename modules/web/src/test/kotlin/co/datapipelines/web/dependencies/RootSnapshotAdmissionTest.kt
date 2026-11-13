package co.datapipelines.web.dependencies

import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointVersionService
import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.dependencies.VersionInfo
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.scheduler.ScheduleErrorCodes
import co.datapipelines.scheduler.ScheduleException
import co.datapipelines.scheduler.ScheduleService
import co.datapipelines.templates.TemplateService
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

/** Native root admission must authorize the facts captured by the walk, including concurrent version changes. */
class RootSnapshotAdmissionTest {
    private val pipelines = mockk<PipelineService>()
    private val templates = mockk<TemplateService>()
    private val sets = mockk<ParameterSetService>()
    private val visualizations = mockk<VisualizationService>()
    private val dashboards = mockk<DashboardService>()
    private val endpoints = mockk<EndpointPublishService>()
    private val endpointVersions = mockk<EndpointVersionService>()
    private val schedules = mockk<ScheduleService>()
    private val lenses =
        ProductionRootLenses.of(pipelines, templates, sets, visualizations, dashboards, endpoints, endpointVersions, schedules)
    private val principal = member(WorkspaceRole.PROMOTER)
    private val names = ReadLens.Only(setOf(ROOT_NAME))
    private val view = LensedView(names, names, parameterSets = names, visualizations = names, dashboards = names)
    private var nativeVisible = true
    private var nativeStatus = PipelineVersionStatus.RELEASED
    private var nativeHash = ORIGINAL_HASH
    private var nativeRevision = 1

    @BeforeEach
    fun nativeReads() {
        artifactReads()
        entityReads()
    }

    private fun artifactReads() {
        val record = mockk<co.datapipelines.pipeline.PipelineRecord> { every { id } returns UUID.fromString(entityIdOf(ROOT_NAME)) }
        val pipeline =
            mockk<PipelineService.LoadedPipeline> {
                every { version } returns
                    mockk {
                        every { status } answers { nativeStatus }
                        every { bodyHash } answers { nativeHash }
                    }
            }
        every { pipelines.findRecord(any(), any(), any<UUID>()) } returns record
        every { pipelines.findVersion(any(), any(), any(), any()) } answers { pipeline.takeIf { nativeVisible } }
        val template =
            mockk<co.datapipelines.templates.Template> {
                every { status } answers { nativeStatus }
                every { bodyHash } answers { nativeHash }
            }
        every { templates.findVersion(any(), any(), any(), any()) } answers { template.takeIf { nativeVisible } }
        val set =
            mockk<co.datapipelines.parameters.ParameterSetVersion> {
                every { detail } returns
                    mockk {
                        every { status } answers { nativeStatus }
                        every { bodyHash } answers { nativeHash }
                    }
            }
        every { sets.findVersion(any(), any(), any(), any()) } answers { set.takeIf { nativeVisible } }
        val visualization =
            mockk<co.datapipelines.visualization.ArtifactVersion<co.datapipelines.visualization.VisualizationBody>> {
                every { detail } returns
                    mockk {
                        every { status } answers { nativeStatus }
                        every { bodyHash } answers { nativeHash }
                    }
            }
        every { visualizations.findVersion(any(), any(), any(), any()) } answers { visualization.takeIf { nativeVisible } }
        val dashboard =
            mockk<co.datapipelines.visualization.ArtifactVersion<co.datapipelines.visualization.DashboardBody>> {
                every { detail } returns
                    mockk {
                        every { status } answers { nativeStatus }
                        every { bodyHash } answers { nativeHash }
                    }
            }
        every { dashboards.findVersion(any(), any(), any(), any()) } answers { dashboard.takeIf { nativeVisible } }
    }

    private fun entityReads() {
        every { endpoints.get(any(), any()) } returns mockk()
        val endpoint =
            mockk<EndpointVersionService.VersionView> {
                every { version } returns
                    mockk {
                        every { status } answers { nativeStatus }
                        every { bodyHash } answers { nativeHash }
                    }
            }
        every { endpointVersions.version(any(), any(), any()) } answers { endpoint.takeIf { nativeVisible } }
        every { schedules.get(any(), any(), any()) } answers {
            if (!nativeVisible) throw ScheduleException(ScheduleErrorCodes.NOT_FOUND, "Schedule not found")
            mockk { every { revision } answers { nativeRevision } }
        }
    }

    @ParameterizedTest
    @EnumSource(Artifact::class, names = ["API_ENDPOINT"], mode = EnumSource.Mode.EXCLUDE)
    fun `a version released during traversal does not admit the earlier draft snapshot`(artifact: Artifact) {
        val family = StubFamily(artifact.family)
        val leaf = family.put(exact(artifact.family, "acme/old-only", 1))
        val root = family.put(exact(artifact.family, ROOT_NAME, 1), Lifecycle.DRAFT, leaf, bodyHash = ORIGINAL_HASH)
        family.beforeBatch = { requests, _ ->
            if (leaf in requests) {
                nativeStatus = PipelineVersionStatus.RELEASED
                nativeHash = EDITED_HASH
                family.put(root, Lifecycle.RELEASED, bodyHash = EDITED_HASH)
            }
        }

        notFound { read(family, artifact.lens(lenses)) }
    }

    @ParameterizedTest
    @EnumSource(Artifact::class)
    fun `an unchanged admitted native version remains visible`(artifact: Artifact) {
        val family = StubFamily(artifact.family)
        family.put(exact(artifact.family, ROOT_NAME, 1), bodyHash = ORIGINAL_HASH)

        read(family, artifact.lens(lenses)).nodes.single().bodyHash shouldBe ORIGINAL_HASH
    }

    @ParameterizedTest
    @EnumSource(Artifact::class, names = ["API_ENDPOINT"], mode = EnumSource.Mode.EXCLUDE)
    fun `a release without a body edit still requires the captured lifecycle to match`(artifact: Artifact) {
        val family = StubFamily(artifact.family)
        val leaf = family.put(exact(artifact.family, "acme/old-only", 1))
        val root = family.put(exact(artifact.family, ROOT_NAME, 1), Lifecycle.DRAFT, leaf, bodyHash = ORIGINAL_HASH)
        nativeStatus = PipelineVersionStatus.DRAFT
        family.beforeBatch = { requests, _ ->
            if (leaf in requests) {
                nativeStatus = PipelineVersionStatus.RELEASED
                family.put(root, Lifecycle.RELEASED, leaf, bodyHash = ORIGINAL_HASH)
            }
        }

        notFound { read(family, artifact.lens(lenses)) }
    }

    @ParameterizedTest
    @EnumSource(Artifact::class)
    fun `a version the native read hides stays hidden`(artifact: Artifact) {
        val family = StubFamily(artifact.family)
        family.put(exact(artifact.family, ROOT_NAME, 1), bodyHash = ORIGINAL_HASH)
        nativeVisible = false

        notFound { read(family, artifact.lens(lenses)) }
    }

    @Test
    fun `an API draft repinned during traversal does not admit its previous hash and references`() {
        val family = StubFamily(FamilyKey.API_ENDPOINT)
        val leaf = family.put(exact(FamilyKey.API_ENDPOINT, "acme/old-only", 1))
        val root = family.put(exact(FamilyKey.API_ENDPOINT, ROOT_NAME, 1), Lifecycle.DRAFT, leaf, bodyHash = ORIGINAL_HASH)
        nativeStatus = PipelineVersionStatus.DRAFT
        family.beforeBatch = { requests, _ ->
            if (leaf in requests) {
                nativeHash = EDITED_HASH
                family.put(root, Lifecycle.DRAFT, bodyHash = EDITED_HASH)
            }
        }

        notFound { read(family, lenses.apiEndpoint) }
    }

    @Test
    fun `an unchanged API draft admitted by its native read remains visible`() {
        val family = StubFamily(FamilyKey.API_ENDPOINT)
        family.put(exact(FamilyKey.API_ENDPOINT, ROOT_NAME, 1), Lifecycle.DRAFT, bodyHash = ORIGINAL_HASH)
        nativeStatus = PipelineVersionStatus.DRAFT

        read(family, lenses.apiEndpoint).nodes.single().lifecycle shouldBe "DRAFT"
    }

    @Test
    fun `a changed schedule revision does not admit the earlier target snapshot`() {
        val family = schedule { nativeRevision++ }

        notFound { readSchedule(family) }
    }

    @Test
    fun `a schedule hidden during traversal stays hidden`() {
        val family = schedule { nativeVisible = false }

        notFound { readSchedule(family) }
    }

    @Test
    fun `an unchanged admitted schedule revision remains visible`() {
        readSchedule(schedule {}).nodes.first().version shouldBe WireNumbered("revision", 1)
    }

    private fun schedule(afterRootRead: () -> Unit): StubFamily {
        val family = StubFamily(FamilyKey.SCHEDULE)
        val leaf = family.put(entityRequest(FamilyKey.SCHEDULE, SCHEDULE_LEAF), lifecycle = null)
        family.put(
            entityRequest(FamilyKey.SCHEDULE, SCHEDULE_ROOT),
            lifecycle = null,
            targets = arrayOf(leaf),
            versionInfo = VersionInfo.Revision(1),
        )
        family.beforeBatch = { requests, _ -> if (leaf in requests) afterRootRead() }
        return family
    }

    private fun readSchedule(family: StubFamily): DependencyTreeData {
        val row = FamilyTransportRow(FamilyKey.SCHEDULE, SelectorShape.ID, ANY_KEY, ScheduleErrorCodes.NOT_FOUND, lenses.schedule)
        return treeService(listOf(family), listOf(row), view = { view }).tree(principal, FamilyKey.SCHEDULE, "id=$SCHEDULE_ROOT")
    }

    private fun read(
        family: StubFamily,
        lens: RootLens,
    ): DependencyTreeData =
        treeService(listOf(family), listOf(stubRow(family.key, lens = lens)), view = { view })
            .tree(principal, family.key, "name=$ROOT_NAME&version=1")

    private fun notFound(read: () -> Any) {
        shouldThrow<DatapipelinesException> { read() }.message shouldBe DependencyTreeService.NOT_FOUND_MESSAGE
    }

    enum class Artifact(
        val family: FamilyKey,
        val lens: (DependencyFamilyTransport.ProductionRootLenses) -> RootLens,
    ) {
        PIPELINE(FamilyKey.PIPELINE, { it.pipeline }),
        TEMPLATE(FamilyKey.TEMPLATE, { it.template }),
        PARAMETER_SET(FamilyKey.PARAMETER_SET, { it.parameterSet }),
        VISUALIZATION(FamilyKey.VISUALIZATION, { it.visualization }),
        DASHBOARD(FamilyKey.DASHBOARD, { it.dashboard }),
        API_ENDPOINT(FamilyKey.API_ENDPOINT, { it.apiEndpoint }),
    }

    companion object {
        private const val ROOT_NAME = "acme/root"
        private const val ORIGINAL_HASH = "original-root-hash"
        private const val EDITED_HASH = "edited-root-hash"
        private const val SCHEDULE_ROOT = "00000000-0000-0000-0000-000000000875"
        private const val SCHEDULE_LEAF = "00000000-0000-0000-0000-000000000876"
    }
}
