package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import software.amazon.awssdk.core.sync.RequestBody
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * #969 C13, C14 through the production adapter, C16 (a)'s object-store read and D9: the production beans (the staging
 * factory and its budget, the web adapter over a trusted scratch database, 957's worker and its parts, 959's planner)
 * with MinIO, real Parquet appended by deliveries, and the real DuckDB engine. No test opens a JDBC connection of its
 * own to the lake (the one `jdbc:duckdb:` connection writes a delivery's input file, as the 968 suite does).
 *
 * Two copies are made by reflection (this module compiles against app only), each from the production bean's own
 * fields with ONE field replaced: the planner's queue records instead of enqueueing (the scheduler must not run the
 * worker beside the test), and the worker's scratch is the production `MaintenanceScratch` bean behind an observer
 * that counts the reservations and registers each one's release callback. Every other collaborator is the bean's.
 *
 * Settings: the production profile admits compaction all day and calls a partition due at two small files; the
 * compaction factor is 1 (C13 (b): a sort that spills cannot fit a reservation of its own input bytes); the assumed
 * rate is 1 GB a second, so time never limits a group here (time is G5's and C14a's).
 */
@SpringBootTest(classes = [DatapipelinesApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ManagedLakeCompactionE2eTest {
    @LocalServerPort private var port = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var context: ApplicationContext
    private val runtime get() = ManagedLakeRuntimeAccess(context)
    private val json = ObjectMapper()

    @BeforeAll
    fun seed() {
        E2eClean.beforeSeeding()
        runtime.restoreStartupSeeds()
        jdbc.update("INSERT INTO workspaces (id, name, display_name) VALUES (?, ?, 'Lane 969')", WORKSPACE, WORKSPACE_NAME)
        listOf(ADMIN to "super_admin", AUTHOR to "author").forEach { (id, role) ->
            jdbc.update(
                "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) " +
                    "VALUES (?, ?, ?, 'test', ?, TRUE, ?)",
                id,
                "$role@lane969.test",
                role,
                id.toString(),
                id == ADMIN,
            )
            if (id != ADMIN) {
                jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)", WORKSPACE, id, role)
            }
        }
        val created =
            given()
                .port(port)
                .asSession(jwt(ADMIN))
                .contentType(ContentType.JSON)
                .body(
                    json.writeValueAsString(
                        mapOf(
                            "name" to LAKE,
                            "display_name" to "Lane 969",
                            "dialect" to "LAKE",
                            "jdbc_url" to "jdbc:duckdb::memory:",
                            "readonly" to true,
                            "credential" to
                                mapOf("kind" to "password", "username" to SharedE2e.MINIO_USER, "secret" to SharedE2e.MINIO_PASSWORD),
                            "properties" to
                                mapOf(
                                    "dialect" to
                                        mapOf(
                                            "catalog.kind" to "s3",
                                            "region" to "us-east-1",
                                            "endpoint" to SharedE2e.minioEndpoint,
                                            "url_style" to "path",
                                        ),
                                ),
                        ),
                    ),
                ).post("/api/v1/datasources")
                .then()
                .extract()
                .response()
        withClue("datasource creation code=${created.jsonPath().getString("error.code")}") { created.statusCode() shouldBe 201 }
        TABLES.forEach { (table, body) -> createTable(table, body) }
        val profiled =
            jdbc.update(
                "UPDATE lake_profiles SET values_json = jsonb_set(jsonb_set(values_json, '{maintenance,window}', '\"00:00-00:00\"'), " +
                    "'{maintenance,compact_min_small_files}', '2'), updated_at = clock_timestamp() WHERE name = 'production'",
            )
        profiled shouldBe 1
    }

    @AfterAll
    fun closeDiscovery() {
        oidc.close()
    }

    @Test
    fun `C13 a and d - two groups commit through one reservation and one database, then nothing due never opens one`() {
        val pipeline = createPipeline(TWO_GROUPS)
        repeat(2) { n -> deliver(pipeline, twoRegions(start = n * TWO_GROUPS_ROWS, rows = TWO_GROUPS_ROWS)) }
        val before = revisions(TWO_GROUPS)
        val rows = rowCount(TWO_GROUPS)
        measure() shouldContain tableId(TWO_GROUPS).toString()

        val run = compact(TWO_GROUPS)

        run.state shouldBe "finished"
        val part = run.part
        withClue("the part: $part") { part.path("outcome").asText() shouldBe "done" }
        val groups = part.path("groups")
        groups.size() shouldBe 2
        revisions(TWO_GROUPS) shouldBe before + 2
        rowCount(TWO_GROUPS) shouldBe rows
        run.reservations shouldBe 1
        run.releases shouldBe 1
        withClue("the budget held the declaration while the part ran, and its start after") {
            run.declared shouldBeGreaterThan 0L
            run.peakReserved shouldBe run.startReserved + run.declared
            run.endReserved shouldBe run.startReserved
        }
        run.leftInScratch.shouldBeEmpty()
        groups.forEach { group ->
            println(
                "case=d9 table=$TWO_GROUPS input_files=${group["input_files"]} input_bytes=${group["input_bytes"]} " +
                    "output_files=${group["output_files"]} output_bytes=${group["output_bytes"]} rows=${group["rows"]} " +
                    "read_write_seconds=${group["read_write_seconds"]} lease_seconds=${group["lease_seconds"]} " +
                    "scratch_peak_bytes=${group["scratch_peak_bytes"]} grant_bytes=${run.declared}",
            )
        }

        // (d): the stored measurement still says due, the table no longer is; the run reserves, admits and opens nothing.
        val again = compact(TWO_GROUPS)
        again.reservations shouldBe 1
        again.releases shouldBe 1
        again.databasesSeen.shouldBeEmpty()
        again.endReserved shouldBe again.startReserved
        revisions(TWO_GROUPS) shouldBe before + 2
        withClue("the part: ${again.part}") { again.part.path("outcome").asText() shouldNotBe "done" }
    }

    @Test
    fun `C13 b - a reservation too small for the sort is capacity by scratch, nothing committed, the scratch released`() {
        val pipeline = createPipeline(SPILLS)
        repeat(2) { n -> deliver(pipeline, spilling(start = n * SPILLS_ROWS, rows = SPILLS_ROWS)) }
        val before = revisions(SPILLS)
        measure() shouldContain tableId(SPILLS).toString()

        val run = compact(SPILLS)

        withClue("the part: ${run.part}") {
            run.part.path("outcome").asText() shouldBe "capacity"
            run.part.path("limit_source").asText() shouldBe "scratch"
            run.part.path("committed").asBoolean() shouldBe false
        }
        revisions(SPILLS) shouldBe before
        run.databasesSeen shouldHaveSize 1
        run.leftInScratch.shouldBeEmpty()
        run.releases shouldBe 1
        run.endReserved shouldBe run.startReserved
        println("case=c13b grant_bytes=${run.declared} part=${run.part}")
    }

    @Test
    fun `C13 c - an input file gone from the bucket fails the part and the run, not capacity, the same cleanup`() {
        val pipeline = createPipeline(MISSING)
        repeat(2) { n -> deliver(pipeline, twoRegions(start = n * MISSING_ROWS, rows = MISSING_ROWS)) }
        val before = revisions(MISSING)
        measure() shouldContain tableId(MISSING).toString()
        val victim = dataKeys(MISSING).first()
        SharedE2e.s3Client().use { client -> client.deleteObject { it.bucket(BUCKET).key(victim) } }

        val run = compact(MISSING)

        run.state shouldBe "failed"
        withClue("the part: ${run.part}") { run.part.path("outcome").asText() shouldBe "failed" }
        revisions(MISSING) shouldBe before
        run.leftInScratch.shouldBeEmpty()
        run.releases shouldBe 1
        run.endReserved shouldBe run.startReserved
    }

    @Test
    fun `C14 a and b through the adapter - a deadline inside the sort, before its first row, and one passed at the open`() {
        val pipeline = createPipeline(TIMING)
        repeat(2) { n -> deliver(pipeline, narrowRows(start = n * TIMING_ROWS, rows = TIMING_ROWS)) }
        val statement = statementOf(TIMING)
        val files = dataKeys(TIMING).map { "s3://$BUCKET/$it" }
        files shouldHaveSize 2
        // The same two small, narrow files listed thousands of times: the statement reads every entry from the object
        // store before its sort hands over a row, long enough to place a deadline inside it at the timeout's one-second
        // grain, while the sorted rows stay small. (Measured: 30,000,000 narrow rows from 5,000 entries handed over the
        // first row after 2.7 s and spilled past 600 MiB, hence the grant; 4,000,000 wide rows from 40 entries after
        // 0.9 s; wide row groups read whole meet the engine's 128 MB memory limit before the sort spills.)
        val inputs = List(TIMING_REPEATS) { files }.flatten()

        // Timed with ample time first, to the first row only (C16 (a): the trusted database reads s3:// through the
        // configured secret). Draining 30,000,000 rows at the 128 MB limit once ended in the driver's "Invalid result set"
        // part-way through the stream, a failure this case does not measure.
        val timed = adapterRead(statement, inputs, Instant.now().plus(Duration.ofMinutes(2)), untilFirstRow = true)
        withClue("the timing read: ${timed.failure}") { timed.failure shouldBe null }
        timed.rows shouldBe 1L
        println("case=c14_timing rows=${timed.rows} millis=${timed.millis} first_row_millis=${timed.firstRowMillis} files=${inputs.size}")
        withClue("the sort before the first row must outlast the statement timeout's one-second grain") {
            timed.firstRowMillis shouldBeGreaterThan MIN_FIRST_ROW_MILLIS
        }

        // (a) a quarter of the time to the first row left: the driver stops the sort; the clock, not the message, says
        // deadline.
        val stopped = adapterRead(statement, inputs, Instant.now().plusMillis(timed.firstRowMillis / 4))
        withClue("(a): ${stopped.failure}") { stopped.failure shouldBe DEADLINE_PASSED }
        stopped.rows shouldBe 0
        stopped.releases shouldBe 1
        stopped.leftInScratch.shouldBeEmpty()
        stopped.endReserved shouldBe stopped.startReserved

        // (b) already passed at the open.
        val late = adapterRead(statement, inputs, Instant.now().minusSeconds(1))
        withClue("(b): ${late.failure}") { late.failure shouldBe DEADLINE_PASSED }
        late.rows shouldBe 0
        late.releases shouldBe 1
        late.leftInScratch.shouldBeEmpty()
        late.endReserved shouldBe late.startReserved
    }

    // --- the production beans, observed -------------------------------------------------------------------------------

    /** One worker run of [table] with the observing scratch; samples the budget and the scratch directory meanwhile. */
    private fun compact(table: String): ObservedRun {
        val observer = ScratchObserver()
        val worker = copyOf(bean(WORKER), "scratch" to observer.proxy())
        val start = budgetReserved()
        val peak = AtomicLong(start)
        val seen = ConcurrentHashMap.newKeySet<String>()
        val running = AtomicBoolean(true)
        val sampler =
            thread(name = "c13-sampler") {
                while (running.get()) {
                    peak.accumulateAndGet(budgetReserved()) { a, b -> maxOf(a, b) }
                    observer.ids.forEach { id -> scratchFiles(id).forEach { seen += it } }
                    Thread.sleep(SAMPLE_MILLIS)
                }
            }
        try {
            invoke(worker, "run", tableId(table).toString())
        } finally {
            running.set(false)
            sampler.join()
        }
        val row =
            jdbc.queryForMap(
                "SELECT state, scratch_reserved_bytes, parts_json::text AS parts FROM lake_maintenance_runs WHERE table_id = ? " +
                    "ORDER BY started_at DESC, id DESC LIMIT 1",
                tableId(table),
            )
        val parts = json.readTree(row["parts"] as String)
        return ObservedRun(
            state = row["state"] as String,
            part = parts.find { it["name"].asText() == "compaction" } ?: json.createObjectNode(),
            declared = (row["scratch_reserved_bytes"] as Number).toLong(),
            startReserved = start,
            peakReserved = peak.get(),
            endReserved = budgetReserved(),
            reservations = observer.ids.size,
            releases = observer.releases.get(),
            databasesSeen = seen.filter { it.endsWith(".duckdb") },
            leftInScratch = observer.ids.flatMap(::scratchFiles),
        )
    }

    /** The adapter alone: one reservation from the production bean, the port bean's session, one read of [files]. */
    private fun adapterRead(
        statement: Any,
        files: List<String>,
        deadline: Instant,
        untilFirstRow: Boolean = false,
    ): AdapterRead {
        val observer = ScratchObserver()
        val start = budgetReserved()
        val reservation = invoke(observer.proxy(), "reserve", ADAPTER_GRANT) as? AutoCloseable ?: error("the budget refused $ADAPTER_GRANT")
        val rows = AtomicLong()
        val firstRow = AtomicLong(-1)
        val began = System.nanoTime()
        val consume: (ResultSet) -> Unit = { cursor ->
            while ((!untilFirstRow || rows.get() == 0L) && cursor.next()) {
                if (rows.incrementAndGet() == 1L) firstRow.set((System.nanoTime() - began) / NANOS_PER_MILLI)
            }
        }
        val failure =
            try {
                val session = invoke(bean(PORT), "open", reservation, deadline)
                try {
                    invoke(session, "read", statement, files, consume)
                } finally {
                    invoke(session, "close")
                }
                null
            } catch (thrown: Exception) {
                thrown.javaClass.name.also { println("case=adapter_read failure=$it rows=${rows.get()} message=${thrown.message}") }
            } finally {
                reservation.close()
            }
        return AdapterRead(
            rows = rows.get(),
            millis = (System.nanoTime() - began) / NANOS_PER_MILLI,
            firstRowMillis = firstRow.get(),
            failure = failure,
            releases = observer.releases.get(),
            startReserved = start,
            endReserved = budgetReserved(),
            leftInScratch = observer.ids.flatMap(::scratchFiles),
        )
    }

    /** The production `MaintenanceScratch` bean behind a proxy that records each reservation and counts its releases. */
    private inner class ScratchObserver {
        val ids = CopyOnWriteArrayList<String>()
        val releases = AtomicInteger()
        private val scratch = bean(SCRATCH)

        fun proxy(): Any =
            Proxy.newProxyInstance(type(SCRATCH).classLoader, arrayOf(type(SCRATCH))) { _, method, args ->
                val answer = unwrapped { method.invoke(scratch, *(args ?: emptyArray())) }
                if (method.name == "reserve" && answer != null) {
                    ids += invoke(answer, "getExecutionId").toString()
                    val counted: () -> Unit = { releases.incrementAndGet() }
                    invoke(answer, "afterRelease", counted)
                }
                answer
            }
    }

    /** 959's planner with a recording queue: measures and decides as in production; answers the keys it would enqueue. */
    private fun measure(): List<String> {
        val keys = CopyOnWriteArrayList<String>()
        val queueType = type("co.datapipelines.scheduler.SystemJobQueue")
        val queue =
            Proxy.newProxyInstance(queueType.classLoader, arrayOf(queueType)) { _, method, args ->
                when (method.name) {
                    "enqueueIfAbsent" -> keys.add(args[1] as String)
                    "move" -> false
                    else -> null
                }
            }
        val supplier: () -> Any = { queue }
        invoke(copyOf(bean(PLANNER), "queue" to supplier), "run")
        return keys
    }

    /** D6's statement for [table]'s current schema and order, built by the lake's own function. */
    private fun statementOf(table: String): Any {
        val location = metadataLocation(table)
        val text = SharedE2e.s3Client().use { client -> client.getObjectAsBytes { it.bucket(BUCKET).key(keyOf(location)) }.asUtf8String() }
        val metadata =
            type("org.apache.iceberg.TableMetadataParser")
                .methods
                .single { it.name == "fromJson" && it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java)) }
                .invoke(null, location, text)
        val schema = invoke(metadata, "schema")
        val order = invoke(metadata, "sortOrder")
        val companion = type(STATEMENT).getField("Companion").get(null)
        return invoke(companion, "of", schema, order)
    }

    /** The table's data objects in the bucket (keys, not URIs), oldest first: everything under its location's `data/`. */
    private fun dataKeys(table: String): List<String> {
        val location = keyOf(metadata(table)["location"].asText()).trimEnd('/')
        return SharedE2e.s3Client().use { client ->
            client
                .listObjectsV2Paginator { it.bucket(BUCKET).prefix("$location/data/") }
                .contents()
                .filter { it.key().endsWith(".parquet") }
                .sortedBy { it.lastModified() }
                .map { it.key() }
        }
    }

    private fun keyOf(uri: String): String = uri.removePrefix("s3://$BUCKET/")

    private fun budgetReserved(): Long = invoke(invoke(duckDbFactory(), "getBudget"), "getReservedBytes") as Long

    private fun scratchFiles(executionId: String): List<String> {
        val dir = invoke(invoke(duckDbFactory(), "getProperties"), "getScratchDir") as Path
        return Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.filter { it.contains(executionId) }.toList() }
    }

    private fun duckDbFactory(): Any {
        val factory = context.getBean(type("co.datapipelines.staging.StagingFactory"))
        return if (factory.javaClass.name.endsWith("EngineDispatchingStagingFactory")) invoke(factory, "getDuckDb") else factory
    }

    // --- fixtures --------------------------------------------------------------------------------------------------------

    private fun createTable(
        table: String,
        body: String,
    ) {
        val definitions = context.getBean(type("co.datapipelines.lake.definitions.LakeDefinitionService"))
        val surface = type("co.datapipelines.pipeline.WriteSurface").enumConstants.single { (it as Enum<*>).name == "SESSION" }
        val draft = invoke(definitions, "createDraft", NAMESPACE, table, body, ADMIN, surface)
        invoke(definitions, "release", invoke(draft, "getTableId"), invoke(draft, "getVersion"), ADMIN, surface, null)
    }

    private fun createPipeline(table: String): String {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        listOf("land" to "SELECT * FROM delivery", "append" to "SELECT * FROM staged").forEach { (node, sql) ->
            given()
                .port(port)
                .asSession(jwt(ADMIN))
                .contentType(ContentType.JSON)
                .body(
                    json.writeValueAsString(
                        mapOf(
                            "id" to "$WORKSPACE_NAME/${node}_$suffix.sql",
                            "dialect" to "DUCKDB",
                            "display_name" to node,
                            "description" to "Lane 969 compaction inputs",
                            "imports" to emptyList<String>(),
                            "body" to sql,
                        ),
                    ),
                ).post("/api/v1/templates")
                .then()
                .statusCode(201)
        }
        return given()
            .port(port)
            .asSession(jwt(ADMIN))
            .contentType(ContentType.JSON)
            .body(
                """
                {"schema_version":1,"name":"$WORKSPACE_NAME/run_$suffix","display_name":"Lane 969","description":"Lane 969 inputs",
                 "settings":{"tempdb":{"engine":"DUCKDB"}},"parameters":{"batch_id":{"type":"STRING","required":true}},
                 "nodes":[{"id":"land","description":"Read the immutable delivery","type":"DQL","source":"tempdb",
                   "delivery":{"feed":"orders","id":"${'$'}batch_id"},"template":{"id":"$WORKSPACE_NAME/land_$suffix.sql","version":1},
                   "output":{"target":"tempdb","table":"staged"},"depends_on":[]},
                  {"id":"append","description":"Append one file per partition","type":"DQL","source":"tempdb",
                   "template":{"id":"$WORKSPACE_NAME/append_$suffix.sql","version":1},
                   "output":{"target":"datasource","datasource":"$LAKE","table":"$NAMESPACE.$table","mode":"append"},
                   "depends_on":["land"]}]}
                """.trimIndent(),
            ).post("/api/v1/pipelines")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("data.id")
    }

    /** Writes [select]'s rows as one Parquet delivery, executes [pipeline] on it and requires it published. */
    private fun deliver(
        pipeline: String,
        select: String,
    ) {
        val batch = "batch_" + UUID.randomUUID().toString()
        val dir = Files.createTempDirectory("lane969")
        val file = dir.resolve("input.parquet")
        try {
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { it.execute("COPY ($select) TO '$file' (FORMAT parquet, COMPRESSION zstd)") }
            }
            SharedE2e.s3Client().use { client ->
                client.putObject({ it.bucket(BUCKET).key("landing/orders/$batch/input.parquet") }, RequestBody.fromFile(file))
                client.putObject({ it.bucket(BUCKET).key("landing/orders/$batch/_done") }, RequestBody.empty())
            }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(dir)
        }
        val events = E2eSse.parseEvents(execute(pipeline, batch), json)
        withClue("delivery $batch: ${events.filter { it.first == "node_failed" }}") {
            events.any { it.first == "pipeline_completed" } shouldBe true
        }
    }

    private fun execute(
        id: String,
        batch: String,
    ): String {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$id/execute"))
                .timeout(Duration.ofSeconds(EXECUTE_TIMEOUT_SECONDS))
                .header("Cookie", E2eSession.cookieHeader(jwt(AUTHOR)))
                .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapOf("parameters" to mapOf("batch_id" to batch)))))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        return response.body()
    }

    private fun tableId(table: String): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT t.id FROM lake_tables_managed t JOIN lake_namespaces n ON n.id = t.namespace_id WHERE n.name = ? AND t.name = ?",
                UUID::class.java,
                NAMESPACE,
                table,
            ),
        )

    private fun revisions(table: String): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM lake_table_revisions WHERE namespace = ? AND table_name = ?",
                Int::class.java,
                NAMESPACE,
                table,
            ),
        )

    /** The table's current metadata file as JSON. */
    private fun metadata(table: String): JsonNode =
        json.readTree(
            SharedE2e.s3Client().use { client ->
                client.getObjectAsBytes { it.bucket(BUCKET).key(keyOf(metadataLocation(table))) }.asUtf8String()
            },
        )

    /** The table's rows as its committed manifests count them, read from the current metadata's snapshot summary. */
    private fun rowCount(table: String): Long {
        val metadata = metadata(table)
        val current = metadata["current-snapshot-id"].asLong()
        val snapshot = metadata["snapshots"].single { it["snapshot-id"].asLong() == current }
        return snapshot["summary"]["total-records"].asLong()
    }

    private fun metadataLocation(table: String): String =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT metadata_location FROM iceberg_tables WHERE table_namespace = ? AND table_name = ?",
                String::class.java,
                NAMESPACE,
                table,
            ),
        ) { "the catalog has no metadata location for $NAMESPACE.$table" }

    private fun jwt(user: UUID): String =
        if (user == ADMIN) {
            E2eSession.jwt(SECRET, user.toString(), "super_admin@lane969.test", WORKSPACE_NAME)
        } else {
            E2eSession.jwt(SECRET, user.toString(), "author@lane969.test", WORKSPACE_NAME)
        }

    // --- reflection ------------------------------------------------------------------------------------------------------

    private fun bean(name: String): Any = context.getBean(type(name))

    /**
     * A new instance of [original]'s class built from its own fields, [replaced] by name. Each parameter of the
     * primary constructor (the widest one without Kotlin's default-argument marker) takes the first unused instance
     * field of exactly its type, in declaration order: a Kotlin class declares its constructor properties first, in
     * parameter order. The replaced field must be one of those picked.
     */
    private fun copyOf(
        original: Any,
        replaced: Pair<String, Any>,
    ): Any {
        val type = original.javaClass
        val constructor =
            type.constructors
                .filter { it.parameterTypes.none { p -> p.name.endsWith("DefaultConstructorMarker") } }
                .maxBy { it.parameterCount }
        val unused =
            type.declaredFields
                .filter {
                    !java.lang.reflect.Modifier
                        .isStatic(it.modifiers)
                }.toMutableList()
        val picked =
            constructor.parameterTypes.map { parameter ->
                unused.first { it.type == parameter }.also { unused.remove(it) }
            }
        check(
            picked.any { it.name == replaced.first },
        ) { "${type.name}: no constructor field ${replaced.first} in ${picked.map { it.name }}" }
        val arguments =
            picked.map { field ->
                if (field.name == replaced.first) {
                    replaced.second
                } else {
                    field.isAccessible = true
                    field.get(original)
                }
            }
        return constructor.newInstance(*arguments.toTypedArray())
    }

    private data class ObservedRun(
        val state: String,
        val part: JsonNode,
        val declared: Long,
        val startReserved: Long,
        val peakReserved: Long,
        val endReserved: Long,
        val reservations: Int,
        val releases: Int,
        val databasesSeen: List<String>,
        val leftInScratch: List<String>,
    )

    private data class AdapterRead(
        val rows: Long,
        val millis: Long,
        val firstRowMillis: Long,
        val failure: String?,
        val releases: Int,
        val startReserved: Long,
        val endReserved: Long,
        val leftInScratch: List<String>,
    )

    companion object {
        private val SECRET = E2eSession.newSecret()
        private val oidc = OidcDiscoveryStub()
        private val WORKSPACE = UUID.randomUUID()
        private val ADMIN = UUID.randomUUID()
        private val AUTHOR = UUID.randomUUID()
        private const val WORKSPACE_NAME = "lane969"
        private const val NAMESPACE = "lane969"
        private const val LAKE = "lake969e2e"
        private const val BUCKET = "dp969-compaction-e2e"
        private const val TWO_GROUPS = "two_groups"
        private const val SPILLS = "spills"
        private const val MISSING = "missing"
        private const val TIMING = "timing"
        private const val TIMING_REPEATS = 2_500
        private const val TIMING_ROWS = 6_000L
        private const val TWO_GROUPS_ROWS = 100_000L
        private const val SPILLS_ROWS = 400_000L
        private const val MISSING_ROWS = 1_000L
        private const val ADAPTER_GRANT = 950L shl 20
        private const val MIN_FIRST_ROW_MILLIS = 2_000L
        private const val SAMPLE_MILLIS = 5L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val EXECUTE_TIMEOUT_SECONDS = 180L
        private const val WORKER = "co.datapipelines.lake.maintenance.MaintenanceWorker"
        private const val PLANNER = "co.datapipelines.lake.maintenance.plan.MaintenancePlanner"
        private const val SCRATCH = "co.datapipelines.lake.maintenance.MaintenanceScratch"
        private const val PORT = "co.datapipelines.lake.maintenance.compaction.CompactionScratchPort"
        private const val STATEMENT = "co.datapipelines.lake.maintenance.compaction.CompactionStatement"
        private const val DEADLINE_PASSED = "co.datapipelines.lake.maintenance.compaction.CompactionDeadlinePassed"

        /** Two regions (an identity partition), keys in scrambled order, 64-character hexadecimal payloads. */
        private const val TWO_GROUPS_FIELDS =
            """{"fields":[{"id":1,"name":"id","type":"BIGINTEGER"},{"id":2,"name":"region","type":"STRING"},""" +
                """{"id":3,"name":"k","type":"INTEGER"},{"id":4,"name":"payload","type":"STRING"}],""" +
                """"partition":[{"field_id":2,"transform":"identity"}],"sort_field_ids":[3,1]}"""

        /** O9's shape (#968): one distinct key per row and 240-character payloads that compress well and sort large. */
        private const val SPILLS_FIELDS =
            """{"fields":[{"id":1,"name":"id","type":"BIGINTEGER"},{"id":2,"name":"k","type":"INTEGER"},""" +
                """{"id":3,"name":"payload","type":"STRING"}],"sort_field_ids":[2,1]}"""

        /** One partition, so its one group holds the removed file and nothing of the run commits. */
        private val TABLES
            get() = listOf(TWO_GROUPS to TWO_GROUPS_FIELDS, SPILLS to SPILLS_FIELDS, MISSING to MISSING_FIELDS, TIMING to MISSING_FIELDS)

        private const val MISSING_FIELDS =
            """{"fields":[{"id":1,"name":"id","type":"BIGINTEGER"},{"id":2,"name":"region","type":"STRING"},""" +
                """{"id":3,"name":"k","type":"INTEGER"},{"id":4,"name":"payload","type":"STRING"}],"sort_field_ids":[3,1]}"""

        private fun twoRegions(
            start: Long,
            rows: Long,
        ): String =
            "SELECT i::BIGINT AS id, CASE WHEN i % 2 = 0 THEN 'eu' ELSE 'us' END AS region, ((i * 7919) % 1009)::INTEGER AS k, " +
                "md5(i::VARCHAR) || md5((i * 31)::VARCHAR) AS payload FROM range($start, ${start + rows}) t(i)"

        /** The timing table's rows: a one-letter payload, so thousands of listed entries sort within the memory limit. */
        private fun narrowRows(
            start: Long,
            rows: Long,
        ): String =
            "SELECT i::BIGINT AS id, CASE WHEN i % 2 = 0 THEN 'eu' ELSE 'us' END AS region, ((i * 7919) % 1009)::INTEGER AS k, " +
                "'p' AS payload FROM range($start, ${start + rows}) t(i)"

        private fun spilling(
            start: Long,
            rows: Long,
        ): String =
            "SELECT i::BIGINT AS id, (hash(i) % 1000000)::INTEGER AS k, repeat(chr((65 + i % 3)::INTEGER), 240) || " +
                "(i % 50)::VARCHAR AS payload FROM range($start, ${start + rows}) t(i)"

        private fun type(name: String): Class<*> = Class.forName(name)

        private fun <T> unwrapped(call: () -> T): T =
            try {
                call()
            } catch (
                @Suppress("SwallowedException") failure: InvocationTargetException, // the reflected call's own throw is its cause
            ) {
                throw failure.targetException
            }

        /** Calls the public method [name] of [receiver] whose parameters accept [arguments]; a null or void answer is Unit. */
        private fun invoke(
            receiver: Any,
            name: String,
            vararg arguments: Any?,
        ): Any =
            unwrapped {
                receiver.javaClass.methods
                    .filter { it.name == name && !it.isBridge && it.parameterCount == arguments.size }
                    .single { method ->
                        method.parameterTypes.indices.all { i ->
                            arguments[i]?.let {
                                method.parameterTypes[i]
                                    .kotlin.javaObjectType
                                    .isInstance(it)
                            }
                                ?: true
                        }
                    }.also { it.isAccessible = true }
                    .invoke(receiver, *arguments) ?: Unit
            }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            ManagedLakeDeliveryProperties.register(registry, SECRET, oidc.issuer, LAKE, BUCKET)
            // Each delivery reserves 32 MB x 4 = 128 MiB: the 400,000-row inputs of the spilling table sort in their own
            // delivery. The budget holds that and the adapter's 600 MiB reservation (C14), never at the same time.
            registry.add("datapipelines.lake.admission.max-delivery-bytes") { "32MB" }
            registry.add("datapipelines.lake.admission.staging-expansion-factor") { "4" }
            registry.add("datapipelines.lake.admission.staging-memory-limit") { "128MB" }
            registry.add("datapipelines.lake.admission.scratch-bytes") { "1GB" }
            registry.add("datapipelines.lake.maintenance.compaction-scratch-factor") { "1" }
            registry.add("datapipelines.lake.maintenance.compaction-bytes-per-second") { "1GB" }
            // The production planner runs at start and then a day later: only the test's measurements decide.
            registry.add("datapipelines.lake.maintenance.plan-interval") { "PT24H" }
        }
    }
}
