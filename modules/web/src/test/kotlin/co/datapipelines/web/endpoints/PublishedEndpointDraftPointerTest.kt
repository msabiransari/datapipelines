package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointAuthorizer
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.executor.ResultConfig
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.web.config.EndpointsProperties
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * D63 (owner, 2026-09-09): a published endpoint serves whatever the pipeline's pointer names in
 * DEVELOPMENT — a draft included — because the endpoint has to be tested before the release.
 * Outside development a draft pointer cannot arise (no drafts there), and the rule says so
 * anyway. 101 shipped a 503 on a draft pointer; this is the reversal, pinned.
 */
class PublishedEndpointDraftPointerTest {
    private fun service(development: Boolean) =
        PublishedEndpointServeService(
            registry = mockk<EndpointRegistry>(),
            bindings = mockk<EndpointKeyBindingRepository>(),
            authorizer = EndpointAuthorizer(),
            readOnlyRule = mockk<ReadOnlyPipelineRule>(),
            pipelines = mockk(),
            runner = mockk(),
            resultStore = mockk(),
            resultUrls = { "https://dp.test/api/v1/executions/$it/result" },
            resultConfig = ResultConfig(),
            endpointsProperties = EndpointsProperties(),
            audit = mockk(relaxed = true),
            authoring = AuthoringGuard(enabled = development),
            scope = CoroutineScope(Dispatchers.Default),
        )

    @Test
    fun `development serves a released or a draft pointer, never a discarded one`() {
        val dev = service(development = true)
        assertAll(
            { dev.servable(PipelineVersionStatus.RELEASED) shouldBe true },
            { dev.servable(PipelineVersionStatus.DRAFT) shouldBe true },
            { dev.servable(PipelineVersionStatus.DISCARDED) shouldBe false },
        )
    }

    @Test
    fun `outside development only a release is served`() {
        val hardened = service(development = false)
        assertAll(
            { hardened.servable(PipelineVersionStatus.RELEASED) shouldBe true },
            { hardened.servable(PipelineVersionStatus.DRAFT) shouldBe false },
            { hardened.servable(PipelineVersionStatus.DISCARDED) shouldBe false },
        )
    }
}
