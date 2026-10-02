package co.datapipelines.web.parameters.stream

import co.datapipelines.parameters.EvaluateResponse
import co.datapipelines.parameters.EvaluateResponseJson
import co.datapipelines.parameters.EvaluationOutcome
import co.datapipelines.parameters.OrgEcho
import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterEvaluationEvent
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.web.api.ApiExceptionHandler
import co.datapipelines.web.sse.SseJson
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * The engine's events as frames (spec §4.2's FROZEN table): the exact key SET per event — so an extra key cannot pass
 * (the redaction rule as a test: nothing outside the table, ever) — `evaluation_id` stamped on every frame (D1), the
 * completed response verbatim from its one writer, the code-only failure, and `ABORTED` as no frame at all.
 */
class ParameterEvaluationEventProjectionTest {
    private val evaluationId = UUID.randomUUID()
    private val setId = UUID.randomUUID()
    private val response =
        EvaluateResponse(
            id = setId,
            name = "acme/sales/region_filters",
            version = 4,
            org = OrgEcho("$", "Dollar"),
            parameters = emptyList(),
        )

    private fun frame(event: ParameterEvaluationEvent) = ParameterEvaluationEventProjection.frame(event, evaluationId, setId, 4)

    private val everyEvent: List<Pair<ParameterEvaluationEvent, Set<String>>> =
        listOf(
            ParameterEvaluationEvent.Started(listOf("country", "state"), Instant.parse("2026-10-02T00:00:30Z")) to
                setOf("evaluation_id", "parameter_set_id", "version", "deadline_at"),
            ParameterEvaluationEvent.ParameterWaiting("state", listOf("country")) to setOf("evaluation_id", "parameter", "waiting_on"),
            ParameterEvaluationEvent.ParameterAdmitted("state") to setOf("evaluation_id", "parameter"),
            ParameterEvaluationEvent.ParameterRunning("state", "warehouse", TemplateRef("acme/sales/state.sql", 3)) to
                setOf("evaluation_id", "parameter", "datasource", "template"),
            ParameterEvaluationEvent.ParameterResolved("state", "first", reset = true, rows = 2) to
                setOf("evaluation_id", "parameter", "origin", "reset", "rows"),
            ParameterEvaluationEvent.ParameterResolved("country", "default", reset = false, rows = null) to
                setOf("evaluation_id", "parameter", "origin", "reset"),
            ParameterEvaluationEvent.ParameterFailed("city", "datasource.not_found", "no_row") to
                setOf("evaluation_id", "parameter", "code", "detail"),
            ParameterEvaluationEvent.ParameterFailed("city", ParameterErrorCodes.EVALUATE_SELECTORS_SATURATED, null) to
                setOf("evaluation_id", "parameter", "code"),
            ParameterEvaluationEvent.Ended(EvaluationOutcome.COMPLETED, null, response) to setOf("evaluation_id", "response"),
            ParameterEvaluationEvent.Ended(EvaluationOutcome.TIMEOUT, ParameterErrorCodes.EVALUATE_TIMEOUT, null) to
                setOf("evaluation_id", "code"),
        )

    @Test
    fun `every frame carries exactly its table keys and the request's evaluation id`() {
        everyEvent.forEach { (event, keys) ->
            val frame = frame(event).shouldNotBeNull()
            withClue("${frame.name}'s keys") { frame.data.keys shouldBe keys }
            withClue("${frame.name} stamps the request's id (D1)") { frame.data["evaluation_id"] shouldBe evaluationId.toString() }
            withClue("${frame.name} is a §4.2 frame") { (frame.name in TABLE.keys) shouldBe true }
            withClue(
                "${frame.name} carries nothing outside the table",
            ) { (frame.data.keys - TABLE.getValue(frame.name)) shouldBe emptySet() }
        }
    }

    @Test
    fun `the frame names follow the event kinds - one name per kind, the table's names`() {
        everyEvent.map { (event, _) -> frame(event).shouldNotBeNull().name }.distinct() shouldBe
            listOf(
                "evaluation_started",
                "parameter_waiting",
                "parameter_admitted",
                "parameter_running",
                "parameter_resolved",
                "parameter_failed",
                "evaluation_completed",
                "evaluation_failed",
            )
    }

    @Test
    fun `evaluation_started names the set and the version the caller asked for and the deadline as an instant`() {
        val data = frame(ParameterEvaluationEvent.Started(listOf("a"), Instant.parse("2026-10-02T00:00:30Z"))).shouldNotBeNull().data

        data["parameter_set_id"] shouldBe setId.toString()
        data["version"] shouldBe 4
        data["deadline_at"] shouldBe "2026-10-02T00:00:30Z"
    }

