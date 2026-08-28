package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DeleteResult
import co.datapipelines.datasources.TestResult
import co.datapipelines.datasources.ValidationResult
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.events.EventEmitter
import co.datapipelines.events.ExecutionEvent
import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.staging.StagingFactory
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Shared builders for the executor suites. */
object Fixtures {
    /** A DQL node reading tempdb and returning to the caller — the simplest runnable node. */
    fun node(
        id: String,
        type: NodeType = NodeType.DQL,
        source: String = "tempdb",
        output: NodeOutput? = NodeOutput.Caller,
        dependsOn: List<String> = emptyList(),
        template: TemplateRef = TemplateRef(id, 1),
    ): Node =
        Node(
            id = id,
            description = "node $id",
            type = type,
            source = source,
            template = template,
            output = if (type == NodeType.DQL) output else null,
            dependsOn = dependsOn,
        )

    fun pipeline(
        nodes: List<Node>,
        parameters: Map<String, Parameter> = emptyMap(),
        settings: PipelineSettings = PipelineSettings(),
    ): Pipeline =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = "test_pipeline",
            displayName = "Test pipeline",
            description = "fixture",
            settings = settings,
            parameters = parameters,
            nodes = nodes,
        )

    fun request(
        pipeline: Pipeline,
        userId: UUID = UUID.randomUUID(),
        resultTtlSeconds: Long? = null,
        correlationId: UUID? = null,
    ): ExecuteRequest =
        ExecuteRequest(
            pipelineId = UUID.randomUUID(),
            pipelineVersion = 1,
            pipeline = pipeline,
            userId = userId,
            resultTtlSeconds = resultTtlSeconds,
            correlationId = correlationId,
        )

    /** A template engine that returns [sql] for every reference, recording the budget it was given. */
    fun templateEngine(sql: String): Pair<TemplateEngine, MutableList<Long>> {
        val budgets = CopyOnWriteArrayList<Long>()
        val engine = mockk<TemplateEngine>()
        every { engine.render(any(), any(), any()) } answers
            {
                budgets += thirdArg<Long>()
                sql
            }
        return engine to budgets
    }

    /** A template engine whose SQL depends on the node's template id. */
    fun templateEngine(sqlByTemplateId: Map<String, String>): TemplateEngine {
        val engine = mockk<TemplateEngine>()
        every { engine.render(any(), any(), any()) } answers
            {
                val ref = firstArg<TemplateRef>()
                sqlByTemplateId[ref.id] ?: error("no SQL fixture for template '${ref.id}'")
            }
        return engine
    }

    /**
     * A template engine that returns one constant SQL string, recorded with `returns` rather than
     * `answers`.
     *
     * That distinction is why this exists. `TemplateEngine` is a final class, so a mock is the only
     * way to stand it in — but MockK's answer-block machinery is not built for a single mock being
     * invoked from many threads at once, and a genuinely concurrent test does exactly that. The
     * `answers`-based fixtures below are fine for the suites that drive one execution at a time;
     * anything that runs executions **concurrently** must use this one, or the hang it produces is
     * in the test harness rather than in the executor under test.
     */
    fun constantTemplateEngine(sql: String): TemplateEngine {
        val engine = mockk<TemplateEngine>()
        every { engine.render(any(), any(), any()) } returns sql
        return engine
    }

    fun stagingFactory(): StagingFactory = H2StagingFactory(H2StagingProperties())

    /**
     * A query H2 really iterates, so `Statement.cancel()` and `setQueryTimeout` can both bite.
     *
     * Verified against the pinned 2.3.232 driver: `SELECT COUNT(*) FROM SYSTEM_RANGE(...)` alone is
     * **not** usable — H2 answers the count from the range's cardinality without visiting a row, so
     * a 1-second timeout never fires and a `cancel()` never lands. The cross join plus a predicate
     * forces ~9·10⁸ row visits (minutes of work), and both interrupts then surface as SQLState
     * `57014`. A slow query that is secretly instant would make every cancellation test vacuous —
     * and it briefly was: the unquoted `a.X` this started as fails in 9ms with `Column "a.x" not
     * found` under [h2Datasource]'s `DATABASE_TO_LOWER=TRUE`, because `SYSTEM_RANGE`'s column is
     * `X`. Hence `a."X"`. (The same folding trap `StagedIdentifierCaseFoldingTest` records, met in
     * the test fixtures themselves.)
     */
    const val SLOW_SQL =
        """SELECT COUNT(*) FROM SYSTEM_RANGE(1, 30000) a, SYSTEM_RANGE(1, 30000) b WHERE MOD(a."X" + b."X", 7) = 0"""

    /** Locates a repository file by walking up from the working directory. */
    fun repoFile(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relativePath not found walking up from ${File("").absolutePath}")
    }
}

