package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.util.UUID

/**
 * The security assertion of §4.5: an attacker-adjacent source column label never reaches
 * generated DDL/DML. Validation runs **before** any `CREATE TABLE`, so an injection-shaped
 * label fails the node with `pipeline.staging.invalid_column_name` and the object catalog is
 * left exactly as it was — nothing created, dropped, or altered.
 *
 * The source `ResultSet` is a mock: it lets the test hand the staging layer any label string,
 * including ones a real database would never let you alias a column to.
 */
class H2StagingIdentifierSafetyTest {
    private val executionId = UUID.randomUUID()
    private val props = H2StagingProperties()
    private val staging = H2StagingFactory(props).create(executionId)

    @AfterEach
    fun tearDown() = staging.close()

    @Test
    fun `every injection-shaped label is rejected and mutates no object`() {
        val injectionLabels =
            listOf(
                "",
                "A".repeat(64),
                "1leading_digit",
                "has space",
                "has\"quote",
                "ends;semicolon",
                "sql--comment",
                "x; DROP TABLE stg_victim; --",
            )

        injectionLabels.forEach { label ->
            val rs = singleLabelResultSet(label)
            val thrown = shouldThrow<StagingInvalidColumnNameException> { runBlocking { staging.stage(rs, "stg_target", Dialect.H2) } }
            thrown.code shouldBe StagingErrorCodes.INVALID_COLUMN_NAME

            // The catalog is untouched — the label never reached a CREATE/DROP/ALTER.
            staging.readFromStaging { tableCount(it) } shouldBe 0
        }
    }

    /** A mock source cursor whose single column carries [label]; only the label is ever read. */
    private fun singleLabelResultSet(label: String): ResultSet {
        val meta =
            mockk<ResultSetMetaData> {
                every { columnCount } returns 1
                every { getColumnLabel(1) } returns label
            }
        return mockk {
            every { metaData } returns meta
        }
    }
}
