package co.datapipelines.web.templates

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 142 — [TemplateReleaseService.releasePinned], the leg the pipeline release cascade rides
 * over mocked repositories: it releases exactly the PINNED version at the draft's current
 * hash through the ordinary [TemplateReleaseService.release] path (one implementation), and
 * refuses — with the template's own codes, unmapped — when the template holds no draft or
 * its draft is a different version than the pin names.
 */
class TemplateReleaseServiceTest {
    private val templates = mockk<TemplateRepository>()
    private val validator = mockk<TemplateValidator>()
    private val usage = mockk<co.datapipelines.application.templates.TemplateUsage>()
    private val service = TemplateReleaseService(templates, validator, AuthoringGuard(true), usage)

    private val workspaceId = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    private fun draft(version: Int) =
        TemplateVersionDetail(
            templateId = "test/t.sql",
            version = version,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = "draft-hash-$version",
            createdAt = Instant.EPOCH,
            createdBy = actor,
        )

    private fun summary(version: Int) =
        co.datapipelines.templates.TemplateVersionSummary(
            id = "test/t.sql",
            version = version,
            status = PipelineVersionStatus.DRAFT,
            createdAt = Instant.EPOCH,
            createdBy = actor,
        )

    private fun stored(version: Int) =
        Template(
            id = "test/t.sql",
            version = version,
            dialect = Dialect.H2,
            displayName = "T",
            description = "d",
            body = "SELECT 1",
            createdAt = Instant.EPOCH,
            createdBy = actor,
        )

    private fun setPin(name: String = "acme/sales/pins_only") =
        co.datapipelines.parameters.ParameterSetPin(
            setId = UUID.randomUUID(),
            setName = name,
            parameter = "state",
            setVersion = 1,
            versionStatus = PipelineVersionStatus.DRAFT,
            pinnedVersion = 1,
        )

    private fun vizPin(name: String = "acme/charts/revenue") =
        co.datapipelines.visualization.ArtifactPin(UUID.randomUUID(), name, 1, PipelineVersionStatus.DRAFT, 1)

    private fun pipelinePin(name: String = "acme/p") =
        co.datapipelines.pipeline.TemplatePin(UUID.randomUUID(), name, 2, PipelineVersionStatus.RELEASED, "n1", 1)

    private fun pins(
        pipelines: List<co.datapipelines.pipeline.TemplatePin> = emptyList(),
        sets: List<co.datapipelines.parameters.ParameterSetPin> = emptyList(),
        visualizations: List<co.datapipelines.visualization.ArtifactPin> = emptyList(),
    ) = co.datapipelines.application.templates.TemplateUsage
        .Pins(pipelines, sets, visualizations)

    private fun stubTemplate(vararg versions: Int) {
        every { templates.existsId(workspaceId, "test/t.sql") } returns true
        every { templates.listVersions(workspaceId, "test/t.sql") } returns versions.map(::summary)
    }

    @Test
    fun `purgeEntity refuses when a PARAMETER SET alone pins any version of the template - 194d`() {
        // The record's §8.4 red: a template pinned by a set alone was deletable. The set
        // scanner is the evidence that closes it; the other arms stay empty.
        stubTemplate(1)
        every { usage.everPins(workspaceId, "test/t.sql") } returns pins(sets = listOf(setPin()))

        val error = shouldThrow<DatapipelinesException> { service.purgeEntity(workspaceId, "test/t.sql") }

        error.code shouldBe PipelineErrorCodes.Template.IN_USE
        error.details["referencing_parameter_sets"] shouldBe listOf("acme/sales/pins_only")
        error.details.containsKey("referencing_visualizations") shouldBe false
        verify(exactly = 0) { templates.deleteTemplateRow(any(), any()) }
    }

    @Test
    fun `purgeEntity refuses when a VISUALIZATION alone pins any version of the template - 320`() {
        stubTemplate(1)
        every { usage.everPins(workspaceId, "test/t.sql") } returns pins(visualizations = listOf(vizPin(), vizPin()))

        val error = shouldThrow<DatapipelinesException> { service.purgeEntity(workspaceId, "test/t.sql") }

        error.code shouldBe PipelineErrorCodes.Template.IN_USE
        error.details["referencing_visualizations"] shouldBe listOf("acme/charts/revenue")
        error.details["pinned_by"] shouldBe emptyList<String>()
        error.message shouldBe
            "Version of template 'test/t.sql' is pinned by 1 visualization version(s): acme/charts/revenue; discard or repoint them first."
        verify(exactly = 0) { templates.deleteTemplateRow(any(), any()) }
    }

