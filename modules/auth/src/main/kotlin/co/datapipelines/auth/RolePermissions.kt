package co.datapipelines.auth

import co.datapipelines.auth.Permission.API_KEY_BIND
import co.datapipelines.auth.Permission.API_KEY_CREATE
import co.datapipelines.auth.Permission.API_KEY_READ
import co.datapipelines.auth.Permission.API_KEY_REVOKE
import co.datapipelines.auth.Permission.CALCULATOR_READ
import co.datapipelines.auth.Permission.DATASOURCE_GRANT
import co.datapipelines.auth.Permission.DATASOURCE_INTROSPECT
import co.datapipelines.auth.Permission.DATASOURCE_MANAGE
import co.datapipelines.auth.Permission.DATASOURCE_PREVIEW_ROWS
import co.datapipelines.auth.Permission.DATASOURCE_READ
import co.datapipelines.auth.Permission.DATASOURCE_SQL_PROBE
import co.datapipelines.auth.Permission.DATASOURCE_TEST
import co.datapipelines.auth.Permission.DOCS_READ
import co.datapipelines.auth.Permission.ENDPOINT_PUBLISH
import co.datapipelines.auth.Permission.ENDPOINT_READ
import co.datapipelines.auth.Permission.ENDPOINT_SERVE
import co.datapipelines.auth.Permission.ENDPOINT_UNPUBLISH
import co.datapipelines.auth.Permission.EXECUTION_CANCEL
import co.datapipelines.auth.Permission.EXECUTION_CANCEL_ALL
import co.datapipelines.auth.Permission.EXECUTION_READ
import co.datapipelines.auth.Permission.EXECUTION_READ_ALL
import co.datapipelines.auth.Permission.EXECUTION_RESULT_READ
import co.datapipelines.auth.Permission.LAKE_TABLE_MANAGE
import co.datapipelines.auth.Permission.MCP_KEY_CREATE
import co.datapipelines.auth.Permission.MCP_KEY_OWN
import co.datapipelines.auth.Permission.MCP_KEY_REVOKE_OWN
import co.datapipelines.auth.Permission.PIPELINE_CREATE
import co.datapipelines.auth.Permission.PIPELINE_DELETE
import co.datapipelines.auth.Permission.PIPELINE_EXECUTE
import co.datapipelines.auth.Permission.PIPELINE_EXECUTE_NODE
import co.datapipelines.auth.Permission.PIPELINE_IMPORT
import co.datapipelines.auth.Permission.PIPELINE_READ
import co.datapipelines.auth.Permission.PIPELINE_RELEASE
import co.datapipelines.auth.Permission.PIPELINE_RUN_CHECKS
import co.datapipelines.auth.Permission.PIPELINE_SWITCH_VERSION
import co.datapipelines.auth.Permission.PIPELINE_UPDATE
import co.datapipelines.auth.Permission.PIPELINE_VERSION_MANAGE
import co.datapipelines.auth.Permission.PROFILE_PASSWORD
import co.datapipelines.auth.Permission.PROFILE_PREFERENCE
import co.datapipelines.auth.Permission.PROFILE_READ
import co.datapipelines.auth.Permission.PROMOTION_INVENTORY_READ
import co.datapipelines.auth.Permission.PROMOTION_PROMOTE
import co.datapipelines.auth.Permission.PROMOTION_PUSH
import co.datapipelines.auth.Permission.PROMOTION_READ
import co.datapipelines.auth.Permission.SEMANTIC_READ
import co.datapipelines.auth.Permission.SEMANTIC_RECORD
import co.datapipelines.auth.Permission.SEMANTIC_RETIRE
import co.datapipelines.auth.Permission.SERVER_KEY_CREATE
import co.datapipelines.auth.Permission.SERVER_KEY_REVOKE
import co.datapipelines.auth.Permission.TEMPLATE_CREATE
import co.datapipelines.auth.Permission.TEMPLATE_DELETE
import co.datapipelines.auth.Permission.TEMPLATE_EVALUATE
import co.datapipelines.auth.Permission.TEMPLATE_IMPORT
import co.datapipelines.auth.Permission.TEMPLATE_READ
import co.datapipelines.auth.Permission.TEMPLATE_RELEASE
import co.datapipelines.auth.Permission.TEMPLATE_RENDER
import co.datapipelines.auth.Permission.TEMPLATE_SWITCH_VERSION
import co.datapipelines.auth.Permission.TEMPLATE_UPDATE
import co.datapipelines.auth.Permission.TEMPLATE_VERSION_MANAGE
import co.datapipelines.auth.Permission.USER_IDENTITY_RESET
import co.datapipelines.auth.Permission.USER_MANAGE
import co.datapipelines.auth.Permission.WORKSPACE_CREATE
import co.datapipelines.auth.Permission.WORKSPACE_LIFECYCLE
import co.datapipelines.auth.Permission.WORKSPACE_MEMBERS_MANAGE
import co.datapipelines.auth.Permission.WORKSPACE_READ
import co.datapipelines.auth.Permission.WORKSPACE_SWITCH
import co.datapipelines.auth.Permission.WORKSPACE_UPDATE

