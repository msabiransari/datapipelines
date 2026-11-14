package co.datapipelines.lake.maintenance.remove

import co.datapipelines.lake.LakeCatalogSession
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.ReaderPins
import co.datapipelines.lake.definitions.LakeDefinitionRepository
import org.apache.iceberg.DataFile
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.SortOrder
import org.apache.iceberg.Table
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.catalog.TableIdentifier
import org.apache.iceberg.data.GenericRecord
import org.apache.iceberg.data.Record
import org.apache.iceberg.data.parquet.GenericParquetWriter
import org.apache.iceberg.parquet.Parquet
import org.apache.iceberg.types.Types
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * A wrapped catalog clock (D7): the real `clock_timestamp()` shifted by [shift], or frozen at one instant. The object
 * store is the real MinIO container with its own clock; only the catalog's side moves.
 */
internal class TestClock(
    private val base: CatalogClock,
) : CatalogClock {
    private val shift = AtomicReference(Duration.ZERO)
    private val frozen = AtomicReference<Instant?>(null)

    override fun now(): Instant = frozen.get() ?: base.now().plus(shift.get())

    /** The catalog reads `real + by` from now on (positive = the catalog is AHEAD of the store). */
    fun shift(by: Duration) {
        frozen.set(null)
        shift.set(by)
    }

    /** Every reading answers [at] until [shift] or [reset]. */
    fun freeze(at: Instant) = frozen.set(at)

    fun reset() = shift(Duration.ZERO)
}

/** The test implementation of the [RunStates] port (962 ships the one over `lake_maintenance_runs`); absent = unknown. */
internal class FakeRunStates : RunStates {
    private val states = ConcurrentHashMap<UUID, Boolean>()

    override fun isTerminal(runId: UUID): Boolean? = states[runId]

    fun terminal(runId: UUID) = states.put(runId, true)

    fun live(runId: UUID) = states.put(runId, false)

    fun clear() = states.clear()
}

/**
 * One suite's lake: the real catalog database ([LakeTestDb]), the real MinIO bucket [bucket] ([LakeMinio]), managed table
 * rows in [namespace], Iceberg tables created through production's [LakeCatalogs], and the remover built from its parts
 * as 962 will build it. The test is the caller: it holds each table's lease ([Fixture.lease]) across a pass.
 */
