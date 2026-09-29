package co.datapipelines.persistence

/**
 * The bounds and cadence of one [BatchingWriter] — every value a `datapipelines.persistence.*` key
 * ([Configuration §3.32](../../../../../../../../docs/configuration.md)); the binding classes that
 * read those keys build one of these. Sizes are PER WRITER INSTANCE: each store the application
 * batches (the audit log, the execution-event record, the replay log) owns its own writer.
 */
data class BatchingConfig(
    /** A batch is flushed at this many items at most. */
    val batchMaxEvents: Int = DEFAULT_BATCH_MAX_EVENTS,
    /** …or at this many bytes ([BatchSink.sizeOf]), whichever comes first. One item larger than this is its own batch. */
    val batchMaxBytes: Long = ONE_MIB,
    /**
     * How long a BUSY writer waits for a fuller batch — busy meaning items were already queued
     * when its previous commit finished. A writer that was idle writes a lone item at once: there
     * is no linger at idle, so a quiet system pays nothing for the batching.
     */
    val lingerMillis: Long = 0,
    /** Items admitted and not yet finished — queued or in a commit — across all partitions. */
    val queueMaxEvents: Int = DEFAULT_QUEUE_MAX_EVENTS,
    /** The same bound in bytes; one item larger than this is still admitted into an EMPTY queue. */
    val queueMaxBytes: Long = DEFAULT_QUEUE_MAX_BYTES,
    /**
     * How long [BatchingWriter.record] waits for room in a full queue and then for its item's
     * commit before the caller writes the item itself (the direct path). The bounded suspending
     * record additionally waits at most this long again — for the direct write, or for an item
     * already inside a commit — before it reports [Outcome.Indeterminate].
     */
    val recordMaxWaitMillis: Long = DEFAULT_RECORD_MAX_WAIT_MILLIS,
    /** Writer threads = partitions. An item's partition is a function of its key, so one key is one writer's, in order. */
    val writers: Int = DEFAULT_WRITERS,
    /** How long [BatchingWriter.stop] keeps flushing before it gives up and reports what it lost. */
    val shutdownDrainMillis: Long = DEFAULT_SHUTDOWN_DRAIN_MILLIS,
) {
    init {
        require(batchMaxEvents > 0) { "batch-max-events must be > 0" }
        require(batchMaxBytes > 0) { "batch-max-bytes must be > 0" }
        require(lingerMillis >= 0) { "linger-ms must be >= 0" }
        require(queueMaxEvents > 0) { "queue-max-events must be > 0" }
        require(queueMaxBytes > 0) { "queue-max-bytes must be > 0" }
        require(recordMaxWaitMillis > 0) { "record-max-wait-ms must be > 0" }
        require(writers > 0) { "writers must be > 0" }
        require(shutdownDrainMillis >= 0) { "shutdown-drain-ms must be >= 0" }
    }

    companion object {
        const val ONE_MIB: Long = 1024L * 1024L

        // The defaults configuration.md §3.32 documents — the binding classes default to these.
        const val DEFAULT_BATCH_MAX_EVENTS = 200
        const val DEFAULT_QUEUE_MAX_EVENTS = 10_000
        const val DEFAULT_QUEUE_MAX_BYTES: Long = 32 * ONE_MIB
        const val DEFAULT_RECORD_MAX_WAIT_MILLIS = 2_000L
        const val DEFAULT_WRITERS = 4
        const val DEFAULT_SHUTDOWN_DRAIN_MILLIS = 10_000L
    }
}

/**
 * What one store needs to say for the writer to batch it. The writer never looks inside an item:
 * it asks the sink for the item's partition key, its size and a log-safe description, and hands
 * the item back to [write] untouched — payloads are the store's business, never the writer's
 * (observability §9.2: a WARN about a failed row names ids and counts, never a payload).
 */
interface BatchSink<T> {
    /**
     * Writes [items] — one to [BatchingConfig.batchMaxEvents] of them, in record order — as ONE
     * unit, and throws when it could not. The writer retries a thrown batch one item at a time
     * through this same function, so a store whose batch can half-apply must make a re-sent item
     * harmless (the execution-event record's `ON CONFLICT (execution_id, event_id)` clause).
     */
    fun write(items: List<T>)

