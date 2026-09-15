package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** pipeline-contract §12.12 — the `checks[]` release-check declarations, happy path and every failing case. */
class ChecksRulesTest {
    private val validator = Fixtures.validator()
    private val workspaceId = UUID.randomUUID()

    private fun failuresOf(pipeline: Pipeline): List<ValidationFailure> =
        validator.validate(pipeline, workspaceId).withCode(Validation.CHECK_INVALID)

    @Test
    fun `valid checks of every kind pass`() {
        val pipeline =
            Fixtures.pipeline(
                checks =
                    listOf(
                        Fixtures.check(
                            id = "row_count",
                            expected = CheckExpectation(kind = CheckExpectation.KIND_VALUE, value = 1_000_000.0, tolerance = 0.5),
                        ),
                        Fixtures.check(
                            id = "null_share",
                            expected = CheckExpectation(kind = CheckExpectation.KIND_RANGE, min = 0.0, max = 0.01),
                        ),
                        Fixtures.check(
                            id = "calendar_complete",
                            expected = CheckExpectation(kind = CheckExpectation.KIND_ROWS, rows = 0),
                        ),
                    ),
            )

        validator.validate(pipeline, workspaceId).failures.shouldBeEmpty()
    }

    @Test
    fun `a check with no binds and a check binding a declared parameter pass`() {
        val pipeline =
            Fixtures.pipeline(
                parameters = mapOf("start_date" to Parameter(LogicalType.DATE, required = true)),
                checks =
                    listOf(
                        Fixtures.check(id = "plain", sql = "SELECT COUNT(*) FROM orders"),
                        Fixtures.check(
                            id = "bounded",
                            sql = "SELECT COUNT(*) FROM orders WHERE created_at >= :start_date",
                        ),
                    ),
            )

        validator.validate(pipeline, workspaceId).failures.shouldBeEmpty()
    }

    @Test
    fun `a pipeline carries at most 20 checks`() {
        val checks = (1..ChecksRules.MAX_CHECKS + 1).map { Fixtures.check(id = "check_$it") }

        val failures = failuresOf(Fixtures.pipeline(checks = checks))

        failures.single().path shouldBe "checks"
        ChecksRules.MAX_CHECKS shouldBe 20
        // Twenty is legal — the bound is a ceiling, not a tripwire below it.
        validator.validate(Fixtures.pipeline(checks = checks.dropLast(1)), workspaceId).failures.shouldBeEmpty()
    }

    @Test
    fun `a check id must match the identifier grammar`() {
        listOf("Row Count", "row-count", "row.count", "a".repeat(64), "").forEach { id ->
            withClue(id) {
                val failures = failuresOf(Fixtures.pipeline(checks = listOf(Fixtures.check(id = id))))

                failures.single().path shouldBe "checks[0].id"
            }
        }
    }

    @Test
    fun `a reserved check id is refused`() {
        listOf("tempdb", "__internal__").forEach { id ->
            withClue(id) {
                val failures = failuresOf(Fixtures.pipeline(checks = listOf(Fixtures.check(id = id))))

                failures.single().path shouldBe "checks[0].id"
            }
        }
    }

    @Test
    fun `check ids are unique within the body`() {
        val pipeline =
            Fixtures.pipeline(checks = listOf(Fixtures.check(id = "dupe"), Fixtures.check(id = "dupe", name = "A different name.")))

        val failures = failuresOf(pipeline)

        failures.single().path shouldBe "checks[1].id"
        failures.single().details["value"] shouldBe "dupe"
    }

    @Test
    fun `a check name is 1 to 200 characters`() {
        listOf("", "   ", "x".repeat(ChecksRules.MAX_NAME_CHARS + 1)).forEach { name ->
            withClue(name) {
                val failures = failuresOf(Fixtures.pipeline(checks = listOf(Fixtures.check(name = name))))

                failures.single().path shouldBe "checks[0].name"
            }
        }
    }

    @Test
    fun `a check cannot read tempdb`() {
        val failures = failuresOf(Fixtures.pipeline(checks = listOf(Fixtures.check(datasource = "tempdb"))))

        failures.single().path shouldBe "checks[0].datasource"
    }

    @Test
    fun `a check datasource must resolve in the environment`() {
        listOf("", "pg-unregistered").forEach { datasource ->
            withClue(datasource) {
                val failures = failuresOf(Fixtures.pipeline(checks = listOf(Fixtures.check(datasource = datasource))))

                failures.single().path shouldBe "checks[0].datasource"
            }
        }
    }

