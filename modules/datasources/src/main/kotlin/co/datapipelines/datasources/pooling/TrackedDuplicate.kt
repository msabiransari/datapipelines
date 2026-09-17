package co.datapipelines.datasources.pooling

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 152 (R152-2) — a physical duplicate of a LAKE generation's instance, registered with the
 * [LakeInstanceOwner] that created it until it is closed by whoever holds it.
 *
 * HikariCP holds it from `DataSource.getConnection()` on: through its own JDBC setup
 * (`isReadOnly`, auto-commit, validation), bag admission, every lease, and the close or abort
 * at shutdown. All of that passes through here unchanged — this wrapper adds exactly one thing:
 * [close] and [abort] unregister the handle from the generation, so that at the generation's
 * own close the handles still registered are precisely the ones NOBODY else will close (a
 * creation Hikari's closed bag refused, or a borrower it abandoned), and the owner closes them.
 *
 * `unwrap` still reaches the driver's connection (the identity tests and any driver-specific
 * caller see the real thing); `isWrapperFor` says so.
 */
internal class TrackedDuplicate(
    private val delegate: Connection,
    private val onRelease: (TrackedDuplicate) -> Unit,
) : Connection by delegate {
    private val released = AtomicBoolean(false)

    override fun close() {
        release()
        delegate.close()
    }

    override fun abort(executor: Executor?) {
        release()
        delegate.abort(executor)
    }

    /** The generation's own close: the handle is already out of the set; only the driver close remains. */
    @Throws(SQLException::class)
    fun closeUnowned() {
        released.set(true)
        delegate.close()
    }

    private fun release() {
        if (released.compareAndSet(false, true)) onRelease(this)
    }

    override fun <T : Any?> unwrap(iface: Class<T>?): T =
        if (iface != null && iface.isInstance(delegate)) iface.cast(delegate) else delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>?): Boolean = (iface != null && iface.isInstance(delegate)) || delegate.isWrapperFor(iface)
}
