package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.longs.shouldBeInRange
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
                        val read = cancelledWhileHeld(watcher, "the catalog read", CATALOG_READ_TEXT, "lake_tables_managed", "AccessExclusiveLock")
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
                                "reaps_after=${recorder.countOf(REAP_JOB) - reaps} ticks_after=${recorder.countOf(FRESHNESS_JOB) - ticks} " +
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
        val outage = boot(maxAgeSeconds = OUTAGE_MAX_AGE_SECONDS, tickSeconds = OUTAGE_TICK_SECONDS, application = APP_E, recordTicks = true)
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
                metadata { it.createStatement().use { st -> st.execute("ALTER TABLE lake_tables_managed_away RENAME TO lake_tables_managed") } }
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

    /** Instance F, booted once: its tick and age bound outlast the suite, so only [retireManagedPool] replaces a generation. */
    private fun lifecycle(): ConfigurableApplicationContext =
        instanceF ?: boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = LIFECYCLE_TICK_SECONDS, application = APP_F).also { instanceF = it }

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
        ): ConfigurableApplicationContext {
            val sources =
                listOfNotNull(
                    DatapipelinesApplication::class.java,
                    ScheduledJobsSchedulerE2eTest.TickRecorderConfiguration::class.java.takeIf { recordTicks },
                )
            return SpringApplicationBuilder(*sources.toTypedArray()).run(
                "--server.port=0",
                *bootProperties(maxAgeSeconds, tickSeconds, name, enabled, application).map { (k, v) -> "--$k=$v" }.toTypedArray(),
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
