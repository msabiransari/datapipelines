package co.datapipelines.pipeline.release

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * The constraint-race savepoint (#517): outside a transaction the statement runs bare; inside one it is bracketed by a
 * savepoint, a lost unique race rolls back to it (so the winner's read can still run) and rethrows, and any other
 * failure leaves the savepoint alone — the transaction is the caller's to roll back.
 */
class RaceSavepointTest {
    private val statements = mutableListOf<String>()
    private val jdbc =
        mockk<JdbcOperations> {
            every { execute(any<String>()) } answers { statements += firstArg<String>() }
        }

    @AfterEach
    fun clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false)
    }

    @Test
    fun `outside a transaction the block runs bare`() {
        RaceSavepoint.attempt(jdbc) { "row" } shouldBe "row"
        statements.shouldBeEmpty()
    }

    @Test
    fun `inside a transaction a won race is released`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)

        val written =
            RaceSavepoint.attempt(jdbc) {
                statements += "INSERT"
                1
            }

        written shouldBe 1

        statements shouldContainExactly listOf("SAVEPOINT dp_constraint_race", "INSERT", "RELEASE SAVEPOINT dp_constraint_race")
    }

    @Test
    fun `inside a transaction a lost race rolls back to the savepoint and rethrows`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        val lost = DuplicateKeyException("duplicate key")

        shouldThrow<DuplicateKeyException> { RaceSavepoint.attempt(jdbc) { throw lost } } shouldBe lost

        statements shouldContainExactly listOf("SAVEPOINT dp_constraint_race", "ROLLBACK TO SAVEPOINT dp_constraint_race")
    }

    @Test
    fun `any other failure is not a race and leaves the savepoint to the transaction`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)

        shouldThrow<IllegalStateException> { RaceSavepoint.attempt(jdbc) { error("not a race") } }

        statements shouldContainExactly listOf("SAVEPOINT dp_constraint_race")
    }
}
