package co.datapipelines.lake.maintenance

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.SystemActorPrincipals
import co.datapipelines.auth.User
import co.datapipelines.auth.UserKind
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.definitions.LakeApplyOutcome
import co.datapipelines.lake.definitions.LakeDefinitionFixtures
import co.datapipelines.lake.definitions.LakeDefinitionsHarness
import co.datapipelines.lake.definitions.RecordingAuditSink
import co.datapipelines.pipeline.WriteSurface
import org.apache.iceberg.TableMetadataParser
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #957 — the maintenance worker wired as [LakeMaintenanceAutoConfiguration] wires it, over the module's one Postgres
 * (V53–V60) and MinIO, with real managed tables created and applied through the definitions service. Every port is
 * replaceable by name; the test parts commit one marker property each through [MaintenanceRun.commit].
 *
 * Named without the `Test` suffix so `verifyTestsExecuted` counts only real test classes.
 */
internal class MaintenanceHarness(
    /** A short lock wait: a takeover of a lease another transaction holds answers `Busy` quickly. */
    val leases: LakeLeases = LakeLeases(LakeTestDb.jdbc, holderInstance = "lake-test-957", lockTimeout = Duration.ofMillis(LOCK_TIMEOUT_MILLIS)),
) {
    val defs = LakeDefinitionsHarness(leases = leases)
    val named = NamedParameterJdbcTemplate(LakeTestDb.dataSource)
    val store = MaintenanceRunRepository(named)
    val audit = RecordingAuditSink()
    val heartbeats: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    /** The provisioned system identity as the worker's wiring reads it; no workspace exists for it. */
    val system: User =
        User(
            id = UUID.fromString("00000000-0000-0000-0000-000000000957"),
            email = "system@system.invalid",
            displayName = "System",
            provider = "system",
            providerSubject = "system",
            isActive = true,
            isAdmin = false,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            kind = UserKind.SYSTEM,
        )

    fun reset() {
        defs.reset()
        LakeTestDb.jdbc.execute("TRUNCATE lake_maintenance_runs")
        audit.events.clear()
    }

    fun close() {
        heartbeats.shutdownNow()
    }

    /** A managed table `maint.<name>`, released and applied: its Iceberg table exists. */
    fun table(name: String = "orders"): MaintenanceTable {
        val draft = defs.service.createDraft(NAMESPACE, name, LakeDefinitionFixtures.ORDERS_V1, defs.actor, WriteSurface.SESSION)
        val applied = defs.service.release(draft.tableId, 1, defs.actor, WriteSurface.SESSION).apply
        check(applied == LakeApplyOutcome.Applied(1, committed = true)) { "table $name not applied: $applied" }
        return requireNotNull(store.findTable(draft.tableId))
    }

    @Suppress("LongParameterList")
    fun worker(
        parts: List<MaintenancePart>,
        gate: MaintenanceGate = MaintenanceGate.ADMIT_ALL,
        slots: MaintenanceSlots = SemaphoreMaintenanceSlots(1),
        scratch: MaintenanceScratch = MaintenanceScratch.NONE,
        settlement: MaintenanceSettlement = NO_SETTLEMENT,
        store: MaintenanceRunStore = this.store,
        hooks: MaintenanceRunHooks = MaintenanceRunHooks.NONE,
        jobTimeout: Duration = Duration.ofHours(2),
        clock: Clock = Clock.systemUTC(),
        principal: () -> AuthenticatedPrincipal = { SystemActorPrincipals.forInstance(system) },
    ): MaintenanceWorker =
        MaintenanceWorker(
            principal = principal,
            store = store,
            slots = slots,
            scratch = scratch,
            parts = parts,
            gate = gate,
            settlement = settlement,
            context = MaintenanceRunContext(leases, defs.catalogs, heartbeats, hooks),
            audit = audit,
            jobTimeout = jobTimeout,
            clock = clock,
        )

    /** The table's current Iceberg properties, read from its metadata file with no lease (a reader's view). */
    fun properties(table: MaintenanceTable): Map<String, String> {
        val location =
            LakeTestDb.jdbc.queryForObject(
                "SELECT metadata_location FROM iceberg_tables WHERE table_namespace = ? AND table_name = ?",
                String::class.java,
                table.namespace,
                table.name,
            )
        return TableMetadataParser.read(defs.catalogs.metadataFileIo(), location).properties()
    }

    /** The marker properties the test parts committed, by part name. */
    fun markers(table: MaintenanceTable): Set<String> =
        properties(table).keys.filter { it.startsWith(MARKER) }.map { it.removePrefix(MARKER) }.toSet()

    fun revisions(table: MaintenanceTable): Int = defs.revisions(table.namespace, table.name)

    fun rows(table: MaintenanceTable): List<MaintenanceRunRow> = store.forTable(table.id)

    fun running(table: MaintenanceTable): Int =
        requireNotNull(
            LakeTestDb.jdbc.queryForObject(
                "SELECT count(*) FROM lake_maintenance_runs WHERE table_id = ? AND state = 'running'",
                Int::class.java,
                table.id,
            ),
        )

    fun leaseRows(): Int = requireNotNull(LakeTestDb.jdbc.queryForObject("SELECT count(*) FROM lake_leases", Int::class.java))

    /** The catalog's clock. */
    fun dbNow(): Instant = leases.catalogNowPlus(Duration.ZERO)

    /** Polls the catalog's clock until it is past [instant], at most [WAIT_SECONDS]. */
    fun awaitDbPast(instant: Instant) {
        val end = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos()
        while (!dbNow().isAfter(instant)) {
            check(System.nanoTime() < end) { "the catalog clock did not pass $instant" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Polls `pg_stat_activity` on its own connection until a backend waits on [waitEventType], at most [WAIT_SECONDS]. */
    fun awaitWaiter(waitEventType: String): Boolean {
        val end = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos()
        LakeTestDb.connect().use { probe ->
            while (System.nanoTime() < end) {
                if (waiters(probe, waitEventType) > 0) return true
                Thread.sleep(POLL_MILLIS)
            }
        }
        return false
    }

    private fun waiters(
        probe: Connection,
        waitEventType: String,
    ): Int =
        probe.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = ? AND pid <> pg_backend_pid()").use { st ->
            st.setString(1, waitEventType)
            st.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    companion object {
        const val NAMESPACE = "maint"
        const val MARKER = "dp.test.marker."
        const val LOCK_TIMEOUT_MILLIS = 500L
        const val WAIT_SECONDS = 10L
        const val POLL_MILLIS = 20L

        val NO_SETTLEMENT = MaintenanceSettlement { SettlementRecord(0, 0, leftUnsettled = false, deferred = false) }
    }
}

/**
 * A test part: it declares what it is told, answers [due] after admission, and when run commits ONE marker property
 * (`dp.test.marker.<name>`) through [MaintenanceRun.commit], answering that commit's outcome. [before] runs first;
 * [started] says whether [run] was ever called.
 */
internal class MarkerPart(
    override val name: String,
    override val order: Int,
    private val declaration: MaintenanceDeclaration = MaintenanceDeclaration(expectsDue = true, scratchBytes = 0),
    private val due: Boolean = true,
    private val trace: MutableList<String>? = null,
    private val commits: Boolean = true,
    private val before: (MaintenanceRun) -> Unit = {},
) : MaintenancePart {
    val started = AtomicBoolean(false)

    override fun declare(table: MaintenanceTable): MaintenanceDeclaration {
        trace?.add("declare:$name")
        return declaration
    }

    override fun dueNow(run: MaintenanceRun): Boolean {
        trace?.add("due:$name")
        return due
    }

    override fun run(run: MaintenanceRun): PartResult {
        started.set(true)
        trace?.add("run:$name")
        before(run)
        if (!commits) return PartResult(MaintenanceOutcome.DONE)
        return when (val guarded = run.commit { table -> table.updateProperties().set(MaintenanceHarness.MARKER + name, run.runId.toString()) }) {
            is Guarded.Done -> PartResult(MaintenanceOutcome.DONE)
            is Guarded.Refused -> PartResult(guarded.outcome)
        }
    }
}
