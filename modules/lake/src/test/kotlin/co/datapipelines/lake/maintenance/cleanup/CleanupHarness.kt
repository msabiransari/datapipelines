package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.maintenance.plan.MaintenanceDue
import co.datapipelines.lake.maintenance.MaintenanceHarness
import co.datapipelines.lake.maintenance.MaintenancePart
import co.datapipelines.lake.maintenance.MaintenanceRun
import co.datapipelines.lake.maintenance.MaintenanceRunContext
import co.datapipelines.lake.maintenance.MaintenanceRunHooks
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.MaintenanceWorker
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames
import co.datapipelines.lake.maintenance.plan.PlanHarness
import co.datapipelines.lake.maintenance.plan.TableDue
import co.datapipelines.lake.maintenance.remove.CatalogClock
import co.datapipelines.lake.maintenance.remove.FaultingS3Client
import co.datapipelines.lake.maintenance.remove.ListedObject
import co.datapipelines.lake.maintenance.remove.RemovalTables
import co.datapipelines.lake.maintenance.remove.RemovalTarget
import co.datapipelines.lake.maintenance.remove.TestClock
import co.datapipelines.lake.profiles.LakeProfileFixtures
import co.datapipelines.lake.profiles.LakeProfiles
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.iceberg.DataFile
import org.apache.iceberg.DataFiles
import org.apache.iceberg.FileFormat
import org.apache.iceberg.HasTableOperations
import org.apache.iceberg.Table
import org.apache.iceberg.TableMetadata
import org.apache.iceberg.TableMetadataParser
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #962 — the two parts built by their production factories ([CleanupRemoval.of], [CleanupPart.of],
 * [PurgeDeletionPart.of]) over the module's one Postgres and MinIO, with real managed tables (957's [MaintenanceHarness]
 * through 959's [PlanHarness], so the planner's due rule is at hand), the production [MaintenanceRunStates], and:
 *
 * - [partClock] and [removerClock]: the catalog's clock wrapped (958 D7, the cut's L17), the first for the parts' "now"
 *   (the floor, the keep plan, due-ness), the second for the remover (its probe and its age arithmetic) — shifted only
 *   when a case says so;
 * - [store]: the REAL MinIO client behind 958's [FaultingS3Client] (a delete call answered or followed by a fault) and
 *   [AgingS3Client] (the listed `LastModified` of every key moved back by [AgingS3Client.age], never a `dp/clock/`
 *   key: the record's §14 E1 (o) admits this for the age arithmetic only, never for the probe);
 * - [restamp]: 960's technique (`KeepTagsExpiryTest`) on the real catalog: a commit that rewrites snapshot timestamps
 *   and snapshot-log entries in the metadata JSON, so "a snapshot ten days old" is one, by Iceberg's own clock.
 *
 * Named without the `Test` suffix so `verifyTestsExecuted` counts only real test classes.
 */
internal class CleanupHarness(
    val protectionDays: Int = LakeProperties.Retention().protectionDays,
    val graceHours: Int = GRACE_HOURS,
) {
    val p = PlanHarness()
    val m: MaintenanceHarness get() = p.m
    val jdbc = LakeTestDb.jdbc
    val real: S3Client = LakeMinio.client()
    val faults = FaultingS3Client(real)
    val store = AgingS3Client(faults)
    val partClock = TestClock(CatalogClock.of(jdbc))
    val removerClock = TestClock(CatalogClock.of(jdbc))
    val runStates = MaintenanceRunStates(jdbc)
    val profiles: LakeProfiles = LakeProfiles.over(m.defs.named, m.defs.tx, m.audit)
    val observer = RecordingObserver()
    val properties =
        LakeProperties(
            enabled = true,
            storage = m.defs.properties.storage,
            retention = LakeProperties.Retention(protectionDays, graceHours),
        )
    val grace: Duration = Duration.ofHours(graceHours.toLong())
    val reads = CleanupReads(jdbc, m.defs.catalogs::metadataFileIo, properties.name)
    val removal: CleanupRemoval =
        CleanupRemoval.of(properties, jdbc, m.defs.catalogs, m.leases, store, runStates, partClock, removerClock)
    private val tables = RemovalTables(jdbc, properties.storage)
    private val mapper = ObjectMapper()

    /** Every data file entry this harness committed, by object key (an overwrite names the entry it removes). */
    private val files = java.util.concurrent.ConcurrentHashMap<String, DataFile>()

    fun reset() {
        p.reset()
        jdbc.update("UPDATE lake_control SET mode = 'normal' WHERE id = 1")
        faults.onDelete = null
        faults.afterDelete = null
        faults.calls.clear()
        faults.deleteCalls.clear()
        store.age = Duration.ZERO
        store.young.clear()
        store.agedMarkers.clear()
        partClock.reset()
        removerClock.reset()
        observer.clear()
    }

    fun close() = p.close()

    fun cleanup(
        observer: CleanupObserver = this.observer,
        properties: LakeProperties = this.properties,
    ): CleanupPart = CleanupPart.of(properties, reads, removal, profiles, observer)

    fun purge(): PurgeDeletionPart = PurgeDeletionPart.of(properties, reads, removal)

    /** This deployment's properties with `retention.protection-days` = [days] (C1: a non-default window). */
    fun withWindow(days: Int): LakeProperties =
        LakeProperties(enabled = true, storage = properties.storage, retention = LakeProperties.Retention(days, graceHours))

    /** A run of [table] the test builds from 957's production class with no row (C8 step 4); its deadline [lifetime] ahead. */
    fun run(
        table: MaintenanceTable,
        lifetime: Duration = Duration.ofHours(1),
        runId: UUID = UUID.randomUUID(),
        hooks: MaintenanceRunHooks = MaintenanceRunHooks.NONE,
        leases: LakeLeases = m.leases,
    ): MaintenanceRun = MaintenanceRunContext(leases, m.defs.catalogs, m.heartbeats, hooks).open(runId, table, m.dbNow() + lifetime, emptyMap())

    /** The production lease protocol with a lock wait long enough that an acquire WAITS behind a held commit (C7). */
    fun patientLeases(): LakeLeases = LakeLeases(jdbc, holderInstance = "lake-test-962", lockTimeout = Duration.ofSeconds(PATIENT_SECONDS))

    /**
     * An append's commit under [lease] (the caller's writer lease, its deadline its own): the data file is PUT first, then
     * committed through the catalog session; answers the file's key. [beforeCommit] runs after the PUT.
     */
    fun appendUnder(
        table: MaintenanceTable,
        lease: LeaseHandle,
        beforeCommit: (String) -> Unit = {},
    ): String {
        var key = ""
        m.defs.catalogs.open(lease).use { session ->
            val loaded = session.loadTable(table.namespace, table.name)
            session.write {
                val file = dataFile(loaded)
                key = requireNotNull(target(table).keyOf(file.location()))
                put(key)
                files[key] = file
                beforeCommit(key)
                loaded.newAppend().appendFile(file).commit()
            }
        }
        return key
    }

    /** A run row of [table] in [state] with no parts (a crashed or a live run of another worker, C15). */
    fun runRow(
        table: MaintenanceTable,
        state: String,
        runId: UUID = UUID.randomUUID(),
    ): UUID {
        jdbc.update(
            "INSERT INTO lake_maintenance_runs (id, table_id, kind, state, deadline_at, ended_at)" +
                " VALUES (?, ?, 'maintenance', ?, now(), CASE WHEN ? = 'running' THEN NULL ELSE now() END)",
            runId,
            table.id,
            state,
            state,
        )
        return runId
    }

    fun setRunState(
        runId: UUID,
        state: String,
    ) = check(jdbc.update("UPDATE lake_maintenance_runs SET state = ?, ended_at = now() WHERE id = ?", state, runId) == 1)

    /** [count] orphans under [table]'s `data/`, PUT on [threads] threads; answers their keys. */
    fun orphans(
        table: MaintenanceTable,
        count: Int,
        threads: Int = PUT_THREADS,
    ): List<String> {
        val prefix = "${target(table).keyPrefix}data/orphan-"
        val keys = (0 until count).map { "$prefix${UUID.randomUUID()}.parquet" }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            keys.map { key -> pool.submit { put(key) } }.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        return keys
    }

    /** The production worker with both parts (or [parts]) and [jobTimeout]. */
    fun worker(
        parts: List<MaintenancePart> = listOf(cleanup(), purge()),
        jobTimeout: Duration = Duration.ofHours(1),
        hooks: MaintenanceRunHooks = MaintenanceRunHooks.NONE,
    ): MaintenanceWorker = m.worker(parts, jobTimeout = jobTimeout, hooks = hooks, clock = Clock.systemUTC())

    /** The planner's due rule (959) for [table] now, with both parts registered and this deployment's grace. */
    fun due(table: MaintenanceTable): TableDue =
        MaintenanceDue(grace)
            .plan(p.repository.decisionInputs(), REGISTERED, p.repository.catalogNow())
            .tables
            .single { it.tableId == table.id }

    fun table(name: String): MaintenanceTable = m.table(name)

    fun target(table: MaintenanceTable): RemovalTarget = requireNotNull(tables.resolve(table.id))

    fun metadata(table: MaintenanceTable): TableMetadata = requireNotNull(reads.metadata(table)) { "no pointer for ${table.name}" }

    fun snapshotIds(table: MaintenanceTable): List<Long> = metadata(table).snapshots().map { it.snapshotId() }

    fun revisionCount(table: MaintenanceTable): Int = m.revisions(table)

    /** Every revision of [table], oldest first: (metadata location, its current snapshot id or null). */
    fun revisions(table: MaintenanceTable): List<Pair<String, Long?>> =
        jdbc
            .queryForList(
                "SELECT metadata_location FROM lake_table_revisions WHERE namespace = ? AND table_name = ? ORDER BY seq",
                String::class.java,
                table.namespace,
                table.name,
            ).map { it to TableMetadataParser.read(m.defs.catalogs.metadataFileIo(), it).currentSnapshot()?.snapshotId() }

    /**
     * Appends one data file entry whose object exists in the store (PUT first: a file the commit references), as an
     * append does; answers the new snapshot id and the file's key.
     */
    fun append(table: MaintenanceTable): Pair<Long, String> {
        var key = ""
        p.commit(table) { t ->
            val file = dataFile(t)
            key = requireNotNull(target(table).keyOf(file.location()))
            put(key)
            files[key] = file
            t.newAppend().appendFile(file).commit()
        }
        return metadata(table).currentSnapshot().snapshotId() to key
    }

    /** Replaces the data file [key] (from [append]) by a new one in ONE overwrite: [key] stays reachable only from older snapshots. */
    fun replace(
        table: MaintenanceTable,
        key: String,
    ): Pair<Long, String> {
        var added = ""
        p.commit(table) { t ->
            val removed = files.getValue(key)
            val file = dataFile(t)
            added = requireNotNull(target(table).keyOf(file.location()))
            put(added)
            files[added] = file
            t.newOverwrite().deleteFile(removed).addFile(file).commit()
        }
        return metadata(table).currentSnapshot().snapshotId() to added
    }

    /**
     * [append], then [restamp] of the new snapshot to [at] in the next commit: the revision whose current snapshot it is
     * carries [at] (the floor reads a revision's current snapshot time from its own metadata file).
     */
    fun appendAt(
        table: MaintenanceTable,
        at: Instant,
    ): Pair<Long, String> = append(table).also { (id, _) -> restamp(table, mapOf(id to at)) }

    /** A branch [name] on [snapshotId], committed under the table lease. */
    fun branch(
        table: MaintenanceTable,
        name: String,
        snapshotId: Long,
    ) = p.commit(table) { t -> t.manageSnapshots().createBranch(name, snapshotId).commit() }

    /** The parts of [row] as the run row stores them: one map per part, the fixed keys and the part's details. */
    fun parts(row: co.datapipelines.lake.maintenance.MaintenanceRunRow): List<Map<String, Any?>> =
        mapper.readValue(row.partsJson, object : com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Any?>>>() {})

    /** The last revision of [table] whose current snapshot is [snapshotId] (the one a restamp left). */
    fun revisionOf(
        table: MaintenanceTable,
        snapshotId: Long,
    ): String = revisions(table).last { it.second == snapshotId }.first

    /** 960's technique on the real catalog: one commit rewriting the timestamps of [times]'s snapshots and their log entries. */
    fun restamp(
        table: MaintenanceTable,
        times: Map<Long, Instant>,
    ) = p.commit(table) { t ->
        val ops = (t as HasTableOperations).operations()
        val base = ops.current()
        val json = mapper.readTree(TableMetadataParser.toJson(base))
        listOf("snapshots", "snapshot-log").forEach { array ->
            json.get(array)?.forEach { node ->
                val entry = node as ObjectNode
                times[entry.get("snapshot-id").asLong()]?.let { entry.put("timestamp-ms", it.toEpochMilli()) }
            }
        }
        // As Iceberg's own builder does for every commit: the replaced file joins the metadata log, so the file stays
        // reached exactly as an ordinary commit leaves it (without this, the restamp alone would orphan the base file).
        val log = (json as ObjectNode).get("metadata-log") as? ArrayNode ?: json.putArray("metadata-log")
        log
            .addObject()
            .put("timestamp-ms", base.lastUpdatedMillis())
            .put("metadata-file", base.metadataFileLocation())
        ops.commit(base, TableMetadataParser.fromJson(json))
    }

    /** The table's row was created at [at] (a keep period that ended before it is never asked for). */
    fun createdAt(
        table: MaintenanceTable,
        at: Instant,
    ) = check(jdbc.update("UPDATE lake_tables_managed SET created_at = ? WHERE id = ?", Timestamp.from(at), table.id) == 1)

    /** The settlement sweep's finalisation, simulated: every superseded revision's `protected_until` set to [until]. */
    fun finalise(
        table: MaintenanceTable,
        until: Instant = m.dbNow() - Duration.ofDays(1),
    ) = jdbc.update(
        "UPDATE lake_table_revisions SET protected_until = ? WHERE namespace = ? AND table_name = ? AND superseded_at IS NOT NULL",
        Timestamp.from(until),
        table.namespace,
        table.name,
    )

    fun protect(location: String) =
        check(jdbc.update("UPDATE lake_table_revisions SET protected_until = NULL WHERE metadata_location = ?", location) == 1)

    fun status(
        table: MaintenanceTable,
        status: String,
    ): MaintenanceTable {
        check(jdbc.update("UPDATE lake_tables_managed SET status = ? WHERE id = ?", status, table.id) == 1)
        return table.copy(status = status)
    }

    fun setMode(mode: String) = check(jdbc.update("UPDATE lake_control SET mode = ? WHERE id = 1", mode) == 1)

    /** A profile [name] holding [keep] (the `retention.keep` JSON) assigned to [table]. */
    fun keepProfile(
        table: MaintenanceTable,
        name: String,
        keep: String,
    ) {
        profiles.create(name, LakeProfileFixtures.document("retention.keep" to keep), m.defs.actor)
        profiles.assign(table.id, name, m.defs.actor)
    }

    /** A live reader generation pinned to [location] (record §9). */
    fun pin(
        table: MaintenanceTable,
        location: String,
    ): LeaseHandle {
        val id = UUID.randomUUID()
        val leases = LakeTestDb.leases
        val name = "${table.leaseName}@${leases.holderInstance}/$id"
        val ttl = Duration.ofMinutes(10)
        val outcome = leases.acquireReader(name, id, ttl, leases.catalogNowPlus(ttl), location)
        return (outcome as? LeaseOutcome.Acquired)?.lease ?: error("reader pin not acquired: $outcome")
    }

    fun unpin(pin: LeaseHandle) = LakeTestDb.leases.release(pin.operationId)

    fun put(
        key: String,
        bytes: Int = SMALL,
    ) {
        real.putObject(
            PutObjectRequest
                .builder()
                .bucket(properties.storage.bucket)
                .key(key)
                .build(),
            RequestBody.fromBytes(ByteArray(bytes)),
        )
    }

    fun exists(key: String): Boolean =
        try {
            real.headObject(
                HeadObjectRequest
                    .builder()
                    .bucket(properties.storage.bucket)
                    .key(key)
                    .build(),
            )
            true
        } catch (_: NoSuchKeyException) {
            false
        }

    /** The test's OWN listing under [table]'s location (never the remover's), with MinIO's real `LastModified`. */
    fun listing(table: MaintenanceTable): List<ListedObject> =
        real
            .listObjectsV2Paginator(
                ListObjectsV2Request
                    .builder()
                    .bucket(properties.storage.bucket)
                    .prefix(target(table).keyPrefix)
                    .build(),
            ).contents()
            .map { ListedObject(it.key(), it.size(), it.lastModified()) }

    /** An orphan under [table]'s `data/` (a file no snapshot names), PUT now; answers its key. */
    fun orphan(table: MaintenanceTable): String = "${target(table).keyPrefix}data/orphan-${UUID.randomUUID()}.parquet".also { put(it) }

    private fun dataFile(t: Table): DataFile =
        DataFiles
            .builder(t.spec())
            .withPath("${t.location()}/data/${UUID.randomUUID()}.parquet")
            .withFormat(FileFormat.PARQUET)
            .withFileSizeInBytes(SMALL.toLong())
            .withRecordCount(1)
            .withPartitionPath("${t.spec().fields().single().name()}=2026-11-01")
            .build()

    companion object {
        /** The recipe's grace (brief 962 C8): G = 2 h, admitted with J = 3585 s and S = 5 s (`LakeRulesTest`). */
        const val GRACE_HOURS = 2
        const val SMALL = 16
        const val PATIENT_SECONDS = 60L
        const val PUT_THREADS = 16
        val REGISTERED: Set<String> = setOf(MaintenancePartNames.CLEANUP, MaintenancePartNames.PURGE_DELETION)

        /** Older than any grace a case uses. */
        val OLD: Duration = Duration.ofHours(GRACE_HOURS.toLong()).plusHours(1)
    }
}

/**
 * The remover's object-store client with the listed ages moved back by [age] (brief 962 C8 step 2): keys, sizes and the
 * set of keys are MinIO's; a key under `dp/clock/` and a key in [young] keep MinIO's own `LastModified`. Every other call
 * is forwarded unchanged; an operation the remover does not use is the interface's default, which refuses.
 */
internal class AgingS3Client(
    private val real: S3Client,
) : S3Client {
    @Volatile
    var age: Duration = Duration.ZERO

    val young: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Keys aged by [age] even under `dp/clock/`: ANOTHER run's marker (C15), never the probing pass's own. */
    val agedMarkers: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override fun listObjectsV2(request: ListObjectsV2Request): software.amazon.awssdk.services.s3.model.ListObjectsV2Response {
        val response = real.listObjectsV2(request)
        val shift = age
        if (shift.isZero) return response
        return response
            .toBuilder()
            .contents(
                response.contents().map { o ->
                    val exempt = (CLOCK in o.key() && o.key() !in agedMarkers) || o.key() in young
                    if (exempt) o else o.toBuilder().lastModified(o.lastModified().minus(shift)).build()
                },
            ).build()
    }

    override fun putObject(
        request: PutObjectRequest,
        body: RequestBody,
    ) = real.putObject(request, body)

    override fun headObject(request: HeadObjectRequest) = real.headObject(request)

    override fun deleteObjects(request: software.amazon.awssdk.services.s3.model.DeleteObjectsRequest) = real.deleteObjects(request)

    override fun serviceName(): String = real.serviceName()

    override fun close() = Unit

    private companion object {
        const val CLOCK = "/" + RemovalTarget.CLOCK_DIR
    }
}

/** A recording [CleanupObserver] (C14b): every observation in order; [failure] makes the next calls throw it. */
internal class RecordingObserver : CleanupObserver {
    val observations: MutableList<CleanupObservation> = CopyOnWriteArrayList()

    @Volatile
    var failure: Exception? = null

    override fun observed(observation: CleanupObservation) {
        observations += observation
        failure?.let { throw it }
    }

    fun clear() {
        observations.clear()
        failure = null
    }
}