    @Test
    fun `a check carries SQL`() {
        listOf("", "   ").forEach { sql ->
            withClue(sql) {
                val failures = failuresOf(Fixtures.pipeline(checks = listOf(Fixtures.check(sql = sql))))

                failures.single().path shouldBe "checks[0].sql"
            }
        }
    }

    @Test
    fun `a check has no rendering - interpolation is refused`() {
        val check = Fixtures.check(sql = "SELECT COUNT(*) FROM orders WHERE region = '\${region}'")

        val failures = failuresOf(Fixtures.pipeline(checks = listOf(check)))

        failures.single().path shouldBe "checks[0].sql"
    }

    @Test
    fun `every bind must name a declared parameter`() {
        val check = Fixtures.check(sql = "SELECT COUNT(*) FROM orders WHERE created_at >= :start_date AND region = :region")

        val failures = failuresOf(Fixtures.pipeline(checks = listOf(check)))

        failures.size shouldBe 2
        failures.forEach { it.path shouldBe "checks[0].sql" }
        failures.map { it.details["parameter"] } shouldBe listOf("start_date", "region")
    }

    @Test
    fun `a cast does not count as a bind`() {
        val check = Fixtures.check(sql = "SELECT SUM(amount::numeric) FROM orders WHERE created_at >= now()::date")

        validator.validate(Fixtures.pipeline(checks = listOf(check)), workspaceId).failures.shouldBeEmpty()
    }

    @Test
    fun `expected kind is the closed list`() {
        val check = Fixtures.check(expected = CheckExpectation(kind = "median"))

        val failures = failuresOf(Fixtures.pipeline(checks = listOf(check)))

        failures.single().path shouldBe "checks[0].expected.kind"
    }

    @Test
    fun `a value expectation needs its value`() {
        val check = Fixtures.check(expected = CheckExpectation(kind = CheckExpectation.KIND_VALUE))

        val failures = failuresOf(Fixtures.pipeline(checks = listOf(check)))

        failures.single().path shouldBe "checks[0].expected"
    }

    @Test
    fun `tolerance is absolute`() {
        val check = Fixtures.check(expected = CheckExpectation(kind = CheckExpectation.KIND_VALUE, value = 0.0, tolerance = -0.001))

        val failures = failuresOf(Fixtures.pipeline(checks = listOf(check)))

        failures.single().path shouldBe "checks[0].expected.tolerance"
    }

    @Test
    fun `a range expectation needs min and max`() {
        listOf(
            CheckExpectation(kind = CheckExpectation.KIND_RANGE),
            CheckExpectation(kind = CheckExpectation.KIND_RANGE, min = 0.0),
            CheckExpectation(kind = CheckExpectation.KIND_RANGE, max = 1.0),
        ).forEach { expected ->
            withClue(expected) {
                val failures = failuresOf(Fixtures.pipeline(checks = listOf(Fixtures.check(expected = expected))))

                failures.single().path shouldBe "checks[0].expected"
            }
        }
    }

    @Test
    fun `a range expectation needs min not above max`() {
        val check = Fixtures.check(expected = CheckExpectation(kind = CheckExpectation.KIND_RANGE, min = 1.0, max = 0.0))

        val failures = failuresOf(Fixtures.pipeline(checks = listOf(check)))

        failures.single().path shouldBe "checks[0].expected"
    }

    @Test
    fun `a rows expectation needs a non-negative count`() {
        listOf(
            CheckExpectation(kind = CheckExpectation.KIND_ROWS),
            CheckExpectation(kind = CheckExpectation.KIND_ROWS, rows = -1),
        ).forEach { expected ->
            withClue(expected) {
                val failures = failuresOf(Fixtures.pipeline(checks = listOf(Fixtures.check(expected = expected))))

                failures.single().path shouldBe "checks[0].expected"
            }
        }
    }

    @Test
    fun `failures are indexed - the second check's defect reports checks index 1`() {
        val pipeline = Fixtures.pipeline(checks = listOf(Fixtures.check(id = "fine"), Fixtures.check(id = "broken", name = "")))

        val failures = failuresOf(pipeline)

        failures.single().path shouldBe "checks[1].name"
    }
}
