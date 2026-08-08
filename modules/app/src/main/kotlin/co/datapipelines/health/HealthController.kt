package co.datapipelines.health

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.health.CompositeHealth
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Root-level probe endpoints: `/health`, `/ready`, `/info`.
 *
 * Payload and paths for `/health` and `/ready` are owned by [rest-api.md §11.1];
 * `/info` is owned by observability.md §6.3. All three are served at the **root** —
 * not under `/api/v1`, not under `/actuator`. `/actuator/prometheus` is a different
 * surface entirely and lives on the separate management port (observability.md §4.2).
 *
 * Values are bare by design: an unauthenticated probe surface must not leak
 * topology — no hostnames, no JDBC URLs, no credentials, no exception text
 * (observability.md §6.4/§9.2).
 *
 * TODO(auth module): once `SecurityConfig` exists, `/health`, `/ready` and `/info`
 *  must be added to the permit-all matchers in the Spring Security chain
 *  (auth.md §8) so they stay reachable without a credential.
 */
@RestController
class HealthController(
    private val healthEndpoint: HealthEndpoint,
    private val availability: ApplicationAvailability,
    buildProperties: ObjectProvider<BuildProperties>,
) {
    private val build: BuildProperties? = buildProperties.ifAvailable

    @GetMapping("/health")
    fun health(): HealthResponse {
        val components =
            mapOf(
                DATABASE to statusOf(BOOT_DB_INDICATOR),
                REDIS to statusOf(BOOT_REDIS_INDICATOR),
                // TODO(staging module — module-structure.md §5.5): replace with a real
                //  probe that asks StagingFactory to create and drop a throwaway H2
                //  instance. Reported UP unconditionally until StagingFactory exists.
                H2_FACTORY to Status.UP.code,
            )
        val overall = if (components.values.all { it == Status.UP.code }) Status.UP.code else Status.DOWN.code
        return HealthResponse(status = overall, version = versionOrUnknown(), components = components)
    }

    @GetMapping("/ready")
    fun ready(): ResponseEntity<HealthResponse> {
        val body = health()
        val accepting = availability.readinessState == ReadinessState.ACCEPTING_TRAFFIC
        val ready = accepting && body.status == Status.UP.code
        // 503 during startup (until Boot signals ready) and during shutdown draining.
        return ResponseEntity.status(if (ready) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE).body(body)
    }

    /**
     * Build info (observability.md §6.3): version, build timestamp, commit hash.
     *
     * `commit` is omitted entirely when the build did not supply one, rather than
     * reported as `"unknown"` — an operator correlating a deployment to a revision
     * needs the field to be either true or absent, never plausibly wrong. It is
     * populated by building with `-Pdatapipelines.commit=<sha>` (see build.gradle.kts).
     */
    @GetMapping("/info")
    fun info(): InfoResponse =
        InfoResponse(
            version = versionOrUnknown(),
            buildTime = build?.time?.toString(),
            commit = build?.get(COMMIT_PROPERTY),
        )

    private fun versionOrUnknown(): String = build?.version ?: UNKNOWN_VERSION

    /**
     * Reads a Spring Boot health indicator by name and flattens it to `UP` / `DOWN`.
     * A missing indicator is `DOWN`, never absent: a component the contract promises
     * must always appear in the payload.
     */
    private fun statusOf(indicatorName: String): String {
        val component = runCatching { healthEndpoint.healthForPath(indicatorName) }.getOrNull()
        val status =
            when (component) {
                null -> Status.DOWN
                is CompositeHealth -> component.status
                else -> component.status
            }
        return if (status == Status.UP) Status.UP.code else Status.DOWN.code
    }

    private companion object {
        const val UNKNOWN_VERSION = "unknown"
        const val DATABASE = "database"
        const val REDIS = "redis"
        const val H2_FACTORY = "h2_factory"
        const val COMMIT_PROPERTY = "commit"

        // Spring Boot's own indicator names; remapped to the snake_case contract keys.
        const val BOOT_DB_INDICATOR = "db"
        const val BOOT_REDIS_INDICATOR = "redis"
    }
}

/**
 * The `/health` and `/ready` response body (rest-api.md §11.1).
 *
 * `h2_factory` is snake_case and would be mangled by a naming strategy, so every
 * field carries an explicit [JsonProperty] with all three use-site targets.
 */
data class HealthResponse(
    @field:JsonProperty("status") @get:JsonProperty("status") @param:JsonProperty("status")
    val status: String,
    @field:JsonProperty("version") @get:JsonProperty("version") @param:JsonProperty("version")
    val version: String,
    @field:JsonProperty("components") @get:JsonProperty("components") @param:JsonProperty("components")
    val components: Map<String, String>,
)

/**
 * The `/info` response body (observability.md §6.3).
 *
 * `buildTime` → `build_time` and the nullable `commit` are exactly the shapes a
 * Jackson naming strategy would silently get wrong, so both carry an explicit
 * [JsonProperty] on all three use-site targets. Null fields are dropped.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class InfoResponse(
    @field:JsonProperty("version") @get:JsonProperty("version") @param:JsonProperty("version")
    val version: String,
    @field:JsonProperty("build_time") @get:JsonProperty("build_time") @param:JsonProperty("build_time")
    val buildTime: String?,
    @field:JsonProperty("commit") @get:JsonProperty("commit") @param:JsonProperty("commit")
    val commit: String?,
)
