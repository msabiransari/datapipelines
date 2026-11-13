package co.datapipelines.application.templates

import co.datapipelines.application.dependencies.IncomingRows
import co.datapipelines.application.dependencies.InverseArm
import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetPin
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplatePin
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseScope
import co.datapipelines.templates.TemplateUsageService
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactPin
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The combined templates reverse arrow over a scripted inverse read (the record's §8.4, #194 lane D; #877 §C): every arm
 * answers through [IncomingRows], the set arm is lensed like the pipeline arm (178b — a hidden set never leaks, a
 * narrowing view sees RELEASED rows only), the counts re-derive under a lens from the pinned rows, and each answer is
 * mapped and ORDERED by the domain scans' own functions (so the rows arrive here in inverse order, not answer order).
 */
class TemplateUsageTest {
    private val workspaceId = UUID.randomUUID()
    private val templateId = "acme/sales/states.sql"
    private val everything = LensedView.EVERYTHING
    private val onlyVisible =
        LensedView(ReadLens.Only(setOf("acme/p/visible")), ReadLens.Everything, parameterSets = ReadLens.Only(setOf("acme/s/visible")))

    /** The scripted answers, by (arm, scope, target version); every question asked is recorded. */
    private val answers = mutableMapOf<Triple<InverseArm, InverseScope, Int?>, List<InverseRow>>()
    private val asked = mutableListOf<Triple<InverseArm, InverseScope, Int?>>()
    private val incoming =
        IncomingRows { ws, arm, key, version, scope ->
            ws shouldBe workspaceId
            key shouldBe templateId
            Triple(arm, scope, version).also(asked::add).let { answers[it].orEmpty() }
        }
    private val templates = mockk<TemplateUsageService>(relaxed = true)
    private val usage = TemplateUsage(incoming, templates)

    private val visibleReleased = setPin("acme/s/visible", PipelineVersionStatus.RELEASED, pinnedVersion = 1)
    private val visibleDraft = setPin("acme/s/visible", PipelineVersionStatus.DRAFT, pinnedVersion = 2)
    private val hiddenReleased = setPin("acme/s/hidden", PipelineVersionStatus.RELEASED, pinnedVersion = 1)
    private val pipelinePin =
        TemplatePin(UUID.randomUUID(), "acme/p/visible", 3, PipelineVersionStatus.RELEASED, "n1", 1)

    private fun answer(
        arm: InverseArm,
        scope: InverseScope,
        version: Int?,
        rows: List<InverseRow>,
    ) {
        answers[Triple(arm, scope, version)] = rows
    }

    private fun pipelines(
        scope: InverseScope,
        version: Int?,
        vararg pins: TemplatePin,
    ) = answer(InverseArm.TEMPLATE_FROM_PIPELINE, scope, version, pins.map(::row))

    private fun sets(
        scope: InverseScope,
        version: Int?,
        vararg pins: ParameterSetPin,
    ) = answer(InverseArm.TEMPLATE_FROM_PARAMETER_SET, scope, version, pins.map(::row))

    private fun visualizations(
        scope: InverseScope,
        version: Int?,
        vararg pins: ArtifactPin,
    ) = answer(InverseArm.TEMPLATE_FROM_VISUALIZATION, scope, version, pins.map(::row))

    private fun vizPin(
        name: String,
        status: PipelineVersionStatus,
        pinnedVersion: Int,
        artifactId: UUID = UUID.randomUUID(),
    ) = ArtifactPin(artifactId, name, 1, status, pinnedVersion)

    private fun setPin(
        name: String,
        status: PipelineVersionStatus,
        pinnedVersion: Int,
        setId: UUID = UUID.randomUUID(),
    ) = ParameterSetPin(setId, name, "state", 1, status, pinnedVersion)

    @Test
    fun `usedBy combines the pipeline answer with the sets' working-version pins - everything under the whole view`() {
        pipelines(InverseScope.WORKING, 1, pipelinePin)
        sets(InverseScope.WORKING, 1, visibleReleased, visibleDraft, hiddenReleased)

        val combined = usage.usedBy(workspaceId, everything, templateId, 1)

        combined.templateId shouldBe templateId
        combined.version shouldBe 1
        combined.pipelineCount shouldBe 1
        combined.pipelineReferences shouldContainExactly listOf(pipelinePin)
        // set name, then parameter — the scan's order, not the rows'
        combined.parameterSetReferences shouldContainExactly listOf(hiddenReleased, visibleReleased, visibleDraft)
        combined.isEmpty() shouldBe false
    }

    @Test
    fun `usedBy refuses before any arm is read - the not-found refusal stays the service's`() {
        every { templates.requireVisibleVersion(workspaceId, ReadLens.Everything, templateId, 9) } throws
            DatapipelinesException("template.not_found", "Template has no version 9.")

        shouldThrow<DatapipelinesException> { usage.usedBy(workspaceId, everything, templateId, 9) }.code shouldBe "template.not_found"
        asked.shouldBeEmpty()
    }

    @Test
    fun `usedBy under a narrowing view drops the hidden set and the draft - a set-only pin still counts as in use`() {
        val hiddenPipeline = TemplatePin(UUID.randomUUID(), "acme/p/hidden", 1, PipelineVersionStatus.RELEASED, "n1", 1)
        val draftPipeline = TemplatePin(UUID.randomUUID(), "acme/p/visible", 4, PipelineVersionStatus.DRAFT, "n1", 1)
        pipelines(InverseScope.WORKING, 1, hiddenPipeline, draftPipeline)
        sets(InverseScope.WORKING, 1, visibleReleased, visibleDraft, hiddenReleased)

        val combined = usage.usedBy(workspaceId, onlyVisible, templateId, 1)

        combined.pipelineReferences shouldBe emptyList()
        combined.pipelineCount shouldBe 0
        combined.parameterSetReferences shouldContainExactly listOf(visibleReleased)
        combined.isEmpty() shouldBe false
    }

    @Test
    fun `referencedAnywhere is the sets' any-version evidence, lensed the same way - and the pipeline arm is re-exposed`() {
        sets(InverseScope.ANY, null, visibleReleased, visibleDraft, hiddenReleased)
        pipelines(InverseScope.ANY, null, pipelinePin)

        usage.referencedAnywhere(workspaceId, everything, templateId) shouldContainExactly
            listOf(hiddenReleased, visibleReleased, visibleDraft)
        usage.referencedAnywhere(workspaceId, onlyVisible, templateId) shouldContainExactly listOf(visibleReleased)
        usage.pipelinesReferencedAnywhere(workspaceId, everything, templateId) shouldContainExactly listOf(pipelinePin)
        usage
            .pipelinesReferencedAnywhere(workspaceId, LensedView(ReadLens.Only(setOf("nothing")), ReadLens.Only(setOf())), templateId)
            .shouldBeEmpty()
    }

    @Test
    fun `inUseCounts adds the sets' counts to the pipelines' per pinned version under the whole view`() {
        val (a, b, c) = List(3) { UUID.randomUUID() }
        pipelines(
            InverseScope.WORKING,
            null,
            TemplatePin(a, "acme/p/a", 1, PipelineVersionStatus.RELEASED, "n1", 1),
            TemplatePin(b, "acme/p/b", 1, PipelineVersionStatus.DRAFT, "n1", 1),
            TemplatePin(c, "acme/p/c", 1, PipelineVersionStatus.RELEASED, "n1", 3),
        )
        sets(
            InverseScope.WORKING,
            null,
            setPin("acme/s/one", PipelineVersionStatus.RELEASED, pinnedVersion = 1),
            *List(4) { setPin("acme/s/many$it", PipelineVersionStatus.RELEASED, pinnedVersion = 2) }.toTypedArray(),
        )

        usage.inUseCounts(workspaceId, everything, templateId) shouldBe mapOf(1 to 3, 2 to 4, 3 to 1)
    }

    @Test
    fun `inUseCounts under a narrowing view re-derives the sets' counts from the admitted RELEASED pins, one per set`() {
        val setA = UUID.randomUUID()
        pipelines(InverseScope.WORKING, null, pipelinePin)
        sets(
            InverseScope.WORKING,
            null,
            // two parameters of ONE set pin version 1 — one set, one count
            setPin("acme/s/visible", PipelineVersionStatus.RELEASED, pinnedVersion = 1, setId = setA),
            setPin("acme/s/visible", PipelineVersionStatus.RELEASED, pinnedVersion = 1, setId = setA),
            // a draft and a hidden set never count under the narrowing view
            setPin("acme/s/visible", PipelineVersionStatus.DRAFT, pinnedVersion = 2),
            setPin("acme/s/hidden", PipelineVersionStatus.RELEASED, pinnedVersion = 2),
        )

        usage.inUseCounts(workspaceId, onlyVisible, templateId) shouldBe mapOf(1 to 2)
    }

    @Test
    fun `inUseCounts under a narrowing view counts only the admitted RELEASED pipelines, one per pipeline - #340`() {
        val admitted = UUID.randomUUID()
        pipelines(
            InverseScope.WORKING,
            null,
            // two nodes of ONE admitted pipeline — one pipeline, one count
            TemplatePin(admitted, "acme/p/visible", 3, PipelineVersionStatus.RELEASED, "n1", 1),
            TemplatePin(admitted, "acme/p/visible", 3, PipelineVersionStatus.RELEASED, "n2", 1),
            // a pipeline the lens hides never counts
            TemplatePin(UUID.randomUUID(), "acme/p/hidden", 1, PipelineVersionStatus.RELEASED, "n1", 1),
            // a DRAFT working version of an ADMITTED pipeline never reaches a lensed caller
            TemplatePin(UUID.randomUUID(), "acme/p/visible", 4, PipelineVersionStatus.DRAFT, "n1", 1),
            TemplatePin(UUID.randomUUID(), "acme/p/hidden", 1, PipelineVersionStatus.RELEASED, "n1", 2),
        )

        // version 2 has only a hidden pin, so it is absent (renders as zero), not a stale 1
        usage.inUseCounts(workspaceId, onlyVisible, templateId) shouldBe mapOf(1 to 1)
    }

    @Test
    fun `inUseCounts reads ONE entity-wide working question per arm, never a per-version loop`() {
        pipelines(InverseScope.WORKING, null, pipelinePin)

        usage.inUseCounts(workspaceId, onlyVisible, templateId)

        asked shouldContainExactly
            listOf(
                Triple(InverseArm.TEMPLATE_FROM_PIPELINE, InverseScope.WORKING, null),
                Triple(InverseArm.TEMPLATE_FROM_PARAMETER_SET, InverseScope.WORKING, null),
                Triple(InverseArm.TEMPLATE_FROM_VISUALIZATION, InverseScope.WORKING, null),
            )
    }

    @Test
    fun `usedBy reports the visualizations' working-version pins beside the pipelines' and the sets' - the third arm`() {
        val draft = vizPin("acme/v/chart", PipelineVersionStatus.DRAFT, pinnedVersion = 1)
        visualizations(InverseScope.WORKING, 1, draft)

        val combined = usage.usedBy(workspaceId, everything, templateId, 1)

        combined.visualizationReferences shouldContainExactly listOf(draft)
        combined.isEmpty() shouldBe false
    }

    @Test
    fun `usedBy under a narrowing visualization lens drops the hidden visualization and the draft`() {
        val view = LensedView(ReadLens.Everything, ReadLens.Everything, visualizations = ReadLens.Only(setOf("acme/v/visible")))
        val shown = vizPin("acme/v/visible", PipelineVersionStatus.RELEASED, pinnedVersion = 1)
        visualizations(
            InverseScope.WORKING,
            1,
            shown,
            vizPin("acme/v/visible", PipelineVersionStatus.DRAFT, pinnedVersion = 1),
            vizPin("acme/v/hidden", PipelineVersionStatus.RELEASED, pinnedVersion = 1),
        )

        usage.usedBy(workspaceId, view, templateId, 1).visualizationReferences shouldContainExactly listOf(shown)
    }

    @Test
    fun `inUseCounts adds the visualizations' counts per pinned version, one per visualization`() {
        val chart = UUID.randomUUID()
        pipelines(InverseScope.WORKING, null, pipelinePin)
        visualizations(
            InverseScope.WORKING,
            null,
            vizPin("acme/v/chart", PipelineVersionStatus.RELEASED, pinnedVersion = 1, artifactId = chart),
            vizPin("acme/v/chart", PipelineVersionStatus.RELEASED, pinnedVersion = 1, artifactId = chart),
            vizPin("acme/v/other", PipelineVersionStatus.DRAFT, pinnedVersion = 2),
        )

        usage.inUseCounts(workspaceId, everything, templateId) shouldBe mapOf(1 to 2, 2 to 1)
    }

    @Test
    fun `liveVersionPins asks all four aggregates for the exact pin under LIVE - unlensed`() {
        val viz = vizPin("acme/v/chart", PipelineVersionStatus.DRAFT, pinnedVersion = 3)
        pipelines(InverseScope.LIVE, 3, pipelinePin)
        sets(InverseScope.LIVE, 3, hiddenReleased)
        visualizations(InverseScope.LIVE, 3, viz)
        answer(InverseArm.TEMPLATE_FROM_TEMPLATE, InverseScope.LIVE, 3, listOf(importRow("acme/t/report.sql", 2, alias = "lib")))

        val pins = usage.liveVersionPins(workspaceId, templateId, 3)

        pins.pipelines shouldContainExactly listOf(pipelinePin)
        pins.parameterSets shouldContainExactly listOf(hiddenReleased)
        pins.visualizations shouldContainExactly listOf(viz)
        pins.templates.map { it.importerName to it.pinnedVersion } shouldContainExactly listOf("acme/t/report.sql" to 3)
        pins.isEmpty() shouldBe false
    }

    @Test
    fun `an importer alone makes the pins non-empty - the fourth arm protects like the other three (#877 D)`() {
        answer(InverseArm.TEMPLATE_FROM_TEMPLATE, InverseScope.LIVE, 3, listOf(importRow("acme/t/report.sql", 1, alias = "lib")))

        usage.liveVersionPins(workspaceId, templateId, 3).let {
            it.pipelines.shouldBeEmpty()
            it.isEmpty() shouldBe false
        }
    }

    @Test
    fun `importedBy maps each import row to its importer pin and orders by importer name, newest version, then alias`() {
        val id = UUID.randomUUID()
        answer(
            InverseArm.TEMPLATE_FROM_TEMPLATE,
            InverseScope.ANY,
            null,
            listOf(
                importRow("acme/t/z.sql", 1, alias = null),
                importRow("acme/t/a.sql", 1, alias = "lib", importerId = id, status = PipelineVersionStatus.DISCARDED),
                importRow("acme/t/a.sql", 2, alias = "other", importerId = id),
                importRow("acme/t/a.sql", 2, alias = "lib", importerId = id),
            ),
        )

        usage.importedBy(workspaceId, templateId, null, InverseScope.ANY) shouldContainExactly
            listOf(
                TemplateUsage.TemplateImportPin(id, "acme/t/a.sql", 2, PipelineVersionStatus.DRAFT, 3, "lib"),
                TemplateUsage.TemplateImportPin(id, "acme/t/a.sql", 2, PipelineVersionStatus.DRAFT, 3, "other"),
                TemplateUsage.TemplateImportPin(id, "acme/t/a.sql", 1, PipelineVersionStatus.DISCARDED, 3, "lib"),
                TemplateUsage.TemplateImportPin(IMPORTER, "acme/t/z.sql", 1, PipelineVersionStatus.DRAFT, 3, null),
            )
        asked shouldContainExactly listOf(Triple(InverseArm.TEMPLATE_FROM_TEMPLATE, InverseScope.ANY, null))
    }

    @Test
    fun `the read answers are unchanged by the importer arm - usedBy and inUseCounts never ask it`() {
        answer(InverseArm.TEMPLATE_FROM_TEMPLATE, InverseScope.WORKING, 1, listOf(importRow("acme/t/report.sql", 1, alias = "lib")))

        usage.usedBy(workspaceId, everything, templateId, 1).isEmpty() shouldBe true
        usage.inUseCounts(workspaceId, everything, templateId) shouldBe emptyMap()
        asked.map { it.first }.distinct() shouldNotContain InverseArm.TEMPLATE_FROM_TEMPLATE
    }

    @Test
    fun `everPins asks all four aggregates for ANY version under ANY - and is empty only when every arm is`() {
        usage.everPins(workspaceId, templateId).isEmpty() shouldBe true
        asked.toSet() shouldBe
            setOf(
                Triple(InverseArm.TEMPLATE_FROM_PIPELINE, InverseScope.ANY, null),
                Triple(InverseArm.TEMPLATE_FROM_PARAMETER_SET, InverseScope.ANY, null),
                Triple(InverseArm.TEMPLATE_FROM_VISUALIZATION, InverseScope.ANY, null),
                Triple(InverseArm.TEMPLATE_FROM_TEMPLATE, InverseScope.ANY, null),
            )

        val viz = vizPin("acme/v/restorable", PipelineVersionStatus.DISCARDED, pinnedVersion = 1)
        visualizations(InverseScope.ANY, null, viz)
        usage.everPins(workspaceId, templateId).let {
            it.visualizations shouldContainExactly listOf(viz)
            it.isEmpty() shouldBe false
        }
    }

    private companion object {
        val IMPORTER: UUID = UUID.nameUUIDFromBytes("importer".toByteArray())

        /** One `imports[i]` row of the template family's importer arm: the importer pins version 3 under [alias]. */
        fun importRow(
            importer: String,
            version: Int,
            alias: String?,
            importerId: UUID = IMPORTER,
            status: PipelineVersionStatus = PipelineVersionStatus.DRAFT,
        ) = InverseRow(importerId.toString(), importer, version, status, "imports[0]", 3, alias)

        fun row(pin: TemplatePin) =
            InverseRow(
                pin.pipelineId.toString(),
                pin.pipelineName,
                pin.pipelineVersion,
                pin.versionStatus,
                "nodes[0].template",
                pin.pinnedVersion,
                pin.nodeId,
            )

        fun row(pin: ParameterSetPin) =
            InverseRow(
                pin.setId.toString(),
                pin.setName,
                pin.setVersion,
                pin.versionStatus,
                "parameters[0].source.template",
                pin.pinnedVersion,
                pin.parameter,
            )

        fun row(pin: ArtifactPin) =
            InverseRow(pin.artifactId.toString(), pin.name, pin.version, pin.status, "transform.template", pin.pinnedVersion, null)
    }
}
