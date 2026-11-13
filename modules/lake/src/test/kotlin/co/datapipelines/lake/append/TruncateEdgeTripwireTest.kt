package co.datapipelines.lake.append

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.apache.iceberg.transforms.Transforms
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * #967 V7: pure Iceberg, no product code. Each test asserts today's upstream defect on purpose, one half each, so
 * an Iceberg bump that fixes either half goes red here and names the refusal that may be removable. Never delete
 * or loosen this to make a bump green: the lane it goes red in turns the half that changed round and reports it.
 */
class TruncateEdgeTripwireTest {
    @Test
    fun `decimal - truncate still gains a digit at the lowest value (apache iceberg 17027)`() {
        val partition =
            Transforms
                .truncate<BigDecimal>(2)
                .bind(Types.DecimalType.of(15, 3))
                .apply(BigDecimal("-999999999999.999"))
        withClue(MESSAGE) { partition.precision() shouldBe 16 }
    }

    @Test
    fun `int - truncate still wraps above the lowest value (apache iceberg 13105, 17027's discussion)`() {
        val partition =
            Transforms
                .truncate<Int>(10)
                .bind(Types.IntegerType.get())
                .apply(Int.MIN_VALUE)
        withClue(MESSAGE) { partition shouldBeGreaterThan Int.MIN_VALUE }
    }

    private companion object {
        const val MESSAGE =
            "Iceberg's truncate now stays inside its type at this edge: re-read apache/iceberg 17027 and 13105; " +
                "the refusal lake.append.partition_value_out_of_range may be removable (#967)"
    }
}
