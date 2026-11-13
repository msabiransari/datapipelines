package co.datapipelines.web.config

import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.MailProperties
import co.datapipelines.auth.WorkspaceLiveness
import co.datapipelines.executor.ResultConfig
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mock.env.MockEnvironment

/**
 * #786 A37 — API eligibility at save, switch and serve reads THE authoring capability
 * (`datapipelines.deployment.authoring-enabled`, [co.datapipelines.pipeline.AuthoringGuard]), never the scheduler mail
 * policy's own `developmentPosture` ([co.datapipelines.scheduler.NotificationDeployment], derived from
 * `datapipelines.posture`). The two are different facts and CAN disagree: a development posture with authoring turned
 * off is a legal deployment, and on it a DRAFT pipeline version is not an eligible pin — mail's "development" says
 * nothing about drafts.
 *
 * The beans are built by the production `@Bean` methods from ONE environment, so a wiring that fed the mail posture
 * (or any second posture source) into the pin validator or the serve service goes red here.
 */
class EndpointPostureSourceWiringTest {
    /** Development posture for mail, authoring off — the mismatch. */
    private val environment =
        MockEnvironment()
            .withProperty(DeploymentEnv.ENV_KEY, "development")
            .withProperty(DeploymentEnv.POSTURE_KEY, "development")
            .withProperty("datapipelines.deployment.authoring-enabled", "false")

    private val authoring = DomainConfiguration().authoringGuard(environment)
    private val mail = SchedulerWebConfiguration().schedulerNotificationDeployment(MailProperties(), AuthProperties(), environment)

    @Test
    fun `the premise - on this deployment the mail policy says development while authoring is off`() {
        mail.developmentPosture shouldBe true
        authoring.developmentPosture shouldBe false
    }

    @Test
    fun `the pin validator - save and switch - follows the authoring capability, not the mail posture`() {
        val validator =
            EndpointsConfiguration().endpointPinValidator(mockk(), mockk<ObjectProvider<PipelineService>>(), mockk(), authoring)
        withClue("authoring off: a DRAFT pipeline version is not an eligible pin, whatever mail's posture says") {
            validator.eligible(PipelineVersionStatus.DRAFT) shouldBe false
        }
        validator.eligible(PipelineVersionStatus.RELEASED) shouldBe true
        validator.eligible(PipelineVersionStatus.DISCARDED) shouldBe false
    }

    @Test
    fun `the serve service follows the authoring capability, not the mail posture`() {
        val serve =
            EndpointsConfiguration().publishedEndpointServeService(
                registry = mockk(),
                versions = mockk(),
                pipelineRepository = mockk(),
                bindings = mockk(),
                authorizer = mockk(),
                readOnlyRule = mockk(),
                pipelines = mockk(),
                runner = mockk(),
                resultStore = mockk(),
                resultUrls = mockk(),
                resultConfig = ResultConfig(),
                endpointsProperties = EndpointsProperties(),
                audit = mockk(),
                executionScope = mockk(),
                authoringGuard = authoring,
                workspaceLiveness = WorkspaceLiveness { true },
            )
        withClue("authoring off: a DRAFT API version or pin is not served, whatever mail's posture says") {
            serve.servable(PipelineVersionStatus.DRAFT) shouldBe false
        }
        serve.servable(PipelineVersionStatus.RELEASED) shouldBe true
    }
}
