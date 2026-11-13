package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTableProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.definitions.LakeApplyLeases
import co.datapipelines.lake.definitions.LakeApplyRepository
import co.datapipelines.lake.definitions.LakeApplyState
import co.datapipelines.lake.definitions.LakeDefinitionApplier
import co.datapipelines.lake.definitions.LakeDefinitionFixtures
import co.datapipelines.lake.definitions.LakeDefinitionRepository
import co.datapipelines.lake.definitions.RecordingAuditSink
import co.datapipelines.lake.settle.DeliverySettlementRepository
import co.datapipelines.lake.settle.DeliverySettler
import co.datapipelines.lake.settle.LakeMetadataReads
import co.datapipelines.lake.settle.LakeProjectionRepair
import co.datapipelines.lake.settle.LakeSemanticsRepair
import co.datapipelines.lake.settle.LakeSettlementSweep
import co.datapipelines.lake.settle.SettleBudget
import co.datapipelines.pipeline.WriteSurface
import io.mockk.mockk
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.TableMetadata
import org.apache.iceberg.TableMetadataParser
import org.apache.iceberg.io.FileIO
import org.apache.iceberg.types.Types
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Real ledger, real fence and separate bounded JDBC statements; no MinIO needed to move the catalog pointer.
 * The ledger carries a recording audit sink (799b): every settled conclusion's strict in-transaction audit is observable.
 */
