package co.datapipelines.mcp

import co.datapipelines.calculators.CalculatorInput
import co.datapipelines.calculators.CalculatorKind
import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.modelcontextprotocol.spec.McpSchema

/**
 * `calculators_list` (mcp-server.md §6.2.23). Scope: `read` — the catalog is a property of the
 * BUILD, not of any workspace's data: the same kinds, in the same order, for every caller.
 *
 * The one tool an agent must call before it can author a `CALCULATOR` node, because a `kind` and
 * its input names are the two things it cannot guess. Returning the typed schemas — not just the
 * names — is what lets an agent get a node right on the first attempt instead of discovering the
 * input list one 400 at a time.
 */
class CalculatorsListTool : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "calculators_list",
            description =
                "The catalog of calculator kinds a CALCULATOR node can evaluate: every kind with its typed " +
                    "inputs (name, type, required, whether it takes a JSON array, and its default when optional), " +
                    "its output type, and one worked example. Call this before authoring a CALCULATOR node — the " +
                    "kind names and input names are not guessable. Also returns the Context keys every pipeline " +
                    "can reference without declaring anything: the deployment's org_* values and the platform " +
                    "keys current_date, current_timestamp and execution_id. Read-only.",
            schema =
                """
                {
                  "type": "object",
                  "properties": {},
                  "additionalProperties": false
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any =
        mapOf(
            "kinds" to CalculatorRegistry.KINDS.map(CalculatorPayload::of),
            "count" to CalculatorRegistry.KINDS.size,
            "context_keys" to CalculatorPayload.contextKeys(),
            "docs" to "docs/calculators.md",
        )
}

/**
 * `calculators_get` (mcp-server.md §6.2.24). Scope: `read`.
 *
 * The same entry `calculators_list` returns, for one kind. It exists so an agent that already
 * knows the kind does not have to pull the whole catalog to re-read one signature — the same
 * reason `templates_get` exists beside `templates_list`.
 */
class CalculatorsGetTool : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "calculators_get",
            description =
                "One calculator kind's full definition: display name, description, typed inputs, output type and " +
                    "a worked example. Use it when you know the kind and need its exact input names and types. " +
                    "An unknown kind is refused with the catalogued names in the error detail. Read-only.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["kind"],
                  "properties": {
                    "kind": {"type": "string", "description": "The kind name, e.g. fiscal_quarter."}
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val kind = args.requiredString("kind")
        val found =
            CalculatorRegistry.find(kind)
                ?: throw DatapipelinesException(
                    // The save-time code, deliberately: an agent that sees `calculator_unknown`
                    // here and `calculator_unknown` from a rejected `pipelines_create` is looking
                    // at one fact about the world, not two unrelated failures.
                    code = PipelineErrorCodes.Validation.CALCULATOR_UNKNOWN,
                    message = "No calculator kind named '$kind'.",
                    details = mapOf("kind" to kind, "known_kinds" to CalculatorRegistry.NAMES),
                )
        return CalculatorPayload.of(found)
    }
}

/** The one projection both tools return, so the two shapes cannot drift apart. */
internal object CalculatorPayload {
    fun of(kind: CalculatorKind): Map<String, Any?> =
        mapOf(
            "kind" to kind.kind,
            "display_name" to kind.displayName,
            "description" to kind.description,
            "inputs" to kind.inputs.map(::input),
            "output" to (kind.output?.wire ?: CalculatorInput.ANY_TYPE),
            "example" to mapOf("inputs" to kind.example.inputs, "output" to kind.example.output),
        )

    private fun input(input: CalculatorInput): Map<String, Any?> =
        buildMap {
            put("name", input.name)
            put("type", input.typeName)
            put("required", input.required)
            // Only when true: a `"list": false` on eighty inputs is noise in an agent's context
            // window, and the absence carries the same information.
            if (input.isList) put("list", true)
            put("description", input.description)
            input.defaultDescription?.let { put("default", it) }
        }

    /**
     * The keys a pipeline may reference with no declaration of its own — org config and the
     * platform tier (pipeline-contract §7.2 tiers 1-2).
     *
     * Deliberately the SHIPPED key names rather than this deployment's values: the tool is a
     * catalog, values belong to an execution, and an agent authoring a body needs to know that
     * `$org_fiscal_start_date` resolves — not what it currently resolves to.
     */
    fun contextKeys(): Map<String, Any?> =
        mapOf(
            "org" to OrgContext.KEYS,
            "platform" to ContextKeys.PLATFORM_TYPES.map { (name, type) -> mapOf("name" to name, "type" to type.wire) },
        )
}
