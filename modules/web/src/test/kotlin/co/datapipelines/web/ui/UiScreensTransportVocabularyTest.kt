package co.datapipelines.web.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File
import kotlin.text.RegexOption.IGNORE_CASE

/**
 * The ui-screens transport vocabulary guard (#668): the sidebar is never the home of the REST
 * tree/search routes. #554 moved the family trees into the resource browser; #588 named the
 * control; #654 reworded the transport sentences that still said the sidebar uses the REST
 * tree/search routes. This guard fails when a sentence outside the Change Log and outside a
 * dated ruling quote names the sidebar as that transport again — #654's F1 grep plus the
 * keep-list mechanism — so the next control rename cannot leave the same residue.
 *
 * Two shapes, both case-insensitive:
 *
 *   - SIDEBAR_USES — `sidebar uses`, #654's F1 grep verbatim.
 *   - SIDEBAR_HOME — `sidebar`, up to 60 characters that are neither `.` nor `|`, then
 *     `REST tree` or `tree/search`: the two nouns every transport sentence for the trees
 *     has used.
 *
 * Lines inside a Change Log section are skipped by docs-audit's heading flip — a heading line
 * whose text matches `change\s*log` turns the skip on until the next heading — copied here
 * verbatim so the two guards agree on what counts as history. Every other matching line must
 * be admitted by a KEEP_LIST entry, a dated quote or a named exception with its reason and
 * section; an entry that admits no line is STALE and fails the suite, so the keep-list cannot
 * quietly grow hairs nothing justifies any more.
 *
 * The keep-list seeds EMPTY at the guard's base 5e1326773: the sweep found zero matching
 * lines outside the Change Log, and the dated ruling quotes (the owner's #350/#398 paragraphs)
 * match neither shape. The Change Log row of #466 (v1.140, "The sidebar has read the REST
 * tree/search routes since #460.") DOES match SIDEBAR_HOME and stays out of the scan by the
 * flip alone — the self-check asserts the matcher sees it, proving the flip, not the regex,
 * is what keeps it out.
 *
 * The scan floor (3,000 lines; the document is 3,316 at the base) and the flip-seen check
 * keep the scan itself honest. The matcher self-check carries the pre-#654 transport
 * sentences VERBATIM from aedfaa6bb, the document before #654's rewording. There are six,
 * not the five a `sidebar uses` grep counts: aedfaa6bb:1187, the §4.4 template-reference
 * picker note, matches only SIDEBAR_HOME and was reworded in the same change.
 */
class UiScreensTransportVocabularyTest {
    @Test
    fun `no line outside the change log names the sidebar as the REST tree or search transport`() {
        val lines = File(repoRoot(), DOC).readLines()
        println(
            "ui-screens transport scan: ${lines.size} lines (floor $LINE_FLOOR), " +
                "keep entries ${KEEP_LIST.size} (seeded empty at 5e1326773)",
        )
        if (lines.size < LINE_FLOOR) {
            fail("the scan read only ${lines.size} lines of $DOC (floor $LINE_FLOOR) — the read is broken")
        }
        val violations = mutableListOf<String>()
        val entryHits = mutableMapOf<String, Int>()
        var inChangeLog = false
        var changeLogSections = 0
        lines.forEachIndexed { index, line ->
            if (HEADING.containsMatchIn(line)) {
                inChangeLog = CHANGE_LOG_HEADING.containsMatchIn(line)
                if (inChangeLog) changeLogSections += 1
            }
            if (inChangeLog) return@forEachIndexed
            if (!SIDEBAR_USES.containsMatchIn(line) && !SIDEBAR_HOME.containsMatchIn(line)) return@forEachIndexed
            val admitted = KEEP_LIST.filter { it.first.containsMatchIn(line) }
            if (admitted.isEmpty()) {
                violations += "$DOC:${index + 1}: ${line.trim().take(160)}"
            } else {
                admitted.forEach { entryHits.merge(it.first.pattern, 1, Int::plus) }
            }
        }
        KEEP_LIST.filter { (entryHits[it.first.pattern] ?: 0) == 0 }.forEach {
            violations += "STALE keep entry (admits no line): ${it.first.pattern} — ${it.third}"
        }
        if (changeLogSections == 0) {
            violations += "the change-log flip never turned on — the scan cannot tell history from the spec"
        }
        if (violations.isNotEmpty()) {
            fail(
                "${violations.size} ui-screens transport violation(s); a line outside the Change Log " +
                    "that names the sidebar as the REST tree/search transport is a residue to reword " +
                    "or a dated quote to admit with its reason:\n" + violations.joinToString("\n"),
            )
        }
    }

