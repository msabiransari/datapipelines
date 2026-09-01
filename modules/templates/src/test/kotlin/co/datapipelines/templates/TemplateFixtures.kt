package co.datapipelines.templates

import co.datapipelines.typesystem.Dialect
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Builders and test doubles shared across this module's specs.
 *
 * Every builder defaults to a **valid** value so a test names only what it is about: a spec for
 * `duplicate_alias` sets two colliding aliases and nothing else, and its assertion therefore
 * proves the rule rather than the fixture.
 */
internal object TemplateFixtures {
    val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    fun draft(
        id: String? = "fetch_orders.sql",
        dialect: Dialect = Dialect.POSTGRES,
        body: String = "SELECT 1",
        imports: List<TemplateImport> = emptyList(),
        isLibrary: Boolean = false,
        engine: String = Template.FREEMARKER_ENGINE,
        schemaVersion: Int = Template.SUPPORTED_SCHEMA_VERSION,
    ): TemplateDraft =
        TemplateDraft(
            schemaVersion = schemaVersion,
            id = id,
            dialect = dialect,
            displayName = "Fetch Orders",
            description = "Pulls orders in a date range.",
            imports = imports,
            body = body,
            isLibrary = isLibrary,
            engine = engine,
        )

    fun version(
        id: String,
        version: Int = 1,
        dialect: Dialect = Dialect.POSTGRES,
        isLibrary: Boolean = false,
        imports: List<TemplateImport> = emptyList(),
        body: String = "SELECT 1",
    ): TemplateVersion =
        TemplateVersion(
            id = id,
            version = version,
            dialect = dialect,
            isLibrary = isLibrary,
            imports = imports,
            body = body,
            createdAt = Instant.EPOCH,
            createdBy = ACTOR,
        )

    /** Walks up from the working directory to locate a repo file, like `typesystem`'s fixture. */
    fun repoFile(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relativePath not found walking up from ${File("").absolutePath}")
    }

    /** As [repoFile], for a directory. */
    fun repoDirectory(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("$relativePath not found walking up from ${File("").absolutePath}")
    }
}

/**
 * The shipped Flyway migrations, in **version order** (numeric: V10 after V2), as
 * repo-relative paths ready for [TemplateFixtures.repoFile] — the exact derivation
 * `datasources`' `ShippedMigrations` established (035/H: this suite's hand-copied
 * V1–V4 list had already missed V5, which is the failure mode the derivation
 * exists to make impossible).
 *
 * Flyway and the scripts live in `app` alone (module-structure §3.1 rule 2), so
 * integration tests that need the shipped schema apply them through plain JDBC —
 * always through THIS derivation, never a hand-copied list: a migration added to
 * the directory but not to a hand-copied list runs that suite against a stale
 * schema while production Flyway applies the real one.
 *
 * ## Accepted grammar — everything else FAILS LOUD
 *
 * `V<version>__<description>.sql` with a version that fits an [Int]. Any OTHER
 * `.sql` file in the directory throws [IllegalArgumentException] NAMING THE FILE —
 * a silent `mapNotNull` exclusion would relocate the stale-schema drift inside the
 * guard. Widening the grammar is a deliberate change, not a side effect of adding
 * a file.
 */
internal object ShippedMigrations {
    private const val DIR = "modules/app/src/main/resources/db/migration"
    private val VERSION_PREFIX = Regex("""^V(\d+)__.*\.sql$""")

    /** The real directory's migrations as repo-relative paths, version order. */
    fun paths(): List<String> = migrations(TemplateFixtures.repoDirectory(DIR)).map { "$DIR/${it.second.name}" }

    /** The pure rule over any directory — injectable so the ordering itself is testable. */
    fun migrations(dir: File): List<Pair<Int, File>> =
        dir
            .listFiles { f -> f.isFile && f.name.endsWith(".sql") }
            .orEmpty()
            .sortedBy { it.name }
            .map { file -> versionOf(file) to file }
            .sortedBy { (version, _) -> version }

    /** The parsed version of one `.sql` file; anything else fails loud, naming the file. */
    private fun versionOf(file: File): Int {
        val match =
            requireNotNull(VERSION_PREFIX.find(file.name)) {
                "'${file.name}' does not match the accepted migration grammar V<version>__<description>.sql — the " +
                    "suites would silently run a stale schema while production Flyway applies it. Either " +
                    "rename it to the grammar or widen ShippedMigrations deliberately, applying suites included."
            }
        return requireNotNull(match.groupValues[1].toIntOrNull()) {
            "'${file.name}' carries a version number that does not fit an Int — widen ShippedMigrations deliberately."
        }
    }
}

/**
 * A [TemplateRegistry] over a fixed `{key → version}` map.
 *
 * Lets the engine, validator and dry-renderer be tested with a hand-built import graph — the
 * point of [TemplateRegistry] being an interface. Registering a raw [TemplateVersion] (rather
 * than going through validation) is exactly what the SSTI render-layer tests need: a body that
 * would never pass save-time validation, put in front of the render engine to prove the §4.3
 * configuration blocks it independently.
 */
internal class InMemoryTemplateRegistry(
    versions: List<TemplateVersion> = emptyList(),
) : TemplateRegistry {
    private val byKey = versions.associateBy { it.key }.toMutableMap()
    private val ids = versions.map { it.id }.toMutableSet()

    fun put(version: TemplateVersion): InMemoryTemplateRegistry {
        byKey[version.key] = version
        ids += version.id
        return this
    }

    override fun lookup(
        id: String,
        version: Int,
    ): TemplateVersion? = byKey["$id@$version"]

    override fun existsId(id: String): Boolean = id in ids
}

/**
 * A registry that parks every lookup on [gate] — the deterministic way to occupy render workers.
 *
 * [TemplateEngine] resolves a template through the loader, which calls [lookup], **on the worker
 * thread**. Blocking there pins exactly as many workers as the pool has, with no CPU burned and
 * no timing assumptions, which is what lets the pool-rejection test fill the queue and assert the
 * refusal deterministically instead of racing a runaway loop.
 */
internal class BlockingRegistry(
    private val gate: java.util.concurrent.CountDownLatch,
    private vararg val versions: TemplateVersion,
) : TemplateRegistry {
    override fun lookup(
        id: String,
        version: Int,
    ): TemplateVersion? {
        gate.await()
        return versions.firstOrNull { it.key == "$id@$version" }
    }

    override fun existsId(id: String): Boolean = versions.any { it.id == id }
}
