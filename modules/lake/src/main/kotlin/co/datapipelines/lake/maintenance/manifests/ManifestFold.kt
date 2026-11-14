package co.datapipelines.lake.maintenance.manifests

import org.apache.iceberg.ManifestContent
import org.apache.iceberg.ManifestFile
import org.apache.iceberg.Table
import org.apache.iceberg.TableMetadata
import org.apache.iceberg.TableProperties
import org.apache.iceberg.util.PropertyUtil

/** One entry of a snapshot's manifest list, as the list itself describes it (no manifest is opened). */
data class ManifestFact(
    val path: String,
    val length: Long,
    val specId: Int,
    /** True for a data manifest; a delete manifest is never a candidate. */
    val data: Boolean,
) {
    companion object {
        fun of(manifest: ManifestFile): ManifestFact =
            ManifestFact(manifest.path(), manifest.length(), manifest.partitionSpecId(), manifest.content() == ManifestContent.DATA)
    }
}

/**
 * What a manifest rewrite would fold (brief 963 D2a): [paths] are the candidates of the groups that fold, [groups] how
 * many of them each partition spec id holds. Empty → nothing to fold.
 */
data class FoldPlan(
    val paths: Set<String>,
    val groups: Map<Int, Int>,
) {
    val folds: Boolean get() = paths.isNotEmpty()

    /** The summed list length of the manifests the rewrite replaces. */
    fun bytes(manifests: List<ManifestFact>): Long = manifests.filter { it.path in paths }.sumOf { it.length }
}

/**
 * Brief 963 D2a, "fold only what folds": a PURE function of the current snapshot's manifest list and the table's
 * manifest target size. It decides progress, never whether the part is due (D2, the record's count rule).
 *
 * - a **candidate** is a DATA manifest whose length is under HALF the target: small manifests usually come out as
 *   fewer, and a manifest the writer rolled at the target is not a candidate again;
 * - candidates are grouped by partition spec id (core keeps specs apart: one writer per key and spec);
 * - a group **folds** when it holds at least [MIN_GROUP] candidates; a lone candidate stays as it is.
 *
 * A heuristic, not a proof: core writes each new manifest afresh with the current schema in its header and rolls on the
 * new writer's length, so after a schema change a fold can come out with as many manifests, or more (D3 records it).
 */
object ManifestFold {
    /** Two manifests are the fewest that can fold into one: a grammar fact of "folding", not a tuning value. */
    private const val MIN_GROUP = 2

    fun plan(
        manifests: List<ManifestFact>,
        targetBytes: Long,
    ): FoldPlan {
        require(targetBytes > 0) { "a manifest target size is positive: $targetBytes" }
        val folding =
            manifests
                .filter { it.data && it.length < targetBytes / 2 }
                .groupBy { it.specId }
                .filterValues { it.size >= MIN_GROUP }
        return FoldPlan(
            folding.values
                .flatten()
                .map { it.path }
                .toSet(),
            folding.mapValues { it.value.size },
        )
    }

    /**
     * The manifest target size core rolls a rewritten manifest at: the table's `commit.manifest.target-size-bytes`, else
     * Iceberg's default. Read, never set: it is the library's own knob, not a profile value (brief 963 NOT list). Core's
     * `BaseRewriteManifests` reads the same property the same way, so the rule and the writer agree on the target.
     */
    fun targetBytes(table: Table): Long = targetBytes(table.properties())

    /** [targetBytes] of a table read from its metadata file (no catalog session). */
    fun targetBytes(metadata: TableMetadata): Long = targetBytes(metadata.properties())

    private fun targetBytes(properties: Map<String, String>): Long =
        PropertyUtil.propertyAsLong(
            properties,
            TableProperties.MANIFEST_TARGET_SIZE_BYTES,
            TableProperties.MANIFEST_TARGET_SIZE_BYTES_DEFAULT,
        )
}