/** Captures every emitted event so a test can assert on order and on exactly-once rules. */
class RecordingEmitter : EventEmitter {
    val events = CopyOnWriteArrayList<ExecutionEvent>()

    override suspend fun emit(event: ExecutionEvent) {
        events += event
    }

    fun types(): List<SseEventType> = events.map { it.type }

    fun count(type: SseEventType): Int = events.count { it.type == type }

    inline fun <reified T : ExecutionEvent> firstOf(): T = events.filterIsInstance<T>().first()

    inline fun <reified T : ExecutionEvent> allOf(): List<T> = events.filterIsInstance<T>()
}

/**
 * An in-memory [ResultStore] for unit tests: same contract, no Redis.
 *
 * The Redis implementation is exercised for real by `RedisResultStoreIntegrationTest`; this
 * exists so node-level tests do not need a container to assert dispatch behaviour.
 */
class InMemoryResultStore(
    private val config: ResultConfig = ResultConfig(),
    /** When set, every `materialize` fails with this — the `storage_unavailable` simulation. */
    private val failWith: Throwable? = null,
) : ResultStore {
    /**
     * `ConcurrentHashMap`, not `mutableMapOf`.
     *
     * This fixture is shared by every execution an [ExecutorHarness] runs, and the concurrency
     * suites run a dozen at once. A plain `HashMap` under concurrent `put` corrupts silently — and
     * a resize race can spin a thread indefinitely, which is how this first showed up: not as a
     * failed assertion but as a suite that stopped making progress.
     */
    private val stored = java.util.concurrent.ConcurrentHashMap<String, StoredResultView>()

    override suspend fun materialize(
        executionId: UUID,
        resultSet: java.sql.ResultSet,
        sourceDialect: Dialect,
        ttlSeconds: Long,
    ): StoredResult {
        failWith?.let { throw it }
        val schema = ResultRowReader.schemaOf(resultSet.metaData, sourceDialect)
        val rows = mutableListOf<List<Any?>>()
        var bytes = 0L
        while (resultSet.next()) {
            val row = schema.columns.mapIndexed { i, c -> ResultRowReader.readValue(resultSet, i + 1, c) }
            bytes += row.sumOf { (it?.toString() ?: "null").length.toLong() }
            if (bytes > config.maxSizeBytes) {
                throw co.datapipelines.typesystem.DatapipelinesException(
                    code = co.datapipelines.pipeline.PipelineErrorCodes.Result.TOO_LARGE,
                    message = "over cap",
                )
            }
            rows += row
        }
        val key = keyFor(executionId)
        stored[key] =
            StoredResultView(
                key = key,
                executionId = executionId,
                schema = schema.columns,
                firstPage = rows.take(config.pageSizeRows),
                totalRows = rows.size.toLong(),
                bytes = bytes,
                expiresAt = Instant.now().plusSeconds(ttlSeconds),
                warnings = schema.warnings,
            )
        return StoredResult(key, rows.size.toLong(), bytes, stored.getValue(key).expiresAt, schema.warnings)
    }

    override suspend fun materializeRows(
        executionId: UUID,
        schema: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
        ttlSeconds: Long,
    ): StoredResult {
        failWith?.let { throw it }
        val collected = rows.toList()
        val bytes = collected.sumOf { row -> row.sumOf { (it?.toString() ?: "null").length.toLong() } }
        val key = keyFor(executionId)
        stored[key] =
            StoredResultView(
                key = key,
                executionId = executionId,
                schema = schema,
                firstPage = collected.take(config.pageSizeRows),
                totalRows = collected.size.toLong(),
                bytes = bytes,
                expiresAt = Instant.now().plusSeconds(ttlSeconds),
            )
        return StoredResult(key, collected.size.toLong(), bytes, stored.getValue(key).expiresAt)
    }

    /** This fake owns its own keyspace — deliberately NOT Redis's, so the two never get confused. */
    override fun keyFor(executionId: UUID): String = "mem:result:$executionId"

    override fun describe(key: String): StoredResultView? = stored[key]

    override fun page(
        key: String,
        offset: Long,
        limit: Int,
    ): ResultPage? = null

    override fun discard(key: String) {
        stored.remove(key)
    }
}