internal class RemoverHarness(
    val bucket: String,
    val namespace: String,
) {
    val storage: LakeProperties.Storage = LakeMinio.storage(bucket)
    val catalogs = LakeCatalogs(LakeProperties(enabled = true, storage = storage), LakeTestDb.catalogDatabase())
    val s3: S3Client = LakeMinio.client()
    val jdbc: JdbcTemplate = LakeTestDb.jdbc
    val clock = TestClock(CatalogClock.of(jdbc))
    val runStates = FakeRunStates()
    private val named = NamedParameterJdbcTemplate(jdbc)
    private val sessions = mutableListOf<LakeCatalogSession>()

    /** Empties the catalog, the managed rows and the bucket; the catalog back to `normal`; the clock unshifted. */
    fun reset() {
        close()
        LakeTestDb.reset()
        jdbc.execute("TRUNCATE lake_tables_managed, lake_namespaces CASCADE")
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (?, ?, 'Lane 958', 'google', ?) " +
                "ON CONFLICT (id) DO NOTHING",
            ACTOR,
            "lane958@example.com",
            "sub-958",
        )
        emptyBucket()
        clock.reset()
        runStates.clear()
        val lease = LakeTestDb.lease(LeaseKind.NAMESPACE, namespace)
        try {
            catalogs.open(lease).use { it.createNamespace(namespace) }
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    fun close() {
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    /** A managed table row plus its Iceberg table, under a TABLE lease the test keeps for the remover. */
    fun table(
        name: String,
        properties: Map<String, String> = emptyMap(),
    ): Fixture {
        val id = UUID.randomUUID()
        val definitions = LakeDefinitionRepository(named)
        check(definitions.insertTable(id, definitions.namespaceId(namespace, ACTOR), name, "{}", ACTOR))
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "$namespace.$name", ttl = LEASE_TTL)
        val session = catalogs.open(lease).also { sessions += it }
        val table = session.createTable(namespace, name, id, SCHEMA, PartitionSpec.unpartitioned(), SortOrder.unsorted(), 1)
        if (properties.isNotEmpty()) {
            table.updateProperties().apply { properties.forEach { (k, v) -> set(k, v) } }.commit()
        }
        return Fixture(id, name, lease, table, session)
    }

    /** The remover as 962 builds it, over [client] (the real one by default) and the wrapped [clock]. */
    fun remover(
        client: S3Client = s3,
        grace: Duration = GRACE,
        catalogClock: CatalogClock = clock,
    ): LakeRemover = LakeRemover(RemovalTables(jdbc, storage), checks(), walker(), client, catalogClock, runStates, grace)

    fun checks(): RemovalChecks = RemovalChecks(jdbc, LakeTestDb.leases, ReaderPins(jdbc), LakeProperties.DEFAULT_NAME, LEASE_TTL)

    fun walker(): ReachabilityWalker = ReachabilityWalker(catalogs.metadataFileIo())

    fun markPurged(fixture: Fixture) =
        check(
            jdbc.update(
                "UPDATE lake_tables_managed SET status = 'purged', purged_at = now(), purged_by = ? WHERE id = ?",
                ACTOR,
                fixture.id,
            ) == 1,
        )

    fun setMode(mode: String) = check(jdbc.update("UPDATE lake_control SET mode = ? WHERE id = 1", mode) == 1)

    /** The caller's lease lapses (a takeover would be the same for the remover: its heartbeat answers no). */
    fun loseLease(lease: LeaseHandle) =
        check(jdbc.update("UPDATE lake_leases SET expires_at = now() - interval '1 second' WHERE operation_id = ?", lease.operationId) == 1)

    /** Every superseded revision of [fixture] leaves the protection window (`protected_until` in the past). */
    fun unprotectSuperseded(fixture: Fixture) =
        jdbc.update(
            "UPDATE lake_table_revisions SET protected_until = now() - interval '1 day'" +
                " WHERE namespace = ? AND table_name = ? AND superseded_at IS NOT NULL",
            namespace,
            fixture.name,
        )

    fun setProtectedUntilNull(location: String) =
        check(jdbc.update("UPDATE lake_table_revisions SET protected_until = NULL WHERE metadata_location = ?", location) == 1)

    /** A live reader generation pinned to [location] (record §9 draft 24). */
    fun pin(
        fixture: Fixture,
        location: String,
    ): LeaseHandle {
        val id = UUID.randomUUID()
        val leases = LakeTestDb.leases
        val name = "$namespace.${fixture.name}@${leases.holderInstance}/$id"
        val outcome = leases.acquireReader(name, id, LEASE_TTL, leases.catalogNowPlus(LEASE_TTL), location)
        return (outcome as? LeaseOutcome.Acquired)?.lease ?: error("reader pin not acquired: $outcome")
    }

    fun unpin(pin: LeaseHandle) = LakeTestDb.leases.release(pin.operationId)

    /** Every revision's metadata location of [fixture], oldest first (the catalog's own record of the commits). */
    fun revisions(fixture: Fixture): List<String> =
        jdbc.queryForList(
            "SELECT metadata_location FROM lake_table_revisions WHERE namespace = ? AND table_name = ? ORDER BY seq",
            String::class.java,
            namespace,
            fixture.name,
        )

    fun put(
        key: String,
        bytes: Int = SMALL,
    ) {
        s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .build(),
            RequestBody.fromBytes(ByteArray(bytes)),
        )
    }

    /**
     * Waits so that a key written after this call carries a `LastModified` at least two whole seconds later than one
     * written before it; then [youngAt] makes the later keys young and the earlier ones old.
     */
    fun separate() = Thread.sleep(SEPARATION_MILLIS)

    /** Freezes the catalog clock so [youngKey] is one second inside grace, and every key two or more seconds older is past it. */
    fun youngAt(youngKey: String) = clock.freeze(lastModified(youngKey).plus(GRACE).minusSeconds(1))

    /** Freezes the catalog clock so [key] (and everything older) is one second past grace. */
    fun oldAt(key: String) = clock.freeze(lastModified(key).plus(GRACE).plusSeconds(1))

    fun lastModified(key: String): Instant =
        s3
            .headObject(
                HeadObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
            ).lastModified()

    fun exists(key: String): Boolean =
        try {
            s3.headObject(
                HeadObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
            )
            true
        } catch (_: NoSuchKeyException) {
            false
        }

    /** The test's OWN listing under [prefix] (never the remover's): key → (size, LastModified). */
    fun listing(prefix: String): List<ListedObject> =
        s3
            .listObjectsV2Paginator(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build(),
            ).contents()
            .map { ListedObject(it.key(), it.size(), it.lastModified()) }

    private fun emptyBucket() {
        listing("").map { it.key }.chunked(BATCH).forEach { keys ->
            s3.deleteObjects(
                DeleteObjectsRequest
                    .builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(keys.map { ObjectIdentifier.builder().key(it).build() }).build())
                    .build(),
            )
        }
    }

    inner class Fixture(
        val id: UUID,
        val name: String,
        val lease: LeaseHandle,
        val table: Table,
        private val session: LakeCatalogSession,
    ) {
        val target: RemovalTarget get() = requireNotNull(RemovalTables(jdbc, storage).resolve(id))

        /** The object key of [relative] under this table's location. */
        fun key(relative: String): String = "${target.keyPrefix}$relative"

        /** The object key of an Iceberg location of this bucket. */
        fun keyOf(location: String): String = requireNotNull(target.keyOf(location)) { "not a location of $bucket: $location" }

        /** A data file written to the table's `data/` and NOT committed (an abandoned append's file). */
        fun writeData(): DataFile {
            val writer =
                Parquet
                    .writeData(table.io().newOutputFile("${table.location()}/data/${UUID.randomUUID()}.parquet"))
                    .schema(table.schema())
                    .withSpec(table.spec())
                    .createWriterFunc(GenericParquetWriter::create)
                    .build<Record>()
            writer.use { it.write(GenericRecord.create(table.schema()).copy(mapOf("id" to 1L))) }
            return writer.toDataFile()
        }

        /** One committed append of a fresh data file. */
        fun append(): DataFile = writeData().also { table.newAppend().appendFile(it).commit() }

        fun currentMetadata(): String = revisions(this).last()

        /** Drops the table from the catalog without purging (the purge sequence's first step; files stay). */
        fun drop() = check(session.write { it.dropTable(TableIdentifier.of(Namespace.of(namespace), name), false) })
    }

    companion object {
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000958")

        /** The tests' orphan grace; production converts `retention.orphan-grace-hours` (962). */
        val GRACE: Duration = Duration.ofMinutes(30)

        /** A shift that makes every file older than [GRACE] while the probe stays inside ±3600 s. */
        val PAST_GRACE: Duration = Duration.ofMinutes(40)
        val LEASE_TTL: Duration = Duration.ofMinutes(10)
        val SCHEMA = Schema(Types.NestedField.required(1, "id", Types.LongType.get()))
        const val SMALL = 16
        const val BATCH = 1000
        const val SEPARATION_MILLIS = 2_200L
    }
}
