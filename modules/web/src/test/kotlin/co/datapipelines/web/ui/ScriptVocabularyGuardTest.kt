package co.datapipelines.web.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File
import kotlin.text.RegexOption.IGNORE_CASE

/**
 * The script-node rename's whole-tree vocabulary guard (the script-node-rename lane, owner
 * ruling 2026-10-20): the product noun for the JSONata/JavaScript node is SCRIPT everywhere;
 * TRANSFORM survives ONLY in the senses this file's allowlist names.
 *
 * The scan walks every tracked text source under the repository root (never `build/`, never
 * `.git/` or `.pregate-logs/`) and fails on any line containing `transform` that neither
 *
 *   - matches one of the GLOBAL exemptions (the same line shape everywhere in the tree), nor
 *   - lives in a file matched by a [PATH_EXEMPTIONS] entry whose per-line regex admits it.
 *
 * Each entry carries its reason. An entry that no longer matches any line is a STALE entry and
 * fails the suite — the allowlist cannot quietly grow hairs that nothing justifies any more.
 * A scanned-file floor and a matcher self-check keep the scan itself honest.
 *
 * The senses, stated:
 *
 *   - **The design record** `transform-nodes design §N` — the record's file name is its date
 *     and the store cites the path; the citation is immutable.
 *   - **English** — "transformation/transformed/transforming/transformer(s)": the verb, the
 *     JCE cipher's `TRANSFORMATION`, the calculators ("pre-execution transformers"), the
 *     dashboards' `WebDashboardTransformer`; and, phrase by phrase, the bare verb in marketing
 *     copy (what dbt does, a demo manifest's preparation step, the login's Connect / Transform /
 *     Deliver).
 *   - **The rename's own name** — `TRANSFORM→SCRIPT` in the docs and the refusal messages.
 *   - **Removed-key machinery** — `datapipelines.transform.*`, `checkRemovedTransformKeys`,
 *     `REMOVED_TRANSFORM_KEY_SUFFIXES`, `transformLegacyKeysSet`: the refusal names the old
 *     keys BY NAME (§7, no alias).
 *   - **The visualization block** — a visualization pins a TEMPLATE; the pin's wire key is
 *     `transform` and the error code is `transform_binding_invalid`. The visualization module,
 *     its web half, its templates, its tests and the dashboards refresh stage keep that
 *     vocabulary by the owner's block decision. Elsewhere (the MCP schema and docs, §7.6, the
 *     skill) the block's key, `transform.template`/`transform.inputs`, its pin, its output and the
 *     refresh stage are admitted phrase by phrase, so the node noun in the same file stays red (#640).
 *   - **CSS** — the `transform:` property, the SVG `transform` attribute, the pan/zoom/drawer
 *     geometry notes and the shell's "transform and opacity only" motion.
 *   - **Frozen history** — the V33/V36/V47 migrations, the `chk_transform_blocks` pre-V50
 *     constraint name, the migration tests' pre-state rows, the research snapshots.
 *   - **The sample-data fence** — `site/demo/manifest-*.json`'s `transform` key names a
 *     dataset's derivation step for the fenced `scripts/sample-data*` builds.
 */
