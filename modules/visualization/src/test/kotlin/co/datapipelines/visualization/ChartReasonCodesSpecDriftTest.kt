package co.datapipelines.visualization

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The save-reason list and both documented tables stay equal; source literals cannot create a second producer. */
class ChartReasonCodesSpecDriftTest {
    @Test
    fun `the list is well formed`() {
        val wires = ChartReason.WIRE_VALUES
        withClue("ChartReason wires must be unique") { wires.distinct() shouldBe wires }
        withClue("ChartReason members must be sorted by wire") { wires shouldBe wires.sorted() }
        wires shouldBe ChartReason.entries.map { it.wire }
        for (reason in ChartReason.entries) {
            withClue("${reason.wire}: use chart.family.name with lowercase letters, digits and name underscores") {
                WIRE.matches(reason.wire) shouldBe true
            }
            withClue("${reason.name}: derive the member name from ${reason.wire}") {
                reason.name shouldBe reason.wire.removePrefix("chart.").uppercase().replace('.', '_')
            }
            withClue("${reason.wire}: fromWire must return its member") { ChartReason.fromWire(reason.wire) shouldBe reason }
        }
        ChartReason.fromWire("schema") shouldBe null
    }

    @Test
    fun `the docs table holds exactly the list and schema`() {
        checkTable(parseTable(VisualizationTestFiles.read(DOCS), DOCS_HEADER, DOCS), DOCS)
    }

    @Test
    fun `the manual table holds exactly the list and schema`() {
        val folder = VisualizationTestFiles.repoFile("$MANUAL_FOLDER/dashboards.md").parentFile
        val documents =
            requireNotNull(folder.listFiles()) { "$MANUAL_FOLDER: cannot list manual files" }
                .filter { it.isFile && it.extension == "md" }
                .sortedBy { it.name }
                .associate { "$MANUAL_FOLDER/${it.name}" to it.readText() }
        val headers = documents.mapValues { (_, text) -> text.lineSequence().count { it.trim() == MANUAL_HEADER } }
        val occurrences = headers.values.sum()
        check(occurrences == 1) {
            "$MANUAL_FOLDER: '$MANUAL_HEADER' found $occurrences times ($headers); keep exactly one manual table"
        }
        val document = headers.filterValues { it == 1 }.keys.single()
        checkTable(parseTable(documents.getValue(document), MANUAL_HEADER, document), document)
    }

    @Test
    fun `no source spells a code outside the list`() {
        val source = VisualizationTestFiles.repoFile("modules/visualization/build.gradle.kts").parentFile.resolve("src/main")
        val files = source.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        val enumFiles = files.filter { it.name == "ChartReason.kt" }
        withClue("$source: scan must find exactly one ChartReason.kt") { enumFiles.size shouldBe 1 }
        var enumHits = 0
        val problems = mutableListOf<String>()
        for (file in files) {
            file.readLines().forEachIndexed { index, line ->
                val hits = SOURCE_LITERAL.findAll(line).toList()
                if (file == enumFiles.single()) {
                    enumHits += hits.size
                } else {
                    hits.forEach { hit ->
                        problems += "${file.relativeTo(source)}:${index + 1}: ${hit.value}; use a ChartReason member"
                    }
                }
            }
        }
        check(problems.isEmpty()) { problems.joinToString("\n") }
        withClue("ChartReason.kt: literal scan must see one wire per member") { enumHits shouldBe ChartReason.entries.size }
    }

