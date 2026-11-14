package co.datapipelines.web.config

import ch.qos.logback.classic.Logger
import co.datapipelines.lake.delivery.LakeStagingStorage
import co.datapipelines.lake.maintenance.compaction.CompactionDeadlinePassed
import co.datapipelines.lake.maintenance.compaction.CompactionScratchExhausted
import co.datapipelines.lake.maintenance.compaction.CompactionStatement
import co.datapipelines.logcapture.capturingLogEvents
import co.datapipelines.staging.Staging
import co.datapipelines.staging.StagingPreparation
import co.datapipelines.staging.StagingScratchLimitException
import co.datapipelines.staging.TrustedScratchDatabase
import co.datapipelines.staging.TrustedStagingInitializer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * #969 D5 step 5 and C14's controls: the web adapter over a stand-in trusted database and a fixed clock. The deadline
 * is decided by the clock before anything else; before it, only staging's scratch-limit exception converts, and an
 * "Interrupted" or a plain [SQLException] passes unchanged (so the part fails: neither `deadline` nor `capacity`). The
 * real store and engine are `ManagedLakeCompactionE2eTest`'s.
 */
class TrustedCompactionScratchTest {
    private val deadline = Instant.parse("2026-11-14T03:00:00Z")
    private val before = Clock.fixed(deadline.minusMillis(2_500), ZoneOffset.UTC)
    private val after = Clock.fixed(deadline, ZoneOffset.UTC)
    private val statement = unsortedLongStatement()

    @Test
    fun `before the deadline an Interrupted and a plain SQLException pass unchanged`() {
        for (thrown in listOf(SQLException("Interrupted"), SQLException("IO Error: no such key", "58030"))) {
            val db = StandIn(failOnQuery = thrown)
            val session = TrustedCompactionScratch(RecordingStorage(), before).open(db.preparation, deadline)
            val caught = shouldThrow<SQLException> { session.read(statement, listOf("s3://b/f.parquet")) { } }
            caught shouldBeSameInstanceAs thrown
        }
    }

    @Test
    fun `before the deadline the scratch-limit exception and only it becomes capacity's exception, with the grant`() {
        val limit = StagingScratchLimitException(UUID.randomUUID(), GRANT, measuredBytes = null, engineReading = "9.0 GiB")
        val session = TrustedCompactionScratch(RecordingStorage(), before).open(StandIn(failOnQuery = limit).preparation, deadline)

        val caught = shouldThrow<CompactionScratchExhausted> { session.read(statement, listOf("s3://b/f.parquet")) { } }

        caught.grantBytes shouldBe GRANT
        caught.engineReading shouldBe "9.0 GiB"
    }

    @Test
    fun `after the deadline any failure of the read is the deadline, decided by the clock, the scratch one included`() {
        val limit = StagingScratchLimitException(UUID.randomUUID(), GRANT, measuredBytes = null, engineReading = "9.0 GiB")
        for (thrown in listOf(SQLException("secret-bearing text", "HY008"), limit, IllegalStateException("x"))) {
            val clock = MovableClock(before.instant())
            val db = StandIn(failOnQuery = thrown, onQuery = { clock.now = deadline })
            val session = TrustedCompactionScratch(RecordingStorage(), clock).open(db.preparation, deadline)
            val events =
                capturingLogEvents(LoggerFactory.getLogger(TrustedCompactionScratch::class.java) as Logger) {
                    shouldThrow<CompactionDeadlinePassed> { session.read(statement, listOf("s3://b/f.parquet")) { } }
                }
            val line = events.single().formattedMessage
            line shouldContain "event=lake.maintenance.compaction_deadline"
            line shouldContain "cause=${thrown.javaClass.simpleName}"
            line shouldNotContain "secret-bearing"
        }
    }

    @Test
    fun `an open that fails after the deadline is the deadline, before it the failure passes as it is`() {
        val refusal = IllegalStateException("creation_failed")
        val late = StandIn(failOnOpen = refusal)
        shouldThrow<CompactionDeadlinePassed> { TrustedCompactionScratch(RecordingStorage(), after).open(late.preparation, deadline) }
        val early = StandIn(failOnOpen = refusal)
        shouldThrow<IllegalStateException> {
            TrustedCompactionScratch(RecordingStorage(), before).open(early.preparation, deadline)
        } shouldBeSameInstanceAs refusal
    }

    @Test
    fun `the initializer configures the store then makes keys literal, and a read binds the files as one array under the deadline`() {
        val storage = RecordingStorage()
        val db = StandIn()
        val session = TrustedCompactionScratch(storage, before).open(db.preparation, deadline)
        val seen = AtomicInteger()

        session.read(statement, listOf("s3://b/a?.parquet", "s3://b/[b].parquet")) { seen.incrementAndGet() }

        storage.deadlines shouldContainExactly listOf(deadline)
        db.executed shouldContainExactly listOf("SET s3_url_compatibility_mode = true")
        db.prepared shouldContainExactly listOf(statement.sql)
        db.bound shouldContainExactly listOf(listOf("s3://b/a?.parquet", "s3://b/[b].parquet"))
        db.timeouts shouldContainExactly listOf(3, 3)
        seen.get() shouldBe 1
        session.close()
        db.closes.get() shouldBe 1
    }

