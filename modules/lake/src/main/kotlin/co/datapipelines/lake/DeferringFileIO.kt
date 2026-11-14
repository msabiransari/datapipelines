package co.datapipelines.lake

import org.apache.iceberg.io.FileIO
import org.apache.iceberg.io.InputFile
import org.apache.iceberg.io.OutputFile
import org.slf4j.LoggerFactory

/**
 * The catalog's [FileIO]: reads and writes go to the delegate (`S3FileIO`); a DELETE the library asks for is
 * deferred, never executed (§8a "library deletes are deferred to the remover"; E1 #774's `GuardedFileIO`).
 *
 * Why: Iceberg deletes files on its own initiative after a failure it classifies as clean — and E1 F3 showed a
 * replayed commit misclassifying a COMMITTED write that way, deleting files the catalog pointer names. With
 * `write.metadata.delete-after-commit.enabled=false` and no reconnect-and-replay the library has no planned
 * delete left, so this wrapper is the second, independent control: no path inside Iceberg can delete an object
 * in the lake. Files the library would have deleted become orphans that the remover (P6) reclaims after the
 * orphan grace, against the revision log.
 *
 * Deliberately implements ONLY [FileIO]: the delegate's bulk and prefix operations (`SupportsBulkOperations`,
 * `SupportsPrefixOperations`) are hidden, so Iceberg's bulk-delete helpers fall back to [deleteFile] and are
 * deferred too.
 *
 * Every request records the key and throws: Iceberg cannot mistake a deferred request for a deletion.
 * The default callback logs the key and offers it to [DeferredDeleteHints] — the queue of a maintenance pass collecting on
 * this thread, if any — as a HINT only: the remover decides it after its own fresh read, by the same tests as a listed key
 * (brief 958 D4). An explicit callback replaces both.
 */
class DeferringFileIO(
    private val delegate: FileIO,
    private val onDeferredDelete: (String) -> Unit = ::deferDelete,
) : FileIO {
    override fun newInputFile(path: String): InputFile = delegate.newInputFile(path)

    override fun newInputFile(
        path: String,
        length: Long,
    ): InputFile = delegate.newInputFile(path, length)

    override fun newOutputFile(path: String): OutputFile = delegate.newOutputFile(path)

    /** Recorded through [onDeferredDelete], then refused; no callback can turn this into a successful deletion. */
    override fun deleteFile(path: String) {
        onDeferredDelete(path)
        throw LibraryDeleteRefusedException()
    }

    override fun properties(): Map<String, String> = delegate.properties()

    /** The delegate was initialised by the catalog's IO builder; a second initialise would rebuild its client. */
    override fun initialize(properties: Map<String, String>) = Unit

    override fun close() = delegate.close()

    private companion object {
        private val log = LoggerFactory.getLogger(DeferringFileIO::class.java)

        fun deferDelete(path: String) {
            logDeferredDelete(path)
            DeferredDeleteHints.offer(path)
        }

        /** An object key in DP's own bucket — not user data and not a secret; the remover's input when P6 lands. */
        fun logDeferredDelete(path: String) =
            log.warn(
                "event=lake.file.delete_refused path={} cause={} sql_state=none",
                path,
                LibraryDeleteRefusedException::class.java.simpleName,
            )
    }
}

private class LibraryDeleteRefusedException : IllegalStateException("Library file deletion is reserved for lake maintenance.")

/**
 * Where the keys Iceberg hands the refusing FileIO go besides the log line (brief 958 D4): while [collectInto] runs, every
 * deferred key requested ON THIS THREAD is added to its queue, which the caller then passes to the remover as hints.
 * A hint deletes nothing by itself. A key requested on another thread (a library worker pool) is only logged, and the
 * remover's own listing finds the file anyway.
 */
object DeferredDeleteHints {
    private val current = ThreadLocal<MutableCollection<String>?>()

    /** Runs [block] with [queue] collecting this thread's deferred keys; the previous collector is restored after it. */
    fun <T> collectInto(
        queue: MutableCollection<String>,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(queue)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    internal fun offer(path: String) {
        current.get()?.add(path)
    }
}
