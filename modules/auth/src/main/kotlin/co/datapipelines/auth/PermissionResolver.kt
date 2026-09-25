package co.datapipelines.auth

import java.util.UUID

/**
 * **The one question every member permission decision asks** (security-assurance record §7.1,
 * ratified B4): does a principal holding [role] (null: no membership) in the workspace
 * [workspaceId] (null: none addressed) — or a super admin — hold [permission]?
 *
 * ## Why a seam at all
 * The record's isolated-permission witness (`PermissionSeamE2eTest`) needs a principal that holds
 * ONE permission and nothing else. No role gives that — every role holds dozens — so a route that
 * declares the wrong one of two co-granted permissions (delete declared as create, while every
 * role holding one holds both) passes every role walk. This interface is where a test context
 * substitutes a synthetic grant; the interceptor, the MCP dispatcher and every service keep
 * deciding exactly as they do in production, and none of them knows the difference.
 *
 * ## The production shape (B4) — and what enforces it
 * One interface, ONE production implementation ([RolePermissionsResolver]), no profile and no
 * property that selects another. `PackagedResolverTest` opens the built application jar and fails
 * on a second implementing class or on a packaged test class, and fails on `@Profile` or any
 * `@Conditional…` in the files that declare or register a resolver. Deliberately NOT a
 * `fun interface`: a Kotlin lambda cannot implement it, so every implementation is a named class
 * file the jar scan can count.
 *
 * ## What is not behind it
 * The two KEY-role columns (`api_caller`, `promotion_receiver`): `ScopeMatrix.allowed` reads them
 * straight from [RolePermissions] at admission, and routing only [AuthenticatedPrincipal.holds]'s
 * half through the seam would split one decision across two paths. Nor `ScopeMatrix`'s
 * no-workspace carve-out for a super admin's instance permissions (#113), which reads the user
 * row, not a role. Both are the matrix's, which this seam does not change.
 */
interface PermissionResolver {
    fun holds(
        workspaceId: UUID?,
        role: WorkspaceRole?,
        superAdmin: Boolean,
        permission: Permission,
    ): Boolean
}

/**
 * The production resolver, and the only one packaged: auth.md §7.6's role table, read from
 * [RolePermissions] — the body [RolePermissions.holds] carried before the seam, moved here
 * unchanged. [RolePermissions.holds] now asks [PermissionResolution] and must never be called
 * from here (that would recurse).
 *
 * The workspace is not read: a member's permissions are its role's column, the same in every
 * workspace. It is part of the question because the question is "in this workspace", and it is
 * the one handle a synthetic grant can key on.
 */
object RolePermissionsResolver : PermissionResolver {
    override fun holds(
        workspaceId: UUID?,
        role: WorkspaceRole?,
        superAdmin: Boolean,
        permission: Permission,
    ): Boolean = (superAdmin && permission in RolePermissions.SUPER_ADMIN) || (role != null && permission in RolePermissions.of(role))
}

/**
 * Where the three decision points — [RolePermissions.holds], [WorkspaceContext.permits] and
 * [AuthenticatedPrincipal.holds] — find the resolver.
 *
 * A holder rather than a constructor argument because the decision points are values and an
 * object: a [WorkspaceContext] is built in three services and two seeders, an
 * [AuthenticatedPrincipal] in two filters, a service and two seeders. Threading a bean through every construction site
 * would change all of them for a seam whose production answer never changes; one read here changes
 * none of them.
 *
 * [PermissionResolverInstallation] — the bean that takes the context's ONE [PermissionResolver] —
 * installs it when the context starts and puts [RolePermissionsResolver] back when the context
 * closes, so a test context that installed a synthetic resolver cannot leave it behind in a JVM
 * other contexts share. With no context at all (a unit test), it is [RolePermissionsResolver].
 * [install] is `internal`: nothing outside this module can reach it except through that bean.
 */
object PermissionResolution {
    @Volatile
    private var installed: PermissionResolver = RolePermissionsResolver

    /** The resolver every decision asks. */
    val resolver: PermissionResolver get() = installed

    internal fun install(resolver: PermissionResolver) {
        installed = resolver
    }
}

/**
 * The bean that makes [PermissionResolution] answer with the context's [PermissionResolver] —
 * in production the one [RolePermissionsResolver] bean, so installing it changes nothing — and
 * restores the production resolver on close (Spring calls [close] when the context shuts down).
 */
class PermissionResolverInstallation(
    resolver: PermissionResolver,
) : AutoCloseable {
    init {
        PermissionResolution.install(resolver)
    }

    override fun close() {
        PermissionResolution.install(RolePermissionsResolver)
    }
}
