package co.datapipelines.lake.definitions

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V1
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V2
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.iceberg.aws.s3.S3FileIO
import org.apache.iceberg.exceptions.NotFoundException
import org.apache.iceberg.io.FileIO
import org.apache.iceberg.io.InputFile
import org.apache.iceberg.io.OutputFile
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import java.io.IOException
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * #822: every failure of an apply attempt after the release's landing committed is RETURNED as the release's apply
 * outcome, never thrown (dp-lake design record §7, erratum 2026-11-12). One case per call site, each fault raised
 * where production meets it: a Postgres trigger refuses the metadata-store statement, the catalog's object-store
 * client refuses the read or the session open, the catalog clock's query fails. A catalog session's close is the one
 * fault raised through a seam ([LakeApplyHooks.afterSessionClose]): Iceberg's `JdbcCatalog.close()` suppresses its own
 * close failures. Each case asserts (1) the release returns, (2) the version is RELEASED, (3) the outcome, (4) the
 * row's `apply_state`, (5) one ERROR line per converted failure naming its `step`, by class and SQLSTATE only.
 */
@Suppress("LargeClass") // one case per call site of the brief's section A, and their shared fault seams
class LakeApplyFaultIntegrationTest {
    private val hooks = FaultHooks()
    private val store = FaultyStore()
    private val clock = FaultyCatalogClock(LakeTestDb.dataSource)
    private val h =
        LakeDefinitionsHarness(
            hooks = hooks,
            fileIoFactory = store::wrap,
            leases = LakeLeases(clock, holderInstance = "lake-test-822"),
        )
    private val lines = ListAppender<ILoggingEvent>() // raw-list-appender: synchronous — only test thread logs
    private val applierLog = LoggerFactory.getLogger(LakeDefinitionApplier::class.java) as Logger

    @BeforeEach
    fun clean() {
        dropTriggers()
        h.reset()
        lines.start()
        applierLog.addAppender(lines)
    }

    @AfterEach
    fun detach() {
        applierLog.detachAppender(lines)
        dropTriggers()
    }

    @Test
    fun `path 0 - the deadline read fails after the landing - Pending, no lease taken, the row as the release left it`() {
        val id = appliedV1()
        val before = h.table(id).applyOperationId
        val version = draftV2(id)
        clock.armed = true
        val result = released(id, version)
        clock.raised.get() shouldBe 1
        expect(id, result, PENDING, LakeApplyState.PENDING, Step("deadline", "DataAccessResourceFailureException", "08006"))
        h.table(id).applyOperationId shouldBe before
        h.revisions(NS, T) shouldBe 1
        leaseRows() shouldBe 0
    }

    @Test
    fun `path 1 - the object store fails while the table's metadata loads - Pending, the row put back`() {
        val id = appliedV1()
        store.failMetadataReads = true
        val result = releaseV2(id)
        store.failMetadataReads = false
        store.readsRefused.get() shouldBe 1
        expect(id, result, PENDING, LakeApplyState.PENDING, Step("pre_commit", "NotFoundException"))
        h.revisions(NS, T) shouldBe 1
    }

    @Test
    fun `path 2 - the table's catalog session fails to open - Pending, nothing committed`() {
        val id = appliedV1()
        val version = draftV2(id)
        store.failNextOpen = true
        val result = released(id, version)
        store.opensRefused.get() shouldBe 1
        expect(id, result, PENDING, LakeApplyState.PENDING, Step("session_open", "IllegalStateException"))
        h.revisions(NS, T) shouldBe 1
    }

    @Test
    fun `path 3a - the metadata store refuses the table lease - Pending, the row as the release left it`() {
        val id = appliedV1()
        val version = draftV2(id)
        DbFault.LEASE_TAKE.install()
        val result = released(id, version)
        expect(id, result, PENDING, LakeApplyState.PENDING, Step.trigger("metadata_store"))
        leaseRows() shouldBe 0
    }

    @Test
    fun `path 3b - the applying mark is refused - Unknown, the mark never landed`() {
        val id = appliedV1()
        val version = draftV2(id)
        DbFault.MARK_APPLYING.install()
        val result = released(id, version)
        expect(id, result, UNKNOWN, LakeApplyState.PENDING, Step.trigger("metadata_store"))
        leaseRows() shouldBe 0
    }

    @Test
    fun `path 3b - the lease renewal before the commit is refused - Unknown, the row left applying`() {
        val id = appliedV1()
        val version = draftV2(id)
        DbFault.LEASE_RENEW.install()
        val result = released(id, version)
        expect(id, result, UNKNOWN, LakeApplyState.APPLYING, Step.trigger("metadata_store"))
        h.revisions(NS, T) shouldBe 1
    }

