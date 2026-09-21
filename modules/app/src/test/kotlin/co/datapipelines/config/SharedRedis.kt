package co.datapipelines.config

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * ONE Redis container for this module's whole test JVM (round 060) — the singleton
 * pattern `dag`'s `RedisSupport` established. `noeviction` matches what both suites here
 * declared per class: the engine's result store must never evict under memory pressure.
 *
 * The keyspace is FLUSHALLed at first touch so a locally reused container (see
 * DEVELOPMENT.md, "Reusing test containers across runs") is semantically fresh; without
 * the reuse opt-in `withReuse(true)` is a no-op and Ryuk reaps the container at JVM exit.
 *
 * Password-protected since 188 (#189): a passwordless Redis off loopback is a REFUSAL
 * under the `hardened` posture, so the world `ApplicationHardenedSmokeTest` boots into
 * must carry one — and a container on a Docker bridge is "off loopback" to the
 * validator. Every consumer passes [PASSWORD] on both keys the app binds. The in-container
 * `redis-cli` (the FLUSHALL below) authenticates through `REDISCLI_AUTH`, the same
 * environment path the compose healthcheck uses — never `-a` on argv.
 */
internal object SharedRedis {
    private const val IMAGE = "redis:7-alpine"
    private const val PORT = 6379

    /** The shared container's `requirepass` — a test fixture, not a secret. */
    const val PASSWORD = "shared-redis-test-password"

    /** The shared container, started and flushed on first touch. */
    val redis: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(IMAGE))
            .withCommand("redis-server", "--maxmemory-policy", "noeviction", "--requirepass", PASSWORD)
            .withEnv("REDISCLI_AUTH", PASSWORD)
            .withExposedPorts(PORT)
            .withReuse(true)
            .also { container ->
                container.start()
                container
                    .execInContainer("redis-cli", "FLUSHALL")
                    .also { result -> check(result.exitCode == 0) { "redis-cli FLUSHALL failed: ${result.stderr}" } }
            }
    }

    val host: String get() = redis.host

    val port: Int get() = redis.getMappedPort(PORT)
}
