package co.datapipelines.executor

import co.datapipelines.staging.StageObserver
import co.datapipelines.staging.StageResult
import co.datapipelines.staging.Staging
import co.datapipelines.typesystem.ColumnSchema
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.ResultSet

/**
 * Observes the real H2 cursor and lazy output drain, without replacing either with a fake.
 * Each decoded payload is copied before the production reader puts it in a wire row: H2
 * owns the original string; only the transform owns the copy. Output deliberately drops
 * this column. Weak references therefore measure retained input payload identities, not
 * transient allocation, H2 tables, JVM heap bytes, or all of the transform's objects.
 * The probe itself retains O(N) weak-reference bookkeeping, never the payloads.
 */
internal class RetainedInputProbe(
    private val delegate: Staging,
    private val batchSize: Int,
) : Staging by delegate {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    private val payloads = mutableListOf<WeakReference<String>>()
    private var cursors = 0
    private var delivered = 0
    private var samples = 0
    private var peakRetained = 0
    private var peakReadAhead = 0

    override suspend fun <T> withQuery(
        sql: String,
        block: suspend (ResultSet) -> T,
    ): T {
        if (sql != INPUT_QUERY) return delegate.withQuery(sql, block)
        cursors++
        return delegate.withQuery(sql) { rs ->
            val observed =
                Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java)) { _, method, args ->
                    val value =
                        try {
                            method.invoke(rs, *args.orEmpty())
                        } catch (failure: InvocationTargetException) {
                            throw failure.targetException
                        }
                    if (method.name == "getString" && args?.singleOrNull() == PAYLOAD_COLUMN) {
                        val payload = String((value as String).toCharArray())
                        payloads += WeakReference(payload)
                        peakReadAhead = maxOf(peakReadAhead, payloads.size - delivered)
                        if (payloads.size % batchSize == 0) sample()
                        payload
                    } else {
                        value
                    }
                } as ResultSet
            block(observed)
        }
    }

    override suspend fun stageRows(
        tableName: String,
        columns: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
        observer: StageObserver,
    ): StageResult {
        if (tableName != "out_big") return delegate.stageRows(tableName, columns, rows, observer)
        return delegate.stageRows(
            tableName,
            columns,
            rows.map { row ->
                delivered++
                row
            },
            observer,
        )
    }

    private fun sample() {
        val before = collectionCount()
        val witness = collectionWitness()
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
        val after = collectionCount()
        check(witness.get() == null) { "invalid retention probe: fresh weak witness was not collected" }
        val retained = payloads.count { it.get() != null }
        validateSample(before, after, retained, batchSize)
        samples++
        peakRetained = maxOf(peakRetained, retained)
        println("input retention sample: read=${payloads.size} delivered=$delivered live=$retained collections=$before->$after")
    }

    fun verify(expectedRows: Int) {
        val status = Path.of("/proc/self/status")
        val affinity =
            if (Files.isRegularFile(status)) {
                Files.readAllLines(status).single { it.startsWith("Cpus_allowed_list:") }.substringAfter(':').trim()
            } else {
                "unavailable"
            }
        println(
            "streaming proof: read=${payloads.size} delivered=$delivered cursors=$cursors samples=$samples " +
                "peakReadAhead=$peakReadAhead peakRetained=$peakRetained batch=$batchSize " +
                "processors=${Runtime.getRuntime().availableProcessors()} affinity=$affinity",
        )
        check(
            expectedRows > 2 * batchSize && cursors == 1 && payloads.size == expectedRows &&
                delivered == expectedRows && samples == expectedRows / batchSize,
        ) {
            "invalid retention probe: must observe the full input cursor, output drain and every batch"
        }
        check(peakReadAhead <= batchSize) { "streaming read-ahead $peakReadAhead exceeds one batch $batchSize" }
        // At the read boundary, the previous batch's suspended sequence/evaluation may still
        // hold it while the new batch is being filled. At most two batches' worth may stay live.
        check(peakRetained <= 2 * batchSize) { "retained input payloads $peakRetained exceed two batches ${2 * batchSize}" }
    }

    internal companion object {
        private const val INPUT_QUERY = "SELECT \"n\", \"payload\" FROM \"stg_big\""
        private const val PAYLOAD_COLUMN = 2

        fun validateSample(
            before: Long,
            after: Long,
            retained: Int,
            batchSize: Int,
        ) {
            check(before >= 0 && after > before) {
                "invalid retention probe: collection count must advance ($before->$after)"
            }
            check(batchSize > 0 && retained >= batchSize) {
                "invalid retention probe: the current batch must be live ($retained/$batchSize)"
            }
        }

        private fun collectionCount(): Long {
            val counts = ManagementFactory.getGarbageCollectorMXBeans().map { it.collectionCount }
            check(counts.isNotEmpty() && counts.all { it >= 0 }) { "invalid retention probe: collection counts unavailable" }
            return counts.sum()
        }

        private fun collectionWitness(): WeakReference<Any> = WeakReference(Any())
    }
}
