package co.datapipelines.auth

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.persistence.BatchingConfig
import co.datapipelines.persistence.BatchingHooks
import co.datapipelines.persistence.BatchingWriter
import co.datapipelines.persistence.FallbackReason
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * The audit sink over its batching writer (#266 B.5; auth.md §10), against the real `audit_log`.
 *
 * The contract that did NOT change, and why it must not: audit rows are authorization inputs — an
 * MCP key's read of its own execution is decided by its `mcp.execution.launched` / `mcp.tool.called`
 * rows, an endpoint key's by its serve row — so [AuditLogger.log] returns only after the row is
 * committed. What changed is who commits it: outside a transaction, the writer, sharing one commit
 * among concurrent callers; INSIDE a caller's transaction, the caller's own connection, exactly as
 * before — the row must vanish if the business write it describes rolls back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditLoggerBatchingIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var writer: BatchingWriter<AuditRow>
    private lateinit var logger: AuditLogger
    private val committed = AtomicInteger()
    private val fallbacks = Collections.synchronizedList(mutableListOf<FallbackReason>())
    private val dataSource by lazy { SharedPostgres.dataSource() }
    private lateinit var userId: UUID

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(dataSource)
    }

    @BeforeEach
    fun setUp() {
        committed.set(0)
        fallbacks.clear()
        writer =
            BatchingWriter(
                "audit",
                BatchingConfig(writers = 4),
                AuditRowSink(jdbc),
                object : BatchingHooks {
                    override fun onCommitted(lagNanos: Long) {
                        committed.incrementAndGet()
                    }

                    override fun onFallback(reason: FallbackReason) {
                        fallbacks += reason
                    }
                },
            )
        // Through the production wiring with the audit writer switched ON explicitly (#266b: it ships
        // off) — every batched case below is a proof of the batched path, never of the default.
        logger = AuthConfiguration().auditLogger(jdbc, ObjectMapper(), BATCHED_AUDIT, writer)
        userId =
            UUID.randomUUID().also { id ->
                jdbc.update(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'A', 'google', :sub)",
                    mapOf("id" to id, "email" to "audit-$id@example.com", "sub" to "sub-$id"),
                )
            }
    }

    @AfterEach
    fun tearDown() {
        writer.close()
    }

    @Test
    fun `a batched audit write is committed before log returns - every caller reads its own row at once`() {
        // The authorization property the ruling keeps: 32 callers × 20 events, each reading its OWN
        // row the instant log() returns. Red if log() acknowledged from memory (fire-and-forget).
        val callers = Executors.newFixedThreadPool(CALLERS)
        val misses = AtomicInteger()
        val done = CountDownLatch(CALLERS)
        repeat(CALLERS) { caller ->
            callers.execute {
                repeat(PER_CALLER) { n ->
                    val marker = UUID.randomUUID().toString()
                    logger.log("audit.batching.probe", userId = userId, keyId = "k_$caller", details = mapOf("marker" to marker, "n" to n))
                    if (rowsWith(marker) != 1) misses.incrementAndGet()
                }
                done.countDown()
            }
        }
        done.await(SIXTY_SECONDS, TimeUnit.SECONDS) shouldBe true
        callers.shutdown()
        misses.get() shouldBe 0
        committed.get() shouldBe CALLERS * PER_CALLER
    }

    @Test
    fun `with the default configuration an audit row takes the direct path - the writer never sees it`() {
        // #266b, the ruling: `datapipelines.persistence.audit.enabled` ships false, so the application's
        // wiring hands AuditLogger no writer and every row is the pre-266 INSERT. Red with the default
        // flipped (the row is batched: the writer's commit hook fires, the DEBUG line says batched).
        val lines = ListAppender<ILoggingEvent>().apply { start() }
        val auditLog = LoggerFactory.getLogger(AuditLogger::class.java) as Logger
        val previous = auditLog.level
        auditLog.level = ch.qos.logback.classic.Level.DEBUG
        auditLog.addAppender(lines)
        val markers = mutableListOf<String>()
        try {
            listOf(PersistenceProperties(), PersistenceProperties(enabled = false, audit = PersistenceProperties.Audit(enabled = true)))
                .forEach { config ->
                    val marker = UUID.randomUUID().toString().also { markers += it }
                    AuthConfiguration()
                        .auditLogger(jdbc, ObjectMapper(), config, writer)
                        .log("audit.default.path", userId = userId, details = mapOf("marker" to marker))
                }
        } finally {
            auditLog.detachAppender(lines)
            auditLog.level = previous
        }
        markers.forEach { rowsWith(it) shouldBe 1 }
        withClue("the audit writer committed nothing") { committed.get() shouldBe 0 }
        lines.list.map { it.formattedMessage }.filter { it.contains("audit_event=audit.default.path") } shouldBe
            List(2) { "event=audit.write audit_event=audit.default.path path=direct" }
    }

    @Test
    fun `inside a caller's transaction the row joins it and rolls back with it`() {
        // Red if the batched path ignored the caller's transaction: the writer would commit the row
        // on its own connection and it would survive the rollback of the action it describes.
        val marker = UUID.randomUUID().toString()
        TransactionTemplate(DataSourceTransactionManager(dataSource)).executeWithoutResult { status ->
            logger.log("audit.batching.in_tx", userId = userId, details = mapOf("marker" to marker))
            // Visible inside the transaction: it was written on the caller's own connection (the
            // count below reads through the same bound connection).
            rowsWith(marker) shouldBe 1
            status.setRollbackOnly()
        }
        rowsWith(marker) shouldBe 0
        committed.get() shouldBe 0
    }

    @Test
    fun `inside a caller's transaction that commits, the row commits with it`() {
        val marker = UUID.randomUUID().toString()
        TransactionTemplate(DataSourceTransactionManager(dataSource)).executeWithoutResult {
            logger.log("audit.batching.in_tx_commit", userId = userId, details = mapOf("marker" to marker))
        }
        rowsWith(marker) shouldBe 1
        committed.get() shouldBe 0
    }

    @Test
    fun `one key's events commit in the order they were logged`() {
        repeat(ORDERED) { n -> logger.log("audit.batching.order", keyId = "k_order", details = mapOf("n" to n)) }
        jdbc.queryForList(
            "SELECT (details_json ->> 'n')::int FROM audit_log WHERE event = 'audit.batching.order' AND key_id = 'k_order' ORDER BY id",
            emptyMap<String, Any>(),
            Int::class.java,
        ) shouldBe (0 until ORDERED).toList()
    }

    @Test
    fun `a failed audit write is logged and swallowed, never thrown into the request`() {
        // A row the database refuses (a user id with no user row — the foreign key) must not break
        // the request path; today's contract, kept on the batched path.
        val marker = UUID.randomUUID().toString()
        logger.log("audit.batching.refused", userId = UUID.randomUUID(), details = mapOf("marker" to marker))
        rowsWith(marker) shouldBe 0
    }

    @Test
    fun `without a writer every write is the direct INSERT - the pre-266 path`() {
        val direct = AuditLogger(jdbc, ObjectMapper())
        val marker = UUID.randomUUID().toString()
        direct.log("audit.batching.direct", userId = userId, details = mapOf("marker" to marker))
        rowsWith(marker) shouldBe 1
        committed.get() shouldBe 0
    }

    @Test
    fun `after the writer stops, writes still land - directly`() {
        writer.close()
        val marker = UUID.randomUUID().toString()
        logger.log("audit.batching.stopped", userId = userId, details = mapOf("marker" to marker))
        rowsWith(marker) shouldBe 1
        fallbacks shouldBe listOf(FallbackReason.STOPPED)
    }

    @Test
    fun `the sink's batch is one transaction - a refused row commits none of its batch`() {
        // PgJDBC commits an autocommit batch in ~256-statement chunks; the sink must not.
        val marker = UUID.randomUUID().toString()
        val rows =
            (1 until BATCH).map { AuditRow("audit.batching.tx", userId, null, null, null, """{"marker":"$marker"}""") } +
                AuditRow("audit.batching.tx", UUID.randomUUID(), null, null, null, """{"marker":"$marker"}""")
        shouldThrow<DataIntegrityViolationException> { AuditRowSink(jdbc).write(rows) }
        rowsWith(marker) shouldBe 0
    }

    @Test
    fun `a refused row's WARN names the failure's class and SQLState, never the row - direct, batched and fallback`() {
        // #266b (the security pass's observation 3): Postgres quotes a refused row in its message —
        // a JSONB parse refusal's CONTEXT carries the JSON up to the bad token — and `details` are
        // redaction-bound. A NUL in a detail value is such a row: Jackson escapes it, JSONB refuses it.
        val details = mapOf("v" to "$ROW_CONTENT\u0000")
        withClue("non-vacuity: the store's own message must carry the row, or this case proves nothing") {
            val refusal = shouldThrow<DataAccessException> { AuditRowSink(jdbc).writeOne(row("audit.leak.probe", details)) }
            generateSequence<Throwable>(refusal) { it.cause }.any { it.message.orEmpty().contains(ROW_CONTENT) } shouldBe true
        }
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val loggers = listOf(AuditLogger::class.java, BatchingWriter::class.java).map { LoggerFactory.getLogger(it) as Logger }
        loggers.forEach { it.addAppender(appender) }
        try {
            // The DEFAULT path (#266b: the audit writer ships switched off) — the pre-266 INSERT.
            AuditLogger(jdbc, ObjectMapper()).log("audit.leak.direct", userId = userId, details = details)
            // The batched path — a batch of one the store refuses: write_failed.
            logger.log("audit.leak.batched", userId = userId, details = details)
            // The writer stopped — the caller's own direct write: direct_write_failed.
            writer.close()
            logger.log("audit.leak.fallback", userId = userId, details = details)
        } finally {
            loggers.forEach { it.detachAppender(appender) }
        }
        val rendered = appender.list.map { it.formattedMessage + " " + it.throwableProxy.render() }

        // 22P05: untranslatable_character — the jsonb refusal, named by its state, never its text.
        fun linesNamingTheState(prefix: String) = rendered.count { it.startsWith(prefix) && it.contains("sql_state=22P05") }
        withClue("every WARN, rendered whole: $rendered") {
            rendered.none { it.contains(ROW_CONTENT) } shouldBe true
            linesNamingTheState("audit_log write failed event=audit.leak.direct") shouldBe 1
            linesNamingTheState("event=persistence.write_failed writer=audit") shouldBe 1
            linesNamingTheState("event=persistence.direct_write_failed writer=audit") shouldBe 1
        }
    }

    @Test
    fun `a failure that is not the store's reaches the caller on every path - batched, fallback and direct`() {
        // #266b (the pass's observation 6): the unbatched INSERT caught DataAccessException only, so
        // anything else — here a runtime exception out of the driver — failed the request. The batched
        // path swallowed every Exception into an outcome; the sink now claims the non-store ones and
        // the writer rethrows them on the caller's thread. Red while the writer ignored the claim.
        val broken = NamedParameterJdbcTemplate(statementsThrow { IllegalStateException("driver bug") })
        val brokenWriter = BatchingWriter("audit", BatchingConfig(writers = 1), AuditRowSink(broken))
        try {
            val batched = AuditLogger(broken, ObjectMapper(), brokenWriter)
            shouldThrow<IllegalStateException> { batched.log("audit.nonstore.batched", userId = userId) }.message shouldBe "driver bug"
            brokenWriter.close()
            shouldThrow<IllegalStateException> { batched.log("audit.nonstore.fallback", userId = userId) }.message shouldBe "driver bug"
            shouldThrow<IllegalStateException> {
                AuditLogger(broken, ObjectMapper()).log("audit.nonstore.direct", userId = userId)
            }.message shouldBe "driver bug"
        } finally {
            brokenWriter.close()
        }
    }

    @Test
    fun `a store that cannot be reached is a failed row, never a failed request - batched, fallback and direct`() {
        // The other half of the classification, and why it is not "DataAccessException only": the
        // batch runs in its own transaction, and a database that cannot be reached surfaces there as
        // CannotCreateTransactionException — a TransactionException, not a DataAccessException. The
        // unbatched INSERT met the same outage as CannotGetJdbcConnectionException and swallowed it;
        // the batched path must too (red with TransactionException dropped from the sink's store set).
        val unreachable = NamedParameterJdbcTemplate(connectionsRefused())
        val deadWriter = BatchingWriter("audit", BatchingConfig(writers = 1), AuditRowSink(unreachable))
        try {
            val batched = AuditLogger(unreachable, ObjectMapper(), deadWriter)
            batched.log("audit.outage.batched", userId = userId)
            deadWriter.close()
            batched.log("audit.outage.fallback", userId = userId)
            AuditLogger(unreachable, ObjectMapper()).log("audit.outage.direct", userId = userId)
        } finally {
            deadWriter.close()
        }
        AuditRowSink(unreachable).run {
            propagates(org.springframework.transaction.CannotCreateTransactionException("begin")) shouldBe false
            propagates(org.springframework.transaction.TransactionSystemException("commit")) shouldBe false
            propagates(org.springframework.jdbc.CannotGetJdbcConnectionException("connect")) shouldBe false
            propagates(IllegalStateException("bug")) shouldBe true
            propagates(AssertionError("error")) shouldBe true
        }
    }

    /** The shared database, except that every statement the connection prepares throws [failure]. */
    private fun statementsThrow(failure: () -> RuntimeException): DataSource =
        object : DataSource by dataSource {
            override fun getConnection(): Connection = throwingStatements(dataSource.connection, failure)

            override fun getConnection(
                username: String?,
                password: String?,
            ): Connection = throwingStatements(dataSource.connection, failure) // credentials: the shared pool's own
        }

    private fun throwingStatements(
        real: Connection,
        failure: () -> RuntimeException,
    ): Connection =
        Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement") throw failure()
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection

    /** A database nobody can reach: every connection attempt is refused (SQLState 08001). */
    private fun connectionsRefused(): DataSource =
        object : DataSource by dataSource {
            override fun getConnection(): Connection = throw SQLException("connection refused", "08001")

            override fun getConnection(
                username: String?,
                password: String?,
            ): Connection = throw SQLException("connection refused", "08001")
        }

    private fun row(
        event: String,
        details: Map<String, Any?>,
    ) = AuditRow(event, userId, null, null, null, ObjectMapper().writeValueAsString(details))

    private fun IThrowableProxy?.render(): String =
        if (this == null) "" else "$className: $message | " + cause.render() + suppressed.joinToString(" ") { it.render() }

    private fun rowsWith(marker: String): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE details_json ->> 'marker' = :marker",
            mapOf("marker" to marker),
            Int::class.java,
        ) ?: 0

    private companion object {
        const val CALLERS = 32
        const val PER_CALLER = 20
        const val ORDERED = 100
        const val BATCH = 500
        const val SIXTY_SECONDS = 60L
        const val ROW_CONTENT = "row-content-266b-must-not-be-logged"

        /** The audit writer switched on — the batched path's configuration, never the default. */
        val BATCHED_AUDIT = PersistenceProperties(audit = PersistenceProperties.Audit(enabled = true))
    }
}
