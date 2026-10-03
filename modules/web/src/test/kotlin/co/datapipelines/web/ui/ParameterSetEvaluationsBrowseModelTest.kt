package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.EvaluationCaller
import co.datapipelines.parameters.EvaluationDetail
import co.datapipelines.parameters.EvaluationSummary
import co.datapipelines.parameters.ParameterEvaluationRepository
import co.datapipelines.parameters.ParameterEvaluationStatus
import co.datapipelines.parameters.ParameterOutcome
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.QueryAttemptOutcome
import co.datapipelines.parameters.QueryAttemptRecord
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * #376 — the History tab's model: the lens decides before any record is read (a hidden set is the workspace page's own
 * 404, word for word), a narrowing lens narrows the rows to the versions it admits, the house pager's arithmetic, and
 * every cell projected to text.
 */
class ParameterSetEvaluationsBrowseModelTest {
    private val sets = mockk<ParameterSetService>()
    private val evaluations = mockk<ParameterEvaluationRepository>()
    private val model = ParameterSetEvaluationsBrowseModel(sets, evaluations)
    private val ws = ParameterSetsUiFixtures.workspaceId
    private val id = UUID.randomUUID()
    private val record = ParameterSetsUiFixtures.record(id, currentVersion = 1)
    private val everything = LensedView(ReadLens.Everything, ReadLens.Everything)
    private val promoterLens = ReadLens.Only(setOf(record.name))
    private val promoter = LensedView(ReadLens.Everything, ReadLens.Everything, parameterSets = promoterLens)

    private fun visible(lens: ReadLens) {
        val one = ParameterSetsUiFixtures.version(record, 1, PipelineVersionStatus.RELEASED, "Geo filters")
        val draft = ParameterSetsUiFixtures.version(record, 2, PipelineVersionStatus.DRAFT, "Geo filters")
        every { sets.findWorking(ws, lens, id) } returns if (lens.isEverything) draft else one
        every { sets.listVersions(ws, lens, id) } returns (if (lens.isEverything) listOf(draft, one) else listOf(one)).map { it.detail }
    }

    private fun summary(
        status: ParameterEvaluationStatus = ParameterEvaluationStatus.COMPLETED,
        keyId: String? = null,
    ) = EvaluationSummary(
        id = UUID.randomUUID(),
        version = 1,
        caller = if (keyId == null) EvaluationCaller.PAGE else EvaluationCaller.MCP,
        principalUserId = if (keyId == null) UUID.randomUUID() else null,
        principalName = if (keyId == null) "Ada <b>" else null,
        principalKeyId = keyId,
        correlationId = null,
        status = status,
        outcomeCode = null,
        valid = if (status == ParameterEvaluationStatus.COMPLETED) false else null,
        outcomeCount = 3,
        queryCount = 2,
        startedAt = Instant.parse("2026-10-02T23:30:00.123Z"),
        finishedAt = Instant.parse("2026-10-02T23:30:00.164Z"),
    )

