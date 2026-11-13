package co.datapipelines.application.dependencies.dashboard

import co.datapipelines.parameters.ParameterDefinition
import co.datapipelines.parameters.ParameterKind
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

/**
 * #873 A34 — the dashboard → parameter-set edge's `parameter_names` constraint, evaluated against the set version the
 * edge reaches: every bound name the set does not declare is one violation, in the constraint's (sorted) order. The
 * walker has no edge diagnostic slot yet (G4 projects one), so [DashboardReleaseProvider.violations] is the evaluation.
 */
class DashboardReleaseProviderTest {
    private fun set(vararg names: String) =
        ParameterSetBody(
            displayName = "Filters",
            parameters = names.map { ParameterDefinition(name = it, label = it, type = LogicalType.STRING, kind = ParameterKind.INPUT) },
        )

    @Test
    fun `every bound name the set declares - no violation`() {
        DashboardReleaseProvider.violations(listOf("country", "year"), set("year", "country", "unused")).shouldBeEmpty()
    }

    @Test
    fun `a bound name the set lacks is a violation - each one, in the constraint's order`() {
        DashboardReleaseProvider.violations(listOf("country", "region", "year"), set("year")) shouldContainExactly
            listOf("country", "region")
    }

    @Test
    fun `no bound name constrains nothing - even over an empty set`() {
        DashboardReleaseProvider.violations(emptyList(), set()).shouldBeEmpty()
    }
}
