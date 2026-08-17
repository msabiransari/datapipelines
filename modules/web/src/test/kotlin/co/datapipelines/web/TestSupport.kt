package co.datapipelines.web

import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * Locates files relative to the repository root, whichever directory the test task runs from —
 * the same helper `dag`, `auth` and `pipeline-contract` keep in their own test source sets to
 * read the specs and app's real `V1__initial_schema.sql` (module-structure §3.1 rule 2: no domain
 * module takes a Flyway dependency).
 */
object TestRepoFiles {
    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "Could not locate repository root (no settings.gradle.kts on any ancestor)" }
    }

    fun read(relativePath: String): String =
        File(root, relativePath)
            .also { require(it.exists()) { "Expected repo file not found: $relativePath (root=$root)" } }
            .readText()

    const val MIGRATION_PATH = "modules/app/src/main/resources/db/migration/V1__initial_schema.sql"
    const val CONTRACT_SPEC_PATH = "docs/pipeline-contract.md"
    const val REST_SPEC_PATH = "docs/rest-api.md"
    const val CONFIG_SPEC_PATH = "docs/configuration.md"
}

/**
 * A one-row `getTables` [java.sql.ResultSet] — the single (schema, name, type) row the
 * schema-controller tests' tables walk reports ([schemaColumn] selects the dialect's
 * vocabulary; TABLE_CAT for catalog-routing drivers). This module's OWN copy of the small
 * builder the datasources and mcp-server test sources also keep — no cross-module coupling
 * (R5 F8; the hand-copied stanza had 12+ copies across the three modules).
 */
fun tablesResultSet(
    schema: String?,
    name: String,
    type: String = "TABLE",
    schemaColumn: String = "TABLE_SCHEM",
): java.sql.ResultSet {
    val rs = io.mockk.mockk<java.sql.ResultSet>(relaxed = true)
    io.mockk.every { rs.next() } returns true andThen false
    io.mockk.every { rs.getString(schemaColumn) } returns schema
    io.mockk.every { rs.getString("TABLE_NAME") } returns name
    io.mockk.every { rs.getString("TABLE_TYPE") } returns type
    return rs
}

/**
 * One Redis container shared by this module's integration tests — the singleton pattern
 * `dag`'s RedisSupport established (one image startup, Ryuk reaps it at JVM exit).
 */
object TestRedis {
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
 * An `SseEmitter` whose frames a test can read. Spring's `ResponseBodyEmitter.Handler` — the
 * intended capture point — is package-private, and `SseEmitter.send(SseEventBuilder)` calls
 * `super.send(...)`, which bypasses subclass overrides of the `Set` overload. The one virtual
 * method every send in this module funnels through is the `SseEventBuilder` overload, so that is
 * the capture point.
 */
class CapturingSseEmitter : SseEmitter(0L) {
    private val frames = ConcurrentLinkedQueue<String>()
    val completed = CountDownLatch(1)

    /** When set, the next send throws it — the dropped-client signal a servlet container gives. */
    @Volatile var failNextSendWith: IOException? = null

    override fun send(builder: SseEventBuilder) {
        failNextSendWith?.let { throw it }
        builder.build().forEach { frames.add(it.data.toString()) }
    }

    override fun complete() {
        completed.countDown()
        super.complete()
    }

    /** The captured frame fragments. */
    fun frames(): List<String> = frames.toList()

    /** The `event:` names captured, in order. */
    fun eventNames(): List<String> = frames().mapNotNull { EVENT_REGEX.find(it)?.groupValues?.get(1) }

    /** The `id:` values captured, in order. */
    fun eventIds(): List<String> = frames().mapNotNull { ID_REGEX.find(it)?.groupValues?.get(1) }

    private companion object {
        val EVENT_REGEX = Regex("""event: ?(\w+)""")
        val ID_REGEX = Regex("""id: ?(\d+)""")
    }
}