    @Test
    fun `the parser refuses what it cannot read`() {
        val valid = fixture("`schema`", "`chart.field.binary`")
        parseTable(valid, DOCS_HEADER, "single") shouldBe setOf("schema", "chart.field.binary")
        parseTable(fixture("`schema`", "`chart.field.binary` / `chart.field.unknown`"), DOCS_HEADER, "pair") shouldBe
            setOf("schema", "chart.field.binary", "chart.field.unknown")
        val invalid =
            listOf(
                valid.replace(DOCS_HEADER, "missing header"),
                "$valid\n$DOCS_HEADER",
                fixture("`schema`", "`chart.field.binary`", "`chart.field.binary`"),
                fixture("`schema`", "`chart.field.binary` / `chart.field.binary`"),
                fixture("`schema`", "`chart.field.binary`, `chart.field.unknown`"),
                fixture("`schema`", "`chart.field.binary``chart.field.unknown`"),
                fixture("`schema`", "`chart.field.binary` // `chart.field.unknown`"),
                fixture("`schema`", "chart.field.binary"),
                fixture("`schema`", "`other`"),
                fixture("`chart.field.binary`"),
                fixture("`schema`"),
            )
        invalid.forEachIndexed { index, text ->
            val document = "inline case $index"
            val failure = shouldThrow<IllegalStateException> { parseTable(text, DOCS_HEADER, document) }
            withClue("parser diagnostic must identify $document") { failure.message.orEmpty().contains(document) shouldBe true }
        }
    }

    private fun checkTable(
        codes: Set<String>,
        document: String,
    ) {
        val members = ChartReason.WIRE_VALUES.toSet()
        val chartCodes = codes - "schema"
        val differences =
            (members - chartCodes).sorted().map { "code $it has no row in $document; add its row" } +
                (chartCodes - members).sorted().map { "row $it has no member in ChartReason ($document); remove it or add its member" }
        check(differences.isEmpty()) { differences.joinToString("\n") }
    }

    private fun parseTable(
        text: String,
        header: String,
        document: String,
    ): Set<String> {
        val lines = text.lines()
        val headers = lines.indices.filter { lines[it].trim() == header }
        check(headers.size == 1) { "$document: '$header' found ${headers.size} times; keep exactly one table header" }
        val rows = lines.drop(headers.single() + 1).takeWhile { it.trim().startsWith('|') }
        val codes = linkedSetOf<String>()
        var count = 0
        for (row in rows) {
            if (SEPARATOR.matches(row.trim())) continue
            val cell = row.trim().removePrefix("|").substringBefore('|').trim()
            check(CELL.matches(cell)) { "$document: unreadable row $row; use backticked codes joined by exactly ' / '" }
            for (match in TOKEN.findAll(cell)) {
                val code = match.groupValues[1]
                check(code == "schema" || WIRE.matches(code)) { "$document: row $code is not a chart reason or schema; fix $row" }
                check(codes.add(code)) { "$document: code $code occurs twice; keep one occurrence (row $row)" }
            }
            count++
        }
        check("schema" in codes) { "$document: schema has no row; restore the schema producer's row" }
        check(codes.any { it.startsWith("chart.") }) { "$document: no chart codes found; restore the reason rows" }
        println("$document: rows=$count chart codes=${codes.size - 1} schema=1 total codes=${codes.size}")
        return codes
    }

    private fun fixture(vararg cells: String): String =
        DOCS_HEADER + "\n|---|---|---|\n" + cells.joinToString("\n") { "| $it | save rules | fixture |" }

    private companion object {
        const val DOCS = "docs/dashboards.md"
        const val MANUAL_FOLDER = "modules/mcp-server/src/main/resources/skill"
        const val DOCS_HEADER = "| `details.reason` | Layer | When |"
        const val MANUAL_HEADER = "| `details.reason` | Fix |"
        val WIRE = Regex("chart\\.[a-z0-9]+\\.[a-z0-9_]+")
        val SOURCE_LITERAL = Regex("\"chart\\.[a-z0-9]+\\.[a-z0-9_]+")
        val CELL = Regex("`[^`]+`(?: / `[^`]+`)*")
        val TOKEN = Regex("`([^`]+)`")
        val SEPARATOR = Regex("\\|(?:\\s*:?-+:?\\s*\\|)+")
    }
}
