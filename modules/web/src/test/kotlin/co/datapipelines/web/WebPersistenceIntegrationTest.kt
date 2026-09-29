package co.datapipelines.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.events.DataReady
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.NodeCompleted
import co.datapipelines.events.NodeStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.SseEventType
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.NodeStats
import co.datapipelines.executor.NodeStatus
import co.datapipelines.executor.RedisResultStore
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultStore
import co.datapipelines.persistence.BatchingConfig
import co.datapipelines.persistence.BatchingHooks
import co.datapipelines.persistence.BatchingWriter
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.config.RateLimitProperties
import co.datapipelines.web.executions.ResultCursor
import co.datapipelines.web.metrics.WebMetrics
import co.datapipelines.web.ratelimit.RateLimitFilter
import co.datapipelines.web.ratelimit.RedisRateLimiter
import co.datapipelines.web.sse.BatchedEventRecorder
import co.datapipelines.web.sse.DirectEventRecorder
import co.datapipelines.web.sse.ExecutionContext
import co.datapipelines.web.sse.ExecutionEventRowSink
import co.datapipelines.web.sse.LoggedSseEvent
import co.datapipelines.web.sse.RecordedEvent
import co.datapipelines.web.sse.ReplayLogSink
import co.datapipelines.web.sse.SseEventLog
import co.datapipelines.web.sse.WebEventEmitter
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The web surface's persistence story against the real stores: a Postgres container running app's
 * shipped migrations and a Redis container — the same rig `dag`'s integration tests
 * use. Covered end to end:
 *
 *  - [WebEventEmitter]: a full event sequence lands the `pipeline_executions` row (RUNNING →
 *    terminal UPDATE), the durable `execution_events` rows with monotonic ids, and the 1-hour
 *    Redis replay log — with the correlation id on every stored payload (carry-forward #1).
 *  - [SseEventLog]: replay order and content.
 *  - [RedisRateLimiter]: the shared per-user counters really are Redis-backed.
 *  - [ResultCursor] over a real [RedisResultStore]: the stored result pages through
 *    `ResultStore.keyFor` (carry-forward #7).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebPersistenceIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executions: ExecutionRepository
    private lateinit var events: ExecutionEventRepository
    private val redis by lazy { TestRedis.template() }
    private val eventLog by lazy { SseEventLog(redis, co.datapipelines.executor.ExecutorJson.mapper) }

    private lateinit var userId: UUID
    private lateinit var pipelineId: UUID

    /** Binds the JDBC template to the module's shared, already-migrated container. */
    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        // (The shared container's migrations include the §4.6 lineage columns (V3)
        // ExecutionRepository.create now writes.)
    }

    @BeforeEach
    fun setUp() {
        executions = ExecutionRepository(jdbc)
        events = ExecutionEventRepository(jdbc)
        // The CASCADE also reaches workspaces (created_by), so the V4-seeded `default`
        // workspace is re-seeded after every truncate.
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('$DEFAULT_WORKSPACE_ID', 'default', 'Default')",
        )
        TestRedis.flush(redis)
        userId =
            UUID.randomUUID().also { id ->
                jdbc.update(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
                    mapOf("id" to id, "email" to "u$id@example.com", "sub" to "sub-$id"),
                )
            }
        pipelineId =
            UUID.randomUUID().also { id ->
                jdbc.update(
                    """
                    INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)
                    VALUES (:id, :name, 'P', :owner, 1, '$DEFAULT_WORKSPACE_ID')
                    """.trimIndent(),
                    mapOf("id" to id, "name" to "p_${id.toString().replace("-", "")}", "owner" to userId),
                )
                jdbc.update(
                    """
                    INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)
                    VALUES (:id, 1, CAST('{}' AS jsonb), 'seed-hash', 'RELEASED', :owner, :owner, NOW())
                    """.trimIndent(),
                    mapOf("id" to id, "owner" to userId),
                )
            }
    }

    @Test
    fun `a full event sequence persists the row, the events and the replay log`() =
        runTest {
            val executionId = UUID.randomUUID()
            val correlationId = UUID.randomUUID()
            val emitter =
                WebEventEmitter(
                    context =
                        ExecutionContext(
                            pipelineId = pipelineId,
                            pipelineVersion = 1,
                            userId = userId,
                            correlationId = correlationId,
                            triggeredVia = ExecutionTrigger.REST,
                            parametersJson = "{}",
                            workspaceId = DEFAULT_WORKSPACE_ID,
                        ),
                    stream = null,
                    streams = mockkRegistry(),
                    eventLog = eventLog,
                    eventRepository = events,
                    executionRepository = executions,
                    persistenceDispatcher = Dispatchers.Default,
                )
            val started = Instant.parse("2026-08-05T14:30:00Z")
            val stats =
                NodeStats("n1", NodeStatus.SUCCESS, started, started.plusMillis(900), 900, 10, 100)

            emitter.emit(ExecutionStarted(executionId, pipelineId, 1, emptyMap(), startedAt = started))
            emitter.emit(NodeStarted(executionId, "n1", started))
            emitter.emit(NodeCompleted(executionId, "n1", stats))
            emitter.emit(PipelineCompleted(executionId, pipelineId, 1, started, started.plusMillis(900), 900, listOf(stats)))
            emitter.emit(
                DataReady(executionId, pipelineId, emptyList(), emptyList(), 0, false, "http://x/result", started, 300),
            )

            val row = executions.findById(DEFAULT_WORKSPACE_ID, executionId).shouldNotBeNull()
            row.status shouldBe ExecutionStatus.SUCCESS
            row.durationMs shouldBe 900L
            row.triggeredVia shouldBe ExecutionTrigger.REST
            row.correlationId shouldBe correlationId

            val stored = events.findByExecution(executionId)
            stored shouldHaveSize 5
            stored.map { it.eventId } shouldBe listOf(1, 2, 3, 4, 5)
            stored.forEach {
                // jsonb::TEXT is Postgres-normalized, so parse rather than substring-match.
                co.datapipelines.executor.ExecutorJson.mapper
                    .readTree(it.payloadJson)
                    .get("correlation_id")
                    .asText() shouldBe correlationId.toString()
            }

            val replayed = eventLog.replay(executionId).shouldNotBeNull()
            replayed.map { it.eventName } shouldBe
                listOf("execution_started", "node_started", "node_completed", "pipeline_completed", "data_ready")
            replayed.map { it.eventId } shouldBe listOf(1, 2, 3, 4, 5)
        }

    @Test
    fun `the direct recorder's WARN for a refused row names the failure's class and SQLState, never the payload`() =
        runBlocking<Unit> {
            // #266b: the pre-266 path (`datapipelines.persistence.enabled: false`) attached the store's
            // exception to its WARN, and Postgres quotes a refused JSONB payload in CONTEXT. A NUL in a
            // payload value is such a row: Jackson escapes it, the jsonb cast refuses it — before any
            // foreign key is checked, so no execution row is needed.
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val recorderLog = LoggerFactory.getLogger(DirectEventRecorder::class.java) as Logger
            recorderLog.addAppender(appender)
            try {
                DirectEventRecorder(events, eventLog, Dispatchers.IO).record(
                    RecordedEvent(UUID.randomUUID(), 1, SseEventType.NODE_STARTED, "node_started", Instant.now(), mapOf("v" to "$ROW_CONTENT\u0000")),
                )
            } finally {
                recorderLog.detachAppender(appender)
            }
            val rendered = appender.list.map { it.formattedMessage + " " + it.throwableProxy.render() }
            withClue("every WARN, rendered whole: $rendered") {
                rendered.none { it.contains(ROW_CONTENT) } shouldBe true
                rendered.single() shouldContain "sql_state=22P05"
            }
        }

    private fun IThrowableProxy?.render(): String =
        if (this == null) "" else "$className: $message | " + cause.render() + suppressed.joinToString(" ") { it.render() }

    @Test
    fun `the same sequence through the BATCHED recorder lands the same rows and the same replay, 1 to N`() =
        runBlocking<Unit> {
            // #266 B.2/B.3: the production path — both writers over the real stores — must leave
            // exactly what the direct path leaves: the row RUNNING → SUCCESS, events 1..5 in the
            // durable record and in the replay, correlation id on every payload.
            val rows = BatchingWriter("execution_events", BatchingConfig(), ExecutionEventRowSink(events))
            val replay = BatchingWriter("replay_log", BatchingConfig(), ReplayLogSink(eventLog))
            // Non-vacuity: the direct path leaves the same rows, so the writers must be SEEN committing
            // them — an emitter that ignored its recorder stayed green here before (#266 F18).
            val rowsCommitted = AtomicInteger()
            val replayCommitted = AtomicInteger()
            rows.hooks = committedCounter(rowsCommitted)
            replay.hooks = committedCounter(replayCommitted)
            val direct = Executors.newSingleThreadExecutor()
            try {
                val executionId = UUID.randomUUID()
                val correlationId = UUID.randomUUID()
                val emitter =
                    WebEventEmitter(
                        context = ExecutionContext(pipelineId, 1, userId, correlationId, ExecutionTrigger.REST, "{}", DEFAULT_WORKSPACE_ID),
                        stream = null,
                        streams = mockkRegistry(),
                        eventLog = eventLog,
                        eventRepository = events,
                        executionRepository = executions,
                        persistenceDispatcher = Dispatchers.Default,
                        eventRecorder = BatchedEventRecorder(rows, replay, eventLog, direct),
                    )
                val started = Instant.parse("2026-08-05T14:30:00Z")
                val stats = NodeStats("n1", NodeStatus.SUCCESS, started, started.plusMillis(900), 900, 10, 100)
                emitter.emit(ExecutionStarted(executionId, pipelineId, 1, emptyMap(), startedAt = started))
                emitter.emit(NodeStarted(executionId, "n1", started))
                emitter.emit(NodeCompleted(executionId, "n1", stats))
                emitter.emit(PipelineCompleted(executionId, pipelineId, 1, started, started.plusMillis(900), 900, listOf(stats)))
                emitter.emit(DataReady(executionId, pipelineId, emptyList(), emptyList(), 0, false, "http://x/result", started, 300))

                // Awaited: every emit returned only after its row and its replay entry were written.
                executions.findById(DEFAULT_WORKSPACE_ID, executionId).shouldNotBeNull().status shouldBe ExecutionStatus.SUCCESS
                val stored = events.findByExecution(executionId)
                stored.map { it.eventId } shouldBe listOf(1, 2, 3, 4, 5)
                stored.forEach {
                    co.datapipelines.executor.ExecutorJson.mapper
                        .readTree(it.payloadJson)
                        .get("correlation_id")
                        .asText() shouldBe correlationId.toString()
                }
                eventLog.replay(executionId).shouldNotBeNull().map { it.eventId } shouldBe listOf(1, 2, 3, 4, 5)
                withClue("every event went through the writers, not around them") {
                    rowsCommitted.get() shouldBe 5
                    replayCommitted.get() shouldBe 5
                }
            } finally {
                rows.close()
                replay.close()
                direct.shutdownNow()
            }
        }

    private fun committedCounter(count: AtomicInteger) =
        object : BatchingHooks {
            override fun onCommitted(lagNanos: Long) {
                count.incrementAndGet()
            }
        }

    @Test
    fun `a batched replay-log append is one round trip - one RPUSH and one EXPIRE per execution, order kept, TTL still one hour`() {
        // #266 B.2: N events of two executions in ONE script call. Counted on the server itself
        // (INFO commandstats, which counts the commands a script runs): two RPUSH, two PEXPIRE, one
        // EVALSHA/EVAL — not one pair per event, which is what append() costs. Red if appendAll
        // loops append().
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val entries =
            (1..BATCH_EVENTS).flatMap { id ->
                listOf(
                    eventLog.entry(first, LoggedSseEvent(id, "node_started", mapOf("n" to id))),
                    eventLog.entry(second, LoggedSseEvent(id, "node_started", mapOf("n" to id))),
                )
            }
        val before = commandCalls()

        eventLog.appendAll(entries)

        val after = commandCalls()
        // The data commands only: reading INFO opens its own connection (hello, client|setinfo, info).
        // A Duration expiry reaches Redis as PEXPIRE — same one-hour TTL, asserted below.
        val delta =
            after
                .filterKeys { it in setOf("rpush", "expire", "pexpire", "multi", "exec", "eval", "evalsha") }
                .mapValues { (command, calls) -> calls - before.getOrDefault(command, 0) }
                .filterValues { it > 0 }
        delta.filterKeys { it != "eval" && it != "evalsha" } shouldBe mapOf("rpush" to 2, "pexpire" to 2)
        // One script call: EVALSHA — or EVAL, the first time the script meets this server.
        (delta.getOrDefault("evalsha", 0) + delta.getOrDefault("eval", 0) in 1..2) shouldBe true
        eventLog.replay(first).shouldNotBeNull().map { it.eventId } shouldBe (1..BATCH_EVENTS).toList()
        eventLog.replay(second).shouldNotBeNull().map { it.eventId } shouldBe (1..BATCH_EVENTS).toList()
        val ttl = redis.getExpire("dp:events:$first").shouldNotBeNull()
        (ttl in (ONE_HOUR_SECONDS - TTL_SLACK_SECONDS)..ONE_HOUR_SECONDS) shouldBe true
    }

    @Test
    fun `a batched replay-log append of 9000 entries for one execution commits whole - the script chunks its RPUSH`() {
        // #266b (the pass's observation 4): Lua refuses to unpack more than ~7,990 values ("too many
        // results to unpack"), and the script unpacked one execution's whole batch into ONE RPUSH. Red
        // before the chunking: the call threw and nothing landed. Now at most APPEND_CHUNK values per
        // RPUSH, all inside the one script — atomic still, one TTL still.
        val executionId = UUID.randomUUID()
        val before = commandCalls()

        eventLog.appendAll((1..HUGE_BATCH).map { eventLog.entry(executionId, LoggedSseEvent(it, "node_started", emptyMap())) })

        val after = commandCalls()
        eventLog.replay(executionId).shouldNotBeNull().map { it.eventId } shouldBe (1..HUGE_BATCH).toList()
        val delta = after.mapValues { (k, v) -> v - (before[k] ?: 0) }.filterValues { it > 0 }
        withClue("server-side command counts for one 9,000-entry append: $delta") {
            delta["rpush"] shouldBe HUGE_BATCH / APPEND_CHUNK
            delta["pexpire"] shouldBe 1
            (delta.getOrDefault("evalsha", 0) + delta.getOrDefault("eval", 0) in 1..2) shouldBe true
        }
    }

    @Test
    fun `a batched replay-log append opens no connection - it rides the shared one`() {
        // Measured (#266 C run 1): a pipelined MULTI/EXEC through Spring Data Redis takes a DEDICATED
        // Lettuce connection — a new TCP connection and handshake per batch, ~7 ms, which made the
        // batched replay log 15x slower than the per-event append it replaced. Counted on the server:
        // twenty batches must not grow total_connections_received beyond the one this read opens.
        val executionId = UUID.randomUUID()
        val before = connectionsReceived()
        repeat(BATCHES) { n -> eventLog.appendAll(listOf(eventLog.entry(executionId, LoggedSseEvent(n + 1, "node_started", emptyMap())))) }
        val after = connectionsReceived()
        io.kotest.assertions.withClue("connections the server received across $BATCHES batches: ${after - before}") {
            (after - before <= 1) shouldBe true
        }
        eventLog.replay(executionId).shouldNotBeNull().size shouldBe BATCHES
    }

    private fun connectionsReceived(): Long =
        redis.connectionFactory
            .shouldNotBeNull()
            .connection
            .use { connection ->
                connection
                    .serverCommands()
                    .info("stats")
                    .shouldNotBeNull()
                    .getProperty("total_connections_received")
                    .toLong()
            }

    @Test
    fun `the replay serves each event id once - a re-sent batch never duplicates what clients see`() {
        // The replay log's retry dedup: the Redis list is at-least-once (a batch whose EXEC reply
        // was lost is re-sent by the writer), so the replay keeps the first entry per event id.
        val executionId = UUID.randomUUID()
        val batch = (1..3).map { eventLog.entry(executionId, LoggedSseEvent(it, "node_started", mapOf("n" to it))) }
        eventLog.appendAll(batch)
        eventLog.appendAll(batch.subList(1, 3))

        eventLog.replay(executionId).shouldNotBeNull().map { it.eventId } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `a batched replay-log append against a stopped Redis throws - the writer counts it, the caller is told`() {
        val disposable = TestRedis.disposable()
        try {
            val log = SseEventLog(disposable.template, co.datapipelines.executor.ExecutorJson.mapper)
            disposable.stopServer()
            val entry = log.entry(UUID.randomUUID(), LoggedSseEvent(1, "node_started", emptyMap()))
            runCatching { log.appendAll(listOf(entry)) }.isFailure shouldBe true
        } finally {
            disposable.close()
        }
    }

    /** `INFO commandstats` → command → calls, read off the server that did the work. */
    private fun commandCalls(): Map<String, Int> =
        redis.connectionFactory
            .shouldNotBeNull()
            .connection
            .use { connection ->
                connection
                    .serverCommands()
                    .info("commandstats")
                    .shouldNotBeNull()
                    .entries
                    .associate { (k, v) ->
                        k.toString().removePrefix("cmdstat_") to Regex("calls=(\\d+)").find(v.toString())!!.groupValues[1].toInt()
                    }
            }

    @Test
    fun `the rate limiter holds its counts in Redis`() {
        // Real Redis, pinned clock: the fixed window must not roll over mid-test.
        //
        // BOTH limits are 2, and that is the fix for the flake this test carried into five of
        // seven gates (083 §A). The pinned clock pins the KEY's bucket, not Redis's own TTL
        // clock: the per-second key is created with `EXPIRE 1`, so with only the second window
        // binding, the three calls below had to complete inside one real second. Under a loaded
        // full gate they did not, the key expired, `INCR` recreated it at 1, and the third
        // request was allowed — green in isolation every single time. With the MINUTE window
        // also at 2 the refusal is carried by a key whose TTL is 60 s, so any pause short of a
        // minute is harmless, and whichever window binds reports the same limit of 2.
        val pinned = Instant.ofEpochSecond(1_700_000_000)
        val limiter = RedisRateLimiter(redis, RateLimitProperties(requestsPerSecond = 2, requestsPerMinute = 2)) { pinned }
        val user = UUID.randomUUID()

        limiter.consume(user).allowed shouldBe true
        limiter.consume(user).allowed shouldBe true
        val third = limiter.consume(user)
        third.allowed shouldBe false
        third.limit shouldBe 2L
        // A THROTTLE, not an outage. Without this the fail-closed refusal of the sibling test
        // below would satisfy every other assertion here — a Redis that had gone away would read
        // as a limiter working perfectly (083 §A).
        third.reason shouldBe null
        val retryAfter: Long = third.retryAfterSeconds
        (retryAfter > 0L) shouldBe true
    }

    @Test
    fun `a limiter whose Redis has stopped refuses with 429 rate_limit unavailable`() {
        // The sibling of the test above, and the only place the ruling is proved end to end
        // against a real (absent) Redis rather than a thrown mock. No timing: the container is
        // STOPPED, so the fault is a fact of the world, not a race.
        val disposable = TestRedis.disposable()
        try {
            val limiter = RedisRateLimiter(disposable.template, RateLimitProperties())
            val user = UUID.randomUUID()
            limiter.consume(user).allowed shouldBe true

            disposable.stopServer()

            val decision = limiter.consume(user)
            decision.allowed shouldBe false
            decision.unavailable shouldBe true

            // Through the real filter, because the CODE is the filter's half of the contract.
            val response = MockHttpServletResponse()
            val chain = mockk<jakarta.servlet.FilterChain>(relaxed = true)
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(principalFor(user), null, emptyList())
            try {
                RateLimitFilter(limiter, AuthErrorWriter(JsonMapper.builder().build()))
                    .doFilter(MockHttpServletRequest("GET", "/api/v1/pipelines"), response, chain)
            } finally {
                SecurityContextHolder.clearContext()
            }

            response.status shouldBe 429
            response.getHeader("Retry-After") shouldBe "1"
            response.contentAsString shouldContain "\"code\":\"rate_limit.unavailable\""
            verify(exactly = 0) { chain.doFilter(any(), any()) }
        } finally {
            disposable.close()
        }
    }

    /** The authenticated principal the limiter filter meters against. */
    private fun principalFor(user: UUID) =
        co.datapipelines.auth.AuthenticatedPrincipal(
            user,
            "a@b.c",
            "A",
            co.datapipelines.auth.AuthMethod.API_KEY,
            "dpk_x",
            workspace =
                co.datapipelines.auth
                    .WorkspaceContext(DEFAULT_WORKSPACE_ID, "default"),
        )

    @Test
    fun `the cursor reads a real stored result through keyFor`() {
        val store: ResultStore = RedisResultStore(redis, ResultConfig(pageSizeRows = 2, pageMaxRows = 10))
        val executionId = UUID.randomUUID()
        runBlocking { store.materialize(executionId, h2Rows(1, 5), Dialect.H2, 300) }

        // The execution row the cursor's ownership/status gate reads.
        executions.create(
            co.datapipelines.executor.ExecutionRecord(
                executionId = executionId,
                pipelineId = pipelineId,
                pipelineVersion = 1,
                status = ExecutionStatus.RUNNING,
                parametersJson = "{}",
                executedBy = userId,
                triggeredVia = ExecutionTrigger.REST,
            ),
        )
        executions.complete(executionId, ExecutionStatus.SUCCESS, Instant.now(), 1L, "[]", null, null, 5L, 100L)

        val cursor = ResultCursor(executions, store, ResultConfig(pageSizeRows = 2, pageMaxRows = 10), WebMetrics(SimpleMeterRegistry()))
        val principal =
            co.datapipelines.auth.AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                co.datapipelines.auth.AuthMethod.API_KEY,
                "dpk_x",
                workspace =
                    co.datapipelines.auth
                        .WorkspaceContext(DEFAULT_WORKSPACE_ID, "default"),
            )

        val page = cursor.jsonPage(cursor.readable(executionId, principal), 0L, null)
        page["row_count"] shouldBe 2
        page["total_rows"] shouldBe 5L
        page["has_more"] shouldBe true

        val out = java.io.ByteArrayOutputStream()
        cursor.writeCsv(cursor.readable(executionId, principal), out)
        val lines = out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        lines.size shouldBe 6 // header + 5 rows
        lines[0] shouldBe "n,label,big"
    }

    /** A real forward-only H2 cursor — the fixture shape dag's result-store test uses. */
    private fun h2Rows(
        from: Int,
        to: Int,
    ): ResultSet =
        DriverManager
            .getConnection("jdbc:h2:mem:web_it_${UUID.randomUUID().toString().replace("-", "")}")
            .createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
            .executeQuery(
                """SELECT CAST("X" AS INT) AS "n", CONCAT('r', "X") AS "label", "X" AS "big"
                   FROM SYSTEM_RANGE($from, $to) ORDER BY "X"""",
            )

    private fun mockkRegistry(): co.datapipelines.web.sse.ExecutionStreamRegistry =
        co.datapipelines.web.sse.ExecutionStreamRegistry(
            co.datapipelines.web.config
                .SseProperties(),
            co.datapipelines.executor.ExecutionCancellationService(
                co.datapipelines.executor.InMemoryCancellationRegistry(),
                co.datapipelines.executor.RedisCancellationFlags(redis),
                co.datapipelines.executor.ExecutorConfig(),
            ),
            co.datapipelines.executor.ExecutorJson.mapper,
        )

    private companion object {
        const val BATCH_EVENTS = 50
        const val BATCHES = 20
        const val ONE_HOUR_SECONDS = 3_600L
        const val TTL_SLACK_SECONDS = 60L
        const val ROW_CONTENT = "row-content-266b-must-not-be-logged"

        /** Past Lua's unpack limit (~7,990) for one execution in one append. */
        const val HUGE_BATCH = 9_000

        /** SseEventLog's RPUSH chunk — the most values the script unpacks at once. */
        const val APPEND_CHUNK = 1_000

        /** The V4-seeded `default` workspace the pipeline fixture and every repository read are scoped to. */
        val DEFAULT_WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
    }
}
