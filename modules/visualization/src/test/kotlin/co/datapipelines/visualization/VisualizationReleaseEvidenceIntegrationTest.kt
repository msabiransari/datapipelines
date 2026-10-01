package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.DocumentFixtures.obj
import co.datapipelines.pipeline.WriteSurface
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The production release gate (the brief's §D) over the REAL database and the REAL release path: the
 * evidence verdicts pinned missing/stale/red/incomplete/expired, a mechanical refusal after an earlier
 * GREEN, and the draft-update race FORCED — a candidate formed before the transaction cannot release on
 * evidence whose draft moved underneath it, and a refusal leaves NO partial release (version DRAFT, no
 * template cascaded).
 */
class VisualizationReleaseEvidenceIntegrationTest {
    private val h = TestEvidenceHarness()

    @BeforeEach
    fun begin() {
        h.reset()
        h.fixtureRows += listOf(mapOf("month_labels" to "Jan", "amounts" to 10.5))
    }

    @AfterEach
    fun end() {
        h.reset()
    }

    private fun evidenceFor(candidate: ReleaseCandidate): EvidenceVerdict =
        VisualizationReleaseEvidence(h.runRepository, h.mechanical).verdict(TestEvidenceHarness.WORKSPACE, candidate)

    /** The candidate for a live version detail — the body re-read so the hash and body agree. */
    private fun candidateOf(detail: ArtifactVersionDetail): ReleaseCandidate {
        val version = checkNotNull(h.visualizationRepository.findVersion(TestEvidenceHarness.WORKSPACE, detail.artifactId, detail.version))
        return ReleaseCandidate(detail.artifactId, DocumentFixtures.VISUALIZATION_NAME, detail.version, detail.bodyHash, version.body)
    }

    /** A GREEN run for the candidate's exact hash, through the real session service. */
    private fun greenRun(candidate: ReleaseCandidate): TestSessionStarted {
        val session = h.sessions.start(TestEvidenceHarness.WORKSPACE, candidate.visualizationId, TestEvidenceHarness.AUTHOR)
        h.sessions.submit(TestEvidenceHarness.WORKSPACE, candidate.visualizationId, session.sessionId, TestEvidenceHarness.AUTHOR, listOf(SubmittedCase("twelve months", CaseVerdict.GREEN)))
        return session
    }

    @Test
    fun `no run at all is tests_missing - the gate's own answer, not the factory default's`() {
        val created = h.createVisualization()
        val verdict = evidenceFor(candidateOf(created.detail))
        verdict.shouldBeRefused(VisualizationErrorCodes.RELEASE_TESTS_MISSING, "no_runs")
    }

    @Test
    fun `the latest run for other content is tests_stale - the spec judges THE LATEST run of the version`() {
        val created = h.createVisualization()
        val candidate = candidateOf(created.detail)
        greenRun(candidate)

        // The draft moves on: the version's LATEST run still holds the OLD hash — stale for the new content.
        val current = h.visualizationRepository.findDraft(TestEvidenceHarness.WORKSPACE, created.detail.artifactId)!!
        val edited = ValidatorFakes.visualizationDocument(DocumentFixtures.visualization().also { it.obj("presentation").put("title", "v2") })
        h.visualizations.write(TestEvidenceHarness.WORKSPACE, created.detail.artifactId, VisualizationDocument(DocumentFixtures.VISUALIZATION_NAME, edited.body), current.bodyHash, TestEvidenceHarness.AUTHOR, WriteSurface.MCP)

        val newDraft = h.visualizationRepository.findDraft(TestEvidenceHarness.WORKSPACE, created.detail.artifactId)!!
        evidenceFor(candidateOf(newDraft)).shouldBeRefused(VisualizationErrorCodes.RELEASE_TESTS_STALE, "run_hash_mismatch")
        // The stale refusal does NOT release: the new draft is still a draft.
        h.visualizationRepository.findDraft(TestEvidenceHarness.WORKSPACE, created.detail.artifactId) shouldNotBe null
    }

    @Test
    fun `a RED or INCOMPLETE latest run is tests_red`() {
        val created = h.createVisualization()
        val candidate = candidateOf(created.detail)

        val red = h.sessions.start(TestEvidenceHarness.WORKSPACE, created.detail.artifactId, TestEvidenceHarness.AUTHOR)
        h.sessions.submit(TestEvidenceHarness.WORKSPACE, created.detail.artifactId, red.sessionId, TestEvidenceHarness.AUTHOR, listOf(SubmittedCase("twelve months", CaseVerdict.RED)))
        evidenceFor(candidate).shouldBeRefused(VisualizationErrorCodes.RELEASE_TESTS_RED, "red")

        val incomplete = h.sessions.start(TestEvidenceHarness.WORKSPACE, created.detail.artifactId, TestEvidenceHarness.AUTHOR)
        h.sessions.submit(TestEvidenceHarness.WORKSPACE, created.detail.artifactId, incomplete.sessionId, TestEvidenceHarness.AUTHOR, emptyList())
        evidenceFor(candidate).shouldBeRefused(VisualizationErrorCodes.RELEASE_TESTS_RED, "incomplete")
    }

    @Test
    fun `an EXPIRED latest run is tests_stale`() {
        val created = h.createVisualization()
        val candidate = candidateOf(created.detail)
        val session = h.sessions.start(TestEvidenceHarness.WORKSPACE, created.detail.artifactId, TestEvidenceHarness.AUTHOR)
        h.jdbc.jdbcTemplate.update("UPDATE visualization_test_runs SET status = 'EXPIRED', preview_token_hash = NULL WHERE session_id = '${session.sessionId}'")
        evidenceFor(candidate).shouldBeRefused(VisualizationErrorCodes.RELEASE_TESTS_STALE, "run_expired")
    }

    @Test
    fun `a GREEN run for the exact hash passes - and a mechanical refusal after it fails the release now`() {
        val created = h.createVisualization()
        val candidate = candidateOf(created.detail)
        greenRun(candidate)
        evidenceFor(candidate) shouldBe EvidenceVerdict.Pass

        // The mechanics stop passing NOW (the evaluator refuses): the earlier GREEN does not carry.
        h.fixtureRefusal = FixtureEvaluation.Refused("template.type_gate_refused", "row 0 is not a number")
        evidenceFor(candidate).shouldBeRefused(VisualizationErrorCodes.RELEASE_MECHANICAL_FAILED, "mechanical")
        h.fixtureRefusal = null
        evidenceFor(candidate) shouldBe EvidenceVerdict.Pass
    }

    @Test
    fun `the real release flips on GREEN evidence - and the same release refuses with no evidence`() {
        val created = h.createVisualization()
        val candidate = candidateOf(created.detail)
        val release = { h.visualizations.release(TestEvidenceHarness.WORKSPACE, created.detail.artifactId, candidate.bodyHash, TestEvidenceHarness.AUTHOR) }

        shouldThrow<DatapipelinesException>(release).code shouldBe VisualizationErrorCodes.RELEASE_TESTS_MISSING

        greenRun(candidate)
        val released = release()
        released.version.detail.status shouldBe PipelineVersionStatus.RELEASED
        h.templatesReleased.shouldBeEmpty() // the RELEASED pin cascades nothing

        // The retained run is the release's evidence for the version's life.
        h.runRepository.latestRun(created.detail.artifactId, 1)!!.status shouldBe TestRunStatus.GREEN
    }

    @Test
    fun `the draft-update race is FORCED - a candidate formed before the transaction cannot release on moved evidence`() {
        val created = h.createVisualization()
        val candidate = candidateOf(created.detail)
        greenRun(candidate)

        // A concurrent draft write (the in-place arm) holds the version row, uncommitted.
        val outcome =
            ForcedRace.holdingThenCommitting(
                hold = { connection ->
                    connection.prepareStatement(
                        "UPDATE visualization_versions SET body_json = body_json || '{\"stale\":true}', body_hash = 'fedcba'" +
                            " WHERE visualization_id = ? AND version = 1 AND status = 'DRAFT'",
                    ).use { statement ->
                        statement.setObject(1, created.detail.artifactId)
                        statement.executeUpdate() shouldBe 1
                    }
                },
                contender = {
                    h.visualizations.release(TestEvidenceHarness.WORKSPACE, created.detail.artifactId, candidate.bodyHash, TestEvidenceHarness.AUTHOR)
                    "released"
                },
            )
        // The gate's FOR SHARE re-read sees the COMMITTED new hash: the candidate is stale.
        outcome.shouldBeFailure<DatapipelinesException>().let {
            it.code shouldBe VisualizationErrorCodes.RELEASE_TESTS_STALE
            it.details["reason"] shouldBe "draft_changed"
        }
        // NO partial release: the version is still DRAFT (the flip's hash precondition would refuse anyway),
        // and no template was cascaded on the way.
        h.visualizationRepository.findDraft(TestEvidenceHarness.WORKSPACE, created.detail.artifactId) shouldNotBe null
        h.templatesReleased.shouldBeEmpty()
        // The flip never happened: the version row's hash is the concurrent writer's.
        h.jdbc
            .query(
                "SELECT body_hash FROM visualization_versions WHERE visualization_id = :id AND version = 1",
                mapOf("id" to created.detail.artifactId),
            ) { rs, _ -> rs.getString(1) }.single() shouldBe "fedcba"
    }

    @Test
    fun `the dashboard cascade releases the pin through this same gate - without consenting the visualization's own draft templates`() {
        // The pin is a DRAFT transform: the visualization's OWN release needs the cascade consent.
        h.templateStatuses[ValidatorFakes.TRANSFORM_REF] = PipelineVersionStatus.DRAFT
        val created = h.createVisualization()
        val candidate = candidateOf(created.detail)
        greenRun(candidate)

        // The visualization's own release WITHOUT consent: dependency_not_released, no evidence spent.
        shouldThrow<DatapipelinesException> {
            h.visualizations.release(TestEvidenceHarness.WORKSPACE, created.detail.artifactId, candidate.bodyHash, TestEvidenceHarness.AUTHOR)
        }.code shouldBe VisualizationErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED

        // The dashboard's cascade releases the DRAFT visualization pin — through ITS OWN gate — but the
        // dashboard's flag does NOT reach the visualization's DRAFT template pin.
        val dashboardVersion =
            h.dashboards.create(
                TestEvidenceHarness.WORKSPACE,
                h.dashboardDocument(DocumentFixtures.VISUALIZATION_NAME, candidate.version),
                TestEvidenceHarness.AUTHOR,
                WriteSurface.MCP,
            )
        val dashboardHash = dashboardVersion.detail.bodyHash
        shouldThrow<DatapipelinesException> {
            h.dashboards.release(TestEvidenceHarness.WORKSPACE, dashboardVersion.detail.artifactId, dashboardHash, TestEvidenceHarness.AUTHOR, releasePinnedVisualizations = true)
        }.code shouldBe VisualizationErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED
        h.templatesReleased.shouldBeEmpty()
        // The visualization stayed DRAFT: the cascade's gate refused for the template, not for evidence.
        h.visualizationRepository.findDraft(TestEvidenceHarness.WORKSPACE, created.detail.artifactId) shouldNotBe null
    }

    private fun EvidenceVerdict.shouldBeRefused(
        code: String,
        reason: String,
    ): EvidenceVerdict.Refused {
        val refused = this as? EvidenceVerdict.Refused ?: error("expected a refusal, was Pass")
        refused.code shouldBe code
        refused.details["reason"] shouldBe reason
        return refused
    }
}
