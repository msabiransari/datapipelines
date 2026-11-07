package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
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
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/**
 * #818 (dp-lake P5, design record §6, §10, §15a): the managed lake's READ side in the booted application, with
 * `datapipelines.lake.enabled=true` against a real object store (an own MinIO) and the real catalog schema.
 *
 * Four boots share one metadata database (a scratch database, so no other suite's cached context sees the managed
 * row) and one store:
 *
 * - **A**, this class's `@SpringBootTest` context: the managed row is created and granted at boot; REST and UI writes
 *   to it are refused (`datasource.managed`); a workspace created after boot is granted within one tick; and
 *   **proof (a)**: with no catalog movement (so the tick retires nothing), every lease is served by a generation
 *   younger than `reads.max-generation-age-seconds` (A runs a 6 s bound) and the generation is replaced.
 * - **B**, a second full boot after a table was defined with no registry row: B's registrar BACKFILLS the row, and
 *   **A4**: the catalog pointer is moved by a direct UPDATE under a lease (simulating #799's `settle`, which P5 does
 *   not own) — nothing is published on the invalidation channel, B's age bound is 900 s, so only B's 2 s freshness
 *   tick can make B's next lease read the new snapshot.
 * - **C**, a boot whose `lake.name` is held by an ordinary datasource: refused with `lake.boot.datasource_name_taken`.
 * - **D**, a boot with the lake disabled: the managed row stays, and a read of a managed table is refused
 *   `lake.table.unavailable` with reason `lake.disabled`.
 * - **G** (#818b A): the freshness tick's two statements, each held behind a lock on the real scheduler, are
 *   cancelled at the tick's statement bound while the lock is still granted, and the reaper then runs.
 * - **E** (#818b B): a catalog outage leaves E's live generation serving until its age bound; past it the rebuild
 *   is refused `lake.unavailable` and nothing is installed.
 * - **F** (#818b B): lifecycle moves, each judged against the generation the lease came from (F's tick and age
 *   bound are both longer than the test, so only an explicit retire builds a new one).
 * - **H** (#818c C, record §10 RULING 4 amended 2026-11-05): an executing reader on a generation nobody borrows from
 *   again is retired by the real reaper (on `dp-reaper` since #818d) past its age bound and hard-closed past its
 *   ceiling, inside `age + 2 × reaper delay + ceiling + reader termination`.
 * - **I1–I3** (#818d, record §10 RULING 4, second amendment): the same timeline under adverse conditions on the real
 *   scheduler — the metadata pool exhausted, the metadata Postgres paused, and the first generation's close held —
 *   each still inside the bound, because the reaper owns its thread and hands every close away.
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
@Suppress("LargeClass") // one managed-read suite over shared booted instances and fixtures; a split boots its contexts twice
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
            { row.properties shouldNotContain WRITER_SECRET },
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
    fun `a workspace created after boot is granted the managed lake within one tick`() {
        val workspace = UUID.randomUUID()
        metadata {
            it.createStatement().execute(
                "INSERT INTO workspaces (id, name, display_name) VALUES ('$workspace', 'later_ws', 'Later')",
            )
        }

        awaitTrue(Duration.ofSeconds(A_TICK_SECONDS * 4)) { workspace in grantedWorkspaces() }
    }

    @Test
    @Order(3)
    fun `a table defined while the instance was down is backfilled by the next boot's registrar`() {
        defineManagedTable(PREVIOUS)
        registryLocation().shouldBe(null)

        instanceB = boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = B_TICK_SECONDS)
        portB = Integer.parseInt(checkNotNull(instanceB?.environment?.getProperty("local.server.port")))

        registryLocation() shouldBe PREVIOUS
    }

    @Test
    @Order(4)
    fun `A4 - a catalog move nobody published reaches B's next lease through B's freshness tick alone`() {
        pipelineId = createReadPipeline()
        val before = countOn(portB)
        before shouldBeInRange 1..(ICEBERG_ROWS - 1)

        // #799's settle, simulated: P5 does not own it. A direct UPDATE under a lease publishes nothing.
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
        // The lagging projection was never the source: the registry row still names the old file.
        registryLocation() shouldBe PREVIOUS
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

        val refused = shouldThrowAny { boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = B_TICK_SECONDS, name = TAKEN_NAME).close() }

        generateSequence(refused) { it.cause }.joinToString(" | ") { it.message.orEmpty() } shouldContain
            "lake.boot.datasource_name_taken"
        dialectOf(TAKEN_NAME) shouldBe "H2"
    }

    @Test
    @Order(7)
    fun `with the lake disabled the managed row stays and a managed table read is refused lake disabled`() {
        val disabled = boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = B_TICK_SECONDS, enabled = false)
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

    @Test
    @Order(8)
    fun `A - each freshness-tick statement is cancelled at its bound under a held lock, and the reaper then runs`() {
        val held = boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = HELD_TICK_SECONDS, application = APP_G, recordTicks = true)
        try {
            countOn(portOf(held)) shouldBe ICEBERG_ROWS
            val recorder = held.getBean(ScheduledJobsSchedulerE2eTest.TickRecorder::class.java)
            metadata { lock ->
                lock.autoCommit = false
                lock.createStatement().use {
                    it.execute("LOCK TABLE lake_tables_managed IN ACCESS EXCLUSIVE MODE")
                    it.execute("LOCK TABLE datasource_workspaces IN SHARE MODE")
                }
                try {
                    metadata { watcher ->
                        val read =
                            cancelledWhileHeld(watcher, "the catalog read", CATALOG_READ_TEXT, "lake_tables_managed", "AccessExclusiveLock")
                        val regrant = cancelledWhileHeld(watcher, "the regrant", REGRANT_TEXT, "datasource_workspaces", "ShareLock")
                        val reaps = recorder.countOf(REAP_JOB)
                        val ticks = recorder.countOf(FRESHNESS_JOB)
                        val regrantGoneAt = System.nanoTime()
                        // One scheduler thread: the reaper may wait its own cadence plus one more held tick (two bounded
                        // statements) behind the lock. That wait is claim 3's delay, measured here.
                        awaitTrue(Duration.ofSeconds(REAPER_CADENCE_SECONDS + 2 * FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS)) {
                            recorder.countOf(REAP_JOB) > reaps
                        }
                        val reaperDelay = Duration.ofNanos(System.nanoTime() - regrantGoneAt).toMillis()
                        awaitTrue(Duration.ofSeconds(HELD_TICK_SECONDS + 2 * FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS)) {
                            recorder.countOf(FRESHNESS_JOB) > ticks
                        }
                        val stillHeld =
                            lockGranted(watcher, "lake_tables_managed", "AccessExclusiveLock") &&
                                lockGranted(watcher, "datasource_workspaces", "ShareLock")
                        println(
                            "HELD_LOCK_TICK read_queued_to_gone_ms=$read regrant_queued_to_gone_ms=$regrant " +
                                "reaper_after_regrant_ms=$reaperDelay " +
                                "reaps_after=${recorder.countOf(
                                    REAP_JOB,
                                ) - reaps} ticks_after=${recorder.countOf(FRESHNESS_JOB) - ticks} " +
                                "locks_still_granted=$stillHeld",
                        )
                        assertAll(
                            { read shouldBeInRange CANCEL_WINDOW_MS },
                            { regrant shouldBeInRange CANCEL_WINDOW_MS },
                            { stillHeld shouldBe true },
                        )
                    }
                } finally {
                    lock.rollback()
                }
            }
        } finally {
            held.close()
        }
    }

    @Test
    @Order(9)
    fun `B - a catalog outage leaves the live generation serving until its age bound, then refuses and installs nothing`() {
        val outage =
            boot(maxAgeSeconds = OUTAGE_MAX_AGE_SECONDS, tickSeconds = OUTAGE_TICK_SECONDS, application = APP_E, recordTicks = true)
        try {
            val portE = portOf(outage)
            countOn(portE) shouldBe ICEBERG_ROWS
            val builtAt = generationBuiltAt(outage).shouldNotBeNull()
            val recorder = outage.getBean(ScheduledJobsSchedulerE2eTest.TickRecorder::class.java)
            metadata { it.createStatement().use { st -> st.execute("ALTER TABLE lake_tables_managed RENAME TO lake_tables_managed_away") } }
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
                metadata {
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
            setStatus("archived")
            withClue("active -> archived: the live generation was built active and still serves") { countOn(portF) shouldBe ICEBERG_ROWS }
            retireManagedPool(lifecycle()) shouldBe true
            refusalOn(portF).also {
                it shouldContain "lake.table.unavailable"
                it shouldContain "status archived"
            }
        } finally {
            setStatus("active")
        }
    }

    @Test
    @Order(11)
    fun `B - archived to active - refused with the leased generation's reason until it is replaced, then served`() {
        val portF = lifecycleReady(status = "archived")
        try {
            refusalOn(portF) shouldContain "status archived"
            setStatus("active")
            withClue("archived -> active: the leased generation was built archived, so it still refuses, never an engine error") {
                refusalOn(portF).also {
                    it shouldContain "lake.table.unavailable"
                    it shouldContain "status archived"
                }
            }
            retireManagedPool(lifecycle()) shouldBe true
            countOn(portF) shouldBe ICEBERG_ROWS
        } finally {
            setStatus("active")
        }
    }

    @Test
    @Order(12)
    fun `B - a deprecated table stays readable`() {
        val portF = lifecycleReady(status = "deprecated")
        try {
            countOn(portF) shouldBe ICEBERG_ROWS
        } finally {
            setStatus("active")
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
        // The projection row still names PREVIOUS (see A4): a fallback to it would serve the older snapshot.
        registryLocation() shouldBe PREVIOUS
        renameCatalogRow(from = "trips", to = AWAY)
        try {
            refusalOn(lifecycleReady()) shouldContain "no catalog row"
        } finally {
            renameCatalogRow(from = AWAY, to = "trips")
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
            boot(
                maxAgeSeconds = H_MAX_AGE_SECONDS,
                tickSeconds = H_TICK_SECONDS,
                application = APP_H,
                extra = listOf("datapipelines.datasources.retire-ceiling-seconds" to H_CEILING_SECONDS.toString()),
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
                awaitUntil(retireBy, "the reaper retires the generation with no lease") { generationBuiltAt(reader) == null }
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
        val reader = bootReaderProfile(APP_I1)
        try {
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
        val reader = bootReaderProfile(APP_I2)
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

    /**
     * #818d I3 (item 6) — a close that does not return must cost a `dp-pool-close` thread, never a reap. The instance
     * boots with a test seam on its `PoolCloser` ([DelayFirstManagedClose]): the FIRST managed generation handed to
     * the closer is passed on to the production closer wrapped so that its close waits on a latch the test holds.
     * Gen 1 is leased once and returned; the reaper retires it at its age bound and hands its close away, where it
     * hangs. Gen 2 is then built by a lease that runs the long reader: the reaper must retire and hard-close gen 2 in
     * bound while gen 1's close is still hung, and `datasource.pool_close_stuck` must name gen 1 once, no earlier than
     * the deadline after its hand-off. Red with the synchronous close restored (the reaper then hangs in gen 1's close
     * and never reaches gen 2), and with the `scheduler` attribute removed (the retirement lines' thread).
     */
    @Test
    @Order(18)
    @Suppress("LongMethod") // one timeline on the real scheduler is the witness; its instants are asserted together
    fun `I3 - a first close that never returns does not delay the next generation's retirement or close`() {
        val release = CountDownLatch(1)
        val seam = DelayFirstManagedClose(release)
        val reader = bootReaderProfile(APP_I3, initializers = listOf(seam.initializer()))
        val logger = LoggerFactory.getLogger(PRODUCT_LOGGERS) as Logger
        val capture = SnapshotListAppender()
        logger.addAppender(capture)
        try {
            leaseManaged(reader).close()
            val gen1BuiltAt = generationBuiltAt(reader).shouldNotBeNull()
            val gen1RetireBy = gen1BuiltAt.plusSeconds(H_MAX_AGE_SECONDS + REAPER_CADENCE_SECONDS + BOUND_SLACK_SECONDS)
            awaitUntil(gen1RetireBy, "gen 1 retired and handed off") {
                seam.handedOffAt.get() != null
            }
            val handedOffAt = seam.handedOffAt.get().shouldNotBeNull()
            val lease = leaseManaged(reader)
            val builtAt = generationBuiltAt(reader).shouldNotBeNull()
            val statement = lease.prepareStatement(H_LONG_QUERY)
            val run = LongRead(statement)
            try {
                run.start()
                awaitBurning(run)
                val timeline = observeRetirementAndClose(reader, capture, run, builtAt, "I3 first close held")
                val stuckBy = handedOffAt.plusSeconds(POOL_CLOSE_DEADLINE_SECONDS + STUCK_SLACK_SECONDS)
                awaitUntil(stuckBy, "the stuck close is reported") { linesOf(capture, "event=datasource.pool_close_stuck").isNotEmpty() }
                val stuck = linesOf(capture, "event=datasource.pool_close_stuck")
                println(
                    "I3_TIMELINE gen1_built=$gen1BuiltAt gen1_handed_off=$handedOffAt gen1=${seam.generation.get()} " +
                        "gen1_close_released=${release.count == 0L} ${timeline.text} " +
                        "stuck=${stuck.map { "${it.at} ${it.thread} ${it.text}" }}",
                )
                assertAll(
                    { timeline.assertInBound() },
                    { withClue("gen 1's close was still held when gen 2 closed") { release.count shouldBe 1L } },
                    { stuck.size shouldBe 1 },
                    { stuck.single().text shouldContain "datasource=$LAKE generation=${seam.generation.get()} " },
                    { (stuck.single().at >= handedOffAt.plusSeconds(POOL_CLOSE_DEADLINE_SECONDS)) shouldBe true },
                )
            } finally {
                runCatching { statement.cancel() }
                run.thread.join(CLEANUP_JOIN_MS)
                runCatching { lease.close() }
            }
        } finally {
            release.countDown()
            logger.detachAppender(capture)
            capture.stop()
            reader.close()
        }
    }

    /** H's profile (short age, tick, ceiling) under its own `ApplicationName`. */
    private fun bootReaderProfile(
        application: String,
        initializers: List<ApplicationContextInitializer<ConfigurableApplicationContext>> = emptyList(),
    ): ConfigurableApplicationContext =
        boot(
            maxAgeSeconds = H_MAX_AGE_SECONDS,
            tickSeconds = H_TICK_SECONDS,
            application = application,
            extra = listOf("datapipelines.datasources.retire-ceiling-seconds" to H_CEILING_SECONDS.toString()),
            initializers = initializers,
        )

    /** An adverse condition: how to undo it, and a sample proving it held (the largest sample must be at least 1). */
    private class Adversity(
        val release: () -> Unit,
        val witness: () -> Int,
    )

    /** E.1: the physical query outlives close; database-clock reader rows must outlive it too. */
    @Test
    @Order(19)
    fun `E1 - trickling Parquet GET remains pinned while the real closer cannot finish`() {
        verifyTrickle(shutdown = false)
    }

    @Test
    @Order(23)
    fun `E1shutdown - context close keeps the heartbeat alive until physical readers close`() {
        verifyTrickle(shutdown = true)
    }

    @Suppress("LongMethod", "NestedBlockDepth") // one physical-reader timeline, including context shutdown and the final release oracle
    private fun verifyTrickle(shutdown: Boolean) {
        ensureReaderFixture()
        ReaderTrickleProxy(minio.host, minio.getMappedPort(MINIO_PORT)).use { proxy ->
            val reader =
                boot(
                    maxAgeSeconds = B_MAX_AGE_SECONDS,
                    tickSeconds = LIFECYCLE_TICK_SECONDS,
                    application = "reader-trickle",
                    extra =
                        listOf(
                            "datapipelines.lake.storage.endpoint" to proxy.endpoint,
                            "datapipelines.datasources.retire-ceiling-seconds" to "1",
                        ),
                )
            val lease = leaseManaged(reader)
            recordReaderInputs(reader, lease)
            val run = LongRead(lease.prepareStatement("SELECT sum(fare) FROM $LAKE.sales.trips"))
            var contextClose: Thread? = null
            try {
                val registry = reader.getBean("datasourceRegistry")
                val generation = readerGeneration(reader)
                val ids = readerRows().filterKeys { it == generation }
                ids.isNotEmpty() shouldBe true
                val expectedPins = readerViewLocations(reader)
                expectedPins.isNotEmpty() shouldBe true
                withClue("E1 pins match the generation view metadata locations") {
                    readerPinLocations(generation) shouldBe expectedPins
                }
                installHeartbeatWitness()
                proxy.arm()
                run.start()
                proxy.arrived.await(ReaderTrickleProxy.START_SECONDS, TimeUnit.SECONDS) shouldBe true
                retireManagedPool(reader) shouldBe true
                Thread.sleep(2000)
                startRetiredClose(registry, generation)
                if (shutdown) {
                    contextClose =
                        Thread({ reader.close() }, "reader-context-close").apply {
                            isDaemon = true
                            start()
                        }
                }
                val closeStarted = databaseNow()
                Thread.sleep(ReaderTrickleProxy.WAIT_SECONDS * 1000)
                val stillLive = readerRows().filterKeys { it in ids }
                println(
                    "READER_TRICKLE shutdown=$shutdown close_started=$closeStarted observed=${databaseNow()} bytes=${proxy.bytes.get()} " +
                        "rows=$stillLive reader_alive=${run.thread.isAlive}",
                )
                withClue("E1 reader still executing above curl low-speed threshold") { run.thread.isAlive shouldBe true }
                if (shutdown) contextClose?.isAlive shouldBe true
                withClue("E1 every snapshot pin stays live by database clock during blocked close") { stillLive.keys shouldBe ids.keys }
                stillLive.values.all { it } shouldBe true
                readerPinLocations(generation) shouldBe expectedPins
                val physicalCloses = Thread.getAllStackTraces().keys.filter { it.name.startsWith("dp-pool-interrupt-$generation-") }
                withClue("E1 real physical close remains blocked") { physicalCloses.isNotEmpty() shouldBe true }
                val beats = heartbeatWitness(generation, closeStarted)
                println("READER_HEARTBEATS generation=$generation rounds=$beats close_threads=${physicalCloses.map { it.name }}")
                beats shouldBeGreaterThanOrEqual 19
                finishTrickle(proxy, run, physicalCloses, generation)
                contextClose?.join(CLEANUP_JOIN_MS)
                contextClose?.isAlive shouldBe if (shutdown) false else null
            } finally {
                proxy.close()
                run.thread.join(CLEANUP_JOIN_MS)
                runCatching { lease.close() }
                contextClose?.join(CLEANUP_JOIN_MS)
                reader.close()
            }
        }
    }

    /** E.2: a metadata lock refuses the build before a single connection can be served. */
    @Test
    @Order(20)
    fun `E2 - a refused reader pin never publishes the generation`() {
        ensureReaderFixture()
        val reader = boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = LIFECYCLE_TICK_SECONDS, application = "reader-pin-refusal")
        try {
            metadata { connection ->
                connection.autoCommit = false
                connection.createStatement().use { it.execute("LOCK TABLE lake_leases IN SHARE MODE") }
                try {
                    val failure = shouldThrowAny { leaseManaged(reader) }
                    val causes =
                        generateSequence(
                            failure as Throwable?,
                        ) { it.cause }.map { it.javaClass.simpleName + ":" + it.message }.joinToString(" <- ")
                    println("READER_PIN_REFUSED database_now=${databaseNow()} causes=$causes")
                    causes shouldContain "Managed lake reader protection could not be acquired"
                    generationBuiltAt(reader) shouldBe null
                } finally {
                    connection.rollback()
                }
            }
            leaseManaged(reader).use { lease ->
                readerRows().isNotEmpty() shouldBe true
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
        ensureReaderFixture()
        val reader = boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = LIFECYCLE_TICK_SECONDS, application = "reader-dead-keeper")
        val lease = leaseManaged(reader)
        val run = LongRead(lease.prepareStatement(H_LONG_QUERY))
        try {
            run.start()
            awaitBurning(run)
            (reader.getBean("readerLeaseTaskScheduler") as ThreadPoolTaskScheduler).shutdown()
            val generation = readerGeneration(reader)
            val pins = setOf(generation)
            val expiry =
                metadata { connection ->
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
            println("READER_TTL before=${databaseNow()} expiry=$expiry reader_alive=${run.thread.isAlive}")
            readerRows().filterKeys { it in pins }.values.all { it } shouldBe true
            val delay = Duration.between(databaseNow(), expiry.plusSeconds(1)).toMillis().coerceAtLeast(0)
            Thread.sleep(delay)
            val after = readerRows().filterKeys { it in pins }
            withClue("E3a rows lapse at database expiry, with a physical reader still executing") {
                run.thread.isAlive shouldBe true
                after.values.none { it } shouldBe true
            }
            withClue("a delayed keeper cannot admit a new query after its last known protection has lapsed") {
                shouldThrowAny { leaseManaged(reader) }
            }
            metadata { connection ->
                connection.createStatement().use {
                    it.executeUpdate(
                        "UPDATE lake_leases SET expires_at=now()+interval '80 seconds' WHERE kind='reader' " +
                            "AND operation_id='$generation' AND expires_at>now() AND deadline_at>now()",
                    ) shouldBe
                        0
                }
            }
            println("READER_TTL after=${databaseNow()} expiry=$expiry rows=$after reader_alive=${run.thread.isAlive}")
        } finally {
            runCatching { run.thread.interrupt() }
            reader.close()
            run.thread.join(CLEANUP_JOIN_MS)
            runCatching { lease.close() }
        }
    }

    /** E.3b: short and long metadata outages differ in pin survival, while both retire new admission. */
    @Test
    @Order(22)
    fun `E3b - a paused metadata server is bounded and lost reader protection is reported`() {
        pausedReader(40, true)
        pausedReader(90, false)
    }

    private fun pausedReader(
        pauseSeconds: Long,
        remainsPinned: Boolean,
    ) {
        ensureReaderFixture()
        val reader =
            boot(
                maxAgeSeconds = B_MAX_AGE_SECONDS,
                tickSeconds = LIFECYCLE_TICK_SECONDS,
                application = "reader-paused-$pauseSeconds",
                extra = listOf("datapipelines.datasources.retire-ceiling-seconds" to "180"),
            )
        val lease = leaseManaged(reader)
        val generation = readerGeneration(reader)
        val run = LongRead(lease.prepareStatement(H_LONG_QUERY))
        val logger = LoggerFactory.getLogger(PRODUCT_LOGGERS) as Logger
        val capture = SnapshotListAppender()
        logger.addAppender(capture)
        val docker = DockerClientFactory.instance().client()
        var paused = false
        try {
            run.start()
            awaitBurning(run)
            val before = databaseNow()
            docker.pauseContainerCmd(SharedE2e.postgres.containerId).exec()
            paused = true
            Thread.sleep(pauseSeconds * 1000)
            val lost = linesOf(capture, "event=lake.reader_lease_lost").filter { "generation=$generation" in it.text }
            withClue("E3b bounded heartbeat must report loss while Postgres is still paused") { lost.size shouldBe 1 }
            generationBuiltAt(reader) shouldBe null
            run.thread.isAlive shouldBe true
            docker.unpauseContainerCmd(SharedE2e.postgres.containerId).exec()
            paused = false
            val pinned = readerRows()[generation] == true
            println(
                "READER_PAUSED before=$before after=${databaseNow()} pause_s=$pauseSeconds pinned=$pinned " +
                    "reader_alive=${run.thread.isAlive} lost=${lost.map { it.text }}",
            )
            withClue(
                "E3b one failed round cannot expire a live reader; a full TTL outage loses protection",
            ) { pinned shouldBe remainsPinned }
            val meters = reader.getBean(Class.forName("io.micrometer.core.instrument.MeterRegistry"))
            val search = meters.javaClass.getMethod("find", String::class.java).invoke(meters, "datapipelines.lake.reader_lease.lost")
            val counter =
                search.javaClass
                    .getMethod("counter")
                    .invoke(search)
                    .shouldNotBeNull()
            (Class.forName("io.micrometer.core.instrument.Counter").getMethod("count").invoke(counter) as Double) shouldBe 1.0
        } finally {
            if (paused) docker.unpauseContainerCmd(SharedE2e.postgres.containerId).exec()
            logger.detachAppender(capture)
            capture.stop()
            reader.close()
            run.thread.join(CLEANUP_JOIN_MS)
            runCatching { lease.close() }
        }
    }

    /** Acceptance methods can also run alone during falsification. */
    private fun ensureReaderFixture() {
        val present =
            metadata { connection ->
                connection.createStatement().use {
                    it
                        .executeQuery(
                            "SELECT EXISTS(SELECT 1 FROM iceberg_tables WHERE table_namespace='sales' AND table_name='trips')",
                        ).use { rows ->
                            rows.next()
                            rows.getBoolean(1)
                        }
                }
            }
        if (!present) defineManagedTable(CURRENT)
        setStatus("active")
    }

    private fun finishTrickle(
        proxy: ReaderTrickleProxy,
        run: LongRead,
        closes: List<Thread>,
        generation: UUID,
    ) {
        proxy.close()
        run.thread.join(CLEANUP_JOIN_MS)
        run.thread.isAlive shouldBe false
        closes.forEach { it.join(CLEANUP_JOIN_MS) }
        closes.any { it.isAlive } shouldBe false
        val closedAt = databaseNow()
        awaitUntil(Instant.now().plusSeconds(10), "reader pins released after physical close") { generation !in readerRows() }
        val releasedAt = databaseNow()
        val elapsed = Duration.between(closedAt, releasedAt).toMillis()
        println("READER_RELEASE closed=$closedAt released=$releasedAt elapsed_ms=$elapsed")
        elapsed shouldBeLessThanOrEqual 5000L
    }

    private fun startRetiredClose(
        registry: Any,
        generation: UUID,
    ) {
        registry.javaClass.getMethod("reapRetiredPools").invoke(registry)
        awaitUntil(Instant.now().plusSeconds(ReaderTrickleProxy.START_SECONDS), "physical DuckDB close has begun") {
            Thread.getAllStackTraces().any { (thread, frames) ->
                thread.name.startsWith("dp-pool-interrupt-$generation-") &&
                    frames.any { it.className == "org.duckdb.DuckDBConnection" && it.methodName == "close" }
            }
        }
    }

    private fun recordReaderInputs(
        reader: ApplicationContext,
        lease: Connection,
    ) {
        println("READER_ENGINE database=${lease.metaData.databaseProductVersion} driver=${lease.metaData.driverVersion}")
        val keeper = reader.getBean("managedGenerationPins")
        val metadataAcquire = (reader.getBean("dataSource") as HikariDataSource).connectionTimeout
        val ttl = keeper.javaClass.getMethod("getTtl").invoke(keeper)
        println("READER_LEASE_INPUTS acquire_ms=$metadataAcquire ttl=$ttl")
    }

    private fun readerPool(reader: ApplicationContext): Any {
        val registry = reader.getBean("datasourceRegistry")
        val type = Class.forName("co.datapipelines.datasources.DatasourceRegistry")
        val datasource = type.getMethod("get", String::class.java).invoke(registry, LAKE)
        return requireNotNull(
            type.getMethod("poolFor", Class.forName("co.datapipelines.datasources.Datasource")).invoke(registry, datasource),
        )
    }

    private fun readerGeneration(reader: ApplicationContext): UUID {
        val pool = readerPool(reader)
        val id = Class.forName("co.datapipelines.datasources.pooling.ConnectionPool").getMethod("getGeneration").invoke(pool) as String
        return UUID.fromString(id)
    }

    private fun readerViewLocations(reader: ApplicationContext): Map<String, String> {
        val generation =
            Class
                .forName(
                    "co.datapipelines.datasources.pooling.ConnectionPool",
                ).getMethod("getManagedGeneration")
                .invoke(readerPool(reader))
        val locations =
            Class
                .forName("co.datapipelines.datasources.pooling.ManagedLakeGeneration")
                .getMethod("getLocations")
                .invoke(requireNotNull(generation)) as Map<*, *>
        return locations.entries
            .mapNotNull { (name, location) ->
                if (location == null) return@mapNotNull null
                require(name is String && location is String)
                name to location
            }.toMap()
    }

    private fun readerPinLocations(generation: UUID): Map<String, String> =
        metadata { connection ->
            connection
                .prepareStatement(
                    "SELECT split_part(name,'@',1),pinned_metadata_location FROM lake_leases WHERE kind='reader' AND operation_id=?",
                ).use { statement ->
                    statement.setObject(1, generation)
                    statement.executeQuery().use { rows ->
                        buildMap { while (rows.next()) put("$LAKE.${rows.getString(1)}", rows.getString(2)) }
                    }
                }
        }

    /** Independent database witness: only committed UPDATEs count, one transaction per keeper round. */
    private fun installHeartbeatWitness() {
        metadata { connection ->
            connection.createStatement().use { sql ->
                sql.execute("CREATE TABLE IF NOT EXISTS test_reader_beats (generation uuid, at timestamptz, tx bigint)")
                sql.execute("TRUNCATE test_reader_beats")
                sql.execute(
                    """
                    CREATE OR REPLACE FUNCTION test_reader_beat() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      INSERT INTO test_reader_beats VALUES (NEW.operation_id,clock_timestamp(),txid_current());
                      RETURN NEW;
                    END; $$
                    """.trimIndent(),
                )
                sql.execute("DROP TRIGGER IF EXISTS test_reader_beat ON lake_leases")
                sql.execute(
                    "CREATE TRIGGER test_reader_beat AFTER UPDATE ON lake_leases FOR EACH ROW " +
                        "WHEN (NEW.kind='reader') EXECUTE FUNCTION test_reader_beat()",
                )
            }
        }
    }

    private fun heartbeatWitness(
        generation: UUID,
        since: Instant,
    ): Int =
        metadata { connection ->
            connection.prepareStatement("SELECT count(DISTINCT tx) FROM test_reader_beats WHERE generation=? AND at>=?").use {
                it.setObject(1, generation)
                it.setObject(2, java.time.OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC))
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun databaseNow(): Instant =
        metadata { connection ->
            connection.createStatement().use {
                it.executeQuery("SELECT clock_timestamp()").use { rows ->
                    rows.next()
                    rows.getTimestamp(1).toInstant()
                }
            }
        }

    private fun readerRows(): Map<UUID, Boolean> =
        metadata { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT operation_id,bool_and(expires_at>now() AND deadline_at>now()) " +
                            "FROM lake_leases WHERE kind='reader' GROUP BY operation_id",
                    ).use { rows ->
                        buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getBoolean(2)) }
                    }
            }
        }

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

    /** What [observeRetirementAndClose] saw; [assertInBound] is H's tight fits and the record's bound. */
    private inner class ReapTimeline(
        val builtAt: Instant,
        val expired: List<Line>,
        val hardClosed: List<Line>,
        val ended: Boolean,
        val run: LongRead,
        val label: String,
    ) {
        val text: String
            get() =
                "built=$builtAt expired=${expired.map { "${it.at} ${it.thread}" }} " +
                    "hard_closed=${hardClosed.map { "${it.at} ${it.thread}" }} " +
                    "ended=${run.endedAt.get()} outcome=${run.outcome.get()} bound=${lifetimeBound()}"

        fun assertInBound() {
            withClue("$label: $text") {
                expired.size shouldBe 1
                expired.single().thread shouldBe REAPER_THREAD
                (expired.single().at >= builtAt.plusSeconds(H_MAX_AGE_SECONDS)) shouldBe true
                hardClosed.size shouldBe 1
                hardClosed.single().thread shouldBe REAPER_THREAD
                ended shouldBe true
                run.outcome.get().startsWith("failed:") shouldBe true
                Duration.between(builtAt, run.endedAt.get().shouldNotBeNull()).toMillis() shouldBeLessThanOrEqual lifetimeBound().toMillis()
            }
        }
    }

    /** Waits for the reaper's retirement of the generation built at [builtAt], then for the close to end [run]. */
    private fun observeRetirementAndClose(
        reader: ConfigurableApplicationContext,
        capture: SnapshotListAppender,
        run: LongRead,
        builtAt: Instant,
        label: String,
    ): ReapTimeline {
        val retireBy = builtAt.plusSeconds(H_MAX_AGE_SECONDS + REAPER_CADENCE_SECONDS + BOUND_SLACK_SECONDS)
        awaitUntil(retireBy, "$label: the reaper retires the generation with no lease") { generationBuiltAt(reader) == null }
        val expired =
            linesOf(capture, "event=lake.generation_expired").filter {
                "max_age_seconds=$H_MAX_AGE_SECONDS " in it.text && "trigger=reaper" in it.text && it.at >= builtAt
            }
        val retiredAt = expired.firstOrNull()?.at ?: Instant.now()
        val closeBy = retiredAt.plusSeconds(H_CEILING_SECONDS + REAPER_CADENCE_SECONDS + BOUND_SLACK_SECONDS)
        val ended = run.ended.await(Duration.between(Instant.now(), closeBy).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
        val hardClosed =
            linesOf(capture, "event=datasource.pool_hard_closed").filter {
                "ceiling_seconds=$H_CEILING_SECONDS " in it.text && it.at >= builtAt
            }
        return ReapTimeline(builtAt, expired, hardClosed, ended, run, label)
    }

    /** The staleness budget; these CPU-query regressions also finish inside it, unlike a trickling reader. */
    private fun lifetimeBound(): Duration = Duration.ofSeconds(H_MAX_AGE_SECONDS + 2 * REAPER_DELAY_SECONDS + H_CEILING_SECONDS)

    /**
     * I3's test seam: the application's `PoolCloser`, decorated. The first managed generation handed to it goes on to
     * the production closer (the `poolCloser` bean) wrapped so its close first waits for [release]; every other pool
     * goes on unchanged. Registered `@Primary`, so `DomainConfiguration.datasourceRegistry` takes it. Built from JDK
     * proxies because this module compiles against `app` only (the pool and closer types live in `datasources`).
     */
    private class DelayFirstManagedClose(
        private val release: CountDownLatch,
    ) {
        val handedOffAt = AtomicReference<Instant?>(null)
        val generation = AtomicReference<String?>(null)
        private val poolType = Class.forName(POOL_TYPE)
        private val closerType = Class.forName(CLOSER_TYPE)

        fun initializer(): ApplicationContextInitializer<ConfigurableApplicationContext> =
            ApplicationContextInitializer { context ->
                @Suppress("UNCHECKED_CAST")
                (context as GenericApplicationContext).registerBean(
                    "delayFirstManagedClose",
                    closerType as Class<Any>,
                    Supplier { closer(context) },
                    BeanDefinitionCustomizer { it.isPrimary = true },
                )
            }

        private fun closer(context: ApplicationContext): Any =
            Proxy.newProxyInstance(closerType.classLoader, arrayOf(closerType)) { proxy, method, args ->
                when (method.name) {
                    "close" -> closerType.getMethod("close", poolType).invoke(context.getBean("poolCloser"), delayedIfFirst(args!![0]))
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> "DelayFirstManagedClose"
                }
            }

        private fun delayedIfFirst(pool: Any): Any {
            val managed = poolType.getMethod("getManagedGeneration").invoke(pool) != null
            if (!managed || !generation.compareAndSet(null, poolType.getMethod("getGeneration").invoke(pool) as String?)) return pool
            handedOffAt.set(Instant.now())
            return Proxy.newProxyInstance(poolType.classLoader, arrayOf(poolType)) { _, method, args ->
                if (method.name == "close" && method.parameterCount == 0) release.await()
                method.invoke(pool, *(args ?: emptyArray()))
            }
        }
    }

    /** One captured line: its instant, thread and formatted text. */
    private data class Line(
        val at: Instant,
        val thread: String,
        val text: String,
    )

    private fun linesOf(
        capture: SnapshotListAppender,
        prefix: String,
    ): List<Line> = capture.events().filter { it.formattedMessage.startsWith(prefix) }.map(::lineOf)

    private fun lineOf(event: ILoggingEvent): Line = Line(event.instant, event.threadName, event.formattedMessage)

    /** A lease on [context]'s live managed generation, through the registry's own entry points (by name). */
    private fun leaseManaged(context: ApplicationContext): Connection {
        val registry = context.getBean("datasourceRegistry")
        val registryType = Class.forName("co.datapipelines.datasources.DatasourceRegistry")
        val datasource = registryType.getMethod("get", String::class.java).invoke(registry, LAKE).shouldNotBeNull()
        val pool = registryType.getMethod("poolFor", Class.forName("co.datapipelines.datasources.Datasource")).invoke(registry, datasource)
        return Class.forName("co.datapipelines.datasources.pooling.ConnectionPool").getMethod("leaseConnection").invoke(pool) as Connection
    }

    /** The CPU nanoseconds [run]'s thread burned once two reads apart show it rising: the engine is executing. */
    private fun awaitBurning(run: LongRead): Long {
        val threads = ManagementFactory.getThreadMXBean()
        val until = System.nanoTime() + Duration.ofSeconds(EXECUTING_BUDGET_SECONDS).toNanos()
        while (System.nanoTime() < until) {
            val before = threads.getThreadCpuTime(run.thread.threadId())
            Thread.sleep(EXECUTING_GAP_MILLIS)
            val after = threads.getThreadCpuTime(run.thread.threadId())
            withClue("the query ended before it was seen executing: ${run.outcome.get()}") { run.ended.count shouldBe 1L }
            if (before >= 0 && after - before >= Duration.ofMillis(EXECUTING_CPU_MIN_MILLIS).toNanos()) return after
        }
        error("the query thread never burned CPU within $EXECUTING_BUDGET_SECONDS s")
    }

    private fun awaitUntil(
        deadline: Instant,
        what: String,
        condition: () -> Boolean,
    ) {
        while (Instant.now().isBefore(deadline)) {
            if (condition()) return
            Thread.sleep(WATCH_MILLIS)
        }
        withClue("$what by $deadline") { condition() shouldBe true }
    }

    /** The long read on its own thread; [outcome] is `completed:<count>` or `failed:<class>: <message>`. */
    private class LongRead(
        private val statement: java.sql.PreparedStatement,
    ) {
        val ended = CountDownLatch(1)
        val endedAt = AtomicReference<Instant?>(null)
        val outcome = AtomicReference("running")
        val thread =
            Thread({
                try {
                    statement.executeQuery().use { rs ->
                        rs.next()
                        outcome.set("completed:${rs.getLong(1)}")
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    outcome.set("failed:${e.javaClass.simpleName}: ${e.message?.take(MESSAGE_CHARS)}")
                } finally {
                    endedAt.set(Instant.now())
                    ended.countDown()
                }
            }, "managed-long-read")

        fun start() = thread.start()
    }

    /** Instance F, booted once: its tick and age bound outlast the suite, so only [retireManagedPool] replaces a generation. */
    private fun lifecycle(): ConfigurableApplicationContext =
        instanceF
            ?: boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = LIFECYCLE_TICK_SECONDS, application = APP_F).also { instanceF = it }

    /** F's port, after the row is set to [status] (when given) and F's live generation, if any, is replaced. */
    private fun lifecycleReady(status: String? = null): Int {
        val context = lifecycle()
        status?.let { setStatus(it) }
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

    /** The registry's own clock for its live managed pool, read by name: integration-tests reaches app only. */
    private fun generationBuiltAt(context: ApplicationContext): Instant? {
        val registry = context.getBean("datasourceRegistry")
        return registry.javaClass.getMethod("generationBuiltAt", String::class.java).invoke(registry, LAKE) as Instant?
    }

    /** The registry's own retire, by name (the hook #799's settle and an operator use); true when a pool was live. */
    private fun retireManagedPool(context: ApplicationContext): Boolean {
        val registry = context.getBean("datasourceRegistry")
        return registry.javaClass.getMethod("retirePool", String::class.java).invoke(registry, LAKE) as Boolean
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
    ): Long {
        awaitTrue(Duration.ofSeconds(HELD_TICK_SECONDS + FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS)) {
            waitingOnLock(watcher, instanceText)
        }
        val queuedAt = System.nanoTime()
        val deadline = queuedAt + Duration.ofSeconds(FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS).toNanos()
        while (System.nanoTime() < deadline && waitingOnLock(watcher, instanceText)) Thread.sleep(WATCH_MILLIS)
        val goneAt = System.nanoTime()
        withClue("$what was still waiting on $table after ${FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS}s: no bound cancelled it") {
            waitingOnLock(watcher, instanceText) shouldBe false
        }
        withClue("$what left the waiters only because the lock on $table went") { lockGranted(watcher, table, mode) shouldBe true }
        return Duration.ofNanos(goneAt - queuedAt).toMillis()
    }

    private fun waitingOnLock(
        watcher: Connection,
        statementText: String,
    ): Boolean =
        watcher
            .prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock' AND query LIKE ?",
            ).use { ps ->
                ps.setString(1, APP_G)
                ps.setString(2, statementText)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
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

    /** A lifecycle status the V54 checks admit: each non-active status carries its own stamp, active carries none. */
    private fun setStatus(status: String) {
        metadata { connection ->
            connection
                .prepareStatement(
                    "UPDATE lake_tables_managed SET status = ?, " +
                        "deprecated_at = CASE WHEN ? = 'deprecated' THEN now() END, " +
                        "archived_at = CASE WHEN ? = 'archived' THEN now() END WHERE name = 'trips'",
                ).use { ps ->
                    ps.setString(1, status)
                    ps.setString(2, status)
                    ps.setString(3, status)
                    ps.executeUpdate() shouldBe 1
                }
        }
    }

    private fun renameLifecycleRow(
        from: String,
        to: String,
    ) {
        metadata { connection ->
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
        metadata { connection ->
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
        metadata { connection ->
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
        metadata { connection ->
            connection.prepareStatement("SELECT dialect FROM datasources WHERE name = ?").use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun grantedWorkspaces(): Set<UUID> =
        metadata { connection ->
            connection.prepareStatement("SELECT workspace_id FROM datasource_workspaces WHERE datasource_name = ?").use { ps ->
                ps.setString(1, LAKE)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getObject(1, UUID::class.java) else null }.toSet() }
            }
        }

    private fun registryLocation(): String? =
        metadata { connection ->
            connection
                .prepareStatement("SELECT location FROM lake_tables WHERE datasource_id = ? AND name = 'trips' AND origin = 'managed'")
                .use { ps ->
                    ps.setString(1, LAKE)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
        }

    /** A namespace, its lifecycle row and the catalog pointer (written under a lease, so the revision trigger runs). */
    private fun defineManagedTable(location: String) {
        metadata { connection ->
            val actor = systemActor(connection)
            val namespace = UUID.randomUUID()
            connection.createStatement().use {
                it.execute("INSERT INTO lake_namespaces (id, name, status, created_by) VALUES ('$namespace', 'sales', 'active', '$actor')")
                it.execute(
                    "INSERT INTO lake_tables_managed (id, namespace_id, name, status, policy_json, created_by) " +
                        "VALUES ('${UUID.randomUUID()}', '$namespace', 'trips', 'active', '{}', '$actor')",
                )
            }
            underLease(connection) {
                connection
                    .prepareStatement(
                        "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name, metadata_location, " +
                            "previous_metadata_location, iceberg_type) VALUES (?, 'sales', 'trips', ?, NULL, 'TABLE')",
                    ).use { ps ->
                        ps.setString(1, LAKE)
                        ps.setString(2, location)
                        ps.executeUpdate() shouldBe 1
                    }
            }
        }
    }

    private fun moveCatalogPointer(
        from: String,
        to: String,
    ) {
        metadata { connection ->
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

    /** The V53 fence: a catalog write needs a live table lease and its token set in the same transaction. */
    private fun underLease(
        connection: Connection,
        leaseNames: List<String> = listOf("sales.trips"),
        write: () -> Unit,
    ) {
        val token = UUID.randomUUID()
        val operation = UUID.randomUUID()
        leaseNames.forEach { leaseName -> lease(connection, leaseName, operation, token) }
        connection.autoCommit = false
        try {
            connection.prepareStatement("SELECT set_config('dp.lease_token', ?, true), set_config('dp.operation_id', ?, true)").use { ps ->
                ps.setString(1, token.toString())
                ps.setString(2, operation.toString())
                ps.executeQuery().close()
            }
            write()
            connection.commit()
        } finally {
            connection.autoCommit = true
        }
    }

    /** One table lease row for [leaseName], held by [operation] with [token] (the fence matches both). */
    private fun lease(
        connection: Connection,
        leaseName: String,
        operation: UUID,
        token: UUID,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO lake_leases " +
                    "(kind, name, operation_id, token, acquired_at, expires_at, deadline_at, operation, holder_instance) " +
                    "VALUES ('table', ?, ?, ?, now(), now() + interval '1 minute', " +
                    "now() + interval '1 minute', 'test', 'test') " +
                    "ON CONFLICT (kind, name) DO UPDATE SET operation_id = EXCLUDED.operation_id, token = EXCLUDED.token, " +
                    "expires_at = EXCLUDED.expires_at, deadline_at = EXCLUDED.deadline_at",
            ).use { ps ->
                ps.setString(1, leaseName)
                ps.setObject(2, operation)
                ps.setObject(3, token)
                ps.executeUpdate()
            }
    }

    private fun systemActor(connection: Connection): UUID =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT created_by FROM datasources WHERE name = '$LAKE'").use { rs ->
                rs.next()
                rs.getObject(1, UUID::class.java)
            }
        }

    private fun awaitTrue(
        budget: Duration,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + budget.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        condition() shouldBe true
    }

    companion object {
        private const val LAKE = "lake"
        private const val TAKEN_NAME = "lake_taken"
        private const val MANAGED_CODE = "datasource.managed"
        private const val TEMPLATE_ID = "test/managed_lake_count.sql"
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private val DEFAULT_WORKSPACE_ID = UUID.fromString(DEFAULT_WORKSPACE)

        private const val BUCKET = "dp-lake-it"
        private const val METADATA = "s3://$BUCKET/iceberg/trips_iceberg/metadata"
        private const val PREVIOUS = "$METADATA/00001-7c81713e-1543-46ec-b6f4-6b3875c8b8c3.metadata.json"
        private const val CURRENT = "$METADATA/00002-dba28368-bd68-452e-bd41-3bac13f6d77b.metadata.json"
        private const val ICEBERG_ROWS = 2000

        /** A: a short age bound for proof (a), the tick below it (LakeProperties refuses a tick at or above the bound). */
        private const val A_MAX_AGE_SECONDS = 6L
        private const val A_TICK_SECONDS = 5L

        /** B: the shipped age bound, so within this test only the tick can retire B's generation. */
        private const val B_MAX_AGE_SECONDS = 900L
        private const val B_TICK_SECONDS = 2L

        private const val POLL_MILLIS = 500L

        /** G (#818b A): a 2 s tick; the bound is the issue's 5 s, a literal (never read back from the class under test). */
        private const val HELD_TICK_SECONDS = 2L
        private const val FRESHNESS_BOUND_SECONDS = 5L
        private const val REAPER_CADENCE_SECONDS = 5L
        private const val BOUND_SLACK_SECONDS = 3L
        private const val WATCH_MILLIS = 100L

        /** Queued-to-gone: at least the bound less one watch poll and the queue's own detection lag, at most bound + slack. */
        private val CANCEL_WINDOW_MS = (FRESHNESS_BOUND_SECONDS * 1000 - 1000)..((FRESHNESS_BOUND_SECONDS + BOUND_SLACK_SECONDS) * 1000)
        private const val CATALOG_READ_TEXT = "%SELECT t.namespace, t.name, t.format%FROM lake_tables t%"
        private const val REGRANT_TEXT = "%INSERT INTO datasource_workspaces%FROM workspaces w%"
        private const val REAP_JOB = "co.datapipelines.web.config.DatasourcePoolReaperScheduler.reap"
        private const val FRESHNESS_JOB = "co.datapipelines.web.config.ManagedLakeFreshnessScheduler.tick"

        /** E (#818b B): a tick that runs twice inside the age bound, so a failed tick read is observed before expiry. */
        private const val OUTAGE_MAX_AGE_SECONDS = 30L
        private const val OUTAGE_TICK_SECONDS = 5L

        /** F (#818b B): a tick longer than the test, so only an explicit retire builds a new generation. */
        private const val LIFECYCLE_TICK_SECONDS = 600L
        private const val AWAY = "trips_away"

        private const val APP_E = "dp818-e"

        /**
         * H (#818c C): a short age bound and ceiling, so the test crosses both in seconds, the tick below the age (the
         * binder refuses one at or above it). LakeRules admits it: 12 + 2 × 7 + 6 + 92 + 10 = 134 ≤ 7 × 3600 (#818d).
         * I1–I3 run the same profile.
         */
        private const val H_MAX_AGE_SECONDS = 12L
        private const val H_TICK_SECONDS = 10L
        private const val H_CEILING_SECONDS = 6L
        private const val APP_H = "dp818-h"

        /** The shipped `protection-days` (7) × 86400 / 24: the window LakeRules holds H's sum to. */
        private const val H_PROTECTION_WINDOW_SECONDS = 7L * 3600L

        /**
         * The reaper delay the record's bound adds twice (#818d: cadence 5 + 2 × pass bound 1); integration-tests
         * cannot see web's constant, so a literal, never read back from the class under test.
         */
        private const val REAPER_DELAY_SECONDS = 7L

        /** #818d — the per-close deadline (`PoolCloser.DEADLINE_SECONDS`), a literal; I3 waits for its WARN. */
        private const val POOL_CLOSE_DEADLINE_SECONDS = 65L

        /** #818d — the reaper's own thread, and the jobs' shared one, by the spec's names. */
        private const val REAPER_THREAD = "dp-reaper"
        private const val APP_I1 = "dp818-i1"
        private const val APP_I2 = "dp818-i2"
        private const val APP_I3 = "dp818-i3"
        private const val STUCK_SLACK_SECONDS = 10L
        private const val POOL_TYPE = "co.datapipelines.datasources.pooling.ConnectionPool"
        private const val CLOSER_TYPE = "co.datapipelines.datasources.pooling.PoolCloser"

        /** 2 000⁴ rows: minutes of work, so nothing but the close can end it inside the bound. */
        private const val H_TRIPS = "$LAKE.sales.trips"
        private const val H_LONG_QUERY = "SELECT count(*) FROM $H_TRIPS a, $H_TRIPS b, $H_TRIPS c, $H_TRIPS d"

        /** The parent logger of the pool manager's and registry's lines (datasources) and the reaper's count (web). */
        private const val PRODUCT_LOGGERS = "co.datapipelines"
        private const val EXECUTING_BUDGET_SECONDS = 60L
        private const val EXECUTING_GAP_MILLIS = 500L
        private const val EXECUTING_CPU_MIN_MILLIS = 200L
        private const val CLEANUP_JOIN_MS = 30_000L
        private const val MESSAGE_CHARS = 200
        private const val APP_F = "dp818-f"
        private const val APP_G = "dp818-g"
        private val EXECUTION_BUDGET: Duration = Duration.ofSeconds(180)

        private const val MINIO_PORT = 9000
        private const val WRITER_ACCESS = "dpwriter818"

        /** Generated per run: no literal credential in a fixture. Distinct from the username, so the dialect check bites. */
        private val WRITER_SECRET = "w-" + UUID.randomUUID().toString().replace("-", "")

        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32))
        private const val ADMIN_USER_ID = "a11e0000-0000-0000-0000-000000000818"
        private val ADMIN_SESSION get() = E2eSession.jwt(SECRET, ADMIN_USER_ID, "e2e-managed-lake@datapipelines.test")

        private val oidc = OidcDiscoveryStub()

        /** The OTHER instances this suite boots; B and F live for the class, C, D, E and G only for their test. */
        private var instanceB: ConfigurableApplicationContext? = null
        private var instanceF: ConfigurableApplicationContext? = null
        private var portB: Int = 0
        private var pipelineId: String = ""

        /** The suite's own metadata database: every boot migrates or reuses it; no other suite's context touches it. */
        private val database by lazy { SharedE2e.scratchDatabase("managed_lake_reads") }

        /** Its own MinIO (see the class KDoc), seeded with the Iceberg fixture under `dp-lake-it` on first touch. */
        private val minio: GenericContainer<*> by lazy {
            GenericContainer(DockerImageName.parse(SharedE2e.MINIO_IMAGE))
                .withEnv("MINIO_ROOT_USER", WRITER_ACCESS)
                .withEnv("MINIO_ROOT_PASSWORD", WRITER_SECRET)
                .withCommand("server", "/data")
                .withExposedPorts(MINIO_PORT)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(MINIO_PORT))
                .also { container ->
                    container.start()
                    seedBucket(container)
                }
        }

        private val endpoint get() = "http://localhost:${minio.getMappedPort(MINIO_PORT)}"

        private fun seedBucket(container: GenericContainer<*>) {
            S3Client
                .builder()
                .endpointOverride(URI.create("http://localhost:${container.getMappedPort(MINIO_PORT)}"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(WRITER_ACCESS, WRITER_SECRET)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build()
                .use { s3 ->
                    s3.createBucket { it.bucket(BUCKET) }
                    val fixture = Path.of(checkNotNull(ManagedLakeReadsE2eTest::class.java.getResource("/lake-fixture/iceberg")).toURI())
                    Files.walk(fixture).use { paths ->
                        paths.filter { it.isRegularFile() && it.name != ".DS_Store" }.forEach { file ->
                            s3.putObject({ it.bucket(BUCKET).key("iceberg/${file.relativeTo(fixture)}") }, RequestBody.fromFile(file))
                        }
                    }
                }
        }

        private fun <T> metadata(block: (Connection) -> T): T =
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use(block)

        /** After A's Flyway (a static @BeforeAll runs before it): idempotent. */
        private fun seedAuthRows() {
            metadata { connection ->
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

        /** Every property a boot needs, as `key to value`; A takes them as dynamic properties, B, C and D as args. */
        private fun bootProperties(
            maxAgeSeconds: Long,
            tickSeconds: Long,
            name: String = LAKE,
            enabled: Boolean = true,
            application: String? = null,
        ): List<Pair<String, String>> =
            listOf(
                "management.server.port" to "0",
                "spring.datasource.url" to (application?.let { urlNamed(it) } ?: database.jdbcUrl),
                "spring.datasource.username" to database.username,
                "spring.datasource.password" to database.password,
                "datapipelines.redis.password" to "",
                "datapipelines.redis.host" to SharedE2e.redisHost,
                "datapipelines.redis.port" to SharedE2e.redisPort.toString(),
                "datapipelines.jwt.secret" to SECRET,
                "datapipelines.db.encryption-key" to SECRET,
                "datapipelines.auth.oidc.providers[0].name" to "google",
                "datapipelines.auth.oidc.providers[0].client-id" to "test-google-client-id",
                "datapipelines.auth.oidc.providers[0].client-secret" to "test-google-client-secret",
                "datapipelines.auth.oidc.providers[0].issuer-uri" to oidc.issuer,
                "datapipelines.auth.oidc.providers[0].display-name" to "Test google",
                "datapipelines.auth.base-url" to "http://localhost:8080",
                "datapipelines.lake.enabled" to enabled.toString(),
                "datapipelines.lake.name" to name,
                "datapipelines.lake.storage.bucket" to BUCKET,
                "datapipelines.lake.storage.region" to "us-east-1",
                "datapipelines.lake.storage.endpoint" to endpoint,
                "datapipelines.lake.storage.path-style" to "true",
                "datapipelines.lake.storage.access-key" to WRITER_ACCESS,
                "datapipelines.lake.storage.secret-key" to WRITER_SECRET,
                "datapipelines.lake.reads.max-generation-age-seconds" to maxAgeSeconds.toString(),
                "datapipelines.lake.reads.freshness-tick-seconds" to tickSeconds.toString(),
            )

        // Command-line args, not builder `.properties(...)`: those are DEFAULT properties and application.yml's
        // `${SPRING_DATASOURCE_URL}` would override them; args win (DatasourcePoolInvalidationE2eTest's note).
        private fun boot(
            maxAgeSeconds: Long,
            tickSeconds: Long,
            name: String = LAKE,
            enabled: Boolean = true,
            application: String? = null,
            recordTicks: Boolean = false,
            extra: List<Pair<String, String>> = emptyList(),
            initializers: List<ApplicationContextInitializer<ConfigurableApplicationContext>> = emptyList(),
        ): ConfigurableApplicationContext {
            val sources =
                listOfNotNull(
                    DatapipelinesApplication::class.java,
                    ScheduledJobsSchedulerE2eTest.TickRecorderConfiguration::class.java.takeIf { recordTicks },
                )
            return SpringApplicationBuilder(*sources.toTypedArray()).initializers(*initializers.toTypedArray()).run(
                "--server.port=0",
                *(bootProperties(maxAgeSeconds, tickSeconds, name, enabled, application).toMap() + extra.toMap())
                    .map { (k, v) -> "--$k=$v" }
                    .toTypedArray(),
            )
        }

        /** The suite's database under a Postgres `ApplicationName`, so `pg_stat_activity` names the instance. */
        private fun urlNamed(application: String): String =
            database.jdbcUrl + (if ('?' in database.jdbcUrl) "&" else "?") + "ApplicationName=" + application

        @AfterAll
        @JvmStatic
        fun closeInstances() {
            instanceB?.close()
            instanceF?.close()
            oidc.close()
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            bootProperties(A_MAX_AGE_SECONDS, A_TICK_SECONDS).forEach { (key, value) -> registry.add(key) { value } }
        }
    }
}
