package co.datapipelines.parameters

import io.kotest.matchers.shouldBe
import java.util.UUID
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

/**
 * The parameters side of the templates reverse arrow (the record's §8.4, lane D) against the
 * REAL tables — the scanner's three questions, workspace scoping, and the rule that makes the
 * delete guard work: a pin from ANY stored version is a reference, a pin from a DISCARDED
 * version is not a live one.
 *
 * The falsification this test exists for (the brief's "red on the base"): a template pinned by
 * a set alone WAS deletable — the delete guards scanned pipelines only. With the scanner
 * disconnected from the guards, `liveVersionPins`/`anyVersionPins` here still answer, and the
 * guards' new branches go red against these rows.
 */
class ParameterSetTemplatePinsIntegrationTest {
    init {
        ParametersTestDb.reset()
    }

    private val harness = ParametersHarness(customers = null)
    private val scanner = ParameterSetTemplatePins(ParametersTestDb.jdbc)
    private val workspaceId = ParametersTestDb.WORKSPACE
    private val author = ParametersTestDb.AUTHOR

    @Test
    fun `a set-only pin is found by every scan`() {
        val pin = harness.template("acme/lib/states_of_country.sql", "SELECT 1 AS value, 1 AS display_value, TRUE AS is_default")
        val document =
            harness.document(
                """{"name":"acme/sales/pins_only","display_name":"Pins only","parameters":[
                   {"name":"state","label":"State","type":"STRING","kind":"SELECT","cardinality":"SINGLE",
                    "source":{"template":{"id":"${pin.id}","version":${pin.version}},"datasource":"warehouse"}}]}""",
            )
        val set = harness.create(document)

        val working = scanner.workingVersionPins(workspaceId, pin.id, pin.version)
        working.map { it.setName } shouldContainExactly listOf(set.record.name)
        working.single().parameter shouldBe "state"
        working.single().versionStatus shouldBe co.datapipelines.pipeline.PipelineVersionStatus.DRAFT

        scanner.anyVersionPins(workspaceId, pin.id).size shouldBe 1
        scanner.liveVersionPins(workspaceId, pin.id, pin.version).size shouldBe 1
        scanner.countWorkingPinsByPinnedVersion(workspaceId, pin.id) shouldBe mapOf(pin.version to 1)
    }

    @Test
    fun `a discarded historical version keeps its any-version reference - the live one is gone`() {
        val pin = harness.template("acme/lib/pinned_then_dropped.sql", "SELECT 1 AS value, 1 AS display_value, TRUE AS is_default")
        val document =
            harness.document(
                """{"name":"acme/sales/discarded_pin","display_name":"Discarded pin","parameters":[
                   {"name":"state","label":"State","type":"STRING","kind":"SELECT","cardinality":"SINGLE",
                    "source":{"template":{"id":"${pin.id}","version":${pin.version}},"datasource":"warehouse"}}]}""",
            )
        val set = harness.create(document)
        // Discard targets a RELEASED version; the harness's template() seeds it RELEASED, so
        // the release's pin precondition holds. v1 pins; v2 (constants) does not.
        harness.service.release(workspaceId, set.record.id, set.detail.bodyHash, author)
        val v2 =
            harness.service.write(
                workspaceId,
                set.record.id,
                harness.document(
                    """{"name":"acme/sales/discarded_pin","display_name":"Discarded pin","parameters":[
                       {"name":"state","label":"State","type":"STRING","kind":"SELECT","cardinality":"SINGLE",
                        "source":{"constants":[{"value":"USA","display_value":"United States"}]}}]}""",
                ),
                set.detail.bodyHash,
                author,
                co.datapipelines.pipeline.WriteSurface.MCP,
            )
        harness.service.release(workspaceId, set.record.id, v2.detail.bodyHash, author)
        harness.service.discardVersion(workspaceId, set.record.id, 1, author)

        // The guard's evidence (040 D1 question 2, sets included): the DISCARDED v1 is not a
        // LIVE pin, but the set is LIVE and v1 is restorable — its pin IS a reference, and the
        // delete guard must refuse on it.
        scanner.liveVersionPins(workspaceId, pin.id, pin.version).shouldBeEmpty()
        scanner.anyVersionPins(workspaceId, pin.id).size shouldBe 1
        scanner.countWorkingPinsByPinnedVersion(workspaceId, pin.id) shouldBe emptyMap()
    }

    @Test
    fun `another workspace's set is invisible to the scan`() {
        val otherWorkspace = ParametersTestDb.OTHER_WORKSPACE
        // The pin's template lives in the OTHER workspace; the set is created there too.
        val pin =
            harness.template(
                "acme/lib/foreign_pin.sql",
                "SELECT 1 AS value, 1 AS display_value, TRUE AS is_default",
                workspaceId = otherWorkspace,
            )
        val document =
            harness.document(
                """{"name":"acme/other/pinned","display_name":"Pinned elsewhere","parameters":[
                   {"name":"state","label":"State","type":"STRING","kind":"SELECT","cardinality":"SINGLE",
                    "source":{"template":{"id":"${pin.id}","version":${pin.version}},"datasource":"warehouse"}}]}""",
            )
        harness.service.create(otherWorkspace, document, author, co.datapipelines.pipeline.WriteSurface.MCP)

        scanner.workingVersionPins(workspaceId, pin.id, pin.version).shouldBeEmpty()
        scanner.anyVersionPins(workspaceId, pin.id).shouldBeEmpty()
        scanner.liveVersionPins(workspaceId, pin.id, pin.version).shouldBeEmpty()
    }

}
