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
                // 074 — published endpoints. Both families, because the request validator's
                // codes are a separate table half and a truncated parse could drop just one.
                "endpoint.",
                "endpoint.request.",
                // 118 — the learned semantic layer (§13.15).
                "semantics.",
                // 120 — the MCP surface itself (§13.16).
                "mcp.",
                // 140 — release checks (§13.17).
                "pipeline.check.",
                // 7c (#7) — the TRANSFORM node's execution-time family (§13.18).
                "pipeline.transform.",
                // #9 — the scheduler's refusals (§13.19).
                "schedule.",
            )

        val SEGMENTATION = Regex("^[a-z0-9_]+\\.[a-z0-9_]+(\\.[a-z0-9_]+)?$")

        val KNOWN_TWO_SEGMENT_CODES =
            setOf(
                PipelineErrorCodes.Datasource.IN_USE,
                PipelineErrorCodes.Datasource.NOT_FOUND,
                PipelineErrorCodes.Datasource.DRIVER_NOT_LOADED,
                // 056 §E.2 — the datasource domain has no entity dimension, same shape as its siblings.
                PipelineErrorCodes.Datasource.LEASE_IN_TRANSACTION,
                // 089 §A — the dp-lake registry's duplicate/not-found, same two-segment shape.
                PipelineErrorCodes.Datasource.LAKE_TABLE_DUPLICATE,
                PipelineErrorCodes.Datasource.LAKE_TABLE_NOT_FOUND,
                // 112 — the grant refusal, same two-segment shape as its datasource siblings.
                PipelineErrorCodes.Datasource.GRANT_REQUIRED,
                // 123 — the introspector's table-level not-found/forbidden, same two-segment shape.
                PipelineErrorCodes.Datasource.TABLE_NOT_FOUND,
                PipelineErrorCodes.Datasource.TABLE_FORBIDDEN,
                // 112 — the ROLE axis has no entity dimension: these are properties of the
                // caller's membership, not of a thing they named (RBAC design §2).
                PipelineErrorCodes.Auth.ROLE_REQUIRED,
                PipelineErrorCodes.Auth.KEY_ISSUER_ROLE_LOST,
                // 179 — a user key is minted at login only; "kind" is a property of the
                // credential requested, not of a named entity, so the code is two-segment
                // like its key_* siblings above.
                PipelineErrorCodes.Auth.KEY_KIND_NOT_MINTABLE,
                // Keys v2 (A18) — the NAME conflict is a property of the credential request,
                // not of a named entity: two segments like its key_* siblings above.
                PipelineErrorCodes.Auth.KEY_NAME_TAKEN,
                PipelineErrorCodes.Auth.KEY_WORKSPACE_INACTIVE,
                // 180 — "deactivated" is a state of the PRINCIPAL, not of a named entity.
                PipelineErrorCodes.Auth.PRINCIPAL_DEACTIVATED,
                // 112 — the workspace domain's two: "the last admin" and "deactivated" are
                // states of the WORKSPACE, with no entity dimension under it.
                PipelineErrorCodes.Workspace.LAST_ADMIN,
                // #208 — the own-membership refusal, same two-segment shape as its workspace siblings.
                PipelineErrorCodes.Workspace.SELF_MEMBERSHIP,
                PipelineErrorCodes.Workspace.INACTIVE,
                PipelineErrorCodes.Template.NOT_FOUND,
                PipelineErrorCodes.Template.IN_USE,
                // 7b (#7) — the transform blocks' codes are bare `template.*` (the record's §7
                // names them without the `validation.` infix): the domain has no entity
                // dimension for these — each is about the version's own content.
                PipelineErrorCodes.Template.CONTRACT_INVALID,
                PipelineErrorCodes.Template.INVARIANT_INVALID,
                PipelineErrorCodes.Template.TEST_FAILED,
                PipelineErrorCodes.Template.BLOCKS_NOT_ALLOWED,
                PipelineErrorCodes.Template.RENDER_NOT_APPLICABLE,
                // 7e (#7) — the citation refusal is about the version's own `implements` list,
                // the same no-entity-dimension shape as 7b's block codes above.
                PipelineErrorCodes.Template.IMPLEMENTS_UNRESOLVED,
                PipelineErrorCodes.Limits.RATE_LIMIT_EXCEEDED,
                PipelineErrorCodes.Limits.RATE_LIMIT_UNAVAILABLE,
                // #9 — a schedule IS the entity (like `template.not_found`): its not-found, name,
                // revision and block states are properties of the schedule named, with no entity
                // dimension under it. A run's own refusals keep three segments (`schedule.run.*`).
                PipelineErrorCodes.Schedule.NOT_FOUND,
                PipelineErrorCodes.Schedule.NAME_TAKEN,
                PipelineErrorCodes.Schedule.REVISION_CONFLICT,
                PipelineErrorCodes.Schedule.BLOCKED,
                PipelineErrorCodes.Schedule.NOT_BLOCKED,
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
                PipelineErrorCodes.Workspace.HEADER_FORBIDDEN,
                PipelineErrorCodes.Workspace.SESSION_REQUIRED,
                PipelineErrorCodes.Workspace.NOT_FOUND,
                PipelineErrorCodes.Workspace.IN_USE,
                // 074 §13.14 — the `endpoint` domain has no entity dimension, so its
                // publish-time and resolution-time codes are two-segment, exactly like the
                // datasource and template ones above. The validator's `endpoint.request.*`
                // family DOES have one and is three-segment, so it is deliberately absent here.
                PipelineErrorCodes.Endpoint.PATH_INVALID,
                PipelineErrorCodes.Endpoint.PATH_RESERVED,
                PipelineErrorCodes.Endpoint.PATH_CONFLICT,
                PipelineErrorCodes.Endpoint.PATH_VARIABLE_UNKNOWN,
                PipelineErrorCodes.Endpoint.PIPELINE_NOT_READONLY,
                PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED,
                PipelineErrorCodes.Endpoint.NOT_FOUND,
                PipelineErrorCodes.Endpoint.METHOD_NOT_ALLOWED,
                PipelineErrorCodes.Endpoint.NOT_ACCEPTABLE,
                PipelineErrorCodes.Endpoint.KEY_NOT_BOUND,
                PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
                // 118 §13.15 — the `semantics` domain has no entity dimension either: every
                // code is about one fact.
                PipelineErrorCodes.Semantics.KIND_INVALID,
                PipelineErrorCodes.Semantics.FACT_INVALID,
                PipelineErrorCodes.Semantics.REF_UNRESOLVED,
                PipelineErrorCodes.Semantics.REF_MISMATCH,
                PipelineErrorCodes.Semantics.EVIDENCE_REFUSED,
                PipelineErrorCodes.Semantics.EVIDENCE_FAILED,
                PipelineErrorCodes.Semantics.DUPLICATE,
                PipelineErrorCodes.Semantics.NOT_FOUND,
                // 120 §13.16 — the `mcp` domain has no entity dimension either.
                PipelineErrorCodes.Mcp.DOC_NOT_FOUND,
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
