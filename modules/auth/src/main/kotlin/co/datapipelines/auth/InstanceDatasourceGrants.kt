package co.datapipelines.auth

import java.util.UUID

/**
 * Grants every INSTANCE datasource — one no workspace owns — to a workspace the product itself
 * created (D-R7, D-R11).
 *
 * ## Why this exists at all
 * D-R7's rule is that nothing is visible by default: a datasource is seen by a workspace only
 * because somebody granted it. V23's migration honours that for the workspaces that existed
 * when it ran (every former `global` datasource became one grant row per workspace), but a
 * workspace created LATER starts with no grants at all — and `demo` is created later, at first
 * boot, by [DemoWorkspaceSeeder].
 *
 * That left the shipped demo empty and said so only in a log line: the example content
 * declares `requires_datasources`, the bootstrap-registered datasources are instance ones
 * granted to nobody, so the gate skipped and `demo` came up with no pipelines. Found by
 * `TaxiVsRideshareFourEngineE2eTest`, which asserts the CONTENT rather than the seeder running.
 *
 * ## Why it is narrow
 * This applies to `demo` and nothing else. An instance datasource is the operator's statement
 * that this deployment may use it, and the workspace the PRODUCT ships is the one place where
 * "available to the deployment" and "available to the workspace" are the same sentence — its
 * content is the operator's own examples, referencing the operator's own datasources. Every
 * other workspace still gets each grant decided out loud by a super admin.
 *
 * `auth` declares the port because it owns the seeder; the aggregation layer implements it,
 * because `datasources` is not a module `auth` may see (module-structure §4.2).
 */
fun interface InstanceDatasourceGrants {
    /**
     * Grants every datasource with no owning workspace to [workspaceId], attributing the grants
     * to [grantedBy]. Idempotent: a grant that already exists keeps its original actor.
     *
     * @return how many grants were created — the seeder logs it, so an operator can see whether
     *   the demo workspace actually gained the datasources its examples need.
     */
    fun grantAllTo(
        workspaceId: UUID,
        grantedBy: UUID,
    ): Int

    companion object {
        /** For contexts with no datasource layer (auth-only test slices). */
        val NONE = InstanceDatasourceGrants { _, _ -> 0 }
    }
}
