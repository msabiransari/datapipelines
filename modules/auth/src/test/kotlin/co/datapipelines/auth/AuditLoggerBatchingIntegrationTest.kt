package co.datapipelines.auth

import co.datapipelines.persistence.BatchingConfig
import co.datapipelines.persistence.BatchingHooks
import co.datapipelines.persistence.BatchingWriter
import co.datapipelines.persistence.FallbackReason
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        logger = AuditLogger(jdbc, ObjectMapper(), writer)
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
    }
}
