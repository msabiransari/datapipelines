package co.datapipelines.lake.definitions

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.expressions.BoundReference
import org.apache.iceberg.expressions.BoundTransform
import org.apache.iceberg.expressions.UnboundTerm
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Every supported partition transform (lake.md §1) reaches Iceberg as itself through BOTH builders: a new table's
 * spec ([LakeTransforms.addTo]) and a partition evolution's term ([LakeTransforms.term]), read back as the
 * transform Iceberg reports, never trusted from the string that was passed.
 */
class LakeTransformsTest {
    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource(
        "identity, id",
        "year, ts",
        "month, ts",
        "day, ts",
        "hour, ts",
        "bucket[16], id",
        "truncate[4], name",
    )
    fun `a new table's spec carries the transform`(
        transform: String,
        column: String,
    ) {
        val builder = PartitionSpec.builderFor(SCHEMA)
        LakeTransforms.addTo(builder, column, transform)
        val field = builder.build().fields().single()
        withClue(transform) {
            field.transform().toString() shouldBe transform
            field.sourceId() shouldBe SCHEMA.findField(column).fieldId()
        }
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource(
        "identity, id",
        "year, ts",
        "month, ts",
        "day, ts",
        "hour, ts",
        "bucket[16], id",
        "truncate[4], name",
    )
    fun `a partition evolution's term binds to the transform`(
        transform: String,
        column: String,
    ) {
        val bound = (LakeTransforms.term(column, transform) as UnboundTerm<*>).bind(SCHEMA.asStruct(), true)
        withClue(transform) {
            if (transform == "identity") {
                bound.shouldBeInstanceOf<BoundReference<*>>().name() shouldBe column
            } else {
                bound.shouldBeInstanceOf<BoundTransform<*, *>>().transform().toString() shouldBe transform
                bound.ref().name() shouldBe column
            }
        }
    }

    @Test
    fun `a transform outside the list is refused by both builders - the rules refuse it first`() {
        shouldThrow<IllegalArgumentException> { LakeTransforms.addTo(PartitionSpec.builderFor(SCHEMA), "id", "void") }
            .message shouldContain "unsupported transform 'void'"
        shouldThrow<IllegalArgumentException> { LakeTransforms.term("id", "bucket[]") }
            .message shouldContain "unsupported transform 'bucket[]'"
    }

    private companion object {
        val SCHEMA =
            Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "name", Types.StringType.get()),
                Types.NestedField.required(3, "ts", Types.TimestampType.withoutZone()),
            )
    }
}
