package co.datapipelines.lake.definitions

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V1
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V2
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.iceberg.aws.s3.S3FileIO
import org.apache.iceberg.io.FileIO
import org.apache.iceberg.io.InputFile
import org.apache.iceberg.io.OutputFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * #811: an exception the catalog does not classify, raised in an apply's commit step AFTER the release's own
 * transaction committed. The release RETURNS it as its apply outcome (`Unknown`, `lake.definition.apply_unclassified`;
 * the landing stands, lake.md §3), the row stays `applying` under the attempt's operation id, and `verifyApplying`
 * settles it from the revision log: `pending` when nothing landed (a metadata write refused inside the commit),
 * `applied` when the commit had landed (a failure right after it). Each case prints the numbers it rests on.
 */
class LakeApplyUnclassifiedIntegrationTest {
    private val metadataWrites = FailingMetadataWrites()
    private val afterCommit = ThrowAfterCommit()
    private val h = LakeDefinitionsHarness(hooks = afterCommit, fileIoFactory = metadataWrites::wrap)
    private val lines = ListAppender<ILoggingEvent>()
    private val applierLog = LoggerFactory.getLogger(LakeDefinitionApplier::class.java) as Logger

    @BeforeEach
    fun clean() {
        h.reset()
        metadataWrites.armed = false
        afterCommit.armed = false
        lines.start()
        applierLog.addAppender(lines)
    }

    @AfterEach
    fun detach() {
        applierLog.detachAppender(lines)
    }

    @Test
    fun `an unclassified failure that committed nothing - release returns Unknown, verify finds no revision, pending`() {
        val id = appliedV1()
        val before = h.revisions(NS, T)
        metadataWrites.armed = true
        val result = releaseV2(id)
        val afterRelease = h.revisions(NS, T)
        println("(811 not landed) outcome = $result; metadata writes refused = ${metadataWrites.refused}")
        println("(811 not landed) revisions $before -> $afterRelease")
        metadataWrites.refused.get() shouldBe 1
        result shouldBe LakeReleaseResult(2, UNCLASSIFIED)
        afterRelease shouldBe before
        val attempt = landingStands(id)

        val verified = h.applier.verifyApplying(id, deadline())
        println("(811 not landed) verify = $verified; revisions ${h.revisions(NS, T)}")
        verified shouldBe LakeApplyOutcome.Pending(null)
        // `pending` keeps the last attempt's operation id (finishPending moves only the state).
        h.table(id).let { (it.applyState to it.applyOperationId) shouldBe (LakeApplyState.PENDING to attempt) }
        h.revisions(NS, T) shouldBe before
        h.applier.apply(id, 2, deadline(), h.actor) shouldBe LakeApplyOutcome.Applied(2, committed = true)
        h.revisions(NS, T) shouldBe before + 1
    }

    @Test
    fun `an unclassified failure after the commit landed - release returns Unknown, verify finds the revision, applied`() {
        val id = appliedV1()
        val before = h.revisions(NS, T)
        afterCommit.armed = true
        val result = releaseV2(id)
        val afterRelease = h.revisions(NS, T)
        println("(811 landed) outcome = $result; thrown after commit = ${afterCommit.thrown}; revisions $before -> $afterRelease")
        afterCommit.thrown.get() shouldBe 1
        result shouldBe LakeReleaseResult(2, UNCLASSIFIED)
        afterRelease shouldBe before + 1
        landingStands(id)

        val verified = h.applier.verifyApplying(id, deadline())
        println("(811 landed) verify = $verified; revisions ${h.revisions(NS, T)}")
        verified shouldBe LakeApplyOutcome.Applied(2, committed = true)
        h.table(id).let { (it.applyState to it.appliedVersionHere) shouldBe (LakeApplyState.APPLIED to 2) }
        h.revisions(NS, T) shouldBe afterRelease
        h.audit.events
            .last()
            .event shouldBe LakeAuditEvents.APPLIED
    }

