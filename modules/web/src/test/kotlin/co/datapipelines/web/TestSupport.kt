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
 * read the specs and app's shipped migrations (module-structure §3.1 rule 2: no domain
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

    /**
     * Asserts [classpathPath] — a resource a test found by scanning the classpath — also
     * exists under one of [sourceDirs], the repo-relative directories the build COPIES
     * resources from, returning the path for chaining (034 D1).
     *
     * The test classpath is the BUILD OUTPUT, and `build/resources` keeps a file deleted
     * from source until `clean` — `--rerun-tasks` re-runs tasks but removes no orphaned
     * resources, so a scanner that trusts the classpath audits ghosts (the 029+031 merged
     * tree went red on `partials/datasource-row.html`, deleted in `ee2aa83`). A ghost now
     * fails HERE with "stale build artifact" instead of a confusing content error. One
     * helper for every classpath scanner in this module: the cause is fixed once. The
     * source dirs are a parameter because not everything packaged lives under this module's
     * own main resources — test fixtures ship from `src/test/resources` and the packaged
     * spec set is copied from the repo-root `docs/` (033).
     */
    fun requireInSources(
        classpathPath: String,
        vararg sourceDirs: String,
    ): String {
        require(sourceDirs.any { File(root, "$it/$classpathPath").isFile }) {
            "'$classpathPath' is on the test classpath but under none of ${sourceDirs.toList()} — " +
                "a stale build artifact (build/resources is not pruned by --rerun-tasks). " +
                "Run ./gradlew :modules:web:clean (or a full clean) and re-run."
        }
        return classpathPath
    }

    /** [requireInSources] rooted at this module's own main AND test resources. */
    fun requireInModuleResources(classpathPath: String): String =
        requireInSources(classpathPath, "modules/web/src/main/resources", "modules/web/src/test/resources")

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

    /**
     * A THROWAWAY container plus a template on it, for the one kind of test whose subject is
     * Redis **going away** (083 §A: the limiter's fail-closed refusal).
     *
     * Deliberately NOT the shared container: stopping that one would take every other suite in
     * this test JVM down with it, and a fixture that can break its neighbours is worse than the
     * flake it was meant to remove. The caller stops it — [Disposable.close] is idempotent, so a
     * test that already stopped it to provoke the outage can still close it in a `finally`.
     */
    fun disposable(): Disposable {
        val container =
            GenericContainer(DockerImageName.parse(IMAGE))
                .withExposedPorts(PORT)
                .also { it.start() }
        val config = RedisStandaloneConfiguration(container.host, container.getMappedPort(PORT))
        val factory = LettuceConnectionFactory(config).apply { afterPropertiesSet() }
        return Disposable(container, StringRedisTemplate(factory).apply { afterPropertiesSet() })
    }

    /** A private Redis and the template on it; [close] stops the container. */
    class Disposable(
        private val container: GenericContainer<*>,
        val template: StringRedisTemplate,
    ) : AutoCloseable {
        /** Stops the server under the template — what makes the next command a `DataAccessException`. */
        fun stopServer() {
            if (container.isRunning) container.stop()
        }

        override fun close() = stopServer()
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

/**
 * A REAL [co.datapipelines.pipeline.PipelineService] over a suite's own mocked collaborators
 * (056).
 *
 * The pipeline controllers take the service rather than the repository now. Building it here from
 * the SAME mocks a suite already stubs is what kept the round's promise that no REST or UI test
 * assertion changed: `every { repository.findById(…) } returns …` still fires, because the service
 * is a thin composition over exactly those repository calls.
 */
fun pipelineServiceOver(
    pipelines: co.datapipelines.pipeline.PipelineRepository,
    validator: co.datapipelines.pipeline.PipelineValidator = io.mockk.mockk(),
    authoring: co.datapipelines.pipeline.AuthoringGuard = co.datapipelines.pipeline.AuthoringGuard(true),
    templateVersions: co.datapipelines.pipeline.TemplateVersionStatuses =
        co.datapipelines.pipeline.TemplateVersionStatuses { _, _, _ -> null },
): co.datapipelines.pipeline.PipelineService =
    co.datapipelines.pipeline.PipelineService(
        pipelines = pipelines,
        validator = validator,
        drafts = co.datapipelines.pipeline.PipelineDraftService(pipelines, authoring),
        releases = co.datapipelines.pipeline.PipelineReleaseService(pipelines, templateVersions, validator, authoring),
        authoring = authoring,
    )
