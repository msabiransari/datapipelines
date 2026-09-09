package co.datapipelines.pipeline

/**
 * Which surface a version-row WRITE arrived on (102, owner ruling 2026-09-09) — the column
 * value of `pipeline_versions.created_via` / `updated_via` and their `template_versions`
 * twins, constrained `CHECK IN` by V20.
 *
 * It records the SURFACE, never the credential: `created_by` / `updated_by` keep naming the
 * person on every path (a key principal's writes are its OWNER's — no key id is stored on any
 * row), and this enum answers the other half of "who wrote this": through what door.
 *
 * Stamped at the ENTRY POINT, not inferred: the REST controllers map the principal's auth
 * method, the MCP tools pass [MCP] (they are the only caller that knows the call is MCP —
 * an MCP call is API-key-authenticated, so the auth method alone cannot tell them apart),
 * and the import/seed paths keep the database default [SESSION] wire value (versioning §3.7).
 */
enum class WriteSurface(
    val wire: String,
) {
    SESSION("session"),
    API_KEY("api_key"),
    MCP("mcp"),
}
