package co.datapipelines.web.api

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * The 502 logging partition (R6), pinned as a coupling: a code mapped to HTTP 502 is EITHER
 * "the caller's own downstream is down" (logs WARN without a stack, because it is not an
 * operator incident) or "possibly OUR bug" (logs ERROR with the stack, like
 * `query_execution_failed` — the rendered SQL may be the defect).
 *
 * Both sets are named exactly, so a future code added to [ApiErrorCatalog] at 502 fails here
 * until its WARN-vs-ERROR decision is made consciously — a gateway code cannot silently log
 * ERROR+stack (invisible incident-noise regression) or silently WARN (hidden defect).
 */
class ApiErrorCatalogGatewayCodesTest {
    @Test
    fun `gateway codes are exactly the deliberate three`() {
        ApiErrorCatalog.GATEWAY_CODES shouldContainExactly
            setOf(
                PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
                PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED,
                PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED,
            )
    }

    @Test
    fun `the WARN-demotion set is the caller-downstream subset of gateway codes`() {
        assertAll(
            {
                ApiErrorCatalog.CALLER_DOWNSTREAM_DOWN shouldContainExactly
                    setOf(
                        PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
                        PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED,
                    )
            },
            { ApiErrorCatalog.CALLER_DOWNSTREAM_DOWN.all { it in ApiErrorCatalog.GATEWAY_CODES } shouldBe true },
        )
    }

    @Test
    fun `every gateway code is classified - the ERROR-with-stack remainder is exactly the our-bug set`() {
        ApiErrorCatalog.GATEWAY_CODES - ApiErrorCatalog.CALLER_DOWNSTREAM_DOWN shouldContainExactly
            setOf(PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED)
    }
}
