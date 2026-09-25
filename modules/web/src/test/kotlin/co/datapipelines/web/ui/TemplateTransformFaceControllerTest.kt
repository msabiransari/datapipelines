package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateService
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.config.TransformProperties
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 7d (#7) — the transform face's three routes over 7b's REAL gate: the validator with its suite
 * runner, a real evaluation pool, the real JSONata engine ([TransformFixtures]). The repository
 * reads are stubbed (the rows a DB would hold); the one write — [TemplateDraftService.write] —
 * is captured, because what matters about it is its ARGUMENTS (the draft, the precondition),
 * and the call is asserted to happen.
 */
class TemplateTransformFaceControllerTest {
    private val templates = mockk<TemplateRepository>()
    private val drafts = mockk<TemplateDraftService>()
    private val controller =
        TemplateTransformFaceController(
            templates,
            TemplateService(templates),
            co.datapipelines.web.EVERYTHING_LENS,
            TransformFixtures.validator(),
            drafts,
            TransformFixtures.runner(),
            TransformProperties(),
        )

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(role: WorkspaceRole = WorkspaceRole.AUTHOR) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(userId, "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "acme", role)),
                null,
                emptyList(),
            )
    }

    /** The rows a template with a v1 RELEASED and (optionally) a v2 DRAFT holds. */
    private fun stubStored(
        draft: Template?,
        released: Template? = null,
    ) {
        val name = TransformFixtures.NAME
        every { templates.findLatest(any(), name) } returns released
        every { templates.findWorking(any(), name) } answers { draft ?: released }
        every { templates.findDraftDetail(any(), name) } returns draft?.let { detailOf(it) }
        every { templates.findVersion(any(), name, any()) } answers {
            listOfNotNull(draft, released).firstOrNull { it.version == thirdArg<Int>() }
        }
        every { templates.findVersionDetail(any(), name, any()) } answers {
            listOfNotNull(draft, released).firstOrNull { it.version == thirdArg<Int>() }?.let { detailOf(it) }
        }
    }

    private fun detailOf(t: Template) =
        TemplateVersionDetail(
            templateId = t.id,
            version = t.version,
            status = t.status,
            bodyHash = t.bodyHash,
            createdAt = t.createdAt,
            createdBy = t.createdBy,
            releasedAt = if (t.status == PipelineVersionStatus.RELEASED) Instant.parse("2026-09-25T11:00:00Z") else null,
            releasedBy = if (t.status == PipelineVersionStatus.RELEASED) userId else null,
        )

    private fun panes(template: Template = TransformFixtures.storedSkeleton()) = TransformPanes.of(template)

    // ------------------------------------------------------------------ the face (GET)

    @Test
    fun `an author's face on the working draft is editable, with the draft's hash as the save precondition`() {
        authenticate()
        val draft = TransformFixtures.storedSkeleton(version = 2, bodyHash = "draft-hash-abcdef012345")
        stubStored(draft, released = TransformFixtures.storedSkeleton(version = 1, status = PipelineVersionStatus.RELEASED))
        val model = ExtendedModelMap()

        controller.face(TransformFixtures.NAME, null, model) shouldBe TransformFace.VIEW

        model["isTransform"] shouldBe true
        model["faceEditable"] shouldBe true
        model["faceHash"] shouldBe "draft-hash-abcdef012345"
        model["faceHashShort"] shouldBe "draft-hash-a"
        model["faceLanguage"] shouldBe "JSONata"
        (model["panes"] as TransformPanes).tests shouldContain "\"missing customer is rejected\""
        model["canAuthor"] shouldBe true
    }

    @Test
    fun `a viewer's face is read-only - the same panes, no edit surface`() {
        authenticate(WorkspaceRole.VIEWER)
        stubStored(TransformFixtures.storedSkeleton(version = 2))
        val model = ExtendedModelMap()

        controller.face(TransformFixtures.NAME, null, model) shouldBe TransformFace.VIEW

        model["faceEditable"] shouldBe false
        model["readOnly"] shouldBe true
        model["canAuthor"] shouldBe false
        (model["panes"] as TransformPanes).body shouldBe TransformSkeleton.body
    }

    @Test
    fun `a RELEASED working version is read-only even for an author - Edit opens a draft, Save never creates one`() {
        authenticate()
        stubStored(draft = null, released = TransformFixtures.storedSkeleton(version = 1, status = PipelineVersionStatus.RELEASED))
        val model = ExtendedModelMap()

        controller.face(TransformFixtures.NAME, null, model)

        model["readOnly"] shouldBe false // the column rule: the working version, an author…
        model["faceEditable"] shouldBe false // …but the face writes drafts only
        model["selectedStatus"] shouldBe "RELEASED"
    }

    // ------------------------------------------------------------------ Save draft

    @Test
    fun `Save draft writes the four panes through 7b's gate under the draft's hash, then re-renders with the new hash`() {
        authenticate()
        var stored = TransformFixtures.storedSkeleton(version = 2, bodyHash = "before-hash-000000")
        stubStored(stored)
        every { templates.findWorking(any(), TransformFixtures.NAME) } answers { stored }
        every { templates.findDraftDetail(any(), TransformFixtures.NAME) } answers { detailOf(stored) }
        every { templates.findVersion(any(), TransformFixtures.NAME, 2) } answers { stored }
        val written = slot<TemplateDraft>()
        every {
            drafts.write(
                workspaceId,
                TransformFixtures.NAME,
                capture(written),
                "before-hash-000000",
                userId,
                WriteSurface.SESSION,
            )
        } answers
            {
                val draft = written.captured
                stored =
                    stored.copy(
                        body = draft.body,
                        contract = draft.contract,
                        invariants = draft.invariants,
                        tests = draft.tests,
                        bodyHash = "after-hash-111111",
                    )
                detailOf(stored)
            }
        // The author reformats the body — the same function, so the suite still holds.
        val edited = panes().copy(body = TransformSkeleton.body.replace("amount_cents / 100", "amount_cents / 100.0"))
        val model = ExtendedModelMap()
        val response = MockHttpServletResponse()

        val view =
            controller.save(
                TransformFixtures.NAME,
                "before-hash-000000",
                edited.body,
                edited.contract,
                edited.invariants,
                edited.tests,
                model,
                response,
            )

        view shouldBe TransformFace.VIEW
        response.getHeader("HX-Retarget") shouldBe null
        verify(exactly = 1) { drafts.write(workspaceId, TransformFixtures.NAME, any(), "before-hash-000000", userId, WriteSurface.SESSION) }
        written.captured.body shouldContain "100.0"
        written.captured.engine shouldBe Template.NONE_ENGINE
        written.captured.displayName shouldBe "Order lines"
        written.captured.tests.shouldNotBeNull() shouldHaveSize 3
        model["faceSaved"] shouldBe true
        model["faceHash"] shouldBe "after-hash-111111"
    }

    @Test
    fun `a Save whose suite fails is refused by 7b's gate, names the tests pane, and writes nothing`() {
        authenticate()
        stubStored(TransformFixtures.storedSkeleton(version = 2))
        // The body stops rejecting missing customers: the "missing customer" case now fails.
        val broken = panes().copy(body = """{ "rows": [], "rejects": [] }""")
        val model = ExtendedModelMap()
        val response = MockHttpServletResponse()

        val view =
            controller.save(
                TransformFixtures.NAME,
                "h",
                broken.body,
                broken.contract,
                broken.invariants,
                broken.tests,
                model,
                response,
            )

        view shouldBe TransformFace.RESULT_VIEW
        response.getHeader("HX-Retarget") shouldBe "#tf-result"
        response.getHeader("HX-Reswap") shouldBe "innerHTML"
        model["saveRefused"] shouldBe true
        @Suppress("UNCHECKED_CAST")
        val refusals = model["refusals"] as List<FaceRefusal>
        refusals.map { it.code }.toSet() shouldBe setOf(PipelineErrorCodes.Template.TEST_FAILED)
        refusals.map { it.pane }.toSet() shouldBe setOf(TransformFace.TESTS)
        refusals.first().message shouldContain "missing customer is rejected"
        verify(exactly = 0) { drafts.write(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a pane that is not JSON is refused before 7b is asked - naming the pane`() {
        authenticate()
        stubStored(TransformFixtures.storedSkeleton(version = 2))
        val model = ExtendedModelMap()
        val p = panes()

        controller.save(TransformFixtures.NAME, "h", p.body, "{ oops", p.invariants, p.tests, model, MockHttpServletResponse())

        @Suppress("UNCHECKED_CAST")
        (model["refusals"] as List<FaceRefusal>).single().pane shouldBe TransformFace.CONTRACT
        verify(exactly = 0) { drafts.write(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a stale hash is 7b's version conflict, shown against the whole draft`() {
        authenticate()
        stubStored(TransformFixtures.storedSkeleton(version = 2))
        every { drafts.write(any(), any(), any(), any(), any(), any()) } throws
            DatapipelinesException(
                PipelineErrorCodes.Template.VERSION_CONFLICT,
                "Template was modified by someone else after you loaded it.",
                mapOf("current_body_hash" to "newer"),
            )
        val model = ExtendedModelMap()
        val p = panes()

        controller.save(TransformFixtures.NAME, "stale", p.body, p.contract, p.invariants, p.tests, model, MockHttpServletResponse())

        @Suppress("UNCHECKED_CAST")
        val refusal = (model["refusals"] as List<FaceRefusal>).single()
        refusal.code shouldBe PipelineErrorCodes.Template.VERSION_CONFLICT
        refusal.pane shouldBe null
        refusal.detail shouldBe "current_body_hash: newer"
    }

    @Test
    fun `Save on a template with no draft is refused with not_draft - Edit is the way in`() {
        authenticate()
        stubStored(draft = null, released = TransformFixtures.storedSkeleton(version = 1, status = PipelineVersionStatus.RELEASED))
        val model = ExtendedModelMap()
        val p = panes()

        controller.save(TransformFixtures.NAME, "h", p.body, p.contract, p.invariants, p.tests, model, MockHttpServletResponse())

        @Suppress("UNCHECKED_CAST")
        (model["refusals"] as List<FaceRefusal>).single().code shouldBe PipelineErrorCodes.Template.VERSION_NOT_DRAFT
        verify(exactly = 0) { drafts.write(any(), any(), any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------ Run suite

    private fun runSuite(p: TransformPanes): Pair<SuiteResult?, List<FaceRefusal>> {
        val model = ExtendedModelMap()
        controller.runSuite(TransformFixtures.NAME, p.body, p.contract, p.invariants, p.tests, model) shouldBe TransformFace.RESULT_VIEW
        @Suppress("UNCHECKED_CAST")
        return (model["suite"] as SuiteResult?) to (model["refusals"] as List<FaceRefusal>)
    }

    @Test
    fun `Run suite over the unsaved panes - every case green, every invariant listed, Save would accept`() {
        authenticate()
        stubStored(TransformFixtures.storedSkeleton(version = 2))

        val (suite, refusals) = runSuite(panes())

        refusals.shouldBeEmpty()
        val result = suite.shouldNotBeNull()
        result.saveAccepted shouldBe true
        result.cases.map { it.name to it.passed } shouldBe
            listOf("empty input" to true, "missing customer is rejected" to true, "wrong shape is refused" to true)
        result.cases[1].invariants.map { it.name to it.passed } shouldBe listOf("customer_present" to true, "one_to_one" to true)
        // The refusal-expecting case passed BECAUSE the run refused with exactly that code.
        result.cases[2].actualRefusal shouldBe "pipeline.transform.input_contract_violation"
        verify(exactly = 0) { drafts.write(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unsaved edit that breaks a case shows red - the first difference's path, both sides, and Save's verdict`() {
        authenticate()
        stubStored(TransformFixtures.storedSkeleton(version = 2))
        // The author changes the reject reason in the body but not in the case's expectation.
        val edited = panes().copy(body = TransformSkeleton.body.replace("\"customer_id missing\"", "\"no customer\""))

        val result = runSuite(edited).first.shouldNotBeNull()

        result.saveAccepted shouldBe false
        result.saveCode shouldBe PipelineErrorCodes.Template.TEST_FAILED
        result.passedCount shouldBe 2
        val red = result.cases.single { !it.passed }
        red.name shouldBe "missing customer is rejected"
        red.diffPath shouldBe "$.rejects[0].reason"
        red.expected shouldBe "\"customer_id missing\""
        red.actual shouldBe "\"no customer\""
        // The invariants still hold — the output is wrong, not the guarantees.
        red.invariants.all { it.passed } shouldBe true
    }

    @Test
    fun `a false invariant is red with its name and message, even when the output matches`() {
        authenticate()
        stubStored(TransformFixtures.storedSkeleton(version = 2))
        val invariants =
            """[ { "name": "never_empty", "expr": "${'$'}count(rows) > 0", "message": "a batch always yields a row" } ]"""

        val result = runSuite(panes().copy(invariants = invariants)).first.shouldNotBeNull()

        val empty = result.cases.first { it.name == "empty input" }
        empty.passed shouldBe false
        empty.diffPath shouldBe null
        empty.invariants.single().let {
            it.name shouldBe "never_empty"
            it.passed shouldBe false
            it.message shouldBe "a batch always yields a row"
        }
    }

    @Test
    fun `a contract the gate refuses statically never runs - the refusal names the contract pane, no case list`() {
        authenticate()
        stubStored(TransformFixtures.storedSkeleton(version = 2))
        // `rejects` on a value output — 7b's static rule.
        val contract = """{ "mode": "value", "inputs": { "x": { "kind": "value", "type": "INTEGER" } },
                           "output": { "kind": "value", "type": "INTEGER" }, "rejects": true }"""

        val result = runSuite(panes().copy(contract = contract)).first.shouldNotBeNull()

        result.cases.shouldBeEmpty()
        result.saveAccepted shouldBe false
        result.refusals.map { it.pane }.toSet() shouldBe setOf(TransformFace.CONTRACT)
    }

    @Test
    fun `the suite is bounded - a case that would start past the deadline is reported as not run, never dropped`() {
        authenticate()
        stubStored(TransformFixtures.storedSkeleton(version = 2))
        // A clock that jumps past the suite budget after the gate's pass: the detail pass sees
        // the deadline behind it on its first case.
        var calls = 0L
        val suite =
            TransformSuiteRun(TransformFixtures.validator(), TransformFixtures.runner(), Duration.ofSeconds(1)) {
                if (calls++ == 0L) 0L else Duration.ofSeconds(5).toNanos()
            }
        val draft = (TransformFace.bind(TransformFixtures.storedSkeleton(), panes()) as TransformFace.Bound.Draft).draft

        val result = suite.run(workspaceId, draft)

        result.cases shouldHaveSize 3
        result.cases.all { it.notRun && !it.passed } shouldBe true
    }

    // ------------------------------------------------------------------ the routes' permissions

    @Test
    fun `each route declares its one catalog permission - read, update, evaluate (the §7_6 rows)`() {
        fun permissionOf(method: String): Permission =
            TemplateTransformFaceController::class.java.methods
                .single { it.name == method }
                .getAnnotation(RequiredScope::class.java)
                .value

        permissionOf("face") shouldBe Permission.TEMPLATE_READ
        permissionOf("save") shouldBe Permission.TEMPLATE_UPDATE
        permissionOf("runSuite") shouldBe Permission.TEMPLATE_EVALUATE
    }
}
