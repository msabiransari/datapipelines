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
 * - **The posture line.** `deployment.name` (a LABEL — nothing branches on it, pinned by
 *   [DeploymentNameBranchingGuardTest]) is logged once beside the authoring state, so a
 *   deployment's posture is visible in its own logs. That is the name's ONLY consumer this
 *   round; it is deliberately not on `/info`, which is permitAll.
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
        val deploymentName = environment.getProperty(DEPLOYMENT_NAME_KEY)?.trim().orEmpty()

        // C2: the label's one consumer — visible posture, never a branch.
        log.info(
            "event=config.deployment_posture deployment={} authoring_enabled={} " +
                "message=\"deployment posture at boot; the name is a label only and nothing branches on it\"",
            deploymentName.ifEmpty { "(unset)" },
            authoringEnabled,
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
        /** configuration.md §3.19 — the deployment LABEL. Its only consumer is the posture line above. */
        const val DEPLOYMENT_NAME_KEY = "datapipelines.deployment.name"

        /** The refusal NAMES the offenders; past this many per kind it counts them. */
        const val MAX_NAMED = 20
    }
}
