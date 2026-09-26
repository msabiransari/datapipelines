package co.datapipelines.mcp

import co.datapipelines.mcp.docs.DocContext
import co.datapipelines.mcp.docs.DocErrorCatalog
import co.datapipelines.mcp.docs.DocRenderer
import co.datapipelines.mcp.docs.DocSet

/**
 * Builds the served document set OUTSIDE Spring — the harness `docsExport` and the DocSet
 * guards share (the `SkillToolsDoc` precedent, moved up a level: the set is more than the
 * tools file now).
 *
 * The tool instances come from [realShippedTools] (the REAL `@Bean` method's output, mocked
 * collaborators — the bean method itself is the only tool list under test) and the render
 * context from [DocContext.DEFAULTS], the shipped configuration. The one piece test machinery
 * cannot reach is `web`'s `ApiErrorDocCatalog` — the [DocErrorCatalog] implementation lives
 * with `ApiErrorCatalog` in `web`, which this module must not compile against (§5.8) — so the
 * catalog is injectable: `docsExport` reflects it off the classpath this task assembles and
 * FAILS when absent; the guards pass [FixtureDocErrorCatalog], whose rows are shapes only.
 * The narrative documents — everything the golden test pins — do not reach the error catalog
 * at all.
 */
object SkillDocsExport {
    /**
     * The catalog class `docsExport` requires on its classpath. Reflection, because the main
     * class must not reference `web` types; the task declares the `:modules:web:classes`
     * dependency that puts it there, and a missing class is a loud failure, never a fallback.
     */
    const val WEB_CATALOG_CLASS: String = "co.datapipelines.web.api.ApiErrorDocCatalog"

    fun render(
        context: DocContext = DocContext.DEFAULTS,
        errorCatalog: DocErrorCatalog = webErrorCatalog(),
        tools: List<McpTool> = realShippedTools(),
    ): DocSet = DocRenderer(context, errorCatalog, tools).render()

    /** Loads `web`'s implementation off the execution classpath — no `web` types at compile time. */
    private fun webErrorCatalog(): DocErrorCatalog =
        try {
            val clazz = Class.forName(WEB_CATALOG_CLASS)
            clazz.getDeclaredConstructor().newInstance() as DocErrorCatalog
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "$WEB_CATALOG_CLASS is not on the classpath — run the export through " +
                    ":modules:mcp-server:docsExport, which assembles web's classes; the exported " +
                    "error-code reference must carry the real §13 statuses and messages, never a fixture",
                e,
            )
        }
}
