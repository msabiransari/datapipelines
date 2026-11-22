package co.datapipelines.integration

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID

/**
 * The §7/§9 downgrade rule of published-mutation-apis (#1037): a binary that predates V65 must either refuse to start
 * on method-bearing metadata or honor none of it. A shipped jar cannot be changed, so the refusal lives in the schema's
 * history — the repeatable `R__published_endpoint_method_downgrade_barrier.sql` — and the way back is the documented
 * rollback block of metadata-db.md section 7.4. This class pins the mechanism with the REAL Flyway (the same engine and
 * the same validation the previous binary runs at boot), against scratch databases:
 *
 * - the CONTROL: without the barrier a V65 database is accepted by a resolver that knows only V1 through V64 (Flyway's
 *   default ignores a future versioned migration) — the measured defect, kept in the suite so the barrier's reason
 *   cannot silently disappear;
 * - the barrier: the same V65 database, migrated by the shipped scripts, is REFUSED by that resolver, naming the
 *   barrier migration;
 * - the rollback: the block the docs print, extracted from the doc and executed verbatim, removes every non-GET route
 *   and grant, keeps every GET one, restores the pre-V65 shape, and the previous resolver then validates;
 * - the round trip: the current scripts migrate that database forward again (V65 and the barrier re-apply).
 *
 * Flyway is driven by reflection because it is a runtime-only dependency of the app module; adding it to this module's
 * compile classpath would move the committed lock file for no product reason.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PublishedMethodDowngradeBarrierIntegrationTest {
    private val actor: UUID = UUID.randomUUID()
    private val workspace: UUID = UUID.randomUUID()
    private val pipeline: UUID = UUID.randomUUID()
    private val getRoute: UUID = UUID.randomUUID()
    private val postRoute: UUID = UUID.randomUUID()

    private val allShipped = repoFile("modules/app/src/main/resources/db/migration")

    // This class pins V65's boundary: its "shipped" scripts end at V65 and its barrier is V65's alone, so the
    // history it asserts stays the V65 one. V66 and its own barrier have their own class (#1035).
    private val shipped: File = copyOf(allShipped) { it.version <= FIRST_METHOD_VERSION }
    private val barrier: File = barrierCopy()
    private val previousBinary: File = copyOf(allShipped) { it.version < FIRST_METHOD_VERSION }
    private val withoutBarrier: File = shipped
    private val db = SharedE2e.scratchDatabase("v65_barrier")
    private val control = SharedE2e.scratchDatabase("v65_no_barrier")

    @Test
    @Order(1)
    fun `a V65 database without the barrier is accepted by a resolver that predates V65`() {
        migrate(control, withoutBarrier)

        // The measured defect: Flyway ignores a FUTURE versioned migration by default, so the previous binary starts.
        validate(control, previousBinary) shouldBe null
    }

    @Test
    @Order(2)
    fun `the shipped scripts migrate to V65 and record the barrier as a repeatable`() {
        migrate(db, shipped, barrier)

        history() shouldContainExactly
            listOf("63|lake maintenance plan", "64|lake storage inventory", "65|published endpoint methods") +
            "R|published endpoint method downgrade barrier"
    }

    @Test
    @Order(3)
    fun `a resolver that predates the barrier refuses the migrated database and names the barrier`() {
        seedRoutesAndGrants()

        val refusal = requireNotNull(validate(db, previousBinary)) { "the previous resolver accepted a barrier-bearing database" }

        refusal shouldContain "Detected applied migration not resolved locally"
        refusal shouldContain "published endpoint method downgrade barrier"
    }

    @Test
    @Order(4)
    fun `the documented rollback removes every non-GET route and grant and restores the previous shape`() {
        execute(documentedRollback())

        routes() shouldContainExactly listOf("/barrier/v1/x")
        grants() shouldContainExactly listOf("/barrier/v1")
        columnsOf("published_endpoints").contains("method") shouldBe false
        columnsOf("endpoint_key_bindings").let { (it.contains("method") || it.contains("endpoint_id")) shouldBe false }
        history() shouldContainExactly listOf("63|lake maintenance plan", "64|lake storage inventory")
    }

    @Test
    @Order(5)
    fun `after the rollback the resolver that predates V65 validates the database`() {
        validate(db, previousBinary) shouldBe null
    }

    @Test
    @Order(6)
    fun `the current scripts migrate the rolled-back database forward again`() {
        migrate(db, shipped, barrier)

        history() shouldContainExactly
            listOf("63|lake maintenance plan", "64|lake storage inventory", "65|published endpoint methods") +
            "R|published endpoint method downgrade barrier"
        columnsOf("published_endpoints").contains("method") shouldBe true
        routes() shouldContainExactly listOf("/barrier/v1/x")
        validate(db, shipped, barrier) shouldBe null
    }

    // ---------------------------------------------------------------- fixtures

    private fun seedRoutesAndGrants() {
        execute(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_admin)
            VALUES ('$actor', 'barrier@test.invalid', 'Barrier', 'test', 'barrier-sub', FALSE);
            INSERT INTO workspaces (id, name, display_name, created_by) VALUES ('$workspace', 'barrier-ws', 'Barrier', '$actor');
            INSERT INTO pipelines (id, name, display_name, owner_id, workspace_id)
            VALUES ('$pipeline', 'test/barrier', 'Barrier', '$actor', '$workspace');
            INSERT INTO published_endpoints (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, created_by, method) VALUES
                ('$getRoute', '$workspace', '/barrier/v1/x', '$pipeline', 60, '$actor', 'GET'),
                ('$postRoute', '$workspace', '/barrier/v1/x', '$pipeline', 60, '$actor', 'POST');
            INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)
            VALUES ('dpk_barrier', '$actor', '$actor', 'barrier', 'barrier-hash', '$workspace', 'endpoint', 'api_caller');
            INSERT INTO endpoint_key_bindings (path_prefix, api_key_id, workspace_id, created_by, method, endpoint_id) VALUES
                ('/barrier/v1', 'dpk_barrier', '$workspace', '$actor', 'GET', NULL),
                ('/barrier/v1/x', 'dpk_barrier', '$workspace', '$actor', 'POST', '$postRoute');
            """.trimIndent(),
        )
        routes().size shouldBe 2
        grants().size shouldBe 2
    }

    /** The one rollback text: the fenced block between the doc's two markers, executed exactly as printed. */
    private fun documentedRollback(): String {
        val doc = repoFile("docs/metadata-db.md").readText()
        val block = doc.substringAfter(ROLLBACK_START).substringBefore(ROLLBACK_END)
        block.contains("BEGIN;") shouldBe true
        return block.lineSequence().filterNot { it.trim().startsWith("```") }.joinToString("\n")
    }

    // ---------------------------------------------------------------- Flyway, by reflection

    /** Applies the migrations under [locations] to [target]; a Flyway failure is thrown as an IllegalStateException. */
    private fun migrate(
        target: SharedE2e.SharedPostgresRef,
        vararg locations: File,
    ) {
        call(flyway(target, locations.toList()), "migrate")
    }

    /** Flyway's own validation of [target] against [locations]: null when it passes, else the refusal's message. */
    private fun validate(
        target: SharedE2e.SharedPostgresRef,
        vararg locations: File,
    ): String? =
        try {
            call(flyway(target, locations.toList()), "validate")
            null
        } catch (refusal: IllegalStateException) {
            refusal.message
        }

    private fun flyway(
        target: SharedE2e.SharedPostgresRef,
        locations: List<File>,
    ): Any {
        val flywayClass = Class.forName("org.flywaydb.core.Flyway")
        val configuration = flywayClass.getMethod("configure").invoke(null)
        val type = configuration.javaClass
        type
            .getMethod("dataSource", String::class.java, String::class.java, String::class.java)
            .invoke(configuration, target.jdbcUrl, target.username, target.password)
        type
            .getMethod("locations", Array<String>::class.java)
            .invoke(configuration, locations.map { "filesystem:${it.absolutePath}" }.toTypedArray())
        return type.getMethod("load").invoke(configuration)
    }

    /** Invokes a no-argument Flyway command; Flyway's exception is rethrown as an IllegalStateException carrying its text. */
    private fun call(
        flyway: Any,
        command: String,
    ) {
        try {
            flyway.javaClass.getMethod(command).invoke(flyway)
        } catch (failure: InvocationTargetException) {
            throw IllegalStateException(failure.targetException.message, failure)
        }
    }

    // ---------------------------------------------------------------- JDBC

    private fun execute(sql: String) {
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun query(
        sql: String,
        target: SharedE2e.SharedPostgresRef = db,
    ): List<String> =
        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
        }

    /** The last rows of the history, newest last — the versions that decide what a binary may start on. */
    private fun history(): List<String> =
        query(
            "SELECT COALESCE(version, 'R') || '|' || description FROM flyway_schema_history " +
                "WHERE version IS NULL OR version::int >= 63 ORDER BY installed_rank",
        )

    private fun routes(): List<String> = query("SELECT path_pattern FROM published_endpoints WHERE workspace_id = '$workspace' ORDER BY 1")

    private fun grants(): List<String> = query("SELECT path_prefix FROM endpoint_key_bindings WHERE workspace_id = '$workspace' ORDER BY 1")

    private fun columnsOf(table: String): List<String> =
        query("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '$table'")

    // ---------------------------------------------------------------- the shipped scripts

    private data class Script(
        val file: File,
        val version: Int,
    )

    /** A scratch directory holding the shipped versioned scripts [keep] admits — never the repeatable ones. */
    private fun copyOf(
        source: File,
        keep: (Script) -> Boolean,
    ): File {
        val target = Files.createTempDirectory("dp-v65-barrier-").toFile().also { it.deleteOnExit() }
        source
            .listFiles()
            .orEmpty()
            .mapNotNull { file -> VERSIONED.matchEntire(file.name)?.let { Script(file, it.groupValues[1].toInt()) } }
            .filter(keep)
            .forEach { it.file.copyTo(File(target, it.file.name)) }
        return target
    }

    /** A scratch directory holding only V65's repeatable barrier — a later version's barrier is that version's class's. */
    private fun barrierCopy(): File {
        val target = Files.createTempDirectory("dp-v65-barrier-only-").toFile().also { it.deleteOnExit() }
        val source = File(repoFile("modules/app/src/main/resources/db/downgrade-barrier"), V65_BARRIER)
        source.copyTo(File(target, V65_BARRIER))
        return target
    }

    companion object {
        private const val V65_BARRIER = "R__published_endpoint_method_downgrade_barrier.sql"
        private const val FIRST_METHOD_VERSION = 65
        private const val ROLLBACK_START = "<!-- v65-rollback:start -->"
        private const val ROLLBACK_END = "<!-- v65-rollback:end -->"
        private val VERSIONED = Regex("""V(\d+)__.*\.sql""")

        fun repoFile(relativePath: String): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val candidate = File(dir, relativePath)
                if (candidate.exists()) return candidate
                dir = dir.parentFile
            }
            error("$relativePath not found walking up from ${File("").absolutePath}")
        }
    }
}
