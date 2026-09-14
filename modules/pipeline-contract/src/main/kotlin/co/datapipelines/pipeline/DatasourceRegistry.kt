package co.datapipelines.pipeline

import co.datapipelines.typesystem.Dialect
import java.util.UUID

/**
 * The pipeline validator's read-only view of the environment's datasource registry
 * (pipeline-contract §12.5).
 *
 * The registry itself lives in the `datasources` module, which sits **beside** this one in
 * the §4.2 layering table — `pipeline-contract` may depend on `typesystem` and nothing else.
 * So the dependency is inverted: this module declares the facts it needs and the
 * `datasources` module (or the wiring in `app`) supplies an implementation.
 *
 * A registry lookup is also what makes §11.4's env-specific scan tolerable: `pg-prod` is a
 * *name* and legal, and the scan skips any `source` / `output.datasource` value the registry
 * resolves ("the check applies to values that are not references into the datasource
 * registry").
 */
fun interface DatasourceRegistry {
    /**
     * Everything the validator needs to know about the datasource registered under [name] **as
     * seen from [workspaceId]**, or **null when no such datasource is visible there**.
     *
     * One method returning one resolved value, not `exists` + `dialectOf` + `readonlyOf`:
     * separate lookups are separate ways for the answers to disagree. The readonly fact rides
     * the same lookup as the dialect (workspaces design 2026-08-16 §6 — a write-shaped use of
     * a readonly datasource is `pipeline.validation.datasource_readonly`).
     *
     * ## The workspace is an argument, never ambient (134)
     *
     * [workspaceId] is the workspace the pipeline is being saved into — the same value
     * [PipelineValidator.validate] already receives and threads to the template and pipeline
     * ports. It is passed EXPLICITLY because the caller is the only thing that knows it: the
     * `web` adapter used to read it off Spring Security's thread-local principal, and an MCP
     * tool call runs on the SDK's scheduler thread where that thread-local is empty, so every
     * pipeline saved over MCP validated as "no principal" and could see only owner-less
     * datasources — a customer's own database was `unknown_datasource` over MCP and `201` over
     * REST for the same body. A port that takes its scope as a parameter cannot be fooled by
     * which thread it is called on; this is the shape the executor already had
     * (`ExecuteRequest.workspaceId`), which is why execution worked while save did not.
     *
     * The scope is NON-NULL (136 §C / T285): every save is workspace-scoped — a promotion
     * saves into the target workspace like any other write — so there is no caller that
     * "genuinely has no workspace", and the D-R7 owner-less branch a nullable parameter
     * implied was dead code that a future caller could have reached by passing `null` for
     * "I did not look it up". The validator's own `workspaceId` is non-null; so is this.
     *
     * The reserved literal `"tempdb"` is never passed here — it is not a datasource (§4.8),
     * and a registry that answers for it would let a pipeline shadow the staging database.
     */
    fun describe(
        name: String,
        workspaceId: UUID,
    ): DatasourceFacts?

    /** [describe] narrowed to the dialect — the existence question every `dialectOf` caller asked. */
    fun dialectOf(
        name: String,
        workspaceId: UUID,
    ): Dialect? = describe(name, workspaceId)?.dialect

    companion object {
        /** A registry with nothing in it — every name is unknown. */
        val EMPTY = DatasourceRegistry { _, _ -> null }
    }
}

/**
 * The two registry facts a pipeline save validates against: the datasource's [dialect]
 * (§12.6's template dialect check) and its [readonly] flag (workspaces design §6 — a
 * readonly datasource forbids the three write-shaped uses at save time).
 */
data class DatasourceFacts(
    val dialect: Dialect,
    val readonly: Boolean = false,
)
