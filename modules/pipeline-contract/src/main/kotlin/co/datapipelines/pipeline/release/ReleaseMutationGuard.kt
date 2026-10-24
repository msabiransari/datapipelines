package co.datapipelines.pipeline.release

import java.util.UUID

/**
 * The workspace metadata writer guard (record §2, §4.3): every pin-affecting write — saves, creates, purges, discards,
 * restores, pointer switches, imports, promotion receives, visualization evidence and releases — acquires it INSIDE its
 * metadata transaction, BEFORE any version or evidence row lock, so a release's re-resolution cannot interleave with a
 * write that changes what it resolved. Held to the transaction's end; other workspaces are independent.
 *
 * Exhausting the configured wait refuses `<family>.release.busy` ([family] is the caller's), with nothing written.
 * Production wires the row lock on the existing `workspaces` row (`web`'s `JdbcReleaseMutationGuard`); [NONE] is for
 * directly constructed services in unit tests, which run without a database transaction at all.
 */
fun interface ReleaseMutationGuard {
    fun acquire(
        workspaceId: UUID,
        family: ReleaseFamily,
    )

    companion object {
        /** No database, no lock — the unit-test construction default. Production never wires it. */
        val NONE = ReleaseMutationGuard { _, _ -> }
    }
}
