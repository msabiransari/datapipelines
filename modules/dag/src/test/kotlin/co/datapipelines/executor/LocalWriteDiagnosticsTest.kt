package co.datapipelines.executor

import ch.qos.logback.classic.Logger
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.logcapture.capturingLogEvents
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Transaction diagnostics name the execution and JDBC metadata without copying driver text. */
class LocalWriteDiagnosticsTest {
    @Test
    fun `commit diagnostics omit driver text and exception chains`() {
        checkDiagnostics(Failure.COMMIT)
    }

    @Test
    fun `rollback diagnostics omit driver text and exception chains`() {
        checkDiagnostics(Failure.ROLLBACK)
    }

    @Test
    fun `connection return diagnostics omit driver text and exception chains`() {
        checkDiagnostics(Failure.CLOSE)
    }

    private fun checkDiagnostics(failure: Failure) {
        val source = h2Datasource("diagnostics_${failure.name.lowercase()}", emptyList()).copy(dialect = Dialect.POSTGRES)
        val base = FakeDatasourceRegistry(mapOf(source.name to source))
        val diagnostic = SQLException(DRIVER_TEXT, "08006")
        var acquired: Connection? = null
        val registry =
            object : DatasourceRegistry by base {
                override fun poolFor(datasource: Datasource): ConnectionPool {
                    val pool = base.poolFor(datasource)
                    return object : ConnectionPool by pool {
                        override fun leaseConnection(): Connection {
                            val connection = pool.leaseConnection()
                            acquired = connection
                            return object : Connection by connection {
                                override fun commit() {
                                    if (failure == Failure.COMMIT) throw diagnostic
                                    connection.commit()
                                }

                                override fun rollback() {
                                    if (failure == Failure.ROLLBACK) throw diagnostic
                                    connection.rollback()
                                }

                                override fun close() {
                                    if (failure == Failure.CLOSE) throw diagnostic
                                    connection.close()
                                }
                            }
                        }
                    }
                }
            }
        val logger = LoggerFactory.getLogger(LocalWriteTransaction::class.java) as Logger
        ExecutorDispatcher.forConfig(ExecutorConfig(), maxThreads = 2).use { dispatcher ->
            val transaction =
                LocalWriteTransaction(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    source.name,
                    registry,
                    ExecutorMetrics.inMemory(),
                    dispatcher,
                )
            try {
                runBlocking { transaction.withParticipant { } }
                val events =
                    capturingLogEvents(logger) {
                        runBlocking {
                            when (failure) {
                                Failure.COMMIT -> {
                                    val refused = shouldThrow<DatapipelinesException> { transaction.commit() }
                                    refused.message.orEmpty() shouldNotContain DRIVER_TEXT
                                    // Existing execution-error policy may inspect the original cause.
                                    refused.cause shouldBe diagnostic
                                }

                                Failure.ROLLBACK -> {
                                    transaction.rollbackQuietly()
                                }

                                Failure.CLOSE -> {
                                    transaction.close()
                                }
                            }
                        }
                    }
                events.shouldHaveSize(1)
                val event = events.single()
                event.formattedMessage shouldContain transaction.rootExecutionId.toString()
                event.formattedMessage shouldContain "SQLState 08006"
                event.formattedMessage shouldNotContain DRIVER_TEXT
                event.throwableProxy shouldBe null
            } finally {
                runBlocking { transaction.close() }
                acquired?.close()
            }
        }
    }

    private enum class Failure {
        COMMIT,
        ROLLBACK,
        CLOSE,
    }

    private companion object {
        const val DRIVER_TEXT = "driver diagnostic fixture"
    }
}
