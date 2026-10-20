package co.datapipelines.executor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import javax.sql.DataSource

/**
 * [BoundedJdbc] — #311's construction, lifted by #326: 0 is the template itself (unbounded, every
 * direct construction in the tests); a positive bound is a NEW template on the SAME DataSource, so
 * only its statements carry the `queryTimeout` and the caller's template is left as it was. That the
 * driver cancels at the bound is `LifecycleWriteBoundIntegrationTest`'s case A (the lifecycle writes)
 * and `ScheduledJobsSchedulerE2eTest` (the sweep), both on a real Postgres.
 */
class BoundedJdbcTest {
    private val dataSource = mockk<DataSource>()
    private val jdbc = NamedParameterJdbcTemplate(dataSource)

    @Test
    fun `zero is the template itself - unbounded`() {
        BoundedJdbc.bounded(jdbc, 0, "bound") shouldBeSameInstanceAs jdbc
    }

    @Test
    fun `a positive bound is a new template on the same DataSource, and the caller's template is untouched`() {
        val bounded = BoundedJdbc.bounded(jdbc, SECONDS, "bound")

        bounded shouldNotBeSameInstanceAs jdbc
        bounded.jdbcTemplate.queryTimeout shouldBe SECONDS
        bounded.jdbcTemplate.dataSource shouldBeSameInstanceAs dataSource
        jdbc.jdbcTemplate.queryTimeout shouldBe UNBOUNDED
    }

    @Test
    fun `a positive bound refuses a template with no DataSource, naming the bound`() {
        val noDataSource = NamedParameterJdbcTemplate(JdbcTemplate())

        shouldThrow<IllegalArgumentException> { BoundedJdbc.bounded(noDataSource, SECONDS, "sweepStatementTimeoutSeconds") }
            .message shouldBe "sweepStatementTimeoutSeconds requires a DataSource-backed NamedParameterJdbcTemplate"
    }

    private companion object {
        const val SECONDS = 7

        /** `JdbcTemplate`'s own default: -1, the driver's. */
        const val UNBOUNDED = -1
    }
}
