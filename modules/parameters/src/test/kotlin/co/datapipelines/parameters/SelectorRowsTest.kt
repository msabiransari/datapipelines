package co.datapipelines.parameters

import co.datapipelines.parameters.EvaluatorFixtures.attempt
import co.datapipelines.parameters.EvaluatorFixtures.templateSelect
import co.datapipelines.parameters.EvaluatorFixtures.version
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The evaluate-time judgement of a selector's rows (record §6.2, §6.4, P31) — every invariant with
 * its `details.reason`, every cap exactly at and one past its bound (the guard is the bound, not a
 * coincidence), §6.4's counterexamples re-checked from metadata at evaluate, and the response's byte
 * budget.
 */
class SelectorRowsTest {
    private val selectors = ScriptedSelectors()
    private val pick = version(templateSelect("pick"))

    private fun evaluate(
        set: ParameterSetVersion,
        config: ParametersConfig = ParametersConfig(),
    ): EvaluateResponse =
        ParameterEvaluator(selectors, SelectorPool(4, 64), config).evaluateBlocking(EvaluatorFixtures.WORKSPACE, set, emptyMap(), attempt())

    /** The one error [run] earns `pick` (null when accepted), with its options empty on refusal. */
    private fun judged(
        run: SelectorRun.Rows,
        set: ParameterSetVersion = pick,
        config: ParametersConfig = ParametersConfig(),
    ): ParameterError? {
        selectors[
            set.body.parameters
                .last()
                .source!!
                .template!!
                .id,
        ] = { run }
        val state = evaluate(set, config).parameters.last().state
        val error = state.errors.firstOrNull { it.code != ParameterErrorCodes.EVALUATE_REQUIRED_MISSING }
        // An INPUT has no options at all (null); a refused SELECT offers none.
        if (error != null) withClue("a refused selector offers nothing") { state.options.orEmpty() shouldBe emptyList() }
        return error
    }

    private fun rows(
        vararg rows: List<Any?>,
        columns: List<ColumnSchema> = ScriptedSelectors.SELECT_COLUMNS,
    ) = SelectorRun.Rows(columns, rows.toList())

    private fun ParameterError?.reason(): Pair<String?, Any?> = this?.code to this?.details?.get("reason")

    private val invalid = ParameterErrorCodes.EVALUATE_SELECTOR_ROWS_INVALID
    private val mismatch = ParameterErrorCodes.EVALUATE_SELECTOR_VALUE_TYPE_MISMATCH

    @Test
    fun `the four invariants of record 5-4 - each selector_rows_invalid with its reason`() {
        judged(rows(listOf("A", "a", false), listOf("A", "b", false))).reason() shouldBe (invalid to "duplicate_value")
        judged(rows(listOf(null, "a", false))).reason() shouldBe (invalid to "null_value")
        judged(rows(listOf("A", "", false))).reason() shouldBe (invalid to "empty_label")
        judged(rows(listOf("A", null, false))).reason() shouldBe (invalid to "empty_label")
        judged(rows(listOf("A", "a", true), listOf("B", "b", true))).reason() shouldBe (invalid to "multiple_defaults")
    }

    @Test
    fun `an is_default of SQL NULL is false - three-valued logic, not an author's error`() {
        judged(rows(listOf("A", "a", null), listOf("B", "b", true))) shouldBe null
    }

    @Test
    fun `uniqueness is the canonical value's - 1-0 and 1-00 are one value`() {
        val decimal = version(decimalPick(precision = 5, scale = 2))
        val columns =
            listOf(
                ColumnSchema("value", LogicalType.DECIMAL, 5, 2),
                ScriptedSelectors.SELECT_COLUMNS[1],
                ScriptedSelectors.SELECT_COLUMNS[2],
            )

        judged(
            rows(listOf(BigDecimal("1.0"), "a", false), listOf(BigDecimal("1.00"), "b", false), columns = columns),
            decimal,
        ).reason() shouldBe
            (invalid to "duplicate_value")
    }

    @Test
    fun `too_many_options at cap plus one - the cap itself is accepted, nothing truncated`() {
        val three = ParametersConfig(maxOptionsPerSelector = 3)
        val options = List(4) { listOf("V$it", "v$it", false) }

        judged(rows(*options.take(3).toTypedArray()), config = three) shouldBe null
        judged(rows(*options.toTypedArray()), config = three)?.code shouldBe ParameterErrorCodes.EVALUATE_TOO_MANY_OPTIONS
    }

    @Test
    fun `max-option-value-chars and max-option-label-chars - at the bound accepted, one past refused`() {
        val tight = ParametersConfig(maxOptionValueChars = 3, maxOptionLabelChars = 3)

        judged(rows(listOf("ABC", "abc", false)), config = tight) shouldBe null
        judged(rows(listOf("ABCD", "abc", false)), config = tight).reason() shouldBe (invalid to "value_too_long")
        judged(rows(listOf("ABC", "abcd", false)), config = tight).reason() shouldBe (invalid to "label_too_long")
    }

    @Test
    fun `the columns drifted since save - selector_rows_invalid reason columns - names compare case-insensitively`() {
        judged(rows(listOf("A", "a"), columns = ScriptedSelectors.SELECT_COLUMNS.take(2))).reason() shouldBe (invalid to "columns")
        judged(rows(listOf("A", "a", false, 1), columns = ScriptedSelectors.SELECT_COLUMNS + ColumnSchema("extra", LogicalType.INTEGER)))
            .reason() shouldBe (invalid to "columns")
        judged(
            rows(
                listOf("A", 1, false),
                columns =
                    listOf(
                        ScriptedSelectors.SELECT_COLUMNS[0],
                        ColumnSchema("display_value", LogicalType.INTEGER),
                        ScriptedSelectors.SELECT_COLUMNS[2],
                    ),
            ),
        ).reason() shouldBe (invalid to "columns")
        val upper =
            listOf(
                ColumnSchema("VALUE", LogicalType.STRING),
                ColumnSchema("DISPLAY_VALUE", LogicalType.STRING),
                ColumnSchema("IS_DEFAULT", LogicalType.BOOLEAN),
            )
        judged(rows(listOf("A", "a", false), columns = upper)) shouldBe null
    }

