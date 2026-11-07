package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.ManagedLakeErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** Deterministically lose protection inside physical acquisition, between the two admission checks. */
class ManagedGenerationPinAdmissionTest {
    @Test
    fun `a connection acquired after pin loss is discarded and never handed to the borrower`() {
        val pins = Pins()
        val connection = mockk<Connection>(relaxed = true)
        val pool = Pool(connection) { pins.live = false }
        val manager = ConnectionPoolManager(poolFactory = { pool }, pins = pins)
        val failure = shouldThrow<DatapipelinesException> { manager.poolFor(datasource()).leaseConnection() }
        failure.code shouldBe ManagedLakeErrorCodes.UNAVAILABLE
        pool.discarded shouldBe 1
        verify(exactly = 1) { connection.close() }
        manager.close()
    }

    @Test
    fun `a still protected connection is handed out without discard`() {
        val pins = Pins()
        val connection = mockk<Connection>(relaxed = true)
        val pool = Pool(connection) {}
        val manager = ConnectionPoolManager(poolFactory = { pool }, pins = pins)
        manager.poolFor(datasource()).leaseConnection() shouldBeSameInstanceAs connection
        pool.discarded shouldBe 0
        verify(exactly = 0) { connection.close() }
        manager.close()
    }

    @Test
    fun `a false pin result closes the built owner without acquiring a borrower connection`() {
        val connection = mockk<Connection>(relaxed = true)
        val pool = Pool(connection) {}
        val manager = ConnectionPoolManager(poolFactory = { pool }, pins = Pins(acquired = false))
        val failure = shouldThrow<DatapipelinesException> { manager.poolFor(datasource()) }
        failure.code shouldBe ManagedLakeErrorCodes.UNAVAILABLE
        pool.acquisitions shouldBe 0
        pool.closes shouldBe 1
        manager.close()
    }

    private class Pool(
        private val connection: Connection,
        private val acquire: () -> Unit,
    ) : ConnectionPool {
        override val name = "lake"
        override val generation = UUID.randomUUID().toString()
        override val managedGeneration = ManagedLakeGeneration(name, emptyList())
        var discarded = 0
        var acquisitions = 0
        var closes = 0

        override fun leaseConnection(): Connection {
            acquisitions++
            acquire()
            return connection
        }

        override fun discard(connection: Connection) {
            discarded++
        }

        override fun close() {
            closes++
        }
    }

    private class Pins(
        private val acquired: Boolean = true,
    ) : ManagedGenerationPins {
        var live = true

        override fun pin(
            generation: UUID,
            datasource: String,
            locations: Map<String, String>,
            builtAt: Instant,
            holdsHandles: () -> Boolean,
            retire: () -> Unit,
        ): Boolean = acquired

        override fun valid(generation: UUID): Boolean = live

        override fun unpin(generation: UUID) = Unit
    }

    private fun datasource(): Datasource = Datasource(name = "lake", displayName = "Lake", dialect = Dialect.LAKE, jdbcUrl = "jdbc:duckdb:")
}