    @Test
    fun `path 3b - the applied stamp is refused after the commit landed - Unknown, the row left applying for verify`() {
        val id = appliedV1()
        val version = draftV2(id)
        DbFault.STAMP_APPLIED.install()
        val result = released(id, version)
        expect(id, result, UNKNOWN, LakeApplyState.APPLYING, Step.trigger("metadata_store"))
        h.revisions(NS, T) shouldBe 2
        dropTriggers()
        h.verifyApplying(id) shouldBe LakeApplyOutcome.Applied(2, committed = true)
    }

    @Test
    fun `path 3b - the failed stamp of a drift verdict is refused - Unknown, the row left applying`() {
        val id = appliedV1()
        handEdit()
        val version = draftV2(id)
        DbFault.STAMP_FAILED.install()
        val result = released(id, version)
        expect(id, result, UNKNOWN, LakeApplyState.APPLYING, Step.trigger("metadata_store"))
        h.revisions(NS, T) shouldBe 2
    }

    @Test
    fun `path 3c - the leases' release fails - the applied outcome stands`() {
        val id = appliedV1()
        val version = draftV2(id)
        DbFault.LEASE_RELEASE.install()
        val result = released(id, version)
        expect(id, result, LakeApplyOutcome.Applied(2, committed = true), LakeApplyState.APPLIED, Step.trigger("lease_release"))
        h.revisions(NS, T) shouldBe 2
    }

    @Test
    fun `path 3c - the table's catalog session fails to close - the applied outcome stands`() {
        val id = appliedV1()
        hooks.failClose = LeaseKind.TABLE
        val result = releaseV2(id)
        hooks.closesFailed.get() shouldBe 1
        expect(
            id,
            result,
            LakeApplyOutcome.Applied(2, committed = true),
            LakeApplyState.APPLIED,
            Step("session_close", "IllegalStateException"),
        )
    }

    @Test
    fun `path 3d - the namespace's catalog session fails to close - Pending, the attempt stops before the table`() {
        val v1 = h.service.createDraft(NS, T, ORDERS_V1, h.actor, WriteSurface.SESSION)
        hooks.failClose = LeaseKind.NAMESPACE
        val result = released(v1.tableId, 1)
        hooks.closesFailed.get() shouldBe 1
        expect(v1.tableId, result, PENDING, LakeApplyState.PENDING, Step("session_close", "IllegalStateException"), version = 1)
        h.revisions(NS, T) shouldBe 0
        h.jdbc.queryForObject("SELECT count(*) FROM iceberg_tables WHERE table_namespace = ?", Int::class.java, NS) shouldBe 0
    }

    @Test
    fun `path 4 - a non-runtime exception after the commit landed - Unknown, never thrown, verify finds the revision`() {
        val id = appliedV1()
        hooks.afterCommitFault = IOException(FAULT)
        val result = releaseV2(id)
        expect(id, result, UNKNOWN, LakeApplyState.APPLYING, Step("commit", "IOException"))
        h.revisions(NS, T) shouldBe 2
        h.verifyApplying(id) shouldBe LakeApplyOutcome.Applied(2, committed = true)
    }

    @Test
    fun `combined - a pre-commit failure and a failing lease release - the pre-commit outcome survives, two ERROR lines`() {
        val id = appliedV1()
        val version = draftV2(id)
        store.failMetadataReads = true
        DbFault.LEASE_RELEASE.install()
        val result = released(id, version)
        store.failMetadataReads = false
        expect(
            id,
            result,
            PENDING,
            LakeApplyState.PENDING,
            Step("pre_commit", "NotFoundException"),
            Step.trigger("lease_release"),
        )
    }

    @Test
    fun `combined - a landed commit and a failing table session close - Applied survives`() {
        val id = appliedV1()
        hooks.failClose = LeaseKind.TABLE
        val result = releaseV2(id)
        expect(
            id,
            result,
            LakeApplyOutcome.Applied(2, committed = true),
            LakeApplyState.APPLIED,
            Step("session_close", "IllegalStateException"),
        )
        h.revisions(NS, T) shouldBe 2
    }

    @Test
    fun `an Error from the commit step is never caught - it escapes the release`() {
        val id = appliedV1()
        hooks.afterCommitFault = InjectedError()
        val version = draftV2(id)
        shouldThrow<InjectedError> { h.service.release(id, version, h.actor, WriteSurface.SESSION) }
        lines.list.filter { it.level == Level.ERROR }.shouldBeEmpty()
        requireNotNull(h.definitions.findVersion(id, version)).status shouldBe PipelineVersionStatus.RELEASED
    }

    /** One converted failure's ERROR line: where it was caught, the failure's class and its SQLSTATE. */
    private data class Step(
        val step: String,
        val cause: String,
        val sqlState: String = "none",
    ) {
        companion object {
            /** A trigger's `RAISE EXCEPTION` (P0001), as Spring's JDBC layer surfaces it. */
            fun trigger(step: String) = Step(step, TRIGGER_CAUSE, "P0001")
        }
    }

