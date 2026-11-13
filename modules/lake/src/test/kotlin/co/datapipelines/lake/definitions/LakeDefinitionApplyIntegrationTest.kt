package co.datapipelines.lake.definitions

import co.datapipelines.lake.LakeTableProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V1
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V2
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.WriteSurface
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.iceberg.Table
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The apply state machine (lake.md §3, dp-lake design record §7) end to end: V53's fence and revision log, Iceberg's
 * JdbcCatalog through [co.datapipelines.lake.LakeCatalogs], MinIO. Cases (i)–(vi) are the brief's; each prints the
 * numbers it rests on (revision counts, field-id sets, the drift diff) so a vacuous pass is visible in the log.
 */
class LakeDefinitionApplyIntegrationTest {
    private val crash = CrashOnce()
    private val h = LakeDefinitionsHarness(hooks = crash)

    @BeforeEach
    fun clean() {
        h.reset()
        crash.armed = false
    }

    @Test
    fun `(i) release v1 creates the namespace and table, version 1, one revision, applied`() {
        val id = createAndRelease()
        val revisions = h.revisions(NS, T)
        println("(i) revisions after v1 = $revisions")
        revisions shouldBe 1
        val table = load()
        table.properties()[LakeTableProperties.DEFINITION_VERSION] shouldBe "1"
        table.location() shouldBe "s3://${LakeDefinitionsHarness.BUCKET}/lake/$NS/$id"
        LakeTableShape.of(table) shouldBe LakeTableShape.of(LakeDefinitionBodies.parse(ORDERS_V1, null, null))
        h.table(id).let { (it.applyState to it.appliedVersionHere) shouldBe (LakeApplyState.APPLIED to 1) }
        h.audit.events.map { it.event } shouldContainExactly listOf(LakeAuditEvents.RELEASED, LakeAuditEvents.APPLIED)
        h.audit.events.all { it.inTransaction } shouldBe true
        h.audit.events
            .last()
            .details["operation_id"]
            .toString()
            .length shouldBe UUID_LENGTH
    }

    @Test
    fun `(ii) v2 adding an optional column is exactly one more revision and reads back as intent(v2) by field id`() {
        val id = createAndRelease()
        val before = h.revisions(NS, T)
        val outcome = releaseNext(id, ORDERS_V2)
        val after = h.revisions(NS, T)
        val fieldIds = load().schema().columns().map { it.fieldId() }
        println("(ii) revisions $before -> $after; field ids = $fieldIds")
        outcome shouldBe LakeApplyOutcome.Applied(2, committed = true)
        after shouldBe before + 1
        fieldIds shouldBe listOf(1, 2, 3, 4, 5)
        LakeTableShape.of(load()) shouldBe LakeTableShape.of(LakeDefinitionBodies.parse(ORDERS_V2, null, null))
        load().schema().findField(5).doc() shouldBe "sales channel"
    }

    @Test
    fun `a version that changes no column (tags only) applies - the version property is its one commit`() {
        val id = createAndRelease()
        val tagsOnly = ORDERS_V1.replace("[\"pii\"]", "[\"pii\",\"contact\"]")
        check(tagsOnly != ORDERS_V1) { "the fixture's tags spelling changed" }
        val before = h.revisions(NS, T)
        val outcome = releaseNext(id, tagsOnly)
        println("(tags-only) revisions $before -> ${h.revisions(NS, T)}; outcome = $outcome")
        outcome shouldBe LakeApplyOutcome.Applied(2, committed = true)
        h.revisions(NS, T) shouldBe before + 1
        load().properties()[LakeTableProperties.DEFINITION_VERSION] shouldBe "2"
        h.jdbc.queryForObject(
            "SELECT tags_json::text FROM lake_column_semantics WHERE table_id = ? AND field_id = 2",
            String::class.java,
            id,
        ) shouldBe "[\"pii\", \"contact\"]"
    }

