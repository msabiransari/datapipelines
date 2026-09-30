package co.datapipelines.application.dashboards

import co.datapipelines.application.dashboards.RefreshFixtures.bindLiteral
import co.datapipelines.application.dashboards.RefreshFixtures.bindParameter
import co.datapipelines.application.dashboards.RefreshFixtures.dashboard
import co.datapipelines.application.dashboards.RefreshFixtures.number
import co.datapipelines.application.dashboards.RefreshFixtures.occurrence
import co.datapipelines.application.dashboards.RefreshFixtures.source
import co.datapipelines.application.dashboards.RefreshFixtures.text
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ActionScope
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.LiteralValue
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [RefreshPlanner] — the sharing rule and the closure, proven without a database, a stream or an executor (spec §9
 * steps 3–5, the record's §5.2).
 *
 * The property under test is the one D53 sizes admission by: N sources collapse to the DISTINCT invocations, where
 * identity is pipeline release + parameters after the outgoing overrides — so `s1` and `s2` below share one execution
 * for three consumers, and a differing literal keeps two otherwise-identical sources apart.
 */
class RefreshPlannerTest {
    private val year = mapOf("year" to bindParameter("year"))

    @Test
    fun `two sources with the same release and the same resolved parameters are ONE execution for all their consumers`() {
        val body =
            dashboard(
                sources = listOf(source("s1", "p/a", year), source("s2", "p/a", year), source("s3", "p/b")),
                visualizations =
                    listOf(
                        occurrence("v1", inputs = mapOf("main" to "s1")),
                        occurrence("v2", inputs = mapOf("main" to "s2")),
                        occurrence("v3", inputs = mapOf("a" to "s3", "b" to "s1")),
                    ),
            )

        val plan = RefreshFixtures.plan(body, values = mapOf("year" to number(2026)))

        plan.invocations.map { it.sources } shouldContainExactly listOf(listOf("s1", "s2"), listOf("s3"))
        plan.invocations.first().consumers shouldContainExactly listOf("v1", "v2", "v3")
        plan.invocations.first().shared shouldBe true
        plan.invocations.last().shared shouldBe false
        plan.invocationFor("s2").id shouldBe plan.invocationFor("s1").id
    }

    @Test
    fun `a different release, a different resolved value, or a different type keeps invocations apart`() {
        val body =
            dashboard(
                sources =
                    listOf(
                        source("a", "p/a", year),
                        source("b", "p/b", year), // another release
                        source("c", "p/a", mapOf("year" to bindLiteral(number(2025)))), // another value
                        source("d", "p/a", mapOf("year" to bindLiteral(text("2026")))), // "2026" is not 2026
                    ),
                visualizations = listOf(occurrence("v", inputs = mapOf("a" to "a", "b" to "b", "c" to "c", "d" to "d"))),
            )

        val plan = RefreshFixtures.plan(body, values = mapOf("year" to number(2026)))

        plan.invocations.size shouldBe 4
    }

    @Test
    fun `the outgoing override wins over the control's value and can make two sources one`() {
        val body =
            dashboard(
                sources = listOf(source("a", "p/a", year), source("b", "p/a", mapOf("year" to bindLiteral(number(2000))))),
                visualizations = listOf(occurrence("v", inputs = mapOf("a" to "a", "b" to "b"))),
                overrides = mapOf("a" to mapOf("year" to LiteralValue(number(2000)))),
            )

        val plan = RefreshFixtures.plan(body, values = mapOf("year" to number(2026)))

        plan.invocations.single().parameters["year"] shouldBe number(2000)
        plan.invocations.single().sources shouldContainExactly listOf("a", "b")
    }

    @Test
    fun `object keys are canonical - the same literal object in another key order is the same invocation`() {
        val nodes = JsonNodeFactory.instance
        val first = nodes.objectNode().put("a", 1).put("b", 2)
        val second = nodes.objectNode().put("b", 2).put("a", 1)
        val body =
            dashboard(
                sources =
                    listOf(
                        source("x", "p/a", mapOf("f" to bindLiteral(first))),
                        source("y", "p/a", mapOf("f" to bindLiteral(second))),
                    ),
                visualizations = listOf(occurrence("v", inputs = mapOf("x" to "x", "y" to "y"))),
            )

        RefreshFixtures.plan(body).invocations.size shouldBe 1
    }

    @Test
    fun `a binding to a parameter with no value is left out - the pipeline's default or its own refusal applies`() {
        val body =
            dashboard(
                sources = listOf(source("s", "p/a", year)),
                visualizations = listOf(occurrence("v", inputs = mapOf("main" to "s"))),
            )

        RefreshFixtures
            .plan(body, values = emptyMap())
            .invocations
            .single()
            .parameters shouldBe emptyMap()
        RefreshFixtures
            .plan(body, values = mapOf("year" to JsonNodeFactory.instance.nullNode()))
            .invocations
            .single()
            .parameters shouldBe emptyMap()
    }

    @Test
    fun `scope targets runs only the sources the named targets read`() {
        val body =
            dashboard(
                sources = listOf(source("s1"), source("s2"), source("s3")),
                visualizations =
                    listOf(
                        occurrence("v1", inputs = mapOf("main" to "s1")),
                        occurrence("v2", inputs = mapOf("main" to "s2")),
                        occurrence("v3", inputs = mapOf("main" to "s3")),
                    ),
            )

        val plan = RefreshPlanner().plan(body, ActionScope.TARGETS, listOf("v3", "v1"), emptyMap())

        plan.targets shouldContainExactly listOf("v1", "v3") // dashboard order, not request order
        plan.invocations.flatMap { it.sources } shouldContainExactly listOf("s1", "s3")
    }

    @Test
    fun `a target that reads no source needs no execution`() {
        val body = dashboard(sources = emptyList(), visualizations = listOf(occurrence("static", inputs = emptyMap())))

        val plan = RefreshFixtures.plan(body)

        plan.targets shouldContainExactly listOf("static")
        plan.invocations shouldBe emptyList()
    }

    @Test
    fun `scope targets with no target, or a name that is no visualization, is refused with the validation codes`() {
        val body = dashboard(listOf(source("s")), listOf(occurrence("v", inputs = mapOf("main" to "s"))))

        shouldThrow<DatapipelinesException> { RefreshPlanner().plan(body, ActionScope.TARGETS, emptyList(), emptyMap()) }.code shouldBe
            DashboardErrorCodes.EMPTY_TARGETS
        shouldThrow<DatapipelinesException> { RefreshPlanner().plan(body, ActionScope.TARGETS, listOf("s"), emptyMap()) }.code shouldBe
            DashboardErrorCodes.TARGET_NOT_VISUALIZATION
    }

    @Test
    fun `a name echoed in a refusal is clipped`() {
        val body = dashboard(listOf(source("s")), listOf(occurrence("v", inputs = mapOf("main" to "s"))))

        val refusal =
            shouldThrow<DatapipelinesException> { RefreshPlanner().plan(body, ActionScope.TARGETS, listOf("x".repeat(500)), emptyMap()) }

        (refusal.details["name"] as String).length shouldBe 64
    }
}
