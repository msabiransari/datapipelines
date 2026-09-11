package co.datapipelines.mcp

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * The 094 addendum's rule: an AGENT may not mint a new top-level folder without being told to.
 *
 * The thing under test is a GUARANTEE replacing an INSTRUCTION. Before this, the tool schemas and
 * the SKILL told a model to list the roots and ask; a model that did not, did not. The rule buys
 * exactly one thing — the exchange has to happen — and these cases pin its four branches plus the
 * two exemptions that keep it from being a nuisance.
 */
class NewRootConfirmationTest {
    private val roots = listOf("finance", "nyc", "ops")
    private var rootsRead = 0

    private fun existingRoots(): List<String> {
        rootsRead++
        return roots
    }

    private fun check(
        name: String?,
        confirmed: Boolean? = null,
    ) = NewRootConfirmation.require(
        name = name,
        confirmed = confirmed,
        code = PipelineErrorCodes.Validation.NEW_ROOT_REQUIRES_CONFIRMATION,
        existingRoots = ::existingRoots,
    )

    @Test
    fun `an existing root is accepted`() {
        shouldNotThrowAny { check("finance/payments/daily_settlement") }
    }

    @Test
    fun `a new root is refused, and the refusal names the roots that do exist`() {
        val refusal = shouldThrow<DatapipelinesException> { check("analytics/revenue/monthly") }

        assertAll(
            { refusal.code shouldBe "pipeline.validation.new_root_requires_confirmation" },
            { refusal.details["root"] shouldBe "analytics" },
            // The agent's next move has to be obvious from the refusal alone.
            { refusal.details["existing_roots"] shouldBe roots },
            { refusal.message!! shouldContain "confirm_new_root: true" },
        )
    }

    @Test
    fun `a confirmed new root is accepted, and the roots are not even queried`() {
        shouldNotThrowAny { check("analytics/revenue/monthly", confirmed = true) }

        // The query is one GROUP BY per call and the overwhelmingly common case is an existing
        // root; running it when the answer cannot change anything would be waste on every create.
        rootsRead shouldBe 0
    }

    @Test
    fun `test slash is always allowed, unconfirmed and unqueried`() {
        shouldNotThrowAny { check("test/scratch_thing") }

        rootsRead shouldBe 0
    }

    @Test
    fun `confirm false is not confirmation`() {
        // An agent that echoes the argument as false has said no, not yes.
        shouldThrow<DatapipelinesException> { check("analytics/revenue", confirmed = false) }
    }

    @Test
    fun `a name with no folder at all is left to the mandatory-folder rule`() {
        // 077 already refuses a bare name with details.reason='folder_required'. Answering the
        // same mistake with two different codes would be worse than either.
        shouldNotThrowAny { check("daily_settlement") }

        rootsRead shouldBe 0
    }

    @Test
    fun `a null name is left alone - templates_create generates one under test slash`() {
        shouldNotThrowAny { check(null) }

        rootsRead shouldBe 0
    }

    @Test
    fun `rootOf reads the first segment, and nothing when there is no folder`() {
        assertAll(
            { NewRootConfirmation.rootOf("finance/payments/daily") shouldBe "finance" },
            { NewRootConfirmation.rootOf("  nyc/mobility  ") shouldBe "nyc" },
            { NewRootConfirmation.rootOf("flat_name").shouldBeNull() },
            { NewRootConfirmation.rootOf("").shouldBeNull() },
            { NewRootConfirmation.rootOf(null).shouldBeNull() },
            // A leading slash has no first segment to judge; the grammar refuses it anyway.
            { NewRootConfirmation.rootOf("/leading").shouldBeNull() },
        )
    }

    @Test
    fun `the template family raises its own catalogued code`() {
        val refusal =
            shouldThrow<DatapipelinesException> {
                NewRootConfirmation.require(
                    name = "analytics/revenue.sql",
                    confirmed = null,
                    code = PipelineErrorCodes.Template.NEW_ROOT_REQUIRES_CONFIRMATION,
                    existingRoots = ::existingRoots,
                )
            }

        refusal.code shouldBe "template.validation.new_root_requires_confirmation"
    }

    @Test
    fun `both create tools accept the argument, and no other tool does`() {
        // The rule is create-only because there is no rename: `pipelines_update` cannot mint a
        // root, so a schema that ACCEPTED the argument there would advertise a decision the tool
        // cannot take. (`templates_update` (117) is the same: it names a template that already
        // exists, so the folder — and any new root it would imply — is already settled.)
        // Asserted over the WHOLE shipped surface rather than over a named pair, so a future
        // tool that grows the argument by accident is caught.
        val schemas = realShippedTools().associate { it.name to McpTools.readTree(schemaOf(it)) }
        val accepting = schemas.filterValues { it["properties"]?.has(NewRootConfirmation.ARG) == true }.keys

        accepting shouldBe setOf("pipelines_create", "templates_create")
    }

    @Test
    fun `the SKILL tells the agent the rule, by its catalogued codes`() {
        // The schema description is what a tool caller reads mid-call; the SKILL is what it reads
        // BEFORE choosing a folder, which is the only moment the advice can still save a round
        // trip. Both have to say it, so both are asserted.
        val skill = SpecFiles.read(SpecFiles.SKILL_PATH)

        assertAll(
            { skill shouldContain "confirm_new_root" },
            { skill shouldContain "pipeline.validation.new_root_requires_confirmation" },
            { skill shouldContain "template.validation.new_root_requires_confirmation" },
            { skill shouldContain "existing_roots" },
        )
    }

    private fun schemaOf(tool: McpTool): String =
        io.modelcontextprotocol.json.McpJsonDefaults
            .getMapper()
            .writeValueAsString(tool.definition.inputSchema())
}
