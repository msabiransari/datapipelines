package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.maintenance.MaintenanceHarness
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.profiles.LakeProfilesHarness
import co.datapipelines.scheduler.KeyedSystemJob
import co.datapipelines.scheduler.SystemJobOutcome
import co.datapipelines.scheduler.SystemJobQueue
import org.apache.iceberg.DataFile
import org.apache.iceberg.DataFiles
import org.apache.iceberg.DeleteFile
import org.apache.iceberg.FileFormat
import org.apache.iceberg.FileMetadata
import org.apache.iceberg.SortOrder
import org.apache.iceberg.Table
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #959 — the planner wired as `LakeMaintenanceAutoConfiguration` wires it, over the module's one Postgres and MinIO,
 * with real managed tables (957's [MaintenanceHarness]) whose snapshots hold FABRICATED file entries: the measurer opens
 * manifests only, so a data file's bytes never need to exist. Readers build [StallingFileIo] over the catalog's
 * `metadataFileIo()`, steered by [control]; the queue is [RecordingQueue] unless a test passes the scheduler's.
 *
 * Named without the `Test` suffix so `verifyTestsExecuted` counts only real test classes.
 */
internal class PlanHarness {
    val m = MaintenanceHarness()
    private val profiles = LakeProfilesHarness()
    val named = m.named
    val repository = PlanRepository(named)
    val figures = PlanFiguresReader(named)
    val control = StallControl()
    val queue = RecordingQueue()

    /** A thread name unique to this harness: live reader threads are counted by it, never confused with another test's. */
    val threadName = "plan-test-reader-${UUID.randomUUID()}"

    fun reset() {
        profiles.reset()
        m.reset()
        LakeTestDb.jdbc.execute("TRUNCATE lake_maintenance_plan")
    }

    fun close() {
        control.releaseAll()
        m.close()
    }

    fun readers(name: String = threadName): PlanReaders =
        PlanReaders(ioFactory = { StallingFileIo(m.defs.catalogs.metadataFileIo(), control) }, threadName = name)

    @Suppress("LongParameterList")
    fun planner(
        pageSize: Int = 200,
        tableTimeout: Duration = Duration.ofSeconds(30),
        maxAbandonedReads: Int = 4,
        readers: PlanReaders = readers(),
        registered: Set<String> = emptySet(),
        queue: SystemJobQueue = this.queue,
        worker: KeyedSystemJob = WORKER,
        clock: Clock = Clock.systemUTC(),
        hooks: PlanRaceHooks = PlanRaceHooks.NONE,
    ): MaintenancePlanner =
        MaintenancePlanner(
            bounds = PlanBounds(Duration.ofMinutes(15), pageSize, tableTimeout, maxAbandonedReads),
            repository = repository,
            measurer = TableMeasurer(named, m.defs.properties.name),
            readers = readers,
            due = MaintenanceDue(GRACE),
            queue = { queue },
            worker = worker,
            registered = { registered },
            clock = clock,
            hooks = hooks,
        )

    /** Runs one plan on its own thread under [bound]: a pass that never ends is a failure, never a hung suite. */
    fun pass(
        planner: MaintenancePlanner,
        bound: Duration = Duration.ofSeconds(PASS_BOUND_SECONDS),
    ) {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "plan-test-pass").apply { isDaemon = true } }
        try {
            executor.submit(planner::run).get(bound.toMillis(), TimeUnit.MILLISECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    fun table(name: String): MaintenanceTable = m.table(name)

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

    /** Appends one data file entry per [files] item in ONE commit (one manifest). */
    fun append(
        table: MaintenanceTable,
        vararg files: FileSpec,
    ) = commit(table) { t ->
        val append = t.newAppend()
        files.forEach { append.appendFile(dataFile(t, it)) }
        append.commit()
    }

    /** Adds one position-delete file entry of [bytes] in [day]'s partition. */
    fun delete(
        table: MaintenanceTable,
        day: String,
        bytes: Long,
    ) = commit(table) { t -> t.newRowDelta().addDeletes(deleteFile(t, day, bytes)).commit() }

    fun profileId(name: String): UUID =
        requireNotNull(LakeTestDb.jdbc.queryForObject("SELECT id FROM lake_profiles WHERE name = ?", UUID::class.java, name))

    fun profileUpdatedAt(name: String): Instant =
        requireNotNull(
            LakeTestDb.jdbc.queryForObject("SELECT updated_at FROM lake_profiles WHERE name = ?", Timestamp::class.java, name),
        ).toInstant()

    /** An edit of profile [name]'s values, as `LakeProfiles.edit` leaves the row: new values, a new `updated_at`. */
    fun editProfile(
        name: String,
        values: String,
    ) {
        val updated =
            LakeTestDb.jdbc.update(
                "UPDATE lake_profiles SET values_json = CAST(? AS jsonb), updated_at = clock_timestamp() WHERE name = ?",
                values,
                name,
            )
        check(updated == 1) { "profile $name missing" }
    }

    /** A reassignment of [table] to profile [name], as `LakeProfiles.assign` leaves the row. */
    fun assign(
        table: MaintenanceTable,
        name: String,
    ) {
        LakeTestDb.jdbc.update(
            "UPDATE lake_tables_managed SET profile_id = (SELECT id FROM lake_profiles WHERE name = ?), " +
                "profile_assigned_at = clock_timestamp() WHERE id = ?",
            name,
            table.id,
        )
    }

    fun decide(
        registered: Set<String> = PlanFixtures.ALL_PARTS,
        now: Instant = repository.catalogNow(),
    ): Map<UUID, TableDue> = MaintenanceDue(GRACE).plan(repository.decisionInputs(), registered, now).tables.associateBy { it.tableId }

    fun measurement(table: MaintenanceTable): StoredMeasurement? = repository.measurement(table.id)

    /** Live threads of this harness's readers. */
    fun liveReaderThreads(name: String = threadName): Int = Thread.getAllStackTraces().keys.count { it.name.startsWith(name) && it.isAlive }

    /** Polls until [condition] holds, at most [WAIT_SECONDS]. */
    fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        val end = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos()
        while (!condition()) {
            check(System.nanoTime() < end) { "timed out waiting for: $what" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun dataFile(
        t: Table,
        spec: FileSpec,
    ): DataFile =
        DataFiles
            .builder(t.spec())
            .withPath("${t.location()}/data/${UUID.randomUUID()}.parquet")
            .withFormat(FileFormat.PARQUET)
            .withFileSizeInBytes(spec.bytes)
            .withRecordCount(spec.records)
            .withPartitionPath("${t.spec().fields().single().name()}=${spec.day}")
            .withSortOrder(if (spec.sorted) t.sortOrder() else SortOrder.unsorted())
            .build()

    private fun deleteFile(
        t: Table,
        day: String,
        bytes: Long,
    ): DeleteFile =
        FileMetadata
            .deleteFileBuilder(t.spec())
            .ofPositionDeletes()
            .withPath("${t.location()}/data/${UUID.randomUUID()}-deletes.parquet")
            .withFormat(FileFormat.PARQUET)
            .withFileSizeInBytes(bytes)
            .withRecordCount(1)
            .withPartitionPath("${t.spec().fields().single().name()}=$day")
            .build()

    /** One fabricated data file entry: its partition day, size, record count and whether it carries the table's sort order. */
    data class FileSpec(
        val day: String,
        val bytes: Long,
        val records: Long = RECORDS,
        val sorted: Boolean = true,
    )

    companion object {
        val GRACE: Duration = Duration.ofHours(72)
        const val RECORDS = 10L
        const val PASS_BOUND_SECONDS = 60L
        const val WAIT_SECONDS = 20L
        const val POLL_MILLIS = 20L

        /** A stand-in for the worker's task identity: the planner only names it to the queue. */
        val WORKER =
            object : KeyedSystemJob {
                override val name = "lake-maintenance"
                override val instances = "one instance per table"

                override fun run(key: String) = SystemJobOutcome.Done
            }
    }
}

/**
 * A recording [SystemJobQueue]: the queued instances and their due times; a key in [running] is picked (move refuses
 * it, enqueue finds it present).
 */
internal class RecordingQueue : SystemJobQueue {
    val queued = ConcurrentHashMap<String, Instant>()
    val running: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val enqueues = mutableListOf<String>()

    override fun enqueueIfAbsent(
        job: KeyedSystemJob,
        key: String,
        at: Instant,
    ): Boolean {
        if (key in running || queued.containsKey(key)) return false
        queued[key] = at
        enqueues += key
        return true
    }

    override fun move(
        job: KeyedSystemJob,
        key: String,
        at: Instant,
    ): Boolean {
        if (key in running || !queued.containsKey(key)) return false
        queued[key] = at
        return true
    }

    /** The queued keys in the order a scheduler would hand them out: by due time. */
    fun order(): List<String> = queued.entries.sortedBy { it.value }.map { it.key }
}
