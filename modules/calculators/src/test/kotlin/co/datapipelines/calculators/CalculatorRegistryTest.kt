package co.datapipelines.calculators

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * The invariants every catalog entry obeys, asserted over the whole registry rather than one kind
 * at a time — so the twenty-fourth kind inherits them without anyone remembering to.
 */
class CalculatorRegistryTest {
    @Test
    fun `the catalog ships the kinds the ratified design names`() {
        // §0.4's list, verbatim and in its own order. A kind quietly dropped, or one added without
        // an owner ruling, shows up here rather than in a support ticket.
        CalculatorRegistry.NAMES.sorted() shouldContainExactly
            listOf(
                "add_business_days",
                "add_days",
                "add_months",
                "coalesce",
                "date_diff",
                "date_format",
                "date_parse",
                "date_trunc",
                "day_of_week",
                "days_in_month",
                "fiscal_quarter",
                "fiscal_year",
                "if_null",
                "iso_week",
                "iso_year",
                "map",
                "percent_change",
                "period_end",
                "period_start",
                "prior_period",
                "quarter_of_year",
                "round",
                "tz_shift",
            ).sorted()
    }

    @Test
    fun `every kind name obeys the same identifier shape as a context key`() {
        val malformed = CalculatorRegistry.NAMES.filterNot { NAME.matches(it) }
        malformed.shouldBeEmpty()
    }

    @Test
    fun `every kind declares a display name, a description and at least one input`() {
        CalculatorRegistry.KINDS.forEach { kind ->
            withClue(kind.kind) {
                kind.displayName.isNotBlank() shouldBe true
                kind.description.isNotBlank() shouldBe true
                kind.inputs.size shouldBeGreaterThanOrEqual 1
            }
        }
    }

    @Test
    fun `no kind declares a duplicate input name`() {
        val offenders =
            CalculatorRegistry.KINDS.filter { kind ->
                kind.inputs
                    .map { it.name }
                    .toSet()
                    .size != kind.inputs.size
            }
        withClue("A kind with two inputs of one name cannot be given both") {
            offenders.map { it.kind }.shouldBeEmpty()
        }
    }

    @Test
    fun `every input name obeys the identifier shape and declares a description`() {
        val offenders =
            CalculatorRegistry.KINDS.flatMap { kind ->
                kind.inputs.filter { !NAME.matches(it.name) || it.description.isBlank() }.map { "${kind.kind}.${it.name}" }
            }
        offenders.shouldBeEmpty()
    }

    @Test
    fun `required inputs come before optional ones`() {
        // An author reads a signature left to right and stops at the first `?`. A required input
        // hiding behind an optional one is a signature that lies about what is mandatory.
        val offenders =
            CalculatorRegistry.KINDS.filter { kind ->
                val firstOptional = kind.inputs.indexOfFirst { !it.required }
                firstOptional >= 0 && kind.inputs.drop(firstOptional).any { it.required }
            }
        offenders.map { it.kind }.shouldBeEmpty()
    }

    @Test
    fun `every optional input documents its default and every required one does not`() {
        val offenders =
            CalculatorRegistry.KINDS.flatMap { kind ->
                kind.inputs
                    .filter { it.required == (it.defaultDescription != null) }
                    .map { "${kind.kind}.${it.name} required=${it.required} default=${it.defaultDescription}" }
            }
        withClue("An optional input with no documented default leaves an author guessing") {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `the open-ended variadic input is the last one - the fixed-role lists need not be`() {
        // §0.3 restricts `variadic` to the last input. That restriction exists for the ONE
        // open-ended case: `coalesce`, whose argument count is the authoring choice. Kinds whose
        // lists are named roles (`add_business_days`' weekends and holidays, `map`'s pairs)
        // declare more than one, and nothing about them is ambiguous — the refinement is recorded
        // in the module's KDoc and pinned here so it is a decision, not a drift.
        val coalesce = CalculatorRegistry.require("coalesce")
        coalesce.inputs.size shouldBe 1
        coalesce.inputs.single().isList shouldBe true

        CalculatorRegistry.KINDS.filter { it.inputs.count { input -> input.isList } > 1 }.map { it.kind } shouldContainExactly
            listOf("add_business_days", "map")
    }

    @Test
    fun `every declared type is one the value accessors can carry`() {
        val offenders =
            CalculatorRegistry.KINDS.flatMap { kind ->
                val outputs = listOfNotNull(kind.output).filterNot { it in CalculatorValues.SUPPORTED }.map { "${kind.kind} → $it" }
                val inputs =
                    kind.inputs
                        .mapNotNull { it.type }
                        .filterNot { it in CalculatorValues.SUPPORTED }
                        .map { "${kind.kind} input $it" }
                outputs + inputs
            }
        offenders.shouldBeEmpty()
    }

    @Test
    fun `every kind declares an example naming only its own inputs`() {
        val offenders =
            CalculatorRegistry.KINDS.flatMap { kind ->
                val declared = kind.inputs.map { it.name }.toSet()
                (kind.example.inputs.keys - declared).map { "${kind.kind}: example names '$it', which is not an input" }
            }
        offenders.shouldBeEmpty()
    }

    @Test
    fun `every example supplies every required input`() {
        val offenders =
            CalculatorRegistry.KINDS.flatMap { kind ->
                kind.inputs
                    .filter { it.required && it.name !in kind.example.inputs }
                    .map { "${kind.kind}: example omits required input '${it.name}'" }
            }
        offenders.shouldBeEmpty()
    }

    @Test
    fun `find and require agree, and an unknown kind is a refusal not a null dereference`() {
        CalculatorRegistry.find("fiscal_quarter") shouldNotBe null
        CalculatorRegistry.find("no_such_kind") shouldBe null
        io.kotest.assertions.throwables
            .shouldThrow<CalculatorEvaluationException> { CalculatorRegistry.require("no_such_kind") }
    }

    private companion object {
        /** §6.1's context-key shape — a kind name is written in the same places and reads the same. */
        val NAME = Regex("[a-z_][a-z0-9_]*")
    }
}
