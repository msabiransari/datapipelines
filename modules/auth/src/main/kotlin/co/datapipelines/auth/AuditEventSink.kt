package co.datapipelines.auth

import java.util.UUID

/**
 * The `audit_log` write contract, as seen by consumers that must not depend on HOW rows
 * land (auth.md §10, observability §3) — the sink behind [AuditLogger].
 *
 * Extracted (052) so the MCP dispatcher can emit its `mcp.tool.*` events through the same
 * sink the `auth.*` events use while remaining testable against a real in-memory
 * implementation: a strict mock over a "must be called" contract makes a MISSING emission
 * unobservable, so consumers' tests assert the recorded effect, not the interaction.
 *
 * `details` is redaction-bound — implementations and callers must not let credentials,
 * SQL text or row data through here.
 */
interface AuditEventSink {
    fun log(
        event: String,
        userId: UUID? = null,
        keyId: String? = null,
        sourceIp: String? = null,
        userAgent: String? = null,
        details: Map<String, Any?> = emptyMap(),
    )
}
