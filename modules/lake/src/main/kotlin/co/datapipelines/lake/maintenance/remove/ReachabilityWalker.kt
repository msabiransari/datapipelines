package co.datapipelines.lake.maintenance.remove

import org.apache.iceberg.ManifestContent
import org.apache.iceberg.ManifestFile
import org.apache.iceberg.ManifestFiles
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.TableMetadata
import org.apache.iceberg.TableMetadataParser
import org.apache.iceberg.io.FileIO

/** How the walker reached a file (design record §9). Taken from the path through the metadata, never from a file name. */
enum class FileKind(
    val wire: String,
) {
    METADATA("metadata"),
    MANIFEST_LIST("manifest_list"),
    MANIFEST("manifest"),
    DATA("data"),
    DELETE("delete"),
    STATISTICS("statistics"),
    PARTITION_STATISTICS("partition_statistics"),
}

/**
 * One reach: [location] (as the metadata names it) reached as [kind] from the protected [revision] (its metadata file),
 * through [snapshotId] where there is one. A metadata file and a metadata-log entry have no snapshot; a statistics file
 * carries the snapshot it is declared for. A file shared by several snapshots or revisions has one visit for each.
 */
data class ReachVisit(
    val location: String,
    val kind: FileKind,
    val revision: String,
    val snapshotId: Long?,
)

/** A metadata file or a manifest the walker could not read: the pass that asked fails and deletes nothing (D3). */
class ReachabilityReadException(
    val revision: String,
    cause: Throwable,
) : IllegalStateException("a protected revision's metadata could not be walked (${cause.javaClass.simpleName})", cause)

/**
 * §9 "Reachable, precisely": from each protected revision's metadata file the walker reaches the file itself, the DIRECT
 * entries of its `metadata-log` (one level, never recursive — call 32), and for every snapshot that file lists: its
 * manifest list, every manifest the list names, every LIVE entry's data or delete file (`ADDED`/`EXISTING`: Iceberg's
 * [org.apache.iceberg.ManifestReader] iterates `liveEntries()`, so a `DELETED` tombstone is never handed out), plus the
 * statistics and partition-statistics files the metadata file lists.
 *
 * Metadata is read as JSON through Iceberg's own parser, manifests through Iceberg's readers, with [io] (a read-only
 * use of the lake's deferring FileIO). Nothing is kept between calls: each [walk] reads every file afresh, so a pass
 * never reuses a walk made before it (the record's §8a (ii)); inside one call a manifest list or manifest shared by
 * several snapshots is read once.
 */
class ReachabilityWalker(
    private val io: FileIO,
) {
    /**
     * Walks every revision in [revisions] (metadata file locations) and hands every reach to [visitor], in order.
     * Answers each revision's declared table location (`location` in its metadata), for the caller's location check.
     * Throws [ReachabilityReadException] when any metadata file, manifest list or manifest cannot be read.
     */
    fun walk(
        revisions: Collection<String>,
        visitor: (ReachVisit) -> Unit,
    ): Map<String, String> {
        val manifestLists = HashMap<String, List<ManifestFile>>()
        val manifests = HashMap<String, List<Pair<String, FileKind>>>()
        return revisions.distinct().associateWith { revision ->
            try {
                walkRevision(revision, visitor, manifestLists, manifests)
            } catch (
                // Iceberg's readers throw several unchecked types (NotFoundException, RuntimeIOException, parse errors);
                // every one of them means the protected graph is unknown, so the pass stops with nothing deleted.
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                throw ReachabilityReadException(revision, e)
            }
        }
    }

    private fun walkRevision(
        revision: String,
        visitor: (ReachVisit) -> Unit,
        manifestLists: MutableMap<String, List<ManifestFile>>,
        manifests: MutableMap<String, List<Pair<String, FileKind>>>,
    ): String {
        val metadata: TableMetadata = TableMetadataParser.read(io, revision)
        visitor(ReachVisit(revision, FileKind.METADATA, revision, null))
        metadata.previousFiles().forEach { visitor(ReachVisit(it.file(), FileKind.METADATA, revision, null)) }
        val specs = metadata.specsById()
        metadata.snapshots().forEach { snapshot ->
            val id = snapshot.snapshotId()
            val list = snapshot.manifestListLocation()
            val files =
                if (list == null) {
                    // A v1 snapshot may embed its manifests instead of a list; its manifests are still reached below.
                    snapshot.allManifests(io)
                } else {
                    visitor(ReachVisit(list, FileKind.MANIFEST_LIST, revision, id))
                    manifestLists.getOrPut(list) { snapshot.allManifests(io) }
                }
            files.forEach { manifest ->
                visitor(ReachVisit(manifest.path(), FileKind.MANIFEST, revision, id))
                manifests
                    .getOrPut(manifest.path()) { liveFiles(manifest, specs) }
                    .forEach { (location, kind) -> visitor(ReachVisit(location, kind, revision, id)) }
            }
        }
        metadata.statisticsFiles().forEach { visitor(ReachVisit(it.path(), FileKind.STATISTICS, revision, it.snapshotId())) }
        metadata.partitionStatisticsFiles().forEach {
            visitor(ReachVisit(it.path(), FileKind.PARTITION_STATISTICS, revision, it.snapshotId()))
        }
        return metadata.location()
    }

    /** The manifest's live entries: iterating a reader yields `ADDED` and `EXISTING` files only. */
    private fun liveFiles(
        manifest: ManifestFile,
        specs: Map<Int, PartitionSpec>,
    ): List<Pair<String, FileKind>> =
        when (manifest.content()) {
            ManifestContent.DATA -> {
                ManifestFiles.read(manifest, io, specs).use { reader -> reader.map { it.location() to FileKind.DATA } }
            }

            ManifestContent.DELETES -> {
                ManifestFiles.readDeleteManifest(manifest, io, specs).use { reader -> reader.map { it.location() to FileKind.DELETE } }
            }

            null -> {
                error("manifest ${manifest.path()} declares no content")
            }
        }
}
