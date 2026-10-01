package co.datapipelines.visualization

import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.DocumentFixtures.obj
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The durable session and capability lifecycle over the REAL database (the brief's §A): exact-hash
 * ownership, the single-use consumption, expiry that never erases a completed record, and the forced
 * race (MISTAKES.md: a race FORCED, never timed). The capability material appears exactly once — at
 * minting — and only its purpose-prefixed hash is ever at rest.
 */
class TestSessionServiceIntegrationTest {
    private val h = TestEvidenceHarness()

    /** The test's one artifact; tests that need several create them by name. */
    private var artifact: ArtifactVersion<VisualizationBody>? = null

    @BeforeEach
    fun begin() {
        h.reset()
        h.fixtureRows += listOf(mapOf("month_labels" to "Jan", "amounts" to 10.5))
    }

    @AfterEach
    fun end() {
        h.reset()
    }

    private fun viz(): ArtifactVersion<VisualizationBody> = artifact ?: h.createVisualization().also { artifact = it }

    private fun start(actor: UUID = TestEvidenceHarness.AUTHOR): TestSessionStarted =
        h.sessions.start(TestEvidenceHarness.WORKSPACE, viz().detail.artifactId, actor)

    private fun submit(
        session: TestSessionStarted,
        verdict: CaseVerdict = CaseVerdict.GREEN,
        actor: UUID = TestEvidenceHarness.AUTHOR,
    ): TestSessionSubmitted =
        h.sessions.submit(
            TestEvidenceHarness.WORKSPACE,
            session.visualizationId,
            session.sessionId,
            actor,
            listOf(SubmittedCase("twelve months", verdict)),
        )

    private fun editBody(
        session: TestSessionStarted,
        title: String,
    ) {
        val current = h.visualizationRepository.findDraft(TestEvidenceHarness.WORKSPACE, session.visualizationId)!!
        val document =
            ValidatorFakes.visualizationDocument(
                DocumentFixtures.visualization().also { it.obj("presentation").put("title", title) },
            )
        h.visualizations.write(
            TestEvidenceHarness.WORKSPACE,
            session.visualizationId,
            VisualizationDocument(DocumentFixtures.VISUALIZATION_NAME, document.body),
            current.bodyHash,
            TestEvidenceHarness.AUTHOR,
            WriteSurface.MCP,
        )
    }

    @Test
    fun `start pins workspace, artifact, version, hash and actor - and the preview token is hash-only at rest`() {
        val session = start()
        session.version shouldBe 1
        session.cases shouldBe listOf("twelve months")

        val row = h.runRepository.findBySession(TestEvidenceHarness.WORKSPACE, session.visualizationId, session.sessionId)!!
        row.startedBy shouldBe TestEvidenceHarness.AUTHOR
        row.bodyHash shouldBe session.bodyHash
        row.status shouldBe TestRunStatus.RUNNING
        val material =
            java.util.Base64
                .getUrlDecoder()
                .decode(session.previewToken)
        row.previewTokenHash shouldBe TestCapability.hash(TestCapability.PREVIEW_PURPOSE, material)
        row.previewTokenHash shouldNotBe session.previewToken // the raw form is nowhere at rest
    }

    @Test
    fun `start refuses an unknown artifact and a caseless body`() {
        shouldThrow<DatapipelinesException> {
            h.sessions.start(TestEvidenceHarness.WORKSPACE, UUID.randomUUID(), TestEvidenceHarness.AUTHOR)
        }.code shouldBe VisualizationErrorCodes.NOT_FOUND

        val caselessTree = DocumentFixtures.visualization()
        caselessTree.remove("tests")
        val caseless =
            h.visualizations.create(
                TestEvidenceHarness.WORKSPACE,
                VisualizationDocument("finance/visualizations/caseless", ValidatorFakes.visualizationDocument(caselessTree).body),
                TestEvidenceHarness.AUTHOR,
                WriteSurface.MCP,
            )
        shouldThrow<DatapipelinesException> {
            h.sessions.start(TestEvidenceHarness.WORKSPACE, caseless.detail.artifactId, TestEvidenceHarness.AUTHOR)
        }.let {
            it.code shouldBe VisualizationErrorCodes.TEST_CASE_INVALID
            it.details["reason"] shouldBe "no_cases"
        }
    }

    @Test
    fun `a green submit completes the run, mints the upload capability once and revokes the preview`() {
        val session = start()
        val submitted = submit(session)

        submitted.status shouldBe TestRunStatus.GREEN
        submitted.uploadToken shouldNotBe null
        // No later than the session deadline (the database rounds to microseconds; a microsecond is the slack).
        java.time.Duration
            .between(
                session.expiresAt,
                submitted.uploadExpiresAt,
            ).abs()
            .minus(java.time.Duration.ofMillis(1))
            .isNegative shouldBe
            true

        val row = h.runRepository.findBySession(TestEvidenceHarness.WORKSPACE, session.visualizationId, session.sessionId)!!
        row.completedAt shouldNotBe null
        row.previewTokenHash.shouldBeNull() // revoked by the submit
        val material =
            java.util.Base64
                .getUrlDecoder()
                .decode(submitted.uploadToken)
        row.uploadTokenHash shouldBe TestCapability.hash(TestCapability.UPLOAD_PURPOSE, material)
        row.uploadConsumedAt.shouldBeNull()
        row.mechanicalJson!!.path("ok").asBoolean() shouldBe true // the §11.3 report, stored verbatim
    }

    @Test
    fun `a red verdict completes RED with no capability - evidence of failure needs no screenshot`() {
        val session = start()
        submit(session, CaseVerdict.RED).let { submitted ->
            submitted.status shouldBe TestRunStatus.RED
            submitted.uploadToken.shouldBeNull()
        }
        h.runRepository
            .findBySession(TestEvidenceHarness.WORKSPACE, session.visualizationId, session.sessionId)!!
            .uploadTokenHash
            .shouldBeNull()
    }

    @Test
    fun `a missing verdict completes INCOMPLETE and a mechanical failure forces RED over green verdicts`() {
        val session = start()
        h.sessions
            .submit(TestEvidenceHarness.WORKSPACE, session.visualizationId, session.sessionId, TestEvidenceHarness.AUTHOR, emptyList())
            .status shouldBe TestRunStatus.INCOMPLETE

        h.fixtureRefusal = FixtureEvaluation.Refused("template.type_gate_refused", "row 0 is not a number")
        val mechanical = start()
        submit(mechanical).status shouldBe TestRunStatus.RED
        h.runRepository
            .findBySession(TestEvidenceHarness.WORKSPACE, mechanical.visualizationId, mechanical.sessionId)!!
            .mechanicalJson!!
            .path("ok")
            .asBoolean() shouldBe false
    }

    @Test
    fun `unknown and duplicate case names and over-long notes refuse and the run stays open`() {
        val session = start()

        fun refusal(
            verdicts: List<SubmittedCase>,
            environment: TestEnvironment? = null,
        ): DatapipelinesException =
            shouldThrow {
                h.sessions.submit(
                    TestEvidenceHarness.WORKSPACE,
                    session.visualizationId,
                    session.sessionId,
                    TestEvidenceHarness.AUTHOR,
                    verdicts,
                    environment,
                )
            }

        refusal(listOf(SubmittedCase("no such case", CaseVerdict.GREEN))).details["reason"] shouldBe "unknown_case"
        refusal(
            listOf(SubmittedCase("twelve months", CaseVerdict.GREEN), SubmittedCase("twelve months", CaseVerdict.RED)),
        ).details["reason"] shouldBe "duplicate_case"
        refusal(listOf(SubmittedCase("twelve months", CaseVerdict.GREEN, "x".repeat(2001)))).details["reason"] shouldBe "notes_too_long"
        // A bounded environment in the closed field set is fine — it just completes the run INCOMPLETE.
        h.sessions
            .submit(
                TestEvidenceHarness.WORKSPACE,
                session.visualizationId,
                session.sessionId,
                TestEvidenceHarness.AUTHOR,
                emptyList(),
                TestEnvironment(browser = "headless", theme = "dark"),
            ).status shouldBe TestRunStatus.INCOMPLETE
        val environment = h.runRepository.findBySession(TestEvidenceHarness.WORKSPACE, session.visualizationId, session.sessionId)!!
        environment.environmentJson!!.path("browser").asText() shouldBe "headless"
    }

    @Test
    fun `a session is its owner's - another actor's submit verifies nothing`() {
        val session = start()
        shouldThrow<DatapipelinesException> {
            submit(session, actor = TestEvidenceHarness.OTHER_AUTHOR)
        }.code shouldBe VisualizationErrorCodes.TEST_SESSION_NOT_FOUND
    }

    @Test
    fun `a completed run is never re-submitted - no replacement capability, no overwrite`() {
        val session = start()
        submit(session)
        val hash =
            h.runRepository
                .findBySession(
                    TestEvidenceHarness.WORKSPACE,
                    session.visualizationId,
                    session.sessionId,
                )!!
                .uploadTokenHash

        shouldThrow<DatapipelinesException> { submit(session) }.let {
            it.code shouldBe VisualizationErrorCodes.TEST_SESSION_EXPIRED
            it.details["reason"] shouldBe "not_running"
        }
        h.runRepository
            .findBySession(TestEvidenceHarness.WORKSPACE, session.visualizationId, session.sessionId)!!
            .uploadTokenHash shouldBe hash // the same capability, never minted anew
    }

    @Test
    fun `a content edit after start voids the session - never a silent retarget`() {
        val session = start()
        editBody(session, "Edited")
        shouldThrow<DatapipelinesException> { submit(session) }.let {
            it.code shouldBe VisualizationErrorCodes.TEST_SESSION_EXPIRED
            it.details["reason"] shouldBe "content_moved_on"
        }
    }

    @Test
    fun `expiry sweeps a due RUNNING session and revokes its preview - and never erases a completed GREEN`() {
        val greenSession = start()
        submit(greenSession)

        val dueSession = start()
        h.jdbc.jdbcTemplate.update(
            "UPDATE visualization_test_runs SET expires_at = NOW() - INTERVAL '1 minute' WHERE session_id = '${dueSession.sessionId}'",
        )
        h.sessions.run(TestEvidenceHarness.WORKSPACE, dueSession.visualizationId, dueSession.sessionId).let { view ->
            view.status shouldBe TestRunStatus.EXPIRED
            view.previewRevoked shouldBe true
        }
        shouldThrow<DatapipelinesException> { submit(dueSession) }.let {
            it.code shouldBe VisualizationErrorCodes.TEST_SESSION_EXPIRED
            it.details["reason"] shouldBe "not_running"
        }
        // The completed GREEN record for the SAME version is untouched by the sweep.
        h.runRepository
            .findBySession(TestEvidenceHarness.WORKSPACE, greenSession.visualizationId, greenSession.sessionId)!!
            .status shouldBe TestRunStatus.GREEN
    }

    @Test
    fun `a submit on a due session PERSISTS the sweep - the refusal it causes does not roll the EXPIRED write back`() {
        val session = start()
        h.jdbc.jdbcTemplate.update(
            "UPDATE visualization_test_runs SET expires_at = NOW() - INTERVAL '1 minute' WHERE session_id = '${session.sessionId}'",
        )
        shouldThrow<DatapipelinesException> { submit(session) }.let {
            it.code shouldBe VisualizationErrorCodes.TEST_SESSION_EXPIRED
            it.details["reason"] shouldBe "not_running"
        }
        // Before the 352 merge's F6 the sweep ran inside the submit's transaction and rolled back with the refusal,
        // so the row stayed RUNNING in the database until some non-throwing read swept it.
        h.jdbc
            .query(
                "SELECT status FROM visualization_test_runs WHERE session_id = :sid",
                mapOf("sid" to session.sessionId),
            ) { rs, _ -> rs.getString(1) }
            .single() shouldBe "EXPIRED"
    }

    @Test
    fun `the upload consume stamps the APP clock - the same clock its guard and the CHECK's expiry are stamped with`() {
        val session = start()
        val submitted = submit(session)
        val hash = TestCapability.hashEncoded(TestCapability.UPLOAD_PURPOSE, submitted.uploadToken!!)
        val at = submitted.completedAt.plusSeconds(1).truncatedTo(ChronoUnit.MILLIS)
        h.runRepository.consumeUploadCapability(submitted.runId, hash, at) shouldBe true
        // A DB NOW() here (the 352 merge's F3) could land on the wrong side of the app-stamped expiry near the deadline
        // and trip V44's CHECK as a 500 where the guard would have refused cleanly.
        h.jdbc
            .query(
                "SELECT upload_consumed_at FROM visualization_test_runs WHERE id = :id",
                mapOf("id" to submitted.runId),
            ) { rs, _ -> rs.getTimestamp(1).toInstant() }
            .single() shouldBe at
    }

    @Test
    fun `a run whose version hash moved on reads EXPIRED and can never qualify`() {
        val session = start()
        submit(session)
        editBody(session, "Moved")
        h.sessions.run(TestEvidenceHarness.WORKSPACE, session.visualizationId, session.sessionId).status shouldBe TestRunStatus.EXPIRED
    }

    @Test
    fun `the screenshot validates bytes, consumes the capability exactly once and follows submission`() {
        val session = start()
        val submitted = submit(session)
        val bytes = png(320, 240)

        h.sessions
            .storeScreenshot(
                TestEvidenceHarness.WORKSPACE,
                session.visualizationId,
                session.sessionId,
                submitted.uploadToken!!,
                "image/png",
                bytes,
                "twelve months",
            ).let { stored ->
                stored.mediaType shouldBe "image/png"
                stored.width shouldBe 320
                stored.height shouldBe 240
                stored.sha256 shouldBe ScreenshotImages.sha256(bytes)
            }
        h.runRepository
            .findBySession(TestEvidenceHarness.WORKSPACE, session.visualizationId, session.sessionId)!!
            .uploadConsumedAt shouldNotBe null

        // Replay of the SAME capability: indistinguishable from absent.
        shouldThrow<DatapipelinesException> {
            h.sessions.storeScreenshot(
                TestEvidenceHarness.WORKSPACE,
                session.visualizationId,
                session.sessionId,
                submitted.uploadToken,
                "image/png",
                bytes,
            )
        }.code shouldBe VisualizationErrorCodes.TEST_SESSION_NOT_FOUND

        // Another run's capability verifies nothing here.
        val other = start()
        val otherSubmitted = submit(other)
        shouldThrow<DatapipelinesException> {
            h.sessions.storeScreenshot(
                TestEvidenceHarness.WORKSPACE,
                session.visualizationId,
                session.sessionId,
                otherSubmitted.uploadToken!!,
                "image/png",
                bytes,
            )
        }.code shouldBe VisualizationErrorCodes.TEST_SESSION_NOT_FOUND

        // A depicted case outside the run's inventory is refused.
        val third = start()
        val thirdSubmitted = submit(third)
        shouldThrow<DatapipelinesException> {
            h.sessions.storeScreenshot(
                TestEvidenceHarness.WORKSPACE,
                third.visualizationId,
                third.sessionId,
                thirdSubmitted.uploadToken!!,
                "image/png",
                png(),
                "no such case",
            )
        }.details["reason"] shouldBe "case_unknown"
    }

    @Test
    fun `malformed and oversized screenshots store and consume nothing`() {
        val session = start()
        val submitted = submit(session)

        shouldThrow<DatapipelinesException> {
            h.sessions.storeScreenshot(
                TestEvidenceHarness.WORKSPACE,
                session.visualizationId,
                session.sessionId,
                submitted.uploadToken!!,
                "image/png",
                ByteArray(0),
            )
        }
        shouldThrow<DatapipelinesException> {
            h.sessions.storeScreenshot(
                TestEvidenceHarness.WORKSPACE,
                session.visualizationId,
                session.sessionId,
                submitted.uploadToken!!,
                "image/png",
                ByteArray(VisualizationTestSessionService.MAX_SCREENSHOT_BYTES + 1),
            )
        }.code shouldBe VisualizationErrorCodes.TEST_SCREENSHOT_TOO_LARGE
        shouldThrow<DatapipelinesException> {
            h.sessions.storeScreenshot(
                TestEvidenceHarness.WORKSPACE,
                session.visualizationId,
                session.sessionId,
                submitted.uploadToken!!,
                "image/png",
                "junk".toByteArray(),
            )
        }.code shouldBe VisualizationErrorCodes.TEST_SCREENSHOT_INVALID

        // Nothing consumed, nothing stored: a fresh, valid upload still wins.
        h.sessions.storeScreenshot(
            TestEvidenceHarness.WORKSPACE,
            session.visualizationId,
            session.sessionId,
            submitted.uploadToken!!,
            "image/png",
            png(),
        )
        h.runRepository.screenshotOf(session.runId) shouldNotBe null
    }

    @Test
    fun `two competing valid uploads have exactly one winner - the consume is the serialization point`() {
        val session = start()
        val submitted = submit(session)
        val bytes = png()

        // The first consumer's UPDATE runs on a raw connection and HOLDS the run row's lock.
        val outcome =
            ForcedRace.holdingThenCommitting(
                hold = { connection ->
                    connection
                        .prepareStatement(
                            "UPDATE visualization_test_runs SET upload_consumed_at = NOW()" +
                                " WHERE id = ? AND upload_token_hash = ? AND upload_consumed_at IS NULL",
                        ).use { statement ->
                            statement.setObject(1, session.runId)
                            statement.setString(2, TestCapability.hash(TestCapability.UPLOAD_PURPOSE, base64(submitted.uploadToken!!)))
                            statement.executeUpdate() shouldBe 1
                        }
                },
                contender = {
                    h.sessions.storeScreenshot(
                        TestEvidenceHarness.WORKSPACE,
                        session.visualizationId,
                        session.sessionId,
                        submitted.uploadToken!!,
                        "image/png",
                        bytes,
                    )
                    "stored"
                },
            )
        // The contender lost the consume (0 rows past the commit) and refused.
        outcome.shouldBeFailure<DatapipelinesException>().let {
            it.code shouldBe VisualizationErrorCodes.TEST_SESSION_EXPIRED
            it.details["reason"] shouldBe "capability_expired"
        }
        // The forced consumer consumed but never inserted: exactly ONE upload can ever win.
        h.runRepository.screenshotOf(session.runId).shouldBeNull()
    }

    @Test
    fun `the draft's superseded screenshot is deleted on a newer store - a released version's evidence survives`() {
        val session = start()
        val submitted = submit(session)
        h.sessions.storeScreenshot(
            TestEvidenceHarness.WORKSPACE,
            session.visualizationId,
            session.sessionId,
            submitted.uploadToken!!,
            "image/png",
            png(11, 11),
        )
        h.runRepository.screenshotOf(session.runId) shouldNotBe null

        // A second session over the same DRAFT version supersedes the first's screenshot.
        val second = start()
        val secondSubmitted = submit(second)
        h.sessions.storeScreenshot(
            TestEvidenceHarness.WORKSPACE,
            session.visualizationId,
            second.sessionId,
            secondSubmitted.uploadToken!!,
            "image/png",
            png(22, 22),
        )
        h.runRepository.screenshotOf(session.runId).shouldBeNull()
        h.runRepository.screenshotOf(second.runId) shouldNotBe null

        // The version becomes RELEASED: its run's screenshot survives later sessions, edits and stores.
        h.jdbc.jdbcTemplate.update(
            "UPDATE visualizations SET current_version = 1 WHERE id = '${session.visualizationId}'",
        )
        h.jdbc.jdbcTemplate.update(
            "UPDATE visualization_versions SET status = 'RELEASED', released_at = NOW(), released_by = '${TestEvidenceHarness.AUTHOR}'" +
                " WHERE visualization_id = '${session.visualizationId}' AND version = 1",
        )
        val afterRelease = start()
        val afterSubmitted = submit(afterRelease)
        h.sessions.storeScreenshot(
            TestEvidenceHarness.WORKSPACE,
            session.visualizationId,
            afterRelease.sessionId,
            afterSubmitted.uploadToken!!,
            "image/png",
            png(33, 33),
        )
        // The released version's evidence is never superseded: the second run's screenshot survives the
        // later store, while the superseded DRAFT-era one stays gone.
        h.runRepository.screenshotOf(afterRelease.runId) shouldNotBe null
        h.runRepository.screenshotOf(second.runId) shouldNotBe null
        h.runRepository.screenshotOf(session.runId).shouldBeNull()
    }

    @Test
    fun `capability expiry follows the session deadline - a past-deadline capability is refused`() {
        val session = start()
        val submitted = submit(session)
        h.jdbc.jdbcTemplate.update(
            "UPDATE visualization_test_runs SET upload_expires_at = NOW() - INTERVAL '1 minute' WHERE session_id = '${session.sessionId}'",
        )
        shouldThrow<DatapipelinesException> {
            h.sessions.storeScreenshot(
                TestEvidenceHarness.WORKSPACE,
                session.visualizationId,
                session.sessionId,
                submitted.uploadToken!!,
                "image/png",
                png(),
            )
        }.let {
            it.code shouldBe VisualizationErrorCodes.TEST_SESSION_EXPIRED
            it.details["reason"] shouldBe "capability_expired"
        }
        h.runRepository.screenshotOf(session.runId).shouldBeNull()
    }

    @Test
    fun `the runs list answers newest first with redacted views`() {
        val first = start()
        val second = start()
        h.sessions.runs(TestEvidenceHarness.WORKSPACE, first.visualizationId).let { views ->
            views.map { it.sessionId } shouldBe listOf(second.sessionId, first.sessionId)
            views.forEach { view ->
                view.uploadCapability.shouldBeNull() // no capability yet — presence only, never material
                view.previewRevoked shouldBe false
            }
        }
    }

    private fun base64(material: String): ByteArray =
        java.util.Base64
            .getUrlDecoder()
            .decode(material)

    /** A minimal valid PNG, for the byte-level cases. */
    private fun png(
        width: Int = 3,
        height: Int = 2,
    ): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = ByteArray(25)
        writeInt(ihdr, 0, 13)
        ihdr[4] = 'I'.code.toByte()
        ihdr[5] = 'H'.code.toByte()
        ihdr[6] = 'D'.code.toByte()
        ihdr[7] = 'R'.code.toByte()
        writeInt(ihdr, 8, width)
        writeInt(ihdr, 12, height)
        val idat = ByteArray(13)
        writeInt(idat, 0, 1)
        idat[4] = 'I'.code.toByte()
        idat[5] = 'D'.code.toByte()
        idat[6] = 'A'.code.toByte()
        idat[7] = 'T'.code.toByte()
        val iend = ByteArray(12)
        iend[4] = 'I'.code.toByte()
        iend[5] = 'E'.code.toByte()
        iend[6] = 'N'.code.toByte()
        iend[7] = 'D'.code.toByte()
        return signature + ihdr + idat + iend
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
