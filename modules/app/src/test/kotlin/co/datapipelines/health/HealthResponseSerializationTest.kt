package co.datapipelines.health

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.info.BuildProperties
import java.time.Instant
import java.util.Properties

/**
 * Wire-shape regression guard for the probe payloads.
 *
 * This test exists because of a specific, previously-shipped failure mode: a field
 * whose Kotlin name differs from its JSON name gets silently renamed by Jackson's
 * Java Beans introspection plus a naming strategy, and the endpoint keeps returning
 * 200 with the wrong keys. `h2_factory` and `build_time` are exactly that shape.
 * Asserting the key SET (not just individual lookups) is what makes the guard real —
 * a renamed key fails as a missing key AND an unexpected one.
 *
 * Contracts pinned here: rest-api.md §11.1 (`/health`) and observability.md §6.3
 * (`/info`). Changing either payload must break this test first.
 */
class HealthResponseSerializationTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `health payload serializes to exactly the rest-api §11-1 keys`() {
        val json =
            mapper.readTree(
                mapper.writeValueAsString(
                    HealthResponse(
                        status = "UP",
                        version = "1.2.3",
                        components = mapOf("database" to "UP", "redis" to "UP", "h2_factory" to "UP"),
                    ),
                ),
            )

        json.fieldNames().asSequence().toList() shouldContainExactly listOf("status", "version", "components")
        json["status"].asText() shouldBe "UP"
        json["version"].asText() shouldBe "1.2.3"

        // The xMin trap: `h2_factory`, not `h2Factory` and not `h2factory`.
        json["components"].fieldNames().asSequence().toList() shouldContainExactly
            listOf("database", "redis", "h2_factory")
    }

    @Test
    fun `health payload survives a snake_case naming strategy unchanged`() {
        // If the app ever adopts a global naming strategy, the explicit @JsonProperty
        // annotations must still win. Without them, `h2_factory` is a map KEY and safe,
        // but `version`/`status` and any future camelCase field would be rewritten.
        val strategised =
            ObjectMapper()
                .registerKotlinModule()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

        val json =
            strategised.readTree(
                strategised.writeValueAsString(
                    HealthResponse("UP", "1.2.3", mapOf("h2_factory" to "UP")),
                ),
            )

        json.fieldNames().asSequence().toList() shouldContainExactly listOf("status", "version", "components")
        json["components"].fieldNames().asSequence().toList() shouldContainExactly listOf("h2_factory")
    }

    @Test
    fun `info payload uses build_time and omits an absent commit`() {
        val json =
            mapper.readTree(
                mapper.writeValueAsString(
                    InfoResponse(version = "1.2.3", buildTime = "2026-08-07T12:00:00Z", commit = null),
                ),
            )

        // `commit` absent entirely — never the string "unknown" (see InfoResponse KDoc).
        json.fieldNames().asSequence().toList() shouldContainExactly listOf("version", "build_time")
        json["build_time"].asText() shouldBe "2026-08-07T12:00:00Z"
    }

    @Test
    fun `info payload includes commit when the build supplied one`() {
        val json =
            mapper.readTree(
                mapper.writeValueAsString(
                    InfoResponse(version = "1.2.3", buildTime = "2026-08-07T12:00:00Z", commit = "abc1234"),
                ),
            )

        json.fieldNames().asSequence().toList() shouldContainExactly listOf("version", "build_time", "commit")
        json["commit"].asText() shouldBe "abc1234"
    }

    @Test
    fun `controller maps Boot indicator names onto the contract component names`() {
        val endpoint =
            mockk<HealthEndpoint> {
                // Boot calls them `db` and `redis`; the contract says `database` and `redis`.
                every { healthForPath("db") } returns Health.up().build()
                every { healthForPath("redis") } returns Health.up().build()
            }

        val response = controller(endpoint).health()

        response.status shouldBe "UP"
        response.components shouldContainExactly
            mapOf("database" to "UP", "redis" to "UP", "h2_factory" to "UP")
    }

    @Test
    fun `a down component drives the overall status down`() {
        val endpoint =
            mockk<HealthEndpoint> {
                every { healthForPath("db") } returns Health.up().build()
                every { healthForPath("redis") } returns Health.down().build()
            }

        val response = controller(endpoint).health()

        response.status shouldBe Status.DOWN.code
        response.components["redis"] shouldBe "DOWN"
        // Still reports all three keys — a failing component must not vanish.
        response.components.keys shouldContainExactly setOf("database", "redis", "h2_factory")
    }

    @Test
    fun `an unknown indicator reports DOWN rather than disappearing`() {
        val endpoint =
            mockk<HealthEndpoint> {
                every { healthForPath("db") } returns null
                every { healthForPath("redis") } returns null
            }

        val response = controller(endpoint).health()

        response.status shouldBe Status.DOWN.code
        response.components shouldContainExactly
            mapOf("database" to "DOWN", "redis" to "DOWN", "h2_factory" to "UP")
    }

    @Test
    fun `ready returns 503 while readiness is refusing traffic`() {
        val endpoint =
            mockk<HealthEndpoint> {
                every { healthForPath(any()) } returns Health.up().build()
            }
        val notAccepting =
            mockk<ApplicationAvailability> {
                every { readinessState } returns ReadinessState.REFUSING_TRAFFIC
            }

        controller(endpoint, notAccepting).ready().statusCode.value() shouldBe 503
    }

    @Test
    fun `info reports the build version and timestamp`() {
        val props =
            BuildProperties(
                Properties().apply {
                    setProperty("version", "9.9.9")
                    setProperty("time", Instant.parse("2026-08-07T12:00:00Z").toEpochMilli().toString())
                    setProperty("commit", "deadbee")
                },
            )

        val info = controller(buildProperties = props).info()

        info.version shouldBe "9.9.9"
        info.commit shouldBe "deadbee"
        info.buildTime shouldBe "2026-08-07T12:00:00Z"
    }

    @Test
    fun `version falls back to unknown when no build info was generated`() {
        controller(buildProperties = null).health().version shouldBe "unknown"
    }

    private fun controller(
        endpoint: HealthEndpoint =
            mockk {
                every { healthForPath(any()) } returns Health.up().build()
            },
        availability: ApplicationAvailability =
            mockk {
                every { readinessState } returns ReadinessState.ACCEPTING_TRAFFIC
            },
        buildProperties: BuildProperties? = null,
    ): HealthController =
        HealthController(
            healthEndpoint = endpoint,
            availability = availability,
            buildProperties = objectProviderOf(buildProperties),
        )

    private fun objectProviderOf(value: BuildProperties?): ObjectProvider<BuildProperties> =
        mockk {
            every { ifAvailable } returns value
        }
}
