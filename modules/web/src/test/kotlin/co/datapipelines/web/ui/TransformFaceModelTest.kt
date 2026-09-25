package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateValidationFailure
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 7d — the transform face's model: the create skeleton, the lossless panes, the binding and
 * the refusal → pane mapping. The two properties that would fail SILENTLY are pinned first:
 * a skeleton the real gate refuses would make the create modal unusable for every transform,
 * and a pretty-printer that dropped a null would rewrite a test row's data on the next save.
 */
class TransformFaceModelTest {
    private val workspace = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val identity = TransformFace.Identity(TransformFixtures.NAME, "jsonata", Template.NONE_ENGINE, "Order lines", "d")

    @Test
    fun `the create skeleton - the record's example - passes 7b's real save gate, suite included`() {
        val bound = TransformFace.bind(identity, TransformFixtures.skeletonPanes)
        val draft = bound.shouldBeInstanceOf<TransformFace.Bound.Draft>().draft

        TransformFixtures
            .validator()
            .validate(draft, workspace)
            .failures
            .shouldBeEmpty()
        // …and it is the record's example: a row contract with rejects, three cases.
        draft.type shouldBe TemplateType.JSONATA
        draft.engine shouldBe Template.NONE_ENGINE
        draft.contract?.rejects shouldBe true
        draft.tests?.map { it.name } shouldBe listOf("empty input", "missing customer is rejected", "wrong shape is refused")
    }

    @Test
    fun `the panes print losslessly - a null inside a test row survives, an absent optional stays absent`() {
        val stored = TransformFixtures.storedSkeleton()
        val panes = TransformPanes.of(stored)

        // Data nulls are values: the rejected row's customer_id is null in the INPUT and in
        // the expected reject — dropping either would change what the case asserts.
        panes.tests shouldContain "\"customer_id\": null"
        // Absent optionals stay absent: no `meta`/`now` the author never wrote, no `refusal: null`
        // beside an output, no `precision: null` on an INTEGER column.
        panes.tests shouldNotContain "\"meta\""
        panes.tests shouldNotContain "\"now\""
        panes.tests shouldNotContain "\"refusal\": null"
        panes.contract shouldNotContain "\"precision\": null"

        val rebound = TransformFace.bind(identity, panes).shouldBeInstanceOf<TransformFace.Bound.Draft>().draft
        rebound.body shouldBe stored.body
        rebound.contract shouldBe stored.contract
        rebound.invariants shouldBe stored.invariants
        rebound.tests shouldBe stored.tests
    }

    @Test
    fun `an expected output that is JSON null round-trips as null, not as an absent expectation`() {
        val tests = """[ { "name": "nothing", "input": { "rows": [] }, "expect": { "output": null } } ]"""
        val panes = TransformFixtures.skeletonPanes.copy(tests = tests)
        val first = TransformFace.bind(identity, panes).shouldBeInstanceOf<TransformFace.Bound.Draft>().draft
        val printed = TransformPanes.pretty(first.tests)

        printed shouldContain "\"output\": null"
        val second = TransformFace.bind(identity, panes.copy(tests = printed)).shouldBeInstanceOf<TransformFace.Bound.Draft>().draft
        second.tests shouldBe first.tests
    }

    @Test
    fun `a pane that is not JSON, or is empty, is refused naming the pane - nothing else is bound`() {
        val bound =
            TransformFace.bind(
                identity,
                TransformFixtures.skeletonPanes.copy(contract = "{ \"mode\": ", invariants = "   "),
            )
        val refusals = bound.shouldBeInstanceOf<TransformFace.Bound.Refused>().refusals

        refusals.map { it.pane } shouldBe listOf(TransformFace.CONTRACT, TransformFace.INVARIANTS)
        refusals[0].message shouldContain "not valid JSON"
        refusals[1].message shouldContain "empty"
        refusals.map { it.code }.toSet() shouldBe setOf(PipelineErrorCodes.Template.CONTRACT_INVALID)
    }

    @Test
    fun `a typo inside a block is 7b's strict refusal, and it names the pane the typo is in`() {
        val typo = TransformSkeleton.tests.replaceFirst("\"expect\"", "\"expekt\"")
        val refusals =
            TransformFace
                .bind(identity, TransformFixtures.skeletonPanes.copy(tests = typo))
                .shouldBeInstanceOf<TransformFace.Bound.Refused>()
                .refusals

        refusals.single().code shouldBe PipelineErrorCodes.Template.CONTRACT_INVALID
        refusals.single().pane shouldBe TransformFace.TESTS
    }

    @Test
    fun `7b's failures map to the pane that owns them`() {
        fun failure(
            code: String,
            vararg details: Pair<String, Any?>,
        ) = TemplateValidationFailure(code = code, message = "m", details = mapOf(*details))

        TransformFace.paneOf(failure(PipelineErrorCodes.Template.SYNTAX_ERROR)) shouldBe TransformFace.BODY
        TransformFace.paneOf(failure(PipelineErrorCodes.Template.FREEMARKER_FORBIDDEN, "rule" to "body")) shouldBe TransformFace.BODY
        TransformFace.paneOf(failure(PipelineErrorCodes.Template.CONTRACT_INVALID, "rule" to "row_mode_inputs")) shouldBe
            TransformFace.CONTRACT
        TransformFace.paneOf(failure(PipelineErrorCodes.Template.CONTRACT_INVALID, "rule" to "empty_case_missing")) shouldBe
            TransformFace.TESTS
        TransformFace.paneOf(failure(PipelineErrorCodes.Template.CONTRACT_INVALID, "rule" to "expect_shape")) shouldBe TransformFace.TESTS
        TransformFace.paneOf(failure(PipelineErrorCodes.Template.CONTRACT_INVALID, "rule" to "row_case_lists_table")) shouldBe
            TransformFace.TESTS
        TransformFace.paneOf(
            failure(PipelineErrorCodes.Template.CONTRACT_INVALID, "rule" to "unknown_field", "path" to "TemplateDraft[\"invariants\"]->x"),
        ) shouldBe TransformFace.INVARIANTS
        TransformFace.paneOf(failure(PipelineErrorCodes.Template.INVARIANT_INVALID)) shouldBe TransformFace.INVARIANTS
        TransformFace.paneOf(failure(PipelineErrorCodes.Template.TEST_FAILED, "case" to "x")) shouldBe TransformFace.TESTS
        // A failure no pane owns names none — the face shows it against the whole draft.
        TransformFace.paneOf(failure(PipelineErrorCodes.Template.VERSION_CONFLICT)) shouldBe null
        TransformFace.paneOf(failure("transform.js.unavailable")) shouldBe null
    }

    @Test
    fun `a refusal's one-line form names pane, code, message and detail - the create modal's slot`() {
        val refusal = FaceRefusal(pane = "tests", code = "template.test_failed", message = "Test case 'x' failed.", detail = "case: x")
        refusal.line shouldBe "[tests] template.test_failed — Test case 'x' failed. (case: x)"
        FaceRefusal(pane = null, code = "c", message = "m", detail = null).line shouldBe "c — m"
    }
}