    /**
     * The release's landing is committed and untouched by its apply's failure, the row is `applying` under the
     * attempt's operation id, and the failure was logged once at ERROR with its class and SQLSTATE only (never its
     * message or stack), before the attempt's one ending line. Answers the attempt's operation id.
     */
    private fun landingStands(id: UUID): UUID {
        requireNotNull(h.definitions.findVersion(id, 2)).status shouldBe PipelineVersionStatus.RELEASED
        val row = h.table(id)
        (row.currentDefinitionVersion to row.appliedVersionHere) shouldBe (2 to 1)
        row.applyState shouldBe LakeApplyState.APPLYING
        val attempt = requireNotNull(row.applyOperationId) { "the applying row names no operation id" }
        h.audit.events
            .last()
            .event shouldBe LakeAuditEvents.RELEASED

        val errors = lines.list.filter { it.level == Level.ERROR }
        errors shouldHaveSize 1
        val error = errors.single()
        println("(811) ERROR line: ${error.formattedMessage}")
        error.formattedMessage shouldContain "event=lake.definition.apply_unclassified "
        error.formattedMessage shouldContain "operation_id=$attempt "
        error.formattedMessage shouldContain "cause=IllegalStateException sql_state=none"
        error.formattedMessage shouldNotContain FAULT_MESSAGE
        error.throwableProxy shouldBe null
        val ended = lines.list.last()
        (ended.level to ended.formattedMessage.substringBefore(' ')) shouldBe (Level.WARN to "event=lake.definition.apply_unknown")
        ended.formattedMessage shouldContain "code=${PipelineErrorCodes.Lake.DEFINITION_APPLY_UNCLASSIFIED}"
        return attempt
    }

    private fun appliedV1(): UUID {
        val v1 = h.service.createDraft(NS, T, ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.service.release(v1.tableId, 1, h.actor, WriteSurface.SESSION).apply shouldBe LakeApplyOutcome.Applied(1, committed = true)
        lines.list.clear()
        return v1.tableId
    }

    private fun releaseV2(id: UUID): LakeReleaseResult {
        val v1Hash = requireNotNull(h.definitions.findVersion(id, 1)).bodyHash
        val draft = h.service.writeDraft(id, ORDERS_V2, v1Hash, h.actor, WriteSurface.SESSION)
        return h.service.release(id, draft.version, h.actor, WriteSurface.SESSION)
    }

    private fun deadline() = LakeTestDb.leases.catalogNowPlus(Duration.ofMinutes(1))

    /**
     * The catalog's object-store client with one fault: while [armed], the next table-metadata write is refused with
     * an `IllegalStateException` (none of the catalog's classified refusals), inside the commit and before the catalog
     * pointer moves, so nothing lands.
     */
    private class FailingMetadataWrites {
        @Volatile var armed = false
        val refused = AtomicInteger()

        fun wrap(props: Map<String, String>): FileIO = Io(S3FileIO().apply { initialize(props) })

        private inner class Io(
            private val delegate: FileIO,
        ) : FileIO {
            override fun newInputFile(path: String): InputFile = delegate.newInputFile(path)

            override fun newInputFile(
                path: String,
                length: Long,
            ): InputFile = delegate.newInputFile(path, length)

            override fun newOutputFile(path: String): OutputFile {
                if (armed && path.endsWith(METADATA_SUFFIX)) {
                    armed = false
                    refused.incrementAndGet()
                    error(FAULT_MESSAGE)
                }
                return delegate.newOutputFile(path)
            }

            override fun deleteFile(path: String) = delegate.deleteFile(path)

            override fun properties(): Map<String, String> = delegate.properties()

            override fun initialize(properties: Map<String, String>) = Unit

            override fun close() = delegate.close()
        }
    }

    /** Throws once, right after the commit landed, when armed: the commit step fails with the commit in place. */
    private class ThrowAfterCommit : LakeApplyHooks {
        @Volatile var armed = false
        val thrown = AtomicInteger()

        override fun afterCommit(
            tableId: UUID,
            operationId: UUID,
        ) {
            if (armed) {
                armed = false
                thrown.incrementAndGet()
                error(FAULT_MESSAGE)
            }
        }
    }

    private companion object {
        const val NS = "sales"
        const val T = "orders"
        const val METADATA_SUFFIX = ".metadata.json"
        const val FAULT_MESSAGE = "injected unclassified failure in the commit step"
        val UNCLASSIFIED = LakeApplyOutcome.Unknown(PipelineErrorCodes.Lake.DEFINITION_APPLY_UNCLASSIFIED)
    }
}