/** A [CancellationFlags] backed by a map — the same contract without a Redis round trip. */
class InMemoryCancellationFlags : CancellationFlags {
    private val flags = mutableMapOf<UUID, AbortReason>()

    /** Reads served so far — proves the executor really polls at node boundaries. */
    val reads = AtomicInteger()

    override fun request(
        executionId: UUID,
        reason: AbortReason,
        ttlSeconds: Long,
    ) {
        flags[executionId] = reason
    }

    override fun read(executionId: UUID): AbortReason? {
        reads.incrementAndGet()
        return flags[executionId]
    }

    override fun clear(executionId: UUID) {
        flags.remove(executionId)
    }
}

/**
 * A registry over throwaway in-memory H2 databases, standing in for external datasources.
 *
 * Real driver, real pool semantics, real `Statement.cancel()` — the parts a mocked `Connection`
 * would quietly fake.
 *
 * [liveEntries] is what [getLive] serves — by default the same map, which is the
 * "cache-through" shape. A workspaces-D10 test passes a DIFFERENT map so the cached view
 * ([get]) and the live view ([getLive]) disagree exactly the way a readonly flag flipped in
 * the DB after a pipeline was saved makes them disagree.
 */
class FakeDatasourceRegistry(
    private val datasources: Map<String, Datasource>,
    private val liveEntries: Map<String, Datasource> = datasources,
) : DatasourceRegistry {
    /** Connections handed out, and the ones handed back — the resource-leak assertion surface. */
    val leased = AtomicInteger()
    val closed = AtomicInteger()

    override fun list(dialect: Dialect?): List<Datasource> = datasources.values.toList()

    override fun get(name: String): Datasource? = datasources[name]

    override fun getLive(name: String): Datasource? = liveEntries[name]

    override fun exists(name: String): Boolean = name in datasources

    override fun save(
        datasource: Datasource,
        actor: UUID,
    ): Datasource = datasource

    override fun validate(datasource: Datasource): ValidationResult = ValidationResult.ok()

    override fun delete(name: String): DeleteResult = DeleteResult(true, name)

    override fun poolFor(datasource: Datasource): ConnectionPool = TrackingPool(datasource, leased, closed)

    override fun testConnection(name: String): TestResult? = TestResult(true, Instant.now())

    private class TrackingPool(
        private val datasource: Datasource,
        private val leased: AtomicInteger,
        private val closed: AtomicInteger,
    ) : ConnectionPool {
        override val name: String get() = datasource.name

        override fun leaseConnection(): Connection {
            leased.incrementAndGet()
            // The password matters once a container-backed source is in play (C4); H2 fixtures
            // leave it null and get the empty string they had before.
            val delegate = DriverManager.getConnection(datasource.jdbcUrl, datasource.username, datasource.password ?: "")
            return CountingConnection(delegate, closed)
        }

        override fun close() = Unit
    }
}

/** Counts closes so a test can prove every leased connection was returned. */
private class CountingConnection(
    private val delegate: Connection,
    private val closed: AtomicInteger,
) : Connection by delegate {
    override fun close() {
        closed.incrementAndGet()
        delegate.close()
    }
}

