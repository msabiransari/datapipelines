package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

/** A shared-data reset must leave V53's singleton available to the next managed-lake context's startup check. */
@SpringBootTest(classes = [DatapipelinesApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class E2eCleanSeedRestorationTest : EntryAssuranceE2eBase() {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `a seed reset restores the lake control singleton in normal mode`() {
        E2eClean.beforeSeeding()

        jdbc.queryForList("SELECT id::text || ':' || mode FROM lake_control", String::class.java) shouldBe listOf("1:normal")
    }

    @Test
    fun `a pre-context reset restores the lake control singleton before a later startup`() {
        E2eClean.beforeContext()

        jdbc.queryForList("SELECT id::text || ':' || mode FROM lake_control", String::class.java) shouldBe listOf("1:normal")
    }
}
