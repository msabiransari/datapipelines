package co.datapipelines.lake.append

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.iceberg.NullOrder
import org.apache.iceberg.Schema
import org.apache.iceberg.SortOrder
import org.apache.iceberg.expressions.Expressions
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.Test

/**
 * #968 D2, D4, D5, D6: the one value an append's scan order and its files' stamp are read from. Pure: no catalog,
 * no engine. O8 is the floating-column assertion, called directly.
 */
class AppendOrderTest {
    @Test
    fun `D2 - positions follow the sort order and come from each source field's staged position`() {
        val order =
            SortOrder
                .builderFor(SCHEMA)
                .asc("region", NullOrder.NULLS_FIRST)
                .asc("score", NullOrder.NULLS_FIRST)
                .build()
        // Staged order: score at 2, region at 4; field ids 3 and 12 and schema indexes 1 and 2 are all different.
        AppendOrder.positions(order, bindings(region = 4, score = 2)) shouldBe listOf(4, 2)
    }

    @Test
    fun `D2 - a sort column the delivery does not supply is skipped`() {
        val order =
            SortOrder
                .builderFor(SCHEMA)
                .asc("region", NullOrder.NULLS_FIRST)
                .asc("score", NullOrder.NULLS_FIRST)
                .build()
        AppendOrder.positions(order, bindings(region = null, score = 1)) shouldBe listOf(1)
        AppendOrder.positions(order, bindings(region = 3, score = null)) shouldBe listOf(3)
    }

    @Test
    fun `D4 - a sorted table's value stamps the table's order, a legacy table's stamps unsorted with no positions`() {
        val order = SortOrder.builderFor(SCHEMA).asc("score", NullOrder.NULLS_FIRST).build()
        val sorted = AppendOrder.of(order, bindings(region = 4, score = 2))
        sorted.stamp shouldBe order
        sorted.positions shouldBe listOf(2)
        val legacy = AppendOrder.of(SortOrder.unsorted(), bindings(region = 4, score = 2))
        legacy.stamp shouldBe SortOrder.unsorted()
        legacy.positions shouldBe emptyList()
    }

    @Test
    fun `O8 - a double sort column fails naming the field`() {
        val order = SortOrder.builderFor(SCHEMA).asc("approx", NullOrder.NULLS_FIRST).build()
        val failure = shouldThrow<IllegalStateException> { AppendOrder.positions(order, bindings(region = 4, score = 2, approx = 1)) }
        failure.message shouldContain "'approx'"
        failure.message shouldContain "floating-point"
    }

    @Test
    fun `O8 - a float sort column fails naming the field, also when the delivery leaves it out`() {
        val order = SortOrder.builderFor(SCHEMA).asc("ratio", NullOrder.NULLS_FIRST).build()
        shouldThrow<IllegalStateException> { AppendOrder.positions(order, bindings(region = 4, score = 2)) }.message shouldContain "'ratio'"
    }

    @Test
    fun `D6 - descending, nulls last and a transform each fail naming the field`() {
        val orders =
            listOf(
                SortOrder.builderFor(SCHEMA).desc("score", NullOrder.NULLS_FIRST).build(),
                SortOrder.builderFor(SCHEMA).asc("score", NullOrder.NULLS_LAST).build(),
                SortOrder.builderFor(SCHEMA).asc(Expressions.bucket<Int>("score", 4), NullOrder.NULLS_FIRST).build(),
            )
        orders.forEach { order ->
            val refused = shouldThrow<IllegalStateException> { AppendOrder.positions(order, bindings(region = 4, score = 2)) }
            refused.message shouldContain "'score'"
        }
    }

    private fun bindings(
        region: Int?,
        score: Int?,
        approx: Int? = null,
    ): List<AppendColumnBinding> =
        SCHEMA.columns().map { field ->
            val index =
                when (field.name()) {
                    "id" -> 3
                    "region" -> region
                    "score" -> score
                    "approx" -> approx
                    else -> null
                }
            AppendColumnBinding(field, index, index?.let { field.type().asPrimitiveType() })
        }

    private companion object {
        val SCHEMA =
            Schema(
                Types.NestedField.required(7, "id", Types.LongType.get()),
                Types.NestedField.optional(3, "region", Types.StringType.get()),
                Types.NestedField.optional(12, "score", Types.IntegerType.get()),
                Types.NestedField.optional(5, "approx", Types.DoubleType.get()),
                Types.NestedField.optional(9, "ratio", Types.FloatType.get()),
            )
    }
}
