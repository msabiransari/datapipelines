package co.datapipelines.lake.maintenance.manifests

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.maintenance.MaintenanceGate
import co.datapipelines.lake.maintenance.MaintenanceHarness
import co.datapipelines.lake.maintenance.MaintenancePart
import co.datapipelines.lake.maintenance.MaintenanceRunHooks
import co.datapipelines.lake.maintenance.MaintenanceRunRow
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.profiles.LakeProfilesHarness
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.scheduler.SystemJobOutcome
import com.fasterxml.jackson.databind.JsonNode
import org.apache.iceberg.BaseTable
import org.apache.iceberg.DataFile
import org.apache.iceberg.DataFiles
import org.apache.iceberg.FileFormat
import org.apache.iceberg.FileMetadata
import org.apache.iceberg.ManifestFile
import org.apache.iceberg.ManifestFiles
import org.apache.iceberg.Metrics
import org.apache.iceberg.PartitionKey
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Snapshot
import org.apache.iceberg.SnapshotSummary
import org.apache.iceberg.StaticTableOperations
import org.apache.iceberg.Table
import org.apache.iceberg.TableProperties
import org.apache.iceberg.data.GenericRecord
import org.apache.iceberg.data.IcebergGenerics
import org.apache.iceberg.data.InternalRecordWrapper
import org.apache.iceberg.data.Record
import org.apache.iceberg.data.parquet.GenericParquetWriter
import org.apache.iceberg.parquet.Parquet
import org.apache.iceberg.types.Conversions
import org.apache.iceberg.types.Types
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * #963 — the part `manifest_rewrite` wired as the lake wires it ([ManifestRewritePart.over]), run by 957's worker over
 * the module's one Postgres and MinIO, on real managed tables ([MaintenanceHarness]). Tables are shaped through the
 * catalog session under their writer lease, as an append would: fabricated data and delete entries (a manifest rewrite
 * never opens a data file) or real Parquet rows where history must be read back.
 *
 * Named without the `Test` suffix so `verifyTestsExecuted` counts only real test classes.
 */
internal class ManifestRewriteHarness {
    val m = MaintenanceHarness()
    val profiles = LakeProfilesHarness()
    val part = ManifestRewritePart.over(m.named, m.defs.properties.name, m.defs.catalogs, profiles.profiles)
    val reader = ManifestListReader(m.named, m.defs.properties.name, m.defs.catalogs::metadataFileIo)
    private val json = PipelineJson.objectMapper()

    fun reset() {
        profiles.reset()
        m.reset()
    }

    fun close() = m.close()

    fun table(name: String = "orders"): MaintenanceTable = m.table(name)

    /** Sets `maintenance.rewrite_manifests_min_count` of [table]'s profile, as a profile edit leaves the row. */
    fun threshold(
        table: MaintenanceTable,
        count: Int,
    ) {
        val updated =
            LakeTestDb.jdbc.update(
                "UPDATE lake_profiles " +
                    "SET values_json = jsonb_set(values_json, '{maintenance,rewrite_manifests_min_count}', to_jsonb(?)), " +
                    "updated_at = clock_timestamp() WHERE id = (SELECT profile_id FROM lake_tables_managed WHERE id = ?)",
                count,
                table.id,
            )
        check(updated == 1) { "no profile for ${table.id}" }
    }

    /** Sets `maintenance.window` of [table]'s profile (`HH:MM-HH:MM`, UTC). */
    fun window(
        table: MaintenanceTable,
        window: String,
    ) {
        val updated =
            LakeTestDb.jdbc.update(
                "UPDATE lake_profiles SET values_json = jsonb_set(values_json, '{maintenance,window}', to_jsonb(?::text)), " +
                    "updated_at = clock_timestamp() WHERE id = (SELECT profile_id FROM lake_tables_managed WHERE id = ?)",
                window,
                table.id,
            )
        check(updated == 1) { "no profile for ${table.id}" }
    }

    /** Sets [table]'s lifecycle status row (`archived`, `deprecated`), as the lifecycle service leaves it. */
    fun status(
        table: MaintenanceTable,
        status: String,
    ): MaintenanceTable {
        check(LakeTestDb.jdbc.update("UPDATE lake_tables_managed SET status = ? WHERE id = ?", status, table.id) == 1)
        return requireNotNull(m.store.findTable(table.id))
    }

