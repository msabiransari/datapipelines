package co.datapipelines.lake.definitions

import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.expressions.Expressions
import org.apache.iceberg.expressions.Term

/**
 * The supported partition transforms (lake.md §1), from their Iceberg strings to the two builders that take them:
 * a new table's spec and a partition evolution. The strings are already validated by [LakeDefinitionRules].
 */
internal object LakeTransforms {
    private val PARAMETERISED = Regex("^(bucket|truncate)\\[([0-9]+)]$")

    fun addTo(
        builder: PartitionSpec.Builder,
        column: String,
        transform: String,
    ) {
        when (transform) {
            "identity" -> {
                builder.identity(column)
            }

            "year" -> {
                builder.year(column)
            }

            "month" -> {
                builder.month(column)
            }

            "day" -> {
                builder.day(column)
            }

            "hour" -> {
                builder.hour(column)
            }

            else -> {
                val (kind, parameter) = parameterised(transform)
                if (kind == "bucket") builder.bucket(column, parameter) else builder.truncate(column, parameter)
            }
        }
    }

    fun term(
        column: String,
        transform: String,
    ): Term =
        when (transform) {
            "identity" -> {
                Expressions.ref<Any>(column)
            }

            "year" -> {
                Expressions.year<Any>(column)
            }

            "month" -> {
                Expressions.month<Any>(column)
            }

            "day" -> {
                Expressions.day<Any>(column)
            }

            "hour" -> {
                Expressions.hour<Any>(column)
            }

            else -> {
                val (kind, parameter) = parameterised(transform)
                if (kind == "bucket") Expressions.bucket<Any>(column, parameter) else Expressions.truncate<Any>(column, parameter)
            }
        }

    private fun parameterised(transform: String): Pair<String, Int> {
        val match = requireNotNull(PARAMETERISED.matchEntire(transform)) { "unsupported transform '$transform'" }
        return match.groupValues[1] to match.groupValues[2].toInt()
    }
}
