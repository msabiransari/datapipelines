package co.datapipelines.pipeline

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Drift guard: the error-code catalog **in pipeline-contract.md §12 and §13** versus
 * [PipelineErrorCodes].
 *
 * This is the highest-leverage test in the module. §13 is the single authority for concrete
 * error codes across the whole system — every other module, the REST envelopes, the MCP tool
 * errors and the UI all quote codes that originate there. Every other test in this module
 * asserts against a constant a developer typed, so the document and the code could drift
 * apart with the suite still green, because both sides of every comparison live in the same
 * repository under the same hand.
 *
 * So this test reads the document, parses the code tables out of §12/§13, and drives its
 * assertions **from the parsed values**. Add a code to the spec and it fails until the
 * constant exists; misspell a constant and it fails until the document agrees.
 *
 * Deliberately zero-dependency: the tables are located by heading text and parsed with a
 * regex over the first cell, and the constants are read by plain Java reflection (Kotlin
 * `const val`s in an `object` compile to static final fields). Same discipline as
 * `ColumnSchemaSpecDriftTest` in `typesystem`.
 */
class PipelineErrorCodesSpecDriftTest {
    private val documented: Set<String> = parseCatalogFromSpec()
    private val declared: Map<String, String> = declaredConstants()

    @Test
    fun `every catalog domain is present on both sides`() {
        // Guards the guard. A heading rename or a table reformat that made the parse return
        // nothing would otherwise turn every assertion below into a vacuous pass.
        //
        // This replaces a bare size floor, which was structurally blind: §13.7–§13.11 are 39 of
        // the ~100 codes, so the whole auth + datasource + template + result + rate-limit half
        // of the catalog could stop parsing and a `size > 60` floor would still be satisfied by
        // the pipeline.* codes alone. Per-domain presence is what a truncated parse breaks.
        val missingFromSpec = DOMAINS.filterNot { domain -> documented.any { it.startsWith(domain) } }
        withClue("Domains with no code parsed out of §12/§13 — the parse or the doc is truncated") {
            missingFromSpec.shouldBeEmpty()
        }

        val missingFromCode = DOMAINS.filterNot { domain -> declared.values.any { it.startsWith(domain) } }
        withClue("Domains with no constant in PipelineErrorCodes") {
            missingFromCode.shouldBeEmpty()
        }
    }

    @Test
    fun `every code in §12 and §13 has a constant`() {
        val missing = documented - declared.values.toSet()
        withClue("Codes in pipeline-contract.md §12/§13 with no PipelineErrorCodes constant") {
            missing.sorted().shouldBeEmpty()
        }
    }

    @Test
    fun `every constant appears verbatim in the spec`() {
        val specText = Fixtures.repoFile(SPEC_PATH).readText()
        val fabricated = declared.filterValues { !specText.contains("`$it`") }
        withClue("PipelineErrorCodes constants that pipeline-contract.md does not define") {
            fabricated.map { (field, code) -> "$field = $code" }.shouldBeEmpty()
        }
    }

    @Test
    fun `the §12 validation codes this module raises are exactly the documented validation set`() {
        // §12's own tables, in full. A rule quietly dropped from the code — or a code added to
        // the object that §12 never asked for — shows up here rather than at the first author
        // who hits the missing check.
        val documentedValidation = documented.filter { it.startsWith("pipeline.validation.") }
        val declaredValidation = declared.values.filter { it.startsWith("pipeline.validation.") }
        // `non_dql_caller_target` is defined in §9.2 rather than a §12 table (it is the
        // caller-side name for dml_has_output / ddl_has_output), so it is declared and not
        // documented in the parsed range.
        declaredValidation shouldContainExactlyInAnyOrder
            documentedValidation + PipelineErrorCodes.Validation.NON_DQL_CALLER_TARGET
    }

    @Test
    fun `no two constants carry the same code`() {
        val duplicates =
            declared.values
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
        withClue("A code with two constants is a code with two homes to drift between") {
            duplicates.keys.shouldBeEmpty()
        }
    }

