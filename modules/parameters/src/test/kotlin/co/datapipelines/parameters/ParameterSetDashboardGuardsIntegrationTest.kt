package co.datapipelines.parameters

import co.datapipelines.parameters.ParametersTestDb.AUTHOR
import co.datapipelines.parameters.ParametersTestDb.WORKSPACE
import co.datapipelines.pipeline.DashboardPin
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #320 — the four set verbs that remove a version, against the real V39 schema and the real service: a dashboard that
 * pins the set is a consumer, and the verb is refused `parameter.in_use` BEFORE it writes — naming the dashboards, and
 * asking the right graph-rule question (rule 1's exact pin for a discard or a draft that leaves other versions; rule 3's
 * any-version scan for an entity purge or a draft purge that takes the entity). [ParametersHarness.consumerQuestions]
 * records the question, so a verb asking the wrong rule is red here, not at review.
 */
class ParameterSetDashboardGuardsIntegrationTest {
    private lateinit var h: ParametersHarness
    private val name = "acme/sales/region_filters"
    private val pin = DashboardPin("acme/boards/revenue", 4, PipelineVersionStatus.RELEASED)

    @BeforeEach
    fun reset() {
        ParametersTestDb.reset()
        h = ParametersHarness()
    }

    private fun document() = h.document(ParameterSetFixtures.setJson(ParameterSetFixtures.countryJson(), name = name))

    private fun edited() =
        h.document(ParameterSetFixtures.setJson(ParameterSetFixtures.countryJson(), ParameterSetFixtures.amountJson(), name = name))

    /** v1 RELEASED. */
    private fun released(): ParameterSetVersion {
        val created = h.create(document())
        return h.service.release(WORKSPACE, created.record.id, created.detail.bodyHash, AUTHOR).version
    }

    /** v1 RELEASED and a v2 DRAFT beside it. */
    private fun releasedWithDraft(): Pair<ParameterSetVersion, ParameterSetVersion> {
        val v1 = released()
        return v1 to h.service.write(WORKSPACE, v1.record.id, edited(), v1.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
    }

    private fun refused(block: () -> Unit): DatapipelinesException = shouldThrow<DatapipelinesException>(block)

    private fun DatapipelinesException.assertPinnedBy(
        id: java.util.UUID,
        version: Int?,
    ) {
        code shouldBe ParameterErrorCodes.IN_USE
        details["id"] shouldBe id.toString()
        details["version"] shouldBe version
        details["pinned_by"] shouldBe listOf(mapOf("dashboard" to "acme/boards/revenue", "version" to 4, "status" to "RELEASED"))
        (message ?: "") shouldBe
            (if (version == null) "Parameter set '$name'" else "Version $version of parameter set '$name'") +
            " is pinned by 1 dashboard version(s): acme/boards/revenue; discard or repoint them first."
    }

    @Test
    fun `discard of a released version a dashboard pins is refused - exact pin, nothing written`() {
        val v1 = released()
        h.pinnedByDashboards += pin

        refused { h.service.discardVersion(WORKSPACE, v1.record.id, 1, AUTHOR) }.assertPinnedBy(v1.record.id, 1)

        h.consumerQuestions shouldBe listOf("live $name@1")
        h.repository.findVersionDetail(WORKSPACE, v1.record.id, 1)!!.status shouldBe PipelineVersionStatus.RELEASED
    }

    @Test
    fun `discard of an unpinned version still discards - the guard refuses only what a dashboard holds`() {
        val v1 = released()

        h.service
            .discardVersion(WORKSPACE, v1.record.id, 1, AUTHOR)
            .detail.status shouldBe PipelineVersionStatus.DISCARDED

        h.consumerQuestions shouldBe listOf("live $name@1")
    }

    @Test
    fun `a draft purge beside a release asks the exact pin of the DRAFT's version - and a DRAFT dashboard's pin refuses it`() {
        val (v1, v2) = releasedWithDraft()
        h.pinnedByDashboards += pin.copy(status = PipelineVersionStatus.DRAFT)

        val error = refused { h.service.purgeDraft(WORKSPACE, v1.record.id, v2.detail.bodyHash) }

        error.code shouldBe ParameterErrorCodes.IN_USE
        error.details["version"] shouldBe 2
        h.consumerQuestions shouldBe listOf("live $name@2")
        h.repository.findVersionDetail(WORKSPACE, v1.record.id, 2)!!.status shouldBe PipelineVersionStatus.DRAFT
    }

    @Test
    fun `purgeVersion asks the same - the exact pin of a draft that leaves other versions`() {
        val (v1, _) = releasedWithDraft()
        h.pinnedByDashboards += pin

        refused { h.service.purgeVersion(WORKSPACE, v1.record.id, 2) }.assertPinnedBy(v1.record.id, 2)

        h.consumerQuestions shouldBe listOf("live $name@2")
        h.repository.findVersionDetail(WORKSPACE, v1.record.id, 2) shouldNotBe null
    }

    @Test
    fun `a draft purge that takes the entity is an ENTITY purge - the any-version question, DISCARDED dashboards included`() {
        val created = h.create(document())
        h.pinnedByDashboards += pin.copy(status = PipelineVersionStatus.DISCARDED)

        val error = refused { h.service.purgeDraft(WORKSPACE, created.record.id, created.detail.bodyHash) }

        error.code shouldBe ParameterErrorCodes.IN_USE
        // The entity form carries no single version: the whole set goes.
        error.details.containsKey("version") shouldBe false
        h.consumerQuestions shouldBe listOf("any $name")
        h.repository.findRecord(WORKSPACE, created.record.id) shouldNotBe null
    }

    @Test
    fun `purgeEntity of a draft-only set a dashboard pins is refused - and an unpinned one goes`() {
        val pinned = h.create(document())
        h.pinnedByDashboards += pin

        refused { h.service.purgeEntity(WORKSPACE, pinned.record.id) }.assertPinnedBy(pinned.record.id, null)
        h.consumerQuestions shouldBe listOf("any $name")
        h.repository.findRecord(WORKSPACE, pinned.record.id) shouldNotBe null

        h.pinnedByDashboards.clear()
        h.service.purgeEntity(WORKSPACE, pinned.record.id)
        h.repository.findRecord(WORKSPACE, pinned.record.id) shouldBe null
    }

    @Test
    fun `an unknown set is not_found before any question is asked`() {
        val ghost = java.util.UUID.randomUUID()

        refused { h.service.purgeEntity(WORKSPACE, ghost) }.code shouldBe ParameterErrorCodes.NOT_FOUND

        h.consumerQuestions shouldBe emptyList()
    }
}
