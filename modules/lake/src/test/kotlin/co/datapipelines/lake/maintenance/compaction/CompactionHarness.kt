package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.definitions.LakeApplyOutcome
import co.datapipelines.lake.definitions.LakeDefinitionsHarness
import co.datapipelines.lake.maintenance.MaintenanceGate
import co.datapipelines.lake.maintenance.MaintenancePart
import co.datapipelines.lake.maintenance.MaintenanceRunHooks
import co.datapipelines.lake.maintenance.MaintenanceScratch
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.plan.PlanHarness
import co.datapipelines.lake.profiles.LakeProfileFixtures
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.WriteSurface
import com.fasterxml.jackson.databind.JsonNode
import org.apache.iceberg.BaseTable
import org.apache.iceberg.DataFile
import org.apache.iceberg.FileFormat
import org.apache.iceberg.PartitionKey
import org.apache.iceberg.Schema
import org.apache.iceberg.SortOrder
import org.apache.iceberg.SortOrderComparators
import org.apache.iceberg.StaticTableOperations
import org.apache.iceberg.Table
import org.apache.iceberg.data.GenericFileWriterFactory
import org.apache.iceberg.data.IcebergGenerics
import org.apache.iceberg.data.InternalRecordWrapper
import org.apache.iceberg.data.Record
import org.apache.iceberg.data.parquet.GenericParquetReaders
import org.apache.iceberg.io.OutputFileFactory
import org.apache.iceberg.parquet.Parquet
import org.apache.iceberg.parquet.ParquetValueReader
import org.apache.parquet.schema.MessageType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Function

/**
 * #969 — the compaction part over the module's one Postgres and MinIO (957's [co.datapipelines.lake.maintenance.MaintenanceHarness]
 * through 959's [PlanHarness]), with real managed tables created and released through the definitions service and
 * REAL Parquet files written into them by Iceberg's own writer (test-only commits under the table lease). The oracle is
 * Iceberg's reader ([rows]) and comparator, never DuckDB.
 *
 * Named without the `Test` suffix so `verifyTestsExecuted` counts only real test classes.
 */
internal class CompactionHarness {
    val plan = PlanHarness()
    val m get() = plan.m
    val reservations = CopyOnWriteArrayList<TestReservation>()
    val scratch = MaintenanceScratch { bytes -> TestReservation(bytes).also { reservations += it } }
    val clock = TestClock()
    private val fileCounter = AtomicLong()
    private val hashes = ConcurrentHashMap<UUID, String>()

    fun reset() {
        plan.reset()
        reservations.clear()
    }

    fun close() = plan.close()

    /** Profile `production` (every test table's) with [changes] over the built-in values. */
    fun profile(vararg changes: Pair<String, String?>) = plan.editProfile("production", LakeProfileFixtures.document(*changes))

    /** A managed table `maint.<name>` with definition [body], released and applied. */
    fun table(
        name: String,
        body: String,
    ): MaintenanceTable {
        val defs = m.defs
        val draft = defs.service.createDraft(NAMESPACE, name, body, defs.actor, WriteSurface.SESSION)
        val applied = defs.service.release(draft.tableId, 1, defs.actor, WriteSurface.SESSION).apply
        check(applied == LakeApplyOutcome.Applied(1, committed = true)) { "table $name not applied: $applied" }
        hashes[draft.tableId] = draft.bodyHash
        return requireNotNull(m.store.findTable(draft.tableId))
    }

    /** A COMPATIBLE release of [body] over [table]'s definition (an added column, a widening, an added partition field). */
    fun evolve(
        table: MaintenanceTable,
        body: String,
    ) {
        val defs = m.defs
        val draft = defs.service.writeDraft(table.id, body, hashes.getValue(table.id), defs.actor, WriteSurface.SESSION)
        val applied = defs.service.release(table.id, draft.version, defs.actor, WriteSurface.SESSION).apply
        check(applied == LakeApplyOutcome.Applied(draft.version, committed = true)) { "release of ${table.name} not applied: $applied" }
        hashes[table.id] = draft.bodyHash
    }

    /** 959's plan over every table: stores the measurement the part declares from (D4). */
    fun measure() = plan.pass(plan.planner())

    /** Writes each of [files] as ONE real Parquet file (rows given, stamp chosen) and appends them in ONE commit. */
    fun append(
        table: MaintenanceTable,
        vararg files: Fixture,
    ): List<DataFile> {
        val written = mutableListOf<DataFile>()
        plan.commit(table) { t ->
            files.forEach { written += write(t, it) }
            val append = t.newAppend()
            written.forEach(append::appendFile)
            append.commit()
        }
        return written
    }

    /** A test-only change committed under the table lease (a schema or spec evolution, a delete file). */
    fun change(
        table: MaintenanceTable,
        block: (Table) -> Unit,
    ) = plan.commit(table, block)