    @Test
    fun `every code follows the §13 segmentation scheme`() {
        // "{domain}.{entity}.{failure}" — three segments, lowercase snake_case, ASCII. Two
        // segments exist only where the domain has no entity dimension (enums.md §16 names
        // them: datasource.in_use, datasource.driver_not_loaded, rate_limit.exceeded).
        val malformed = declared.values.filterNot { SEGMENTATION.matches(it) }
        malformed.sorted().shouldBeEmpty()

        val twoSegment = declared.values.filter { it.count { c -> c == '.' } == 1 }.toSet()
        twoSegment shouldBe KNOWN_TWO_SEGMENT_CODES
    }

    private companion object {
        const val SPEC_PATH = "docs/pipeline-contract.md"
        const val CATALOG_START = "## 12. Validation Rules"
        const val CATALOG_END = "## 14. Pipeline Lifecycle Operations"

        /**
         * Every domain §13 catalogs, per enums.md §16's registry. Presence of each on both
         * sides is the structural check a size floor could not make.
         */
        val DOMAINS =
            listOf(
                "pipeline.validation.",
                "pipeline.import.",
                "pipeline.execution.",
                "pipeline.node.",
                "pipeline.staging.",
                "type_mapping.",
                "auth.",
                "datasource.",
                "template.validation.",
                "result.",
                "rate_limit.",
                "idempotency.",
                "workspace.",
            )

        val SEGMENTATION = Regex("^[a-z0-9_]+\\.[a-z0-9_]+(\\.[a-z0-9_]+)?$")

        val KNOWN_TWO_SEGMENT_CODES =
            setOf(
                PipelineErrorCodes.Datasource.IN_USE,
                PipelineErrorCodes.Datasource.NOT_FOUND,
                PipelineErrorCodes.Datasource.DRIVER_NOT_LOADED,
                PipelineErrorCodes.Template.NOT_FOUND,
                PipelineErrorCodes.Template.IN_USE,
                PipelineErrorCodes.Limits.RATE_LIMIT_EXCEEDED,
                PipelineErrorCodes.Result.EXECUTION_NOT_FOUND,
                PipelineErrorCodes.Result.EXECUTION_INCOMPLETE,
                PipelineErrorCodes.Result.EXECUTION_FAILED,
                PipelineErrorCodes.Result.EXPIRED,
                PipelineErrorCodes.Result.FORMAT_UNSUPPORTED,
                PipelineErrorCodes.Result.TOO_LARGE,
                PipelineErrorCodes.Result.STORAGE_UNAVAILABLE,
                PipelineErrorCodes.TypeMapping.UNKNOWN_SOURCE_TYPE,
                PipelineErrorCodes.TypeMapping.SQL_VARIANT,
                PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED,
                PipelineErrorCodes.Workspace.MEMBERSHIP_REQUIRED,
                PipelineErrorCodes.Workspace.CREATION_FORBIDDEN,
                PipelineErrorCodes.Workspace.HEADER_FORBIDDEN,
                PipelineErrorCodes.Workspace.SESSION_REQUIRED,
                PipelineErrorCodes.Workspace.NOT_FOUND,
                PipelineErrorCodes.Workspace.IN_USE,
            )

        /** First cell of a markdown table row, when it is a backticked lowercase code. */
        val TABLE_CODE = Regex("^\\|\\s*`([a-z0-9_]+(?:\\.[a-z0-9_]+)+)`\\s*\\|", RegexOption.MULTILINE)

        fun parseCatalogFromSpec(): Set<String> {
            val text = Fixtures.repoFile(SPEC_PATH).readText()
            val start = text.indexOf(CATALOG_START)
            check(start >= 0) { "'$CATALOG_START' not found in $SPEC_PATH" }
            val end = text.indexOf(CATALOG_END, start)
            check(end > start) { "'$CATALOG_END' not found after '$CATALOG_START' in $SPEC_PATH" }
            return TABLE_CODE
                .findAll(text.substring(start, end))
                .map { it.groupValues[1] }
                .toSet()
        }

        /** Every `const val String` on [PipelineErrorCodes]'s nested objects, by field path. */
        fun declaredConstants(): Map<String, String> =
            PipelineErrorCodes::class.java.declaredClasses
                .flatMap { group ->
                    group.declaredFields
                        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                        .map { field -> "${group.simpleName}.${field.name}" to (field.get(null) as String) }
                }.toMap()
    }
}
