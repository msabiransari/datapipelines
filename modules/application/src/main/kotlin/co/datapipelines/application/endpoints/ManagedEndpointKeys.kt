package co.datapipelines.application.endpoints

import java.util.UUID

/**
 * Endpoint keys this deployment manages itself, whose namespace-writes elevation (#1111) is refused whatever the
 * caller's role — today the demo workspace's public read key, which the demo seeder mints from published
 * configuration and reconciles on every boot. Identity is the manager's own (a key id in a workspace), never a name
 * alone: a key of the same name in another workspace is an ordinary key.
 */
fun interface ManagedEndpointKeys {
    /** True when [apiKeyId] in [workspaceId] is a key this deployment manages and must never elevate. */
    fun isManaged(
        apiKeyId: String,
        workspaceId: UUID,
    ): Boolean

    companion object {
        /** No managed keys: a surface built without a demo seeder (tests, tools). */
        val NONE = ManagedEndpointKeys { _, _ -> false }
    }
}
