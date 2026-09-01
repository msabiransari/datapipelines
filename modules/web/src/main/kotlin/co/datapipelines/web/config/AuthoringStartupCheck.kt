package co.datapipelines.web.config

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment

/**
 * The authoring-capability boot checks (versioning §5.5 / configuration.md §7, 039 C4+C5).
 *
 * Two states are checked at startup, one warning and one refusal:
 *
 * - **WARN — the receiver that also authors.** A promotion `server-key` configured AND
 *   authoring enabled is D7's violation stated in config: a receiver is written to by
 *   promotion only. It does not fail startup — the one-box deployment (author and runner
 *   on the same server) may legitimately be both, and the operator is trusted to know
 *   which they are — but it is logged loudly.
 * - **Refusal — drafts on an authoring-disabled server.** A receiver holding drafts means
 *   someone authored there, and version alignment may already be broken (§9.3: local
 *   numbers collide with future dev releases). Better found at boot, naming the offenders,
 *   than at the next promotion's 409.
 *
 * Lives in `web` (not `app`'s ConfigValidator) because the drafts check needs the
 * repositories; as a bean depending on them it initializes after Flyway has applied the
 * schema, like every other database-reading bean.
 */
class AuthoringStartupCheck(
    private val environment: Environment,
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
) {
    private val log = LoggerFactory.getLogger(AuthoringStartupCheck::class.java)

    @PostConstruct
    fun check() {
        val authoringEnabled =
            environment.getProperty(AuthoringGuard.CONFIG_KEY, Boolean::class.java) ?: true
        val serverKey = environment.getProperty(AuthoringGuard.PROMOTION_SERVER_KEY)?.trim()

        if (authoringEnabled && !serverKey.isNullOrEmpty()) {
            log.warn(
                "event=config.authoring_receiver_also_authors " +
                    "message=\"datapipelines.promotion.server-key is configured AND authoring is enabled " +
                    "(datapipelines.authoring.enabled=true): a promotion receiver should not author (versioning D7). " +
                    "Set datapipelines.authoring.enabled=false on receivers — unless this ONE box deliberately both " +
                    "authors and receives.\"",
            )
        }

        if (!authoringEnabled) {
            val pipelineDrafts = pipelines.findAllDraftPipelineNames()
            val templateDrafts = templates.findAllDraftTemplateNames()
            if (pipelineDrafts.isNotEmpty() || templateDrafts.isNotEmpty()) {
                val message =
                    buildString {
                        append("Authoring is disabled (datapipelines.authoring.enabled=false) but this server " +
                            "holds existing drafts — someone authored on a promotion receiver, and version " +
                            "alignment may already be broken (versioning §9.3). Release or discard them on an " +
                            "authoring server, or re-import this workspace. Drafts found:")
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

    private companion object {
        /** The refusal NAMES the offenders; past this many per kind it counts them. */
        const val MAX_NAMED = 20
    }
}
