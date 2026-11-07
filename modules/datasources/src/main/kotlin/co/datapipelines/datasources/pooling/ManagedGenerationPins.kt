package co.datapipelines.datasources.pooling

import java.time.Instant
import java.util.UUID

/** Managed generations acquire protection before publication; the keeper follows physical handles through close. */
interface ManagedGenerationPins : AutoCloseable {
    /** False or an exception refuses the build. Callbacks inspect memory only, never the driver or metadata database. */
    fun pin(
        generation: UUID,
        datasource: String,
        locations: Map<String, String>,
        builtAt: Instant,
        holdsHandles: () -> Boolean,
        retire: () -> Unit,
    ): Boolean

    /** Release only after close returned AND every physical handle's closure was confirmed. */
    fun unpin(generation: UUID)

    /** Generations still owning physical handles, including handles blocked in close. Memory only. */
    fun live(): Set<UUID> = emptySet()

    /** Whether the generation may serve a new query. Lost protection is irreversible for this generation. */
    fun valid(generation: UUID): Boolean

    /** Shutdown barrier: keep protection alive until all physical readers close, then release their rows. */
    override fun close() = Unit

    companion object {
        /** Library callers may omit the metadata adapter; the application's assembly always supplies it. */
        val NONE: ManagedGenerationPins =
            object : ManagedGenerationPins {
                override fun pin(
                    generation: UUID,
                    datasource: String,
                    locations: Map<String, String>,
                    builtAt: Instant,
                    holdsHandles: () -> Boolean,
                    retire: () -> Unit,
                ): Boolean = true

                override fun unpin(generation: UUID) {}

                override fun valid(generation: UUID): Boolean = true
            }
    }
}
