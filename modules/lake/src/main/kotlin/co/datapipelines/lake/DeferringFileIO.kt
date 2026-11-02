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
 */
class DeferringFileIO(
    private val delegate: FileIO,
    private val onDeferredDelete: (String) -> Unit = ::logDeferredDelete,
) : FileIO {
    override fun newInputFile(path: String): InputFile = delegate.newInputFile(path)

    override fun newInputFile(
        path: String,
        length: Long,
    ): InputFile = delegate.newInputFile(path, length)

    override fun newOutputFile(path: String): OutputFile = delegate.newOutputFile(path)

    /** Deferred: recorded through [onDeferredDelete], never executed. */
    override fun deleteFile(path: String) = onDeferredDelete(path)

    override fun properties(): Map<String, String> = delegate.properties()

    /** The delegate was initialised by the catalog's IO builder; a second initialise would rebuild its client. */
    override fun initialize(properties: Map<String, String>) = Unit

    override fun close() = delegate.close()

    private companion object {
        private val log = LoggerFactory.getLogger(DeferringFileIO::class.java)

        /** An object key in DP's own bucket — not user data and not a secret; the remover's input when P6 lands. */
        fun logDeferredDelete(path: String) = log.warn("lake: deferred a library delete of {} to the remover", path)
    }
}