    @Test
    fun `a set the lens hides is the workspace page's own 404 - before any record is read`() {
        every { sets.findWorking(ws, promoterLens, id) } returns null

        val list = shouldThrow<ResponseStatusException> { model.fillHistory(ExtendedModelMap(), ws, promoter, id, 0) }
        val detail = shouldThrow<ResponseStatusException> { model.fillDetail(ExtendedModelMap(), ws, promoter, id, UUID.randomUUID()) }

        listOf(list, detail).forEach {
            it.statusCode shouldBe HttpStatus.NOT_FOUND
            it.reason shouldBe "Parameter set not found"
        }
        verify(exactly = 0) { evaluations.page(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { evaluations.find(any(), any(), any(), any()) }
    }

    @Test
    fun `the Everything lens reads every version - a narrowing lens only the versions it admits`() {
        visible(ReadLens.Everything)
        visible(promoterLens)
        every { evaluations.page(ws, id, null, 26, 0) } returns emptyList()
        every { evaluations.page(ws, id, listOf(1), 26, 0) } returns emptyList()

        model.fillHistory(ExtendedModelMap(), ws, everything, id, 0) shouldBe ParameterSetEvaluationsBrowseModel.HISTORY_VIEW
        model.fillHistory(ExtendedModelMap(), ws, promoter, id, 0)

        verify(exactly = 1) { evaluations.page(ws, id, null, 26, 0) }
        withClue("the draft v2 is not admitted by the promoter's lens, so its records are not read") {
            verify(exactly = 1) { evaluations.page(ws, id, listOf(1), 26, 0) }
        }
    }

    @Test
    fun `the pager - 25 a page, one probe row for more, never a negative offset`() {
        visible(ReadLens.Everything)
        every { evaluations.page(ws, id, null, 26, 25) } returns List(26) { summary() }
        every { evaluations.page(ws, id, null, 26, 0) } returns List(3) { summary() }

        val second = ExtendedModelMap()
        model.fillHistory(second, ws, everything, id, 25)
        val clamped = ExtendedModelMap()
        model.fillHistory(clamped, ws, everything, id, -10)

        (second["evaluations"] as List<*>).size shouldBe 25
        second["historyHasMore"] shouldBe true
        second["historyNextOffset"] shouldBe 50
        second["historyPreviousOffset"] shouldBe 0
        clamped["historyOffset"] shouldBe 0
        clamped["historyHasMore"] shouldBe false
    }

    @Test
    fun `every cell is projected to text - the principal, the tone, the took and the UTC stamp`() {
        visible(ReadLens.Everything)
        every { evaluations.page(ws, id, null, 26, 0) } returns
            listOf(
                summary(),
                summary(ParameterEvaluationStatus.TIMEOUT, keyId = "dpk_abcdefghijkl"),
                summary(ParameterEvaluationStatus.INCOMPLETE),
            )

        val m = ExtendedModelMap()
        model.fillHistory(m, ws, everything, id, 0)

        @Suppress("UNCHECKED_CAST")
        val rows = m["evaluations"] as List<ParameterSetEvaluationsBrowseModel.HistoryRow>
        rows[0].principal shouldBe "Ada <b>"
        rows[0].statusTone shouldBe "ok"
        rows[0].valid shouldBe "no"
        rows[0].took shouldBe "41 ms"
        rows[0].started shouldBe "2026-10-02 23:30:00"
        rows[1].principal shouldBe "key dpk_abcdefghijkl"
        rows[1].caller shouldBe "MCP"
        rows[1].statusTone shouldBe "bad"
        rows[1].valid shouldBe "—"
        rows[2].statusTone shouldBe "warn"
    }

    @Test
    fun `the detail shows the outcomes and the attempts to the millisecond - an unknown record is its own 404`() {
        visible(ReadLens.Everything)
        val record = summary()
        val outcomes = listOf(ParameterOutcome("state", "error", "parameter.evaluate.required_missing", "no_options"))
        every { evaluations.queries(record.id) } returns
            listOf(
                QueryAttemptRecord(
                    UUID.randomUUID(),
                    "state",
                    "warehouse",
                    "acme/state.sql",
                    2,
                    Instant.parse("2026-10-02T23:30:00.120Z"),
                    Instant.parse("2026-10-02T23:30:00.125Z"),
                    null,
                    QueryAttemptOutcome.TIMEOUT,
                    null,
                    null,
                    null,
                ),
            )
        every { evaluations.find(ws, id, any(), null) } answers
            { if (thirdArg<UUID>() == record.id) EvaluationDetail(record, outcomes) else null }

        val m = ExtendedModelMap()
        model.fillDetail(m, ws, everything, id, record.id) shouldBe ParameterSetEvaluationsBrowseModel.DETAIL_VIEW

        @Suppress("UNCHECKED_CAST")
        val query = (m["evaluationQueries"] as List<ParameterSetEvaluationsBrowseModel.QueryRow>).single()
        query.started shouldBe "23:30:00.125"
        query.ended shouldBe "—"
        query.outcome shouldBe "TIMEOUT"
        query.template shouldBe "acme/state.sql v2"
        @Suppress("UNCHECKED_CAST")
        val outcome = (m["evaluationOutcomes"] as List<ParameterSetEvaluationsBrowseModel.OutcomeRow>).single()
        (outcome.errorCode to outcome.detail) shouldBe ("parameter.evaluate.required_missing" to "no_options")
        shouldThrow<ResponseStatusException> { model.fillDetail(ExtendedModelMap(), ws, everything, id, UUID.randomUUID()) }.reason shouldBe
            "Evaluation not found"
    }
}
