package co.datapipelines.lake.definitions

import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V1
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.body
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.field
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.partition
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.SortOrder
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * lake.md §1: every body rule refuses with `lake.definition.invalid_body`, its `details.path` and `details.reason`
 * ([refusals]), and the nearest valid body on the other side of the same rule is admitted ([admissions]). A valid body
 * round-trips JSON → body → JSON and Schema → body → Schema, equal by field id.
 */
class LakeDefinitionBodiesTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    fun `an invalid body is refused naming its path and rule`(
        rule: String,
        json: String,
        path: String,
        reason: String,
    ) {
        withClue(rule) {
            val refusal = shouldThrow<DatapipelinesException> { LakeDefinitionBodies.parse(json, base = null, lockBasis = null) }
            refusal.code shouldBe PipelineErrorCodes.Lake.DEFINITION_INVALID_BODY
            (refusal.details["path"] to refusal.details["reason"]) shouldBe (path to reason)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("admissions")
    fun `the valid side of each rule is admitted`(
        rule: String,
        json: String,
    ) {
        val parsed = withClue(rule) { LakeDefinitionBodies.parse(json, base = null, lockBasis = null) }
        LakeDefinitionBodies.parse(LakeDefinitionBodies.toJson(parsed), base = null, lockBasis = null) shouldBe parsed
    }

    @Test
    fun `with a base, new fields take the ids after the base's highest - and only those`() {
        val base = LakeDefinitionBodies.parse(ORDERS_V1, base = null, lockBasis = null)
        val next = { id: Int -> body(listOf(field(1, "id", "BIGINTEGER", "required" to true), field(id, "channel"))) }
        LakeDefinitionBodies.parse(next(5), base, lockBasis = base).field(5)?.name shouldBe "channel"
        val skipped = shouldThrow<DatapipelinesException> { LakeDefinitionBodies.parse(next(6), base, lockBasis = base) }
        (skipped.details["path"] to skipped.details["reason"]) shouldBe ("fields[1].id" to "id_sequence")
    }

    @Test
    fun `the canonical JSON is stable - optional keys omitted, fixed key order`() {
        val parsed = LakeDefinitionBodies.parse(ORDERS_V1, base = null, lockBasis = null)
        val canonical = LakeDefinitionBodies.toJson(parsed)
        canonical shouldBe LakeDefinitionBodies.toJson(LakeDefinitionBodies.parse(canonical, base = null, lockBasis = null))
        canonical shouldNotContain "\"precision\":null"
        canonical.startsWith("{\"fields\":[{\"id\":1,\"name\":\"id\",\"type\":\"BIGINTEGER\",\"required\":true") shouldBe true
    }

    @Test
    fun `Schema to body to Schema round-trips equal by field id`() {
        val parsed = LakeDefinitionBodies.parse(ORDERS_V1, base = null, lockBasis = null)
        val schema = LakeTableShape.schemaOf(parsed)
        val spec = LakeTableShape.specOf(parsed, schema)
        val order = LakeTableShape.sortOrderOf(parsed, schema)
        val back = LakeDefinitionBodies.fromIceberg(schema, spec, order)
        back shouldBe parsed.copy(fields = parsed.fields.map { it.copy(tags = emptyList()) })
        val again = LakeTableShape.schemaOf(back)
        again.sameSchema(schema) shouldBe true
        schema.columns().map { it.fieldId() } shouldBe listOf(1, 2, 3, 4)
        LakeTableShape.of(again, LakeTableShape.specOf(back, again), LakeTableShape.sortOrderOf(back, again)) shouldBe
            LakeTableShape.of(schema, spec, order)
    }

    @Test
    fun `every DP type maps to one Iceberg type and back`() {
        val columns =
            listOf(
                LakeFieldDefinition(1, "a", LogicalType.BOOLEAN),
                LakeFieldDefinition(2, "b", LogicalType.INTEGER),
                LakeFieldDefinition(3, "c", LogicalType.BIGINTEGER),
                LakeFieldDefinition(4, "d", LogicalType.DECIMAL),
                LakeFieldDefinition(5, "e", LogicalType.DECIMAL, precision = 15, scale = 0),
                LakeFieldDefinition(6, "f", LogicalType.BIGDECIMAL, precision = 16, scale = 4),
                LakeFieldDefinition(7, "g", LogicalType.STRING),
                LakeFieldDefinition(8, "h", LogicalType.BINARY),
                LakeFieldDefinition(9, "i", LogicalType.DATE),
                LakeFieldDefinition(10, "j", LogicalType.TIME),
                LakeFieldDefinition(11, "k", LogicalType.TIMESTAMP),
            )
        val body = LakeDefinitionBody(columns)
        LakeDefinitionBodies.fromIceberg(LakeTableShape.schemaOf(body), PartitionSpec.unpartitioned(), SortOrder.unsorted()) shouldBe body
    }

    @Test
    fun `an Iceberg type outside the closed list is refused on the way back`() {
        val schema = Schema(Types.NestedField.optional(1, "local", Types.TimestampType.withoutZone()))
        val refusal =
            shouldThrow<DatapipelinesException> {
                LakeDefinitionBodies.fromIceberg(schema, PartitionSpec.unpartitioned(), SortOrder.unsorted())
            }
        (refusal.details["path"] to refusal.details["reason"]) shouldBe ("fields[0].type" to "unsupported_type")
    }

    companion object {
        private fun one(vararg extra: Pair<String, Any?>) = body(listOf(field(1, "a", "STRING", *extra)))

        private fun typed(
            type: String,
            vararg extra: Pair<String, Any?>,
        ) = body(listOf(field(1, "a", type, *extra)))

        private fun refuse(
            rule: String,
            json: String,
            path: String,
            reason: String,
        ) = Arguments.of(rule, json, path, reason)

        @JvmStatic
        @Suppress("LongMethod") // one refusal case per rule, as a table
        fun refusals(): List<Arguments> =
            listOf(
                refuse(
                    "over the byte cap",
                    body(listOf(field(1, "a", "STRING", "doc" to "x".repeat(LakeDefinitionBodies.MAX_BODY_BYTES)))),
                    "$",
                    "too_large",
                ),
                refuse("malformed JSON", "{\"fields\": [", "$", "malformed_json"),
                refuse("duplicate key", "{\"fields\": [], \"fields\": []}", "$", "malformed_json"),
                refuse("not an object", "[]", "$", "not_object"),
                refuse("unknown root key", body(listOf(field(1, "a")), extra = mapOf("name" to "t")), "name", "unknown_key"),
                refuse("unknown field key", one("nullable" to true), "fields[0].nullable", "unknown_key"),
                refuse(
                    "unknown partition key",
                    body(
                        listOf(field(1, "a")),
                        listOf(
                            mapOf(
                                "field_id" to 1,
                                "transform" to "identity",
                                "x" to 1,
                            ),
                        ),
                    ),
                    "partition[0].x",
                    "unknown_key",
                ),
                refuse("fields missing", "{}", "fields", "missing"),
                refuse("fields not an array", "{\"fields\": {}}", "fields", "wrong_type"),
                refuse("no field", body(emptyList()), "fields", "empty"),
                refuse("too many fields", body((1..LakeDefinitionRules.MAX_FIELDS + 1).map { field(it, "c$it") }), "fields", "too_many"),
                refuse(
                    "id not an integer",
                    body(listOf(mapOf("id" to 1.5, "name" to "a", "type" to "STRING"))),
                    "fields[0].id",
                    "wrong_type",
                ),
                refuse("id below 1", body(listOf(field(0, "a"))), "fields[0].id", "out_of_range"),
                refuse("duplicate id", body(listOf(field(1, "a"), field(1, "b"))), "fields[1].id", "duplicate"),
                refuse("id out of sequence", body(listOf(field(2, "a"))), "fields[0].id", "id_sequence"),
                refuse("name grammar", body(listOf(field(1, "Amount"))), "fields[0].name", "grammar"),
                refuse("duplicate name", body(listOf(field(1, "a"), field(2, "a"))), "fields[1].name", "duplicate"),
                refuse("type outside the closed list", typed("FLOAT"), "fields[0].type", "unknown_type"),
                refuse("NULL is not a column type", typed("NULL"), "fields[0].type", "unknown_type"),
                refuse("required not a boolean", one("required" to "yes"), "fields[0].required", "wrong_type"),
                refuse(
                    "precision on a non-decimal",
                    typed("STRING", "precision" to 4, "scale" to 0),
                    "fields[0].precision",
                    "not_applicable",
                ),
                refuse("DECIMAL scale without precision", typed("DECIMAL", "scale" to 2), "fields[0].precision", "missing"),
                refuse("DECIMAL precision without scale", typed("DECIMAL", "precision" to 10), "fields[0].scale", "missing"),
                refuse(
                    "DECIMAL precision over 15",
                    typed("DECIMAL", "precision" to 16, "scale" to 2),
                    "fields[0].precision",
                    "out_of_range",
                ),
                refuse("scale over precision", typed("DECIMAL", "precision" to 4, "scale" to 5), "fields[0].scale", "out_of_range"),
                refuse("BIGDECIMAL without precision", typed("BIGDECIMAL"), "fields[0].precision", "missing"),
                refuse(
                    "BIGDECIMAL precision 15",
                    typed("BIGDECIMAL", "precision" to 15, "scale" to 0),
                    "fields[0].precision",
                    "out_of_range",
                ),
                refuse(
                    "BIGDECIMAL precision 39",
                    typed("BIGDECIMAL", "precision" to 39, "scale" to 0),
                    "fields[0].precision",
                    "out_of_range",
                ),
                refuse("doc over the cap", one("doc" to "x".repeat(LakeDefinitionRules.MAX_DOC_CHARS + 1)), "fields[0].doc", "too_long"),
                refuse("doc with a control character", one("doc" to "a\u0007b"), "fields[0].doc", "control_character"),
                refuse("doc not a string", one("doc" to 3), "fields[0].doc", "wrong_type"),
                refuse("too many tags", one("tags" to (0..LakeDefinitionRules.MAX_TAGS).map { "t$it" }), "fields[0].tags", "too_many"),
                refuse("tag grammar", one("tags" to listOf("PII")), "fields[0].tags[0]", "grammar"),
                refuse("duplicate tag", one("tags" to listOf("pii", "pii")), "fields[0].tags[1]", "duplicate"),
                refuse("tag not a string", one("tags" to listOf(1)), "fields[0].tags[0]", "wrong_type"),
                refuse(
                    "too many partition fields",
                    body(
                        (1..9).map {
                            field(it, "c$it")
                        },
                        (1..9).map { partition(it, "identity") },
                    ),
                    "partition",
                    "too_many",
                ),
                refuse(
                    "partition on an unknown field",
                    body(listOf(field(1, "a")), listOf(partition(2, "identity"))),
                    "partition[0].field_id",
                    "unknown_field",
                ),
                refuse("unknown transform", body(listOf(field(1, "a")), listOf(partition(1, "void"))), "partition[0].transform", "grammar"),
                refuse(
                    "bucket parameter 0",
                    body(listOf(field(1, "a")), listOf(partition(1, "bucket[0]"))),
                    "partition[0].transform",
                    "out_of_range",
                ),
                refuse(
                    "truncate parameter over the cap",
                    body(listOf(field(1, "a")), listOf(partition(1, "truncate[10001]"))),
                    "partition[0].transform",
                    "out_of_range",
                ),
                refuse(
                    "day on a string",
                    body(listOf(field(1, "a")), listOf(partition(1, "day"))),
                    "partition[0].transform",
                    "unsupported_type",
                ),
                refuse(
                    "duplicate partition field",
                    body(listOf(field(1, "a")), listOf(partition(1, "identity"), partition(1, "identity"))),
                    "partition[1]",
                    "duplicate",
                ),
                refuse(
                    "two time transforms on one field",
                    body(listOf(field(1, "t", "TIMESTAMP")), listOf(partition(1, "day"), partition(1, "hour"))),
                    "partition[1]",
                    "redundant",
                ),
                refuse(
                    "identifier on an unknown field",
                    body(listOf(field(1, "a", "STRING", "required" to true)), identifiers = listOf(2)),
                    "identifier_field_ids[0]",
                    "unknown_field",
                ),
                refuse(
                    "identifier listed twice",
                    body(listOf(field(1, "a", "STRING", "required" to true)), identifiers = listOf(1, 1)),
                    "identifier_field_ids[1]",
                    "duplicate",
                ),
                refuse(
                    "identifier not required",
                    body(listOf(field(1, "a")), identifiers = listOf(1)),
                    "identifier_field_ids[0]",
                    "not_required",
                ),
                refuse(
                    "identifier on an approximate DECIMAL",
                    body(listOf(field(1, "a", "DECIMAL", "required" to true)), identifiers = listOf(1)),
                    "identifier_field_ids[0]",
                    "unsupported_type",
                ),
            )

        @JvmStatic
        fun admissions(): List<Arguments> =
            listOf(
                Arguments.of("the orders fixture", ORDERS_V1),
                Arguments.of("exactly the field cap", body((1..LakeDefinitionRules.MAX_FIELDS).map { field(it, "c$it") })),
                Arguments.of("approximate DECIMAL", body(listOf(field(1, "a"), field(2, "b", "DECIMAL")))),
                Arguments.of("DECIMAL precision 15", typed("DECIMAL", "precision" to 15, "scale" to 15)),
                Arguments.of(
                    "BIGDECIMAL precision 16 and 38",
                    body(
                        listOf(
                            field(1, "a", "BIGDECIMAL", "precision" to 16, "scale" to 0),
                            field(
                                2,
                                "b",
                                "BIGDECIMAL",
                                "precision" to 38,
                                "scale" to 38,
                            ),
                        ),
                    ),
                ),
                Arguments.of("doc at the cap, newline and tab", one("doc" to "x".repeat(LakeDefinitionRules.MAX_DOC_CHARS - 2) + "\n\t")),
                Arguments.of("tags at the cap", one("tags" to (1..LakeDefinitionRules.MAX_TAGS).map { "t$it" })),
                Arguments.of("eight partition fields", body((1..8).map { field(it, "c$it") }, (1..8).map { partition(it, "identity") })),
                Arguments.of(
                    "every transform",
                    body(
                        listOf(field(1, "t", "TIMESTAMP"), field(2, "d", "DATE"), field(3, "s"), field(4, "n", "INTEGER")),
                        listOf(partition(1, "hour"), partition(2, "month"), partition(3, "truncate[10000]"), partition(4, "bucket[1]")),
                    ),
                ),
                Arguments.of(
                    "a time and an identity transform on one field",
                    body(listOf(field(1, "t", "TIMESTAMP")), listOf(partition(1, "day"), partition(1, "identity"))),
                ),
                Arguments.of(
                    "identifier on a required exact column",
                    body(listOf(field(1, "a", "BIGINTEGER", "required" to true)), identifiers = listOf(1)),
                ),
                Arguments.of("null optional keys", body(listOf(field(1, "a", "STRING", "doc" to null, "tags" to null)), partition = null)),
            )
    }
}