    /** Commits [block]'s change to [table] under its writer lease, through the catalog session (as an append does). */
    fun commit(
        table: MaintenanceTable,
        block: (Table) -> Unit,
    ) {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, table.leaseName)
        try {
            m.defs.catalogs.open(lease).use { session ->
                val loaded = session.loadTable(table.namespace, table.name)
                session.write { block(loaded) }
            }
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    /** ONE fast append (one new manifest) of a fabricated entry per day in [days], under the table's current spec. */
    fun entries(
        table: MaintenanceTable,
        vararg days: String,
    ) = commit(table) { t ->
        val append = t.newFastAppend()
        days.forEach { append.appendFile(fabricated(t, t.spec(), it)) }
        append.commit()
    }

    /** [count] fast appends of [perManifest] fabricated entries each, over [days] in turn: [count] new manifests. */
    fun manifests(
        table: MaintenanceTable,
        count: Int,
        perManifest: Int = 1,
        days: List<String> = listOf(DAY_1),
    ) = repeat(count) { i -> entries(table, *Array(perManifest) { j -> days[(i * perManifest + j) % days.size] }) }

    /**
     * [count] manifests of [perManifest] fabricated entries each, written as manifest files and appended in ONE fast
     * append commit (a fixture of thousands of files in seconds; the table keeps every manifest as written).
     */
    fun bulk(
        table: MaintenanceTable,
        count: Int,
        perManifest: Int,
        withMetrics: Boolean = false,
    ) = commit(table) { t ->
        val append = t.newFastAppend()
        var n = 0L
        repeat(count) { i ->
            val output = t.io().newOutputFile("${t.location()}/metadata/bulk-${UUID.randomUUID()}-m0.avro")
            val writer = ManifestFiles.write(FORMAT_VERSION, t.spec(), output, null)
            writer.use { w ->
                repeat(perManifest) { j ->
                    val day = DAYS[(i + j) % DAYS.size]
                    w.add(if (withMetrics) fabricated(t, t.spec(), day, metrics(t, n++)) else fabricated(t, t.spec(), day))
                }
            }
            append.appendManifest(writer.toManifestFile())
        }
        append.commit()
    }

    /**
     * Column metrics as an append's writer records them for one file: sizes, value and null counts for every column and
     * distinct lower and upper bounds for `id`, `customer` and `ordered_at` (a manifest entry of realistic size).
     */
    private fun metrics(
        t: Table,
        n: Long,
    ): Metrics {
        val schema = t.schema()
        val ids = schema.columns().map { it.fieldId() }
        val id = requireNotNull(schema.findField("id")).fieldId()
        val customer = requireNotNull(schema.findField("customer")).fieldId()
        val orderedAt = requireNotNull(schema.findField("ordered_at"))
        val lowMicros = n * MICROS_PER_FILE
        return Metrics(
            RECORDS,
            ids.associateWith { FILE_BYTES / ids.size },
            ids.associateWith { RECORDS },
            ids.associateWith { 0L },
            emptyMap(),
            mapOf(
                id to Conversions.toByteBuffer(Types.LongType.get(), n * RECORDS),
                customer to Conversions.toByteBuffer(Types.StringType.get(), "customer-${n * RECORDS}"),
                orderedAt.fieldId() to Conversions.toByteBuffer(orderedAt.type(), lowMicros),
            ),
            mapOf(
                id to Conversions.toByteBuffer(Types.LongType.get(), n * RECORDS + RECORDS - 1),
                customer to Conversions.toByteBuffer(Types.StringType.get(), "customer-${n * RECORDS + RECORDS - 1}"),
                orderedAt.fieldId() to Conversions.toByteBuffer(orderedAt.type(), lowMicros + MICROS_PER_FILE - 1),
            ),
        )
    }

    /** A fabricated position-delete entry in [day]'s partition (its own delete manifest). */
    fun deleteEntry(
        table: MaintenanceTable,
        day: String = DAY_1,
    ) = commit(table) { t ->
        val delete =
            FileMetadata
                .deleteFileBuilder(t.spec())
                .ofPositionDeletes()
                .withPath("${t.location()}/data/${UUID.randomUUID()}-deletes.parquet")
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(FILE_BYTES)
                .withRecordCount(1)
                .withPartitionPath(path(t.spec(), day))
                .build()
        t.newRowDelta().addDeletes(delete).commit()
    }

    /** ONE append of a real Parquet file holding rows [ids] dated [day] (one new manifest). */
    fun rows(
        table: MaintenanceTable,
        ids: LongRange,
        day: String = DAY_1,
    ) = commit(table) { t -> t.newFastAppend().appendFile(writeRows(t, ids, day)).commit() }

    /** Sets the table's `commit.manifest.target-size-bytes` (no snapshot: the manifests do not change). */
    fun target(
        table: MaintenanceTable,
        bytes: Long,
    ) = commit(table) { t -> t.updateProperties().set(TableProperties.MANIFEST_TARGET_SIZE_BYTES, bytes.toString()).commit() }

    /** Evolves the partition spec: identity on `customer` beside the day (a second spec id). */
    fun addCustomerPartition(table: MaintenanceTable) = commit(table) { t -> t.updateSpec().addField("customer").commit() }

    /** Grows the schema by [columns] optional columns each carrying [docChars] of documentation (a larger manifest header). */
    fun growSchema(
        table: MaintenanceTable,
        columns: Int,
        docChars: Int,
    ) = commit(table) { t ->
        val update = t.updateSchema()
        repeat(columns) { update.addColumn("note_$it", Types.StringType.get(), "d".repeat(docChars)) }
        update.commit()
    }

    /** Tags [table]'s current snapshot as [name] (a tag must survive a rewrite). */
    fun tag(
        table: MaintenanceTable,
        name: String,
    ) = commit(table) { t -> t.manageSnapshots().createTag(name, t.currentSnapshot().snapshotId()).commit() }

    /** The table as its current metadata file describes it, with no lease (a reader's view). */
    fun loaded(table: MaintenanceTable): Table = at(currentLocation(table))

    /** The table as the metadata file at [location] describes it (a reader pinned to that revision). */
    fun at(location: String): Table = BaseTable(StaticTableOperations(location, m.defs.catalogs.metadataFileIo()), location)

    fun currentLocation(table: MaintenanceTable): String =
        requireNotNull(
            LakeTestDb.jdbc.queryForObject(
                "SELECT metadata_location FROM iceberg_tables WHERE table_namespace = ? AND table_name = ?",
                String::class.java,
                table.namespace,
                table.name,
            ),
        )

    /** Every revision's metadata location, oldest first (the catalog's own record of the commits). */
    fun revisionLocations(table: MaintenanceTable): List<String> =
        LakeTestDb.jdbc.queryForList(
            "SELECT metadata_location FROM lake_table_revisions WHERE namespace = ? AND table_name = ? ORDER BY seq",
            String::class.java,
            table.namespace,
            table.name,
        )

    fun revisions(table: MaintenanceTable): Int = m.revisions(table)

    /** The current snapshot's manifests (data and delete), as its manifest list names them. */
    fun manifestList(table: MaintenanceTable): List<ManifestFile> = loaded(table).let { t -> t.currentSnapshot().allManifests(t.io()) }

    /** The live data files and delete files of [snapshot], by path: the full lists, never a count. */
    fun liveFiles(
        t: Table,
        snapshot: Snapshot = t.currentSnapshot(),
    ): Pair<Set<String>, Set<String>> {
        val data = mutableSetOf<String>()
        val deletes = mutableSetOf<String>()
        snapshot.dataManifests(t.io()).forEach { manifest ->
            ManifestFiles.read(manifest, t.io(), t.specs()).use { files -> files.forEach { data += it.location() } }
        }
        snapshot.deleteManifests(t.io()).forEach { manifest ->
            ManifestFiles.readDeleteManifest(manifest, t.io(), t.specs()).use { files -> files.forEach { deletes += it.location() } }
        }
        return data to deletes
    }

    /** The data file paths each data manifest of the current snapshot lists, with the manifest's spec id. */
    fun entriesByManifest(t: Table): List<Pair<Int, Set<String>>> =
        t.currentSnapshot().dataManifests(t.io()).map { manifest ->
            manifest.partitionSpecId() to
                ManifestFiles.read(manifest, t.io(), t.specs()).use { files -> files.map { it.location() }.toSet() }
        }

    /** The rows [t] returns at [snapshotId], each as its text, sorted. */
    fun rowsAt(
        t: Table,
        snapshotId: Long,
    ): List<String> =
        IcebergGenerics
            .read(t)
            .useSnapshot(snapshotId)
            .build()
            .use { rows -> rows.map { it.toString() }.sorted() }

    /** A snapshot's totals: the six `total-*` values of its summary. */
    fun totals(snapshot: Snapshot): Map<String, String?> = TOTALS.associateWith { snapshot.summary()[it] }

    fun exists(path: String): Boolean =
        m.defs.catalogs
            .metadataFileIo()
            .use { it.newInputFile(path).exists() }

    /** One run of the worker over [parts] (default: this part alone); answers the run row. */
    fun run(
        table: MaintenanceTable,
        parts: List<MaintenancePart> = listOf(part),
        gate: MaintenanceGate = MaintenanceGate.ADMIT_ALL,
        hooks: MaintenanceRunHooks = MaintenanceRunHooks.NONE,
        jobTimeout: Duration = Duration.ofHours(2),
    ): MaintenanceRunRow {
        val before = m.rows(table).map { it.id }.toSet()
        check(
            m.worker(parts = parts, gate = gate, hooks = hooks, jobTimeout = jobTimeout).run(table.id.toString()) == SystemJobOutcome.Done,
        )
        return m.rows(table).single { it.id !in before }
    }

    /** The `parts_json` entry of [name] in [row]. */
    fun entry(
        row: MaintenanceRunRow,
        name: String = part.name,
    ): JsonNode = json.readTree(row.partsJson).single { it["name"].asText() == name }

    private fun fabricated(
        t: Table,
        spec: PartitionSpec,
        day: String,
        metrics: Metrics? = null,
    ): DataFile =
        DataFiles
            .builder(spec)
            .withPath("${t.location()}/data/${UUID.randomUUID()}.parquet")
            .withFormat(FileFormat.PARQUET)
            .withFileSizeInBytes(FILE_BYTES)
            .withRecordCount(RECORDS)
            .withPartitionPath(path(spec, day))
            .apply { if (metrics != null) withMetrics(metrics) }
            .build()

    /** The partition path of [day] under [spec]: the day, and `customer=c` when the spec carries the customer. */
    private fun path(
        spec: PartitionSpec,
        day: String,
    ): String = spec.fields().joinToString("/") { field -> if (field.name() == "customer") "customer=c" else "${field.name()}=$day" }

    private fun writeRows(
        t: Table,
        ids: LongRange,
        day: String,
    ): DataFile {
        val schema = t.schema()
        val at = LocalDate.parse(day).atStartOfDay()
        val records =
            ids.map { id ->
                GenericRecord.create(schema).copy(
                    mapOf(
                        "id" to id,
                        "customer" to "c$id",
                        "amount" to BigDecimal.valueOf(id * 100 + 25, 2),
                        "ordered_at" to timestamp(schema.findType("ordered_at"), at),
                    ),
                )
            }
        val key = PartitionKey(t.spec(), schema)
        key.partition(InternalRecordWrapper(schema.asStruct()).wrap(records.first()))
        val writer =
            Parquet
                .writeData(t.io().newOutputFile("${t.location()}/data/${UUID.randomUUID()}.parquet"))
                .schema(schema)
                .withSpec(t.spec())
                .withPartition(key)
                .createWriterFunc(GenericParquetWriter::create)
                .build<Record>()
        writer.use { w -> records.forEach(w::write) }
        return writer.toDataFile()
    }

    private fun timestamp(
        type: org.apache.iceberg.types.Type,
        at: LocalDateTime,
    ): Any = if ((type as Types.TimestampType).shouldAdjustToUTC()) at.atOffset(ZoneOffset.UTC) else at

    companion object {
        const val DAY_1 = "2026-01-01"
        const val DAY_2 = "2026-01-02"
        const val DAY_3 = "2026-01-03"
        val DAYS = listOf(DAY_1, DAY_2, DAY_3)
        const val FILE_BYTES = 1_000L
        const val RECORDS = 10L
        const val FORMAT_VERSION = 2
        const val MICROS_PER_FILE = 3_600_000_000L

        val TOTALS =
            listOf(
                SnapshotSummary.TOTAL_RECORDS_PROP,
                SnapshotSummary.TOTAL_DATA_FILES_PROP,
                SnapshotSummary.TOTAL_DELETE_FILES_PROP,
                SnapshotSummary.TOTAL_FILE_SIZE_PROP,
                SnapshotSummary.TOTAL_POS_DELETES_PROP,
                SnapshotSummary.TOTAL_EQ_DELETES_PROP,
            )
    }
}
