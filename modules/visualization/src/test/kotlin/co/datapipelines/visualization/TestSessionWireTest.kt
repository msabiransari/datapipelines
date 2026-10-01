package co.datapipelines.visualization

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The one results reader and the one wire projection REST and MCP share (#353): every wrong shape is
 * `body_invalid` naming its reason and path, the closed environment set binds field for field, the links come from
 * the configured origin (root-relative without one, never a request's), and the start / submit / screenshot answers
 * carry exactly the fields the docs promise — the upload capability only on a GREEN run.
 */
class TestSessionWireTest {
    private val mapper = ArtifactJson.mapper
    private val id = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val session = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val run = UUID.fromString("00000000-0000-4000-8000-000000000003")
    private val at = Instant.parse("2026-10-01T22:00:00Z")

    @Test
    fun `the reader binds verdicts, notes and the closed environment set`() {
        val read =
            TestSubmissionReader.read(
                mapper.readTree(
                    """{"cases": [{"name": "a", "verdict": "green", "notes": "fine"}, {"name": "b", "verdict": "red", "notes": null}],
                        "environment": {"theme": "dark", "viewport": "1280x720", "browser": "chromium", "locale": "en",
                                        "renderer_version": "plotly 4.1.1"}}""",
                ),
            )
        read.verdicts shouldBe listOf(SubmittedCase("a", CaseVerdict.GREEN, "fine"), SubmittedCase("b", CaseVerdict.RED, null))
        read.environment shouldBe TestEnvironment("dark", "1280x720", "chromium", "en", "plotly 4.1.1")
        TestSubmissionReader.read(mapper.readTree("""{"cases": [], "environment": null}""")).environment.shouldBeNull()
        TestSubmissionReader
            .read(mapper.readTree("""{"cases": [], "environment": {"theme": null}}"""))
            .environment
            ?.theme
            .shouldBeNull()
    }

    @Test
    fun `every wrong shape is body_invalid naming its reason and path`() {
        val long = "x".repeat(TestEnvironment.MAX_FIELD + 1)
        mapOf(
            """[]""" to ("not_an_object" to ""),
            """{"verdicts": []}""" to ("unknown_key" to "verdicts"),
            """{}""" to ("missing" to "cases"),
            """{"cases": null}""" to ("missing" to "cases"),
            """{"cases": {}}""" to ("wrong_type" to "cases"),
            """{"cases": ["a"]}""" to ("wrong_type" to "cases[0]"),
            """{"cases": [{"name": "a", "verdict": "green", "x": 1}]}""" to ("unknown_key" to "cases[0].x"),
            """{"cases": [{"verdict": "green"}]}""" to ("missing" to "cases[0].name"),
            """{"cases": [{"name": 3, "verdict": "green"}]}""" to ("missing" to "cases[0].name"),
            """{"cases": [{"name": "a"}]}""" to ("missing" to "cases[0].verdict"),
            """{"cases": [{"name": "a", "verdict": "amber"}]}""" to ("verdict_invalid" to "cases[0].verdict"),
            """{"cases": [{"name": "a", "verdict": "green", "notes": 3}]}""" to ("wrong_type" to "cases[0].notes"),
            """{"cases": [], "environment": []}""" to ("wrong_type" to "environment"),
            """{"cases": [], "environment": {"os": "linux"}}""" to ("environment_unknown" to "environment.os"),
            """{"cases": [], "environment": {"theme": 1}}""" to ("environment_field_invalid" to "environment.theme"),
            """{"cases": [], "environment": {"theme": "$long"}}""" to ("environment_field_invalid" to "environment.theme"),
        ).forEach { (body, expected) ->
            withClue(body.take(80)) {
                val e = shouldThrow<DatapipelinesException> { TestSubmissionReader.read(mapper.readTree(body)) }
                e.code shouldBe VisualizationErrorCodes.BODY_INVALID
                (e.details["reason"] to e.details["path"]) shouldBe expected
            }
        }
    }

    @Test
    fun `the links come from the configured origin - trimmed - and are root-relative without one`() {
        TestSessionLinks(" https://dp.example.com/ ").preview(id, "T") shouldBe
            "https://dp.example.com/visualizations/$id/preview?session=T"
        TestSessionLinks(null).upload(id, session) shouldBe "/api/v1/visualizations/$id/tests/sessions/$session/screenshot"
        TestSessionLinks("").preview(id, "T") shouldBe "/visualizations/$id/preview?session=T"
    }

    @Test
    fun `the start, submit and screenshot answers carry exactly the documented fields`() {
        val links = TestSessionLinks("https://dp.example.com")
        val started = TestSessionWire.started(TestSessionStarted(run, session, id, 2, "h", at, "P", listOf("a")), links)
        started.keys shouldBe
            setOf("session_id", "run_id", "visualization_id", "version", "body_hash", "preview_url", "expires_at", "cases")
        started["preview_url"] shouldBe "https://dp.example.com/visualizations/$id/preview?session=P"

        val green =
            TestSessionWire.submitted(
                TestSessionSubmitted(run, session, TestRunStatus.GREEN, at, mapper.createObjectNode(), "U", at),
                id,
                links,
            )
        (green["upload"] as Map<*, *>) shouldBe
            mapOf(
                "url" to "https://dp.example.com/api/v1/visualizations/$id/tests/sessions/$session/screenshot",
                "header" to "DP-Upload-Token",
                "token" to "U",
                "expires_at" to at.toString(),
            )
        val red =
            TestSessionWire.submitted(
                TestSessionSubmitted(run, session, TestRunStatus.RED, at, mapper.createObjectNode(), null, null),
                id,
                links,
            )
        red["upload"].shouldBeNull()
        red["status"] shouldBe "RED"

        val shot = TestSessionWire.screenshot(ScreenshotView(run, "image/png", "ab", 3, 2, "a", UUID.randomUUID(), at))
        shot.keys shouldBe setOf("run_id", "media_type", "sha256", "width", "height", "depicted_case", "uploaded_at")
    }
}
