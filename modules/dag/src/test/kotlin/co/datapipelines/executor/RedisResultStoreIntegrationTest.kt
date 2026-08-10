package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.core.StringRedisTemplate
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * [RedisResultStore] against a real Redis and a real JDBC cursor (dag-executor.md §6.4.2, D9).
 *
 * The in-memory stand-in the unit suites use cannot prove any of this: the key layout, the fixed
 * expiry, `LRANGE` paging stability, or that the size cap aborts a drain **mid-stream** rather than
 * after buffering.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisResultStoreIntegrationTest {
    private val redis: StringRedisTemplate = RedisSupport.template()
    private val h2: Connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:rs_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            "sa",
            "",
        )

    @BeforeEach
    fun setUp() {
        RedisSupport.flush(redis)
    }

    @Test
    fun `materialize then describe round-trips the schema, the rows and the totals`() =
        runBlocking<Unit> {
            val executionId = UUID.randomUUID()
            val store = store()

            val stored = store.materialize(executionId, rows(1, 3), Dialect.H2, TTL_SECONDS)

            stored.key shouldBe "dp:result:$executionId"
            stored.totalRows shouldBe 3
            (stored.bytes > 0).shouldBeTrue()

            val view = store.describe(stored.key).shouldNotBeNull()
            view.executionId shouldBe executionId
            view.totalRows shouldBe 3
            view.schema.map { it.name } shouldBe listOf("n", "label", "big")
            view.schema.map { it.type } shouldBe
                listOf(LogicalType.INTEGER, LogicalType.STRING, LogicalType.BIGINTEGER)
            // type-system §3.5: INTEGER is a JSON number; BIGINTEGER is a string, so a JS client
            // cannot silently lose precision on a value past 2^53.
            view.firstPage shouldBe
                listOf(listOf(1, "r1", "1"), listOf(2, "r2", "2"), listOf(3, "r3", "3"))
            view.hasMore shouldBe false
        }

    @Test
    fun `paging is stable because the result is fully materialized first`() =
        runBlocking<Unit> {
            val store = store()
            val stored = store.materialize(UUID.randomUUID(), rows(1, 10), Dialect.H2, TTL_SECONDS)

            val first = store.page(stored.key, offset = 0, limit = 4).shouldNotBeNull()
            val second = store.page(stored.key, offset = 4, limit = 4).shouldNotBeNull()
            val tail = store.page(stored.key, offset = 8, limit = 4).shouldNotBeNull()

            first.rows.map { it[0] } shouldBe listOf(1, 2, 3, 4)
            second.rows.map { it[0] } shouldBe listOf(5, 6, 7, 8)
            tail.rows.map { it[0] } shouldBe listOf(9, 10)
            first.hasMore.shouldBeTrue()
            tail.hasMore shouldBe false
            tail.totalRows shouldBe 10
        }

    @Test
    fun `the inline first page is bounded by page-size-rows`() =
        runBlocking<Unit> {
            val store = store(ResultConfig(pageSizeRows = 3))
            val stored = store.materialize(UUID.randomUUID(), rows(1, 10), Dialect.H2, TTL_SECONDS)

            val view = store.describe(stored.key).shouldNotBeNull()
            view.firstPage.size shouldBe 3
            view.totalRows shouldBe 10
            view.hasMore.shouldBeTrue()
        }

    @Test
    fun `crossing max-size-bytes aborts the drain and discards the partial result`() =
        runBlocking<Unit> {
            val executionId = UUID.randomUUID()
            val store = store(ResultConfig(maxSizeBytes = 20))

            shouldThrow<DatapipelinesException> {
                store.materialize(executionId, rows(1, 5_000), Dialect.H2, TTL_SECONDS)
            }.code shouldBe PipelineErrorCodes.Result.TOO_LARGE

            // §6.4.2 step 3: the partial result is discarded, so nothing is readable afterwards —
            // and neither key is left behind to occupy memory until its TTL.
            store.describe("dp:result:$executionId").shouldBeNull()
            redis.hasKey("dp:result:$executionId:rows") shouldBe false
            redis.hasKey("dp:result:$executionId:meta") shouldBe false
        }

    @Test
    fun `both keys carry the fixed expiry so nothing is stored forever`() =
        runBlocking<Unit> {
            val store = store()
            val stored = store.materialize(UUID.randomUUID(), rows(1, 1_500), Dialect.H2, TTL_SECONDS)

            val rowsTtl = redis.getExpire("${stored.key}:rows")
            val metaTtl = redis.getExpire("${stored.key}:meta")

            (rowsTtl in 1..TTL_SECONDS).shouldBeTrue()
            (metaTtl in 1..TTL_SECONDS).shouldBeTrue()
        }

    @Test
    fun `an unknown or expired key reads as absent rather than as an empty result`() {
        val store = store()

        store.describe("dp:result:${UUID.randomUUID()}").shouldBeNull()
        store.page("dp:result:${UUID.randomUUID()}", 0, 10).shouldBeNull()
    }

    @Test
    fun `discard removes both keys`() =
        runBlocking<Unit> {
            val store = store()
            val stored = store.materialize(UUID.randomUUID(), rows(1, 2), Dialect.H2, TTL_SECONDS)

            store.discard(stored.key)

            store.describe(stored.key).shouldBeNull()
            redis.hasKey("${stored.key}:rows") shouldBe false
        }

    @Test
    fun `a second materialize for the same execution replaces rather than appends`() =
        runBlocking<Unit> {
            // A retry that re-ran the caller node must not leave the first attempt's rows in the
            // list — LRANGE would then serve a result that is two runs concatenated.
            val executionId = UUID.randomUUID()
            val store = store()

            store.materialize(executionId, rows(1, 5), Dialect.H2, TTL_SECONDS)
            val second = store.materialize(executionId, rows(1, 2), Dialect.H2, TTL_SECONDS)

            second.totalRows shouldBe 2
            store
                .describe(second.key)
                .shouldNotBeNull()
                .firstPage.size shouldBe 2
        }

    // ------------------------------------------------------------------ helpers

    private fun store(config: ResultConfig = ResultConfig()) = RedisResultStore(redis, config)

    /**
     * A real forward-only H2 cursor over `[from, to]`, with one column of each numeric shape.
     *
     * `n` is cast to `INT` deliberately: `SYSTEM_RANGE`'s own `X` is a `BIGINT`, which maps to
     * canonical `BIGINTEGER` and therefore wire-encodes as a **string** (type-system §3.5). Both
     * are worth having — `n` proves a JSON number survives the round trip, `big` proves the BIG\*
     * string rule is applied rather than quietly lost to Jackson's default numeric binding.
     */
    private fun rows(
        from: Int,
        to: Int,
    ): ResultSet =
        h2
            .createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
            .executeQuery(
                """SELECT CAST("X" AS INT) AS n, CONCAT('r', "X") AS label, "X" AS big
                   FROM SYSTEM_RANGE($from, $to) ORDER BY "X"""",
            )

    private companion object {
        const val TTL_SECONDS = 300L
    }
}
