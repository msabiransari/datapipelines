package co.datapipelines.visualization

import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.DocumentFixtures.obj
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #353's additive reads over the REAL database: the two capability-authenticated operations
 * ([VisualizationTestCapabilities] — the preview page's read and the session-less screenshot upload) and
 * the three service reads the routes address by RUN id (`runById`, `screenshotBytes`, `check`).
 *
 * The property under test for the capabilities is the NO-ORACLE rule: every request that does not carry
 * the right, live capability of a starter who still holds `visualization.update` gets ONE refusal — the
 * same code, message and details — so the capability tells an attacker nothing. Each refusal case below
 * is compared against the canonical one field for field; the authority seam is a mutable flag so the
 * "demoted starter" case is falsified in-test (served with the flag up, refused with it down).
 */
class VisualizationTestCapabilitiesIntegrationTest {
    private val h = TestEvidenceHarness()

    /** The starter's authority, as the web adapter would judge it; flipped per test. */
    private var starterHolds = true
    private var clock: Instant? = null

    private val capabilities =
        VisualizationTestCapabilities(
            runs = h.runRepository,
            visualizations = h.visualizationRepository,
            sessions = h.sessions,
            fixtures = { _, _, _ -> h.fixtureRefusal ?: FixtureEvaluation.Rows(h.fixtureRows.toList()) },
            authority = { _, _ -> starterHolds },
            now = { clock ?: Instant.now() },
        )

    @BeforeEach
    fun begin() {
        h.reset()
        h.fixtureRows += listOf(mapOf("month_labels" to "Jan", "amounts" to 10.5))
        starterHolds = true
        clock = null
    }

    @AfterEach
    fun end() {
        h.reset()
    }

    private fun start(): TestSessionStarted {
        val viz = h.createVisualization()
        return h.sessions.start(TestEvidenceHarness.WORKSPACE, viz.detail.artifactId, TestEvidenceHarness.AUTHOR)
    }

    private fun submitGreen(session: TestSessionStarted): TestSessionSubmitted =
        h.sessions.submit(
            TestEvidenceHarness.WORKSPACE,
            session.visualizationId,
            session.sessionId,
            TestEvidenceHarness.AUTHOR,
            listOf(SubmittedCase("twelve months", CaseVerdict.GREEN)),
        )

    /** The one refusal every capability failure must equal — code, message AND details. */
    private fun refusal(block: () -> Unit): Triple<String, String?, Map<String, Any?>> {
        val e = shouldThrow<DatapipelinesException> { block() }
        return Triple(e.code, e.message, e.details)
    }

    private val canonical =
        Triple(
            VisualizationErrorCodes.TEST_SESSION_NOT_FOUND,
            "No such test session for this visualization; its capabilities verify nothing.",
            mapOf<String, Any?>("reason" to "session_unknown"),
        )

    // ---- preview -----------------------------------------------------------------------------------

    @Test
    fun `the preview serves the run's exact version with each case evaluated as the mechanical test evaluates it`() {
        val session = start()
        val preview = capabilities.preview(session.visualizationId, session.previewToken)

        preview.runId shouldBe session.runId
        preview.sessionId shouldBe session.sessionId
        preview.visualization.detail.version shouldBe session.version
        preview.visualization.detail.bodyHash shouldBe session.bodyHash
        preview.cases.map { it.name } shouldBe session.cases
        preview.cases.single().rows shouldBe listOf(mapOf("month_labels" to "Jan", "amounts" to 10.5))
        preview.cases
            .single()
            .refusal
            .shouldBeNull()
        preview.cases
            .single()
            .fixtures.keys
            .isNotEmpty() shouldBe true
    }

    @Test
    fun `a fixture the evaluator refuses is carried as the case's refusal, never as rows`() {
        val session = start()
        h.fixtureRefusal = FixtureEvaluation.Refused("template.evaluate.input_invalid", "the fixture does not fit")
        val case = capabilities.preview(session.visualizationId, session.previewToken).cases.single()
        case.rows.shouldBeNull()
        case.refusal.shouldNotBeNull().code shouldBe "template.evaluate.input_invalid"
    }

    @Test
    fun `every preview refusal is the same answer - absent, malformed, wrong, foreign, revoked, expired, moved on, demoted`() {
        val session = start()
        val id = session.visualizationId
        val other = h.createVisualization("finance/visualizations/other")
        val wellFormedWrong = TestCapability.encode(ByteArray(TestCapability.BYTES) { 7 })

        val answers =
            mutableMapOf(
                "absent" to refusal { capabilities.preview(id, null) },
                "blank" to refusal { capabilities.preview(id, " ") },
                "malformed" to refusal { capabilities.preview(id, "!!not-base64url!!") },
                "wrong" to refusal { capabilities.preview(id, wellFormedWrong) },
                "another visualization's path" to refusal { capabilities.preview(other.detail.artifactId, session.previewToken) },
                "a garbled token" to refusal { capabilities.preview(id, session.previewToken.reversed()) },
            )
        // Expired: the clock past the deadline, the row still RUNNING (no sweep wrote) — statusAt judges it.
        clock = session.expiresAt.plusSeconds(1)
        answers["expired, unswept"] = refusal { capabilities.preview(id, session.previewToken) }
        clock = null
        // Demoted: the starter no longer holds visualization.update — falsified both ways in one test.
        starterHolds = false
        answers["starter demoted"] = refusal { capabilities.preview(id, session.previewToken) }
        starterHolds = true
        capabilities.preview(id, session.previewToken).runId shouldBe session.runId // the same token, served again

        // Moved on: an edit changes the draft's hash; the token covers THAT content only.
        editTitle(id, "Moved")
        answers["content moved on"] = refusal { capabilities.preview(id, session.previewToken) }

        answers.forEach { (case, answer) -> withClue(case) { answer shouldBe canonical } }
        answers.size shouldBe 9
    }

    @Test
    fun `a submit revokes the preview - the same token is the same refusal afterwards`() {
        val session = start()
        capabilities.preview(session.visualizationId, session.previewToken) // served while RUNNING
        submitGreen(session)
        refusal { capabilities.preview(session.visualizationId, session.previewToken) } shouldBe canonical
    }

    // ---- screenshot ----------------------------------------------------------------------------------

    @Test
    fun `the upload stores with the capability alone - the run's workspace is the row's, no principal`() {
        val session = start()
        val submitted = submitGreen(session)
        val stored =
            capabilities.storeScreenshot(session.visualizationId, session.sessionId, submitted.uploadToken, "image/png", png(), null)
        stored.runId shouldBe session.runId
        stored.mediaType shouldBe "image/png"
        stored.uploadedBy shouldBe TestEvidenceHarness.AUTHOR
    }

    @Test
    fun `every upload refusal without the right capability is the same answer - and consumes nothing`() {
        val session = start()
        val id = session.visualizationId
        val bytes = png()
        val wellFormedWrong = TestCapability.encode(ByteArray(TestCapability.BYTES) { 9 })
        val answers = mutableMapOf<String, Triple<String, String?, Map<String, Any?>>>()

        // RUNNING: the run has no upload capability yet — 352's service would say `no_capability`; the route may not.
        answers["running, no capability yet"] =
            refusal { capabilities.storeScreenshot(id, session.sessionId, wellFormedWrong, null, bytes, null) }
        val submitted = submitGreen(session)
        val token = submitted.uploadToken.shouldNotBeNull()
        answers["absent"] = refusal { capabilities.storeScreenshot(id, session.sessionId, null, null, bytes, null) }
        answers["malformed"] = refusal { capabilities.storeScreenshot(id, session.sessionId, "%%%", null, bytes, null) }
        answers["wrong"] = refusal { capabilities.storeScreenshot(id, session.sessionId, wellFormedWrong, null, bytes, null) }
        answers["the preview token"] =
            refusal { capabilities.storeScreenshot(id, session.sessionId, session.previewToken, null, bytes, null) }
        answers["unknown session"] = refusal { capabilities.storeScreenshot(id, UUID.randomUUID(), token, null, bytes, null) }
        val other = h.createVisualization("finance/visualizations/other")
        answers["another visualization's path"] =
            refusal { capabilities.storeScreenshot(other.detail.artifactId, session.sessionId, token, null, bytes, null) }
        starterHolds = false
        answers["starter demoted"] = refusal { capabilities.storeScreenshot(id, session.sessionId, token, null, bytes, null) }
        starterHolds = true

        // Nothing above consumed the capability: the legitimate upload still wins, exactly once.
        capabilities.storeScreenshot(id, session.sessionId, token, null, bytes, null).runId shouldBe session.runId
        answers["replayed"] = refusal { capabilities.storeScreenshot(id, session.sessionId, token, null, bytes, null) }

        answers.forEach { (case, answer) -> withClue(case) { answer shouldBe canonical } }
        answers.size shouldBe 9
        h.jdbc
            .query("SELECT count(*) FROM visualization_test_screenshots", emptyMap<String, Any>()) { rs, _ -> rs.getInt(1) }
            .single() shouldBe 1
    }

    // ---- the service's run-id reads --------------------------------------------------------------------

    @Test
    fun `runById answers the redacted view by run id - an unknown or foreign run is session_not_found`() {
        val session = start()
        val byId = h.sessions.runById(TestEvidenceHarness.WORKSPACE, session.visualizationId, session.runId)
        byId shouldBe h.sessions.run(TestEvidenceHarness.WORKSPACE, session.visualizationId, session.sessionId)

        shouldThrow<DatapipelinesException> {
            h.sessions.runById(TestEvidenceHarness.WORKSPACE, session.visualizationId, UUID.randomUUID())
        }.code shouldBe VisualizationErrorCodes.TEST_SESSION_NOT_FOUND
        shouldThrow<DatapipelinesException> {
            h.sessions.runById(TestEvidenceHarness.OTHER_WORKSPACE, session.visualizationId, session.runId)
        }.code shouldBe VisualizationErrorCodes.TEST_SESSION_NOT_FOUND
    }

    @Test
    fun `screenshotBytes answers the stored bytes and the detected type - none stored is no_screenshot`() {
        val session = start()
        shouldThrow<DatapipelinesException> {
            h.sessions.screenshotBytes(TestEvidenceHarness.WORKSPACE, session.visualizationId, session.runId)
        }.details["reason"] shouldBe "no_screenshot"

        val bytes = png(5, 4)
        capabilities.storeScreenshot(session.visualizationId, session.sessionId, submitGreen(session).uploadToken, null, bytes, null)
        val read = h.sessions.screenshotBytes(TestEvidenceHarness.WORKSPACE, session.visualizationId, session.runId)
        read.mediaType shouldBe "image/png"
        read.bytes.toList() shouldBe bytes.toList()

        shouldThrow<DatapipelinesException> {
            h.sessions.screenshotBytes(TestEvidenceHarness.OTHER_WORKSPACE, session.visualizationId, session.runId)
        }.code shouldBe VisualizationErrorCodes.TEST_SESSION_NOT_FOUND
    }

    @Test
    fun `check runs the mechanical test on demand and records nothing`() {
        val viz = h.createVisualization()
        val report = h.sessions.check(TestEvidenceHarness.WORKSPACE, viz.body)
        report.path("ok").asBoolean() shouldBe true
        report.path("failures_dropped").asInt() shouldBe 0
        report.path("cases").size() shouldBe 1

        h.fixtureRefusal = FixtureEvaluation.Refused("template.evaluate.input_invalid", "the fixture does not fit")
        val failing = h.sessions.check(TestEvidenceHarness.WORKSPACE, viz.body)
        failing.path("ok").asBoolean() shouldBe false
        failing.path("failures").map { it.path("code").asText() } shouldHaveSize 1
        h.jdbc
            .query("SELECT count(*) FROM visualization_test_runs", emptyMap<String, Any>()) { rs, _ -> rs.getInt(1) }
            .single() shouldBe 0
    }

    private fun editTitle(
        id: UUID,
        title: String,
    ) {
        val current = h.visualizationRepository.findDraft(TestEvidenceHarness.WORKSPACE, id).shouldNotBeNull()
        val document =
            ValidatorFakes.visualizationDocument(DocumentFixtures.visualization().also { it.obj("presentation").put("title", title) })
        h.visualizations.write(
            TestEvidenceHarness.WORKSPACE,
            id,
            VisualizationDocument(DocumentFixtures.VISUALIZATION_NAME, document.body),
            current.bodyHash,
            TestEvidenceHarness.AUTHOR,
            WriteSurface.MCP,
        )
    }

    /** A minimal valid PNG header (signature, IHDR, an empty IDAT, IEND) — the byte validator's input. */
    private fun png(
        width: Int = 3,
        height: Int = 2,
    ): ByteArray {
        fun chunk(
            type: String,
            length: Int,
        ): ByteArray =
            ByteArray(length + 12).also { writeInt(it, 0, length) }.also {
                type.forEachIndexed { i, c ->
                    it[4 + i] =
                        c.code.toByte()
                }
            }
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = chunk("IHDR", 13).also { writeInt(it, 8, width) }.also { writeInt(it, 12, height) }
        return signature + ihdr + chunk("IDAT", 1) + chunk("IEND", 0)
    }

    private fun writeInt(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
