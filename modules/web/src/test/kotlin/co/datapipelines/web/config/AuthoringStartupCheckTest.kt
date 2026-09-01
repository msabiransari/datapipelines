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
 * The authoring-capability boot checks (versioning §5.5 / configuration.md §7, 039 C4+C5):
 * the receiver-also-authors combination WARNs (and does not fail), and existing drafts on
 * an authoring-disabled server refuse startup, naming them.
 */
class AuthoringStartupCheckTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>()

    private fun check(
        authoring: String?,
        serverKey: String? = null,
    ): AuthoringStartupCheck = AuthoringStartupCheck(environment(authoring, serverKey), pipelines, templates)

    private fun environment(
        authoring: String?,
        serverKey: String?,
    ): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "test",
                    buildMap<String, Any> {
                        authoring?.let { put(AuthoringGuard.CONFIG_KEY, it) }
                        serverKey?.let { put(AuthoringGuard.PROMOTION_SERVER_KEY, it) }
                    },
                ),
            )
        }

    @Test
    fun `authoring enabled with no server-key is the quiet default`() {
        shouldNotThrow<Exception> { check("true").check() }
        verify(exactly = 0) { pipelines.findAllDraftPipelineNames() }
    }

    @Test
    fun `a server-key on an authoring-enabled server WARNs but starts`() {
        // C4: the one-box deployment may legitimately be both — loud, not fatal.
        shouldNotThrow<Exception> { check("true", serverKey = "a-long-secret").check() }
        verify(exactly = 0) { pipelines.findAllDraftPipelineNames() }
    }

    @Test
    fun `authoring disabled with no drafts starts`() {
        every { pipelines.findAllDraftPipelineNames() } returns emptyList()
        every { templates.findAllDraftTemplateNames() } returns emptyList()

        shouldNotThrow<Exception> { check("false").check() }
    }

    @Test
    fun `authoring disabled with existing drafts refuses startup, naming them`() {
        // C5: a receiver holding drafts means someone authored there — found at boot,
        // not at the next promotion's 409.
        every { pipelines.findAllDraftPipelineNames() } returns listOf("monthly_revenue", "churn_rollup")
        every { templates.findAllDraftTemplateNames() } returns listOf("fetch_orders.sql")

        val refused = shouldThrow<IllegalStateException> { check("false").check() }

        refused.message shouldContain "monthly_revenue"
        refused.message shouldContain "churn_rollup"
        refused.message shouldContain "fetch_orders.sql"
        (refused.message?.contains("authoring") ?: false) shouldBe true
    }
}
