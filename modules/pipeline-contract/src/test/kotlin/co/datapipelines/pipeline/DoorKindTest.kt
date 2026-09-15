package co.datapipelines.pipeline

import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The door classifier's shapes (139 §C) — the ledger's shapes, since the classifier exists
 * because each of them kept being mis-authored: the raw pair (the miss itself), the period
 * parameter, the base/comp year pair, the anchor + window calculator, and no dates at all.
 * A pure function over the body, so every shape is a fixture and no fixture is a hope.
 */
class DoorKindTest {
    private fun params(vararg entries: Pair<String, LogicalType>): Map<String, Parameter> =
        entries.associate { (name, type) -> name to Parameter(type = type) }

    private fun calculatorNode(
        id: String,
        kind: String,
    ): Node =
        Node(
            id = id,
            description = "",
            type = NodeType.CALCULATOR,
            source = "",
            template = TemplateRef(),
            output = null,
            dependsOn = emptyList(),
            kind = kind,
        )

    private fun sqlNode(id: String): Node =
        Node(
            id = id,
            description = "",
            type = NodeType.DQL,
            source = "pg",
            template = TemplateRef(id = "test/t.sql", version = 1),
            output = null,
            dependsOn = listOf(id),
        )

    @Test
    fun `two raw date parameters are a RAW_DATE_PAIR`() {
        Door.classify(params("start_date" to LogicalType.DATE, "end_date" to LogicalType.DATE), listOf(sqlNode("rows"))) shouldBe
            DoorKind.RAW_DATE_PAIR
    }

    @Test
    fun `a year parameter makes it PERIOD`() {
        Door.classify(
            params("start_date" to LogicalType.DATE, "end_date" to LogicalType.DATE, "year" to LogicalType.INTEGER),
            listOf(sqlNode("rows")),
        ) shouldBe DoorKind.PERIOD
    }

    @Test
    fun `quarter and month parameters make it PERIOD`() {
        Door.classify(
            params("as_of" to LogicalType.DATE, "quarter" to LogicalType.INTEGER),
            emptyList(),
        ) shouldBe DoorKind.PERIOD
        Door.classify(
            params("as_of" to LogicalType.DATE, "month" to LogicalType.INTEGER),
            emptyList(),
        ) shouldBe DoorKind.PERIOD
    }

    @Test
    fun `base and comp years make it PERIOD`() {
        Door.classify(
            params(
                "start_date" to LogicalType.DATE,
                "end_date" to LogicalType.DATE,
                "base_year" to LogicalType.INTEGER,
                "comp_year" to LogicalType.INTEGER,
            ),
            listOf(sqlNode("rows")),
        ) shouldBe DoorKind.PERIOD
    }

    @Test
    fun `a non-period integer next to two dates does not save the raw pair`() {
        Door.classify(
            params("start_date" to LogicalType.DATE, "end_date" to LogicalType.DATE, "limit" to LogicalType.INTEGER),
            emptyList(),
        ) shouldBe DoorKind.RAW_DATE_PAIR
    }

    @Test
    fun `an anchor date with a window calculator is PERIOD`() {
        Door.classify(
            params("anchor" to LogicalType.DATE),
            listOf(calculatorNode("window", "period_bounds"), sqlNode("rows")),
        ) shouldBe DoorKind.PERIOD
    }

    @Test
    fun `a trailing-window calculator saves a two-date body`() {
        Door.classify(
            params("start_date" to LogicalType.DATE, "end_date" to LogicalType.DATE),
            listOf(calculatorNode("window", "trailing_periods"), sqlNode("rows")),
        ) shouldBe DoorKind.PERIOD
    }

    @Test
    fun `a calculator whose kind declares no DATE output does not save the raw pair`() {
        Door.classify(
            params("start_date" to LogicalType.DATE, "end_date" to LogicalType.DATE),
            listOf(calculatorNode("label", "quarter_of_year"), sqlNode("rows")),
        ) shouldBe DoorKind.RAW_DATE_PAIR
    }

    @Test
    fun `an unknown kind contributes nothing - 12_10 already refuses it`() {
        Door.classify(
            params("start_date" to LogicalType.DATE, "end_date" to LogicalType.DATE),
            listOf(calculatorNode("mystery", "no_such_kind"), sqlNode("rows")),
        ) shouldBe DoorKind.RAW_DATE_PAIR
    }

    @Test
    fun `no date parameters is NONE`() {
        Door.classify(params("year" to LogicalType.INTEGER), emptyList()) shouldBe DoorKind.NONE
        Door.classify(emptyMap(), listOf(sqlNode("rows"))) shouldBe DoorKind.NONE
    }

    /**
     * The re-run pipeline 4's shape (139 handback): no declared parameters at all, the window
     * baked as DATE literals INSIDE two templates' bodies. The classifier reads only the body
     * a pipeline save carries — parameters and nodes — so this is NONE today. A `door_absent`
     * arm for it would need the template bodies, which the pipeline body does not carry: a
     * design, not one clause, and deliberately not built.
     */
    @Test
    fun `the re-run pipeline 4 shape - empty parameters, window as DATE literals in templates - is NONE`() {
        Door.classify(emptyMap(), listOf(sqlNode("window_one"), sqlNode("window_two"))) shouldBe DoorKind.NONE
    }

    @Test
    fun `a single anchor date without a calculator is PERIOD - one date is an anchor, not a pair`() {
        Door.classify(params("as_of" to LogicalType.DATE), emptyList()) shouldBe DoorKind.PERIOD
    }

    @Test
    fun `the registry lookup is the default and finds the shipped window writers`() {
        // The gate the classifier reads through is the deployed catalog itself.
        Door.derivesWindow(CalculatorRegistry.find("period_bounds")) shouldBe true
        Door.derivesWindow(CalculatorRegistry.find("trailing_periods")) shouldBe true
        Door.derivesWindow(CalculatorRegistry.find("period_start")) shouldBe true
        Door.derivesWindow(CalculatorRegistry.find("coalesce")) shouldBe false
        Door.derivesWindow(null) shouldBe false
    }
}
