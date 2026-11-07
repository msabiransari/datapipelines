package co.datapipelines.lake.append

import co.datapipelines.lake.LeaseHandle
import java.time.Instant

/** The assembling layer owns periodic coroutine scheduling; lake owns the lease's authority. */
fun interface LakeAppendHeartbeat {
    /** Renew before returning; a late or already lost acquisition must not begin writing files. */
    fun start(
        lease: LeaseHandle,
        deadline: Instant,
        renew: () -> Boolean,
    ): LakeAppendWatch
}

/** Stops issuing mutations after cancellation, deadline or any failed heartbeat. */
interface LakeAppendWatch : AutoCloseable {
    fun checkActive()

    override fun close()
}