/**
 * **The** role table (#215, record §2): which [Permission]s each [WorkspaceRole] holds — auth.md
 * §7.6's catalog read column by column. The ONLY place in main code a role is related to what
 * it may do: [Permission.roles] and [Permission.satisfiedBy] are derived from here, and
 * `ScopeMatrixSpecDriftTest` compares every column with the doc's, in both directions.
 *
 * The roles are not a chain, so each column is written out rather than built from another:
 * the promoter reads and promotes but executes nothing (D5), the author releases but does not
 * promote (D8). A super admin is not a role (D7, `users.is_admin`): it holds [SUPER_ADMIN] —
 * every permission but the two [FENCED] ones — in every workspace.
 *
 * The KEY roles (#215 slice (b), widened by keys v2 — record §3.2, A13/A14/B1) are columns of the
 * same table: [of] a [KeyRole]. The three MEMBER key roles (`author`, `promoter`, `workspace_admin`
 * — what an `mcp` key carries) read the SAME columns the member roles hold; the two transport
 * roles (`api_caller`, `promotion_receiver`) are key-only. A key role never includes an instance
 * permission and never the member-only administration verbs beyond its column's reach —
 * `ScopeMatrixSpecDriftTest` compares the columns with auth.md §7.6, and `RoleMatrixTest`
 * pins that no key role reaches [INSTANCE].
 */
object RolePermissions {
    /** Reads, runs, reads and cancels its OWN executions, tests a connection (D3). Changes nothing. */
    private val VIEWER: Set<Permission> =
        setOf(
            PIPELINE_READ,
            PIPELINE_EXECUTE,
            PIPELINE_RUN_CHECKS,
            TEMPLATE_READ,
            EXECUTION_READ,
            EXECUTION_RESULT_READ,
            EXECUTION_CANCEL,
            DATASOURCE_READ,
            DATASOURCE_INTROSPECT,
            DATASOURCE_TEST,
            ENDPOINT_READ,
            ENDPOINT_SERVE,
            SEMANTIC_READ,
            CALCULATOR_READ,
            DOCS_READ,
            MCP_KEY_OWN,
            WORKSPACE_SWITCH,
            PROFILE_READ,
            PROFILE_PREFERENCE,
            PROFILE_PASSWORD,
        )

    /** The viewer's column + authoring, release, switch, endpoints, lake tables, facts, row-data probes, promotion page (D4, D8). */
    private val AUTHOR: Set<Permission> =
        VIEWER +
            setOf(
                PIPELINE_CREATE,
                PIPELINE_UPDATE,
                PIPELINE_VERSION_MANAGE,
                PIPELINE_DELETE,
                PIPELINE_IMPORT,
                PIPELINE_RELEASE,
                PIPELINE_SWITCH_VERSION,
                PIPELINE_EXECUTE_NODE,
                TEMPLATE_CREATE,
                TEMPLATE_UPDATE,
                TEMPLATE_VERSION_MANAGE,
                TEMPLATE_DELETE,
                TEMPLATE_IMPORT,
                TEMPLATE_RENDER,
                TEMPLATE_EVALUATE,
                TEMPLATE_RELEASE,
                TEMPLATE_SWITCH_VERSION,
                DATASOURCE_PREVIEW_ROWS,
                DATASOURCE_SQL_PROBE,
                LAKE_TABLE_MANAGE,
                ENDPOINT_PUBLISH,
                ENDPOINT_UNPUBLISH,
                SEMANTIC_RECORD,
                SEMANTIC_RETIRE,
                PROMOTION_READ,
                MCP_KEY_CREATE,
                MCP_KEY_REVOKE_OWN,
            )

    /**
     * The ops role (D5): reads (through the LENS, #178 — a value on every read, not a row here),
     * introspects, reads the promotion page and promotes. Executes nothing, reads no executions,
     * authors and releases nothing.
     */
    private val PROMOTER: Set<Permission> =
        setOf(
            PIPELINE_READ,
            TEMPLATE_READ,
            DATASOURCE_READ,
            DATASOURCE_INTROSPECT,
            ENDPOINT_READ,
            ENDPOINT_SERVE,
            SEMANTIC_READ,
            CALCULATOR_READ,
            DOCS_READ,
            PROMOTION_READ,
            PROMOTION_PROMOTE,
            MCP_KEY_OWN,
            MCP_KEY_CREATE,
            MCP_KEY_REVOKE_OWN,
            WORKSPACE_SWITCH,
            PROFILE_READ,
            PROFILE_PREFERENCE,
            PROFILE_PASSWORD,
        )

    /** Everything in the workspace (D6): the author's column + every execution, datasources, promotion, keys, members. */
    private val WORKSPACE_ADMIN: Set<Permission> =
        AUTHOR +
            setOf(
                EXECUTION_READ_ALL,
                EXECUTION_CANCEL_ALL,
                DATASOURCE_MANAGE,
                PROMOTION_PROMOTE,
                API_KEY_READ,
                API_KEY_CREATE,
                API_KEY_REVOKE,
                API_KEY_BIND,
                WORKSPACE_READ,
                WORKSPACE_UPDATE,
                WORKSPACE_MEMBERS_MANAGE,
            )