    @Test
    fun `parameter_running carries the template pin as id and version`() {
        val data =
            frame(
                ParameterEvaluationEvent.ParameterRunning("state", "warehouse", TemplateRef("acme/sales/state.sql", 3)),
            ).shouldNotBeNull().data

        data["template"] shouldBe mapOf("id" to "acme/sales/state.sql", "version" to 3)
        data["datasource"] shouldBe "warehouse"
    }

    @Test
    fun `evaluation_completed carries the response verbatim from its one writer`() {
        val data = frame(ParameterEvaluationEvent.Ended(EvaluationOutcome.COMPLETED, null, response)).shouldNotBeNull().data
        val written = SseJson.mapper.writeValueAsString(data)

        data["response"] shouldBe EvaluateResponseJson.write(response)
        written shouldContain String(EvaluateResponseJson.bytes(response), Charsets.UTF_8)
    }

    @Test
    fun `evaluation_failed is code-only - a timeout carries its code, a code-less failure the 500 backstop's stand-in`() {
        frame(
            ParameterEvaluationEvent.Ended(EvaluationOutcome.TIMEOUT, ParameterErrorCodes.EVALUATE_TIMEOUT, null),
        ).shouldNotBeNull().data shouldBe
            mapOf("evaluation_id" to evaluationId.toString(), "code" to ParameterErrorCodes.EVALUATE_TIMEOUT)
        frame(
            ParameterEvaluationEvent.Ended(EvaluationOutcome.FAILED, ParameterErrorCodes.EVALUATE_RESPONSE_TOO_LARGE, null),
        ).shouldNotBeNull().data shouldBe
            mapOf("evaluation_id" to evaluationId.toString(), "code" to ParameterErrorCodes.EVALUATE_RESPONSE_TOO_LARGE)
        frame(ParameterEvaluationEvent.Ended(EvaluationOutcome.FAILED, null, null)).shouldNotBeNull().data["code"] shouldBe
            ParameterEvaluationEventProjection.INTERNAL_STAND_IN_CODE
    }

    @Test
    fun `the stand-in code is the ordinary evaluate's 500 code - the two envelopes cannot disagree`() {
        val backstop =
            ApiExceptionHandler::class.java
                .getDeclaredField("INTERNAL_STAND_IN_CODE")
                .apply { isAccessible = true }
                .get(null) as String

        ParameterEvaluationEventProjection.INTERNAL_STAND_IN_CODE shouldBe backstop
    }

    @Test
    fun `an ABORTED end is no frame - the table has no aborted event and the catalogue no aborted code`() {
        frame(ParameterEvaluationEvent.Ended(EvaluationOutcome.ABORTED, null, null)).shouldBeNull()
    }

    @Test
    fun `the projection source names no value, option, SQL, bind or message field`() {
        val source = File(sourceRoot(), "ParameterEvaluationEventProjection.kt").readText()
        val code =
            source.lines().filterNot {
                it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") ||
                    it.trimStart().startsWith("//")
            }

        withClue("a hit is a field a frame must never carry (spec §13: identity and progress only)") {
            code.filter { FORBIDDEN.containsMatchIn(it) } shouldBe emptyList()
        }
    }

    private fun sourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return File(requireNotNull(dir), "modules/web/src/main/kotlin/co/datapipelines/web/parameters/stream")
    }

    private companion object {
        /** The spec §4.2 table — every frame name and the keys its `data` may carry. */
        val TABLE: Map<String, Set<String>> =
            mapOf(
                "evaluation_started" to setOf("evaluation_id", "parameter_set_id", "version", "deadline_at"),
                "parameter_waiting" to setOf("evaluation_id", "parameter", "waiting_on"),
                "parameter_admitted" to setOf("evaluation_id", "parameter"),
                "parameter_running" to setOf("evaluation_id", "parameter", "datasource", "template"),
                "parameter_resolved" to setOf("evaluation_id", "parameter", "origin", "reset", "rows"),
                "parameter_failed" to setOf("evaluation_id", "parameter", "code", "detail"),
                "evaluation_completed" to setOf("evaluation_id", "response"),
                "evaluation_failed" to setOf("evaluation_id", "code", "message"),
            )

        /** `.value`, `.options`, `sql`, `bind`, `message` — what the redaction rule forbids a frame to read. */
        val FORBIDDEN = Regex("""\.(value|options|message)\b|\bgetMessage\b|\bsql\b|\bbinds?\b""", RegexOption.IGNORE_CASE)
    }
}