    /** One real Parquet data file of [fixture] for [t] (under [Fixture.spec] or the table's current spec). */
    fun write(
        t: Table,
        fixture: Fixture,
    ): DataFile {
        val schema = t.schema()
        val spec = fixture.specId?.let { t.specs().getValue(it) } ?: t.spec()
        val stamp = if (fixture.sorted) t.sortOrder() else SortOrder.unsorted()
        val partition =
            if (spec.isUnpartitioned) {
                null
            } else {
                PartitionKey(spec, schema).apply { partition(InternalRecordWrapper(schema.asStruct()).wrap(fixture.rows.first())) }
            }
        val factory =
            GenericFileWriterFactory
                .Builder(t)
                .dataFileFormat(FileFormat.PARQUET)
                .dataSchema(schema)
                .dataSortOrder(stamp)
                .build()
        val output =
            OutputFileFactory
                .builderFor(
                    t,
                    1,
                    fileCounter.incrementAndGet(),
                ).operationId("fixture")
                .format(FileFormat.PARQUET)
                .build()
        val target = if (partition == null) output.newOutputFile() else output.newOutputFile(spec, partition)
        val writer = factory.newDataWriter(target, spec, partition)
        writer.use { w -> fixture.rows.forEach(w::write) }
        return writer.toDataFile()
    }

    /** The table as a reader sees it now: a handle over the catalog row's metadata file, no lease. */
    fun load(table: MaintenanceTable): Table {
        val location =
            requireNotNull(
                LakeTestDb.jdbc.queryForObject(
                    "SELECT metadata_location FROM iceberg_tables WHERE table_namespace = ? AND table_name = ?",
                    String::class.java,
                    table.namespace,
                    table.name,
                ),
            )
        return BaseTable(StaticTableOperations(location, m.defs.catalogs.metadataFileIo()), table.leaseName)
    }

    /** Every row of [table] (at [snapshotId], or the current snapshot), read with Iceberg's reader. */
    fun rows(
        table: MaintenanceTable,
        snapshotId: Long? = null,
    ): List<Record> {
        val t = load(table)
        val scan = IcebergGenerics.read(t).let { if (snapshotId != null) it.useSnapshot(snapshotId) else it }
        return scan.build().use { it.toList() }
    }

    /** The data files of [table] at [snapshotId] (or the current snapshot). */
    fun files(
        table: MaintenanceTable,
        snapshotId: Long? = null,
    ): List<DataFile> {
        val t = load(table)
        val scan = t.newScan().let { if (snapshotId != null) it.useSnapshot(snapshotId) else it }
        return scan.planFiles().use { tasks -> tasks.map { it.file() } }
    }

    /** One data file's rows in file order, read with Iceberg's Parquet reader under the table's schema. */
    fun fileRows(
        t: Table,
        file: DataFile,
    ): List<Record> =
        Parquet
            .read(t.io().newInputFile(file.location()))
            .project(t.schema())
            .createReaderFunc(Function<MessageType, ParquetValueReader<*>> { GenericParquetReaders.buildReader(t.schema(), it) })
            .build<Record>()
            .use { records -> records.map { it.copy() }.toList() }

    /** How many adjacent pairs of [rows] break [t]'s sort order, by Iceberg's comparator. */
    fun outOfOrder(
        t: Table,
        rows: List<Record>,
    ): Int {
        val comparator = SortOrderComparators.forSchema(t.schema(), t.sortOrder())
        val left = InternalRecordWrapper(t.schema().asStruct())
        val right = InternalRecordWrapper(t.schema().asStruct())
        return rows.zipWithNext().count { (a, b) -> comparator.compare(left.wrap(a), right.wrap(b)) > 0 }
    }

    fun part(
        port: CompactionScratchPort = DuckDbTestScratch(LakeDefinitionsHarness.BUCKET),
        settings: CompactionSettings = SETTINGS,
        clock: Clock = this.clock,
        catalogNow: () -> Instant = m::dbNow,
    ): CompactionPart =
        CompactionPart(
            tables = CompactionTables(m.named, m.defs.properties.name),
            measurements = plan.repository::measurement,
            io = m.defs.catalogs::metadataFileIo,
            scratch = port,
            settings = settings,
            catalogNow = catalogNow,
            clock = clock,
        )

    /**
     * One maintenance run of [table] through 957's worker with [parts]; answers the run row's `parts_json` entries by
     * part name.
     */
    fun run(
        table: MaintenanceTable,
        parts: List<MaintenancePart>,
        gate: MaintenanceGate = MaintenanceGate.ADMIT_ALL,
        hooks: MaintenanceRunHooks = MaintenanceRunHooks.NONE,
        jobTimeout: Duration = SETTINGS.jobTimeout,
    ): Map<String, JsonNode> {
        failures.clear()
        m
            .worker(
                parts = parts.map(::Witnessed),
                gate = gate,
                scratch = scratch,
                hooks = hooks,
                jobTimeout = jobTimeout,
            ).run(table.id.toString())
        return lastParts(table)
    }

    /** What each part threw in the last [run] (the run row keeps only the class name): a test reads the message. */
    val failures = CopyOnWriteArrayList<Throwable>()