    @Test
    fun `(iii) a crash between commit and stamp leaves applying - the retry finds S == intent and adds no revision`() {
        val id = createAndRelease()
        crash.armed = true
        releaseNext(id, ORDERS_V2) shouldBe LakeApplyOutcome.Unknown(PipelineErrorCodes.Lake.DEFINITION_APPLY_UNCLASSIFIED)
        crash.armed shouldBe false
        val afterCrash = h.revisions(NS, T)
        h.table(id).applyState shouldBe LakeApplyState.APPLYING
        val retry = h.applier.apply(id, 2, LakeTestDb.leases.catalogNowPlus(Duration.ofMinutes(1)), h.actor)
        val afterRetry = h.revisions(NS, T)
        val fieldIds = load().schema().columns().map { it.fieldId() }
        println("(iii) revisions after crash = $afterCrash, after retry = $afterRetry; field ids = $fieldIds")
        afterCrash shouldBe 2
        afterRetry shouldBe afterCrash
        retry shouldBe LakeApplyOutcome.Applied(2, committed = false)
        fieldIds shouldBe listOf(1, 2, 3, 4, 5)
        h.table(id).let { (it.applyState to it.appliedVersionHere) shouldBe (LakeApplyState.APPLIED to 2) }
    }

    @Test
    fun `(iv) a foreign hand-edit is drift - failed with a diff naming the field, zero commits`() {
        val id = createAndRelease()
        handEdit()
        val before = h.revisions(NS, T)
        val outcome = releaseNext(id, ORDERS_V2)
        val after = h.revisions(NS, T)
        println("(iv) revisions $before -> $after; outcome = $outcome")
        outcome.shouldBeInstanceOf<LakeApplyOutcome.Failed>()
        outcome.code shouldBe PipelineErrorCodes.Lake.DEFINITION_DRIFT
        after shouldBe before
        val stored = requireNotNull(h.table(id).applyErrorJson)
        println("(iv) apply_error_json = $stored")
        stored shouldContain "hand_added"
        stored shouldContain "\"code\": \"lake.definition.drift\""
        h.table(id).let { (it.applyState to it.appliedVersionHere) shouldBe (LakeApplyState.FAILED to 1) }
        h.audit.events.last().let {
            (it.event to it.details["code"]) shouldBe
                (LakeAuditEvents.APPLY_FAILED to PipelineErrorCodes.Lake.DEFINITION_DRIFT)
        }
    }

    @Test
    fun `drift has an exit - the live shape adopted as the next version applies as one property-only commit`() {
        val id = createAndRelease()
        handEdit()
        val drifted = load()
        val before = h.revisions(NS, T)
        val outcome = releaseNext(id, ADOPTED)
        val adopted = load()
        println(
            "(drift exit) revisions $before -> ${h.revisions(NS, T)}; outcome = $outcome; schema id ${drifted.schema().schemaId()} -> " +
                "${adopted.schema().schemaId()}, spec id ${drifted.spec().specId()} -> ${adopted.spec().specId()}, snapshot " +
                "${drifted.currentSnapshot()?.snapshotId()} -> ${adopted.currentSnapshot()?.snapshotId()}",
        )
        outcome shouldBe LakeApplyOutcome.Applied(2, committed = true)
        h.revisions(NS, T) shouldBe before + 1
        adopted.properties()[LakeTableProperties.DEFINITION_VERSION] shouldBe "2"
        // A property-only commit: the schema, the spec and the data are exactly the ones the hand-edit left.
        adopted.schema().schemaId() shouldBe drifted.schema().schemaId()
        adopted.schemas().keys shouldBe drifted.schemas().keys
        adopted.spec().specId() shouldBe drifted.spec().specId()
        adopted.currentSnapshot()?.snapshotId() shouldBe drifted.currentSnapshot()?.snapshotId()
        LakeTableShape.of(adopted) shouldBe LakeTableShape.of(drifted)
        h.table(id).let { (it.applyState to it.appliedVersionHere) shouldBe (LakeApplyState.APPLIED to 2) }
        h.audit.events
            .last()
            .event shouldBe LakeAuditEvents.APPLIED
    }

