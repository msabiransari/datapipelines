package co.datapipelines.application.mcp

import co.datapipelines.application.SharedPostgres
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * The audit-log read behind the 139 entry-point checks, against a real Postgres — the same
 * reasoning [co.datapipelines.application.endpoints.EndpointPersistenceIntegrationTest]
 * records: every property here is a statement *about Postgres* (a JSONB `->>` equality, a
 * `DISTINCT` over JSONB-extracted text, a `max("timestamp")` over `timestamptz` bound back as
 * an `Instant`). A mocked template would assert string-passing and keep passing if every one
 * of those statements were wrong.
 *
 * The 139 reading is also pinned here: a row WITHOUT the `table` / `template` detail key —
 * every row written before 139 — matches neither query, so a fresh key learns fresh.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpToolLearningsIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var learnings: McpToolLearnings

    private val key = "dpk_${UUID.randomUUID()}"

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        learnings = McpToolLearnings(jdbc)
    }

    @BeforeEach
    fun clean() {
        jdbc.jdbcTemplate.execute("TRUNCATE audit_log")
    }

    /** One `mcp.tool.called` row in the shape the dispatcher writes it (identifiers only). */
    private fun insertCall(
        keyId: String,
        tool: String,
        target: String,
        outcome: String = "success",
        table: String? = null,
        template: String? = null,
        at: Instant = Instant.parse("2026-09-15T10:00:00Z"),
    ) {
        val details =
            buildMap<String, Any?> {
                put("tool", tool)
                put("outcome", outcome)
                put("target", target)
                table?.let { put("table", it) }
                template?.let { put("template", it) }
            }
        val json =
            details.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\": ${if (v is String) "\"$v\"" else v}"
            }
        jdbc.jdbcTemplate.update(
            "INSERT INTO audit_log (timestamp, event, key_id, details_json) VALUES (?, 'mcp.tool.called', ?, ?::jsonb)",
            Timestamp.from(at),
            keyId,
            json,
        )
    }

    @Test
    fun `columnsRead returns exactly the tables this key read on that datasource`() {
        insertCall(key, "datasources_get_columns", "pg", table = "trips")
        insertCall(key, "datasources_get_columns", "pg", table = "zones")
        insertCall(key, "datasources_get_columns", "mysql", table = "trips")
        insertCall(key, "datasources_get_columns", "pg", table = "orders", outcome = "error")
        insertCall("dpk_other", "datasources_get_columns", "pg", table = "stations")

        learnings.columnsRead(key, "pg") shouldBe setOf("trips", "zones")
    }

    @Test
    fun `a row without the table detail does not count - pre-139 rows teach nothing`() {
        insertCall(key, "datasources_get_columns", "pg")

        withClue("rows written before 139 carry no `table`; a fresh key learns fresh") {
            learnings.columnsRead(key, "pg").shouldBeEmpty()
        }
    }

    @Test
    fun `a null or blank key learns nothing - fail-closed`() {
        insertCall(key, "datasources_get_columns", "pg", table = "trips")

        learnings.columnsRead(null, "pg").shouldBeEmpty()
        learnings.columnsRead("", "pg").shouldBeEmpty()
    }

    @Test
    fun `lastRenderAt is the latest successful render and failed renders do not count`() {
        insertCall(key, "templates_render", "test/t.sql", template = "test/t.sql", at = Instant.parse("2026-09-15T09:00:00Z"))
        insertCall(key, "templates_render", "test/t.sql", template = "test/t.sql", at = Instant.parse("2026-09-15T11:30:00Z"))
        insertCall(
            key,
            "templates_render",
            "test/t.sql",
            template = "test/t.sql",
            outcome = "error",
            at = Instant.parse("2026-09-15T12:00:00Z"),
        )
        insertCall(key, "templates_render", "test/other.sql", template = "test/other.sql", at = Instant.parse("2026-09-15T12:00:00Z"))

        learnings.lastRenderAt(key, "test/t.sql") shouldBe Instant.parse("2026-09-15T11:30:00Z")
    }

    @Test
    fun `lastRenderAt is null when this key never rendered the template`() {
        insertCall("dpk_other", "templates_render", "test/t.sql", template = "test/t.sql")

        learnings.lastRenderAt(key, "test/t.sql").shouldBeNull()
    }

    @Test
    fun `an evaluate row counts as the render (7b) — newer than the draft's update satisfies Check B`() {
        insertCall(
            key,
            "templates_evaluate",
            "test/xform.jsonata",
            template = "test/xform.jsonata",
            at = Instant.parse("2026-09-23T10:00:00Z"),
        )

        learnings.lastRenderAt(key, "test/xform.jsonata") shouldBe Instant.parse("2026-09-23T10:00:00Z")

        // And the render still wins when it is the newer of the two tools.
        insertCall(
            key,
            "templates_render",
            "test/xform.jsonata",
            template = "test/xform.jsonata",
            at = Instant.parse("2026-09-23T12:00:00Z"),
        )
        learnings.lastRenderAt(key, "test/xform.jsonata") shouldBe Instant.parse("2026-09-23T12:00:00Z")
    }
}
