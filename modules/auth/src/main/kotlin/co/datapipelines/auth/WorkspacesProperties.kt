package co.datapipelines.auth

import org.springframework.boot.context.properties.ConfigurationProperties

/** How workspaces come into existence (design §7, configuration.md §3.17). */
enum class WorkspaceProvisioningMode {
    /** Every first OIDC login auto-creates a personal workspace (the datapipelines.co demo shape). */
    AUTO_PER_USER,

    /** Any authenticated user creates workspaces (company default); join per `open-join`. */
    SELF_SERVE,

    /** Only `admin` creates workspaces and manages membership — the future invite flow's base. */
    CLOSED,
    ;

    /** Lowercase hyphenated wire/config form (`auto-per-user`) — the configuration.md §3.17 spelling. */
    val wire: String get() = name.lowercase().replace('_', '-')
}

/**
 * The `datapipelines.workspaces.*` keys (configuration.md §3.17). `auto-per-user` etc.
 * bind to [WorkspaceProvisioningMode] by Spring's relaxed enum binding (case- and
 * dash-insensitive); [ConfigValidator][co.datapipelines.config.ConfigValidator] rejects
 * anything else at startup with a named violation.
 */
@ConfigurationProperties(prefix = "datapipelines.workspaces")
data class WorkspacesProperties(
    val provisioningMode: WorkspaceProvisioningMode = WorkspaceProvisioningMode.SELF_SERVE,
    /** `self-serve` only: `true` lists all workspaces as joinable by any authenticated user. */
    val openJoin: Boolean = false,
    /** D8 gate: may non-admin members create workspace-bound datasources (consumed by slice 021). */
    val memberDatasourcesEnabled: Boolean = true,
)
