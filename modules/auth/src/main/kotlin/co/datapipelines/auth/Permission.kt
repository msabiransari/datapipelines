package co.datapipelines.auth

/**
 * The permission CATALOG (#215; the permissions and keys record,
 * `docs/superpowers/specs/2026-09-23-permissions-and-keys-design.md` §2, ratified 2026-09-23):
 * one `<functionality>.<permission>` per piece of functionality a
 * person can take separately (PK1). Every REST handler, UI route and MCP tool declares exactly
 * ONE of these, and code asks whether a principal HOLDS one — it never compares role names.
 *
 * The wire form ([wire], `pipeline.read`) is what auth.md §7.6's catalog table, the refusal's
 * `required` detail and the audit carry; the constant name is its code spelling. Which roles
 * hold a permission is NOT written here: [RolePermissions] is the one role table, and [roles]
 * and [satisfiedBy] are derived from it, so a permission cannot carry a role list that
 * disagrees with the table.
 *
 * Granularity rule (record §2): two permissions stay separate even while every role holds
 * both (`pipeline.create`, `pipeline.import`), so a later ruling can split them without
 * touching a handler. Declaration order is the record's §2 order and auth.md §7.6's row order.
 */
enum class Permission(
    /** The `<functionality>.<permission>` token — auth.md §7.6's first column. */
    val wire: String,
) {
    // §2.1 — pipelines and templates
    PIPELINE_READ("pipeline.read"),
    PIPELINE_CREATE("pipeline.create"),
    PIPELINE_UPDATE("pipeline.update"),
    PIPELINE_VERSION_MANAGE("pipeline.version.manage"),
    PIPELINE_DELETE("pipeline.delete"),
    PIPELINE_IMPORT("pipeline.import"),
    PIPELINE_RELEASE("pipeline.release"),
    PIPELINE_SWITCH_VERSION("pipeline.switch_version"),
    PIPELINE_EXECUTE("pipeline.execute"),
    PIPELINE_RUN_CHECKS("pipeline.run_checks"),
    PIPELINE_EXECUTE_NODE("pipeline.execute_node"),
    TEMPLATE_READ("template.read"),
    TEMPLATE_CREATE("template.create"),
    TEMPLATE_UPDATE("template.update"),
    TEMPLATE_VERSION_MANAGE("template.version.manage"),
    TEMPLATE_DELETE("template.delete"),
    TEMPLATE_IMPORT("template.import"),
    TEMPLATE_RENDER("template.render"),
    TEMPLATE_EVALUATE("template.evaluate"),
    TEMPLATE_RELEASE("template.release"),
    TEMPLATE_SWITCH_VERSION("template.switch_version"),

    // §2.2 — executions. The two `_all` rows have no surface of their own: they lift "own"
    // (D11) where a read or cancel path asks for them.
    EXECUTION_READ("execution.read"),
    EXECUTION_RESULT_READ("execution.result.read"),
    EXECUTION_READ_ALL("execution.read_all"),
    EXECUTION_CANCEL("execution.cancel"),
    EXECUTION_CANCEL_ALL("execution.cancel_all"),

    // §2.3 — datasources and the lake catalog
    DATASOURCE_READ("datasource.read"),
    DATASOURCE_INTROSPECT("datasource.introspect"),
    DATASOURCE_TEST("datasource.test"),
    DATASOURCE_PREVIEW_ROWS("datasource.preview_rows"),
    DATASOURCE_SQL_PROBE("datasource.sql_probe"),
    DATASOURCE_MANAGE("datasource.manage"),
    DATASOURCE_GRANT("datasource.grant"),
    LAKE_TABLE_MANAGE("lake_table.manage"),

    // §2.4 — published endpoints, semantics, reference
    ENDPOINT_READ("endpoint.read"),
    ENDPOINT_PUBLISH("endpoint.publish"),
    ENDPOINT_UNPUBLISH("endpoint.unpublish"),
    ENDPOINT_SERVE("endpoint.serve"),
    SEMANTIC_READ("semantic.read"),
    SEMANTIC_RECORD("semantic.record"),
    SEMANTIC_RETIRE("semantic.retire"),
    CALCULATOR_READ("calculator.read"),
    DOCS_READ("docs.read"),

    // §2.5 — promotion. The two receiving rows are FENCED ([RolePermissions.FENCED]).
    PROMOTION_READ("promotion.read"),
    PROMOTION_PROMOTE("promotion.promote"),
    PROMOTION_INVENTORY_READ("promotion.inventory.read"),
    PROMOTION_PUSH("promotion.push"),

    // §2.6 — keys, workspaces, users, profile
    MCP_KEY_OWN("mcp_key.own"),
    API_KEY_READ("api_key.read"),
    API_KEY_CREATE("api_key.create"),
    API_KEY_REVOKE("api_key.revoke"),
    API_KEY_BIND("api_key.bind"),
    SERVER_KEY_CREATE("server_key.create"),
    SERVER_KEY_REVOKE("server_key.revoke"),
    WORKSPACE_SWITCH("workspace.switch"),
    WORKSPACE_READ("workspace.read"),
    WORKSPACE_UPDATE("workspace.update"),
    WORKSPACE_MEMBERS_MANAGE("workspace.members.manage"),
    WORKSPACE_CREATE("workspace.create"),
    WORKSPACE_LIFECYCLE("workspace.lifecycle"),
    USER_MANAGE("user.manage"),
    USER_IDENTITY_RESET("user.identity_reset"),
    PROFILE_READ("profile.read"),
    PROFILE_PREFERENCE("profile.preference"),
    PROFILE_PASSWORD("profile.password"),
    ;

    /** The workspace roles holding this permission — [RolePermissions]' column read as a row. A super admin is not a role (D7). */
    val roles: Set<WorkspaceRole> get() = RolePermissions.rolesHolding(this)

    /**
     * True when a member holding [role] — or a super admin — holds this permission; the one
     * question [WorkspaceContext.permits] and the principal's predicates ask. A null [role] is
     * "no membership": only the super admin flag can answer yes then.
     */
    fun satisfiedBy(
        role: WorkspaceRole?,
        superAdmin: Boolean,
    ): Boolean = RolePermissions.holds(role, superAdmin, this)

    companion object {
        /** Parses a wire token (`pipeline.read`). Throws on an unknown token — a permission that does not exist is a defect. */
        fun fromWire(token: String): Permission =
            entries.firstOrNull { it.wire == token } ?: throw IllegalArgumentException("Unknown permission: $token")
    }
}
