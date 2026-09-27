package co.datapipelines.parameters

import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The catalogue's two readings that could drift silently: the record's §3.7 control table (the
 * derived default first), and the temporal-pattern rule, which reads the fields a compiled
 * `DateTimeFormatter` prints from its own description — pinned letter by letter here, so a JDK that
 * renames them turns this red instead of letting a `DATE` show hours.
 */
class PresentationCatalogueTest {
    private fun input(
        type: LogicalType,
        pattern: String,
    ) = ParameterDefinition(
        name = "p",
        label = "P",
        type = type,
        kind = ParameterKind.INPUT,
        presentation = Presentation(format = DisplayFormat(pattern = pattern)),
    )

    @Test
    fun `every time letter is refused on a DATE, every date letter on a TIME, and both are fine on a TIMESTAMP`() {
        listOf("HH", "kk", "KK", "hh", "a", "mm", "ss", "SSS", "n", "A").forEach { letter ->
            withClue("DATE with '$letter'") {
                PresentationCatalogue.check(input(LogicalType.DATE, "yyyy-MM-dd $letter")).map { it.details["reason"] } shouldBe
                    listOf("time_field_on_date")
            }
            withClue("TIMESTAMP with '$letter'") {
                PresentationCatalogue.check(input(LogicalType.TIMESTAMP, "yyyy-MM-dd $letter")) shouldBe
                    emptyList()
            }
        }
        listOf("yyyy", "uuuu", "MM", "dd", "DDD", "EEE", "YYYY").forEach { letter ->
            withClue("TIME with '$letter'") {
                PresentationCatalogue.check(input(LogicalType.TIME, "HH:mm $letter")).map { it.details["reason"] } shouldBe
                    listOf("date_field_on_time")
            }
        }
        PresentationCatalogue.check(input(LogicalType.DATE, "dd MMM yyyy")) shouldBe emptyList()
        PresentationCatalogue.check(input(LogicalType.TIME, "HH:mm:ss")) shouldBe emptyList()
    }

    @Test
    fun `the control table - the derived default first, row by row`() {
        fun first(
            kind: ParameterKind,
            cardinality: ParameterCardinality,
            type: LogicalType,
        ) = PresentationCatalogue.controlsFor(kind, cardinality, type).first()
        first(ParameterKind.INPUT, ParameterCardinality.SINGLE, LogicalType.STRING) shouldBe PresentationControl.TEXT
        first(ParameterKind.INPUT, ParameterCardinality.SINGLE, LogicalType.BIGDECIMAL) shouldBe PresentationControl.NUMBER
        first(ParameterKind.INPUT, ParameterCardinality.SINGLE, LogicalType.BOOLEAN) shouldBe PresentationControl.TOGGLE
        first(ParameterKind.INPUT, ParameterCardinality.SINGLE, LogicalType.DATE) shouldBe PresentationControl.CALENDAR
        first(ParameterKind.INPUT, ParameterCardinality.SINGLE, LogicalType.TIME) shouldBe PresentationControl.CLOCK
        first(ParameterKind.INPUT, ParameterCardinality.SINGLE, LogicalType.TIMESTAMP) shouldBe PresentationControl.DATETIME
        first(ParameterKind.SELECT, ParameterCardinality.SINGLE, LogicalType.STRING) shouldBe PresentationControl.DROPDOWN
        PresentationCatalogue.controlsFor(ParameterKind.SELECT, ParameterCardinality.MULTI, LogicalType.STRING) shouldBe
            listOf(PresentationControl.DROPDOWN, PresentationControl.CHECKBOXES, PresentationControl.LIST)
        // Every catalogued control is reachable from some row — the enums.md §29 table has no orphan.
        val reachable =
            ParameterKind.entries.flatMap { kind ->
                ParameterCardinality.entries.flatMap { card ->
                    LogicalType.entries.flatMap { PresentationCatalogue.controlsFor(kind, card, it) }
                }
            }
        reachable.toSet() shouldBe PresentationControl.entries.toSet()
    }
}
