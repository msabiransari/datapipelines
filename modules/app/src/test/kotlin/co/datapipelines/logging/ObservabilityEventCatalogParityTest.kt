package co.datapipelines.logging

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `docs/observability.md` §3.4's structured-log event tables against the `event=` literals the
 * code actually logs (#439, the house drift shape — the same file-reading precedent as
 * [ObservabilityRedactionSpecDriftTest]). The spec is the authority for every `event=<ns>.<name>`
 * line an operator greps for, `modules/<module>/src/main/kotlin` is the implementation, and neither side
 * may move alone: an event logged with no catalogue row is unfindable in the record, and a stale
 * row is a search that matches nothing.
 *
 * The guard is generic over [ENFORCED] — namespace to a non-vacuity floor — so a new namespace
 * joins by one table row, no new code. Each enforced namespace is kept name-for-name: the
 * `event=<ns>.` literals under `modules/<module>/src/main/kotlin` equal the backticked `<ns>.<name>`
 * names in the EVENT column (the second cell) of every §3.4 table row, from `#### 3.4A` to the
 * next `### `. The floor states how many names the parser must have seen, so a broken extractor
 * cannot pass by agreeing with itself on an empty set (the plant the gate demands is a deleted
 * row and a new uncatalogued literal — both one-sided, both red).
 *
 * Only namespaces whose §3.4 rows already cover the code exactly are enforced; the others have
 * known one-sided names (filed as a follow-up, not silently tolerated), so enforcing them here
 * would be red on a clean main. A computed event name (`event={}`) inside a file that logs an
 * enforced namespace is refused outright: a name the guard cannot read is not in parity.
 */
class ObservabilityEventCatalogParityTest {
    @Test
    fun `each enforced namespace's event= literals are exactly its §3_4 catalogue rows`() {
        val code = codeEventsByNamespace()
        val docs = documentedEventsByNamespace()
        for ((namespace, floor) in ENFORCED) {
            val codeNames = code[namespace].orEmpty()
            val docNames = docs[namespace].orEmpty()
            val codeOnly = (codeNames - docNames).sorted()
            val docOnly = (docNames - codeNames).sorted()
            withClue(
                "event=$namespace.* — code-only=$codeOnly, doc-only=$docOnly " +
                    "(the §3.4 event column and modules/<module>/src/main/kotlin must agree)",
            ) {
                codeOnly.shouldBeEmpty()
                docOnly.shouldBeEmpty()
            }
            withClue(
                "event=$namespace.* — the code must log at least the non-vacuity floor " +
                    "of $floor names, found ${codeNames.size}",
            ) {
                codeNames.size shouldBeGreaterThanOrEqual floor
            }
        }
    }

    @Test
    fun `a computed event name in a file that logs an enforced namespace is red`() {
        val offenders = computedEventOffenders()
        withClue(
            "a computed event= name cannot be checked against §3.4; it is refused at its file:line " +
                "(found: $offenders)",
        ) {
            offenders.shouldBeEmpty()
        }
    }

    /** Every `event=<ns>.<name>` literal under `modules/<module>/src/main/kotlin`, keyed by namespace. */
    private fun codeEventsByNamespace(): Map<String, Set<String>> {
        val byNamespace = mutableMapOf<String, MutableSet<String>>()
        for (file in sourceFiles()) {
            val text = file.readText()
            for (namespace in ENFORCED.keys) {
                for (match in codeEventRegex(namespace).findAll(text)) {
                    byNamespace
                        .getOrPut(namespace) { mutableSetOf() }
                        .add(match.value.removePrefix("event="))
                }
            }
        }
        return byNamespace
    }

    /** Every backticked `<ns>.<name>` in the EVENT column of a §3.4 table row, keyed by namespace. */
    private fun documentedEventsByNamespace(): Map<String, Set<String>> {
        val byNamespace = mutableMapOf<String, MutableSet<String>>()
        for ((_, eventCell) in section34TableRows()) {
            for (match in BACKTICKED_NAME.findAll(eventCell)) {
                val name = match.groupValues[1]
                val namespace = name.substringBefore('.')
                if (namespace in ENFORCED) {
                    byNamespace.getOrPut(namespace) { mutableSetOf() }.add(name)
                }
            }
        }
        return byNamespace
    }

    /**
     * Every `event={}` (or `event=<ns>.{}`) in a file that also logs a literal enforced-namespace
     * event, as `path:line: text`. A computed name has no fixed string to compare with §3.4.
     */
    private fun computedEventOffenders(): List<String> {
        val offenders = mutableListOf<String>()
        for (file in sourceFiles()) {
            val lines = file.readLines()
            for (namespace in ENFORCED.keys) {
                if (lines.none { it.contains("event=$namespace.") }) continue
                val computed = Regex("event=(?:" + Regex.escape(namespace) + "\\.\\{|\\{)")
                lines.forEachIndexed { index, line ->
                    if (computed.containsMatchIn(line)) {
                        offenders.add("${file.path}:${index + 1}: ${line.trim()}")
                    }
                }
            }
        }
        return offenders
    }

    /** §3.4's table rows — `#### 3.4A` to the next `### ` — as (level, event) cell pairs. */
    private fun section34TableRows(): List<Pair<String, String>> {
        val text = File(repoRoot(), "docs/observability.md").readText()
        val section =
            SECTION_34.find(text)?.value
                ?: error("observability.md §3.4A heading not found (or the next '### ' heading is missing)")
        return section
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .map { it.trim('|').split('|') }
            .filter { it.size >= 2 }
            .map { it[0].trim() to it[1] }
            .toList()
    }

    /** Every `*.kt` under `modules/<module>/src/main/kotlin`, never entering `.claude/` or `build/`. */
    private fun sourceFiles(): List<File> =
        File(repoRoot(), "modules")
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".claude" }
            .filter { it.isFile && it.extension == "kt" && it.path.contains(MAIN_KOTLIN_SEGMENT) }
            .toList()

    /** The checkout root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir
    }

    private fun codeEventRegex(namespace: String) = Regex("event=" + Regex.escape(namespace) + "\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)*")

    private companion object {
        /** Namespace to its non-vacuity floor. Every §3.4-catalogued namespace with zero drift joins. */
        val ENFORCED =
            mapOf(
                "parameter" to 20,
                "lake" to 9,
                "mail" to 8,
                "persistence" to 10,
                "scheduler" to 12,
            )

        val SECTION_34 = Regex("(?ms)^#### 3\\.4A\\b.*?(?=^### )")
        val BACKTICKED_NAME = Regex("`([a-z0-9_]+(?:\\.[a-z0-9_]+)+)`")
        val MAIN_KOTLIN_SEGMENT = "${File.separator}src${File.separator}main${File.separator}kotlin${File.separator}"
    }
}
