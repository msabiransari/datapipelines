package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [Datasource.normalizeIncludeSchemas] — the ONE lowercase rule of the §7A include-schemas
 * allowlist, shared by the registry's save boundary (every programmatic write crosses it) and
 * the repository's read boundary (rows that landed by restore or a manual JSONB edit bypass
 * save; an unnormalized entry there would silently never match — inert, not rejected).
 */
class DatasourceTest {
    @Test
    fun `include-schemas normalization trims and lowercases every entry`() {
        Datasource.normalizeIncludeSchemas(listOf(" APEX_Reporting ", "Sales", "my_app")) shouldBe
            listOf("apex_reporting", "sales", "my_app")
    }

    @Test
    fun `include-schemas normalization of an empty allowlist is the empty list`() {
        Datasource.normalizeIncludeSchemas(emptyList()) shouldBe emptyList()
    }
}
