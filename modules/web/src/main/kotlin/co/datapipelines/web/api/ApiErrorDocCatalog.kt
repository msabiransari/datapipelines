package co.datapipelines.web.api

import co.datapipelines.mcp.docs.DocErrorCatalog

/**
 * The served manual's error-code source (242a): the [DocErrorCatalog] port the renderer codes
 * against, answered from [ApiErrorCatalog] — the ONE projection of pipeline-contract §13's
 * status and `user_message` columns. No table is copied here: status, message and the §13
 * family anchor all resolve through the same functions the REST envelope uses, so the manual
 * and the API cannot answer differently.
 *
 * Declared as a bean by `web`'s [co.datapipelines.web.config.DocsConfiguration] — the 068/074
 * pattern, because `mcp-server` codes against the port and must not depend on `web`.
 */
class ApiErrorDocCatalog : DocErrorCatalog {
    override fun describe(code: String): DocErrorCatalog.ErrorDocRow {
        val docUrl = ApiErrorCatalog.docUrl(code)
        require('#' in docUrl) { "docs: no §13 anchor derived for '$code' — the doc URL lost its fragment: $docUrl" }
        return DocErrorCatalog.ErrorDocRow(
            status = ApiErrorCatalog.statusFor(code).value(),
            familyAnchor = docUrl.substringAfter('#'),
            userMessage = ApiErrorCatalog.userMessageFor(code),
        )
    }
}
