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
 */
internal object SharedRedis {
    private const val IMAGE = "redis:7-alpine"
    private const val PORT = 6379

    /** The shared container, started and flushed on first touch. */
    val redis: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(IMAGE))
            .withCommand("redis-server", "--maxmemory-policy", "noeviction")
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
