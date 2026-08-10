package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.JsonEncoder
import co.datapipelines.typesystem.TypeMappingWarning
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * The Redis implementation of [ResultStore] (dag-executor.md §6.4.2, D9).
 *
 * ## Layout
 *
 * Two keys per execution, both carrying the same fixed expiry:
 *  - `dp:result:{execution_id}:rows` — a Redis LIST, one JSON array per row, appended in batches
 *    as the cursor is drained. Paging is `LRANGE offset..offset+limit-1`, which is why row order
 *    is stable across pages (REST §7.1).
 *  - `dp:result:{execution_id}:meta` — the schema, totals, warnings and expiry.
 *
 * ## The size cap is checked during the drain
 *
 * §6.4.2 step 3: crossing `datapipelines.result.max-size-bytes` **aborts immediately** and
 * discards the partial result. Checking after the drain would require buffering the whole result
 * first — precisely the thing the cap exists to prevent.
 *
 * ## No fallback
 *
 * A Redis fault is `result.storage_unavailable` and the execution fails. There is deliberately
 * no fallback to inline delivery: a second delivery path is exactly the hole D9 closed.
 */
class RedisResultStore(
    private val redis: StringRedisTemplate,
    private val config: ResultConfig,
    /** D9's result-store instruments (F10); defaults to an in-memory registry for tests. */
    private val metrics: ExecutorMetrics = ExecutorMetrics.inMemory(),
) : ResultStore {
    override suspend fun materialize(
        executionId: UUID,
        resultSet: ResultSet,
        sourceDialect: Dialect,
        ttlSeconds: Long,
    ): StoredResult {
        val key = baseKey(executionId)
        val schema = ResultRowReader.schemaOf(resultSet.metaData, sourceDialect)
        val ttl = Duration.ofSeconds(ttlSeconds)
        discard(key)
        return try {
            drain(key, resultSet, schema, ttl)
                .also { writeMeta(key, executionId, schema, it, ttl) }
                .also { metrics.resultWritten(ExecutorMetrics.OUTCOME_STORED, it.bytes) }
        } catch (e: DataAccessException) {
            discard(key)
            metrics.resultWritten(ExecutorMetrics.OUTCOME_STORAGE_UNAVAILABLE, 0)
            throw storageUnavailable(executionId, e)
        } catch (e: DatapipelinesException) {
            // `result.too_large` — a DatapipelinesException, not a DataAccessException, so it was
            // already passing through untouched with its partial result discarded at the throw
            // site. Counted here and rethrown unchanged.
            metrics.resultWritten(ExecutorMetrics.OUTCOME_TOO_LARGE, 0)
            throw e
        }
    }

    /** `dp:result:{execution_id}` — the base key both this class's suffixed keys hang off. */
    override fun keyFor(executionId: UUID): String = baseKey(executionId)

    override fun describe(key: String): StoredResultView? {
        requireResultKey(key)
        val meta = readMeta(key) ?: return null
        return StoredResultView(
            key = key,
            executionId = meta.executionId,
            schema = meta.schema,
            firstPage = readRows(key, 0, config.pageSizeRows.toLong()),
            totalRows = meta.totalRows,
            bytes = meta.bytes,
            expiresAt = meta.expiresAt,
            warnings = meta.warnings,
        )
    }

    override fun page(
        key: String,
        offset: Long,
        limit: Int,
    ): ResultPage? {
        requireResultKey(key)
        val meta = readMeta(key) ?: return null
        val effectiveLimit = config.effectiveLimit(limit)
        // A negative offset is TAIL-relative in `LRANGE`, so -1 silently serves the last row while
        // `hasMore` is computed from the negative offset and comes out wrong too. Clamp, don't trust.
        val effectiveOffset = offset.coerceAtLeast(0)
        return ResultPage(
            executionId = meta.executionId,
            schema = meta.schema,
            rows = readRows(key, effectiveOffset, effectiveLimit.toLong()),
            offset = effectiveOffset,
            limit = effectiveLimit,
            totalRows = meta.totalRows,
            expiresAt = meta.expiresAt,
        )
    }

    override fun discard(key: String) {
        requireResultKey(key)
        redis.delete(listOf(rowsKey(key), metaKey(key)))
    }

    /**
     * Defence in depth on the one string that reaches Redis from outside this class (F5).
     *
     * `web` owns the ownership check — a caller may only read *their own* execution's result — and
     * that stays there. This is the shape check underneath it: a key is `dp:result:{uuid}` and
     * nothing else, so no caller-influenced string can be steered at another prefix in this
     * keyspace (`idem:*`, `dp:cancel:*`) whatever a future surface passes down.
     */
    private fun requireResultKey(key: String) {
        val suffix = key.removePrefix(KEY_PREFIX)
        require(suffix.length != key.length && runCatching { UUID.fromString(suffix) }.isSuccess) {
            "not a result key: expected '$KEY_PREFIX{uuid}'"
        }
    }

    /** UTF-8 byte length without allocating a second copy of the string (F4). */
    private fun utf8Length(value: String): Long {
        var length = 0L
        var index = 0
        while (index < value.length) {
            val code = value[index].code
            length +=
                when {
                    code < ONE_BYTE_CEILING -> {
                        1
                    }

                    code < TWO_BYTE_CEILING -> {
                        2
                    }

                    Character.isHighSurrogate(value[index]) && index + 1 < value.length &&
                        Character.isLowSurrogate(value[index + 1]) -> {
                        // A surrogate pair is one code point encoded in four bytes.
                        index++
                        SURROGATE_PAIR_BYTES
                    }

                    else -> {
                        BASIC_PLANE_BYTES
                    }
                }
            index++
        }
        return length
    }

    /**
     * Streams the cursor into the rows list, batching pushes and measuring encoded size as it
     * goes. The TTL is applied on the **first** batch as well as at the end, so an execution that
     * dies mid-drain leaves an expiring key rather than a permanent one.
     */
    private fun drain(
        key: String,
        resultSet: ResultSet,
        schema: ResultSchema,
        ttl: Duration,
    ): StoredResult {
        val batch = ArrayList<String>(PUSH_BATCH_ROWS)
        var rows = 0L
        var bytes = 0L
        var ttlApplied = false

        while (resultSet.next()) {
            val encoded = encodeRow(resultSet, schema.columns)
            // F4: a CHARACTER-length gate before anything is measured in bytes. `toByteArray`
            // allocates a second full copy of the row, so the old order materialised a single
            // oversized LOB twice on the heap purely to discover it was over the cap — which is the
            // one thing the cap exists to prevent. UTF-8 is ≥ 1 byte per char, so "these chars
            // already exceed the cap" implies "these bytes do too": bailing here is exact in the
            // direction that matters and never lets an oversized result through.
            if (bytes + encoded.length > config.maxSizeBytes) {
                discard(key)
                throw tooLarge(bytes + encoded.length)
            }
            bytes += utf8Length(encoded)
            if (bytes > config.maxSizeBytes) {
                discard(key)
                throw tooLarge(bytes)
            }
            batch += encoded
            rows++
            if (batch.size >= PUSH_BATCH_ROWS) {
                redis.opsForList().rightPushAll(rowsKey(key), batch)
                if (!ttlApplied) ttlApplied = redis.expire(rowsKey(key), ttl) == true
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) redis.opsForList().rightPushAll(rowsKey(key), batch)
        redis.expire(rowsKey(key), ttl)

        return StoredResult(key, rows, bytes, Instant.now().plus(ttl), schema.warnings)
    }

    /** One row as a JSON array, each value encoded by the type system's egress rules (§3.5). */
    private fun encodeRow(
        resultSet: ResultSet,
        columns: List<ColumnSchema>,
    ): String =
        ExecutorJson.write(
            columns.mapIndexed { index, column ->
                JsonEncoder.encode(ResultRowReader.readValue(resultSet, index + 1, column), column)
            },
        )

    private fun writeMeta(
        key: String,
        executionId: UUID,
        schema: ResultSchema,
        stored: StoredResult,
        ttl: Duration,
    ) {
        val meta =
            ResultMeta(
                executionId = executionId,
                schema = schema.columns,
                totalRows = stored.totalRows,
                bytes = stored.bytes,
                expiresAt = stored.expiresAt,
                warnings = schema.warnings,
            )
        redis.opsForValue().set(metaKey(key), ExecutorJson.write(meta), ttl)
    }

    /**
     * Reads the stored header, mapping a corrupt/foreign payload to the catalogued
     * `result.storage_unavailable` rather than letting a Jackson or `DateTimeParseException`
     * escape as a bare 500 with a stack trace the caller cannot act on.
     */
    private fun readMeta(key: String): ResultMeta? {
        val raw = redis.opsForValue().get(metaKey(key)) ?: return null
        return try {
            ExecutorJson.mapper.readValue<ResultMeta>(raw)
        } catch (e: JacksonException) {
            throw corruptMeta(key, e)
        } catch (e: DateTimeParseException) {
            throw corruptMeta(key, e)
        }
    }

    private fun corruptMeta(
        key: String,
        cause: Throwable,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Result.STORAGE_UNAVAILABLE,
        message = "The stored result header for '$key' could not be read; treat the result as unavailable.",
        details = mapOf("key" to key),
        cause = cause,
    )

    private fun readRows(
        key: String,
        offset: Long,
        limit: Long,
    ): List<List<Any?>> =
        redis
            .opsForList()
            .range(rowsKey(key), offset, offset + limit - 1)
            .orEmpty()
            .map { ExecutorJson.mapper.readValue<List<Any?>>(it) }

    private fun tooLarge(bytes: Long) =
        DatapipelinesException(
            code = PipelineErrorCodes.Result.TOO_LARGE,
            message =
                "The caller result exceeded the ${config.maxSizeBytes}-byte cap (reached $bytes bytes) " +
                    "and was discarded. Write large datasets back with output.target 'datasource'.",
            details = mapOf("max_size_bytes" to config.maxSizeBytes, "bytes" to bytes),
        )

    private fun storageUnavailable(
        executionId: UUID,
        cause: DataAccessException,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Result.STORAGE_UNAVAILABLE,
        message = "The result store rejected the caller result for execution $executionId: ${cause.message}",
        details = mapOf("execution_id" to executionId.toString()),
        cause = cause,
    )

    private fun baseKey(executionId: UUID) = "$KEY_PREFIX$executionId"

    private fun rowsKey(key: String) = "$key:rows"

    private fun metaKey(key: String) = "$key:meta"

    /** The stored header. Deserialized by the Kotlin module — no `@JsonCreator` needed. */
    internal data class ResultMeta(
        val executionId: UUID,
        val schema: List<ColumnSchema>,
        val totalRows: Long,
        val bytes: Long,
        val expiresAt: Instant,
        val warnings: List<TypeMappingWarning> = emptyList(),
    )

    private companion object {
        const val KEY_PREFIX = "dp:result:"

        /**
         * Rows per `RPUSH`. Large enough that a wide result is not one round-trip per row, small
         * enough that the in-flight batch is never the thing that exhausts heap — the drain's
         * whole point is constant memory (staging §6.1).
         */
        const val PUSH_BATCH_ROWS = 500

        /** UTF-8 code-unit boundaries — the encoding's own definition, not tunable values. */
        const val ONE_BYTE_CEILING = 0x80
        const val TWO_BYTE_CEILING = 0x800
        const val BASIC_PLANE_BYTES = 3
        const val SURROGATE_PAIR_BYTES = 4
    }
}
