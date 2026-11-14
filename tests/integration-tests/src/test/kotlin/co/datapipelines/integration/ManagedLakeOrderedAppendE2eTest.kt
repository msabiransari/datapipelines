package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.function.Function

/**
 * #968 (dp-lake design record §11a call 37, row M14): appends through the production runtime, native staging, the
 * fenced catalog and S3, to tables with sort columns. Each test has its own table, so neither sees the other's files or
 * revisions in any method order (round 2 correction C2).
 *
 * O11 (`orders_o11`, sorted by `k`, then `label`, then `id`): the committed files, read back with Iceberg's own Parquet
 * reader and `SortOrderComparators` (reached by reflection at run time: this module compiles against app only), carry
 * the table's order id and are in its order. The author's SQL attaches `COLLATE NOCASE` to the string sort column
 * `label` (mixed-case values inside every tie of `k`; JDBC still reports `VARCHAR`), so the production scan must order
 * it in Iceberg's string order whatever its collation (round 2 correction C1).
 *
 * O9 (`orders_o9`, sorted by `k`, then `id`): the staging keys are set so a delivery's scratch reservation
 * (`max-delivery-bytes` 8 MB × `staging-expansion-factor` 4 = 32 MiB, DuckDB's `max_temp_directory_size`) holds the landed and materialized relations (about 9 MB on disk:
 * the payload is one of 150 repeated strings, so it stores small) but not the sort's spill of 1,000,000 rows of
 * 240-character payloads (measured on the pinned driver at the same 128 MB memory limit: past 30 MiB of spill). The
 * ordered append fails with `pipeline.staging.scratch_limit_exceeded`, nothing is committed, the ledger row is FAILED in
 * phase `publish` with that code. The control is the same delivery to the same table with both sort columns left out
 * (its template selects `payload` only), so its write scan has no `ORDER BY`; leaving out `k` alone would still order
 * by `id` (#968 D2). Under the same reservation it publishes, so the sort is what did not fit. Both sort columns are
 * optional in this table for that reason. (A keyless control table cannot be created any more: brief 935 requires
 * sort columns on every new table.)
 */