    @Test
    fun `the refusal names every arm that pins it - pipelines, sets and visualizations, in one sentence`() {
        stubTemplate(1)
        every { usage.everPins(workspaceId, "test/t.sql") } returns
            pins(listOf(pipelinePin("acme/p")), listOf(setPin()), listOf(vizPin()))

        val error = shouldThrow<DatapipelinesException> { service.purgeEntity(workspaceId, "test/t.sql") }

        error.details["pinned_by"] shouldBe listOf("acme/p")
        error.details["referencing_parameter_sets"] shouldBe listOf("acme/sales/pins_only")
        error.details["referencing_visualizations"] shouldBe listOf("acme/charts/revenue")
        error.message shouldBe
            "Version of template 'test/t.sql' is pinned by 1 live pipeline version(s): acme/p and " +
            "1 parameter set version(s): acme/sales/pins_only and 1 visualization version(s): acme/charts/revenue; " +
            "discard or repoint them first."
    }

    @Test
    fun `purgeEntity proceeds when nothing - pipeline, set or visualization - pins any version`() {
        stubTemplate(1)
        every { usage.everPins(workspaceId, "test/t.sql") } returns pins()
        every { templates.deleteTemplateRow(workspaceId, "test/t.sql") } returns true

        service.purgeEntity(workspaceId, "test/t.sql")

        verify(exactly = 1) { templates.deleteTemplateRow(workspaceId, "test/t.sql") }
    }

    // ---- 320, gap one: the DRAFT purges ran NO pin check on any arm

    @Test
    fun `a draft purge that takes the entity is an ENTITY purge - a set that pins the draft refuses it, nothing is deleted`() {
        stubTemplate(1)
        every { templates.findDraftDetail(workspaceId, "test/t.sql") } returns draft(1)
        every { usage.everPins(workspaceId, "test/t.sql") } returns pins(sets = listOf(setPin()))

        val error = shouldThrow<DatapipelinesException> { service.purge(workspaceId, "test/t.sql", "draft-hash-1") }

        error.code shouldBe PipelineErrorCodes.Template.IN_USE
        error.details["referencing_parameter_sets"] shouldBe listOf("acme/sales/pins_only")
        verify(exactly = 0) { templates.purgeDraft(any(), any(), any(), any()) }
        verify(exactly = 0) { usage.liveVersionPins(any(), any(), any()) }
    }

    @Test
    fun `a draft purge that leaves other versions asks the exact-pin question of the DRAFT's version - a visualization refuses it`() {
        stubTemplate(1, 2)
        every { templates.findDraftDetail(workspaceId, "test/t.sql") } returns draft(2)
        every { usage.liveVersionPins(workspaceId, "test/t.sql", 2) } returns pins(visualizations = listOf(vizPin()))

        val error = shouldThrow<DatapipelinesException> { service.purge(workspaceId, "test/t.sql", "draft-hash-2") }

        error.code shouldBe PipelineErrorCodes.Template.IN_USE
        error.details["referencing_visualizations"] shouldBe listOf("acme/charts/revenue")
        verify(exactly = 0) { templates.purgeDraft(any(), any(), any(), any()) }
        verify(exactly = 0) { usage.everPins(any(), any()) }
    }

    @Test
    fun `an unpinned draft purges - through the same statement it always did`() {
        stubTemplate(1)
        every { templates.findDraftDetail(workspaceId, "test/t.sql") } returns draft(1)
        every { usage.everPins(workspaceId, "test/t.sql") } returns pins()
        every { templates.purgeDraft(workspaceId, "test/t.sql", "draft-hash-1", any()) } returns true

        service.purge(workspaceId, "test/t.sql", "draft-hash-1")

        verify(exactly = 1) { templates.purgeDraft(workspaceId, "test/t.sql", "draft-hash-1", any()) }
    }

