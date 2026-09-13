package co.datapipelines.mcp

import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * The agent skill, as the server ships it (095 §C2/§C3/§D).
 *
 * The skill is `.agents/skills/datapipelines/` in the repository — `SKILL.md` plus the
 * files under `references/` — and `mcp-server`'s `processResources` packages that directory into the
 * jar under `skill/`. This object is the ONE reader of those packaged bytes; the MCP resource
 * (`datapipelines://docs/skill`) and the HTTP route (`GET /skill.md`) both serve what it
 * returns, so the two surfaces cannot answer differently.
 *
 * ## Why `skill/` and not `docs/`
 *
 * `web` packages the root `docs` markdown set and `DocsCatalog` fails fast at init on any doc
 * with no declared index group — a docs-only commit broke `main` on 2026-09-01 for exactly
 * that reason. `DocsCatalog` scans every packaged `docs` markdown file, so a skill file landing under
 * `docs/` would take the whole application context down. Its own directory owes no grouping
 * decision and is invisible to that scan.
 *
 * ## Failing loudly
 *
 * The content is immutable inside a jar, so it is read once and memoized (`DocsCatalog`'s
 * discipline). A missing `SKILL.md` is a broken build, not a degraded mode: it throws at
 * first use rather than serving an empty manual that an agent would read as "there is no
 * guidance here".
 */
object SkillDocs {
    /** The classpath directory `processResources` packages the skill into. */
    const val CLASSPATH_DIR: String = "skill"

    /** The reference name grammar — a file name under `references/`, minus `.md`. */
    private val REFERENCE_NAME = Regex("[a-z0-9][a-z0-9-]{0,63}")

    /** `SKILL.md` — the operating core. */
    val skill: String by lazy { load("$CLASSPATH_DIR/SKILL.md") }

    /** Every reference file, keyed by its name (the file name minus `.md`), alphabetically. */
    val references: Map<String, String> by lazy { loadReferences() }

    /**
     * The body of one reference, or null when [name] names none.
     *
     * A caller may pass either `templates` or `templates.md`: the bare form is canonical (it
     * is what `resources/list` advertises and what `SKILL.md`'s map prints), and the `.md`
     * form is what someone copying a file name types. Anything outside the name grammar —
     * a path separator, `..`, an absolute path — matches no key and is simply not found;
     * nothing here concatenates caller input into a path.
     */
    fun reference(name: String): String? = references[name.removeSuffix(".md")]

    /** A reference's own H1, used as its resource description. */
    fun title(name: String): String =
        reference(name)
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
            ?: name

    /**
     * The skill's doc catalog as `docs_list` returns it (120/R3): `skill` first, then every
     * reference in the order SKILL.md's own map lists them, carrying the map's one-line
     * "open this when…" purposes.
     *
     * Parsed from the packaged [skill] bytes rather than kept as a second table, so the tools
     * surface and the map the agent reads cannot be two things. A reference on disk with no
     * map line, or a map line with no file, throws here — and is a `SkillDistributionTest`
     * failure in the build, which asserts the same agreement in both directions.
     */
    val catalog: List<Entry> by lazy {
        val mapped = MAP_LINE.findAll(skill).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
        require(mapped.map { it.first }.toSet() == references.keys) {
            "skill: SKILL.md's reference map and the packaged references disagree " +
                "(map=${mapped.map { it.first }.sorted()}, files=${references.keys.sorted()})"
        }
        listOf(Entry(SKILL_NAME, heading(skill) ?: SKILL_NAME, SKILL_PURPOSE)) +
            mapped.map { (name, purpose) -> Entry(name, title(name), purpose) }
    }

    /** One `docs_list` row: the name `docs_get` takes, the doc's own H1, and when to open it. */
    data class Entry(
        val name: String,
        val title: String,
        val purpose: String,
    )

    /** The core's name in [catalog] and as `docs_get`'s argument — the resource URI's own word. */
    const val SKILL_NAME: String = "skill"

    private const val SKILL_PURPOSE: String =
        "The operating core — read it before authoring anything beyond a one-node pipeline."

    /** `- **`references/<name>.md`** — <purpose>` — one line of SKILL.md's reference map. */
    private val MAP_LINE = Regex("^- \\*\\*`references/([a-z0-9-]+)\\.md`\\*\\* — (.+?)\\s*$", RegexOption.MULTILINE)

    private fun heading(markdown: String): String? =
        markdown
            .lineSequence()
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()

    private fun loadReferences(): Map<String, String> =
        PathMatchingResourcePatternResolver(SkillDocs::class.java.classLoader)
            .getResources("classpath*:$CLASSPATH_DIR/references/*.md")
            .mapNotNull { resource ->
                val filename = resource.filename ?: return@mapNotNull null
                val name = filename.removeSuffix(".md")
                require(REFERENCE_NAME.matches(name)) {
                    "skill: packaged reference '$filename' does not match the reference name grammar"
                }
                name to resource.inputStream.use { it.readBytes().decodeToString() }
            }.sortedBy { it.first }
            .toMap()

    private fun load(path: String): String {
        val stream =
            SkillDocs::class.java.classLoader.getResourceAsStream(path)
                ?: error("skill: $path is not on the classpath — the mcp-server jar's skill packaging is broken")
        return stream.use { it.readBytes().decodeToString() }
    }
}
