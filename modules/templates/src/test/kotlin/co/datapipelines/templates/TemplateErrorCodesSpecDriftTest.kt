package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Drift guard: the **template** error catalog in pipeline-contract.md §13.9 versus the
 * `template.validation.*` spellings this module actually raises.
 *
 * Every template validation test asserts against a constant a developer typed, so §13.9 and the
 * code could drift apart with the suite still green. This test reads the document, parses the
 * §13.9 table, and drives its assertions from the parsed values — add a code to §13.9 and it
 * fails until a constant exists; misspell a constant and it fails until the document agrees.
 * It has already earned its place twice. It caught templates.md v1.3 adding
 * `engine_unsupported` / `schema_version_unsupported` while `PipelineErrorCodes` still carried
 * the pre-v1.3 ten; a temporary in-module stopgap then held those two spellings until
 * `pipeline-contract` caught up, and a companion assertion turned the build red the moment it
 * did — which is how the stopgap came to be deleted rather than forgotten. Both are gone now
 * and `PipelineErrorCodes.Template` is once again the single spelling authority, which is why
 * the reconciliation below is a plain two-way set comparison.
 *
 * Same zero-dependency discipline as `ColumnSchemaSpecDriftTest` (typesystem) and
 * `PipelineErrorCodesSpecDriftTest` (pipeline-contract): located by heading text, parsed by a
 * regex over the first table cell, constants read by plain Java reflection.
 */
class TemplateErrorCodesSpecDriftTest {
    private val documented: Set<String> = parseSection()
    private val declared: Set<String> = declaredTemplateConstants()

    @Test
    fun `the section 13-9 table parses to a plausible set`() {
        // Guards the guard: a heading rename or table reformat that returned nothing — or two
        // rows — would make every assertion below a vacuous pass. Deliberately a floor rather
        // than an exact list, so that ADDING a code to §13.9 fails the reconciliation test below
        // (which names what is missing) instead of this one (which would only say "count wrong").
        withClue("§13.9 parsed to ${documented.size} codes — the parse or the doc is truncated") {
            documented.size shouldBeGreaterThanOrEqual MINIMUM_DOCUMENTED_CODES
        }
        withClue("every parsed code must be a template.validation.* spelling") {
            // §13.9 gained `template.not_found` (404 read-path miss) on 2026-08-11 (v1.3,
            // gate C) — a surface-raised lookup code, deliberately NOT a `template.validation.*`
            // write-time rule, so the shape guard admits exactly it (same exclusion style the
            // datasource drift guard applies to `pipeline.execution.datasource_unreachable`).
            // §13.9 gained `template.version.*` (draft/release lifecycle) on 2026-08-31
            // (versioning.md) — lifecycle codes, same deliberate exclusion.
            val nonValidationShape =
                documented.filterNot {
                    it.startsWith("template.validation.") || it.startsWith("template.version.") ||
                        it == PipelineErrorCodes.Template.NOT_FOUND
                }
            nonValidationShape.shouldBeEmpty()
        }
    }

    @Test
    fun `every section 13-9 code is implemented, and every implemented code is in section 13-9`() {
        declared shouldContainExactlyInAnyOrder documented
    }

    @Test
    fun `every implemented template code appears verbatim in the spec`() {
        val specText = TemplateFixtures.repoFile(SPEC_PATH).readText()
        val fabricated = declared.filterNot { specText.contains("`$it`") }
        withClue("Template codes pipeline-contract.md does not define") {
            fabricated.sorted().shouldBeEmpty()
        }
    }

    private companion object {
        const val SPEC_PATH = "docs/pipeline-contract.md"
        const val SECTION_START = "### 13.9 Template"
        const val SECTION_END = "### 13.10"

        /** templates.md v1.2 shipped ten codes; v1.3 added two. The floor never decreases. */
        const val MINIMUM_DOCUMENTED_CODES = 12

        val TABLE_CODE = Regex("^\\|\\s*`(template\\.[a-z0-9_.]+)`\\s*\\|", RegexOption.MULTILINE)

        fun parseSection(): Set<String> {
            val text = TemplateFixtures.repoFile(SPEC_PATH).readText()
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $SPEC_PATH" }
            val end = text.indexOf(SECTION_END, start)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START'" }
            return TABLE_CODE.findAll(text.substring(start, end)).map { it.groupValues[1] }.toSet()
        }

        fun declaredTemplateConstants(): Set<String> =
            PipelineErrorCodes.Template::class.java.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .map {
                    it.isAccessible = true
                    it.get(null) as String
                }.toSet()
    }
}