    @Test
    fun `purgeVersion guards the draft too - the entity rule when it is the only version, the exact pin otherwise`() {
        stubTemplate(1)
        every { templates.findVersionDetail(workspaceId, "test/t.sql", 1) } returns draft(1)
        every { usage.everPins(workspaceId, "test/t.sql") } returns pins(sets = listOf(setPin()))
        shouldThrow<DatapipelinesException> { service.purgeVersion(workspaceId, "test/t.sql", 1) }.code shouldBe
            PipelineErrorCodes.Template.IN_USE

        stubTemplate(1, 2)
        every { templates.findVersionDetail(workspaceId, "test/t.sql", 2) } returns draft(2)
        every { usage.liveVersionPins(workspaceId, "test/t.sql", 2) } returns pins(listOf(pipelinePin()))
        shouldThrow<DatapipelinesException> { service.purgeVersion(workspaceId, "test/t.sql", 2) }.code shouldBe
            PipelineErrorCodes.Template.IN_USE

        verify(exactly = 0) { templates.purgeDraft(any(), any(), any(), any()) }
    }

    @Test
    fun `discard of a released version refuses on a visualization pin - the statement is never reached`() {
        stubTemplate(1)
        every { templates.findVersionDetail(workspaceId, "test/t.sql", 1) } returns draft(1).copy(status = PipelineVersionStatus.RELEASED)
        every { usage.liveVersionPins(workspaceId, "test/t.sql", 1) } returns pins(visualizations = listOf(vizPin()))

        val error = shouldThrow<DatapipelinesException> { service.discardVersion(workspaceId, "test/t.sql", 1, actor) }

        error.code shouldBe PipelineErrorCodes.Template.IN_USE
        error.details["referencing_visualizations"] shouldBe listOf("acme/charts/revenue")
        verify(exactly = 0) { templates.discardVersion(any(), any(), any(), any(), any()) }
    }

    @Test
    @Suppress("LongMethod") // the fixture is the point: an exhausted pool, a real validator, a stored transform draft
    fun `release re-runs the suite on a transform draft — a pool refusing at release fails the release`() {
        // 7b §8.1: release re-runs the version's test suite. A suite that passed at save fails
        // at release only if the engine changed — here the evaluation pool is exhausted, so the
        // release is refused with template.test_failed instead of flipping the draft.
        val pool =
            co.datapipelines.scripting.ScriptEvaluationPool(
                size = 1,
                queue = 1,
                abandonGrace = java.time.Duration.ofMillis(200),
                clock = co.datapipelines.scripting.ScriptEvaluationPool.SYSTEM,
            )
        val blocker = java.util.concurrent.CountDownLatch(1)
        Thread {
            pool.run(
                co.datapipelines.scripting.EvaluationLimits(
                    wallClock = java.time.Duration.ofSeconds(30),
                    maxDepth = 100,
                ),
                "blocker",
            ) { blocker.await() }
        }.start()
        // Let the blocker take the one slot before the release asks for it.
        Thread.sleep(100)

        val runner =
            co.datapipelines.templates.TransformTestRunner(
                engines = mapOf(co.datapipelines.scripting.ScriptLanguage.JSONATA to co.datapipelines.scripting.JsonataEngine()),
                pool = pool,
                evaluateTimeout = java.time.Duration.ofSeconds(5),
                suiteTimeout = java.time.Duration.ofSeconds(30),
            )
        val emptyRegistry =
            object : co.datapipelines.templates.TemplateRegistry {
                override fun lookup(
                    id: String,
                    version: Int,
                ): co.datapipelines.templates.TemplateVersion? = null

                override fun existsId(id: String): Boolean = false
            }
        val realValidator =
            co.datapipelines.templates.TemplateValidator(
                co.datapipelines.templates.LibraryResolver { _ -> emptyRegistry },
                suiteRunner = runner,
            )
        val releaseService =
            TemplateReleaseService(
                templates,
                realValidator,
                AuthoringGuard(true),
                usage,
            )

        val contract =
            co.datapipelines.templates.TransformContract(
                mode = co.datapipelines.templates.TransformMode.ROW,
                inputs =
                    mapOf(
                        "orders" to
                            co.datapipelines.templates.TransformInput.Table(
                                listOf(
                                    co.datapipelines.templates.ContractColumn("order_id", co.datapipelines.typesystem.LogicalType.INTEGER),
                                ),
                            ),
                    ),
                output =
                    co.datapipelines.templates.TransformOutput.Table(
                        listOf(co.datapipelines.templates.ContractColumn("order_id", co.datapipelines.typesystem.LogicalType.INTEGER)),
                    ),
            )
        val case =
            co.datapipelines.templates.TransformTestCase(
                name = "empty",
                input = co.datapipelines.templates.TransformTestInput(rows = emptyList(), inputs = emptyMap()),
                expect =
                    co.datapipelines.templates.TransformTestExpect(
                        output =
                            co.datapipelines.templates.TransformBlocks.mapper
                                .readTree("""{"rows": []}"""),
                    ),
            )
        val transformStored =
            Template(
                id = "test/t.sql",
                version = 1,
                engine = Template.NONE_ENGINE,
                type = co.datapipelines.pipeline.TemplateType.JSONATA,
                dialect = null,
                displayName = "T",
                description = "d",
                body = "rows",
                createdAt = Instant.EPOCH,
                createdBy = actor,
                contract = contract,
                invariants = emptyList(),
                tests = listOf(case),
            )
        every { templates.findDraftDetail(workspaceId, "test/t.sql") } returns draft(1)
        every { templates.findVersion(workspaceId, "test/t.sql", 1) } returns transformStored

        try {
            val thrown =
                shouldThrow<co.datapipelines.templates.TemplateValidationException> {
                    releaseService.release(workspaceId, "test/t.sql", "draft-hash-1", actor)
                }
            thrown.result.failures.map { it.code } shouldBe listOf(PipelineErrorCodes.Template.TEST_FAILED)
            verify(exactly = 0) { templates.releaseDraft(any(), any(), any(), any()) }
        } finally {
            blocker.countDown()
        }
    }

