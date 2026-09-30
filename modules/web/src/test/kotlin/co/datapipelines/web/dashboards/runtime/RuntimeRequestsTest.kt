package co.datapipelines.web.dashboards.runtime

import co.datapipelines.visualization.ActionScope
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The three runtime request bodies (spec §8.2–§8.4; rest-api §23.3). A body is judged WHOLE and every refusal is
 * `dashboard.validation.body_invalid` naming the field's PATH and a REASON — never the value: the test asserts that the
 * offending value (a marker no legitimate request contains) appears in NO refusal.
 */
class RuntimeRequestsTest {
    private val id = UUID.randomUUID().toString()
    private val instance = UUID.randomUUID().toString()
    private val marker = "LEAK-MARKER-424242"

    private fun refresh(
        configurationId: String = "cfg",
        instanceId: String = instance,
        refreshId: String = id,
        revision: String = "1",
        selections: String = "{}",
        scope: String = "all",
        targets: String = "[]",
    ) = """{"configuration_id":"$configurationId","instance_id":"$instanceId","refresh_id":"$refreshId","parameter_revision":$revision,""" +
        """"selections":$selections,"scope":"$scope","targets":$targets}"""

    private fun refused(block: () -> Unit): ApiException =
        shouldThrow<ApiException>(block).also {
            it.code shouldBe "dashboard.validation.body_invalid"
            it.toString() + it.details.toString() shouldNotContainText marker
        }

    private infix fun String.shouldNotContainText(text: String) = (contains(text)) shouldBe false

    @Test
    fun `a valid refresh reads whole - ids, revision, selections, scope and targets`() {
        val request = RuntimeRequests.refresh(refresh(selections = """{"year":2026}""", scope = "targets", targets = """["a","b"]"""))

        request.configurationId shouldBe "cfg"
        request.refreshId.toString() shouldBe id
        request.instanceId.toString() shouldBe instance
        request.parameterRevision shouldBe 1
        request.selections.keys shouldContainExactly listOf("year")
        request.selectionsJson shouldBe """{"year":2026}"""
        request.scope shouldBe ActionScope.TARGETS
        request.targets shouldContainExactly listOf("a", "b")
    }

    @Test
    fun `an absent selections is the empty object, and targets under scope all must be absent or empty`() {
        val request =
            RuntimeRequests.refresh(
                """{"configuration_id":"c","instance_id":"$instance","refresh_id":"$id","parameter_revision":0,"scope":"all"}""",
            )

        request.selections shouldBe emptyMap()
        request.selectionsJson shouldBe "{}"
        refused { RuntimeRequests.refresh(refresh(scope = "all", targets = """["a"]""")) }.details["reason"] shouldBe "unexpected"
    }

    @Test
    fun `the refresh id is a v4 uuid in its canonical spelling - anything else is malformed, and a marker never leaks`() {
        refused { RuntimeRequests.refresh(refresh(refreshId = "00000000-0000-1000-8000-000000000000")) }.details shouldBe
            mapOf("path" to "refresh_id", "reason" to "malformed") // a v1
        refused { RuntimeRequests.refresh(refresh(refreshId = marker)) }.details["reason"] shouldBe "malformed"
        refused { RuntimeRequests.refresh(refresh(refreshId = id.uppercase())) }.details["reason"] shouldBe "malformed" // not canonical
        refused { RuntimeRequests.refresh(refresh(instanceId = marker)) }.details shouldBe
            mapOf("path" to "instance_id", "reason" to "malformed")
    }

    @Test
    fun `a missing or mistyped field names its path and reason`() {
        val cases =
            listOf(
                """{"instance_id":"$instance","refresh_id":"$id","parameter_revision":1,"scope":"all"}""" to
                    ("configuration_id" to "missing"),
                """{"configuration_id":5,"instance_id":"$instance","refresh_id":"$id","parameter_revision":1,"scope":"all"}""" to
                    ("configuration_id" to "wrong_type"),
                refresh(revision = "-1") to ("parameter_revision" to "wrong_type"),
                refresh(revision = "\"1\"") to ("parameter_revision" to "wrong_type"),
                """{"configuration_id":"c","instance_id":"$instance","refresh_id":"$id","scope":"all"}""" to
                    ("parameter_revision" to "missing"),
                refresh(scope = "everything") to ("scope" to "unknown_value"),
                refresh(selections = "[]") to ("selections" to "wrong_type"),
                refresh(scope = "targets", targets = "\"a\"") to ("targets" to "wrong_type"),
                refresh(scope = "targets", targets = "[1]") to ("targets" to "wrong_type"),
            )
        cases.forEach { (body, expected) ->
            val refusal = refused { RuntimeRequests.refresh(body) }
            (refusal.details["path"] to refusal.details["reason"]) shouldBe expected
        }
    }

    @Test
    fun `selections and targets are bounded before anything is read`() {
        refused { RuntimeRequests.refresh(refresh(selections = """{"a":"${"x".repeat(RuntimeRequests.MAX_SELECTIONS_BYTES)}"}""")) }
            .details shouldBe mapOf("path" to "selections", "reason" to "too_large")
        val many = (1..RuntimeRequests.MAX_TARGETS + 1).joinToString(",", "[", "]") { "\"t$it\"" }
        refused { RuntimeRequests.refresh(refresh(scope = "targets", targets = many)) }.details shouldBe
            mapOf("path" to "targets", "reason" to "too_large")
        RuntimeRequests
            .refresh(
                refresh(
                    scope = "targets",
                    targets =
                        (1..RuntimeRequests.MAX_TARGETS).joinToString(",", "[", "]") {
                            "\"t$it\""
                        },
                ),
            ).targets.size shouldBe
            RuntimeRequests.MAX_TARGETS
    }

    @Test
    fun `parameters reads its intent from the closed vocabulary`() {
        val body = { intent: String -> """{"configuration_id":"c","instance_id":"$instance","selections":{"a":1},"intent":"$intent"}""" }

        ParametersRequest.Intent.entries.forEach { intent ->
            RuntimeRequests.parameters(body(intent.wire)).intent shouldBe intent
        }
        refused { RuntimeRequests.parameters(body("whatever")) }.details shouldBe mapOf("path" to "intent", "reason" to "unknown_value")
        refused { RuntimeRequests.parameters("""{"configuration_id":"c","instance_id":"$instance"}""") }.details shouldBe
            mapOf("path" to "intent", "reason" to "missing")
    }

    @Test
    fun `abort reads exactly its instance id`() {
        RuntimeRequests.abort("""{"instance_id":"$instance"}""").instanceId.toString() shouldBe instance
        refused { RuntimeRequests.abort("{}") }.details shouldBe mapOf("path" to "instance_id", "reason" to "missing")
        refused { RuntimeRequests.abort("[]") }.details shouldBe mapOf("path" to "$", "reason" to "wrong_type")
    }

    @Test
    fun `a body that is not JSON, or nests past the bound, is the family's malformed 400 - and an empty body is a wrong-typed root`() {
        listOf("{", "not json", "[".repeat(200)).forEach { body ->
            val refusal = shouldThrow<ApiException> { RuntimeRequests.abort(body) }
            refusal.code shouldBe "dashboard.validation.body_invalid"
            refusal.details["reason"] shouldBe "malformed_json"
        }
        // Nothing to read is not "unparseable": it is a root that is not an object — the same 400, named by its path.
        refused { RuntimeRequests.abort("") }.details shouldBe mapOf("path" to "$", "reason" to "wrong_type")
    }
}
