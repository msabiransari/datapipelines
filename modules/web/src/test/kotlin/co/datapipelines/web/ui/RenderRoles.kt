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
    canReadExecutions: Boolean = canExecute,
    canAuthor: Boolean = true,
    canPromote: Boolean = true,
    canReadPromotion: Boolean = canAuthor || canPromote,
    canAdminWorkspace: Boolean = true,
    isSuperAdmin: Boolean = true,
    roleLabel: String = "super admin",
    // 143 — the shell's Admin entry (UiWorkspaceAdvice, from RoleModel.shell), defaulted
    // the way that helper derives it so a render of the fullest role carries the rail's
    // instance-users link; a test about the entry itself passes both explicitly.
    navAdminUsers: Boolean = isSuperAdmin,
    navAdminMembers: Boolean = canAdminWorkspace && !isSuperAdmin,
    // 2026-09-20 — the three row-following rail items (RoleModel.Shell), derived the way the
    // advice derives them from the booleans above.
    navExecutions: Boolean = canReadExecutions,
    navPromotion: Boolean = canReadPromotion,
    navWorkspaces: Boolean = canAdminWorkspace || isSuperAdmin,
    // 179 — the avatar menu's API-keys link (MANAGE_API_KEYS).
    navApiKeys: Boolean = canAdminWorkspace || isSuperAdmin,
): WebContext =
    apply {
        setVariable("canRead", canRead)
        setVariable("canExecute", canExecute)
        setVariable("canReadExecutions", canReadExecutions)
        setVariable("canAuthor", canAuthor)
        setVariable("canReadPromotion", canReadPromotion)
        setVariable("canPromote", canPromote)
        setVariable("canAdminWorkspace", canAdminWorkspace)
        setVariable("isSuperAdmin", isSuperAdmin)
        setVariable("roleLabel", roleLabel)
        setVariable("navAdminUsers", navAdminUsers)
        setVariable("navAdminMembers", navAdminMembers)
        setVariable("navExecutions", navExecutions)
        setVariable("navPromotion", navPromotion)
        setVariable("navWorkspaces", navWorkspaces)
        setVariable("navApiKeys", navApiKeys)
    }

/** The same set from [RoleModel.Roles], so a render test can stamp exactly what a controller would. */
fun WebContext.withRoles(roles: RoleModel.Roles): WebContext =
    withRoles(
        canRead = roles.canRead,
        canExecute = roles.canExecute,
        canReadExecutions = roles.canReadExecutions,
        canAuthor = roles.canAuthor,
        canReadPromotion = roles.canReadPromotion,
        canPromote = roles.canPromote,
        canAdminWorkspace = roles.canAdminWorkspace,
        isSuperAdmin = roles.isSuperAdmin,
        roleLabel = roles.roleLabel,
    )
