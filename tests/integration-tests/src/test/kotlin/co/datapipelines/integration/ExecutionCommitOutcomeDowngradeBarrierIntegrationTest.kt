package co.datapipelines.integration

import co.datapipelines.integration.PublishedMethodDowngradeBarrierIntegrationTest.Companion.repoFile
import io.kotest.assertions.throwables.shouldThrow
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
import java.sql.SQLException
import java.util.UUID

/**
 * The V66 boundary of pipeline-write-transactions (#1035): a binary that predates V66 cannot read `OUTCOME_UNKNOWN`, and its
 * stale sweep would label an attempted commit `ABORTED`. A shipped jar cannot be changed, so the refusal lives in the schema's
 * history — the repeatable `R__execution_commit_outcome_downgrade_barrier.sql` — and the way back is the documented
 * rollback block of metadata-db.md section 7.5, extracted from the doc and executed verbatim against the REAL Flyway
 * (driven by reflection: it is a runtime-only dependency of the app module).
 *
 * Observed here, in order: the shipped scripts record V66 and the barrier; a resolver that knows V65 and its barrier (the
 * previous binary) REFUSES the database and names the new barrier; the documented rollback REFUSES while an
 * `OUTCOME_UNKNOWN` row exists and changes nothing; once the unknown outcome is resolved the block runs, removes the four
 * columns and narrows the CHECK, and the previous resolver validates; the current scripts then migrate the database
 * forward again.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ExecutionCommitOutcomeDowngradeBarrierIntegrationTest {
    private val actor: UUID = UUID.randomUUID()
    private val workspace: UUID = UUID.randomUUID()
    private val pipeline: UUID = UUID.randomUUID()
    private val confirmed: UUID = UUID.randomUUID()
    private val unknown: UUID = UUID.randomUUID()

    private val allShipped = repoFile("modules/app/src/main/resources/db/migration")
    private val allBarriers = repoFile("modules/app/src/main/resources/db/downgrade-barrier")

    // Pin the V66-to-V65 boundary; the newer-schema refusal below separately exercises all current migrations.
    private val shipped: File = scratch("dp-v66-current-") { copyVersioned(it) { version -> version <= COMMIT_OUTCOME_VERSION } }
    private val shippedBarriers: File =
        scratch("dp-v66-barriers-") { target ->
            listOf(V65_BARRIER, V66_BARRIER).forEach { File(allBarriers, it).copyTo(File(target, it)) }
        }
    private val previousBinary: File = scratch("dp-v66-previous-") { copyVersioned(it) { version -> version <= LAST_PREVIOUS_VERSION } }
    private val previousBarrier: File = scratch("dp-v66-previous-barrier-") { File(allBarriers, V65_BARRIER).copyTo(File(it, V65_BARRIER)) }
    private val db = SharedE2e.scratchDatabase("v66_barrier")

    @Test
    @Order(1)
    fun `the shipped scripts record V66 and the commit outcome barrier as a repeatable`() {
        migrate(db, shipped, shippedBarriers)

        history() shouldContainExactly listOf("66|execution commit outcomes", "R|execution commit outcome downgrade barrier")
    }

    @Test
    @Order(2)
    fun `a resolver that predates the barrier refuses the migrated database and names the barrier`() {
        val refusal = requireNotNull(validate(db, previousBinary, previousBarrier)) { "the previous resolver accepted a V66 database" }

        refusal shouldContain "Detected applied migration not resolved locally"
        refusal shouldContain "execution commit outcome downgrade barrier"
    }

    @Test
    @Order(3)
    fun `the documented rollback refuses while an unknown outcome exists and changes nothing`() {
        seedExecutions()

        val refusal = shouldThrow<SQLException> { execute(documentedRollback()) }

        refusal.message!! shouldContain "V66 rollback refused"
        columnsOf("pipeline_executions").containsAll(V66_COLUMNS) shouldBe true
        query("SELECT status FROM pipeline_executions WHERE execution_id = '$unknown'") shouldContainExactly listOf("OUTCOME_UNKNOWN")
        history() shouldContainExactly listOf("66|execution commit outcomes", "R|execution commit outcome downgrade barrier")
    }

    @Test
    @Order(4)
    fun `once the unknown outcome is resolved the rollback removes the V66 shape and its history`() {
        // The operator's step: check the target, export the row, remove it under their own change control.
        execute("DELETE FROM pipeline_executions WHERE execution_id = '$unknown'")

        execute(documentedRollback())

        columnsOf("pipeline_executions").any { it in V66_COLUMNS } shouldBe false
        query("SELECT status FROM pipeline_executions WHERE execution_id = '$confirmed'") shouldContainExactly listOf("SUCCESS")
        shouldThrow<SQLException> {
            execute(
                "INSERT INTO pipeline_executions (pipeline_id, pipeline_version, status, executed_by, triggered_via, root_execution_id) " +
                    "VALUES ('$pipeline', 1, 'OUTCOME_UNKNOWN', '$actor', 'REST', gen_random_uuid())",
            )
        }.message!! shouldContain "chk_status"
        history() shouldContainExactly emptyList()
    }

    @Test
    @Order(5)
    fun `after the rollback the resolver that predates V66 validates the database`() {
        validate(db, previousBinary, previousBarrier) shouldBe null
    }

    @Test
    @Order(6)
    fun `the current scripts migrate the rolled-back database forward again`() {
        migrate(db, shipped, shippedBarriers)

        history() shouldContainExactly listOf("66|execution commit outcomes", "R|execution commit outcome downgrade barrier")
        columnsOf("pipeline_executions").containsAll(V66_COLUMNS) shouldBe true
        query(
            "SELECT settlement_pending::TEXT || result_unavailable::TEXT FROM pipeline_executions WHERE execution_id = '$confirmed'",
        ) shouldContainExactly
            listOf("falsefalse")
        validate(db, shipped, shippedBarriers) shouldBe null
    }

    @Test
    @Order(7)
    fun `the V66 rollback refuses later migrations without changing history or columns`() {
        val newer = SharedE2e.scratchDatabase("v66_newer")
        migrate(newer, allShipped, allBarriers)
        val historySql = "SELECT COALESCE(version, 'R') || '|' || script FROM flyway_schema_history ORDER BY installed_rank"
        val originalHistory = query(historySql, newer)
        query(
            "SELECT (MAX(version::INTEGER) > $COMMIT_OUTCOME_VERSION)::TEXT FROM flyway_schema_history WHERE version IS NOT NULL",
            newer,
        ) shouldContainExactly listOf("true")

        val refusal = shouldThrow<SQLException> { execute(documentedRollback(), newer) }

        requireNotNull(refusal.message) shouldContain "V66 rollback refused: later migrations"
        query(historySql, newer) shouldContainExactly originalHistory
        columnsOf("pipeline_executions", newer).containsAll(V66_COLUMNS) shouldBe true
        validate(newer, allShipped, allBarriers) shouldBe null
    }

    // ---------------------------------------------------------------- fixtures

    private fun seedExecutions() {
        execute(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_admin)
            VALUES ('$actor', 'v66barrier@test.invalid', 'V66', 'test', 'v66-sub', FALSE);
            INSERT INTO workspaces (id, name, display_name, created_by) VALUES ('$workspace', 'v66-ws', 'V66', '$actor');
            INSERT INTO pipelines (id, name, display_name, owner_id, workspace_id)
            VALUES ('$pipeline', 'test/v66', 'V66', '$actor', '$workspace');
            INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)
            VALUES ('$pipeline', 1, '{}'::jsonb, 'v66-hash', 'RELEASED', '$actor', '$actor', NOW());
            INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status, executed_by, triggered_via,
                                             root_execution_id, commit_attempted_at, commit_confirmed_at, completed_at) VALUES
                ('$confirmed', '$pipeline', 1, 'SUCCESS', '$actor', 'REST', '$confirmed', NOW(), NOW(), NOW()),
                ('$unknown', '$pipeline', 1, 'OUTCOME_UNKNOWN', '$actor', 'REST', '$unknown', NOW(), NULL, NOW());
            """.trimIndent(),
        )
    }

    /** The one rollback text: the fenced block between the doc's two markers, executed exactly as printed. */
    private fun documentedRollback(): String {
        val doc = repoFile("docs/metadata-db.md").readText()
        val block = doc.substringAfter(ROLLBACK_START).substringBefore(ROLLBACK_END)
        block.contains("BEGIN;") shouldBe true
        return block.lineSequence().filterNot { it.trim().startsWith("```") }.joinToString("\n")
    }

    // ---------------------------------------------------------------- Flyway, by reflection

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

    private fun execute(
        sql: String,
        target: SharedE2e.SharedPostgresRef = db,
    ) {
        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { connection ->
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

    /** V66's own history rows, newest last: the versions and repeatables that decide what a binary may start on. */
    private fun history(): List<String> =
        query(
            "SELECT COALESCE(version, 'R') || '|' || description FROM flyway_schema_history " +
                "WHERE version = '66' OR description = 'execution commit outcome downgrade barrier' ORDER BY installed_rank",
        )

    private fun columnsOf(
        table: String,
        target: SharedE2e.SharedPostgresRef = db,
    ): List<String> =
        query("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '$table'", target)

    // ---------------------------------------------------------------- the shipped scripts

    private fun scratch(
        prefix: String,
        fill: (File) -> Unit,
    ): File =
        Files
            .createTempDirectory(prefix)
            .toFile()
            .also { it.deleteOnExit() }
            .also(fill)

    /** Copies the shipped versioned scripts [keep] admits into [target] — never the repeatable ones. */
    private fun copyVersioned(
        target: File,
        keep: (Int) -> Boolean,
    ) {
        allShipped
            .listFiles()
            .orEmpty()
            .mapNotNull { file -> VERSIONED.matchEntire(file.name)?.let { file to it.groupValues[1].toInt() } }
            .filter { keep(it.second) }
            .forEach { it.first.copyTo(File(target, it.first.name)) }
    }

    private companion object {
        const val LAST_PREVIOUS_VERSION = 65
        const val COMMIT_OUTCOME_VERSION = 66
        const val V65_BARRIER = "R__published_endpoint_method_downgrade_barrier.sql"
        const val V66_BARRIER = "R__execution_commit_outcome_downgrade_barrier.sql"
        const val ROLLBACK_START = "<!-- v66-rollback:start -->"
        const val ROLLBACK_END = "<!-- v66-rollback:end -->"
        val V66_COLUMNS = listOf("commit_attempted_at", "commit_confirmed_at", "settlement_pending", "result_unavailable")
        val VERSIONED = Regex("""V(\d+)__.*\.sql""")
    }
}
