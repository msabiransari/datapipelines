package co.datapipelines.mcp.docs

import co.datapipelines.mcp.McpTool
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Assembles the [DocSet] — the render is a pure function of the build (the packaged narrative
 * resources), the boot configuration ([DocContext]) and the shipped catalogs ([McpToolCatalog]
 * through the tool instances, `PipelineErrorCodes` through [DocErrorCalculator], the calculator
 * registry), run once at boot (record §3.3). No template engine: a Kotlin builder for the
 * generated parts and exact-key `${substitution}` for the narrative.
 *
 * ## Failing loudly
 *
 * Every way the render can be wrong is a render-time failure, never a degraded serve: a
 * narrative resource without (or with extra) front matter, an unknown placeholder key, a
 * tool mapping to a reserved area, two documents with one name. An empty manual that an agent
 * would read as "there is no guidance here" is not a mode this class has — the same
 * discipline `SkillDocs` held for the packaged bytes.
 *
 * @param errorCatalog the §13 projection port (`DocErrorCatalog`, implemented by `web` in the
 *   assembled application — the 068/074 pattern).
 */
class DocRenderer(
    private val context: DocContext,
    private val errorCatalog: DocErrorCatalog,
    private val tools: List<McpTool>,
) {
    fun render(): DocSet {
        val narrative =
            loadResources()
                .map { (file, text) -> renderNarrative(file, text) }
                .sortedWith(
                    compareBy(
                        { doc -> ORDER.indexOf(doc.name).let { i -> if (i >= 0) i else ORDER.size } },
                        { doc -> doc.name },
                    ),
                )
        val generated = generate()
        val docs = narrative + generated
        val duplicates = docs.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "docs: duplicate document name(s) in the rendered set: $duplicates" }
        return DocSet(docs)
    }

    /** Front matter parse, placeholder substitution, section cutting — one narrative resource. */
    private fun renderNarrative(
        file: String,
        text: String,
    ): Doc {
        val frontMatter = FrontMatter.parse(file, text)
        val body = substitute(file, frontMatter.body)
        val title =
            body
                .lineSequence()
                .firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")
                ?.trim()
                ?: error("docs: $file has no H1 — the title comes from the document's own heading")
        val area =
            DocArea.entries.firstOrNull { it.wire == frontMatter.fields.getValue("area") }
                ?: error("docs: $file declares unknown area '${frontMatter.fields.getValue("area")}'")
        val layer =
            DocLayer.entries.firstOrNull { it.name.lowercase() == frontMatter.fields.getValue("layer") }
                ?: error("docs: $file declares unknown layer '${frontMatter.fields.getValue("layer")}'")
        return Doc(
            name = frontMatter.name,
            area = area,
            layer = layer,
            title = title,
            purpose = frontMatter.fields.getValue("purpose"),
            markdown = body,
            sections = MarkdownSections.cut(body),
        )
    }

    /** Exact-key substitution from [DocContext]; the failure modes fail the render. */
    private fun substitute(
        file: String,
        body: String,
    ): String {
        var out = body
        for ((key, value) in context.placeholders) {
            out = out.replace("\${$key}", value)
        }
        val unknown =
            PLACEHOLDER
                .findAll(out)
                .map { it.groupValues[1] }
                .filter { it !in context.placeholders && it !in PROSE_LITERALS }
                .distinct()
                .toList()
        require(unknown.isEmpty()) {
            "docs: $file names placeholder(s) $unknown that no DocContext field supplies — add the field " +
                "or fix the key (the dollar-brace mention of a parameter is the interpolation syntax's own)"
        }
        return out
    }

    /** The generated half of the set: the error codes, the calculator catalog, the eight tools references. */
    private fun generate(): List<Doc> =
        listOf(ErrorCodesReferenceGenerator.render(errorCatalog), CalculatorsReferenceGenerator.render()) +
            DocArea.entries.map { area -> ToolsReferenceGenerator.render(area, tools) }

    private fun loadResources(): List<Pair<String, String>> {
        val resources =
            PathMatchingResourcePatternResolver(DocRenderer::class.java.classLoader)
                .getResources("classpath*:skill/*.md")
                .filter { resource ->
                    (resource.filename ?: "").let { name -> name.isNotEmpty() && !name.endsWith("index.md") }
                }.map { resource ->
                    val filename = resource.filename ?: error("docs: a packaged skill resource has no filename")
                    filename to resource.inputStream.use { it.readBytes().decodeToString() }
                }
        require(resources.isNotEmpty()) {
            "docs: no narrative resources under classpath skill/ — the mcp-server jar's resource packaging is broken"
        }
        return resources
    }

    /**
     * The document name is the file's name minus `.md` — the front matter carries no name, so
     * a file and its catalog entry cannot disagree about what answers for it.
     */
    private class FrontMatter(
        val name: String,
        val fields: Map<String, String>,
        val body: String,
    ) {
        companion object {
            /** The three keys the record §3.1 gives every narrative document; nothing else. */
            private val KEYS = setOf("area", "layer", "purpose")

            fun parse(
                file: String,
                text: String,
            ): FrontMatter {
                require(text.startsWith(FM_OPEN)) {
                    "docs: $file does not open with front matter (--- area/layer/purpose ---)"
                }
                val end = text.indexOf(FM_CLOSE, FM_DELIMITER_LEN)
                require(end > 0) { "docs: $file front matter is never closed" }
                val lines = text.substring(FM_DELIMITER_LEN, end).lines()
                val fields =
                    lines.associate { line ->
                        val idx = line.indexOf(':')
                        require(idx > 0) { "docs: $file front matter line is not key: value — '$line'" }
                        line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                    }
                require(fields.keys == KEYS) {
                    "docs: $file front matter carries ${fields.keys} — exactly $KEYS is the contract"
                }
                fields.forEach { (key, value) -> require(value.isNotBlank()) { "docs: $file front matter key '$key' is blank" } }
                val body = text.substring(end + FM_DELIMITER_LEN).removePrefix("\n")
                return FrontMatter(file.removeSuffix(".md"), fields, body)
            }
        }
    }

    private companion object {
        /** The front-matter fence: an opening `---` line, its closing `\n---`, and the shared length. */
        const val FM_OPEN = "---\n"
        const val FM_CLOSE = "\n---"
        const val FM_DELIMITER_LEN = 4

        /** `` `${key}` `` — the typed substitution's own spelling. */
        val PLACEHOLDER = Regex("\\$\\{([a-z][a-z0-9_]*)\\}")

        /**
         * The prose's own mention of the Freemarker interpolation syntax — SKILL.md's "never
         * `` `${name}` ``, for a declared parameter" — which matches the placeholder grammar
         * and is not one. Spelled out so an unknown-key failure names a real typo instead of
         * the manual's subject matter; a second literal is a conscious addition.
         */
        val PROSE_LITERALS = setOf("name")

    /**
     * The narrative documents' serving order: the core first, then the area guides, then the
     * references — `docs_list`'s order (the guides in catalog order as of 242b's split; a
     * reference name not listed here sorts alphabetically after them). Generated documents are
     * appended by [generate] in their own stable order; a name here sorts by position,
     * anything else after it.
     */
    val ORDER: List<String> =
        listOf(
            DocSet.CORE_NAME,
            "pipelines",
            "executions",
            "templates",
            "transforms",
            "datasources",
            "lake",
            "endpoints",
        )
    }
}
