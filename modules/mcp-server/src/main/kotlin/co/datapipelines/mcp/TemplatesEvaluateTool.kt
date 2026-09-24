package co.datapipelines.mcp

import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.templates.TransformBlocks
import co.datapipelines.templates.TransformTestInput
import com.fasterxml.jackson.databind.JsonMappingException
import io.modelcontextprotocol.spec.McpSchema
import java.time.Instant

/** §6.2.43 — the `input` object description, kept off the schema line for length. */
private const val EVALUATE_INPUT_DESC =
    "The input object of the template's contract: { rows: [...], inputs: {...} } — in row mode `rows` is the " +
        "batch and `inputs` holds the value inputs only (the table input is NOT listed); in table/value mode " +
        "`inputs` holds every input, tables as arrays. Optional `now` (ISO-8601) pins the clock: without it the " +
        "\$now()/\$millis() builtins refuse."

/** §6.2.43 — the `now` description. */
private const val EVALUATE_NOW_DESC =
    "Optional ISO-8601 instant the \$now()/\$millis() builtins return for this evaluation. Absent, a body that " +
        "reads the clock refuses (a transform is a pure function of its inputs — the clock is an input)."

/**
 * `templates_evaluate` (mcp-server.md §6.2.43). Scope: `author` — the `templates_render` row
 * (R6: evaluating untrusted code on the server is the same authoring act as rendering).
 * Not mutating: nothing is staged and nothing is stored.
 *
 * `sql_probe`'s twin for transform templates: resolves the version exactly like
 * `templates_render` (explicit `version`, else the working version — the draft when one
 * exists, else the latest released), applies the contract's input check, evaluates on the
 * evaluation pool under `evaluate-timeout-seconds`, gates the output and runs the
 * invariants, and answers `{ output, rejects, invariants }`. A refusal is the code with
 * its detail; the audit row carries `tool`, `template` and `version` — never the input
 * object or the output (record §9.5).
 */
class TemplatesEvaluateTool(
    private val evaluate: TemplateEvaluateService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_evaluate",
            description =
                "Evaluate a transform template ('jsonata'/'javascript') over a caller-supplied input object and " +
                    "return { output, rejects, invariants } — no staging, no Context. Use this to run a transform's " +
                    "body against one input the way its test suite does (sql_probe's twin; templates_render is for " +
                    "sql/html and refuses a transform type with template.render_not_applicable). The version " +
                    "resolves as templates_render does: omitted, the working version (the draft when one exists, " +
                    "else the latest released). A refusal is the code with its detail — a type-gate refusal, an " +
                    "input-contract violation, or an engine refusal (timeout, resource limit, pool exhausted).",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id", "input"],
                  "properties": {
                    "id": {"type": "string"},
                    "version": {"type": "integer", "description": "Specific version. Defaults to the working version: the draft when one exists, else the latest released."},
                    "input": {"type": "object", "description": "$EVALUATE_INPUT_DESC", "additionalProperties": true},
                    "now": {"type": "string", "description": "$EVALUATE_NOW_DESC"}
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredString("id")
        val input =
            try {
                TransformBlocks.mapper.convertValue(args.requiredObject("input"), TransformTestInput::class.java)
            } catch (
                @Suppress("SwallowedException") err: JsonMappingException,
            ) {
                // The protocol error names the binding failure; the original is a mapping detail.
                throw McpArguments.invalidParams(
                    "The 'input' object does not bind ({ rows, inputs, meta?, now? }): ${err.originalMessage}",
                )
            }
        val now =
            args.string("now")?.let { raw ->
                try {
                    Instant.parse(raw)
                } catch (
                    @Suppress("SwallowedException") err: java.time.format.DateTimeParseException,
                ) {
                    throw McpArguments.invalidParams("'now' must be an ISO-8601 instant, was '$raw'.")
                }
            }
        val result = evaluate.evaluate(workspaceId, id, args.version(), input, now)
        return mapOf(
            "output" to result.output,
            "rejects" to result.rejects,
            "invariants" to
                result.invariants.map { verdict ->
                    mapOf("name" to verdict.name, "passed" to verdict.passed, "message" to verdict.message)
                },
        )
    }
}
