package co.datapipelines.logging

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
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
 * All twelve currently catalogued namespaces are enforced; uncatalogued namespaces remain
 * outside this guard. Extraction reads source text, including comments, not an AST or runtime.
 * A computed event identity in a file that logs an enforced namespace is refused. Only two
 * exact legacy audit-row diagnostics in AuditLogger are exempt, each required exactly once.
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

    @Test
    fun `legacy audit exceptions exist exactly once in their declared file`() {
        legacyExceptionProblems(sourceTexts()).shouldBeEmpty()
    }

    @Test
    fun `event fields do not include audit_event or other field suffixes`() {
        val text = "event=audit.write audit_event={} previous_event={}"
        computedEventOffenders(AUDIT_LOGGER_PATH, text).shouldBeEmpty()
        codeEventRegex("audit").findAll("audit_event=audit.fake event=audit.write").map { it.value }.toList() shouldBe
            listOf("event=audit.write")
    }

    @Test
    fun `only exact legacy literals at the declared path are exempt`() {
        val text = "event=audit.write\n" + LEGACY_AUDIT_EXCEPTIONS.joinToString("\n") { "log.warn(\"${it.format}\")" }
        computedEventOffenders(AUDIT_LOGGER_PATH, text).shouldBeEmpty()
        computedEventOffenders("modules/auth/src/main/kotlin/Other.kt", text).size shouldBe LEGACY_AUDIT_EXCEPTIONS.size
        computedEventOffenders(AUDIT_LOGGER_PATH, "$text\nlog.warn(\"event={}\")").size shouldBe 1
        computedEventOffenders(AUDIT_LOGGER_PATH, "$text\nlog.warn(\"event=audit.{}\")").size shouldBe 1
        computedEventOffenders(AUDIT_LOGGER_PATH, text.replace("kind={}", "kind={} changed=true")).size shouldBe 1
        val legacy = LEGACY_AUDIT_EXCEPTIONS.first().format
        computedEventOffenders(AUDIT_LOGGER_PATH, "event=audit.write\nlog.warn(\"$legacy\"); log.warn(\"event={}\")").size shouldBe 1
    }

    @Test
    fun `missing altered duplicate and moved legacy exceptions are non-vacuity failures`() {
        val text = LEGACY_AUDIT_EXCEPTIONS.joinToString("\n") { "\"${it.format}\"" }
        legacyExceptionProblems(mapOf(AUDIT_LOGGER_PATH to text)).shouldBeEmpty()
        legacyExceptionProblems(emptyMap()).size shouldBe LEGACY_AUDIT_EXCEPTIONS.size
        for (exception in LEGACY_AUDIT_EXCEPTIONS) {
            val literal = "\"${exception.format}\""
            legacyExceptionProblems(mapOf(AUDIT_LOGGER_PATH to text.replace(literal, ""))).size shouldBe 1
            legacyExceptionProblems(
                mapOf(AUDIT_LOGGER_PATH to text.replace(literal, "\"${exception.format} changed=true\"")),
            ).size shouldBe 1
            legacyExceptionProblems(mapOf(AUDIT_LOGGER_PATH to "$text\n$literal")).size shouldBe 1
        }
        legacyExceptionProblems(mapOf("Other.kt" to text)).size shouldBe LEGACY_AUDIT_EXCEPTIONS.size
    }

    /** Computed event FIELD identities, with only the exact quoted audit-row formats exempt. */
    private fun computedEventOffenders(): List<String> = sourceTexts().flatMap { (path, text) -> computedEventOffenders(path, text) }

    private fun computedEventOffenders(
        path: String,
        text: String,
    ): List<String> {
        val namespaces = ENFORCED.keys.filter { codeEventRegex(it).containsMatchIn(text) }
        if (namespaces.isEmpty()) return emptyList()
        val computed = Regex("(?<![A-Za-z0-9_])event=(?:\\{|(?:" + namespaces.joinToString("|") + ")\\.\\{)")
        return text
            .lineSequence()
            .flatMapIndexed { index, line ->
                val exemptRanges =
                    if (path == AUDIT_LOGGER_PATH) {
                        LEGACY_AUDIT_EXCEPTIONS.flatMap { exception ->
                            quotedFormatRegex(exception).findAll(line).map { it.range }.toList()
                        }
                    } else {
                        emptyList()
                    }
                computed.findAll(line).filter { match -> exemptRanges.none { match.range.first in it } }.map {
                    "$path:${index + 1}: ${line.trim()}"
                }
            }.toList()
    }

    private fun legacyExceptionProblems(sources: Map<String, String>): List<String> =
        LEGACY_AUDIT_EXCEPTIONS.mapNotNull { exception ->
            val count = quotedFormatRegex(exception).findAll(sources[AUDIT_LOGGER_PATH].orEmpty()).count()
            if (count == exception.expectedOccurrences) {
                null
            } else {
                "$AUDIT_LOGGER_PATH: legacy exception '${exception.format}' expected exactly " +
                    "${exception.expectedOccurrences}, found $count (${exception.reason})"
            }
        }

    private fun quotedFormatRegex(exception: LegacyAuditException) = Regex(Regex.escape("\"${exception.format}\""))

    private fun sourceTexts(): Map<String, String> =
        sourceFiles().associate { it.relativeTo(repoRoot()).invariantSeparatorsPath to it.readText() }

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

    private fun codeEventRegex(namespace: String) =
        Regex("(?<![A-Za-z0-9_])event=" + Regex.escape(namespace) + "\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)*")

    private data class LegacyAuditException(
        val format: String,
        val reason: String,
        val expectedOccurrences: Int,
    )

    private companion object {
        const val AUDIT_LOGGER_PATH = "modules/auth/src/main/kotlin/co/datapipelines/auth/AuditLogger.kt"
        val LEGACY_AUDIT_EXCEPTIONS =
            listOf(
                LegacyAuditException(
                    format = "audit_log write failed event={} user_id={} key_id={} kind={}",
                    reason = "retained batched-path diagnostic: event is the audit-row identity, not the log identity (§3.4G)",
                    expectedOccurrences = 1,
                ),
                LegacyAuditException(
                    format = "audit_log write failed event={} user_id={} key_id={} cause={} sql_state={}",
                    reason = "retained direct-path diagnostic: event is the audit-row identity, not the log identity (§3.4G)",
                    expectedOccurrences = 1,
                ),
            )

        /** Namespace to its non-vacuity floor. Every §3.4-catalogued namespace with zero drift joins. */
        val ENFORCED =
            mapOf(
                "parameter" to 20,
                "lake" to 9,
                "mail" to 8,
                "persistence" to 10,
                "scheduler" to 12,
                "audit" to 4,
                "dashboard" to 22,
                "datasource" to 18,
                "endpoint" to 9,
                "execution" to 7,
                "pipeline" to 3,
                "shutdown" to 8,
            )

        val SECTION_34 = Regex("(?ms)^#### 3\\.4A\\b.*?(?=^### )")
        val BACKTICKED_NAME = Regex("`([a-z0-9_]+(?:\\.[a-z0-9_]+)+)`")
        val MAIN_KOTLIN_SEGMENT = "${File.separator}src${File.separator}main${File.separator}kotlin${File.separator}"
    }
}
