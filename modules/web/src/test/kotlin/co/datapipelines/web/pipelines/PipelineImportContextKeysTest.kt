package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * §13.2 `pipeline.import.context_key_missing` (calculators design §0.5) — the promotion refusal.
 *
 * The scenario is the one the code exists for: a pipeline authored on a deployment whose
 * `datapipelines.org.*` block defines `org_region`, promoted to one that does not. Nothing about
 * the body is malformed and the sender was right to save it; the receiver simply cannot supply a
 * value. Left to run, the bind would fail at 3am with `sql_parameter_missing` — or, worse in the
 * calculator case, a null would flow into a kind that has a sensible answer for null and the
 * numbers would come out plausible and wrong.
 *
 * Both routes into the Context are covered, because they are two different scans: a `$reference`
 * in a CALCULATOR node's `inputs`, and a `:bind` in a template body.
 *
 * The receiver's org tier is a **stub with a different key set**, which the design brief permits
 * in place of two Spring contexts. That is not a weaker test: the whole mechanism is "compare the
 * body's demands against THIS deployment's keys", and a stub is the only way to vary the second
 * half without booting a second application.
 */
class PipelineImportContextKeysTest {
    private val pipelines = mockk<PipelineRepository>(relaxed = true)
    private val validator = mockk<PipelineValidator>()
    private val workspaceId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()

    /** The SENDER's deployment: five shipped org keys plus one this organisation added. */
    private val sender = OrgContext.ofValues(OrgContext.DEFAULTS.values + ("org_region" to "EU"))

    /** The RECEIVER's: the shipped five, and nothing else. */
    private val receiver = OrgContext.DEFAULTS

    init {
        every { validator.validate(any(), any()) } returns ValidationResult.VALID
    }

    @Test
    fun `a calculator input referencing an org key the receiver lacks is refused with 409`() {
        val failure = shouldThrow<ApiException> { import(service(receiver), calculatorBody()) }

        failure.code shouldBe PipelineErrorCodes.Import.CONTEXT_KEY_MISSING
        failure.details["missing_context_keys"] shouldBe listOf("org_region")
        failure.message.shouldContain("datapipelines.org")
    }

    @Test
    fun `the SAME body imports cleanly on the deployment that defines the key`() {
        // The half that makes the refusal meaningful: nothing is wrong with the body.
        import(service(sender), calculatorBody())
    }

    @Test
    fun `a template binding an org key the receiver lacks is refused too - the other route in`() {
        val failure = shouldThrow<ApiException> { import(service(receiver, binds = listOf("org_region")), sqlBody()) }

        failure.code shouldBe PipelineErrorCodes.Import.CONTEXT_KEY_MISSING
        failure.details["missing_context_keys"] shouldBe listOf("org_region")
    }

    @Test
    fun `a template binding an org key the receiver DOES define is untouched`() {
        import(service(receiver, binds = listOf("org_currency_symbol")), sqlBody())
    }

    @Test
    fun `a bind that is not an org key is left to the validator and the executor`() {
        // The scan is scoped to `org_` on purpose: every other tier travels with the body or is
        // universal, so widening it would start refusing pipelines that import fine today.
        import(service(receiver, binds = listOf("start_date", "run_fiscal_quarter")), sqlBody())
    }

    @Test
    fun `every missing key is reported at once, sorted, with what the deployment DOES provide`() {
        val failure =
            shouldThrow<ApiException> {
                import(service(receiver, binds = listOf("org_zzz", "org_region")), sqlBody())
            }

        failure.details["missing_context_keys"] shouldBe listOf("org_region", "org_zzz")
        @Suppress("UNCHECKED_CAST")
        (failure.details["provided_context_keys"] as List<String>) shouldContainExactly OrgContext.KEYS
    }

    // ------------------------------------------------------------------ fixture

    private fun import(
        service: PipelineImportService,
        body: String,
    ) = service.import(body, workspaceId, actorId)

    private fun service(
        org: OrgContext,
        binds: List<String> = emptyList(),
    ): PipelineImportService =
        PipelineImportService(
            pipelines = pipelines,
            validator = validator,
            orgContext = org,
            templates = stubTemplates(binds),
        )

    private fun stubTemplates(binds: List<String>): TemplateDryRenderer =
        object : TemplateDryRenderer {
            override fun lookup(
                workspaceId: UUID,
                ref: TemplateRef,
            ): TemplateLookup = TemplateLookup.Found(co.datapipelines.typesystem.Dialect.POSTGRES)

            override fun dryRender(
                workspaceId: UUID,
                ref: TemplateRef,
                context: Map<String, Any?>,
            ) = co.datapipelines.pipeline.DryRenderOutcome.Success

            override fun interpolatedParameters(
                workspaceId: UUID,
                ref: TemplateRef,
                declared: Set<String>,
            ): List<String> = emptyList()

            override fun boundParameters(
                workspaceId: UUID,
                ref: TemplateRef,
            ): List<String> = binds
        }

    private fun calculatorBody(): String =
        """
        {"schema_version":1,"name":"regional","display_name":"Regional","description":"d",
         "parameters":{},"settings":{"tempdb":{"engine":"H2"}},
         "nodes":[{"id":"region","description":"the run's region","type":"CALCULATOR",
                   "kind":"if_null","inputs":{"value":"${'$'}org_region","default":"GLOBAL"},
                   "context_key":"run_region","depends_on":[]}]}
        """.trimIndent()

    private fun sqlBody(): String =
        """
        {"schema_version":1,"name":"regional","display_name":"Regional","description":"d",
         "parameters":{},"settings":{"tempdb":{"engine":"H2"}},
         "nodes":[{"id":"report","description":"report","type":"DQL","source":"warehouse",
                   "template":{"id":"report.sql","version":1},"depends_on":[]}]}
        """.trimIndent()
}