    /**
     * An `endpoint` key (record §3.2, owner ruling 2026-09-24): serve the published paths bound to
     * it, and read the executions it started — their metadata and their result, "own" only (the
     * read paths judge ownership against the key's identity). Exactly today's endpoint key.
     */
    private val API_CALLER: Set<Permission> = setOf(ENDPOINT_SERVE, EXECUTION_READ, EXECUTION_RESULT_READ)

    /**
     * A `server` key (record §3.2, C4, B6): the promotion receiver's inventory and push — for any
     * workspace the batch names — and nothing else.
     */
    private val PROMOTION_RECEIVER: Set<Permission> = setOf(PROMOTION_INVENTORY_READ, PROMOTION_PUSH)

    /**
     * The promotion RECEIVING rows: no MEMBER role holds them, a super admin included. Their one
     * path is the server-key route family (`PromotionServerKeyFilter`, §7.7), whose principal
     * holds them through the [KeyRole.PROMOTION_RECEIVER] column.
     */
    val FENCED: Set<Permission> = setOf(PROMOTION_INVENTORY_READ, PROMOTION_PUSH)

    /** D7: a super admin holds every permission in every workspace — except the [FENCED] ones, which are nobody's. */
    val SUPER_ADMIN: Set<Permission> = Permission.entries.toSet() - FENCED

    /**
     * The INSTANCE permissions: held by a super admin and by no workspace role, because none of
     * them reads or writes a workspace's content. A super admin holds them with no reachable
     * workspace at all (#113's empty-instance recovery) — [holds] and `ScopeMatrix.allowed`
     * both read this set. Explicit rather than computed, and pinned equal to "super admin's
     * column minus every role's" by `RoleMatrixTest`.
     */
    val INSTANCE: Set<Permission> =
        setOf(
            DATASOURCE_GRANT,
            SERVER_KEY_CREATE,
            SERVER_KEY_REVOKE,
            WORKSPACE_CREATE,
            WORKSPACE_LIFECYCLE,
            USER_MANAGE,
            USER_IDENTITY_RESET,
        )

    /** Every permission [role] holds — one column of the table. */
    fun of(role: WorkspaceRole): Set<Permission> =
        when (role) {
            WorkspaceRole.VIEWER -> VIEWER
            WorkspaceRole.AUTHOR -> AUTHOR
            WorkspaceRole.PROMOTER -> PROMOTER
            WorkspaceRole.WORKSPACE_ADMIN -> WORKSPACE_ADMIN
        }

    /** Every permission a key of [role] holds — one key-role column of the table (keys v2 B1: the union enum). */
    fun of(role: KeyRole): Set<Permission> =
        when (role) {
            KeyRole.API_CALLER -> API_CALLER
            KeyRole.PROMOTION_RECEIVER -> PROMOTION_RECEIVER
            KeyRole.AUTHOR -> AUTHOR
            KeyRole.PROMOTER -> PROMOTER
            KeyRole.WORKSPACE_ADMIN -> WORKSPACE_ADMIN
        }

    /**
     * The MEMBER roles an `mcp` key may carry, in dialog order — author first (keys v2 A15).
     * Viewer is never a key role (A15) and super admin is not a role at all (D7, B1).
     */
    val KEY_OFFERABLE: List<WorkspaceRole> = listOf(WorkspaceRole.AUTHOR, WorkspaceRole.PROMOTER, WorkspaceRole.WORKSPACE_ADMIN)

    /**
     * The subset rule (keys v2 A14, record O3 ruled): which key roles [creator]'s permission set
     * may mint — exactly those whose column is a SUBSET of the creator's own permissions in that
     * workspace. No ordinal ladder exists: promoter and author are not comparable, and neither
     * may mint the other; a workspace admin's column contains both, so an admin may mint any of
     * the three; a super admin holds every permission, so any role. The same predicate, applied
     * to the requested role alone, is the service's creation guard (`ApiKeyService.issue`).
     */
    fun offerable(creator: Set<Permission>): Set<WorkspaceRole> =
        KEY_OFFERABLE.filterTo(mutableSetOf()) { role -> creator.containsAll(of(role)) }

    /** The workspace roles whose column holds [permission]. */
    fun rolesHolding(permission: Permission): Set<WorkspaceRole> = WorkspaceRole.entries.filterTo(mutableSetOf()) { permission in of(it) }

    /**
     * Does a member holding [role] (null: no membership) — or a super admin — hold [permission]?
     * Asked of the installed [PermissionResolver] (security-assurance record §7.1, B4), with no
     * workspace named: the callers here judge a role, not a request's context. In production that
     * is [RolePermissionsResolver], which answers from this table.
     */
    fun holds(
        role: WorkspaceRole?,
        superAdmin: Boolean,
        permission: Permission,
    ): Boolean = PermissionResolution.resolver.holds(null, role, superAdmin, permission)
}