    @Test
    fun `after the drift exit, a version that changes the table takes the normal path - classified, staged, committed`() {
        val id = createAndRelease()
        handEdit()
        releaseNext(id, ADOPTED) shouldBe LakeApplyOutcome.Applied(2, committed = true)
        val adopted = load()
        val before = h.revisions(NS, T)
        val outcome = releaseNext(id, ADOPTED_PLUS_NOTE)
        val after = load()
        println(
            "(drift exit, next) revisions $before -> ${h.revisions(NS, T)}; outcome = $outcome; field ids = " +
                "${after.schema().columns().map {
                    it.fieldId()
                }}; schema id ${adopted.schema().schemaId()} -> ${after.schema().schemaId()}",
        )
        outcome shouldBe LakeApplyOutcome.Applied(3, committed = true)
        h.revisions(NS, T) shouldBe before + 1
        LakeTableShape.of(after) shouldBe LakeTableShape.of(LakeDefinitionBodies.parse(ADOPTED_PLUS_NOTE, null, null))
        after.schema().schemaId() shouldNotBe adopted.schema().schemaId()
        after.properties()[LakeTableProperties.DEFINITION_VERSION] shouldBe "3"
    }

    @Test
    fun `drift found by a failed release - discard it, adopt the live shape from the applied version, and the release applies`() {
        val id = createAndRelease()
        handEdit()
        releaseNext(id, ORDERS_V2).code shouldBe PipelineErrorCodes.Lake.DEFINITION_DRIFT
        // The rejected v2 holds field id 5 for `channel`; the live table's 5 is `hand_added`, so the adoption builds on
        // the applied v1 once v2 is discarded (the field-ID limitation of #820).
        h.service.discard(id, 2, h.actor, WriteSurface.SESSION)
        h.table(id).currentDefinitionVersion shouldBe 1
        val outcome = releaseNext(id, ADOPTED)
        println("(drift found by release) outcome = $outcome; table = ${h.table(id).applyState}")
        outcome shouldBe LakeApplyOutcome.Applied(3, committed = true)
        load().properties()[LakeTableProperties.DEFINITION_VERSION] shouldBe "3"
        h.table(id).let { (it.applyState to it.appliedVersionHere) shouldBe (LakeApplyState.APPLIED to 3) }
    }

    @Test
    fun `(iv-b) a REVIEWED change is refused with no commit and the change named`() {
        val id = createAndRelease()
        val renamed = ORDERS_V1.replace("\"customer\"", "\"client\"")
        val before = h.revisions(NS, T)
        val outcome = releaseNext(id, renamed)
        outcome.code shouldBe PipelineErrorCodes.Lake.DEFINITION_REVIEWED_CHANGE
        h.revisions(NS, T) shouldBe before
        requireNotNull(h.table(id).applyErrorJson) shouldContain "rename"
    }

    @Test
    fun `(v) verifyApplying - an applying row with no revision goes pending, with a revision goes applied`() {
        val id = createAndRelease()
        val v2 =
            h.service.writeDraft(
                id,
                ORDERS_V2,
                requireNotNull(h.definitions.findVersion(id, 1)).bodyHash,
                h.actor,
                WriteSurface.SESSION,
            )
        h.landWithoutApply(id, v2.version)
        val dead = UUID.randomUUID()
        h.applies.markApplying(id, 2, dead) shouldBe 1
        val before = h.revisions(NS, T)
        val none = h.verifyApplying(id)
        println("(v) no-revision verify: revisions $before -> ${h.revisions(NS, T)}, outcome = $none")
        none shouldBe LakeApplyOutcome.Pending(null)
        h.table(id).applyState shouldBe LakeApplyState.PENDING
        h.revisions(NS, T) shouldBe before

        crash.armed = true
        h.applier.apply(id, 2, deadline(), h.actor) shouldBe LakeApplyOutcome.Unknown(PipelineErrorCodes.Lake.DEFINITION_APPLY_UNCLASSIFIED)
        crash.armed shouldBe false
        h.table(id).applyState shouldBe LakeApplyState.APPLYING
        val committed = h.revisions(NS, T)
        val verified = h.verifyApplying(id)
        println("(v) revision verify: revisions $before -> $committed -> ${h.revisions(NS, T)}, outcome = $verified")
        verified shouldBe LakeApplyOutcome.Applied(2, committed = true)
        committed shouldBe before + 1
        h.revisions(NS, T) shouldBe committed
        h.table(id).let { (it.applyState to it.appliedVersionHere) shouldBe (LakeApplyState.APPLIED to 2) }
    }

    @Test
    fun `(v-b) verifyApplying leaves a row whose table lease is live`() {
        val id = createAndRelease()
        val v2 =
            h.service.writeDraft(
                id,
                ORDERS_V2,
                requireNotNull(h.definitions.findVersion(id, 1)).bodyHash,
                h.actor,
                WriteSurface.SESSION,
            )
        h.landWithoutApply(id, v2.version)
        val live = LakeTestDb.lease(LeaseKind.TABLE, "$NS.$T")
        h.applies.markApplying(id, 2, live.operationId) shouldBe 1
        h.verifyApplying(id) shouldBe LakeApplyOutcome.Busy(LakeApplyLeases.REASON_TABLE)
        h.table(id).applyState shouldBe LakeApplyState.APPLYING
        LakeTestDb.leases.release(live.operationId)
    }

    @Test
    fun `(vi) a table lease held by another operation is table_busy after the wait - nothing changed`() {
        val v1 = h.service.createDraft(NS, T, ORDERS_V1, h.actor, WriteSurface.SESSION)
        val foreign = LakeTestDb.lease(LeaseKind.TABLE, "$NS.$T")
        val started = System.nanoTime()
        val result = h.service.release(v1.tableId, 1, h.actor, WriteSurface.SESSION)
        val waitedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis()
        println("(vi) outcome = ${result.apply}, waited ${waitedMillis}ms")
        result.apply shouldBe LakeApplyOutcome.Busy(LakeApplyLeases.REASON_TABLE)
        result.apply.code shouldBe PipelineErrorCodes.Lake.DEFINITION_TABLE_BUSY
        (waitedMillis >= MIN_WAIT_MILLIS) shouldBe true
        h.table(v1.tableId).let { (it.applyState to it.applyOperationId) shouldBe (LakeApplyState.PENDING to null) }
        h.revisions(NS, T) shouldBe 0
        h.jdbc.queryForObject("SELECT count(*) FROM lake_leases WHERE operation <> 'test'", Int::class.java) shouldBe 0
        LakeTestDb.leases.release(foreign.operationId)
        h.applier.apply(v1.tableId, 1, deadline(), h.actor) shouldBe LakeApplyOutcome.Applied(1, committed = true)
    }

    @Test
    fun `the namespace lease is taken before the table lease when the namespace is new`() {
        val v1 = h.service.createDraft(NS, T, ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.landWithoutApply(v1.tableId, 1)
        val foreign = LakeTestDb.lease(LeaseKind.TABLE, "$NS.$T")
        val slow = LakeDefinitionsHarness(leaseWait = Duration.ofSeconds(3))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit<LakeApplyOutcome> { slow.applier.apply(v1.tableId, 1, deadline(), h.actor) }
            var namespaceHeldWhileWaiting = false
            val until = System.nanoTime() + Duration.ofSeconds(2).toNanos()
            while (!namespaceHeldWhileWaiting && System.nanoTime() < until) {
                namespaceHeldWhileWaiting =
                    h.jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM lake_leases WHERE kind = 'namespace' AND name = ? AND operation = ?)",
                        Boolean::class.java,
                        NS,
                        LakeDefinitionApplier.OPERATION_APPLY,
                    ) == true
                Thread.sleep(POLL_MILLIS)
            }
            println("(order) namespace lease held while waiting for the table lease = $namespaceHeldWhileWaiting")
            namespaceHeldWhileWaiting shouldBe true
            running.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe LakeApplyOutcome.Busy(LakeApplyLeases.REASON_TABLE)
        } finally {
            pool.shutdownNow()
            LakeTestDb.leases.release(foreign.operationId)
        }
    }

    @Test
    fun `a stale attempt's stamp changes nothing once a newer attempt owns the row`() {
        val v1 = h.service.createDraft(NS, T, ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.landWithoutApply(v1.tableId, 1)
        val old = UUID.randomUUID()
        val new = UUID.randomUUID()
        h.applies.markApplying(v1.tableId, 1, old) shouldBe 1
        h.applies.markApplying(v1.tableId, 1, new) shouldBe 1
        val stamped = h.tx.execute { h.applies.finishApplied(v1.tableId, 1, old) }
        val failed = h.tx.execute { h.applies.finishFailed(v1.tableId, 1, old, "{\"code\":\"x\"}") }
        val pending = h.applies.finishPending(v1.tableId, old)
        println("(stale) old attempt's stamps changed rows: applied=$stamped failed=$failed pending=$pending")
        listOf(stamped, failed, pending) shouldBe listOf(0, 0, 0)
        h.table(v1.tableId).let { (it.applyState to it.applyOperationId) shouldBe (LakeApplyState.APPLYING to new) }
        h.table(v1.tableId).appliedVersionHere shouldBe null
    }

    @Test
    fun `the applied stamp rebuilds the semantics projection and, with the lake datasource, the registry row`() {
        val id = createAndRelease()
        h.jdbc.queryForObject("SELECT count(*) FROM lake_tables", Int::class.java) shouldBe 0
        h.registerLakeDatasource()
        releaseNext(id, ORDERS_V2) shouldBe LakeApplyOutcome.Applied(2, committed = true)
        val semantics =
            h.jdbc.queryForList(
                "SELECT field_id, ordinal, name, logical_type, iceberg_type, required, doc, tags_json::text AS tags, applied_version " +
                    "FROM lake_column_semantics WHERE table_id = ? ORDER BY ordinal",
                id,
            )
        semantics.map { it["field_id"] } shouldBe listOf(1, 2, 3, 4, 5)
        semantics[1].let { (it["name"] to it["tags"]) shouldBe ("customer" to "[\"pii\"]") }
        semantics[2].let { (it["logical_type"] to it["iceberg_type"]) shouldBe ("DECIMAL(12,2)" to "decimal(12, 2)") }
        semantics.all { it["applied_version"] == 2 } shouldBe true
        val row =
            h.jdbc.queryForMap(
                "SELECT array_to_string(namespace, '.') AS ns, name, format, origin, revision_seq, location FROM lake_tables",
            )
        val current =
            h.jdbc.queryForMap(
                "SELECT seq, metadata_location FROM lake_table_revisions WHERE namespace = ? AND table_name = ? AND superseded_at IS NULL",
                NS,
                T,
            )
        (row["ns"] to row["name"]) shouldBe ("${h.properties.name}.$NS" to T)
        (row["format"] to row["origin"]) shouldBe ("iceberg" to "managed")
        (row["revision_seq"] to row["location"]) shouldBe (current["seq"] to current["metadata_location"])
        h.applies.upsertRegistryRow(h.properties.name, NS, T, h.actor) shouldBe 0
    }

    private fun createAndRelease(): UUID {
        val v1 = h.service.createDraft(NS, T, ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.service.release(v1.tableId, 1, h.actor, WriteSurface.SESSION).apply shouldBe LakeApplyOutcome.Applied(1, committed = true)
        return v1.tableId
    }

    private fun releaseNext(
        id: UUID,
        body: String,
    ): LakeApplyOutcome {
        val current = requireNotNull(h.definitions.findVersion(id, requireNotNull(h.table(id).currentDefinitionVersion)))
        val draft = h.service.writeDraft(id, body, current.bodyHash, h.actor, WriteSurface.SESSION)
        return h.service.release(id, draft.version, h.actor, WriteSurface.SESSION).apply
    }

    /** Changes the table outside DP, under a foreign lease: an optional `hand_added` string column (Iceberg gives it id 5). */
    private fun handEdit() {
        LakeTestDb.lease(LeaseKind.TABLE, "$NS.$T").let { foreign ->
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
    }

    private fun load(): Table {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "$NS.$T")
        try {
            return h.catalogs.open(lease).use { it.loadTable(NS, T) }
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    private fun deadline() = LakeTestDb.leases.catalogNowPlus(Duration.ofMinutes(1))

    /** Throws once, right after the commit, when armed — the crash-between case's injected fault. */
    private class CrashOnce : LakeApplyHooks {
        @Volatile var armed = false

        override fun afterCommit(
            tableId: UUID,
            operationId: UUID,
        ) {
            if (armed) {
                armed = false
                error(MESSAGE)
            }
        }

        companion object {
            const val MESSAGE = "injected crash between commit and stamp"
        }
    }

    private companion object {
        const val NS = "sales"
        const val T = "orders"

        val V1_FIELDS =
            listOf(
                LakeDefinitionFixtures.field(1, "id", "BIGINTEGER", "required" to true, "doc" to "order id"),
                LakeDefinitionFixtures.field(2, "customer", "STRING", "tags" to listOf("pii")),
                LakeDefinitionFixtures.field(3, "amount", "DECIMAL", "precision" to 12, "scale" to 2),
                LakeDefinitionFixtures.field(4, "ordered_at", "TIMESTAMP", "required" to true),
            )

        /** [handEdit]'s live shape written down as a definition: `orders` v1 + the hand-added column at id 5. */
        val ADOPTED: String =
            LakeDefinitionFixtures.body(
                V1_FIELDS + LakeDefinitionFixtures.field(5, "hand_added", "STRING"),
                partition = listOf(LakeDefinitionFixtures.partition(4, "day")),
                identifiers = listOf(1),
            )

        /** [ADOPTED] + an optional `note` column (id 6): a COMPATIBLE change after the drift exit. */
        val ADOPTED_PLUS_NOTE: String =
            LakeDefinitionFixtures.body(
                V1_FIELDS + LakeDefinitionFixtures.field(5, "hand_added", "STRING") + LakeDefinitionFixtures.field(6, "note", "STRING"),
                partition = listOf(LakeDefinitionFixtures.partition(4, "day")),
                identifiers = listOf(1),
            )
        const val UUID_LENGTH = 36
        const val MIN_WAIT_MILLIS = 900L
        const val POLL_MILLIS = 50L
        const val WAIT_SECONDS = 30L
    }
}
