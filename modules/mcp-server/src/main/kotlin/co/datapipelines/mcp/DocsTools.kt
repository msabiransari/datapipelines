package co.datapipelines.mcp

import co.datapipelines.mcp.docs.DocArea
import co.datapipelines.mcp.docs.DocSet
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.modelcontextprotocol.spec.McpSchema

/**
 * `docs_list` (mcp-server.md §6.2.40) — the served manual's catalog, as a TOOL (120, owner
 * ruling R3; the by-area shape is the 242a record §4). Permission: `docs.read`.
 *
 * Without `area`: the core and every area's entry point — the guide when the area has one,
 * else its first reference — each carrying the rest of the area's documents nested under
 * `references`, so the top-level list stays the short routing table and nothing is invisible.
 * With `area`: that area's documents flat. A document over the [DocSet.BUDGET_CHARS] response
 * budget is flagged `over_budget` — it is served by section only.
 *
 * The `read` floor is the [CalculatorsListTool] reasoning: the answer is a property of the
 * BUILD — the manual this deployment ships — identical for every caller, every key and every
 * workspace.
 */
class DocsListTool(
    private val docSet: () -> DocSet,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "docs_list",
            description =
                "The served manual's catalog: the operating core (core) first, then every area's entry point " +
                    "— the area guide, or its tools reference where no guide exists yet — with each area's " +
                    "other documents nested under references (name, title, purpose, size). Optional area " +
                    "filter (pipelines, executions, templates, transforms, datasources, lake, endpoints, core) " +
                    "returns that area's documents flat. Documents flagged over_budget are served by section " +
                    "only. Read-only.",
            schema =
                """
                {
                  "type": "object",
                  "properties": {
                    "area": {"type": "string", "enum": ["core", "pipelines", "executions", "templates", "transforms", "datasources", "lake", "endpoints"], "description": "Return only this area's documents, flat."}
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val docSet = docSet()
        val areaWire = args.string("area")
        if (areaWire == null) {
            return docSet.areas.map { area -> entry(docSet.entryPoint(area)!!, docSet) }
        }
        val area =
            DocArea.entries.firstOrNull { it.wire == areaWire }
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Mcp.DOC_NOT_FOUND,
                    message = "No documents area '$areaWire'.",
                    details =
                        mapOf(
                            "reason" to "unknown_area",
                            "area" to areaWire,
                            "known_areas" to DocArea.entries.map { it.wire },
                        ),
                )
        return docSet.byArea(area).map { entry(it, docSet) }
    }

    private fun entry(
        doc: co.datapipelines.mcp.docs.Doc,
        set: DocSet,
    ): Map<String, Any?> =
        buildMap {
            put("name", doc.name)
            put("area", doc.area.wire)
            put("layer", doc.layer.name.lowercase())
            put("title", doc.title)
            put("purpose", doc.purpose)
            put("chars", doc.chars)
            put("over_budget", doc.overBudget)
            put("sections", doc.sections.map { mapOf("id" to it.id, "title" to it.title, "chars" to it.markdown.length) })
            if (doc === set.entryPoint(doc.area)) {
                put(
                    "references",
                    set
                        .byArea(doc.area)
                        .filter { it !== doc }
                        .map { reference(it) },
                )
            }
        }

    private fun reference(doc: co.datapipelines.mcp.docs.Doc): Map<String, Any?> =
        mapOf(
            "name" to doc.name,
            "layer" to doc.layer.name.lowercase(),
            "title" to doc.title,
            "purpose" to doc.purpose,
            "chars" to doc.chars,
            "over_budget" to doc.overBudget,
        )
}

/**
 * `docs_get` (mcp-server.md §6.2.41) — the served manual's retrieval, with the by-section shape
 * of the 242a record §4. Permission: `docs.read`.
 *
 * The whole-document form stays for compatibility; a document over the response budget is
 * served by section only — the no-section call is refused with `mcp.doc_not_found`,
 * `details.reason: "document_over_budget"` and the section list. A section answer is
 * `{name, title, section, markdown}` plus `next` when the section itself exceeded the budget
 * and was split at its inner headings; `next` names the continuation id to pass back as
 * `section`. One-release aliases (skill, authoring-playbook, …) resolve to their successors and
 * the response carries the NEW name.
 *
 * An unknown name is `mcp.doc_not_found` with the catalogued names in the detail — the
 * tool-surface answer to the resource read's RESOURCE_NOT_FOUND, which is a protocol-level
 * error a tool result cannot carry (§9.2 errors are content, with a §13 code).
 */
class DocsGetTool(
    private val docSet: () -> DocSet,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "docs_get",
            description =
                "One document of the served manual: `name` is `core` for the operating core, an area guide " +
                    "name (pipelines-authoring, templates, transforms, lake, endpoints) or a reference name " +
                    "from docs_list. Optional `section`: a section id from docs_list, for the by-section read " +
                    "large documents require (they answer whole-document calls with their section list); a " +
                    "response carrying `next` continues at that id. Unknown names list the catalogued ones. " +
                    "Read-only.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["name"],
                  "properties": {
                    "name": {"type": "string", "description": "The document name: `core` for the operating core, or a name from docs_list, e.g. pipelines-authoring."},
                    "section": {"type": "string", "description": "A section id from docs_list (or a `next` continuation id) for the by-section read."}
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val docSet = docSet()
        // The `.md` tolerance is DocSet.resolve's own: the bare form is canonical, the
        // suffixed form is what someone copying a file name types.
        val requested = args.requiredString("name")
        val doc =
            docSet.resolve(requested)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Mcp.DOC_NOT_FOUND,
                    message = "No doc named '$requested'.",
                    details = mapOf("name" to requested, "known_docs" to docSet.docs.map { it.name }),
                )
        val sectionArg = args.string("section")
        if (sectionArg == null) {
            if (doc.overBudget) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Mcp.DOC_NOT_FOUND,
                    message =
                        "'${doc.name}' is ${doc.chars} characters — over the ${DocSet.BUDGET_CHARS}-character " +
                            "response budget, so it is served by section only. Pass one of its section ids.",
                    details =
                        mapOf(
                            "reason" to "document_over_budget",
                            "name" to doc.name,
                            "sections" to doc.sections.map { mapOf("id" to it.id, "title" to it.title, "chars" to it.markdown.length) },
                        ),
                )
            }
            return mapOf("name" to doc.name, "title" to doc.title, "area" to doc.area.wire, "markdown" to doc.markdown)
        }
        val parts = sectionParts(doc)
        val part = parts.firstOrNull { it.id == sectionArg } ?: throw sectionNotFound(doc, sectionArg)
        val response =
            linkedMapOf(
                "name" to doc.name,
                "title" to doc.title,
                "section" to part.id,
                "markdown" to part.markdown,
            )
        parts.lastOrNull()?.let { last ->
            if (part.id != last.id) {
                val index = parts.indexOfFirst { it.id == part.id }
                response["next"] = parts[index + 1].id
            }
        }
        return response
    }

    /** Every answerable part of [doc]: its sections, each pre-split to the budget. */
    private fun sectionParts(doc: co.datapipelines.mcp.docs.Doc): List<co.datapipelines.mcp.docs.MarkdownSections.Section> =
        doc.sections.flatMap { section ->
            co.datapipelines.mcp.docs.MarkdownSections
                .splitToBudget(section, DocSet.BUDGET_CHARS)
        }

    private fun sectionNotFound(
        doc: co.datapipelines.mcp.docs.Doc,
        section: String,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Mcp.DOC_NOT_FOUND,
            message = "No section '$section' in '${doc.name}'.",
            details =
                mapOf(
                    "reason" to "unknown_section",
                    "name" to doc.name,
                    "section" to section,
                    "known_sections" to doc.sections.map { it.id },
                ),
        )
}

/**
 * Both docs tools, in catalog order (120/R3) — the production bean and the wiring fixtures
 * append them as one, the [EndpointsTools] / [LakeTableTools] / [SemanticsTools] pattern.
 */
object DocsTools {
    fun all(docSet: () -> DocSet): List<McpTool> = listOf(DocsListTool(docSet), DocsGetTool(docSet))
}