    @Test
    fun `releasePinned releases the pinned version at the draft's own hash, through the ordinary release`() {
        every { templates.findDraftDetail(workspaceId, "test/t.sql") } returns draft(2)
        every { templates.findVersion(workspaceId, "test/t.sql", 2) } returns stored(2)
        every { validator.validateOrThrow(any(), workspaceId) } answers { firstArg() }
        every { templates.releaseDraft(workspaceId, "test/t.sql", "draft-hash-2", actor) } returns
            draft(2).copy(status = PipelineVersionStatus.RELEASED)

        val released = service.releasePinned(workspaceId, "test/t.sql", 2, actor)

        released.detail.version shouldBe 2
        released.detail.status shouldBe PipelineVersionStatus.RELEASED
        // The flip carried the DRAFT's hash — the pin names the version, the hash follows.
        verify(exactly = 1) { templates.releaseDraft(workspaceId, "test/t.sql", "draft-hash-2", actor) }
        // And the save-time validation re-ran, exactly as a direct release does.
        verify(exactly = 1) { validator.validateOrThrow(any(), workspaceId) }
    }

    @Test
    fun `releasePinned refuses not_draft when the template holds no draft`() {
        every { templates.findDraftDetail(workspaceId, "test/t.sql") } returns null

        val error = shouldThrow<DatapipelinesException> { service.releasePinned(workspaceId, "test/t.sql", 2, actor) }

        error.code shouldBe PipelineErrorCodes.Template.VERSION_NOT_DRAFT
        verify(exactly = 0) { templates.releaseDraft(any(), any(), any(), any()) }
    }

    @Test
    fun `releasePinned refuses conflict when the draft is not the pinned version - never a newer one`() {
        every { templates.findDraftDetail(workspaceId, "test/t.sql") } returns draft(3)

        val error = shouldThrow<DatapipelinesException> { service.releasePinned(workspaceId, "test/t.sql", 2, actor) }

        error.code shouldBe PipelineErrorCodes.Template.VERSION_CONFLICT
        error.details["pinned_version"] shouldBe 2
        error.details["draft_version"] shouldBe 3
        verify(exactly = 0) { templates.releaseDraft(any(), any(), any(), any()) }
    }
}
