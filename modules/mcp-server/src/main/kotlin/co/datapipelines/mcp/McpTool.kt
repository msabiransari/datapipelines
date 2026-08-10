package co.datapipelines.mcp

import io.modelcontextprotocol.spec.McpSchema

/**
 * One MCP tool (mcp-server.md §6.2).
 *
 * An implementation is **translation only** (§2 principle 1): it reads typed arguments, calls the
 * same service-layer collaborators the REST controllers call, and returns the payload to be
 * JSON-stringified into the §6.3 envelope. It does not build the envelope, does not check scopes
 * and does not map errors — [McpToolDispatcher] owns all three, so those rules cannot be
 * implemented fifteen slightly different ways.
 *
 * Failures are raised as [co.datapipelines.typesystem.DatapipelinesException] subclasses carrying
 * a catalogued code; the dispatcher turns them into `isError: true` results (§9.2).
 */
interface McpTool {
    /** The tool's wire definition — name, description and JSON input schema (§6.2). */
    val definition: McpSchema.Tool

    /** The tool name, i.e. the key the §7.6 scope matrix and the dispatcher are keyed on. */
    val name: String get() = definition.name()

    /**
     * Executes the tool for an already-authorized caller.
     *
     * @return the payload to serialize into the result's text block; `Unit`/`null` is not used —
     *   every v1 tool returns an object.
     */
    fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any?
}
