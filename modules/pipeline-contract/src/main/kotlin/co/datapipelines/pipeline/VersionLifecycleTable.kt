package co.datapipelines.pipeline

/**
 * The parser of versioning.md §3.5.2's **lifecycle table** (101) — the normative ~67 rows
 * that ARE the ruling.
 *
 * It lives in MAIN (not a test tree) because two consumers in different modules must read
 * the SAME rows with the SAME parser: [VersioningSpecDriftTest][co.datapipelines.pipeline.VersioningSpecDriftTest]
 * (unit — asserts the table is readable, complete, and cites only catalogued codes) and
 * `VersionLifecycleModelTest` (integration — REPLAYS the doc's rows against real Postgres
 * before its random phase, so the proof runs the DOC's rows, not a copy). One parser, two
 * consumers: a second copy is where the doc and the test would silently diverge — the exact
 * defect shape `ScopeMatrixSpecDriftTest` exists to prevent.
 *
 * Plain string ops, no markdown dependency — the `ScopeMatrixSpecDriftTest` mechanism.
 */
object VersionLifecycleTable {
    /** [parse]'s search offset: skip the newline that ends the §3.5.2 heading line. */
    private const val SKIP_HEADING_NEWLINE = 1

    /** The table's column count (Shape|Before|Event|Posture|Edge|Outcome|After|Entity). */
    private const val COLUMN_COUNT = 8

    /** One parsed row of §3.5.2. Field names mirror the table's column headers. */
    data class Row(
        val shape: String,
        /** e.g. `1R 2X 3D cur=1`; `cur=∅` is the NULL pointer. */
        val before: String,
        /** e.g. `release`, `discard(v2 = current)`, `purge(v1) / purge(v2)`, `import (first — no current)`. */
        val event: String,
        /** `dev` | `hard` | `both`. */
        val posture: String,
        /** `—` or the edge description. */
        val edge: String,
        /** `allowed…` or `refused \`code\`` (slash-separated alternatives stay one cell). */
        val outcome: String,
        val after: String,
        val entity: String,
    ) {
        /** The Before cell's version map, number → status letter. */
        val versionsBefore: Map<Int, Char> by lazy { parseVersions(before) }

        /** The Before cell's pointer; `∅` (or absence) means NULL. */
        val pointerBefore: Int? by lazy {
            Regex("cur=(∅|\\d+)")
                .find(before)
                ?.groupValues
                ?.get(1)
                ?.let { if (it == "∅") null else it.toInt() }
        }

        /** Every error code the Outcome cell cites (template twins included). */
        val citedCodes: List<String> by lazy {
            Regex("`([a-z][a-z0-9_.]+)`").findAll(outcome).map { it.groupValues[1] }.toList()
        }

        /** True when the row's outcome is an allowance (no refusal code cited). */
        val allowed: Boolean by lazy { citedCodes.isEmpty() }
    }

    /**
     * The rows of §3.5.2, in document order. The table is located between the `#### 3.5.2`
     * heading and the next heading — structural end markers, the drift-guard discipline.
     */
    fun parse(docText: String): List<Row> {
        val start =
            docText
                .indexOf("#### 3.5.2")
                .let { if (it < 0) return emptyList() else docText.indexOf('\n', it) }
        val rest = docText.substring(start)
        val end =
            Regex("^#{2,4} ", RegexOption.MULTILINE)
                .find(rest, SKIP_HEADING_NEWLINE)
                ?.range
                ?.start ?: rest.length
        return rest
            .substring(0, end)
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .map { it.trim('|').split("|").map(String::trim) }
            .filter { it.size == COLUMN_COUNT }
            .filterNot { it[0] == "Shape" || it[0].startsWith("---") }
            .map { cells ->
                Row(
                    shape = cells[0],
                    // Before/After cells are backticked in the doc; the parser unwraps.
                    before = cells[1].trim('`'),
                    event = cells[2],
                    posture = cells[3],
                    edge = cells[4],
                    outcome = cells[5],
                    after = cells[6],
                    entity = cells[7],
                )
            }.toList()
    }

    /** `1R 2X 3D` → {1:'R', 2:'X', 3:'D'}; anything after `cur=` is not a version token. */
    internal fun parseVersions(cell: String): Map<Int, Char> =
        cell
            .substringBefore("cur=")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.matches(Regex("\\d+[RXD]")) }
            .associate { it.dropLast(1).toInt() to it.last() }
}
