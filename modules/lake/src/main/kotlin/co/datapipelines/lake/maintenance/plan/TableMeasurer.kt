package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.maintenance.MaintenanceErrors
import co.datapipelines.lake.profiles.LakeProfile
import co.datapipelines.lake.settle.LakeMetadataReads
import com.fasterxml.jackson.databind.JsonNode
import org.apache.iceberg.ManifestFiles
import org.apache.iceberg.TableMetadata
import org.apache.iceberg.TableMetadataParser
import org.apache.iceberg.io.FileIO
import org.apache.iceberg.util.JsonUtil
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.GZIPInputStream

/** The table a measurement reads: its id and name, from the catalog's rows (never from a request). */
data class PlanTarget(
    val tableId: UUID,
    val namespace: String,
    val name: String,
)

/**
 * Measures one table from its current snapshot's manifests (brief 959 D2, D3): the catalog row's metadata location
 * through a bounded statement, the metadata file (its inflated JSON capped at [LakeMetadataReads.METADATA_MAX_BYTES]),
 * then every data and delete manifest of the current snapshot through the [FileIO] it is handed. No data file is
 * opened, no lease and no maintenance slot is taken, and nothing is written: the reader's FileIO is
 * `LakeCatalogs.metadataFileIo()`, which refuses deletes.
 *
 * A table with no catalog row or no current snapshot is measured with every figure null ([TableFigures.NO_SNAPSHOT]).
 * The profile-dependent figures are computed under the [profile] copy the caller read once, before the manifests (D3a).
 */
class TableMeasurer(
    private val jdbc: NamedParameterJdbcTemplate,
    private val catalogName: String,
) {
    fun measure(
        io: FileIO,
        target: PlanTarget,
        profile: LakeProfile,
    ): TableFigures {
        val location = metadataLocation(target) ?: return TableFigures.NO_SNAPSHOT
        val metadata = readMetadata(io, location)
        val snapshot = metadata.currentSnapshot() ?: return TableFigures.NO_SNAPSHOT
        val specs = metadata.specsById()
        val sortOrder = metadata.sortOrder().takeIf { !it.isUnsorted }?.orderId()
        val count = TableFigureCount(profile, sortOrder)
        snapshot.dataManifests(io).forEach { manifest ->
            ManifestFiles.read(manifest, io, specs).use { files ->
                files.forEach { file ->
                    val spec = specs.getValue(file.specId())
                    count.addData(
                        DataFileFact(
                            "${file.specId()}/${spec.partitionToPath(file.partition())}",
                            file.fileSizeInBytes(),
                            file.recordCount(),
                            file.sortOrderId(),
                        ),
                    )
                }
            }
        }
        snapshot.deleteManifests(io).forEach { manifest ->
            ManifestFiles.readDeleteManifest(manifest, io, specs).use { files -> files.forEach { count.addDelete(it.fileSizeInBytes()) } }
        }
        return count.result(snapshot.snapshotId(), snapshot.allManifests(io).size.toLong())
    }

    private fun metadataLocation(target: PlanTarget): String? =
        jdbc
            .queryForList(
                "SELECT metadata_location FROM iceberg_tables WHERE catalog_name = :catalog AND table_namespace = :namespace " +
                    "AND table_name = :name",
                mapOf("catalog" to catalogName, "namespace" to target.namespace, "name" to target.name),
                String::class.java,
            ).singleOrNull()

    /** `TableMetadataParser.read(io, location)` with the inflated bytes capped; the stream is closed on this thread. */
    private fun readMetadata(
        io: FileIO,
        location: String,
    ): TableMetadata {
        val raw = io.newInputFile(location).newStream()
        val node =
            raw.use {
                val inflated =
                    if (TableMetadataParser.Codec.fromFileName(location) == TableMetadataParser.Codec.GZIP) GZIPInputStream(raw) else raw
                CappedStream(inflated, LakeMetadataReads.METADATA_MAX_BYTES).use { capped ->
                    JsonUtil.mapper().readValue(capped, JsonNode::class.java)
                }
            }
        return TableMetadataParser.fromJson(location, node)
    }

    /** Counts the bytes passed on and refuses more than [cap]: a metadata file is never read past it. */
    private class CappedStream(
        delegate: InputStream,
        private val cap: Long,
    ) : FilterInputStream(delegate) {
        private var passed = 0L

        override fun read(): Int = `in`.read().also { if (it >= 0) count(1) }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = `in`.read(b, off, len).also { if (it > 0) count(it.toLong()) }

        private fun count(bytes: Long) {
            passed += bytes
            if (passed > cap) throw MetadataTooLarge()
        }
    }
}

/** The metadata file inflates past the cap; the attempt is stamped with [PlanErrors.METADATA_TOO_LARGE]. */
class MetadataTooLarge : RuntimeException("table metadata exceeds the read cap")

/** The error codes a measurement attempt stamps (lake.md "Maintenance runs"); never a message. */
object PlanErrors {
    /** The measurement did not end within `plan-table-timeout`: the read is abandoned. */
    const val TIMEOUT = "timeout"

    /** The metadata file inflates past the cap. */
    const val METADATA_TOO_LARGE = "metadata_too_large"

    /** Any other failure: its catalogued code, or `unclassified`. */
    fun codeOf(failure: Throwable): String =
        when (failure) {
            is MetadataTooLarge -> METADATA_TOO_LARGE
            else -> MaintenanceErrors.codeOf(failure)
        }
}