    /**
     * The direct path: one item, on the CALLER's thread, when the queue could not take it in time
     * or the writer has stopped. Defaults to [write] of one; a store whose direct path is a
     * different statement (the audit log's own INSERT) overrides it.
     */
    fun writeOne(item: T) = write(listOf(item))

    /** Items with equal keys share a partition and are written in record order; null is a key like any other. */
    fun partitionKey(item: T): Any?

    /** The item's weight against the byte bounds — an estimate is enough; it must be cheap and never negative. */
    fun sizeOf(item: T): Int

    /** The item's ids for a log line — never its payload. */
    fun describe(item: T): String

    /** The failure-kind tag for a write that threw — `poison` for a row the store refuses, anything else for an outage. */
    fun classify(failure: Throwable): String = FailureKinds.WRITE_FAILED

    /**
     * Whether [failure] belongs to the CALLER rather than to the item's outcome (#266b). True →
     * [BatchingWriter.record] and [BatchingWriter.recordSuspending] rethrow it on the caller's
     * thread — after the writer has counted and logged it like any failure — whether it came from a
     * batch (the instance the writer thread caught, so its stack is the writer's) or from the
     * caller's own direct write. Default false: every failure is an [Outcome.Failed], never thrown.
     * The audit log uses it to keep the contract its INSERT had before batching: a store failure is
     * logged and swallowed, anything else fails the request.
     */
    fun propagates(failure: Throwable): Boolean = false
}

/** What happened to one recorded item. */
sealed interface Outcome {
    /** Durably written — by a batch, by its singles retry, or by the caller's own direct write. */
    data object Committed : Outcome

    /** Definitively NOT written: the store refused it, the store was down, or the write was abandoned before it started. */
    data class Failed(
        val kind: String,
        val cause: Throwable?,
    ) : Outcome

    /**
     * The bounded suspending record stopped waiting while a write of the item was still running —
     * it may yet land. Only [BatchingWriter.recordSuspending] reports this; the blocking record
     * never returns before an outcome is known.
     */
    data object Indeterminate : Outcome
}

/** Why a caller wrote its own item instead of the writer. */
enum class FallbackReason(
    val tag: String,
) {
    /** The item waited [BatchingConfig.recordMaxWaitMillis] in the queue; its caller claimed it back. */
    TIMEOUT("timeout"),

    /** The queue stayed full for [BatchingConfig.recordMaxWaitMillis]; the item never entered it. */
    SATURATED("saturated"),

    /** The writer has stopped (shutdown); nothing new is queued. */
    STOPPED("stopped"),
}

/** The failure-kind tags the writer itself reports through [BatchingHooks.onFailure]. */
object FailureKinds {
    const val POISON = "poison"
    const val WRITE_FAILED = "write_failed"
    const val INDETERMINATE = "indeterminate"
    const val ABANDONED = "abandoned"
    const val DRAIN_LOST = "drain_lost"
}

/**
 * What a writer tells its metrics. Every method has a no-op default, so a binding overrides only
 * what it records; the web layer binds these to Micrometer (observability §4). Called on writer
 * and caller threads — implementations must be thread-safe and must not throw.
 */
interface BatchingHooks {
    /** One batch committed: its size, its bytes, and how long the store took. */
    fun onBatch(
        size: Int,
        bytes: Long,
        durationNanos: Long,
    ) {}

    /** A batch of [size] threw and is being retried one item at a time. */
    fun onBatchRetried(size: Int) {}

    /** One item committed, [lagNanos] after it was queued. */
    fun onCommitted(lagNanos: Long) {}

    /** [count] items of [kind] ([FailureKinds], or a sink's own [BatchSink.classify] tag) were not written, or not known to be. */
    fun onFailure(
        kind: String,
        count: Int,
    ) {}

    /** One caller wrote its own item. */
    fun onFallback(reason: FallbackReason) {}

    /** One [BatchingWriter.submit] was refused — the only way an item is ever dropped without a caller waiting on it. */
    fun onDropped() {}

    companion object {
        val NONE: BatchingHooks = object : BatchingHooks {}
    }
}

/** What [BatchingWriter.stop] achieved: [lost] items never written, [inFlight] still inside a commit at the deadline (outcome unknown). */
data class DrainReport(
    val lost: Int,
    val inFlight: Int,
)
