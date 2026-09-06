package co.datapipelines.web.config

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment

/**
 * The deployment-posture boot checks (versioning §5.5 / configuration.md §7, 039 C2+C5+C8).
 *
 * Three things happen here, in order:
 *
 * - **The posture line** (075). The org's ENV label (a LABEL — nothing branches on it,
 *   pinned by [DeploymentNameBranchingGuardTest]), the product's POSTURE, the authoring
 *   state and the demo families are logged once, so what a deployment IS is visible in its
 *   own logs and in a pasted issue report. The env is deliberately not on `/info`, which is
 *   permitAll. The values are resolved through [DeploymentEnv] — the same resolver `app`'s
 *   `ConfigValidator` refuses on, so the line can never describe a posture the validator
 *   judged differently.
 * - **WARN — the receiver that also authors** (C5, BOTH-SIDED since 055): a deployment with
 *   a promotion `server-key` configured — meaning it RECEIVES — AND authoring enabled is
 *   D7's violation stated in config. [promotionServerKeyPresent] is the seam; `DomainConfiguration`
 *   wires it to `PromotionProperties.receives`, and the default `{ false }` survives only for
 *   the unit tests that drive the check directly. It does not fail startup — a one-box
 *   deployment may legitimately be both.
 * - **Refusal — drafts on an authoring-disabled deployment** (C8): a receiver holding
 *   drafts means someone authored there, and version alignment may already be broken
 *   (§9.3: local numbers collide with future dev releases). Better found at boot, naming
 *   the offenders, than at the next promotion's 409.
 *
 * Lives in `web` (not `app`'s ConfigValidator) because the drafts check needs the
 * repositories; as a bean depending on them it initializes after Flyway has applied the
 * schema, like every other database-reading bean.
 */
class AuthoringStartupCheck(
    private val environment: Environment,
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
    private val promotionServerKeyPresent: () -> Boolean = { false },
) {
    private val log = LoggerFactory.getLogger(AuthoringStartupCheck::class.java)

    @PostConstruct
    fun check() {
        val authoringEnabled =
            environment.getProperty(AuthoringGuard.CONFIG_KEY, Boolean::class.java) ?: true
        val env = DeploymentEnv.resolveEnv(environment.getProperty(DeploymentEnv.ENV_KEY), environment.getProperty(DeploymentEnv.LEGACY_ENV_KEY))
        val posture = DeploymentEnv.resolvePosture(environment.getProperty(DeploymentEnv.POSTURE_KEY), env)
        val demo = DeploymentEnv.demoFamilies(environment.getProperty(DeploymentEnv.DEMO_KEY))

        // C2 / 075: the label's one consumer — visible posture, never a branch.
        log.info(
            "event=config.posture env={} posture={} authoring={} demo={}",
            env,
            posture ?: "(unset)",
            if (authoringEnabled) "on" else "off",
            demo.joinToString(",").ifEmpty { "(none)" },
        )

        if (authoringEnabled && promotionServerKeyPresent()) {
            log.warn(
                "event=config.authoring_receiver_also_authors " +
                    "message=\"a promotion server-key is configured AND authoring is enabled " +
                    "(datapipelines.deployment.authoring-enabled=true): a promotion receiver should not author " +
                    "(versioning D7). Set datapipelines.deployment.authoring-enabled=false on receivers — unless " +
                    "this ONE box deliberately both authors and receives.\"",
            )
        }

        if (!authoringEnabled) {
            val pipelineDrafts = pipelines.findAllDraftPipelineNames()
            val templateDrafts = templates.findAllDraftTemplateNames()
            if (pipelineDrafts.isNotEmpty() || templateDrafts.isNotEmpty()) {
                val message =
                    buildString {
                        append(
                            "Authoring is disabled (datapipelines.deployment.authoring-enabled=false) but this " +
                                "server holds existing drafts — someone authored on a promotion receiver, and version " +
                                "alignment may already be broken (versioning §9.3). Release or discard them on an " +
                                "authoring server, or re-import this workspace. Drafts found:",
                        )
                        pipelineDrafts.take(MAX_NAMED).forEach { append("\n  - pipeline: ").append(it) }
                        if (pipelineDrafts.size > MAX_NAMED) append("\n  - … and ${pipelineDrafts.size - MAX_NAMED} more pipelines")
                        templateDrafts.take(MAX_NAMED).forEach { append("\n  - template: ").append(it) }
                        if (templateDrafts.size > MAX_NAMED) append("\n  - … and ${templateDrafts.size - MAX_NAMED} more templates")
                    }
                log.error(message)
                throw IllegalStateException(message)
            }
        }
    }

    companion object {
        /** The refusal NAMES the offenders; past this many per kind it counts them. */
        const val MAX_NAMED = 20
    }
}
