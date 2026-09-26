package co.datapipelines.mcp.docs

/**
 * The rendered document set — the manual this deployment serves (record §3).
 *
 * Assembled ONCE at boot by [DocRenderer] from the narrative resources in the jar, the typed
 * [DocContext] and the generators over the application's own catalogs; immutable afterwards and
 * served from memory. The same instance answers the MCP tools (`docs_list`/`docs_get`), the
 * `datapipelines://docs/skill` resources and the public HTTP twin, so the surfaces cannot
 * answer differently — the property `SkillDocs`'s packaged bytes used to carry, one level up.
 */
class DocSet(
    val docs: List<Doc>,
) {
    private val byName: Map<String, Doc> = docs.associateBy { it.name }

    /**
     * The one-release aliases (record §4, owner ruling O4): every name the previous delivery
     * answered, mapped to its successor. `docs_get`, the resource read and the HTTP twin resolve
     * through [resolve], which answers with the NEW name in the response; the aliases go away
     * the release after.
     */
    private val aliases: Map<String, String> =
        mapOf(
            "skill" to CORE_NAME,
            "authoring-playbook" to "pipelines-authoring",
            "connecting" to "datasources-connecting",
            "dp-lake" to "lake",
            "error-codes" to "core-error-codes",
            "naming" to "pipelines-naming",
            "node-types" to "pipelines-node-types",
            "pipeline-schema" to "pipelines-schema",
            "tools" to "core-tools",
        )

    /** The core document — orientation, the universal rules, the area index. */
    val core: Doc by lazy { byName.getValue(CORE_NAME) }

    /**
     * The document [name] names — a flat document name, an alias of the previous delivery, or
     * either with a `.md` suffix (what someone copying a file name types). Null when nothing
     * answers: the caller renders its own surface's not-found.
     */
    fun resolve(name: String): Doc? = byName[name.removeSuffix(".md")] ?: aliases[name.removeSuffix(".md")]?.let { byName[it] }

    /** The document named [name], which must exist — the renderer's own lookups. */
    fun get(name: String): Doc = byName.getValue(name)

    /**
     * The entry point of [area]: its guide when one exists, else the area's first reference —
     * the record §4's "without `area`, the core and every area guide" made total for the areas
     * whose guides 242b has not written yet. Every area appears, so the top-level list stays
     * the routing table the handshake points at.
     */
    fun entryPoint(area: DocArea): Doc? {
        val inArea = docs.filter { it.area == area }
        if (area == DocArea.CORE) {
            return inArea.firstOrNull { it.layer == DocLayer.CORE }
        }
        return inArea.firstOrNull { it.layer == DocLayer.GUIDE } ?: inArea.firstOrNull()
    }

    /** Every document of [area], guide first — `docs_list`'s `area` filter. */
    fun byArea(area: DocArea): List<Doc> {
        val entry = entryPoint(area)
        val rest = docs.filter { it.area == area && it !== entry }
        return listOfNotNull(entry) + rest
    }

    /** The areas of the rendered set, catalog order — the top-level list's order. */
    val areas: List<DocArea> by lazy { DocArea.entries.filter { area -> docs.any { it.area == area } } }

    companion object {
        /** The core document's name — what the `skill` alias resolves to. */
        const val CORE_NAME: String = "core"

        /**
         * The response budget (owner ruling O2): one `docs_get` answer carries at most this
         * many characters of markdown, ≈ 6k tokens — under the 10,000-token client warning with
         * room for the JSON envelope. A document or section over it is served by section only,
         * and `docs_list` says so.
         */
        const val BUDGET_CHARS: Int = 24_000
    }
}

/** One rendered document. */
data class Doc(
    /** The flat name `docs_get` takes and the `/skill/<name>.md` URL carries. */
    val name: String,
    val area: DocArea,
    val layer: DocLayer,
    /** The document's own H1. */
    val title: String,
    /** One line: when to open it. */
    val purpose: String,
    /** The served markdown — front matter stripped, placeholders substituted. */
    val markdown: String,
    /** The `##` sections, cut and slugged at render time. */
    val sections: List<MarkdownSections.Section>,
) {
    val chars: Int get() = markdown.length

    /** True when the whole-document form is refused and only sections are served. */
    val overBudget: Boolean get() = chars > DocSet.BUDGET_CHARS
}

/** The three layers of the record's §3.1. */
enum class DocLayer {
    /** The core: orientation, the universal rules, the area index. */
    CORE,

    /** An area guide: the area's concepts, workflow, prerequisites, mistakes. */
    GUIDE,

    /** A reference: one operation, schema, error family — or the area's tools. */
    REFERENCE,
}