    @Test
    fun `a reservation that is not the staging factory's preparation is a wiring defect`() {
        shouldThrow<IllegalArgumentException> {
            TrustedCompactionScratch(RecordingStorage(), before).open(AutoCloseable { }, deadline)
        }.message.orEmpty() shouldContain "not the staging factory's preparation"
    }

    private class RecordingStorage : LakeStagingStorage {
        val deadlines = CopyOnWriteArrayList<Instant>()

        override fun configure(
            connection: Connection,
            deadline: Instant,
            statements: co.datapipelines.pipeline.LakeStatementExecution,
        ) {
            deadlines += deadline
        }
    }

    private class MovableClock(
        @Volatile var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }

    /** A trusted database stand-in: one JDBC connection of proxies; its body passes failures as the engine gave them. */
    private class StandIn(
        private val failOnQuery: Exception? = null,
        private val failOnOpen: Exception? = null,
        private val onQuery: () -> Unit = {},
    ) {
        val executed = CopyOnWriteArrayList<String>()
        val prepared = CopyOnWriteArrayList<String>()
        val bound = CopyOnWriteArrayList<List<String>>()
        val timeouts = CopyOnWriteArrayList<Int>()
        val closes = AtomicInteger()

        private val connection: Connection =
            proxy(Connection::class.java) { name, args ->
                when (name) {
                    "createStatement" -> statement()
                    "prepareStatement" -> prepared(args[0] as String)
                    "createArrayOf" -> keys(@Suppress("UNCHECKED_CAST") (args[1] as Array<Any?>).map { it as String })
                    else -> null
                }
            }

        private fun statement(): Statement =
            proxy(Statement::class.java) { name, args ->
                when (name) {
                    "setQueryTimeout" -> timeouts.add(args[0] as Int).let { null }
                    "execute" -> executed.add(args[0] as String)
                    else -> null
                }
            }

        private fun prepared(sql: String): PreparedStatement {
            prepared += sql
            return proxy(PreparedStatement::class.java) { name, args ->
                when (name) {
                    "setQueryTimeout" -> {
                        timeouts.add(args[0] as Int).let { null }
                    }

                    "setArray" -> {
                        bound.add(lastKeys).let { null }
                    }

                    "executeQuery" -> {
                        onQuery()
                        failOnQuery?.let { throw it }
                        proxy(ResultSet::class.java) { _, _ -> null }
                    }

                    else -> {
                        null
                    }
                }
            }
        }

        private var lastKeys: List<String> = emptyList()

        private fun keys(values: List<String>): java.sql.Array {
            lastKeys = values
            return proxy(java.sql.Array::class.java) { _, _ -> null }
        }

        val database =
            object : TrustedScratchDatabase {
                override val executionId: UUID = UUID.randomUUID()
                override val reservedBytes: Long = GRANT

                override fun <T> withRoot(body: (Connection) -> T): T = body(connection)

                override fun diskBytes(): Long = 0

                override fun close() {
                    closes.incrementAndGet()
                }
            }

        val preparation =
            object : StagingPreparation {
                override val executionId: UUID = database.executionId
                override val reservedBytes: Long = GRANT

                override fun open(initializer: TrustedStagingInitializer): Staging = error("never an author database")

                override fun openTrusted(initializer: TrustedStagingInitializer): TrustedScratchDatabase {
                    failOnOpen?.let { throw it }
                    initializer.initialize(connection)
                    return database
                }

                override fun afterRelease(callback: () -> Unit) = Unit

                override fun close() = Unit
            }
    }

    private companion object {
        const val GRANT = 64L shl 20

        /**
         * D6's statement for one BIGINT column, unsorted. This module does not compile against Iceberg (the lake carries
         * it at run time), so the schema is built by reflection, as the integration module's oracle does.
         */
        fun unsortedLongStatement(): CompactionStatement {
            fun type(name: String) = Class.forName(name)
            val long = type("org.apache.iceberg.types.Types\$LongType").getMethod("get").invoke(null)
            val fieldType = type("org.apache.iceberg.types.Types\$NestedField")
            val field =
                fieldType
                    .getMethod("optional", Int::class.javaPrimitiveType, String::class.java, type("org.apache.iceberg.types.Type"))
                    .invoke(null, 1, "k", long)
            val fields =
                java.lang.reflect.Array
                    .newInstance(fieldType, 1)
                    .also {
                        java.lang.reflect.Array
                            .set(it, 0, field)
                    }
            val schemaType = type("org.apache.iceberg.Schema")
            val schema = schemaType.getConstructor(fields.javaClass).newInstance(fields)
            val orderType = type("org.apache.iceberg.SortOrder")
            val unsorted = orderType.getMethod("unsorted").invoke(null)
            return CompactionStatement.Companion.javaClass
                .getMethod("of", schemaType, orderType)
                .invoke(CompactionStatement.Companion, schema, unsorted) as CompactionStatement
        }

        /** A JDBC proxy whose unhandled calls answer their return type's default. */
        fun <T> proxy(
            type: Class<T>,
            handle: (String, Array<Any?>) -> Any?,
        ): T =
            type.cast(
                Proxy.newProxyInstance(
                    type.classLoader,
                    arrayOf(type),
                    InvocationHandler { _, method, args ->
                        val answer = handle(method.name, args ?: emptyArray())
                        answer ?: when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            else -> null
                        }
                    },
                ),
            )
    }
}
