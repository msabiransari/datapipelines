package co.datapipelines.metrics

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `docs/observability.md` §4.1's metric tables against the meters the code registers (#708, the failure-flow
 * record §13 — the class of drift behind #568 and #708: a table of promises nobody built, and a meter nobody
 * documented). §4.1 is the contract an operator writes a dashboard or an alert against; neither side may move
 * alone.
 *
 * The CODED side is a static sweep that must not miss by naming. It reads every `*.kt` under
 * `modules/<module>/src/main/kotlin` that names Micrometer (`io.micrometer` or `MeterRegistry` — the sink's
 * type), finds every registration call (`.builder(`, `.counter(`, `.timer(`, `.gauge(`, `.summary(` —
 * `Counter`/`Gauge`/`Timer`/`DistributionSummary` builders and the registry shorthands) and resolves its first
 * argument to a literal: a string, a `const val` in the same file, or `Owner.CONST` in `Owner.kt`. An argument
 * it cannot resolve — a template, a parameter, a call — is RED at its file:line, never skipped: a computed
 * meter name cannot be checked against §4.1. The booted application's registry is the second reader:
 * `ApplicationSmokeTest` asserts every `datapipelines.*` meter the running app holds is in this sweep's set,
 * so a registration form the sweep does not know turns that case red the moment its meter is eager.
 *
 * The DOCUMENTED side is the backticked name in the first cell of every table row from `### 4.1` to
 * `### 4.2`. Rules: code = doc, name for name; every coded name carries the `datapipelines.` prefix (§4.1's
 * own rule); [FRAMEWORK_NAMES] are the documented names the application does not register itself. Floors
 * keep a broken extractor from passing by agreeing with itself on an empty set.
 */
class MeterCatalogueParityTest {
    @Test
    fun `every coded meter has a §4_1 row and every §4_1 row has a coded meter`() {
        val coded = MeterCatalogue.codedSites()
        val codedNames = coded.mapNotNull { it.name }.toSortedSet()
        val documented = MeterCatalogue.documentedNames()
        println("event=meter.catalogue coded=${codedNames.size} ${codedNames.joinToString(",")}")
        println("event=meter.catalogue documented=${documented.size} ${documented.joinToString(",")}")

        val drift =
            listOf(
                "registrations whose name is not a literal or a resolvable const val" to
                    coded.filter { it.name == null }.map { it.where },
                "coded names outside the `datapipelines.` prefix (§4.1's rule)" to
                    codedNames.filterNot { it.startsWith(MeterCatalogue.PREFIX) },
                "coded meters with no §4.1 row" to (codedNames - documented).toList(),
                "§4.1 rows with no coded meter (framework names allowed: ${MeterCatalogue.FRAMEWORK_NAMES})" to
                    (documented - codedNames - MeterCatalogue.FRAMEWORK_NAMES).toList(),
            ).filter { (_, names) -> names.isNotEmpty() }
        val report = drift.joinToString("\n") { (what, names) -> "$what: $names" }
        withClue("observability.md §4.1 and the coded meters drifted:\n$report") {
            drift.shouldBeEmpty()
        }
        codedNames.size shouldBeGreaterThanOrEqual MeterCatalogue.FLOOR
        documented.size shouldBeGreaterThanOrEqual MeterCatalogue.FLOOR
    }

    @Test
    fun `the sweep resolves literals, same-file constants and qualified constants - and refuses a computed name`() {
        val owner = "class ScheduledJob { companion object { const val FAILED_TICK_METER = \"datapipelines.a.b\" } }"
        val text =
            """
            import io.micrometer.core.instrument.Counter
            class X(private val registry: MeterRegistry) {
                fun a() = registry.counter(LOCAL, "k", "v")
                fun b() = Counter
                    .builder("datapipelines.literal")
                fun c() = Counter.builder(ScheduledJob.FAILED_TICK_METER)
                fun d(name: String) = registry.timer(name)
                fun e() = registry.counter("datapipelines.${'$'}{suffix}")
                // registry.counter(COMMENTED) is prose, not a registration
                companion object { const val LOCAL = "datapipelines.local" }
            }
            """.trimIndent()
        val sites = MeterCatalogue.sites("X.kt", text, mapOf("ScheduledJob" to owner))
        sites.map { it.name } shouldBe
            listOf("datapipelines.local", "datapipelines.literal", "datapipelines.a.b", null, null)
    }

