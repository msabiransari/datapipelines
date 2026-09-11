package co.datapipelines.web.ui

import org.thymeleaf.context.WebContext

/**
 * The 114 role attributes, for the render tests that pin MARKUP rather than visibility.
 *
 * Every verb-bearing template now guards on `canAuthor` / `canPromote` / `canExecute` /
 * `canAdminWorkspace` / `isSuperAdmin` (RBAC design §7 — "the role decides what is rendered").
 * A context with none of them set renders as a viewer, which is correct behaviour and wrong for
 * a test whose subject is the swap contract, the icon source or the table's typeface: those
 * would silently start asserting nothing.
 *
 * So the default here is the FULLEST role — everything true. It is deliberately a default and
 * not a fixed value: a test that wants to see a narrower role passes one, and
 * `RoleVisibilityRenderTest` is where the role question itself is asked, screen by screen and
 * verb by verb.
 */
@Suppress("LongParameterList") // seven independent booleans; a parameter object here would be RoleModel.Roles again
fun WebContext.withRoles(
    canRead: Boolean = true,
    canExecute: Boolean = true,
    canAuthor: Boolean = true,
    canPromote: Boolean = true,
    canAdminWorkspace: Boolean = true,
    isSuperAdmin: Boolean = true,
    roleLabel: String = "super admin",
): WebContext =
    apply {
        setVariable("canRead", canRead)
        setVariable("canExecute", canExecute)
        setVariable("canAuthor", canAuthor)
        setVariable("canPromote", canPromote)
        setVariable("canAdminWorkspace", canAdminWorkspace)
        setVariable("isSuperAdmin", isSuperAdmin)
        setVariable("roleLabel", roleLabel)
    }

/** The same set from [RoleModel.Roles], so a render test can stamp exactly what a controller would. */
fun WebContext.withRoles(roles: RoleModel.Roles): WebContext =
    withRoles(
        canRead = roles.canRead,
        canExecute = roles.canExecute,
        canAuthor = roles.canAuthor,
        canPromote = roles.canPromote,
        canAdminWorkspace = roles.canAdminWorkspace,
        isSuperAdmin = roles.isSuperAdmin,
        roleLabel = roles.roleLabel,
    )
