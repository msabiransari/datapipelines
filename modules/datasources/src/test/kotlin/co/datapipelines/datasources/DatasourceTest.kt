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

    @Test
    fun `include-schemas normalization drops blank-after-trim entries and duplicates - first-seen order`() {
        // R5 F2: `[" "]` used to normalize to `[""]` — a non-empty allowlist projected to
        // REST and MCP that still exempted nothing AND poisoned the GET->PUT round-trip
        // (the validator rejects blank entries, so an unmodified re-save 400s). The ONE
        // rule is trim -> lowercase -> drop blanks -> dedupe (first-seen order preserved).
        Datasource.normalizeIncludeSchemas(listOf(" ", "APEX_Reporting ", "apex_reporting", "\t", " Sales ")) shouldBe
            listOf("apex_reporting", "sales")
    }
}