/**
 * Creates an H2 "source database" seeded with [ddl], and the [Datasource] pointing at it.
 *
 * `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE` is not decoration, and **both** parameters are load
 * bearing: verified against the pinned driver (2.3.232), `MODE=PostgreSQL` **alone does not**
 * lower-fold unquoted identifiers — `CREATE TABLE tgt` still stores `TGT`, and `DATABASE_TO_LOWER`
 * is what makes it store `tgt`, which is Postgres' actual rule and therefore the rule the dominant
 * supported dialect follows. Write-back quotes every identifier it emits (staging §4.5,
 * dag-executor §6.4.3), so a target created by unquoted author DDL only resolves under lower
 * folding; H2's *native* upper folding is what [upperFoldingH2Datasource] exercises deliberately.
 */
fun h2Datasource(
    name: String,
    ddl: List<String>,
    queryTimeoutSeconds: Int? = null,
): Datasource = h2Datasource(name, ddl, queryTimeoutSeconds, mode = ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")

/**
 * The same, in H2's **native** mode, where unquoted DDL folds identifiers to UPPER case.
 *
 * This is the Oracle/DB2 family's rule. It is a real interop wart for write-back, and pinning it
 * in a test is the point: a lowercase `output.table` quoted per spec does not match an
 * upper-folded stored name, and the failure must be the actionable `writeback_target_missing`
 * rather than a silent no-op or a confusing syntax error.
 */
fun upperFoldingH2Datasource(
    name: String,
    ddl: List<String>,
): Datasource = h2Datasource(name, ddl, queryTimeoutSeconds = null, mode = "")

private fun h2Datasource(
    name: String,
    ddl: List<String>,
    queryTimeoutSeconds: Int?,
    mode: String,
): Datasource {
    val url = "jdbc:h2:mem:src_${name}_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1$mode"
    DriverManager.getConnection(url, "sa", "").use { connection ->
        connection.createStatement().use { statement -> ddl.forEach(statement::execute) }
    }
    return Datasource(
        name = name,
        displayName = name,
        dialect = Dialect.H2,
        jdbcUrl = url,
        username = "sa",
        queryTimeoutSeconds = queryTimeoutSeconds,
    )
}

/** A [java.sql.Statement] that records `cancel()` calls; everything else is unsupported. */
class RecordingStatement : Statement by NoopStatement() {
    val cancels = AtomicInteger()

    override fun cancel() {
        cancels.incrementAndGet()
    }
}

/**
 * A `Statement` whose methods all throw — a delegation base so [RecordingStatement] can override
 * exactly the one method under test without a mocking framework generating 60 stubs.
 */
private class NoopStatement : Statement {
    @Suppress("TooGenericExceptionThrown")
    private fun unsupported(): Nothing = throw UnsupportedOperationException("test double")

    override fun <T : Any?> unwrap(iface: Class<T>): T = unsupported()

    override fun isWrapperFor(iface: Class<*>): Boolean = false

    override fun close() = Unit

    override fun executeQuery(sql: String): java.sql.ResultSet = unsupported()

    override fun executeUpdate(sql: String): Int = unsupported()

    override fun getMaxFieldSize(): Int = 0

    override fun setMaxFieldSize(max: Int) = Unit

    override fun getMaxRows(): Int = 0

    override fun setMaxRows(max: Int) = Unit

    override fun setEscapeProcessing(enable: Boolean) = Unit

    override fun getQueryTimeout(): Int = 0

    override fun setQueryTimeout(seconds: Int) = Unit

    override fun cancel() = Unit

    override fun getWarnings(): java.sql.SQLWarning? = null

    override fun clearWarnings() = Unit

    override fun setCursorName(name: String?) = Unit

    override fun execute(sql: String): Boolean = unsupported()

    override fun getResultSet(): java.sql.ResultSet? = null

    override fun getUpdateCount(): Int = -1

    override fun getMoreResults(): Boolean = false

    override fun setFetchDirection(direction: Int) = Unit

    override fun getFetchDirection(): Int = java.sql.ResultSet.FETCH_FORWARD

    override fun setFetchSize(rows: Int) = Unit

    override fun getFetchSize(): Int = 0

    override fun getResultSetConcurrency(): Int = java.sql.ResultSet.CONCUR_READ_ONLY

    override fun getResultSetType(): Int = java.sql.ResultSet.TYPE_FORWARD_ONLY

    override fun addBatch(sql: String) = Unit

    override fun clearBatch() = Unit

    override fun executeBatch(): IntArray = IntArray(0)

    override fun getConnection(): Connection = unsupported()

    override fun getMoreResults(current: Int): Boolean = false

    override fun getGeneratedKeys(): java.sql.ResultSet = unsupported()

    override fun executeUpdate(
        sql: String,
        autoGeneratedKeys: Int,
    ): Int = unsupported()

    override fun executeUpdate(
        sql: String,
        columnIndexes: IntArray,
    ): Int = unsupported()

    override fun executeUpdate(
        sql: String,
        columnNames: Array<out String>,
    ): Int = unsupported()

    override fun execute(
        sql: String,
        autoGeneratedKeys: Int,
    ): Boolean = unsupported()

    override fun execute(
        sql: String,
        columnIndexes: IntArray,
    ): Boolean = unsupported()

    override fun execute(
        sql: String,
        columnNames: Array<out String>,
    ): Boolean = unsupported()

    override fun getResultSetHoldability(): Int = java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT

    override fun isClosed(): Boolean = false

    override fun setPoolable(poolable: Boolean) = Unit

    override fun isPoolable(): Boolean = false

    override fun closeOnCompletion() = Unit

    override fun isCloseOnCompletion(): Boolean = false
}

/**
 * A [ResultStore] that parks inside `materialize` until released, then reports the store as gone.
 *
 * The shape F12/C7 needs: an abort that lands *while* a node is mid-materialisation, so the node's
 * own failure and the execution's abort are genuinely concurrent rather than sequenced by the test.
 */
class LatchedResultStore(
    private val entered: java.util.concurrent.CountDownLatch,
    private val release: java.util.concurrent.CountDownLatch,
) : ResultStore {
    override suspend fun materialize(
        executionId: UUID,
        resultSet: java.sql.ResultSet,
        sourceDialect: Dialect,
        ttlSeconds: Long,
    ): StoredResult {
        entered.countDown()
        release.await()
        throw co.datapipelines.typesystem.DatapipelinesException(
            code = co.datapipelines.pipeline.PipelineErrorCodes.Result.STORAGE_UNAVAILABLE,
            message = "store went away mid-drain",
        )
    }

    override suspend fun materializeRows(
        executionId: UUID,
        schema: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
        ttlSeconds: Long,
    ): StoredResult {
        entered.countDown()
        release.await()
        throw co.datapipelines.typesystem.DatapipelinesException(
            code = co.datapipelines.pipeline.PipelineErrorCodes.Result.STORAGE_UNAVAILABLE,
            message = "store went away mid-drain",
        )
    }

    override fun keyFor(executionId: UUID): String = "latched:result:$executionId"

    override fun describe(key: String): StoredResultView? = null

    override fun page(
        key: String,
        offset: Long,
        limit: Int,
    ): ResultPage? = null

    override fun discard(key: String) = Unit
}

/**
 * Records what a `direct` delivery (design §4.2) streamed to it.
 *
 * The rows are consumed **inside** `accept`, as the contract requires — the sequence is lazy over
 * a still-open cursor and reads garbage (or throws) once the node's `use` block has closed it.
 */
class RecordingSink : DirectResultSink {
    var schema: List<ColumnSchema>? = null
        private set

    val rows = mutableListOf<List<Any?>>()

    override suspend fun accept(
        schema: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
    ) {
        this.schema = schema
        rows.forEach { this.rows += it }
    }
}
