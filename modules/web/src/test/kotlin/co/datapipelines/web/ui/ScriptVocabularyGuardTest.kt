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
 *     dashboards' `WebDashboardTransformer`.
 *   - **The rename's own name** — `TRANSFORM→SCRIPT` in the docs and the refusal messages.
 *   - **Removed-key machinery** — `datapipelines.transform.*`, `checkRemovedTransformKeys`,
 *     `REMOVED_TRANSFORM_KEY_SUFFIXES`, `transformLegacyKeysSet`: the refusal names the old
 *     keys BY NAME (§7, no alias).
 *   - **The visualization block** — a visualization pins a TEMPLATE; the pin's wire key is
 *     `transform` and the error code is `transform_binding_invalid`. The visualization module,
 *     its web half, its templates, its tests and the dashboards refresh stage keep that
 *     vocabulary by the owner's block decision.
 *   - **CSS** — the `transform:` property and the pan/zoom/drawer geometry notes.
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
        )) {
            if (!GLOBAL_EXEMPTIONS.any { it.containsMatchIn(allowed) }) {
                fail("a global exemption should admit: $allowed")
            }
        }
    }

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
            )
    }
}
