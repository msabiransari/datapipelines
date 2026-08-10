package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.staging.StagingInvalidColumnNameException
import co.datapipelines.staging.StagingMemoryLimitException
import co.datapipelines.staging.StagingValueOverflowException
import co.datapipelines.templates.TemplateRenderException
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * dag-executor.md §8.2 — every row of the mapping table, and the rule that governs the whole table:
 * **a code the raising module already chose always wins.**
 *
 * Re-deriving a code from the exception's Java type here would silently coarsen the template
 * engine's `template_not_found`/`template_render_failed` split, staging's overflow codes and the
 * result store's `too_large`/`storage_unavailable` into one generic per-phase code.
 */
class ErrorCodeMapperTest {
    @Test
    fun `a carried catalog code always wins over the phase default`() {
        // Raised in the EXECUTE phase, which would otherwise map to pipeline.execution.aborted.
        val carried =
            DatapipelinesException(
                code = PipelineErrorCodes.Result.TOO_LARGE,
                message = "over cap",
                details = mapOf("bytes" to 5L),
            )

        val mapped = ErrorCodeMapper.map(carried, NodePhase.EXECUTE, "n1")

        mapped.code shouldBe PipelineErrorCodes.Result.TOO_LARGE
        mapped.details["bytes"] shouldBe 5L
        mapped.details["node_id"] shouldBe "n1"
        mapped.details["phase"] shouldBe "execute"
    }

    @Test
    fun `the template engine's own two codes survive the mapper`() {
        val notFound =
            TemplateRenderException("gone", TemplateRef("t", 1), PipelineErrorCodes.Node.TEMPLATE_NOT_FOUND)
        val renderFailed = TemplateRenderException("undefined variable: x", TemplateRef("t", 1))

        ErrorCodeMapper.map(notFound, NodePhase.RENDER, "n").code shouldBe PipelineErrorCodes.Node.TEMPLATE_NOT_FOUND
        ErrorCodeMapper.map(renderFailed, NodePhase.RENDER, "n").code shouldBe PipelineErrorCodes.Node.TEMPLATE_RENDER_FAILED
    }

    @Test
    fun `staging's own codes survive the mapper`() {
        ErrorCodeMapper.map(StagingValueOverflowException("too wide"), NodePhase.STAGE, "n").code shouldBe
            PipelineErrorCodes.Staging.VALUE_OVERFLOW
        ErrorCodeMapper.map(StagingMemoryLimitException(2L, 1L), NodePhase.STAGE, "n").code shouldBe
            PipelineErrorCodes.Staging.MEMORY_LIMIT_EXCEEDED
        ErrorCodeMapper.map(StagingInvalidColumnNameException(1, "1bad"), NodePhase.STAGE, "n").code shouldBe
            PipelineErrorCodes.Staging.INVALID_COLUMN_NAME
    }

    @Test
    fun `a raw SQLException is classified by the phase it surfaced in`() {
        val sql = SQLException("boom", "42000", 1234)

        ErrorCodeMapper.map(sql, NodePhase.CONNECT, "n").code shouldBe PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED
        ErrorCodeMapper.map(sql, NodePhase.EXECUTE, "n").code shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED
        ErrorCodeMapper.map(sql, NodePhase.MATERIALIZE, "n").code shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED
        ErrorCodeMapper.map(sql, NodePhase.STAGE, "n").code shouldBe PipelineErrorCodes.Node.STAGING_FAILED
        ErrorCodeMapper.map(sql, NodePhase.WRITEBACK, "n").code shouldBe PipelineErrorCodes.Node.WRITEBACK_FAILED
        ErrorCodeMapper.map(sql, NodePhase.RENDER, "n").code shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED
    }

    @Test
    fun `a SQLException carries its sqlstate and vendor code into the details`() {
        val mapped = ErrorCodeMapper.map(SQLException("boom", "57014", 57014), NodePhase.EXECUTE, "n")

        mapped.details["sql_state"] shouldBe "57014"
        mapped.details["vendor_code"] shouldBe 57014
    }

