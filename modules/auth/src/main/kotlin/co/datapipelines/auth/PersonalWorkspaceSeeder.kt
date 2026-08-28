package co.datapipelines.auth

import java.util.UUID

/**
 * The D9 example-content hook (sample-data design §6.1): called once, immediately after an
 * `auto-per-user` personal workspace row and its owner membership exist, so a brand-new user's
 * first screen is a working cross-datasource example rather than an empty list.
 *
 * `auth` declares the port and `web` implements it, because seeding goes through the pipeline
 * and template **import** services and `auth` may depend on `typesystem` only
 * (module-structure §4.2). That constraint is also why the signature is pure JDK types: `auth`
 * cannot name `Pipeline` or `TemplateDraft`. Same inversion as [LastUsedWorkspaceStore], and
 * wired the same way — an `ObjectProvider` in `AuthConfiguration`, so auth-only test contexts
 * legitimately run without one.
 *
 * ## Deliberately NOT fail-open
 * [LastUsedWorkspaceStore] degrades to a worse answer; this degrades to a **wrong** one — a
 * personal workspace that silently lacks the examples the deployment promised, indistinguishable
 * from one that was seeded. So an implementation that cannot seed must throw: the exception
 * propagates out of `WorkspaceService.ensurePersonalWorkspace` and fails the login loudly.
 * A no-op is legitimate only when the deployment configured no examples file at all.
 */
fun interface PersonalWorkspaceSeeder {
    /**
     * Imports the configured example content into the freshly provisioned [workspaceId],
     * attributing every row to [userId] (its owner).
     *
     * @throws RuntimeException when the configured content cannot be imported — see the KDoc.
     */
    fun seed(
        workspaceId: UUID,
        userId: UUID,
    )
}
