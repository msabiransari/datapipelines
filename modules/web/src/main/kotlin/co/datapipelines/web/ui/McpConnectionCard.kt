package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKeyCredential
import co.datapipelines.auth.AuthProperties
import co.datapipelines.mcp.McpToolCatalog
import org.springframework.ui.Model

/**
 * The MCP connection card's model (079 §C; shared since #585): the values
 * `partials/mcp-connection.html` renders, stamped by the two pages that show the card — the API
 * console and the API home — so the config a reader pastes and the tool count beside it come
 * from ONE place and the two pages cannot drift.
 *
 * Every value is a constant of the system (the header name, the key prefix, the MCP path) or
 * configuration — none of it is user input, and the key in the config is always the PLACEHOLDER,
 * never a real one.
 */
class McpConnectionCard(
    private val authProperties: AuthProperties,
) {
    fun stamp(model: Model) {
        model.addAttribute("mcpUrl", mcpUrl())
        model.addAttribute("mcpHeader", ApiKeyCredential.HEADER)
        model.addAttribute("keyPrefix", ApiKeyCredential.KEY_PREFIX)
        // Built HERE, not in the template: a multi-line block assembled out of Thymeleaf
        // string literals would depend on how that dialect treats a backslash escape, and
        // the answer would be discovered by a reader seeing a literal "\n" on the screen.
        // `th:text` escapes the whole thing on the way out regardless.
        model.addAttribute("mcpConfigJson", mcpConfigJson())
        // NEVER a literal: the count is what `tools/list` will actually return, and the
        // marketing site renders the same expression (SiteController) for the same reason.
        model.addAttribute("mcpToolCount", McpToolCatalog.NAMES.size)
    }

    /**
     * The MCP URL the connection JSON shows.
     *
     * `datapipelines.auth.base-url` is the deployment's declared external origin and is the
     * ONLY safe source: `OidcConfig` documents why a request-derived origin is refused — a
     * hostile `Host`/`X-Forwarded-Host` would otherwise pick the URL, and here that URL is
     * one a reader is invited to paste into an agent's config alongside a live API key.
     * Unset, the card renders the same `{host}/mcp` placeholder the marketing site uses
     * rather than guessing.
     */
    private fun mcpConfigJson(): String =
        """
        {
          "datapipelines": {
            "url": "${mcpUrl()}",
            "headers": { "${ApiKeyCredential.HEADER}": "${ApiKeyCredential.KEY_PREFIX}…" }
          }
        }
        """.trimIndent()

    private fun mcpUrl(): String {
        val base =
            authProperties.baseUrl
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotEmpty() }
        return (base ?: HOST_PLACEHOLDER) + ApiKeyCredential.MCP_PATH
    }

    private companion object {
        /** What `site/add-mcp-server.html` already shows when the origin is not configured. */
        const val HOST_PLACEHOLDER = "{host}"
    }
}