    /**
     * The five assertions of every case. [result] already proved (1); the version is RELEASED (2); the outcome is
     * [outcome] (3); the row is [state] (4); the ERROR lines are exactly [steps], in order, before the attempt's ending
     * line, each by class and SQLSTATE only (5).
     */
    private fun expect(
        id: UUID,
        result: LakeReleaseResult,
        outcome: LakeApplyOutcome,
        state: LakeApplyState,
        vararg steps: Step,
        version: Int = 2,
    ) {
        println("(822) outcome = $result; row = ${h.table(id).applyState}")
        requireNotNull(h.definitions.findVersion(id, version)).status shouldBe PipelineVersionStatus.RELEASED
        result shouldBe LakeReleaseResult(version, outcome)
        h.table(id).applyState shouldBe state
        val errors = lines.list.filter { it.level == Level.ERROR }
        errors.forEach { println("(822) ERROR line: ${it.formattedMessage}") }
        errors.map { it.formattedMessage.substringAfter(" step=", "").substringBefore(' ') } shouldBe steps.map { it.step }
        errors.zip(steps).forEach { (line, step) ->
            line.formattedMessage shouldContain "event=lake.definition.apply_unclassified "
            line.formattedMessage shouldContain " step=${step.step} cause=${step.cause} sql_state=${step.sqlState}"
            line.formattedMessage shouldNotContain FAULT
            line.throwableProxy shouldBe null
        }
        val ended = lines.list.last()
        ended.formattedMessage.substringBefore(' ') shouldBe "event=${endingEvent(outcome)}"
    }

    private fun endingEvent(outcome: LakeApplyOutcome): String =
        when (outcome) {
            is LakeApplyOutcome.Applied -> "lake.definition.applied"
            is LakeApplyOutcome.Pending -> "lake.definition.apply_deferred"
            is LakeApplyOutcome.Unknown -> "lake.definition.apply_unknown"
            else -> error("no case expects $outcome")
        }