    @Test
    fun `the matcher itself sees the shapes a future miss would take`() {
        for ((origin, sentence) in NOT_ADMITTED) {
            if (!SIDEBAR_USES.containsMatchIn(sentence) && !SIDEBAR_HOME.containsMatchIn(sentence)) {
                fail("the matcher cannot see a pre-#654 transport sentence ($origin): $sentence")
            }
            if (KEEP_LIST.any { it.first.containsMatchIn(sentence) }) {
                fail("no keep entry may admit a pre-#654 transport sentence ($origin): $sentence")
            }
        }
        for (line in LIVE_SIDEBAR_LINES) {
            val matches = SIDEBAR_USES.containsMatchIn(line) || SIDEBAR_HOME.containsMatchIn(line)
            if (matches && KEEP_LIST.none { it.first.containsMatchIn(line) }) {
                fail("a live sidebar line matches a shape no keep entry admits: ${line.trim().take(160)}")
            }
        }
        for (line in CHANGE_LOG_FIXTURES) {
            if (!SIDEBAR_USES.containsMatchIn(line) && !SIDEBAR_HOME.containsMatchIn(line)) {
                fail("the change-log fixture must match a shape — the flip, not the regex, keeps it out")
            }
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
    }

    private companion object {
        private const val DOC = "docs/ui-screens.md"

        /** The document is 3,316 lines at the guard's base 5e1326773. */
        private const val LINE_FLOOR = 3_000

        private val SIDEBAR_USES = Regex("sidebar uses", setOf(IGNORE_CASE))
        private val SIDEBAR_HOME = Regex("sidebar[^.|]{0,60}(REST tree|tree/search)", setOf(IGNORE_CASE))
        private val HEADING = Regex("^#{1,6}\\s")
        private val CHANGE_LOG_HEADING = Regex("change\\s*log", setOf(IGNORE_CASE))

        /**
         * Kept lines: a line regex, its reason and the section it lives in. Seeded EMPTY at
         * 5e1326773 — the base sweep found zero matching lines outside the Change Log. A future
         * dated quote gets an entry here with its origin; a shape is never weakened to admit one.
         */
        private val KEEP_LIST: List<Triple<Regex, String, String>> = emptyList()

        /** The pre-#654 transport sentences, verbatim from aedfaa6bb's docs/ui-screens.md. */
        private val NOT_ADMITTED: List<Pair<String, String>> =
            listOf(
                // aedfaa6bb:774 (§4.3 Pipelines htmx row)
                "aedfaa6bb:774 (§4.3 Pipelines htmx row)" to
                    "| htmx | the search input (`#pipeline-filter-q`, `input changed delay:300ms, search`, `hx-sync=\"this:replace\"`" +
                    ") and the shared §5 pager re-fetch ONLY the list into its stable root `#pipeline-list-wrapper` (`GET /partials/p" +
                    "ipelines`, `outerHTML`); the sidebar uses REST tree/search (§3.4) |",
                // aedfaa6bb:794 (§4.3 Pipelines intro)
                "aedfaa6bb:794 (§4.3 Pipelines intro)" to
                    "  request answers the catalog list). The sidebar uses the REST tree/search routes (§3.4,",
                // aedfaa6bb:1187 (§4.4 picker note; SIDEBAR_HOME only)
                "aedfaa6bb:1187 (§4.4 picker note; SIDEBAR_HOME only)" to
                    "- **Template reference (§9.4 there):** a node's template is a read-only reference display — `acme/finance/monthl" +
                    "y_revenue @ v3`, one line with the FULL reference on `title`, in the Details tab's key/value grid **and** in the" +
                    " server-rendered `partials/pipeline-node-sql` **and** in the `template-missing` empty state. **There is no templ" +
                    "ate picker on this screen**; template selection happens through pipeline JSON authoring, import and MCP. **If a " +
                    "picker is ever added, it reuses the sidebar's REST tree component (§3.4, rest-api §24) — it does not get its own" +
                    " client-side tree.** (§4.6's server-rendered prefix fragment, which this sentence named before, was removed with" +
                    " #466.)",
                // aedfaa6bb:1334 (§4.6 Templates htmx row)
                "aedfaa6bb:1334 (§4.6 Templates htmx row)" to
                    "| htmx | the search input and the two filter selects re-fetch ONLY the list into its stable root `#template-list" +
                    "-wrapper` (`GET /partials/templates`, `outerHTML`); the sidebar uses REST tree/search (§3.4) |",
                // aedfaa6bb:1346 (§4.6 Templates intro)
                "aedfaa6bb:1346 (§4.6 Templates intro)" to
                    "  request answers the catalog list). The sidebar uses the REST tree/search routes (§3.4,",
                // aedfaa6bb:2437 (§4.23 Parameter sets)
                "aedfaa6bb:2437 (§4.23 Parameter sets)" to
                    "The sidebar uses its REST tree/search routes (§3.4, rest-api §24), under the same read lens.",
            )

        /** The live sidebar lines outside the Change Log at 5e1326773, verbatim. */
        private val LIVE_SIDEBAR_LINES: List<String> =
            listOf(
                // 5e1326773:173
                "**Path ownership (#659).** A section or family owns `/<section>` and `/<section>/…`, the same under `/overview/`" +
                    "; nothing else. `AppNav.owns` defines the segment boundary for the server, including off-rail breadcrumbs; the l" +
                    "ayout computes the active section once through `AppNav.activeFor` and compares by equality. `shell.js`, `sidebar" +
                    "-state.mjs` and `persistent-navigation.mjs` mirror that boundary after navigation. `/pipelinesx` activates no it" +
                    "em, names no crumb and opens no browser; `/dashboards` belongs only to Dashboards. Guards: `AppNavTest`, `ShellR" +
                    "enderTest`, `shell.test.mjs` and `sidebar-state.test.mjs`.",
                // 5e1326773:301
                "| `dp-tree-open:<workspace>`, `dp-tree-panel:<workspace>:<family>` | the old accordion's open family | — | retir" +
                    "ed: removed on sight by `tree/sidebar.mjs` |",
                // 5e1326773:386
                "  sprite with the server-rendered sidebar levels (#466). A leaf row keeps the chevron SLOT, empty, so its glyph " +
                    "and",
                // 5e1326773:778
                "| JS | none of its own — `tree/sidebar.mjs` owns **Browse folders** (`data-nav-tree-reveal`), which shows the re" +
                    "source browser's Pipelines tree (the Browse resources drawer at 760px and below) and puts focus in its search; s" +
                    "ince #554 it leaves the menu's collapse alone |",
                // 5e1326773:781
                "**Owner ruling 2026-10-02 (#350):** with the tree in the sidebar, the page keeps the catalog the",
                // 5e1326773:1174
                "- **Six tabs, one page (#349, spec §4.1):** Flow (the graph + the dock) is the default; Overview, Parameters, Ru" +
                    "ns, Usage and Versions are the reading surfaces. A tab change is NAVIGATION ONLY — it swaps no execution state a" +
                    "nd cancels no run; the URL carries `tab=` (absent for Flow), and since #402 each user tab change PUSHES an entry" +
                    " of the workspace's own (`workspace/history.js`, §3.2) — Back/Forward re-select the tab in page; re-selecting th" +
                    "e active tab mints nothing. The tab set is the closed set the server resolves (unknown → flow; `runs` without th" +
                    "e execution read → flow BEFORE any runs read), mirrored client-side in `tabs.js`. **Runs renders — and lazily fe" +
                    "tches — only for a caller with the execution read**, and its absence costs nothing: a promoter's strip is five t" +
                    "abs and their dock is Node Details alone, proven on the wire (zero execution requests across every tab). Runs an" +
                    "d Usage load ONCE, on the tab's first open, generation-stamped. The Overview pane is client-rendered from the pa" +
                    "ge's ONE lens-filtered workspace block (the admitted history, the record facts, the datasource dialect map acros" +
                    "s every admitted body); the Parameters tab composes the viewed version's declared schema in the house table with" +
                    " the RUN OVERRIDES as a separate section, reachable beside Execute through the topbar's Run parameters button; t" +
                    "he Versions tab composes the explorer's fragment (per-row verbs, dialogs into `#pe-dialog` with `from=editor` — " +
                    "a success reloads the whole workspace). The settings sidebar is GONE — its description and parameters moved to O" +
                    "verview and Parameters, and no second sidebar renders (§4.1's \"no duplicate full description/settings sidebar\"" +
                    ").",
                // 5e1326773:1188
                "- **The settings sidebar is withdrawn (#349, spec §4.1) — 141's resize contract went with it.** 141 made the 280" +
                    "px settings sidebar the user's width (a `splitter.js` handle, `--pe-sidebar-w` on `<html>`, the `dp.pane.editor-" +
                    "sidebar` key, a 220px floor and a 50vw ceiling, a drawer below 1024px). The owner's workspace ruling of 2026-09-" +
                    "30 withdraws the PANE: the graph owns the available width, and the description and the declared parameters live " +
                    "in the Overview and Parameters tabs (the six-tabs bullet above; [Pipeline Editor §4.3](pipeline-editor.md)). `pe" +
                    "-sidebar` and the `editor-sidebar` splitter occur zero times in `editor.html`, `pipeline-editor.css` and `init.j" +
                    "s`; `PipelineWorkspaceLayoutBrowserTest` pins their absence (no settings sidebar, no second splitter handle), re" +
                    "-reads the dock's drag contract as the composition's control and asserts the tab strip stays reachable while the" +
                    " dock is collapsed. The 141 sidebar-resize suite is deleted with the pane; the dock's own resize (the bullet abo" +
                    "ve) is unchanged and remains `PipelineEditorDockResizeBrowserTest`'s. Since #535 the module carries no wiring fo" +
                    "r the withdrawn pane either: `static/js/splitter.js` wires the dock and the explorer tree only.",
                // 5e1326773:1338
                "| JS | the create modal's lifecycle (`/js/template-create-modal.js`: open/close, inline refusal, dialect-conditi" +
                    "onal-on-type, the script blocks); none of its own otherwise — `tree/sidebar.mjs` owns **Browse folders** (`data-" +
                    "nav-tree-reveal`), which shows the resource browser's Templates tree and puts focus in its search |",
                // 5e1326773:1341
                "**#398 (the #396 ruling's first reuse of the workspace pattern):** with the tree in the sidebar,",
                // 5e1326773:1398
                "#### 4.6a The resource browser (§3.4's Templates panel; the sidebar tree before #554)",
            )

        /** The one in-Change-Log match at 5e1326773 (#466's v1.140 row) — the flip's fixture. */
        private val CHANGE_LOG_FIXTURES: List<String> =
            listOf(
                // 5e1326773:3171 — the v1.140 #466 row, skipped by the flip only
                "| 2026-10-15 | v1.140 | #466 (lane 466b) the sidebar's server-rendered tree removed | **§4.3, §4.6** (and the da" +
                    "shboards, visualizations and parameter-sets catalogs): each catalog partial serves its flat list alone — the `sc" +
                    "ope=nav` presentation, the `prefix` tree levels, the `DP-Nav-Stamp` header and the five `*-tree-level` fragments" +
                    " are gone (owner ruling 2026-10-10, option A); `GET /partials/visualizations/tree` and `GET /partials/dashboards" +
                    "/tree` are removed, `GET /partials/parameter-sets/tree` keeps its path for the catalog. A stale `scope` or `pref" +
                    "ix` is ignored and answers the catalog list. The sidebar has read the REST tree/search routes since #460. **§4.7" +
                    "**: the workspace's foreign-response guard is the REST payload's `view_token`, not the stamp. The two catalog ht" +
                    "mx rows stop naming a `scope=page` the pages never sent; the template picker note points at the REST tree compon" +
                    "ent. **§3.4 icons**: the `file-code` glyph's only users were the deleted pipeline and template levels, so it lea" +
                    "ves the sprite and the manifest's subset (`IconSpriteAuditTest` keeps the subset exact). |",
            )
    }
}
