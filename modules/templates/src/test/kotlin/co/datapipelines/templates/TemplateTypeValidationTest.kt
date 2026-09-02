package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The type/dialect consistency rules of 046 (template-hierarchy-design §5.3/§5.4/§7) at BOTH
 * layers that enforce them: the deserializer's wire-value pre-scan and the validator's check
 * set — the validator half is what the MCP and import surfaces ride, which is exactly why it
 * exists even though the REST deserializer already refuses the same payloads.
 *
 * `dialect_not_allowed` is deliberately a different code from `dialect_invalid` (§7): presence
 * on an html template and an unknown value are different failures, each with its own greppable
 * code — collapsing them was considered and rejected (decision 1).
 */
class TemplateTypeValidationTest {
    private val workspaceId = UUID.randomUUID()
    private val deserializer = TemplateDeserializer()

    private fun validator(): TemplateValidator = TemplateValidator(LibraryResolver { _ -> InMemoryTemplateRegistry() })

    private fun json(
        type: String?,
        dialect: String?,
    ): String =
        buildString {
            append("""{"id":"t.sql","display_name":"T","description":"d","body":"SELECT 1"""")
            type?.let { append(""","type":"$it"""") }
            dialect?.let { append(""","dialect":"$it"""") }
            append("}")
        }

    @Test
    fun `the deserializer accepts the three legal payload shapes`() {
        assertSoftly {
            withClue("no type, dialect present - the pre-046 payload") {
                (deserializer.read(json(type = null, dialect = "POSTGRES")) as TemplateDeserializationOutcome.Parsed)
                    .draft.type shouldBe null
            }
            withClue("explicit sql") {
                (deserializer.read(json(type = "sql", dialect = "POSTGRES")) as TemplateDeserializationOutcome.Parsed)
                    .draft.type shouldBe TemplateType.SQL
            }
            withClue("html without dialect") {
                (deserializer.read(json(type = "html", dialect = null)) as TemplateDeserializationOutcome.Parsed)
                    .draft.type shouldBe TemplateType.HTML
            }
        }
    }

    @Test
    fun `the deserializer refuses the three illegal shapes each with its own code`() {
        fun codeOf(body: String): String =
            (deserializer.read(body) as TemplateDeserializationOutcome.Rejected)
                .result
                .codes
                .single()

        assertSoftly {
            withClue("unknown type value") {
                codeOf(json(type = "csv", dialect = "POSTGRES")) shouldBe
                    PipelineErrorCodes.Template.TYPE_INVALID
            }
            withClue("uppercase type is not a value") {
                codeOf(json(type = "SQL", dialect = "POSTGRES")) shouldBe
                    PipelineErrorCodes.Template.TYPE_INVALID
            }
            withClue("dialect present on html") {
                codeOf(json(type = "html", dialect = "POSTGRES")) shouldBe
                    PipelineErrorCodes.Template.DIALECT_NOT_ALLOWED
            }
            withClue("dialect absent on a non-html payload") {
                codeOf(json(type = null, dialect = null)) shouldBe
                    PipelineErrorCodes.Template.DIALECT_INVALID
            }
            withClue("unknown dialect value on sql") {
                codeOf(json(type = "sql", dialect = "DB2")) shouldBe
                    PipelineErrorCodes.Template.DIALECT_INVALID
            }
        }
    }

    @Test
    fun `the validator enforces the same pair - the layer MCP and import ride`() {
        assertSoftly {
            withClue("html with a dialect") {
                validator().validate(TemplateFixtures.draft(type = TemplateType.HTML), workspaceId).codes shouldContain
                    PipelineErrorCodes.Template.DIALECT_NOT_ALLOWED
            }
            withClue("sql without a dialect") {
                validator().validate(TemplateFixtures.draft(dialect = null), workspaceId).codes shouldContain
                    PipelineErrorCodes.Template.DIALECT_INVALID
            }
            withClue("html without a dialect is valid") {
                validator()
                    .validate(TemplateFixtures.draft(type = TemplateType.HTML, dialect = null, body = "<p>\${x}</p>"), workspaceId)
                    .isValid
                    .shouldBeTrue()
            }
        }
    }

    @Test
    fun `the forbidden-construct scan is type-blind - an html body faces the same refusals`() {
        // §6: identical hardening at save, on top of which html adds escaping at render. The
        // scan must not grow an html exception, and this test is what stops one appearing.
        val htmlDraft =
            TemplateFixtures.draft(
                type = TemplateType.HTML,
                dialect = null,
                body = "<#import \"/evil@1\" as evil><p>\${evil.x}</p>",
            )
        validator().validate(htmlDraft, workspaceId).codes shouldContain
            PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
    }

    @Test
    fun `the immutability rule refuses a differing type and resolves an absent one`() {
        assertSoftly {
            withClue("a differing payload type is refused") {
                val thrown =
                    io.kotest.assertions.throwables.shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                        TemplateTypeRule.forExisting(
                            TemplateFixtures.draft(type = TemplateType.HTML),
                            established = TemplateType.SQL,
                        )
                    }
                thrown.code shouldBe PipelineErrorCodes.Template.TYPE_IMMUTABLE
                thrown.details["established_type"] shouldBe "sql"
                thrown.details["type"] shouldBe "html"
            }
            withClue("an echoing payload type passes and carries the established value") {
                TemplateTypeRule
                    .forExisting(TemplateFixtures.draft(type = TemplateType.SQL), established = TemplateType.SQL)
                    .type shouldBe TemplateType.SQL
            }
            withClue("an absent payload type inherits the established value") {
                TemplateTypeRule
                    .forExisting(TemplateFixtures.draft(type = null), established = TemplateType.HTML)
                    .type shouldBe TemplateType.HTML
            }
            withClue("create defaults an absent type to sql") {
                TemplateTypeRule.forCreate(TemplateFixtures.draft(type = null)).type shouldBe TemplateType.SQL
            }
        }
    }
}