    @Test
    fun `anything else falls back to the phase's own code, and to execution_aborted otherwise`() {
        val other = IllegalStateException("internal")

        ErrorCodeMapper.map(other, NodePhase.RENDER, "n").code shouldBe PipelineErrorCodes.Node.TEMPLATE_RENDER_FAILED
        ErrorCodeMapper.map(other, NodePhase.CONNECT, "n").code shouldBe PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED
        ErrorCodeMapper.map(other, NodePhase.STAGE, "n").code shouldBe PipelineErrorCodes.Node.STAGING_FAILED
        ErrorCodeMapper.map(other, NodePhase.WRITEBACK, "n").code shouldBe PipelineErrorCodes.Node.WRITEBACK_FAILED
        // §8.2's "executor-internal failure with no more specific code".
        ErrorCodeMapper.map(other, NodePhase.EXECUTE, "n").code shouldBe PipelineErrorCodes.Execution.ABORTED
        ErrorCodeMapper.map(other, NodePhase.MATERIALIZE, "n").code shouldBe PipelineErrorCodes.Execution.ABORTED
    }

    @Test
    fun `a message-less exception still yields a usable message`() {
        ErrorCodeMapper.map(RuntimeException(), NodePhase.EXECUTE, "n").message shouldBe "RuntimeException"
    }

    /**
     * B1: driver text is bounded at the funnel.
     *
     * H2, MSSQL and Oracle append the whole failing statement to `SQLException.message`, and the
     * rendered SQL that produced it is bounded only by the 64M-character engine backstop. An
     * unbounded copy of that string went into `MappedError`, `NodeStats.errorMessage`, the
     * `node_failed` and `pipeline_failed` payloads, `error_json` and `node_stats_json` in Postgres,
     * and every log line that printed it — once per node, across up to
     * `max-concurrent-executions-global` executions at a time.
     */
    @Test
    fun `an enormous driver message is truncated before it reaches any carrier`() {
        val huge = "X".repeat(HUGE_MESSAGE_CHARS)

        val fromSql = ErrorCodeMapper.map(SQLException(huge, "42000", 1), NodePhase.EXECUTE, "n")
        val fromOther = ErrorCodeMapper.map(RuntimeException(huge), NodePhase.STAGE, "n")
        val fromCollaborator =
            ErrorCodeMapper.map(
                DatapipelinesException(code = PipelineErrorCodes.Staging.VALUE_OVERFLOW, message = huge),
                NodePhase.STAGE,
                "n",
            )

        fromSql.message.length shouldBe ErrorCodeMapper.MAX_MESSAGE_CHARS
        fromOther.message.length shouldBe ErrorCodeMapper.MAX_MESSAGE_CHARS
        // A collaborator's own exception can quote driver text too — bounded on that path as well.
        fromCollaborator.message.length shouldBe ErrorCodeMapper.MAX_MESSAGE_CHARS

        // The head is kept, because that is where a SQL author's actual error is.
        fromSql.message shouldBe "X".repeat(ErrorCodeMapper.MAX_MESSAGE_CHARS)
        // Codes and structured details are untouched by the bound.
        fromSql.code shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED
        fromCollaborator.code shouldBe PipelineErrorCodes.Staging.VALUE_OVERFLOW
    }

    @Test
    fun `a message within the bound is passed through unchanged`() {
        val exact = "Y".repeat(ErrorCodeMapper.MAX_MESSAGE_CHARS)
        val short = "column NOPE not found"

        ErrorCodeMapper.map(SQLException(exact), NodePhase.EXECUTE, "n").message shouldBe exact
        ErrorCodeMapper.map(SQLException(short), NodePhase.EXECUTE, "n").message shouldBe short
    }

    private companion object {
        /** Comfortably past the bound, and past anything a human would paste into a bug report. */
        const val HUGE_MESSAGE_CHARS = 200_000
    }
}
