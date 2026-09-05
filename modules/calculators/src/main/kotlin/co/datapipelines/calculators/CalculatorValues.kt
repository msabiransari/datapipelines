package co.datapipelines.calculators

import co.datapipelines.typesystem.LogicalType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * The Kotlin type each canonical type arrives as, and the accessors every kind reads through.
 *
 * The Context holds **typed Kotlin objects**, not JSON (pipeline-contract §7): a `DATE` is a
 * `LocalDate`, a `TIMESTAMP` an `Instant`, a `DECIMAL` a `BigDecimal`. A caller (the executor,
 * the validator's literal coercion) has already produced those types before [CalculatorKind.evaluate]
 * runs, so the accessors here are about **reporting a defect clearly**, not about coercing:
 * reaching one with the wrong runtime type means a caller skipped the coercion, and the
 * resulting message names the input rather than surfacing a `ClassCastException`.
 *
 * `INTEGER` is the one place a little tolerance is right: a JSON `2` may legitimately have been
 * coerced to `Int` or, through a `BIGINTEGER`-shaped path, to `Long`, and refusing the second
 * would be pedantry about a value that is exactly representable either way.
 */
object CalculatorValues {
    /** The canonical types a calculator input or output may declare. */
    val SUPPORTED: Set<LogicalType> =
        setOf(
            LogicalType.STRING,
            LogicalType.INTEGER,
            LogicalType.DECIMAL,
            LogicalType.BOOLEAN,
            LogicalType.DATE,
            LogicalType.TIMESTAMP,
        )

    /** The Kotlin class a value of [type] arrives as. */
    fun kotlinType(type: LogicalType): Class<*> =
        when (type) {
            LogicalType.STRING -> String::class.java
            LogicalType.INTEGER -> Number::class.java
            LogicalType.DECIMAL -> BigDecimal::class.java
            LogicalType.BOOLEAN -> Boolean::class.javaObjectType
            LogicalType.DATE -> LocalDate::class.java
            LogicalType.TIMESTAMP -> Instant::class.java
            else -> Any::class.java
        }

    fun string(
        values: Map<String, Any?>,
        name: String,
    ): String = require(values, name) as? String ?: wrongType(name, values[name], "a string")

    fun stringOr(
        values: Map<String, Any?>,
        name: String,
        fallback: String,
    ): String = (values[name] ?: return fallback) as? String ?: wrongType(name, values[name], "a string")

    fun date(
        values: Map<String, Any?>,
        name: String,
    ): LocalDate = require(values, name) as? LocalDate ?: wrongType(name, values[name], "a DATE")

    fun timestamp(
        values: Map<String, Any?>,
        name: String,
    ): Instant = require(values, name) as? Instant ?: wrongType(name, values[name], "a TIMESTAMP")

    fun int(
        values: Map<String, Any?>,
        name: String,
    ): Int = (require(values, name) as? Number)?.toInt() ?: wrongType(name, values[name], "an INTEGER")

    fun intOr(
        values: Map<String, Any?>,
        name: String,
        fallback: Int,
    ): Int = (values[name] ?: return fallback).let { it as? Number }?.toInt() ?: wrongType(name, values[name], "an INTEGER")

    fun decimal(
        values: Map<String, Any?>,
        name: String,
    ): BigDecimal = require(values, name).asDecimal(name)

    /** A LIST input's values, or an empty list when the author omitted an optional one. */
    fun list(
        values: Map<String, Any?>,
        name: String,
    ): List<Any?> =
        when (val value = values[name]) {
            null -> emptyList()
            is List<*> -> value
            else -> wrongType(name, value, "a JSON array")
        }

    private fun Any.asDecimal(name: String): BigDecimal =
        when (this) {
            is BigDecimal -> this

            // An INTEGER-typed reference bound to a DECIMAL input is the author's arithmetic, not
            // a defect: `round(:count, 0)` is legal and means what it says.
            is Number -> BigDecimal(this.toString())

            else -> wrongType(name, this, "a number")
        }

    private fun require(
        values: Map<String, Any?>,
        name: String,
    ): Any =
        values[name]
            ?: throw CalculatorEvaluationException(name, "Input '$name' is required and was null.")

    private fun wrongType(
        name: String,
        value: Any?,
        expected: String,
    ): Nothing =
        throw CalculatorEvaluationException(
            name,
            "Input '$name' must be $expected, but the resolved value was a ${value?.javaClass?.simpleName ?: "null"}.",
        )
}
