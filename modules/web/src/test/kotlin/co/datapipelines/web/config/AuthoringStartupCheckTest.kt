package co.datapipelines.web.config

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * The deployment-posture boot checks (versioning §5.5 / configuration.md §7, 039 C2+C5+C8):
 * the receiver-also-authors WARN (driven through the seam — the promotion half of the
 * combination is reserved config, so the check is currently ONE-SIDED and the seam answers
 * "no key" in production until promotion ships), and the refusal when an authoring-disabled
 * deployment holds existing drafts, naming them.
 */
class AuthoringStartupCheckTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>()

    private fun environment(authoring: String?): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "test",
                    if (authoring == null) emptyMap() else mapOf(AuthoringGuard.CONFIG_KEY to authoring),
                ),
            )
        }

    @Test
    fun `authoring enabled with no promotion key is the quiet default`() {
        val check = AuthoringStartupCheck(environment("true"), pipelines, templates)

        shouldNotThrow<Exception> { check.check() }
        verify(exactly = 0) { pipelines.findAllDraftPipelineNames() }
    }

    @Test
    fun `a promotion key on an authoring-enabled deployment WARNs but starts`() {
        // The WARN's logic, driven through the seam the promotion round wires. In
        // production the seam currently answers "no key" (reserved config), so this only
        // fires once promotion ships — the check is one-sided by design, not by omission.
        val check =
            AuthoringStartupCheck(
                environment("true"),
                pipelines,
                templates,
                promotionServerKeyPresent = { true },
            )

        shouldNotThrow<Exception> { check.check() }
        verify(exactly = 0) { pipelines.findAllDraftPipelineNames() }
    }

    @Test
    fun `a promotion key on an authoring-DISABLED deployment is the consistent receiver - no warn`() {
        val check =
            AuthoringStartupCheck(
                environment("false"),
                pipelines,
                templates,
                promotionServerKeyPresent = { true },
            )
        every { pipelines.findAllDraftPipelineNames() } returns emptyList()
        every { templates.findAllDraftTemplateNames() } returns emptyList()

        shouldNotThrow<Exception> { check.check() }
    }

    @Test
    fun `authoring disabled with no drafts starts`() {
        every { pipelines.findAllDraftPipelineNames() } returns emptyList()
        every { templates.findAllDraftTemplateNames() } returns emptyList()

        shouldNotThrow<Exception> { AuthoringStartupCheck(environment("false"), pipelines, templates).check() }
    }

    @Test
    fun `authoring disabled with existing drafts refuses startup, naming them`() {
        // C8: a receiver holding drafts means someone authored there — found at boot,
        // not at the next promotion's 409.
        every { pipelines.findAllDraftPipelineNames() } returns listOf("monthly_revenue", "churn_rollup")
        every { templates.findAllDraftTemplateNames() } returns listOf("fetch_orders.sql")

        val refused =
            shouldThrow<IllegalStateException> {
                AuthoringStartupCheck(environment("false"), pipelines, templates).check()
            }

        refused.message shouldContain "monthly_revenue"
        refused.message shouldContain "churn_rollup"
        refused.message shouldContain "fetch_orders.sql"
        (refused.message?.contains("datapipelines.deployment.authoring-enabled") ?: false) shouldBe true
    }
}