    @Test
    fun `the documented side reads only the first cell of §4_1's table rows`() {
        val doc =
            """
            ### 4.1 Metric naming
            Prose naming `datapipelines.not.a.row`.
            | Metric | Type | Tags | Description |
            |---|---|---|---|
            | `datapipelines.one` | counter | (none) | Mentions `datapipelines.two` in prose |
            | `http.server.requests` | timer | `uri` | Boot's own |
            ### 4.2 Exposure
            | `datapipelines.after` | counter | | |
            """.trimIndent()
        MeterCatalogue.documentedNames(doc) shouldBe sortedSetOf("datapipelines.one", "http.server.requests")
    }
}

/**
 * The two readers of the meter catalogue — the static sweep and the §4.1 parser — shared by
 * [MeterCatalogueParityTest] and `ApplicationSmokeTest`'s runtime registry read.
 */
internal object MeterCatalogue {
    const val PREFIX = "datapipelines."

    /** Spring Boot's own `http.server.requests` (§4.1 says it keeps its name); registered by the framework, not swept. */
    val FRAMEWORK_NAMES = setOf("http.server.requests")

    /** Non-vacuity floor for both sides (43 coded names when the guard was written). */
    const val FLOOR = 40

    /** One registration call: where it is, and the name it resolves to (`null` = unresolvable). */
    data class Site(
        val where: String,
        val name: String?,
    )

    fun codedSites(): List<Site> {
        val files = sourceFiles()
        val owners = files.associate { it.nameWithoutExtension to it.readText() }
        return files
            .map { it to it.readText() }
            .filter { (_, text) -> text.contains("io.micrometer") || text.contains("MeterRegistry") }
            .flatMap { (file, text) -> sites(file.relativeTo(repoRoot()).invariantSeparatorsPath, text, owners) }
    }

    /** The registration calls in one file's code (comments removed first), each with its resolved name. */
    fun sites(
        path: String,
        text: String,
        owners: Map<String, String>,
    ): List<Site> {
        val code = stripComments(text)
        return REGISTRATION
            .findAll(code)
            .map { match ->
                val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                Site("$path:$line ${match.value.trim()}", resolve(match.groupValues[1], code, owners))
            }.toList()
    }

    fun documentedNames(): Set<String> = documentedNames(File(repoRoot(), "docs/observability.md").readText())

    fun documentedNames(doc: String): Set<String> {
        val section = SECTION_41.find(doc)?.value ?: error("observability.md §4.1 not found (or no '### 4.2' after it)")
        return section
            .lineSequence()
            .map { it.trim() }
            .mapNotNull { FIRST_CELL_NAME.find(it)?.groupValues?.get(1) }
            .toSortedSet()
    }

    private fun resolve(
        argument: String,
        code: String,
        owners: Map<String, String>,
    ): String? {
        LITERAL.matchEntire(argument)?.let { return it.groupValues[1] }
        if (argument.startsWith('"')) return null // a template or an escape: computed
        val owner = argument.substringBeforeLast('.', missingDelimiterValue = "")
        val constant = argument.substringAfterLast('.')
        val source = if (owner.isEmpty()) code else owners[owner.substringAfterLast('.')]?.let(::stripComments) ?: return null
        return Regex("""const\s+val\s+${Regex.escape(constant)}\s*(?::\s*String\s*)?=\s*"([^"$]+)"""")
            .findAll(source)
            .map { it.groupValues[1] }
            .singleOrNull()
    }

    /** Block comments and whole-line `//` comments; a `//` inside a string literal (a URL) is left alone. */
    private fun stripComments(text: String): String =
        BLOCK_COMMENT
            .replace(text) { match -> "\n".repeat(match.value.count { it == '\n' }) }
            .lines()
            .joinToString("\n") { if (it.trimStart().startsWith("//")) "" else it }

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

    /** `.builder(`/`.counter(`/`.timer(`/`.gauge(`/`.summary(` and the first argument, possibly on the next line. */
    private val REGISTRATION = Regex("""\.(?:builder|counter|timer|gauge|summary)\(\s*("(?:[^"\\]|\\.)*"|[A-Za-z_][\w.]*)""")
    private val LITERAL = Regex(""""([^"$\\]+)"""")
    private val BLOCK_COMMENT = Regex("""(?s)/\*.*?\*/""")
    private val SECTION_41 = Regex("""(?ms)^### 4\.1\b.*?(?=^### 4\.2\b)""")
    private val FIRST_CELL_NAME = Regex("""^\|\s*`([a-z0-9_]+(?:\.[a-z0-9_]+)+)`\s*\|""")
    private val MAIN_KOTLIN_SEGMENT = "${File.separator}src${File.separator}main${File.separator}kotlin${File.separator}"
}
