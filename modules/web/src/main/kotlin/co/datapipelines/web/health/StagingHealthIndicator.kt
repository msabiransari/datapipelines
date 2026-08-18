package co.datapipelines.web.health

import co.datapipelines.staging.StagingEngine
import co.datapipelines.staging.StagingFactory
import kotlinx.coroutines.runBlocking
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import java.util.UUID

/**
 * Actuator indicator for the staging H2 factory. The bean name is the actuator health
 * path — it must stay `h2_factory` (rest-api.md §11.1 names that exact component key),
 * which is why the declaring `@Bean` pins the name explicitly (015).
 */
class StagingHealthIndicator(
    private val stagingFactory: StagingFactory,
) : HealthIndicator {
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun health(): Health =
        try {
            val staging = stagingFactory.create(UUID.randomUUID(), StagingEngine.H2)
            try {
                runBlocking {
                    staging.withConnection { conn ->
                        conn.createStatement().use { st ->
                            st.executeQuery("SELECT 1").use { rs ->
                                require(rs.next())
                                require(rs.getInt(1) == 1)
                            }
                        }
                    }
                }
                Health.up().build()
            } finally {
                staging.close()
            }
        } catch (e: Exception) {
            Health.down().build()
        }
}
