package co.datapipelines.datasources.semantics

import co.datapipelines.datasources.TestFiles
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Drift guard: the closed kind list, held to ONE truth across its three homes — **enums.md
 * §19** (the wire table, with each kind's scope), the **V25 `chk_learned_facts_kind` CHECK**
 * (the enforcing copy in the database, plus `chk_learned_facts_kind_scope` for the scope rule)
 * and [LearnedFactKind] (what the recorder and the tool schema derive from).
 *
 * Order matters and is asserted: the enum's declaration order is the doc's row order is the
 * CHECK's list order, so a reader of any one sees the same list. Add a kind to one home and
 * this fails until the other two agree.
 */
class LearnedFactKindSpecDriftTest {
    private val documented: List<Pair<String, String>> = parseDocRows()
    private val checked: List<String> = parseCheckList()
    private val workspaceKindsInCheck: List<String> = parseScopeCheck()

    @Test
    fun `the parse found kinds in both the doc and the migration - guards against a silent empty parse`() {
        withClue("No kinds parsed from enums.md §19") { documented.isEmpty() shouldBe false }
        withClue("No kinds parsed from V25's chk_learned_facts_kind") { checked.isEmpty() shouldBe false }
        withClue("No WORKSPACE kinds parsed from V25's chk_learned_facts_kind_scope") { workspaceKindsInCheck.isEmpty() shouldBe false }
    }

    @Test
    fun `the Kotlin enum, the doc table and the CHECK list are the same kinds in the same order`() {
        val declared = LearnedFactKind.entries.map { it.wire }
        documented.map { it.first } shouldContainExactly declared
        checked shouldContainExactly declared
    }

    @Test
    fun `each kind's scope agrees between the doc, the enum and the kind-scope CHECK`() {
        documented.forEach { (kind, scope) ->
            withClue("enums.md §19 says $kind is $scope") {
                requireNotNull(LearnedFactKind.fromWire(kind)).scope.name shouldBe scope
            }
        }
        workspaceKindsInCheck shouldContainExactly LearnedFactKind.entries.filter { it.scope == LearnedFactScope.WORKSPACE }.map { it.wire }
    }

    private companion object {
        const val DOC_PATH = "docs/enums.md"
        const val MIGRATION_PATH = "modules/app/src/main/resources/db/migration/V25__learned_facts.sql"
        const val SECTION_START = "## 19."
        const val SECTION_END = "## Cross-Reference"

        /** `| \`kind\` | \`SCOPE\` | …` — the first two cells of a §19 row. */
        val DOC_ROW = Regex("^\\|\\s*`([a-z_]+)`\\s*\\|\\s*`(DATASOURCE|WORKSPACE)`\\s*\\|", RegexOption.MULTILINE)
        val QUOTED = Regex("'([a-z_]+)'")

        fun parseDocRows(): List<Pair<String, String>> {
            val text = TestFiles.repoFile(DOC_PATH).readText()
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $DOC_PATH" }
            val end = text.indexOf(SECTION_END, start)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START' in $DOC_PATH" }
            return DOC_ROW.findAll(text.substring(start, end)).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }

        /** The quoted list inside `CONSTRAINT chk_learned_facts_kind CHECK (kind IN ( … ))`. */
        fun parseCheckList(): List<String> = quotedListOf("chk_learned_facts_kind CHECK")

        /** The quoted list inside `chk_learned_facts_kind_scope` — the WORKSPACE kinds. */
        fun parseScopeCheck(): List<String> = quotedListOf("chk_learned_facts_kind_scope CHECK")

        /** The quoted tokens between the constraint's `(kind IN (` and the `)` that closes that list. */
        private fun quotedListOf(constraint: String): List<String> {
            val sql = TestFiles.repoFile(MIGRATION_PATH).readText()
            val start = sql.indexOf(constraint)
            check(start >= 0) { "'$constraint' not found in $MIGRATION_PATH" }
            val listStart = sql.indexOf("kind IN (", start)
            check(listStart > start) { "'kind IN (' not found inside '$constraint' in $MIGRATION_PATH" }
            val listEnd = sql.indexOf(")", listStart)
            return QUOTED.findAll(sql.substring(listStart, listEnd)).map { it.groupValues[1] }.toList()
        }
    }
}
