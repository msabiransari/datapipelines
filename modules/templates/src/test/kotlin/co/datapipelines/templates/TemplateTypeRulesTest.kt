package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The transform-nodes design §2.1 type rules, table-driven over
 * (type × engine × dialect × imports × is_library × body) → the refusal code, or accept.
 *
 * The matrix is the contract: `engine` must match the type both ways (`freemarker` iff
 * sql/html, `none` iff a transform type), `dialect` is required on sql and forbidden on every
 * other type, `imports`/`is_library` are refused on a transform type, a Freemarker construct in
 * a transform body is refused (D-T9), a `jsonata` body is compiled through the scripting engine
 * at save, and a `javascript` body is refused until round two (`transform.js.unavailable`).
 *
 * Falsified at birth: dropping the `none` arm of the engine rule turns the jsonata-accept row
 * red.
 */
class TemplateTypeRulesTest {
    private val workspaceId = java.util.UUID.randomUUID()

    private fun validator() = TemplateValidator(LibraryResolver { _ -> InMemoryTemplateRegistry() })

    private data class Row(
        val name: String,
        val type: TemplateType?,
        val engine: String,
        val dialect: Dialect?,
        val imports: List<TemplateImport> = emptyList(),
        val isLibrary: Boolean = false,
        val body: String = "SELECT 1",
        /** A transform row needs its blocks to be accepted at all (§2.2); the type-rule rows do not touch them. */
        val contract: TransformContract? = null,
        val invariants: List<TransformInvariant>? = null,
        val tests: List<TransformTestCase>? = null,
        /** The expected refusal codes — empty means the row must be accepted. */
        val codes: List<String> = emptyList(),
        /** Every expected `freemarker_forbidden` carries its rule in details. */
        val detailRule: String? = null,
    )

    private val legalContract =
        TransformContract(
            mode = TransformMode.ROW,
            inputs =
                mapOf(
                    "orders" to TransformInput.Table(listOf(ContractColumn("order_id", co.datapipelines.typesystem.LogicalType.INTEGER))),
                ),
            output = TransformOutput.Table(listOf(ContractColumn("order_id", co.datapipelines.typesystem.LogicalType.INTEGER))),
        )

    private val legalCase =
        TransformTestCase(
            name = "empty",
            input = TransformTestInput(rows = emptyList(), inputs = emptyMap()),
            expect = TransformTestExpect(output = TransformBlocks.mapper.readTree("""{"rows": []}""")),
        )

    private val rows =
        listOf(
            Row(
                name = "sql + freemarker + dialect → accept",
                type = TemplateType.SQL,
                engine = Template.FREEMARKER_ENGINE,
                dialect = Dialect.POSTGRES,
            ),
            Row(
                name = "sql + none → engine_unsupported",
                type = TemplateType.SQL,
                engine = Template.NONE_ENGINE,
                dialect = Dialect.POSTGRES,
                codes = listOf(PipelineErrorCodes.Template.ENGINE_UNSUPPORTED),
            ),
            Row(
                name = "sql + no dialect → dialect_invalid",
                type = TemplateType.SQL,
                engine = Template.FREEMARKER_ENGINE,
                dialect = null,
                codes = listOf(PipelineErrorCodes.Template.DIALECT_INVALID),
            ),
            Row(
                name = "html + freemarker + no dialect → accept",
                type = TemplateType.HTML,
                engine = Template.FREEMARKER_ENGINE,
                dialect = null,
                body = "<p>Hello</p>",
            ),
            Row(
                name = "html + none → engine_unsupported",
                type = TemplateType.HTML,
                engine = Template.NONE_ENGINE,
                dialect = null,
                codes = listOf(PipelineErrorCodes.Template.ENGINE_UNSUPPORTED),
            ),
            Row(
                name = "html + dialect → dialect_not_allowed",
                type = TemplateType.HTML,
                engine = Template.FREEMARKER_ENGINE,
                dialect = Dialect.POSTGRES,
                codes = listOf(PipelineErrorCodes.Template.DIALECT_NOT_ALLOWED),
            ),
            Row(
                name = "jsonata + none + clean body → accept",
                type = TemplateType.JSONATA,
                engine = Template.NONE_ENGINE,
                dialect = null,
                body = "rows",
                contract = legalContract,
                invariants = emptyList(),
                tests = listOf(legalCase),
            ),
            Row(
                name = "jsonata + freemarker → engine_unsupported",
                type = TemplateType.JSONATA,
                engine = Template.FREEMARKER_ENGINE,
                dialect = null,
                body = "rows",
                codes = listOf(PipelineErrorCodes.Template.ENGINE_UNSUPPORTED),
            ),
            Row(
                name = "jsonata + dialect → dialect_not_allowed",
                type = TemplateType.JSONATA,
                engine = Template.NONE_ENGINE,
                dialect = Dialect.POSTGRES,
                body = "rows",
                codes = listOf(PipelineErrorCodes.Template.DIALECT_NOT_ALLOWED),
            ),
            Row(
                name = "jsonata + imports → freemarker_forbidden (imports)",
                type = TemplateType.JSONATA,
                engine = Template.NONE_ENGINE,
                dialect = null,
                imports = listOf(TemplateImport("acme/lib.sql", 1, "lib")),
                body = "rows",
                codes = listOf(PipelineErrorCodes.Template.FREEMARKER_FORBIDDEN),
                detailRule = "imports",
            ),
            Row(
                name = "jsonata + is_library → freemarker_forbidden (is_library)",
                type = TemplateType.JSONATA,
                engine = Template.NONE_ENGINE,
                dialect = null,
                isLibrary = true,
                body = "rows",
                codes = listOf(PipelineErrorCodes.Template.FREEMARKER_FORBIDDEN),
                detailRule = "is_library",
            ),
            Row(
                name = "jsonata + freemarker construct in body → freemarker_forbidden (body)",
                type = TemplateType.JSONATA,
                engine = Template.NONE_ENGINE,
                dialect = null,
                body = "rows.\${amount}",
                codes = listOf(PipelineErrorCodes.Template.FREEMARKER_FORBIDDEN),
                detailRule = "body",
            ),
            Row(
                name = "jsonata + unparseable body → syntax_error with line/column",
                type = TemplateType.JSONATA,
                engine = Template.NONE_ENGINE,
                dialect = null,
                body = "(((((",
                codes = listOf(PipelineErrorCodes.Template.SYNTAX_ERROR),
            ),
            Row(
                name = "javascript + none → transform.js.unavailable",
                type = TemplateType.JAVASCRIPT,
                engine = Template.NONE_ENGINE,
                dialect = null,
                body = "function transform(input) { return input; }",
                codes = listOf(TransformCodes.JS_UNAVAILABLE),
            ),
            Row(
                name = "javascript + freemarker → engine_unsupported AND js unavailable",
                type = TemplateType.JAVASCRIPT,
                engine = Template.FREEMARKER_ENGINE,
                dialect = null,
                body = "function transform(input) { return input; }",
                codes = listOf(PipelineErrorCodes.Template.ENGINE_UNSUPPORTED, TransformCodes.JS_UNAVAILABLE),
            ),
        )

