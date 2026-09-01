package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.core.env.Environment

/**
 * The authoring capability, enforced at the write path (versioning §5.5, 039 C3).
 *
 * `versioning.md` D7 says *"receiver environments never author"*, and nothing enforced it.
 * This guard makes it mechanical: a deployment with
 * `datapipelines.deployment.authoring-enabled=false` refuses every pipeline/template
 * authoring write — create, update/draft, release, discard, delete — with the catalogued
 * `*.authoring.disabled` refusal naming the reason. Reads, execution and **import are
 * unaffected**: promotion imports RELEASED versions into a receiver, and that is the one
 * writer a receiver must accept.
 *
 * The flag lives in the `deployment` block (039's amendment) because the codebase groups
 * config one-per-concern (`auth`, `executor`, `staging`, …), and it names the CAPABILITY,
 * not the environment, deliberately: someone running this on ONE box authors there and
 * runs there — that server is "production" in every ordinary sense, so a key that gated
 * authoring on an environment NAME would lock them out of authoring on the only server
 * they have. Default **true** (authoring on); a promotion receiver turns it off. The
 * block's `name` sibling is a LABEL only — nothing may branch on it (the C2 guard test
 * pins that).
 *
 * ## Where it lives, and the wiring rule
 *
 * `pipeline-contract`, because both write surfaces (REST controllers in `web`, MCP tools in
 * `mcp-server`) share the services that own these paths. The guard is a constructor
 * dependency WITHOUT a default on purpose: a surface that forgets it does not compile —
 * a capability check that can be silently skipped by a missed argument is fail-open, and
 * this one fails closed at build time.
 */
class AuthoringGuard(
    private val enabled: Boolean,
) {
    /** Refuses when authoring is disabled, with `pipeline.authoring.disabled` (§13.13). */
    fun requirePipelineAuthoring() {
        if (!enabled) throw pipelineRefusal()
    }

    /** Refuses when authoring is disabled, with `template.authoring.disabled` (§13.13). */
    fun requireTemplateAuthoring() {
        if (!enabled) throw templateRefusal()
    }

    private fun pipelineRefusal(): DatapipelinesException =
        refusal(
            code = PipelineErrorCodes.Versioning.AUTHORING_DISABLED,
            surface = "pipelines",
        )

    private fun templateRefusal(): DatapipelinesException =
        refusal(
            code = PipelineErrorCodes.Template.AUTHORING_DISABLED,
            surface = "templates",
        )

    private fun refusal(
        code: String,
        surface: String,
    ): DatapipelinesException =
        DatapipelinesException(
            code = code,
            message =
                "This deployment has authoring disabled (datapipelines.deployment.authoring-enabled=false) — it is a " +
                    "promotion receiver, not an authoring environment. Create and edit $surface where " +
                    "authoring is enabled and promote; reads, execution and import are unaffected.",
            details =
                mapOf(
                    "capability" to "$surface-authoring",
                    "config_key" to CONFIG_KEY,
                ),
        )

    companion object {
        /** configuration.md §3.19 — the capability flag in the `deployment` block, default true. */
        const val CONFIG_KEY = "datapipelines.deployment.authoring-enabled"

        /** Reads the guard from the live [Environment]; absent means the default (enabled). */
        fun from(environment: Environment): AuthoringGuard =
            AuthoringGuard(environment.getProperty(CONFIG_KEY, Boolean::class.java) ?: true)
    }
}
