package co.datapipelines.web.ui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * The `?version=` parse every workspace model delegates to (#421): the semantics are the ones the
 * models always had (optional; a positive integer; everything else the 400), and the refusal's
 * reason is one constant that never carries the caller's input.
 */
class RequestedVersionTest {
    @Test
    fun `no version asked for is null`() {
        RequestedVersion.parse(null) shouldBe null
        RequestedVersion.parse("") shouldBe null
    }

    @ParameterizedTest
    @ValueSource(strings = ["1", "3", "2147483647"])
    fun `a positive integer is the version`(raw: String) {
        RequestedVersion.parse(raw) shouldBe raw.toInt()
    }

    @ParameterizedTest
    @ValueSource(strings = ["<script>", "abc", "0", "-1", "1e3", "1.5", " 3", "2147483648"])
    fun `anything else is a 400 whose reason is the constant and never echoes the input`(raw: String) {
        val refused = shouldThrow<ResponseStatusException> { RequestedVersion.parse(raw) }

        refused.statusCode shouldBe HttpStatus.BAD_REQUEST
        refused.reason shouldBe RequestedVersion.BAD_VERSION
        // The whole message, not only the reason: nothing of the input rides the exception into a log.
        // (Equality, not shouldNotContain: the message's own "400" contains the input "0".)
        refused.message shouldBe "400 BAD_REQUEST \"${RequestedVersion.BAD_VERSION}\""
        refused.reason!! shouldNotContain raw
    }

    @ParameterizedTest
    @ValueSource(strings = ["<script>", "abc", "1e3"])
    fun `each family's parser answers the shared parse`(raw: String) {
        FAMILY_PARSERS.forEach { parser ->
            shouldThrow<ResponseStatusException> { parser(raw) }.reason shouldBe RequestedVersion.BAD_VERSION
            parser("7") shouldBe 7
            parser(null) shouldBe null
        }
    }

    @Test
    fun `the visualizations alias is the shared constant`() {
        VisualizationWorkspaceModel.BAD_VERSION shouldBe RequestedVersion.BAD_VERSION
    }

    private companion object {
        val FAMILY_PARSERS: List<(String?) -> Int?> =
            listOf(
                { raw: String? -> PipelineWorkspaceModel.parseRequestedVersion(raw) },
                { raw: String? -> DashboardWorkspaceModel.parseRequestedVersion(raw) },
                { raw: String? -> VisualizationWorkspaceModel.parseRequestedVersion(raw) },
                { raw: String? -> ParameterSetsWorkspaceModel.parseRequestedVersion(raw) },
            )
    }
}
