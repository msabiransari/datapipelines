package co.datapipelines.pipeline

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * The drift guard of versioning.md §3.5.2's **lifecycle table** (101) — the ruling IS the
 * table, so the table must be mechanically readable and must cite only catalogued codes.
 *
 * The `ScopeMatrixSpecDriftTest` shape: plain string parsing, a row-count floor so a
 * heading rename cannot silently zero the parse (a drift guard that reads nothing proves
 * nothing), and every refusal code checked against the constants the services actually
 * throw — the doc's `pipeline.version.last_release` and [PipelineErrorCodes.Versioning.LAST_RELEASE]
 * cannot part ways without this going red.
 *
 * A SECOND consumer replays these rows against real Postgres (`VersionLifecycleModelTest`
 * in integration-tests, through the shared [VersionLifecycleTable] parser) — which is why
 * the parser lives in main and this test is the thin consistency arm.
 */
class VersioningSpecDriftTest {
    private val rows = VersionLifecycleTable.parse(Fixtures.repoFile(SPEC_PATH).readText())

    @Test
    fun `the table parses with its full row count - a renamed heading cannot zero it`() {
        rows.size shouldBeGreaterThanOrEqual ROW_FLOOR
        SHAPES.forEach { shape -> rows.map { it.shape } shouldContain shape }
    }

    @Test
    fun `every Before cell is a well-formed version map whose pointer names a version or nothing`() {
        rows.forEach { row ->
            row.versionsBefore.isEmpty() shouldBe false
            row.pointerBefore?.let { p -> row.versionsBefore.keys shouldContain p }
        }
    }

    @Test
    fun `every refusal cites only catalogued codes - the doc and the constants cannot part ways`() {
        val unknown = rows.flatMap { it.citedCodes }.filterNot { it in cataloguedCodes() }
        unknown.shouldBeEmpty()
    }

    @Test
    fun `both postures are covered and every row states an entity verdict`() {
        rows.count { it.posture == "dev" } shouldBeGreaterThanOrEqual 30
        rows.count { it.posture == "hard" } shouldBeGreaterThanOrEqual 3
        rows.count { it.posture == "both" } shouldBeGreaterThanOrEqual 6
        rows.filter { it.allowed }.forEach { row -> row.after shouldNotBe "" }
        rows.forEach { row -> row.entity shouldNotBe "" }
    }

    private companion object {
        const val SPEC_PATH = "docs/versioning.md"

        /**
         * The floor, re-derived from the shipped table: 67 rows at 101. A row REMOVED below
         * this fails loudly — rows are removed deliberately, with this constant, or never.
         */
        const val ROW_FLOOR = 60

        /** §3.5.1's ten version-set shapes. */
        val SHAPES =
            listOf(
                "{D}",
                "{R}",
                "{R,D}",
                "{R,R}",
                "{R,R,D}",
                "{R,X}",
                "{X,D}",
                "{X,X}",
                "{X,X,D}",
                "{R,X,D}",
            )

        /** Every code the services can throw, read from the constants by reflection. */
        fun cataloguedCodes(): Set<String> {
            val codes = mutableSetOf<String>()

            fun collect(container: Any) {
                container::class.nestedClasses.forEach { nested -> nested.objectInstance?.let { collect(it) } }
                container::class.java.declaredFields
                    .filter { it.type == String::class.java && !it.isSynthetic }
                    .forEach { field ->
                        field.isAccessible = true
                        (field.get(null) as? String)
                            ?.takeIf { it.contains('.') && it == it.lowercase() }
                            ?.let { codes.add(it) }
                    }
            }
            collect(PipelineErrorCodes)
            return codes
        }
    }
}
