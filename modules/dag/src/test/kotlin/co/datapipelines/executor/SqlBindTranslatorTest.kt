package co.datapipelines.executor

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.sql.PreparedStatement

/**
 * [SqlBindTranslator] — the wrapper's own contract, on top of the pinned jar behaviour
 * ([NamedParameterTranslationTest] pins spring-jdbc's parser; [ParameterBindingIntegrationTest]
 * drives the full node path end-to-end). What only this suite pins: the [SqlBindTranslator.BoundSql]
 * shape, the `sql_parameter_missing` refusal mapping, and the deliberate null binding —
 * the three places the wrapper's own code could drift while every other test stays green.
 */
class SqlBindTranslatorTest {
    @Test
    fun `sql without parameters passes through untouched`() {
        val bound = SqlBindTranslator.translate("SELECT 1", emptyMap())

        bound.sql shouldBe "SELECT 1"
        bound.originalSql shouldBe "SELECT 1"
        bound.hasBindParameters shouldBe false
    }

    @Test
    fun `named references become ordered positional placeholders and values`() {
        val bound =
            SqlBindTranslator.translate(
                "SELECT * FROM t WHERE a = :a AND b = :b",
                mapOf("a" to 1, "b" to "x"),
            )

        bound.sql shouldBe "SELECT * FROM t WHERE a = ? AND b = ?"
        bound.bindValues shouldBe listOf(1, "x")
        bound.hasBindParameters shouldBe true
        bound.originalSql shouldContain ":a"
    }

    @Test
    fun `a declared key with a null value binds null - the author's semantics, not a refusal`() {
        val bound =
            SqlBindTranslator.translate(
                "SELECT * FROM t WHERE x IS NOT DISTINCT FROM :opt",
                mapOf("opt" to null),
            )

        bound.bindValues shouldBe listOf(null)
        bound.hasBindParameters shouldBe true
    }

    @Test
    fun `a name the context does not declare is the loud refusal - never a silent null`() {
        val error =
            io.kotest.assertions.throwables.shouldThrow<DatapipelinesException> {
                SqlBindTranslator.translate("SELECT * FROM t WHERE a = :ghost", emptyMap())
            }

        error.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.SQL_PARAMETER_MISSING
        error.message shouldContain "declare"
        error.message shouldContain ":name"
    }

    @Test
    fun `a reference inside a string literal is not a parameter`() {
        val bound =
            SqlBindTranslator.translate(
                """SELECT ':not_a_param' AS literal, :real FROM t""",
                mapOf("real" to 5),
            )

        bound.bindValues shouldBe listOf(5)
        // The literal survives verbatim — only the real reference was substituted.
        bound.sql shouldContain ":not_a_param"
        bound.sql shouldNotContain ":real"
    }

    @Test
    fun `bind puts the values on the statement positionally through the house path`() {
        val statement = mockk<PreparedStatement>(relaxed = true)
        every { statement.setObject(any(), any()) } returns Unit

        SqlBindTranslator.bind(statement, listOf("first", 2))

        // StatementCreatorUtils routes a String through setString and the Int through
        // setObject — the house binding path, whatever statement method it picks.
        verify { statement.setString(1, "first") }
        verify { statement.setObject(2, 2) }
    }

    @Test
    fun `bind on an empty value list touches nothing`() {
        val statement = mockk<PreparedStatement>(relaxed = true)

        SqlBindTranslator.bind(statement, emptyList())

        verify(exactly = 0) { statement.setObject(any(), any()) }
    }
}
