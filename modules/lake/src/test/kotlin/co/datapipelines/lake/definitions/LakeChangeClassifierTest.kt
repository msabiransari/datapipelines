package co.datapipelines.lake.definitions

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.apache.iceberg.NullOrder
import org.apache.iceberg.SortDirection
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random

/**
 * The supported-change policy (lake.md §4, dp-lake design record §7) as a pure function: one case per policy row in
 * each direction ([policy] — the reverse of an admitted change is usually a refused one), and the property that a
 * shape compared with itself is COMPATIBLE with no change, over seeded random shapes.
 */
class LakeChangeClassifierTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("policy")
    fun `each policy row classifies as the table says`(
        row: String,
        current: LakeTableShape,
        intent: LakeTableShape,
        tableHasRows: Boolean,
        kinds: Set<LakeChangeKind>,
        changeClass: LakeChangeClass,
    ) {
        val verdict = LakeChangeClassifier.classify(current, intent, tableHasRows)
        withClue("$row: ${verdict.changes}") {
            verdict.changes.map { it.kind }.toSet() shouldBe kinds
            verdict.changeClass shouldBe changeClass
        }
    }

    @Test
    fun `the verdict is the strictest class among its changes`() {
        val current = shape(col(1, "a"), col(2, "b"))
        val intent = shape(col(1, "a", doc = "now documented"), col(3, "c", required = true))
        val verdict = LakeChangeClassifier.classify(current, intent, tableHasRows = true)
        verdict.changes.map { it.kind }.toSet() shouldBe setOf(LakeChangeKind.DOC, LakeChangeKind.DROP, LakeChangeKind.ADD_REQUIRED)
        verdict.changeClass shouldBe LakeChangeClass.BREAKING
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sortOrders")
    fun `U6 - any difference in the sort order is one SORT_ORDER change, BREAKING, naming both orders`(
        case: String,
        current: List<LakeSortFieldShape>,
        intent: List<LakeSortFieldShape>,
    ) {
        val verdict = LakeChangeClassifier.classify(shape(A, T, sort = current), shape(A, T, sort = intent), tableHasRows = true)
        withClue("$case: ${verdict.changes}") {
            verdict.changes.size shouldBe 1
            val change = verdict.changes.single()
            (change.kind to change.fieldId) shouldBe (LakeChangeKind.SORT_ORDER to null)
            change.kind.wire shouldBe "sort_order"
            change.detail shouldBe "sort order $current → $intent"
            verdict.changeClass shouldBe LakeChangeClass.BREAKING
        }
    }

    @Test
    fun `U6 - equal sort orders add nothing`() {
        val order = listOf(LakeSortFieldShape.ascending(2), LakeSortFieldShape.ascending(1))
        val verdict = LakeChangeClassifier.classify(shape(A, T, sort = order), shape(A, T, sort = order.toList()), tableHasRows = true)
        verdict.changes.shouldBeEmpty()
        verdict.changeClass shouldBe LakeChangeClass.COMPATIBLE
    }

    @Test
    fun `property - every shape compared with itself is COMPATIBLE with an empty change list`() {
        val random = Random(SEED)
        repeat(SAMPLES) { sample ->
            val s = randomShape(random)
            val verdict = LakeChangeClassifier.classify(s, s.copy(), random.nextBoolean())
            withClue("sample $sample: $s") {
                verdict.changes.shouldBeEmpty()
                verdict.changeClass shouldBe LakeChangeClass.COMPATIBLE
            }
        }
    }

    companion object {
        private const val SEED = 798
        private const val SAMPLES = 500
        private const val MAX_COLUMNS = 12
        private const val MAX_SORT_SAMPLE = 5

        private val TYPES: List<Type> =
            listOf(
                Types.BooleanType.get(),
                Types.IntegerType.get(),
                Types.LongType.get(),
                Types.DoubleType.get(),
                Types.DecimalType.of(12, 2),
                Types.StringType.get(),
                Types.DateType.get(),
                Types.TimestampType.withZone(),
            )

        private fun col(
            id: Int,
            name: String,
            type: Type = Types.StringType.get(),
            required: Boolean = false,
            doc: String? = null,
        ) = LakeColumnShape(id, name, type, required, doc)

        private fun shape(
            vararg columns: LakeColumnShape,
            partition: List<LakePartitionShape> = emptyList(),
            identifiers: Set<Int> = emptySet(),
            sort: List<LakeSortFieldShape> = emptyList(),
        ) = LakeTableShape(columns.toList(), partition, identifiers, sort)

        private fun case(
            row: String,
            current: LakeTableShape,
            intent: LakeTableShape,
            changeClass: LakeChangeClass,
            vararg kinds: LakeChangeKind,
            rows: Boolean = true,
        ) = Arguments.of(row, current, intent, rows, kinds.toSet(), changeClass)

        private fun asc(id: Int) = LakeSortFieldShape.ascending(id)

        @JvmStatic
        fun sortOrders(): List<Arguments> =
            listOf(
                Arguments.of("another column", listOf(asc(1)), listOf(asc(2))),
                Arguments.of("the same columns in another order", listOf(asc(1), asc(2)), listOf(asc(2), asc(1))),
                Arguments.of("a column more", listOf(asc(1)), listOf(asc(1), asc(2))),
                Arguments.of(
                    "descending",
                    listOf(asc(1)),
                    listOf(LakeSortFieldShape(1, "identity", SortDirection.DESC, NullOrder.NULLS_FIRST)),
                ),
                Arguments.of(
                    "nulls last",
                    listOf(asc(1)),
                    listOf(LakeSortFieldShape(1, "identity", SortDirection.ASC, NullOrder.NULLS_LAST)),
                ),
                Arguments.of(
                    "a transform",
                    listOf(asc(1)),
                    listOf(LakeSortFieldShape(1, "bucket[4]", SortDirection.ASC, NullOrder.NULLS_FIRST)),
                ),
                Arguments.of("sorted to unsorted", listOf(asc(1)), emptyList<LakeSortFieldShape>()),
                Arguments.of("unsorted to sorted", emptyList<LakeSortFieldShape>(), listOf(asc(1))),
            )

        private val A = col(1, "a")
        private val T = col(2, "t", Types.TimestampType.withZone())
        private val BASE = shape(A, T)

        @JvmStatic
        @Suppress("LongMethod") // the policy table, one case per line
        fun policy(): List<Arguments> {
            val compatible = LakeChangeClass.COMPATIBLE
            val reviewed = LakeChangeClass.REVIEWED
            val breaking = LakeChangeClass.BREAKING
            return listOf(
                case("add optional column", BASE, shape(A, T, col(3, "c")), compatible, LakeChangeKind.ADD_OPTIONAL),
                case("reverse: drop column", shape(A, T, col(3, "c")), BASE, reviewed, LakeChangeKind.DROP),
                case(
                    "add required column to an empty table",
                    BASE,
                    shape(A, T, col(3, "c", required = true)),
                    compatible,
                    LakeChangeKind.ADD_REQUIRED_EMPTY_TABLE,
                    rows = false,
                ),
                case(
                    "add required column to a table with rows",
                    BASE,
                    shape(A, T, col(3, "c", required = true)),
                    breaking,
                    LakeChangeKind.ADD_REQUIRED,
                ),
                case(
                    "widen int to long",
                    shape(col(1, "n", Types.IntegerType.get())),
                    shape(col(1, "n", Types.LongType.get())),
                    compatible,
                    LakeChangeKind.WIDEN,
                ),
                case(
                    "reverse: narrow long to int",
                    shape(col(1, "n", Types.LongType.get())),
                    shape(col(1, "n", Types.IntegerType.get())),
                    breaking,
                    LakeChangeKind.TYPE_CHANGE,
                ),
                case(
                    "widen float to double",
                    shape(col(1, "n", Types.FloatType.get())),
                    shape(col(1, "n", Types.DoubleType.get())),
                    compatible,
                    LakeChangeKind.WIDEN,
                ),
                case(
                    "reverse: narrow double to float",
                    shape(col(1, "n", Types.DoubleType.get())),
                    shape(col(1, "n", Types.FloatType.get())),
                    breaking,
                    LakeChangeKind.TYPE_CHANGE,
                ),
                case(
                    "widen decimal precision",
                    shape(col(1, "n", Types.DecimalType.of(10, 2))),
                    shape(col(1, "n", Types.DecimalType.of(12, 2))),
                    compatible,
                    LakeChangeKind.WIDEN,
                ),
                case(
                    "reverse: narrow decimal precision",
                    shape(col(1, "n", Types.DecimalType.of(12, 2))),
                    shape(col(1, "n", Types.DecimalType.of(10, 2))),
                    breaking,
                    LakeChangeKind.TYPE_CHANGE,
                ),
                case(
                    "decimal scale change",
                    shape(col(1, "n", Types.DecimalType.of(10, 2))),
                    shape(col(1, "n", Types.DecimalType.of(12, 4))),
                    breaking,
                    LakeChangeKind.TYPE_CHANGE,
                ),
                case(
                    "incompatible type change",
                    shape(col(1, "n")),
                    shape(col(1, "n", Types.IntegerType.get())),
                    breaking,
                    LakeChangeKind.TYPE_CHANGE,
                ),
                case("add a doc", BASE, shape(col(1, "a", doc = "documented"), T), compatible, LakeChangeKind.DOC),
                case("reverse: remove a doc", shape(col(1, "a", doc = "documented"), T), BASE, compatible, LakeChangeKind.DOC),
                case(
                    "add a partition field",
                    BASE,
                    shape(A, T, partition = listOf(LakePartitionShape(2, "day"))),
                    compatible,
                    LakeChangeKind.ADD_PARTITION_FIELD,
                ),
                case(
                    "reverse: remove a partition field",
                    shape(A, T, partition = listOf(LakePartitionShape(2, "day"))),
                    BASE,
                    reviewed,
                    LakeChangeKind.REMOVE_PARTITION_FIELD,
                ),
                case("rename column", BASE, shape(col(1, "b"), T), reviewed, LakeChangeKind.RENAME),
                case("reverse: rename back", shape(col(1, "b"), T), BASE, reviewed, LakeChangeKind.RENAME),
                case("make optional", shape(col(1, "a", required = true), T), BASE, reviewed, LakeChangeKind.MAKE_OPTIONAL),
                case("reverse: make required", BASE, shape(col(1, "a", required = true), T), breaking, LakeChangeKind.MAKE_REQUIRED),
                case("reorder columns", BASE, shape(T, A), reviewed, LakeChangeKind.REORDER),
                case("reverse: reorder back", shape(T, A), BASE, reviewed, LakeChangeKind.REORDER),
                case(
                    "new column before a survivor",
                    BASE,
                    shape(A, col(3, "c"), T),
                    reviewed,
                    LakeChangeKind.ADD_OPTIONAL,
                    LakeChangeKind.REORDER,
                ),
                case(
                    "reorder partition fields",
                    shape(A, T, partition = listOf(LakePartitionShape(1, "identity"), LakePartitionShape(2, "day"))),
                    shape(A, T, partition = listOf(LakePartitionShape(2, "day"), LakePartitionShape(1, "identity"))),
                    reviewed,
                    LakeChangeKind.REORDER_PARTITION,
                ),
                case(
                    "new partition field before a kept one",
                    shape(A, T, partition = listOf(LakePartitionShape(2, "day"))),
                    shape(A, T, partition = listOf(LakePartitionShape(1, "identity"), LakePartitionShape(2, "day"))),
                    reviewed,
                    LakeChangeKind.ADD_PARTITION_FIELD,
                    LakeChangeKind.REORDER_PARTITION,
                ),
                case(
                    "set identifier fields",
                    shape(col(1, "a", required = true), T),
                    shape(col(1, "a", required = true), T, identifiers = setOf(1)),
                    breaking,
                    LakeChangeKind.IDENTIFIER_FIELDS,
                ),
                case(
                    "reverse: clear identifier fields",
                    shape(col(1, "a", required = true), T, identifiers = setOf(1)),
                    shape(col(1, "a", required = true), T),
                    breaking,
                    LakeChangeKind.IDENTIFIER_FIELDS,
                ),
            )
        }

        private fun randomShape(random: Random): LakeTableShape {
            val columns =
                (1..random.nextInt(1, MAX_COLUMNS)).map { id ->
                    col(id, "c$id", TYPES[random.nextInt(TYPES.size)], random.nextBoolean(), if (random.nextBoolean()) "doc $id" else null)
                }
            val partition = columns.filter { random.nextInt(4) == 0 }.map { LakePartitionShape(it.fieldId, "identity") }
            val identifiers = columns.filter { it.required && random.nextInt(3) == 0 }.map { it.fieldId }.toSet()
            val sort = columns.shuffled(random).take(random.nextInt(0, MAX_SORT_SAMPLE)).map { LakeSortFieldShape.ascending(it.fieldId) }
            return LakeTableShape(columns, partition, identifiers, sort)
        }
    }
}