    private fun appliedV1(): UUID {
        val v1 = h.service.createDraft(NS, T, ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.service.release(v1.tableId, 1, h.actor, WriteSurface.SESSION).apply shouldBe LakeApplyOutcome.Applied(1, committed = true)
        lines.list.clear()
        return v1.tableId
    }

    private fun draftV2(id: UUID): Int {
        val v1Hash = requireNotNull(h.definitions.findVersion(id, 1)).bodyHash
        return h.service.writeDraft(id, ORDERS_V2, v1Hash, h.actor, WriteSurface.SESSION).version
    }

    /** Assertion (1): the release RETURNS — an exception escaping it fails the case here, whatever it is. */
    private fun released(
        id: UUID,
        version: Int,
    ): LakeReleaseResult = shouldNotThrowAny { h.service.release(id, version, h.actor, WriteSurface.SESSION) }

    private fun releaseV2(id: UUID): LakeReleaseResult = released(id, draftV2(id))

    private fun leaseRows(): Int = requireNotNull(h.jdbc.queryForObject("SELECT count(*) FROM lake_leases", Int::class.java))

    /** Changes the table outside DP, under a foreign lease: an optional `hand_added` column — drift for the next apply. */
    private fun handEdit() {
        val foreign = LakeTestDb.lease(LeaseKind.TABLE, TABLE_LEASE)
        h.catalogs.open(foreign).use { s ->
            s.write {
                s
                    .loadTable(NS, T)
                    .updateSchema()
                    .addColumn("hand_added", Types.StringType.get())
                    .commit()
            }
        }
        LakeTestDb.leases.release(foreign.operationId)
    }

    private fun dropTriggers() {
        DbFault.entries
            .map { it.table }
            .distinct()
            .forEach { h.jdbc.execute("DROP TRIGGER IF EXISTS $TRIGGER ON $it") }
    }

    /**
     * A metadata-store statement the database refuses: a row trigger raising P0001, scoped to this class's own table
     * (namespace `f822`), so a trigger a killed run left behind refuses no other suite's statement.
     */
    private enum class DbFault(
        val table: String,
        val timing: String,
        val condition: String,
    ) {
        LEASE_TAKE("lake_leases", "BEFORE INSERT", "NEW.name = '$TABLE_LEASE'"),
        LEASE_RENEW("lake_leases", "BEFORE UPDATE", "OLD.name = '$TABLE_LEASE'"),
        LEASE_RELEASE("lake_leases", "BEFORE DELETE", "OLD.name = '$TABLE_LEASE'"),
        MARK_APPLYING("lake_tables_managed", "BEFORE UPDATE", "NEW.name = '$T' AND NEW.apply_state = 'applying'"),
        STAMP_APPLIED("lake_tables_managed", "BEFORE UPDATE", "NEW.name = '$T' AND NEW.apply_state = 'applied'"),
        STAMP_FAILED("lake_tables_managed", "BEFORE UPDATE", "NEW.name = '$T' AND NEW.apply_state = 'failed'"),
        ;

        fun install() {
            LakeTestDb.jdbc.execute(
                "CREATE OR REPLACE FUNCTION $TRIGGER() RETURNS trigger LANGUAGE plpgsql AS " +
                    "\$\$ BEGIN RAISE EXCEPTION '$FAULT'; END \$\$",
            )
            LakeTestDb.jdbc.execute("CREATE TRIGGER $TRIGGER $timing ON $table FOR EACH ROW WHEN ($condition) EXECUTE FUNCTION $TRIGGER()")
        }
    }

    /** The two apply seams: an after-commit fault (any Throwable) and one failing catalog-session close of a kind. */
    private class FaultHooks : LakeApplyHooks {
        @Volatile var afterCommitFault: Throwable? = null

        @Volatile var failClose: LeaseKind? = null
        val closesFailed = AtomicInteger()

        override fun afterCommit(
            tableId: UUID,
            operationId: UUID,
        ) {
            afterCommitFault?.let {
                afterCommitFault = null
                throw it
            }
        }

        override fun afterSessionClose(lease: LeaseHandle) {
            if (failClose == lease.kind) {
                failClose = null
                closesFailed.incrementAndGet()
                error(FAULT)
            }
        }
    }

    /**
     * The catalog's object-store client with two faults: [failNextOpen] refuses the next session's client (the catalog
     * cannot initialise), [failMetadataReads] refuses every table-metadata read while set. The read fails with Iceberg's
     * `NotFoundException`, the one its metadata refresh does not retry (twenty retries would only slow the case).
     */
    private class FaultyStore {
        @Volatile var failNextOpen = false

        @Volatile var failMetadataReads = false
        val opensRefused = AtomicInteger()
        val readsRefused = AtomicInteger()

        fun wrap(props: Map<String, String>): FileIO {
            if (failNextOpen) {
                failNextOpen = false
                opensRefused.incrementAndGet()
                error(FAULT)
            }
            return Io(S3FileIO().apply { initialize(props) })
        }

        private inner class Io(
            private val delegate: FileIO,
        ) : FileIO {
            override fun newInputFile(path: String): InputFile = delegate.newInputFile(refused(path))

            override fun newInputFile(
                path: String,
                length: Long,
            ): InputFile = delegate.newInputFile(refused(path), length)

            override fun newOutputFile(path: String): OutputFile = delegate.newOutputFile(path)

            override fun deleteFile(path: String) = delegate.deleteFile(path)

            override fun properties(): Map<String, String> = delegate.properties()

            override fun initialize(properties: Map<String, String>) = Unit

            override fun close() = delegate.close()

            private fun refused(path: String): String {
                if (failMetadataReads && path.endsWith(METADATA_SUFFIX)) {
                    readsRefused.incrementAndGet()
                    throw NotFoundException(FAULT)
                }
                return path
            }
        }
    }

    /**
     * The lease protocol's JDBC with one fault: when [armed], the next catalog-clock read (`LakeLeases.catalogNowPlus`)
     * fails as a lost connection does — the release's deadline read, armed between the draft and the release.
     */
    private class FaultyCatalogClock(
        dataSource: DataSource,
    ) : JdbcTemplate(dataSource) {
        @Volatile var armed = false
        val raised = AtomicInteger()

        override fun <T : Any?> queryForObject(
            sql: String,
            requiredType: Class<T>,
            vararg args: Any?,
        ): T? {
            if (armed && sql.startsWith(CLOCK_SQL)) {
                armed = false
                raised.incrementAndGet()
                throw DataAccessResourceFailureException(FAULT, SQLException(FAULT, "08006"))
            }
            return super.queryForObject(sql, requiredType, *args)
        }
    }

    private class InjectedError : Error(FAULT)

    private companion object {
        const val NS = "f822"
        const val T = "f822_orders"
        const val TABLE_LEASE = "$NS.$T"
        const val TRIGGER = "lane822_fault"
        const val METADATA_SUFFIX = ".metadata.json"
        const val CLOCK_SQL = "SELECT now() + make_interval"
        const val FAULT = "injected 822 fault"

        /** Spring's translation of a PL/pgSQL `RAISE EXCEPTION` (SQLSTATE P0001, no specific mapping). */
        const val TRIGGER_CAUSE = "UncategorizedSQLException"
        val PENDING = LakeApplyOutcome.Pending(PipelineErrorCodes.Lake.DEFINITION_APPLY_UNCLASSIFIED)
        val UNKNOWN = LakeApplyOutcome.Unknown(PipelineErrorCodes.Lake.DEFINITION_APPLY_UNCLASSIFIED)
    }
}
