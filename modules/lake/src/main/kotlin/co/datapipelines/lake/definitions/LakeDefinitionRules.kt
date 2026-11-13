package co.datapipelines.lake.definitions

import co.datapipelines.lake.definitions.LakeDefinitionErrors.invalidBody
import co.datapipelines.typesystem.LogicalType
import org.apache.iceberg.transforms.Transforms
import org.apache.iceberg.types.Types

/**
 * The body rules of lake.md §1, each refused as `lake.definition.invalid_body` with `details.path` naming the element
 * and `details.reason` naming the rule. The first broken rule is reported; nothing is stored.
 */
@Suppress("TooManyFunctions", "ThrowsCount") // one function per rule family; each broken rule is its own named refusal
object LakeDefinitionRules {
    const val MAX_FIELDS = 500
    const val MAX_DOC_CHARS = 1_000
    const val MAX_TAGS = 16
    const val MAX_PARTITION_FIELDS = 8
    const val MAX_TRANSFORM_PARAMETER = 10_000
    const val MAX_SORT_FIELDS = 4

    private val NAME = Regex("^[a-z0-9_]{1,63}$")
    private val TRANSFORM = Regex("^(identity|year|month|day|hour|bucket\\[([0-9]{1,5})]|truncate\\[([0-9]{1,5})])$")

    /** The regex groups after the whole match and the transform: `bucket[N]`'s N, then `truncate[N]`'s. */
    private const val TRANSFORM_PARAMETER_GROUPS = 2

    private val TIME_TRANSFORMS = setOf("year", "month", "day", "hour")

    private const val SORT = LakeDefinitionBodies.SORT_FIELD_IDS

    /**
     * Validates [body]. With no [base] (a table's first definition) the field ids must be exactly 1..n in listed order,
     * which is how Iceberg numbers a table it creates. With a [base], an id the base lacks is a new column, and new
     * columns take consecutive ids after the base's highest, in listed order (Iceberg's `lastColumnId + 1`).
     *
     * The sort columns are judged last, by [sortRules] against [lockBasis] alone, never against [base]: the lock's
     * basis is the table's first version ever released, which a discard does not undo, while [base] is the current
     * release, which a discard can remove.
     */
    fun validate(
        body: LakeDefinitionBody,
        base: LakeDefinitionBody?,
        lockBasis: LakeDefinitionBody?,
    ) {
        if (body.fields.isEmpty()) throw invalidBody("fields", "empty", "a table has at least one field")
        if (body.fields.size > MAX_FIELDS) throw invalidBody("fields", "too_many", "at most $MAX_FIELDS fields")
        val ids = HashSet<Int>()
        val names = HashSet<String>()
        body.fields.forEachIndexed { i, field ->
            val path = "fields[$i]"
            if (field.id < 1) throw invalidBody("$path.id", "out_of_range", "a field id is at least 1")
            if (!ids.add(field.id)) throw invalidBody("$path.id", "duplicate", "field id ${field.id} is used twice")
            if (!NAME.matches(field.name)) throw invalidBody("$path.name", "grammar", "a field name is [a-z0-9_]{1,63}")
            if (!names.add(field.name)) throw invalidBody("$path.name", "duplicate", "field name ${field.name} is used twice")
            typeRules(field, path)
            docRules(field.doc, "$path.doc")
            tagRules(field.tags, "$path.tags")
        }
        idRules(body, base)
        partitionRules(body)
        identifierRules(body)
        sortRules(body, lockBasis)
    }

    /**
     * The sort-column rule (lake.md §1, dp-lake design record §11a call 36 items 1 and 3), also run on its own by a
     * release (D3 of brief 935). [lockBasis] is the body of the table's first version ever released, RELEASED or
     * DISCARDED; null when the table has never been released.
     * - No lock basis: the key is required, 1 to [MAX_SORT_FIELDS] distinct ids of the body's fields, none `BINARY`
     *   and, for now, none an approximate `DECIMAL` (a floating-point column; #971 lifts that refusal).
     * - A lock basis: the list must be exactly the basis's, the same ids in the same order. A basis without the key
     *   (a legacy table) admits only a body without it.
     */
    fun sortRules(
        body: LakeDefinitionBody,
        lockBasis: LakeDefinitionBody?,
    ) {
        val ids = body.sortFieldIds
        if (lockBasis == null && ids.isEmpty()) {
            throw invalidBody(SORT, "missing", "a table has 1 to $MAX_SORT_FIELDS sort columns, fixed by its first release")
        }
        if (ids.size > MAX_SORT_FIELDS) throw invalidBody(SORT, "too_many", "at most $MAX_SORT_FIELDS sort columns")
        val seen = HashSet<Int>()
        ids.forEachIndexed { i, id ->
            val path = "$SORT[$i]"
            val field = body.field(id) ?: throw invalidBody(path, "unknown_field", "no field has id $id")
            if (!seen.add(id)) throw invalidBody(path, "duplicate", "sort column $id listed twice")
            if (field.icebergType is Types.BinaryType) {
                throw invalidBody(path, "unsupported_type", "a BINARY column cannot be a sort column")
            }
            if (field.icebergType is Types.DoubleType) {
                // Temporary (owner ruling 2026-11-13T18:23:58Z, lifted by #971): the engine that will order the rows
                // returns every -0.0 of a floating ORDER BY key as 0.0 (duckdb/duckdb#25417).
                throw invalidBody(path, "unsupported_type", "a floating-point column (an approximate DECIMAL) cannot be a sort column")
            }
        }
        if (lockBasis != null && ids != lockBasis.sortFieldIds) {
            val remedy =
                if (lockBasis.sortFieldIds.isEmpty()) {
                    "this table was released without sort columns and stays unsorted"
                } else {
                    "they are ${lockBasis.sortFieldIds}; another order needs a replacement table"
                }
            throw invalidBody(SORT, "locked", "sort columns are fixed by the table's first release: $remedy")
        }
    }

