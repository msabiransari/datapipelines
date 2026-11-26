package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.A_MAX_AGE_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.A_TICK_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.BOUND_SLACK_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.B_MAX_AGE_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.CLEANUP_JOIN_MS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.CURRENT
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.H_CEILING_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.H_LONG_QUERY
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.H_MAX_AGE_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.H_TICK_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.H_TRIPS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.LAKE
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.LIFECYCLE_TICK_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.METADATA
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.PRODUCT_LOGGERS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.REAPER_CADENCE_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.REAPER_THREAD
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.WATCH_MILLIS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.awaitBurning
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.awaitUntil
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.generationBuiltAt
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.hCeilings
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.leaseManaged
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.lifetimeBound
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.linesOf
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.observeRetirementAndClose
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.readerGeneration
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.retireManagedPool
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.systemActor
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.underLease
import co.datapipelines.integration.ManagedLakeReadsFixture.LongRead
import co.datapipelines.logcapture.SnapshotListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * #818 (dp-lake P5, design record §6, §10, §15a): the managed lake's READ side in the booted application, with
 * `datapipelines.lake.enabled=true` against a real object store (an own MinIO) and the real catalog schema.
 *
 * Four boots share one metadata database (a scratch database, so no other suite's cached context sees the managed
 * row) and one store:
 *
 * - **A**, this class's `@SpringBootTest` context: the managed row is created and granted at boot; REST and UI writes
 *   to it are refused (`datasource.managed`); a workspace inserted by SQL after boot is granted within one tick (the repair path, since
 *   #823 grants a workspace created through the product in its own transaction); and
 *   **proof (a)**: with no catalog movement (so the tick retires nothing), every lease is served by a generation
 *   younger than `reads.max-generation-age-seconds` (A runs a 6 s bound) and the generation is replaced.
 * - **B**, a second full boot after a table was defined with no registry row: B's registrar BACKFILLS the row, and
 *   **A4**: the catalog pointer moves by a direct UPDATE under a lease while the projection row is held, so P4's
 *   repair cannot publish invalidation. B's age bound is 900 s, so only B's 2 s freshness tick can make its next
 *   lease read the new snapshot.
 * - **C**, a boot whose `lake.name` is held by an ordinary datasource: refused with `lake.boot.datasource_name_taken`.
 * - **D**, a boot with the lake disabled: the managed row stays, and a read of a managed table is refused
 *   `lake.table.unavailable` with reason `lake.disabled`.
 * - **G** (#818b A; #841 R4): the freshness tick's steps, each held behind a lock on the real scheduler, end at the
 *   tick's bound while the lock is still granted, and the reaper then runs — with the siblings' scheduler threads
 *   parked, so G's INSERT-owning wait, its wait at the datasource key and the two in one budget are each forced.
 * - **E** (#818b B): a catalog outage leaves E's live generation serving until its age bound; past it the rebuild
 *   is refused `lake.unavailable` and nothing is installed.
 * - **F** (#818b B): lifecycle moves, each judged against the generation the lease came from (F's tick and age
 *   bound are both longer than the test, so only an explicit retire builds a new one).
 * - **H** (#818c C, record §10 RULING 4 amended 2026-11-05): an executing reader on a generation nobody borrows from
 *   again is retired by the real reaper (on `dp-reaper` since #818d) past its age bound and hard-closed past its
 *   ceiling, inside `age + 2 × reaper delay + ceiling + reader termination`.
 * - **I1–I3** (#818d, record §10 RULING 4, second amendment; I3 runs in [ManagedLakeReadsStuckCloseE2eTest]): the same
 *   timeline under adverse conditions on the real scheduler — the metadata pool exhausted, the metadata Postgres paused,
 *   and the first generation's close held — each still inside the bound, because the reaper owns its thread and hands
 *   every close away.
 *
 * #982 moved the four long timelines into their own classes, so that different test workers run them side by side:
 * E1 and E1shutdown ([ManagedLakeReadsTrickleE2eTest]), E3b ([ManagedLakeReadsPausedMetadataE2eTest]) and I3
 * ([ManagedLakeReadsStuckCloseE2eTest]). The code the family shares, and this class's database, MinIO and OIDC stub, live
 * in its [ManagedLakeReadsFixture].
 *
 * E, F and G each connect under their own `ApplicationName`, so `pg_stat_activity` attributes a statement to one
 * instance; A and B keep ticking against the same database meanwhile, which is why every lock watcher filters on it.
 *
 * The Iceberg fixture is the checked-in `lake-fixture/iceberg/trips_iceberg` (its metadata bakes the bucket name
 * `dp-lake-it`, see [LakeMinioE2eTest]); its `00001` snapshot holds fewer rows than `00002`'s 2 000, so a row count
 * names the snapshot a lease read. This suite runs its OWN MinIO: [LakeMinioE2eTest] creates `dp-lake-it` on the
 * shared one unconditionally, and its writer credential must differ from the username here (the test asserts the
 * secret never reaches `properties_json`).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass") // one managed-read suite over context A and the class-long instances B and F (its long timelines: #982)
class ManagedLakeReadsE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var contextA: ApplicationContext

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `the boot creates the managed row, grants it, and refuses every REST and UI write to it`() {
        seedAuthRows()
        val row = managedRow().shouldNotBeNull()
        assertAll(
            { row.dialect shouldBe "LAKE" },
            { row.readonly shouldBe true },
            { row.jdbcUrl shouldBe "jdbc:duckdb::memory:" },
            { row.properties shouldContain "\"managed\"" },
            { row.properties shouldNotContain fixture.writerSecret },
            { grantedWorkspaces() shouldContain DEFAULT_WORKSPACE_ID },
        )
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get("/api/v1/datasources/$LAKE")
            .then()
            .statusCode(200)

        val put =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                // A well-formed edit: a malformed body is a 400 before any datasource is consulted.
                .body(
                    """
                    {"display_name": "Renamed lake", "dialect": "LAKE", "jdbc_url": "jdbc:duckdb::memory:",
                     "credential": {"kind": "none"}}
                    """.trimIndent(),
                ).`when`()
                .put("/api/v1/datasources/$LAKE")
        val delete =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .delete("/api/v1/datasources/$LAKE")
        listOf("PUT" to put, "DELETE" to delete).forEach { (verb, response) ->
            withClue("$verb answered ${response.body.asString()}") {
                response.statusCode shouldBe 409
                response.jsonPath().getString("error.code") shouldBe MANAGED_CODE
            }
        }
        val ui =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.URLENC)
                .formParam("jdbcUrl", "jdbc:duckdb::memory:")
                .formParam("credentialKind", "none")
                .formParam("displayName", "Renamed lake")
                .`when`()
                .post("/partials/datasources/$LAKE")
                .then()
                .statusCode(400)
                .extract()
                .asString()
        ui shouldContain "is the managed lake"
        managedRow().shouldNotBeNull().displayName shouldBe row.displayName
    }

    @Test
    @Order(2)
    fun `a workspace inserted by SQL after boot is granted the managed lake within one tick (repair path)`() {
        val workspace = UUID.randomUUID()
        fixture.metadata {
            it.createStatement().execute(
                "INSERT INTO workspaces (id, name, display_name) VALUES ('$workspace', 'later_ws', 'Later')",
            )
        }

        awaitTrue(Duration.ofSeconds(A_TICK_SECONDS * 4)) { workspace in grantedWorkspaces() }
    }

    // ------------------------------------------------------------------ #823: the grant at creation, the refused revoke, the repair

    /**
     * #823 (A.3) — a workspace created through `POST /api/v1/workspaces` is granted the managed lake IN ITS OWN
     * TRANSACTION: listed at once, on an instance whose freshness tick (600 s) cannot have run, and visible to a key
     * pinned to it. A and B tick the SAME database every 5 s and 2 s, so a listing alone could be their repair; the grant's
     * `granted_at` equals the workspace's `created_at` only when both were written by one transaction (Postgres `NOW()` is
     * the transaction's start), and its actor is the system actor, never the super admin who created the workspace.
     */
    @Test
    @Order(8231)
    fun `#823 - a workspace created through REST is granted the managed lake in its own transaction, before any tick`() {
        val created = lifecycle()
        val createdPort = portOf(created)
        given()
            .port(createdPort)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"name": "$CREATED_WS", "display_name": "Created by #823"}""")
            .`when`()
            .post("/api/v1/workspaces")
            .then()
            .statusCode(201)

        val listed =
            given()
                .port(createdPort)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/datasources/$LAKE/grants")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList<String>("data.workspace")
        val provenance = managedGrantProvenance(CREATED_WS)
        assertAll(
            { withClue("listed at once, with no await") { listed shouldContain CREATED_WS } },
            { withClue("granted in the creation's transaction: $provenance") { provenance?.sameTransaction shouldBe true } },
            { withClue("granted as the server, not as the creating super admin") { provenance?.byUserId shouldBe systemActorId() } },
            { withClue("the super admin is not the grantor") { provenance?.byUserId shouldNotBe UUID.fromString(ADMIN_USER_ID) } },
        )

        // Visible to a key pinned to the new workspace, through the agent surface.
        val key = mintMcpKeyIn(createdPort, CREATED_WS)
        val (datasources, refused) = ManagedLakeMcpClient(createdPort, key).call("datasources_list", emptyMap())
        withClue("datasources_list refused: $datasources") { refused shouldBe false }
        datasources.map { it.path("name").asText() } shouldContain LAKE
    }

    /**
     * #823 (B.2) — a revoke of the managed lake's grant is refused `datasource.managed` on the REST surface and the UI
     * surface, the grant stays, and no `datasource.revoked` audit row is written. An ordinary datasource's revoke is
     * unchanged (204), and the dialog draws a Revoke button for it and none for the managed row.
     */
    @Test
    @Order(8232)
    fun `#823 - the managed lake's grant cannot be revoked over REST or the UI, an ordinary datasource's still can`() {
        val revokedBefore = revokeAuditRows(LAKE)

        val rest =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .delete("/api/v1/datasources/$LAKE/grants/default")
        val ui =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .header("HX-Request", "true")
                .`when`()
                .post("/partials/datasources/$LAKE/grants/default/remove")
        val dialog =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .header("HX-Request", "true")
                .`when`()
                .get("/partials/datasources/$LAKE/grants")
        assertAll(
            { withClue("REST answered ${rest.body.asString()}") { rest.statusCode shouldBe 409 } },
            { rest.jsonPath().getString("error.code") shouldBe MANAGED_CODE },
            { rest.body.asString() shouldNotContain "jdbc:" },
            { withClue("UI answered ${ui.body.asString()}") { ui.statusCode shouldBe 409 } },
            { ui.body.asString() shouldContain MANAGED_CODE },
            { grantedWorkspaces() shouldContain DEFAULT_WORKSPACE_ID },
            { revokeAuditRows(LAKE) shouldBe revokedBefore },
            { dialog.statusCode shouldBe 200 },
            { dialog.body.asString() shouldNotContain "datasource-grant-remove" },
            { dialog.body.asString() shouldContain "granted by the server" },
        )

        // The ordinary path is untouched: a revoke of an ordinary datasource's grant is still a 204, and its dialog keeps the button.
        registerH2Datasource(ORDINARY)
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .post("/api/v1/datasources/$ORDINARY/grants/default")
            .then()
            .statusCode(200)
        val ordinaryDialog =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .header("HX-Request", "true")
                .`when`()
                .get("/partials/datasources/$ORDINARY/grants")
        ordinaryDialog.body.asString() shouldContain "datasource-grant-remove"
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/datasources/$ORDINARY/grants/default")
            .then()
            .statusCode(204)
    }

    /**
     * #823 (C.1) — the tick stays the repair path: a grant row an operator deleted by hand for an EXISTING workspace is
     * written back within four ticks of A's, and the registrar logs it (`lake.managed_datasource_granted … workspaces=1`).
     * The repair writes no audit row: the server repairing its own grant is not a person's act.
     */
    @Test
    @Order(8233)
    fun `#823 - the tick repairs a managed grant an operator deleted by hand, logs it and audits nothing`() {
        val logger = LoggerFactory.getLogger(PRODUCT_LOGGERS) as Logger
        val capture = SnapshotListAppender()
        logger.addAppender(capture)
        try {
            // The row to delete exists at once on the fixed code and after one tick on the old (the workspace was
            // created by the case above); waiting for it makes this case a repair test on both, not a copy of that one.
            awaitTrue(Duration.ofSeconds(A_TICK_SECONDS * 4)) { CREATED_WS in grantedWorkspaceNames() }
            val auditBefore = grantAuditRows()
            fixture.metadata { connection ->
                connection
                    .prepareStatement(
                        "DELETE FROM datasource_workspaces WHERE datasource_name = ? AND workspace_id = " +
                            "(SELECT id FROM workspaces WHERE name = ?)",
                    ).use {
                        it.setString(1, LAKE)
                        it.setString(2, CREATED_WS)
                        it.executeUpdate() shouldBe 1
                    }
            }

            awaitTrue(Duration.ofSeconds(A_TICK_SECONDS * 4)) { CREATED_WS in grantedWorkspaceNames() }

            val repaired = linesOf(capture, "event=lake.managed_datasource_granted").filter { "workspaces=1 " in it.text }
            withClue("the registrar logged the repair: ${capture.messages()}") { repaired.size shouldBeGreaterThanOrEqual 1 }
            withClue("the repair is not a person's act: no grant audit row") { grantAuditRows() shouldBe auditBefore }
        } finally {
            logger.detachAppender(capture)
            capture.stop()
        }
    }

    @Test
    @Order(3)
    fun `a table defined while the instance was down is backfilled by the next boot's registrar`() {
        fixture.defineManagedTable(PREVIOUS)
        registryLocation().shouldBe(null)

        instanceB = fixture.boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = B_TICK_SECONDS)
        portB = Integer.parseInt(checkNotNull(instanceB?.environment?.getProperty("local.server.port")))

        registryLocation() shouldBe PREVIOUS
    }

    @Test
    @Order(4)
    fun `A4 - a catalog move nobody published reaches B's next lease through B's freshness tick alone`() {
        pipelineId = createReadPipeline()
        withHeldProjection { projected ->
            projected shouldBe PREVIOUS
            val before = countOn(portB)
            before shouldBeInRange 1..(ICEBERG_ROWS - 1)

            // P4's repair cannot update the held projection or publish its invalidation. Only the catalog moves.
            moveCatalogPointer(PREVIOUS, CURRENT)

            val deadline = System.nanoTime() + Duration.ofSeconds(B_TICK_SECONDS * 10).toNanos()
            var after = before
            while (System.nanoTime() < deadline && after != ICEBERG_ROWS) {
                Thread.sleep(POLL_MILLIS)
                after = countOn(portB)
            }
            withClue("B kept reading the old snapshot; its age bound is $B_MAX_AGE_SECONDS s, so only the tick could move it") {
                after shouldBe ICEBERG_ROWS
            }
            // The lagging projection was never the source: it still names the old file under its row lock.
            registryLocation() shouldBe PREVIOUS
        }
    }

    @Test
    @Order(5)
    fun `proof a - with nothing moving, every lease is served by a generation younger than the age bound`() {
        val built = mutableSetOf<Instant>()
        val until = System.nanoTime() + Duration.ofSeconds(A_MAX_AGE_SECONDS * 3).toNanos()
        while (System.nanoTime() < until) {
            val leaseStarted = Instant.now()
            countOn(port) shouldBe ICEBERG_ROWS
            val builtAt = generationBuiltAt(contextA).shouldNotBeNull()
            withClue("generation built $builtAt served a lease started $leaseStarted with a ${A_MAX_AGE_SECONDS}s bound") {
                builtAt.isAfter(leaseStarted.minusSeconds(A_MAX_AGE_SECONDS)) shouldBe true
            }
            built += builtAt
            Thread.sleep(POLL_MILLIS)
        }
        built.size shouldBeGreaterThanOrEqual 2
    }

    @Test
    @Order(6)
    fun `a boot whose lake name an ordinary datasource holds is refused with the catalogued id`() {
        registerH2Datasource(TAKEN_NAME)

        val refused =
            shouldThrowAny { fixture.boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = B_TICK_SECONDS, name = TAKEN_NAME).close() }

        generateSequence(refused) { it.cause }.joinToString(" | ") { it.message.orEmpty() } shouldContain
            "lake.boot.datasource_name_taken"
        dialectOf(TAKEN_NAME) shouldBe "H2"
    }

    @Test
    @Order(7)
    fun `with the lake disabled the managed row stays and a managed table read is refused lake disabled`() {
        val disabled = fixture.boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = B_TICK_SECONDS, enabled = false)
        try {
            val portD = Integer.parseInt(checkNotNull(disabled.environment.getProperty("local.server.port")))
            managedRow().shouldNotBeNull()
            val failed =
                executeOn(portD)
                    .firstOrNull { it.first == "node_failed" }
                    ?.second
                    .shouldNotBeNull()
                    .toString()
            assertAll(
                { failed shouldContain "lake.table.unavailable" },
                { failed shouldContain "lake.disabled" },
            )
        } finally {
            disabled.close()
        }
    }

    /**
     * G (#818b A; #841 R4): the freshness tick's steps on the real scheduler, each ended at its 5 s bound under a held
     * lock while the lock stays granted, the reaper still running — with the instance that owns each wait FORCED.
     *
     * Since G0 the regrant takes the managed lake's datasource prerequisite key before its INSERT, so a held table lock
     * can reach G two ways: G's own INSERT queued on the table, or G queued on the KEY behind a sibling whose INSERT is
     * queued there (the gate-red failure: A and B tick the same database). So the siblings' one `dp-scheduled` thread is
     * parked for the whole case ([ParkedSiblings]) and each way is forced on its own:
     *
     * 1. **INSERT owner** — G holds the key and its INSERT, queued on the held table, is cancelled at the bound
     *    (`57014`); the catalog read before it likewise. Nothing is granted; the next tick repairs.
     * 2. **Key waiter** — the test holds the key (the sibling's part, without its INSERT): G waits at the key and
     *    refuses busy (`55P03`) at the bound, never issuing its INSERT; nothing is granted; the next tick repairs.
     * 3. **One budget** — the test holds the key for [KEY_HOLD_MILLIS], then lets G through to an INSERT queued on the
     *    held table: G's key wait and INSERT wait together end within ONE bound (before #841 R4: two, back to back).
     *
     * A busy refusal is not evidence that the INSERT's statement bound fired, so phase 1 alone carries that claim.
     */
    @Test
    @Order(8)
    fun `A - each freshness-tick statement is cancelled at its bound under a held lock, and the reaper then runs`() {
        val held = fixture.boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = HELD_TICK_SECONDS, application = APP_G, recordTicks = true)
        val logger = LoggerFactory.getLogger(PRODUCT_LOGGERS) as Logger
        val capture = SnapshotListAppender()
        logger.addAppender(capture)
        try {
            countOn(portOf(held)) shouldBe ICEBERG_ROWS
            val recorder = held.getBean(ScheduledJobsSchedulerE2eTest.TickRecorder::class.java)
            ParkedSiblings(listOfNotNull(contextA, instanceB, instanceF)).use {
                heldTickPhases(held, recorder, capture)
            }
        } finally {
            logger.detachAppender(capture)
            capture.stop()
            held.close()
        }
    }

    private fun heldTickPhases(
        held: ApplicationContext,
        recorder: ScheduledJobsSchedulerE2eTest.TickRecorder,
        capture: SnapshotListAppender,
    ) {
        ManagedLakeReadsHeldLockDiagnosis(APP_G, ManagedLakeReadsHeldLockDiagnosis.identify(held), held) {
            recorder.countOf(FRESHNESS_JOB)
        }.use { diagnosis ->
            ParkedSiblings(listOf(held)).use { park -> insertOwnerPhase(recorder, capture, diagnosis, park) }
            ParkedSiblings(listOf(held)).use { park -> keyWaiterPhase(recorder, capture, diagnosis, park) }
            ParkedSiblings(listOf(held)).use { park -> oneBudgetPhase(recorder, capture, diagnosis, park) }
        }
    }

    /** Phase 1 of G: G owns the key and the INSERT; both of the tick's steps are cancelled at the bound. */
    @Suppress("LongMethod") // one held-lock timeline on the real scheduler is the witness; its instants are asserted together
    private fun insertOwnerPhase(
        recorder: ScheduledJobsSchedulerE2eTest.TickRecorder,
        capture: SnapshotListAppender,
        diagnosis: ManagedLakeReadsHeldLockDiagnosis,
        park: ParkedSiblings,
    ) {
        val phase = "phase 1 (INSERT owner, instance G)"
        diagnosis.beforeLocks()
        lateinit var lacking: UUID
        fixture.metadata { lock ->
            lock.autoCommit = false
            lock.createStatement().use {
                it.execute("LOCK TABLE lake_tables_managed IN ACCESS EXCLUSIVE MODE")
                it.execute("LOCK TABLE datasource_workspaces IN SHARE MODE")
            }
            try {
                val mark = capture.events().size
                lacking = sqlWorkspace("g841_owner")
                fixture.metadata { watcher ->
                    startHeldTick(watcher, recorder, diagnosis, park, phase)
                    val read =
                        cancelledWhileHeld(
                            watcher,
                            "$phase: the catalog read",
                            CATALOG_READ_TEXT,
                            "lake_tables_managed",
                            "AccessExclusiveLock",
                            diagnosis,
                        )
                    val regrant =
                        cancelledWhileHeld(
                            watcher,
                            "$phase: the regrant INSERT",
                            REGRANT_TEXT,
                            "datasource_workspaces",
                            "ShareLock",
                            diagnosis,
                        ) {
                            withClue("$phase: G's queued INSERT runs under G's own hold of the datasource key") {
                                lakeKeyLocks(watcher, granted = true, application = APP_G) shouldBe 1
                            }
                        }
                    val reaps = recorder.countOf(REAP_JOB)
                    val ticks = recorder.countOf(FRESHNESS_JOB)
                    val regrantGoneAt = System.nanoTime()
                    // The reaper owns its thread (#818d): it reaps on its own cadence while the tick is held.
                    awaitTrue(
                        Duration.ofSeconds(REAPER_CADENCE_SECONDS + 2 * FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS),
                        "$phase: the reaper",
                    ) {
                        recorder.countOf(REAP_JOB) > reaps
                    }
                    val reaperDelay = Duration.ofNanos(System.nanoTime() - regrantGoneAt).toMillis()
                    awaitTrue(
                        Duration.ofSeconds(HELD_TICK_SECONDS + 2 * FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS),
                        "$phase: G's next tick",
                    ) {
                        recorder.countOf(FRESHNESS_JOB) > ticks
                    }
                    val stillHeld =
                        lockGranted(watcher, "lake_tables_managed", "AccessExclusiveLock") &&
                            lockGranted(watcher, "datasource_workspaces", "ShareLock")
                    val failures = regrantFailures(capture, mark)
                    println(
                        "HELD_LOCK_TICK phase=insert_owner read_queued_to_gone_ms=$read regrant_queued_to_gone_ms=$regrant " +
                            "reaper_after_regrant_ms=$reaperDelay reaps_after=${recorder.countOf(REAP_JOB) - reaps} " +
                            "ticks_after=${recorder.countOf(FRESHNESS_JOB) - ticks} locks_still_granted=$stillHeld " +
                            "regrant_failures=$failures",
                    )
                    assertAll(
                        { withClue("$phase: the catalog read's queued-to-gone") { read shouldBeInRange CANCEL_WINDOW_MS } },
                        { withClue("$phase: the INSERT's queued-to-gone") { regrant shouldBeInRange CANCEL_WINDOW_MS } },
                        { withClue("$phase: both held locks still granted") { stillHeld shouldBe true } },
                        {
                            withClue("$phase: G's INSERT was cancelled by its statement bound: $failures") {
                                failures.any {
                                    "sql_state=57014" in
                                        it
                                } shouldBe
                                    true
                            }
                        },
                        {
                            withClue("$phase: G alone writes, so nothing refused it busy: $failures") {
                                failures.none {
                                    "sql_state=55P03" in
                                        it
                                } shouldBe
                                    true
                            }
                        },
                        { withClue("$phase: the cancelled repair granted nothing") { (lacking in grantedWorkspaces()) shouldBe false } },
                    )
                }
            } finally {
                lock.rollback()
            }
        }
        awaitTrue(
            Duration.ofSeconds(HELD_TICK_SECONDS * 2 + FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS),
            "$phase: the next tick's repair",
        ) {
            lacking in grantedWorkspaces()
        }
    }

    /** Phase 2 of G: the datasource key is held (a sibling's part); G waits there and refuses busy, its INSERT never sent. */
    @Suppress("LongMethod") // one held-lock timeline on the real scheduler is the witness; its instants are asserted together
    private fun keyWaiterPhase(
        recorder: ScheduledJobsSchedulerE2eTest.TickRecorder,
        capture: SnapshotListAppender,
        diagnosis: ManagedLakeReadsHeldLockDiagnosis,
        park: ParkedSiblings,
    ) {
        val phase = "phase 2 (key waiter, instance G)"
        diagnosis.beforeLocks()
        lateinit var lacking: UUID
        fixture.metadata { key ->
            key.autoCommit = false
            holdLakeKey(key)
            try {
                val mark = capture.events().size
                lacking = sqlWorkspace("g841_waiter")
                fixture.metadata { watcher ->
                    startHeldTick(watcher, recorder, diagnosis, park, phase)
                    diagnosis.await(
                        watcher,
                        Duration.ofSeconds(BOUND_SLACK_SECONDS),
                        "$phase: G queuing on the key",
                        condition = {
                            lakeKeyLocks(watcher, granted = false, application = APP_G) > 0
                        },
                    )
                    val queuedAt = System.nanoTime()
                    val reaps = recorder.countOf(REAP_JOB)
                    var insertSent = false
                    val deadline = queuedAt + Duration.ofSeconds(FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS).toNanos()
                    while (System.nanoTime() < deadline && lakeKeyLocks(watcher, granted = false, application = APP_G) > 0) {
                        insertSent = insertSent || statementActive(watcher, REGRANT_TEXT)
                        Thread.sleep(WATCH_MILLIS)
                    }
                    val waited = Duration.ofNanos(System.nanoTime() - queuedAt).toMillis()
                    withClue(
                        "$phase: G still waited on the key ${FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS}s on: nothing refused it",
                    ) {
                        lakeKeyLocks(watcher, granted = false, application = APP_G) shouldBe 0
                    }
                    awaitTrue(Duration.ofSeconds(REAPER_CADENCE_SECONDS + BOUND_SLACK_SECONDS), "$phase: the reaper") {
                        recorder.countOf(REAP_JOB) >
                            reaps
                    }
                    awaitTrue(Duration.ofSeconds(BOUND_SLACK_SECONDS), "$phase: G's busy WARN") {
                        regrantFailures(capture, mark).any { "sql_state=55P03" in it }
                    }
                    val failures = regrantFailures(capture, mark)
                    println(
                        "HELD_LOCK_TICK phase=key_waiter key_queued_to_refused_ms=$waited insert_sent=$insertSent " +
                            "regrant_failures=$failures",
                    )
                    assertAll(
                        { withClue("$phase: the key wait's queued-to-refused") { waited shouldBeInRange CANCEL_WINDOW_MS } },
                        { withClue("$phase: refused before its INSERT") { insertSent shouldBe false } },
                        {
                            withClue("$phase: refused busy, the guard's native lock timeout: $failures") {
                                failures.first() shouldContain
                                    "error=DatapipelinesException sql_state=55P03"
                            }
                        },
                        {
                            withClue("$phase: the holder still holds the key") {
                                lakeKeyLocks(watcher, granted = true, pid = backendPid(key)) shouldBe
                                    1
                            }
                        },
                        { withClue("$phase: the refused repair granted nothing") { (lacking in grantedWorkspaces()) shouldBe false } },
                    )
                }
            } finally {
                key.rollback()
            }
        }
        awaitTrue(Duration.ofSeconds(HELD_TICK_SECONDS * 2 + BOUND_SLACK_SECONDS), "$phase: the next tick's repair") {
            lacking in
                grantedWorkspaces()
        }
    }

    /**
     * Phase 3 of G — #841 R4's property: a key wait and the INSERT wait after it share ONE bound. The key is held for
     * [KEY_HOLD_MILLIS] after G queued on it, then released; G's INSERT then queues on the held table and is cancelled
     * at what is left of the step's budget, so queued-on-key to INSERT-gone stays within one bound plus
     * [STEP_ALLOWANCE_MILLIS] (two bounds back to back would be ~[KEY_HOLD_MILLIS] + 5 000 ms).
     */
    @Suppress("LongMethod") // one held-lock timeline on the real scheduler is the witness; its instants are asserted together
    private fun oneBudgetPhase(
        recorder: ScheduledJobsSchedulerE2eTest.TickRecorder,
        capture: SnapshotListAppender,
        diagnosis: ManagedLakeReadsHeldLockDiagnosis,
        park: ParkedSiblings,
    ) {
        val phase = "phase 3 (one budget, instance G)"
        diagnosis.beforeLocks()
        lateinit var lacking: UUID
        fixture.metadata { table ->
            table.autoCommit = false
            table.createStatement().use { it.execute("LOCK TABLE datasource_workspaces IN SHARE MODE") }
            try {
                val mark = capture.events().size
                fixture.metadata { watcher ->
                    val queuedAt =
                        fixture.metadata { key ->
                            key.autoCommit = false
                            holdLakeKey(key)
                            lacking = sqlWorkspace("g841_budget")
                            startHeldTick(watcher, recorder, diagnosis, park, phase)
                            diagnosis.await(
                                watcher,
                                Duration.ofSeconds(BOUND_SLACK_SECONDS),
                                "$phase: G queuing on the key",
                                condition = { lakeKeyLocks(watcher, granted = false, application = APP_G) > 0 },
                            )
                            System.nanoTime().also {
                                Thread.sleep(KEY_HOLD_MILLIS)
                                key.rollback()
                            }
                        }
                    val deadline = queuedAt + Duration.ofSeconds(FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS).toNanos()
                    diagnosis.await(
                        watcher,
                        Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0)),
                        "$phase: G's INSERT queuing on the table once the key was released",
                        REGRANT_TEXT,
                    )
                    while (System.nanoTime() < deadline && waitingOnLock(watcher, REGRANT_TEXT)) Thread.sleep(WATCH_MILLIS)
                    val step = Duration.ofNanos(System.nanoTime() - queuedAt).toMillis()
                    awaitTrue(Duration.ofSeconds(BOUND_SLACK_SECONDS), "$phase: G's cancellation WARN") {
                        regrantFailures(capture, mark).any { "sql_state=57014" in it }
                    }
                    val failures = regrantFailures(capture, mark)
                    println(
                        "HELD_LOCK_TICK phase=one_budget key_hold_ms=$KEY_HOLD_MILLIS key_queued_to_insert_gone_ms=$step " +
                            "regrant_failures=$failures",
                    )
                    assertAll(
                        { withClue("$phase: G's INSERT left the waiters") { waitingOnLock(watcher, REGRANT_TEXT) shouldBe false } },
                        {
                            withClue(
                                "$phase: key wait + INSERT wait = $step ms; one bound is " +
                                    "${FRESHNESS_BOUND_SECONDS * 1000} + $STEP_ALLOWANCE_MILLIS ms",
                            ) {
                                step shouldBeLessThanOrEqual FRESHNESS_BOUND_SECONDS * 1000 + STEP_ALLOWANCE_MILLIS
                            }
                        },
                        {
                            withClue("$phase: the table lock is still granted") {
                                lockGranted(watcher, "datasource_workspaces", "ShareLock") shouldBe
                                    true
                            }
                        },
                        { withClue("$phase: the cancelled repair granted nothing") { (lacking in grantedWorkspaces()) shouldBe false } },
                    )
                }
            } finally {
                table.rollback()
            }
        }
        awaitTrue(Duration.ofSeconds(HELD_TICK_SECONDS * 2 + BOUND_SLACK_SECONDS), "$phase: the next tick's repair") {
            lacking in
                grantedWorkspaces()
        }
    }

    /** #1099: the acknowledged park precedes the locks; a baseline and actual start precede the statement observation. */
    private fun startHeldTick(
        watcher: Connection,
        recorder: ScheduledJobsSchedulerE2eTest.TickRecorder,
        diagnosis: ManagedLakeReadsHeldLockDiagnosis,
        park: ParkedSiblings,
        phase: String,
    ) {
        diagnosis.beginWindow(watcher)
        val baseline = recorder.countOf(FRESHNESS_JOB)
        // A chosen observation deadline, not a production whole-job maximum: other jobs may queue ahead of the tick.
        val operationSeconds = 25L
        val budget = Duration.ofSeconds(operationSeconds + BOUND_SLACK_SECONDS)
        val openedAt = System.nanoTime()
        park.close()
        diagnosis.await(
            watcher,
            budget,
            "$phase: the freshness tick did not start within ${budget.seconds} s: ticks_started_since_locks=0",
            condition = { recorder.countOf(FRESHNESS_JOB) > baseline },
        )
        println(
            "HELD_LOCK_TICK_STARTED phase=$phase window_open_nano=$openedAt start_observed_nano=${System.nanoTime()} " +
                "baseline=$baseline ticks=${recorder.countOf(FRESHNESS_JOB)}",
        )
    }

    /**
     * #841 R4 — parks each sibling instance's ONE `dp-scheduled` thread (the jobs' scheduler, `taskScheduler`) on a latch
     * for the life of G's case, so no sibling's freshness tick can hold the datasource key while G is observed. The
     * park is a task the scheduler itself runs: once it has started, any sibling tick in flight has finished and none
     * can start until [close].
     */
    private class ParkedSiblings(
        contexts: List<ApplicationContext>,
    ) : AutoCloseable {
        private val release = CountDownLatch(1)

        init {
            val parked = CountDownLatch(contexts.size)
            val threads = java.util.concurrent.ConcurrentLinkedQueue<String>()
            try {
                contexts.forEach { context ->
                    val jobs = context.getBean(JOBS_SCHEDULER_BEAN, ThreadPoolTaskScheduler::class.java)
                    withClue("a sibling's jobs scheduler has one thread, so one park stops every tick") {
                        jobs.scheduledThreadPoolExecutor.corePoolSize shouldBe 1
                    }
                    jobs.execute {
                        threads += Thread.currentThread().name
                        parked.countDown()
                        release.await(PARK_MAX_SECONDS, TimeUnit.SECONDS)
                    }
                }
                withClue("every sibling's dp-scheduled thread parks within ${PARK_WAIT_SECONDS}s") {
                    parked.await(PARK_WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
                }
                withClue("the parked threads are the jobs' thread") { threads.toSet() shouldBe setOf(JOBS_THREAD) }
            } catch (e: Throwable) {
                release.countDown()
                throw e
            }
        }

        override fun close() = release.countDown()
    }

    @Test
    @Order(9)
    fun `B - a catalog outage leaves the live generation serving until its age bound, then refuses and installs nothing`() {
        val outage =
            fixture.boot(maxAgeSeconds = OUTAGE_MAX_AGE_SECONDS, tickSeconds = OUTAGE_TICK_SECONDS, application = APP_E, recordTicks = true)
        try {
            val portE = portOf(outage)
            countOn(portE) shouldBe ICEBERG_ROWS
            val builtAt = generationBuiltAt(outage).shouldNotBeNull()
            val recorder = outage.getBean(ScheduledJobsSchedulerE2eTest.TickRecorder::class.java)
            fixture.metadata {
                it.createStatement().use { st -> st.execute("ALTER TABLE lake_tables_managed RENAME TO lake_tables_managed_away") }
            }
            try {
                // Two tick starts: the first has then finished, its catalog read failed against the outage.
                val ticks = recorder.countOf(FRESHNESS_JOB)
                awaitTrue(Duration.ofSeconds(OUTAGE_TICK_SECONDS * 3)) { recorder.countOf(FRESHNESS_JOB) >= ticks + 2 }
                countOn(portE) shouldBe ICEBERG_ROWS
                generationBuiltAt(outage) shouldBe builtAt

                Thread.sleep(Duration.between(Instant.now(), builtAt.plusSeconds(OUTAGE_MAX_AGE_SECONDS + 1)).toMillis().coerceAtLeast(0))
                val refused = refusalOn(portE)
                assertAll(
                    { refused shouldContain "lake.unavailable" },
                    { generationBuiltAt(outage) shouldBe null },
                )
            } finally {
                fixture.metadata {
                    it.createStatement().use { st ->
                        st.execute("ALTER TABLE lake_tables_managed_away RENAME TO lake_tables_managed")
                    }
                }
            }
            countOn(portE) shouldBe ICEBERG_ROWS
            generationBuiltAt(outage).shouldNotBeNull().isAfter(builtAt) shouldBe true
        } finally {
            outage.close()
        }
    }

    // Each lifecycle case below is independent: it sets the row's state itself and restores it in `finally`, so a
    // falsification that breaks one case cannot hide another behind an earlier failure. They share instance F.

    @Test
    @Order(10)
    fun `B - active to archived - served until the generation is replaced, then refused with the generation's reason`() {
        val portF = lifecycleReady()
        try {
            // This read builds F's generation while the row is active; the archive below lands after it.
            countOn(portF) shouldBe ICEBERG_ROWS
            fixture.setStatus("archived")
            withClue("active -> archived: the live generation was built active and still serves") { countOn(portF) shouldBe ICEBERG_ROWS }
            retireManagedPool(lifecycle()) shouldBe true
            refusalOn(portF).also {
                it shouldContain "lake.table.unavailable"
                it shouldContain "status archived"
            }
        } finally {
            fixture.setStatus("active")
        }
    }

    @Test
    @Order(11)
    fun `B - archived to active - refused with the leased generation's reason until it is replaced, then served`() {
        val portF = lifecycleReady(status = "archived")
        try {
            refusalOn(portF) shouldContain "status archived"
            fixture.setStatus("active")
            withClue("archived -> active: the leased generation was built archived, so it still refuses, never an engine error") {
                refusalOn(portF).also {
                    it shouldContain "lake.table.unavailable"
                    it shouldContain "status archived"
                }
            }
            retireManagedPool(lifecycle()) shouldBe true
            countOn(portF) shouldBe ICEBERG_ROWS
        } finally {
            fixture.setStatus("active")
        }
    }

    @Test
    @Order(12)
    fun `B - a deprecated table stays readable`() {
        val portF = lifecycleReady(status = "deprecated")
        try {
            countOn(portF) shouldBe ICEBERG_ROWS
        } finally {
            fixture.setStatus("active")
        }
    }

    @Test
    @Order(13)
    fun `B - a table with no lifecycle row is refused no lifecycle row`() {
        renameLifecycleRow(from = "trips", to = AWAY)
        try {
            refusalOn(lifecycleReady()) shouldContain "no lifecycle row"
        } finally {
            renameLifecycleRow(from = AWAY, to = "trips")
        }
    }

    @Test
    @Order(14)
    fun `B - a table with no catalog row is refused no catalog row, never served from the projection`() {
        // P4 can repair the display row after A4. Make that observed state explicit instead of relying on tick timing.
        fixture.metadata { connection ->
            connection
                .prepareStatement(
                    "UPDATE lake_tables SET location = ? WHERE datasource_id = ? AND name = 'trips' AND origin = 'managed'",
                ).use { statement ->
                    statement.setString(1, CURRENT)
                    statement.setString(2, LAKE)
                    statement.executeUpdate() shouldBe 1
                }
        }
        // Complete boot/backfill before holding the projection; the first lease is still after the catalog row goes.
        val portF = lifecycleReady()
        withHeldProjection { projected ->
            // This real fixture pointer would serve if fallback were allowed, even with no catalog row.
            projected shouldBe CURRENT
            renameCatalogRow(from = "trips", to = AWAY)
            try {
                registryLocation() shouldBe projected
                refusalOn(portF) shouldContain "no catalog row"
            } finally {
                renameCatalogRow(from = AWAY, to = "trips")
            }
        }
        countOn(lifecycleReady()) shouldBe ICEBERG_ROWS
    }

    /**
     * #818c C — the bound observed on the real scheduler, for the case no lease ever re-judges: H's one lease runs a
     * cross join that takes minutes, and nothing borrows from H again. Its reaper (`dp-reaper`) must retire the
     * generation past the age bound (the `trigger=reaper` line, H's own by its `max_age_seconds`) and hard-close it
     * past H's ceiling (H's `ceiling_seconds`), which ends the query. The tight fits, age + one cadence + slack and
     * ceiling + one cadence + slack on a quiet thread, make the falsification (no retirement in the reaper's pass)
     * red within seconds; the bound the record states, age + 2 × reaper delay + ceiling + reader termination (#818d),
     * is asserted too.
     *
     * What else could end the query is ruled out: it completes in minutes (the outcome must be a failure, and it was
     * seen burning CPU first); its statement has no timeout; the test never interrupts its thread; the cleanup cancel
     * runs after the assertions; the catalog does not move, and no `lake.generation_stale` line is logged, so the
     * freshness tick retires nothing. `LakeRules` admits H's profile, or H would not boot.
     */
    @Test
    @Order(15)
    @Suppress("LongMethod") // one timeline on the real scheduler is the witness; its instants are asserted together
    fun `H - with no later borrower the reaper retires an executing reader's generation and its close ends the query`() {
        val reader =
            fixture.boot(
                maxAgeSeconds = H_MAX_AGE_SECONDS,
                tickSeconds = H_TICK_SECONDS,
                application = APP_H,
                extra = hCeilings(),
            )
        val logger = LoggerFactory.getLogger(PRODUCT_LOGGERS) as Logger
        val capture = SnapshotListAppender()
        logger.addAppender(capture)
        try {
            val catalogBefore = registryLocation()
            val lease = leaseManaged(reader)
            val builtAt = generationBuiltAt(reader).shouldNotBeNull()
            val statement = lease.prepareStatement(H_LONG_QUERY)
            val timeout = statement.queryTimeout
            val run = LongRead(statement)
            try {
                run.start()
                val cpu = awaitBurning(run)
                // No poolFor on H from here on: only H's reaper can retire the generation.
                val retireBy = builtAt.plusSeconds(H_MAX_AGE_SECONDS + REAPER_CADENCE_SECONDS + BOUND_SLACK_SECONDS)
                // Removal precedes the retirement log; observe both before freezing the event snapshot.
                awaitUntil(retireBy, "the reaper retires the generation with no lease") {
                    generationBuiltAt(reader) == null &&
                        linesOf(capture, "event=lake.generation_expired").any {
                            "max_age_seconds=$H_MAX_AGE_SECONDS " in it.text && "trigger=reaper" in it.text && it.at >= builtAt
                        }
                }
                val retiredSeenAt = Instant.now()
                val runningAfterRetire = run.ended.count == 1L
                val expired =
                    linesOf(capture, "event=lake.generation_expired").filter { "max_age_seconds=$H_MAX_AGE_SECONDS " in it.text }
                val retiredAt = expired.singleOrNull()?.at
                val closeBy = (retiredAt ?: retiredSeenAt).plusSeconds(H_CEILING_SECONDS + REAPER_CADENCE_SECONDS + BOUND_SLACK_SECONDS)
                val ended = run.ended.await(Duration.between(Instant.now(), closeBy).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
                val endedAt = run.endedAt.get()
                val hardClosed =
                    linesOf(capture, "event=datasource.pool_hard_closed").filter { "ceiling_seconds=$H_CEILING_SECONDS " in it.text }
                val reaperCounts = linesOf(capture, "event=lake.reaper_retired")
                val stale = linesOf(capture, "event=lake.generation_stale")
                val catalogAfter = registryLocation()
                val bound = lifetimeBound()
                println(
                    "REAPER_BOUND_E2E built=$builtAt executing_cpu_ns=$cpu statement_timeout=$timeout " +
                        "age_s=$H_MAX_AGE_SECONDS ceiling_s=$H_CEILING_SECONDS tick_s=$H_TICK_SECONDS " +
                        "lake_rules=${bound.seconds + H_TICK_SECONDS}<=$H_PROTECTION_WINDOW_SECONDS " +
                        "expired=${expired.map { "${it.at} ${it.thread} ${it.text}" }} retired_seen=$retiredSeenAt " +
                        "running_after_retire=$runningAfterRetire hard_closed=${hardClosed.map { "${it.at} ${it.thread}" }} " +
                        "reaper_retired=${reaperCounts.map { "${it.at} ${it.text}" }} ended=$endedAt outcome=${run.outcome.get()} " +
                        "stale_lines=${stale.size} catalog_unmoved=${catalogBefore == catalogAfter} bound=$bound",
                )
                assertAll(
                    { withClue("the statement carries no timeout of its own") { timeout shouldBe 0 } },
                    { withClue("one reaper retirement of H's generation, past its age bound") { expired.size shouldBe 1 } },
                    { expired.single().text shouldContain "trigger=reaper" },
                    { expired.single().thread shouldBe REAPER_THREAD },
                    { (expired.single().at >= builtAt.plusSeconds(H_MAX_AGE_SECONDS)) shouldBe true },
                    { reaperCounts.any { "generations=" in it.text && it.at >= expired.single().at } shouldBe true },
                    { withClue("retired while the query still ran: the lease was held") { runningAfterRetire shouldBe true } },
                    { withClue("H's hard close, past the retirement's ceiling") { hardClosed.size shouldBe 1 } },
                    { (hardClosed.single().at >= expired.single().at.plusSeconds(H_CEILING_SECONDS)) shouldBe true },
                    { withClue("the close ends the query by $closeBy") { ended shouldBe true } },
                    { run.outcome.get().startsWith("failed:") shouldBe true },
                    { (endedAt.shouldNotBeNull() >= hardClosed.single().at) shouldBe true },
                    { Duration.between(builtAt, endedAt.shouldNotBeNull()).toMillis() shouldBeLessThanOrEqual bound.toMillis() },
                    { withClue("the freshness tick retired nothing") { stale shouldContainExactly emptyList() } },
                    { catalogAfter shouldBe catalogBefore },
                )
            } finally {
                // Cleanup only after the assertions: a planted (still running) query is cancelled here.
                runCatching { statement.cancel() }
                run.thread.join(CLEANUP_JOIN_MS)
                runCatching { lease.close() }
            }
        } finally {
            logger.detachAppender(capture)
            capture.stop()
            reader.close()
        }
    }

    /**
     * #818d I1 (record §10 RULING 4, second amendment, item 5) — H's timeline with the instance's metadata pool
     * exhausted: once the reader is executing, the test leases every connection of the instance's metadata
     * `HikariDataSource`, so each sibling job on `dp-scheduled` waits out the pool's connection timeout. The reaper
     * issues no statement and owns `dp-reaper`, so it still retires the generation within one cadence of its age bound
     * and closes it within one cadence of its ceiling. Non-vacuity: the pool's MXBean must show a thread waiting for a
     * connection while the condition holds — a job really was blocked. Red with the reaper's `scheduler` attribute
     * removed: the reaper then queues behind those waits.
     */
    @Test
    @Order(16)
    fun `I1 - with the metadata pool exhausted the reaper still retires and closes an executing reader's generation in bound`() {
        val reader = fixture.bootReaderProfile(APP_I1)
        try {
            // Isolate the reaper, as in I2: a failed keeper heartbeat would otherwise retire the generation first.
            (reader.getBean("readerLeaseTaskScheduler") as ThreadPoolTaskScheduler).shutdown()
            val metadataPool = reader.getBean("dataSource") as HikariDataSource
            reapedUnderAdversity(reader, "I1 metadata pool exhausted") {
                val held = (1..metadataPool.maximumPoolSize).map { metadataPool.connection }
                Adversity(
                    release = { held.forEach { runCatching { it.close() } } },
                    witness = { metadataPool.hikariPoolMXBean.threadsAwaitingConnection },
                )
            }
        } finally {
            reader.close()
        }
    }

    /**
     * #818d I2 — H's timeline with the metadata Postgres PAUSED (`docker pause` of this JVM's own Postgres container, not
     * a reused one): every statement any job sends hangs on its socket, which no statement timeout can end (#818d's
     * follow-up on the metadata pool's own timeouts). The reaper still retires and closes in bound. Non-vacuity: the
     * instance's metadata pool must show a connection in use while paused — a job's statement stuck on the socket. The
     * container is unpaused in `finally`, before anything else touches the database. Red with the reaper's
     * `scheduler` attribute removed: the reaper then waits behind a statement that never returns.
     */
    @Test
    @Order(17)
    fun `I2 - with the metadata Postgres paused the reaper still retires and closes an executing reader's generation in bound`() {
        val reader = fixture.bootReaderProfile(APP_I2)
        try {
            (reader.getBean("readerLeaseTaskScheduler") as ThreadPoolTaskScheduler).shutdown()
            val metadataPool = reader.getBean("dataSource") as HikariDataSource
            val docker = DockerClientFactory.instance().client()
            val container = SharedE2e.postgres.containerId
            reapedUnderAdversity(reader, "I2 metadata Postgres paused") {
                docker.pauseContainerCmd(container).exec()
                Adversity(
                    release = { docker.unpauseContainerCmd(container).exec() },
                    witness = { metadataPool.hikariPoolMXBean.activeConnections },
                )
            }
        } finally {
            reader.close()
        }
    }

    /** An adverse condition: how to undo it, and a sample proving it held (the largest sample must be at least 1). */
    private class Adversity(
        val release: () -> Unit,
        val witness: () -> Int,
    )

    /** E.2: a metadata lock refuses the build before a single connection can be served. */
    @Test
    @Order(20)
    fun `E2 - a refused reader pin never publishes the generation`() {
        fixture.ensureReaderFixture()
        val reader =
            fixture.boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = LIFECYCLE_TICK_SECONDS, application = "reader-pin-refusal")
        try {
            fixture.metadata { connection ->
                connection.autoCommit = false
                connection.createStatement().use { it.execute("LOCK TABLE lake_leases IN SHARE MODE") }
                try {
                    val failure = shouldThrowAny { leaseManaged(reader) }
                    val causes =
                        generateSequence(
                            failure as Throwable?,
                        ) { it.cause }.map { it.javaClass.simpleName + ":" + it.message }.joinToString(" <- ")
                    println("READER_PIN_REFUSED database_now=${fixture.databaseNow()} causes=$causes")
                    causes shouldContain "Managed lake reader protection could not be acquired"
                    generationBuiltAt(reader) shouldBe null
                } finally {
                    connection.rollback()
                }
            }
            leaseManaged(reader).use { lease ->
                fixture.readerRows().isNotEmpty() shouldBe true
                lease.isClosed shouldBe false
            }
        } finally {
            reader.close()
        }
    }

    /** E.3a: a stopped instance cannot renew after its database-clock TTL, while its reader remains active. */
    @Test
    @Order(21)
    fun `E3a - dead keeper pins lapse at TTL and the old operation cannot renew`() {
        fixture.ensureReaderFixture()
        val reader =
            fixture.boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = LIFECYCLE_TICK_SECONDS, application = "reader-dead-keeper")
        val lease = leaseManaged(reader)
        val run = LongRead(lease.prepareStatement(H_LONG_QUERY))
        try {
            run.start()
            awaitBurning(run)
            (reader.getBean("readerLeaseTaskScheduler") as ThreadPoolTaskScheduler).shutdown()
            val generation = readerGeneration(reader)
            val pins = setOf(generation)
            val expiry =
                fixture.metadata { connection ->
                    connection.createStatement().use {
                        it
                            .executeQuery(
                                "SELECT min(expires_at) FROM lake_leases " +
                                    "WHERE kind='reader' AND operation_id='$generation'",
                            ).use { rows ->
                                rows.next()
                                rows.getTimestamp(1).toInstant()
                            }
                    }
                }
            println("READER_TTL before=${fixture.databaseNow()} expiry=$expiry reader_alive=${run.thread.isAlive}")
            fixture
                .readerRows()
                .filterKeys { it in pins }
                .values
                .all { it } shouldBe true
            val delay = Duration.between(fixture.databaseNow(), expiry.plusSeconds(1)).toMillis().coerceAtLeast(0)
            Thread.sleep(delay)
            val after = fixture.readerRows().filterKeys { it in pins }
            withClue("E3a rows lapse at database expiry, with a physical reader still executing") {
                run.thread.isAlive shouldBe true
                after.values.none { it } shouldBe true
            }
            withClue("a delayed keeper cannot admit a new query after its last known protection has lapsed") {
                shouldThrowAny { leaseManaged(reader) }
            }
            fixture.metadata { connection ->
                connection.createStatement().use {
                    it.executeUpdate(
                        "UPDATE lake_leases SET expires_at=now()+interval '80 seconds' WHERE kind='reader' " +
                            "AND operation_id='$generation' AND expires_at>now() AND deadline_at>now()",
                    ) shouldBe
                        0
                }
            }
            println("READER_TTL after=${fixture.databaseNow()} expiry=$expiry rows=$after reader_alive=${run.thread.isAlive}")
        } finally {
            runCatching { run.thread.interrupt() }
            reader.close()
            run.thread.join(CLEANUP_JOIN_MS)
            runCatching { lease.close() }
        }
    }

    /**
     * #833 — the read that BUILDS a managed generation (the catalog read, first in the view init) runs on the lease's
     * thread inside `poolFor` and carries its own bound. Under a held catalog lock the rebuild is refused
     * `lake.unavailable` by a statement cancel (SQLState 57014) within that bound, installs nothing, and the retired
     * generation's held lease keeps serving meanwhile; once the lock goes, the next lease builds. The red run (no
     * bound) is observed as the build still waiting at [BUILD_READ_RED_SECONDS], never as an unbounded wait.
     */
    @Test
    @Order(24)
    fun `#833 - a held catalog lock refuses the rebuild lake unavailable within its bound while the retired generation serves`() {
        fixture.ensureReaderFixture()
        val reader = fixture.boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = LIFECYCLE_TICK_SECONDS, application = APP_833)
        val lease = leaseManaged(reader)
        try {
            val served = countOnLease(lease)
            retireManagedPool(reader) shouldBe true
            fixture.metadata { lock ->
                lock.autoCommit = false
                lock.createStatement().use { it.execute("LOCK TABLE lake_tables_managed IN ACCESS EXCLUSIVE MODE") }
                try {
                    fixture.metadata { watcher -> rebuildUnderHeldLock(reader, lease, watcher, served) }
                } finally {
                    lock.rollback()
                }
            }
            leaseManaged(reader).close()
            generationBuiltAt(reader).shouldNotBeNull()
        } finally {
            runCatching { lease.close() }
            reader.close()
        }
    }

    private fun rebuildUnderHeldLock(
        reader: ApplicationContext,
        lease: Connection,
        watcher: Connection,
        served: Long,
    ) {
        val failure = AtomicReference<Throwable?>()
        val failedAt = AtomicReference<Long?>()
        val build =
            Thread({
                runCatching { leaseManaged(reader).close() }.onFailure {
                    failedAt.set(System.nanoTime())
                    failure.set(it)
                }
            }, "dp833-rebuild").apply {
                isDaemon = true
                start()
            }
        ManagedLakeReadsHeldLockDiagnosis(APP_833, build.threadId()).use { diagnosis ->
            diagnosis.beginWindow(watcher)
            diagnosis.await(
                watcher,
                Duration.ofSeconds(BUILD_READ_BOUND_SECONDS),
                "the build catalog read queuing on lake_tables_managed",
                CATALOG_READ_TEXT,
            )
        }
        val queuedAt = System.nanoTime()
        val servedWhileQueued = countOnLease(lease)
        val countedAt = System.nanoTime()
        build.join(Duration.ofSeconds(BUILD_READ_RED_SECONDS).toMillis())
        val stillWaiting = build.isAlive
        val causes = generateSequence(failure.get()) { it.cause }.toList()
        val elapsedMs = failedAt.get()?.let { Duration.ofNanos(it - queuedAt).toMillis() }
        println(
            "BUILD_READ_BOUND queued_to_refused_ms=$elapsedMs still_waiting_after_${BUILD_READ_RED_SECONDS}s=$stillWaiting " +
                "served=$served served_while_queued=$servedWhileQueued " +
                "causes=${causes.joinToString(" <- ") { it.javaClass.simpleName + ":" + it.message?.take(CAUSE_PRINT_CHARS) }}",
        )
        withClue("the build read was still waiting on the held lock after ${BUILD_READ_RED_SECONDS}s: it carries no bound") {
            stillWaiting shouldBe false
        }
        assertAll(
            { elapsedMs.shouldNotBeNull() shouldBeInRange BUILD_READ_WINDOW_MS },
            { causes.map { codeOf(it) } shouldContain "lake.unavailable" },
            { causes.filterIsInstance<SQLException>().map { it.sqlState } shouldContain "57014" },
            { generationBuiltAt(reader) shouldBe null },
            { servedWhileQueued shouldBe served },
            { (countedAt < failedAt.get().shouldNotBeNull()) shouldBe true },
        )
    }

    private fun countOnLease(lease: Connection): Long =
        lease.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM $H_TRIPS").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    /** A `DatapipelinesException`'s code, by name (integration-tests reaches app only); null for any other throwable. */
    private fun codeOf(error: Throwable): String? =
        error.javaClass.methods
            .firstOrNull { it.name == "getCode" && it.parameterCount == 0 }
            ?.invoke(error) as? String

    /**
     * H's timeline with [apply]'s condition imposed once the reader is executing and released only after the
     * assertions: the reaper retires the generation within one cadence (plus slack) of its age bound and hard-closes it
     * within one cadence of its ceiling, both on `dp-reaper`, and the query ends inside the lifetime bound.
     */
    private fun reapedUnderAdversity(
        reader: ConfigurableApplicationContext,
        label: String,
        apply: () -> Adversity,
    ) {
        val logger = LoggerFactory.getLogger(PRODUCT_LOGGERS) as Logger
        val capture = SnapshotListAppender()
        logger.addAppender(capture)
        val lease = leaseManaged(reader)
        val builtAt = generationBuiltAt(reader).shouldNotBeNull()
        val statement = lease.prepareStatement(H_LONG_QUERY)
        val run = LongRead(statement)
        var adversity: Adversity? = null
        val witnessed = AtomicInteger()
        try {
            run.start()
            awaitBurning(run)
            val held = apply()
            adversity = held
            val sampler =
                Thread({
                    while (!Thread.currentThread().isInterrupted) {
                        witnessed.accumulateAndGet(held.witness(), ::maxOf)
                        runCatching { Thread.sleep(WATCH_MILLIS) }.onFailure { return@Thread }
                    }
                }, "test-adversity-witness").apply {
                    isDaemon = true
                    start()
                }
            val timeline = observeRetirementAndClose(reader, capture, run, builtAt, label)
            sampler.interrupt()
            println("ADVERSE_REAP $label witness_max=${witnessed.get()} ${timeline.text}")
            assertAll(
                { timeline.assertInBound() },
                { withClue("$label: the condition must have held a job (witness ≥ 1)") { witnessed.get() shouldBeGreaterThanOrEqual 1 } },
            )
        } finally {
            adversity?.release?.invoke()
            runCatching { statement.cancel() }
            run.thread.join(CLEANUP_JOIN_MS)
            runCatching { lease.close() }
            logger.detachAppender(capture)
            capture.stop()
        }
    }

    /** Instance F, booted once: its tick and age bound outlast the suite, so only [retireManagedPool] replaces a generation. */
    private fun lifecycle(): ConfigurableApplicationContext =
        instanceF
            ?: fixture
                .boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = LIFECYCLE_TICK_SECONDS, application = APP_F)
                .also { instanceF = it }

    /** F's port, after the row is set to [status] (when given) and F's live generation, if any, is replaced. */
    private fun lifecycleReady(status: String? = null): Int {
        val context = lifecycle()
        status?.let { fixture.setStatus(it) }
        retireManagedPool(context)
        return portOf(context)
    }

    // ------------------------------------------------------------- reads through the pipeline surface

    private fun refusalOn(targetPort: Int): String =
        executeOn(targetPort)
            .firstOrNull { it.first == "node_failed" }
            ?.second
            .shouldNotBeNull()
            .toString()

    private fun countOn(targetPort: Int): Int {
        val events = executeOn(targetPort)
        val failed = events.firstOrNull { it.first == "node_failed" }?.second
        withClue("node_failed payload: $failed") {
            events.lifecycleNames() shouldContainExactly
                listOf("execution_started", "node_started", "node_completed", "pipeline_completed", "data_ready")
        }
        val executionId = events.first().second["execution_id"].asText()
        val rows: List<List<Any?>> =
            given()
                .port(targetPort)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .get("data.rows")
        return rows
            .single()
            .single()
            .toString()
            .toInt()
    }

    private fun executeOn(targetPort: Int): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(EXECUTION_BUDGET) {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$targetPort/api/v1/pipelines/$pipelineId/execute"))
                    .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                    .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                    .header("DP-Correlation-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            E2eSse.parseEvents(response.body(), mapper)
        }

    private fun createReadPipeline(): String {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"id": "$TEMPLATE_ID", "dialect": "LAKE", "display_name": "Managed lake count",
                 "description": "818 managed read", "imports": [],
                 "body": "SELECT count(*) AS n FROM $LAKE.sales.trips"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
        return given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "test/managed_lake_count", "nodes": [{
                    "id": "count_trips", "description": "Count the managed table",
                    "type": "DQL", "source": "$LAKE",
                    "template": {"id": "$TEMPLATE_ID", "version": 1},
                    "output": {"target": "caller"}, "depends_on": []}]}
                """.trimIndent().replace("\n", " "),
            ).`when`()
            .post("/api/v1/pipelines")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("data.id")
    }

    private fun registerH2Datasource(name: String) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "$name", "display_name": "Not the lake", "dialect": "H2",
                 "jdbc_url": "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1", "username": "sa", "password": "sa"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun portOf(context: ConfigurableApplicationContext): Int =
        Integer.parseInt(checkNotNull(context.environment.getProperty("local.server.port")))

    /**
     * Waits for [instanceText]'s statement from instance G to queue on [table]'s lock, then for it to leave the
     * waiters while that lock is still granted; returns queued-to-gone in ms. A red run says which never happened.
     */
    private fun cancelledWhileHeld(
        watcher: Connection,
        what: String,
        instanceText: String,
        table: String,
        mode: String,
        diagnosis: ManagedLakeReadsHeldLockDiagnosis,
        whileQueued: () -> Unit = {},
    ): Long {
        diagnosis.await(
            watcher,
            Duration.ofSeconds(BOUND_SLACK_SECONDS),
            "$what queuing on $table",
            instanceText,
        )
        val queuedAt = System.nanoTime()
        whileQueued()
        val deadline = queuedAt + Duration.ofSeconds(FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS).toNanos()
        while (System.nanoTime() < deadline && waitingOnLock(watcher, instanceText)) Thread.sleep(WATCH_MILLIS)
        val goneAt = System.nanoTime()
        withClue("$what was still waiting on $table after ${FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS}s: no bound cancelled it") {
            waitingOnLock(watcher, instanceText) shouldBe false
        }
        withClue("$what left the waiters only because the lock on $table went") { lockGranted(watcher, table, mode) shouldBe true }
        println("HELD_LOCK_CANCELLED what=$what queued_nano=$queuedAt gone_nano=$goneAt")
        return Duration.ofNanos(goneAt - queuedAt).toMillis()
    }

    private fun waitingOnLock(
        watcher: Connection,
        statementText: String,
        application: String = APP_G,
    ): Boolean =
        watcher
            .prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock' AND query LIKE ?",
            ).use { ps ->
                ps.setString(1, application)
                ps.setString(2, statementText)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }

    /**
     * #841 R4 — the backends holding ([granted]) or waiting on the managed lake's datasource prerequisite key, optionally
     * only [application]'s or only backend [pid]'s: the two-int advisory lock `(841001, subkey)`, read on [watcher].
     */
    private fun lakeKeyLocks(
        watcher: Connection,
        granted: Boolean,
        application: String? = null,
        pid: Int? = null,
    ): Int =
        watcher
            .prepareStatement(
                """
                SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
                 WHERE l.locktype = 'advisory' AND l.classid::bigint = ? AND l.objid::bigint = ? AND l.objsubid = 2
                   AND l.granted = ? AND (CAST(? AS text) IS NULL OR a.application_name = ?) AND (CAST(? AS int) IS NULL OR l.pid = ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, DATASOURCE_LOCK_SPACE)
                ps.setLong(2, LAKE_KEY_SUBKEY.toLong() and UNSIGNED_INT_MASK)
                ps.setBoolean(3, granted)
                ps.setObject(4, application, java.sql.Types.VARCHAR)
                ps.setObject(5, application, java.sql.Types.VARCHAR)
                ps.setObject(6, pid, java.sql.Types.INTEGER)
                ps.setObject(7, pid, java.sql.Types.INTEGER)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }

    /** Takes the managed lake's datasource prerequisite key on [connection]'s open transaction, as the guard does. */
    private fun holdLakeKey(connection: Connection) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)").use { ps ->
            ps.setInt(1, DATASOURCE_LOCK_SPACE.toInt())
            ps.setInt(2, LAKE_KEY_SUBKEY)
            ps.executeQuery().close()
        }
    }

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT pg_backend_pid()").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    /** Whether one of [application]'s backends is RUNNING [statementText] (waiting on a lock or not). */
    private fun statementActive(
        watcher: Connection,
        statementText: String,
        application: String = APP_G,
    ): Boolean =
        watcher
            .prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND state = 'active' AND query LIKE ?",
            ).use { ps ->
                ps.setString(1, application)
                ps.setString(2, statementText)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }

    /** The regrant step's failure WARNs logged since event [since] of [capture]. */
    private fun regrantFailures(
        capture: SnapshotListAppender,
        since: Int,
    ): List<String> =
        capture
            .events()
            .drop(since)
            .map { it.formattedMessage.substringBefore(" message=") }
            .filter { it.startsWith("event=lake.freshness_tick_failed step=regrant ") }

    /** A live workspace inserted by SQL — one lacking the managed grant, so the repair has a row to change. */
    private fun sqlWorkspace(name: String): UUID =
        UUID.randomUUID().also { id ->
            fixture.metadata {
                it.createStatement().execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$id', '$name', 'G841')")
            }
        }

    private fun lockGranted(
        watcher: Connection,
        table: String,
        mode: String,
    ): Boolean =
        watcher
            .prepareStatement(
                "SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation WHERE c.relname = ? AND l.mode = ? AND l.granted",
            ).use { ps ->
                ps.setString(1, table)
                ps.setString(2, mode)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }

    // ------------------------------------------------------------- the metadata database, directly

    private fun renameLifecycleRow(
        from: String,
        to: String,
    ) {
        fixture.metadata { connection ->
            connection.prepareStatement("UPDATE lake_tables_managed SET name = ? WHERE name = ?").use { ps ->
                ps.setString(1, to)
                ps.setString(2, from)
                ps.executeUpdate() shouldBe 1
            }
        }
    }

    /** The catalog row renamed under leases on its old AND new names (the V53 fence checks both on a rename). */
    private fun renameCatalogRow(
        from: String,
        to: String,
    ) {
        fixture.metadata { connection ->
            underLease(connection, leaseNames = listOf("sales.$from", "sales.$to")) {
                connection
                    .prepareStatement(
                        "UPDATE iceberg_tables SET table_name = ? WHERE catalog_name = ? AND table_namespace = 'sales' AND table_name = ?",
                    ).use { ps ->
                        ps.setString(1, to)
                        ps.setString(2, LAKE)
                        ps.setString(3, from)
                        ps.executeUpdate() shouldBe 1
                    }
            }
        }
    }

    private data class ManagedRow(
        val dialect: String,
        val readonly: Boolean,
        val jdbcUrl: String,
        val properties: String,
        val displayName: String,
    )

    private fun managedRow(): ManagedRow? =
        fixture.metadata { connection ->
            connection
                .prepareStatement(
                    "SELECT dialect, is_readonly, jdbc_url, CAST(properties_json AS text), display_name FROM datasources " +
                        "WHERE name = ? AND NOT is_deleted",
                ).use { ps ->
                    ps.setString(1, LAKE)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            ManagedRow(
                                rs.getString(1),
                                rs.getBoolean(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                            )
                        }
                    }
                }
        }

    private fun dialectOf(name: String): String? =
        fixture.metadata { connection ->
            connection.prepareStatement("SELECT dialect FROM datasources WHERE name = ?").use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun grantedWorkspaces(): Set<UUID> =
        fixture.metadata { connection ->
            connection.prepareStatement("SELECT workspace_id FROM datasource_workspaces WHERE datasource_name = ?").use { ps ->
                ps.setString(1, LAKE)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getObject(1, UUID::class.java) else null }.toSet() }
            }
        }

    private fun grantedWorkspaceNames(): Set<String> =
        fixture.metadata { connection ->
            connection
                .prepareStatement(
                    "SELECT w.name FROM datasource_workspaces g JOIN workspaces w ON w.id = g.workspace_id WHERE g.datasource_name = ?",
                ).use { ps ->
                    ps.setString(1, LAKE)
                    ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toSet() }
                }
        }

    /** Who wrote the managed grant for [workspace], and whether it was written in the very transaction that created the workspace. */
    private data class GrantProvenance(
        val byUserId: UUID,
        val sameTransaction: Boolean,
    )

    private fun managedGrantProvenance(workspace: String): GrantProvenance? =
        fixture.metadata { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT g.granted_by, (g.granted_at = w.created_at) AS same_tx
                      FROM datasource_workspaces g JOIN workspaces w ON w.id = g.workspace_id
                     WHERE g.datasource_name = ? AND w.name = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, LAKE)
                    ps.setString(2, workspace)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) GrantProvenance(rs.getObject(1, UUID::class.java), rs.getBoolean(2)) else null
                    }
                }
        }

    private fun systemActorId(): UUID = fixture.metadata { systemActor(it) }

    private fun revokeAuditRows(datasource: String): Int =
        fixture.metadata { connection ->
            connection
                .prepareStatement("SELECT COUNT(*) FROM audit_log WHERE event = 'datasource.revoked' AND details_json ->> 'datasource' = ?")
                .use { ps ->
                    ps.setString(1, datasource)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    /** Every grant or revoke audit row on the instance: the events a person's grant surface writes. */
    private fun grantAuditRows(): Int =
        fixture.metadata { connection ->
            connection.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM audit_log WHERE event IN ('datasource.granted', 'datasource.revoked')").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** A key minted by the signed-in super admin in [workspace] over REST; the plaintext is returned, never logged. */
    private fun mintMcpKeyIn(
        onPort: Int,
        workspace: String,
    ): String =
        given()
            .port(onPort)
            .contentType(ContentType.JSON)
            .asSession(E2eSession.jwt(fixture.secret, ADMIN_USER_ID, "e2e-managed-lake@datapipelines.test", workspace))
            .body("""{"name": "k823", "kind": "mcp", "role": "author"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("data.key")

    private fun registryLocation(): String? =
        fixture.metadata { connection ->
            connection
                .prepareStatement("SELECT location FROM lake_tables WHERE datasource_id = ? AND name = 'trips' AND origin = 'managed'")
                .use { ps ->
                    ps.setString(1, LAKE)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
        }

    /** Keep P4's projection repair from helping a catalog-only proof; ordinary catalog reads remain free. */
    private fun withHeldProjection(block: (String) -> Unit) {
        fixture.metadata { connection ->
            connection.autoCommit = false
            try {
                val projected =
                    connection
                        .prepareStatement(
                            "SELECT location FROM lake_tables WHERE datasource_id = ? AND name = 'trips' " +
                                "AND origin = 'managed' FOR UPDATE",
                        ).use { statement ->
                            statement.setString(1, LAKE)
                            statement.executeQuery().use { rows ->
                                check(rows.next()) { "the managed projection fixture must exist" }
                                requireNotNull(rows.getString(1))
                            }
                        }
                block(projected)
            } finally {
                connection.rollback()
            }
        }
    }

    private fun moveCatalogPointer(
        from: String,
        to: String,
    ) {
        fixture.metadata { connection ->
            underLease(connection) {
                connection
                    .prepareStatement(
                        "UPDATE iceberg_tables SET metadata_location = ?, previous_metadata_location = ? " +
                            "WHERE catalog_name = ? AND table_namespace = 'sales' AND table_name = 'trips' AND metadata_location = ?",
                    ).use { ps ->
                        ps.setString(1, to)
                        ps.setString(2, from)
                        ps.setString(3, LAKE)
                        ps.setString(4, from)
                        ps.executeUpdate() shouldBe 1
                    }
            }
        }
    }

    private fun awaitTrue(
        budget: Duration,
        what: String? = null,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + budget.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        if (what == null) {
            condition() shouldBe true
        } else {
            withClue("$what: not observed within ${budget.toMillis()} ms") { condition() shouldBe true }
        }
    }

    companion object {
        private const val TAKEN_NAME = "lake_taken"
        private const val CREATED_WS = "created_823"
        private const val ORDINARY = "ordinary_823"
        private const val MANAGED_CODE = "datasource.managed"
        private const val TEMPLATE_ID = "test/managed_lake_count.sql"
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private val DEFAULT_WORKSPACE_ID = UUID.fromString(DEFAULT_WORKSPACE)

        private const val PREVIOUS = "$METADATA/00001-7c81713e-1543-46ec-b6f4-6b3875c8b8c3.metadata.json"
        private const val ICEBERG_ROWS = 2000

        /** B's 2 s freshness tick: the only thing that can retire B's generation within this test (see A4). */
        private const val B_TICK_SECONDS = 2L

        private const val POLL_MILLIS = 500L

        /** G (#818b A): a 2 s tick; the bound is the issue's 5 s, a literal (never read back from the class under test). */
        private const val HELD_TICK_SECONDS = 2L
        private const val FRESHNESS_BOUND_SECONDS = 5L

        /** Queued-to-gone: at least the bound less one watch poll and the queue's own detection lag, at most bound + slack. */
        private val CANCEL_WINDOW_MS = (FRESHNESS_BOUND_SECONDS * 1000 - 1000)..((FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS) * 1000)
        private const val CATALOG_READ_TEXT = "%SELECT t.namespace, t.name, t.format%FROM lake_tables t%"
        private const val REGRANT_TEXT = "%INSERT INTO datasource_workspaces%FROM workspaces w%"

        /**
         * #841 R4: the managed lake's datasource prerequisite key as the writer guard takes it — lock space 841001 and
         * the first four bytes (big-endian) of SHA-256 of the canonical name — restated here, never read from the class
         * under test.
         */
        private const val DATASOURCE_LOCK_SPACE = 841_001L
        private val LAKE_KEY_SUBKEY: Int =
            java.nio.ByteBuffer
                .wrap(
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(LAKE.toByteArray(Charsets.UTF_8)),
                ).int
        private const val UNSIGNED_INT_MASK = 0xFFFF_FFFFL

        /** #841 R4 phase 3: how long the key is held after G queued on it — half the bound — and the step's allowance. */
        private const val KEY_HOLD_MILLIS = 2_500L
        private const val STEP_ALLOWANCE_MILLIS = 1_500L

        /** #841 R4: the jobs' scheduler bean and its one thread, by the spec's names; how long a park may take and last. */
        private const val JOBS_SCHEDULER_BEAN = "taskScheduler"
        private const val JOBS_THREAD = "dp-scheduled"
        private const val PARK_WAIT_SECONDS = 30L
        private const val PARK_MAX_SECONDS = 300L
        private const val REAP_JOB = "co.datapipelines.web.config.DatasourcePoolReaperScheduler.reap"
        private const val FRESHNESS_JOB = "co.datapipelines.web.config.ManagedLakeFreshnessScheduler.tick"

        /** E (#818b B): a tick that runs twice inside the age bound, so a failed tick read is observed before expiry. */
        private const val OUTAGE_MAX_AGE_SECONDS = 30L
        private const val OUTAGE_TICK_SECONDS = 5L

        private const val AWAY = "trips_away"

        private const val APP_E = "dp818-e"

        private const val APP_H = "dp818-h"

        /** The shipped `protection-days` (7) × 86400 / 24: the window LakeRules holds H's sum to. */
        private const val H_PROTECTION_WINDOW_SECONDS = 7L * 3600L

        private const val APP_I1 = "dp818-i1"
        private const val APP_I2 = "dp818-i2"

        private const val APP_F = "dp818-f"
        private const val APP_G = "dp818-g"

        /**
         * #833: the build read's bound is the issue's 5 s, a literal (never read back from the class under test); the
         * window is queued-to-refused like A's, and the red observation is bounded at 15 s.
         */
        private const val APP_833 = "dp833-build"
        private const val BUILD_READ_BOUND_SECONDS = 5L
        private const val BUILD_READ_RED_SECONDS = 15L
        private const val MILLIS_PER_SECOND = 1_000L
        private val BUILD_READ_WINDOW_MS =
            LongRange(
                BUILD_READ_BOUND_SECONDS * MILLIS_PER_SECOND - MILLIS_PER_SECOND,
                (BUILD_READ_BOUND_SECONDS + BOUND_SLACK_SECONDS) * MILLIS_PER_SECOND,
            )
        private const val CAUSE_PRINT_CHARS = 160
        private val EXECUTION_BUDGET: Duration = Duration.ofSeconds(180)

        private const val ADMIN_USER_ID = "a11e0000-0000-0000-0000-000000000818"
        private val ADMIN_SESSION get() = E2eSession.jwt(fixture.secret, ADMIN_USER_ID, "e2e-managed-lake@datapipelines.test")

        /** This class's share of the family's code and its own database, MinIO and OIDC stub (#982); A reads them too. */
        private val fixture = ManagedLakeReadsFixture("managed_lake_reads", ManagedLakeReadsE2eTest::class)

        /** The OTHER instances this suite boots; B and F live for the class, C, D, E and G only for their test. */
        private var instanceB: ConfigurableApplicationContext? = null
        private var instanceF: ConfigurableApplicationContext? = null
        private var portB: Int = 0
        private var pipelineId: String = ""

        /** After A's Flyway (a static @BeforeAll runs before it): idempotent. */
        private fun seedAuthRows() {
            fixture.metadata { connection ->
                connection.createStatement().use {
                    it.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER_ID', 'e2e-managed-lake@datapipelines.test', 'E2E Managed Lake', 'test',
                                'e2e-managed-lake-sub', TRUE, TRUE)
                        ON CONFLICT (id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun closeInstances() {
            instanceB?.close()
            instanceF?.close()
            fixture.oidc.close()
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            fixture.bootProperties(A_MAX_AGE_SECONDS, A_TICK_SECONDS).forEach { (key, value) -> registry.add(key) { value } }
        }
    }
}
