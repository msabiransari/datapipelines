package co.datapipelines.visualization

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** pipeline-contract.md **§13.23** versus [DashboardErrorCodes] — both directions, the statuses per family. */
class DashboardErrorCodesSpecDriftTest {
    private val documented: Map<String, String> = CatalogSections.parse("### 13.23 Dashboards")

    @Test
    fun `the parse found the section - every sub-family has a row`() {
        documented.size shouldBeGreaterThan 20
        SUB_FAMILIES.forEach { part ->
            withClue("§13.23 has no row for 'dashboard.$part'") {
                documented.keys.any { it.startsWith("dashboard.$part") } shouldBe true
            }
        }
    }

    @Test
    fun `every code in §13-23 has a constant, and every constant is a row of §13-23`() {
        withClue("documented in §13.23, no DashboardErrorCodes constant") {
            (documented.keys - DashboardErrorCodes.ALL).sorted().shouldBeEmpty()
        }
        withClue("DashboardErrorCodes constants §13.23 does not define") {
            (DashboardErrorCodes.ALL - documented.keys).sorted().shouldBeEmpty()
        }
    }

    @Test
    fun `the documented statuses are the family's`() {
        documented
            .filterKeys { it.startsWith("dashboard.validation.") && it != DashboardErrorCodes.NAME_TAKEN }
            .filterValues { it != "400" }
            .keys
            .shouldBeEmpty()
        documented
            .filterKeys { it.startsWith("dashboard.version.") || it.startsWith("dashboard.release.") }
            .filterKeys { it !in RELEASE_PLAN_NON_CONFLICT }
            .filterValues { it != "409" }
            .keys
            .shouldBeEmpty()
        // The release-dependency plan's three non-409 codes (release-dependencies record §14, #517).
        documented[DashboardErrorCodes.RELEASE_PLAN_REQUIRED] shouldBe "428"
        documented[DashboardErrorCodes.RELEASE_PLAN_INVALID] shouldBe "400"
        documented[DashboardErrorCodes.RELEASE_DEPENDENCY_LIMIT] shouldBe "422"
        documented[DashboardErrorCodes.NAME_TAKEN] shouldBe "409"
        documented[DashboardErrorCodes.NOT_FOUND] shouldBe "404"
        documented[DashboardErrorCodes.IMPORT_ID_TAKEN] shouldBe "409"
        documented[DashboardErrorCodes.IMPORT_MISSING_DEPENDENCY] shouldBe "400"
        documented[DashboardErrorCodes.AUTHORING_DISABLED] shouldBe "403"
        documented[DashboardErrorCodes.RUNTIME_CONFIGURATION_STALE] shouldBe "409"
        documented[DashboardErrorCodes.RUNTIME_DEPENDENCY_MISSING] shouldBe "409"
        documented[DashboardErrorCodes.REFRESH_SATURATED] shouldBe "429"
        documented[DashboardErrorCodes.REFRESH_RESULT_TOO_LARGE] shouldBe "422"
        documented[DashboardErrorCodes.REFRESH_NOT_FOUND] shouldBe "404"
        documented[DashboardErrorCodes.KEY_KIND_REFUSED] shouldBe "403"
    }

    private companion object {
        /** The release-plan refusals whose status is not the family's 409 (record §14): 428, 400, 422. */
        val RELEASE_PLAN_NON_CONFLICT =
            setOf(
                DashboardErrorCodes.RELEASE_PLAN_REQUIRED,
                DashboardErrorCodes.RELEASE_PLAN_INVALID,
                DashboardErrorCodes.RELEASE_DEPENDENCY_LIMIT,
            )

        val SUB_FAMILIES =
            listOf("validation.", "version.", "release.", "import.", "authoring.", "runtime.", "refresh.", "key.", "not_found")
    }
}
