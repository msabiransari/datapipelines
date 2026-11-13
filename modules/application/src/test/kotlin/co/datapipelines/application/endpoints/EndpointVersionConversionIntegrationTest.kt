package co.datapipelines.application.endpoints

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.application.SharedPostgres
import co.datapipelines.pipeline.PipelineVersionStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * #786 §E.0–E.2 — the in-place conversion's rows, against real PostgreSQL: a pre-V58 API is an entity row with its
 * legacy columns and no version row, inserted here by SQL exactly as the previous version wrote it. The startup
 * ordering, the refused boot and the conversion-versus-discard race under the real G0 guard are
 * `EndpointVersionMigrationE2eTest`'s, and the joint rollback boundary with the schedules is
 * `ConversionRollbackBoundaryE2eTest`'s (#895); this class pins what one attempt writes, logs and refuses, driven as the
 * startup runner drives it: E.0, then plan → entity locks → convert in ONE transaction, then the commit-time effects.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointVersionConversionIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var versions: PublishedEndpointVersionRepository
    private lateinit var registry: EndpointRegistry
    private lateinit var conversion: EndpointVersionConversion
    private lateinit var transactions: TransactionTemplate

    // Every event this capture reads is logged by the class's own EndpointVersionConversion, inside run() or
    // logPrecondition() called from a test method; TransactionTemplate runs the attempt's callback on that caller's
    // thread, no other class in this module builds the conversion, and test forks are separate JVMs.
    // raw-list-appender: synchronous — the conversion logs only on the test thread that calls it
    private val logs = ListAppender<ILoggingEvent>()

    private val logger = LoggerFactory.getLogger(EndpointVersionConversion::class.java) as Logger

    private var userId: UUID = UUID.randomUUID()
    private var workspaceId: UUID = UUID.randomUUID()

    @BeforeAll
    fun wire() {
        val dataSource = SharedPostgres.dataSource()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        versions = PublishedEndpointVersionRepository(jdbc)
        registry = EndpointRegistry(PublishedEndpointRepository(jdbc))
        conversion = EndpointVersionConversion(jdbc, versions, registry)
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @BeforeEach
    fun seed() {
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        userId = UUID.randomUUID()
        workspaceId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        workspace(workspaceId)
        logs.list.clear()
        logs.start()
        logger.addAppender(logs)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(logs)
    }

    @Test
    fun `a RELEASED current becomes v1 RELEASED - same content, timeout, description and enabled state, pointer unmoved`() {
        val pipeline = pipeline("acme/rides/daily", current = 2, PipelineVersionStatus.RELEASED, PipelineVersionStatus.RELEASED)
        val enabled = legacyApi("/acme/v1/rides", pipeline, timeout = 45, description = "Daily rides", enabled = true)
        val disabled = legacyApi("/acme/v1/rides-off", pipeline, timeout = 30, description = "", enabled = false)
        val warmed = registry.matcher()

        val report = runOnce()

        report.converted shouldContainExactly
            listOf(
                ConvertedEndpoint("/acme/v1/rides", "acme/rides/daily", 2, PipelineVersionStatus.RELEASED),
                ConvertedEndpoint("/acme/v1/rides-off", "acme/rides/daily", 2, PipelineVersionStatus.RELEASED),
            )
        val v1 = versions.find(enabled, 1).shouldNotBeNull()
        v1.status shouldBe PipelineVersionStatus.RELEASED
        v1.body shouldBe EndpointVersionBody("acme/rides/daily", 2, 45, "Daily rides")
        v1.bodyHash shouldBe sharedHash(v1.body)
        pointer(enabled) shouldBe 1
        pointer(disabled) shouldBe 1
        enabledOf(enabled) shouldBe true
        enabledOf(disabled) shouldBe false
        pipelineCurrent(pipeline) shouldBe 2
        // The registry snapshot was dropped after the writes, so the next match reads the converted rows.
        registry.matcher() shouldNotBeSameInstanceAs warmed
        reportLines() shouldContainExactly
            listOf(
                "event=endpoint.conversion.converted path=/acme/v1/rides pipeline=acme/rides/daily pipeline_version=2 status=RELEASED",
                "event=endpoint.conversion.converted path=/acme/v1/rides-off pipeline=acme/rides/daily pipeline_version=2 status=RELEASED",
                "event=endpoint.conversion.summary converted=2 released=2 draft=0",
            )
    }

    @Test
    fun `a DRAFT current becomes v1 DRAFT pinned to that exact draft - never promoted to RELEASED`() {
        val pipeline = pipeline("acme/rides/draft_only", current = 2, PipelineVersionStatus.RELEASED, PipelineVersionStatus.DRAFT)
        val api = legacyApi("/acme/v1/drafty", pipeline)

        runOnce()
            .converted
            .single()
            .status shouldBe PipelineVersionStatus.DRAFT

        versions.find(api, 1).shouldNotBeNull().status shouldBe PipelineVersionStatus.DRAFT
        versions
            .find(api, 1)
            .shouldNotBeNull()
            .body.pipelineVersion shouldBe 2
        pointer(api) shouldBe 1
    }

    @Test
    fun `E0 - a NULL CURRENT refuses the whole run with the exact list, and NOTHING is written - not even a convertible sibling`() {
        val good = pipeline("acme/a/good", current = 1, PipelineVersionStatus.RELEASED)
        val noCurrent = pipeline("acme/z/no_current", current = null, PipelineVersionStatus.DISCARDED)
        val noVersions = pipeline("acme/z/no_versions", current = null)
        val elsewhere = UUID.randomUUID().also { workspace(it) }
        val foreign = pipeline("acme/z/foreign", current = 1, PipelineVersionStatus.RELEASED, workspace = elsewhere)
        legacyApi("/acme/a/good", good)
        val a = legacyApi("/acme/z/one", noCurrent)
        val b = legacyApi("/acme/z/two", noVersions)
        val c = legacyApi("/acme/z/three", foreign)

        // E.0 is the runner's first phase: it reads every family's list and refuses before any family writes.
        conversion.logPrecondition() shouldBe 3

        conversion.precondition() shouldContainExactly
            listOf(
                ConversionRefusal(a, "/acme/z/one", "acme/z/no_current", ConversionRefusal.Reason.NO_CURRENT),
                ConversionRefusal(c, "/acme/z/three", foreign.toString(), ConversionRefusal.Reason.MISSING_PIPELINE),
                ConversionRefusal(b, "/acme/z/two", "acme/z/no_versions", ConversionRefusal.Reason.NO_VERSIONS),
            )
        count("SELECT COUNT(*) FROM published_endpoint_versions") shouldBe 0
        count("SELECT COUNT(*) FROM published_endpoints WHERE current_version IS NOT NULL") shouldBe 0
        logs.list.filter { it.level == Level.ERROR }.map { it.formattedMessage } shouldContainExactly
            listOf(
                "event=endpoint.conversion.refused kind=api id=$a path=/acme/z/one pipeline=acme/z/no_current reason=no_current",
                "event=endpoint.conversion.refused kind=api id=$c path=/acme/z/three pipeline=$foreign reason=missing_pipeline",
                "event=endpoint.conversion.refused kind=api id=$b path=/acme/z/two pipeline=acme/z/no_versions reason=no_versions",
                "event=endpoint.conversion.refused_summary refused=3 fix=\"${ConversionRefusedException.OPERATOR_FIX}\"",
            )
    }

    @Test
    fun `a rerun is a no-op - versions, pointers and hashes unchanged, and an entity with versions but no pointer is never selected`() {
        val pipeline = pipeline("acme/rides/rerun", current = 1, PipelineVersionStatus.RELEASED)
        val legacy = legacyApi("/acme/v1/legacy", pipeline)
        // A natively created API whose only version was discarded: versions exist, the pointer is NULL. The selector is
        // "no version row", never "no pointer" — so the conversion must leave it exactly as it is.
        val native = legacyApi("/acme/v1/native", pipeline)
        versions.insert(native, 1, PipelineVersionStatus.RELEASED, EndpointVersionBody("acme/rides/rerun", 1, 30, ""), userId)
        jdbc.update(
            "UPDATE published_endpoint_versions SET status = 'DISCARDED', discarded_at = NOW(), discarded_by = :u WHERE endpoint_id = :id",
            mapOf("id" to native, "u" to userId),
        )
        runOnce().converted.map { it.path } shouldContainExactly listOf("/acme/v1/legacy")
        val before = snapshot()
        logs.list.clear()

        runOnce().converted.shouldBeEmpty()

        snapshot() shouldBe before
        pointer(native) shouldBe null
        pointer(legacy) shouldBe 1
        reportLines() shouldContainExactly listOf("event=endpoint.conversion.summary converted=0 released=0 draft=0")
    }

    @Test
    fun `a CURRENT lost after the plan refuses under the locks, logs the existing line, and the transaction keeps nothing`() {
        val first = pipeline("acme/a/first", current = 1, PipelineVersionStatus.RELEASED)
        val later = pipeline("acme/z/later", current = 1, PipelineVersionStatus.RELEASED)
        legacyApi("/acme/a/first", first)
        val refusedApi = legacyApi("/acme/z/later", later)
        conversion.logPrecondition() shouldBe 0
        logs.list.clear()

        val refused =
            shouldThrow<ConversionRefusedException> {
                transactions.executeWithoutResult {
                    val attempt = conversion.plan()
                    attempt.lockEntities()
                    // Between the plan and the locked re-read, the later API's pipeline loses its CURRENT.
                    jdbc.update("UPDATE pipelines SET current_version = NULL WHERE id = :id", mapOf("id" to later))
                    attempt.convert(attempt.workspaces)
                }
            }

        refused.refusals shouldContainExactly
            listOf(ConversionRefusal(refusedApi, "/acme/z/later", "acme/z/later", ConversionRefusal.Reason.NO_CURRENT))
        refused.message.shouldNotBeNull() shouldContain ConversionRefusedException.OPERATOR_FIX
        withClue("the first API was written inside the transaction, which rolled back with the refusal") {
            count("SELECT COUNT(*) FROM published_endpoint_versions") shouldBe 0
        }
        logs.list.map { it.formattedMessage } shouldContainExactly
            listOf(
                "event=endpoint.conversion.refused kind=api id=$refusedApi path=/acme/z/later pipeline=acme/z/later reason=no_current",
                "event=endpoint.conversion.refused_summary refused=1 fix=\"${ConversionRefusedException.OPERATOR_FIX}\"",
            )
    }

    @Test
    fun `a candidate outside the guarded workspaces or the locked rows is a conflict, and nothing is written or reported`() {
        val pipeline = pipeline("acme/rides/conflict", current = 1, PipelineVersionStatus.RELEASED)
        legacyApi("/acme/v1/planned", pipeline)

        shouldThrow<EndpointConversionConflict> {
            transactions.executeWithoutResult {
                val attempt = conversion.plan()
                attempt.lockEntities()
                // A legacy row appears after the entity locks: the attempt must not write a row it did not lock.
                legacyApi("/acme/v1/late", pipeline)
                attempt.convert(attempt.workspaces)
            }
        }
        shouldThrow<EndpointConversionConflict> {
            transactions.executeWithoutResult {
                val attempt = conversion.plan()
                attempt.lockEntities()
                attempt.convert(emptySet())
            }
        }

        count("SELECT COUNT(*) FROM published_endpoint_versions") shouldBe 0
        reportLines().shouldBeEmpty()
    }

    // ------------------------------------------------------------------ fixtures

    /** One attempt as the runner drives it — plan, entity locks, convert in ONE transaction — then its effects. */
    private fun runOnce(): ConversionReport {
        val outcome =
            transactions
                .execute {
                    val attempt = conversion.plan()
                    attempt.lockEntities()
                    attempt.convert(attempt.workspaces)
                }.shouldNotBeNull()
        outcome.publish()
        return outcome.report
    }

    private fun workspace(id: UUID) =
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf("id" to id, "name" to "w_${id.toString().replace("-", "")}", "owner" to userId),
        )

    /** A pipeline named [name] whose versions 1..n carry [statuses]; [current] is its pointer as stored. */
    private fun pipeline(
        name: String,
        current: Int?,
        vararg statuses: PipelineVersionStatus,
        workspace: UUID = workspaceId,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES (:id, :name, 'P', :owner, :current, :ws)",
            mapOf("id" to id, "name" to name, "owner" to userId, "current" to current, "ws" to workspace),
        )
        statuses.forEachIndexed { index, status ->
            jdbc.update(
                "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at," +
                    " discarded_by, discarded_at) VALUES (:id, :version, CAST('{}' AS jsonb), :hash, :status, :owner," +
                    " CASE WHEN :status <> 'DRAFT' THEN CAST(:owner AS UUID) END, CASE WHEN :status <> 'DRAFT' THEN NOW() END," +
                    " CASE WHEN :status = 'DISCARDED' THEN CAST(:owner AS UUID) END, CASE WHEN :status = 'DISCARDED' THEN NOW() END)",
                mapOf("id" to id, "version" to index + 1, "hash" to "seed-$index", "status" to status.name, "owner" to userId),
            )
        }
        return id
    }

    /** A pre-V58 API: the entity row and its legacy columns, no version row — what the previous version wrote. */
    private fun legacyApi(
        path: String,
        pipelineId: UUID,
        timeout: Int = 30,
        description: String = "",
        enabled: Boolean = true,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO published_endpoints" +
                " (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, description, is_enabled, created_by)" +
                " VALUES (:id, :ws, :path, :pipeline, :timeout, :description, :enabled, :owner)",
            mapOf(
                "id" to id,
                "ws" to workspaceId,
                "path" to path,
                "pipeline" to pipelineId,
                "timeout" to timeout,
                "description" to description,
                "enabled" to enabled,
                "owner" to userId,
            ),
        )
        return id
    }

    private fun sharedHash(body: EndpointVersionBody): String =
        jdbc
            .queryForObject(
                "SELECT ${PublishedEndpointVersionRepository.API_BODY_HASH_EXPR}",
                PublishedEndpointVersionRepository.bodyParams(body),
                String::class.java,
            ).shouldNotBeNull()

    private fun pointer(id: UUID): Int? =
        jdbc.queryForObject("SELECT current_version FROM published_endpoints WHERE id = :id", mapOf("id" to id), Int::class.javaObjectType)

    private fun enabledOf(id: UUID): Boolean =
        jdbc
            .queryForObject(
                "SELECT is_enabled FROM published_endpoints WHERE id = :id",
                mapOf("id" to id),
                Boolean::class.java,
            ).shouldNotBeNull()

    private fun pipelineCurrent(id: UUID): Int? =
        jdbc.queryForObject("SELECT current_version FROM pipelines WHERE id = :id", mapOf("id" to id), Int::class.javaObjectType)

    private fun count(sql: String): Int = jdbc.queryForObject(sql, emptyMap<String, Any>(), Int::class.java).shouldNotBeNull()

    private fun snapshot(): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT e.id, e.current_version, v.version, v.status, v.pipeline_version, v.body_hash FROM published_endpoints e" +
                " LEFT JOIN published_endpoint_versions v ON v.endpoint_id = e.id ORDER BY e.id, v.version",
            emptyMap<String, Any>(),
        )

    private fun reportLines(): List<String> = logs.list.filter { it.level == Level.INFO }.map { it.formattedMessage }
}
