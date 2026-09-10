package co.datapipelines.auth

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The `datapipelines.workspaces.*` keys (configuration.md §3.17).
 *
 * ## What round 1 removed (D-R11)
 * `provisioning-mode` and `open-join` are GONE — workspaces are created by super admins and
 * the out-of-the-box workspace is `demo`. Both keys are refused BY NAME at startup
 * (`ConfigValidator`) rather than ignored: a deployment that still sets `auto-per-user` is a
 * deployment expecting per-user workspaces, and silently giving it something else is how an
 * operator finds out from a user.
 */
@ConfigurationProperties(prefix = "datapipelines.workspaces")
data class WorkspacesProperties(
    /**
     * May a workspace ADMIN register a datasource bound to their own workspace (design §4)?
     * `false` makes datasource registration a super-admin-only act instance-wide.
     */
    val memberDatasourcesEnabled: Boolean = true,
)
