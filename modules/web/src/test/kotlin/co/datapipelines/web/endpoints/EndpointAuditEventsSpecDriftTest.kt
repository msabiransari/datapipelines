package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointServeAudit
import co.datapipelines.application.endpoints.EndpointVersionService
import co.datapipelines.web.TestRepoFiles
import co.datapipelines.web.release.EndpointReleaseParticipant
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Drift guard (#786): every `endpoint.*` audit kind the API writers emit is a row of enums.md §15, and §15 documents no
 * `endpoint.*` kind beyond them plus the one retired from new writes (`endpoint.published`, kept for history). The
 * names are wire values in `audit_log.event`: a typo would write rows no query finds and nothing else would fail.
 */
class EndpointAuditEventsSpecDriftTest {
    private val documented: Set<String> = parseEndpointEvents()

    @Test
    fun `the parse found endpoint events - guards against a silent empty parse`() {
        withClue("No endpoint.* events parsed from enums.md §15 — the heading or table format changed") {
            documented.isEmpty() shouldBe false
        }
    }

    @Test
    fun `the emitted endpoint audit kinds plus the retired one are exactly the documented rows`() {
        val emitted =
            setOf(
                EndpointServeAudit.SERVE_EVENT,
                // The binding writer's kinds are private to it (bindings are outside #786); spelled as written there.
                "endpoint.key_bound",
                "endpoint.key_unbound",
                EndpointPublishService.AUDIT_DRAFT_SAVED,
                EndpointPublishService.AUDIT_UNPUBLISHED,
                EndpointReleaseParticipant.AUDIT_RELEASED,
                EndpointVersionService.AUDIT_VERSION_DISCARDED,
                EndpointVersionService.AUDIT_VERSION_RESTORED,
                EndpointVersionService.AUDIT_VERSION_PURGED,
                EndpointVersionService.AUDIT_CURRENT_SWITCHED,
            )
        documented shouldContainExactlyInAnyOrder emitted + RETIRED
    }

    private companion object {
        const val SPEC_PATH = "docs/enums.md"
        const val SECTION_START = "## 15."
        const val SECTION_END = "## 16."

        /** Retired from new writes by #786; its history rows stay readable. */
        const val RETIRED = "endpoint.published"

        val TABLE_VALUE = Regex("^\\|\\s*`(endpoint\\.[a-z0-9_.]+)`", RegexOption.MULTILINE)

        fun parseEndpointEvents(): Set<String> {
            val text = TestRepoFiles.read(SPEC_PATH)
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $SPEC_PATH" }
            val end = text.indexOf(SECTION_END, start + SECTION_START.length)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START' in $SPEC_PATH" }
            return TABLE_VALUE.findAll(text.substring(start, end)).map { it.groupValues[1] }.toSet()
        }
    }
}