internal class DeliverySettlementHarness(
    dataSource: DataSource = LakeTestDb.dataSource,
    private val resetDatabase: () -> Unit = LakeTestDb::reset,
    private val connect: () -> Connection = { LakeTestDb.connect() },
    statementTimeoutSeconds: Int = 2,
    /** Production's 5 s when a case measures the allowance; unbounded otherwise. */
    transactionTimeoutSeconds: Int = TransactionDefinition.TIMEOUT_DEFAULT,
) {
    val jdbc = JdbcTemplate(dataSource).apply { queryTimeout = statementTimeoutSeconds }
    val named = NamedParameterJdbcTemplate(jdbc)
    val repository = DeliveryLedgerRepository(named)
    val observations = DeliverySettlementRepository(named)
    val transaction = TransactionTemplate(DataSourceTransactionManager(dataSource)).apply { timeout = transactionTimeoutSeconds }
    val audit = RecordingAuditSink()
    val ledger = DeliveryLedger(repository, transaction, audit)
    val tableId: UUID = UUID.randomUUID()
    val actor: UUID = UUID.fromString("00000000-0000-0000-0000-000000000799")
    private val openReads = mutableListOf<LakeMetadataReads>()

    fun reset() {
        resetDatabase()
        jdbc.execute("TRUNCATE lake_delivery_attempts, lake_deliveries, lake_tables_managed, lake_namespaces CASCADE")
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (?, ?, 'Lane 799', 'google', ?) " +
                "ON CONFLICT (id) DO NOTHING",
            actor,
            "lane799@example.com",
            "sub-799",
        )
        val definitions = LakeDefinitionRepository(named)
        definitions.insertTable(tableId, definitions.namespaceId("settle", actor), "orders", "{}", actor)
    }

    fun leases(lockTimeout: Duration = Duration.ofMillis(100)): LakeLeases = LakeLeases(jdbc, lockTimeout = lockTimeout)

    fun settler(leases: LakeLeases = leases()): DeliverySettler =
        DeliverySettler(observations, repository, ledger, leases, LakeProjectionRepair(named, transaction, "lake799") {})

    /** Another managed table in the harness's namespace; [tableId] stays the default for every helper. */
    fun addTable(name: String): UUID {
        val definitions = LakeDefinitionRepository(named)
        val id = UUID.randomUUID()
        check(definitions.insertTable(id, definitions.namespaceId("settle", actor), name, "{}", actor))
        return id
    }

    /**
     * The production sweep over this harness's tables. They carry no applied definition and no APPLYING attempt, so
     * the applier and the semantics repair return before reading any metadata; the store that refuses proves it.
     * [budgets] mints each tick's budget (production's by default); pass it and [protectionDays] by name.
     */
    fun sweep(
        leases: LakeLeases = leases(),
        budgets: () -> SettleBudget = { SettleBudget.startingNow(leases, LakeSettlementSweep.SWEEP_BUDGET) },
        protectionDays: Int = LakeProperties.DEFAULT_PROTECTION_DAYS,
    ): LakeSettlementSweep = graph(leases, budgets, protectionDays) { error("these tables never reach a metadata read") }

    /**
     * The production sweep over tables that DO reach verification and repair (B.0), with [io] as the object store the
     * metadata read meets. The catalog stays the strict double: a catalog session on the sweep path fails the case.
     * Pass [budgets] by name.
     */
    fun stallableSweep(
        io: FileIO,
        leases: LakeLeases = leases(),
        budgets: () -> SettleBudget = { SettleBudget.startingNow(leases, LakeSettlementSweep.SWEEP_BUDGET) },
    ): LakeSettlementSweep = graph(leases, budgets, LakeProperties.DEFAULT_PROTECTION_DAYS) { io }

    private fun graph(
        leases: LakeLeases,
        budgets: () -> SettleBudget,
        protectionDays: Int,
        store: () -> FileIO,
    ): LakeSettlementSweep {
        val definitions = LakeDefinitionRepository(named)
        val reads = LakeMetadataReads(named, LAKE, store).also(openReads::add)
        return LakeSettlementSweep(
            named,
            leases,
            settler(leases),
            LakeDefinitionApplier(
                definitions,
                LakeApplyRepository(named),
                LakeApplyLeases(leases, Duration.ZERO),
                mockk(),
                LAKE,
                transaction,
                audit,
            ),
            LakeSemanticsRepair(named, transaction, observations, definitions, leases, reads),
            reads,
            protectionDays,
            budgets = budgets,
        )
    }

    /** A work budget a case controls: [expired] answers every check; its deadline is a minute away on the database clock. */
    fun budget(expired: () -> Boolean): SettleBudget =
        SettleBudget(leases().catalogNowPlus(Duration.ofMinutes(1)), expired) { Duration.ofMinutes(1) }

    /**
     * Waits for the latest sweep's abandoned read to be over, not only its stream closed: the read's own thread still
     * parses after the close. Called between ticks, as `stuck()` is only ever called by one tick at a time.
     */
    fun noStuckReadWithin(millis: Long): Boolean {
        val end = System.nanoTime() + Duration.ofMillis(millis).toNanos()
        while (openReads.last().stuck() && System.nanoTime() < end) {
            Thread.sleep(POLL_MILLIS)
        }
        return !openReads.last().stuck()
    }

    /** Ends what a case's sweeps left running. */
    fun close() = openReads.forEach(LakeMetadataReads::close)

    /** Version 1 of [table]'s definition, released and current; the row is `pending` (P3's landing without its apply). */
    fun releaseDefinition(table: UUID = tableId) {
        val definitions = LakeDefinitionRepository(named)
        checkNotNull(definitions.insertDraft(table, 1, LakeDefinitionFixtures.ORDERS_V1, actor, WriteSurface.SESSION))
        check(definitions.releaseDraft(table, 1, null, actor) == 1)
        check(definitions.pointAtRelease(table, 1) == 1)
    }

    /**
     * [table] is `applying` an attempt that died after committing: the row names the attempt, the catalog row and its
     * revision carry the attempt's operation id, no lease is live. The shape verification settles. Answers the attempt.
     */
    fun applyingWithEvidence(
        table: UUID = tableId,
        name: String = "orders",
    ): UUID {
        releaseDefinition(table)
        val attempt = UUID.randomUUID()
        check(LakeApplyRepository(named).markApplying(table, 1, attempt) == 1)
        commitPointer(name, attempt)
        return attempt
    }

    /**
     * [table] is `applied` at version 1 with no semantics rows: its applied definition differs from
     * `lake_column_semantics`, the shape the semantics repair rebuilds.
     */
    fun staleSemantics(
        table: UUID = tableId,
        name: String = "orders",
    ) {
        val attempt = applyingWithEvidence(table, name)
        check(LakeApplyRepository(named).finishApplied(table, 1, attempt) == 1)
    }

    fun applyState(table: UUID = tableId): LakeApplyState? = LakeDefinitionRepository(named).findTable(table)?.applyState

    /** The JSON of a table at definition [version]: what the store answers for the catalog row's location. */
    fun metadataJson(version: Int = 1): ByteArray =
        TableMetadataParser
            .toJson(
                TableMetadata.newTableMetadata(
                    Schema(Types.NestedField.required(1, "id", Types.LongType.get())),
                    PartitionSpec.unpartitioned(),
                    "s3://$LAKE/settle/orders",
                    mapOf(LakeTableProperties.DEFINITION_VERSION to version.toString()),
                ),
            ).toByteArray()

    /** The catalog row and revision of [name], committed by the attempt [attempt] under its fence, the lease then gone. */
    private fun commitPointer(
        name: String,
        attempt: UUID,
    ) {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "settle.$name", operationId = attempt)
        fencedConnection(lease).use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name, metadata_location, iceberg_type) " +
                        "VALUES (?, 'settle', ?, ?, 'TABLE')",
                ).use { statement ->
                    statement.setString(1, LAKE)
                    statement.setString(2, name)
                    statement.setString(3, "s3://$LAKE/settle/$name/metadata/00001.metadata.json")
                    check(statement.executeUpdate() == 1)
                }
        }
        LakeTestDb.leases.release(attempt)
    }

    /** One RECEIVED delivery; its random execution id has no RUNNING execution row, so settlement sees it ended. */
    fun receive(
        key: DeliveryKey = DeliveryKey("orders", "one"),
        table: UUID = tableId,
    ): LakeDelivery =
        ledger
            .receive(
                ReceiveDelivery(
                    table,
                    DeliveryManifest(
                        key,
                        emptyList(),
                        emptyList(),
                        DeliveryFormat.Csv,
                        "a".repeat(64),
                        1,
                    ),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                ),
            ).delivery

    /** [n] RECEIVED deliveries with ended executions, distinct keys, returned in PostgreSQL's `ORDER BY id`. */
    fun backlog(
        n: Int,
        table: UUID = tableId,
    ): List<LakeDelivery> {
        repeat(n) { receive(DeliveryKey("orders", "backlog-$it"), table) }
        return byId(table)
    }

    /**
     * [n] PUBLISHING deliveries the sweep cannot settle: the first holds the table's one live lease as its own attempt
     * (`attemptLeaseLive`), every other row's takeover finds that live holder (`Busy`). Release the returned lease to
     * make them all eligible. Returned rows are in PostgreSQL's `ORDER BY id`.
     */
    fun livePublishing(rows: List<LakeDelivery>): LeaseHandle {
        rows.forEach { check(ledger.beginPublishing(it)) }
        return LakeTestDb.lease(LeaseKind.TABLE, "settle.orders", operationId = rows.first().operationId)
    }

    /** Every unsettled or settled delivery of this table in PostgreSQL's uuid order (JVM `UUID.compareTo` differs). */
    fun byId(table: UUID = tableId): List<LakeDelivery> =
        jdbc
            .queryForList("SELECT id FROM lake_deliveries WHERE table_id = ? ORDER BY id", UUID::class.java, table)
            .map { requireNotNull(repository.find(it)) }

    fun unknown(): LakeDelivery {
        val received = receive()
        check(ledger.beginPublishing(received))
        check(ledger.conclude(requireNotNull(repository.find(received.id)), DeliveryConclusion.Unknown(null)))
        return requireNotNull(repository.find(received.id))
    }

    fun fencedConnection(lease: LeaseHandle): Connection =
        connect().also { connection ->
            connection
                .prepareStatement(
                    "SELECT set_config('dp.lease_token', ?, false), set_config('dp.operation_id', ?, false)",
                ).use { statement ->
                    statement.setString(1, lease.token.toString())
                    statement.setString(2, lease.operationId.toString())
                    statement.executeQuery().close()
                }
        }

    fun insertPointer(connection: Connection): Int =
        connection
            .prepareStatement(
                "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name, metadata_location, iceberg_type) " +
                    "VALUES ('lake', 'settle', 'orders', 's3://settle/metadata/one.json', 'TABLE')",
            ).use { it.executeUpdate() }

    fun attemptOutcome(row: LakeDelivery): String? =
        jdbc.queryForObject(
            "SELECT outcome FROM lake_delivery_attempts WHERE operation_id = ?",
            String::class.java,
            row.operationId,
        )

    companion object {
        /** The harness's `catalog_name`. */
        const val LAKE = "lake799"
        private const val POLL_MILLIS = 10L
    }
}

/**
 * The pre-799b pins' settlement call: one page of [tableId]'s unsettled deliveries from its first row, under a budget
 * that ends at the database-clock [deadline] ([leases] reads that clock). Answers how many rows it changed; the pins
 * hold far fewer rows than a page, so the page is the whole table for them.
 */
internal fun DeliverySettler.settlePage(
    leases: LakeLeases,
    tableId: UUID,
    deadline: Instant,
): Int = settle(tableId, SettleBudget.until(leases, deadline), null, LakeSettlementSweep.SETTLE_PAGE_SIZE).changed