    @Test
    fun `every row of the type matrix produces its code, or is accepted`() {
        rows.forEach { row ->
            withClue(row.name) {
                val failures =
                    validator().validate(
                        TemplateFixtures.draft(
                            type = row.type,
                            engine = row.engine,
                            dialect = row.dialect,
                            imports = row.imports,
                            isLibrary = row.isLibrary,
                            body = row.body,
                            contract = row.contract,
                            invariants = row.invariants,
                            tests = row.tests,
                        ),
                        workspaceId,
                    ).failures
                val codes = failures.map { it.code }
                codes.filter { it in row.codes } shouldBe row.codes
                if (row.codes.isEmpty()) {
                    failures.shouldBeEmpty()
                }
                if (row.detailRule != null) {
                    val refusal = failures.single { it.code == PipelineErrorCodes.Template.FREEMARKER_FORBIDDEN }
                    refusal.details["rule"] shouldBe row.detailRule
                }
            }
        }
    }

    @Test
    fun `the syntax_error of a jsonata body carries the engine's line and column`() {
        val failures =
            validator().validate(
                TemplateFixtures.draft(type = TemplateType.JSONATA, engine = Template.NONE_ENGINE, dialect = null, body = "((((("),
                workspaceId,
            ).failures
        val refusal = failures.single { it.code == PipelineErrorCodes.Template.SYNTAX_ERROR }
        refusal.details["line"] shouldBe 1
        (refusal.details["column"] as Int > 0) shouldBe true
    }

    @Test
    fun `the deserializer defaults an omitted engine to none on a transform create`() {
        val outcome =
            TemplateDeserializer().read(
                """{"type":"jsonata","display_name":"X","description":"Y","body":"rows"}""",
            )
        val draft = (outcome as TemplateDeserializationOutcome.Parsed).draft
        draft.engine shouldBe Template.NONE_ENGINE
        draft.type shouldBe TemplateType.JSONATA
    }

    @Test
    fun `the deserializer refuses a dialect on a transform type before binding`() {
        val outcome =
            TemplateDeserializer().read(
                """{"type":"jsonata","dialect":"POSTGRES","display_name":"X","description":"Y","body":"rows"}""",
            )
        val rejected = (outcome as TemplateDeserializationOutcome.Rejected).result
        rejected.failures.single().code shouldBe PipelineErrorCodes.Template.DIALECT_NOT_ALLOWED
        rejected.failures.single().message shouldContain "jsonata"
    }
}