    @Test
    fun `record 6-4's counterexamples at evaluate - DECIMAL(6,2) into (6,4), INTEGER into DECIMAL(9,0) refused, into (10,0) accepted`() {
        fun decimalColumn(
            type: LogicalType,
            precision: Int?,
            scale: Int?,
        ) = listOf(ColumnSchema("value", type, precision, scale), ScriptedSelectors.SELECT_COLUMNS[1], ScriptedSelectors.SELECT_COLUMNS[2])

        judged(
            rows(listOf(BigDecimal("12.50"), "a", false), columns = decimalColumn(LogicalType.DECIMAL, 6, 2)),
            version(decimalPick(6, 4)),
        )?.code shouldBe
            mismatch
        judged(
            rows(listOf(5, "a", false), columns = decimalColumn(LogicalType.INTEGER, null, null)),
            version(decimalPick(9, 0)),
        )?.code shouldBe
            mismatch
        val widened = version(decimalPick(10, 0))
        judged(rows(listOf(5, "a", false), columns = decimalColumn(LogicalType.INTEGER, null, null)), widened) shouldBe null
        evaluate(widened)
            .parameters
            .single()
            .state.value shouldBe BigDecimal(5)
    }

    @Test
    fun `a driver that under-reports its metadata still refuses - every value is judged by the shared validator (P28)`() {
        val columns =
            listOf(
                ColumnSchema("value", LogicalType.DECIMAL, 5, 2),
                ScriptedSelectors.SELECT_COLUMNS[1],
                ScriptedSelectors.SELECT_COLUMNS[2],
            )

        judged(rows(listOf(BigDecimal("1.234"), "a", false), columns = columns), version(decimalPick(5, 2))).let {
            it?.code shouldBe mismatch
            it?.details?.get("reason") shouldBe "scale"
        }
    }

    @Test
    fun `an all-NULL value column is refused with the CAST hint`() {
        val columns =
            listOf(ColumnSchema("value", LogicalType.NULL), ScriptedSelectors.SELECT_COLUMNS[1], ScriptedSelectors.SELECT_COLUMNS[2])

        judged(rows(listOf(null, "a", false), columns = columns))?.details?.get("hint") shouldBe "CAST the column"
    }

    @Test
    fun `an INPUT's sourced row - its column, its type, and the input's OWN constraints`() {
        val quantity =
            version(
                """
                { "name": "qty", "label": "Qty", "type": "INTEGER", "kind": "INPUT", "default_value": 20, "constraints": { "min": 10 },
                  "source": { "template": { "id": "acme/sales/qty.sql", "version": 1 }, "datasource": "warehouse" } }
                """.trimIndent(),
            )

        judged(ScriptedSelectors.inputRows(LogicalType.INTEGER, 50), quantity) shouldBe null
        judged(SelectorRun.Rows(listOf(ColumnSchema("val", LogicalType.INTEGER)), listOf(listOf(50))), quantity).reason() shouldBe
            (invalid to "columns")
        judged(ScriptedSelectors.inputRows(LogicalType.STRING, "50"), quantity)?.code shouldBe mismatch
        judged(ScriptedSelectors.inputRows(LogicalType.INTEGER, 5), quantity).let {
            it?.code shouldBe ParameterErrorCodes.EVALUATE_CONSTRAINT_VIOLATION
            it?.details shouldBe mapOf("parameter" to "qty", "reason" to "min", "source" to "row")
        }
        withClue("a refused row is no row: default_value is used") {
            evaluate(quantity)
                .parameters
                .single()
                .state
                .let { (it.value to it.origin) shouldBe (20 to ValueOrigin.DEFAULT) }
        }
    }

    @Test
    fun `the response budget - a response of exactly max-evaluate-response-bytes is answered, one byte more is response_too_large`() {
        val wide = ParametersConfig(maxOptionsPerSelector = 400, maxEvaluateResponseBytes = ParametersKey.MAX_EVALUATE_RESPONSE_BYTES.max!!)
        selectors["acme/sales/pick.sql"] = { rows(*List(400) { listOf("V%04d".format(it), "l".repeat(250), false) }.toTypedArray()) }
        val size = EvaluateResponseJson.bytes(evaluate(pick, wide)).size.toLong()
        withClue("the fixture must exceed the key's floor for the bound to be settable: $size bytes") {
            (size > ParametersKey.MAX_EVALUATE_RESPONSE_BYTES.min) shouldBe true
        }

        evaluate(pick, wide.copy(maxEvaluateResponseBytes = size)).valid shouldBe true
        val refused = shouldThrow<DatapipelinesException> { evaluate(pick, wide.copy(maxEvaluateResponseBytes = size - 1)) }
        refused.code shouldBe ParameterErrorCodes.EVALUATE_RESPONSE_TOO_LARGE
        refused.details shouldBe mapOf("bytes" to size, "max_bytes" to size - 1)
    }

    private fun decimalPick(
        precision: Int,
        scale: Int,
    ): String =
        """
        { "name": "pick", "label": "Pick", "type": "DECIMAL", "precision": $precision, "scale": $scale, "kind": "SELECT",
          "source": { "template": { "id": "acme/sales/pick.sql", "version": 1 }, "datasource": "warehouse" } }
        """.trimIndent()
}
