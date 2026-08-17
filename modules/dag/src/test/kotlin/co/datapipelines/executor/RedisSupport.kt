package co.datapipelines.executor

import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.io.File

/**
 * One Redis container shared by every `*IntegrationTest` in this module.
 *
 * The **singleton container** pattern rather than a per-class `@Container`: `dag` is the module
 * with three independent Redis collaborators (result store, idempotency keys, cancel flags), and a
 * container per class would pay three startups for one image. The JVM tears it down at exit —
 * Testcontainers' Ryuk sidecar reaps it even if the worker dies.
 *
 * There is no `org.testcontainers:redis` module; `GenericContainer` on the core artifact is the
 * documented way, which is why `build.gradle.kts` declares `libs.testcontainers`.
 */
object RedisSupport {
    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(IMAGE))
            .withExposedPorts(PORT)
            .also { it.start() }
    }

    /** A live [StringRedisTemplate] against the shared container. */
    fun template(): StringRedisTemplate {
        val config = RedisStandaloneConfiguration(container.host, container.getMappedPort(PORT))
        val factory = LettuceConnectionFactory(config).apply { afterPropertiesSet() }
        return StringRedisTemplate(factory).apply { afterPropertiesSet() }
    }

    /** Wipes the keyspace between tests so one suite's keys cannot satisfy another's assertion. */
    fun flush(template: StringRedisTemplate) {
        template.connectionFactory?.getConnection()?.use { it.serverCommands().flushAll() }
    }

    private const val IMAGE = "redis:7-alpine"
    private const val PORT = 6379
}

/**
 * Locates files relative to the repository root, whichever directory the test task runs from —
 * the same helper `auth` and `pipeline-contract` use to execute app's shipped migrations
 * without any module taking a Flyway dependency (module-structure §3.1 rule 2).
 */
object RepoFiles {
    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "Could not locate repository root (no settings.gradle.kts on any ancestor)" }
    }

    fun read(relativePath: String): String =
        File(root, relativePath)
            .also {
                require(it.exists()) { "Expected repo file not found: $relativePath (root=$root)" }
            }.readText()

    /**
     * Every shipped migration as a repo-relative path, in NUMERIC version order — derived from
     * the real directory, never a hand-copied list: a migration added to `app` but not to a
     * literal list would run this suite against a stale schema (the R4 F5 failure that
     * datasources' `ShippedMigrations` guards against). Lexicographic order would apply V10
     * between V1 and V2.
     */
    fun migrationPaths(): List<String> =
        File(root, MIGRATION_DIR)
            .listFiles { f: File -> f.isFile }
            .orEmpty()
            .mapNotNull { f -> VERSION_PREFIX.matchEntire(f.name)?.let { it.groupValues[1].toInt() to f.name } }
            .sortedBy { it.first }
            .map { "$MIGRATION_DIR/${it.second}" }
            .also { require(it.isNotEmpty()) { "No migrations found under $MIGRATION_DIR (root=$root)" } }

    private const val MIGRATION_DIR = "modules/app/src/main/resources/db/migration"
    private val VERSION_PREFIX = Regex("""^V(\d+)__.*\.sql$""")
}
