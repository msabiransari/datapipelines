package co.datapipelines.pipeline

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * The two pre-deploy scans of deployment §8.3 step 4 (#194, #268), run VERBATIM — the SQL is read
 * out of `docs/deployment.md` by its tag line, never copied here — against the shipped schema.
 *
 * An operator pastes these into a production metadata DB before an upgrade; a query that does not
 * run, or runs and finds the wrong rows, is a runbook that lies. So each scan is held to the rows
 * it promises over a fixture that has one of every case — a hit, a near miss, and a body the
 * query must survive (no `parameters` object at all).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreDeployScanQueriesTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
    }

    @BeforeEach
    fun setUp() {
        jdbc.jdbcTemplate.execute("TRUNCATE pipelines, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name) VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default')",
        )
        val owner =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO users (email, display_name, provider, provider_subject)" +
                        " VALUES ('o@x.test', 'O', 'google', 's') RETURNING id",
                    emptyMap<String, Any>(),
                    UUID::class.java,
                ),
            )
        store(owner, "test/stored_params", PARAMETERS)
        store(owner, "test/no_params", null)
    }

    @Test
    fun `the #194 scan lists exactly the stored decimal defaults with more places than their scale`() {
        // #298: a stored `scale` that is not an integer (`"2.0"`, `"x"`) is LISTED — a malformed
        // declaration is exactly what the operator runs the scan to find — where the unguarded
        // `::int` cast stopped the whole scan with an error.
        parametersFound(scan(OVER_SCALE_TAG)) shouldContainExactly listOf("amount", "bad_scale", "odd_scale")
    }

    @Test
    fun `the #268 scan lists exactly the stored parameters declaring constraints or a cardinality`() {
        parametersFound(scan(DECLARATION_TAG)) shouldContainExactly listOf("limit", "regions")
    }

    /** The fenced SQL block of deployment §8.3 whose first line is [tag] — exactly one. */
    private fun scan(tag: String): String {
        val blocks =
            SQL_BLOCK
                .findAll(Fixtures.repoFile(DEPLOYMENT_DOC).readText())
                .map { it.groupValues[1].trimIndent() }
                .filter { it.lines().first().trim() == tag }
                .toList()
        withClue("deployment §8.3 must carry exactly one block tagged '$tag'") { blocks.size shouldBe 1 }
        return blocks.single()
    }

    /** The scan's `parameter` column — every row must be the fixture pipeline's; the body with no parameters yields none. */
    private fun parametersFound(sql: String): List<String> {
        // A plain Statement, as psql runs it: jsonb's `?` operator is a bind marker to a PreparedStatement.
        val rows = jdbc.jdbcTemplate.query(sql) { rs, _ -> rs.getString("pipeline") to rs.getString("parameter") }
        rows.map { it.first }.distinct().forEach { it shouldBe "test/stored_params" }
        return rows.map { it.second }
    }

    private fun store(
        owner: UUID,
        name: String,
        parameters: String?,
    ) {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version)" +
                " VALUES (:id, :name, :name, '', :owner, 'defa0000-0000-0000-0000-000000000001', 1)",
            mapOf("id" to id, "name" to name, "owner" to owner),
        )
        val body = """{"schema_version": 1, "name": "p"${parameters?.let { ", \"parameters\": $it" }.orEmpty()}, "nodes": []}"""
        jdbc.update(
            "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)" +
                " VALUES (:id, 1, CAST(:body AS jsonb), 'h', 'RELEASED', :owner, :owner, NOW())",
            mapOf("id" to id, "body" to body, "owner" to owner),
        )
    }

    private companion object {
        const val DEPLOYMENT_DOC = "docs/deployment.md"
        const val OVER_SCALE_TAG = "-- pre-deploy #194: stored DECIMAL/BIGDECIMAL defaults with more places than their scale"
        const val DECLARATION_TAG = "-- pre-deploy #268: stored parameters declaring constraints or a cardinality"
        val SQL_BLOCK = Regex("```sql\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

        /**
         * One of every case: `amount` over its scale (a hit), `fee` at its scale with a trailing
         * zero and `rate` with no scale (misses), `whole` an integral default; `odd_scale` and
         * `bad_scale` with a scale that is not an integer (hits, #298 — listed, never a cast
         * error); `limit` with a constraints block and `regions` with a cardinality (hits),
         * `plain` with neither.
         */
        const val PARAMETERS =
            """{"amount": {"type": "DECIMAL", "scale": 2, "default": 12.345},
                "fee": {"type": "DECIMAL", "scale": 2, "default": 1.50},
                "rate": {"type": "DECIMAL", "default": 0.125},
                "whole": {"type": "BIGDECIMAL", "scale": 0, "default": "100"},
                "odd_scale": {"type": "DECIMAL", "scale": "2.0", "default": 1.5},
                "bad_scale": {"type": "BIGDECIMAL", "scale": "x", "default": "1"},
                "limit": {"type": "INTEGER", "constraints": {"min": 10, "max": 1}},
                "regions": {"type": "STRING", "cardinality": "MULTI"},
                "plain": {"type": "STRING"}}"""
    }
}