    private fun typeRules(
        field: LakeFieldDefinition,
        path: String,
    ) {
        val (precision, scale) = field.precision to field.scale
        when (field.type) {
            LogicalType.DECIMAL -> {
                if (precision != null || scale != null) {
                    decimal(precision, scale, 1..LakeTypes.MAX_DECIMAL_PRECISION, path)
                }
            }

            LogicalType.BIGDECIMAL -> {
                if (precision == null) throw invalidBody("$path.precision", "missing", "BIGDECIMAL needs a precision (16..38)")
                decimal(precision, scale, (LakeTypes.MAX_DECIMAL_PRECISION + 1)..LakeTypes.MAX_BIGDECIMAL_PRECISION, path)
            }

            else -> {
                if (precision != null || scale != null) {
                    throw invalidBody("$path.precision", "not_applicable", "only DECIMAL and BIGDECIMAL take a precision and scale")
                }
            }
        }
    }

    private fun decimal(
        precision: Int?,
        scale: Int?,
        range: IntRange,
        path: String,
    ) {
        if (precision == null) throw invalidBody("$path.precision", "missing", "a scale needs a precision")
        if (precision !in range) throw invalidBody("$path.precision", "out_of_range", "precision must be in $range")
        if (scale == null) throw invalidBody("$path.scale", "missing", "a precision needs a scale")
        if (scale !in 0..precision) throw invalidBody("$path.scale", "out_of_range", "scale must be in 0..$precision")
    }

    private fun docRules(
        doc: String?,
        path: String,
    ) {
        if (doc == null) return
        if (doc.length > MAX_DOC_CHARS) throw invalidBody(path, "too_long", "at most $MAX_DOC_CHARS characters")
        if (doc.any { it.isISOControl() && it != '\n' && it != '\t' }) {
            throw invalidBody(path, "control_character", "a doc holds no control characters but newline and tab")
        }
    }

    private fun tagRules(
        tags: List<String>,
        path: String,
    ) {
        if (tags.size > MAX_TAGS) throw invalidBody(path, "too_many", "at most $MAX_TAGS tags")
        val seen = HashSet<String>()
        tags.forEachIndexed { i, tag ->
            if (!NAME.matches(tag)) throw invalidBody("$path[$i]", "grammar", "a tag is [a-z0-9_]{1,63}")
            if (!seen.add(tag)) throw invalidBody("$path[$i]", "duplicate", "tag $tag is used twice")
        }
    }

    private fun idRules(
        body: LakeDefinitionBody,
        base: LakeDefinitionBody?,
    ) {
        val baseIds =
            base
                ?.fields
                ?.map { it.id }
                ?.toSet()
                .orEmpty()
        var next = (baseIds.maxOrNull() ?: 0) + 1
        body.fields.forEachIndexed { i, field ->
            if (field.id in baseIds) return@forEachIndexed
            if (field.id != next) {
                val rule = if (base == null) "ids are 1..n in listed order" else "a new field takes id $next"
                throw invalidBody("fields[$i].id", "id_sequence", "field ${field.name} has id ${field.id}; $rule")
            }
            next++
        }
    }

    private fun partitionRules(body: LakeDefinitionBody) {
        if (body.partition.size > MAX_PARTITION_FIELDS) {
            throw invalidBody("partition", "too_many", "at most $MAX_PARTITION_FIELDS partition fields")
        }
        val seen = HashSet<LakePartitionField>()
        val timeTransformed = HashSet<Int>()
        body.partition.forEachIndexed { i, p ->
            val path = "partition[$i]"
            val field = body.field(p.fieldId) ?: throw invalidBody("$path.field_id", "unknown_field", "no field has id ${p.fieldId}")
            val match = TRANSFORM.matchEntire(p.transform) ?: throw invalidBody("$path.transform", "grammar", "unknown transform")
            val parameterText = match.groupValues.drop(TRANSFORM_PARAMETER_GROUPS).firstOrNull { it.isNotEmpty() }
            val parameter = parameterText?.toIntOrNull()
            if (parameter != null && parameter !in 1..MAX_TRANSFORM_PARAMETER) {
                throw invalidBody("$path.transform", "out_of_range", "a transform parameter is in 1..$MAX_TRANSFORM_PARAMETER")
            }
            if (parameter != null && parameterText != parameter.toString()) {
                throw invalidBody("$path.transform", "noncanonical", "a transform parameter has no leading zeroes")
            }
            if (!Transforms.fromString(p.transform).canTransform(field.icebergType)) {
                throw invalidBody("$path.transform", "unsupported_type", "${p.transform} cannot partition a ${field.typeLabel} field")
            }
            if (!seen.add(p)) throw invalidBody(path, "duplicate", "partition field listed twice")
            if (p.transform in TIME_TRANSFORMS && !timeTransformed.add(p.fieldId)) {
                throw invalidBody(path, "redundant", "field ${field.name} has more than one time transform")
            }
        }
    }

    private fun identifierRules(body: LakeDefinitionBody) {
        val seen = HashSet<Int>()
        body.identifierFieldIds.forEachIndexed { i, id ->
            val path = "identifier_field_ids[$i]"
            val field = body.field(id) ?: throw invalidBody(path, "unknown_field", "no field has id $id")
            if (!seen.add(id)) throw invalidBody(path, "duplicate", "identifier field $id listed twice")
            if (!field.required) throw invalidBody(path, "not_required", "identifier field ${field.name} must be required")
            if (field.icebergType is Types.DoubleType) {
                throw invalidBody(path, "unsupported_type", "an approximate DECIMAL cannot be an identifier field")
            }
        }
    }
}
