package co.datapipelines.mcp

import io.modelcontextprotocol.spec.McpError
import java.util.UUID

/**
 * Typed, validating access to one tool call's `arguments` object.
 *
 * A missing or ill-typed argument is a **protocol** failure, not an application error:
 * mcp-server.md §9.1 maps it to JSON-RPC `-32602 invalid params`, which is what [invalidParams]
 * raises. Application errors (validation, not-found, scope) travel as tool results with
 * `isError: true` (§9.2) and never come from here.
 */
class McpArguments(
    private val raw: Map<String, Any?>,
) {
    /** The raw argument map — for the two tools whose input is a free-form object (§6.2.3, §6.2.9). */
    fun rawMap(): Map<String, Any?> = raw

    fun has(name: String): Boolean = raw[name] != null

    fun string(name: String): String? =
        when (val value = raw[name]) {
            null -> null
            is String -> value.takeIf { it.isNotBlank() }
            else -> throw invalidParams("Argument '$name' must be a string.")
        }

    fun requiredString(name: String): String = string(name) ?: throw invalidParams("Missing required argument '$name'.")

    fun uuid(name: String): UUID? =
        string(name)?.let {
            runCatching { UUID.fromString(it) }.getOrElse { _ -> throw invalidParams("Argument '$name' must be a UUID.") }
        }

    fun requiredUuid(name: String): UUID = uuid(name) ?: throw invalidParams("Missing required argument '$name'.")

    fun boolean(name: String): Boolean? =
        when (val value = raw[name]) {
            null -> null
            is Boolean -> value
            else -> throw invalidParams("Argument '$name' must be a boolean.")
        }

    /**
     * An integer argument, clamped into the schema's own bounds rather than rejected: the
     * declared `maximum` in a tool's inputSchema (§6.2) is a server limit, and an agent asking
     * for more rows than the server pages gets the server's page, not an error.
     */
    fun int(
        name: String,
        default: Int,
        min: Int,
        max: Int,
    ): Int {
        val value =
            when (val raw = raw[name]) {
                null -> return default
                is Number -> raw.toLong()
                else -> throw invalidParams("Argument '$name' must be an integer.")
            }
        return value.coerceIn(min.toLong(), max.toLong()).toInt()
    }

    /**
     * An entity `version` argument — **never clamped**.
     *
     * Clamping is right for `limit` (a server bound: an agent asking for more rows than the server
     * pages gets the server's page). It is catastrophic for `version`: an off-by-one `{version: 0}`
     * would silently execute version **1** against a production datasource and label the result
     * `version: 1`, which is a wrong answer wearing a correct-looking label. A version below 1 does
     * not exist in any pipeline or template (both start at 1), so it is refused outright.
     *
     * @return the requested version, or null when the argument is absent (caller defaults to latest).
     */
    fun version(name: String = "version"): Int? {
        val value =
            when (val raw = raw[name]) {
                null -> return null
                is Number -> raw.toLong()
                else -> throw invalidParams("Argument '$name' must be an integer.")
            }
        if (value < MIN_VERSION || value > Int.MAX_VALUE) {
            throw invalidParams("Argument '$name' must be $MIN_VERSION or greater; versions start at $MIN_VERSION.")
        }
        return value.toInt()
    }

    /** A nested object argument (`parameters`, `context`, `settings`). */
    fun objectArg(name: String): Map<String, Any?>? {
        val value = raw[name] ?: return null
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw invalidParams("Argument '$name' must be an object.")
    }

    fun requiredObject(name: String): Map<String, Any?> = objectArg(name) ?: throw invalidParams("Missing required argument '$name'.")

    /** A list-of-objects argument (`nodes`, `imports`). */
    fun listArg(name: String): List<Any?>? {
        val value = raw[name] ?: return null
        return value as? List<Any?> ?: throw invalidParams("Argument '$name' must be an array.")
    }

    fun requiredList(name: String): List<Any?> = listArg(name) ?: throw invalidParams("Missing required argument '$name'.")

    /** An enum-valued string argument, rejecting anything outside [allowed]. */
    fun enumString(
        name: String,
        allowed: Set<String>,
        default: String? = null,
    ): String? {
        val value = string(name) ?: return default
        if (value !in allowed) {
            throw invalidParams("Argument '$name' must be one of ${allowed.sorted().joinToString(", ")}.")
        }
        return value
    }

    companion object {
        /** JSON-RPC `-32602` (§9.1) — malformed or missing tool arguments. */
        const val INVALID_PARAMS: Int = -32602

        /** JSON-RPC `-32603` (§9.1) — an unexpected server-side failure. */
        const val INTERNAL_ERROR: Int = -32603

        /**
         * Authorization refusal on a method with no `isError` content channel — the `resources` methods.
         *
         * JSON-RPC 2.0 reserves `-32000..-32099` for implementation-defined server errors, which is
         * where an authorization refusal belongs — §9.1's standard codes have no auth member, and
         * reusing `-32602`/`-32603` would tell an agent to fix its arguments or retry, both wrong.
         * The message carries the §13.7 code so the agent still sees `auth.scope.insufficient`.
         */
        const val FORBIDDEN: Int = -32003

        /** The lowest version any pipeline or template can have — both start at 1. */
        const val MIN_VERSION: Long = 1

        fun invalidParams(message: String): McpError = McpError.builder(INVALID_PARAMS).message("Invalid params: $message").build()

        /** A scope refusal for a method that cannot answer with a tool result (§7.3 listing, reads). */
        fun forbidden(message: String): McpError = McpError.builder(FORBIDDEN).message(message).build()
    }
}
