package co.datapipelines.auth

import java.util.UUID

/**
 * Remembers which workspace a session user last worked in, so the next login can stamp it
 * as the JWT's `active_workspace` (design §5.1: "last-used, else first membership, else
 * the freshly provisioned personal workspace").
 *
 * This is deliberately NOT a Postgres table: slice 2 ships no DDL, and last-used is a
 * convenience, not state of record — losing it degrades a login to "first membership",
 * never to a wrong answer. The auth module declares the port; the Redis implementation
 * lives in `web` (module-structure §3.1 rule 3: Redis is talked to from `dag` and `web`
 * only) and is wired at the application layer.
 *
 * Implementations must be **fail-open**: a store outage degrades to `null` (first
 * membership at login, no recorded switch), never to a failed request — the same posture
 * as `RedisRateLimiter`.
 */
interface LastUsedWorkspaceStore {
    /** The last workspace name recorded for [userId], or null when unknown/expired/unavailable. */
    fun lastUsed(userId: UUID): String?

    /**
     * Records [workspaceName] as [userId]'s last-used workspace (called on login stamping
     * and on every successful `DP-Workspace` switch).
     */
    fun recordUsed(
        userId: UUID,
        workspaceName: String,
    )
}
