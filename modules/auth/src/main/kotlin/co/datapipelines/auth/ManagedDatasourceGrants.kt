package co.datapipelines.auth

import java.util.UUID

/**
 * Grants the SERVER'S OWN datasource — the managed lake (`datapipelines.lake.*`, dp-lake record D3: the lake is
 * server-level) — to a workspace, inside the transaction that creates it (#823, owner ruling 2026-11-07).
 *
 * ## Why this exists
 * The managed lake is granted to every workspace, and the freshness tick repeats that grant for workspaces created
 * since its last pass. Before this port a workspace created through [WorkspaceService.create] saw no managed lake
 * until the next tick: up to one tick, and never when `reads.freshness-tick-seconds` is large. [WorkspaceService.create]
 * now makes the grant itself, in the same metadata transaction as the workspace row, so there is no window in which a
 * workspace exists and cannot see the server's datasource. The tick stays as the REPAIR path (an operator who deleted
 * a grant row by hand, a workspace inserted by SQL, a grant that predates the managed lake).
 *
 * ## Why it is not [InstanceDatasourceGrants]
 * That port's contract is "every instance datasource to the `demo` workspace" (D-R7's one exception). This one is "the
 * server's own datasource to every workspace". Folding the second into the first would make the demo seeder grant the
 * lake twice and make the lake grant read like a demo rule.
 *
 * ## Why it takes no datasource name and no actor
 * The port can grant exactly one thing, so the creation transaction cannot be used to grant anything else. The actor is
 * the implementation's own: the grant is the SERVER'S act (the system actor the managed lake's registrar provisioned),
 * never the principal who happened to create the workspace.
 *
 * `auth` declares the port because it owns the creation; the aggregation layer implements it, because `datasources` is
 * not a module `auth` may see (module-structure §4.2).
 */
fun interface ManagedDatasourceGrants {
    /**
     * Grants the managed lake to [workspaceId], on the caller's transaction. Idempotent.
     *
     * @return true when a grant row was created; false when the lake is disabled (nothing to grant) or the row existed.
     */
    fun grantManagedLakeTo(workspaceId: UUID): Boolean

    companion object {
        /** For contexts with no datasource layer (auth-only test slices) and for a deployment with the lake off. */
        val NONE = ManagedDatasourceGrants { false }
    }
}