class ScriptVocabularyGuardTest {
    @Test
    fun `no line outside the allowlist carries the old noun`() {
        val scanned = scanTargets()
        if (scanned.size < SCAN_FLOOR) {
            fail("the scan saw only ${scanned.size} files (floor $SCAN_FLOOR) — the walk is broken")
        }
        val violations = mutableListOf<String>()
        val entryHits = mutableMapOf<String, Int>()
        for (file in scanned) {
            val relative = file.relativeTo(repoRoot()).invariantSeparatorsPath
            val entries = PATH_EXEMPTIONS.filter { it.first.containsMatchIn(relative) }
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!OLD_NOUN.containsMatchIn(line)) return@forEachIndexed
                val admitted =
                    GLOBAL_EXEMPTIONS.any { it.containsMatchIn(line) } ||
                        entries.any { it.second.containsMatchIn(line) }
                if (admitted) {
                    entries.forEach { entryHits.merge(it.first.pattern, 1, Int::plus) }
                } else {
                    violations += "$relative:${index + 1}: ${line.trim().take(160)}"
                }
            }
        }
        val stale = PATH_EXEMPTIONS.filter { (entryHits[it.first.pattern] ?: 0) == 0 }
        if (stale.isNotEmpty()) {
            violations += stale.map { "STALE allowlist entry (matches no line): ${it.first.pattern} — ${it.third}" }
        }
        if (violations.isNotEmpty()) {
            fail(
                "${violations.size} unclassified `transform` line(s); every line is either renamed, " +
                    "an allowlisted sense, or a miss:\n" + violations.joinToString("\n"),
            )
        }
    }

    @Test
    fun `the matcher itself sees the shapes a future miss would take`() {
        for (shape in listOf(
            "TRANSFORM nodes evaluate",
            "val transformPins",
            "type: \"TRANSFORM\"",
            "transform binding invalid",
            "the transform pin",
            "a transform node",
        )) {
            if (!OLD_NOUN.containsMatchIn(shape)) fail("the matcher cannot see: $shape")
        }
        for (allowed in listOf(
            "transform-nodes design §9.3",
            "a silent transformation",
            "pre-execution transformers",
            "TRANSFORM→SCRIPT node-type rename",
            "datapipelines.transform.pool-size",
            "checkRemovedTransformKeys",
            "TRANSFORM_BINDING_INVALID",
            "TRANSFORM_STAGE",
            "TRANSFORMATION = \"AES/GCM/NoPadding\"",
            "visualizations whose `transform.template` pins the version",
            "`transform.inputs`' keys",
        )) {
            if (!GLOBAL_EXEMPTIONS.any { it.containsMatchIn(allowed) }) {
                fail("a global exemption should admit: $allowed")
            }
        }
        for ((path, line) in ADMITTED) {
            if (!admittedByPath(path, line)) fail("a path entry should admit $path: $line")
        }
        for ((path, line) in NOT_ADMITTED) {
            if (admittedByPath(path, line) || GLOBAL_EXEMPTIONS.any { it.containsMatchIn(line) }) {
                fail("the node noun must stay red in $path: $line")
            }
        }
    }

    private fun admittedByPath(
        path: String,
        line: String,
    ): Boolean = PATH_EXEMPTIONS.any { (selector, admits) -> selector.containsMatchIn(path) && admits.containsMatchIn(line) }

    private fun scanTargets(): List<File> {
        val extensions =
            setOf("kt", "kts", "java", "md", "html", "js", "mjs", "json", "yml", "yaml", "sql", "properties", "toml", "sh", "txt", "xml")
        val excludedDirs = setOf("build", ".git", ".claude", ".pregate-logs", "node_modules", "out", "target")
        return repoRoot()
            .walkTopDown()
            .onEnter { dir -> dir.name !in excludedDirs }
            .filter { it.isFile && it.extension in extensions && it.length() < 4_000_000 }
            .filter { file ->
                val p = file.relativeTo(repoRoot()).invariantSeparatorsPath
                !p.startsWith("modules/visualization/") &&
                    !p.startsWith("docs/superpowers/") &&
                    !p.startsWith("static/vendor/") &&
                    !p.startsWith("modules/web/src/main/resources/static/vendor/") &&
                    !p.startsWith("scripts/sample-data") &&
                    !p.contains("/overview/") &&
                    !p.endsWith(".css")
            }.toList()
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
    }

    private companion object {
        private val OLD_NOUN = Regex("transform", setOf(IGNORE_CASE))

        /** The floor is the count of tracked text files the walk saw at the rename (2026-10-20). */
        private const val SCAN_FLOOR = 2_500

        /** Line shapes admitted EVERYWHERE, each naming the sense it protects. */
        private val GLOBAL_EXEMPTIONS =
            listOf(
                Regex("transform-nodes"), // the design record's immutable path/name, cited everywhere
                Regex("transformation", setOf(IGNORE_CASE)), // English noun + the JCE cipher's TRANSFORMATION
                Regex("transformed|transforming", setOf(IGNORE_CASE)), // English verb forms
                Regex("transformer", setOf(IGNORE_CASE)), // WebDashboardTransformer, the calculators, panelTransform writers
                Regex("TRANSFORM→SCRIPT"), // the rename's own name, in docs and refusals
                Regex("chk_transform_blocks"), // the V33 constraint's pre-V50 name (history)
                Regex("datapipelines\\.transform"), // the removed-key refusal names the old tree
                Regex("checkRemovedTransformKeys|REMOVED_TRANSFORM_KEY_SUFFIXES|transformLegacyKeysSet"),
                Regex("TRANSFORM_BINDING_INVALID|transform_binding_invalid"), // the visualization code
                Regex("`transform\\.(?:template|inputs)`"), // the visualization block's pin and inputs keys (#640)
                Regex("TRANSFORM_STAGE|stage:\\s*\"transform\"|\"transform\\.failed\"|\"transform\\.status\""), // the refresh stage
                Regex("^\\s*\\| 2026-"), // a docs ledger row records the vocabulary of its day (append-only history)
            )

        /**
         * Path → (path selector, per-line regex, reason). A file matching the selector may carry
         * `transform` only on lines the regex admits; everything else in it is red.
         */
        private val PATH_EXEMPTIONS: List<Triple<Regex, Regex, String>> =
            listOf(
                Triple(
                    Regex("ui/ScriptVocabularyGuardTest\\.kt$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the guard itself names every sense it protects",
                ),
                Triple(
                    Regex("^docs/dashboards\\.md$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the visualization block: a visualization pins a TEMPLATE through the `transform` key",
                ),
                Triple(
                    Regex("^docs/pipeline-editor\\.md$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the editor's pan/zoom CSS transform notes",
                ),
                Triple(
                    Regex("^docs/semantic-layer-research\\.md$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "research: dbt (the Transform acquisition) and the semantic-layer sense",
                ),
                Triple(
                    Regex("^docs/research/"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "historical exception-audit snapshots citing class names as they were",
                ),
                Triple(
                    Regex("^docs/pipeline-contract\\.md$"),
                    Regex("the transform|transform's|transform pin|transform template", setOf(IGNORE_CASE)),
                    "the visualization §13.21/§13.22 rows (the block pin) — a future node noun stays red here",
                ),
                Triple(
                    Regex("^docs/rest-api\\.md$"),
                    Regex("transform pin|transform template|the transform|transform,|transform`", setOf(IGNORE_CASE)),
                    "the visualization/dashboard rows and the runtime stage list (the block)",
                ),
                Triple(
                    Regex("^modules/app/src/main/kotlin/co/datapipelines/config/(?:ConfigValidator|ScriptRules)\\.kt$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the removed-key refusal machinery NAMES the old keys by design (§7, no alias)",
                ),
                Triple(
                    Regex("^modules/app/src/test/kotlin/co/datapipelines/config/ConfigValidatorTest\\.kt$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the refusal tests pin the old keys' refusal BY NAME",
                ),
                Triple(
                    Regex("^modules/app/src/main/kotlin/co/datapipelines/logging/RedactingConverter\\.kt$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "Logback's CompositeConverter.transform override",
                ),
                Triple(
                    Regex("^modules/app/src/main/resources/db/migration/"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "frozen migrations (V33/V36/V47) are immutable history",
                ),
                Triple(
                    Regex("^modules/datasources/.*(?:RefusedPropertyKeys|DialectRefusalSetsTest)\\.kt$"),
                    Regex("propertiestransform"),
                    "the refused JDBC datasource property spelling",
                ),
                Triple(
                    Regex("^modules/templates/src/test/kotlin/co/datapipelines/templates/(ScriptBlocks|TypedTemplates)MigrationTest\\.kt$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the V33→V50 constraint-rename history in the migration tests",
                ),
                Triple(
                    Regex("^modules/pipeline-contract/src/test/resources/examples/"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "worked-example fixture prose uses the verb",
                ),
                Triple(
                    Regex("^modules/application/.*dashboards/"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the dashboards refresh stage (the block's `transform` stage and TRANSFORM_STAGE)",
                ),
                Triple(
                    Regex("^modules/web/src/main/kotlin/co/datapipelines/web/dashboards/"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the dashboards runtime resolves the visualization's transform pin (the block)",
                ),
                Triple(
                    Regex("^modules/web/src/test/kotlin/co/datapipelines/web/dashboards/"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the dashboards runtime tests' block fixtures",
                ),
                Triple(
                    Regex("^modules/web/src/main/kotlin/co/datapipelines/web/visualizations/"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the visualization block's web half (body key `transform`, pins, exports)",
                ),
                Triple(
                    Regex("^modules/web/src/test/kotlin/co/datapipelines/web/visualizations/"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the visualization block's web-half tests (body key `transform`)",
                ),
                Triple(
                    Regex("web/ui/Visualization\\w*\\.(kt)$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the visualization lifecycle models (the block's TransformBinding)",
                ),
                Triple(
                    Regex("partials/visualization-"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the visualization partials render the block's pin",
                ),
                Triple(
                    Regex("^modules/web/src/main/resources/site/demo/"),
                    Regex("\"transform\"\\s*:", setOf(IGNORE_CASE)),
                    "sample-data fence metadata: the manifest's derivation-step key",
                ),
                Triple(
                    Regex("^modules/web/src/test/js/dashboard-runtime\\.test\\.mjs$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the refresh-stage fixture (the block's stage value)",
                ),
                Triple(
                    Regex(
                        "browser/(?:DashboardFilters|DashboardFiltersShots|DashboardParametersPane|DataTable|" +
                            "MobileShell|PipelineEditorStartMarker|ShellFeel)BrowserTest\\.kt$",
                    ),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the drawer/panel CSS transform and the geometry notes",
                ),
                Triple(
                    Regex("browser/DraftDashboardDependenciesBrowserTest\\.kt$"),
                    Regex("\"transform\"", setOf(IGNORE_CASE)),
                    "the visualization body's `transform` key (the block)",
                ),
                Triple(
                    Regex(
                        "integration/(?:DashboardRuntimeE2eTest|VisualizationTransferE2eTest|VisualizationLifecycleE2eTest|" +
                            "VisualizationReleaseCascadeFixtures|DependencyGuardsE2eTest)\\.kt$",
                    ),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the block fixtures: TRANSFORM_PIN_BOARD/VIZ_TRANSFORM and the `transform` body key",
                ),
                Triple(
                    Regex("integration/ScriptNodeMigrationTest\\.kt$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the V50 migration test's pre-state: stored TRANSFORM rows are the fixture; the rename is the assertion",
                ),
                Triple(
                    Regex("pipeline-contract/src/test/kotlin/co/datapipelines/pipeline/PipelineDeserializerTest\\.kt$"),
                    Regex("TRANSFORM"),
                    "the no-alias refusal test names the old wire value by design; the alias arm plant goes red on it",
                ),
                Triple(
                    Regex("config/ScriptKeyRefusalStartupTest\\.kt$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the boot refusal test sets a stale datapipelines.transform key and asserts the operator-facing refusal",
                ),
                Triple(
                    Regex("integration/(?:FlywayMigrationIntegrationTest|KeysV2MigrationTest)\\.kt$"),
                    Regex("transform", setOf(IGNORE_CASE)),
                    "the V33 pre-state rows and the migration's immutable filename",
                ),
                // The visualization block's kept senses outside the visualization module (#640): the key, its pin,
                // its output and the dashboards refresh stage. Each regex names the restored phrase, so a node noun
                // in the same file stays red (the self-test's NOT_ADMITTED cases).
                Triple(
                    Regex("^docs/mcp-server\\.md$"),
                    Regex("\"transform\": \\{|inputs, transform, config|transform pin's contract|column of the transform's output"),
                    "the visualization tools' rows: the document's `transform` key, its pin and its output",
                ),
                Triple(
                    Regex("mcp/VisualizationsTools\\.kt$"),
                    Regex("\"transform\": \\{\"type\"|inputs, transform, \"|transform pin's contract|column of the transform's output"),
                    "the visualization write schema's `transform` property is the reader's DOCUMENT_KEYS entry",
                ),
                Triple(
                    Regex("^docs/auth\\.md$"),
                    Regex("the transform pin RELEASED|the transform run WITHOUT"),
                    "§7.6's visualization.release row (the block's pin) and dashboard.execute row (the refresh stage)",
                ),
                Triple(
                    Regex("^docs/versioning\\.md$"),
                    Regex("its transform pin RELEASED|the transform pins of any pushed visualization"),
                    "the visualization release gate and the transfer closure (the block's pin)",
                ),
                Triple(
                    Regex("^docs/ui-screens\\.md$"),
                    Regex(
                        "the transform pin with its status|the transform pin not released|" +
                            "transform and opacity only|Motion is `transform` on the drawers|Connect / Transform / Deliver",
                    ),
                    "the visualization tab's Overview and release dialog (the block's pin); the shell's motion " +
                        "(CSS, kept sense 1); the login companion's workflow verb (kept sense 3)",
                ),
                Triple(
                    Regex("^docs/module-structure\\.md$"),
                    Regex("its DRAFT transform pins"),
                    "the visualization module's release cascade (the block's pin)",
                ),
                Triple(
                    Regex("executor/RefreshAbortFlags\\.kt$"),
                    Regex("a pending transform,"),
                    "the dashboards refresh stage",
                ),
                Triple(
                    Regex("web/(?:config/VisualizationConfiguration|templates/TemplateLifecycleConfiguration)\\.kt$"),
                    Regex("a DRAFT transform pin|the visualizations' transform pins"),
                    "the visualization release cascade and the template in-use guard (the block's pin)",
                ),
                Triple(
                    Regex("(?:resources/skill|resources/docs-golden)/dashboards\\.md$"),
                    Regex("the transform's output"),
                    "the skill's dashboards page: a binding names a column of the block's output",
                ),
                Triple(
                    Regex("ui/site/SiteHandTypedCountsGuardTest\\.kt$"),
                    Regex("\"viewBox\", \"transform\""),
                    "the SVG `transform` attribute is layout geometry (kept sense 1)",
                ),
                Triple(
                    Regex("static/js/shell\\.js$"),
                    Regex("transform and opacity only"),
                    "the swap's CSS motion (kept sense 1)",
                ),
                Triple(
                    Regex("(?:static/js/pipeline-editor/graph\\.js|templates/pipelines/editor\\.html)$"),
                    Regex("pan/zoom transform|before transforms\\."),
                    "the canvas's CSS pan/zoom transform (kept sense 1)",
                ),
                Triple(
                    Regex("(?:ui/site/SitePages\\.kt|templates/site/compare-dbt\\.html)$"),
                    Regex("dbt transforms"),
                    "the marketing comparison's English verb: what dbt does (kept sense 3)",
                ),
                Triple(
                    Regex("templates/site/demo-data\\.html$"),
                    Regex("the URL, the transform, the licence"),
                    "a demo manifest's provenance names its data-preparation step (kept sense 3)",
                ),
                Triple(
                    Regex("templates/login\\.html$"),
                    Regex("<b>Transform</b><small>Reusable logic</small>"),
                    "the login companion's Connect / Transform / Deliver workflow verb (kept sense 3)",
                ),
            )

        /** Restored kept-sense lines (#640) a path entry must admit — path to line. */
        private val ADMITTED =
            listOf(
                "docs/mcp-server.md" to "      \"transform\": {",
                "docs/mcp-server.md" to "the WORKING version's full document (renderer, inputs, transform, config, bindings)",
                "modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/VisualizationsTools.kt" to
                    "    \"transform\": {\"type\": \"object\", \"description\": \"Optional\"",
                "docs/auth.md" to "| `visualization.release` | Release a visualization's draft (a test case, the transform pin RELEASED",
                "docs/versioning.md" to "with at least one test case, its transform pin RELEASED (or the 142 cascade",
                "docs/ui-screens.md" to "the inputs and their columns, the transform pin with its status and bindings",
                "modules/dag/src/main/kotlin/co/datapipelines/executor/RefreshAbortFlags.kt" to " * a pending transform, a source",
                "modules/mcp-server/src/main/resources/skill/dashboards.md" to "(or of the transform's output)",
                "modules/web/src/test/kotlin/co/datapipelines/web/ui/site/SiteHandTypedCountsGuardTest.kt" to
                    "\"d\", \"viewBox\", \"transform\")",
                "docs/ui-screens.md" to "5. **Motion is `transform` on the drawers and `opacity` on the backdrop**",
                "modules/web/src/main/resources/static/js/pipeline-editor/graph.js" to
                    "// whole container carries the pan/zoom transform, so cards scale WITH the canvas",
                "modules/web/src/main/resources/templates/site/compare-dbt.html" to "dbt transforms in place, inside the engine",
                "modules/web/src/main/resources/templates/login.html" to "<b>Transform</b><small>Reusable logic</small>",
            )

        /** The node noun in those same files stays red: the path entries admit the block, not the word. */
        private val NOT_ADMITTED =
            listOf(
                "docs/mcp-server.md" to "and so is a TRANSFORM node (use templates_evaluate)",
                "modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/VisualizationsTools.kt" to "a pinned TRANSFORM template",
                "docs/auth.md" to "`GET /partials/templates/transform-face`",
                "docs/versioning.md" to "a transform template's draft",
                "modules/mcp-server/src/main/resources/skill/dashboards.md" to "a pinned transform template that reshapes",
                "docs/ui-screens.md" to "the transform face's Save draft",
                "modules/web/src/main/resources/static/js/pipeline-editor/graph.js" to "the card says `transform` until the pin resolves",
                "modules/web/src/main/resources/templates/site/roadmap.html" to "No JSONata or JavaScript transform nodes yet",
                "modules/web/src/main/resources/templates/login.html" to "<b>Transform</b><small>Node</small>",
            )
    }
}
