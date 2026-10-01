package co.datapipelines.web.ui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * #348 — the OLD editor route is a compatibility redirect into the canonical read page
 * (workspace spec §3.2): an explicit valid version and a supported tab survive, a malformed
 * version is the same house 400 the canonical route answers, and no-version links follow the
 * canonical current-first default on purpose. The redirect carries NO permission of its own
 * beyond the read floor it now declares (`PIPELINE_READ` — the doc row and the walk moved with
 * it in the same commit).
 */
class PipelineEditorControllerTest {
    private val controller = PipelineEditorController()
    private val pipelineId = UUID.randomUUID()

    @Test
    fun `the old editor URL redirects to the canonical read page`() {
        val view = controller.editor(pipelineId, version = null, tab = null)
        view.url shouldBe "/pipelines/$pipelineId"
    }

    @Test
    fun `an explicit valid version and a supported tab survive the redirect`() {
        val view = controller.editor(pipelineId, version = "3", tab = "overview")
        view.url shouldBe "/pipelines/$pipelineId?version=3&tab=overview"
    }

    @Test
    fun `a version without a tab redirects with the version alone - the canonical default resolves the tab`() {
        val view = controller.editor(pipelineId, version = "2", tab = null)
        view.url shouldBe "/pipelines/$pipelineId?version=2"
    }

    @Test
    fun `an unsupported tab is dropped, not forwarded`() {
        val view = controller.editor(pipelineId, version = null, tab = "banana")
        view.url shouldBe "/pipelines/$pipelineId"
    }

    @Test
    fun `a malformed version is the house 400 - the shim never clamps to a different version`() {
        shouldThrow<ResponseStatusException> { controller.editor(pipelineId, version = "abc", tab = null) }
            .statusCode shouldBe HttpStatus.BAD_REQUEST
        shouldThrow<ResponseStatusException> { controller.editor(pipelineId, version = "0", tab = null) }
            .statusCode shouldBe HttpStatus.BAD_REQUEST
    }
}
