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
