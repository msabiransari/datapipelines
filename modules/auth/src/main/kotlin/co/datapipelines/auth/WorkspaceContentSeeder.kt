package co.datapipelines.auth

import java.util.UUID

/**
 * The example-content hook (sample-data design §6.1), called once immediately after a
 * workspace the PRODUCT created comes into existence — since D-R11 that means `demo`, and
 * only `demo`.
 *
 * It was `PersonalWorkspaceSeeder` until RBAC round 1, fired on every `auto-per-user` first
 * login. Personal workspaces went with D-R11, but the reason the hook exists did not: a
 * brand-new deployment's first screen should be a working cross-datasource example rather
 * than an empty list. So the port survives with the workspace it now serves in its name, and
 * fires exactly once in a deployment's life instead of once per user.
 *
 * `auth` declares the port and `web` implements it, because seeding goes through the pipeline
 * and template **import** services and `auth` may depend on `typesystem` only
 * (module-structure §4.2). That constraint is also why the signature is pure JDK types.
 *
 * ## Deliberately NOT fail-open
 * An implementation that cannot seed must throw: a `demo` workspace that silently lacks the
 * examples the deployment promised is indistinguishable from one that was seeded. A no-op is
 * legitimate only when the deployment configured no examples file at all.
 */
fun interface WorkspaceContentSeeder {
    /**
     * Imports the configured example content into the freshly created [workspaceId],
     * attributing every row to [userId] — the system actor (auth.md §4.5), because no human
     * created what the product ships.
     *
     * @throws RuntimeException when the configured content cannot be imported — see the KDoc.
     */
    fun seed(
        workspaceId: UUID,
        userId: UUID,
    )
}