@SpringBootTest(classes = [DatapipelinesApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ManagedLakeOrderedAppendE2eTest {
    @LocalServerPort private var port = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var context: ApplicationContext
    private val runtime get() = ManagedLakeRuntimeAccess(context)
    private val json = ObjectMapper()

    @BeforeAll
    fun seed() {
        E2eClean.beforeSeeding()
        runtime.restoreStartupSeeds()
        jdbc.update("INSERT INTO workspaces (id, name, display_name) VALUES (?, ?, 'Lane 968')", WORKSPACE, WORKSPACE_NAME)
        listOf(ADMIN to "super_admin", AUTHOR to "author").forEach { (id, role) ->
            jdbc.update(
                "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) " +
                    "VALUES (?, ?, ?, 'test', ?, TRUE, ?)",
                id,
                "$role@lane968.test",
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
                            "display_name" to "Lane 968",
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
        Reflect.createSortedTable(context, ADMIN, O11_TABLE, O11_FIELDS)
        Reflect.createSortedTable(context, ADMIN, O9_TABLE, O9_FIELDS)
        withClue("O9's reservation keys override the shared fixture's") {
            listOf("max-delivery-bytes", "staging-expansion-factor", "staging-memory-limit").map {
                context.environment.getProperty("datapipelines.lake.admission.$it")
            } shouldBe listOf("8MB", "4", "128MB")
            context.environment.getProperty("datapipelines.lake.admission.scratch-bytes") shouldBe "512MB"
        }
    }

    @AfterAll
    fun closeDiscovery() {
        oidc.close()
    }

    @Test
    fun `O11 - a delivery through the production runtime commits files stamped with the table's order and in it`() {
        val pipeline = createPipeline("SELECT id, k, label COLLATE NOCASE AS label, payload FROM staged", O11_TABLE)
        val batch = putDelivery(O11_ROWS, label = MIXED_CASE_LABELS)
        val events = execute(pipeline, batch)
        withClue(events) { events.contains("pipeline_completed") shouldBe true }
        state(batch) shouldBe "PUBLISHED"
        val committed = IcebergOracle().committed(metadataLocation(O11_TABLE), O11_TABLE)
        println("case=o11 order_id=${committed.orderId} files=${committed.files.map { it.sortOrderId to it.rows }}")
        committed.orderId shouldBeGreaterThan 0
        committed.files.size shouldBeGreaterThan 0
        committed.files.sumOf { it.rows } shouldBe O11_ROWS
        committed.files.forEach { file ->
            withClue("${file.location} stamp") { file.sortOrderId shouldBe committed.orderId }
            withClue("${file.location} order (first rows ${file.firstKeys})") { file.inversions shouldBe 0 }
        }
        withClue("nulls first: a file starts with a null k (${committed.files.map { it.firstKeys }})") {
            committed.files.any { it.firstKeys.firstOrNull() == null && it.rows > 0 } shouldBe true
        }
    }

    @Test
    fun `O9 - a sort that outgrows the scratch reservation fails with the staging code, the unsorted control publishes`() {
        val before = revisions(O9_TABLE)
        val control = createPipeline("SELECT payload FROM staged", O9_TABLE)
        val controlBatch = putDelivery(O9_ROWS, O9_WIDTH, SPREAD_KEYS)
        val controlEvents = E2eSse.parseEvents(execute(control, controlBatch), json)
        withClue("the same rows with no sort, same reservation: ${controlEvents.filter { it.first == "node_failed" }}") {
            controlEvents.any { it.first == "pipeline_completed" } shouldBe true
        }
        state(controlBatch) shouldBe "PUBLISHED"
        val revisions = revisions(O9_TABLE)
        revisions shouldBe before + 1
        println("case=o9_control ledger=PUBLISHED rows=$O9_ROWS")

        val sorted = createPipeline("SELECT * FROM staged", O9_TABLE)
        val batch = putDelivery(O9_ROWS, O9_WIDTH, SPREAD_KEYS)
        val location = metadataLocation(O9_TABLE)
        val events = E2eSse.parseEvents(execute(sorted, batch), json)
        val failed = events.single { it.first == "node_failed" }.second
        println("case=o9 node_failed=$failed ledger=${ledger(batch)}")
        failed.path("node_id").asText() shouldBe "append"
        withClue("the ledger row") { ledger(batch) shouldBe Triple("FAILED", "publish", SCRATCH_LIMIT_EXCEEDED) }
        withClue("the caller's code") { failed.path("error").path("code").asText() shouldBe SCRATCH_LIMIT_EXCEEDED }
        revisions(O9_TABLE) shouldBe revisions
        metadataLocation(O9_TABLE) shouldBe location
    }

    private fun createPipeline(
        appendSql: String,
        table: String,
    ): String {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        listOf("land" to "SELECT * FROM delivery", "append" to appendSql).forEach { (node, sql) ->
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
                            "description" to "Lane 968 ordered appends",
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
                {"schema_version":1,"name":"$WORKSPACE_NAME/run_$suffix","display_name":"Lane 968","description":"Lane 968 ordered appends",
                 "settings":{"tempdb":{"engine":"DUCKDB"}},"parameters":{"batch_id":{"type":"STRING","required":true}},
                 "nodes":[{"id":"land","description":"Read the immutable delivery","type":"DQL","source":"tempdb",
                   "delivery":{"feed":"orders","id":"${'$'}batch_id"},"template":{"id":"$WORKSPACE_NAME/land_$suffix.sql","version":1},
                   "output":{"target":"tempdb","table":"staged"},"depends_on":[]},
                  {"id":"append","description":"Publish in the table's order","type":"DQL","source":"tempdb",
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

    /**
     * A Parquet delivery written by DuckDB (on the test runtime through app) at test time: `k` is the SQL expression
     * [keys] over the row number `i`; `payload` is one of 150 strings of [width] characters; `label`, when [label] is
     * given, is that SQL expression over `i`. Nothing is kept in the repository.
     */
    private fun putDelivery(
        rows: Long,
        width: Int = 8,
        keys: String = SCRAMBLED_KEYS,
        label: String? = null,
    ): String {
        val batch = "batch_" + UUID.randomUUID().toString()
        val dir = Files.createTempDirectory("lane968")
        val file = dir.resolve("input.parquet")
        try {
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "COPY (SELECT i::BIGINT AS id, $keys AS k, " + (label?.let { "$it AS label, " } ?: "") +
                            "repeat(chr((65 + i % 3)::INTEGER), $width) || (i % 50)::VARCHAR AS payload FROM range($rows) t(i)) " +
                            "TO '$file' (FORMAT parquet, COMPRESSION zstd)",
                    )
                }
            }
            SharedE2e.s3Client().use { client ->
                client.putObject({ it.bucket(BUCKET).key("landing/orders/$batch/input.parquet") }, RequestBody.fromFile(file))
                client.putObject({ it.bucket(BUCKET).key("landing/orders/$batch/_done") }, RequestBody.empty())
            }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(dir)
        }
        return batch
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

    private fun state(batch: String): String? =
        jdbc.queryForList("SELECT state FROM lake_deliveries WHERE delivery_id = ?", String::class.java, batch).singleOrNull()

    private fun ledger(batch: String): Triple<String?, String?, String?> =
        jdbc
            .query(
                "SELECT state, failure_phase, failure_code FROM lake_deliveries WHERE delivery_id = ?",
                { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) },
                batch,
            ).single()

    private fun revisions(table: String): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM lake_table_revisions WHERE namespace = ? AND table_name = ?",
                Int::class.java,
                NAMESPACE,
                table,
            ),
        )

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
            E2eSession.jwt(SECRET, user.toString(), "super_admin@lane968.test", WORKSPACE_NAME)
        } else {
            E2eSession.jwt(SECRET, user.toString(), "author@lane968.test", WORKSPACE_NAME)
        }

    /** One committed data file as Iceberg reads it: its stamp, its row count, comparator inversions, its first keys. */
    private data class CommittedFile(
        val location: String,
        val sortOrderId: Int,
        val rows: Long,
        val inversions: Int,
        val firstKeys: List<Any?>,
    )

    private data class Committed(
        val orderId: Int,
        val files: List<CommittedFile>,
    )

    /**
     * Iceberg's own reader and comparator (the oracle of the brief's section B), reached by reflection: this module may
     * compile against app only (module-structure §4.2), and app carries Iceberg at run time. Each method is looked up on
     * the public type that declares it, so an implementation class's visibility never matters.
     */
    private class IcebergOracle {
        private val io: Any =
            Class.forName("org.apache.iceberg.aws.s3.S3FileIO").getConstructor().newInstance().also { io ->
                call(
                    "org.apache.iceberg.io.FileIO",
                    io,
                    "initialize",
                    mapOf(
                        "s3.endpoint" to "http://${SharedE2e.minioEndpoint}",
                        "s3.path-style-access" to "true",
                        "s3.access-key-id" to SharedE2e.MINIO_USER,
                        "s3.secret-access-key" to SharedE2e.MINIO_PASSWORD,
                        "client.region" to "us-east-1",
                        "http-client.type" to "urlconnection",
                    ),
                )
            }

        @Suppress("UNCHECKED_CAST")
        fun committed(
            metadataLocation: String,
            tableName: String,
        ): Committed {
            val ops =
                Class
                    .forName("org.apache.iceberg.StaticTableOperations")
                    .getConstructor(String::class.java, type("org.apache.iceberg.io.FileIO"))
                    .newInstance(metadataLocation, io)
            val table =
                Class
                    .forName("org.apache.iceberg.BaseTable")
                    .getConstructor(type("org.apache.iceberg.TableOperations"), String::class.java)
                    .newInstance(ops, "$NAMESPACE.$tableName")
            val schema = call(TABLE, table, "schema")
            val order = call(TABLE, table, "sortOrder")
            val orderId = call("org.apache.iceberg.SortOrder", order, "orderId") as Int
            val comparator = staticCall("org.apache.iceberg.SortOrderComparators", "forSchema", schema, order) as Comparator<Any?>
            val struct = call("org.apache.iceberg.Schema", schema, "asStruct")
            val tasks = call("org.apache.iceberg.Scan", call(TABLE, table, "newScan"), "planFiles") as Iterable<Any?>
            val files =
                tasks.map { task ->
                    val file = call("org.apache.iceberg.ContentScanTask", task, "file")
                    val location = call(CONTENT_FILE, file, "location") as String
                    val records = read(schema, location)
                    val left = wrapper(struct)
                    val right = wrapper(struct)
                    val inversions =
                        records.zipWithNext().count { (a, b) ->
                            comparator.compare(wrap(left, a), wrap(right, b)) > 0
                        }
                    CommittedFile(
                        location,
                        call(CONTENT_FILE, file, "sortOrderId") as Int,
                        records.size.toLong(),
                        inversions,
                        records.take(FIRST_KEYS).map { type(RECORD).getMethod("getField", String::class.java).invoke(it, "k") },
                    )
                }
            (tasks as AutoCloseable).close()
            return Committed(orderId, files)
        }

        @Suppress("UNCHECKED_CAST")
        private fun read(
            schema: Any,
            location: String,
        ): List<Any> {
            val input = call("org.apache.iceberg.io.FileIO", io, "newInputFile", location)
            val builder =
                type("org.apache.iceberg.parquet.Parquet")
                    .getMethod("read", type("org.apache.iceberg.io.InputFile"))
                    .invoke(null, input)
            val builderType = type("org.apache.iceberg.parquet.Parquet\$ReadBuilder")
            builderType.getMethod("project", type("org.apache.iceberg.Schema")).invoke(builder, schema)
            val buildReader =
                type("org.apache.iceberg.data.parquet.GenericParquetReaders")
                    .getMethod("buildReader", type("org.apache.iceberg.Schema"), type("org.apache.parquet.schema.MessageType"))
            builderType
                .getMethod("createReaderFunc", Function::class.java)
                .invoke(builder, Function<Any, Any> { fileSchema -> buildReader.invoke(null, schema, fileSchema) })
            val records = builderType.getMethod("build").invoke(builder) as Iterable<Any>
            return try {
                records.map { call(RECORD, it, "copy") }
            } finally {
                (records as AutoCloseable).close()
            }
        }

        private fun wrapper(struct: Any): Any =
            type("org.apache.iceberg.data.InternalRecordWrapper")
                .getConstructor(type("org.apache.iceberg.types.Types\$StructType"))
                .newInstance(struct)

        private fun wrap(
            wrapper: Any,
            record: Any,
        ): Any =
            type("org.apache.iceberg.data.InternalRecordWrapper")
                .getMethod("wrap", type("org.apache.iceberg.StructLike"))
                .invoke(wrapper, record)
    }

    /** Runtime access to the definition service (as [ManagedLakeRuntimeAccess] does): one sorted table per test. */
    private object Reflect {
        fun createSortedTable(
            context: ApplicationContext,
            actor: UUID,
            table: String,
            body: String,
        ) {
            val definitions = context.getBean(Class.forName("co.datapipelines.lake.definitions.LakeDefinitionService"))
            val surface =
                Class.forName("co.datapipelines.pipeline.WriteSurface").enumConstants.single { (it as Enum<*>).name == "SESSION" }
            val draft =
                invoke(
                    definitions,
                    "createDraft",
                    NAMESPACE,
                    table,
                    body,
                    actor,
                    surface,
                )
            invoke(definitions, "release", invoke(draft, "getTableId"), invoke(draft, "getVersion"), actor, surface, null)
        }

        private fun invoke(
            receiver: Any,
            name: String,
            vararg arguments: Any?,
        ): Any =
            try {
                receiver.javaClass.methods
                    .single { it.name == name && it.parameterCount == arguments.size }
                    .invoke(receiver, *arguments) ?: Unit
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
    }

    companion object {
        private val SECRET = E2eSession.newSecret()
        private val oidc = OidcDiscoveryStub()
        private val WORKSPACE = UUID.randomUUID()
        private val ADMIN = UUID.randomUUID()
        private val AUTHOR = UUID.randomUUID()
        private const val WORKSPACE_NAME = "lane968"
        private const val NAMESPACE = "lane968"
        private const val LAKE = "lake968e2e"
        private const val BUCKET = "dp968-ordered-e2e"
        private const val SCRATCH_LIMIT_EXCEEDED = "pipeline.staging.scratch_limit_exceeded"
        private const val O11_ROWS = 5_000L
        private const val O9_ROWS = 1_000_000L
        private const val O9_WIDTH = 240
        private const val O11_TABLE = "orders_o11"
        private const val O9_TABLE = "orders_o9"

        /** O11's table: sorted by `k`, then the string `label`, then `id`; every sort column optional. */
        private const val O11_FIELDS =
            """{"fields":[{"id":1,"name":"id","type":"BIGINTEGER"},{"id":2,"name":"k","type":"INTEGER"},""" +
                """{"id":3,"name":"label","type":"STRING"},{"id":4,"name":"payload","type":"STRING"}],"sort_field_ids":[2,3,1]}"""

        /** O9's table: sorted by `k`, then `id`, both optional (its control leaves both out). */
        private const val O9_FIELDS =
            """{"fields":[{"id":1,"name":"id","type":"BIGINTEGER"},{"id":2,"name":"k","type":"INTEGER"},""" +
                """{"id":3,"name":"payload","type":"STRING"}],"sort_field_ids":[2,1]}"""

        /**
         * Mixed-case labels whose binary (Iceberg) order `A < B < E < a < b < e` differs from `NOCASE`'s, scattered
         * inside every tie of `k`, null every thirteenth row.
         */
        private const val MIXED_CASE_LABELS = "CASE WHEN i % 13 = 0 THEN NULL ELSE ['a', 'B', 'A', 'b', 'e', 'E'][(i * 31) % 6 + 1] END"

        /** 101 repeated values in scrambled arrival order, null every ninth row: ties and nulls first are both visible. */
        private const val SCRAMBLED_KEYS = "CASE WHEN i % 9 = 0 THEN NULL ELSE ((i * 7919) % 101)::INTEGER END"

        /** The expression of the local spill measurement (lane evidence, D7): about one distinct key per row. */
        private const val SPREAD_KEYS = "(hash(i) % 1000000)::INTEGER"
        private const val EXECUTE_TIMEOUT_SECONDS = 120L
        private const val FIRST_KEYS = 3
        private const val TABLE = "org.apache.iceberg.Table"
        private const val CONTENT_FILE = "org.apache.iceberg.ContentFile"
        private const val RECORD = "org.apache.iceberg.data.Record"

        private fun type(name: String): Class<*> = Class.forName(name)

        private fun call(
            declaring: String,
            receiver: Any?,
            name: String,
            vararg arguments: Any?,
        ): Any =
            try {
                type(declaring)
                    .methods
                    .filter { it.name == name && !it.isBridge && accepts(it.parameterTypes, arguments) }
                    .let { candidates ->
                        candidates.singleOrNull() ?: error("$declaring.$name: ${candidates.size} overloads accept ${arguments.toList()}")
                    }.invoke(receiver, *arguments) ?: Unit
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }

        /** An overload accepts the arguments when each one is an instance of its parameter's type (a null: any reference type). */
        private fun accepts(
            parameters: Array<Class<*>>,
            arguments: Array<out Any?>,
        ): Boolean =
            parameters.size == arguments.size &&
                parameters.indices.all { i -> arguments[i]?.let { parameters[i].isInstance(it) } ?: !parameters[i].isPrimitive }

        private fun staticCall(
            declaring: String,
            name: String,
            vararg arguments: Any?,
        ): Any = call(declaring, null, name, *arguments)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            ManagedLakeDeliveryProperties.register(registry, SECRET, oidc.issuer, LAKE, BUCKET)
            // O9's reservation: 8 MB x 4 = 32 MiB of scratch for each delivery; 128 MB of engine memory so the sort
            // spills rather than failing on the memory limit (measured: at 64 MB the sort cannot spill at all).
            registry.add("datapipelines.lake.admission.max-delivery-bytes") { "8MB" }
            registry.add("datapipelines.lake.admission.staging-expansion-factor") { "4" }
            registry.add("datapipelines.lake.admission.staging-memory-limit") { "128MB" }
            // The machine's scratch budget must hold one default reservation, memory limit x factor (LakeRules, record
            // §15); the delivery's own reservation is still max-delivery-bytes x factor (DeliveryAdmission).
            registry.add("datapipelines.lake.admission.scratch-bytes") { "512MB" }
        }
    }
}
