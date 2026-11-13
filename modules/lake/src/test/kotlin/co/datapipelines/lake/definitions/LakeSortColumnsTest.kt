package co.datapipelines.lake.definitions

import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V1
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V1_LEGACY
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.body
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.field
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.apache.iceberg.NullOrder
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.SortOrder
import org.apache.iceberg.expressions.Expressions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The sort columns of a definition body (lake.md §1; dp-lake design record §11a call 36 items 1 and 3, row M10):
 * U1 every refusal with its code, `details.path` and `details.reason`; U2 the lock against the table's first version
 * ever released, read from the lock basis and never from the id rules' base; U3 the canonical text; U4 what the lock
 * implies for a column; U5 the Iceberg round trip.
 */
class LakeSortColumnsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    fun `U1 - each sort-column refusal names its path and reason`(
        case: String,
        json: String,
        lockBasis: LakeDefinitionBody?,
        path: String,
        reason: String,
    ) {
        withClue(case) {
            val refusal = refuse(json, lockBasis)
            (refusal.details["path"] to refusal.details["reason"]) shouldBe (path to reason)
        }
    }

    @Test
    fun `U1 - an approximate DECIMAL is refused as a floating-point column, for now`() {
        val refusal = refuse(sorted(listOf(4)), null)
        (refusal.details["path"] to refusal.details["reason"]) shouldBe ("sort_field_ids[0]" to "unsupported_type")
        refusal.message shouldContain "floating-point"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("admissions")
    fun `U1 - the valid side of each sort-column rule is admitted`(
        case: String,
        json: String,
        expected: List<Int>,
    ) {
        withClue(case) { parse(json, null).sortFieldIds shouldBe expected }
    }

    @Test
    fun `U2 - with a keyed first release, only the same list in the same order is admitted`() {
        val basis = parse(sorted(listOf(1, 2)), null)
        listOf(listOf(2, 1), listOf(1), listOf(1, 2, 5), null).forEach { ids ->
            withClue("draft $ids against [1, 2]") { reasonOf(sorted(ids), basis) shouldBe "locked" }
        }
        parse(sorted(listOf(1, 2)), basis).sortFieldIds shouldBe listOf(1, 2)
        refuse(sorted(listOf(2, 1)), basis).message shouldContain "another order needs a replacement table"
    }

    @Test
    fun `U2 - with a legacy first release, the key stays absent`() {
        val legacy = LakeDefinitionBodies.read(sorted(null))
        legacy.sortFieldIds shouldBe emptyList()
        reasonOf(sorted(listOf(1)), legacy) shouldBe "locked"
        refuse(sorted(listOf(1)), legacy).message shouldContain "released without sort columns and stays unsorted"
        parse(sorted(null), legacy).sortFieldIds shouldBe emptyList()
    }

    @Test
    fun `U2 - before the first release any valid list is admitted, and may differ from the last draft's`() {
        parse(sorted(listOf(1, 2)), null).sortFieldIds shouldBe listOf(1, 2)
        parse(sorted(listOf(5, 2, 1)), null).sortFieldIds shouldBe listOf(5, 2, 1)
    }

    @Test
    fun `U2 - the lock reads the lock basis, not the id rules' base (the discarded-release state)`() {
        val basis = parse(sorted(listOf(1, 2)), null)
        val refusal =
            shouldThrow<DatapipelinesException> { LakeDefinitionBodies.parse(sorted(listOf(2, 1)), base = null, lockBasis = basis) }
        refusal.details["reason"] shouldBe "locked"
        val withBase =
            shouldThrow<DatapipelinesException> { LakeDefinitionBodies.parse(sorted(listOf(2, 1)), base = basis, lockBasis = basis) }
        withBase.details["reason"] shouldBe "locked"
    }

    @Test
    fun `U3 - the key is written last, and parse, toJson and read round-trip`() {
        val parsed = parse(ORDERS_V1, null)
        val canonical = LakeDefinitionBodies.toJson(parsed)
        canonical shouldEndWith "\"identifier_field_ids\":[1],\"sort_field_ids\":[1]}"
        LakeDefinitionBodies.read(canonical) shouldBe parsed
        LakeDefinitionBodies.toJson(LakeDefinitionBodies.read(canonical)) shouldBe canonical
    }

    @Test
    fun `U3 - a legacy body's canonical text is byte for byte what the base produced`() {
        val legacy = LakeDefinitionBodies.read(ORDERS_V1_LEGACY)
        legacy.sortFieldIds shouldBe emptyList()
        LakeDefinitionBodies.toJson(legacy) shouldBe BASE_CANONICAL_ORDERS_V1
    }

    @Test
    fun `U4 - a sort column cannot be dropped, and may be made optional`() {
        val basis = parse(BASIS_REQUIRED_SORT, null)
        val dropped = body(listOf(field(1, "id", "BIGINTEGER", "required" to true)), sort = listOf(1, 2))
        refuse(dropped, basis).details.let { (it["path"] to it["reason"]) shouldBe ("sort_field_ids[1]" to "unknown_field") }
        val droppedFromList = body(listOf(field(1, "id", "BIGINTEGER", "required" to true)), sort = listOf(1))
        reasonOf(droppedFromList, basis) shouldBe "locked"
        val optional = body(listOf(field(1, "id", "BIGINTEGER", "required" to true), field(2, "at", "TIMESTAMP")), sort = listOf(1, 2))
        LakeDefinitionBodies.parse(optional, base = basis, lockBasis = basis).field(2)?.required shouldBe false
    }

    @Test
    fun `U5 - fromIceberg reads an ascending nulls-first identity order as the ids, in order`() {
        val body = parse(sorted(listOf(5, 1)), null)
        val schema = LakeTableShape.schemaOf(body)
        val order = LakeTableShape.sortOrderOf(body, schema)
        order.fields().map { it.sourceId() } shouldBe listOf(5, 1)
        LakeDefinitionBodies.fromIceberg(schema, PartitionSpec.unpartitioned(), order).sortFieldIds shouldBe listOf(5, 1)
        LakeDefinitionBodies.fromIceberg(schema, PartitionSpec.unpartitioned(), SortOrder.unsorted()).sortFieldIds shouldBe emptyList()
    }

    @Test
    fun `U5 - fromIceberg refuses an order the body cannot spell`() {
        val schema = LakeTableShape.schemaOf(parse(sorted(listOf(1)), null))
        val orders =
            mapOf(
                "descending" to SortOrder.builderFor(schema).desc("id", NullOrder.NULLS_FIRST).build(),
                "nulls last" to SortOrder.builderFor(schema).asc("id", NullOrder.NULLS_LAST).build(),
                "a transform" to SortOrder.builderFor(schema).asc(Expressions.bucket<Any>("id", BUCKETS), NullOrder.NULLS_FIRST).build(),
            )
        orders.forEach { (case, order) ->
            withClue(case) {
                val refusal =
                    shouldThrow<DatapipelinesException> { LakeDefinitionBodies.fromIceberg(schema, PartitionSpec.unpartitioned(), order) }
                refusal.code shouldBe PipelineErrorCodes.Lake.DEFINITION_INVALID_BODY
                (refusal.details["path"] to refusal.details["reason"]) shouldBe ("sort_field_ids" to "unsupported_sort_order")
            }
        }
    }

    companion object {
        private const val BUCKETS = 4

        /**
         * `LakeDefinitionBodies.toJson(parse(ORDERS_V1))` as the base of this lane produced it (spec/dp-lake-p6-prerequisites
         * at 70df57151, before the key existed), captured there and pinned here.
         */
        private const val BASE_CANONICAL_ORDERS_V1 =
            "{\"fields\":[{\"id\":1,\"name\":\"id\",\"type\":\"BIGINTEGER\",\"required\":true,\"doc\":\"order id\"}," +
                "{\"id\":2,\"name\":\"customer\",\"type\":\"STRING\",\"required\":false,\"tags\":[\"pii\"]}," +
                "{\"id\":3,\"name\":\"amount\",\"type\":\"DECIMAL\",\"precision\":12,\"scale\":2,\"required\":false}," +
                "{\"id\":4,\"name\":\"ordered_at\",\"type\":\"TIMESTAMP\",\"required\":true}]," +
                "\"partition\":[{\"field_id\":4,\"transform\":\"day\"}],\"identifier_field_ids\":[1]}"

        /** One column of each kind the rule judges: 1 BIGINTEGER, 2 STRING, 3 BINARY, 4 approximate DECIMAL, 5 TIMESTAMP, 6 INTEGER. */
        private val FIELDS =
            listOf(
                field(1, "id", "BIGINTEGER", "required" to true),
                field(2, "customer"),
                field(3, "payload", "BINARY"),
                field(4, "approx", "DECIMAL"),
                field(5, "at", "TIMESTAMP"),
                field(6, "n", "INTEGER"),
            )

        /** A first release sorted by a required column that is not an identifier (U4). */
        private val BASIS_REQUIRED_SORT =
            body(
                listOf(field(1, "id", "BIGINTEGER", "required" to true), field(2, "at", "TIMESTAMP", "required" to true)),
                sort = listOf(1, 2),
            )

        private fun sorted(ids: List<Int>?) = body(FIELDS, sort = ids)

        /** [FIELDS] with `sort_field_ids` set to any JSON [value]. */
        private fun raw(value: Any) = body(FIELDS, sort = null, extra = mapOf("sort_field_ids" to value))

        private fun parse(
            json: String,
            lockBasis: LakeDefinitionBody?,
        ) = LakeDefinitionBodies.parse(json, base = lockBasis, lockBasis = lockBasis)

        private fun refuse(
            json: String,
            lockBasis: LakeDefinitionBody?,
        ): DatapipelinesException =
            shouldThrow<DatapipelinesException> { parse(json, lockBasis) }.also {
                it.code shouldBe PipelineErrorCodes.Lake.DEFINITION_INVALID_BODY
            }

        private fun reasonOf(
            json: String,
            lockBasis: LakeDefinitionBody?,
        ) = refuse(json, lockBasis).details["reason"]

        private fun legacy() = LakeDefinitionBodies.read(sorted(null))

        private fun case(
            name: String,
            json: String,
            path: String,
            reason: String,
            lockBasis: LakeDefinitionBody? = null,
        ) = Arguments.of(name, json, lockBasis, path, reason)

        @JvmStatic
        fun refusals(): List<Arguments> =
            listOf(
                case("key absent on a never-released table", sorted(null), "sort_field_ids", "missing"),
                case("an empty list", sorted(emptyList()), "sort_field_ids", "empty"),
                case("an empty list on a legacy table", sorted(emptyList()), "sort_field_ids", "empty", lockBasis = legacy()),
                case("five ids", sorted(listOf(1, 2, 5, 6, 3)), "sort_field_ids", "too_many"),
                case("not an array", raw(1), "sort_field_ids", "wrong_type"),
                case("an element not an integer", raw(listOf("id")), "sort_field_ids[0]", "wrong_type"),
                case("a fractional element", raw(listOf(1.5)), "sort_field_ids[0]", "wrong_type"),
                case("an id no field has", sorted(listOf(1, 9)), "sort_field_ids[1]", "unknown_field"),
                case("an id listed twice", sorted(listOf(1, 1)), "sort_field_ids[1]", "duplicate"),
                case("a BINARY column", sorted(listOf(3)), "sort_field_ids[0]", "unsupported_type"),
                case("an approximate DECIMAL", sorted(listOf(4)), "sort_field_ids[0]", "unsupported_type"),
                case("a list other than the first release's", sorted(listOf(2)), "sort_field_ids", "locked", lockBasis = legacy()),
            )

        @JvmStatic
        fun admissions(): List<Arguments> =
            listOf(
                Arguments.of("one column", sorted(listOf(1)), listOf(1)),
                Arguments.of("four columns, in the order listed", sorted(listOf(6, 5, 2, 1)), listOf(6, 5, 2, 1)),
                Arguments.of("an optional column", sorted(listOf(2)), listOf(2)),
                Arguments.of(
                    "every other admitted type",
                    body(
                        listOf(
                            field(1, "b", "BOOLEAN"),
                            field(2, "e", "DECIMAL", "precision" to 12, "scale" to 2),
                            field(3, "f", "BIGDECIMAL", "precision" to 20, "scale" to 4),
                            field(4, "d", "DATE"),
                            field(5, "t", "TIME"),
                        ),
                        sort = listOf(1, 2, 3, 4),
                    ),
                    listOf(1, 2, 3, 4),
                ),
                Arguments.of("TIME", body(listOf(field(1, "t", "TIME")), sort = listOf(1)), listOf(1)),
            )
    }
}