    /** A part as given, whose throw is kept in [failures] before it leaves unchanged. */
    private inner class Witnessed(
        private val part: MaintenancePart,
    ) : MaintenancePart by part {
        override fun run(run: co.datapipelines.lake.maintenance.MaintenanceRun): co.datapipelines.lake.maintenance.PartResult =
            try {
                part.run(run)
            } catch (failure: Exception) {
                failures += failure
                throw failure
            }
    }

    fun lastParts(table: MaintenanceTable): Map<String, JsonNode> {
        val row = m.rows(table).last()
        return PipelineJson.objectMapper().readTree(row.partsJson).associateBy { it["name"].asText() }
    }

    fun lastRun(table: MaintenanceTable) = m.rows(table).last()

    /** A test clock the part's deadline is read on; [advance] moves it. */
    class TestClock(
        @Volatile private var now: Instant = Instant.parse("2026-11-14T03:00:00Z"),
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now

        fun advance(by: Duration) {
            now = now.plus(by)
        }

        fun set(to: Instant) {
            now = to
        }
    }

    /** One fixture file: its rows (one partition), whether it is stamped with the table's order, and its spec. */
    class Fixture(
        val rows: List<Record>,
        val sorted: Boolean,
        val specId: Int? = null,
    )

    companion object {
        const val NAMESPACE = "maint"
        const val MIB = 1024L * 1024L

        /** Ample time and scratch: a run writes 1 GiB/s for an hour, the factor is 3, the ceiling 64 GiB. */
        val SETTINGS = CompactionSettings(3, 1024 * MIB, Duration.ofHours(1), 64 * 1024 * MIB)

        /** A record's values in the bit-exact form C1 compares: raw bits of floats, bytes as hex, decimals with scale. */
        fun canon(record: Record): String = (0 until record.size()).joinToString("|") { canon(record.get(it)) }

        private fun canon(value: Any?): String =
            when (value) {
                null -> "null"
                is Double -> "d:" + java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(value))
                is Float -> "f:" + Integer.toHexString(java.lang.Float.floatToRawIntBits(value))
                is ByteBuffer -> "b:" + HexFormat.of().formatHex(ByteArray(value.remaining()).also { value.duplicate().get(it) })
                is BigDecimal -> "n:" + value.toPlainString()
                else -> value.javaClass.simpleName + ":" + value
            }

        /** The multiset of [rows] as sorted canonical strings. */
        fun multiset(rows: List<Record>): List<String> = rows.map(::canon).sorted()

        /** [count] bytes of seeded noise: a fixture file's incompressible padding, so its size is what the test needs. */
        fun noise(
            random: Random,
            count: Int,
        ): ByteBuffer = ByteBuffer.wrap(ByteArray(count).also(random::nextBytes))

        /** A cursor that hides the first row of [rows] (C9's reader that drops one row). */
        fun dropFirstRow(rows: ResultSet): ResultSet {
            var skipped = false
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java)) { _, method, args ->
                if (method.name == "next" && !skipped) {
                    skipped = true
                    rows.next()
                }
                try {
                    method.invoke(rows, *(args ?: emptyArray()))
                } catch (
                    @Suppress("SwallowedException") e: InvocationTargetException, // the cursor's own throw is its cause
                ) {
                    throw requireNotNull(e.cause)
                }
            } as ResultSet
        }

        /** Schema helper for tests that build records by hand. */
        fun record(
            schema: Schema,
            vararg values: Any?,
        ): Record =
            org.apache.iceberg.data.GenericRecord
                .create(schema)
                .also { r -> values.forEachIndexed(r::set) }
    }
}

/**
 * C5's view of a table's FIRST sort column alone: files written from one ordered stream hold first-column ranges that
 * are disjoint but for one shared boundary value.
 */
internal class SortOrderFirstColumn(
    t: Table,
) {
    private val comparator: Comparator<org.apache.iceberg.StructLike>
    private val left = InternalRecordWrapper(t.schema().asStruct())
    private val right = InternalRecordWrapper(t.schema().asStruct())

    init {
        val first = t.sortOrder().fields().first()
        val name = t.schema().findColumnName(first.sourceId())
        val builder = SortOrder.builderFor(t.schema())
        val one =
            if (first.direction() == org.apache.iceberg.SortDirection.ASC) {
                builder.asc(name, first.nullOrder())
            } else {
                builder.desc(name, first.nullOrder())
            }
        comparator = SortOrderComparators.forSchema(t.schema(), one.build())
    }

    private fun compare(
        a: Record,
        b: Record,
    ) = comparator.compare(left.wrap(a), right.wrap(b))

    /** Over files each in order: how many neighbours (by their first row) overlap beyond one shared boundary value. */
    fun overlapsInside(files: List<List<Record>>): Int {
        val ordered = files.filter { it.isNotEmpty() }.sortedWith { a, b -> compare(a.first(), b.first()) }
        return ordered.zipWithNext().count { (a, b) -> compare(a.last(), b.first()) > 0 }
    }
}
